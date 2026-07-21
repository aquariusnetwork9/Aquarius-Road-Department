# Aquarius Road Department (ARD)

Crowdsourced, real-time **nether-highway conditions** (holes, lava, obsidian roadblocks,
cobwebs, unbuilt gaps, campers) for 2b2t and other anarchy servers — fed by AquariusProxy /
ZenithProxy plugins and a standalone Fabric client mod, gated behind a tiered trust model
(PROTOCOL.md §6), consumed through a public map (PROTOCOL.md §7): reads are open to anyone, no
login, no token — contribution stays trust-gated, consumption doesn't.

One repo, one frozen wire protocol, many independent producers/consumers — see
[Layout](#layout) below for how the pieces divide.

<p align="center"><em>Pure Python stdlib server. No pip, no Docker.</em></p>

## The one guarantee that matters

**It is impossible to use this network to harvest a player's off-highway coordinates.**

Not "we filter them out" — *unrepresentable*. The wire protocol has no `(x, z)` field. A point
on a highway is a 1-D coordinate — `(road, seg, along)` — and real `(x, z)` is re-derived on the
consumer side. The perpendicular offset (the only thing that could triangulate a base) is
destroyed on the client before a report object exists. This holds even under full server-side
logging: nothing beyond what's already visible by walking the public road is ever exposed.

See **[PROTOCOL.md](PROTOCOL.md)** for the frozen wire contract.

Two guarantees, kept honest and separate:

- **HARD (bulletproof):** no off-highway coordinate can ever enter the network, at any
  distance. Enforced redundantly at four layers: strict schema (rejects unknown fields) →
  server re-derivation → client on-road gate → aggregate-only storage.
- **SOFT (best-effort):** a contributor's own on-highway movements are hard to attribute —
  k-anonymity, TTL/decay, unlinkable reports, timing jitter, a per-contributor radius cap, and a
  spatially-tiered road set (full grid within 100k of spawn, axis-only beyond).

## Downloads

Each producer publishes its own independently-versioned jar (no single "project version" — see
[Layout](#layout)) as a [GitHub Release](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases),
never hand-uploaded — GitHub Actions is the only allowed builder of a released jar (see each
component's own CI workflow). Current releases are all marked **Pre-release**: they build and
pass CI, but haven't yet been run against a real server/client (goldfarm for the two proxy
plugins, an actual Minecraft session for the Fabric mod) — the project's own standing
verification order for exactly this reason. Treat them as early builds for testing/feedback.

| Component | Latest release | Runs against |
|---|---|---|
| [`plugin-aquarius`](plugin-aquarius/) | [plugin-aquarius-v0.1.1](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/plugin-aquarius-v0.1.1) | [AquariusProxy](https://github.com/aquariusnetwork9/AquariusProxy) |
| [`plugin-zenith`](plugin-zenith/) | [plugin-zenith-v0.1.1](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/plugin-zenith-v0.1.1) | stock [ZenithProxy](https://github.com/rfresh2/ZenithProxy) |
| [`client-fabric`](client-fabric/) | 5 parallel releases, one per MC version — see below | Minecraft, version-locked |

`client-fabric` is a real Fabric mod, so unlike the two proxy plugins it's pinned to a specific
Minecraft version rather than being MC-version-agnostic — every version this mod has actually
been ported to and rebuilt for gets its own release, so players pick the one matching their own
game version instead of hunting for "the" download:

| MC version | Release |
|---|---|
| 1.21.4 | [client-fabric-v0.1.0+1.21.4](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.1.0+1.21.4) |
| 1.21.5 | [client-fabric-v0.1.0+1.21.5](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.1.0+1.21.5) |
| 1.21.8 | [client-fabric-v0.1.0+1.21.8](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.1.0+1.21.8) |
| 1.21.10 | [client-fabric-v0.1.0+1.21.10](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.1.0+1.21.10) |
| 1.21.11 | [client-fabric-v0.1.0+1.21.11](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.1.0+1.21.11) |

`server/`, `website/`, and `discord-bot/` aren't distributed as downloadable artifacts — they're
deployed services (see each directory's own README), not something you install into a client.

## Layout

One repo, per-component independent build tooling, bound together only by the schema/protocol
below — every producer and consumer implements the contract itself rather than sharing code,
so no module has to trust another's correctness.

```
PROTOCOL.md                 the frozen wire contract — the one thing every module below must honor
schema/report.schema.json   strict report schema (spec)
schema/moderation.schema.json
geometry/*.nether_highways.json   one file per server (e.g. 2b2t.org, 6b6t.org) — each an
                                   independent authoritative road table (axis/ring/diamond/grid,
                                   y120); a deployment auto-discovers every file here, no code
                                   change needed to add another server

server/                     ingest/aggregation service + gated map (Python stdlib, no pip)
  geometry.py                 the narrow-waist math: snap, quantize, re-derive, region policy
  reference_client.py         canonical client gate (Java/JS ports mirror this)
  trust.py                    Owner-issued token registry — Tier A/M/moderator scopes (§6.3)
  identity.py                 Tier B account linking — Discord OAuth + Minecraft UID (§6.2)
  highway_conditions.py       ingest/aggregation service (strict validate, k-anon, decay, SSE)

plugin-aquarius/            AquariusProxy/ZenithProxy collector plugin (Java, own Gradle build)
client-fabric/              standalone Fabric client mod (own Loom build) — Phase 4, report producer
                             + hazard-ahead HUD shipped, CI-built, awaiting live-server verification
                             (works alongside Meteor/RusherHack/LambdaClient or none of them;
                             no coupling to any client's internal addon API — see its README)

website/                    the public map (Phase 3) — static files served by highway_conditions.py,
                             no build step, no login for viewing (PROTOCOL.md SS7: reads are public)
  index.html, style.css, app.js   Leaflet-based live map: road skeleton + hazard markers + SSE nudge
  link.html, link.js              Tier B account linking UI (PROTOCOL.md SS6.2) -- code entry +
                                   Discord OAuth redirect, driven by the public GET /link/config;
                                   also completes admin dashboard logins (SS6.6), sharing this one
                                   registered redirect URI
  admin/                          moderator/admin dashboard (PROTOCOL.md SS6.6) -- Discord login,
                                   no bearer token in the browser; moderation queue, registry
                                   tokens, dashboard grants, identity suspend/reinstate
  vendor/leaflet.{js,css}         vendored, no CDN

tools/hc_mock.py            mock contributor + off-road-leak audit
tests/                      unit + schema-fuzz + HTTP gating tests
```

## Run

```bash
# tests (stdlib unittest, no pip)
python -m unittest discover -s tests -t .

# the off-road-leak audit / demo (runs the real server in-process)
python tools/hc_mock.py

# the service (also serves the map website at http://127.0.0.1:8788/)
python server/highway_conditions.py --geometry ./geometry --port 8788 \
    --owner-token <OWNER_SECRET> --seed-token <FLEET_BOT_SECRET>:full:2b2t.org:fleet-bot-1
```

Writes are gated by the Owner-issued token registry (`trust.py`) — see the tier/scope table in
[PROTOCOL.md §6](PROTOCOL.md#6-trust-tiers-identity--aggregation). Reads are public — no token,
no bulk/scrape endpoint though (`/conditions` is per-server, no "dump everything" route) — see
the route table in [PROTOCOL.md §7](PROTOCOL.md#7-consumption). Put a CDN/reverse proxy in front
of the read routes in production; the app itself only defends the write side.

**Production (systemd) deployment shouldn't pass secrets as CLI flags** — anything on the command
line is visible to any other process on the box via `ps aux`, which matters on a shared VPS
running several other services. Set them as environment variables instead (e.g. in a systemd
`EnvironmentFile=`) and the `ExecStart=` line carries no secrets at all:

```
ARD_OWNER_TOKEN=<OWNER_SECRET>
ARD_SEED_TOKENS=<FLEET_BOT_SECRET>:full:2b2t.org:fleet-bot-1,<OTHER_SECRET>:maintainer:6b6t.org:highway-crew-1
ARD_DISCORD_CLIENT_SECRET=<DISCORD_CLIENT_SECRET>
ARD_DISCORD_ADMINS=<YOUR_DISCORD_USER_ID>:2b2t.org,<YOUR_DISCORD_USER_ID>:6b6t.org
ARD_BOT_SECRET=<HIGHWAY_BOT_SECRET>
```

`ARD_DISCORD_ADMINS` (or `--discord-admin`) is the bootstrap for the admin dashboard
(PROTOCOL.md §6.6, `website/admin/`) — it grants a Discord identity `admin` scope directly, no
bearer token involved, so the Owner can log into the dashboard with Discord instead of pasting
the raw Owner secret into a browser at all day-to-day. Idempotent across restarts like
`ARD_SEED_TOKENS`.

`ARD_BOT_SECRET` (or `--bot-token`) is a first-party credential for a trusted Discord bot process
(e.g. `discord-bot/`, "Highway Bot") — narrower than the Owner token, it unlocks only
`POST /link/bot-complete` (PROTOCOL.md §6.2.1), the bot's alternate account-linking path that
skips the OAuth round-trip since the bot already has a Discord-verified `discordId` from its own
gateway. Generate it the same way as the Owner token (`openssl rand -hex 32`) and place it in both
this env file and the bot's own `.env`/`EnvironmentFile=` — it's a shared secret between the two
processes, never anything a browser or end user sees.

Both paths are additive, not either/or — a CLI flag and its env-var equivalent can both be
present (useful for local runs); env values are appended to, never replacing, whatever the CLI
already supplied, and `ARD_DISCORD_CLIENT_SECRET` only fills in if `--discord-client-secret`
wasn't passed at all.

**`ARD_TRUSTED_PROXIES`** (or `--trusted-proxy`, repeatable) lists additional CIDRs/IPs allowed to
supply the request's real client address via `CF-Connecting-IP`/`X-Forwarded-For` — loopback is
always included by default, which is what a same-box reverse proxy/tunnel needs (the production
deployment's Cloudflare tunnel qualifies out of the box). Only set this if the reverse proxy runs
on a separate host from the ingest service.

## Status

- **Phase 0** (protocol + strict schema + geometry math) — done, tested. Includes behavioral
  FULL/PARTIAL obstruction detection (PROTOCOL.md §5.1) — a 3-second sustained-stall threshold
  filters decorative signs/item frames without any block-type blacklist.
- **Phase 1** (ingest/aggregation service + audit harness) — done, tested (160 tests total across
  the suite, `python -m unittest`). Condition decay (`TTL`) defaults to 3h, tripled from an
  original 1h —
  highway traffic density varies a lot across the network, and a sparsely-traveled road
  shouldn't expire before someone else happens to pass through and refresh it.
- **Phase 1.5** (Owner-issued trust registry, [`trust.py`](server/trust.py)) — done, tested.
  Tier A (`full`) / Tier M (`maintainer`, clear-only unilateral) / `moderator` scopes live on
  one registry, revocable by `token_id`; `/moderation` now requires `moderator` scope
  specifically rather than being implied by Tier A. CLEAR ↔ hazard reconciliation (§6.4) is also
  implemented: a published CLEAR now suppresses the hazard row(s) it resolves at read time
  (query-time reconciliation — nothing is deleted, a later hazard report simply outranks the
  clear by being newer), needs `K_CLEAR_FACTOR ×` the normal corroboration threshold, can't be
  corroborated by the same source that raised the hazard (checked live per-request against the
  cond-scoped source hash, since that hash is deliberately *not* cross-cond-correlatable — see
  `Store._reported_a_hazard_here`), and a reopen within `MAINTAINER_REOPEN_WINDOW` of a clear
  routes to `/moderation` instead of silently republishing.
- **Phase 1.6** (Tier B account linking, [`identity.py`](server/identity.py)) — done, tested.
  Device-code-style flow (`POST /link/init` mints a code for a producer's `mcUid`,
  `POST /link/complete` resolves it plus a real Discord OAuth `discordCode` — the server does its
  own token exchange via stdlib `urllib`, no separate service needed — into a fresh Tier B bearer
  token). Every UID linked to one Discord identity counts as **one** corroboration source, for
  free: the source key for a Tier B report is the resolved `discord_id`, not the token or UID.
  Tier B gets its own, lower corroboration threshold (`--k-tier-b`, default 2) than Tier C's
  (`--k-anon`). Moderators can suspend/reinstate a Discord identity
  (`/identity/<server>/<id>/suspend`) — a suspended identity's tokens fall back to Tier C rather
  than being rejected outright. This
  closes out the trust model designed in PROTOCOL.md §6 — Tier A/M/B/C, the registry, and
  moderator scope are all implemented now.
- **Phase 1.7** (multi-server + per-server trust) — done, tested. A deployment can serve more
  than one anarchy server by just dropping another `geometry/<server>.nether_highways.json` file
  in — `Store` already auto-discovered every geometry file per-server since Phase 0, no code
  change needed there. What *did* need work: every registry grant (`trust.py`) and Tier B link
  (`identity.py`) is now scoped to exactly one `server` — a token/identity trusted on one anarchy
  server carries no authority on another sharing the deployment (Owner stays deployment-wide on
  purpose). `--seed-token`'s format grew a segment (`TOKEN:SCOPE:SERVER:LABEL`), `POST /registry`
  and `POST /link/init` both now require `server` in the body, and `/moderation`'s list route and
  the `/identity/.../suspend|reinstate` routes gained a `<server>` path segment. `POST /report`'s
  scope resolution moved from once-per-batch to once-per-item, since a single batch can now
  legitimately span servers. Includes a real migration path for both SQLite stores so an
  already-running deployment's existing tokens/links survive the upgrade (back-filled to
  `2b2t.org`, the only server that could have created them) rather than needing a wipe.
- **Phase 2** AquariusProxy/ZenithProxy collector plugin ([plugin-aquarius/](plugin-aquarius/)) —
  built, compiles against the API jar (incl. `ObstructionWatcher`); awaiting live goldfarm
  verification before release — the stall/classification constants are the most likely thing to
  need tuning.
- **Phase 3** the public map website ([website/](website/)) — done, tested. Reads are public
  (PROTOCOL.md §7, locked in — no token on `/geometry`/`/conditions`/`/stream`), served as plain
  static files off the same `highway_conditions.py` process (`/` → `index.html`, path-traversal
  guarded). A vendored-Leaflet page (`CRS.Simple`, no tiles/basemap) draws the road skeleton from
  `/geometry` and live hazard markers from `/conditions`, using SSE purely as a "something
  changed, refetch" nudge rather than an incremental patch feed — the authoritative, already
  CLEAR-reconciled list always comes from `/conditions` itself. No login for viewing; writes are
  untouched, still fully tiered. `/moderation` submission — which used to piggyback its auth check
  on the read gate — got its own explicit rate limit so opening reads didn't also open the
  moderation queue to spam. In-game push (surfacing hazards ahead in the client itself) is still
  open, deferred to Phase 4. **Known gap, not silently papered over**: `sev` (severity) is
  validated on ingest but `Store` doesn't persist or aggregate it anywhere (no column on
  `conditions`), so there's no real severity signal yet for the map to size/color markers by.
  **`link.html`/`link.js`** complete the Tier B account-linking flow (PROTOCOL.md §6.2): enter the
  code your producer showed you, continue with Discord, land back on the same page (the pending
  link code rides through OAuth's `state` param — no server-side session needed), get a Tier B
  token. Builds its own OAuth URL from the public `GET /link/config` (client id + redirect URI +
  scope only — the client secret never leaves the server) instead of hardcoding a deployment's
  client id into committed JS. **LIVE at https://map.aquariusconnect.org** (ohv-2, Cloudflare named
  tunnel, systemd) as of 2026-07-19. **Cursor coordinates + ring-road labels (v0.2.6)**: the
  topbar shows the world `(x, z)` under the cursor (a plain inverse of the existing `w2ll`
  screen-projection, `ll2w`), and every ring road is labeled ("5k RR", "7.5k RR", "farlands RR",
  "WB RR", ...) at its topmost point on the map. Labels are derived from the ring's own
  already-curated name in the geometry data (stripping the trailing dig-dimension parenthetical
  and normalizing "ring road"/"ringroad" → "RR") rather than reformatted from its raw radius —
  the handful of non-round-number rings (the farlands loop is ~1,568,852 blocks out) would
  otherwise get an ugly derived label instead of their real name.
  **Reworked into a Google-Maps-style live label system (v0.2.7)**: the coordinate readout is
  now a tooltip that follows the cursor directly (plain viewport pixels, not routed through
  Leaflet's projection) instead of a fixed topbar field, and every road's label is recomputed
  on every pan/zoom (rAF-throttled) via Liang-Barsky segment/viewport clipping — so a label sits
  wherever that road is actually on screen right now, including deep out along a long ray
  highway where it wasn't visible at all before. One label per road (the first segment with a
  non-empty visible clip), not one per segment, so a 4-edge ring or the 74-segment near-spawn
  grid doesn't turn into a pile of duplicate labels.
  **Axis rays get a compass label, not their raw name (v0.2.9)**: the `axis` category
  combines all 8 cardinal/diagonal rays into just 2 road entries (dug/paved), so their `name`
  ("Cardinals (6x4) + Diagonals (7x4) dug") is really dig-width metadata, not a road name worth
  showing on the map. Axis roads now render as a bare compass direction (N/S/E/W/NE/SE/SW/NW)
  taken from which side of spawn the currently-visible clipped point is actually on, instead.
  **Zoom range opened up (v0.3.0)**: `minZoom` went from -4 to -14 -- the road network reaches
  all the way to the world-border ring (~3.75M blocks) and now has labels the whole way out
  (§ above), so there's a real reason to let someone scroll all the way there manually. The
  *default* view still lands one step further out than a tight fit of everything (rather than
  literally fitting the world-border points, which would reduce the near-spawn cluster everyone
  actually cares about to a speck), floored so it doesn't chase those far points out either.
- **Phase 3.1** the admin dashboard ([website/admin/](website/admin/), PROTOCOL.md §6.6) — done,
  tested. Everything §6.3–§6.5 previously needed raw `curl` + a bearer token for (moderation
  queue, registry tokens, identity suspend/reinstate) now has a browser UI, gated by Discord
  login instead of a token in the address bar. A new registry table (`discord_grants`,
  `trust.py`) grants `moderator`/`admin` scope directly to a `discord_id`, per server — `moderator`
  here is the exact same check §6.5 already used, so a moderator is a moderator whether reached
  by a bot's bearer token or a human's dashboard session; `admin` is dashboard-only and can
  issue/revoke registry tokens and grant/revoke `moderator` access on its own server(s). Login
  reuses the *same* Discord OAuth redirect URI account-linking already registered
  (`website/link.html` branches on `state=admin:<nonce>`, the nonce a login-CSRF guard checked
  via `sessionStorage`) rather than needing a second one configured with Discord. Sessions are a
  short-lived (12h), individually/per-identity-revocable, `HttpOnly`/`Secure`/`SameSite=Strict`
  cookie (`sessions.py`) — never a bearer token in the browser. **Minting or revoking `admin`
  scope itself always requires the raw Owner secret**, even for an existing admin acting through
  a live session; a bootstrap-only CLI/env flag (`--discord-admin` / `ARD_DISCORD_ADMINS`) is
  the only way a *first* admin grant is ever created. A WebAuthn/FIDO2 passkey second factor
  bound to the admin's identity is a natural next step for this login, not built yet — it would
  need a from-scratch pure-stdlib COSE/CBOR + P-256 ECDSA implementation to keep this project's
  no-pip posture.
  **Restyled (v0.2.3)** to match Aquarius Bot Manager's Mission Control look (`website/admin/
  admin.css`, `:root` overrides scoped to this one page — the public map/link pages are
  untouched) — same dark palette, Space Mono/Sora type, glowing accent dots, and hover-lift
  card rows as ABM, so the two Aquarius admin surfaces read as one family.
  **Real production bug fixed live (v0.2.4):** both `/link` and `/admin` login failed with
  `token exchange failed: 403 ... error code: 1010` — Discord's API sits behind Cloudflare,
  and [Discord's own docs](https://discord.com/developers/docs/reference#user-agent) say a
  default HTTP-tool signature (`Python-urllib/...`, unset `User-Agent`) gets blocked outright,
  not just discouraged. `identity.py`'s `discord_exchange()` never set one. Fixed by sending a
  real `User-Agent` on both outbound Discord requests; regression-tested with a mocked
  `urlopen` so this can't silently regress again. Also: a successful admin login now redirects
  straight to `/admin/` instead of pausing on `link.html`'s (intentionally plainer) OAuth-
  callback page, which read as landing somewhere stale mid-flow.
- **Privacy policy + terms of service published (v0.2.5)**, at `website/privacy.html` /
  `website/terms.html` — required for the Discord OAuth app's own settings, but written to
  actually be read. Privacy policy covers what's collected (Discord `identify` scope → only the
  numeric user ID, never email; a linked Minecraft UID; IP/Discord ID used transiently,
  in-memory only, to compute salted per-report corroboration hashes that are never persisted
  raw) and, more importantly, what's structurally impossible to collect at all (real
  coordinates — PROTOCOL.md §1); no analytics, no third-party trackers, no ad network, nothing
  sold or shared. Terms cover the obvious: free/as-is, reports are best-effort community data
  not verified fact, no warranty/liability, acceptable-use + revocable-access basics. Both
  linked from the map, the link page, and the admin dashboard's sign-in gate.
- **Phase 4** standalone Fabric client mod ([client-fabric/](client-fabric/)) — report-producer
  port of `plugin-aquarius` + the hazard-ahead HUD (the one thing nothing else in this project
  provides — surfacing reported conditions in-game, no alt-tabbing to the map) shipped; walking
  its own MC-version range one hop at a time (1.21.4 → 1.21.11 → 1.21.5 → 1.21.8 → **1.21.10**,
  the last stop before the already-shipped 1.21.11) rather than jumping straight to newest,
  independent of the proxy plugins' separate `mc=1.21.4` protocol-compatibility target. Some hops
  have needed a genuinely different HUD registration API (`HudRenderCallback` →
  `HudLayerRegistrationCallback` → `HudElementRegistry` at 1.21.6+, confirmed identical again at
  1.21.8/1.21.10/1.21.11 — each confirmed per-version rather than assumed) and `ClickEvent` moved
  to a
  sealed-interface shape as of ~1.21.5. A hardening pass alongside the 1.21.5 port added logging
  everywhere failures were previously silent, periodic geometry re-fetch (so a server-side policy
  change like this session's own `NEAR_SPAWN_RADIUS` widening reaches an already-running client),
  server-side road-filtering for the HUD's conditions poll, and corrupt-config backup-before-
  overwrite. Builds clean locally and via CI; not yet runtime-verified on a live server (the
  human-run equivalent of the proxy plugins' goldfarm-first standing order). **Phase 5
  reputation, pragmatic first step shipped**:
  travel-plausibility checking + trust-weighted corroboration (PROTOCOL.md §6.1.1) — not yet the
  full blind-token/anonymous-credential vision (still open), federation also still open.
  `client-fabric` is now GitHub-Actions built too (verified green on a real runner), matching
  the "GitHub is the only allowed builder of a released jar" rule the other two producers follow.
