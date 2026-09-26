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
**Owns:** tools/e2e/*, src/e2e/*, src/e2eUndo/*, their Gradle wiring if any, the e2e-driver compile step in `.github/workflows/build.yml` (one added entry for the released 0.3.0 jar; the only build.yml edit in Wave A besides WS-F), the released-jar compatibility harness (AC3.3; run in CI next to the driver compile) and the `downgrade-040-to-030` run seeded from `src/test/resources/v040-written/` (placeholders until the features land; plan-review H-M1), docs/smoke/self-update/*, tools/e2e/README.md ("v0.4 runs").
**Tasks:** add the 0.3.0 old side (the released `rigtune-0.3.0+mc26.2.jar` from the GitHub release, sha256 recorded; a driver that compiles against it); make the harness run 0.3.0 -> new, 0.2.0 -> new and 0.1.0 -> new, the seeded real-state variant (user's 0.1.0 DH state) and undo-after-restart (Undo last + per-entry "Undo this"); Python tests for every harness change; dry runs now against a 0.4.0-dev build (local, under the lock; results under docs/smoke/self-update/dev-*-v04/); a hook for a profile-switch entry in undo-after-restart once WS-P merges (Phase 5). The final runs happen in Phase 5 on the release candidate.

---

## Phase 2 end: contracts

### WS-K: shared contracts (SPEC "Shared contracts" C1-C7). Branch `feat/v04-contracts`, worktree `rigtune-contracts`.
**Owns (only until it merges):** the C1 optional fields + `core/store` state-file helper, the C2 Java data fields and UNKNOWN stubs (+ minimal update_rules.py key sets so SchemaConsistencyTest stays green; no regenerated rules), C3 (notice core, NoticeCenter + 6 skeleton sources, ToolsScreen + 4 skeleton screens, the Tools… button and the notice line in RigTuneScreen), C4 (RigTuneController defaults, view-type skeleton records, service skeletons + one-line RealController delegations, `RealController.apply(selected, entryId)`), C5 (lang blocks), C6 (8 registered game-test stubs), the pinned `v030/` test copies (history, apply, benchmark, rules once + a `SUPPORTED_FEATURES` stub) and a pinned `v010` PendingActions if missing, the `AwarenessStore`/`ProfileStore` shells (synchronized `update`), `NoticeScreen`, Tools… replacing Benchmark in the footer (Benchmark = ToolsScreen's first entry), README's empty per-feature headings (plan-review K-M1, K-L1, X-M1, X-M2, X-L1). Contract list as landed: `docs/v0.4/design/ws-k.md` (every workstream reads it).

---

## Wave A (starts when WS-K is merged; all in parallel)

Rules of thumb for Wave A:
- Each feature workstream owns the **evaluation** of its own condition keys in `ConditionEvaluator` (a separate private method per feature, called from one line each): WS-W `driverVersion` (+ `core/hardware/DriverVersionParser`), WS-J the `jvm-` flag prefix, WS-S the stutter keys against `StutterFacts`. WS-R owns the rules **content**, tools and schema docs. Until WS-R's content lands, feature workstreams test against fixture rules in `src/test/resources/`; after it lands, they merge `origin/feat/v0.4.0` and add scenario tests over the bundled rules.
- Every screen: 640×480@2 fit, screenshots at the 3 standard sizes, network-off case (X1, X7).
- SPEC "Amendments from the plan review" override the item text; read your workstream's amendments before planning.
- Every feature workstream that writes a new file or new optional field commits a "written by 0.4" fixture produced by its own tests under `src/test/resources/v040-written/` (SPEC amendment H-M1), for WS-H's downgrade run and released-jar harness.
  Convention (WS-H): ONE folder per set, `src/test/resources/v040-written/<set>/` with set = ws-a (pending.json + history.json), ws-p (profiles.json + history.json), ws-b (benchmarks.json), ws-s (stutter.json + settings.json), ws-w (awareness.json + server-limits.json), ws-f (startup-times.json); files named exactly as in config/rigtune/. WS-H's placeholders live in `v040-written/placeholder/<set>/` (README there); a real `<set>/` folder replaces its placeholder (no add/add conflicts). Only history.json may come from two sets (merged by `at`, ids unique, same formatVersion); absolute paths in pending.json start with `${INSTANCE}` and use `/` (tools/e2e fixtures.TOKEN); profiles.json switches point at entries in the same set's history.json; timestamps in the past. The released-jar harness is tools/e2e/compat030.py + tools/e2e/compat/Compat030.java (CI's E2E driver step): run it locally against your fixture before finishing.

### WS-A: deferred defects + external-review UI fixes (SPEC 2a-2g, 2j, 2m). Branch `fix/v04-deferred`, worktree `rigtune-fixes4`.
**Owns:** HistoryScreen (2a buttons, 2c names), PreviewScreen's row rendering (2b; WS-P adds a separate constructor overload + buttons: keep your change inside the row-rendering method), `core/history/StagedChanges`, `core/apply/ModJars.nameOf`, `HistoryModel` name preference (2c), `core/modrinth/StagedProjects` (new), DownloadPlanner (projectId on ENABLE_FILE ops; the symmetric pairwise refusal, 2e), DependencyResolver helpers, RealController (the two `StagedProjects.fold` call sites only), ApplyExecutor messages (2f) and every test asserting the old wording, `core/launcher/*` + `client/ui/LauncherLines` MultiMC/GDLauncher (2g; WS-J adds the separate `jvm_steps` lines), `core/hardware/CpuClassifier.classifyDetailed`, `TierBasis` logic, `Report.tierBasis` population in Recommender (one call), RigTuneScreen tier badge/tooltip + checkbox label (2j, 2m), ShareReport tier line, `WordingTest` (X4), HistoryGameTest/PreviewGameTest/LauncherGameTest/UiGameTest extensions.
**Tasks:** each fix with a test that fails first (SPEC ACs 2a.1-2m.1); 2d is the first functional fix — do it first.

### WS-R: rules (SPEC 2k, 2l, C2 tools side; content for 4, 5, 6, 9). Branch `feat/rules-v04`, worktree `rigtune-rules4`.
**Owns:** rules/source/knowledge.json, rules/rules-v1.json, rules/rules-v2.json, src/main/resources/rigtune/rules-v2.json, rules/REVIEW.md, tools/update_rules.py (validation + v1 stripping of `profileTemplates`/`stutterAdvice`; `driverVersion` in V2 keys only; stutter keys only inside `stutterAdvice`), tools/check_rules_v1.py, tools/tests/*, docs/RULES_SCHEMA.md, `Recommender.SUPPORTED_FEATURES` (`{"jvm-flags"}`), SchemaConsistencyTest, `LegacyRulesParseTest` + `LegacyConditionFailClosedTest` (pinned v020/v030 copies from WS-K), RulesV1DifferentialTest (+ baseline), KnowledgeV2ScenarioTest/RecommenderScenarioTest (2l fixtures; 2k), the Python test on the generated files (AC2k.1).
**Tasks:** 2k (VSync unticked + honest reasons); 2l fixtures; the C2 tools side; content: item 4 `profileTemplates` (profiles.md §4.2 + SPEC 4's template definitions), item 5 `stutterAdvice` seeds (stutter.md §5.2, SPEC 5), item 6 `jvm-*` advice (SPEC 6 as reconciled with the final jvm-gc.md), item 9 driver seeds (SPEC 9: the two verified entries only). All new advice `"v1": false`; nothing v2-only reaches rules-v1.json. Regenerate once per merge (one revision for both files), then `./gradlew build`. Content that only evaluates once a feature's evaluator lands (stutter/jvm/driver keys) evaluates UNKNOWN meanwhile (safe: nothing fires); the feature workstreams add the scenario tests for their seeds after merging you.

### WS-P: Performance Profiles + share codes (SPEC 4). Branch `feat/profiles`, worktree `rigtune-profiles`.
**Owns:** new `core/profile/*` (ProfileStore on the WS-K state-file helper, ShareCode, ShareKeys, ProfileTemplates, ProfileSwitch, BatteryPrompt), `client/profile/*` (ProfileService), `client/probe/PowerWatcher`, ProfilesScreen, ProfileImportScreen, the PreviewScreen confirm overload (constructor + buttons only; WS-A owns row rendering), `Recommender.settingTargets` extraction (recommend() output unchanged; lands FIRST as a small early PR so WS-W can build on it, plan-review X-M3; no same-key patch replacement, P-H1), `RealController.apply(selected, entryId)` behaviour (the journal entry id threading), HardwareProbe's `onBattery` refresh hook, HistoryModel's entry-id → label map (a separate constructor/field; WS-A owns name preference), `client/notice/BatteryNoticeSource`, the `rigtune.profile.*`/`rigtune.battery.*` keys, ProfilesGameTest, V030CompatTest (profile-switch entries in the pinned 0.3.0 Journal/HistoryModel/UndoPlanner).
**Tasks:** first the `settingTargets` extraction as its own PR (golden report unchanged); then AC4.1-AC4.12 minus AC4.8 (AC4.13 is Phase 5). Security first: ShareCode + ShareCodeFuzzTest + injection tests before any UI.

### WS-S: Stutter Doctor (SPEC 5). Branch `feat/stutter`, worktree `rigtune-stutter`.
**Owns:** new `core/stutter/*` (SpikeDetector, Attributor, GcClock, GcKind, StutterAdvisor, StutterStore, the facts), `client/stutter/*` (StutterService, StutterMonitor rings, GcListener, ThreadSampler, the signal adapters incl. the guarded Sodium reflection), the one-line call in DebugScreenOverlayMixin + the optional phase-timer mixin, StutterScreen, the settings toggle (RigTuneSettingsScreen + ClientSettings wiring), BenchmarkController's capture-during-sweeps hook (small, separate method), RigTuneClient `StutterMonitor.install()`, the stutter-key evaluation method in ConditionEvaluator, the dev switches (`rigtune.dev.forceGcEverySec`, `rigtune.dev.stutterScript`), `rigtune.stutter.*` keys, StutterGameTest.
**Tasks:** AC5.1-AC5.7 (AC5.8 induced runs are Phase 5, but do one local smoke under the lock early to verify the phase-timer injection points on 26.2; 26.3 through CI logs). Coordinate GC wording with SPEC 6 (single source).

### WS-J: JVM and GC advice (SPEC 6). Branch `feat/jvm-advice`, worktree `rigtune-jvm`.
**Owns:** new `core/jvm/*` (JvmArgs, JvmFlagClassifier, JvmFinding, JvmReport), `client/probe/JvmProbe`, `client/jvm/JvmService`, JvmScreen, the `jvm-` flag facts into HardwareProfile.flags (HardwareProbe: one call), the `jvm-` prefix evaluation in ConditionEvaluator, `LauncherAdvice.JVM_ADVICE_PREFIX` + `rigtune.launcher.jvm_steps.*` (separate lines from WS-A's MultiMC/GDLauncher edits), ShareReport's Java line, the `-PgametestJvmArgs` hook in build.gradle (local runs only), `rigtune.jvm.*` keys, JvmGameTest.
**Tasks:** AC6.1-AC6.5 (AC6.6 is Phase 5); every number in advice text traces to docs/research/v0.4/jvm-gc.md's measured table.

### WS-B: benchmark history + regression alerts (SPEC 7). Branch `feat/bench-history`, worktree `rigtune-benchhist`.
**Owns:** `core/benchmark/BenchmarkTrend` (new), `BenchmarkHistory.comparable`, ChangeWindow (new), the Context fields' population (BenchmarkController: modSetHash + journalCursor, a separate method), BenchmarkResultScreen (trend lines), BenchmarkHistoryScreen, `client/benchmark/TrendService`, `RegressionNoticeSource` + `BenchmarkStaleNoticeSource`, the tier tooltip's last-benchmark line (a helper RigTuneScreen calls; WS-A owns the tooltip's tier part), ShareReport's last-benchmark line, BenchmarkCompatibilityTest (+ pinned v030 reader from WS-K), `rigtune.benchmark.trend.*` keys, BenchmarkHistoryGameTest.
**Tasks:** AC7.1-AC7.5 (AC7.6 is Phase 5).

### WS-W: server-aware advice + change awareness (SPEC 8, 9). Branch `feat/awareness`, worktree `rigtune-aware`.
**Owns:** `client/mixin/ClientPacketListenerMixin` + `ClientPacketListenerAccessor` (+ mixins json lines), `client/server/ServerLimitsTracker`, `core/server/ServerLimitsStore`, the Recommender `ServerLimits` overload and RealController's call with the live limits, BenchmarkController.maxRenderDistance on the accessor, `core/hardware/DriverVersionParser`, `core/model/DriverVersion`, the `driverVersion` evaluation in ConditionEvaluator, `core/awareness/*` (AwarenessStore, ChangeDetector, WhatsNew), `client/awareness/AwarenessService` (+ one call after the probe in RealController), `ServerLimitNoticeSource`, `HardwareChangeNoticeSource`, `WhatsNewNoticeSource`, the notice dismissal persistence in awareness.json, `rigtune.server.*` + `rigtune.awareness.*` keys, ServerLimitsGameTest, AwarenessGameTest.
**Tasks:** AC8.1-AC8.4 (as amended: W-H1 cap only lowers a proposed increase, applied to the output of WS-P's `settingTargets` in the main-list path once that merges) and AC9.1-AC9.7 (AC8.5, AC9.8 are Phase 5).

### WS-F: footprint guard + startup-time trend (SPEC 10, 13). Branch `feat/footprint`, worktree `rigtune-foot`.
**Owns:** `client/FootprintStats`, `client/footprint/StartupTimes` (+ startup-times.json store), RigTuneClient/RigTunePreLaunch instrumentation (small, separate methods), FootprintGameTest, FrameHookBudgetTest, `tools/footprint-budgets.json`, build.yml's footprint artifact path (the only build.yml edit besides WS-H's driver-compile step), the budgets-file wiring in build.gradle, ToolsScreen's startup line, README "RigTune's own footprint", docs/v0.4/verification/footprint/.
**Tasks:** AC10.1-AC10.6 and AC13.1-AC13.2. Land warn-only first; calibrate (1 local run under the lock + 3 CI runs per leg); switch to failing before the RC. Re-measure after WS-S merges (monitor on/off numbers, AC10.4): WS-F's own follow-up branch (else the coordinator in Phase 5).

---

## Wave B (after the Wave A UI work is merged)

### WS-X: accessibility, reduced scope (SPEC 11, P2). Branch `feat/a11y`, worktree `rigtune-a11y`.
Only if P0/P1 are on track. `client/ui/RowFocus`, `client/ui/Palette`, row children across RigTune's list screens (after WS-A/WS-P/WS-S/WS-B/WS-W have merged their screens), A11yGameTest (AC11.1-AC11.3).

---

## Hotspots (shared files: small edits; logic in new classes)
| file | who edits (after WS-K) | how |
|---|---|---|
| client/RealController.java | A (2 fold call sites), P (apply entry id), W (live limits into the Recommender call; one AwarenessService call) | one-line delegations; never restructure |
| client/ui/RigTuneController.java | none (WS-K landed every default) | ask the coordinator if a new method is truly needed |
| client/ui/RigTuneScreen.java | A (tier badge/tooltip tier part, checkbox label), B (tooltip's last-benchmark helper call), X (row focus, palette) | helper methods; the Tools button and notice line belong to WS-K |
| assets/rigtune/lang/en_us.json | everyone, inside their own C5 block | alphabetical inside the block |
| src/gametest/resources/fabric.mod.json | none (WS-K registered all 8) | |
| core/recommend/Recommender.java | A (TierBasis into Report), P (settingTargets extraction), W (ServerLimits overload), R (SUPPORTED_FEATURES) | separate methods; recommend() output unchanged except where a SPEC item says so |
| core/rules/ConditionEvaluator.java, Condition.java | S (stutter keys), J (jvm- prefix), W (driverVersion) | one private method each + one dispatch line each |
| client/probe/HardwareProbe.java | P (onBattery refresh), J (jvm facts) | one call each |
| core/report/ShareReport.java | A (tier line), J (Java line), B (last-benchmark line) | one line each |
| core/launcher/*, client/ui/LauncherLines.java | A (MultiMC, GDLauncher), J (jvm steps) | separate constants/methods |
| core/apply/PendingActions.java | P (merge replacement) | A only uses `withProjectId` |
| client/ui/PreviewScreen.java | A (row rendering), P (confirm overload + buttons) | disjoint methods |
| client/ui/HistoryScreen.java, core/history/HistoryModel.java | A (buttons, names), P (label map) | disjoint methods |
| client/mixin/*, rigtune.client.mixins.json | S (frame call, phase timers), W (packet listener) | separate mixin classes; one json line each |
| client/RigTuneClient.java | P (PowerWatcher), S (StutterMonitor.install), W (DISCONNECT), F (FootprintStats, title time) | one line each in separate helper methods |
| client/benchmark/BenchmarkController.java | S (capture hook), B (context fields), W (accessor clamp) | separate methods |
| .github/workflows/build.yml | H (0.3.0 driver compile + released-jar harness), F (footprint artifact) | disjoint steps |
| build.gradle | J (`-PgametestJvmArgs`), F (budgets path), H (only if the e2e wiring needs it) | disjoint blocks |
| README.md | R (What has been verified), F (footprint), P (profiles + shader-pack note), J (JVM help), S (Stutter Doctor) | one section each, headings created by WS-K |
| src/test/resources/v040-written/ | A, P, B, S, W, F (one fixture file set each) | new files only |

---

## Merge order and coordination
- WS-0 and WS-K first (either order; both before the P1 fan-out). WS-H any time its CI is green.
- Wave A merges as each finishes (CI green on the branch; the coordinator checks evidence, merges `--no-ff`, runs `./gradlew build` on feat/v0.4.0 and pushes). WS-R's content merge should land early (other workstreams add scenario tests on it); WS-A early (hotspots).
- Code-review hand-backs come to the coordinator, who forwards findings with decisions.
- Phase 5 verification (coordinator + verification agents; serial under the lock): unit + game tests on every version locally and in CI (screenshots reviewed); production smokes (a copy of the user's mods on 26.2; a representative Modrinth set on 26.3); AC4.13 profiles real run; AC5.8 induced stutter A-F; AC6.6 JVM findings + default-vs-Aikar pairs; AC7.6; AC8.5 local dedicated server (a fresh vanilla/Fabric server in a scratch dir on a non-default port; never the user's fabric-server-launcher); AC9.8 driver strings; AC10.3 calibration; AC10.5 RC numbers in the README; AC2i.1 read-only real-instance check; regenerate the v040-written fixtures from the RC; AC3.1-3.3 E2E finals + downgrade on the RC.
- Phase 6: two review rounds (Workflow: correctness, apply-pipeline safety, security (share codes are untrusted input), backward compatibility with 0.1.x/0.2.x/0.3.x, rules accuracy, performance/footprint; adversarial verification per finding) → docs/reviews/review-7.md, review-8.md; fix until no high/medium; a focused re-check of the last fixes.
- Phase 7 release, Phase 8 wrap-up as in the brief.

## Self-review against the SPEC
- Item 1 → WS-0. Item 2 → WS-A (2a-2g, 2j, 2m), WS-R (2k, 2l), coordinator (2h, 2i). Item 3 → WS-H + Phase 5/7 (the released-jar harness: WS-H). Item 4 → WS-P (+ WS-R content). Item 5 → WS-S (+ WS-R seeds). Item 6 → WS-J (+ WS-R advice). Item 7 → WS-B. Item 8 → WS-W. Item 9 → WS-W (+ WS-R seeds). Item 10 → WS-F. Item 11 → WS-X (Wave B). Item 12 → deferred (README note: WS-P). Item 13 → WS-F. Contracts → WS-K.
