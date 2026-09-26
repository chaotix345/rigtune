# RigTune v0.4.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:writing-plans (write your workstream's detailed task plan first, to `docs/v0.4/plans/<ws>.md`), superpowers:test-driven-development (every task), superpowers:verification-before-completion (before reporting), superpowers:requesting-code-review (self-review). Execute your plan with superpowers:subagent-driven-development or inline; steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship RigTune v0.4.0 for MC 26.2 and 26.3: an ongoing performance companion (Profiles with share codes, Stutter Doctor, JVM/GC advice, benchmark history and regression alerts, server-aware advice, change awareness, a budgeted footprint), the deferred v0.3 defects fixed, a snapshot canary in CI, and the self-update path from 0.1.x/0.2.x/0.3.x proven; without regressing 0.1.x, 0.2.x or 0.3.x users.

**Architecture:** One Stonecutter codebase builds one jar per MC version. Pure logic lives in `core/` (JUnit-tested, no Minecraft imports); `client/` is the thin MC layer. Workstreams have disjoint file ownership; hotspot files get small, delegating edits; shared contracts (new file schemas, rules sections, hub screen + notice API, lang prefixes, game-test stubs) are committed before the fan-out.

**Tech Stack:** Java 25, Fabric Loom 1.17 (no mappings, Mojang names), Stonecutter 0.9.8, Gradle 9.5.1, Gson, JUnit 5; Python 3.11 stdlib for tools; GitHub Actions (ubuntu-24.04 + Xvfb for game tests); Minotaur for Modrinth.

**Spec:** docs/v0.4/SPEC.md (acceptance criteria per item; its "Amendments from the plan review" override item text). Research: docs/research/v0.4/*.md.

## Global Constraints
- Integration branch `feat/v0.4.0`. Branch from `origin/feat/v0.4.0`; never commit to `main` or `feat/v0.4.0`; the coordinator merges (`--no-ff`) after checking your CI and evidence. After you've pushed, never rebase: merge `origin/feat/v0.4.0` into your branch (hooks block every force push, including `--force-with-lease`).
- Worktrees are created by the coordinator: `C:/Dev/Worktrees/rigtune-<name>`. Work only in your own worktree. Never `cd` into the main repo; use absolute paths (the Bash tool is Git Bash; `cd` changes the session cwd).
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for BOTH MC versions. NEVER run `./gradlew --stop` (it kills every agent's daemons).
- Stonecutter: the committed active version is 26.2 (CI fails otherwise). Version-specific code uses `//? if >=26.3 {` blocks, only where the API differs.
- Compatibility (SPEC "Compatibility promise"): rules-v1.json never less conservative for 0.1.x (RulesV1DifferentialTest, check_rules_v1.py, tierTablesStayAtV010); rules-v2.json safe for 0.2.0 and 0.3.0 (new condition keys fail closed; new sections need `requires` or are ignored by older parsers, proven by a test); no formatVersion/schemaVersion bump in any file; new state goes in NEW files; existing files get only optional additive fields that 0.1.0/0.2.0/0.3.0 ignore (and 0.3.0 dropping them on rewrite must be harmless).
- `core/` has no Minecraft imports. Helper-safe code (anything reachable from ApplyHelper/ApplyExecutor/Journal updates in the helper) uses core + Gson only, never `RigTune.LOGGER`, Fabric or MC (HelperLauncherTest must pass).
- UI text: every string the client shows comes from `assets/rigtune/lang/en_us.json` (`Component.translatable`), except user/mod/rule data and numbers (LangCheckTest). Add keys in alphabetical position under your prefix (SPEC "Shared contracts"); never append at the end of the file.
- Network off: every new feature works fully on-device; nothing new touches the network.
- Honest wording: estimates are called estimates; correlations are "may be related", never causes.
- Tests: JUnit for core; your own client game-test class for UI/behaviour (pre-registered by the contracts commit; if you need a new one, add one entrypoint line to `src/gametest/resources/fabric.mod.json`). Always rerun `./gradlew build` after regenerating rules (scenario tests read the bundled rules).
- **ONE Minecraft client at a time, machine-wide.** Before any local game launch (runClientGameTest, runBenchmarkAutorun, production smoke, e2e): `mkdir C:/Dev/Worktrees/.gametest-lock` (atomic; fails if held) and write `owner.txt` inside (lines `agent: <ws>`, `worktree: <your worktree>`, `started: <date -Is>`). If mkdir fails, don't wait in a loop: do other work and retry at most every 2 minutes (give up after 45 and report). Release it IN THE SAME COMMAND as the run, pass or fail: `<run>; rc=$?; rm -f C:/Dev/Worktrees/.gametest-lock/owner.txt; rmdir C:/Dev/Worktrees/.gametest-lock; exit $rc` (a hook blocks `rm -rf` on it). Long runs: `run_in_background` with the release chained in, then poll the log. Kill only your own orphaned clients (command line contains your worktree path). Never touch other java processes (the user's `fabric-server-launcher.jar`, Gradle daemons). CI runs every game test on Linux for every push: prefer CI for iteration; local runs are for E2E, autorun benchmarks, production smokes and Phase 5.
- Game-test harness quirks: render distance reset to 5; tick sync makes 1% lows unrepresentative; world-exit deadlock with Xaero's World Map or Distant Horizons loaded (harness only); `runClientGameTest` wipes its run dir; vanilla 26.3 crashes natively at startup on most local Windows launches (retry up to 6 times; not RigTune).
- NO STALLS: never sit waiting for a background-task notification. Poll CI yourself (`gh run watch <id> --exit-status`, `gh run list --branch <b>`) and poll your processes. Append a timestamped line to your scratch dir's `progress.log` at least every 10 minutes (a stall watchdog watches your worktree and scratch dir). If blocked, write the blocker into your design doc and report it.
- NEVER run unbounded filesystem searches (`find /`, `find C:/`, `grep -r /`): search only known roots (your worktree, `C:/Users/Admin/.gradle/caches`) with `-maxdepth` and `timeout 120`. Runaway `find /` processes from v0.4 research skewed benchmarks for an hour.
- Real instance: never write under `%APPDATA%\ModrinthApp`. Temp files go to your scratch dir `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/<ws>/`.
- Windows/Git Bash: absolute paths; no Python string literals with Windows backslashes; write text files with `newline='\n'`; workflow YAML must be LF.
- Anything that greps the game's latest.log must also read the rotated `logs/*.log.gz` (a run across midnight UTC rotates it).
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`
- Design notes and deviations go to `docs/v0.4/design/<ws>.md` (your own file); the coordinator folds them into DESIGN.md.
- Self-review: dispatch a code-reviewer subagent on your diff (superpowers:requesting-code-review). Its hand-back goes to the COORDINATOR, who forwards the findings and decisions to you: after dispatching it, carry on with other work; fix high and medium findings when they arrive (or when you read the reviewer's output file yourself).

## Workstream protocol (every agent)
1. Read SPEC.md (the top, "Shared contracts", your items and the Amendments), this plan (Global Constraints, your section, Hotspots), your research doc(s), and the code you'll touch.
2. Write your detailed task plan with superpowers:writing-plans to `docs/v0.4/plans/<ws>.md` in your worktree (tasks, files, test names, steps). Commit it.
3. Execute task by task with TDD, committing after each task. Push often (CI runs unit tests + game tests on every push).
4. Game tests: extend YOUR game-test class. CI must be green on every leg; download your leg artifacts (`gh run download <id>`) and LOOK at your screenshots.
5. Self-review (above); fix high/medium findings.
6. Finish: merge `origin/feat/v0.4.0` into your branch (keep both sides' intent in hotspots), `./gradlew build`, push, CI green on every job, write `docs/v0.4/design/<ws>.md`. Return at most 15 lines: branch head, unit test counts per version, game-test result (CI run URL + local if any), AC status per item (verified / not, with evidence), anything UNVERIFIED.

---

## Phase 3: foundation

### WS-0: snapshot canary + snapshot patches + version bump (SPEC 1). Branch `feat/v04-foundation`, worktree `rigtune-found4`.
**Owns:** new `.github/workflows/snapshot-canary.yml`, `gradle.properties` (`mod_version=0.4.0-dev`), the two `//? if >=26.4-snapshot-1` blocks in `client/benchmark/BenchmarkWorld.java` (terrainFloor) and `gametest/BenchmarkGameTest.java`, `tools/MC_VERSIONS.md` (canary section), `docs/v0.4/verification/snapshot-canary/`.
**Tasks:** the workflow per docs/research/v0.4/mc-versions.md §4 (weekly + dispatch; resolves the newest snapshot from piston-meta; skip-with-notice when there's no newer snapshot or add_mc_version.py refuses; node added only in the CI checkout; build + unit tests; one deduped issue on failure, a comment on repeat failures, auto-close when green; `permissions: contents: read, issues: write`; existing action versions); the two snapshot patches (trial diff: scratchpad/r-mcver/snapshot-ifpatch.diff), verified by a local throwaway `add_mc_version.py 26.4-snapshot-1 --prerelease-ok` node build + unit tests (never committed) and green 26.2/26.3 builds; prove the workflow on the branch (a temporary `push:` trigger for your branch is fine, removed before merge) including one real failure path (issue opened) and the green path (issue auto-closed), links recorded in `docs/v0.4/verification/snapshot-canary/README.md`; `mod_version=0.4.0-dev`.

---

## Wave A (early start: independent of the contracts commit)

### WS-H: self-update E2E for 0.4 (SPEC 3). Branch `test/e2e-v04`, worktree `rigtune-e2e4`.
**Owns:** tools/e2e/*, src/e2e/*, src/e2eUndo/*, their Gradle wiring if any, the e2e-driver compile step in `.github/workflows/build.yml` (one added entry for the released 0.3.0 jar; the only build.yml edit in Wave A besides WS-F), docs/smoke/self-update/*, tools/e2e/README.md ("v0.4 runs").
**Tasks:** add the 0.3.0 old side (the released `rigtune-0.3.0+mc26.2.jar` from the GitHub release, sha256 recorded; a driver that compiles against it); make the harness run 0.3.0 -> new, 0.2.0 -> new and 0.1.0 -> new, the seeded real-state variant (user's 0.1.0 DH state) and undo-after-restart (Undo last + per-entry "Undo this"); Python tests for every harness change; dry runs now against a 0.4.0-dev build (local, under the lock; results under docs/smoke/self-update/dev-*-v04/); a hook for a profile-switch entry in undo-after-restart once WS-P merges (Phase 5). The final runs happen in Phase 5 on the release candidate.

(The rest of Wave A, Wave B, hotspots and merge order follow once the SPEC and the contracts commit land.)
