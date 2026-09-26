# WS-S: Stutter Doctor (SPEC 5) — design notes as landed

Branch `feat/stutter`. Plan: docs/v0.4/plans/ws-s.md. Sources: SPEC 5 + amendments S-M1, K-M1, F-M1; research
docs/research/v0.4/stutter.md; contracts docs/v0.4/design/ws-k.md.

## What landed

**core/stutter (pure, JUnit):**
- `GcKind`: GC notification (bean, gcAction, gcCause) → int flags: PAUSE / CYCLE (`"end of GC cycle"` only), FULL,
  EXPLICIT, STALL_HINT (ZGC "Allocation Stall", Shenandoah "Allocation Failure"), MAJOR (a live-set sample), PHASE (one
  pause phase of a concurrent cycle: ZGC/Shenandoah pause beans, G1 Remark/Cleanup); `collection(flags)`; `family(bean)`.
- `GcClock`: uptime anchor (spin on the tick) + `offset = min(receive − endMs)`; pauses map to
  `[start + offset, end + offset + 1 ms]`; `Calibration` copy for the analysis thread.
- Rings (allocated when capture starts, released when it stops, allocation-free writes): `FrameRing` (render thread:
  `long[2^17]` frame ends, bit 0 = excluded; 9-bucket time-weighted histogram; candidates `long[4096 × 10]` with the
  frame's phases, the phases' running baselines and chunk loads), `RecordRing`, `StutterRings` (events, GC records +
  GcClock, 4 Hz samples). Benchmark capture uses a smaller `FrameRing(2^15, 1024)`.
- `SpikeDetector`, `Attributor`, `StutterAnalyzer` → `StutterReport` (the stutter.json session shape) + `StutterFacts`,
  `StutterAdvisor` (the only evaluator knowing `stutter-doctor`), `StutterStore` (stutter.json on `JsonStateFile`),
  `StutterSummary` (the Copy summary text), `StutterView` (reshaped: monitorOn, recording, paused, analysing, live,
  report, advice).
- `ConditionEvaluator.stutter(...)`: WS-K's stub filled (the dispatch line was already there).

**client/stutter:** `StutterMonitor` (MC-free hot path: `onFrame`, phase hooks, chunk counter, exclusion flags, the two
capture slots), `StutterCapture` (starts/stops captures with the shared rings, `GcListener`, `ThreadSampler`, dev GC
thread), `GcListener`, `ThreadSampler` ("RigTune stutter sampler"), `BuildBacklog` (guarded Sodium reflection via
MethodHandles, vanilla `SectionRenderDispatcher.getCompileQueueSize()` fallback), `StutterHooks` (Fabric events, the
tick, the benchmark API, game-test probes), `DevStutter`, `StutterService` (C4). Mixin `MinecraftFrameMixin`.

**UI:** `StutterScreen` (scrollable list: status, header, histogram bars, causes with "Not explained", tag counts,
worst 10 with ●●●/●●/● markers, advice with the launcher's memory steps for `ram-` ids; Start/Stop, Pause/Resume,
Clear, Copy summary, Done), the settings toggle (`RigTuneSettingsScreen.stutterMonitorRow`), the benchmark result line
(`BenchmarkResultScreen.stutterLine` → `StutterScreen.benchmarkLine`), `rigtune.stutter.*` strings.

**Hotspot edits (one line / one small method each):** DebugScreenOverlayMixin (+1 call), rigtune.client.mixins.json
(+1 entry), RigTuneClient (`StutterHooks.install(real.stutterService())`), BenchmarkController (`stutterSweep(boolean)`
+ 2 calls at FrameTimes.start/stop + `StutterHooks.benchmarkFinished(!run.cancelled())` in `finish()`),
BenchmarkResultScreen (`stutterLine`), RigTuneSettingsScreen (`stutterMonitorRow`), ConditionEvaluator (the private
method + `GC_COLLECTORS`), en_us.json (the `rigtune.stutter.*` block), README "Stutter Doctor".

## Decisions and deviations (and why)
1. **`StutterHooks.install(service)` instead of `StutterMonitor.install()`** (SPEC's name): StutterMonitor stays free of
   Minecraft types so WS-F's FrameHookBudgetTest (and StutterMonitorTest) can call `onFrame` in plain JUnit. Still one
   line in RigTuneClient.
2. **Render phase:** claims milliseconds only as **chunk building**, and only with evidence (a Sodium build backlog in the
   sampler window, busy = total; the vanilla compile queue > 0; or Chunk Updates ZERO_FRAMES/ONE_FRAME). Without
   evidence the render excess stays unexplained with a `render:low` note. This is what the research's worked example
   (AC5.2) requires (frames 1800/2400 carry render excess but are "unexplained"). `causes.render` is therefore normally
   absent; the key stays in the vocabulary (C1, the rules' `stutterShareAtLeast`).
3. **Spike rule extension:** a run of 60 consecutive spikes re-baselines (those frames become the baseline and stop
   counting). The strict rule ("previous 120 non-spike frames") never recovers from a lasting drop to under half the
   frame rate (every frame would be a spike forever). AC5.1's drift case passes with or without it; a step case is tested.
4. **Counts are collections:** `gcExplicitPauses` / `gcFullPauses` / `gcStalls` count collections (`GcKind.collection`),
   so one ZGC `System.gc()` (8 pause-phase notifications + 1 cycle) counts once. Full = FULL and not EXPLICIT.
5. **Live set** = the median old-generation usage after MAJOR collections (G1 Remark/Cleanup and full GCs, ZGC major
   cycles, Shenandoah cycles, Serial/Parallel full), over `Runtime.maxMemory()`; null (→ UNKNOWN, fail closed) when none.
6. **`cpuContentionShare`** = % of the capture's sampler windows in which the process used ≥ 85 % of all cores.
   `heapRaiseRoomMb` = min(RAM / 2, RAM − 4096) − heap (null when RAM is unknown).
7. **Exclusions:** menus and an unfocused window are flagged per frame (bit 0 of the frame end, set from the tick); the
   10 s after `AFTER_CLIENT_LEVEL_CHANGE` are world loading; the first frame of a capture and the frame after a pause span
   a gap and are excluded. "After teleport" (10 s) and "moving fast" are **tags**, not exclusions (SPEC 5; the research
   had teleports excluded).
8. **Phase timers (S-M1):** 7 injectors, all `require = 0, expect = 0`; each handler sets a bit on its first call while
   capturing; phase timing = all 5 required bits (packets pair, ticks pair, render start) and the limiter pair both or
   neither (the limiter runs only while the frame rate is capped). The render phase is closed by `onFrame` (renderFrame
   start → frame end, minus the limiter).
9. **Benchmark capture:** on during each step's sweeps only (paused through settle/warm-up); analysed synchronously on
   the render thread when the run ends (≤ 32k frames, a few ms) so the result screen can show its line; finished runs
   are also saved to stutter.json (`source: benchmark`); cancelled runs are dropped.
10. **Live analysis** refreshes every 5 s while StutterScreen is open (C4 has no refresh call; `stutter()` triggers it).
11. **Clear** deletes the saved summaries and restarts the running session's buffers.
12. **stutter.json** carries C1's fields plus `sessionSeconds`, `avgFps`, `onePercentLowFps`, `enoughData`, `phaseTiming`
    (the screen's header for a saved session); `histogramTimeMs` in whole ms; `causes` as 0-1 fractions; `tags` as
    counts; the advice ids only (titles come from the current rules).
13. **Sampler / backlog threading:** the backlog is read on the render thread (tick, every 5 ticks) and published through
    volatiles; the sampler thread never touches Minecraft objects.
14. **GcClockTest tolerance (AC5.3):** the ZGC probe run (129 notifications) calibrates 0.17 ms from the -Xlog truth
    (within 0.3). The only G1 run whose receive times were kept has 21 notifications and calibrates 0.32 ms above the
    -Xlog value (the research's 300-pause G1 runs measured +0.08/+0.23 ms but kept only the estimates), so the G1 case
    asserts 0.35 ms; both runs map every System.gc() pause inside its call window.
15. **Settings toggle keys** are `rigtune.stutter.monitor(.tooltip)` (inside this workstream's C5 block).
16. **Tests owned elsewhere, touched:** RulesContractsTest's stutter expectation (stub → decided with facts);
    KnowledgeV2ScenarioTest's stutter part of the fires-nothing check moved into StutterSeedScenarioTest (WS-R handoff).

## Verification
- Unit tests (26.2 local): 1298 → see the final report for both versions. New: GcKindTest, GcClockTest,
  FrameRingAllocationTest (AC5.4), SpikeDetectorTest (AC5.1), AttributorTest (AC5.2, the worked example exactly),
  StutterAnalyzerTest, StutterConditionTest, StutterAdvisorTest (fixture rules) + StutterSeedScenarioTest (bundled r14
  seeds) (AC5.5), StutterStoreTest (AC5.5), StutterSummaryTest, StutterMonitorTest (hot path 0 bytes, lifecycle,
  exclusions, S-M1 verdict, per-frame cost), StutterWrittenFixtureTest (v040-written/ws-s).
- The released-jar harness (tools/e2e/compat030.py, released 0.3.0 jar) passes with the real ws-s set.
- **Phase timers:** 26.2 local dev client (runBenchmarkAutorun under the lock, Sodium, 2525 FPS): "phase timing ok
  (timers seen 11111; render 0.45 ms per frame)". CI (run 36225350621): "timers seen 11111" on all three legs (26.2
  OpenGL, 26.3 OpenGL, 26.3 Vulkan) with plausible baselines (packets 0.02-0.42 ms, ticks 0.10-0.99 ms, render 10-86 ms
  under software rendering). They ship.
- **Per-frame cost** (StutterMonitorTest, local, 10 M calls): 0.4 ns off, ~24 ns on, ~124 ns on with all 6 phase-timer
  calls and a chunk load per frame. SPEC 10's budgets are WS-F's FrameHookBudgetTest.
- Memory while on: 1.98 MB (`StutterMonitor.retainedBytes()`: frame ring 1 MiB + candidates 320 KiB + events 96 KiB +
  GC 80 KiB + samples 416 KiB), 0 when off (F-M1).

## UNVERIFIED / left for Phase 5
- AC5.8 induced runs A-F (and S-M1's missing-target run showing "phase timing unavailable"); the teleport dev script
  (`-Drigtune.dev.stutterScript=teleport`, e.g. via `JAVA_TOOL_OPTIONS` on `runClient`) compiles and is wired but hasn't
  been run end to end; `-Drigtune.dev.forceGcEverySec` likewise.
- The `DH-` thread prefix at runtime, whether autosave causes client spikes, full-GC durations on a multi-GB heap
  (research §10).
- FootprintGameTest's monitor-on numbers (AC10.4) are WS-F's follow-up (F-L1).
