# Footprint guard: calibration and gate proofs (SPEC 10, AC10.1-AC10.6; SPEC 13)

WS-F, branch `feat/footprint`. Budgets: `tools/footprint-budgets.json` (fail mode since `9644d1f`). The guard:

- `FrameHookBudgetTest` (JUnit, the `java` job, both MC versions): `FrameTimes.onFrame` 10 M calls. It reports
  ns/call (best of 5 hot runs) and bytes allocated, counted over the hot runs and over the first 10 M calls from cold.
  The cold count forgives 4 KiB of JIT noise: the JVM allocates 72 B on the calling thread while C2 compiles the loop in
  a bare JVM, and 344 B in the test JVM. It counts from cold so that an allocation C2's escape analysis later removes
  still shows: a `new long[1]` showed as 324,776 B over the cold calls and 0 B over the hot ones.
- `FootprintGameTest` (every client game-test leg, production client, after the other game tests in the same JVM):
  - startup numbers from `client/FootprintStats`, measured by RigTune itself as they happen, so the test's position
    doesn't matter;
  - the END_CLIENT_TICK hook (best of 3 × 100,000 calls on the render thread after a 200,000-call warm-up);
  - the notice evaluation;
  - the DiagnosticCommand class histogram and the heap after that histogram's full GC (G1 Old Generation GcInfo),
    before and after 20 RigTuneScreen + Tools open/close cycles;
  - the thread census, the startup-time record and the hub line.
  It writes `footprint-<mc>-<backend>.json`, uploaded as the CI artifact `footprint-<mc>-<backend>`.

## Deviations (coordinator decisions, 2026-09-26)

- All game tests share one client JVM, and PreviewGameTest needs live Modrinth, so **Modrinth stays on** during the
  5 s worker window: its lookups are part of `workerCpuMs5s`.
- **Rules from a loopback fixture** for every production game test: a Gradle build service (`RulesFixtureServer`,
  build.gradle) serves `rules/rules-v2.json` and `rules/rules-v1.json` on 127.0.0.1, and
  `-Drigtune.rules.baseUrl=http://127.0.0.1:<port>/` goes to `runProductionClientGameTest`. rules/rules-v2.json is the
  bundled copy (the `rules-consistency` job), so the remote document is never newer and the reports stay on the
  bundled rules, as they did against GitHub's main. No game test asserts the rules source; ProductionSmoke (its own
  task) is unaffected. Every leg was green with it (runs below).
- **Render-thread init ceilings** amended from 25 ms wall / 15 ms CPU (the research's estimate from reading the code)
  to **400 ms wall / 150 ms CPU**. `-Xlog:class+load` shows where the measured 65-124 ms goes:
  - preLaunch loads Gson (148 classes, which Minecraft loads soon after anyway, so this moves the cost earlier rather
    than adding to it) and 32 RigTune classes;
  - onInitializeClient loads 85 RigTune classes, plus about 80 java.net.http/security classes before the fix below.
  **Wall time is a loose backstop:** a shared 4-vCPU runner deschedules the main thread (26.3 Vulkan in run
  36222187126: preLaunch took 130 ms of wall time on 36 ms of CPU). The CPU budget is the tight one.
- Found by the guard and fixed:
  - `HttpModrinthClient` built both HttpClients in its constructor, which runs in onInitializeClient on the render
    thread. They're now built on the first request and never with Modrinth or the network off
    (`HttpModrinthClientLazyTest`). Local init went from 27.8/33.1 ms to 24.5 ms; in CI the change is inside the noise.
  - RigTuneClientGameTest calls `onPreLaunch()` again with the apply lock held (it waits 5 s). FootprintStats keeps
    only the launch's own call.
- `heapGrowthAfterCyclesBytes` swings both ways by several MB (Minecraft's own caches), so its limit is 2 × the largest
  |delta|: a backstop for large leaks. `leakSuspects` catches per-cycle leaks exactly: a RigTune class with at least
  one more live instance per cycle, limit 0.

## Calibration (AC10.3)

Code: `eb3054f`. The **local run**: 26.2, Windows 11, Ryzen 7 7800X3D + RX 7800 XT, 2026-09-26 15:53, under the
machine-wide game-test lock (first in the lock order). It ran FootprintGameTest only, through a temporary local edit of
src/gametest/resources/fabric.mod.json that was reverted in the same command and never committed; the run took 36 s.
Windows counts thread CPU in 15.6 ms steps. The **CI runs** (ubuntu-24.04, Xvfb, llvmpipe/lavapipe, 4 vCPUs):
[36222065977](https://github.com/chaotix345/rigtune/actions/runs/36222065977) (feat/footprint),
[36222187126](https://github.com/chaotix345/rigtune/actions/runs/36222187126) and
[36222189551](https://github.com/chaotix345/rigtune/actions/runs/36222189551) (the same commit on scratch branches, so
that they ran in parallel).

| leg | run | init wall ms | init CPU ms | client start ms | worker CPU ms (5 s) | tick ns | tick B | RigTune class B | heap Δ B | leaks |
|---|---|---|---|---|---|---|---|---|---|---|
| 26.2 GL | 36222065977 | 123.70 | 91.77 | 23.07 | 173.87 | 55.15 | 0 | 53,720 | +196,424 | 0 |
| 26.3 GL | 36222065977 | 90.17 | 86.94 | 14.54 | 182.23 | 26.44 | 0 | 53,872 | −756,232 | 0 |
| 26.3 VK | 36222065977 | 64.69 | 64.48 | 19.20 | 142.77 | 37.49 | 0 | 53,912 | −1,149,848 | 0 |
| 26.2 GL | 36222187126 | 104.96 | 92.47 | 35.81 | 156.09 | 54.33 | 0 | 54,648 | −567,144 | 0 |
| 26.3 GL | 36222187126 | 94.77 | 83.03 | 19.00 | 160.01 | 24.46 | 0 | 53,576 | −640,320 | 0 |
| 26.3 VK | 36222187126 | **183.67** | 66.24 | 12.59 | 133.20 | 23.06 | 0 | 53,728 | +48,440 | 0 |
| 26.2 GL | 36222189551 | 111.65 | **95.94** | 24.79 | 159.37 | 42.18 | 0 | 54,200 | −6,583,192 | 0 |
| 26.3 GL | 36222189551 | 116.09 | 95.40 | 18.41 | **186.72** | 27.33 | 0 | 53,576 | −757,464 | 0 |
| 26.3 VK | 36222189551 | 116.02 | 93.24 | **70.37** | 159.43 | 24.96 | 0 | 54,208 | **−6,759,496** | 0 |
| 26.2 GL local | — | 64.70 | 62.50 | 38.88 | 171.88 | 8.38 | 0 | 45,312 | +97,880 | 0 |

Frame hook (the `java` job, both MC versions per run), ns/call best of 3 at calibration time: 0.343/5.076,
6.407/0.204 and 0.326/0.605. The 5-6 ns runs are the loop still in C1 code on a busy runner, hence best of 5 now.
Allocation was 344 B over the cold calls (all within the JIT allowance) and 0 B over the hot calls, in every run.
Worker CPU by thread in 26.2 GL: "RigTune rules" 42.6-44.8 ms, "RigTune worker" 111.5-129.1 ms. The histogram took
454-494 ms per call. Notice evaluation took 0.9-1.4 µs.

Budgets: limit = min(ceiling, 2 × the largest value observed, local or CI); the heap delta uses 2 × the largest |delta|.

| budget | largest | limit | ceiling |
|---|---|---|---|
| renderThreadInitWallMs | 183.67 | 368 | 400 (amended) |
| renderThreadInitCpuMs | 95.94 | 150 | 150 (amended) |
| clientStartedWallMs | 70.37 | 141 | none |
| workerCpuMs5s | 186.72 | 300 | 300 |
| frameHookNsPerCallOff | 6.407 | 13 | 20 |
| frameHookAllocBytesOff | 0 | 0 | 0 |
| tickHookNsPerCall | 55.15 | 111 | 2000 |
| tickHookAllocBytes | 0 | 0 | 0 |
| rigtuneClassBytesIdle | 54,648 | 109,296 | 8 MiB |
| heapGrowthAfterCyclesBytes | \|−6,759,496\| | 13,518,992 | none |
| leakSuspects | 0 | 0 | 0 |
| frameHookNsPerCallOn, frameHookAllocBytesOn, monitorOnRetainedBytes, monitorOffRetainedBytes, samplerCpuMsPer60s | not measured yet (item 5) | = ceiling | 200 ns, 0, 2.5 MiB, 256 KiB, 30 ms |

When a feature adds always-on work or retained state (item 5's monitor, more rules content), re-run the calibration:
a local run plus 3 CI runs per leg, then 2 × the largest value, never above the ceiling.

## Fail mode (AC10.3, AC10.4)

[36222768149](https://github.com/chaotix345/rigtune/actions/runs/36222768149) (`9644d1f`, fail mode) passed every job.
Its values stay inside the calibration spread:
- init wall 94-120 ms, init CPU 88-89 ms
- client start 19-25 ms
- worker CPU 150-169 ms
- tick hook 29-60 ns
- RigTune class bytes about 54 KB
- heap Δ −1.2 MB to +0.19 MB
- no leak suspects
- frame hook 0.38-0.40 ns, 0 B

## Gate proofs (AC10.2)

Both ran on scratch branches off `9644d1f` (fail mode) and were never merged; feat/footprint never carried a
regression.

- [36222846655](https://github.com/chaotix345/rigtune/actions/runs/36222846655), branch
  `scratch/ws-f-proof-sleep-alloc`: a 420 ms `Thread.sleep` at the top of onInitializeClient (the wall budget + about
  50 ms), and a `new long[1]` in `FrameTimes.onFrame`.
  - **All 3 game-test legs red** on the wall budget alone: "renderThreadInitWallMs: 505.83 > 368" (26.2 GL), 510.41
    (26.3 GL), 473.39 (26.3 VK).
  - **The `java` job red** on FrameHookBudgetTest: "frameHookAllocBytesOff: 125248 > 0" and 377,464 (129,344 and
    381,560 B over the cold calls, less the 4 KiB allowance; 0 B over the hot calls, because C2 removes the
    allocation, which is why the cold count exists).
- [36222850330](https://github.com/chaotix345/rigtune/actions/runs/36222850330), branch `scratch/ws-f-proof-cpu`: a
  200 ms CPU busy loop at the top of onInitializeClient. **All 3 legs red** on the CPU budget alone: "renderThreadInitCpuMs:
  273.57 > 150" (26.2 GL), 266.03 (26.3 GL), 237.33 (26.3 VK). Wall stayed under its budget, so the tight CPU gate
  catches a CPU-bound regression by itself. The `java` job stayed green.

## Known limits of the measurement

- `workerCpuMs5s` counts threads named "RigTune…" that are alive when the window closes, which is what SPEC 10 lists.
  Two kinds aren't counted:
  - the short-lived "RigTune Modrinth check" pool (OnlineDataFetcher creates it for each check and shuts it down),
    once it has finished inside the window;
  - java.net.http's own worker and selector threads, which do the TLS and I/O for the rules and Modrinth requests.
  With Modrinth live, part of the Modrinth cost therefore falls outside the number. The spread over 12 CI legs
  (133-187 ms) is what the 2× budget absorbs.
- The PowerWatcher check (no "RigTune power" thread without a battery) keys on SPEC 4's thread name. Until WS-P merges
  no such thread exists anywhere, so it can't fail yet.
- Launch-to-title is taken at the first TitleScreen of the JVM (SPEC 13). On a first launch, a screen shown before
  it (the accessibility onboarding, or another mod's screen) adds the player's time on that screen to that one run;
  the median of 10 absorbs it.
- The histograms run minutes after start (the test runs 15th), when RigTune's background work is idle.

## Every always-on path (X5) and what measures it (AC10.6)

| always-on path | measured by |
|---|---|
| preLaunch (`RigTunePreLaunch`), `onInitializeClient` | FootprintStats → `renderThreadInitWallMs` / `renderThreadInitCpuMs` |
| CLIENT_STARTED handler (`RealController.start`: dispatches the rules load and the scan) | FootprintStats → `clientStartedWallMs` |
| background startup work: OSHI probe, mod scan, launcher probe, rules load, report build, Modrinth lookups (threads "RigTune worker", "RigTune rules") | `workerCpuMs5s`: CPU of every thread named "RigTune…" in the 5 s after CLIENT_STARTED, per thread in `workerCpuMsByThread` |
| frame hook (`DebugScreenOverlayMixin` → `FrameTimes.onFrame`; `StutterMonitor.onFrame` after item 5) | FrameHookBudgetTest → `frameHookNsPerCallOff` / `frameHookAllocBytesOff` (monitor-on cases: F-L1 follow-up) |
| tick hook (`RigTuneClient.onTick` on END_CLIENT_TICK; item 5's additions) | FootprintGameTest → `tickHookNsPerCall` / `tickHookAllocBytes` (monitor-on case: F-L1 follow-up) |
| notice evaluation (`NoticeCenter` via `controller.notices()`) | evaluated on screen init and rebuild only (C3; `NoticeSource` is asked by RigTuneScreen.init, never per frame); its cost is recorded as `noticeEvaluationUs` (about 1 µs), and it runs in each of the 20 retention cycles |
| PowerWatcher ("RigTune power", SPEC 4) | FootprintGameTest asserts no such thread without a battery (AC4.9; CI has none); with a battery its CPU counts in `workerCpuMs5s` like every "RigTune…" thread |
| stutter sampler ("RigTune stutter sampler", SPEC 5) | FootprintGameTest asserts no such thread with the monitor off; `samplerCpuMsPer60s` with it on: F-L1 follow-up |
| retained memory | `rigtuneClassBytesIdle`, `heapGrowthAfterCyclesBytes`, `leakSuspects`; the monitor's rings through `StutterMonitor.retainedBytes()` (`monitorOn/OffRetainedBytes`): F-L1 follow-up |
| the startup-time record (SPEC 13: one uptime read at the first title screen, one small file write on a worker thread) | `workerCpuMs5s` (when inside the window); AC13.2 checks in FootprintGameTest: exactly one run per launch, the hub line at 3 sizes |

## SPEC 13 (startup-time trend)

AC13.1: `StartupTimesTest` (11 cases). AC13.2: FootprintGameTest; screenshots `footprint-tools-<w>x<h>-scale<s>`.
The v040-written set `src/test/resources/v040-written/ws-f/startup-times.json` is written by `StartupTimesFixtureTest`
through the store. WS-H's released-0.3.0 harness (`tools/e2e/compat030.py` from `origin/test/e2e-v04`, run locally
2026-09-26 with the released `rigtune-0.3.0+mc26.2.jar`, the other sets still placeholders) passed 9/9, including "0.3.0
reading them changed no file".
