# WS-S: Stutter Doctor (SPEC 5) — task plan

Branch `feat/stutter`, worktree `C:/Dev/Worktrees/rigtune-stutter`. Sources: SPEC 5 + amendments S-M1, K-M1, F-M1;
docs/research/v0.4/stutter.md; docs/v0.4/design/ws-k.md. TDD for every core class; commit after each task; push often.

## Architecture (decided before coding)
- **core/stutter (pure, unit-tested):**
  - `GcKind`: int flags from (bean, gcAction, gcCause): PAUSE / CYCLE (`"end of GC cycle"`), FULL, EXPLICIT, STALL_HINT,
    MAJOR (live-set sample), PHASE (a pause that is one phase of a concurrent cycle: ZGC/Shenandoah pause beans, G1
    Concurrent GC); `collection(flags)` (counts as one collection); `family(bean)` → g1/zgc/shenandoah/parallel/serial.
  - `GcClock`: anchor (`nanosAtUptimeZero`, spin on the uptime tick), `observe(recvNanos, endMs)` → min offset,
    `pauseStart/pauseEnd` nanos (`+1 ms` guard), `calibrated()`.
  - Rings, each with one writer, allocated on enable, `retainedBytes()` (F-M1): `FrameRing` (render thread: `long[2^17]`
    frame ends with bit 0 = excluded, 9-bucket time-weighted histogram, clamped EWMA, candidate ring `long[4096 x 10]`
    with the frame's phases, phase baselines and chunk loads), `EventRing` (synchronized: saves, level changes,
    teleports, moving fast, pauses), `GcRing` (synchronized, Notification Thread: recv, start, end, flags, usedAfter +
    session totals), `SampleRing` (synchronized, sampler: CPU per thread group + process CPU + build backlog).
  - `SpikeDetector` (rule d > max(2b, b+8 ms, 20 ms), b = median of the previous 120 non-spike gameplay frames, EWMA
    until 30; hitches < 100 ms; severity; excluded frames; a steady run of 60 consecutive spikes re-baselines).
  - `Attributor` (§4.2: GC overlap → packets → ticks → render-as-chunk-build with evidence; tags never claim ms).
  - `StutterAnalyzer` → `StutterReport` (the session summary = stutter.json's session shape, C1 + a few display fields)
    and `StutterFacts`.
  - `StutterAdvisor` (rules `stutterAdvice`, features {"stutter-doctor"}), `StutterStore` (stutter.json on
    `JsonStateFile`, 5 sessions, 10 worst, 64 KiB).
  - `ConditionEvaluator.stutter(...)` (the WS-K stub filled; UNKNOWN without facts, K-M1 value types).
- **client/stutter:** `StutterMonitor` (install, `onFrame`, phase hooks, session/benchmark captures, tick work),
  `GcListener`, `ThreadSampler` ("RigTune stutter sampler", 4 Hz), `BuildBacklog` (guarded Sodium reflection, vanilla
  fallback), `DevStutter` (the two dev switches), `StutterService` (C4). Mixin `MinecraftFrameMixin` (phase timers,
  `require = 0`, S-M1).
- **UI:** `StutterScreen`, the settings toggle, one line on BenchmarkResultScreen.

## Tasks
1. [ ] Plan (this file). Commit.
2. [ ] `GcKind` + `GcKindTest` (every bean/action/cause triple from the research's 5 probe runs, in
   `src/test/resources/stutter/gc-strings.txt`).
3. [ ] `GcClock` + `GcClockTest` (the recorded G1 and ZGC probe tuples + -Xlog truth + System.gc() call windows in
   `src/test/resources/stutter/gc-{g1,zgc}.txt`).
4. [ ] Rings + `FrameRingAllocationTest` (1 M `onFrame` after warm-up allocate 0 bytes; wrap-around; snapshot order;
   retainedBytes).
5. [ ] `SpikeDetector` + `SpikeDetectorTest` (AC5.1 cases + step change + hitch merge + severity).
6. [ ] `Attributor` + `AttributorTest` (AC5.2: the §4.4 worked example exactly, straddling pause, +1 ms guard, cycles,
   tags, claims ≤ lost).
7. [ ] `StutterAnalyzer`/`StutterReport` + `StutterAnalyzerTest` (histogram, header, not enough data, shares, tags,
   facts, worst 10, phase timing unavailable).
8. [ ] ConditionEvaluator stutter keys + `StutterConditionTest`; RulesContractsTest's stub expectation updated.
9. [ ] `StutterAdvisor` + `StutterAdvisorTest` against fixture rules (`src/test/resources/stutter/advice-fixture.json`,
   the research §5.2 seeds): fire/no-fire per seed, unknown key → UNKNOWN, unknown `requires` → skipped, stutter keys
   in a main-list rule → UNKNOWN (Recommender output unchanged).
10. [ ] `StutterStore` + `StutterStoreTest` (64 KiB cap, 5 sessions, corrupt → .bad + empty, newer → untouched);
    `src/test/resources/v040-written/ws-s/` (stutter.json + settings.json with stutterMonitor) written by a test.
11. [ ] Client capture: StutterMonitor, GcListener, ThreadSampler, BuildBacklog, events, the one call in
    DebugScreenOverlayMixin, `StutterMonitor.install()` in RigTuneClient, dev switches.
12. [ ] Phase-timer mixin (S-M1) + "phase timing unavailable".
13. [ ] StutterService (view, off-thread analysis, persistence, summary, clear, pause) + StutterScreen + settings toggle +
    `rigtune.stutter.*` lang keys (WordingTest X4).
14. [ ] Benchmark capture hook (separate method in BenchmarkController) + the result-screen line.
15. [ ] StutterGameTest (AC5.7, both versions, 3 sizes, network off).
16. [ ] Local smoke under the lock on 26.2 (runBenchmarkAutorun: phase timers inject with plausible values); 26.3 via
    CI logs.
17. [ ] README "Stutter Doctor", design doc, self-review (code-reviewer subagent), fixes.
18. [ ] After WS-R's `stutterAdvice` content lands: merge origin/feat/v0.4.0, scenario tests over the bundled seeds.
19. [ ] Finish: merge origin/feat/v0.4.0, build both versions, push, CI green, look at screenshots, docs/v0.4/design/ws-s.md.
