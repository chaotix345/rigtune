# WS-G2 design notes: undo safety (SPEC 2o: H5, M1 x 2n, M3, M2, M5, H1-B)

Branch `fix/undo-audit`. Plan: docs/v0.4/plans/ws-g2.md. Sources: docs/research/v0.4/audit-apply-pipeline.md and
docs/v0.4/audit-verification.md (WP-2, WP-4). Every fix has a test that failed first against the code it replaced
(behavioural red, checked by temporarily restoring the old UndoPlanner/Staging/merge locally; never committed).
Files: core/history/UndoPlanner, new core/history/FolderCheck and PartlyApplied, client/undo/Staging (discard only),
new client/undo/DisableGuard, RealController.apply's DisableMod case (one guard). Not touched: Journal, HistoryStartup,
LegacyImport, Staging.stage (WS-G4), DownloadPlanner/DependencyResolver (WS-G1), ApplyExecutor (WS-G3). No pending.json
or history.json shape change (no new field, no new op type), so 0.1.0-0.3.0 read what 0.4 writes exactly as before; the
v040-written fixtures are unchanged.

## H5 (MUST): a mod and its library are never undone in separate groups
- `UndoPlanner.netOps`: every file op of one undo is staged in ONE group (was: one group per union of original groups
  that moved the same files). The helper applies a group all-or-nothing, so Undo all of "Sodium, then Sodium Extra" can
  no longer leave Sodium Extra active without Sodium when one rename fails. Round trips (a file that ends where it
  started) still follow the first op of the original groups that moved the same files (union-find kept for that only).
- `joinedGroup`: the second of two Undo last in one start is often safe only because the first undo's staged op runs at
  the same exit (the planner's dependency check has always treated still-staged ops as done). When the plan's folder
  breaks something with no staged ops applied, the staged ops it relies on are found one by one (removing each from the
  overlay); if they all belong to one group, the new ops take that group id (merge keeps incoming groups, so they join
  it: the helper does both undos or neither). Relying on an ungrouped op, on more than one group, or on a combination no
  single op explains: the file part is skipped with `rigtune.undo.reason.waits_staged` ("restart once, then undo it").
  Reliance on a staged ENABLE (a re-enable needing a library an earlier undo re-enables) is handled the same way.
- Tests: UndoPlannerTest (one group for Undo all; every op of one undo one group; joins the first undo's group for a
  disable and for a re-enable; own group when nothing is relied on; waits when relying on an ungrouped op or two
  groups); UndoSafetyTest through the real Staging/UndoService/helper: Undo all and Undo last x2, then a helper whose
  rename of the dependant fails -> both jars still active (was: library disabled, dependant active); without failures both
  end disabled and every change REVERTED.

## M1 x 2n (MUST): the joint test
- `UndoSafetyTest.threeAlternatingUndoLast*`: Sodium chunk_builder_threads 0 -> 4 -> 0 -> 4 through the real stager and
  Staging (PendingActions.merge + StagedChanges journal records), the helper (ApplyExecutor) applies them (once with a
  restart between Applies, once all staged before one restart: 3 ops); then Undo last x3 through UndoService in one start
  (GameState-style `keyOf`); pending.json holds 0, 4, 0 with each undo recording its own op; the helper ends at 0 and
  every Apply change is REVERTED. It fails with the pre-M1 merge (`[0, 4]`, the third undo absorbed into the first) and
  with 2n reverted (the second Undo last finds nothing to undo). No code change was needed: WS-P's merge fix and WS-A's
  2n fix together are correct.

## M3 (SHOULD): the second Undo last targets the entry the first one left
- A reversal whose file a still-staged (not discarded by this plan) ENABLE/DISABLE moves at the next exit (by name:
  from/to/path) is skipped with `waits_restart` ("A change staged earlier still moves x-1.jar at the next restart;
  restart once, then undo it"), not with the false "x-1.jar is no longer in the mods folder".
- `plan()` (Undo last) returns an entry with a waiting skip instead of falling through to an older, unrelated entry;
  the Undo screen shows it with "Nothing here can be undone; the reasons are listed" (confirm disabled).
- Undo last / Undo this on an entry part of which waits (`waitWhole`): every item becomes a skip (`waits_entry`) and the
  script is empty, so the entry isn't half undone now and then passed over by Undo last (it counts as undone once an
  undo entry names it). Undo all keeps per-change skips (its undo entry marks every earlier entry anyway, as before).
- Chosen over the audit's "overlay the pending ops" option (verification: netOps would compute its renames from
  post-pending names or cancel the pending re-enable).

## M2 (MAY, done): Undo/Discard of a half-done update
- `PartlyApplied.groups(ops, files)`: a staged group whose DISABLE is done (jar gone, `.disabled`/`.disabled.N` there)
  while one of its ENABLEs isn't (download there, target missing): H4's state after a failed rollback or a killed helper.
- `UndoPlanner.planStaged` skips such a group with `waits_partly` (and Undo last stops at it); `Staging.discard` keeps it
  in pending.json (everything else discarded, downloads retired, journal DISCARDED as before), so the next exit finishes
  it (disable SKIPPED_ALREADY_DONE, enable OK) and a normal undo is possible afterwards. Chosen over staging a re-enable:
  no unjournaled op, and the mod comes back either way.
- If WS-G3's H4 fix introduces a new intermediate name (e.g. `.rigtune-ready`), PartlyApplied must learn it (noted for
  the coordinator's merge of G2/G3).

## M5 + H1-B (MAY, done): the RigTune screen's "Disable X"
- New core `FolderCheck`: `problems(files, providedElsewhere)` (UndoPlanner.violations' body, which now delegates, so
  Undo and Apply refuse the same folders) and `disableRefusal(folder, pending, file)`:
  - M5: the jar's DISABLE is already staged in a group that also enables a jar (an update, or an undo's swap) -> refuse
    "Another change of it is staged; cancel that first (Undo last or Discard pending)". Refusing (the audit's
    alternative) instead of unstaging the update keeps RealController's staged-recommendation count right without more
    RealController code; the player sees the reason and can cancel the update first.
  - H1-B: with the staged ops done first (the helper runs them before an op staged after them), disabling the jar
    leaves an active jar without a mod it depends on -> refuse "The game wouldn't start without it: y would be missing x".
    Loaded jars' nested mods count as provided (ModsFolder).
- `client/undo/DisableGuard.allows(pendingFile, modsDir, file)`: reads pending.json and `ModsFolder.current`, logs and
  shows a toast (`rigtune.toast.disable_refused.*`); an unexpected exception logs and allows (v0.3 behaviour).
  RealController: the DisableMod case gains `&& DisableGuard.allows(...)` (a refused disable falls to the existing
  "Nothing to apply for" log; the rest of the Apply goes ahead).
- Tests: FolderCheckTest (9 cases), UndoSafetyTest (a library an installed mod needs; a staged update absorbing the
  disable today, then refused).

## Deviations / residuals
- A refused "Disable X" isn't counted in the Apply status line (the toast carries the reason); Preview doesn't show it
  as a skip reason (Preview is PreviewPlanner's, outside this package).
- `waitingFile` also makes a reversal wait when the moving op is an unjournaled carried-over op (another session's or
  0.1.0's); conservative (nothing is undone wrongly), and after the restart the plan is made against the real folder.
- `dropQueuedUpdates` (a staged update of a mod whose own updater queued a build) still unstages a half-done group (M2's
  state plus a queued build of the same mod; not reachable in practice without H4's state).
