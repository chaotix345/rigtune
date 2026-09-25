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
  2. SD (quick, singleplayer only);
  3. the first repeat of the chosen settings (full);
  4. the DH and shader cost reports (quick);
  5. the remaining repeat.

  The cost reports run before the last repeat, so a slow machine loses the CV before it loses the reports (a code review suggestion). **MEASURE** is just the repeats of the current settings.
- **SD candidates**: current, −2, −4, never below the option's minimum (5); skipped with fewer than 2 candidates. They're measured high to low, stopping at the first that meets the target. If none meets it, the best one is chosen only when it beats the current value's 1% low by ≥ 5% (`SD_MIN_IMPROVEMENT`). SD is also a gameplay setting, so noise alone never lowers it.
- **Full settle for quick steps after a rebuild**: a quick step that follows a render-distance change (the chosen RD is usually not the last one measured) or a shader toggle (an Iris reload) waits the full settle. Otherwise the SD choice and the cost baselines were measured while chunks were still compiling (code review).
- **Cost reports**: a quick baseline of the chosen settings (reused from the SD step when that exact configuration was measured), then the same with DH `renderingEnabled` off, then with shaders off. They're reports only: the chosen knobs keep the original DH/shader state, and both are restored.
- **Deadline (300 s)**: a step starts only if its worst case (settle timeout + warm-up + sweeps) still ends before the deadline. A step that doesn't fit ends its stage, and the next stage is still tried.
  - Worst case, RD + SD = 6 × 37.5 + 27.5 (the first SD step settles fully) + 2 × 9.5 = 271.5 s ≤ 300 s (AC6.1 test `worstCaseRdAndSdFinishWithinDeadline`); the repeats are then cut and the result falls back to the RD measurement.
  - Not counted: the time inside a knob change (an Iris reload) and about one tick per phase change. The overshoot is a few seconds at most.
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
  - `world` (levelId, seed) and `deadlineHit`.

  Only finished runs are stored. File handling:
  - A corrupt file (bad JSON or bad UTF-8) is moved to `benchmarks.json.bad`.
  - A newer schema is left untouched until the next finished run replaces it.
  - A file that can't be read or moved aside is never overwritten; the store loads it again next time.
- **Pairing**: "Measure before" makes a new pairId; "Measure after" is offered for the newest unpaired before of the same scene and MC version. The gain line says "1% lows +X% (avg +Y%)", or "no significant change" when |gain| < 2 × max(CV before, CV after).
- **Distant Horizons' `renderingEnabled` IS persisted** (found by AC6.4). This contradicts dh-iris.md and M16's "API-only, not persisted". With DH 3.3.2, `setValue(false)` also writes `rendererMode = "DISABLED"` to `DistantHorizons.toml`: a restore by `clearValue()` alone left DH disabled on disk, even though no API override remained.
  - `DhCompat` remembers the value and API value from before RigTune's first change, writes the original value back with `setValue(original)`, then `clearValue()`s if there was no override before.
  - The crash-marker restore does the same.
  - Only `setValue(T)` is used: `setValue(T, String)` is API 7.2 (DH 3.3.2), and the user's DH 3.3.0 may not have it.
- **Restore marker**: written only when a run is about to change DH or Iris, holding only the fields that were on; deleted when both are back. A marker present at client start, or left by a restore that failed during the session, is retried each second from the title screen on, once each mod is ready: a mod that is no longer loaded drops its field, one that isn't ready keeps it (for up to 10 min). A benchmark refuses to start while an old marker can't be restored yet, since its changed values would otherwise be taken as the originals.
- **L10 (downgrade note)**: Iris saves `enableShaders` to iris.properties at once, and DH saves `rendererMode`. If the game dies during a cost report and the player then downgrades to 0.1.x (no marker support), shaders or DH stay off until they're turned back on in their menus. 0.2.x restores them at the next start.
- **v0.1 API kept** for RigTuneClientGameTest, which passes unchanged:
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
- **Only the benchmark save is touched** (code review, Critical): every state checks that the loaded world is this save (its folder and level name, `inBenchmarkWorld`). So:
  - a creation that fails silently (WorldOpenFlows returns to the menu without a callback) is noticed;
  - another world the player loads is left alone;
  - only the benchmark world is ever left.
- **Leaving**: `Minecraft.disconnectFromWorld` (Save and Quit), then the result screen over the title screen, or a toast if the run was cancelled. It's done in a `finally`, so a failure while storing the run still leaves; not while the client is stopping.
- **Harness findings** (game tests only):
  1. Leaving a world from inside a client tick deadlocks the game-test phaser: the render thread is in `IntegratedServer.halt` → `executeBlocking` while the server thread waits in `ThreadingImpl.enterPhase` (thread dump in the spike).
  2. The harness's threading mixin defers `Minecraft.disconnect` called from a test-thread task, so `disconnectFromWorld` ends on its "Saving world" screen.

  So under `-Dfabric.client.gametest` `leave` waits (`AWAITING_EXIT`) for the test thread to call `exitNow`, and a `LEAVING` state waits until the world and server are gone, puts the title screen back if needed, and then shows the result.
- On 26.2 the first entry waits about 28 s: the teleport opens vanilla's terrain screen, which times out under the harness. A reused world logs in at the camera spot. 26.3 takes about 1 s.

## Game-test observation: 20 FPS runs
In some harness sessions, on both versions and usually after a benchmark world is reopened, every frame lasts exactly one game tick (20 FPS). The frame limit is 260 and the throttle reason NONE in those same log lines, so RigTune's uncapping is in effect: it's the harness pacing frames to ticks (or the hidden window), not RigTune. Numbers aren't asserted in game tests (SPEC AC6.2). The gain line honestly reports such a run as a large loss.

## AC6.4 (production run with Distant Horizons)
`./gradlew :26.2:runProductionSmoke -PextraModsDir=<fabric-api 0.161.0+26.2 + Distant Horizons 3.3.2 for 26.2> -PsmokeBenchmark` is a Loom production client with jars from the research downloads, not the player's instance. `BenchmarkSmoke` ran a short Tune in the smoke world:
- steps: RD ×3, SD, repeat, DH_OFF, repeat;
- DH rendering was seen off during the run, and the marker was seen;
- DH cost: 1% low 287 → 385, avg 1409 → 2789 with DH off (harness numbers);
- afterwards: DH rendering true, API override "none" as before, `DistantHorizons.toml` `rendererMode = "DEFAULT"` after a 5 s wait, and `benchmark-restore.json` gone. "AC6.4 PASSED".

The previous build's run had failed exactly here (`rendererMode = "DISABLED"` left on disk), which is how the persistence above was found. With DH loaded the harness deadlocks when leaving a world, so the check halts the JVM after writing its evidence to `run/rigtune-benchmark-smoke.txt` (M-risk: "check in-test, then kill the client").

## Verification
- Unit tests: 421 per MC version (26.2 and 26.3), all passing (`./gradlew build`). WS-C's classes: ProtocolTest, BenchmarkMathTest, BenchmarkSessionTest, BenchmarkRunTest, RestoreMarkerTest, BenchmarkHistoryTest, BenchmarkRecordsTest, BenchmarkWorldTest and BenchmarkControllerConfigTest.
- Game tests (`runClientGameTest`, RigTuneClientGameTest + BenchmarkGameTest) pass on 26.2 and 26.3:
  - the benchmark-world Measure before/after from the title screen, with the world reused;
  - Esc inside the benchmark world: restored, back on the title screen, nothing stored;
  - a Tune in a harness world with SD, then Use journaled as one `benchmark` entry;
  - settings restored after every run, and `benchmarks.json` has schemaVersion 1 and the runs;
  - screenshots reviewed: menu (title/world), running HUD, before/after results with chart, the tune result with table and chart, the cancel toast.
- AC6.1: verified (unit tests above). AC6.2 and AC6.3: verified in game tests (coordinator: see `bench-world-*` screenshots). AC6.4: verified with DH 3.3.2 in a production run.

## UNVERIFIED
- Leaving the benchmark world from a client tick in a real (non-harness) client. It's the same `disconnectFromWorld` path as Save and Quit, but the harness can't run it (finding 1), so no automated run went down it. Suggested for Phase 5: from the title screen, RigTune → Benchmark → scene "Benchmark world" → Tune, then check that it returns to the title screen with the result.
- The shader cost report: Iris without a shader pack was not in any run, so `SHADERS_OFF` and the Iris restore are only unit-tested through fakes.
- DH 3.3.0 (the user's version) with API 7.1: `getApiValue`/`clearValue`/`setValue(T)` are assumed present (they are in 7.2). Any `LinkageError` counts as "DH not available", so the worst case is no DH report.
- The DH part of the crash-marker restore (`restoreAfterCrash`) never ran against a real crash.
