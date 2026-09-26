# WS-G4: history retention (SPEC 2o: M6 must, L1 should)

Branch `fix/journal-audit`, worktree `rigtune-g4`. Sources: docs/research/v0.4/audit-apply-pipeline.md M6/L1,
docs/v0.4/audit-verification.md (WP-5). Helper-safe: Journal stays core + Gson (HelperLauncherTest).

## Fold rule (M6, coordinator-approved option a)
`Journal.cap`: pass 1 unchanged (drop finished entries, oldest first). Pass 2 no longer drops: it folds the oldest
**contiguous run** of entries without a STAGED change (length >= 2) into one baseline entry at the run's position
(kind `apply`, new id, the run's oldest `at`/versions, `undoOf` null), folding exactly as many entries as the cap needs.
- Settings: the run's candidate changes (APPLIED, not being reverted, not in an undo entry) per key, walked newest to
  oldest while each older `after` equals the target (UndoPlanner.planSettings' chain), collapse to one APPLIED change
  `before` = the chain's oldest before, `after` = the newest after. So Undo all plans exactly what it planned uncapped.
- Files: candidate APPLIED file changes copied as they are (ids, groups, resultFile, modName kept), in order.
- Everything else (REVERTED, DISCARDED, ABANDONED, undo entries) is history only and goes.
- No run of 2: the old drop passes (residual: needs ~25 interleaved entries with staged changes).
No new field, formatVersion 1: 0.3.0 reads, lists, undoes and rewrites the baseline as an ordinary Apply.

## Tasks
- [ ] T1 M6 tests first (fail on 8869abc): `JournalFoldTest`
  - `sixtyProfileSwitchesStillUndoAllToThePreRigTuneValues` (first Apply: RD, FPS cap, a Sodium key, x.jar enabled;
    60 switches through `Journal.update`; <= 50 entries; plan(all) restores 12/120/ONE_FRAME and disables x.jar)
  - `foldedJournalPlansUndoAllAsTheUncappedOne` (seeded random journals, compare scripts)
  - `aPlayerChangeBetweenTwoAppliesStopsTheChainAsUndoAllWould`, `revertedAndUndoneChangesAreNotFolded`,
    `fileChangesAreCopiedAsTheyAre`, `aRunNeverCrossesAnEntryWithStagedChanges`, `noFoldableRunFallsBackToDropping`,
    `theBaselineIsAnApplyAtTheRunsOldestTime`
  - `JournalTest.capDropsOldUnfinishedEntriesOnlyWhenItMust` updated to the fold.
- [ ] T2 implement the fold in Journal.cap; JournalTest, UndoPlannerTest, V030CompatTest green.
- [ ] T3 0.3.0 round trip: `JournalFoldV030Test` (pinned v030 Journal state OK, HistoryModel lists the baseline as an
  Apply and undoable, v030 UndoPlanner Undo all reaches the pre-RigTune values and Undo this plans; a v030 rewrite keeps
  it and 0.4's Undo all still reaches them). Local `compat030.py --old-jar <released 0.3.0> --written <scratch>` with a
  folded history in a scratch copy of the sets (evidence in the design doc).
- [ ] T4 L1 tests first: `LegacyImportTest` (`fromV010`: the 0.1.0 fixtures true; resultPath, PATCH_TOML/PROPERTIES,
  projectId, patches-only and null false); `HistoryStartupTest` (fresh install none; real 0.1.0 state one; second
  start no duplicate; history.json deleted after a 0.4 helper run none); `StagingTest` (first stage() with no
  history.json: the new ops stay under the Apply's entry).
- [ ] T5 implement: `LegacyImport.fromV010` gate in `HistoryStartup.legacyEntry`; `Staging.stage` creates a missing
  journal before the merge (so the first entry reads pending.json as it was).
- [ ] T6 build both versions, self-review (code-reviewer subagent), merge origin/feat/v0.4.0, push, CI, design doc
  docs/v0.4/design/ws-g4.md.
