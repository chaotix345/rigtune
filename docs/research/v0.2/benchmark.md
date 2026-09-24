# RigTune v0.2.0: benchmark research

Research date 2026-09-25, for Minecraft Java 26.2 (unobfuscated Mojang names, javap'd live against
`minecraft-clientonly-deobf-26.2.jar` / `minecraft-common-deobf-26.2.jar` under
`C:/Users/Admin/.gradle/caches/fabric-loom/minecraftMaven/`) and 26.3 where noted. Everything under a
signature block was run through `javap -p` against those exact jars unless marked UNVERIFIED. Existing
v0.1.0 behaviour is read from `src/main/java/.../core/benchmark/`, `src/client/java/.../client/benchmark/`,
`docs/DESIGN.md`, and the gametest sources — cited by path, not re-derived.

## 1. Recommendations summary

1. **Build the deterministic world with production APIs, not the Fabric client-gametest API.**
   `fabric-client-gametest-api-v1` (`ClientGameTestContext.worldBuilder()` /`TestSingleplayerContext`) is
   confirmed absent from the shipped `fabric-api` fat jar (`docs/research/mc-api.md:801,1086`) and is only
   added as an explicit, test-scope-only Gradle dependency (`build.gradle:37-45,112-114`: `fabricApi.configureTests`
   for the `gametest` source set, and `productionSmokeMods fabricApi.module("fabric-client-gametest-api-v1", …)`
   for `runProductionSmoke`). It is **not** on `main`/`client`'s runtime classpath, so the shipped mod cannot
   call it. Use `Minecraft.createWorldOpenFlows()` → `WorldOpenFlows.createFreshLevel(...)` / `.openWorld(...)`
   instead — full signatures in §2. **This is also a real coverage gap worth flagging**: both
   `RigTuneClientGameTest` and `ProductionSmoke` (the "real client, real mods" smoke test) build their
   singleplayer world exclusively via `context.worldBuilder().create()` — the test-only shortcut. Nothing in
   the current test suite exercises `WorldOpenFlows` at all, so v0.2.0's deterministic-world feature will be
   the first code in this repo to touch that production path. Treat it as higher-risk and get at least one
   manual playtest before shipping (I can't launch Minecraft from here — see §10).
2. **Deterministic scene**: one dedicated save, fixed seed, NORMAL preset (not flat/void — see §2), fixed
   spawn/camera coordinates validated once by hand, gamerules frozen via the typed `GameRules` API (not
   `/gamerule` strings — see §2), weather forced off client-side every tick as a second line of defense.
3. **Measurement**: keep v0.1's chunk-settle gate, add a short discarded JIT/GC warm-up phase after it, widen
   the measurement window, and add a cheap 2-repeat/coefficient-of-variation check only on the *final* reported
   number (not during search — no time budget for full iterate-until-stable there). Numbers in §3.
4. **Multi-knob**: reuse `RenderDistancePlanner`'s binary search unchanged for render distance (dominant
   knob), then coordinate-descend on simulation distance and, if present, Distant Horizons LOD distance /
   shaders on-off, each with a 3-point local search, inside a hard wall-clock budget. Pseudocode in §5.
5. **Before/after**: same scene, same held-fixed knobs, report 1% low and avg gain %, but suppress the number
   below a noise floor derived from the repeat CV rather than always printing a precise-looking percentage.
6. **History**: `config/rigtune/benchmarks.json`, schema-versioned like `rules-v1.json`
   (`docs/DESIGN.md:87-110`), bounded to the last N runs — consistent with this codebase's existing
   bounded-everything approach to persisted/fetched data (`BoundedHttp`, remote rules cap; `docs/DESIGN.md:151,160`).
7. Doc path: `C:/Dev/Minecraft Setting Optimisation Mod/docs/research/v0.2/benchmark.md` (this file).

## 2. Deterministic scene design

### 2.1 Why NORMAL over flat/void

Flat/void is maximally cheap and removes terrain-complexity variance, but it's a poor proxy for what the
player actually plays on — near-zero overdraw, no biome mix, no cave geometry, so an RD tuned flat can
under-recommend for real worlds. NORMAL with a fixed seed at hand-picked coordinates is *equally*
deterministic once the seed, version, and installed datapacks are fixed — determinism comes from the seed
being fixed, not from the terrain being simple. Recommendation: NORMAL is the default scene; flat is not
offered in v0.2.0 (not worth the second code path for the goals in the brief).

### 2.2 World identity and reuse

```java
Minecraft mc = Minecraft.getInstance();
String levelId = "rigtune-benchmark";                       // fixed folder name under saves/
LevelStorageSource source = mc.getLevelSource();             // Minecraft.getLevelSource(), public
if (source.levelExists(levelId)) {
    mc.createWorldOpenFlows().openWorld(levelId, onOpenFailed);
} else {
    createFreshBenchmarkWorld(mc, levelId);
}
```
`LevelStorageSource.levelExists(String)` and `.isNewLevelIdAcceptable(String)` are both public instance
methods (javap-confirmed on `net.minecraft.world.level.storage.LevelStorageSource`). If the save is missing
(deleted, or a different MC version wiped/renamed it — see §2.5), regenerate rather than fail.

`WorldOpenFlows.openWorld(String, Runnable)` is public and javap-confirmed; the exact contract of the
`Runnable` (on-cancel vs on-failure) wasn't traced through bytecode — UNVERIFIED, see §10.

### 2.3 Creating the world (exact 26.2 signatures)

All of the following are javap-verified against the real jars, not recalled:

```java
// net.minecraft.client.Minecraft
public WorldOpenFlows createWorldOpenFlows();

// net.minecraft.client.gui.screens.worldselection.WorldOpenFlows
public void createFreshLevel(String levelId, LevelSettings settings, WorldOptions options,
    Function<HolderLookup.Provider, WorldDimensions> dimensionsGetter, Screen uiParent);

// net.minecraft.world.level.LevelSettings (a Record)
public LevelSettings(String levelName, GameType gameType,
    LevelSettings.DifficultySettings difficultySettings, boolean allowCommands,
    WorldDataConfiguration dataConfiguration);
public static final LevelSettings.DifficultySettings DEFAULT;   // on DifficultySettings itself
// DifficultySettings(Difficulty, boolean hardcore, boolean locked)

// net.minecraft.world.level.levelgen.WorldOptions
public WorldOptions(long seed, boolean generateStructures, boolean generateBonusChest);

// net.minecraft.world.level.levelgen.presets.WorldPresets
public static WorldDimensions createNormalWorldDimensions(HolderLookup.Provider);  // matches the Function<> param exactly

// net.minecraft.world.level.WorldDataConfiguration
public static final WorldDataConfiguration DEFAULT;   // no extra datapacks
```

Putting it together:

```java
private static final String LEVEL_ID = "rigtune-benchmark";
private static final long SEED = 8675309L;   // any fixed value works — determinism doesn't depend on which
                                              // seed, only that it never changes. Spawn coords below need one
                                              // manual check for "varied terrain" (I could not launch MC — see §10).
private static final BlockPos CAMERA_POS = new BlockPos(200, 70, 200);   // placeholder, needs validation

static void createFreshBenchmarkWorld(Minecraft mc, Screen uiParent) {
    LevelSettings settings = new LevelSettings(
        "RigTune Benchmark",
        GameType.CREATIVE,                                            // no hunger/damage confound, free flight
        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), // belt-and-suspenders vs. mobs
        true,                                                         // allowCommands, in case a command fallback is needed
        WorldDataConfiguration.DEFAULT);
    WorldOptions options = new WorldOptions(SEED, true, false);       // generateStructures=true (real-world-like), no bonus chest
    mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, options,
        WorldPresets::createNormalWorldDimensions, uiParent);
}
```

Once the level loads, freeze gamerules through the **typed** API (not `/gamerule <name> <value>` strings —
several of these were renamed at the Java level in 26.x, e.g. `doDaylightCycle` → `GameRules.ADVANCE_TIME`,
`doWeatherCycle` → `ADVANCE_WEATHER`, `doMobSpawning` → `SPAWN_MOBS`, `randomTickSpeed` → `RANDOM_TICK_SPEED`;
whether the *command-line* argument spelling also changed wasn't traced through bytecode, so don't rely on
guessed command strings — see §10):

```java
// net.minecraft.world.level.gamerules.GameRules — javap-confirmed
public <T> void set(GameRule<T>, T, MinecraftServer);

IntegratedServer server = mc.getSingleplayerServer();   // Minecraft.hasSingleplayerServer()/getSingleplayerServer(), confirmed
server.execute(() -> {                                  // the integrated server runs its own thread even in singleplayer
    GameRules rules = server.getGameRules();
    rules.set(GameRules.ADVANCE_TIME, false, server);
    rules.set(GameRules.ADVANCE_WEATHER, false, server);
    rules.set(GameRules.SPAWN_MOBS, false, server);
    rules.set(GameRules.SPAWN_MONSTERS, false, server);
    rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
});
```
`Minecraft.hasSingleplayerServer()`/`getSingleplayerServer()`, `MinecraftServer.execute(Runnable)`-style
server-thread marshalling, and `server.getCommands().performPrefixedCommand(...)` as a fallback are all
already documented, javap-confirmed, and explicitly recommended for singleplayer automation in
`docs/research/mc-api.md:965-987` ("recommended path for a benchmark harness: full server permissions, no
packet round trip") — reuse that pattern rather than re-deriving it.

No `LevelSettings`/`WorldOptions` field controls initial time-of-day (`LevelSettings` carries no `GameRules`
either — gamerule defaults live on the server, set post-creation as above). For a fixed daytime, either call
`ServerLevel.setDayTime(long)` (UNVERIFIED — not javap'd this pass, but this exact shape has existed for many
versions) or fall back to `performPrefixedCommand(stack, "time set 6000")` (command spelling also UNVERIFIED
for 26.2). For weather, skip the gamerule/command dependency entirely and force it client-side every
measurement tick, since it's a plain public setter with no server round trip:
```java
// net.minecraft.client.multiplayer.ClientLevel extends Level — javap-confirmed public methods
level.setRainLevel(0f);
level.setThunderLevel(0f);
```

### 2.4 Reuse and clean exit

- Reuse: `LevelStorageSource.levelExists(LEVEL_ID)` gate above. Never regenerate on a hit — same seed, same
  fixed coordinates, same terrain, every run on that machine.
- Exit: `Minecraft.clearClientLevel(Screen)` (public, javap-confirmed) disconnects/unloads the level and shows
  the given screen in one call — use it to return to `TitleScreen` (or straight to `BenchmarkResultScreen`)
  after the deterministic-world run, mirroring how v0.1's non-deterministic-world benchmark already restores
  everything and calls `minecraft.gui.setScreen(...)` on finish (`BenchmarkController.finish`,
  `client/benchmark/BenchmarkController.java:263-291`).
- The deterministic world is **optional** per the brief: when the player runs the benchmark from inside their
  own world (v0.1's flow), keep that path untouched — `BenchmarkController` already handles "benchmark in the
  player's current world" correctly; the deterministic-world path is an additional entry point, not a
  replacement.

### 2.5 Cross-run / cross-machine determinism, and the caveats

- **Same machine, same MC version**: fully deterministic. Same seed + same fixed coordinates + same
  datapacks (`WorldDataConfiguration.DEFAULT`, i.e. vanilla only) → bit-identical terrain every run.
- **Across MC versions (26.2 vs 26.3)**: `docs/research/knowledge.md:3` notes 26.3 was 9 days old at research
  time. Mojang does not guarantee worldgen stability across versions even for the same seed — this has
  changed before (e.g. the 1.18 terrain rewrite). **Do not assume 26.2 and 26.3 produce the same terrain for
  the same seed.** Record the MC version the save was generated under (already implicit in the save's level
  data) and regenerate the benchmark world if the running version differs from the recorded one, rather than
  silently reusing possibly-mismatched terrain.
- **Across machines, same MC version**: deterministic terrain generation itself should hold, but only if
  installed datapacks/mods that touch worldgen are identical (biome mods, TerraBlender-style overworld
  replacement, etc.). RigTune's install base (a Fabric performance-mod user) is unlikely to have these, but
  it isn't guaranteed — worth a one-line caveat in the UI ("comparisons are most meaningful on the same
  install") rather than a hard promise of cross-machine identical scenes.
- The exact seed (`8675309L` above) and coordinates (`200,70,200`) are placeholders. Validating that they
  land on genuinely varied terrain (some elevation change, not pure ocean/void) needs one manual playtest —
  I was told not to launch Minecraft or run Gradle game tasks, so this is flagged as a to-do, not done. Once
  picked, treat the constants as permanent — changing them later breaks history comparability (§7).

## 3. Measurement protocol

v0.1's gate — wait for chunk sections to compile, or a timeout — stays as the first phase; it's a correctness
gate ("is there anything to measure yet"), not a statistics phase, and Sodium already gives the best signal
for it (`SodiumWorldRenderer.isTerrainRenderComplete()`, preferred over vanilla `hasRenderedAllSections()`
per `docs/research/mc-api.md:485` and already implemented in `BenchmarkController.sectionsReady()`).

Proposed v2 phase sequence per candidate configuration:

| Phase | Duration | Purpose | Recorded? |
|---|---|---|---|
| Settle | until sections ready, timeout 20 s (unchanged from v0.1: `Config.DEFAULT.timeoutSeconds()`) | chunk build queue drains | No |
| Warm-up | 1.5 s | let the JIT tier up hot render-loop methods and let any GC from the settle phase finish | No, discarded |
| Measure | 8 s per camera phase (up from 6 s), same two-phase 360° sweep (level, then 25° down) as v0.1 | the actual sample | Yes |

**Why a separate discarded warm-up, and why short**: standard advice for noisy benchmarking is to warm up
until performance stabilizes before recording (Kalibera & Jones, *Rigorous Benchmarking in Reasonable Time*,
https://kar.kent.ac.uk/33611/45/p63-kaliber.pdf) and to watch for GC/JIT-driven variance
(https://medium.com/@mharshavardhan165/unlocking-java-performance-understanding-jvm-warm-up-jit-and-microbenchmarking-like-a-pro-88793493b577).
Unlike a cold-JVM microbenchmark that needs many iterations to hit JIT tier-up thresholds, Minecraft's render
loop at even a modest FPS pushes thousands of frames through the hot path in 1-2 seconds, and the JVM has
already been running (main menu, chunk loading) for tens of seconds before the benchmark starts — so a short,
fixed 1.5 s discard is proportionate, not a full JMH-style multi-iteration warm-up.

**Why 8 s not 6 s**: the brief asks for "longer, more robust" windows. 8 s at even 30 FPS is ~240 frames per
phase (480 total per RD candidate), comfortably enough for a stable 1% low (needs ≥100 frames to have a
non-degenerate slowest-1% bucket — `FrameStats.of`, `core/benchmark/FrameStats.java:22`, uses
`Math.max(1, n / 100)`, so under 100 frames the "1%" bucket is just the single worst frame, which is noisy).
8 s keeps the existing 6-step RD search under the time budget (§5 budget math) while giving 2× the samples of
v0.1's per-RD windows for the same reason CapFrameX recommends longer capture windows for stable percentiles
(https://www.capframex.com/blog/post/Explanation%20of%20different%20performance%20metrics).

**Repeats and stability**: full "repeat until the confidence interval is tight" (the approach in Kalibera &
Jones and in Android macrobenchmark stability criteria,
https://blog.p-y.wtf/statistically-rigorous-android-macrobenchmarks) isn't affordable inside a 2-4 minute
budget while also searching 4 knobs. Compromise: **single measurement per candidate during search** (as
v0.1 already does), but **2 repeats on the final chosen configuration** (both for a plain benchmark's reported
number and for each side of a before/after comparison — §6), with the coefficient of variation (CV =
stddev/mean) of the two 1% lows reported alongside the number. If CV exceeds a threshold (propose 5%,
matching common CV-based warm-up/stability thresholds in the literature above), surface it as "results were
noisy — consider closing background applications" rather than silently trusting one pair of samples.

**Outliers / GC pauses**: don't drop or clip outlier frames from the sample — a real stutter (GC pause, driver
hiccup) is exactly what 1% lows exist to surface, and both CapFrameX and GamersNexus treat the worst frames as
signal, not noise, for smoothness metrics
(https://tier1settings.com/what-are-1-percent-lows-in-gaming/,
https://gamersnexus.net/features/living-doc-current-test-bench-hardware-list-methodologies). Only the CV-based
repeat check above should flag a *whole run* as suspect, never per-frame filtering.

**Thermal throttling**: out of scope for a 2-4 minute in-game benchmark — GamersNexus's own throttling
protocol needs ~23 minutes to reach thermal equilibrium
(https://gamersnexus.net/guides/3477-case-fan-standardization-tests-noise-normalized-thermals). Document this
as a known limitation (§10) rather than attempting to detect it; a benchmark this short mostly avoids the
problem by not running long enough to heat-soak most systems in the first place.

## 4. Stats definitions

All formulas already match `core/benchmark/FrameStats.java` except where marked **(new)**.

- **avg FPS** = `n / (Σ frameTimeNanos / 1e9)` — harmonic-average-like via total time, not mean-of-per-frame-FPS (correct: avoids overweighting short frames).
- **1% low** (CapFrameX-style "1% low", mean-of-slowest-1%) = `1000 / mean_ms(slowest ceil(n·0.01) frames)`. Matches "the average frame rate of the slowest 1% of frames" (https://fpstest.pro/blog/1-percent-low-fps).
- **p99 frame time** (percentile-sample style) = the frame time at the 99th percentile of the *sorted ascending* frame-time array — a single-sample percentile, a stricter/different definition than the mean-of-slowest-1% above (CapFrameX distinguishes these two families of "1% low" explicitly: https://www.capframex.com/blog/post/Explanation%20of%20different%20performance%20metrics). Already both computed (`FrameStats.onePercentLowFps` vs `FrameStats.p99FrameMs`) — keep both in v2, they answer different questions (average worst-case vs. threshold worst-case).
- **0.1% low (new)**: same mean-of-slowest-bucket formula as 1% low, bucket size `max(1, n/1000)`.
- **median (new)**: `sorted[n/2]` for odd n, else average of the two middle samples — a plain robustness check against a skewed mean.
- **max frame time**: already present (`FrameStats.maxFrameMs`), keep.
- **coefficient of variation (new, cross-run only)**: `stddev(metric across repeats) / mean(metric across repeats)`, computed on the final-configuration repeats described in §3, not on in-window per-frame data.

## 5. Multi-knob tuning algorithm

One sentence: **tune render distance first with the existing binary-search planner (it dominates GPU/CPU
load), then coordinate-descend on the remaining knobs one at a time in priority order, each with a small
fixed-candidate local search around its current value while holding every other knob at whatever was already
chosen, inside a hard wall-clock budget that aborts the tail of the list (not the whole benchmark) if time
runs out.**

```
function tuneAll(minecraft, budgetSeconds = 180):
    deadline = now() + budgetSeconds
    rdResult = RenderDistancePlanner.run(...)      // unchanged v1 algorithm/class, up to 6 steps
    applyRenderDistance(rdResult.suggestedRd())

    knobs = [simulationDistanceKnob, dhLodDistanceKnob, dhShadersKnob]   // priority order; each self-reports applicable()
    results = {renderDistance: rdResult}
    for knob in knobs:
        if not knob.applicable(minecraft): continue          // e.g. DH knobs only when Distant Horizons is loaded
        remaining = deadline - now()
        if remaining < knob.minTimeNeeded(): break            // safety valve: stop tuning, leave the rest at default
        results[knob] = coordinateDescentStep(knob, holdFixed = results, timeBudget = min(remaining, knob.maxTime()))
    return results

function coordinateDescentStep(knob, holdFixed, timeBudget):
    start = now()
    candidates = knob.candidatesAround(knob.currentValue())   // e.g. [current-Δ, current, current+Δ], clipped to valid range
    best = knob.currentValue(); bestOnePercentLow = -infinity
    for v in candidates:
        if now() - start > timeBudget: break
        apply(knob, v)
        reapply(holdFixed)               // re-assert already-chosen knobs; nothing here should perturb them, but be defensive
        stats = measure(settle=short, warmup=1.5s, window=6s, singlePhase=true)  // no need for the two-pitch sweep for non-geometry knobs
        if stats.onePercentLowFps > bestOnePercentLow: best = v; bestOnePercentLow = stats.onePercentLowFps
    apply(knob, best)
    return best
```

**Budget math** (justifying "fits in 2-4 minutes"): RD search ≈ 6 steps × (settle ≤2s + warmup 1.5s + 2×8s
measure) ≈ 6 × 19.5s ≈ 117s worst case (fewer if it converges early, which `RenderDistancePlanner.next()`
already does — `core/benchmark/RenderDistancePlanner.java:33-54`). Each secondary knob: 3 candidates ×
(warmup 1.5s + 6s measure) ≈ 22.5s. Three secondary knobs ≈ 67.5s. Worst case ≈ 117 + 67.5 ≈ 185s ≈ 3.1 min,
inside the 2-4 min target; the wall-clock deadline in `tuneAll` is the hard stop if a machine is slower to
settle than expected.

**Simulation distance's effect on client FPS**: in singleplayer the `IntegratedServer` runs on its own thread
in the same JVM (`docs/research/mc-api.md:987`), so a higher simulation distance means more entity/chunk
ticking competing for CPU with the render thread — a real, measurable client FPS effect, especially on
CPU-limited/low-core-count hardware. `DESIGN.md:169` already documents that the integrated server copies the
client's *render* distance into its own view distance every tick; whether it does the same for
*simulation* distance was not independently bytecode-verified this pass (`simulationDistance`'s `OptionInstance`
listener wasn't walked in `docs/research/mc-api.md:182`, only inferred "very likely follows the same
Custom-flip-only pattern"). **On a remote multiplayer server, simulation distance should do nothing
client-side** — ticking happens entirely server-side and the client option is only a preference sent to the
server, which may ignore or cap it — but this wasn't independently verified either (no packet-level trace this
pass). Both are flagged as concrete follow-up verification items in §9/§10, not assumed.

## 6. Before/after flow and honest reporting

1. Run the full `tuneAll` (or a single-knob benchmark) once as "before" on the deterministic scene (or the
   player's current world, unchanged from v0.1) with whatever settings the player currently has.
2. Apply the recommended settings (same `SettingsBridge.applyVanilla(...)` path `BenchmarkResultScreen`
   already uses, `client/ui/BenchmarkResultScreen.java:54-56`).
3. Re-run the **same scene, same camera path, same repeat count** as "after" — critically, only the knobs
   RigTune tuned should differ between before/after; anything else (window size, background load, other
   settings) must be held identical or the comparison is meaningless.
4. Compute gain% = `(after.onePercentLowFps - before.onePercentLowFps) / before.onePercentLowFps * 100`
   (and the same for avg FPS, reported as a secondary number).
5. **Noise floor**: use the repeat-CV from §3 on both sides. If `|gain%|` is smaller than, say,
   `2 × max(before.cv, after.cv) × 100`, report "no significant change" instead of a specific percentage —
   don't print a precise-looking number derived from noise. This mirrors the general principle (not a specific
   tool) behind CV-gated stability checks in the benchmarking literature cited in §3 — decide significance
   from measured variance, not a single hardcoded percent.
6. Always show both 1% low gain and avg gain — a config that raises avg but drops 1% low (worse frame pacing)
   should not be reported as a clean win; show both numbers and let "1% low" be the headline metric, matching
   the existing result screen's emphasis (`BenchmarkResultScreen` already sorts/labels by 1% low, not avg).

## 7. `config/rigtune/benchmarks.json` schema

Modeled on the existing `rules-v1.json` convention (`schemaVersion`/monotonic identity, see
`docs/DESIGN.md:87-110`) and this codebase's bounded-everything philosophy (`BoundedHttp`,
`docs/DESIGN.md:151`):

```jsonc
{
  "schemaVersion": 1,
  "runs": [                                   // newest last; capped, see below
    {
      "id": "2026-09-25T14:03:00Z-a1b2",       // timestamp + short random suffix, sortable and unique
      "createdAt": "2026-09-25T14:03:00Z",
      "scene": { "kind": "deterministic", "levelId": "rigtune-benchmark", "seed": 8675309, "mcVersion": "26.2" },
                                                 // kind: "deterministic" | "playerWorld"
      "targetFps": 170,
      "phase": "before",                        // "before" | "after" | "single" (no before/after pair)
      "pairId": "2026-09-25T14:03:00Z-a1b2",    // shared between a before/after pair; null for "single"
      "knobs": {
        "renderDistance": { "value": 12, "avgFps": 244.1, "onePercentLowFps": 178.3, "p99FrameMs": 6.9, "cv": 0.03 },
        "simulationDistance": { "value": 8, "avgFps": 246.0, "onePercentLowFps": 179.1, "cv": 0.02 },
        "distantHorizonsLodDistance": { "value": 64, "avgFps": null, "onePercentLowFps": null }  // present only if applicable
      },
      "targetMet": true
    }
  ]
}
```
- **Bounded size**: cap `runs` at, e.g., 50 entries, trimming oldest first on write — same spirit as the
  remote-rules 2 MB cap and download byte caps elsewhere in this codebase (`docs/DESIGN.md:151,160`).
- **Versioned**: `schemaVersion` bump path mirrors `rules-v1.json`'s `revision` field; a reader that sees a
  newer `schemaVersion` than it understands should ignore the file (fresh start) rather than guess at parsing
  it, matching `RulesLoader`'s general newest-wins/fallback posture (`docs/DESIGN.md:31`).
- `pairId` links a before/after pair for the chart (§8) and for the honest-reporting gain% in §6 without
  needing to store the delta twice.

## 8. Chart design

`GuiGraphicsExtractor` (§7 of `docs/research/mc-api.md`) has `fill`, `fillGradient`, `outline`,
`horizontalLine`, `verticalLine` — no connected-line/path primitive. `BenchmarkResultScreen` already uses
`graphics.fill(x1, y1, x2, y2, color)` for its table row highlight (`client/ui/BenchmarkResultScreen.java:78,87`),
so a **bar chart**, not a line graph, is the cheapest fit for the existing rendering vocabulary and this
screen's established style.

Proposed: a small strip (~200×40 px) on `BenchmarkResultScreen` showing the last N (8-10) history entries for
the current knob/scene, two bars per run (avg FPS in one color, 1% low in another, like the existing
`COLOR_PASS`/`COLOR_WARN` palette), height scaled to the max value in the visible window:
```java
int barX = left + i * barWidth;
int avgH = (int) (maxBarHeight * (run.avgFps() / windowMax));
int lowH = (int) (maxBarHeight * (run.onePercentLowFps() / windowMax));
graphics.fill(barX, baseline - avgH, barX + barWidth / 2 - 1, baseline, COLOR_AVG);
graphics.fill(barX + barWidth / 2, baseline - lowH, barX + barWidth, baseline, COLOR_LOW);
```
Before/after pairs (`pairId`) can be drawn as adjacent bar pairs with a thin `outline` around the pair, or
simply rely on chronological order — the history is already time-ordered, so pairs naturally sit next to each
other without extra bookkeeping.

## 9. Testing strategy

- **The v0.1 gametest pitfall is specific to running the benchmark logic *inside* a client game test, not to
  the benchmark itself.** `docs/DESIGN.md:170` documents that the harness syncs with the test thread on every
  tick frame, making ~1% of frames artificially slow (avg 1914, 1% low 234 on an RX 7800 XT) — this is a test
  harness artifact, and it will affect v2's multi-knob and before/after numbers the same way if tested the
  same way. **Recommendation**: for any client-game-test assertion involving 1% low or 0.1% low under v2,
  assert structure and direction, not magnitude — e.g. "a before/after pair produced two non-empty
  measurements", "the chosen render distance lies within [MIN_RD, maxRd]", "gain% is present or explicitly
  'no significant change'", not "gain% > 10". `RigTuneClientGameTest` already does this correctly for v0.1
  (it checks `outcome.result().measurements().stream().allMatch(m -> m.stats().frames() > 0)`, not specific
  FPS values — `gametest/RigTuneClientGameTest.java:154`) — keep that pattern.
- **The harness resets render distance to 5 (and clouds off) after options.txt loads**, per
  `ProductionSmoke.java:97-98`. v2 tests must read whatever the harness reset to at test start (as
  `RigTuneClientGameTest` already does: `int rdBefore = context.computeOnClient(mc -> mc.options.renderDistance().get())`,
  not hardcode an expected starting value) rather than assume a baseline.
- **New verification needed, not previously covered**: whether `IntegratedServer` mirrors the client's
  `simulationDistance` into its own simulation distance every tick, the same way `DESIGN.md:169` documents it
  does for render distance. Write an explicit gametest assertion for this (set `simulationDistance` client-side,
  tick, read back the server's effective simulation distance) rather than assuming symmetry with render
  distance.
- **`WorldOpenFlows`/`Minecraft.createWorldOpenFlows()` has zero existing test coverage** (§1) — the gametest
  world-builder shortcut used everywhere today (`RigTuneClientGameTest.java:113`, `ProductionSmoke.java:79`)
  is a different, test-only API. A gametest asserting the deterministic-world feature would have to either (a)
  call the real `WorldOpenFlows` path inside a client game test and hope it behaves the same as in production
  (unverified — the gametest environment may not support real world creation/loading the same way), or (b)
  accept that this path is exercised only by manual/production-smoke testing. Given `ProductionSmoke` already
  exists specifically to run a *real* client, extending it (still via `worldBuilder().create()` today) to
  additionally exercise the deterministic-world creation path once, manually verified, is a reasonable
  interim option — but this needs a human (or CI) to actually launch the game at least once; I could not do
  this from here.
- Existing restoration-correctness tests (Esc cancels and restores everything, frame settings restored,
  render distance restored, camera restored — `RigTuneClientGameTest.java:131-164`) generalize directly to
  the new knobs: assert simulation distance / DH knobs are restored to their pre-benchmark values on cancel
  and on finish, the same way render distance and frame-limit settings already are.

## 10. UNVERIFIED items (need follow-up before/while implementing)

1. **Exact seed/coordinates for "varied terrain"** — `8675309L` at `(200,70,200)` are placeholders; need one
   manual playtest to confirm real elevation/biome variety there. I was told not to launch Minecraft.
2. **`WorldOpenFlows.openWorld(String, Runnable)`'s exact Runnable semantics** (on-cancel vs on-failure) — not
   traced through bytecode.
3. **`ServerLevel.setDayTime(long)`'s existence/signature in 26.2** — not javap'd this pass; the command
   fallback (`/time set <ticks>`) is a safer bet but its exact 26.2 argument spelling also wasn't traced.
4. **Whether `/gamerule <name> <value>` command argument spellings changed alongside the internal
   `GameRules.ADVANCE_TIME`-style Java renames** — use the typed `GameRules.set(...)` API (§2.3) instead of
   guessing command strings.
5. **Whether `IntegratedServer` mirrors client `simulationDistance` into server-side simulation distance every
   tick**, the way it's documented to do for render distance (`DESIGN.md:169`) — not independently verified;
   needs the gametest assertion proposed in §9.
6. **Whether `simulationDistance` genuinely has zero client-FPS effect on a remote multiplayer connection** —
   reasoned from the client/server split, not packet-level verified.
7. **Cross-version (26.2 → 26.3) terrain determinism for the same seed** — no guarantee found; mitigation
   (record MC version, regenerate on mismatch) proposed in §2.5, not itself a determinism guarantee.
8. **`WorldOpenFlows`/`WorldCreationContext`'s exact plumbing for non-NORMAL presets** (e.g. how to get a
   `WorldDimensions` for `WorldPresets.FLAT` outside the `CreateWorldScreen` UI flow) — not needed since §2.1
   recommends NORMAL only, but flagged in case flat is revisited later.
9. **Distant Horizons LOD distance / Iris shader toggle APIs** — explicitly out of scope for this doc per the
   task brief (another agent's research); §5's algorithm treats them as opaque `applicable()`/`apply(value)`
   knobs to be filled in once that research lands.
10. **Thermal throttling** — not detected or mitigated; documented as a known limitation in §3 given the
    2-4 minute budget is far shorter than typical thermal-equilibrium test protocols.
