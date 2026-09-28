# RigTune v0.5.0: review round 1 (review-11)

Range: `987179e4..ce8b1a8b` (v0.4.0 release -> the v0.5.0 integration head with every workstream merged).

Method: eight parallel read-only reviewers, one per area and lens (APPLY: apply pipeline and "never fights the launcher"; STUTTER: Stutter Doctor and C20; BENCH: benchmark and C09; FEAT: profiles, C16, C02, awareness, C18; SEC: untrusted input; COMPAT: 0.1-0.4 compatibility and 26.2 vs 26.3; PERF: footprint; CI: workflows, E2E, release). Each area's full findings are in `docs/reviews/review-11/<AREA>.md`. Verification was by test: every fixing agent first wrote a test that failed on the unfixed code for the stated reason (a finding that could not be made to fail would have been reported REFUTED; none was). Fixes were merged only with CI green on every job, and each fixing agent recorded its work in its design doc's "Review-11 fixes" section.

## Summary

| | High | Medium | Low |
|---|---|---|---|
| Found (unique, duplicates merged) | 2 | 20 | about 35 |
| Fixed | 2 | 18 at the time of writing; PERF-3 and CI-1 (r-ci, the footprint checkpoint) in progress | most; the rest NOT FIXED with a reason or recorded as residuals |

Both highs were crash loops from out-of-range dates in hand-edited config files (stutter-fixes.json: STUTTER-1 = SEC-1; server-profiles.json: SEC-2).

## Status per finding

| id | sev | owner | status |
|---|---|---|---|
| STUTTER-1 = SEC-1 | H | ws-s2 | FIXED 811d1da1 -> 5c32ecac |
| SEC-2 | H | ws-p2 | FIXED 1bd0508e -> ce7dff57 |
| STUTTER-2..5 | M | ws-s2 | FIXED -> 5c32ecac |
| STUTTER-6..11 | L | ws-s2 | 6,7,8 FIXED; 9,10 NOT FIXED (reasons in ws-s2.md); 11 residual |
| PERF-2 | M | ws-s2 + ws-h | FIXED (JournalCache 29af7134; callers 814f5a64; StutterFixService switch pending ws-s2c) |
| PERF-4 | L | ws-s2 | FIXED 9b7a909c |
| APPLY-1 | M | ws-h | FIXED aa57ec4e + self-review 142d0905 -> 3f15188c (conflict with APPLY-5 hand-resolved: hold = done-marked renames only; discard/stale = broader startedByRecords) |
| APPLY-2 = COMPAT-1 | M | ws-h | FIXED a0a69956 -> 3f15188c |
| PERF-1 | M | ws-h | FIXED 45772061 (one read per start; still on the render thread in preLaunch: review-12 checks) |
| SEC-4, COMPAT-4, COMPAT-7 | L | ws-h | FIXED |
| BENCH-8 | L | ws-h | FIXED 3c31e1b8 |
| APPLY-3, APPLY-5 | M | ws-l2 | FIXED badc0e00, 9b05907d (+ downgrade gate 199d3d8e) |
| APPLY-6/7/8 | L | ws-l2 | FIXED |
| APPLY-4 | M | ws-t | FIXED 24946fa8 -> e4acccfd |
| BENCH-1, BENCH-2 | M | ws-t | FIXED (2b88a4cd -> 4bab7742) |
| BENCH-3,4,5,7 | L | ws-t | FIXED (4bab7742) |
| BENCH-6 | L | ws-t | FIXED 231a7c21 |
| FEAT-1 | M | ws-l1 | FIXED e04cde55 -> 4937a81c |
| FEAT-2,3,4 | L | ws-p2 | FIXED 954b462b -> ce7dff57 |
| FEAT-5 | L | ws-w | FIXED f4c35105 |
| COMPAT-3, COMPAT-6 | L | ws-w | FIXED 06d34cf0 (COMPAT-6 premise partly wrong: 26.3 measured; real bug = server re-timing) |
| FEAT-6, PERF-6 | L | r-ci | open |
| PERF-3 | M | r-ci | open |
| PERF-5 | L | ws-t | FIXED 231a7c21 |
| PERF-7, PERF-8 | L | r-ci (UNVERIFIED or measure) | open |
| CI-1 | M | r-ci | open |
| CI-2 | M | r-verify | FIXED 20dabb91 |
| CI-3..6, SEC-6, SEC-7, COMPAT-5 | L | r-verify | FIXED (CI-6 partly: merge-time check only) |
| SEC-3 | M | ws-r | FIXED 859640d8 -> e2e17190 |
| SEC-5 | L | ws-r | FIXED 859640d8 |
| COMPAT-2 | M | ws-b + ws-s2 | FIXED 4d69138e + 63e8adde -> ac109a2d |
Totals: 3 H (STUTTER-1=SEC-1, SEC-2; one dup) -> 2 unique H; M: STUTTER-2..5 (4), PERF-1,2,3 (3), APPLY-1..5 (5, APPLY-2=COMPAT-1), BENCH-1,2 (2), FEAT-1, SEC-3, CI-1, CI-2, COMPAT-2 = 20 unique M.


## Notes

- APPLY-1 (WS-H) and APPLY-5 (WS-L2) both changed `ApplyExecutor`'s notion of a "started" group. The coordinator resolved the merge so the hold (APPLY-5, launcher safety) counts only renames RigTune recorded as done, while Discard and the start-up's stale check (APPLY-1, data safety) keep the broader rule (any recorded rename in effect that isn't recorded as put back). Review round 2 re-checks this.
- COMPAT-6's premise (26.3 not measured) was wrong; the fix found the real bug (the in-process server re-timing the preload).
- CI found two infrastructure classes during the round, both fixed: a checksum-failed JDK download (a one-retry rule, fix/v05-ci-jdk-retry) and the 26.2 OpenGL leg outgrowing its 14-minute timeout once C09 and C20 landed (the dormant two-JVM split switched on, fix/v05-ci-split).
