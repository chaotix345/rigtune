# fix-9: review round 3 fixes (X3-1, SE2-RESIDUAL) and the tick-hook timing flake

Branch `fix/review-9` (from `origin/feat/v0.4.0` @ d1cc16a). Findings: docs/reviews/review-9.md (X3-1 medium,
SE2-RESIDUAL low), plus the coordinator's footprint flake (run 36255335999, a docs-only change: "tickHookNsPerCallWorld
91.62 > 87" on the 26.2 OpenGL leg).

## What landed

| item | fix | tests |
|---|---|---|
| X3-1 | A benchmark run **ends** a running monitor session instead of pausing it. `StutterHooks.benchmarkStarted()` (one line in `BenchmarkController.begin()`, before `prepare()` changes any setting) ends the session as leaving the world does: `aroundBenchmark` set, so it's saved only with 2 minutes of gameplay (P5A-F3), and its rings, the shared rings, the GC listener and the sampler are released. No session starts while the run lasts (`benchmarkRunning`, and never while a benchmark capture exists). The benchmark's capture starts at the first sweep as before; that path also ends any session first (safety net). `benchmarkFinished` stops the benchmark capture, then starts a fresh session if the monitor is on and a world is loaded (`aroundBenchmark`, paused if the ended one was), then analyses the run's copy. `StutterMonitor.startSession`/`startBenchmark` refuse a second capture (`IllegalStateException`). Peak retained = one capture's rings + shared: session 1,900,688 + 606,208 = 2,506,896 B, benchmark 475,280 + 606,208 = 1,081,488 B, both under 2,621,440. The budget is unchanged. | StutterMonitorTest.oneCaptureAtATime (red first: nothing was thrown; reads the limit from tools/footprint-budgets.json); FrameRingAllocationTest.retainedBytesCountTheRings (benchmark ring accounting; both rings together would exceed the budget); FootprintGameTest (CI, real client): monitor on in a world, then `benchmarkStarted` + a sweep + `benchmarkFinished`: the session ended before the benchmark's capture started, exactly one capture at a time, a fresh session afterwards; `monitorOnRetainedBytes` is the larger of the session's and the benchmark's capture (both also in the JSON as `monitorSessionRetainedBytes` / `monitorBenchmarkRetainedBytes`); BenchmarkGameTest waits for both benchmark-world session ends (the one the run ended, the fresh one ended with the world) before checking no `monitor` summary was saved. |
| SE2-RESIDUAL | `BenchmarkResultScreen.reason()` shows a failed step's exception message (possibly another mod's) through `SafeLiteral.of` instead of a bare `Component.literal`. The grep of `src/client/java` for `Component.literal` and exception messages found one more outside string: the download-failure status's `error.getMessage()` as a translatable argument (RealController), now `SafeText.clean`. Every other `Component.literal` there holds numbers, separators or RigTune's own strings. | TextsTest.outsideTextIsInert (red first: the formatting code and the RLO came through). The download status is a one-call change covered by SafeTextTest. |
| footprint flake | FootprintGameTest times the tick hooks (`tickHookNsPerCall`, `tickHookNsPerCallWorld`, `tickHookNsPerCallOn`) as the best of 5 blocks of 100,000 calls (was 3), as FrameHookBudgetTest does for the frame hook (RUNS = 5, unchanged). Budgets and ceilings unchanged. | CI (the gate itself). |

## Decisions

1. **End at the run's start, not only at the first sweep.** The task said "when a benchmark sweep starts"; the first
   sweep comes after the first step's settle and warm-up, by which time the run has uncapped the frame rate, hidden the
   HUD and applied the step's knobs. Ending there would judge the saved session's advice against the benchmark's
   settings (render distance of step 1, uncapped FPS) and count the settle frames as play. Ending in `begin()` before
   `prepare()` uses the player's own settings. The sweep-time path stays as a safety net for any caller that doesn't
   call `benchmarkStarted` (FootprintGameTest calls both, as the controller does).
2. **The fresh session after the run is `aroundBenchmark` too.** Otherwise the benchmark world's short post-run session
   would be saved at world exit (P5A-F3 regression). Trade-off: a session that used to span the run is now two
   halves judged separately against the 2-minute gate; 1.5 min before plus 1.5 min after a run is no longer saved.
3. **Refuse, don't share, in StutterMonitor.** The throw can't fire from StutterService's paths (every start is
   preceded by the matching end on the render thread); it turns any future path that would hold both rings into a
   loud failure in tests instead of a silent budget breach.
4. **Shared rings are released and recreated at the handover** (GC listener and sampler restart), as `clear()` already
   does; each start gets its own sampler worker (review-8 ST-1), so the quick stop/start is safe.

## Self-review

A code-reviewer subagent reviewed d1cc16a..6bed87b: 0 high, 1 medium, 5 low. It confirmed that every path from
`begin()` reaches `finish()`, that `benchmarkRunning` can't stay set, and that the new `IllegalStateException` can't fire
from StutterService's paths. It also confirmed there's no per-frame allocation and that the BenchmarkGameTest count of
two session ends is guaranteed.
- M1 (fixed): after a failed tick (StutterHooks off until the monitor is turned on again), `benchmarkFinished` started
  a fresh session that nothing would end on leaving the world. It now skips the start when `StutterHooks.failed()`.
- L3 (fixed): FootprintGameTest now lets 20 ticks pass between the run's start and its first sweep. This checks that
  tick() starts no session during the run. It also pauses the session first and checks that the fresh one comes back
  paused.
- L4 (fixed): `StutterCapture.stop` detaches the capture in a `finally`, even when its copy fails. Before, a
  benchmark capture left behind would have kept the session monitor off for the rest of the game.
- L1 (not changed): after a run in the benchmark world with the monitor on, `benchmarkFinished` starts a session. The
  world exit ends it on the next tick (buffers, a sampler thread, one "session not saved" line). Starting it from
  tick() instead would make BenchmarkGameTest's count of session ends depend on the harness's AWAITING_EXIT timing.
  So the churn stays: it lasts one tick, once per run.
- L2 (not changed, pre-existing): `end()` counts a session as ended in a `finally` that begins after the analysis. An
  analysis exception would skip the count and time out BenchmarkGameTest, which is a failure worth seeing.
- L5 (not changed): decision 2's trade-off (both halves of an in-place run's session face the 2-minute gate). This
  keeps P5A-F3's rule for sessions a run interrupts.

## Not done (blocked)

- The coordinator's first request for the flake, raising every `*NsPerCall` limit in tools/footprint-budgets.json to
  min(ceiling, 4 x the largest observed value), was refused by the session's permission classifier (a change to a
  shared CI gate needs the user's approval). Not retried; the file is unchanged. The best-of-5 measurement above is
  the coordinator's replacement.

## Verification

- `./gradlew build`: 26.2 and 26.3 green, 1842 unit tests each (0 failures, 1 skipped).
- CI: see the run linked in the hand-back.
