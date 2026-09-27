# WS-B: benchmark fixes and benchmark-screen accessibility (v0.5)

Branch `fix/v05-benchmark` (worktree `rigtune-bench5`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/SPEC.md 2B (BH-1, BH-2, RW-5..RW-9, RW-15's benchmark side) and 2A (L3); PLAN "WS-B". RW-6 is
detect, name and exclude only (amendment SPEC-7: no pause; `benchmark-restore.json`, `RestoreMarker` and `ModToggles`
unchanged). This file is first the TDD task plan (committed before any code), then the record of what landed.

Paths: `client/…` = `src/client/java/io/github/chaotix345/rigtune/client/…`, `core/…` =
`src/main/java/io/github/chaotix345/rigtune/core/…`, `gametest/…` = `src/gametest/java/io/github/chaotix345/rigtune/gametest/…`.

## TDD task plan

Each task: the red test first (for the audit's P0.2 items the throwaway `AuditVerifyBenchmarkTest` from
`<scratch>/audit-verify/tests/` is the starting point: `bh1NoteAndTrendAgree`, `bh2ChangeStagedDuringTheLatestRunIsNotListedForIt`),
then the code, then a commit. Local: `./gradlew :26.2:test --tests '<classes>'` in a build slot; game tests on CI.

### Part 1 (PLAN-16 milestone, merged early; WS-T's client part starts from it)

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| B1 | **RW-5**: an incomplete step (settle timed out) is "not measured": neither a pass nor `lowestFail`. The search bisects below it as below a provisional bound; once the search is done (converged or at `maxRdSteps`) the lowest incomplete distance that could still change the answer (above the best pass, below the lowest complete fail) is measured again **once**, outside `maxRdSteps`; `BenchmarkSession`'s deadline check decides whether it still fits. Without it, the reason says "N couldn't be measured", never that it failed. The result screen's line and table mark say the same (no ✘). | `core/benchmark/RenderDistancePlanner`, `client/ui/BenchmarkResultScreen` (line + mark), new `core/benchmark/ResultNotes` (the result screen's v0.5 lines as `Text`) | `RenderDistancePlannerTest.rw5AnIncompleteStepIsMeasuredAgainAfterTheLowerSteps` (record(32, incomplete), 17..31 pass → `next()` = 32), `.rw5ACompletePassWhenMeasuredAgainMeetsTheMaximum`, `.rw5WithNoTimeLeftItCouldntBeMeasured`, `.rw5AnIncompleteStepIsMeasuredAgainOnlyOnce`; the E-M1 test `anIncompleteStepNeverPasses` changed to the new rule; `BenchmarkSessionTest.rw5TheRemeasureRunsOutsideTheStepLimit`, `.rw5NoRemeasurePastTheDeadline`; `ResultNotesTest.rw5UnmeasuredDistancesAreNamed` | AC2B.3 |
| B2 | **RW-15**: a step whose settle timed out isn't recorded in the benchmark's stutter capture: for that step BenchmarkController calls `StutterHooks.benchmarkStepExcluded(true)` instead of `benchmarkSweep(true)` and `(false)` instead of `benchmarkSweep(false)` (so the capture stays paused through it, whatever the seam does); RW-5's re-measure, when it settles, records normally. The result screen names how many steps were left out (from `Outcome.settles()`). | new `client/benchmark/StutterSteps` (the per-step decision, testable without a game), `client/benchmark/BenchmarkController`, `BenchmarkResultScreen`, `ResultNotes` | `StutterStepsTest.rw15AnIncompleteStepIsLeftOutOfTheCapture` (fake hooks: a complete step sweeps true/false, the incomplete one only excluded true/false, never a sweep; a cancel inside it closes it), `BenchmarkControllerOutcomeTest.rw15StepsLeftOutCountsTheTimedOutSettles`, `ResultNotesTest.rw15TheLineNamesTheStepsLeftOut` | AC2B.9 (WS-B half; closes with the later of WS-B/WS-S) |
| B3 | **The three context fields + RW-8**: the run's context gets `worldFresh` (benchmark world: whether this open created the save; CURRENT: null), `stagedAtStart` (the ids of history.json changes still STAGED at the start, at most 64, else left out; `[]` when none) and `dhGenerating` (null until B8). **RW-8/RW-6 exclusion**: a run with `worldFresh` or `dhGenerating` true stays out of the trend's baseline and median and out of "different conditions", and as the latest run is assessed `EXCLUDED` (never a regression, no notice), with a line naming why ("first run in a new benchmark world" / "Distant Horizons was generating terrain"). | `client/benchmark/BenchmarkWorld` (`createdThisOpen()`), `BenchmarkConditions` (`stagedAtStart()`, pure `stagedIds(...)`), `BenchmarkController` (context in the constructor), `core/benchmark/BenchmarkTrend` (`excluded`, `Kind.EXCLUDED`), `TrendText`, lang | `BenchmarkTrendTest.rw8AFreshWorldRunIsLeftOutOfTheBaselineAndMedian`, `.rw8AFreshLatestRunIsNeverARegression`, `.rw6ADhGeneratingRunIsLeftOutToo`, `.excludedRunsAreNotThePreviousRunOfTheScene`; `TrendTextTest.rw8TheExcludedLineNamesWhy`; `BenchmarkConditionsTest.bh2StagedIdsAreTheStagedChangesCappedAt64`; `BenchmarkGameTest` (worldFresh true on the first benchmark-world run of the fresh run dir, false on the next; stagedAtStart written) | AC2B.7, AC2B.4 (trend half) |
| B4 | **Fixture set `ws-b`**: `src/test/resources/v050-written/ws-b/benchmarks.json` (runs with each new field: a fresh-world run, a DH-generating run, a run with staged ids, a plain 0.5 run) written by the test, plus `expect.json` (0.4.0's `BenchmarkHistory` loads every run, no `.bad`) | new `core/benchmark/BenchmarkWrittenV050Test`, the set | `BenchmarkWrittenV050Test.theV050WrittenSetIsWhatThisVersionWrites` (compare; `RIGTUNE_REGENERATE_FIXTURES=1` writes), `.the020And030ReadersLoadIt` (pinned readers, no `.bad`, same runs minus the unknown fields), `.a030RewriteDropsOnlyTheNewFields` | AC2B.2 (reader half), X11 |

Part 1 push: B1-B4 together, then CI (all jobs, 3 legs) and the report to the coordinator.

### Part 2

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| B5 | **BH-1**: the trend's counts say "earlier": "Not enough earlier comparable runs for a trend yet (%s of 3)", "… (from %s earlier runs)" | `TrendText`, lang | `TrendTextTest.bh1NoteAndTrendAgree` (from the audit test); the existing wording tests updated | AC2B.1 |
| B6 | **BH-2**: with `stagedAtStart` on the latest run, its window leaves out the changes whose ids it lists, and the next run's window carries them in (a baseline with the field carries exactly its listed changes that took effect; without it, today's `carried` path). Runs without the field (0.4): mod-file rows are listed for the latest run only when both mod-set hashes are known and differ. | `ChangeWindow`, `TrendService` (passes nothing new) | `ChangeWindowTest.bh2ChangeStagedDuringTheLatestRunIsNotListedForIt` (with the field and without it: the S fallback), `.bh2TheNextRunListsTheCarriedChange`, `.bh2AHashChangeKeepsTheModRowWithoutTheField` | AC2B.2 |
| B7 | **RW-9, RW-7, RW-6's line**: "Measured with Distant Horizons rendering off" when DH is installed and the run's `dhRendering` is false; the noisy line names Distant Horizons building terrain or new terrain being generated when `dh`- or `chunksLoading`-tagged spikes are at least half of the capture's spikes (else the generic line); "Distant Horizons was generating terrain during this run, so these numbers may be low" exactly when `dhGenerating` is set | `ResultNotes`, `BenchmarkResultScreen`, lang | `ResultNotesTest.rw9TruthTable`, `.rw7TruthTable`, `.rw6LineExactlyWhenSet` | AC2B.8, AC2B.6, AC2B.4 (line) |
| B8 | **RW-6 detection** (after WS-S's "DH world generation" bucket merges): the benchmark capture's DH world-generation CPU over the sweeps' wall time past a threshold sets `Context.dhGenerating` on the stored run | `BenchmarkController` (the stored context only; not the hook area), `ResultNotes`/a pure threshold helper | `DhGenerationTest.rw6ThresholdTruthTable` (below / at / above; no DH; no capture) | AC2B.4 (detection); AC2B.5 is the rolling Phase 5 real run |
| B9 | **L3, result screen**: the painted table becomes a `RowList` (one row per measured distance; each a `RowFocus` narrating distance, average FPS, 1 % low, P99 and pass / fail / not measured, and "suggested" on the chosen one; the header stays painted above it; rows that don't fit now scroll instead of being cut); the chart gets a textual equivalent (a `RowFocus.standalone` over the unchanged chart narrating its runs, numbers, "your usual" and the trend line); the status lines become `RowFocus.standalone` stops as on Benchmark history | `BenchmarkResultScreen`, `TrendChart` (summary text), `TrendText.chartSummary`, lang | `TrendTextTest.l3ChartSummaryNamesTheNumbersAndTheTrend`; `BenchmarkResultScreenTest.l3RowNarration` | AC2A.1, AC2A.2 |
| B10 | **L3, Benchmark history**: the chart's textual equivalent as on the result screen | `BenchmarkHistoryScreen` | (the same summary tests) | AC2A.1, AC2A.2 |
| B11 | **L3 game test**: `A11yGameTest.walkBenchmarkScreens` (a seeded Tune result and Benchmark history: Tab reaches every table row, each status line and each chart summary in order, each narrates its text; high-contrast screenshots) + text extraction of each screen's rows compared with the WS-K-merge baseline's content (AC2A.2), X12 layout at 640×480, 854×480, 1280×720 @2 | `A11yGameTest` (my method only), `BenchmarkHistoryGameTest` | CI, 3 legs; screenshots looked at | AC2A.1, AC2A.2 |
| B12 | Finish: merge `origin/feat/v0.5.0`, targeted tests, fixtures regenerated for the new fields, compat030 (and compat040 once WS-E lands it) against `ws-b`, self-review, this file's AC table | | | |

No code-deciding run in WS-B. AC2B.5 (the real DH run) is the rolling Phase 5 agent's. No new `//? if` block expected (X9).

### Interfaces other workstreams rely on
- **WS-S (RW-15 seam)**: `StutterHooks.benchmarkStepExcluded(true)` is called, on the render thread, **instead of**
  `benchmarkSweep(true)` for a step whose settle timed out, and `(false)` at its end instead of `benchmarkSweep(false)`.
  The benchmark capture is therefore paused through that step (or not started yet, when it is the first step); WS-S's
  implementation only needs to count or mark it. BenchmarkController also calls `(false)` if the run ends inside such a
  step. The result screen's "left out" count is WS-B's (from `Outcome.settles()`). (ws-k.md's comment on the seam says
  "while a step … is measured again"; SPEC 2B RW-15 is the rule: the timed-out step is left out, the re-measure records.)
- **WS-S (RW-6 bucket)**: B8 reads the benchmark capture's "DH world generation" CPU through whatever accessor WS-S's
  merge exposes; B8 waits for it.
- **WS-T**: part 1 leaves `BenchmarkController.show()` and the CURRENT-cancel branch of `finish()` as they are for WS-T's
  outcome hook; B8 touches only the stored context (`outcome()`'s record), not those.
- `BenchmarkTrend.noiseFloorPercent`, `median`, `mad` keep their signatures.
