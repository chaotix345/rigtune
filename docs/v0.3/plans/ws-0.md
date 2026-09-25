# WS-0 (Phase 3 foundation): CI game tests + build changes

> **For agentic workers:** config work; each task ends with a check and a commit. Steps use checkbox (`- [ ]`) syntax.

**Goal:** SPEC item 2 (client game tests in CI) and item 1 changes A (optional Sodium) and B (looped Modrinth publish, `versionType` from the MC id); `mod_version=0.3.0-dev`.

**Architecture:** the prototype job from `research/ci-gametest` (docs/research/v0.3/ci-gametests.md §2) moves into build.yml as `client-gametest`, fed by a `gametest-matrix` setup job that runs `tools/gametest_matrix.py` over `versions/*/`. Loom's `ClientProductionRunTask` (`runProductionClientGameTest`) runs the jar that ships; Loom wraps it in xvfb-run itself when `CI` is set.

**Branch / worktree:** `feat/v03-foundation`, `C:/Dev/Worktrees/rigtune-found`.

## Task 1: matrix script (AC2.5)
Files: new `tools/gametest_matrix.py`, new `tools/tests/test_gametest_matrix.py`.
- [ ] Write the tests first: nodes 26.2 + 26.3 give the three legs (26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan); a temporary extra node (`26.4-snapshot-1`, `27.1`) gets OpenGL + Vulkan without any other change; `26.10` sorts after `26.3`; hidden dirs and files are ignored; no nodes is an error; the CLI prints one line of JSON (`{"include": [...]}`) usable as `matrix=<json>` in `$GITHUB_OUTPUT`.
- [ ] Run `python -m unittest discover -s tools/tests -v`: new tests fail.
- [ ] Implement: every non-hidden directory under `versions/` (the same set as release.yml's `versions/*/`), sorted by numeric version core; OpenGL for every node, Vulkan for core >= 26.3.
- [ ] Tests pass; dry run against the repo prints the three legs; commit.

## Task 2: build changes
Files: `build.gradle`, `stonecutter.gradle`, `gradle.properties`.
- [ ] `runProductionClientGameTest` (ClientProductionRunTask) with its own `productionGameTestMods` configuration and run dir `build/run/productionClientGameTest`, wiped in `doFirst` (research §2, verbatim).
- [ ] Change A: Sodium on `localRuntime` (and in `productionGameTestMods`) only when `sodium_version` is set.
- [ ] Change B (Gradle half): `versionType` = `alpha` when the MC id contains `-snapshot-`, `-pre-` or `-rc-`, else `release`.
- [ ] `stonecutter.tasks { order 'runProductionClientGameTest' }`; `mod_version=0.3.0-dev`.
- [ ] Checks: `./gradlew build` (both nodes); `./gradlew :26.3:test` with `sodium_version` temporarily removed (change A: unit tests still run; restore after); `./gradlew :26.2:modrinth :26.3:modrinth -PmodrinthDryRun` with no token in the env (payload shows `versionType: release`, version `0.3.0-dev+mc…`); the alpha path checked once with a temporary local edit, reverted. No Minecraft launch locally. Commit.

## Task 3: build.yml `client-gametest`
Files: `.github/workflows/build.yml` (LF).
- [ ] `gametest-matrix` job (ubuntu-24.04, checkout, `python3 tools/gametest_matrix.py` into `$GITHUB_OUTPUT`).
- [ ] `client-gametest` job: `needs: gametest-matrix`, `matrix: fromJSON(...)`, `fail-fast: false`, ubuntu-24.04, job timeout 30 min, setup-java 25 + setup-gradle, lavapipe install on Vulkan legs, `SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER=skip` on OpenGL legs, game-test step with a 20-minute timeout and no retry, the backend check on `latest.log`, screenshots and logs/crash reports/hs_err uploaded with `if: always()` per leg.
- [ ] actionlint (downloaded release binary, scratch dir) clean; commit.

## Task 4: release.yml loop (change B, workflow half)
Files: `.github/workflows/release.yml` (LF).
- [ ] Replace the two per-version publish steps with one step looping `versions/*/`: each failure is an `::error::` and the loop continues; exit 1 if any failed. Same `if:` as before, so "Verify Modrinth files match" runs unchanged after it.
- [ ] actionlint clean; review the step order and conditions; commit.

## Task 5: push, CI, AC2.2
- [ ] Push; `gh run watch <id> --exit-status`; iterate until java, python, rules-consistency, rules-v1-compat and every client-gametest leg are green.
- [ ] AC2.2: one temporary commit throwing an AssertionError at the start of UiGameTest; push; the job goes red on every leg and the screenshots/logs artifacts still upload (`gh api .../artifacts`); `git revert` it (normal commit), push, green again.
- [ ] Download the final green run's screenshots to the scratch dir and look at several per leg (not black; the RigTune screens render).

## Task 6: review and notes
- [ ] Code-reviewer subagent on `feat/v0.3.0...HEAD`; fix high/medium findings forwarded by the coordinator.
- [ ] `docs/v0.3/design/ws-0.md`: what changed, deviations, risks, evidence (run URLs, durations, AC2.2/AC2.5).
