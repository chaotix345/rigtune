# Old v0.x worktrees: what they held

2026-11-26, against main at 714582b9. STATUS.md "Housekeeping" kept two worktrees because they had uncommitted files: `C:\Dev\Worktrees\rigtune-audit-verify` (branch `research/v05-audit-verify` at 918927ea) and `C:\Dev\Worktrees\rigtune-realworld` (branch `research/v05-realworld` at e4832c60). Both branches are ancestors of main.

## Verdict

Both worktrees and both branches can be removed. All 8 files are saved, and each one is either a throwaway test whose findings main already fixed and tests, or an earlier draft of a fix main has in a later form. Nothing needs a Backlog item.

## Route taken

The brief expected `git status` to fail in these worktrees. It works: their `.git` files were rewritten on 6 Nov to point at `C:/Dev/rigtune/.git/worktrees/`. So the file list comes from `git -C <worktree> status --porcelain --ignored --untracked-files=all`, which also covers ignored files, instead of a hand comparison of the trees. A commit or bundle on the branches would have meant writing inside the worktrees, so the files were copied instead. Neither worktree has a stash entry.

Skipped as regenerable: `build/`, `.gradle/`, `versions/*/build/` in both, and `tools/__pycache__/update_rules.cpython-314.pyc` in rigtune-realworld.

## Where the copies are

- `C:\Users\Admin\Documents\rigtune-archive\old-worktrees-2026-11-26\<worktree name>\`, with each file at its path in the worktree.
- A second copy at `C:\Users\Admin\Documents\rigtune-archive\backup\old-worktrees-2026-11-26\`.
- Each worktree folder has `SHA256SUMS` (check with `sha256sum -c SHA256SUMS` from that folder) and `SOURCE.txt`.
- `rigtune-realworld\_git\` also holds `git diff` of the ApplyExecutor.java edit and the file as committed at e4832c60, so the edit can be read without the worktree.

All copies were hashed against the manifests after copying, in both locations.

## Per-file verdict

### rigtune-realworld

| File | Status | Verdict |
|---|---|---|
| `src/main/.../core/apply/ApplyExecutor.java` | modified, +46 -4 | Superseded. An earlier draft of 0782fb05 (RW-1, 27 Sep), which reached main in PR #13 (0.5.0). |
| `src/test/.../core/history/RealWorldFixTest.java` | untracked, 91 lines | Superseded. The draft of the same-named test committed in 0782fb05, now on main with 5 tests against the draft's 2. |
| `src/test/.../core/history/RealWorld20260927Test.java` | untracked, 290 lines | Superseded. Never committed under this name. Its scenarios are split across main's `RealWorldFixTest` and `RealWorldUndoTest` (see below). |

**ApplyExecutor.java.** The draft adds `installedElsewhere`: an enable whose download is gone while its jar is present, with no record of RigTune doing the rename, drops its group as "installed another way" instead of counting as done. Records count as proof if `unfinished-groups.json` has the rename or the previous `last-apply.json` has the op as done. Main has the same check, word for word in its message (`ApplyExecutor.java:500-507`), and is stricter than the draft in two places:

- The draft accepts any op id that the last run marked done. Main's `doneByLastRun` (`ApplyExecutor.java:515`) also requires the same op type, the same target file name, and the files still in the done state, and it returns where the file went so a second crash in the same window still proves it.
- Main logs an unreadable `last-apply.json` instead of ignoring it silently.

Main has since built more on it (the 0.5 `holdFileOps` path at `ApplyExecutor.java:178-187`, review-11 APPLY-5, review-12). Nothing in the draft is missing from main.

**RealWorld20260927Test.java.** Its header calls it "Throwaway". It replayed the user's 27 Sep instance (`docs/research/v0.5/real-world-2026-09-27.md`) from a copy in an old session's temp scratchpad. That copy no longer exists, and four of its six tests `assumeTrue` on it, so they would skip anywhere else. Main's `RealWorldUndoTest` header says it is "formerly rigtune-realworld's throwaway RealWorld20260927Test (its Undo half)", built on templated fixtures in `src/test/resources/realworld/` (61c026d3). Mapping:

| Old test | On main |
|---|---|
| `theRealHelperRunIsReproduced` | `RealWorldFixTest.theRealCaseIsDroppedAsInstalledAnotherWay` (now asserts the fixed outcome) |
| `withTheDownloadStillThereTheGroupIsAbandoned` | `RealWorldFixTest.withTheDownloadStillThereTheGroupIsDroppedAsBefore` |
| `undoOnTheCopiedInstance` | `RealWorldUndoTest.theRecordsAreTheRealOnes`, `underRigTuneTheOldPlanWithRw14`, `underLauncherNoUndoStagesAFileOp` |
| `hadTheAppDisabledTheOldJarUndoWouldSwapDhBack` | `RealWorldUndoTest.variantDStagesNoRenameOfTheAppsJars` (asserts the fix: no swap) |
| `anAdditionInstalledThroughTheAppIsDisabledByUndo` | `RealWorldFixTest.anAdditionInstalledThroughTheAppIsNotClaimed` (the helper no longer claims the jar, so Undo has nothing to disable) |
| `aRecordedRenameStaysDone` | `RealWorldFixTest.aRecordedRenameStaysDone` |

Three of the old tests assert the 0.4.0 bug, so they would fail on main. That is expected and is why they shouldn't be revived as they are.

### rigtune-audit-verify

All five are untracked throwaway tests from 27 Sep. `docs/research/v0.5/audit-v040-verification.md:10-12` describes them ("throwaway JUnit tests in the worktree ... not committed") and its summary table names, for each finding, the main test the check should move to. Each test asserts the fixed behaviour, so it failed while the finding held. Every finding was fixed on 27-28 Sep and reached main in PR #13 (0.5.0), each with a regression test.

| File | Tests | Finding: fix commit | Regression test on main |
|---|---|---|---|
| `client/awareness/AuditVerifyAwarenessTest.java` | `aw1ShownNoticeSurvivesARescan`, `latentUnknownGpuIsNoChange` | AW-1: 930a35b1. Latent 2: 43b7878e | `AwarenessServiceTest`, `ChangeDetectorTest` |
| `client/profile/AuditVerifyProfileTest.java` | `pf4SixtyFromASixtyHzSenderStaysSixty`, `pf5BaselineKeepsADhRadiusAbove512` | PF-4: 961285bb. PF-5: 7f98330c, 0516b642 | `ShareCodeTest`, `ProfileStoreTest`, `ProfileSwitchTest`, `V050WrittenWsPTest` |
| `client/stutter/AuditVerifyStutterServiceTest.java` | `sd4ClearWhileTheSavedLoadIsQueuedKeepsItCleared` | SD-4: 0bc7f90e | `StutterServiceTest` |
| `core/benchmark/AuditVerifyBenchmarkTest.java` | `bh1NoteAndTrendAgree`, `bh2ChangeStagedDuringTheLatestRunIsNotListedForIt` | BH-1: ef45e840. BH-2: a0bad56c | `TrendTextTest`, `ChangeWindowTest`, `BenchmarkConditionsTest` |
| `core/stutter/AuditVerifyStutterTest.java` | `sd1FullGcSurvivesTheGcRingWrapping`, `sd1DhTagShareCoversTheWholeSession`, `sd2OnePercentLowIsNeverAboveTheAverage`, `sd5NullUnknownCauseDoesNotThrow`, `ringCapacities` | SD-1: 48dfa12f, d4dc6662. SD-2: 76748e02, db26d764. SD-5: 884cf4af | `StutterAnalyzerTest`, `StutterStoreTest` |

`ringCapacities` checks no finding. It only pins the ring sizes (2048 GC, 4096 sampler) that SD-1's write-up quotes, and SD-1's fix made the analysis independent of them.

These tests were not compiled or run against main for this report (no extra Gradle runs this week). The verdicts rest on the fix commits and the named regression tests above.

## Unclear

Nothing about the files themselves. One related item can't be checked: the instance copy that `RealWorld20260927Test` read was in a since-deleted temp scratchpad, not in the worktree, so it couldn't be compared. Main's templated capture in `src/test/resources/realworld/` replaced it in 61c026d3.

## Backlog

None. For HQ, removal is `git worktree remove --force` on each path (`--force` because of the untracked and modified files saved above), then deleting the two merged local branches.

## Checks run

```
git -C <worktree> status --porcelain --ignored --untracked-files=all
git -C <worktree> stash list
git merge-base --is-ancestor <branch head> main
git -C C:/Dev/Worktrees/rigtune-realworld diff                      # the ApplyExecutor edit
diff <worktree ApplyExecutor.java> <(git show 0782fb05:<same path>)
git log --all -S<test class name>                                      # each saved test
git log main --grep <finding id>                                       # each finding
git merge-base --is-ancestor <fix commit> a20c8eac                     # all 15 fix commits are in PR #13
```
