# Footprint gate verification (v0.5 SPEC 1d, ws-ci)

Budgets, calibration and arithmetic: docs/v0.5/design/ws-ci.md "Footprint budgets" and tools/footprint-budgets.json.

## AC1d.3: a 2x slowdown of the monitor-on tick fails the gate

Scratch branch `scratch/ws-ci-proof-slowdown` (fb2177b8, on b9f5c002): `StutterHooks.tick` runs its body twice while the
stutter monitor is on; only RigTuneClientGameTest and FootprintGameTest run.
Run [36297418835](https://github.com/chaotix345/rigtune/actions/runs/36297418835): every leg red on
`tickHookOnVsReference`, the ns backstop green.

| leg | CPU | tickHookOnVsReference (limit 2.05) | the work twice | tickHookNsPerCallOn (limit 653) |
|---|---|---|---|---|
| 26.2 OpenGL | AMD EPYC 7763 | **2.839** | 5.633 | 79.07 |
| 26.3 OpenGL | AMD EPYC 7763 | **2.859** | 5.748 | 79.94 |
| 26.3 Vulkan | Intel Xeon 6973P-C | **2.704** | 5.407 | 64.08 |

`java.lang.AssertionError: footprint budget tickHookOnVsReference: 2.84 > 2.05 (…)` (26.2 OpenGL; the others alike).

The same code without the slowdown, run [36297288375](https://github.com/chaotix345/rigtune/actions/runs/36297288375) (b9f5c002), all green:

| leg | CPU | tickHookOnVsReference | the work twice (self-check, must exceed 2.05) | tickHookNsPerCallOn |
|---|---|---|---|---|
| 26.2 OpenGL | AMD EPYC 9V45 | 1.311 | 2.643 | 30.26 |
| 26.3 OpenGL | AMD EPYC 9V74 | 1.498 | 2.967 | 46.40 |
| 26.3 Vulkan | AMD EPYC 7763 | 1.550 | 3.075 | 43.11 |

FrameHookBudgetTest in that run's java job: `frameHookOnVsReference` 1.371 / 1.375 (26.2 / 26.3; twice the work 2.744 /
2.748; limit 1.65), `frameHookOnPhasesVsReference` 0.945 / 0.946 (1.892 / 1.894; limit 1.32).

## The first attempt, and the calibration

- Run [36295129832](https://github.com/chaotix345/rigtune/actions/runs/36295129832) (4e0acd3e, the same slowdown on the
  first version of the timing, reverted by 406714be): red on 26.2 OpenGL (2.115) and 26.3 Vulkan (2.146), green on
  26.3 OpenGL (EPYC 9V45, 1.829): the reference loop ran 1.3-1.8x slow under 250-380 ms of JIT compilation during the
  blocks. Fixed with compiled loop methods and a JIT-quiet wait (ws-ci.md), then recalibrated.
- Tick calibration (branch `research/v05-ci`, `ci-experiment.yml`, TimingProbe2GameTest): runs
  [36295704245](https://github.com/chaotix345/rigtune/actions/runs/36295704245) and
  [36296194458](https://github.com/chaotix345/rigtune/actions/runs/36296194458), 192 measurements on 6 CPU models: 1x at
  most 1.745, the monitor's tick doubled at least 2.462.
- Frame calibration: run [36296732786](https://github.com/chaotix345/rigtune/actions/runs/36296732786), 16 runners x 3,
  5 CPU models: monitor on 1x ≤ 1.419, 2x ≥ 1.920; with the phase timers 1x ≤ 0.957, 2x ≥ 1.833.

## The post-Wave-B checkpoint (SPEC 1h, AC1h.1)

Branch `fix/v05-footprint-checkpoint` (r-ci), 2026-09-28, on feat/v0.5.0 5c32ecac (Wave B and the split merged). The
user-approved formula re-applied to the per-call ns budgets only: limit = min(ceiling, ⌈4 × the largest value observed⌉).
No ceiling raised; CPU-ms, wall-ms, bytes, 0-allocation, leak checks and `tickHookOnVsReference` (2.05) untouched.
The budgets file records each key's `observedMax`, `observedRuns` and `observedMaxRun`, and its `about` names the checkpoint;
`FootprintBudgetsTest.everyPerCallBudgetComesFromTheCheckpoint` requires all ten per-call keys to follow the 4x rule from at
least 20 runs, and `timingLimitsFollowTheirRecordedRule` checks the arithmetic (both red on the pre-checkpoint file).

**The data** (collected from the artifacts by a script, `cp_collect.py` in the r-ci scratch folder):
- The six v0.5 keys: every completed, not cancelled build.yml run with the game-test split on (a head containing 111cb2be),
  on any branch, up to 36393851005: 27 runs. The tick keys come from each leg's part-2 footprint JSON (FootprintGameTest
  runs in part 2), the frame keys from FrameHookBudgetTest's lines in the java job's test reports (both nodes). The
  pre-split runs weren't used: their tick values were higher (maxima 8.50, 21.52, 59.34 ns over 116 runs since the ws-ci
  merge, against 6.70, 15.34, 50.01 ns split), and the frame keys, measured in the java job, don't depend on the split
  anyway (maxima 0.424/41.227/265.460 pre-split, 0.328/40.907/267.129 split).
- The four listener keys (X4.4), measured from 39e0b384 on: the 17 completed runs of 39e0b384 (pre-split; the branch and
  the scratch refs `scratch/fp-cp-1`, `scratch/fp-cp-2`, 36379957240 to 36388010588, two of them over-time on 26.2 OpenGL
  after FootprintGameTest had written its JSON) and the 6 split runs of 63e32ea0 (36390596922, 36390634971, 36392056025,
  36392624133, 36393661954, 36393851005): 23 runs. Why the pre-split runs are comparable: each listener is timed alone,
  after the same warm-up and JIT-quiet wait, on its own idle path (one or two field reads, or the settings check's cached
  comparisons), and the two groups agree:

| key | split (6 runs): median / max | pre-split (17 runs): median / max |
|---|---|---|
| settingsCheckNsPerCall | 42.95 / 45.58 | 42.67 / 46.11 |
| tryItTickNsPerCall | 3.40 / 4.63 | 3.49 / 4.63 |
| serverProfileTickNsPerCall | 6.14 / 7.67 | 5.89 / 7.82 |
| launcherLeftoverTickNsPerCall | 5.56 / 7.40 | 5.58 / 7.91 |

| key | runs | values | max observed (run, branch, artifact) | 4 x max | ceiling | limit |
|---|---|---|---|---|---|---|
| frameHookNsPerCallOff | 27 | 54 | 0.328 (run 36385058472, fix/v05-r11-ws-w, test-reports) | 1.31 | 20 | 2 (was 20) |
| frameHookNsPerCallOn | 27 | 54 | 40.907 (run 36384669141, fix/v05-r11-ws-l2, test-reports) | 163.63 | 200 | 164 (was 157) |
| frameHookNsPerCallOnPhases | 27 | 54 | 267.129 (run 36384669141, fix/v05-r11-ws-l2, test-reports) | 1068.52 | 400 | 400 (was 400) |
| tickHookNsPerCall | 27 | 81 | 6.7 (run 36393661954, scratch/fp-cp-1, footprint-26.3-OpenGL-part2) | 26.80 | 2000 | 27 (was 276) |
| tickHookNsPerCallWorld | 27 | 81 | 15.34 (run 36390243310, feat/v0.5.0, footprint-26.2-OpenGL-part2) | 61.36 | 2000 | 62 (was 393) |
| tickHookNsPerCallOn | 27 | 81 | 50.01 (run 36392624133, scratch/fp-cp-2, footprint-26.2-OpenGL-part2) | 200.04 | 2000 | 201 (was 653) |
| settingsCheckNsPerCall | 23 | 69 | 46.11 (run 36384951110, scratch/fp-cp-1, footprint-26.3-Vulkan) | 184.44 | 2000 | 185 (was 2000) |
| tryItTickNsPerCall | 23 | 69 | 4.63 (run 36393661954, scratch/fp-cp-1, footprint-26.2-OpenGL-part2) | 18.52 | 2000 | 19 (was 2000) |
| serverProfileTickNsPerCall | 23 | 69 | 7.82 (run 36381145845, fix/v05-footprint-checkpoint, footprint-26.2-OpenGL) | 31.28 | 2000 | 32 (was 2000) |
| launcherLeftoverTickNsPerCall | 23 | 69 | 7.91 (run 36379964103, scratch/fp-cp-1, footprint-26.3-Vulkan) | 31.64 | 2000 | 32 (was 2000) |

`frameHookNsPerCallOff` drops to 2 ns (the monitor-off frame hook is a static read; its v0.4-era maximum of 7.4 ns came
before the v0.5 runs). A local Windows run over a limit with CI green is recorded, not debugged (docs/v0.5/PLAN.md "Local
runs"). The four new allocation keys are 0 over all 48 blocks in every run (strict).

The new listeners' keys (FootprintGameTest; the same timing as the tick hooks: 48 blocks of 20,000 calls after warm-up, ns =
the median block, bytes = the sum over all blocks):
- `settingsCheckNsPerCall`/`AllocBytes`: RW-11/RW-17's SettingsWatch.tick, with a stutter session running (its checking path).
- `tryItTickNsPerCall`/`AllocBytes`: C09's TryItService.tick with no try running (one volatile read).
- `serverProfileTickNsPerCall`/`AllocBytes`: C16's ServerProfileService.tick with no toast waiting (one field read).
- `launcherLeftoverTickNsPerCall`/`AllocBytes`: 4d's LauncherRepairService.tickLeftover with nothing waiting (WS-L2).

Every v0.5 END_CLIENT_TICK or frame listener found (grep over src/client and src/main for ClientTickEvents, WorldRenderEvents,
HUD and screen render callbacks, and the mixins, on 5c32ecac): RigTuneClient.onTick and StutterHooks.tick (the tick keys;
C20's DevFixCalibration.tick inside StutterHooks.tick is behind a static-final dev switch; C20 adds no listener of its own);
SettingsWatch.tick, TryItService.tick, ServerProfileService.tick, LauncherRepairService's leftover listener (the four new
keys); TryItDevRun's listener (development only, with the environment variable RIGTUNE_DEV_TRYIT; not measured); RigTune's
HUD element (v0.4, unchanged); MinecraftFrameMixin (the frame keys, unchanged since v0.4). CrashReportMixin (RW-16's timer on
CrashReport.preload) runs once at startup; V05Services.registerEvents adds connection (JOIN/DISCONNECT) listeners.

## A returning player's startup (review-11 PERF-3)

The startup keys (`renderThreadInitWallMs`, `renderThreadInitCpuMs`, `clientStartedWallMs`, `workerCpuMs5s`) were measured
only with an empty config folder. In the split part that runs FootprintGameTest, build.yml now starts one more JVM with only
that class, `-Drigtune.footprint.returning=true` and a seeded config/rigtune: `tools/gametest/returning_seed.py` composes
0.4's and 0.5's written sets (src/test/resources/v040-written and v050-written, as the E2E harnesses compose them; 59
history entries, of which RigTune keeps 50 (Journal.MAX_ENTRIES); last-apply.json, awareness.json with `optionsAtExit`, stutter.json,
stutter-fixes.json, tryit.json with an open try, startup-times.json, profiles, benchmarks, server profiles), nothing staged;
build.gradle's `-PgametestSeedConfig` copies it in after the run-folder wipe. The class checks the seed is what the game
started with and gates the four startup keys with the existing budgets (`footprint-<mc>-<backend>-returning.json`, artifact
`footprint-returning-…`). Chosen over a unit-level read counter: it measures the real startup, on every leg, with the same
budgets, and catches work added anywhere (PERF-3's case (b): static startup code outside the lazy holder). Local 26.2
(Windows): render-thread CPU 94 ms, worker CPU 188 ms, client started 39 ms. The CI values of the branch's own run are in the handoff (a commit can't name its own run).
