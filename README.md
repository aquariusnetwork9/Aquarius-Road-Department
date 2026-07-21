# Aquarius Road Department (ARD)

Crowdsourced, real-time **nether-highway conditions** (holes, lava, obsidian roadblocks,
cobwebs, unbuilt gaps, campers) for 2b2t and other anarchy servers — fed by AquariusProxy /
ZenithProxy plugins and a standalone Fabric client mod, gated behind a tiered trust model
(PROTOCOL.md §6), consumed through a public map (PROTOCOL.md §7): reads are open to anyone, no
login, no token — contribution stays trust-gated, consumption doesn't.

This repo holds the **frozen wire protocol** and the **producers** that speak it (the two proxy
plugins + the Fabric mod) — see [Layout](#layout) below. The ingest/moderation service itself
runs separately and isn't distributed from here.

<p align="center"><em>Pure Python stdlib reference client. No pip, no Docker.</em></p>

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
  distance. Enforced redundantly at multiple layers: strict schema (rejects unknown fields) →
  server re-derivation → client on-road gate → aggregate-only storage.
- **SOFT (best-effort):** a contributor's own on-highway movements are hard to attribute —
  k-anonymity, TTL/decay, unlinkable reports, timing jitter, a per-contributor radius cap, and a
  spatially-tiered road set (full grid within 100k of spawn, axis-only beyond).

Reports are trust-gated to prevent low-effort false reporting; see PROTOCOL.md §6 for the tiers
a producer or player can operate at. The exact thresholds and anti-abuse mechanics behind that
gate aren't published — see the note in PROTOCOL.md §6.

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

`website/` isn't distributed as a downloadable artifact — it's deployed alongside the ingest
service (see below), not something you install into a client.

## Layout

One repo, per-component independent build tooling, bound together only by the schema/protocol
below — every producer implements the contract itself rather than sharing code, so no module has
to trust another's correctness.

```
PROTOCOL.md                 the frozen wire contract — the one thing every module below must honor
schema/report.schema.json   strict report schema (spec)
schema/moderation.schema.json
geometry/*.nether_highways.json   one file per server (e.g. 2b2t.org, 6b6t.org) — each an
                                   independent authoritative road table (axis/ring/diamond/grid,
                                   y120); a deployment auto-discovers every file here, no code
                                   change needed to add another server

protocol/                  the shared reference math every producer/consumer ports (Python)
  geometry.py                 the narrow-waist math: snap, quantize, re-derive, region policy
  reference_client.py         canonical client gate (Java/JS ports mirror this)

plugin-aquarius/            AquariusProxy/ZenithProxy collector plugin (Java, own Gradle build)
client-fabric/              standalone Fabric client mod (own Loom build) — Phase 4, report producer
                             + hazard-ahead HUD shipped, CI-built, awaiting live-server verification
                             (works alongside Meteor/RusherHack/LambdaClient or none of them;
                             no coupling to any client's internal addon API — see its README)

website/                    the public map + linking UI (static files, served by the ingest
                             service) — no build step, no login for viewing (PROTOCOL.md §7)
  index.html, style.css, app.js   Leaflet-based live map: road skeleton + hazard markers + SSE nudge
  link.html, link.js              Tier B account linking UI (PROTOCOL.md §6.1) -- code entry +
                                   Discord OAuth redirect, driven by the public GET /link/config
  admin/                          moderator/admin dashboard — Discord login, no bearer token in
                                   the browser
  vendor/leaflet.{js,css}         vendored, no CDN

tests/                      wire-format/geometry tests (unit + schema-fuzz)
```

The ingest/moderation/trust service that actually enforces PROTOCOL.md §6 and serves the routes
in §7 is deployed infrastructure, not distributed source — it only ever runs on boxes we control,
so unlike the producers above it isn't published in a public repo.

## Run (protocol/producer tests only)

```bash
# wire-format / narrow-waist math tests (stdlib unittest, no pip)
python -m unittest discover -s tests -t .
```

Each producer (`plugin-aquarius/`, `plugin-zenith/`, `client-fabric/`) has its own build/run
instructions in its own README. To get write access (a Tier A/M credential, or a moderator role)
for a server you run or contribute to, reach out to a maintainer — see PROTOCOL.md §8.

## Status

- **Phase 0** (protocol + strict schema + geometry math) — done, tested. Includes behavioral
  FULL/PARTIAL obstruction detection (PROTOCOL.md §5.1) — a sustained-stall threshold filters
  decorative signs/item frames without any block-type blacklist.
- **Phase 1** trust tiers, account linking, and multi-server support (PROTOCOL.md §6) — done,
  live. Tier A/M/B/C, the moderator role, and per-server scoping are all implemented in the
  deployed service.
- **Phase 2** AquariusProxy/ZenithProxy collector plugin ([plugin-aquarius/](plugin-aquarius/)) —
  built, compiles against the API jar (incl. `ObstructionWatcher`); awaiting live goldfarm
  verification before release — the stall/classification constants are the most likely thing to
  need tuning.
- **Phase 3** the public map + admin dashboard ([website/](website/)) — done, tested. Reads are
  public (PROTOCOL.md §7 — no token on `/geometry`/`/conditions`/`/stream`), served as plain
  static files. A vendored-Leaflet page (`CRS.Simple`, no tiles/basemap) draws the road skeleton
  and live hazard markers, using SSE purely as a "something changed, refetch" nudge. No login for
  viewing; writes are untouched, still fully tiered. **Live at https://map.aquariusconnect.org**
  since 2026-07-19, including cursor coordinates, ring-road labels, a Google-Maps-style live label
  system, and zoom out to the world border. Privacy policy + terms of service published at
  `website/privacy.html` / `website/terms.html`.
- **Phase 4** standalone Fabric client mod ([client-fabric/](client-fabric/)) — report-producer
  port of `plugin-aquarius` + the hazard-ahead HUD (the one thing nothing else in this project
  provides — surfacing reported conditions in-game, no alt-tabbing to the map) shipped; walking
  its own MC-version range one hop at a time (1.21.4 → 1.21.11 → 1.21.5 → 1.21.8 → **1.21.10**,
  the last stop before the already-shipped 1.21.11) rather than jumping straight to newest,
  independent of the proxy plugins' separate `mc=1.21.4` protocol-compatibility target. Builds
  clean locally and via CI; not yet runtime-verified on a live server (the human-run equivalent
  of the proxy plugins' goldfarm-first standing order). `client-fabric` is GitHub-Actions built
  too, matching the "GitHub is the only allowed builder of a released jar" rule the other two
  producers follow.
- **Phase 5** a reputation layer (weighting repeated contributions from the same verified
  identity) is live in the deployed service — mechanics aren't published here, see PROTOCOL.md
  §6.
