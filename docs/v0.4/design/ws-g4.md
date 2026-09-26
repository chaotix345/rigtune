# WS-G4: history retention (SPEC 2o: M6, L1)

Branch `fix/journal-audit`. Plan: docs/v0.4/plans/ws-g4.md. Sources: audit-apply-pipeline.md M6/L1 and
audit-verification.md (WP-5).

## M6: the cap folds instead of dropping

`Journal.cap` (core, helper-safe: JDK and the journal records only) keeps at most 50 entries in three steps:
1. Unchanged: drop the oldest finished entries. These have nothing staged, and are either an undo or have nothing applied.
2. New: fold the oldest **run** of at least 2 consecutive entries into one baseline entry, in the run's place. An entry
   can be in a run only when it has no STAGED change and no change that an undo which did something is still reverting
   (a staged undo may yet be discarded). The fold takes as many of the run's entries as the cap needs, and repeats if
   it still needs more.
3. Unchanged fallback, used only when no such run exists: drop the oldest entries without staged changes, then any.

**The fold rule (coordinator-approved option a).** The baseline gets the kind `apply`, a new id, the `at`,
`rigtuneVersion` and `mcVersion` of the run's oldest entry, and a null `undoOf`. Its changes:
- **Settings.** For each key, take the run's APPLIED changes, ignoring undo entries and changes being reverted. Walk
  them newest to oldest while each older change's `after` equals the running target, the way
  `UndoPlanner.planSettings` walks its chain. The result is one APPLIED change from the chain's oldest `before` to the
  newest `after`. When the player never touched the key in between, this is the earliest `before`. When the player did
  change it between two RigTune changes, the chain stops there, as Undo all itself would ("You changed it after this
  apply"), so the player's own value is what comes back.
- **Mod files.** APPLIED file changes are copied unchanged (id, group, `resultFile`, `modName`), oldest first. Groups
  that span the baseline and a kept entry stay one group.
- REVERTED, DISCARDED and ABANDONED changes are only history, and they go.

**Invariant, tested as a property.** Undo all on the capped journal plans the same script as on the uncapped history:
the immediate values, the staged values, the discarded op ids and the net file ops.
`JournalFoldTest.foldedHistoriesPlanUndoAllAsTheUncappedOnes` checks this on 300 seeded random histories. They mix
applies, player changes, undoing the newest entry, staged changes and restarts, and the cap runs after every write.
A mutation to "earliest before always wins" fails the property and the player-change test (checked by hand).

**Why a run never crosses another entry.** Folding across an entry that is still staged would move changes past it
and misorder a key's chain. So that entry stays, and the next run of 2 is folded instead. A repeated fold re-folds
the old baseline, which is the oldest entry of the first run, so a journal without staged entries holds exactly one
baseline. A baseline that is blocked stays as it is until the staged entry after it resolves, at the next restart.

**Compatibility (SPEC promise).** No new field, and formatVersion stays 1. To 0.3.0 the baseline is an ordinary Apply:
it reads, lists and undoes it, and rewrites it losing only `modName`. No v040-written fixture changed, because the
file's shape is the same.

## L1: the "Imported from 0.1" gate

- `LegacyImport.fromV010(ApplyResult)` is true when last-apply.json was written by 0.1.x. Two things must hold:
  - At least one mod file op finished OK without a `resultPath`. 0.2.0 and later always record a `resultPath` on those
    ops.
  - Nothing in the file is newer than 0.1.x: no `resultPath`, no PATCH_TOML or PATCH_PROPERTIES op, no `projectId` or
    `versionId`, and no op type this version can't parse.
- `HistoryStartup.legacyEntry` imports only then. A fresh install imports nothing. So does a history.json deleted after
  a later RigTune's helper ran.
- The second start never imports again. That was already true, since the supplier runs only when history.json is
  MISSING, and a test now pins it.
- Case (ii) is a first start that couldn't lock, where the first journal write is a staging. `Staging.stage` now creates
  a missing history.json before its merge, so the legacy entry reads pending.json as it was before, and the Apply's own
  ops stay in its own entry. This is simpler than the audit's "pass the pre-merge base to the supplier" and needs no
  Journal API change: ApplyLock is reentrant, so the nested update runs under the held lock.
- All four 0.1.0 last-apply.json files in the repo are recognised: hand-written, captured, real-instance, and
  seeds/v010-dh, which is byte-equal in its result shapes (checked with a script).

## Deviations and residuals

1. **0.3.0 after a downgrade.** 0.3.0's own cap still drops the oldest entry without staged changes, which is the
   baseline. So once 0.3.0 appends to a full journal (always 50 after a fold), M6 is back for that player. This is
   0.3.0 code and can't be fixed from 0.4. 0.3.0 rewrites that don't evict keep the baseline: its helper applying 0.4's
   staged op (JournalFoldV030Test), and startup reconcile.
2. **Fallback drop.** It happens only when no run of 2 exists, which takes about 25 interleaved entries with changes
   still staged.
3. **An ambiguous 0.1.0 last run isn't imported.** This is a 0.1.0 run that did no mod file op (only patches, skips or
   failures). Its leftover ops then apply at the next exit without a History entry. Such a run can't be told apart from
   a 0.2+ run, and SPEC's wording is "only when a 0.1.0 last-apply.json really exists".
4. **File changes copied as they are.** The baseline keeps every mod file change still in effect, so it grows with the
   mod changes RigTune ever made and never undid: about 2 changes per update, roughly 300 bytes each. Setting changes
   are bounded by the number of keys.
5. **Profiles.** A switch entry that is folded loses its label. profiles.json prunes labels for ids that are no longer
   in the journal. If it was the active switch, `ActiveProfile` voids it, as it does for any entry that is gone.
6. **History display.** The baseline shows as an ordinary "Apply" at the run's oldest date, with no "earlier changes"
   label. A label would need an optional field or an id convention, plus HistoryScreen and lang edits outside this
   package. Possible follow-up.
7. **Undo last and Undo this on the baseline** undo everything it holds at once: every folded entry's changes that are
   still in effect. They can only be reached that way once everything newer is undone, or with Undo this, which skips
   what later entries changed again. Before, Undo last reached one folded entry at a time, until they were gone.
8. **Other readers.** WS-B's ChangeWindow falls back to timestamps when a benchmark's `journalCursor` entry was folded,
   which is its documented fallback.
9. **Docs.** DESIGN.md:105 ("the last 50 entries are kept") should read: "at most 50 entries; the oldest are folded
   into one baseline entry that keeps what Undo all needs". Suggested CHANGELOG lines (Fixed):
   - "Undo all still restores your own settings, and removes mods RigTune added, after many profile switches (the
     history's oldest entries are combined instead of dropped)."
   - "The 'Imported from 0.1' History entry is only created when upgrading from 0.1.x."

## Evidence
- Unit tests: 1630 per version after the change, 0 failed, 1 skipped (`./gradlew build`, both 26.2 and 26.3). New tests:
  - JournalFoldTest (8)
  - JournalFoldV030Test (2)
  - HistoryStartupTest (5)
  - LegacyImportTest (+2)
  - JournalTest: `capFoldsOldUnfinishedEntriesOnlyWhenItMust` replaces the drop expectation.
- Red first: on 8869abc, 7 of 8 JournalFoldTest tests and the JournalTest change failed. `noFoldableRunFallsBackToDropping`
  pins the old behaviour. 4 of 5 HistoryStartupTest tests failed; `theReal010StateIsImportedOnce` is a regression pin.
- Released-jar harness: `python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar --written <scratch>`.
  - The released jar's sha256 is 5717f65c…cd7e9, equal to the release.
  - The scratch root is a copy of `src/test/resources/v040-written` plus a `ws-f/history.json` written by the 0.4
    Journal: a first Apply that enables x.jar and sets RD, maxFps and a Sodium key, then 60 profile switches. That
    leaves 50 entries, with the baseline first: RD 12→6, maxFps 120→60, defer ONE_FRAME→ZERO_FRAMES,
    VSync false→true, and x.jar with modName.
  - Result: **RESULT PASS 9/9**.
    - Journal state OK with 53 of 53 entries (composed with ws-a and ws-p).
    - HistoryModel lists all 53, with no unknown kind.
    - Undo this on ws-p's switch entry reverts each change.
    - Undo last and Undo all plan without a problem (108 items).
    - The other files load, and 0.3.0 reading them changed no file.

## UNVERIFIED
- The downgrade client run (`--scenario downgrade`) with a folded history. The harness composes the committed sets,
  which hold no baseline. The pinned-class tests and compat030 cover the reading and planning. A real 0.3.0 client
  undoing a baseline is left for Phase 5, if the coordinator seeds one.
