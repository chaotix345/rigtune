# WS-C: Benchmark v2, design and deviations

SPEC item 6 as amended by the plan review (M16, M-risk, L6, L10). Plan: docs/v0.2/plans/ws-c.md.

## Structure
- `core/benchmark` (pure, unit-tested):
  - `Timing`/`Protocol`: the full protocol (settle 2–20 s, 1.5 s warm-up, 8 s level + 8 s at 25° down) and the quick one (settle ≤ 2 s, warm-up, one 6 s level sweep). The "quick settled" variant (full settle, quick sweep) is for a step that follows a chunk rebuild. Each has worst-case seconds.
  - `BenchmarkSession`: the step planner, `next(now)`/`record(step, stats)`, like `RenderDistancePlanner`.
  - `KnobGuard` + `BenchmarkRun`: apply knob changes and restore the originals exactly once on every exit path.
  - `BenchmarkMath`: aggregates, CV, noise floor, gain, percent formatting.
  - `RestoreMarker`, `BenchmarkRecord`/`BenchmarkHistory`/`BenchmarkRecords`.
- `client/benchmark`:
  - `BenchmarkController`: the tick-driven executor (settle → warm-up → sweeps per step), plus the v0.1 environment (uncapped frame rate, hidden GUI, client-side flight, camera held).
  - `ClientKnobs`: the KnobGuard applier. RD/SD are set in memory; DH/Iris go through compat, with the marker written first.
  - `BenchmarkWorld`, `BenchmarkStore` (the client's history), `MarkerRestore` (restore at start or after a failed restore), `KeepSettings` (the journaled "Use").
- `client/compat`: `DhCompat` and `IrisCompat` are the only classes linked against the DH API 7.2.0 / Iris jars (compileOnly). They're reached through `OptionalMods` after `FabricLoader.isModLoaded`; a `LinkageError` counts as "not available". Verified with javap on the built jar: no other class references `com.seibel.distanthorizons` or `net.irisshaders` types (HardwareProbe's existing Iris check is a reflection string), and no DH/Iris classes are bundled.
- UI:
  - `BenchmarkMenuScreen`: a scene cycle saved to `ClientSettings.benchmarkScene`; Tune / Measure before / Measure after; refusal reasons as tooltips.
  - `BenchmarkResultScreen`:
    - headline, target, result and a noise warning;
    - the SD line, and the gain line or "saved as before";
    - the DH/shader cost lines and the deadline warning;
    - the RD table (without P99 when it sits next to the chart) and a chart of the last ≤ 10 runs of the scene and MC version;
    - Use / Keep current, or Done for Measure.
- `RealController`: `startBenchmark(BenchmarkRequest)` (a refusal's reason becomes the status), `startBenchmark()` → `DEFAULT`, `latestBenchmark()` from the history.
- Hotspots touched:
  - RealController: three methods.
  - en_us.json: the `rigtune.benchmark.progress` value changed in place; a sorted `rigtune.benchmark.*` block appended.
  - build.gradle: a separate compileOnly block, plus the `-PsmokeBenchmark` line on `runProductionSmoke`.
  - gradle.properties: `dh_api_version`. versions/*/gradle.properties: `iris_version`.
  - gametest fabric.mod.json: one entrypoint.
  - ProductionSmoke: one gated call.
  - RigTuneClient: not touched. The marker restore and the world state machine tick from `BenchmarkController.tick`, which RigTuneClient already calls every tick, and CLIENT_STOPPING already calls `BenchmarkController.cancel()`.
- Not wired here (WS-E owns RigTuneScreen): the RigTune screen's Benchmark button still calls `startBenchmark()` (Tune in the current world). WS-E makes it open `BenchmarkMenuScreen`, which is also how the benchmark world is reached from the title screen.

## The session (M16)
- **TUNE**:
  1. RD with the unchanged `RenderDistancePlanner` (full protocol);
  2. SD (quick; singleplayer only, and not in the benchmark world, where nothing ticks);
  3. the first repeat of the chosen settings (full);
  4. the DH and shader cost reports (quick);
  5. the remaining repeat.

  The cost reports run before the last repeat, so a slow machine loses the CV before it loses the reports (a code review suggestion). **MEASURE** is just the repeats of the current settings.
- **SD candidates**: current, −2, −4, never below the option's minimum (5); skipped with fewer than 2 candidates. They're measured high to low, stopping at the first that meets the target. If none meets it, the best one is chosen only when it beats the current value's 1% low by ≥ 5% (`SD_MIN_IMPROVEMENT`). SD is also a gameplay setting, so noise alone never lowers it.
- **Full settle for quick steps after a rebuild**: a quick step that follows a render-distance change (the chosen RD is usually not the last one measured) or a shader toggle (an Iris reload) waits the full settle. Otherwise the SD choice and the cost baselines were measured while chunks were still compiling (code review).
- **Cost reports**: a quick baseline of the chosen settings (reused from the SD step when that exact configuration was measured), then the same with DH `renderingEnabled` off, then with shaders off (DH back on, so each cost is isolated). They're reports only: the chosen knobs keep the original DH/shader state, and both are restored.
  - A report whose feature refuses to switch off is skipped, with the reason as "not measured: <reason>"; the run carries on. Only a failing RD/SD change fails the run.
  - A report cut by the deadline is "not measured" too, and both are shown on the result screen and stored (`notMeasured`).
- **Deadline (300 s)**: a step starts only if its worst case (settle timeout + warm-up + sweeps) still ends 10 s before the deadline, i.e. within 290 s (`SLACK_SECONDS`). A step that doesn't fit ends its stage, and the next stage is still tried.
  - Worst case, RD + SD = 6 × 37.5 + 27.5 (the first SD step settles fully) + 2 × 9.5 = 271.5 s ≤ 290 s (AC6.1 tests `worstCaseRdAndSdFinishWithinDeadline`, `worstCaseRdAndSdFitTheBudgetWithSlack`); the repeats are then cut and the result falls back to the RD measurement.
  - The worst cases don't count the time inside a knob change (an Iris reload) or the up to one tick per phase change; the 10 s slack is for those. So the deadline is a budget the run keeps in practice, not a proven bound.
- **Result**: the repeats' aggregate; without repeats, the last full-protocol measurement of the chosen settings, with no CV.

## Deviations and decisions
- **Warm-up in the quick protocol too** (the amendment says "settle ≤2 s, then one 6 s phase"). The benchmark.md budget includes it, and a knob change needs it.
- **The warm-up turns the camera** at the first sweep's speed, ending exactly at the start yaw, so the JIT warms up on the sweep path and every recorded sweep starts from the same view.
- **CV** is the sample standard deviation (n − 1) of the repeats' 1% lows over their mean. A missing CV counts as 5% in the gain's noise floor.
- **"Keep"** in SPEC item 3 is the result screen's Use button (applying the tuned RD/SD). It diffs the whole vanilla snapshot (M7) and records one `benchmark` entry with its own id through `ChangeRecorder.current()`; a recorder exception is logged, never thrown. "Keep current" changes nothing and records nothing.
- **Scene names** are the enum names everywhere (L6): `CURRENT`, `BENCHMARK_WORLD`.
- **benchmarks.json**: `schemaVersion` 1. Each run has:
  - `id`, `createdAt`, `rigtuneVersion`, `mcVersion`, `mode`, `scene`, `phase` (before/after/single), `pairId`, `targetFps`, `targetMet`;
  - `knobs`: renderDistance/simulationDistance, each with value, original, and the stats measured at the value (RD stats are matched on RD alone, since the RD steps ran at the original SD);
  - `result`: avg, 1% low, p99, repeats, cv;
  - `costs`: distantHorizons/shaders, each with baseline and off, avg and 1% low;
  - `notMeasured`: for a report that applied but has no numbers, why (`deadline` or `failed: <message>`), by the same keys;
  - `world` (levelId, seed) and `deadlineHit`.

  Only finished runs are stored. Nothing is ever destroyed silently:
  - A corrupt file (bad JSON or bad UTF-8) is moved to `benchmarks.json.bad`, then `.bad.1`, `.bad.2`...
  - A newer schema is left untouched, then copied to `benchmarks.json.newer` (numbered the same way) just before the next finished run replaces it.
  - A file that can't be read (a transient lock, say) or moved aside is never overwritten; the store loads it again next time.
- **Pairing**: "Measure before" makes a new pairId; "Measure after" is offered for the newest unpaired before of the same scene and MC version. The gain line says "1% lows +X% (avg +Y%)", or "no significant change" when |gain| < 2 × max(CV before, CV after).
- **Distant Horizons' `renderingEnabled` IS persisted** (found by AC6.4). This contradicts dh-iris.md and M16's "API-only, not persisted". With DH 3.3.2, `setValue(false)` also writes `rendererMode = "DISABLED"` to `DistantHorizons.toml`: a restore by `clearValue()` alone left DH disabled on disk, even though no API override remained.
  - `DhCompat` remembers the value and API value from before RigTune's first change, writes the original value back with `setValue(original)`, then `clearValue()`s if there was no override before.
  - The crash-marker restore does the same.
  - Only `setValue(T)` is used: `setValue(T, String)` is API 7.2 (DH 3.3.2), and the user's DH 3.3.0 may not have it.
- **Restore marker** (`ModToggles` in core, unit-tested): written only when a run is about to change DH or Iris, holding only the fields that were on. If it can't be written, nothing is touched. It's deleted when both are back, and kept when a restore fails. A marker present at client start, or left by a restore that failed during the session, is retried each second from the title screen on, once each mod is ready: a mod that is no longer loaded drops its field, one that isn't ready keeps it (for up to 10 min). A benchmark refuses to start while an old marker can't be restored yet, since its changed values would otherwise be taken as the originals.
- **L10 (downgrade note)**: Iris saves `enableShaders` to iris.properties at once, and DH saves `rendererMode`. If the game dies during a cost report and the player then downgrades to 0.1.x (no marker support), shaders or DH stay off until they're turned back on in their menus. 0.2.x restores them at the next start.
- **Messages**: "settings restored" is only said when every change was put back (`Outcome.restoreOk`). Otherwise a toast says some settings could not be restored. Each restore step in `finish()` runs even if an earlier one throws.
- **v0.1 API kept** for RigTuneClientGameTest (its only change: it waits up to 4 min for the longer v2 run):
  - `start(Minecraft, Config)` (now TUNE in CURRENT, including SD and repeats);
  - `Config(4 args)`, with the rest of the timing derived: warm-up min(1.5, sweep/2), quick min(6, sweep), quick settle min(2, timeout);
  - `Outcome.result()/cancelled()`, `running()`, `cancel()`, `lastOutcome()`, and the `rigtune.benchmark.keep` button.

  `BenchmarkController.setDefaultConfig` is a test seam, so game tests can run the menu's buttons with short timings.
- **Diagnostics**: each step's log line includes the frame limit and `FramerateLimitTracker.getThrottleReason()`.

## Benchmark world (spike outcome: kept)
- **Creation**: `Minecraft.createWorldOpenFlows().createFreshLevel("rigtune-benchmark", LevelSettings("RigTune Benchmark", CREATIVE, PEACEFUL, commands on, DEFAULT data packs), WorldOptions(8675309, structures, no bonus chest), WorldPresets::createNormalWorldDimensions, parent)`. The signatures are identical on 26.2 and 26.3 (javap).
- **Set-up on the server thread**:
  - gamerules `ADVANCE_TIME`, `ADVANCE_WEATHER`, `SPAWN_MOBS`, `SPAWN_MONSTERS` and `SPAWN_PHANTOMS` off, `RANDOM_TICK_SPEED` 0;
  - `time set noon` and `weather clear` as commands (the world-clock API differs between 26.2 and 26.3);
  - the 3×3 chunks around the camera are generated first, so trees from neighbouring chunks are in the heightmap (reading it earlier gave 117 vs 121 on fresh creations);
  - `tp @a 0.5 (surface+10) 192.5 0 0`.

  The client holds creative flight and clears toasts. The controller holds the camera exactly at that spot.
- **Camera spot** (0, 192): picked from a logged height grid (forest hills with birch, a valley, the sea to the east); the screenshots are identical across reuse. **26.2 and 26.3 generate different terrain for the same seed** (surface 117 vs 123), which confirms the research's warning.
- **Folder handling**: `saves/rigtune-benchmark/rigtune-benchmark.json` records the MC version and seed, and is written as soon as the folder exists. A folder with RigTune's marker is deleted and recreated when the version or seed differs. A folder of that name without the marker may be the player's, so it's moved aside (`rigtune-benchmark-old-<time>`), never deleted (`folderAction`, unit-tested).
- **Only the benchmark save is touched** (code review, Critical). `WorldFlow` is the state machine without Minecraft, with a unit test per rule (`WorldFlowTest`). Every tick, `BenchmarkWorld` turns the game into an observation. The benchmark save is identified by: singleplayer, the integrated server's save folder (its level storage id) `rigtune-benchmark` (`getWorldPath(LevelResource.ROOT)`, since the storage access field is protected), and the level name "RigTune Benchmark". Then:
  - only that save is ever set up (gamerules, time, weather, teleport) or left;
  - a multiplayer connection ends the flow at once, and the player is never disconnected from a server they joined;
  - another save, or a return to the menu (WorldOpenFlows gives up without a callback), ends it without touching anything;
  - if the benchmark world still loads within 60 s after a timeout, it's left again;
  - state transitions are logged (at INFO under the dev autorun).
- **Leaving**: `Minecraft.disconnectFromWorld` (Save and Quit), then the result screen over the title screen, or a toast if the run was cancelled. It's done in a `finally`, so a failure while storing the run still leaves; not while the client is stopping.
- **Harness findings** (game tests only):
  1. Leaving a world from inside a client tick deadlocks the game-test phaser: the render thread is in `IntegratedServer.halt` → `executeBlocking` while the server thread waits in `ThreadingImpl.enterPhase` (thread dump in the spike).
  2. The harness's threading mixin defers `Minecraft.disconnect` called from a test-thread task, so `disconnectFromWorld` ends on its "Saving world" screen.

  So under `-Dfabric.client.gametest` `leave` waits (`AWAITING_EXIT`) for the test thread to call `exitNow`, and a `LEAVING` state waits until the world and server are gone, puts the title screen back if needed, and then shows the result.
- On 26.2 the first entry waits about 28 s: the teleport opens vanilla's terrain screen, which times out under the harness. A reused world logs in at the camera spot. 26.3 takes about 1 s.

## Game-test observation: 20 FPS runs
In some harness sessions, on both versions and usually after a benchmark world is reopened, every frame lasts exactly one game tick (20 FPS). The frame limit is 260 and the throttle reason NONE in those same log lines, so RigTune's uncapping is in effect: it's the harness pacing frames to ticks (or the hidden window), not RigTune. Numbers aren't asserted in game tests (SPEC AC6.2). The gain line honestly reports such a run as a large loss.

## AC6.4 (production run with Distant Horizons)
`./gradlew :26.2:runProductionSmoke -PextraModsDir=<fabric-api 0.161.0+26.2 + Distant Horizons 3.3.2 for 26.2> -PsmokeBenchmark` is a Loom production client with jars from the research downloads, not the player's instance. `BenchmarkSmoke` runs a short Tune in the smoke world and writes `run/rigtune-benchmark-smoke.txt`. The last run, on the final code, 2026-09-25 13:26:
```
Distant Horizons loaded true, rendering before true, API override before none
outcome cancelled false, steps [RENDER_DISTANCE, RENDER_DISTANCE, RENDER_DISTANCE, SIMULATION_DISTANCE, REPEAT, DH_OFF, REPEAT]
DH off seen during the run true, restore marker seen true
DH cost Cost[baselineLow=226.18655842835628, baselineAvg=1212.3503027899767, offLow=388.1646553608603, offAvg=2828.206870991524], shader cost null
Distant Horizons rendering after true, API override after none, shaders in use after false
benchmark-restore.json gone true
DistantHorizons.toml rendererMode = "DEFAULT"
AC6.4 PASSED
```
Then, as expected, the harness hung leaving the world with DH loaded ("Closing all [3] databases..."), and the client was killed. `rendererMode = "DEFAULT"` was still on disk afterwards, and no marker was left.

An earlier build's run had failed exactly here (`rendererMode = "DISABLED"` left on disk), which is how the persistence described above was found.

## The real benchmark-world exit (dev autorun, no harness)
`./gradlew :<mc>:runBenchmarkAutorun [-PrigtuneAutorun=benchmark-world-cancel]` starts a plain dev client (no `-Dfabric.client.gametest`) with `-Drigtune.dev.autorun`. `DevAutorun` then:
- starts a short Tune in the benchmark world from the title screen, through `BenchmarkController.tryStart`, as the menu does;
- in cancel mode, opens the pause screen mid-run, as Esc does;
- waits for the flow to end, logs every step, and quits.

It only exists when that property is set; the run dir is `versions/<mc>/build/run-autorun`, with `onboardAccessibility:false` so the title screen comes up. Each mode was run once per MC version, all passing on the first attempt (2026-09-25). Log excerpts from 26.2 (trimmed: timestamps dropped, long lines cut with "...", numbers rounded):
```
Benchmark world: IDLE -> OPENING (creating the save)
Dev autorun: mode benchmark-world: start from the title screen -> started
Benchmark world: OPENING -> SETTING_UP (SET_UP Observation[worldLoaded=true, multiplayer=false, serverRunning=true, singleplayerReady=true, benchmarkSave=true, ...])
Benchmark world: SETTING_UP -> READY (READY ... atCamera=true])
Benchmark started: TUNE in BENCHMARK_WORLD, target 170.0 FPS (uncapped), start Knobs[renderDistance=12, simulationDistance=12, ...]
Benchmark finished: chosen Knobs[renderDistance=9, simulationDistance=12, ...], target 170.0 FPS met true, result Aggregate[avgFps=2943.3, onePercentLowFps=584.4, ...]
Benchmark world: READY -> LEAVING (save and quit)
Benchmark world: LEAVING -> IDLE (FINISH_EXIT Observation[worldLoaded=false, ..., serverRunning=false, ...])
Dev autorun: back from the benchmark world: world state IDLE, screen BenchmarkResultScreen, outcome finished, record 2026-09-25T03:18:04Z-9b19
Dev autorun: screen now BenchmarkResultScreen; PASSED; stopping the client
```
```
Benchmark world: IDLE -> OPENING (opening the existing save)
Benchmark started: TUNE in BENCHMARK_WORLD, ...
Dev autorun: pressing pause (Esc) mid-run, progress: RigTune benchmark · step 1 · render distance 12 · loading chunks · Esc cancels
Benchmark cancelled: chosen Knobs[renderDistance=12, simulationDistance=12, ...], ..., result null
Benchmark world: READY -> LEAVING (save and quit)
Benchmark world: LEAVING -> IDLE (FINISH_EXIT ...)
Dev autorun: back from the benchmark world: world state IDLE, screen TitleScreen, outcome cancelled, record none
Dev autorun: screen now TitleScreen; PASSED; stopping the client
```
26.3 gave the same sequences: camera at y = 133; the Tune chose RD 9 (avg 3085 FPS, 1% low above the 170 target, "met true"), with the result screen after leaving; the cancel ended on the title screen. So the in-tick Save and Quit (`disconnectFromWorld` from END_CLIENT_TICK) works in a real client on both versions. Outside the harness, runs are at ~3000 FPS; the harness-only 20 FPS pacing (above) doesn't happen.

## Verification
- Unit tests: 454 per MC version (26.2 and 26.3), all passing (`./gradlew build`, 2026-09-25). WS-C's classes: ProtocolTest, BenchmarkMathTest, BenchmarkSessionTest, BenchmarkRunTest, ModTogglesTest, RestoreMarkerTest, BenchmarkHistoryTest, BenchmarkRecordsTest, BenchmarkWorldTest, WorldFlowTest and BenchmarkControllerConfigTest.
- Game tests (`runClientGameTest`: RigTuneClientGameTest + BenchmarkGameTest) pass on 26.2 and 26.3 on the final code:
  - the benchmark-world Measure before/after from the title screen, with the world reused;
  - Esc inside the benchmark world: restored, back on the title screen, nothing stored;
  - a Tune in a harness world with SD, then Use journaled as one `benchmark` entry;
  - settings restored after every run, and `benchmarks.json` has schemaVersion 1 and the runs.
- Screenshots reviewed: the menus, the running HUD, before/after results with the chart, the tune result with table and chart, the cancel toast.
- The dev autorun (real exit and Esc in the benchmark world) passes on 26.2 and 26.3 (above).
- AC6.1: **verified** (unit tests).
- AC6.2: **verified** (game tests, both versions).
- AC6.3: **verified** (game tests plus the non-harness autorun, both versions; coordinator: see the `bench-world-*` screenshots).
- AC6.4: **verified** with Distant Horizons 3.3.2 in a production run on 26.2 (above). Not run with 26.3 + DH.

## UNVERIFIED
- The shader cost report: Iris without a shader pack wasn't in any run, so `SHADERS_OFF` and the Iris restore are unit-tested through fakes only.
- DH 3.3.0 (the user's version) with API 7.1: `getApiValue`/`clearValue`/`setValue(T)` are assumed present (they are in 7.2). A `LinkageError` counts as "DH not available", so the worst case is no DH report.
- The DH part of the crash-marker restore (`restoreAfterCrash`) never ran against a real crash.
- AC6.4 with 26.3 + DH (only 26.2 was run).
