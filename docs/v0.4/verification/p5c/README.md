# P5-C: release-candidate finals (SPEC item 3 AC3.1-AC3.3, AC2n.2, AC5.8 C re-run), 2026-09-27

**Release candidate.** `origin/feat/v0.4.0` @ b27f33fa ("Merge fix/review-8b"), merged into `test/p5-final` (23be54d1,
which adds only test-side commits: the undo driver's profile switch and harness text). `./gradlew build` (both MC
versions, unit tests included) passed; the jars (`rc-jars.sha256`):
- `rigtune-0.4.0-dev+mc26.2.jar` sha256 `4018fe03ee2164724f144f77abc829b3b73ac25e941e38f5c3c6aeb8778a9a12` (every run below)
- `rigtune-0.4.0-dev+mc26.3.jar` sha256 `568aa0b4ba7e6e4c7b72a75425c3e0a786415b9e4b6d71bdeed5613319314b85`

Later, docs-only merges (SPEC amendments 427f4a7 and f8ddfe4d) don't change the product code.

**Released old sides** (`gh release download`, sha256 equal to the pins in `self_update_e2e.RELEASED` and build.yml):
`rigtune-0.1.0.jar` `8294d04a…b950`, `rigtune-0.2.0+mc26.2.jar` `67275e23…7de9`, `rigtune-0.3.0+mc26.2.jar` `5717f65c…d7e9`.

**Lock.** One client run per lock hold, released between runs (`tools/e2e-one.sh`, `tools/stut-run.sh`: `mkdir
C:/Dev/Worktrees/.gametest-lock`, `owner.txt` with agent p5-c / this worktree / started / run, the harness with
`--lock none`, released by a trap in the same command, pass or fail; `tools/queue.sh` retries a held lock every 120 s).
No run found the lock held. MC 26.2 only (vanilla 26.3's native startup crash on most local launches).

## 1. "Written by 0.4" fixtures regenerated from the RC; released-jar harness (AC3.3)

Regenerated with the RC's own tests on `:26.2:test` (PLAN "v040-written" convention): `ws-a` deleted and rewritten by
V040WrittenWsaTest; `ws-s` and `ws-f` rewritten with `RIGTUNE_REGENERATE_FIXTURES=1` / `RIGTUNE_WRITE_FIXTURES=1`
(StutterWrittenFixtureTest, StartupTimesFixtureTest); `ws-w`, `ws-b` and `ws-p` written by V040WrittenWsWTest,
BenchmarkCompatibilityTest and V030CompatTest and compared byte for byte with the committed files. **Result: every set is
byte-identical to what the RC writes; no fixture change** (fix-8b had already regenerated ws-s). 16 tests, 0 failures.

| set | file | sha256 (first 16) |
|---|---|---|
| ws-a | history.json, pending.json | `b8ccd66bfceb757e`, `99816048ce766fcb` |
| ws-p | history.json, profiles.json | `f7ef74676f9de3eb`, `d105ac71257c1252` |
| ws-b | benchmarks.json | `a08b4350efe13039` |
| ws-s | settings.json, stutter.json | `3b60c5a1fcd97d38`, `cfaa8b54b792c245` |
| ws-w | awareness.json, server-limits.json | `fb59082e824c2dfb`, `8639363e0ce56635` |
| ws-f | startup-times.json | `8241a21c31dadbec` |

`python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar` on these sets: **PASS 9/9** (`compat030-rc.txt`: 0.3.0's
Journal state OK with 3 of 3 entries, HistoryModel no unknown kind, Undo this on the profile-switch entry reverts its 3
changes, Undo last/all plan, benchmarks.json 5 of 5 runs without a `.bad`, PendingActions keeps type/id/mod id,
ClientSettings as written, RulesLoader revision 16 counts unchanged with the new sections present, no file changed). CI
runs it on every push.

## 2. E2E finals (AC3.1, AC3.2, AC2n.2)

Commands: tools/e2e/README.md "v0.4 runs", one run per lock hold (`tools/jobs1.txt`). Every run passed on its first
attempt.

| run | what | result |
|---|---|---|
| [final-v030-to-040](../../../smoke/self-update/final-v030-to-040/RESULT.md) | released 0.3.0 → RC, `--expect-history auto` (own-update) | **PASS 20/20** |
| [final-v020-to-040](../../../smoke/self-update/final-v020-to-040/RESULT.md) | released 0.2.0 → RC, own-update | **PASS 20/20** |
| [final-v010-to-040](../../../smoke/self-update/final-v010-to-040/RESULT.md) | released 0.1.0 → RC, `--legacy-disable`, legacy-import | **PASS 21/21** |
| [final-v010-seeded-to-040](../../../smoke/self-update/final-v010-seeded-to-040/RESULT.md) | released 0.1.0 in the user's real DH-failure state (`seeds/v010-dh`) → RC | **PASS 26/26** |
| [undo-after-restart-040](../../../smoke/self-update/undo-after-restart-040/RESULT.md) | M14 Undo last after a restart 22/22, B-M3 Undo this 21/21, and the profile part with **real profile switches** (`--profile-switch profile`, Battery then Max FPS through `controller.switchProfile`): switches 8/8, Undo last twice + check 15/15 (**AC2n.2**), Undo all + check 15/15; History shows "Profile: Battery" / "Profile: Max FPS" (`e2e-profile-apply-1-history.png`) | **PASS 81/81** |
| [undo-after-restart-040-settings](../../../smoke/self-update/undo-after-restart-040-settings/RESULT.md) | the same with `--profile-switch settings`, whose two switches stage the same Sodium key (`chunk_builder_threads` 0 → 2 → 4): the second Undo last now reverts it to 0 (SPEC 2n on a staged key; 4/6 in the dev run before the fix) | **PASS 79/79** |
| [downgrade-040-to-030](../../../smoke/self-update/downgrade-040-to-030/RESULT.md) | the real v040-written sets, released 0.3.0 (History, Undo last incl. a staged Sodium key, its own Apply, 0.4's staged op applied, 0.4-only files byte-identical) 9/9, then the RC again (loads, History 5 of 5, profiles label kept, own files kept) 7/7 | **PASS 16/16** |

Why the extra settings run: on the E2E instance (fabric-api + Sodium, this PC's rules) the Battery and Max FPS templates
change only `vanilla.*` keys (render/simulation distance, FPS cap, VSync, particles, clouds), so the real-profile run
covers AC2n.2's phase but not a staged key; the settings mode is the staged-key case that first exposed 2n.

Harness changes on this branch (test side only): `UndoDriver.switchProfile` calls `controller.switchProfile(id)` for the
profile whose shown name matches (as ProfilesScreen's Switch does; CI's `compileE2eUndoJava` guards it); RESULT.md no
longer calls profile-undo a known failure (2n merged in 7794d2cd); README updated. A dry run of the profile mode on the
pre-merge head (jar `d0e89050…`) passed 81/81 before the RC existed (evidence not kept).

## 3. AC5.8 C re-run (teleport into ungenerated terrain, 26.2)

[stutter/C-rerun/](../stutter/C-rerun/README.md): C1r (default options) 12 spikes, 12 after-teleport, 9 chunks-loading;
C1r2 (repeat) 8 / 8 / 7; C3r (uncapped) 14 / 14 / 11 with chunk building 7 % on measured backlog. The untagged
post-teleport spikes are only those before the first chunk load (the first ~0.3 s): finding P5C-F1, decided by the
coordinator as a SPEC refinement (427f4a7), no product change. **PASS** against AC5.8 C as amended: every post-teleport
hitch "after teleport", "chunks loading" from the first chunk load on, nothing claimed as GC without an overlapping JVM
pause, chunk building only with backlog evidence, save window recorded, remainder shown.

## Findings

[P5C-FINDINGS.md](../P5C-FINDINGS.md): P5C-F1 (low, decided: SPEC wording). No release blocker found.

## UNVERIFIED

- 26.3 for the E2E and AC5.8 C runs (26.2 only, as SPEC item 3 and P5-A's AC5.8 runs).
- C3r: one untagged spike outside stutter.json's worst-10 list can't be placed in time (C1r, C1r2 complete).
- The real-profile switch never touches a staged (Sodium/DH/Iris) key on this instance; the staged-key 2n case is
  covered by the settings-mode run and by UndoPlannerTest (AC2n.1).
