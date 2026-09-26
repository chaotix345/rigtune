# WS-G3 plan: helper atomicity (SPEC 2o: H4, L2)

Branch `fix/helper-audit`, worktree `C:/Dev/Worktrees/rigtune-g3`. Scope: PLAN.md "Hardening round" (WS-G3); findings
docs/research/v0.4/audit-apply-pipeline.md H4 and L2, verified in docs/v0.4/audit-verification.md (WP-3). Files:
ApplyExecutor (runGroup, retrying, rollback, run, writeRemaining), a new helper-safe `UnfinishedGroups`, HelperLauncher
(keep its file), ApplyGroupsTest, ApplyExecutorTest, HelperLauncherTest, a new HelperCompat030Test. No pending.json or
last-apply.json shape change, no new op type (SPEC "Compatibility promise").

## Task 1: H4 in one run: never retry in place after a group's first rename
- [ ] Red (ApplyGroupsTest): an update whose ENABLE hits a sharing violation twice; a Sleeper snapshots mods/ at every
  sleep. No snapshot may lack both the old and the new jar (today every one does).
- [ ] Green: runGroup tries each op once per attempt; a failure after a rename rolls back every rename of the attempt at
  once (rollback keeps its full budget), then the whole group is retried under one group-level RetryState. Existing
  ApplyGroupsTest/ApplyExecutorTest keep passing (single-op retries and messages unchanged).

## Task 2: H4 across runs: a group left half-applied is finished or rolled back by the next run
- [ ] Red: kill (an Error from the Mover) between the disable and the enable → next run ends with exactly one active
  copy: (a) it finishes the update and the disable's result names the real `.disabled` file; (b) when the enable still
  fails, the earlier run's disable is rolled back too. Same for an addition {enable mod, enable lib} killed after the mod.
- [ ] Red: a failed rollback → the next run repairs (finishes, or rolls back completely); a group left half-applied is
  never abandoned (its download stays `.rigtune-pending`, pending.json keeps it) even on its third run.
- [ ] Green: `config/rigtune/helper/unfinished-groups.json` (new file; older versions never read it) records a
  multi-op group's planned renames before its first rename, removed when the group is complete or rolled back, kept
  otherwise. The next run counts a recorded rename still in effect as done by this run (SKIPPED_ALREADY_DONE with its
  resultPath, on the undo list). A failed rollback's result carries resultPath (where the file was left); such a group
  isn't abandoned, and its attempts stop at MAX_FAILED_RUNS - 1. HelperLauncher's cleanup keeps the file.

## Task 3: L2: crash-safe order of pending.json and last-apply.json
- [ ] Red (ApplyExecutorTest): every op succeeds but last-apply.json can't be written → pending.json still holds the ops,
  reconcile leaves the changes STAGED (today ABANDONED), and the next run marks them APPLIED; a mixed run (OK +
  abandoned) with the same failure drops only the abandoned op first.
- [ ] Green: run() = (1) drop ABANDONED ops and count FAILED attempts in pending.json, (2) write last-apply.json,
  (3) drop the OK/SKIPPED ops, (4) the journal.

## Task 4: compatibility
- [ ] HelperCompat030Test: a 0.3.0-shaped pending.json runs the same under the new helper and the pinned 0.3.0 one; the
  pinned 0.3.0 ApplyResult/HistoryUpdates/ApplyFailures read the new helper's last-apply.json (failed rollback,
  recovered disable) like the new code does; the pinned 0.3.0 helper runs the pending.json the new one left.
- [ ] `python tools/e2e/compat030.py --old-jar <released 0.3.0 jar>`.

## Task 5: finish
- [ ] Full apply test suite + `./gradlew build` (26.2 and 26.3); code-reviewer subagent; fix high/medium findings.
- [ ] Merge origin/feat/v0.4.0, build, push, CI green; `docs/v0.4/design/ws-g3.md`.
