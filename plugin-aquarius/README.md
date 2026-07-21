# HighwayConditions — AquariusProxy / ZenithProxy plugin

Contributes on-highway condition reports to the [Highway Conditions Network](../README.md)
from a headless proxy bot. It can only ever emit a 1-D on-highway coordinate — off-highway
positions are never reported (see [PROTOCOL.md](../PROTOCOL.md)).

## What it does

Each bot tick it reads the bot's own position from `Globals.CACHE` and, only when the bot is
**in the nether, on a highway (within tolerance), at y120**, enqueues a `(road, seg, along, cond)`
report and batches it to the ingest service. The gate order mirrors the reference client
(`server/reference_client.py`); the perpendicular offset is confined to `Geo.nearestAllowed`
and never becomes part of a report. Geometry (the road table + `map` hash) is fetched from the
ingest service, so client and server share one source of truth.

- `enabled` defaults **OFF** (opt-in). `reportPresence` (campers) defaults **OFF**.
- Config: `plugins/config/highway-conditions.json` (`HighwayConditionsConfig`).
- Command: `highwayConditions on|off`, `highwayConditions presence on|off`.

## Build

```bash
# needs the AquariusProxy fat jar (compileOnly + annotationProcessor)
./gradlew shadowJar -Paquarius_jar=/path/to/AquariusProxy.jar
# -> build/libs/HighwayConditions-<version>.jar   (copy into the proxy's plugins/ folder)
```

Compile JDK 25 (runs AquariusProxy's `@Plugin` annotation processor) / Java 21 bytecode.

### ZenithProxy build

The source targets `com.aquarius.*`. For stock ZenithProxy, compile the same sources against
`ZenithProxy.jar` with the imports swapped `com.aquarius.*` → `com.zenith.*` (mechanical; done
as a build flavor). Cache-polling + fetched geometry keep it MC-version-stable.

## Condition sampling (v1)

Robust subset that needs no version-specific block tables: `HOLE` (air gap under the road),
`LAVA` (floor or foot-level block is lava — void breach or a poured puddle), `COBWEB` (foot or
body height, trap-placed), `PRESENCE` (opt-in, count-only), obstructions (below), else `CLEAR`
(road traversed). LAVA/COBWEB are identity-checked against `BlockRegistry.LAVA`/`COBWEB` (same
pattern as `World.isWater()` — version-stable, no name-string matching) — implemented but not
yet exercised against a real griefed section; goldfarm is still the place to confirm the trigger
heights are right in practice, not just in theory.

## Obstruction detection (`OBSTRUCTION_FULL` / `OBSTRUCTION_PARTIAL`)

Nether highways routinely have signs and item frames placed on them (waypoints, decor) that
have negligible collision and must never be reported just for being present. Rather than a
block/entity-type blacklist, detection is purely **behavioral** — see PROTOCOL.md §5.1 for the
full rationale:

1. `ObstructionWatcher` runs every tick the bot is on-road: a mode-agnostic stall timer keyed
   off the bot's own recently-observed peak speed (not a fixed constant), so one detector works
   whether the bot is walking, Baritone-driving, or elytra-bouncing, and a parked/idle bot never
   false-triggers (it never had a meaningful peak speed to fall from). Fires at 3s (`sev=1`),
   6s (`sev=2`), 15s (`sev=3`) of sustained stall.
2. Only once a real stall is confirmed, `HighwayReporterModule` scans the road's cross-section
   (perpendicular to the segment, width from the road's `dim` metadata) for solid blocks or
   item-frame entities. All lanes blocked → `OBSTRUCTION_FULL`. Some blocked → `OBSTRUCTION_PARTIAL`
   with `laneMin`/`laneMax`. Nothing found → not reported (the stall had some other cause).

Decorative signs/frames are filtered by construction (they essentially never sustain a 3s
stall) — no version-fragile block-tag list to maintain.

## Status

Compiles against the AquariusProxy API jar and produces valid plugin metadata. **Not yet
runtime-tested on a live server** — per the project's standing order, the next step is a
local-jar → goldfarm deploy → live verification before this is treated as a proven-stable
release. The obstruction stall/classification constants (`ObstructionWatcher`,
`HighwayReporterModule`) are the most likely thing to need tuning against real elytra-bounce
and walking behavior.

**Download:** [plugin-aquarius-v0.1.1](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/plugin-aquarius-v0.1.1)
— GitHub-Actions-built, marked **pre-release** for exactly the untested-on-goldfarm reason above.

**Hardening pass (2026-07-20):** `HighwayReporterModule`'s resend-throttle used to commit
`lastSent` at enqueue time, before the batch was actually sent — a transient network/ingest
failure silently ate that observation's resend window, and on a highway a bot typically only
passes a given segment once per direction, so a real hazard could go unreported entirely.
Fixed: the throttle is now a read-only check at enqueue time; `lastSent` is only written once
`flush()` gets a confirmed HTTP 200. The ingest client is also now rebuilt automatically when
`ingestUrl`/`token` change via a live config edit (previously required a full plugin
off/on to pick up new credentials), and `IngestClient` releases its HTTP resources on
disable/rebuild instead of relying on GC. `flushIntervalTicks`/`resendSeconds` are floored
(1s / 10s) so a misconfigured value can't turn this into a flood against the ingest service.
Per-repo standing order: every published jar is GitHub-Actions-built, never hand-uploaded —
local builds are for VPS/client test staging only.
