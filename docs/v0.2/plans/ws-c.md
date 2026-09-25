# WS-C: Benchmark v2 Implementation Plan

> **For agentic workers:** executed inline by the WS-C agent with superpowers:test-driven-development per task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Benchmark v2 (SPEC item 6 as amended by M16): tune render distance (RD) and simulation distance (SD) under a hard 5 min deadline, report the cost of Distant Horizons (DH) rendering and of shaders, a Measure mode with before/after pairs and an honest gain line, a `benchmarks.json` history with a chart, and (if the spike succeeds) a dedicated `rigtune-benchmark` world.

**Architecture:** All decisions live in pure `core/benchmark` classes driven by `next()`/`record()` like the existing `RenderDistancePlanner`, so they are unit-tested with a fake clock and a fake frame source. `client/benchmark/BenchmarkController` becomes a thin tick-driven executor of core `Step`s (settle, warm-up, camera sweeps). DH and Iris are touched only through `client/compat/DhCompat` and `IrisCompat`, which compile against compileOnly APIs and are only loaded when `FabricLoader.isModLoaded(...)` says so. A restore marker (`config/rigtune/benchmark-restore.json`) covers crashes.

**Tech Stack:** Java 25, Fabric Loom 1.17 (no mappings, Mojang names), Stonecutter 0.9.8 (VCS version 26.2), Gson, JUnit 5, Fabric client game tests.

**Spec:** docs/v0.2/SPEC.md item 6 + "Amendments from the plan review" (item 6); docs/v0.2/PLAN.md WS-C + "Plan-review fixes by workstream"; docs/research/v0.2/benchmark.md; docs/research/v0.2/dh-iris.md §3, §5; docs/v0.2/plan-review.md M16, M-risk, L6, L10.

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-bench` on `feat/benchmark-v2`; rebase onto `origin/feat/v0.2.0` at the end. Never commit while Stonecutter is switched to 26.3.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for 26.2 and 26.3.
- `core/` has no Minecraft imports. Hotspot files (RealController, RigTuneClient, en_us.json, gametest fabric.mod.json, build.gradle, versions/*/gradle.properties) get surgical, delegating edits only.
- Tuned knobs: RD and SD only; SD in singleplayer only, with the quick protocol (settle ≤ 2 s, one 6 s phase). DH (`renderingEnabled` off, API-only) and shaders off are cost REPORTS, never applied, always restored. Hard deadline 5 min (300 s).
- DH/Iris: compileOnly APIs; compat classes load only when the mod is loaded; calls on the render thread; restore marker written before the first DH/Iris change, deleted after a successful restore, and restored at the next start if present.
- Scene names are the enum names `CURRENT` / `BENCHMARK_WORLD` everywhere (ClientSettings, history, BenchmarkSummary) (L6).
- Language keys: only `rigtune.benchmark.*`, inserted in alphabetical order.
- One Minecraft client machine-wide: game tests only while holding `C:/Dev/Worktrees/.gametest-lock` (atomic `mkdir`, `owner.txt`), removed after confirming the client exited.
- Keep the v0.1 API surface that `RigTuneClientGameTest` uses (`BenchmarkController.start(Minecraft, Config)`, `Config(int, double, double, double)`, `running()`, `cancel()`, `lastOutcome()`, `Outcome.result()/cancelled()`, `BenchmarkResultScreen`, the `rigtune.benchmark.keep` button leaving RD alone) so that test compiles and passes unchanged.
- Cut order if time runs short: benchmark-world, then the shader cost report, then the DH cost report, then the chart (keep the gain line).
- Commit messages end with the two attribution lines from PLAN.md.

## File structure

| file | responsibility |
|---|---|
| core/benchmark/Protocol.java (new) | settle/warm-up/sweep durations of one measurement; worst-case seconds |
| core/benchmark/Timing.java (new) | every duration of a run (full + quick protocols, repeats, deadline) |
| core/benchmark/Knobs.java (new) | RD, SD, DH rendering on/off, shaders on/off |
| core/benchmark/Step.java (new) | one measurement to take: kind, knobs, protocol |
| core/benchmark/BenchmarkMath.java (new) | aggregate of repeats, CV, noisy flag, gain %, significance, percent formatting |
| core/benchmark/SessionResult.java (new) | everything a finished (or cancelled) session knows |
| core/benchmark/BenchmarkSession.java (new) | the step planner: RD → SD → repeats → DH/shader cost reports; MEASURE mode; deadline |
| core/benchmark/KnobGuard.java (new) | applies knob changes through an Applier and restores the original exactly once |
| core/benchmark/BenchmarkRun.java (new) | session + guard + clock: advance/record/cancel/fail; restores on every exit path |
| core/benchmark/RestoreMarker.java (new) | `benchmark-restore.json` read/write and the at-start restore with fake targets |
| core/benchmark/BenchmarkRecord.java (new) | one `benchmarks.json` run (schema per benchmark.md §7 + version/mode/scene) |
| core/benchmark/BenchmarkHistory.java (new) | load/save `benchmarks.json` (cap 50, corrupt → `.bad`, newer schema → empty, atomic write), pairing and chart queries |
| core/benchmark/BenchmarkRecords.java (new) | SessionResult + metadata → BenchmarkRecord; record → BenchmarkSummary |
| client/benchmark/BenchmarkController.java (rewrite) | tick-driven executor of core steps; environment (uncap, HUD, flight, camera); v0.1 API kept |
| client/benchmark/ClientKnobs.java (new) | KnobGuard.Applier: vanilla RD/SD in memory, DH/Iris via compat, marker before the first DH/Iris change |
| client/benchmark/BenchmarkStore.java (new) | in-memory `BenchmarkHistory` for the client, saved after each run |
| client/benchmark/BenchmarkWorld.java (new, spike) | create/open/set up/exit the `rigtune-benchmark` world |
| client/benchmark/MarkerRestore.java (new) | polls the restore marker at client start until DH/Iris are ready |
| client/compat/DhCompat.java, IrisCompat.java (new) | the only classes that reference DH/Iris API types |
| client/compat/OptionalMods.java (new) | `isModLoaded` gates; never references DH/Iris types |
| client/ui/BenchmarkMenuScreen.java (replace stub) | scene choice, Tune / Measure before / Measure after |
| client/ui/BenchmarkResultScreen.java (rewrite) | headline, noise, gain line, costs, RD table, chart; Use (records via ChangeRecorder) / Keep current |
| client/RealController.java (hotspot) | `startBenchmark(BenchmarkRequest)`, `latestBenchmark()` |
| build.gradle + versions/*/gradle.properties (hotspot) | compileOnly DH API 7.2.0 and Iris per MC version |
| en_us.json (hotspot) | `rigtune.benchmark.*` keys |
| gametest BenchmarkGameTest.java (new) + gametest fabric.mod.json (hotspot) | AC6.2/AC6.3 on both versions, screenshots |
| gametest BenchmarkSmoke.java (new) + one gated line in ProductionSmoke | AC6.4 with the player's DH in a production run (`-Drigtune.smoke.benchmark=true`) |

---

### Task 1: SPIKE the benchmark world (WorldOpenFlows in a game test)

**Files:**
- Create: `src/client/java/io/github/chaotix345/rigtune/client/benchmark/BenchmarkWorld.java`
- Create: `src/gametest/java/io/github/chaotix345/rigtune/gametest/BenchmarkGameTest.java`
- Modify: `src/gametest/resources/fabric.mod.json` (one entrypoint line)

**Interfaces (produces):**
```java
public final class BenchmarkWorld {
    public static final String LEVEL_ID = "rigtune-benchmark";
    public static final long SEED = 8675309L;            // verified by screenshot in this task
    public enum State { IDLE, OPENING, SETTING_UP, READY, FAILED }
    /** From a screen with no world loaded: opens the world (creating or recreating it first). False if not possible. */
    public static boolean open(Minecraft mc, Screen parent);
    public static State state();
    /** Called every client tick (from BenchmarkController.tick). */
    public static void tick(Minecraft mc);
    /** Saves and leaves the world, ending on the title screen. */
    public static void exit(Minecraft mc);
    static boolean needsRecreate(@Nullable String recordedMc, String runningMc); // unit-testable rule
}
```
Verified signatures (javap, identical on 26.2 and 26.3): `Minecraft.createWorldOpenFlows()`, `WorldOpenFlows.createFreshLevel(String, LevelSettings, WorldOptions, Function<HolderLookup.Provider, WorldDimensions>, Screen)`, `WorldOpenFlows.openWorld(String, Runnable)`, `LevelSettings(String, GameType, LevelSettings.DifficultySettings, boolean, WorldDataConfiguration)`, `WorldOptions(long, boolean, boolean)`, `WorldPresets.createNormalWorldDimensions`, `LevelStorageSource.levelExists/createAccess`, `LevelStorageAccess.deleteLevel()`, `GameRules.set(GameRule<T>, T, MinecraftServer)` with `ADVANCE_TIME/ADVANCE_WEATHER/SPAWN_MOBS/SPAWN_MONSTERS/SPAWN_PHANTOMS/RANDOM_TICK_SPEED`, `Minecraft.disconnectFromWorld(Component)` (Save and Quit: saves, then sets TitleScreen), `Level.getHeight(Heightmap.Types, int, int)`. Time differs between versions (world clocks), so time and weather use commands: `time set noon`, `weather clear` via `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), ...)`; the teleport is `tp @a x y z yaw pitch`.

- [ ] **Step 1:** `BenchmarkWorld`: version marker `saves/rigtune-benchmark/rigtune-benchmark.json` (`{"mcVersion":"26.2","seed":8675309}`); `open()`: if the level exists and the marker's mcVersion equals the running one → `openWorld(LEVEL_ID, onCancel → FAILED)`; otherwise delete it (`createAccess(LEVEL_ID).deleteLevel()`) and `createFreshLevel(LEVEL_ID, new LevelSettings("RigTune Benchmark", GameType.CREATIVE, new DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT), new WorldOptions(SEED, true, false), WorldPresets::createNormalWorldDimensions, parent)` and write the marker once loaded. `tick()`: OPENING → when `mc.level != null && mc.player != null && mc.getSingleplayerServer() != null` run on the server thread: gamerules, `time set noon`, `weather clear`, surface height `server.overworld().getHeight(MOTION_BLOCKING, X, Z)`, `tp @a X (h+CAMERA_ABOVE) Z 0 0` → SETTING_UP → when the client player is within 1 block of the target and `mc.gui.screen() == null` → READY. Timeout 60 s → FAILED.
- [ ] **Step 2:** `BenchmarkGameTest.spike`: title screen → `BenchmarkWorld.open` → wait READY (≤ 2400 ticks) → wait chunks → screenshot `bw-created` → `exit` → wait TitleScreen and `mc.level == null` → assert the save and marker exist → `open` again → READY → assert no recreation happened (the marker's file time unchanged) → screenshot `bw-reused` → `exit` → TitleScreen.
- [ ] **Step 3:** Take the lock, run `:26.2:runClientGameTest`, look at both screenshots (varied terrain: not ocean, not flat), release the lock. If the terrain is poor, pick other X/Z from the screenshot run's log (print a 5×5 height sample) and rerun once.
- [ ] **Step 4:** Take the lock, run `:26.3:runClientGameTest` (retry up to 3 times on the known native sound-start crash), look at the screenshots, release.
- [ ] **Step 5 (decision):** Reliable on both (two consecutive green runs each without hangs) → keep BENCHMARK_WORLD. Otherwise: `BenchmarkWorld.supported()` returns false, the menu hides the scene choice, the game test keeps only a `CURRENT` path, and `docs/v0.2/design/ws-c.md` says why. Commit either way: `feat(benchmark): benchmark world spike (WorldOpenFlows)`.

### Task 2: Measurement protocol, timing and statistics (core)

**Files:**
- Create: `core/benchmark/Protocol.java`, `Timing.java`, `BenchmarkMath.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/benchmark/ProtocolTest.java`, `BenchmarkMathTest.java`

**Interfaces (produces):**
```java
public record Protocol(double settleMinSeconds, double settleTimeoutSeconds, double warmupSeconds, List<Sweep> sweeps) {
    public record Sweep(double seconds, float pitch) {}
    public double worstCaseSeconds();          // settleTimeout + warm-up + Σ sweeps
    public double measuredSeconds();           // Σ sweeps
}
public record Timing(int maxRdSteps, double sweepSeconds, double settleSeconds, double settleTimeoutSeconds,
        double warmupSeconds, double quickSeconds, double quickSettleTimeoutSeconds, int repeats, double deadlineSeconds) {
    public static final Timing DEFAULT = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 2, 300.0);
    public Protocol full();   // settle ≥settleSeconds & ≤settleTimeout, warm-up, level 8 s then 25° down 8 s
    public Protocol quick();  // settle ≤quickSettleTimeout (no minimum), warm-up, one level quickSeconds sweep
}
public final class BenchmarkMath {
    public static final double NOISY_CV = 0.05;
    public record Aggregate(double avgFps, double onePercentLowFps, double p99FrameMs, int repeats, @Nullable Double cv) {}
    public record Gain(double lowPercent, double avgPercent, boolean significant) {}
    public static @Nullable Double cv(double... values);        // sample stddev / mean; null if < 2 values or mean ≤ 0
    public static @Nullable Aggregate aggregate(List<FrameStats> repeats); // means; cv of the 1% lows; null if empty
    public static boolean noisy(@Nullable Double cv);            // cv > 5%
    public static double gainPercent(double before, double after); // (after − before) / before × 100
    public static Gain gain(Aggregate before, Aggregate after);  // significant iff |low gain| ≥ 2 × max(cvB, cvA) × 100, a missing cv counting as NOISY_CV
    public static String percent(double value);                  // "+12.3%", "-3.0%", "+0.0%"
}
```

- [ ] **Step 1: failing tests** — `ProtocolTest`: `defaultFullWorstCaseIs37point5`, `defaultQuickWorstCaseIs9point5`, `fullHasLevelThenDownSweeps`, `quickHasNoSettleMinimum`. `BenchmarkMathTest`: `cvOfTwoValuesUsesSampleStddev` (100, 110 → 0.06734 ± 1e-4), `cvNeedsTwoValues`, `cvOfZeroMeanIsNull`, `aggregateAveragesRepeats`, `aggregateOfOneHasNoCv`, `noisyAboveFivePercent` (0.05 → false, 0.0501 → true, null → false), `gainPercent` (100 → 112 = 12.0), `gainBelowNoiseFloorIsNotSignificant` (cv 0.03/0.02, gain 5% < 6% → false), `gainAtNoiseFloorIsSignificant` (6% → true), `missingCvCountsAsFivePercent` (9% → false, 10% → true), `negativeGainCanBeSignificant`, `percentFormatting` ("+12.3%", "-3.0%", "+0.0%" for −0.04, Locale.ROOT).
- [ ] **Step 2:** run `./gradlew :26.2:test --tests "*ProtocolTest" --tests "*BenchmarkMathTest"` → FAIL (compile).
- [ ] **Step 3:** implement.
- [ ] **Step 4:** rerun → PASS. Commit `feat(benchmark): v2 measurement protocol and statistics`.

### Task 3: Coordinate descent session under a deadline, with restore on every exit path (core)

**Files:**
- Create: `core/benchmark/Knobs.java`, `Step.java`, `SessionResult.java`, `BenchmarkSession.java`, `KnobGuard.java`, `BenchmarkRun.java`
- Test: `BenchmarkSessionTest.java`, `BenchmarkRunTest.java` (+ a `FakeRig` helper in the test folder: knobs → FrameStats, fake clock advancing by a step's worst case or a given settle time)

**Interfaces (produces):**
```java
public record Knobs(int renderDistance, int simulationDistance, boolean dhRendering, boolean shaders) {
    public Knobs withRenderDistance(int rd); public Knobs withSimulationDistance(int sd);
    public Knobs withDhRendering(boolean on); public Knobs withShaders(boolean on);
}
public record Step(Kind kind, Knobs knobs, Protocol protocol) {
    public enum Kind { RENDER_DISTANCE, SIMULATION_DISTANCE, REPEAT, BASELINE, DH_OFF, SHADERS_OFF }
}
public record SessionResult(BenchmarkRequest.Mode mode, Knobs original, Knobs chosen, double targetFps,
        PlannerResult renderDistance, List<Measured> measurements, @Nullable BenchmarkMath.Aggregate result,
        @Nullable Cost dhCost, @Nullable Cost shaderCost, boolean deadlineHit) {
    public record Measured(Step step, FrameStats stats) {}
    public record Cost(double baselineLow, double baselineAvg, double offLow, double offAvg) {
        public double lowGainPercent(); public double avgGainPercent();
    }
    public boolean targetMet(); // TUNE: renderDistance.targetMet(); MEASURE: result != null && result.onePercentLowFps() >= targetFps
}
public record TuneLimits(int minRd, int maxRd, double targetFps, boolean simulationTunable, int minSd) {} // nested in BenchmarkSession
public final class BenchmarkSession {
    public static final double SD_MIN_IMPROVEMENT = 0.05;
    public static BenchmarkSession tune(Knobs original, TuneLimits limits, Timing timing, long startNanos);
    public static BenchmarkSession measure(Knobs original, double targetFps, Timing timing, long startNanos);
    public Optional<Step> next(long nowNanos); // the same step until it is recorded; empty when done
    public void record(Step step, FrameStats stats);
    public boolean done(); public Knobs original(); public SessionResult result();
}
public final class KnobGuard {
    public interface Applier { void apply(Knobs from, Knobs to) throws Exception; }
    public KnobGuard(Knobs original, Applier applier);
    public void set(Knobs target) throws Exception; // no-op when equal to current
    public boolean restore();                        // once; true if the original was reapplied without error
    public boolean restored(); public Knobs current();
}
public final class BenchmarkRun {
    public BenchmarkRun(BenchmarkSession session, KnobGuard guard, LongSupplier nanoClock);
    public Optional<Step> advance();       // next step with its knobs applied; empty → finished (restored)
    public void record(FrameStats stats);  // for the step advance() returned
    public void cancel();                  // restores; cancelled() = true
    public void fail(Throwable error);     // restores; cancelled() = true, error() = error
    public boolean finished(); public boolean cancelled(); public @Nullable Throwable error();
    public SessionResult result(); public @Nullable Step current();
}
```
Session rules:
- RD: `RenderDistancePlanner(minRd, maxRd, original.rd, targetFps, maxRdSteps)` with the full protocol, SD/DH/shaders at the original values. Chosen RD = `planner.result().suggestedRd()`, or the original RD when nothing was measured.
- SD (only when `simulationTunable`): candidates = distinct `[sd, sd−2, sd−4]` that are ≥ `minSd`, in that order, measured at the chosen RD with the quick protocol; skipped when fewer than 2 candidates. Stop at the first candidate whose 1% low ≥ target (that is the chosen SD). If none meets it: the best 1% low if it beats the current SD's by ≥ 5% (`SD_MIN_IMPROVEMENT`), else the current SD.
- REPEAT: `timing.repeats` full-protocol measurements of the chosen knobs. `result` = aggregate of the repeats; without repeats, the last full-protocol measurement of the chosen knobs (RD step), else the last quick one; cv then null.
- Cost reports (after the repeats), only when the original has `dhRendering` / `shaders` on: a quick baseline of the chosen knobs (reused from the SD step when that exact knob set was measured), then `DH_OFF` (chosen with DH rendering off), then `SHADERS_OFF` (chosen with shaders off).
- MEASURE: `repeats` full-protocol measurements of the original knobs; chosen = original.
- Deadline: a step is only started when `elapsed + step.protocol().worstCaseSeconds() ≤ deadlineSeconds`; otherwise that stage ends (`deadlineHit = true`) and the next stage is tried (a later stage's shorter step may still fit). The baseline only starts when baseline + one report fit.

- [ ] **Step 1: failing tests** — `BenchmarkSessionTest`:
  `rdComesFirstThenSdThenRepeatsThenCosts` (kinds in order, with DH and shaders on);
  `rdSearchMatchesThePlanner` (same RD sequence as a bare `RenderDistancePlanner` on the same fake);
  `sdSkippedWhenNotTunable` (multiplayer); `sdSkippedAtMinimum` (sd = 5 → one candidate);
  `sdStopsAtFirstThatMeetsTarget` (current meets → only one SD step, chosen = current);
  `sdLowersWhenLowerMeetsTarget`; `sdKeepsCurrentWhenGainBelowFivePercent`; `sdPicksBestWhenGainAtLeastFivePercent`;
  `sdCandidatesClampedToMinimum` (sd 8 → [8, 6] with minSd 5 → sd−4 = 4 dropped);
  `repeatsAggregateWithCv`; `resultFallsBackToRdMeasurementWithoutRepeats`;
  `costReportsOnlyForActiveFeatures` (DH off originally → no DH_OFF step);
  `costBaselineReusesSdMeasurement`; `costComputedFromBaselineAndOff`;
  `measureModeRepeatsOriginal`;
  `worstCaseRdAndSdFinishWithinDeadline` (fake clock advances by each step's worst case; 6 RD steps + 3 SD steps all run; total ≤ 300 s; AC6.1);
  `deadlineCutsTheTailNotTheWholeRun` (deadline 120 s: RD steps run, repeats partly, `deadlineHit`, no step would have crossed the deadline);
  `stepNeverStartsPastDeadline` (property over deadlines 0..400 s: every started step ends ≤ deadline);
  `nextReturnsSameStepUntilRecorded`.
  `BenchmarkRunTest` (fake Applier recording calls, fake clock):
  `advanceAppliesStepKnobs`; `finishRestoresOnce` (restore after the last step; a later `cancel()` doesn't reapply);
  `cancelMidStepRestores`; `failRestoresAndKeepsError`; `applyExceptionFailsAndRestores` (the applier throws on the DH step → run failed, original restored);
  `restoreExceptionIsReportedNotThrown` (applier throws on restore → `restore()` false, run still finished);
  `restoreRunsEvenIfNothingChanged` is NOT required: `KnobGuard.restore()` on unchanged knobs applies nothing (assert zero calls).
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement `Knobs`, `Step`, `SessionResult`, `BenchmarkSession` (stage enum RD, SD, REPEAT, COSTS, DONE), `KnobGuard`, `BenchmarkRun`.
- [ ] **Step 4:** run → PASS. Commit `feat(benchmark): coordinate descent over RD and SD with a hard deadline`.

### Task 4: DH/Iris knobs and the restore marker

**Files:**
- Create: `core/benchmark/RestoreMarker.java`; Test: `RestoreMarkerTest.java`
- Create: `client/compat/OptionalMods.java`, `client/compat/DhCompat.java`, `client/compat/IrisCompat.java`, `client/benchmark/ClientKnobs.java`, `client/benchmark/MarkerRestore.java`
- Modify: `build.gradle` (compileOnly block), `versions/26.2/gradle.properties`, `versions/26.3/gradle.properties` (`iris_version`), `stonecutter`-agnostic

**Interfaces (produces):**
```java
public record RestoreMarker(@Nullable Boolean dhRenderingEnabled, @Nullable Boolean irisShadersEnabled, String createdAt) {
    public static Path defaultPath(Path configDir);                 // config/rigtune/benchmark-restore.json
    public static Optional<RestoreMarker> load(Path file);          // missing → empty; corrupt → deleted, empty
    public void save(Path file) throws IOException;                 // AtomicFiles
    public interface Target { boolean loaded(); boolean ready(); void set(boolean value) throws Exception; }
    /** True when nothing is left to restore (the marker is gone). Unloaded mods drop their field; unready ones wait. */
    public static boolean restorePending(Path file, Target dh, Target iris);
}
// client/compat/OptionalMods: static boolean dhLoaded(), irisLoaded() (FabricLoader only)
// DhCompat: static boolean ready(), renderingEnabled(), setRenderingEnabled(boolean)  (DhApi.Delayed.configs.graphics().renderingEnabled(); setValue(T) only: API 7.1 lacks the String overload)
// IrisCompat: static boolean shaderPackInUse(), setShadersEnabled(boolean) (IrisApi.getInstance().getConfig().setShadersEnabledAndApply)
// ClientKnobs implements KnobGuard.Applier: RD/SD via SettingsBridge.applyVanilla(options, map, false); before the first DH/Iris change writes the marker with the ORIGINAL values; after restoring them deletes it.
// MarkerRestore.tick(Minecraft): every 20 ticks while a marker exists, RestoreMarker.restorePending(...); gives up for this session after 10 min.
```
Build (verified on Modrinth 2026-09-25): `compileOnly "maven.modrinth:distanthorizonsapi:LTP8vW3B"` (API 7.2.0, jar `DistantHorizonsApi-7.2.0.jar`), `compileOnly("maven.modrinth:iris:${project.iris_version}") { transitive = false }` with `iris_version=gxZWWnKH` (1.11.4+26.2) / `bAdKrpw8` (1.11.6+26.3). Verified APIs (javap): `DhApi.Delayed.configs` (static `IDhApiConfig`), `graphics().renderingEnabled()` → `IDhApiConfigValue<Boolean>` with `getValue()`, `setValue(T)`, `setValue(T, String)`; `IrisApi.getInstance().isShaderPackInUse()`, `getConfig().areShadersEnabled()`, `setShadersEnabledAndApply(boolean)`.

- [ ] **Step 1: failing tests** — `RestoreMarkerTest`: `saveAndLoadRoundTrip`, `missingFileIsEmpty`, `corruptFileIsDeletedAndEmpty`, `restoresBothWhenReadyAndDeletesMarker`, `waitsWhileDhNotReady` (marker kept, Iris field already restored and removed from the file), `unloadedModDropsItsField`, `setFailureKeepsMarker`, `nullFieldsAreIgnored`.
- [ ] **Step 2:** FAIL → implement `RestoreMarker` → PASS.
- [ ] **Step 3:** compat classes + build lines; `./gradlew build` (both versions compile; `javap` the built jar: no DH/Iris references outside `client/compat/DhCompat`/`IrisCompat`).
- [ ] **Step 4:** commit `feat(benchmark): DH and Iris cost knobs with a crash restore marker`.

### Task 5: History, records, before/after pairing (core)

**Files:**
- Create: `core/benchmark/BenchmarkRecord.java`, `BenchmarkHistory.java`, `BenchmarkRecords.java`
- Test: `BenchmarkHistoryTest.java`, `BenchmarkRecordsTest.java`

**Interfaces (produces):**
```java
public record BenchmarkRecord(String id, String createdAt, String rigtuneVersion, String mcVersion, String mode, String scene,
        String phase, @Nullable String pairId, int targetFps, boolean targetMet,
        Map<String, KnobResult> knobs, @Nullable Result result, Map<String, Cost> costs, @Nullable World world) {
    public static final String BEFORE = "before", AFTER = "after", SINGLE = "single";
    public record KnobResult(int value, int original, @Nullable Double avgFps, @Nullable Double onePercentLowFps, @Nullable Double p99FrameMs) {}
    public record Result(double avgFps, double onePercentLowFps, double p99FrameMs, int repeats, @Nullable Double cv) {}
    public record Cost(double baselineAvgFps, double baselineOnePercentLowFps, double offAvgFps, double offOnePercentLowFps) {}
    public record World(String levelId, long seed) {}
}
public final class BenchmarkHistory {
    public static final int SCHEMA_VERSION = 1, MAX_RUNS = 50;
    public static Path defaultPath(Path configDir);                  // config/rigtune/benchmarks.json
    public static BenchmarkHistory load(Path file);                  // missing → empty; corrupt → moved to benchmarks.json.bad, empty; newer schema → empty, file untouched
    public static BenchmarkHistory empty();
    public List<BenchmarkRecord> runs();                             // oldest first
    public BenchmarkHistory with(BenchmarkRecord run);               // appended, oldest dropped beyond 50
    public void save(Path file) throws IOException;                  // {"schemaVersion":1,"runs":[...]} via AtomicFiles
    public Optional<BenchmarkRecord> latest();
    public Optional<BenchmarkRecord> openBefore(String scene, String mcVersion); // newest "before" with no "after" of its pairId
    public Optional<BenchmarkRecord> before(String pairId);
    public List<BenchmarkRecord> chart(String scene, int max);      // newest `max` runs of that scene with a result, oldest first
}
public final class BenchmarkRecords {
    public static String phase(BenchmarkRequest request, BenchmarkHistory history); // MEASURE+pairId: after if a before exists, else before; otherwise single
    public static BenchmarkRecord of(SessionResult r, BenchmarkRequest request, String phase, String id, String createdAt,
            String rigtuneVersion, String mcVersion, @Nullable BenchmarkRecord.World world);
    public static BenchmarkSummary summary(BenchmarkRecord record); // for RigTuneController.latestBenchmark()
    public static @Nullable BenchmarkMath.Gain gain(BenchmarkRecord before, BenchmarkRecord after); // null without results
}
```
- [ ] **Step 1: failing tests** — `BenchmarkHistoryTest`: `missingFileIsEmpty`, `roundTripKeepsEveryField`, `capsAtFiftyDroppingOldest`, `corruptFileIsMovedAsideAndEmpty`, `newerSchemaIsIgnoredAndNotOverwrittenByLoad`, `saveReplacesNewerSchemaAfterASuccessfulRun`, `unknownFieldsIgnored`, `savedFileHasSchemaVersion1`, `openBeforeFindsUnpairedBeforeOfSameSceneAndVersion`, `openBeforeIgnoresPairedAndOtherScene`, `chartReturnsNewestTenOfSceneOldestFirst`. `BenchmarkRecordsTest`: `phaseSingleForTune`, `phaseBeforeThenAfter`, `tuneRecordHasRdAndSdKnobs`, `measureRecordHasResultAndCv`, `costsRecorded`, `summaryUsesResultAndChosenRd`, `gainBetweenPair`.
- [ ] **Step 2:** FAIL → implement → PASS. Commit `feat(benchmark): benchmarks.json history and before/after pairs`.

### Task 6: Client executor, UI, controller wiring, game test

**Files:**
- Rewrite: `client/benchmark/BenchmarkController.java`; Create: `client/benchmark/BenchmarkStore.java`
- Replace: `client/ui/BenchmarkMenuScreen.java` (same constructor); Rewrite: `client/ui/BenchmarkResultScreen.java` (same constructor `(Screen, BenchmarkController.Outcome)`)
- Modify: `client/RealController.java` (`startBenchmark(BenchmarkRequest)`, `latestBenchmark()`; `startBenchmark()` delegates), `en_us.json`
- Extend: `gametest/BenchmarkGameTest.java`

**Interfaces:**
```java
// BenchmarkController (v0.1 surface kept)
public record Config(int maxSteps, double sweepSeconds, double settleSeconds, double timeoutSeconds) {
    public static final Config DEFAULT = new Config(6, 8.0, 2.0, 20.0);
    public Timing timing(); // warm-up min(1.5, sweep), quick min(6, sweep), quick settle ≤ min(2, timeout), 2 repeats, 300 s
}
public record Outcome(BenchmarkRequest request, SessionResult session, boolean cancelled, @Nullable BenchmarkRecord record, @Nullable BenchmarkMath.Gain gain) {
    public PlannerResult result(); public int originalRd(); public double targetFps();
}
public static boolean start(Minecraft mc, Config config);                          // TUNE, CURRENT
public static boolean start(Minecraft mc, BenchmarkRequest request, Config config); // scene CURRENT needs a world; BENCHMARK_WORLD needs none
public static boolean running(); public static void cancel(); public static @Nullable Outcome lastOutcome();
public static @Nullable Component progress(); public static void tick(Minecraft mc); // also ticks BenchmarkWorld and MarkerRestore
```
Executor per step: SETTLE (hold the camera; ready = Sodium/vanilla sections ready for 10 ticks; min/timeout from the protocol) → WARMUP (keep sweeping, frames discarded) → SWEEP i (360° at the sweep's pitch, `FrameTimes` recording across all sweeps of the step) → `run.record(stats)` → `run.advance()`. Environment exactly as v0.1 (uncap, GUI hidden, creative flight client-side, camera restored); vanilla originals written once with `save = true` at the end. Exit paths: finish, Esc/any screen, world/player change, exception, CLIENT_STOPPING (`cancel()`), all through `BenchmarkRun` so knobs are restored once. On finish (not cancelled): record saved to `BenchmarkStore`; CURRENT → `BenchmarkResultScreen(null, outcome)`; BENCHMARK_WORLD → `BenchmarkWorld.exit` then the result screen over the title screen.
Result screen: headline (TUNE: met/missed with RD; MEASURE: avg / 1% low), result line with "noisy" warning when cv > 5%, SD line, gain line for an "after" run (`rigtune.benchmark.gain` "1%% lows: %s (avg %s)" or `rigtune.benchmark.gain.none`), DH/shader cost lines, RD table (left), chart of the last ≤ 10 runs of the scene (right; two bars per run, `fill`, first/last date labels, legend). Buttons: TUNE → "Use RD %s · SD %s" (applies via `SettingsBridge.applyVanilla`, diffs the vanilla snapshot and records the changed keys through `ChangeRecorder.current().record(ChangeRecorder.newEntryId(), JournalEntry.BENCHMARK, changes)` with status APPLIED), and `rigtune.benchmark.keep` "Keep current (%s)" (no change); MEASURE → "Done".
Menu: scene cycle (hidden when `BenchmarkWorld.supported()` is false; saved to `ClientSettings.benchmarkScene`), "Tune settings", "Measure before", "Measure after" (active only when `openBefore(scene, mc)` exists; for CURRENT a tooltip that the location must match), "Back"; CURRENT disabled without a world; BENCHMARK_WORLD disabled inside a world (tooltip: leave the world first).
Game test `BenchmarkGameTest` (both versions): (a) in a harness world: Measure before → Measure after (short Config), assert both stored with one pairId, the result screen shows a gain line, restore of RD/SD/frame settings asserted, screenshot `bench-measure-after`; Tune run completes, `deadlineHit` false, knobs restored, screenshot `bench-tune-result` (chart visible); `benchmarks.json` parses with schemaVersion 1 and 3 runs; pressing Use records a `benchmark` journal entry through a test ChangeRecorder; (b) from the title screen (if BENCHMARK_WORLD kept): menu → BENCHMARK_WORLD Measure → world created, measured, back on the title screen with the result screen, screenshot `bench-world-result`; second run reuses the world.

- [ ] **Step 1:** failing unit test `BenchmarkControllerConfigTest` (`defaultTimingMatchesSpec`, `shortConfigScalesWarmupAndQuick`) → implement Config.timing → PASS.
- [ ] **Step 2:** rewrite the controller, store, screens, RealController methods, language keys; `./gradlew build` green (RigTuneClientGameTest compiles unchanged).
- [ ] **Step 3:** game test; lock; `:26.2:runClientGameTest` then `:26.3:runClientGameTest`; inspect screenshots; unlock.
- [ ] **Step 4:** commit `feat(benchmark): v2 controller, menu and result screens, game test`.

### Task 7: AC6.4 hook for the production smoke run (DH loaded)

**Files:** Create `gametest/BenchmarkSmoke.java`; Modify `gametest/ProductionSmoke.java` (one gated call inside its world block).
- [ ] With `-Drigtune.smoke.benchmark=true` (Gradle `-PsmokeBenchmark` adds it to `runProductionSmoke`), in the smoke world: record DH `renderingEnabled` and Iris state, run a short TUNE, wait for it, assert they equal the originals and `benchmark-restore.json` is gone, write `run/rigtune-benchmark-smoke.txt` with the evidence, then (DH loaded) log "kill the client now" instead of leaving the world (M-risk). Run it with a COPY of the user's mods only if the coordinator's `scratchpad/usermods` copy exists; otherwise report AC6.4 as UNVERIFIED with the exact command.
- [ ] Commit `test(benchmark): production smoke hook for the DH restore check (AC6.4)`.

### Task 8: Review, rebase, CI, design notes
- [ ] code-reviewer subagent on `git diff origin/feat/v0.2.0...HEAD`; fix HIGH/MEDIUM.
- [ ] `git rebase origin/feat/v0.2.0`; resolve hotspots keeping both sides; `./gradlew build` (both versions); rerun the game tests if the rebase touched client code; push; `gh run watch <id> --exit-status`.
- [ ] `docs/v0.2/design/ws-c.md`: design, deviations (e.g. "Keep" = the Use button, CV with the sample stddev, SD ≥ 5% rule, warm-up in the quick protocol, deadline worst-case rule, DH `setValue(T)`), spike outcome, AC status, UNVERIFIED items.

## Self-review against the SPEC
- AC6.1 → Tasks 2, 3, 5 (planner/descent with fake measurer, deadline, knob order, restore; CV/noise floor; gain formatting; history cap/schema/corrupt/newer).
- AC6.2 → Task 6 game test (Measure + Tune in the harness, restore asserted, pair stored, result screen with chart screenshotted, benchmarks.json valid).
- AC6.3 → Tasks 1 and 6 (created, entered, measured, exited to title, reused; screenshot for the coordinator), or documented cut.
- AC6.4 → Task 7 (production run with DH; M-risk: checked in-test, then the client is killed).
- M16 (RD + SD only, quick SD protocol, DH/shader cost reports always restored, 5 min) → Task 3/4. L6 → enum names. L10 → the marker restores Iris after a crash; the downgrade note goes in design/ws-c.md.
