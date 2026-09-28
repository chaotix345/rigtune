# RigTune v0.5.0: review round 2 (review-12)

Range: `ce8b1a8b..ac109a2d` (the review-11 fix diff), with the whole tree for context.

Method: six read-only reviewers: R12APPLY (the apply and history re-check, including the coordinator's hand-resolved ApplyExecutor merge of APPLY-1 and APPLY-5), R12STUTTER (C20's statistics, simulated in Python), R12REL (the rewritten Modrinth publish path, compared field by field with Minotaur's payload), R12FEAT (features and rules), R12X (a cross-cutting regression hunt: compatibility, performance, concurrency) and FLAKES (a game-test race hunt). Area files: `docs/reviews/review-12/`. As in review 11, every fix began with a test that failed on the unfixed code; C20's statistical fixes were accepted against the reviewer's own simulations (false "less stutter" at the no-change level, about 5%, in every scenario; before the fixes 10-66%).

## Result

Review 11's findings: all HIGH and MEDIUM confirmed FIXED, apart from the partial fixes listed below, which were completed in this round. New in this round: 0 HIGH and about 12 MEDIUM (after duplicates), all fixed and merged by 3511cad7; the lows were fixed where cheap or recorded as residuals (the user's usage budget ended optional work at 18:30). The final focused re-check (review-13.md) confirmed every review-12 HIGH/MEDIUM FIXED at 3511cad7 and found one new MEDIUM (R13-1), fixed before the release candidate.

## Status per finding

| id | sev | owner | status |
|---|---|---|---|
| R12APPLY-1,2,3 | M | ws-h | FIXED (merged by 3511cad7) |
| R12APPLY-4 | L | ws-l2 | FIXED (merged by 3511cad7) |
| R12APPLY-5 | L | ws-h | FIXED (merged by 3511cad7) |
| R12APPLY-6 = R12STUTTER-3 = R12X-3 | M | ws-s2 | FIXED d82b7f01 -> ec370f3e |
| R12REL-1..7 | L | r-verify | FIXED 65e0905d (release.yml dry-runs on any non-tag trigger) |
| CI-1 (r11) | M | r-ci 73f3114c (in 8cafe878) | FIXED (merged) |
| R12FEAT-1 | M | ws-b | FIXED (merged by 3511cad7) |
| R12FEAT-2..8 | L | ws-t | FIXED (merged by 3511cad7) |
| R12FEAT-9 | L | ws-p2 | FIXED (merged by 3511cad7) |
| R12STUTTER-1, -2 | M | ws-s2 | FIXED (merged by 3511cad7) |
| R12STUTTER-6 | M (raised from L) | ws-s2 | FIXED (merged by 3511cad7) |
| R12STUTTER-3,4,5,7 | L | ws-s2 | FIXED (97f6ed9a, aa02e35e, de4d34d3, 7dbcfaaa) |
| R12STUTTER-8 = R12FEAT-1 | M | ws-b | FIXED (merged by 3511cad7) |
| R12X-1 = R12APPLY-2 | M | ws-h | FIXED (merged by 3511cad7) |
| R12X-2 = R12FEAT-1 | M | ws-b | FIXED (merged by 3511cad7) |
| R12X-4 | L | ws-t | residual (not started before the budget stop) |
| R12X-5 | L | ws-w | FIXED (merged by 3511cad7) |
| R12X-6 = R12STUTTER-2 | M | ws-s2 | FIXED (merged by 3511cad7) |
| FL-1..FL-6 (flake hunt) | 2 blockers, 3 warnings, 1 nit | ws-s2 (FL-1), r-ci (FL-2..6) | FIXED (merged by 3511cad7) |
| RC-BLOCKER-1 | gate | r-ci: delta gate (returning - fresh <= 80 ms, FAIL mode) | 0.4.0 baseline: returning 193 vs fresh 144 (delta 49); 0.5: 204 vs 143 (61). Moving the reconcile off-thread -> v0.6 |
| PERF-8 note | L | docs | HardwareProbe's comment: the first detect is 92.7 ms (JNA), then 0.43 ms |


## Residuals (lows not fixed, by decision)
R12FEAT-2..8 were fixed after all (WS-T finished before the stop). R12X-4 (Try It's pending answer before the launcher policy is known), R12STUTTER-4/5/7 where not fixed, R12REL-3/5/6, R12APPLY-L2 (the PENDING wording), STUTTER-9/10/11 (review 11) and R13-2 (GpuName drops an APU chip id inside parentheses) are recorded in the owners' design docs. Moving the returning player's preLaunch history reconcile off the render thread (a cost inherited from 0.4.0, measured at +49 ms in 0.4.0 and +61 ms in 0.5 on the same seed) is a v0.6 item; the delta gate keeps v0.5 from adding more than 80 ms.
