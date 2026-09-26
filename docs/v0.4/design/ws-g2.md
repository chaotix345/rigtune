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
- `PartlyApplied.groups(ops, files)`: a staged group whose DISABLE the helper already tried (`attempts > 0`) is done (jar
  gone, `.disabled`/`.disabled.N` there) while one of its ENABLEs isn't (download there, target missing): H4's state after
  a failed enable and a failed rollback. `attempts > 0` keeps an old `x.jar.disabled` next to a never-run update (x.jar
  removed by hand) from counting; the price is that a helper killed before it rewrote pending.json isn't detected (WS-G3's
  H4 fix recovers that case at the next run).
- `UndoPlanner.planStaged` skips such a group with `waits_partly` (and Undo last stops at it); `Staging.discard` keeps it
  in pending.json (everything else discarded, downloads retired, journal DISCARDED as before), so the next exit finishes
  it (disable SKIPPED_ALREADY_DONE, enable OK) and a normal undo is possible afterwards. Chosen over staging a re-enable:
  no unjournaled op, and the mod comes back either way.
- Checked against WS-G3's merged H4 fix (helper/unfinished-groups.json, roll forward or back at the next run): a failed
  rollback still leaves exactly this state with the FAILED ops' attempts counted, and UndoSafetyTest's half-done cases
  run through the new helper (the next exit finishes the update). Residual: a helper killed mid-group leaves attempts at
  0, so PartlyApplied doesn't see it; an Undo or Discard before the next exit would drop that group and the helper's
  record of it is then pruned (kill + a game that still starts + a cancel in that session). UnfinishedGroups' reader is
  package-private in core/apply (WS-G3's file), so using the record itself is left to a follow-up.

## M5 + H1-B (MAY, done): the RigTune screen's "Disable X"
- New core `FolderCheck`: `problems(files, providedElsewhere)` (UndoPlanner.violations' body, which now delegates, so
  Undo and Apply refuse the same folders) and `disableRefusals(folder, pending, files)`, one Apply's disables checked
  as a set:
  - M5: the jar's DISABLE is already staged in a group whose ENABLE brings the same mod back (same file name or mod id:
    an update, or an undo's swap back to an older jar) -> refuse "Another change of it is staged; cancel that first
    (Undo last or Discard pending)". A group that disables it and enables another mod (an undo of several mods, one group
    since H5) already does what was asked and isn't refused. Refusing (the audit's
    alternative) instead of unstaging the update keeps RealController's staged-recommendation count right without more
    RealController code; the player sees the reason and can cancel the update first.
  - H1-B: with the staged ops done first (the helper runs them before ops staged after them) and the Apply's other
    disables too, a jar left active lacks a mod it depends on -> the disable providing it is refused ("The game wouldn't
    start without it: y would be missing x"), one at a time until the folder starts: disabling a library together with
    every jar that needs it goes ahead; of two providers of one id only the first is refused; a chain x <- y <- z with x and
    y ticked refuses both. Loaded jars' nested mods count as provided (ModsFolder).
- `client/undo/DisableGuard.allowed(pendingFile, modsDir, selected)`: once per Apply, reads pending.json and
  `ModsFolder.current`, logs each refusal and shows one toast (`rigtune.toast.disable_refused.*`); an unexpected
  exception logs and allows (v0.3 behaviour). RealController.apply: one line computing the allowed ids before the loop,
  and the DisableMod case gains `&& disablesAllowed.contains(r.id())` (a refused disable falls to the existing "Nothing
  to apply for" log; the rest of the Apply goes ahead).
- Tests: FolderCheckTest (14 cases), UndoSafetyTest (a library an installed mod needs, alone and together with its
  dependant; a staged update absorbing the disable today, then refused).

## Self-review (code-reviewer subagent; scratchpad ws-g2/review.md): 0 high, 2 medium, 7 low
- M1 (fixed): the Apply's own disables weren't checked against each other (two providers of one id both disabled;
  "Disable lib" + "Disable app" refused lib). Now `disableRefusals` over the Apply's set (above).
- M2 (accepted, pinned by `UndoSafetyTest.undoAllOfUnrelatedModsWaitsWhollyWhenOneRenameFails`): one group per undo means
  one jar another program holds open holds back the whole undo (retried at the next exits; abandoned after 3 as a whole)
  instead of the unrelated mods being undone alone. Kept because it is what SPEC 2o/the verification asked for ("one
  group, or refuse"), and grouping only by dependency edges would also need mod-id edges: re-enabling x-old (one entry)
  and disabling x-new (another, no shared file) in separate groups can load x twice when only one succeeds.
- L1 (fixed): the M5 rule now needs the staged ENABLE to bring the same mod back. L2 (fixed): `attempts > 0`.
  L6 (fixed): one folder read and one toast per Apply. L7: tests added for two disables in one Apply, Undo this on a
  partly waiting entry, a re-check that turns into waits, `.disabled.N`, a stale copy, Discard with only a half-done group,
  Undo last of a half-done update through the real helper, Undo all with one failing rename.
- Left (documented below): L3 (Discard's status doesn't mention kept ops), L4 (joinedGroup doesn't try the joined group
  together with some of the other staged groups; only matters with two mods providing one id), L5 (an empty UNDO entry
  when a confirm-time re-check turns everything into waits: harmless, it doesn't count as an undo; UndoService is outside
  this package).

## Deviations / residuals
- Discard pending's status still says "Discarded N pending change(s)" when a half-done group was kept (pending.json and
  the Discard button stay); RealController's discard message is outside this package.
- A refused "Disable X" isn't counted in the Apply status line (the toast carries the reason); Preview doesn't show it
  as a skip reason (Preview is PreviewPlanner's, outside this package).
- `waitingFile` also makes a reversal wait when the moving op is an unjournaled carried-over op (another session's or
  0.1.0's); conservative (nothing is undone wrongly), and after the restart the plan is made against the real folder.
- `dropQueuedUpdates` (a staged update of a mod whose own updater queued a build) still unstages a half-done group (M2's
  state plus a queued build of the same mod; not reachable in practice without H4's state).
