# Footprint guard: calibration and gate proofs (SPEC 10, AC10.1-AC10.6; SPEC 13)

WS-F, branch `feat/footprint`; the session monitor's part (F-L1) by WS-F2, branch `feat/footprint-monitor` (section
"Session monitor" below). Budgets: `tools/footprint-budgets.json` (fail mode since `9644d1f`). The guard:

- `FrameHookBudgetTest` (JUnit, the `java` job, both MC versions), 10 M calls per case:
  - `FrameTimes.onFrame` + `StutterMonitor.onFrame` (what `DebugScreenOverlayMixin` runs) with the session monitor off;
  - the same with it on;
  - the same with it on, plus the phase timers of a capped frame (WS-F2).
  Each case reports ns/call (best of 5 hot runs) and bytes allocated, counted over the hot runs and over the first 10 M
  calls from cold. The cold count forgives 64 KiB of JVM noise; it was 4 KiB until WS-F2, and 64 KiB is now the same
  bound as StutterMonitorTest and FrameRingAllocationTest. The JVM allocates 72 B on the calling thread while C2
  compiles the loop in a bare JVM, and 344-960 B in the test JVM. The count starts from cold so that an allocation C2's
  escape analysis later removes still shows: a `new long[1]` showed as 129-382 KB over the cold calls and 0 B over the
  hot ones.
- `FootprintGameTest` (every client game-test leg, production client, after the other game tests in the same JVM):
  - startup numbers from `client/FootprintStats`, measured by RigTune itself as they happen, so the test's position
    doesn't matter;
  - the END_CLIENT_TICK hook (best of 3 × 100,000 calls on the render thread after a 200,000-call warm-up);
  - the notice evaluation;
  - the DiagnosticCommand class histogram and the heap after that histogram's full GC (G1 Old Generation GcInfo),
    before and after 20 RigTuneScreen + Tools open/close cycles;
  - the thread census, the startup-time record and the hub line;
  - then the session monitor in a singleplayer world, on and off again (WS-F2, below).
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
| the session monitor's budgets | see "Session monitor" below | | |

When a feature adds always-on work or retained state (item 5's monitor, more rules content), re-run the calibration:
a local run plus 3 CI runs per leg, then 2 × the largest value, never above the ceiling.

## Session monitor (F-L1, WS-F2; AC10.4)

Branch `feat/footprint-monitor`, design notes docs/v0.4/design/ws-f2.md. What's measured:
- **FrameHookBudgetTest**, 10 M calls per case:
  - the frame hook with a session capture on;
  - the same plus every phase-timer call of a capped frame with one tick and a chunk load: 7 `MinecraftFrameMixin` calls
    plus onFrame, which is 8 `System.nanoTime()` reads;
  - the uncapped frame (6 reads), as a diagnostic.
- **FootprintGameTest**, in a singleplayer world with no screen open. In order:
  1. the heap after a full GC with the monitor off;
  2. `RigTuneClient.onTick` + `StutterHooks.tick` with the monitor off (`tickHookNsPerCallWorld`);
  3. monitor on;
  4. the sampler's CPU for 5 s, then for 60 s of wall time (the steady state is gated);
  5. the CPU of the sampler's JDK calls alone (`samplerJdkFloorMsPer240`: getAllThreadIds + getThreadCpuTime + process
     CPU, 240 times, and getThreadInfo for every thread apart);
  6. the two tick listeners again with the monitor on (`tickHookNsPerCallOn`);
  7. `StutterMonitor.retainedBytes()`, and the heap after a full GC;
  8. monitor off: no capture, no GC listener, no sampler thread; `retainedBytes()` again; once the session is in
     stutter.json, a class histogram that counts leftover capture objects.

**Coordinator decisions (2026-09-26).**
- **The phase-timer case gets its own 400 ns ceiling** (SPEC F-M3). SPEC 10's 200 ns was for the two onFrame calls
  alone. At about 21-32 ns per clock read, 8 reads leave no 2× headroom under 200.
- **The sampler is gated in steady state (from 5 s after its thread starts) with a 120 ms per 60 s ceiling.** That is
  0.2 % of one core, and only while the opt-in monitor runs; SPEC 10 said 30 ms.
  - Measured from the thread's start, the original sampler used 48.5-68.1 ms per 60 s on CI (run 36231493749).
  - ThreadSampler then changed: it reads each thread's name once and keeps its bookkeeping primitive (ThreadSamplerTest
    checks the attribution against the old loop). The steady state dropped to 20-51 ms over 21 CI legs, a cut of about 20-25 %.
  - The JDK calls alone cost 2-5 ms per 240 samples, measured back to back on the test thread with hot caches.
  - The rest (~150 µs per sample) is most likely waking a sleeping thread on a 4-vCPU runner that llvmpipe keeps busy.
    That is a hypothesis; it's not measured.
  - 4 Hz stays, because attribution quality matters more than about 0.08 % of one core.

**Game-test numbers**:
- Rounds: calibration round 1 on `85bdcec` (3 runs); run 36232640349 on `31bb058`, which has the same sampler and
  window code but no floor diagnostic; calibration round 2 on `8b44741` (3 runs, which also measure the World tick
  keys: 18.7-43.3 ns, 0 B; 14.35 ns locally); two local 26.2 runs.
- Units: retained is `retainedBytes()` in B, sampler in ms per 60 s, the floor in ms per 240, tick in ns per call.
- Heap Δ is the heap after a full GC minus the world-idle value (diagnostic).

| leg | run | retained on | retained off | leftover | sampler steady | first 5 s | JDK floor (+getThreadInfo) | tick on | tick on B | heap Δ on | heap Δ off | frames |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 26.2 GL | 36233278032 | 1,982,608 | 0 | 0 | 39.80 | 4.73 | 4.23 (+5.04) | 42.33 | 0 | +2,937,616 | −25,184 | 2409 |
| 26.3 GL | 36233278032 | 1,982,608 | 0 | 0 | 42.96 | 4.75 | 4.59 (+7.26) | 47.29 | 0 | +2,951,600 | −14,064 | 4009 |
| 26.3 VK | 36233278032 | 1,982,608 | 0 | 0 | 20.10 | 2.92 | 2.17 (+4.06) | 45.69 | 0 | +3,058,760 | +91,768 | 5009 |
| 26.2 GL | 36233279951 | 1,982,608 | 0 | 0 | 41.93 | 4.81 | 2.96 (+3.70) | 32.95 | 0 | −2,082,584 | −5,047,344 | 3856 |
| 26.3 GL | 36233279951 | 1,982,608 | 0 | 0 | 43.79 | 5.23 | 5.49 (+5.21) | 42.93 | 0 | +2,818,376 | −148,808 | 4148 |
| 26.3 VK | 36233279951 | 1,982,608 | 0 | 0 | 36.27 | 4.13 | 2.61 (+5.52) | 43.13 | 0 | +3,144,512 | +179,760 | 3498 |
| 26.2 GL | 36233285459 | 1,982,608 | 0 | 0 | 43.25 | 5.03 | 1.89 (+3.44) | 36.76 | 0 | +3,074,120 | +108,616 | 3154 |
| 26.3 GL | 36233285459 | 1,982,608 | 0 | 0 | 41.99 | 4.87 | 4.67 (+7.71) | 47.02 | 0 | +2,923,072 | −42,760 | 4140 |
| 26.3 VK | 36233285459 | 1,982,608 | 0 | 0 | 41.90 | 4.80 | 5.14 (+3.50) | 36.73 | 0 | +3,077,992 | +112,584 | 5062 |
| 26.2 GL | 36232640349 | 1,982,608 | 0 | 0 | 50.30 | 5.81 | — | 45.97 | 0 | +3,199,976 | +269,176 | 2268 |
| 26.3 GL | 36232640349 | 1,982,608 | 0 | 0 | 41.82 | 4.53 | — | 48.62 | 0 | +2,785,688 | −170,688 | 4399 |
| 26.3 VK | 36232640349 | 1,982,608 | 0 | 0 | 45.11 | 5.01 | — | 47.93 | 0 | +3,196,880 | +230,416 | 3972 |
| 26.2 GL | 36234193894 | 1,982,608 | 0 | 0 | 49.05 | 5.37 | 6.40 (+4.61) | 45.70 | 0 | +3,007,856 | +44,080 | 2299 |
| 26.3 GL | 36234193894 | 1,982,608 | 0 | 0 | 39.62 | 4.82 | 4.75 (+7.57) | 45.34 | 0 | +2,870,136 | −97,304 | 4245 |
| 26.3 VK | 36234193894 | 1,982,608 | 0 | 0 | 39.38 | 4.61 | 4.69 (+5.14) | 45.09 | 0 | +2,948,008 | −17,960 | 4029 |
| 26.2 GL | 36234196318 | 1,982,608 | 0 | 0 | 38.55 | 4.65 | 4.23 (+4.94) | 43.16 | 0 | +2,544,216 | −420,440 | 2434 |
| 26.3 GL | 36234196318 | 1,982,608 | 0 | 0 | 36.64 | 4.30 | 5.14 (+5.26) | 42.85 | 0 | +2,688,648 | −277,568 | 4252 |
| 26.3 VK | 36234196318 | 1,982,608 | 0 | 0 | 42.78 | 4.83 | 4.87 (+7.12) | 42.87 | 0 | +2,852,632 | −112,352 | 3953 |
| 26.2 GL | 36234198557 | 1,982,608 | 0 | 0 | 33.64 | 4.24 | 4.26 (+7.30) | 39.73 | 0 | +2,744,376 | −207,528 | 2448 |
| 26.3 GL | 36234198557 | 1,982,608 | 0 | 0 | 50.83 | 6.05 | 6.80 (+6.52) | 46.44 | 0 | +3,000,888 | +36,624 | 3957 |
| 26.3 VK | 36234198557 | 1,982,608 | 0 | 0 | 44.91 | 4.99 | 4.67 (+5.32) | 41.99 | 0 | +3,006,624 | +39,944 | 3788 |
| 26.2 GL local | — | 1,982,608 | 0 | 0 | 15.63 and 0.00 | 0.00 | 0.00 | 30.92, 50.24 | 0 | +1,667,032, +1,975,592 | −71,016, +130,984 | 5514, 5500 |

The local rows are two runs (4c42449 and 8b44741). Windows counts thread CPU in 15.6 ms steps, so the local sampler and
floor figures are 0 or one step and weren't used for the sampler budget.
- `retainedBytes()` is the rings' exact size (frame ring 1 MiB, candidates 320 KiB, events 96 KiB, GC 80 KiB, samples
  416 KiB, and the histograms); it doesn't vary.
- The whole-heap Δ agrees: +2.5 to +3.2 MB while on, and back within ±0.45 MB after off in all but one leg. That leg
  (36233279951 26.2 GL) swung −2 MB and −5 MB, which is the world's own caches; retention isn't gated on the heap.
- The class histogram showed the capture objects while on (Capture, FrameRing, 3 RecordRing, StutterRings) and none
  after off on every leg. The first CI run's 6 "leftovers" were the test's own reference plus the Snapshot classes'
  static `EMPTY`; both are fixed.
- Phase timers: all 5 required bits seen on every leg (`phaseTimersSeen`; static bits, so not this session alone).
- 238-258 samples per run, i.e. 4 Hz.

**Frame hook** (the `java` job, both MC versions per run, best of 5 × 10 M; ns per call). Every case allocated 0 B over
the hot calls. Only the phase-timer case allocated anything over the cold calls: 784-960 B of JVM noise, under the
64 KiB allowance.

| run (commit) | monitor off | monitor on | + phase timers (capped) | uncapped (diagnostic) |
|---|---|---|---|---|
| 36230942906 (09a04ff) | 0.088-0.090 | 25.49-25.73 | 170.65-171.10 | — |
| 36232640349 (31bb058) | 0.084-0.087 | 24.61-24.89 | 164.17-164.42 | 124.45-125.57 |
| 36233278032 (85bdcec) | 0.176 | 34.70-34.83 | 238.34-238.72 | 180.15-181.00 |
| 36233279951 (85bdcec) | 0.176 | 34.69-34.72 | 238.24-238.43 | 180.25-180.59 |
| 36233285459 (85bdcec) | 0.176 | 34.67-34.83 | 238.17-238.68 | 180.30-180.62 |
| 36234193894 (8b44741) | 0.198-0.199 | 37.10-37.12 | 255.55-256.21 | 194.01-194.36 |
| 36234196318 (8b44741) | 0.181-0.189 | 25.23-25.75 | 152.58-152.88 | 115.92-116.73 |
| 36234198557 (8b44741) | 0.181-0.187 | 25.37-25.57 | 152.56-153.11 | 115.50-116.18 |
| local 26.2 (Windows) | 0.112-0.117 | 23.13-24.19 | 161.48-167.37 | 123.55 |

Runs land on 2 runner speeds, 152-171 ns and 238-256 ns for the phase-timer case. The cost is the clock reads: about 19,
21 and 32 ns per `System.nanoTime()`.

**Budgets** (limit = min(ceiling, 2 × the largest value observed in any CI run of the same measured code, rounded up)):

| budget | largest | limit | ceiling |
|---|---|---|---|
| frameHookNsPerCallOn | 37.12 | 75 | 200 |
| frameHookAllocBytesOn | 0 | 0 | 0 |
| frameHookNsPerCallOnPhases | 256.21 | 400 | 400 (F-M3) |
| frameHookAllocBytesOnPhases | 0 | 0 | 0 |
| tickHookNsPerCallOn | 50.24 (local; CI 48.62) | 101 | 2000 |
| tickHookAllocBytesOn | 0 | 0 | 0 |
| tickHookNsPerCallWorld (monitor off, in a world) | 43.28 | 87 | 2000 |
| tickHookAllocBytesWorld | 0 | 0 | 0 |
| monitorOnRetainedBytes | 1,982,608 | 2,621,440 | 2.5 MiB |
| monitorOffRetainedBytes | 0 | 0 | 256 KiB |
| monitorOffLeftoverInstances | 0 | 0 | 0 |
| samplerCpuMsPer60s (steady) | 50.83 | 102 | 120 (coordinator) |

`monitorOnRetainedBytes` stays at the ceiling: 2 × 1.98 MB is above it, and the value is fixed by the ring sizes.

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
- PowerWatcher (WS-P, merged): FootprintGameTest checks `PowerWatcher.isRunning()` and the "RigTune power" thread
  name, at the title and again in the world with the monitor on. The CI runners have no battery (`hasBattery: false`
  in every artifact), so CI covers only the no-battery path. PowerWatcherTest covers the battery path with a fake
  source list (AC4.9).
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
| frame hook (`DebugScreenOverlayMixin` → `FrameTimes.onFrame` + `StutterMonitor.onFrame`) | FrameHookBudgetTest → `frameHookNsPerCallOff` / `frameHookAllocBytesOff` (monitor off: one volatile read each), `frameHookNsPerCallOn` / `frameHookAllocBytesOn` (monitor on) |
| Stutter Doctor phase timers (`MinecraftFrameMixin`: packets, tick and limiter pairs, render start; each one volatile read while nothing captures) | FrameHookBudgetTest → `frameHookNsPerCallOnPhases` / `frameHookAllocBytesOnPhases` (a capped frame with one tick and a chunk load, together with the frame hook); the uncapped frame as a diagnostic; with the monitor off they're part of the monitor-off case's "one volatile read" design (not timed separately) |
| tick hook (`RigTuneClient.onTick` and the monitor's `StutterHooks.tick` on END_CLIENT_TICK) | FootprintGameTest → `tickHookNsPerCall` / `tickHookAllocBytes` (onTick; StutterHooks.tick returns after its service's session check while the monitor is off), `tickHookNsPerCallOn` / `tickHookAllocBytesOn` (both, monitor on, in a world) |
| other Stutter Doctor listeners (CHUNK_LOAD counter, level change, BEFORE/AFTER_SAVE events: one volatile read each while nothing captures) | covered by the monitor-off design; with the monitor on, their per-event cost is a counter or one ring write (FrameRingAllocationTest, StutterMonitorTest) |
| notice evaluation (`NoticeCenter` via `controller.notices()`) | evaluated on screen init and rebuild only (C3; `NoticeSource` is asked by RigTuneScreen.init, never per frame); its cost is recorded as `noticeEvaluationUs` (about 1 µs), and it runs in each of the 20 retention cycles |
| PowerWatcher ("RigTune power", SPEC 4) | FootprintGameTest asserts `PowerWatcher.isRunning()` is false and no such thread exists without a battery, at the title and in the world (AC4.9; CI has none); with a battery its CPU counts in `workerCpuMs5s` like every "RigTune…" thread |
| stutter sampler ("RigTune stutter sampler", SPEC 5) and GC listener | FootprintGameTest: no sampler thread with the monitor off (title) and after it's turned off (world), and the GC listener removed; `samplerCpuMsPer60s` (steady state) with it on, `samplerCpuMsFirst5s` and `samplerJdkFloorMsPer240` as diagnostics |
| retained memory | `rigtuneClassBytesIdle`, `heapGrowthAfterCyclesBytes`, `leakSuspects`; the monitor's rings through `StutterMonitor.retainedBytes()` (`monitorOnRetainedBytes`, `monitorOffRetainedBytes`, F-M1), and `monitorOffLeftoverInstances` (no capture object alive after it's off); the heap after a full GC around the monitor as a diagnostic |
| the startup-time record (SPEC 13: one uptime read at the first title screen, one small file write on a worker thread) | `workerCpuMs5s` (when inside the window); AC13.2 checks in FootprintGameTest: exactly one run per launch, the hub line at 3 sizes |

## SPEC 13 (startup-time trend)

AC13.1: `StartupTimesTest` (11 cases). AC13.2 is FootprintGameTest: exactly one run from this launch, its fields,
and the hub line (`footprint-tools-<w>x<h>-scale<s>`). It also checks the longest case, which one fresh launch can't
produce: a canned 12-run view with a changed mod set, "Last launch 15.1 s · median of the last 10: 14.5 s", the
note and the advice, nothing clipped above Done (`footprint-tools-trend-<w>x<h>-scale<s>`). The standard 3 sizes
are 1280×720@2, 640×480@2 and 854×480@2.
The v040-written set `src/test/resources/v040-written/ws-f/startup-times.json` is written by `StartupTimesFixtureTest`
through the store. WS-H's released-0.3.0 harness (`tools/e2e/compat030.py` from `origin/test/e2e-v04`, run locally
2026-09-26 with the released `rigtune-0.3.0+mc26.2.jar`, the other sets still placeholders) passed 9/9, including "0.3.0
reading them changed no file".
