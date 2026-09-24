# RigTune v0.2.0: Distant Horizons + Iris research

Research date 2026-09-25, for Minecraft Java 26.2/26.3, Fabric. **GitLab (gitlab.com) was in a
partial outage for the entire research window** (confirmed via web search: "GitLab is in a partial
outage as of September 24, 2026" / 11 outages in the last 30 days; every direct request to
`gitlab.com/distant-horizons-team/...` returned 503, including the raw source, the tags API, and the
hosted JavaDoc at `distant-horizons-team.gitlab.io`). DH's canonical source repo lives on GitLab, so
**DH claims below are verified against the shipped jar (decompiled with `javap`) and the user's own
live config file, not against `Config.java` source** — I could not read DH's internal (non-`api`
package) source at all this session. Iris's source lives on GitHub, which was reachable throughout, so
Iris claims are verified against actual source at the `26.3` branch tip.

Primary evidence used, in order of trust:
1. The user's own **live, working** `DistantHorizons.toml` and `iris.properties` at
   `C:/Users/Admin/AppData/Roaming/ModrinthApp/profiles/Fabric 26.2/config/` (read-only, not copied)
   — real key names, real formatting, produced by the actual mod on the actual rig (Ryzen 7 7800X3D,
   RX 7800 XT 16GB, 32GB RAM, 1440p180, DH 3.3.0 `fabric-26.2.jar`, Iris with
   ComplementaryReimagined, shaders currently off).
2. DH 3.3.2 (`distanthorizons-3.3.2-26.2`/`-26.3`) fabric jars downloaded from the Modrinth CDN and
   decompiled with `javap -p` (`C:/Dev/Tools/jdk/jdk-25.0.4.1+1/bin/javap.exe`) — the DH API is
   bundled inside the main jar under `com/seibel/distanthorizons/api/**`, so this is real bytecode,
   not a guess.
3. Iris source at `github.com/IrisShaders/Iris` (default branch `trunk`; `26.3` is the branch the
   26.3 builds are cut from) fetched directly as raw files.
4. Modrinth versions API (`api.modrinth.com/v2`) for version/loader/game-version matrices and Maven
   coordinates.
5. `blog.curseforge.com/distant-horizons-frequently-asked-questions/` — this is DH's own FAQ,
   linked from the DH Modrinth page, so treated as first-party.
6. `distanthorizonsguide.com` — a third-party (not DH-team) fan site with a maintained
   shader-compatibility database and settings guides. Treated as corroborating, not authoritative;
   cross-checked against a second independent community source (a widely-referenced compatibility
   gist) where possible.

Existing RigTune context read before starting: `docs/RULES_SCHEMA.md`, the DH/Iris entries in
`rules/source/knowledge.json` (DH is currently **advice/settings-only** — `vanilla.renderDistance`
capped at 12 when DH present, two RAM advice rules; Iris is in `reviewIgnore` as "shader-pack loader;
a visual feature that costs FPS, not a performance optimization" — this research does not reopen that
call), and `docs/research/knowledge.md` (prior DH/Iris findings, mostly compatible-version tables).

---

## 1. Summary and proposed rules

**Headline answers:**
- DH 3.3.0 and 3.3.2 both support Fabric 26.2 **and** 26.3 (mod id `distanthorizons`). Older
  3.1.x/3.2.0-b builds are 26.2-only. Iris support is version-pinned per MC version: 26.2 tops out at
  `1.11.4+26.2-fabric`, 26.3 needs `1.11.5+`/`1.11.6+26.3-fabric` — no cross-version Iris jar works.
- DH's config (`config/DistantHorizons.toml`) and Iris's config (`config/iris.properties`) are both
  directly, safely machine-editable **while the game is closed**, with caveats in §8 — most
  importantly a real, verified quoting inconsistency in DH's TOML (§8.1) that will silently corrupt
  the file if a naive read-replace-write is used.
- Both mods expose a real runtime API suited to a benchmark that sweeps a setting and restores it:
  DH's `DhApi.Delayed.configs.graphics().chunkRenderDistance()` /`.renderingEnabled()` (§3), Iris's
  `IrisApi.getInstance().getConfig().setShadersEnabledAndApply(boolean)` (§5). Both are real,
  bytecode/source-verified, not guessed.
- RigTune's existing `max: 12` vanilla-render-distance clamp when DH is present is directly supported
  by DH's own FAQ ("8-12 is the recommended max vanilla render distance" once DH is installed) — see
  §4 for a proposed tier-aware tightening (8-10 for tier ≤2) rather than a flat 12 for everyone.

### 1.1 Proposed settings namespaces

Two new namespaces, following the existing `vanilla.<key>` / `sodium.<section>.<field>` convention
(RULES_SCHEMA.md "Settings keys"):

- **`dh.<toml.dotted.path>`** — the key is the literal dotted path inside `DistantHorizons.toml`,
  e.g. `dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius`. This only works for values
  RigTune's settings-file reader can parse out of the TOML (a new parser, not reused from
  `sodium-options.json`'s JSON reader — DH's file is TOML, not JSON). All entries below need
  `"when": { "modPresent": ["distanthorizons"] }` (or nest it inside `anyOf`/combine with tier
  conditions) since, per RULES_SCHEMA, a `SettingRule` is silently skipped when its key isn't present
  in the current `SettingsSnapshot` — which naturally happens when DH isn't installed, but an explicit
  `modPresent` condition keeps intent clear and matches how the existing DH advice rules already gate.
- **`iris.<key>`** — flat, matching `iris.properties`' own flat structure, e.g. `iris.enableShaders`,
  `iris.maxShadowRenderDistance`. All entries need `"when": { "modPresent": ["iris"] }`.

**One key does *not* fit the TOML-file model**: `renderingEnabled` (the DH API's on/off toggle,
§3) has **no corresponding key anywhere in the live TOML** (verified — grepped the full 1044-line
file for every boolean-looking candidate near "render"/"enable"; nothing matches). It appears to be
an API-only, in-memory-only switch, distinct from the `rendererMode` debug enum
(`DEFAULT`/`DEBUG_TRIANGLE`/`DISABLED`) that *is* in the TOML under
`client.advanced.debugging.rendererMode`. Recommendation: don't model `renderingEnabled` as a
`dh.*` `SettingRule` at all — it belongs to the **benchmark** tool (already being scoped in
`docs/research/v0.2/benchmark.md` by another research pass) as a live API-only toggle, not to the
persistent settings-recommendation system. This is genuinely useful news for that benchmark: toggling
it should not dirty the user's saved config.

### 1.2 Proposed `SettingRule` entries (values are RigTune-authored, synthesized from §4/§6 sources — not copied from an official DH preset table, since presets aren't a settable field at all; see §4)

| key | type | t1 | t2 | t3 | t4 | t5 | condition |
|---|---|---|---|---|---|---|---|
| `dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius` | int (chunks) | 48 | 64 | 96 | 160 | 256 | `modPresent:[distanthorizons]`, `tierAtLeast/tierAtMost N` |
| `dh.client.advanced.graphics.quality.verticalQuality` | enum string | `LOW` | `MEDIUM` | `HIGH` | `HIGH` | `VERY_HIGH` | same |
| `dh.client.advanced.graphics.quality.horizontalQuality` | enum string | `LOWEST` | `LOW` | `MEDIUM` | `HIGH` | `HIGH` | same |
| `dh.client.advanced.graphics.quality.maxHorizontalResolution` | enum string | `CHUNK` | `HALF_CHUNK` | `FOUR_BLOCKS` | `TWO_BLOCKS` | `BLOCK` | same |
| `dh.common.multiThreading.numberOfThreads` | int | 1 | 2 | 4 | 6 | 8 (clamp to logical cores) | same |
| `vanilla.renderDistance` | int (`max` clamp) | 8 | 8 | 10 | 12 | 12 | existing rule generalized: keep `max:12, modPresent:[distanthorizons]`, **add** a second, stricter clamp `max:8, modPresent:[distanthorizons], tierAtMost:2` after it (clamps apply in file order and compose — RULES_SCHEMA.md "SettingRule") |
| `iris.maxShadowRenderDistance` | int (chunks) | 16 | 24 | 32 | 32 | 32 | `modPresent:[iris]`, `flags:["shaders-enabled"]` |

Not proposed as `SettingRule`s: `iris.enableShaders` / `iris.shaderPack` (picking/forcing a shader
pack is an aesthetic choice outside RigTune's current scope, matching the `iris` `reviewIgnore`
rationale) and `dh.client.advanced.graphics.quality.transparency` (only two values exist —
`COMPLETE`/`DISABLED` — and forcing `DISABLED` is a visible quality cut better left as `AdviceRule`
text than a silent auto-apply).

---

## 2. DH config: file, format, and key table

**File**: `config/DistantHorizons.toml` (relative to the instance root — same place `options.txt` and
`config/sodium-options.json` live). **Format**: TOML, `_version = 4` at the top (a schema-version
field — see §8 for what this implies about migration). Tab-indented nested `[section.subsection]`
tables; every key has a preceding `#`-comment block copied near-verbatim from the in-game tooltip.

Values below are **read directly from the user's live 3.3.0 config**, which is a tuned tier-5 rig's
file, not a guaranteed-fresh-install default — I did not verify true factory defaults this session
(that needs either a fresh profile's file, diffing against, or calling `getDefaultValue()` on the live
API, which needs the game running). Types and enum ranges *are* independently confirmed from the
bytecode enums (§3), so those are solid regardless of which instance's file was read.

| dotted TOML path | type (TOML) | observed value (tier-5 rig) | enum range / bounds |
|---|---|---|---|
| `client.advanced.graphics.quality.lodChunkRenderDistanceRadius` | int | `256` | API: `IDhApiConfigValue<Integer>`, has `getMinValue()`/`getMaxValue()` — read at runtime, don't hardcode |
| `client.advanced.graphics.quality.verticalQuality` | quoted string enum | `"HIGH"` | `HEIGHT_MAP, LOW, MEDIUM, HIGH, VERY_HIGH, EXTREME, PIXEL_ART` |
| `client.advanced.graphics.quality.horizontalQuality` | quoted string enum | `"HIGH"` | `LOWEST, LOW, MEDIUM, HIGH, EXTREME` |
| `client.advanced.graphics.quality.maxHorizontalResolution` | quoted string enum | `"BLOCK"` | `CHUNK, HALF_CHUNK, FOUR_BLOCKS, TWO_BLOCKS, BLOCK` (fastest→fanciest, per in-file comment) |
| `client.advanced.graphics.quality.transparency` | quoted string enum | `"COMPLETE"` | `DISABLED, COMPLETE` |
| `client.advanced.graphics.quality.lodBiomeBlending` | int | `3` | 0 (off) .. higher = slower |
| `client.advanced.graphics.fog.*` (11 keys) | mixed | see file | fog on/off, falloff curve, density — not tier-critical, skip for v0.2 rules |
| `client.advanced.graphics.culling.overdrawPrevention` | **quoted string float** | `"-1.0"` | `-1.0` = auto; note the quoting (§8.1) |
| `client.advanced.graphics.culling.disableFrustumCulling` | bool | `false` | — |
| `client.advanced.debugging.rendererMode` | quoted string enum | `"DISABLED"` (this is the *debug* renderer selector, not the on/off switch — see below) | `DEFAULT, DEBUG_TRIANGLE, DISABLED` |
| `common.multiThreading.numberOfThreads` | int | `8` | API `threadCount()`, no visible ceiling in the enum but sanity-clamp to logical cores |
| `common.multiThreading.threadRunTimeRatio` | **quoted string double** | `"1.0"` | 0.0–1.0 |
| `common.worldGenerator.generatorPlan` | quoted string enum | `"SURFACE_THEN_CHUNKS"` | `SURFACE_THEN_CHUNKS, SURFACE_ONLY, CHUNKS_ONLY, DISABLED` |
| `common.worldGenerator.enableDistantGeneration` | bool | `true` | — |
| `client.advanced.debugging.autoUpdater.*` (under `client.advanced.autoUpdater`) | bool/string | `enableAutoUpdater=true`, `enableSilentUpdates=true`, `updateBranch="AUTO"` | auto-update / network settings the brief asked about — confirmed present, client-side only, no server call config beyond branch choice |

**GPU upload method — a specific negative finding worth flagging**: the API still defines an
`EDhApiGpuUploadMethod` enum (`AUTO`, `BUFFER_STORAGE`, `SUB_DATA`, `DATA`), but I grepped **every**
class under `com/seibel/distanthorizons/api/interfaces/config/**` for a `gpuUploadMethod`-shaped
getter and found none, and the live TOML has no matching key either. This setting existed in older
DH/OpenGL-era versions but is **not user-configurable in 3.3.2** on 26.2/26.3 (plausibly hardcoded now
that `BLAZE_3D`/Vulkan is the 26.x default renderer — `client.advanced.graphics.experimental.renderingEngine = "AUTO"`,
comment: "BLAZE_3D - The Default for MC 26.1.2 and newer"). Don't propose a `dh.*` rule for it.

**"Enable Distant Horizons rendering" toggle**: there is **no TOML key for this** either (§1.1) — the
closest TOML-level control is `rendererMode` (`DEFAULT`/`DISABLED`/`DEBUG_TRIANGLE`), which the file's
comment frames as a *debug* setting ("What renderer is active?"), not the user-facing on/off. The
real on/off lives only in the API (`renderingEnabled()`, §3) or presumably a keybind/GUI action not
reflected in the TOML at all.

---

## 3. DH public API (`DhApi`)

Bundled in the main mod jar, package `com.seibel.distanthorizons.api`. Confirmed present and
loader/version-independent (it's in `common/src/api` conceptually — Fabric and NeoForge jars share
it). **Separate lightweight artifact for `compileOnly`**: Modrinth project `distanthorizonsapi`
(id `Xs0XTOVv`) — "This jar includes the source code and classes needed to interact with Distant
Horizons... This jar should be used during development instead of the full Distant Horizons jar to
prevent accidentally using unsafe internal methods." Current version **7.2.0** (matches DH 3.3.2's
changelog: "Up API version 7.1.0 -> 7.2.0"), fabric/forge/neoforge jar, Maven coordinate via the
Modrinth Maven bridge:
```gradle
repositories {
    exclusiveContent {
        forRepository { maven { name = "Modrinth"; url = "https://api.modrinth.com/maven" } }
        filter { includeGroup "maven.modrinth" }
    }
}
dependencies {
    compileOnly "maven.modrinth:distanthorizonsapi:LTP8vW3B"   // = API 7.2.0
}
```
(`LTP8vW3B` is the Modrinth version id for API 7.2.0 — re-resolve if DH bumps its API version.)

**Entry points** (`javap`'d directly, `DhApi.class` / `DhApi$Delayed.class`):
```java
DhApi.getModVersion();           // String, e.g. "3.3.2"
DhApi.getApiMajorVersion();      // int
DhApi.isDhThread();               // boolean — thread-safety helper, see below

DhApi.Delayed.configs;            // IDhApiConfig — the config tree, see below
DhApi.Delayed.renderProxy;        // IDhApiRenderProxy — renderer internals, not settings
DhApi.Delayed.worldProxy;         // IDhApiWorldProxy
DhApi.Delayed.events;             // IDhApiEventInjector
```
**"Delayed" is literal**: these are static fields populated after DH finishes initializing, and the
API ships a dedicated event for exactly this — `com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent`
(confirmed present in the jar). A benchmark tool should bind/wait on that event (via
`DhApi.events`... `IDhApiEventInjector`) before touching `DhApi.Delayed.*`, rather than assuming
they're non-null immediately after Fabric's mod-init phase. (I did not find and could not verify the
exact binding call signature for a third-party listener from the `api` package alone — the injector
interface only exposes `unbind`/`fireAllEvents`; binding is presumably done via
`IDhApiEventInjector`'s parent `IDependencyInjector` interface, which lives in the internal
`coreapi` package I didn't decompile. **UNVERIFIED**: exact subscribe call.)

**Config tree** (`IDhApiConfig`, all confirmed by `javap`):
```java
IDhApiConfig cfg = DhApi.Delayed.configs;
cfg.graphics()        // IDhApiGraphicsConfig
cfg.worldGenerator()  // IDhApiWorldGenerationConfig
cfg.multiThreading()  // IDhApiMultiThreadingConfig
cfg.multiplayer()     // IDhApiMultiplayerConfig
cfg.debugging()       // IDhApiDebuggingConfig
```

**The exact calls the brief asked about, both confirmed real**:
```java
IDhApiConfigValue<Integer> rd = DhApi.Delayed.configs.graphics().chunkRenderDistance();
IDhApiConfigValue<Boolean> on = DhApi.Delayed.configs.graphics().renderingEnabled();
```
`IDhApiConfigValue<T>` (generic, used for every settable field — `threadCount()`,
`verticalQuality()`, `maxHorizontalResolution()`, etc. all return one of these):
```java
public interface IDhApiConfigValue<T> {
    T getValue();
    T getTrueValue();     // distinct from getValue() — see caveat below
    T getApiValue();      // distinct again
    boolean setValue(T);
    boolean setValue(T, String sourceName);  // sourceName is attributed in logs (3.3.2 changelog:
                                              // "API users can now provide their mod's name to show
                                              // who is actively controlling that option")
    boolean clearValue();                    // clears an API override, not the same as resetting to factory default
    boolean getCanBeOverrodeByApi();          // check before setValue — some fields may refuse
    T getDefaultValue();
    T getMaxValue();
    T getMinValue();
    void addChangeListener(Consumer<T>);
}
```
**Benchmark recipe this supports directly**:
```java
IDhApiConfigValue<Integer> rd = DhApi.Delayed.configs.graphics().chunkRenderDistance();
int original = rd.getValue();
for (int candidate : sweepPoints(rd.getMinValue(), rd.getMaxValue())) {
    rd.setValue(candidate, "RigTune");
    // measure FPS
}
rd.setValue(original, "RigTune");   // restore — do NOT use clearValue() for this (see caveat)
```
**Caveat, UNVERIFIED at the precise-semantics level**: `getValue()` vs `getTrueValue()` vs
`getApiValue()` are three distinct getters — the naming strongly implies `getTrueValue()` is the
underlying persisted/TOML value ignoring any live API override, `getApiValue()` is whatever the last
API caller set, and `getValue()` is the effective (currently-in-effect) one, but I could not confirm
this from the `api` package's interfaces alone (no Javadoc survives compilation, and the
implementation lives in internal code I couldn't reach with GitLab down). **Capture the original with
whichever getter you intend to restore with, and restore via `setValue(original, "RigTune")` rather
than `clearValue()`** — `clearValue()` reads as "remove RigTune's override," which could resolve back
to a *different* value than what was live before RigTune touched it if another mod also holds an
override. Verify this empirically before shipping the benchmark.

**Persistence**: whether `setValue()` writes through to `DistantHorizons.toml` immediately, or only
in memory until the game next saves the file, is **UNVERIFIED** this session (would need either
source or an empirical test with the game running, and I was told not to launch Minecraft). Given
`renderingEnabled` has no TOML key at all (§1.1/§2) while `chunkRenderDistance` does, persistence
likely differs per field. Treat every `setValue()` call in the benchmark as "live for this session,
confirm-then-restore," not as "safe to leave changed and rely on TOML rewrite to fix it."

**Thread-safety**: `DhApi.isDhThread()` exists specifically to let external code check this, which
is itself evidence that DH's internals are **not** uniformly thread-safe to call into from an
arbitrary thread — treat all `DhApi.Delayed.*` calls as needing to happen on whatever thread DH
expects (client/render thread is the safe default assumption for a Fabric client mod; **UNVERIFIED**
which thread specifically, no Javadoc survived compilation to confirm).

---

## 4. DH tier recommendations

**No official DH "recommended settings by hardware tier" table was reachable this session** — DH's
own FAQ (blog.curseforge.com) gives only two hard numbers (both already used above): "8-12 is the
recommended max vanilla render distance" once DH is installed, and "at least 2-4 GB more RAM than
vanilla... for higher settings." DH's Quick-Options presets (`EDhApiQualityPreset`:
`MINIMUM, LOW, MEDIUM, HIGH, EXTREME` + `CUSTOM`; `EDhApiThreadPreset`:
`MINIMAL_IMPACT, LOW_IMPACT, BALANCED, AGGRESSIVE, I_PAID_FOR_THE_WHOLE_CPU` + `CUSTOM` — both
confirmed as real enums in the jar) exist for the in-game GUI, but **are not settable fields via the
API or the TOML** (no `qualityPreset()`/`threadPreset()` getter anywhere in `api/interfaces/**`, no
matching TOML key) — they're GUI sugar that presumably fan out to the individual fields internally,
and I could not reach the code that does that fan-out (GitLab down). **Do not treat the 1:5 count
match between these enums and RigTune's tier scale as more than a naming coincidence** — I'm using it
as a mnemonic for building §1.2's table, not claiming DH's own presets map onto RigTune's tiers.

Numbers actually used to build §1.2's table, from `distanthorizonsguide.com`'s settings guides
(third-party, cross-checked against the official FAQ's 8-12 vanilla-RD number, which matches):
- **Entry-level** ("Smooth Performance"): vanilla RD 8-10, LOD 64-96 chunks, quality Low.
- **Mid-range** ("Balanced Starting Point"): vanilla RD 8-12, LOD 96-128 chunks, quality Medium.
- **High-end**: the guide is explicitly vague here ("keep vanilla distance modest, raise LOD
  distance gradually," no hard numbers) — §1.2's tier-4/5 values (160/256) are **my own
  extrapolation**, anchored to the user's own live tier-5 config (`lodChunkRenderDistanceRadius=256`)
  as a real data point for "what a tuned enthusiast rig runs," not to a documented source. Flag as
  such if it goes into the shipped rules.
- **Laptop/integrated GPU**: "start around 64-128 chunks at low/medium quality," disable shaders
  initially, vanilla RD 8-12, prioritize stable frame pacing over max distance. This supports gating
  DH's LOD distance down (not just quality) for `gpuIntegrated: true`, on top of whatever the raw
  tier number says — RigTune's existing `tier = min(gpuTier, cpuTier, memTier)` already pulls
  integrated-GPU rigs down to a low raw tier in most cases, so this may already be handled implicitly;
  worth a spot-check rather than a new explicit `gpuIntegrated` condition.

**Vanilla RD cap sanity check (the brief's specific question)**: RigTune's current flat
`max: 12 when modPresent:[distanthorizons]` is directly supported by the FAQ's own "8-12" range, and
sits at the *permissive* end of it. §1.2 proposes tightening to 8 for tier ≤2, leaving 12 for tier
≥4 — this is a refinement, not a contradiction of the existing rule.

---

## 5. Iris config + API

**Versions** (Modrinth `iris`, project id `YL57xq9U`; confirmed via versions API, `loaders=fabric`):

| game version | latest Iris (fabric) | required Sodium |
|---|---|---|
| 26.2 | `1.11.4+26.2-fabric` | `mc26.2-0.9.2` |
| 26.3 | `1.11.6+26.3-fabric` | `mc26.3-0.9.2` |

(26.2 also has `1.11.2+26.2-fabric` needing Sodium `mc26.2-0.9.1` specifically, and older
1.11.0/1.11.1 builds — confirms the prior `knowledge.md` note that Iris/Sodium are pinned pairs, not
loose ranges.) DH 3.3.2's `fabric.mod.json` for the **26.2** build declares
`"breaks": {"iris": "<1.11.2"}`; the **26.3** build's `fabric.mod.json` (independently checked, same
jar family) does **not** carry that `breaks` entry at all — consistent with 26.3's Iris builds
starting at 1.11.5 regardless, so the constraint is moot there. Both DH jars declare Java `>=25`.

**Config file**: `config/iris.properties` — a plain `java.util.Properties` file (confirmed from
source, `IrisConfig.java`, `github.com/IrisShaders/Iris` branch `26.3`,
`common/src/main/java/net/irisshaders/iris/config/IrisConfig.java`). Exact keys, matching the user's
live file byte-for-byte:

| key | type | source field | notes |
|---|---|---|---|
| `shaderPack` | string (zip filename) or absent | `shaderPackName` | empty string / absent → internal (no) shaders |
| `enableShaders` | `"true"`/`"false"` | `enableShaders` | **default true** if key is missing — `load()` does `!"false".equals(...)`, so an absent or malformed key means *enabled*, not disabled |
| `allowUnknownShaders` | `"true"`/`"false"` | `allowUnknownShaders` | default false |
| `enableDebugOptions` | `"true"`/`"false"` | `enableDebugOptions` | default false |
| `disableUpdateMessage` | `"true"`/`"false"` | `disableUpdateMessage` | default false |
| `maxShadowRenderDistance` | int string | `IrisVideoSettings.shadowDistance` | default `32`; parse failure resets to 32 **and rewrites the whole file** (`save()`) |
| `colorSpace` | enum string | `IrisVideoSettings.colorSpace` | default `SRGB`; invalid value also triggers the same reset-and-rewrite |

There's also a companion `config/iris-excluded.json` (confirmed present in the user's profile too):
`{"excluded": [...]}`, a list of shader resource-location identifiers to skip — not a performance
knob, skip it for v0.2 rules.

**API**: `net.irisshaders.iris.api.v0.IrisApi`, source-verified, entry point
`IrisApi.getInstance()` (no Maven-visible constructor — it's a static singleton). No separate
"IrisApi"-only Maven artifact exists; mod authors `compileOnly` the full Iris jar via the Modrinth
Maven bridge, e.g.:
```gradle
dependencies {
    compileOnly "maven.modrinth:iris:gxZWWnKH"   // = 1.11.4+26.2-fabric
    // compileOnly "maven.modrinth:iris:bAdKrpw8" // = 1.11.6+26.3-fabric, for a 26.3 build
}
```
Exact calls the brief asked about:
```java
boolean inUse = IrisApi.getInstance().isShaderPackInUse();      // true only if a pack loaded AND compiled OK
IrisApiConfig cfg = IrisApi.getInstance().getConfig();
boolean enabled = cfg.areShadersEnabled();                       // "a pack is loaded", not necessarily rendering
cfg.setShadersEnabledAndApply(true /* or false */);
```
`isShaderPackInUse()` is the right check for "is Iris actively costing frames right now" — its
Javadoc is explicit that `areShadersEnabled()` can be true while a pack failed to compile and isn't
actually rendering.

**Toggle cost, confirmed from `IrisApiV0ConfigImpl.java` (the implementation, also fetched from
source)**:
```java
public void setShadersEnabledAndApply(boolean enabled) {
    config.setShadersEnabled(enabled);
    config.save();     // rewrites config/iris.properties on disk, synchronously, immediately
    Iris.reload();      // full shader pipeline reload: recompile/relink shaders, rebuild framebuffers
}
```
This is **not cheap** — it's the same code path as pressing "Apply" in the Iris GUI. For a benchmark:
toggle once per test point (not per frame), discard frames during/immediately after the reload before
measuring, and expect a visible hitch. It also means toggling shaders via the API **does** persist to
`iris.properties` immediately (unlike DH's `setValue()`, whose persistence is unverified — §3) — and
because `Properties.store()` rewrites the entire file and regenerates its own timestamp comment line,
any custom comments or formatting a human added to `iris.properties` by hand will be lost the next
time anything calls `save()`. (I observed this indirectly: the user's `iris.properties` mtime changed
between two reads this session with identical key/value content — consistent with something calling
`save()` and `Properties.store()` rewriting an equivalent file, timestamp line included.)

---

## 6. Shader-pack guidance

No shader pack's own Modrinth/CurseForge description page mentions Distant Horizons compatibility
directly (checked Complementary Reimagined, MakeUp - Ultra Fast, BSL — all silent on DH). DH support
is a technical property of the shader code (it needs dedicated `dh_terrain`/`dh_water`/`dh_shadow`
programs under the `compatibility` profile, and reads DH-specific uniforms like `dhMaterialId` —
confirmed from Iris's own shader-dev reference docs at `shaders.properties/current/reference/mod-support/distant_horizons/`),
so compatibility is tracked by third-party databases, not the packs' own listings. Cross-checked two
independent ones — `distanthorizonsguide.com/shaders` (dedicated DH fan site, 20-pack DB with a
low/medium/high/ultra impact rating per pack) and a widely-cross-referenced community gist
(`gist.github.com/Steveplays28/52db568f297ded527da56dbe6deeec0e`, 40+ packs) — and they agree on
every pack both cover:

| pack | DH support | perf impact | source |
|---|---|---|---|
| Complementary Reimagined | yes | medium | both |
| Complementary Unbound | yes | high | both |
| BSL Shaders (v8.2.0+) | yes | medium | both |
| Bliss | yes | medium | both |
| Solas Shader | yes | low | guide DB |
| Photon | yes | high | both |
| Sildur's Vibrant Shaders | **no** (per current guide DB) | — | guide DB; *note: an older, separate web mention claimed v1.54 added DH support — the dedicated compat DB is more current and more DH-specific, so trust "no" over the stale claim, but re-verify before hard-coding, since this is the one pack in this table with conflicting reports* |
| MakeUp - Ultra Fast | reported yes (added DH support "under Iris") | very low (built for low-end) | secondary listing sites (texture-packs.com, CurseForge listing text) only — **not** in either primary compat DB, so lower confidence than the rows above; treat as UNVERIFIED-but-plausible |

Simple tier guidance for `dh.*`/`iris.*` `AdviceRule` text, synthesized from the table above plus §4's
tier bands (not copied from a single source):
- **tier ≤2 or integrated GPU**: no shaders, or MakeUp - Ultra Fast / Solas (low-impact, DH-capable)
  only if the user wants them anyway — pair with a *reduced* DH LOD distance (§1.2's t1/t2 rows), not
  the tier's normal one, since shaders + DH stack cost.
- **tier 3**: BSL or Complementary Reimagined at its own Low/Medium in-shader profile.
- **tier 4-5**: any of the "yes"-rated packs; Complementary Unbound/Photon (high-impact) only at tier
  5.
- Don't recommend Sildur's Vibrant alongside DH given the current compat-DB "no."

---

## 7. RAM guidance (DH + shaders combined)

From DH's own FAQ (first-party, already the source RigTune's existing advice rules cite): "you can
expect it to use at least 2-4 GB more RAM than vanilla Minecraft would for higher settings," varying
with render distance and terrain. The existing (pre-v0.2) `docs/research/knowledge.md` already has a
sharper version of the same FAQ page's guidance, worth restating since it directly informs §1's
heap-band advice and I did not find a reason to revise it: **6 GB recommended baseline for DH,
6-12 GB for normal DH play, 16-20 GB with DH + shaders together**; VRAM scales with LOD distance too
(~4 GB VRAM at LOD radius 1024, so a benchmark or rule sweeping `chunkRenderDistance` up shouldn't
ignore VRAM headroom, only heap). RigTune's existing advice rules
(`ram-distant-horizons`: heapMb ≤5500 & ramMb ≥12000 → recommend 6GB+; `ram-distant-horizons-shaders`:
heapMb ≤7500 & ramMb ≥16000 → recommend ~8GB) are consistent with this and don't need correction from
this research pass.

**v0.2 opportunity, not yet actionable**: once RigTune can read `dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius`
from the live TOML (§1.1's new parser), the RAM advice could scale with the *actual configured* LOD
distance instead of just DH's presence/absence — e.g. only fire the strongest RAM warning when LOD
distance is already high AND heap is low, rather than on presence alone. Flagging as a natural
follow-up, not implementing here.

---

## 8. Safety notes for editing the files

### 8.1 DH's TOML: a real, verified quoting trap

DH's TOML writer does **not** use TOML's native numeric types for every numeric field. From the live
file: `numberOfThreads = 8` and `lodBiomeBlending = 3` are bare (correctly-typed) TOML integers, but
`threadRunTimeRatio = "1.0"`, `overdrawPrevention = "-1.0"`, `farFogMax = "1.0"`,
`brightnessMultiplier = "1.0"`, `heightFogBaseHeight = "80.0"` — every **double/float** field found in
the file — are written as **quoted strings**, not bare TOML floats. Enums (`verticalQuality`,
`generatorPlan`, etc.) are also quoted strings, same as you'd expect. A naive editor that assumes
"looks like a number → write it unquoted" will write `overdrawPrevention = -1.0` (unquoted) into a
field DH's parser expects as a quoted string, which will likely fail to parse or silently reset to
default on next load (not empirically tested — didn't launch the game — but the pattern is
unambiguous across every float-typed key in the file, so treat this as effectively confirmed).
**Concrete rule for any post-exit helper**: parse the existing line for a key, capture whether its
current value token is quoted, and preserve that quoting on write — never infer TOML type from
Java/Kotlin type. Equivalently: use a real TOML library that round-trips the file rather than
line-based regex substitution guessing at types.

### 8.2 Does DH rewrite the file on startup? — UNVERIFIED

Could not confirm from the `api` package alone, and GitLab (where `Config.java`/the TOML
read/write implementation lives) was down all session. Circumstantial evidence it *does* do some
form of versioned migration: the file's very first line is `_version = 4`, which is the shape of a
schema-version field a migrator would check. **Safe approach regardless of the answer**: a post-exit
helper should only ever modify the exact value token of an existing, already-present key
(read-modify-write of that one line), never add/remove/reorder keys or sections, and should tolerate
the file having been rewritten/reformatted by DH itself between runs (re-locate the key by name each
time, don't assume line numbers are stable).

### 8.3 Settings that need a restart vs. apply live

Scanned every in-file comment for "restart"/"reload": `databaseSyncMode` needs a **level restart**;
`blocksDontUseSideTextureCsv`, `blocksDontRenderTextureCsv`, `blockTagsDontUseSideTextureCsv`,
`blocksAlwaysRasterizeTextureCsv` need a restart; `dimensionEnabledCloudRenderingCsv` and
`beaconRenderHeight` need a **world re-load**; `renderingEngine` (Blaze3D/OpenGL choice) needs a
restart. Critically, **none of §1.2's proposed keys** (`lodChunkRenderDistanceRadius`,
`verticalQuality`, `horizontalQuality`, `maxHorizontalResolution`, `numberOfThreads`) carry a
restart/reload note in their comments — they read as live-applied, which is also consistent with them
being exposed as live-settable API `IDhApiConfigValue`s (§3). A post-exit TOML edit for these should
take effect on the very next world load with no extra restart needed; the API equivalents should be
safe to sweep live in a benchmark without a relaunch between points.

### 8.4 Iris's properties file

Plain Java `Properties` format, `ISO-8859-1` encoding with `\uXXXX` escapes for non-ASCII (per the
source's own comment — this matters if a shader-pack filename ever contains non-ASCII characters).
Any call to `save()` (including the API's `setShadersEnabledAndApply`) rewrites the **entire** file
via `Properties.store()`, which regenerates its own header/timestamp comment and does not preserve
any comments a human might have added by hand. A post-exit helper editing `iris.properties` directly
should write plain `key=value` lines with no assumptions about comment preservation, and should not
rely on `#`-comments in the file surviving the game's own next write.

---

## 9. UNVERIFIED items (collected)

- DH internal `Config.java`/TOML read-write implementation — GitLab outage blocked all access;
  everything about DH's actual parser behavior (§8.2's rewrite-on-startup question, exact
  `setValue()`→TOML persistence timing in §3) is inferred from the API's *shape* and the live file's
  *contents*, not read from source.
- `getValue()` vs `getTrueValue()` vs `getApiValue()` exact semantics (§3) — names only, no Javadoc
  or source reachable.
- Exact subscribe/listen call for `DhApiAfterDhInitEvent` from third-party code (§3) — the visible
  `api` package only shows `unbind`/`fireAllEvents` on the injector; the bind call is presumably on a
  parent interface in the internal `coreapi` package, not decompiled this session.
- Which thread `DhApi.Delayed.*` calls must run on (§3) — inferred only from the existence of
  `DhApi.isDhThread()` as a guard helper, not confirmed directly.
- DH's Quick-Options preset → individual-field mapping (§4) — confirmed the presets exist as enums,
  could not confirm what values they actually set.
- MakeUp - Ultra Fast's DH support (§6) — only from secondary listing sites, not from either primary
  DH-compat database.
- Sildur's Vibrant Shaders' current DH support (§6) — two sources disagree; went with the more
  DH-specific, more current one, but flagged.
- True factory-default values for every DH TOML key in §2 — table shows the user's live, already-tuned
  tier-5 config, not a verified fresh-install default.
