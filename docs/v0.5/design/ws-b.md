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

---

# As landed

Commits on `fix/v05-benchmark` (TDD: each red first, in a build slot on 26.2; the red logs are in the WS-B scratch dir as
`b*-red.log`): 34b40d88 RW-5, c9fb21f4 RW-15 (+ the RW-5 screen line), a6825a28 context fields + RW-8/RW-6 exclusion,
829809dd the `ws-b` set (part 1, CI 36317805129 green on every job and leg); ef45e840 BH-1, a0bad56c BH-2, 96681309
RW-7/RW-9/RW-6's line, 511c896b L3, 52c866be RW-6 detection, 58986e31 the unmeasured line shortened (part 2).

## What each item does now
- **RW-5** (`RenderDistancePlanner`): `lowestFail` counts complete steps only; the search bounds itself by the lowest
  incomplete distance above the best pass (and below the lowest complete fail) while it measures below it; once the
  search is done (converged or at `maxRdSteps`) that distance is measured again **once** (`remeasured`), outside the
  step limit (a re-record replaces the measurement, so `measurements.size()` doesn't grow). `BenchmarkSession`'s existing
  deadline check decides whether it still fits (`rw5NoRemeasurePastTheDeadline`). The reason: "31 meets the target; 32
  couldn't be measured (its terrain hadn't loaded)". The result screen: `?` in the table (warning colour) and "? Render
  distance 32 couldn't be measured: its terrain hadn't loaded in time." (`ResultNotes.unmeasured`); no ✘ for it.
- **RW-15**: `client/benchmark/StutterSteps` decides per step: settled → `benchmarkSweep(true/false)`; timed out
  incomplete → `benchmarkStepExcluded(true/false)` **instead**, so the capture stays paused through it whatever the seam
  does (a run ending inside such a step closes it). `Outcome.stepsLeftOut()` counts the incomplete settles; the result
  screen adds "The stutter check left out N step(s) whose terrain hadn't loaded." under the Stutter Doctor line.
- **Context fields** (recorded in BenchmarkController's constructor, before any setting changes): `worldFresh` =
  `BenchmarkWorld.createdThisOpen()` in the benchmark world (null in the player's own world); `stagedAtStart` =
  `BenchmarkConditions.stagedIds(history.json)`: the ids of changes with status STAGED, `[]` when none, null (left out)
  past 64 or when history.json can't be read. `dhGenerating` is set on the stored record (`storedContext()` in
  `outcome()`, after `StutterHooks.benchmarkFinished`) = `DhGeneration.generating(StutterHooks.lastBenchmarkDhWorldGenCores(),
  OptionalMods.dhLoaded())`: at least 0.5 core-equivalents of "DH world generation" CPU over the recorded sweeps → true;
  DH loaded but below → false; no DH or nothing sampled → null.
- **RW-8/RW-6 exclusion** (`BenchmarkTrend.excluded`): a run with `worldFresh` or `dhGenerating` true is left out of
  `baseline` (so of "your usual", the floor and the change window's baseline) and of `previousOfScene` (never the run a
  "different conditions" line compares with); as the latest run it is assessed `Kind.EXCLUDED` (never a regression, so no
  regression notice), with the usual of the runs before it when there are 3, and the line "Left out of the trend: the
  first run in a new benchmark world" / "…: Distant Horizons was generating terrain" (a first run names the world). The
  chart still shows the run.
- **BH-1**: "Not enough earlier comparable runs for a trend yet (%s of 3)", "(from %s earlier runs)".
- **BH-2** (`ChangeWindow`): with the baseline's `stagedAtStart` the carried-in rows are exactly its listed changes that
  took effect (instead of the M1 guess); with the latest's, its window leaves out the changes it lists. Without the
  field (0.4 runs): mod-file rows are left out of the latest's window when both mod-set hashes are known and equal
  (Deviations 1).
- **RW-7/RW-9/RW-6 lines** (`ResultNotes`, after the result line): the noisy line names Distant Horizons building
  terrain (at least half the capture's spikes tagged `dh`) or new terrain being generated (`chunksLoading`), else the
  generic line; "Measured with Distant Horizons rendering off" when DH is loaded and the run's original `dhRendering` was
  off; "Distant Horizons was generating terrain during this run, so these numbers may be low." exactly when the stored
  record's `dhGenerating` is true.
- **L3** (`BenchmarkResultScreen`): the table is a `RowList` (`ResultTable`, list background and separators off, the
  scrollbar inside its right edge) under the painted header; rows are drawn as the painted ones were (same columns,
  highlight, colours) and each is a `RowFocus` narrating "Render distance 8: average 620 FPS, 1% low 390 FPS, P99 3.6 ms,
  meets the target. Suggested" (`ResultNotes.row`; P99 narrated even where the narrow table hides its column); rows that
  don't fit scroll (before: cut). Every status line is a `RowFocus.standalone` over its rows (a wrapped trend line is one
  stop, a clipped line narrates its whole text), and so is "No measurements were taken.". Each chart keeps its pixels and
  gets a stop over it narrating its title, `TrendText.chartSummary` ("5 comparable runs from 2026-09-20 to 2026-09-24: 1%
  lows 540, 545, 538, 550, 440 FPS; averages …. Your usual: 543 FPS") and the trend's first line; Benchmark history gets
  the same over its chart (`chartSummary()` for the tests). `textContent()` lists every shown or narrated string (AC2A.2).
- **Fixtures**: `src/test/resources/v050-written/ws-b/` (benchmarks.json: a fresh-world Measure run, a run with a staged
  id, a DH-generating Tune, a Tune in the player's own world; expect.json: 0.4.0's BenchmarkHistory loads 4 runs, no
  `.bad`), written by `BenchmarkWrittenV050Test` (`RIGTUNE_REGENERATE_FIXTURES=1` rewrites it). No `placeholder/ws-b/` is
  on the branch (WS-E's placeholders haven't merged); whoever merges second deletes it.
- **compat030** against the set (compat030's `written.py` doesn't know the v0.5 sets yet): the v040-written tree copied
  to scratch with the 4 v0.5 runs appended to `ws-b/benchmarks.json` (the README's concatenation rule), the released
  0.3.0 jar: `RESULT PASS`, "BenchmarkHistory: benchmarks.json loads without a .bad: runs 9 of 9, … contexts kept true",
  "0.3.0 reading them changed no file: 9 file(s) unchanged". compat040 isn't on the integration branch yet (WS-E).

## Deviations
1. **BH-2's fallback drops mod-file rows only for a proven-equal mod set**: SPEC says they are listed "only when its
   modSetHash differs from the baseline's". A hash unknown on either side proves nothing, so such rows are still listed
   (possibly related), as in 0.4; only both hashes known and equal drops them
   (`bh2AHashChangeKeepsTheModRowWithoutTheField`).
2. **The unmeasured line is shorter than first written** ("…couldn't be measured: its terrain hadn't loaded in time.",
   without "so it counts as neither a pass nor a fail"): the longer one wrapped and forced a clipped status line at
   854×480@2 (BenchmarkHistoryGameTest, run 36324863949, 26.2 GL). The `?` mark and the row's narration ("not
   measured") carry the rest; nothing calls it a fail.
3. **`stagedAtStart` is `[]` when nothing is staged** (not left out): it tells a 0.5 run with nothing staged from a 0.4
   run, which the fallback treats differently.
4. The RW-15 seam is called **instead of** the sweep calls (ws-k.md's seam comment says "while a step … is measured
   again"; SPEC RW-15 is the rule). Told to the coordinator for WS-S.
5. ChangeWindowTest's `latest()` fixture and BenchmarkHistoryGameTest's seed now load a different mod set after their
   mod update: under BH-2's fallback a 0.4-shaped run with the same hash no longer lists the update, as intended.
6. `V05TestContext.SCROLLING` (854×480 at GUI scale 3) can't be reached: Minecraft caps the scale at 2 for 854×480, so
   `a11y-bench-result-scrolled-854x480-scale3` is taken at scale 2 (5 rows fit at every X12 size; scrolling is the
   list's own vanilla behaviour).

## Residuals / UNVERIFIED
- **UNVERIFIED**: `DhGeneration.MIN_CORES = 0.5` is a starting value. AC2B.5's real run (rolling Phase 5: dev PC, DH
  3.3.2, a fresh benchmark world) checks that such a run records `dhGenerating = true`, the screen names it and the
  trend leaves it out. Also UNVERIFIED (WS-S): `DH-World Gen` as DH's only world-generation thread prefix outside 3.3.2.
- A step left out of the stutter capture also leaves its world-generation CPU out of `dhGenerating` (WS-S averages over
  the recorded sweeps); a fresh-world run whose every step timed out records no `dhGenerating` (null), but it is
  `worldFresh` and left out of the trend anyway.
- RW-6's "no stutter advice on benchmark-world captures" is WS-S's (StutterService.analyze).
- The narration checked is what vanilla's pipeline collects (CI has no TTS), as in 0.4.

## CI evidence, screenshots, footprint
- Part 1: run 36317805129 (head 949a9422): every job and all 3 legs green. Part 2: run 36324863949 (52c866be) red on
  26.2 GL only (BenchmarkHistoryGameTest: a clipped status line at 854×480@2, Deviations 2; both 26.3 legs green,
  A11yGameTest's new walk included), then run 36325935349 (58986e31): every job and all 3 legs green; unit tests 2093
  per version (2 skipped, 0 failures; the `test-reports` artifact).
- **Screenshots looked at** (`gametest-screenshots-26.2-OpenGL` of 36325935349 against the same artifact of 36311670542,
  the WS-K merge a7613410: AC2A.2's baseline; and `-26.3-OpenGL` of 36324863949): `bench-result-wrapped-{1280x720,
  854x480,640x480}-scale2` show every status line, header label, table value and chart of the baseline, the only
  differences being the `?` mark and the unmeasured line where the baseline had `✘*` and "Some of the terrain hadn't
  loaded…"; `bench-history-*` unchanged apart from the BH-1 wording; `bench-world-before` shows "Left out of the trend: the
  first run in a new benchmark world" (the run that created the world); `a11y-bench-result-row-focus-*` show the focus
  frame on the suggested row at the 3 sizes with the columns aligned under the painted header; `a11y-hc-bench-result`
  the yellow high-contrast frame and the darker header backing; `a11y-bench-history-chart-focus-*` the frame over the
  chart; `a11y-bench-result-scrolled-854x480-scale3` is at scale 2 (Deviations 6).
- **Footprint** (per leg, run 36325935349 against WS-K's baseline 36310249248; no v0.5 class is touched at init: the
  benchmark code runs only when a benchmark or its screens do): 26.2 GL renderThreadInitCpuMs 90.3 (82.2), clientStartedWallMs
  34.1 (36.4), workerCpuMs5s 146.9 (135.5), tickHookOnVsReference 1.521 (1.481); 26.3 GL 96.3 (82.2), 36.3 (27.0), 178.3
  (153.2), 1.611 (1.746); 26.3 Vulkan 96.6 (120.0), 51.4 (39.9), 136.0 (200.7), 1.481 (1.535); `v05RenderThreadResolve`
  null on every leg. All inside the runner spread ws-k.md records (e.g. 26.2 renderThreadInitCpuMs 63.5-112.9 across
  near-identical code) and every budget.

## Docs (for the docs workstream)
- **CHANGELOG [0.5.0]**: "Benchmark: a render distance whose terrain hadn't loaded in time is measured again once after
  the lower ones instead of counting as a fail (a first run could suggest 31 over your 32); a step whose terrain hadn't
  loaded stays out of the benchmark's stutter check, and the result says how many; the first run in a new benchmark
  world, and a run while Distant Horizons was generating terrain, are left out of your usual and never raise a
  regression; the result names Distant Horizons generating terrain, measuring with its rendering off, and what noisy
  results went with; Benchmark history's counts say 'earlier runs'; a change still staged when a run started is no
  longer listed as a change since then for it. Accessibility: the benchmark result's table rows, its lines and both
  charts are reached with Tab and read by the Narrator."
- **README "Known limits"**: remove "The benchmark result's table and the benchmark charts can't be reached with the
  keyboard or read by the Narrator yet" (AC2A.3). Keep: Distant Horizons' own world generation isn't paused during a
  benchmark (it's detected and named, and such a run is left out of the trend).
- **DESIGN.md "Benchmark v2"** step 2: "a step that timed out with more than 2% of those chunks missing is not measured:
  neither a pass nor a fail; once the search is done, the lowest such distance that could still change the answer is
  measured once more (outside the 6 steps) while the deadline allows, else the result says it couldn't be measured. Such a
  step stays out of the benchmark's stutter capture." Step 5 / context: "0.5 adds `worldFresh` (this run created the
  benchmark world), `dhGenerating` (Distant Horizons' world-generation threads used at least half a core over the
  sweeps, from the Stutter Doctor's sampler) and `stagedAtStart` (the ids of history.json changes still staged, at most
  64), all optional."
- **DESIGN.md "Benchmark history and regression alerts"**: "A run with `worldFresh` or `dhGenerating` is left out of
  every baseline and comparison and is never a regression ('Left out of the trend: …'). The change window leaves a run's
  `stagedAtStart` changes out of its own window and carries exactly them into the next; for 0.4 runs, mod-file rows are
  left out when both mod-set hashes are known and equal."
- **DESIGN.md "Accessibility"**: replace "Not covered: the painted benchmark table and charts (v0.5)" with "The
  benchmark result's table is a RowList whose rows narrate distance, average FPS, 1% low, P99 and pass/fail/not measured;
  its status lines are Tab stops; each chart keeps its pixels with a Tab stop over it narrating its runs' numbers, your
  usual and the trend line."

## AC table

| AC | status | evidence |
|---|---|---|
| AC2B.1 (BH-1) | verified | `TrendTextTest.bh1NoteAndTrendAgree` (the audit's test), CI 36325935349 java (both nodes) |
| AC2B.2 (BH-2; readers) | verified (compat040 half closes with WS-E's interpreter; `expect.json` landed) | `ChangeWindowTest.bh2*` (with and without the field; the next window carries it); `BenchmarkWrittenV050Test` (pinned 0.2.0/0.3.0 readers, no `.bad`); compat030 PASS above |
| AC2B.3 (RW-5) | verified | `RenderDistancePlannerTest.rw5*`, `BenchmarkSessionTest.rw5*`, `ResultNotesTest.rw5*`; screenshot `bench-result-wrapped-*` |
| AC2B.4 (RW-6: excluded, line, no stutter advice) | verified for WS-B's parts (the "no stutter advice" part is WS-S's) | `BenchmarkTrendTest.rw6ADhGeneratingRunIsLeftOutToo`, `.rw8AFreshLatestRunIsNeverARegression`, `ResultNotesTest.rw6LineExactlyWhenSet`, `DhGenerationTest.rw6ThresholdTruthTable` (detection on WS-S's merged bucket) |
| AC2B.5 (RW-6 real run) | not verified here: rolling Phase 5 (P5 agent, dev PC, DH 3.3.2) | — (threshold UNVERIFIED) |
| AC2B.6 (RW-7) | verified | `ResultNotesTest.rw7TruthTable` |
| AC2B.7 (RW-8) | verified | `BenchmarkTrendTest.rw8*`, `TrendTextTest.rw8TheExcludedLineNamesWhy`; BenchmarkGameTest: `worldFresh` true on the run that created the world, false on the next, 3 legs (36317805129, 36325935349) |
| AC2B.8 (RW-9) | verified | `ResultNotesTest.rw9TruthTable` |
| AC2B.9 (RW-15) | WS-B half verified; closes with the later of WS-B/WS-S (WS-S implements the seam) | `StutterStepsTest.rw15*` (the excluded step never sweeps), `BenchmarkControllerOutcomeTest.rw15StepsLeftOutCountsTheTimedOutSettles`, `ResultNotesTest.rw15TheLineNamesTheStepsLeftOut` |
| AC2A.1 (Tab reaches every row and chart summary; narration) | verified | `A11yGameTest.walkBenchmarkScreens`, 3 legs (36325935349; 26.3 also 36324863949) |
| AC2A.2 (no content lost; X12 layout; high contrast) | verified | the walk's `textContent()` checks every table value and header at the 3 sizes, its layout check (no widget outside, none overlapping), the screenshots compared above, `a11y-hc-bench-*` |
| AC2A.3 (README known limits) | docs workstream | text in "Docs" above |

