# HighwayConditions — stock ZenithProxy build

The exact same plugin as [`../plugin-aquarius`](../plugin-aquarius), recompiled against stock
[ZenithProxy](https://github.com/rfresh2/ZenithProxy) instead of the AquariusProxy fork.

## This project has no source code of its own

`plugin-aquarius/src` is the single source of truth. `./gradlew build` here runs
`generateZenithSources` first, which copies every `.java` file out of
`../plugin-aquarius/src/main/java`, rewrites the AquariusProxy-fork-specific package prefix and
one branded type name back to their stock-ZenithProxy equivalents, and compiles the result
against `ZenithProxy.jar`:

| AquariusProxy (source of truth) | Stock ZenithProxy (generated) |
|---|---|
| `com.aquarius.*` | `com.zenith.*` |
| `com.aquarius.plugin.api.AquariusProxyPlugin` | `com.zenith.plugin.api.ZenithProxyPlugin` |

That second row is the one case where the fork renamed the *type itself*, not just its package
(confirmed against `zenith-abm-bridge`, a real plugin already built against stock
`ZenithProxy.jar`). Everything else `plugin-aquarius` imports (`Globals`, `event.client.*`,
`module.api.Module`, `util.timer.*`, `mc.block.*`, `command.api.*`, `command.brigadier.*`,
`discord.Embed`) is a pure package rename with no class-name change — a straight
`com.zenith → com.aquarius` rename is the fork's whole history. If a future AquariusProxy-only
API gets used in `plugin-aquarius`, this build will simply fail to compile — that's the signal
to either avoid it or add a rule to `generateZenithSources` in `build.gradle.kts`.

**Never edit anything under `build/generated/`.** Fix the plugin logic in `plugin-aquarius/src`;
this project picks it up on its next build. If a fix is genuinely ZenithProxy-specific (a real
behavioral difference between the fork and stock, not just naming), that's the one case where
this project would need its own source override — not needed yet.

## Build

```bash
# needs a stock ZenithProxy fat jar (compileOnly + annotationProcessor)
./gradlew shadowJar -Pzenith_jar=/path/to/ZenithProxy.jar
# -> build/libs/HighwayConditionsZenith-<version>.jar   (copy into ZenithProxy's plugins/ folder)
```

Compile JDK 25 (runs ZenithProxy's `@Plugin` annotation processor) / Java 21 bytecode — same
constraints as `plugin-aquarius`.

## Status

Hardened + CI-built (mirrors `plugin-aquarius`'s hardening pass, 2026-07-20 — see that
project's README for the reporting/obstruction-detection design this inherits unchanged). **Not
yet runtime-tested on a live server** — same goldfarm-before-stable standing order as
`plugin-aquarius`. Local builds are for VPS/client test staging only — every published jar is
GitHub-Actions-built.

**Download:** [plugin-zenith-v0.1.1](https://github.com/aquariusnetwork9/Aquarius-Road-Department/releases/tag/plugin-zenith-v0.1.1)
— marked **pre-release** for exactly the untested-on-goldfarm reason above.
