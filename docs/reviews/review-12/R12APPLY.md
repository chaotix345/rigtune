# Review 12, area R12APPLY: apply/history re-check of review-11's fixes

Worktree C:/Dev/Worktrees/rigtune-review12 @ ac109a2d, range ce8b1a8b..ac109a2d. Read-only; nothing run.
Paths: `client/` = src/client/java/io/github/chaotix345/rigtune/client/, `core/` = src/main/java/io/github/chaotix345/rigtune/core/.

Counts: 0 HIGH, 3 MEDIUM, 3 LOW.

## Verdict on the hand-resolved ApplyExecutor merge (3f15188c)

The resolution is right: WS-L2's `heldIndexes` is kept as it is (no PartlyApplied, `earlierRenames(..., true)`: only a
rename marked `done: true` and in effect, or `doneByLastRun`), and WS-H's `startedByRecords` uses
`earlierRenames(..., false)`. That broader rule counts `done` true or null, and never false. It is exactly the rule
`runGroup` itself uses for "done earlier" (core/apply/ApplyExecutor.java:572), so Discard and the stale check predict what
an unheld helper will do.
- Hold vs. launcher-made evidence: `done: true` is written only right after a real rename (:653), or by `renames()` for a
  rename the unheld helper already took as done (:746). A launcher disable can't create one. The only way to get
  `done: true` over a file that was put back is the rollback window in R12APPLY-4 (LOW). `doneByLastRun` needs RigTune's
  own OK/SKIPPED+resultPath for the same op id. No realistic launcher-only path releases the hold.
- Discard/stale never dropping a started group: holds for grouped ops (PartlyApplied also ignores `done`, so a kill
  between a rename and its mark is kept while another op is left). There are two gaps: Undo's staged cancel
  (R12APPLY-1) and ungrouped ops in Discard (R12APPLY-2).
- Wrong rule on a path: none in ApplyExecutor. The hold, `held()` (notice, toasts, Cancel, Try It, restart count) and
  `runGroup` use the right rules. The mismatch is outside it: the broad keep-set's "finished at the next restart" wording
  also applies to groups the hold holds (R12APPLY-3).
- ApplyExecutorCrashReplayTest.aDiscardAtTheNextStartKeepsAStartedGroup (:200-211) still models "killed after both renames,
  before last-apply.json". The local `killedAfter(enable)` (:88-95) throws from the record writer after it durably wrote
  the record with the enable marked done. The writes are put (both false), mark D, then mark E, so both renames happened
  and both are marked. `Killed` is an Error, which `UnfinishedGroups.save` doesn't catch, so `run` never reaches
  last-apply.json. `nextStart()` runs the real `Staging.dropStale` and asserts NONE, which exercises `startedGroups`.

## Status of review-11 findings in this area

| r11 id | status | evidence at ac109a2d |
|---|---|---|
| APPLY-1 | PARTLY | Fixed for preLaunch (client/RigTunePreLaunch.java:204-205), `dropStale`, `dropQueuedUpdates` and grouped Discard (client/undo/Staging.java:380-391). Still open: Undo's staged cancel (R12APPLY-1) and ungrouped ops in Discard (R12APPLY-2) |
| APPLY-2 = COMPAT-1 | FIXED (sub-point open) | core/history/SkippedClaims.java:52-86: only bare-SKIPPED enables, plus same-group disables when that enable is relabelled. 0.5's helper can't write a bare-SKIPPED enable (`installedElsewhere` runs first; only a race reaches `enable()`'s branch), so in practice the relabel happens once. The r11 sub-point "this start's toast was read before the relabel" is NOT FIXED (R12APPLY-5) |
| APPLY-3 | FIXED | client/RealController.java:590-611; `heldOps()` is gated on `HelperLauncher.holds` |
| APPLY-4 | FIXED | client/tryit/TryItService.java:221-246, 963-972: the context's `pending` is "ops not held" |
| APPLY-5 | FIXED | `Rename.done`; marks at ApplyExecutor.java:653 and :777; `earlierRenames` provenOnly/false; the four hold tests. The undocumented reverse window is R12APPLY-4 |
| APPLY-6 | FIXED | LauncherRepairService.java:186-249: counted ops only, policy re-checked, busy toast |
| APPLY-7 | FIXED | core/launcher/InstanceEvidence.java:55-59 fails closed. A per-entry stat race also fails closed (safe) |
| APPLY-8 | FIXED | client/RigTuneClient.java:172-175 `catch (Throwable)` -> PENDING |
| COMPAT-4 | FIXED | HistoryModel.java:177 `pairUpdates` at display; no file change |
| COMPAT-7 | FIXED | ws-h.md:302-306 |
| BENCH-8 | FIXED | BenchmarkConditions.java:69-72, one `snapshot()` |
| SEC-4 | FIXED | RangeReader.java:309-316 reserves `length` before sending and refunds `length - body.length`; a throw keeps the reservation |
| PERF-1 | FIXED | HistoryStartup.java:61-72: relabel inside the reconcile's `updateExisting`; `recorded` read only for a candidate; the interrupted-relabel completion still works (`updateExisting` returns true on no change) |
| PERF-2 (JournalCache) | PARTLY | The cache and the start-hook callers are right (see "checked"). StutterFixService's reader never switched to the cache (R12APPLY-6) |
| WS-H self-review M (Discard) | FIXED for grouped ops | crash-replay test above; ungrouped gap R12APPLY-2 |

## Findings

| id | sev | file:line | scenario (inputs/state -> wrong outcome) | fix |
|---|---|---|---|---|
| R12APPLY-1 | MEDIUM | core/history/UndoPlanner.java:478, 488-492; client/undo/UndoService.java:64, 72, 89, 97 | **APPLY-1's cause is still open on the Undo path.** Undo's cancel of a STAGED change keeps only `PartlyApplied` groups. It never uses `ApplyExecutor.startedGroups`, which WS-H added to Discard, dropQueuedUpdates and dropStale. Case (1) of APPLY-1: a 0.5 helper finishes and marks both renames of "Update Sodium" (sodium-0.7.0.jar -> .disabled, 0.7.1 download -> sodium-0.7.1.jar) and is killed before last-apply.json. At the next start the journal still says STAGED and the stale check correctly keeps the group. `PartlyApplied` returns nothing: recordedDone={g} but left={} (both renames done), and the disable has attempts 0. The player opens History and presses Undo last (or Undo this) on that Apply. `planStaged` plans DISCARD_STAGED, and `UndoService.undo` calls `unstageLocked` with no started-group guard. pending.json loses both ops and History marks them DISCARDED ("Cancelled"), yet 0.7.1 is enabled and 0.7.0 is `.disabled`. The game runs the update, Undo can never revert it (DISCARDED isn't undoable), the helper never reports it, and unfinished-groups.json is orphaned. The same happens to an ungrouped started op (key "o:<id>"). | Pass the started groups into the plan: in `UndoService.plan/planEntry/undo` (under the lock for `undo`), compute `ApplyExecutor.startedGroups(plan, pendingFile)` and add a `Set<String> started` parameter to `UndoPlanner.plan/planEntry/recheck`. In `planStaged`, treat `partly.contains(g) \|\| started.contains(op.group() != null ? op.group() : "op:" + op.id())` as WAITS_PARTLY. Test: the crash-replay kill, then `UndoPlanner.plan` gives a waiting plan with no DISCARD_STAGED item. |
| R12APPLY-2 | MEDIUM | client/undo/Staging.java:351-352, 404-409 (also :296); core/apply/ApplyExecutor.java:421-425; client/RealController.java:515 | **Discard reads startedGroups' "op:<id>" keys as group ids.** `startedGroups` returns "op:<id>" for an ungrouped op, and every "Disable X" recommendation is one (`Op.disableFile` with no group). `discardExcept` keeps only `op.group() != null && halfDone.contains(op.group())`. Scenario: the player applies "Disable Foo" plus an update. At exit the 0.5 helper renames foo.jar -> foo.jar.disabled and marks it done, then the update's group sits in its up-to-30 s sharing-violation backoff. The PC shuts down before last-apply.json. At the next start the player presses Discard pending. halfDone={"op:<fooId>"} is non-empty, so `discardExcept` runs, keeps nothing, drops Foo's op, and History marks it DISCARDED over RigTune's own rename. The status line says "Discarded 2 pending change(s); changes already under way are finished or undone at the next restart", although nothing was kept. Foo stays disabled and no Undo is offered. Before WS-H the same drop came with the honest plain "Discarded 2" message; the false "kept" wording is new. `dropQueuedUpdates` (:296) has the same group-only test. | Key by `op.group() != null ? op.group() : "op:" + op.id()` in `discardExcept` and `dropQueuedUpdates`, as StaleOps does. Report `keptGroup` from `!kept.isEmpty()`, not `!halfDone.isEmpty()`. Test: an ungrouped disable done by a killed helper survives Discard, and the next run reports it "Already done earlier". |
| R12APPLY-3 | MEDIUM | client/undo/Staging.java:344-352, 380-391; core/history/UndoPlanner.java:478-492, 378-380; lang en_us.json:632, :859; interplay with core/apply/ApplyExecutor.java:387-400 | **The keep-set says "the next restart finishes it" for groups the hold never runs.** Before review 11, every group Discard or Undo treated as half done was also unheld. APPLY-5 narrowed the hold to done-marked renames, and WS-H widened the keep-set to done=null records. Under LAUNCHER/PENDING, some groups are now in the keep-set (PartlyApplied: any recorded rename in effect, or a disable with attempts>0 that looks done; startedGroups: a 0.4 record) yet held at every exit. Example: a Modrinth App instance whose 0.4.0 exit tried "Update Sodium" and rolled back cleanly after a lock (attempts=1, record pruned). After upgrading, the player disables Sodium in the app. The hold correctly holds the group (APPLY-5), but `PartlyApplied` sees the disabled-looking jar plus the pending enable. Result: Discard pending keeps the group and says "changes already under way are finished or undone at the next restart". Undo gives "It was partly applied at the last exit; restart once so it finishes, then undo it" and sets `waits`. `waitWhole` then blocks the whole entry (its settings too), and Undo last can't get past it. Every restart holds the group again, so both claims stay false and the Undo path is stuck; only the HELD notice's buttons resolve it. The same applies to a 0.4 record, a 0.5 kill between a rename and its mark, and APPLY-5's own scenario. ws-l2.md:129-131's residual ("Cancel them then makes it permanent ... Discard pending does the same in 0.4") describes a different behaviour (Discard keeps these groups). | When `HelperLauncher.holds(policy)`, subtract the groups `ApplyExecutor.held(plan, pendingFile)` returns from the keep-set in `discardPending` and from `partly` in `planStaged`, so both cancel a held group exactly as the notice's Cancel does. Alternatively, keep the groups but use a "waits for your choice (see the notice)" wording that isn't a WAITING key. The callers (RealController/UndoService) know the policy and pass the held keys in. Fix the ws-l2.md residual text. |
| R12APPLY-4 | LOW | core/apply/ApplyExecutor.java:770-781 (then 808-817); core/apply/UnfinishedGroups.java:161-171, 198-212 | **A reverse window reopens APPLY-5.** A rollback moves the file back first and writes `done: false` after. Two things leave `done: true` on disk for a rename that was put back: a kill in between (each backoff pass of a retrying group has such a window), or a failed durable write. `save()` logs and sets dirty, and the group's `finish`/`prune` rewrite can fail the same way (for example AV holding the file for 10x100 ms). The launcher's own later disable of that jar (the Modrinth App gives the same `x.jar.disabled`) then counts as proven under LAUNCHER. `heldIndexes` releases the group, `runGroup` reports the app's disable "Already done earlier" with a resultPath, and it enables the download in a launcher-managed instance without the opt-in. This is APPLY-5's exact outcome through a much narrower window. It isn't in ws-l2.md's residuals, which only name the rename -> mark window (the safe direction). | Mark first: write `done: false` before the rollback's move, and re-mark `done: true` only if the move fails and the file stays under the group's name (stuck). A kill then errs toward "held". Or document it as a residual next to the rename -> mark window. |
| R12APPLY-5 | LOW | client/RigTunePreLaunch.java:63-67; core/apply/ApplyResult.java:46; client/HelperToasts.java:24-30 | APPLY-2's second point is not fixed. `readState` stores `unseenResult` from last-apply.json before `HistoryStartup.run` relabels it. Scenario: a 0.4.0 exit's helper reports the RW-1 DH pair as SKIPPED "already done" and the player's next start is 0.5. The title toast "RigTune applied 2 change(s)" counts both (SKIPPED counts as applied), while History, on the same start, says "Not applied: installed another way". The inconsistency is one-time only. | After `HistoryStartup.run`, re-read `unseenResult` when the relabel ran (keep `finishedAt`), or run `readState`'s result read after the relabel. |
| R12APPLY-6 | LOW | client/stutter/StutterFixService.java:90 (used at :204, :451); core/history/JournalCache.java:13-19 | PERF-2 is only partly fixed. JournalCache's comment names "the rebuild's stutter-fix hold" as a sharer, but StutterFixService's `history` is still `ClientJournal.get().snapshot()`. ws-s2.md:489-491 says "JournalCache isn't on feat/v0.5.0 yet", and ws-h.md:329 says "StutterFixService is ws-s2's", so neither owner switched it. For a player with a tracked C20 fix, every rebuild (setting change, Apply, Undo, late launcher answer) and every 5 s with the Stutter Doctor open still parses history.json (up to about 200 KB) on the worker. | `volatile Supplier<Journal.Snapshot> history = () -> JournalCache.snapshot(ClientJournal.get());`. Tests already inject `history`. |

## Checked, fine
- `startedByRecords` (broad) vs. `heldIndexes` (proven): the broad rule is `runGroup`'s own "done earlier" rule, so for an
  unheld group Discard's and the stale check's "the next exit reports it done earlier" is true. A 0.4 record (done=null)
  in effect is kept and then claimed by the unheld helper, as in 0.4. That is consistent with ws-l2.md:302-304, not a new
  RW-1 path.
- `renames()`/`mark()` bookkeeping: earlier renames are re-put as done=true, new ones as false, and each is marked after
  its own rename. Stuck rollbacks stay true, and a clean rollback is marked false. `put()` rewrites only on change or when
  dirty. `finish`+`prune` remove a finished group. A held-only run writes nothing and keeps held groups' records. The
  `group` key is the same in `put`, `mark` and `finish`.
- The `done` field and downgrades: 0.4.0's Gson record ignores it (the ws-l2 gate). 0.4.0's rewrite drops it, so 0.5 reads
  null: held under the hold, done earlier without it (documented). 0.1.0-0.3.0 never read the file. 0.5 never writes a
  null mark.
- SkippedClaims/HistoryStartup: one journal read per start. The relabel failure is contained. The journal is written
  first, then last-apply.json, and an interrupted relabel completes, because `updateExisting` returns true on an unchanged
  file and the ABANDONED+SKIPPED pair is re-claimed. 0.5's lone "already gone" disable and a disable next to an OK enable
  stay APPLIED. `finishedAt` is kept.
- JournalCache: per Journal instance. The game has one (`ClientJournal.get()`); the helper's Journal is another process.
  The write counter covers every in-JVM write, including the CORRUPT -> .bad move. A write racing a read is never cached,
  because the key is compared before and after. On Windows `fileKey` is null, so the key is size + mtime. The only
  cross-process writer during a session is a helper still running at start (preLaunch waited 5 s). It writes seconds
  later, and every status change it makes (STAGED -> APPLIED/ABANDONED) changes the file's size, so the next ask misses.
  Missing and unreadable files are never cached. The WeakHashMap value holds no Journal. Callers only read the entries.
- preLaunch `staleGroups` mirrors `dropStale` (PartlyApplied + startedGroups, "op:<id>" keys). StaleOps now checks
  ungrouped enables by "op:<id>".
- LauncherRepairService.cancelHeld uses the narrow rule on purpose, so it drops held groups the broad rule would keep.
  This is the accepted ws-l2 residual.
- Notes (no finding): the ApplyExecutor.java:412 comment "by heldIndexes' rule" is stale, since it is now the broader
  rule. PreLaunchStaleOpsTest.aGroupTheHelperStartedIsNeverStale (:102-111) kills before the enable's mark
  (`TestExecutors.killedAfter`), so the "next exit reports it done earlier" in its comment holds for the disable only;
  the helper drops the enable as installed another way (case 1b). The test's assertions still hold.
