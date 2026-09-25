# RigTune v0.3.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:writing-plans (write your workstream's detailed task plan first), superpowers:test-driven-development (every task), superpowers:verification-before-completion (before reporting), superpowers:requesting-code-review (self-review). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship RigTune v0.3.0 for MC 26.2 and 26.3: client game tests in CI, the deferred v0.2 defects and the benchmark render-distance bug fixed, one-command support for new MC versions, launcher-aware RAM advice, a "What RigTune changed" screen with per-entry undo, refreshed hardware tables, benchmark follow-ups, localisation checks, Report a problem, and the P2 items if time allows; without regressing 0.1.x or 0.2.x users.

**Architecture:** One Stonecutter codebase builds one jar per MC version. Pure logic lives in `core/` (JUnit-tested, no Minecraft imports); `client/` is the thin MC layer. Workstreams have disjoint file ownership; hotspot files get small, delegating edits. Phase 3 (foundation) lands the CI game-test job and the build changes alone; Wave A fans out; Wave B (localisation conversion, preview) runs on the merged Wave A code.

**Tech Stack:** Java 25, Fabric Loom 1.17 (no mappings, Mojang names), Stonecutter 0.9.8, Gradle 9.5.1, Gson, JUnit 5; Python 3.11 stdlib for tools; GitHub Actions (ubuntu + Xvfb for game tests); Minotaur for Modrinth.

**Spec:** docs/v0.3/SPEC.md (acceptance criteria per item). Research: docs/research/v0.3/*.md.

## Global Constraints
- Integration branch `feat/v0.3.0`. Branch from `origin/feat/v0.3.0`; never commit to `main` or `feat/v0.3.0`; the coordinator merges (`--no-ff`) after checking your CI and evidence. After you've pushed, don't rebase: merge `origin/feat/v0.3.0` into your branch (hooks block every force push).
- Worktrees are created by the coordinator: `C:/Dev/Worktrees/rigtune-<name>`. Work only in your own worktree. Never `cd` into the main repo.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for BOTH MC versions. Never run `./gradlew --stop` (it kills every agent's daemons).
- Stonecutter: the committed active version is 26.2 (CI fails otherwise). Version-specific code uses `//? if >=26.3 {` blocks, only where the API differs.
- Compatibility (SPEC top): rules-v1.json never less conservative for 0.1.x (RulesV1DifferentialTest, check_rules_v1.py); rules-v2.json safe for 0.2.0; no config-file format change (no formatVersion/schemaVersion bump); every new JSON field optional and ignorable by 0.2.0.
- `core/` has no Minecraft imports. Helper-safe code (anything reachable from ApplyHelper/ApplyExecutor/Journal updates in the helper) uses core + Gson only, never `RigTune.LOGGER`, Fabric or MC.
- UI text: every string the client shows comes from `assets/rigtune/lang/en_us.json` (`Component.translatable`), except user/mod/rule data and numbers. Add keys in alphabetical position under your prefix (Hotspots) so parallel edits rarely collide; never append at the end of the file.
- Tests: JUnit for core; your own client game-test class for UI/behaviour (register it in `src/gametest/resources/fabric.mod.json`). Always rerun `./gradlew build` after regenerating rules (scenario tests read the bundled rules).
- **ONE Minecraft client at a time, machine-wide.** Before any local game launch (runClientGameTest, runBenchmarkAutorun, production smoke, e2e): `mkdir C:/Dev/Worktrees/.gametest-lock` (atomic; fails if held) and write `owner.txt` inside (lines `agent: <ws>`, `worktree: <your worktree>`, `started: <date -Is>`). If mkdir fails, don't wait in a loop: do other work and retry at most every 2 minutes (give up after 30 and report). Release it IN THE SAME COMMAND as the run, pass or fail: `<run>; rc=$?; rm -f C:/Dev/Worktrees/.gametest-lock/owner.txt; rmdir C:/Dev/Worktrees/.gametest-lock; exit $rc` (a hook blocks `rm -rf` on it). Kill only your own orphaned clients (command line contains your worktree path). Never touch other java processes (the user's `fabric-server-launcher.jar`, Gradle daemons). CI runs the game tests on Linux for every push, so prefer CI for iteration and use the local lock for the final local run.
- Game-test harness quirks: render distance reset to 5; tick sync makes 1% lows unrepresentative; world-exit deadlock with Xaero's World Map or Distant Horizons loaded (harness only); `runClientGameTest` wipes its run dir; vanilla 26.3 crashes natively at OpenAL startup on about half of the local Windows launches (retry up to 5 times; not RigTune).
- NO STALLS: never sit waiting for a background notification. Poll CI yourself (`gh run watch <id> --exit-status`, `gh run list --branch <b>`) and poll your processes. If you're blocked, write the blocker into your design doc and report it.
- Real instance: never write under `%APPDATA%\ModrinthApp`. Temp files go to `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/4bf644a6-501a-4093-a81e-9e29a6010059/scratchpad/<ws>/`.
- Windows/Git Bash: absolute paths; no Python string literals with Windows backslashes; write text files with `newline='\n'`; workflow YAML must be LF.
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`
- Design notes and deviations go to `docs/v0.3/design/<ws>.md` (your own file); the coordinator folds them into DESIGN.md. Only WS-D edits RULES_SCHEMA.md; only WS-V edits DESIGN.md (its Porting section).
- Self-review: dispatch a code-reviewer subagent on your diff (superpowers:requesting-code-review). Its hand-back goes to the COORDINATOR, who forwards the findings and decisions to you: after dispatching it, carry on with other work; fix high and medium findings when they arrive (or when you read the reviewer's output file yourself).

## Workstream protocol (every agent)
1. Read SPEC.md (the top plus your items), this plan (Global Constraints, your section, Hotspots), your research doc(s), and the code you'll touch.
2. Write your detailed task plan with superpowers:writing-plans to `docs/v0.3/plans/<ws>.md` in your worktree (tasks, files, test names, steps). Commit it.
3. Execute task by task with TDD, committing after each task. Push often (CI runs unit tests + game tests on every push).
4. Game tests: add or extend YOUR game-test class. CI must be green on both versions; do one final local run per version under the lock and look at the screenshots.
5. Self-review (above), fix high/medium findings.
6. Finish: merge `origin/feat/v0.3.0` into your branch (keep both sides' intent in hotspots), `./gradlew build`, push, CI green on every job, write `docs/v0.3/design/<ws>.md`. Return at most 15 lines: branch head, unit test counts per version, game-test result (CI run URL + local), AC status per item (verified / not, with evidence), anything UNVERIFIED.

## Hotspots (shared files: small edits; logic in new classes)
| file | who edits | how |
|---|---|---|
| client/RealController.java | A (staged bookkeeping, raw game version for Modrinth), B (history, undoPlanFor, last-apply failures), C (launcher info) | a few delegating lines each |
| client/ui/RigTuneController.java | B, C, F | new `default` methods only, grouped under a `// v0.3 (WS-x)` comment |
| client/ui/RigTuneScreen.java | B (History button), C (memory line in the header + the launcher line under `ram-*` advice), F (Report a problem button); Wave B: G, P | buttons in the existing button row; rendering changes in small helpers |
| src/main/resources/assets/rigtune/lang/en_us.json | all UI workstreams | alphabetical; prefixes: A `rigtune.status.*`, B `rigtune.history.*`, C `rigtune.launcher.*`, E `rigtune.benchmark.*`, F `rigtune.report.*` |
| src/gametest/resources/fabric.mod.json | each workstream that adds a game-test class | one entrypoint line each |
| core/report/ShareReport.java | C (launcher name line) | F only reads it |
| README.md | F (Quilt FAQ), G (translator guide) | separate sections |
| tools/update_rules.py | D only (V must not touch it) | |
| build.gradle, stonecutter.gradle, settings.gradle, .github/workflows/* | Phase 3 only; afterwards only D (update-rules.yml) and V (none unless agreed) | |

---

## Phase 3: foundation (alone; merged before Wave A)

### WS-0: CI game tests + build changes (SPEC item 2; item 1 changes A and B). Branch `feat/v03-foundation`, worktree `rigtune-found`.
**Owns:** .github/workflows/build.yml (+ any new workflow), release.yml, build.gradle, stonecutter.gradle, gradle.properties (`mod_version=0.3.0-dev`), gametest harness setup code needed for Linux.
**Tasks:** port the green prototype from `research/ci-gametest` (docs/research/v0.3/ci-gametests.md) into the real build workflow: a game-test job per MC version on ubuntu with Xvfb, screenshots + logs uploaded as artifacts on success and failure, retries only for known native flakes; Sodium on `localRuntime` only when `sodium_version` is set (change A); release.yml publishes every `versions/*/` node in a loop with per-node failure accounting and `versionType` alpha for pre-release nodes (change B); `mod_version=0.3.0-dev`. CI green on every job for both versions; the coordinator reviews the screenshot artifacts.

---

## Wave A (starts when WS-0 is merged; all in parallel)

### WS-A: deferred defects (SPEC 3a, 3b, 3c; raw game version from item 1). Branch `fix/v03-deferred`, worktree `rigtune-fixes`.
**Owns:** client/undo/Staging.java, RealController (staged bookkeeping; `getRawGameVersion()` for the Modrinth lookup at RealController.java:237 and any other Modrinth game-version query, e.g. the self-update check), core/modrinth/DependencyResolver.java, core/modrinth/OnlineDataFetcher.java and DownloadPlanner.java (only as needed), their tests.
**Tasks:** 3a (drop only RigTune's update of a loaded mod with a queued update; recompute staged ids from pending.json after a drop) with the AC3.1 tests; 3b (post-update view: batched updates replace the installed version id and join the together-set) with the AC3.2 tests; 3c (project title, never a raw version id) with AC3.3; the raw game version (unit test that the Modrinth query uses the raw id and conditions still get the normalized one).

### WS-B: History screen + per-entry undo (SPEC 6, 3e). Branch `feat/history`, worktree `rigtune-history`.
**Owns:** new client/ui/HistoryScreen.java; core/history/UndoPlanner.java (`planEntry`), new core/history classes for the view model (entry summaries, status/kind label keys) and for reading `last-apply.json` failures; client/ui/UndoScreen.java (an entry-id constructor path); RigTuneController/RealController/RigTuneScreen (History button) per Hotspots; the startup WARN lines for failed ops (RigTunePreLaunch or RigTuneClient); game test `HistoryGameTest`.
**Tasks:** planEntry with the AC6.1 tests (reuse `plan()`'s candidate selection and `recheck()`); the view model with AC6.2; last-apply failures + WARN lines (AC3.5, using the captured 0.1.0 fixtures under src/test/resources/v010/); the screen (list + details + statuses + failure reason + Undo this → UndoScreen → undo(plan)); empty/corrupt/newer states; HistoryGameTest with a seeded history.json (AC6.3), screenshots at 3 sizes.

### WS-C: launcher-aware RAM advice (SPEC 5). Branch `feat/launcher-ram`, worktree `rigtune-launcher`.
**Owns:** new core/launcher/* (detection from injected signals: properties, env, game dir, a bounded file reader), new client/probe/LauncherProbe.java (reads only the named properties/env vars and the files in SPEC 5), RigTuneScreen (header memory line; the launcher line under `ram-*` advice), ShareReport (launcher name line), RealController/RigTuneController wiring, game test `LauncherGameTest`.
**Tasks:** verify the click-step wording from the open-source launchers' own UI strings (Modrinth App: github.com/modrinth/code app frontend i18n; Prism: source/wiki; ATLauncher: source) and the official launcher's `minecraft.launcher.brand` literal from primary evidence (if not verifiable, the official launcher stays Unknown); record the sources in `docs/v0.3/design/C.md`; detection with the AC5.1 tests; scenario tests AC5.2 (bundled rules × launchers); share report AC5.4; LauncherGameTest screenshots with and without `-Dminecraft.launcher.brand=theseus` (AC5.3; set the property in the game test's JVM args or via a test hook).

### WS-D: rules (SPEC 7, 12, item 1's `vulkan-backend` and change C, 3d). Branch `feat/rules-v03`, worktree `rigtune-rules3`.
**Owns:** rules/source/knowledge.json, rules/rules-v1.json, rules/rules-v2.json, src/main/resources/rigtune/rules-v2.json, rules/REVIEW.md, tools/update_rules.py, tools/check_rules_v1.py, tools/tests/*, tools/README.md, docs/RULES_SCHEMA.md, .github/workflows/update-rules.yml (only if needed for change C), src/test/.../core/hardware/*Test, RulesV1DifferentialTest (+ its baseline), KnowledgeV2ScenarioTest, RecommenderScenarioTest.
**Tasks:** the tier-row `"v1": false` projection in update_rules.py + check_rules_v1.py with Python tests (AC7.3); the four GPU rows (AC7.1) and the differential matrix additions (AC7.2); the spark advice (AC12.1); `vulkan-backend` `mcVersionRange: "<26.4-"` with a v1 override keeping 0.1.x's current behaviour; change C (targets from the Stonecutter version list + hotfix tags, AC1.4); RULES_SCHEMA.md (tier `v1: false`, the `ram-` advice-id convention used by WS-C); one regeneration run (both files, one revision), then `./gradlew build` (AC7.4); triage any open `bot/rules-update-*` PR (3d).

### WS-E: benchmark (SPEC 3f, 8). Branch `feat/benchmark-v03`, worktree `rigtune-bench3`.
**Owns:** client/benchmark/*, core/benchmark/*, client/ui/BenchmarkResultScreen.java, client/ui/BenchmarkMenuScreen.java, BenchmarkGameTest (and BenchmarkSmoke), the `rigtune.benchmark.*` keys.
**Tasks:** broadcastOptions after every RD/SD change and restore (AC8.1); chunk-presence settle with the 20 s timeout (AC8.2); server view distance restored (AC8.3); the RD 5 → 12 game test (AC3.6); SceneVariety + the game-test check + fingerprint (AC8.4-8.5); camera y = floor + 16 (AC8.6); shader advice truth table (AC8.7; the production-smoke half runs in Phase 5); `context` field + 0.2.0 reader compatibility with a pinned copy of 0.2.0's BenchmarkHistory/BenchmarkRecord in the test tree (AC8.8); a real `runBenchmarkAutorun` Tune on 26.2 and 26.3 under the lock recording per-step chunk counts (AC3.7). Correct the v0.2 "different terrain" note in design/E.md.

### WS-F: Report a problem + Quilt FAQ (SPEC 10, 11). Branch `feat/report-problem`, worktree `rigtune-report`.
**Owns:** new core/report/IssueLink.java, .github/ISSUE_TEMPLATE/problem.yml (+ config.yml if useful), RigTuneScreen (Report a problem button) per Hotspots, README.md (FAQ: Quilt; Report a problem), game test `ReportGameTest`, the `rigtune.report.*` keys.
**Tasks:** IssueLink with the AC10.1 tests (the test reads problem.yml for the field ids); the button → `ConfirmLinkScreen.confirmLinkNow(this, uri)` plus the clipboard copy when shortened; ReportGameTest presses the button, screenshots the confirm screen at 3 sizes, cancels (AC10.2); README FAQ (AC11.1).

### WS-V: MC-version tooling (SPEC item 1: D, add_mc_version.py, Porting docs). Branch `feat/mc-tooling`, worktree `rigtune-mctool`.
**Owns:** new tools/add_mc_version.py, new tools/mc_apidiff.py, new tools/tests/test_add_mc_version.py (+ fixtures under tools/tests/fixtures/add_mc_version/), docs/DESIGN.md "Porting" section, docs/v0.3/verification/mc-tooling/ (saved outputs).
**Tasks:** add_mc_version.py per research §4.3 with the AC1.2 offline tests; mc_apidiff.py per §5.2 reproducing the 26.3 → 26.4-snapshot-1 result (AC1.6); the live dry run (AC1.3); DESIGN.md Porting checklist (§4.4). Must not touch update_rules.py (WS-D does change C).

### WS-H: self-update E2E for 0.3 (SPEC 4). Branch `test/e2e-v03`, worktree `rigtune-e2e3`.
**Owns:** tools/e2e/*, src/e2e/*, src/e2eUndo/*, their Gradle wiring if any (coordinate with the coordinator if build.gradle must change), docs/smoke/self-update/*.
**Tasks:** make the harness run 0.2.0 → new and 0.1.0 → new (the released jars from the GitHub releases, sha256 recorded; the 0.2.0 old side needs a driver that works against 0.2.0); undo-after-restart on the new jar; dry runs now against a 0.3.0-dev build (local, under the lock); the final runs happen in Phase 5 on the release candidate. Python tests for any harness change.

---

## Wave B (after Wave A is merged)

### WS-G: localisation (SPEC 9). Branch `feat/l10n`, worktree `rigtune-l10n`.
Core `Text` (key, args, English fallback) for the core-built display text listed in SPEC 9 (Recommender's own titles/reasons, UndoPlanner descriptions/reasons, the queued-update/conflict notes, DependencyResolver/DownloadPlanner/benchmark failure messages shown in the UI); client rendering with the translation or the fallback (check `Component.translatableWithFallback` exists on both versions with javap); the share report stays English; LangCheckTest (AC9.1) with its self-test; the pseudo-locale unit check (AC9.3); README "Translating RigTune" (AC9.2); dead keys removed; game-test screenshots unchanged (AC9.4).

### WS-P: dry-run preview (SPEC 13, P2). Branch `feat/preview`, worktree `rigtune-preview`.
Only if time allows after Wave A. Preview model from the same planning code Apply uses (no writes, no downloads), the differential test (AC13.1), the Preview button + screen + screenshot (AC13.2).

---

## Merge order and coordination
- WS-0 → then Wave A merges as each finishes (CI green on the branch and on the merge result). Rules (WS-D) before any workstream whose tests read changed rules content (C's scenario tests read the `ram-*` rules, which D doesn't change; if D changes them, C merges after D).
- After each merge, the coordinator runs `./gradlew build` on feat/v0.3.0 and pushes; later branches merge `origin/feat/v0.3.0` before finishing.
- Phase 5 verification (coordinator + a verification agent): unit tests + game tests on every version locally and in CI (screenshots reviewed), production smokes (a copy of the user's 48 mods on 26.2; a representative Modrinth set on 26.3), the DH round trip, the shader-pack smoke (AC8.7), the real autorun Tune (AC3.7), the self-update E2E runs and undo-after-restart (AC4.1-4.2).
- Phase 6: two review rounds (Workflow: correctness, apply-pipeline safety, security, backward compatibility with 0.1.x and 0.2.x, rules accuracy; adversarial verification per finding) → docs/reviews/review-5.md, review-6.md; fix until no high/medium; a focused re-check of the last fixes.
- Phase 7 release, Phase 8 wrap-up as in the brief.

## Self-review against the SPEC
- Item 1 → WS-0 (A, B), WS-D (C, vulkan-backend), WS-V (D, add_mc_version.py, Porting), WS-A (raw game version). Item 2 → WS-0. Item 3 → WS-A (3a-3c), WS-D (3d), WS-B (3e), WS-E (3f). Item 4 → WS-H + Phase 5/7. Item 5 → WS-C. Item 6 → WS-B. Item 7 → WS-D. Item 8 → WS-E. Item 9 → WS-G. Item 10 → WS-F. Item 11 → WS-F. Item 12 → WS-D. Item 13 → WS-P.
