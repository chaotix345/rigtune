# WS-G3: helper atomicity (SPEC 2o: H4, L2)

Branch `fix/helper-audit`. Findings: docs/research/v0.4/audit-apply-pipeline.md H4 and L2, verified in
docs/v0.4/audit-verification.md (WP-3). Plan: docs/v0.4/plans/ws-g3.md. Files: `core/apply/ApplyExecutor` (run,
writeRemaining, giveUpOnRepeatFailures, runGroup, rollback; `apply`/`retrying` became `tryOnce`), the new
`core/apply/UnfinishedGroups`, `core/apply/HelperLauncher` (keeps the new file), and tests: ApplyGroupsTest,
ApplyExecutorTest, HelperLauncherTest and the new HelperCompat030Test. Helper-safe: core + Gson only (HelperLauncherTest's
child-JVM runs pass). No pending.json/last-apply.json shape change, no new op type, no format bump.

## H4: no group is left half-applied

**In one run.** Each op of a group is tried once per pass. When one fails, the rest are skipped and every rename of the
pass is rolled back at once (the rollback keeps its full retry budget). Only then is the whole group retried, under the
failing op's own RetryState (the same policies as before: sharing violation 300 ms doubling to 5 s over ~30 s, anything
else `attempts` × delay; one state per op, so a quick failure of one op never cuts another's sharing budget). Between two
passes the group is untouched. The half-applied window is now only the time between two back-to-back renames, plus a
rollback. Before, it was the whole in-place retry of the failing op (up to ~30 s). A group whose first op fails behaves
as before (nothing to roll back, the same sleeps and the same "Gave up after N tries").

If a rollback fails (after its own budget), the group is half-applied anyway. So the remaining ops are then retried in
place under the same budget (roll forward), and one more full rollback is tried only once that budget is spent (review
M1: otherwise one failed try of the enable plus a failed rollback would end the run without the mod, where the old code
kept retrying the enable). Worst case per group: about 30 s of retries plus two rollback budgets.

**Across runs: the record.** Just before a pass renames anything, the helper records that pass's renames (op id, from,
to; a disable's `.disabled` name is decided then and used for the move) in `config/rigtune/helper/unfinished-groups.json`,
one entry per group (lone ops too). Entries stay until last-apply.json has been written (then `prune` drops the groups
that ended consistently and the renames of ops no longer in the plan), so a redo after a kill or a failed result write
still reports the real `.disabled` name (review L1). A half-applied group's entry stays until a later run ends the group.
Reads and writes are best effort: an unreadable file counts as empty and is replaced; a failed write is retried at the
next change and never stops a group (review L2: a group still runs without a record, as before this change).
HelperLauncher's cleanup of `helper/` keeps this one file.

**Recovery rule: roll forward, else roll back completely.** At the next helper run, each recorded rename of the group
that is still in effect counts as done by this run. "In effect" means the new name exists and the old one doesn't. The
record is matched to the op by op id and paths, whichever group recorded it (staging can move a staged op into another
group, e.g. when a newer update of the same mod takes over the group), and both names must be directly in the mods
folder. Such an op
reports SKIPPED_ALREADY_DONE with its resultPath, so History gets the real `.disabled` name, and it goes on the group's
undo list. So the group:
- completes when its remaining renames succeed (for an update, usually just the enable), or
- when any op fails again, is rolled back entirely, including the earlier run's renames. It then counts a failed run as
  usual and is abandoned at the third, now in a consistent state (for an update: the old jar active again).

Why forward first: pending.json still holds the group, so the player's choice still stands, and finishing it needs only
the renames that are left. Rolling back first would undo a nearly finished update only to redo it in the same run. In
every case the group ends all-applied or all-rolled-back, so no mod or its library stays missing. The one exception is
a rollback that fails again; then the group stays recorded and pending for the run after.

Refused group with earlier renames: they are rolled back. Dropped group (a duplicate installed another way meanwhile):
the earlier renames stay, reported as done, because putting the old jar back would load the mod twice.

**Never abandoned while half-applied.** A failed rollback's result now carries `resultPath` (where its file was left; the
0.2 field, unused on FAILED results until now) and says "…; the next exit finishes or rolls back this change".
`giveUpOnRepeatFailures` never abandons a group with such a result, because abandoning would retire its download to
`.rigtune-superseded` and leave the mod missing for good. The group's `attempts` stop at `MAX_FAILED_RUNS - 1`, so
History and the log say "try 3 of 3", and the first run that ends the group consistently but still failing abandons it.

**Why "the next client start" can't do it instead.** If the missing jar is something another mod depends on, Fabric
refuses to start before any RigTune code runs, so nothing in the client can repair the folder. If the game does start,
Fabric has already picked the jars, so a repair in preLaunch would take effect no earlier than the helper's run at the
next exit. Recovery therefore lives in the helper, which is the only thing that executes pending.json. That is also
why the in-run window matters most.

## L2: crash-safe write order

`run()` now writes in this order:
1. The ABANDONED ops leave pending.json, the FAILED ones count a run, and abandoned downloads are retired. Skipped when
   there is nothing to drop or count.
2. last-apply.json.
3. The OK/SKIPPED ops leave pending.json. ABANDONED is listed again, so an op step 1 dropped never comes back when
   pending.json is gone and the plan passed in is used.
4. The journal (best effort, unchanged).

Why this order is safe:
- Death or failure before step 2: the done ops are still in pending.json, so reconcile keeps their changes STAGED
  (never ABANDONED), and the next run redoes them idempotently (SKIPPED_ALREADY_DONE, no-op patches) and marks them
  APPLIED.
- After step 2: reconcile replays the new last-apply.json.
- An abandoned op is out of pending.json before last-apply.json names it. So no later run can apply an op that History
  already calls abandoned, which a plain "last-apply first" order would allow if step 3 failed.
- A failed last-apply.json write throws out of run() as before (ApplyHelper logs it, exit 1). The journal isn't
  updated, and pending.json keeps the done ops.

## Tests (each red first on the old code)
- ApplyGroupsTest:
  - `noMomentWithoutEitherJarWhileTheEnableIsRetried`: the audit's snapshot test.
  - `aHelperKilledBetweenTheRenamesIsFinishedByTheNextRun` and `…IsRolledBackWhenTheNextRunCantFinish`: the kill is an
    Error thrown by the Mover, so nothing after it runs. The next run ends with exactly one active copy either way.
  - `anAdditionKilledAfterTheModNeverLeavesItWithoutItsLibrary`.
  - `aFailedRollbackIsFinishedByTheNextRun` and `aFailedRollbackIsRolledBackByTheNextRunWhenItStillCantFinish`.
  - `aGroupLeftHalfAppliedIsNeverAbandoned`.
  - `anEarlierRenameAfterTheFailingOpIsRolledBackToo`: an earlier run's rename of an op after the one that fails now
    (the new jar left active by a failed rollback, next to the old one) is put back too, never forgotten.
  - Review round: `aRollbackThatFailsMidRunIsRolledForwardInTheSameRun` (M1), `eachOpKeepsItsOwnRetryPolicy` (L3),
    `aRecordStillMatchesAnOpStagingMovedToAnotherGroup`; all red on the code before the review fixes.
  - Guards for the new code (they can't be red on the old code): `aRecordThatDoesntMatchItsOpsIsIgnored`,
    `anUnreadableRecordIsIgnoredAndReplaced`, `aRecordThatCantBeWrittenNeverStopsTheGroup`,
    `aRefusedGroupPutsBackWhatAnEarlierRunDid`, `aDroppedGroupKeepsWhatAnEarlierRunDid`.
- ApplyExecutorTest:
  - `aFailedResultWriteLeavesTheDoneOpsPendingSoHistoryNeverSaysNotApplied`: last-apply.json is a non-empty directory.
    Reconcile gives STAGED, and APPLIED after the next run; the old code gave ABANDONED.
  - `anAbandonedOpLeavesPendingJsonBeforeTheResultAndADoneOneAfter`.
  - `aRedoAfterAFailedResultWriteStillNamesTheRealDisabledFile` (review L1: `.disabled.1`, not a guessed `.disabled`).
- HelperLauncherTest: `keepsTheHelpersRecordOfUnfinishedGroups`.
- HelperCompat030Test (compatibility), using the pinned v030 copies:
  - A pending.json staged by 0.3.0's own PendingActions runs identically under the new helper and 0.3.0's: statuses,
    messages and resultPaths in last-apply.json, the rewritten pending.json, and the mods folder byte for byte.
  - 0.3.0's ApplyResult/ApplyFailures/HistoryUpdates read the new helper's last-apply.json after a failed rollback
    (FAILED + resultPath: STAGED, "try 1") and after the repairing run (SKIPPED + resultPath: APPLIED with the real
    `.disabled` resultFile).
  - 0.3.0's helper runs the pending.json the new one left (a downgrade), and the stale record is dropped by the new
    helper's next run.

## Compatibility
- pending.json: unchanged shape. The only difference is that `attempts` stops at 2 for a half-applied group; older
  helpers read it as usual.
- last-apply.json: unchanged shape. A FAILED result may carry `resultPath`, which 0.1.0-0.3.0 ignore on FAILED results
  (HistoryUpdates leaves FAILED changes alone; ApplyFailures and LegacyImport don't read it for FAILED). Proven for 0.3.0
  by HelperCompat030Test with the pinned verbatim copies. Coordinator decision (2026-09-26): WS-H's released-jar harness
  is not extended for last-apply.json now; the pinned test is the proof, and the coordinator may add a harness check in
  Phase 5.
- `helper/unfinished-groups.json`: new, read only by this helper. 0.1.0-0.3.0 clients delete it when they launch their
  helper (their cleanup removes every non-classpath file there). That is harmless: their helper then re-runs the group
  as before.
- `python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar` (sha256 5717f65c…, the GitHub release): PASS 9/9
  on this branch, unchanged.

## Known limits (UNVERIFIED where marked)
- A group left half-applied by an older helper (0.1.0-0.3.0, or one before this change) has no record. The new helper
  finishes it (as before), but if it then fails it can't roll back the older run's disable (as before).
- If a rollback fails again after its full budget, or the helper is killed during a rollback retry, and the missing jar
  is a dependency, Fabric won't start until the next helper run, which needs a clean exit. The record and the helper log
  name the files; the player repairs by hand, as before. This window is much smaller than before, but not zero.
- UNVERIFIED: power loss between a rename and the record. AtomicFiles doesn't fsync, so a record written microseconds
  before a rename may not survive a power cut (a process kill is fine: the OS keeps the written data).
- UNVERIFIED: real Windows sharing violations and kills were simulated with the Mover seam (a FileSystemException or
  an Error), not with a real locked file or a killed JVM. The audit's shutdown-hook idea was not implemented (not needed
  for the minimal fix).
- Review M2, declined (pre-existing audit M2, owned by WS-G2): Undo/Discard of a half-done update in game drops the
  group; the helper then prunes its renames (their ops are no longer in the plan) and doesn't re-enable the old jar,
  exactly as before this change. Keeping or acting on such orphans needs a duplicate check (the player may have
  installed the mod by hand meanwhile) and belongs with WS-G2's fix; the record isn't meant as its data source.
- Review lows accepted as limits: each retry pass redoes and rolls back the earlier renames (more renames of big jars
  under a long sharing violation; speculative whether that provokes scanners, L4); a record matches by name, not file
  identity, so a rename the player made by hand to the same names counts as the helper's (speculative, L5); a group
  stuck half-applied repeats "try 3 of 3" at every exit (L6); a death between writing last-apply.json and rewriting
  pending.json shows the done ops as staged until the next exit (History is right; cosmetic, L7); a rollback whose
  moved file vanished still says "the original name is taken" (pre-existing, L8).

## For DESIGN.md (coordinator)
- ApplyExecutor: "A group is tried as a whole: each op once per pass; on a failure every rename of the pass is rolled back
  at once and the group is retried under the failing op's retry budget (after a failed rollback, the rest is retried in
  place instead, then one more rollback). Each pass's renames are recorded in `config/rigtune/helper/unfinished-groups.json`
  first and kept until last-apply.json is written; after a kill or a failed rollback the next run finishes the group or,
  if it fails again, rolls back the earlier run's renames too. A group left half-applied is never abandoned."
- Retry policy: "…a failing op's retry is a retry of its whole group, after a rollback; a rollback has its own full budget."
- Helper writes: "pending.json loses its abandoned ops, then last-apply.json is written, then the done ops leave
  pending.json, then the journal."
- HelperLauncher: "…other files in the folder are deleted, except `unfinished-groups.json`."
