# WS-G2 plan: undo safety (SPEC 2o: H5, M1 x 2n, M3; MAY: M2, M5, H1-B)

Branch `fix/undo-audit`, worktree `rigtune-g2`. Sources: docs/research/v0.4/audit-apply-pipeline.md (H5, M1, M2, M3, M5,
H1), docs/v0.4/audit-verification.md (WP-2, WP-4), docs/v0.4/design/ws-a.md (2n), ws-p.md (M1 merge fix).
Files: core/history/UndoPlanner (planFiles, netOps, violations, planStaged, plan), a new core FolderCheck, client/undo
Staging (discard/unstage only; never stage()), RealController.apply's DisableMod branch (one call). Not touched:
Journal, HistoryStartup, LegacyImport, Staging.stage (WS-G4), DownloadPlanner/DependencyResolver (WS-G1),
ApplyExecutor (WS-G3).

Every task: a test that fails first, then the fix, `./gradlew build` (both versions), commit, push.

- [ ] T1 (MUST) joint M1 x 2n: `UndoSafetyTest.threeAlternatingUndoLastInOneStartEndAtTheFirstValue`: three staged
      applies of one Sodium key (A -> B -> A -> B) through the real stager, Staging (PendingActions.merge,
      StagedChanges), helper (ApplyExecutor); then Undo last x3 through UndoService in one start; helper -> the file is at
      A; pending.json had 3 ops. Proven red against the pre-M1 merge (temporarily reverted locally, not committed).
- [ ] T2 (MUST) H5: `UndoPlannerTest`: Undo all of lib (e1, g1) + dependant (e2, g2) -> one distinct op group; second
      Undo last with the first undo's disable of the dependant still pending -> the new op joins that group (or, when the
      plan leans on staged ops of more than one group / an ungrouped op, it is refused with a clear reason). Real stack:
      `UndoSafetyTest` with a helper whose rename of one jar fails -> never the dependant active without its library.
- [ ] T3 (SHOULD) M3: a reversal whose file a still-pending op moves waits for the restart (new reason), Undo last
      stops at that entry (no fall-through to an older one), and an entry with a waiting part is not partly undone by
      Undo last / Undo this; FILE_GONE only when the file really is gone (Undo all too).
- [ ] T4 (MAY) M5: a later "Disable X" while "Update X" is staged: the update group is unstaged (download retired) and
      the disable staged, through one RealController call.
- [ ] T5 (MAY) H1-B: `FolderCheck` extracted from UndoPlanner.violations; the same RealController call refuses a
      disable an active jar depends on.
- [ ] T6 (MAY) M2: Undo/Discard of a half-applied update group (disable done, enable pending) -> skip with a reason /
      Discard keeps (or re-enables) instead of dropping silently.
- [ ] T7 compat030 harness if history.json/pending.json content changes; self-review (code-reviewer); merge
      origin/feat/v0.4.0; CI green; docs/v0.4/design/ws-g2.md.
