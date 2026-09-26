# WS-B: benchmark history and regression alerts (SPEC 7) as built

Branch `feat/bench-history`. Plan: docs/v0.4/plans/ws-b.md. SPEC 7 as amended by B-H1, B-L1 and X-M1.

## What landed
- **Context fields** (C1): `BenchmarkController` fills `modSetHash` and `journalCursor` in a separate method
  (`withModSet`, one changed line in the constructor) from `client/benchmark/BenchmarkConditions`: the coordinator's shared
  `core/model/ModSetHash` over the loaded **non-builtin mods minus RigTune itself** (a RigTune update is named separately as
  "RigTune A → B", so it never reads as "something outside RigTune changed"), cached for the session; the cursor is the id
  of history.json's newest entry (null when there is none).
- **Comparability** (`BenchmarkHistory.comparable(latest, max)`, `BenchmarkTrend.differences/comparable/contextKey`):
  same MC version, scene, render and simulation distance (knob values) and `Context.sameConditions`; runs without a context
  (0.2.x) only with each other; runs without a result never take part. The hash never splits runs (B-H1). `chart()` is
  unchanged for old callers.
- **Trend maths** (pure `core/benchmark/BenchmarkTrend`): median, MAD (unscaled), `noiseFloorPercent` = 2 × max(latest.cv
  or 5 %, 1.4826 × MAD / median) × 100. The baseline is the newest **10** comparable runs with a result **before** the
  latest (the latest isn't in its own median), at least 3. Regression: delta ≤ −floor; improvement: delta ≥ floor (shown in
  green, never an alert or notice); otherwise "in line with your usual". A 1e-9 tolerance makes "at the floor" count, as in
  `BenchmarkMath.gain`.
- **Not comparable**: with fewer than 3 comparable runs, if the previous run of the same scene has other conditions and its
  1 % low differs past 2 × the larger CV (`BenchmarkMath.gain`), the line is "Performance changed under different
  conditions (<keys>); cause unknown." Within the noise nothing is claimed ("Not enough comparable runs for a trend yet").
- **Change window** (`core/benchmark/ChangeWindow`): by cursor when both runs' cursors are still in history.json (entries
  after the baseline's cursor up to and including the latest's), else by time (baseline.createdAt, latest.createdAt]. Only
  rows that took effect count (APPLIED, or REVERTED later); STAGED/DISCARDED/ABANDONED never ran during either benchmark.
  Rows are History's own (`HistoryModel.build` via `RealController.history()`, described with `HistoryScreen.describe`, so
  WS-A's mod names and WS-P's profile labels flow in). Plus "RigTune A → B" when the runs' versions differ, "Something
  outside RigTune changed too (the mod set differs)" when both hashes are known, differ and the window has no mod-file row,
  and "No change recorded; possibly a driver, OS or other change" when there is nothing at all. An unreadable/newer
  history.json counts as no entries.
- **Stale marker** (`BenchmarkTrend.stale`): MC version, RD, SD, then (with a context) resolution with B-L1's 10 %
  pixel-count tolerance, fullscreen, shaders or the pack (pack only with shaders on), Distant Horizons, and the mod set
  when both hashes are known. Comparability stays exact.
- **Wording** (`core/benchmark/TrendText`, all keys in `rigtune.benchmark.trend.*`, X4-checked by `TrendTextTest`): the
  result/history lines, notice texts, context labels, the last-benchmark line and "Needs a rerun (changed since: …)".
  Dates are the local day as yyyy-MM-dd (language-neutral); SPEC's "Sep 24" example is rendered "2026-09-24".
- **Client**: `TrendService` (view per context, the change window of a regression, acknowledgements; a single-entry memo
  keyed on benchmarks.json's loaded copy, history.json's mtime+size, the context and the current conditions, so the two
  notice sources and the screens share one computation per screen init); `BenchmarkStore` re-reads benchmarks.json when
  its mtime or size changed (another game copy, a hand edit, a game test's seed; also stops a new run overwriting newer
  runs); `BenchmarkResultScreen` shows the trend lines under the gain line (≤ 3 change rows, then "…and N more") and its
  chart now shows comparable runs only (`TrendChart`: bars, a 1 px 1 %-low and average polyline, the dashed "usual" line);
  `BenchmarkHistoryScreen` (context selector = `CycleButton` over the contexts, newest first, inactive with one; the note
  "N comparable runs; M with different conditions not shown"; the trend; the last benchmark + marker; the chart; change
  rows are cut first when the height is short).
- **Notices** (C3): `RegressionNoticeSource` (key `benchmark.regression.<runId>`, not dismissible; Details… opens
  Benchmark history and Got it, both acknowledge the run in awareness.json `acknowledgedRegressions` through
  `AwarenessStore.acknowledgeRegression` → `update`, X-M1; a session set keeps it hidden if awareness.json is newer or
  unreadable); `BenchmarkStaleNoticeSource` (key `benchmark.stale.<runId>`, dismissible → awareness.json `dismissed`, so
  the next run's marker shows again, B-L1; Benchmark… opens the benchmark menu). `AwarenessStore` gained
  `acknowledgedRegressions()` / `acknowledgeRegression(id)` (newest 64 kept).
- **Tier tooltip / share report**: `BenchmarkTrendLines.lastBenchmark(controller)` (reusable Component: the last-benchmark
  line + marker) and `badgeTooltip(graphics, font, controller, badge, x, y, mouseX, mouseY, extraLines)`, called in ONE
  line after the badge is drawn in `RigTuneScreen.extractRenderState` (coordinator decision: the badge has exactly one
  tooltip; whichever of WS-A/WS-B merges second passes the tier-basis lines as `extraLines`). The line is computed once
  per badge instance (a new badge per init), never per frame. `RealController.latestBenchmark()` now delegates to
  `TrendService.latestSummary()` (newest run with a result, `BenchmarkSummary` + optional `conditions`/`rerun`, old
  8-arg constructor kept); `ShareReport` prints "- conditions: …" and "- Needs a rerun (…)".
- **Fixture**: `src/test/resources/v040-written/ws-b/benchmarks.json`, written by
  `BenchmarkCompatibilityTest.theV040WrittenFixtureIsWhatThisVersionWrites` (a 0.3.0-shaped run, three 0.4 Measure runs
  with both fields, the first without a cursor, and a 0.4 Tune run).

## Verification
- Unit (both versions): BenchmarkHistoryTest (AC7.1, 4 new), BenchmarkTrendTest (AC7.2 + marker + view, 17),
  ChangeWindowTest (AC7.3, 7), BenchmarkCompatibilityTest (AC7.4, 3 new: pinned 0.2.0 and 0.3.0 readers, 0.3.0 rewrite),
  BenchmarkResultScreenTest (AC7.5 truth table, 7 new), TrendTextTest (6), ShareReportTest (+1), AcknowledgedRegressionsTest (3).
- Released-jar harness (WS-H's tools/e2e/compat030.py from origin/test/e2e-v04, run locally against the released
  rigtune-0.3.0+mc26.2.jar with the placeholder sets + the real ws-b set): RESULT PASS, "BenchmarkHistory: benchmarks.json
  loads without a .bad: runs 5 of 5, unreadable false, contexts kept true".
- BenchmarkHistoryGameTest (AC7.5, CI, both versions): see the CI run in the hand-back.

## Deviations / decisions
1. "Your usual" excludes the latest run (median of the comparable runs before it). AC7.5's "4 comparable runs + a
   regressed latest run" is then 4 baseline runs.
2. Not-comparable wording needs a real difference (past the noise) against the previous run of the scene; otherwise no
   claim at all. "Previous run" is the newest earlier run with a result of the same scene (any MC version: the version is
   then one of the named keys).
3. The regression notice has no × (acknowledging is its dismissal) so one store (`acknowledgedRegressions`) holds it.
4. Dates render as yyyy-MM-dd, not "Sep 24" (no locale-specific month names in a translated string).
5. The tier tooltip shows only when the badge fits the title row (at 640×480@2 it's inside the header's last line, a
   case WS-A's tooltip covers); Benchmark history and the stale notice show the marker at every size.
6. `BenchmarkGameTest` now puts benchmarks.json back after its runs (coordinator-approved) and checks `modSetHash` on
   real runs, so later game-test classes don't see a stale-benchmark notice.

## UNVERIFIED
- AC7.6 (Phase 5 real run) not done here.
- The noise floor is from same-session data (research A2); retune once real cross-session history exists.
