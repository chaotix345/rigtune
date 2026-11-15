# v0.5 leftover branches: audit

2026-11-15, against `origin/main` at 8849843c. STATUS.md "Housekeeping" kept these five branches because they hold commits that aren't on main by hash. This audit compares content instead. For each branch it ran `git cherry`, a reverse `git apply --check` of every non-merge commit against main, `git merge-tree` on each merge commit, and searches of main's history, code and docs for the change.

The remote and local copies of the four pushed branches point at the same commit, so one verdict covers both.

## Verdict

All five can be deleted. Nothing on them is missing from main except experiment-only code that the v0.5 docs describe as "never to be merged" and whose results main already records.

| Branch | Ahead of main | What's on it | Already on main? Evidence | Verdict |
|---|---|---|---|---|
| `fix/v05-awareness` (remote + local) | 1 (a merge) | 07ff2516, a merge of `origin/feat/v0.5.0` into the branch. | Yes. Both parents (01b7a2bf, 46c4750d) are ancestors of main. The merge's tree equals `git merge-tree --write-tree` of the two parents (929f81f4), so the merge adds no resolution of its own. `git cherry` lists nothing. | Delete. |
| `fix/v05-r11-ws-b` (remote + local) | 2 (a merge, 91d863d8) | 91d863d8: review-11 BENCH-6 (say a failed restore before Try It's hook takes the cancelled outcome) and BENCH-8 (one history.json read for the journal cursor and staged ids), with a test and two `ws-b.md` notes. 0ed11720 is a merge with no resolution of its own (both parents on main, tree equals the auto-merge). | Yes, from other branches. BENCH-6 landed as 231a7c21 and was later replaced by R12FEAT-3 (`BenchmarkController.java:693-701`). `ws-t.md:365` says "ws-b's 91d863d8 fixes the same lines; one of the two is enough". BENCH-8 landed as 3c31e1b8 (`BenchmarkConditions.java:71`, `journal.snapshot()`), tested by `BenchmarkConditionsTest.bh2TheJournalIsReadOnce`, and recorded in `review-11.md:30` and `ws-h.md:323`. Only the two `ws-b.md` paragraphs are missing, and those three files already cover the same fixes. | Delete. |
| `research/v05-ci` (remote + local) | 5 | r-ci experiments: `ci-experiment.yml`, `TimingProbeGameTest`, `TimingProbe2GameTest`, `HangWatchdog`, `.github/ci/prefetch.init.gradle`, the Loom 1.17.21 pin, and a frame-hook ratio calibration (`FrameHookBudgetTest`, `footprint-budgets.json`). | The results and the useful parts are on main. `ci-robustness.md:13` names the branch "never to be merged" and writes up its findings. The Loom pin is in `gradle.properties:13`. Prefetch is a `prefetchDependencies` task (`build.gradle:554`) used by `build.yml:78`. Offline runs go through `tools/ci/offline.sh`. Hangs are handled by step timeouts and a TERM at 14 min (`build.yml:270-284`) instead of `HangWatchdog`. The ratio gates are on main with ws-ci's own later calibration (`footprint-budgets.json:32-34`, runs 36295704245, 36296194458, 36296732786), which replaces this branch's numbers. Not on main: the probe game tests, `HangWatchdog` and the experiment workflow. | Delete. |
| `research/v05-verify` (remote + local) | 3 | r-verify experiments: `r-verify-experiment.yml`, a Linux path for the self-update E2E harness (`/proc` process listing, `chattr +i` in place of held-open files, helper command-line watching), a log filter for Linux-only ERROR lines, and extra old-jar compile checks in `build.yml`. | The parts meant to be kept are on main. `verification-gaps.md:8` says "never merged", and `PROGRESS.md:108` says to take 25243b63 and 1f4b6b3a but not the experiment workflow. `self_update_e2e.py` has the Linux path (`posix_java_processes`, `chattr`) and the 0.2.0-0.4.0 26.3 jar hashes (line 61). `e2e_checks.py:933-950` has the narrator, SoundSystem and Xvfb cursor filters. The old-jar checks now run in `e2e.yml` through `e2e_matrix.py`. Not on main: `r-verify-experiment.yml` (639a1f4b changes only that file). | Delete. |
| `local/v05-battery` (local only) | 3 (a merge, c88bb117, 4e3f9d53) | c88bb117: a first version of `BatteryFlowGameTest`, `fake_battery.sh`, the e2e.yml battery OSHI leg and an `e2e_matrix.py --nodes` option. 4e3f9d53: battery-offer cooldown tests at the clock-skew limits. c8fd03a5 is a merge with no resolution of its own. | Yes. 4e3f9d53 reverse-applies cleanly on main. c88bb117 was landed again, revised, as 5ecc4edf. Main's `BatteryFlowGameTest` is that version plus the r12 flake fix FL-6 (a 19-line diff). `fake_battery.sh` is identical. `e2e_matrix.py --nodes` is on main (lines 10 and 107-112), and the `battery-oshi` job in `e2e.yml:259` uses it. | Delete. |

## One thing to know before deleting the research branches

`ci-robustness.md` (lines 13-18) and `verification-gaps.md` (lines 8, 69, 75, 155, 535) cite commits that exist only on `research/v05-ci` and `research/v05-verify`. Once the branches are gone, those hashes stop resolving in a local clone, and on GitHub after it garbage-collects them. The write-ups and the Actions run links in those docs don't depend on them. To keep the probe code anyway, save a bundle before deleting:

```
git bundle create v05-research.bundle research/v05-ci research/v05-verify
```

## Checks run

```
git cherry origin/main <branch>
git diff-tree -p <commit> | git apply --check -R      # each non-merge commit, on a main checkout
git merge-tree --write-tree <parent1> <parent2>        # each merge, compared with the merge's own tree
git log origin/main --grep <id>                        # BENCH-6, BENCH-8, BatteryFlowGameTest, r-ci, r-verify
```
