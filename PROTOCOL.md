# Aquarius Road Department — Protocol (v1)

This document is the **frozen contract** every client and server implementation conforms to.
Its single most important property: **an off-highway coordinate is unrepresentable.** There is
no `(x, z)` field anywhere in the wire format — the only spatial data is a 1-D on-highway
coordinate. This holds even under full server-side logging: nothing beyond what's already
visible by walking the public road is ever exposed.

This document covers the parts a third-party producer or consumer needs to interoperate: the
wire format, the client-side gate, and the public read API. The server's internal trust
enforcement, corroboration thresholds, and reputation logic aren't published here — see
[Contributing / getting write access](#8-contributing--getting-write-access) below.

## 1. The narrow waist

The nether highways form a 1-dimensional manifold (rays + the near-spawn grid). A point *on* a
road is fully described by three integers:

| field  | meaning |
|--------|---------|
| `road` | index into the authoritative road table for the given `map` version |
| `seg`  | segment index within that road's polyline |
| `along`| quantized distance along that segment (`floor(distFromSegStart / BUCKET)`) |

Real `(x, z)` is **derived** from `(road, seg, along)` on the consumer side. The perpendicular
offset (how far off-road you were, and in which direction) is the *only* value that could
triangulate a base — the client computes it solely for the tolerance gate and then **discards
it before constructing any report object**. It is never transmitted, bucketed, or logged.

## 2. Constants (v1 defaults)

| name | value | note |
|------|-------|------|
| `ROAD_Y` | `120` | highways are **always** y120; the client gate is strict |
| `BUCKET` | `100` | blocks per `along` bucket |
| `TOLERANCE` | `3.0` | max off-road XZ distance to count as on-road (matches ElytraPilot's pass tolerance) |
| `NEAR_SPAWN_RADIUS` | `100000` | `max(\|x\|,\|z\|) <= this` → full grid; beyond → axis-only |
| `TTL` | `10800` (3h) | condition decay window — a sparsely-traveled road shouldn't expire before someone else happens to pass through and refresh it |
| `LINK_CODE_TTL` | `600` (10 min) | seconds an unclaimed account-link code stays valid |
| `MAX_LINKED_UIDS` | `8` | max Minecraft UIDs one Discord identity may link (multiboxer allowance) |

## 3. Road-set policy (spatially tiered)

A point may be reported only if its **on-road** point is on an *allowed* road:

- **Within 100k of spawn** (`max(|x|,|z|) <= 100000`): all categories — `axis`, `ring`,
  `diamond`, `grid`. The near-spawn grid (out to and including the 100k ring road) is
  contested public infrastructure.
- **Beyond 100k**: only `category == "axis"` (cardinals + diagonals), all the way to the
  world border. Keeps the manifold thin where real bases live — a ring/diamond/grid road
  that far out is close enough to base territory that automatic corroboration there isn't
  worth the exposure.

`planned` roads are never reportable. The policy is evaluated on the on-road point so it is
identical on the client and the server.

## 4. Geometry versioning

`map` is `sha256:<16 hex>` over the ordered road table's geometry (`category`, `radius`,
`segments`) — see `geometry.map_hash`. Clients snap against a specific `map` and send it; the
server rejects reports whose `map` doesn't match its authoritative geometry for that server.
The server also maps each `road` index to a **canonical, geometry-derived key**
(`geometry.canonical_road_key`) so aggregates survive a road-table reorder/rehash.

## 5. Report (client → `POST /report`)

```jsonc
{ "v": 1, "server": "2b2t.org", "map": "sha256:….", "road": 12, "seg": 3,
  "along": 417, "cond": "HOLE", "sev": 2, "ts": 1720624800 }
```

Strict schema: `schema/report.schema.json`. **Unknown fields are rejected** (this is what makes
a smuggled `"x"`/`"z"` impossible). `cond ∈ {CLEAR, HOLE, LAVA, OBSTRUCTION_FULL,
OBSTRUCTION_PARTIAL, COBWEB, WATER, GRAVEL, UNBUILT, PRESENCE}`. `ts` is rounded to 30s by the
client (k-anonymity / timing jitter). Never present: raw `x`/`z`, perpendicular offset,
distance, yaw/pitch/velocity, entity UUIDs, contributor identity. A `POST /report` body may be
one object or an array (batching).

`laneMin` / `laneMax` (signed integers, bounded to roughly the road's own width) are present
**only** when `cond == OBSTRUCTION_PARTIAL` — see §5.1. They describe a span *within* the
already-known road width, not a world coordinate, so they don't reopen the narrow waist.

### Client algorithm (identical across `plugin-aquarius` and `client-fabric`)

```
if dimension != NETHER: skip
if max(|x|,|z|) > maxReportRadius: skip           # per-contributor privacy cap
snap = nearest_allowed(x, z)                       # region-tiered; None if off-road/disallowed
if snap is None or snap.distance > TOLERANCE: skip # OFF-ROAD → no report object built
if roadPlaneY != 120: skip                         # strict; off-y may go to /moderation instead
# perpendicular offset is DISCARDED here
report = { road, seg = snap.seg, along = floor(distFromSegStart/BUCKET),
           cond = sampleAround(x,y,z), ts = roundTo30s(now) }
```

Reference implementation: `protocol/geometry.py` + `protocol/reference_client.py` (the canonical
Python client gate — Java/JS ports mirror this exactly).

### 5.1 Obstruction detection: FULL vs PARTIAL, and why signs/item frames don't need a blacklist

Nether highways routinely have signs and item frames placed on them (waypoints, ads, decor)
all the way from spawn to the ends of every road. These have negligible collision — a bot
walking or elytra-bouncing through them normally never notices. **They must never be reported
as obstructions merely because they're present.** The rule: a sign/item frame only counts if
its placement actually delays the bot's forward progress by a sustained few seconds — purely
behavioral detection (a stall watchdog on forward progress, followed by a lane-blockage scan),
not a block/entity-type blacklist, so it works the same regardless of what's physically causing
the stall. See `plugin-aquarius`'s `ObstructionWatcher` for the reference implementation and its
own tuned constants.

- **All lanes blocked → `OBSTRUCTION_FULL`** (no `laneMin`/`laneMax` — the whole road is out).
- **Some lanes blocked → `OBSTRUCTION_PARTIAL`**, with `laneMin`/`laneMax` = the min/max
  blocked lane offset found. A routing consumer reads this as "aim outside this span."
- **No lanes blocked at all → not an obstruction.**

The server **unions** corroborating `laneMin`/`laneMax` observations for the same
`(road, seg, along, OBSTRUCTION_PARTIAL)` key (widens the confirmed span, never narrows it).

## 6. Trust tiers (overview)

Trust is a property of the **verified account**, never of the software that submitted the
report. `plugin-aquarius`, `plugin-zenith`, and `client-fabric` all go through the same identity
paths below — a proxy bot is not inherently more trusted than a player just because it has to be
logged into a real Microsoft account; a player linking their own account gets that same
guarantee.

- **Tier A (vouched):** holds a maintainer-issued credential. Publishes at high confidence
  without waiting for corroboration.
- **Tier M (maintainer):** a narrower grant for highway-maintenance groups who do the physical
  work of clearing obstructions.
- **Tier B (verified):** authenticated via a Discord identity with at least one linked,
  ownership-verified Minecraft UID (§6.1). Needs multiple independent identities to agree before
  publishing.
- **Tier C (anonymous):** no linked identity. IP-rate-limited, needs more independent sources to
  agree than Tier B, and is the slowest tier to publish — the fallback for someone who doesn't
  want to link an identity, not the default path.

Exactly how many independent sources are required, how corroboration is weighted, and how the
service protects itself against coordinated false reports are internal to the deployed service
and aren't published as part of this contract. If you're building a producer/consumer against
this protocol, none of that detail changes what you send or receive on the wire.

`PRESENCE` (camper reports) is opt-in, default off, count-only, no identity, short TTL —
unaffected by the tiers above.

### 6.1 Account linking (`POST /link/init` + `POST /link/complete`)

Device-code style, like `gh auth login`. Either producer can initiate a link to reach Tier B:

1. The producer calls `POST /link/init { mcUid, server }` — unauthenticated, since no identity
   exists yet. The server generates a short, high-entropy code, records the pending link with a
   `LINK_CODE_TTL` expiry, and returns it.
2. The producer displays the code (chat/HUD for `client-fabric`; console/log for
   `plugin-aquarius`).
3. A human opens `website/link.html`, enters/confirms the code, and continues with Discord. The
   page builds the OAuth authorize URL itself from `GET /link/config` (client id + redirect URI +
   scope — never the client secret) and carries the pending code through the redirect via OAuth's
   `state` parameter.
4. Whatever completed the Discord login calls `POST /link/complete { linkCode, discordCode }`.
   The server resolves the Discord user id, links the Minecraft UID to that identity, and mints
   a Tier B bearer token. That token is what subsequent `/report` calls authenticate with.

An identity may link up to `MAX_LINKED_UIDS` Minecraft UIDs per server (covers legitimate
multiboxers) — all UIDs linked to the same Discord identity on the same server count as one
corroboration source.

A first-party Discord bot can complete step 4 on a user's behalf via `POST /link/bot-complete`
(bot-credential-authenticated, no OAuth round-trip needed since Discord's own interaction payload
already proves the caller's identity to the bot).

## 7. Consumption

| route | method | auth | purpose |
|-------|--------|------|---------|
| `/health` | GET | none | liveness only, no data |
| `/geometry/<server>` | GET | none (public, rate-limited) | authoritative road table + `map` + `BUCKET` |
| `/report` | POST | trust-gated — see §6 | ingest reports |
| `/link/init` | POST | none (rate-limited) | body `{mcUid, server}` — server mints a pending link code — §6.1 |
| `/link/complete` | POST | none (holds a Discord `discordCode` instead) | resolves a link code + Discord OAuth code into a Tier B token — §6.1 |
| `/link/bot-complete` | POST | first-party bot credential | resolves a link code + an already-Discord-verified `discordId` into a Tier B token — §6.1 |
| `/link/config` | GET | none (public, rate-limited) | Discord `clientId`/`redirectUri`/`authorizeUrl` for the website's link page — never the client secret |
| `/conditions/<server>` | GET | none (public, rate-limited) | published, non-expired conditions (`?road=&from=&to=`) |
| `/conditions/<server>/stream` | GET | none (public, rate-limited) | live SSE of updates |
| `/moderation` | POST | none (public, own rate limit) | submit an anomaly for review |
| `/` and other static paths | GET | none (public, rate-limited) | the map website itself, including `/admin/` — plain files, no templating |

Everything else — the moderation queue, identity suspend/reinstate, registry token management,
the admin dashboard's own login/session routes — is trust-gated internal tooling. It exists, it's
scoped per-server, and it's reachable through `website/admin/` if you hold the right role; the
exact mechanics aren't part of this public contract.

**Read policy: fully public, no token.** `/geometry`, `/conditions` (incl. `/stream`) and the
website's static files carry no auth check at all — this is the "Google-Maps-style consumption"
goal from the start, not a fallback. *Writes* stay trust-gated per §6; only the read side is
open. Since `/conditions` is a fully public route, put a CDN/reverse proxy in front of it in
production.

The human-facing map website is just another consumer of this table: it fetches `/geometry` once
to draw the road skeleton and `/conditions` (+ `/stream` as a live-update nudge) to plot hazard
markers, doing its own `(road,seg,along) → (x,z)` placement from data the server already
re-derives — no new wire concepts, no session, no login for viewing.

## 8. Contributing / getting write access

Want your bot/mod to submit reports at Tier A/M, or need a moderator role for a server you run?
Reach out to a maintainer — write access is provisioned out-of-band, not self-service.
