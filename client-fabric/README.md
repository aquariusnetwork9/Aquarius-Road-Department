# ARD — Fabric client mod

Standalone [Fabric](https://fabricmc.net/) mod: hazard-ahead HUD + optional report submission
for anyone on the highway network, regardless of which (if any) utility client they run.

**Why standalone instead of a Meteor/RusherHack/LambdaClient addon:** those clients are
themselves Fabric mods. A mod built only against stable Fabric/Fabric API hooks (HUD render,
world/tick events, networking, client commands) loads fine alongside any of them — or none,
picking up plain-Fabric players too — without coupling to any one client's internal addon API
(Meteor's especially is unstable and breaks across MC versions). One codebase, one release
cadence, and room for a real custom UI instead of squeezing into someone else's module-list
styling.

**Status (2026-09-07): local hazard alert + addon API + optional Baritone auto-avoid shipped,
not yet runtime-verified.** Builds clean locally (`./gradlew build`) and via CI against Minecraft
**1.21.11** (hopped forward from 1.21.10 for this featureset specifically — see
[Minecraft version](#minecraft-version) below). Actually joining 2b2t/6b6t with this mod loaded
hasn't happened yet — that's the necessary next step, the human-run equivalent of this project's
"goldfarm before any release" standing order for the proxy plugins.

**Download:** every MC version this mod has been ported to and rebuilt for is published as its
own separate release — pick the one matching your own game version, not just "the latest":

| MC version | Release | HUD API used |
|---|---|---|
| 1.21.4 | [client-fabric-v0.2.0+1.21.4](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.2.0+1.21.4) | `HudRenderCallback` |
| 1.21.5 | [client-fabric-v0.2.0+1.21.5](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.2.0+1.21.5) | `HudLayerRegistrationCallback` |
| 1.21.8 | [client-fabric-v0.2.0+1.21.8](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.2.0+1.21.8) | `HudElementRegistry` |
| 1.21.10 | [client-fabric-v0.2.0+1.21.10](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.2.0+1.21.10) | `HudElementRegistry` |
| 1.21.11 | [client-fabric-v0.2.0+1.21.11](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/client-fabric-v0.2.0+1.21.11) | `HudElementRegistry` |

All five are marked **pre-release** for the reason above — each one is GitHub-Actions-built
from the real commit where the mod actually targeted that MC version (never hand-uploaded), but
none has been run in a live game session yet. The tag format (`v<mod version>+<mc version>`)
matches Fabric API's own convention, since this mod's own version hasn't changed between
MC-version ports — a bare `v0.2.0` alone wouldn't distinguish which MC target you got.

**v0.2.0 note:** `main` carries the actual, evolving mod at its current target (1.21.11); the
1.21.4/1.21.5/1.21.8/1.21.10 releases above are built from short-lived `release/client-fabric-*`
branches carrying the identical featureset with only the per-version differences described below
— they're not meant to be merged, they exist so every previously-shipped MC target gets the new
capability too, matching this project's own "every MC version gets rebuilt" precedent. (One thing
worth knowing if you compare release history: the four non-1.21.10 tags under the old **v0.1.1**
line were all accidentally built from the same 1.21.10 commit — a real labeling bug from before
this pass, left as-is in release history rather than silently rewritten; v0.2.0 is the first
release line where all five tags actually contain what their MC-version label says.)

## What it does

- **Hazard-ahead HUD** (`hud/HazardHudElement.java`) — the one thing nothing else in this
  project provides: the AquariusProxy/ZenithProxy plugins have no human watching a screen, and
  the public map website needs alt-tabbing out of the game. Polls the fully-public
  `GET /conditions/<server>` (no token needed — locked in 2026-07-19, see PROTOCOL.md §7) every
  few seconds, filters to the road you're currently on, and shows the nearest upcoming hazard
  ahead of your direction of travel as a small on-screen line. On by default, independent of
  report submission — it's a pure read with zero privacy cost.
- **Local hazard alert** (`module/LocalHazardModule.java`) — a second, always-on HUD line (`⚠
  LOCAL: ...`) fed entirely by *this client's own* live detection, with no network round-trip and
  no trust-tier gate: the same stall-watchdog/lane-scan classification the reporter uses
  (`ObstructionWatcher` + the shared `module/LaneScan.java`), but surfaced the instant it fires
  instead of waiting for a report to be submitted, corroborated, and published. Runs regardless
  of `/ard reporting on|off` — nothing here ever leaves the client, so it's on by default
  (`ard.json`'s `hud.localAlertEnabled`), same reasoning as the crowdsourced line.
- **Public API for other mods** (`api/LocalHazardEvents.java`, `api/LocalHazard.java`) — the
  local detection above is also fired as a plain Fabric event, so a Meteor addon (or any other
  Fabric mod) can react to it directly instead of re-implementing detection:
  ```java
  LocalHazardEvents.DETECTED.register(hazard -> {
      // hazard.x()/y()/z(), hazard.headingX()/headingZ(), hazard.perpX()/perpZ(),
      // hazard.fullyBlocked(), hazard.laneMin()/laneMax(), hazard.severity()
  });
  LocalHazardEvents.CLEARED.register(() -> { /* the last hazard is no longer active */ });
  ```
  Everything in the `api` package is the stable surface this project intends third parties to
  depend on; everything outside it is internal and can change without notice. No coupling runs
  the other way — ARD has no idea what (if anything) is listening.
- **Optional Baritone auto-avoid** (`module/BaritoneAvoidance.java`) — opt-in
  (`/ard baritone on|off`, default off), consumes the exact same public API a third-party addon
  would. On a detected hazard it reflectively drives Baritone's own
  `BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(...)`
  to a detour point past the blocked lanes, then hands control back once the hazard clears (or
  after a 10s timeout). **Zero compile-time or runtime dependency on Baritone** — this mod's
  standing "no coupling to another client's internal API" rule applies to Baritone exactly like
  it does to Meteor/RusherHack/LambdaClient; Baritone's presence and API shape are feature-probed
  once via reflection and everything degrades to "disabled, one log line" if it's missing or
  doesn't match. This mod hopped its own target to **1.21.11** specifically so this feature has
  something to talk to — Baritone's newest release targets 1.21.11 and, as of writing, has no
  1.21.10 build at all.
- **Report submission** (`module/HighwayReporterModule.java`) — a Fabric-native port of
  [`plugin-aquarius`](../plugin-aquarius)'s reporter module: same gate order (nether → radius
  cap → on-road snap → strict y120), same LAVA/COBWEB/HOLE sampling, same behavioral obstruction
  detector (`module/ObstructionWatcher.java`, ported verbatim — pure math, no game-API
  dependency). Off by default (opt-in), toggled via `/ard reporting on|off`.
- **Account linking** — `/ard link` requests a device-code-style link code
  (PROTOCOL.md §6.2) using your already-authenticated Minecraft session UUID, proves you hold
  that account directly to Mojang (a real `session/minecraft/join` call via the game's own
  session service — the same call vanilla makes joining any online-mode server, just pointed at
  a one-time nonce ARD hands back instead of a real server), then posts the code to chat with a
  one-click link to the website's Discord-login flow. Once you finish there, copy the shown
  token and run `/ard token <value>` to actually start reporting as Tier B. The ownership-proof
  step is best-effort from this mod's side (a failure is reported to chat but doesn't block
  getting a link code) — whether it's actually *required* to complete linking is a server-side
  deployment setting, off by default until enough of the fleet runs a build that supports it.

No coordinate ever leaves this module except as `(road, seg, along)` — same hard guarantee as
the proxy plugin, enforced independently (never trust the other producer to have done it).
`net/Geo.java` and `net/Report.java` are byte-for-byte the same files as `plugin-aquarius`'s.

## Commands

| command | effect |
|---|---|
| `/ard status` | shows current config (reporting/presence/HUD on-off, server, ingest URL) |
| `/ard reporting on\|off` | toggles report submission |
| `/ard presence on\|off` | toggles opt-in camper/presence reporting |
| `/ard link` | requests an account-link code |
| `/ard token <value>` | sets the bearer token (Tier A/B) after finishing `/ard link` |

Config persists as JSON at `<Fabric config dir>/ard.json`.

## Minecraft version

Targets **1.21.11** right now — hopped forward from 1.21.10 specifically to give the new
Baritone auto-avoid something to talk to (Baritone's own newest release targets 1.21.11 and has
no 1.21.10 build). This mod has walked the version range one hop at a time since it shipped
(1.21.4 → 1.21.11 → 1.21.5 → 1.21.8 → 1.21.10 → **1.21.11**, again) rather than jumping straight
to newest, so each hop's real API drift gets caught and fixed rather than skipped over — this
particular hop needed no source changes at all (see the HUD-API table below: 1.21.8/1.21.10/
1.21.11 are confirmed identical), just the `gradle.properties` version tuple. Independent of
`plugin-aquarius`/`plugin-zenith`'s separate `mc=1.21.4` property — that's a different concern
(which AquariusProxy/ZenithProxy protocol-compatibility release channel to compile against, not
a real Minecraft client version). Not a hand-rolled multi-source-set project — a proper
multi-version tool (e.g. [Stonecutter](https://stonecutter.kikugie.dev/)) is the intended path
once there's a real need to support more than one version at once, not built yet; in the
meantime, a release for every previously-shipped MC version is still published from a short-lived
per-version branch carrying the same feature set — see the Downloads table above.

**The HUD registration API is genuinely different at some hops** — confirmed by checking the
actual javadoc for each target version rather than assuming, since guessing here has already
produced one real compile failure (below):

| MC version | HUD API |
|---|---|
| 1.21.4 | `net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback` (the only option) |
| 1.21.5 | `HudLayerRegistrationCallback` + `LayeredDrawerWrapper.attachLayerBefore(IdentifiedLayer.CHAT, id, layer)` — `HudRenderCallback` still exists but is already deprecated in favor of this |
| 1.21.8, 1.21.10, 1.21.11 (current) | `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, element)` (introduced at 1.21.6, replacing `HudLayerRegistrationCallback`) — confirmed identical across all three versions |

`(DrawContext, RenderTickCounter)` is the one render-method signature that's stayed constant
across every version so far, which is why `HazardHudElement.render` itself never needed to
change — only the entrypoint's registration call, and only when crossing the 1.21.6 boundary.

`ClickEvent` also changed shape as of ~1.21.5 — it's a sealed interface with nested record types
now (`new ClickEvent.OpenUrl(URI.create(url))`, not the old `ClickEvent(Action, String)`
constructor). **Caught by an actual compile failure, not assumed**: the first attempt at the
1.21.11 port assumed `OpenUrl` took a `String` (following an LLM-summarized javadoc that got the
parameter type wrong); the real compiler error corrected it to `URI`. Unchanged since.

**`MinecraftClient`'s session-service accessor also changed, discovered during the v0.2.0
five-version pass** (`/ard link`'s Mojang ownership-proof call, `HighwayConditionsCommand`):

| MC version | Session service access |
|---|---|
| 1.21.4, 1.21.5, 1.21.8 | `mc.getSessionService()` directly, returns `MinecraftSessionService` |
| 1.21.10, 1.21.11 (current) | `mc.getApiServices().sessionService()` — `ApiServices` is a new record wrapping session service + a few other identity-related services |

Confirmed by loading each version's actual mapped Minecraft jar and inspecting `MinecraftClient`'s
declared methods directly (`javap`), not assumed — this account-linking feature was added to the
codebase after the original 1.21.4/1.21.5/1.21.8 ports had already moved on, so nothing had ever
compiled it against those older mappings before.

## Hardening pass (2026-07-20)

Alongside the 1.21.5 port, a review pass over the whole mod fixed several real robustness gaps
found by re-reading every file critically rather than assuming the first-pass code was solid:

- **No logging existed anywhere.** Geometry-fetch failures, report-POST failures, and the HUD's
  conditions-poll failures were all silently swallowed (`catch (Exception ignored)`) — a broken
  `ingestUrl` or a down server produced zero feedback, in chat or in the log. Added SLF4J logging
  (`LoggerFactory.getLogger("ard")`) at each of those points.
- **Geometry never refreshed after first load.** `GeoCache` fetched once and then never again —
  a server-side geometry/policy change (this project made exactly one this same day, widening
  `NEAR_SPAWN_RADIUS`) would need a client restart to pick up. Now re-fetches every 10 minutes
  even once loaded (a failed refresh keeps the last-known-good geometry, never reverts to null).
- **The HUD fetched the whole network's conditions on every poll.** `GET /conditions/<server>`
  supports a `?road=` filter (PROTOCOL.md §7) that was simply never used — every poll pulled
  every published condition on the entire server and filtered client-side. Now filtered
  server-side to the player's current road.
- **A corrupt `ard.json` was silently discarded and overwritten with defaults** — including
  whatever token was in it, with zero indication anything went wrong. Now logs a warning and
  backs the broken file up to `ard.json.bad` before writing fresh defaults.
- **A pasted `/ard token` value wasn't trimmed** — a stray leading/trailing space from a browser
  copy-paste would silently break auth with no diagnosable symptom. Now trimmed.
- Bumped the shared network executor from 2 to 3 threads (geometry poll, report flush, HUD poll,
  and an on-demand `/ard link` call can all legitimately want a thread at once), and the current
  `IngestClient` is now explicitly closed on `CLIENT_STOPPING`, not just the executor shut down.
- A missing `ObstructionWatcher.reset()` call (on the "geometry not loaded yet" early-return)
  was filled in for consistency with every other early-return in the tick gate, even though it's
  low-impact in practice (only reachable during the initial bootstrap window).

## v0.2.0: new featureset, ported to every published MC version (2026-09-07)

Local hazard alert + addon API + optional Baritone auto-avoid (see "What it does" above) shipped
as a PR against the then-current 1.21.10 target, then the mod hopped to 1.21.11 (Baritone's own
actual supported version) and the identical featureset was rebuilt against every other
previously-published MC version (1.21.4, 1.21.5, 1.21.8, 1.21.10) from short-lived
`release/client-fabric-*` branches — see the Downloads table above for all five v0.2.0 releases.

Real, previously-undiscovered API drift turned up doing this (this account-linking codepath had
never actually been compiled against pre-1.21.10 mappings before): `MinecraftClient`'s session
service moved from a direct `getSessionService()` to `getApiServices().sessionService()` somewhere
around 1.21.9/1.21.10 — confirmed by loading each version's real mapped jar with `javap`, not
assumed. See the version table above.

## Building

```
./gradlew build
```

Produces `build/libs/ard-<version>.jar`. Nothing is shaded — Gson is bundled by Minecraft
itself, `java.net.http.HttpClient` is JDK-native, and Fabric API is a normal player-installed
sibling mod (never embedded). Same standing rule as the rest of this repo: **GitHub Actions is
the only allowed builder of a released jar** — a local build here is for testing only.

CI (`.github/workflows/client-fabric-build.yml` / `client-fabric-publish.yml`) mirrors the two
proxy plugins' pattern — path-scoped triggers, dependency-graph submission, a namespaced release
tag (`client-fabric-v<mod version>+<mc version>`, e.g. `client-fabric-v0.1.0+1.21.11` —
MC-version-suffixed since this mod's own version doesn't change between MC-version ports) — but
is simpler: no proprietary API jar to fetch, since Loom pulls Minecraft/Yarn/Loader/Fabric API
straight from public Fabric/Mojang Maven repos declared in `build.gradle.kts`. Verified green on
a real GitHub-hosted runner. Publishing is
`workflow_dispatch`-only, same as the other two producers.
