# WS-G4: history retention (SPEC 2o: M6, L1)

Branch `fix/journal-audit`. Plan: docs/v0.4/plans/ws-g4.md. Sources: audit-apply-pipeline.md M6/L1 and
audit-verification.md (WP-5).

## M6: the cap folds instead of dropping

`Journal.cap` (core, helper-safe: JDK and the journal records only) keeps at most 50 entries in three steps:
1. Unchanged: drop the oldest finished entries. These have nothing staged, and are either an undo or have nothing applied.
2. New: fold the oldest **run** of at least 2 consecutive entries into one baseline entry, in the run's place.
   - An entry can be in a run only when it has no STAGED change and no change that a working undo is still reverting
     (a staged undo may yet be discarded).
   - The newest entry is never in a run, so the entry just written stays itself (for Undo last, and for a profile
     switch's label and active marker).
   - The fold takes as many of the run's entries as the cap needs, and repeats if it still needs more.
3. Fallback, only when no such run exists: drop the oldest entries without staged changes, a baseline last; then any.

**The fold rule (coordinator-approved option a).** The baseline is an `apply` entry with the `at`, `rigtuneVersion` and
`mcVersion` of the run's oldest entry and a null `undoOf`. Its id is `baseline-<uuid>`, and a baseline the run starts
with keeps its id (`Journal.BASELINE`, `Journal.isBaseline`). Its changes:
- **Settings.** For each key, take the run's APPLIED changes and walk them newest to oldest while each older change's
  `after` equals the running target, the way `UndoPlanner.planSettings` walks its chain. That gives one change,
  keeping the newest change's id and opId, from the chain's oldest `before` to the newest `after`.
  - When the player never touched the key in between, this is the earliest `before`.
  - When the player did change it between two RigTune changes, the chain stops there, as Undo all itself would ("You
    changed it after this apply").
  - A chain that stops inside the run also keeps one change for the older part, placed before the chain change: the
    oldest `before` to the `after` where the chain stopped, with the id of the newest change in that part. Because it
    ends elsewhere, the planner still stops there and never goes on into an older entry that couldn't be folded. That
    was review H1.
  - So at most two changes per key, however often the baseline is folded again.
- **Mod files.** APPLIED file changes are copied unchanged (id, group, `resultFile`, `modName`), oldest first. Groups
  that span the baseline and a kept entry stay one group.
- REVERTED, DISCARDED and ABANDONED changes are history only, and they go.

**Invariant, tested as a property.** Undo all on the capped journal plans the same script as on the uncapped history:
immediate values, staged values, discarded op ids, file ops grouped as staged, and the set of reverted keys and files.
`JournalFoldTest.foldedHistoriesPlanUndoAllAsTheUncappedOnes` runs 400 seeded random histories, with the cap after
every write. They mix:
- Applies of vanilla keys, and profile-switch-like entries (vanilla keys set now plus a staged Sodium key).
- Player changes, and undo of the newest entry.
- Staged Sodium ops that fail and stay staged over restarts, or get discarded.
- Staged undos of older entries.
- Mods added and updated (a disable and an enable in one group).

Mutation checks, run by hand:
- "Earliest before always wins" fails the property and the player-change test.
- Removing the H1 fix fails the property (seed 68) and `aBreakInsideTheRunStillStopsTheChainBeforeAnOlderEntry`.
- Ignoring the pending-undo block fails `onlyChangesStillInEffectAreFoldedAndAPendingUndoKeepsItsTarget`.

**Why a run never crosses another entry.** Folding across an entry that is still staged would move changes past it
and misorder a key's chain. That entry stays, and the next run of 2 is folded instead. A repeated fold re-folds the
old baseline (the oldest entry of the first run), so a journal without staged entries holds exactly one baseline. A
baseline that is blocked stays as it is until the staged entry after it resolves at the next restart.

**Compatibility (SPEC promise).** No new field, and formatVersion stays 1. The `baseline-` id prefix is just an id, and
0.3.0 keeps ids when it rewrites the file. To 0.3.0 the baseline is an ordinary Apply: it reads, lists and undoes it,
and a rewrite loses only `modName`. No v040-written fixture changed, since the file's shape is the same.

## L1: the "Imported from 0.1" gate

- `LegacyImport.fromLater(ApplyResult)`: last-apply.json was written by 0.2.0 or later. That means it has a
  `resultPath`, which 0.2.0 and later record on every mod file op they do (review-checked at the tags), or an op 0.1.x
  couldn't stage.
- `fromV010(ApplyResult)`: last-apply.json isn't from a later version, and a mod file op finished OK without a
  `resultPath`.
- `v010Plan(PendingActions)`: pending.json holds ops, and every one is an op 0.1.x could stage.
  - That means ENABLE_FILE or DISABLE_FILE with no Modrinth `projectId`/`versionId`, or a PATCH_JSON of
    sodium-options.json.
  - Released 0.1.0 gives every op an id (v0.1.0 `PendingActions`:43-52) and groups update pairs. So "no id and no
    group" isn't the 0.1.0 shape; only pre-release plans lack ids, and LegacyImport can't track those anyway.
- `HistoryStartup.legacyEntry` checks in order:
  - A later version's last-apply.json → nothing.
  - Otherwise, import when last-apply.json is 0.1.x's, or pending.json is a 0.1.x plan. This covers a 0.1.0 Apply
    followed by the upgrade before 0.1.0's helper ever ran (coordinator follow-up).
  - The leftovers are imported only when they form a 0.1.x plan.
- A fresh install imports nothing, and neither does a history.json deleted after a later RigTune's helper ran.
- The second start never imports again. The supplier runs only when history.json is MISSING, and a test now pins it.
- Case (ii) is a first start that couldn't lock, where the first journal write is a staging. `Staging.stage` now creates
  a missing history.json before its merge, so the legacy entry reads pending.json as it was, and the Apply's own ops
  stay in its entry.
  - Simpler than the audit's "pass the pre-merge base to the supplier": no Journal API change.
  - ApplyLock is reentrant, so the nested update runs under the held lock.
  - HistoryStartupTest fails without it.
- All four 0.1.0 last-apply.json files are recognised: hand-written, captured, real-instance, and seeds/v010-dh (whose
  results match real-instance's).

## Deviations and residuals

1. **0.3.0 after a downgrade.** 0.3.0's own cap drops the oldest entry without staged changes, which is the baseline.
   So once 0.3.0 appends to a full journal (always 50 after a fold), M6 is back for that player. That is 0.3.0 code and
   can't be fixed from 0.4. 0.3.0 rewrites that don't evict keep the baseline: its helper applying 0.4's staged op
   (JournalFoldV030Test), and startup reconcile.
2. **Fallback drop.** It needs no run of 2 at all: about 25 interleaved entries with changes still staged, or 48 older
   ones. The newest entry and a baseline go last.
3. **A residual mislabel.** 0.2.0 and 0.3.0 stage the same shapes as 0.1.x, and so do 0.4's undo ops and Sodium patches.
   So a history.json deleted on a 0.2+ instance whose helper never did a mod file op (it only ever patched Sodium, or
   never ran) still imports its pending ops as "Imported from 0.1". They stay tracked and undoable; only the label is
   wrong. Any Distant Horizons/Iris patch, Modrinth download or 0.2+ last-apply.json rules it out.
4. **A stale 0.1.x last-apply.json** (the player never staged anything after upgrading, then deleted history.json) is
   imported again. That is intended: RigTune never undid those changes, or its helper would have rewritten the file,
   so they are still in effect and belong in History.
5. **File changes copied as they are.** The baseline keeps every mod file change still in effect, so it grows with the
   mod changes RigTune made and never undid: about 2 per update, roughly 300 bytes each. Setting changes are bounded
   at two per key.
6. **Profiles.** A folded switch entry loses its label (profiles.json prunes ids no longer in the journal). If it was
   the active switch, `ActiveProfile` voids it, as for any entry that is gone.
7. **History display.** The baseline shows as an ordinary "Apply" at the run's oldest date. A chain that stopped
   inside the run shows two rows for that key, and a key that nets out shows "12 → 12". A run starting with the
   legacy-import entry loses the "Imported from 0.1" label. `Journal.isBaseline` makes an "Earlier changes (combined)"
   label possible later, but that is a HistoryScreen and lang edit outside this package.
8. **Undo last and Undo this on the baseline** undo everything it still holds at once. It can only be reached that way
   once everything newer is undone, or with Undo this (which skips what later entries changed again). Before, Undo last
   reached one folded entry at a time, until they were gone.
9. **Ids across writes.** The baseline keeps its id and each key's change keeps the id of its newest change. A write
   while the Undo screen is open (a finished download staging) still reverts the key on confirm. It shows the older
   folded change ids as "It was undone or changed since this list was made".
10. **ChangeWindow (WS-B)** falls back to timestamps when a benchmark's `journalCursor` entry was folded. The baseline
    is dated at the run's oldest time, so an old window can list folded changes made after it. That needs both runs
    older than the fold horizon. Folded setting changes keep their opId, so `carried()` still counts them as staged.
11. **Docs.** DESIGN.md:105 ("the last 50 entries are kept") should read "at most 50 entries; the oldest are folded into
    one baseline entry that keeps what Undo all needs". Suggested CHANGELOG lines (Fixed):
    - "Undo all still restores your own settings, and removes mods RigTune added, after many profile switches: the
      history's oldest entries are combined instead of dropped."
    - "The 'Imported from 0.1' History entry is only created when upgrading from 0.1.x."

## Self-review (code-reviewer subagent, scratchpad ws-g4/review.md)

1 high, 1 medium, 6 low, 7 nits.
- **Fixed:**
  - H1: a chain break is kept, so the chain never reaches past it into an older kept entry.
  - M1: the generator reaches H1, files, staged undos and failing ops, and the comparison covers op groups and reverted
    targets.
  - L-1: the newest entry is never folded.
  - L-2: the fallback drops a baseline last.
  - L-3: stable baseline id, and each change keeps its newest id.
  - L-4 in part: opId kept.
  - Nit: the empty while loop.
- **Documented:** L-4 (timestamps), L-5 (intended), and the display nits (residual 7).
- **L-6: fixed** by the coordinator follow-up: 0.1.x plans are imported without a last-apply.json.
- **Kept:** the undo-kind skip in `baseline()`, which matches UndoPlanner's candidates.
- The plan text is updated.

## Evidence
- Unit tests: `./gradlew build` on both 26.2 and 26.3 (counts in the hand-back). New tests:
  - JournalFoldTest (12)
  - JournalFoldV030Test (2)
  - HistoryStartupTest (7)
  - LegacyImportTest (+3)
  - JournalTest: `capFoldsOldUnfinishedEntriesOnlyWhenItMust` replaces the drop expectation.
- Red first on 8869abc's code:
  - 7 of the first 8 JournalFoldTest tests and the JournalTest change failed. `noFoldableRunFallsBackToDropping` pins
    the old behaviour.
  - 4 of 5 HistoryStartupTest tests failed. `theReal010StateIsImportedOnce` is a regression pin.
- Released-jar harness: `python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar --written <scratch>`.
  - The released jar's sha256 is 5717f65c…cd7e9, equal to the release.
  - The scratch root is a copy of `src/test/resources/v040-written` plus a `ws-f/history.json` written by the 0.4
    Journal: a first Apply (RD, maxFps, a Sodium key, x.jar enabled) and 60 profile switches, which leaves 50 entries
    with the baseline first.
  - A second scratch history adds a player change after the first Apply. Its baseline keeps the break as two
    render-distance changes, 12→8 and 16→6.
  - Result: **RESULT PASS 9/9** for both histories, run before and after the review fixes.
    - Journal state OK with 53 of 53 entries (composed with ws-a and ws-p).
    - HistoryModel lists all 53, with no unknown kind.
    - Undo this on ws-p's switch entry reverts each change.
    - Undo last and Undo all plan without a problem.
    - The other files load, and 0.3.0 reading them changed no file.
- tools/e2e Python tests: 216 OK (no harness change).

## UNVERIFIED
- The downgrade client run (`--scenario downgrade`) with a folded history. The committed sets hold no baseline, and
  the pinned-class tests and compat030 cover reading and planning. A real 0.3.0 client undoing a baseline is left for
  Phase 5, if the coordinator seeds one.
