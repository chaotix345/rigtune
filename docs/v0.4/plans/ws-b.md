# WS-B plan: benchmark history and regression alerts (SPEC 7, AC7.1-AC7.5)

Branch `feat/bench-history`, worktree `C:/Dev/Worktrees/rigtune-benchhist`. Scope: PLAN.md "WS-B"; SPEC 7 as amended by
B-H1 (modSetHash is not part of comparability: it feeds the "needs a rerun" marker and the change window), B-L1
(BENCHMARK_STALE dismissible per latest-run id; < 10 % pixel-count change = same resolution for the marker only) and
X-M1 (acknowledgedRegressions through `AwarenessStore.update`). Research: bench-history-a11y.md Part A, external-review §2/§3.

Decisions (details in docs/v0.4/design/ws-b.md):
- Comparable = same MC version, scene, render and simulation distance (knob values), `Context.sameConditions`; runs
  without a context compare only with each other. A run without a result never takes part.
- Baseline = the newest (up to 10) comparable runs **before** the latest with a result; at least 3. median/MAD over their
  1 % lows; floor = 2 × max(latest.cv or 5 %, 1.4826 × MAD / median) × 100. Regression: delta ≤ −floor; improvement:
  delta ≥ floor (never an alert); otherwise "in line with your usual".
- Not comparable (fewer than 3 comparable runs, and the previous run of the scene differs in conditions and its 1 %
  low differs past 2 × the larger CV): "Performance changed under different conditions (<keys>); cause unknown."
- Change window: journal entries after the baseline's cursor up to the latest's (both found), else `at` in
  (baseline.createdAt, latest.createdAt]; rows that took effect (APPLIED/REVERTED); rendered with History's own
  descriptions; RigTune version change; differing hash without a file change → "Something outside RigTune changed
  too"; nothing → "No change recorded; possibly a driver, OS or other change".
- modSetHash = the coordinator's shared `core/model/ModSetHash` over the non-builtin loaded mods minus RigTune itself
  (its own update is named as a RigTune version change); journalCursor = id of the newest history.json entry.
- Regression notice: not dismissible; actions Details… (opens Benchmark history) and Got it, both acknowledge the run id
  in awareness.json `acknowledgedRegressions`. Stale notice: key per latest-run id, dismissible (awareness.json
  `dismissed`), action Benchmark… (opens the benchmark menu).

## Task 1: comparability (AC7.1)
Files: `core/benchmark/BenchmarkTrend.java` (Difference, differences, comparable, contextKey), `BenchmarkHistory.comparable(latest, max)`.
- [x] Red: BenchmarkHistoryTest `comparableKeepsOnlyMatchingRuns` (each key varied: MC version, scene, RD, SD, width, height, fullscreen, shaders, pack, DH, protocol; hash ignored), `comparableIsOldestFirstAndCapped`, `runsWithoutAContextCompareOnlyWithEachOther`, `runsWithoutAResultAreLeftOut`.
- [x] Green; commit.

## Task 2: trend maths (AC7.2)
Files: `core/benchmark/BenchmarkTrend.java` (median, mad, noiseFloorPercent, Regression, Assessment, assess).
- [x] Red: BenchmarkTrendTest: median/MAD of fixed arrays (odd, even, one value); floor truth table (below → in line; at → regression; above → regression; n < 3 → no alert whatever the delta; cv null → 5 %; MAD term wins); improvement never a regression; anchored at the newest comparable run before the latest; different conditions → keys, no delta.
- [x] Green; commit.

## Task 3: change window (AC7.3)
Files: `core/benchmark/ChangeWindow.java`.
- [x] Red: ChangeWindowTest: by cursor (labels and order); cursor pruned → timestamps; by timestamps; RigTune version change; differing hash without a journal file change → outside; with one → not; empty window → nothing recorded; staged/discarded/abandoned rows left out.
- [x] Green; commit.

## Task 4: the "needs a rerun" marker
Files: `core/benchmark/BenchmarkTrend.java` (Current, stale).
- [x] Red: BenchmarkTrendTest `staleMarker*`: resolution under 10 % pixel change = same, 10 % or more = different; fullscreen, shaders, pack (only with shaders on), DH, mod set (only when both hashes known), RD, SD, MC version; a run without a context: only RD/SD/MC.
- [x] Green; commit.

## Task 5: context fields in BenchmarkController + compatibility (AC7.4)
Files: `client/benchmark/BenchmarkController.java` (separate `withModSet` method), `BenchmarkCompatibilityTest`, `src/test/resources/v040-written/ws-b/benchmarks.json`.
- [x] Merge origin/feat/v0.4.0 for `core/model/ModSetHash`.
- [x] Red: BenchmarkCompatibilityTest: a 0.4 file with the new fields loads in the pinned 0.2.0 and 0.3.0 readers (no `.bad`, every other field equal); 0.3.0 rewrite drops only the two fields; the test writes the v040-written/ws-b fixture and asserts the committed copy equals it.
- [x] Green (context population compiles; BenchmarkGameTest asserts `modSetHash` on real runs); run tools/e2e/compat030.py against the fixture if available; commit.

## Task 6: TrendService + view
Files: `client/benchmark/TrendService.java`, `BenchmarkTrend.View` (reshaped), `client/benchmark/BenchmarkStore.java` (reload when the file changed on disk).
- [x] View: selected context, contexts (key, label, runs), points (≤ 10, oldest first), median, assessment, change window, last run + stale keys, comparable/other counts.
- [x] Unit where pure (grouping in core `BenchmarkTrend.view(...)`: BenchmarkTrendTest `viewGroupsByContext`); commit.

## Task 7: BenchmarkResultScreen lines (AC7.5 unit half)
Files: `client/ui/BenchmarkTrendLines.java` (new), `BenchmarkResultScreen.java`, `client/ui/TrendChart.java` (new; shared chart: bars, 1 %-low/avg polyline, median line).
- [x] Red: BenchmarkResultScreenTest truth table: regression (+ changes, outside, nothing recorded), in line, improvement, too few, different conditions, no result.
- [x] Green; the result screen's chart shows comparable runs only; commit.

## Task 8: BenchmarkHistoryScreen
- [x] Context selector (CycleButton), note "N comparable runs; M with different conditions not shown", assessment + changes, last benchmark line with "needs a rerun (…)", chart; fits 640×480@2; commit.

## Task 9: notices, tooltip line, share report
Files: `RegressionNoticeSource`, `BenchmarkStaleNoticeSource`, `AwarenessStore` (acknowledgedRegressions accessors), `RigTuneScreen` (one hook line), `ShareReport` (last-benchmark context line), lang keys.
- [x] Red: AwarenessStoreTest-style `acknowledgedRegressions` round trip; ShareReportTest last-benchmark line with context and rerun marker; X4 wording check over `rigtune.benchmark.trend.*`.
- [x] Green; commit.

## Task 10: BenchmarkHistoryGameTest (AC7.5 game half)
- [x] Seeded benchmarks.json (4 comparable runs + a regressed latest) and history.json (a mod update between); network off; the regression line, "Changes since then (may be related)" naming the update, the chart and the "not shown" note; the regression notice on RigTuneScreen, acknowledge; after changing the seeded context's resolution: "needs a rerun"; screenshots at 1280×720@2, 640×480@2, 854×480@2; files restored. BenchmarkGameTest restores benchmarks.json (coordinator-approved) and checks modSetHash.
- [x] Push; CI green; download and look at the screenshots; commit.

## Task 11: finish
- [ ] code-reviewer subagent on the diff; fix high/medium findings.
- [ ] Merge origin/feat/v0.4.0, `./gradlew build`, push, CI green; `docs/v0.4/design/ws-b.md`.
