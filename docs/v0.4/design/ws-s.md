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

17. **No advice without enough data** (self-review H1): `StutterAdvisor.evaluate(rules, ctx, enoughData)`; fewer than 3
    spikes or under 2 minutes of gameplay shows "Not enough data" and no advice (also for short benchmark captures).
    AC5.8 B's forced-GC run therefore needs at least 2 minutes of gameplay for `stutter-gc-explicit` to show.
18. **Fail closed on what wasn't measured** (self-review M2): `StutterFacts` gained `gcMeasured` and `unmeasured` (old
    10-arg constructor kept = everything measured). The gc* counts are UNKNOWN without a GC listener; the `gc` share
    without a calibrated clock; `chunkLoad`/`chunkBuild`/`tick` without phase timing; `render` always (it never claims);
    `dh`/`cpuContention` without sampler data. So `not {gcFullPausesAtLeast: 1}` can't fire on "never measured".
19. **Hitches** (self-review M5): the report counts spikes (as the research's worked example does) and also hitches
    (spikes < 100 ms apart once): header "12 spikes (...) in 9 hitches", stutter.json `hitches`.
20. **stutter.json I/O is ordered** (M3/M4): saves, clears and the saved-summary load go through one chained future on
    Probes.EXECUTOR; a Clear bumps a generation so an older save can't bring its summary back; the summary shows as
    saved only when the write succeeded; CLIENT_STOPPING waits up to 2 s for a queued save before saving the running
    session.
21. **A running session pauses during a benchmark run** (L6) and resumes when it ends, so its sweeps aren't counted twice.

## Self-review
A code-reviewer subagent reviewed the diff: 1 high, 4 medium, 12 low, no crash-class bug on the hot path. Fixed: H1, M2-M5
(above), L6-L8 (session paused during a benchmark; retry after a failed tick when the monitor is turned on again;
movement state reset at each capture start), L10-L12 (list width `width - 32`; bar label/value columns sized to the
widest text, checked by the game test; a hand-edited stutter.json with nulls or no `source` reads safely), L14 (above),
L15 (the vanilla backlog needs a queue of 8+), L16 (a candidate at the ring's first frame counts). Not changed, with
reasons: L9 (RigTuneSettingsScreen already drops its note line when it doesn't fit; at 640x480@2 the new row pushes it
out, as designed); L13 (the benchmark's analysis stays synchronous at the run's end: ≤ 32k frames, a few ms, and an async
line would need a BenchmarkResultScreen refresh in WS-B's file); L17 (the uptime anchor cancels out of the pause mapping,
but it makes the stored `gcOffsetMs` exact; ≤ 1 ms once per capture start). The thread-name census stays at INFO (it's
Phase 5's evidence, research §6).

## Verification
- Unit tests after merging origin/feat/v0.4.0 (WS-W, WS-B, WS-F, WS-J): 1501 on 26.2 and 1501 on 26.3, all green locally
  (`./gradlew build`) and in CI (run 36227519166: java, python, rules and all three game-test legs green; StutterGameTest's
  screenshots stutter-off, stutter-{1280x720,640x480,854x480}-scale2 and stutter-saved looked at on every leg). New: GcKindTest, GcClockTest,
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

- **Dev switches, end to end** (local, 26.2, `JAVA_TOOL_OPTIONS="-Drigtune.dev.stutterScript=teleport
  -Drigtune.dev.forceGcEverySec=5" ./gradlew :26.2:runClient` under the lock; run dir options.txt with
  `onboardAccessibility:false`): monitor on → benchmark world → 20 s still → teleport → 30 s → full save → 10 s → report →
  leave → stutter.json logged → quit, "PASSED". Report: 63 s session, 52 s gameplay, 19 spikes, GC 88 % of the lost time
  (the forced full GCs, ~100 ms each on an 8 GB heap), 11 of 19 spikes after the teleport, `stutter-gc-explicit` fired,
  phase timers 1111111 (limiter pair too: frame rate capped), GC offset 17.9 ms. (A dev check, not AC5.8's evidence: the
  forced GCs dominate the teleport part; run C is without them.)

## UNVERIFIED / left for Phase 5
- AC5.8 induced runs A-F, and S-M1's missing-target run showing "phase timing unavailable".
- The `DH-` thread prefix at runtime, whether autosave causes client spikes, full-GC durations on a multi-GB heap
  (research §10).
- FootprintGameTest's monitor-on numbers (AC10.4) are WS-F's follow-up (F-L1).
