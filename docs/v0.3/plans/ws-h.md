# WS-H: self-update E2E for 0.3 Implementation Plan

> **For agentic workers:** executed inline by WS-H (superpowers:executing-plans style), TDD per task, commit after each. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The self-update harness (tools/e2e) runs 0.2.0 → 0.3.0, 0.1.0 → 0.3.0, the H-M2 seeded 0.1.0 → 0.3.0 variant and the undo-after-restart scenario with B-M3's per-entry case, with Python tests for every harness change and dry-run evidence on a 0.3.0-dev build.

**Architecture:** The harness stays one script (`self_update_e2e.py`) with pure assertions in `e2e_checks.py` and file templating in `fixtures.py`. The self-update driver (src/e2e) keeps compiling against the OLD released jar (0.1.0 or 0.2.0; `compileE2eJava -Pe2e.oldJar=<0.2.0 jar>` already compiles, so no build.gradle change). The seeded variant is data: templated copies of the user's real 0.1.0 files plus a `seed.json` listing the fake Distant Horizons jars. The per-entry undo case extends the e2eUndo driver; it reaches WS-B's controller method by reflection until WS-B merges.

**Tech Stack:** Python 3.11 stdlib (unittest), Java 25 driver mods (Fabric client entrypoints), Gradle `:26.2:e2eClient` (Loom ClientProductionRunTask), PowerShell 7 for process checks.

**Spec:** docs/v0.3/SPEC.md item 4 and amendments H-M1, H-M2, H-M3, B-M3; docs/v0.3/plan-review.md (WS-H, B-M3); docs/v0.3/PLAN.md (WS-H, Global Constraints).

## Global Constraints
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; committed Stonecutter version 26.2.
- One Minecraft client machine-wide: `mkdir C:/Dev/Worktrees/.gametest-lock` + `owner.txt` (`agent:`, `worktree:`, `started:`); released in the same command, pass or fail, with `rm -f owner.txt; rmdir` (never `rm -rf`). If busy: do other work, retry at most every 2 minutes, give up after 30 and report.
- Kill only this run's processes (command line contains the run folder). Never touch other java processes.
- Never write, rename or launch anything under `%APPDATA%\ModrinthApp`; the user's files are read only.
- Temp files: `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/4bf644a6-501a-4093-a81e-9e29a6010059/scratchpad/ws-h/`.
- Python tests run on Linux in CI (`python -m unittest discover -s tools/e2e/tests`): no Windows-only assumptions in tests; LF text files.
- build.gradle belongs to WS-0: change it only if unavoidable, minimal and marked, and tell the coordinator.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`. No force push.

## Findings before planning
- Released jars (GitHub release assets, sha256 matches the release digest): `rigtune-0.1.0.jar` `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`; `rigtune-0.2.0+mc26.2.jar` `67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9`.
- H-M1: `./gradlew :26.2:compileE2eJava -Pe2e.oldJar=<rigtune-0.2.0+mc26.2.jar>` succeeds; the source set's classpath is isolated from this repo's sources (a missing jar fails with "package ... does not exist"). No new source set or build.gradle change is needed.
- The user's instance (read 2026-09-26): `pending.json` holds one group (DISABLE `mods/fabric-26.2.jar` = DH 3.3.0, ENABLE `DistantHorizons-3.3.2-26.2-fabric-neoforge.jar` from its `.rigtune-pending`, attempts 1); `last-apply.json` has 17 results, the DH pair FAILED ("being used by another process"); DH's own build waits in `mods/update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar`.
- For the DH group to reach 0.3.0 it must survive 0.1.0's exit, where the 0.1.0 helper retries it. In the real failure a second process held `fabric-26.2.jar` (DH's own updater). The harness reproduces that by keeping the fake jar open (no FILE_SHARE_DELETE on Windows) from the 0.1.0 launch until its helper is done, so the retry fails the same way (attempts 2) and the group is carried over.

---

### Task 1: Lock protocol and multi-launch helper wait

**Files:**
- Modify: `tools/e2e/self_update_e2e.py` (take_lock, release_lock, wait_for_helper, launch_and_apply, parse_args)
- Test: `tools/e2e/tests/test_self_update_e2e.py`

**Interfaces:**
- Produces: `owner_text(agent, repo, run_dir, started) -> str`; `owns_lock(text, run_dir) -> bool`; `helper_log_tail(path, offset) -> str`; CLI `--agent` (default `ws-h`).

- [ ] **Step 1: failing tests**: `LockTest.test_owner_txt_follows_the_plan_protocol` (lines `agent: ws-h`, `worktree: <repo, / separators>`, `started: <iso>`, `run: <run dir>`), `test_busy_lock_raises_with_the_owner`, `test_release_removes_only_its_own_lock` (another run's owner.txt → lock stays), `test_release_uses_unlink_and_rmdir` (an extra file in the lock folder → the folder stays, no rmtree); `HelperWaitTest.test_only_text_after_the_offset_counts`.
- [ ] **Step 2:** run `python -m unittest discover -s tools/e2e/tests` → the new tests fail.
- [ ] **Step 3:** implement: owner.txt from `owner_text`; release = `owner.unlink(); lock.rmdir()` only when `owns_lock`; an OSError on rmdir is logged, never rmtree. `launch_and_apply` records the size of helper.log before the launch and `wait_for_helper(since)` looks only at the new text (six launches share one instance in the undo scenario).
- [ ] **Step 4:** tests pass. **Step 5:** commit `test(e2e): lock owner.txt per the v0.3 protocol, unlink+rmdir release; per-launch helper.log`.

### Task 2: 0.2.0 → new (the released 0.2.0 jar as the old side)

**Files:**
- Modify: `tools/e2e/e2e_checks.py` (new `own_update_history`), `tools/e2e/self_update_e2e.py` (`--expect-history [legacy-import|own-update]`, history snapshot before verify)
- Test: `tools/e2e/tests/test_e2e_checks_v03.py`, `tools/e2e/tests/test_self_update_e2e.py`

**Interfaces:**
- Produces: `own_update_history(instance, old_name, new_name, statuses_before) -> Check`: history.json has exactly one entry, kind `apply`, whose changes are {disable old jar, enable new jar}, all `APPLIED`, no `legacy-import` entry, and the relaunch left every status as it was.

- [ ] **Step 1: failing tests**: `OwnUpdateHistoryTest` (passes on the expected file; fails with a legacy-import entry, a STAGED change, a missing file, a status changed by the relaunch); `ArgsTest.test_expect_history_values` (`--expect-history` alone = `legacy-import`, `--expect-history own-update`, default None).
- [ ] **Step 2-4:** implement, tests pass.
- [ ] **Step 5:** build the new jar (`./gradlew :26.2:jar -Pmod_version=0.3.0-dev` until WS-0's `mod_version=0.3.0-dev` merges) and dry-run under the lock:
  `python tools/e2e/self_update_e2e.py --name dev-v020-to-030 --old-jar <ws-h>/jars/rigtune-0.2.0+mc26.2.jar --old-sha256 67275e23… --new-jar versions/26.2/build/libs/rigtune-0.3.0-dev+mc26.2.jar --expect-history own-update --work <ws-h>/runs --evidence docs/smoke/self-update/dev-v020-to-030`
- [ ] **Step 6:** commit checks + evidence.

### Task 3: 0.1.0 → new dry run (existing flow)
- [ ] Run `--name dev-v010-to-030 --old-jar rigtune-0.1.0.jar --old-sha256 8294d04a… --legacy-disable --expect-history` with the Task 1 harness; evidence `docs/smoke/self-update/dev-v010-to-030`; commit.

### Task 4: H-M2 seeded 0.1.0 → new

**Files:**
- Modify: `tools/e2e/fixtures.py` (`template_seed_json`, `instantiate_json`), `tools/e2e/e2e_env.py` (`test_mod_jar(..., name=None)`), `tools/e2e/e2e_checks.py` (`listing(..., recursive)`, `after_update(..., carried=())`, `after_seeded_verify`), `tools/e2e/self_update_e2e.py` (`--seed <dir>`), `src/e2e/.../SelfUpdateDriver.java` (statuses seen, mods listing at quit)
- Create: `tools/e2e/make_seed.py`, `tools/e2e/seeds/v010-dh/{pending.json,last-apply.json,source.json,seed.json}`
- Test: `tools/e2e/tests/test_fixtures.py`, `tools/e2e/tests/test_e2e_checks_v03.py`, `tools/e2e/tests/test_seed.py`

**Interfaces:**
- `fixtures.template_seed_json(text, root) -> str`: every string value: each spelling of `root` → `${INSTANCE}`, `/` after the token (so messages with embedded paths are templated too).
- `fixtures.instantiate_json(text, instance) -> str`: `${INSTANCE}<rest>` → `<instance><rest with os.sep>`.
- `make_seed.make_seed(config_dir, root, dest, names=("pending.json","last-apply.json")) -> dict`: reads only, writes templated copies + `source.json` (sha256 of each original, location with `%APPDATA%`).
- `seed.json`: `{"jars": [{"path", "id", "name", "version"}], "holdOpenAtOldExit": [path], "modId": "distanthorizons"}`.
- `e2e_checks.after_update(..., carried=[(type, file name)])`: pending.json holds exactly the carried ops (attempts increased), leftovers only their downloads, last-apply.json = the update's two ops OK + the carried ops FAILED.
- `e2e_checks.after_seeded_verify(instance, seed_ops, mod_id, driver, log_text, helper_cmdlines, mods_before_launch, seed_jars) -> [Check]`: group dropped (no seeded op id left in pending.json); notice (a status key containing `queued` naming the mod); journal (every seeded op id's change `DISCARDED`); WARN line per FAILED op of last-apply.json (`/WARN]`, file name or mod id, `attempt \d+ of 3`); nothing in mods/ changes at exit (no helper ran; the recursive listing after exit equals the driver's listing at quit; DH 3.3.0 and the queued jar in place; no enabled DH 3.3.2); during the session mods/ changed only by retiring the dropped download (`.rigtune-pending` → `.rigtune-superseded`).

- [ ] **Step 1: failing tests** for each function above (template of a message with an embedded path; instantiate round trip; make_seed never writes to the source folder and records sha256; each seeded-verify check passing on a good instance and failing on the specific breakage).
- [ ] **Step 2-4:** implement; driver: `statuses` (key + text, each change, every phase) and `modsAtQuit` (relative path → sha256, recursive) in driver-<phase>.json; compile against 0.1.0 and 0.2.0.
- [ ] **Step 5:** generate the seed from the real files (read-only) with make_seed.py; hand-write seed.json; commit.
- [ ] **Step 6:** dry run `--name dev-v010-seeded-to-030 --old-jar rigtune-0.1.0.jar --seed tools/e2e/seeds/v010-dh --expect-history` (legacy import check replaced by the seeded journal check); expected on this branch: drop + notice + DISCARDED may pass with 0.2's broad drop if the import precedes it; the WARN line fails until WS-B (3e) merges; record which fail and why. Commit evidence.

### Task 5: B-M3 per-entry undo in the e2eUndo driver

**Files:**
- Modify: `src/e2eUndo/.../UndoDriver.java` (phases `entry-apply`, `entry-undo`, `entry-check`), `tools/e2e/self_update_e2e.py` (undo scenario continues with the entry phases on the same instance; entry id passed as `-Drigtune.e2e.entryId`), `tools/e2e/e2e_checks.py` (`after_entry_apply`, `after_entry_undo`, `after_entry_check`), `tools/e2e/e2e_env.py` (catalog serves `e2e-first`, `e2e-second`)
- Test: `tools/e2e/tests/test_e2e_checks_v03.py`

**Interfaces:**
- Driver: `entry-apply`: Apply 1 adds `e2e-first`, waits until staged, Apply 2 adds `e2e-second`, waits, quits. `entry-undo`: the controller's per-entry plan method (reflection: a public method `(String) -> UndoPlan`, WS-B's `undoPlanFor`-style API) for `-Drigtune.e2e.entryId`, recorded as `entryPlan`; through `UndoScreen(Screen, RigTuneController, String)` when it exists (confirm button pressed), else `controller.undo(plan)` (`viaScreen: false`); waits for the DISABLE of e2e-first; quits. `entry-check`: loaded mods, `entryUndoableAfter`.
- Checks: entry-apply: two new `apply` entries (first then second), each one enable APPLIED, both jars served bytes, clean. entry-undo: plan = one REVERT needing a restart for the older entry's change only; e2e-first `.disabled`, e2e-second enabled; last-apply = that DISABLE OK; one new `undo` entry `undoOf` the older entry, its change APPLIED reverting it; older change REVERTED; newer change APPLIED. entry-check: e2e-second and e2e-disable-me loaded, e2e-first not; nothing left to undo for the older entry; no crash; mods and statuses unchanged; clean.

- [ ] **Step 1-4:** failing tests for the three check functions, implement, pass; compile `./gradlew :26.2:compileE2eUndoJava`.
- [ ] **Step 5:** dry run `--scenario undo --name dev-undo-after-restart-030 --new-jar <0.3.0-dev>`; expected now: M14 phases PASS, entry-undo fails "no per-entry undo API" until WS-B merges. Commit evidence.

### Task 6: docs and review
- [ ] tools/e2e/README.md (0.2.0 old side, `--expect-history own-update`, `--seed`, the per-entry phases, the lock owner lines); docs/smoke/self-update/README.md rows for the dry runs; docs/v0.3/design/ws-h.md (decisions: no build.gradle change, the held-open jar, reflection until WS-B, the Phase 5 commands).
- [ ] Dispatch a code-reviewer subagent on `git diff origin/feat/v0.3.0...HEAD`; fix high/medium findings.
- [ ] After WS-A/WS-B merge (coordinator's word): merge `origin/feat/v0.3.0`, switch the per-entry call from reflection to the real API, re-run the seeded and undo dry runs.

## Phase 5 commands (release candidate)
Same commands with `--name final-v020-to-030`, `final-v010-to-030`, `final-v010-seeded-to-030`, `undo-after-restart-030` and `--evidence docs/smoke/self-update/<name>`.
