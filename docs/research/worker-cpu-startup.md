# What the "RigTune worker" thread does in the first 5 s

2026-11-26, main at c96b6634. Follow-up to [footprint-headroom.md](footprint-headroom.md), which found `workerCpuMs5s` at 96% of its 300 ms limit on one slow CI leg. The goal was to make RigTune do less CPU work in that window without changing the budget. No budget changed and no code changed. This note is the report.

## How I measured

Local production game tests on Windows (`./gradlew :26.2:runProductionClientGameTest -PgametestClasses=FootprintGameTest`, then the returning-player run as build.yml does it), with temporary instrumentation that isn't committed:

- `Probes.EXECUTOR` wrapped so that every task logs its submitter, its wait and wall time, and its CPU. The CPU comes from Windows `QueryThreadCycleTime` through JNA, converted at 4.195 M cycles per ms (a 300 ms busy-loop calibration in each run). `ThreadMXBean` thread CPU on Windows moves in 15.6 ms steps, so it can't split a 135 ms thread into tasks.
- Each `RealController.rebuild()` call logs who asked for it.
- JFR at a 1 ms period was tried first. It took 13 samples on the worker threads in the whole run, because Windows JFR doesn't sample threads that are loading classes, so it was dropped.

"Tasks" below is the sum of the per-task CPU inside the window. It runs lower than `workerCpuMsByThread`, which comes from the coarse `ThreadMXBean` clock. This PC was shared with other projects' gates during the runs, so a run's total moves by about 10 ms either way.

## Where the worker's CPU goes (fresh start)

The five fresh runs with full per-task data (b1 and base2 before the change below, c1, coal1 and coal2 with it), in CPU ms, median and range:

| Task | CPU ms | Classes loaded meanwhile (all threads) | Needed for |
|---|---|---|---|
| `HardwareProbe.probeSlow` (OSHI: CPU, RAM, battery, GPU cards) | 49 (47-69) | about 1,100 | The report (hardware profile) |
| Report rebuilds (`RealController.rebuild`, two or three of them) | 35 (34-40) | about 270 for the first | The report and the startup toast |
| `ModScanner.scan` (SHA-1 of each mod jar) | 14 (14-16) | about 90-165 | Modrinth lookups and update matching |
| `V05Services.afterStart` (first-run status, try-it derive, outside changes) | 13 (12-16) | about 470-630 | Title-screen toasts and notices |
| `StartupTimes.record` (at the title screen) | 8 (7-10) | about 60 | The launch-time regression notice |
| `JvmProbe` | 6 (5-7) | 70-170 | The report (JVM facts) |
| The rest (launcher probe, try-it I/O, small tasks) | about 6 | | |
| **Total** | **141 (127-152)** | | |

That is close to CI's median of 135 ms for the "RigTune worker" thread. Most of it is first-time work: class loading, the first run of each code path, and OSHI's own setup.

A returning player adds about 14-19 ms of try-it I/O (`TryItService.io`, reading `tryit.json` and the history), about 4-10 ms more in `V05Services.afterStart`, and about 7-12 ms more in the rebuilds. That accounts for the 32.7 ms median that footprint-headroom.md found.

## What I tried

### Coalescing the report rebuilds

On a fresh start two rebuilds are requested a few ms apart, one from the end of `rescan()` and one from the Modrinth lookup's answer (CI's fake Modrinth answers in milliseconds). Each one built a whole report on its own worker thread at the same time, and the generation check then threw the first report away. The change let a build that is asked for while another is building mark that build stale, as before, and run once the first had finished instead of next to it. Published reports were the same.

| Run | Rebuild CPU, fresh (ms) | Rebuild CPU, returning (ms) |
|---|---|---|
| Before (b1, base1, base2) | 39.5, 37.6, 40.3 | 46.1 |
| Coalesced (c1, coal1, coal2) | 34.2, 35.0, 34.5 | 47.2, 47.5 |

It saves about 5 ms on a fresh start and nothing on a returning one. I expected about 15-20 ms, one cold build. The two parallel builds had been sharing the class loading: a class loads once, and the second thread waits on the loading lock without using CPU. So only the duplicated execution was saved. On CI that would be about 2% of the 300 ms budget, well inside the run-to-run noise. Since it doesn't make a real difference, I didn't open it as a code change.

### Starting the OSHI probe in `onInitializeClient`

`HardwareProbe.slowPart()` doesn't need the window or the GPU, so it can start before CLIENT_STARTED. That moves the work earlier rather than removing it, so it was only an experiment to see what it would do.

| Run | Probe CPU before the window (ms) | `workerCpuMs5s` | `clientStartedWallMs` (limit 141) |
|---|---|---|---|
| early1 | 33.5 | 156.3 | 89.4 |
| early2 | 40.9 | 203.1 | **365.1 (failed)** |
| Others, for comparison | | 187.5-234.4 | 14.3-119.3 |

The probe is cheaper there. Only about 380 classes loaded during it, probably because Minecraft's crash-report preload has just used OSHI. But the second run failed `clientStartedWallMs`, and one window of 203 ms isn't a clear drop. It also only works by moving CPU out of the measured window, which makes the gate measure less. I'd not ship it.

## What else I looked at and left alone

- `StartupTimes.record` (7-10 ms) could run after the window, but its result feeds the launch-time regression notice. A player who opens RigTune right after the title screen would see the notice later.
- `V05Services.afterStart` feeds the title-screen toasts (try-it, first run) and the outside-changes notice, so delaying it delays what players see.
- The "RigTune rules" thread (CI median 52 ms) parses the bundled rules, then fetches and parses the remote copy and writes the cache. Skipping the second parse when the remote bytes match the bundled ones would mostly help CI, where the fixture serves the same file. Real players usually get newer rules from `main`.
- `ModScanner`'s SHA-1s are needed by the Modrinth lookup that runs right after the scan.

## Conclusion

Nothing reasonable is left that cuts the window's CPU without delaying something the player sees. Each remaining task does first-time work that the report, the startup toast or a title-screen notice needs. The options that remain are outside this task's rules:

1. Leave it. Without run 45117735319's slow EPYC 7763 leg, the max since 19 Nov is 249.1 (83%).
2. Change what the gate measures or allows. That needs Charlie (27 Sep rule).
3. A larger change that makes the first report cheaper, for example loading fewer classes on the first rebuild or OSHI calls that load less. That's a project of its own with no clear size yet.

Local runs also failed `tickHookOnVsReference` twice (2.26 and 2.06 against 2.05) while other projects' gates were running. That ratio isn't touched by anything here. It's the same check footprint-headroom.md found running highest on CI, at 86%.
