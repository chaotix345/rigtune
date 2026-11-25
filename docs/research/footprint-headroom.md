# Footprint budget headroom on CI after PR #88

2026-11-26, budgets as in `tools/footprint-budgets.json` on main at cbbfe162. No budget changed.

## Data

Every unexpired `build.yml` artifact. With 7-day retention the oldest is from 19 Nov (UTC), so runs from 16-18 Nov, the first after PR #88 merged, have expired. Everything below is after #88.

- FootprintGameTest: `footprint-*` artifacts from 35 runs, 19-25 Nov. 30 runs have all three fresh legs (26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan), 90 legs in all. 29 runs have the three returning-player legs, 87 in all. The 5 runs that were cancelled during the returning step have logs only, and their footprint JSON is missing. Run 45680103012 was cancelled after the returning step had copied the fresh JSON into its artifact, so I counted its fresh legs once.
- FrameHookBudgetTest: the `test-reports` artifact of the same 35 runs, 70 reports (26.2 and 26.3 for each run).
- Retries: the annotations of every `part 2/2` and `java` job. The returning step re-measures once in a new JVM after a `returningAdded*` miss, and FrameHookBudgetTest re-measures a ratio once. Neither shows in the footprint JSON.

No footprint JSON lists a violation, and every ratio self-check reads `ok`. No job annotation shows a returning-step retry. The one budget failure in the window was FrameHookBudgetTest's `frameHookOnVsReference` in run 45116454589 (below).

Runner CPUs on the fresh legs: Xeon 6975P-C 46, EPYC 7763 17, EPYC 9V74 15, Xeon Platinum 8573C 7, EPYC 9V45 4, Xeon 6973P-C 1. None of #88's Xeon 8370C runners appeared. The `java` job's report doesn't record its CPU.

## Results

"Used" is the observed max as a share of the limit. Zero-allocation and leak budgets are 0 on every leg and are listed once at the end.

| Budget | Median | p90 | Max | Limit | Used | Headroom |
|---|---|---|---|---|---|---|
| `renderThreadInitWallMs` (fresh) | 75.9 | 100.9 | 177.8 | 368 | 48% | 190.2 |
| `renderThreadInitWallMs` (returning) | 74.4 | 101.1 | 129.1 | 368 | 35% | 238.9 |
| `renderThreadInitCpuMs` | 54.3 | 64.7 | 104.8 | 150 | 70% | 45.2 |
| `clientStartedWallMs` (fresh) | 39.2 | 52.8 | 72.2 | 141 | 51% | 68.8 |
| `clientStartedWallMs` (returning) | 39.2 | 59.2 | 74.5 | 141 | 53% | 66.5 |
| **`workerCpuMs5s`** | 197.8 | 226.5 | **286.6** | 300 | **96%** | 13.4 |
| `returningAddedRenderThreadInitCpuMs` | 0.8 | 7.1 | 14.9 | 80 | 19% | 65.2 |
| `returningAddedWorkerCpuMs5s` | 24.7 | 47.0 | 63.6 | 80 | 79% | 16.4 |
| `samplerCpuMsPer60s` | 51.2 | 55.6 | 66.8 | 102 | 66% | 35.2 |
| `tickHookNsPerCall` | 3.49 | 5.25 | 6.88 | 27 | 25% | 20.1 |
| `tickHookNsPerCallWorld` | 8.16 | 13.58 | 16.68 | 62 | 27% | 45.3 |
| `tickHookNsPerCallOn` | 42.45 | 47.13 | 52.13 | 201 | 26% | 148.9 |
| `settingsCheckNsPerCall` | 40.60 | 44.70 | 51.07 | 185 | 28% | 133.9 |
| `tryItTickNsPerCall` | 2.07 | 3.69 | 5.23 | 19 | 28% | 13.8 |
| `serverProfileTickNsPerCall` | 3.39 | 6.19 | 7.40 | 32 | 23% | 24.6 |
| `launcherLeftoverTickNsPerCall` | 3.35 | 6.37 | 7.83 | 32 | 24% | 24.2 |
| `tickHookOnVsReference` | 1.639 | 1.689 | 1.768 | 2.05 | 86% | 0.282 |
| `frameHookNsPerCallOff` | 0.176 | 0.275 | 0.359 | 10 | 4% | 9.6 |
| `frameHookNsPerCallOn` | 32.46 | 38.23 | 38.49 | 164 | 23% | 125.5 |
| `frameHookNsPerCallOnPhases` | 191.4 | 244.3 | 252.1 | 400 | 63% | 147.9 |
| **`frameHookOnVsReference`** | 1.268 | 1.377 | **2.741** (failed run), 1.383 without it | 1.65 | 84% without it | 0.267 |
| `frameHookOnPhasesVsReference` | 0.936 | 0.947 | 1.102 | 1.32 | 83% | 0.218 |
| `rigtuneClassBytesIdle` | 87,728 | 87,824 | 87,968 | 109,296 | 80% | 21,328 |
| `heapGrowthAfterCyclesBytes` | -876,920 | -252,876 | 66,440 | 13,518,992 | 0.5% | 13.45 M |
| **`monitorOnRetainedBytes`** | 2,578,576 | 2,578,576 | **2,578,576** | 2,621,440 | **98.4%** | 42,864 |

Each ratio gate also requires the same run's doubled-work ratio to exceed the limit. The lowest doubled-work ratios were 2.563 for the tick hook (limit 2.05), 2.053 for the frame hook (1.65) and 1.800 for the frame hook with phases (1.32).

Zero budgets, 0 on every leg: `tickHookAllocBytes`, `tickHookAllocBytesWorld`, `tickHookAllocBytesOn`, `settingsCheckAllocBytes`, `tryItTickAllocBytes`, `serverProfileTickAllocBytes`, `launcherLeftoverTickAllocBytes`, `frameHookAllocBytesOff`, `frameHookAllocBytesOn`, `frameHookAllocBytesOnPhases` (hot calls, and cold calls beyond the 64 KiB allowance), `leakSuspects`, `monitorOffRetainedBytes`, `monitorOffLeftoverInstances`.

## By runner CPU (median / p90 / max)

| CPU | n | `renderThreadInitCpuMs` | `workerCpuMs5s` | `returningAddedWorkerCpuMs5s` | `tickHookOnVsReference` |
|---|---|---|---|---|---|
| Xeon 6975P-C | 46 (45) | 51.7 / 55.6 / 62.2 | 190.7 / 206.5 / 214.2 | 13.2 / 39.2 / 46.3 | 1.67 / 1.70 / 1.77 |
| EPYC 7763 | 17 (17) | 61.4 / 68.5 / 104.8 | 224.7 / 241.7 / 286.6 | 44.8 / 62.8 / 63.6 | 1.58 / 1.61 / 1.65 |
| EPYC 9V74 | 15 (13) | 54.3 / 65.6 / 69.4 | 205.2 / 222.7 / 226.5 | 24.7 / 41.5 / 60.7 | 1.55 / 1.58 / 1.64 |
| Xeon Platinum 8573C | 7 (7) | 64.7 / 67.5 / 68.5 | 222.1 / 228.7 / 230.0 | 26.7 / 45.1 / 47.0 | 1.44 / 1.46 / 1.46 |
| EPYC 9V45 | 4 (4) | 49.2 / 57.8 / 58.9 | 189.0 / 201.2 / 204.0 | 34.5 / 40.0 / 41.5 | 1.32 / 1.35 / 1.36 |
| Xeon 6973P-C | 1 (1) | 63.0 | 189.8 | 36.8 | 1.50 |

n is fresh legs, with returning legs in brackets.

## Reading

`returningAddedRenderThreadInitCpuMs` now has wide headroom: max 14.9 of 80, against +38 to +64 in the v0.5 calibration and two failures at 80.4 before #88. Render-thread init CPU fell from a 98 ms median before #88 to 54, and its max is 104.8 of 150.

Three budgets are within about 10% of their limit, or were exceeded once:

- `monitorOnRetainedBytes`, 98.4%. This is not noise. `StutterMonitor.retainedBytes()` adds up the lengths of preallocated arrays (`FrameRing`: `ends`, `phases`, `candidates`, the histograms; `StutterRings`: four `RecordRing`s), so every leg reports the same 2,578,576 bytes. It can't flake. Any new ring, or any capacity increase above 42,864 bytes, fails it on every leg. The limit equals the 2.5 MiB ceiling, so it can't be raised without Charlie.
- `workerCpuMs5s`, max 286.6 of 300 (96%) and p90 226.5 (75%). #88 left this window alone: the records thread finishes before CLIENT_STARTED. The median moved from 207 to 198, and the old range ran to 296. The max is one slow leg: run 45117735319, 26.3 OpenGL on an EPYC 7763. On that leg everything ran slow: render-thread init CPU was 104.8 (median 54.3), init wall 177.8, and the "RigTune worker" thread used 202 ms, against a median of 135 and no other leg above 168. It looks like the leg #88 found behind its failure (296 of 300 on a slow 8370C). Without that leg the max is 249.1 (83%). The "RigTune worker" thread is most of the value (110-202 ms, median 135), then "RigTune rules" (37-67, median 52). EPYC 7763 runners sit about 34 ms above Xeon 6975P-C at the median.
- `frameHookOnVsReference`, one CI failure. Run 45116454589 (PR run of 379fd126, 23 Nov, 26.2 `java` job) measured 2.741, then 2.587 on the re-measure. The hook's median was 75.9 ns per call against a usual 32-38, while the reference stayed at 27.7 ns. The same commit passed in its push run (45116443633), so the code didn't cause it. PR #110 (merged 25 Nov) gives the reference the same ring stores as the hook, for the L3-eviction slowdown it measured locally. That matches this failure: the hook slowed and the reference didn't. Only 34 reports come after #110 (max 1.352). Before it, without the failure, the max was 1.383. One bad JVM in 70 is too few to say whether #110 covers a CI runner this noisy.

Next closest: `tickHookOnVsReference` at 86% (max 1.768, above the calibration's 1x max of 1.745). It runs highest on Xeon 6975P-C, the most common runner. `returningAddedWorkerCpuMs5s` is at 79%. It subtracts two JVMs on the same runner, so the noise of both adds: one commit (723035c6) gave 21.2 and 60.7 on two runs. The "RigTune worker" thread is the cause. It uses a median of 32.7 ms more for a returning profile than for a fresh one.

The 18 runs from 25 Nov alone give the same picture: `returningAddedRenderThreadInitCpuMs` max 11.3, `returningAddedWorkerCpuMs5s` max 63.6, `workerCpuMs5s` max 249.1.
