# WS-E: benchmark (SPEC 3f, 8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Tune's render-distance steps measure the terrain they claim to (broadcast + chunk-presence settle), guard the benchmark scene, add a read-only shader advice line and a `context` record, without changing any file format.

**Architecture:** Pure rules go to `core/benchmark` (SettleCheck, SceneVariety, ShaderAdvice, the CURRENT-scene cap, the `context` record) with JUnit tests; `client/benchmark` wires them (ClientKnobs broadcasts through a small `Vanilla` seam that a counting fake replaces in tests; BenchmarkController runs the settle check per tick). BenchmarkGameTest proves the chunk behaviour, the server's view distance, the scene and the camera on every CI leg; a real `runBenchmarkAutorun` Tune on both versions proves the chunk counts grow with the render distance.

**Tech Stack:** Java 25, Fabric Loom 1.17 (Mojang names), Stonecutter 26.2/26.3, Gson, JUnit 5, Fabric client game-test API v1.

**Spec:** docs/v0.3/SPEC.md 3f and 8 plus the amendments E-M1, E-M2, E-M3, E-L1 (they override the item text); docs/v0.3/plan-review.md (WS-E); docs/research/v0.3/benchmark.md.

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-bench3` (branch `feat/benchmark-v03`). Never edit build.gradle, stonecutter.gradle, settings.gradle, gradle.properties or .github/workflows/*.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; `./gradlew build` must pass for 26.2 and 26.3; the committed Stonecutter version stays 26.2.
- `core/` has no Minecraft imports. No `schemaVersion` bump; every new JSON field optional and ignored by 0.2.0.
- UI text only from `assets/rigtune/lang/en_us.json`, keys under `rigtune.benchmark.*`, inserted in alphabetical position.
- The benchmark never writes options.txt for a test value; `broadcastOptions()` is a no-op without a player (verified: javap of `Options.broadcastOptions` on 26.2 and 26.3 returns when `minecraft.player` is null); simulation distance isn't in `ClientInformation`, so an SD-only broadcast sends nothing (vanilla skips an unchanged `ClientInformation`), and no claim is made otherwise.
- Local game launches only under `C:/Dev/Worktrees/.gametest-lock` (PLAN's protocol: atomic mkdir, owner.txt, release in the same command). No force push; merges from `origin/feat/v0.3.0` only.
- Commits end with the two trailer lines from PLAN.

---

### Task 1: SettleCheck (E-M1, AC8.2 unit half)

**Files:**
- Create: `src/main/java/io/github/chaotix345/rigtune/core/benchmark/SettleCheck.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/benchmark/SettleCheckTest.java`

**Interfaces:**
- Produces: `SettleCheck.count(int radius, int centerX, int centerZ, SettleCheck.Chunks chunks) -> Count(inRange, missing)`; `new SettleCheck(Protocol protocol, int radius)`; `Optional<SettleCheck.Result> tick(double elapsedSeconds, Count chunks, boolean sectionsReady)`; `Result(int radius, int inRange, int missing, double seconds, boolean timedOut)` with `complete()` (settled, or at most 2% missing); constants `READY_TICKS = 10`, `MAX_MISSING_FRACTION = 0.02`.

- [ ] Step 1: tests: the circle for radius 11 has 377 chunks (the research probe's number), radius 0 has 1; `count` counts missing chunks only inside dx²+dz² ≤ r² (a missing corner chunk outside the circle doesn't count); `tick` settles only after the minimum time and `READY_TICKS` consecutive ticks with no chunk missing and sections ready (a tick with a missing chunk resets the streak); it returns a timed-out result at the timeout with the last missing count; `complete()` is true when settled, true when timed out with ≤ 2% missing, false when timed out with > 2% missing (e.g. 8 of 377).
- [ ] Step 2: run `./gradlew :26.2:test --tests '*SettleCheckTest'`, expect compile failure.
- [ ] Step 3: implement (pure Java, no MC imports).
- [ ] Step 4: tests pass. Step 5: commit `feat(benchmark): SettleCheck, the chunk-presence settle rule (E-M1)`.

### Task 2: an incomplete step never passes (E-M1)

**Files:**
- Modify: `core/benchmark/RenderDistancePlanner.java` (`record(int rd, FrameStats stats, boolean complete)`; the old 2-arg `record` delegates with `true`; `bestEffort()` prefers complete measurements)
- Modify: `core/benchmark/PlannerResult.java` (`Measurement(int rd, FrameStats stats, boolean passed, boolean complete)` + a 3-arg constructor with `complete = true`)
- Modify: `core/benchmark/BenchmarkSession.java` (`record(Step, FrameStats, boolean complete)`; the old one delegates with `true`; only RENDER_DISTANCE steps use the flag)
- Modify: `core/benchmark/BenchmarkRun.java` (`record(FrameStats, boolean complete)`; the old one delegates)
- Test: `RenderDistancePlannerTest`, `BenchmarkSessionTest`, `BenchmarkRunTest` (new cases)

- [ ] Step 1: tests: planner from 8, target 100: 8 passes, 10 passes, 14 incomplete with 1% low 500 → not passed, next step bisects between 10 and 14 (12), result never 14; with every step failing, `bestEffortRd` ignores an incomplete step with the best 1% low; session + run pass the flag through to the planner (an incomplete RD step with a high low isn't counted as meeting the target).
- [ ] Step 2: run, expect failures. Step 3: implement. Step 4: all core benchmark tests pass. Step 5: commit `feat(benchmark): a step measured on incomplete terrain doesn't pass the render distance search`.

### Task 3: ClientKnobs broadcasts after every RD/SD change and restore (AC8.1)

**Files:**
- Modify: `client/benchmark/ClientKnobs.java`: a package-private seam
  ```java
  interface Vanilla {
      Map<String, SettingsBridge.Result> apply(Map<String, String> values, boolean save);
      void broadcast();
  }
  ClientKnobs(Vanilla vanilla, ModToggles toggles)
  ```
  The Minecraft constructor builds `Vanilla` from `SettingsBridge.applyVanilla(options, values, save)` and `minecraft.options.broadcastOptions()`. `apply` always passes `save = false`, then broadcasts whenever it changed RD or SD (a broadcast failure is collected like any other failure, so the DH/Iris toggles still run), then the toggles.
- Test: `src/test/java/io/github/chaotix345/rigtune/client/benchmark/ClientKnobsTest.java` with a counting fake `Vanilla` and a `ModToggles` over fake `Mods` (temp marker file).

- [ ] Step 1: tests: an RD change applies in memory (every `save` flag false) then broadcasts once, in that order; an SD-only change broadcasts; a DH-only change neither applies vanilla nor broadcasts; through a real `KnobGuard` + `BenchmarkRun`: finishing, cancelling and failing a run each restore the RD and broadcast after the restore; a throwing broadcast still applies the DH toggle and then throws with the broadcast failure.
- [ ] Step 2: fail. Step 3: implement. Step 4: pass. Step 5: commit `fix(benchmark): tell the server each benchmark render distance (3f, AC8.1)`.

### Task 4: controller: settle on chunk presence, CURRENT-scene cap, Config, logging (3f, E-M1, E-M2, E-M3)

**Files:**
- Modify: `core/benchmark/BenchmarkSession.java`: `public static final int CURRENT_SCENE_HEADROOM = 8;` and `public static int maxRenderDistance(BenchmarkRequest.Scene scene, int startRd, int limit)` (CURRENT → `min(limit, startRd + 8)`, else `limit`); unit tests in `BenchmarkSessionTest`.
- Modify: `client/benchmark/BenchmarkController.java`:
  - `Config(int maxSteps, double sweepSeconds, double settleSeconds, double timeoutSeconds, @Nullable Double targetFps, int maxRenderDistance)` plus the existing 4-arg constructor (`null`, `MAX_RD`); target = `config.targetFps()`, else `-Drigtune.dev.targetFps` (dev only, logged), else the refresh-rate cap.
  - TuneLimits max RD = `BenchmarkSession.maxRenderDistance(scene, original RD, min(config.maxRenderDistance(), server/option cap))`.
  - SETTLE uses a `SettleCheck` per step over radius `min(step RD, server/option cap) - 1` around the camera chunk (`ClientChunkCache.hasChunk`), with `sectionsReady()`; logs `Benchmark settle: RD r: p/n chunks within r-1 present, client holds L, s s[, timed out: m missing]`; a timed-out incomplete step is logged at WARN and recorded with `complete = false`.
  - `public record Settled(Step step, SettleCheck.Result settle, int loadedChunks)`; `Outcome` gains `List<Settled> settles`.
  - Test hook `setSweepListener(@Nullable Consumer<Step>)`, called on the render thread right before a step records its first frame.
- Test: `BenchmarkSessionTest` (cap), `BenchmarkControllerConfigTest` (4-arg Config keeps `targetFps == null`, `maxRenderDistance == MAX_RD`).

- [ ] Step 1: failing tests (cap + Config). Step 2: fail. Step 3: implement. Step 4: `./gradlew :26.2:test :26.3:test` pass. Step 5: commit `fix(benchmark): settle each step on the chunks within RD-1 (3f); cap CURRENT-scene Tune at start RD + 8 (E-M2)`.

### Task 5: the menu note before a CURRENT-scene Tune (E-M2)

**Files:** `client/ui/BenchmarkMenuScreen.java` (one extra centred line under the scene hint when the scene is CURRENT); `en_us.json` key `rigtune.benchmark.menu.scene.current.saves` = "Tune loads and saves more of this world around you." (short enough for 320 GUI pixels).
- [ ] Implement, `./gradlew :26.2:compileClientJava`, commit `feat(benchmark): say that a Tune in your world loads and saves more of it (E-M2)`. Verified by the CI screenshot `bench-menu-world`.

### Task 6: SceneVariety (AC8.4)

**Files:**
- Create: `core/benchmark/SceneVariety.java`: `record Sample(int dx, int dz, int floor, String biome)`; `static List<int[]> offsets()` (16-block grid, dx²+dz² ≤ 192²: 441 offsets); `static Report check(List<Sample> samples, int seaLevel)`; `Report(@Nullable String rejection, double water, double heightSd, int heightRange, int biomes, double farWater, int centerFloor, String centerBiome)` with `accepted()` and `fingerprint()`. Rules in order: `ocean` (> 50% of samples within 128 have floor < sea level), `flat` (population sd < 8 or range < 24 within 128), `monotone` (fewer than 2 biomes with ≥ 5% of the samples within 192).
- Create: `src/test/resources/benchmark/scene-grid.csv` (the 441 samples within 192 of (0, 192) from the research probe's noise map; identical on 26.2 and 26.3).
- Test: `core/benchmark/SceneVarietyTest.java`.

- [ ] Step 1: tests: all-ocean → `ocean`; flat hills above the sea with 3 biomes → `flat`; a steep single-biome grid → `monotone`; the recorded grid → accepted with water 0.0, sd 18.5, range 57, 4 biomes, far water 14.7%, centre `117 minecraft:forest`; `offsets()` has 441 entries, 197 within 128.
- [ ] Step 2-4: fail, implement, pass. Step 5: commit `feat(benchmark): SceneVariety, the benchmark-scene guard (AC8.4)`.

### Task 7: camera y = terrain floor + 16 (AC8.6)

**Files:** `client/benchmark/BenchmarkWorld.java`: `setUp` reads `getGenerator().getBaseHeight(0, 192, OCEAN_FLOOR_WG, level, randomState())`, camera at floor + 16 when that block and the one above are air, else the v0.2 rule (MOTION_BLOCKING + 10) with a WARN; package-private `static double cameraY(int floor, boolean clear, int surface)` tested in `BenchmarkWorldTest`. Public `CAMERA_ABOVE_FLOOR = 16`.
- [ ] Test, fail, implement, pass, commit `feat(benchmark): camera at the terrain floor + 16, independent of trees (AC8.6)`.

### Task 8: shader advice (AC8.7 unit half, E-L1)

**Files:**
- Create: `core/benchmark/ShaderAdvice.java`: `static OptionalInt costPercent(@Nullable SessionResult.Cost shaders, double targetFps)`: present exactly when the cost was measured, `baselineLow < target`, `offLow >= target` and `lowGainPercent() >= 10`; the value is `round(100 * (offLow - baselineLow) / offLow)`.
- Modify: `client/ui/BenchmarkResultScreen.java`: the advice line after the shader cost line; `en_us.json` `rigtune.benchmark.shader_advice` = "Your shader pack costs about %s%% of your 1%% lows. A lighter profile or lower shadow quality in its settings may reach your target." (generic: the menu path isn't verified).
- Test: `core/benchmark/ShaderAdviceTest.java`: the full 16-row truth table (measured × below × reaches × gap) plus the boundaries (baseline == target → none; off == target → shown; gap exactly 10% → shown).
- [ ] Test, fail, implement, pass, commit `feat(benchmark): read-only shader advice line (item 8a)`.

### Task 9: `context` on new runs + 0.2.0 compatibility (AC8.8)

**Files:**
- Modify: `core/benchmark/BenchmarkRecord.java`: 17th component `@Nullable Context context`, `record Context(boolean dhRendering, boolean shaders, @Nullable String shaderPack, int width, int height, boolean fullscreen, int protocol)` with `PROTOCOL = 1`, and the 16-arg constructor (context `null`).
- Modify: `core/benchmark/BenchmarkRecords.java`: `of(..., World world, @Nullable Context context)`.
- Modify: `BenchmarkController`: the context from the start knobs, `iris.properties` `shaderPack` (only with shaders in use), `Window.getWidth/getHeight`, `options.fullscreen()`.
- Create: `src/test/java/io/github/chaotix345/rigtune/v020/core/benchmark/{BenchmarkHistory,BenchmarkRecord,BenchmarkMath}.java` = `git show v0.2.0:<path>` with only the package renamed (`...core.benchmark` → `...v020.core.benchmark`).
- Create: `src/test/resources/v020/benchmarks.json`: written once by the pinned 0.2.0 `BenchmarkHistory.save` (a Tune with costs and notMeasured, a Measure pair in the benchmark world).
- Test: `core/benchmark/BenchmarkCompatibilityTest.java`: the 0.2.0 fixture loads with `context == null` and every field equal to the pinned reader's view, and saving it again gives byte-identical text; a 0.3.0 file with `context` loads in the pinned reader (not moved aside, every other field equal); a 0.2.0 rewrite drops `context` and 0.3.0 still loads it; `BenchmarkRecordsTest` checks `of` carries the context.
- [ ] Test, fail, implement, pass, commit `feat(benchmark): optional context on new benchmarks.json runs, 0.2.0-compatible (AC8.8)`.

### Task 10: game tests (AC3.6, AC8.2, AC8.3, AC8.5, AC8.6, AC8.8)

**Files:** `src/gametest/.../BenchmarkGameTest.java`.
- New `benchmarkWorldChunks` (after the Measure pair): Config `(4, 1.0, 0.5, 60.0, 1.0, 12)` → Tune from the harness RD 5 steps 5, 7, 11, 12; a sweep listener counts, on the render thread, the chunks with dx²+dz² ≤ 11² around the camera chunk missing at the RD 12 step's first frame. After the run (world still loaded): RD 12 step settled without a timeout (settle seconds logged per leg), 0 of 377 missing at its first frame; the server's `requestedViewDistance()` equals the restored render distance (read with `server.submit` + `waitFor`, never a blocking join); SceneVariety over `getBaseHeight`/`getUncachedNoiseBiome` on the server thread accepts the scene and the fingerprint is logged; the camera y equals floor + 16 and both blocks are air.
- `benchmarkWorldCancel`: Esc only once a step with RD ≠ the start RD is running; then the server's requested view distance equals the restored render distance.
- `currentWorldTune`: after the Tune, the harness server's requested view distance equals the restored render distance; every step ≤ start RD + 8.
- `checkHistoryFile`: every run this test wrote has a `context` object with `protocol` 1 and a positive width.
- [ ] Compile both versions (`./gradlew :26.2:gametestClasses :26.3:gametestClasses`), push, read the CI legs (`gh run watch`), download the artifacts and look at the screenshots; commit `test(benchmark): 3f chunk game test, server view distance, scene guard and camera (AC3.6, AC8.2-8.6)`.

### Task 11: autorun Tune mode, docs

**Files:** `client/benchmark/DevAutorun.java`: mode `benchmark-world-tune` runs the default (full) Tune and logs each step's settle (`Dev autorun: step ...`); `docs/v0.3/design/ws-e.md` (design notes, deviations, the terrain correction for DESIGN.md, the E-M2 note for DESIGN.md); README benchmark paragraph (one sentence on the CURRENT-scene cap).
- [ ] Implement, build, commit `feat(benchmark): full Tune autorun mode; WS-E design notes`.

### Task 12: AC3.7 real autorun Tune on 26.2 and 26.3 (under the lock)

- [ ] Seed `versions/<mc>/build/run-autorun/options.txt` with `renderDistance:8`; take the lock; `./gradlew :<mc>:runBenchmarkAutorun -PrigtuneAutorun=benchmark-world-tune`; release in the same command; 26.3 retried up to 5 times on the OpenAL crash. Save the RigTune lines of `latest.log` and a README with the per-step chunk counts under `docs/v0.3/verification/benchmark/`. Commit `docs(v0.3): AC3.7 autorun evidence`.

### Task 13: review, merge, finish

- [ ] Dispatch a code-reviewer subagent on `git diff feat/v0.3.0...HEAD`; fix high/medium findings as they arrive.
- [ ] When WS-0 has merged: `git merge origin/feat/v0.3.0`; `./gradlew build` (both versions); push; every CI job green; screenshots reviewed.
