# RigTune v0.5.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:writing-plans (your TDD task plan goes FIRST into `docs/v0.5/design/<ws>.md`, committed before any code), superpowers:test-driven-development (every task), superpowers:verification-before-completion (before reporting), superpowers:requesting-code-review (self-review). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship RigTune v0.5.0 for MC 26.2 and 26.3 as docs/v0.5/SPEC.md defines it: a CI that can't go red for reasons outside the code (P0.1), every v0.4 leftover and audit/real-world finding fixed with a test (P0.2), the verification gaps closed (P0.3), a mod that never fights the launcher (P0.4), and five features (C20, C09, C16, C02, C18), without regressing 0.1.x-0.4.x players or a downgrade to 0.4.0/0.3.0.

**Architecture:** One Stonecutter codebase, one jar per MC version. Pure logic in `core/` (JUnit, no Minecraft imports); `client/` is the thin MC layer. Workstreams own disjoint files; hotspot files get small, delegating edits in marked places; a contracts commit lands every shared signature, slot, field, stub and prefix block before the fan-out.

**Tech Stack:** Java 25, Fabric Loom 1.17.21 (pinned by ws-ci), Stonecutter 0.9.8, Gradle 9.5.1, Gson, JUnit 5; Python 3.11 stdlib for tools; GitHub Actions (ubuntu-24.04, Xvfb, a loopback-only namespace for game tests); Minotaur for Modrinth.

**Spec:** docs/v0.5/SPEC.md (228 ACs; its "Amendments" section, once the plan review lands, overrides item text). Research: docs/research/v0.5/*.md. "SPEC 2S" etc. = the SPEC's sub-item ids; "AC5.3" = its ACs.

## Global Constraints
- **Integration branch `feat/v0.5.0`.** Branch from `origin/feat/v0.5.0`. Never commit to `main` or `feat/v0.5.0`. After you've pushed, never rebase: merge `origin/feat/v0.5.0` into your branch (hooks block every force push, including `--force-with-lease`). The coordinator merges `--no-ff` after checking your CI and evidence.
- **Worktrees** are created by the coordinator: `C:/Dev/Worktrees/rigtune-<name>`. Work only in your own. Never `cd` into the main checkout; use absolute paths (the Bash tool is Git Bash).
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for BOTH MC versions. NEVER run `./gradlew --stop` (it kills every agent's daemons).
- Stonecutter: the committed active version stays 26.2 (CI fails otherwise). Version-specific code only in `//? if >=26.3 {` blocks where the API differs (SPEC X9 lists the known differences); a new block is recorded in your design doc.
- **Compatibility** (SPEC "Compatibility promise"): rules-v1 never less conservative (RulesV1DifferentialTest, `check_rules_v1.py`, `tierTablesStayAtV010`); rules-v2 safe for 0.2.0/0.3.0/0.4.0 (new sections ignored by older parsers or `requires`-gated; new keys fail closed); no `formatVersion`/`schemaVersion` bump; new state in NEW files; existing files only get the optional fields listed in SPEC C1 (old constructors kept); **no new `PendingActions.Type` value**; the helper stays core + Gson (HelperLauncherTest).
- **X4 footprint gate:** no v0.5 service, store, notice source or screen is constructed or class-loaded during `RigTunePreLaunch`, `onInitializeClient` or the CLIENT_STARTED handler. Use the contracts' lazy holder (`client/V05Services`) and lazy notice list; FootprintGameTest fails a leg that resolves the holder early. No new frame-hook work; new tick work allocates nothing. The timing budgets in `tools/footprint-budgets.json` are ws-ci's and nobody else edits that file.
- `core/` has no Minecraft imports. UI text only from `assets/rigtune/lang/en_us.json`, inside YOUR prefix block (SPEC C5, created by the contracts commit), alphabetical inside it; never append at the end of the file (LangCheckTest, PseudoLocaleTest, WordingTest).
- On-device: every new feature works with RigTune's network switches off; each game-test class you own runs its main case with the network off through ws-ci's `GameTestNet.set(...)` (never write `networkEnabled` directly).
- Honest wording (SPEC X3): estimates are estimates; measured comparisons show their numbers and say "may be related" / "measured comparison, not proof"; never "fixed", "caused", "because of", "proves", "guarantee", "limited by", "bottleneck".
- Accessibility (SPEC X6): every new screen extends `RowList`; rows return `RowFocus`; other text uses `RowFocus.standalone`; colours via `Palette.of` with existing literals only; clicks compare `InputConstants.MOUSE_BUTTON_LEFT`, keys `InputConstants.KEY_*`. Your A11y walk goes in the skeleton method the contracts commit created for you in `A11yGameTest` (only that method).
- Threading (SPEC X8): `settings.json` only through `SettingsSaver`; ordered writes on your feature's own ordered chain; `Probes.EXECUTOR` gets short tasks only (no network, no blocking waits); no new network use; no new thread.
- Screens fit 640×480, 854×480 and 1280×720 at GUI scale 2 (+854×480 at scale 3 for scrolling lists): layout check + screenshots; download your CI artifacts and LOOK at them.
- **Fixtures (seed, don't drive):** if you write a new file or a new optional field, commit "written by 0.5" fixtures produced by your own tests to `src/test/resources/v050-written/<your set>/` (convention in that folder's README, from the contracts commit; set names in each workstream below). Run compat030 (and compat040 once WS-E lands it) against your set before finishing.
- **Real instance:** never write under `%APPDATA%\ModrinthApp`. Read-only copies only, into your scratch dir. The user's real 0.1.0-era files for RW-1 are anonymised into test resources (paths cut to file names), never read live by a test.
- NEVER run unbounded filesystem searches (`find /`, `find C:/`, `grep -r /`): only known roots, with `-maxdepth` and `timeout 120`.
- Scratch dir: `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/590d2d3e-58b4-418a-a809-0e625214088f/scratchpad/<ws>/` (temp files, logs, `progress.log`).
- Windows/Git Bash: absolute paths; write text files with `newline='\n'`; workflow YAML must be LF. Anything that greps a game's `latest.log` also reads the rotated `logs/*.log.gz`.
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`
- README, CHANGELOG, DESIGN.md and docs/modrinth/body-0.5.md are edited only by the docs workstream (Phase 7). Put the text you need there (README/known-limits lines, DESIGN paragraphs) into your design doc's "Docs" section.

## Workstream protocol (every agent)
1. Read SPEC.md (the top, X1-X12, C3, C8, "Shared contracts", your items, the Amendments), this plan (Global Constraints, your section, Hotspots, Ownership), your research doc(s) and `docs/v0.5/design/ws-k.md` (the contracts as landed), then the code you'll touch.
2. Write your TDD task plan into `docs/v0.5/design/<ws>.md` (tasks, files, test names, the red test for each fix, which ACs each task closes). Commit and push it before any code.
3. Execute task by task with TDD (a test that fails first; for P0.2 fixes, reuse the audit-verify / realworld throwaway tests named in the SPEC), committing after each task. Push when a task is done (not every commit: CI capacity is shared by ~12 workstreams).
4. Game tests: only YOUR classes/methods (Ownership). CI green on every job and every leg; `gh run download <id>` and look at your screenshots.
5. Self-review: dispatch a code-reviewer subagent on your diff. Its findings go to the coordinator, who forwards them with decisions; keep working meanwhile; fix high/medium.
6. Finish: merge `origin/feat/v0.5.0` into your branch (keep both sides' intent in hotspots), `./gradlew build`, push, CI green on every job, finish `docs/v0.5/design/<ws>.md` (deviations, residuals, UNVERIFIED, Docs text, the AC table with evidence). Report in at most 15 lines: branch head, unit-test counts per version, CI run URL, AC status per item (verified / not, with evidence), anything UNVERIFIED.

---

## Phases, waves and merge order

```
Phase 3 (foundation)    ws-ci (P0.1; RUNNING on feat/v05-ci) ──merge──┐
                        WS-K contracts (parallel, disjoint files) ─────┴─merge after ws-ci─┐
                        WS-E early (tools/e2e only, no build.yml yet) ─────────────────────┤
Phase 4 Wave A          WS-L1  WS-L2  WS-S  WS-P  WS-B  WS-H  WS-R  WS-W  WS-F  WS-E(cont.)  <── all start when WS-K merges
                        + early pure-core starts of the Wave B features (new files only):
                          WS-S2 core (core/stutter/Fix*), WS-T core (core/tryit/*), WS-P2 core (ServerProfileStore/Prompt/View)
Phase 4 Wave B          WS-S2 client (after WS-S merges)   WS-P2 client (after WS-P merges)
                        WS-T client (after WS-B merges)    WS-W2 = C18 (after WS-W merges; first P1 to cut)
Phase 5                 verification (coordinator + P5 agents, serial under the lock) + user steps
Phase 6-8               review rounds (review-11.md on), release, wrap-up
```

**Merge order (coordinator):** ws-ci → WS-K → (Wave A as each is green; WS-R and WS-H early, they unblock scenario tests and L8's baseline labels; WS-L1 before WS-L2; WS-P before WS-E's battery game test; WS-B before WS-T's client part) → Wave B → the docs workstream. ws-ci's first 5-run streak (AC1g.2, P0.1 SHA) runs right after ws-ci merges, with a push freeze on `feat/v0.5.0`; WS-K merges after the streak.

**Phase 3 detail.**
- **ws-ci** owns `.github/workflows/build.yml`, `build.gradle`, `gradle.properties` and the game-test harness (listed in Ownership) until it merges. Nobody else edits those files before then.
- **WS-K** runs in parallel with ws-ci on files ws-ci doesn't touch. Three parts of it touch ws-ci's files and wait until ws-ci has merged: the skeleton methods in `A11yGameTest` and `AwarenessGameTest`, and the lazy-holder assertion in `FootprintGameTest`. WS-K merges `origin/feat/v0.5.0` after ws-ci lands, adds those, and merges second.
- **WS-E** starts early on `tools/e2e/*` only (the two cherry-picks, `e2e_matrix.py`, `compat040.py`, `written.py`); its `build.yml`/`release.yml`/`e2e.yml` and game-test parts wait for ws-ci and WS-K.

---

## Workstreams

Effort is in agent-days (from the research files, adjusted); "Riskiest" names what to de-risk first.

### ws-ci: rock-solid CI (SPEC 1, AC1a.1-AC1g.3). Branch `feat/v05-ci`, worktree `rigtune-ci`. RUNNING.
**Owns:** see Ownership (build.yml, build.gradle, gradle.properties, tools/ci/*, the prefetch init script, FakeModrinth + FakeModrinthTest, `tools/gametest/modrinth-candidates.json`, `HttpModrinthClient`/`OnlineDataFetcher` (1f), `GameTestNet` and the network helpers in the ten game-test classes it edits, FootprintGameTest, FrameHookBudgetTest, FootprintBudgetsTest, ThreadSamplerTest, `tools/footprint-budgets.json`, `tools/ci_streak.py`, docs/v0.5/verification/{ci,footprint,ci-streak.md}, docs/v0.5/design/ws-ci.md).
**Deliverables:** proofs (a live call fails under the namespace; a 2× tick hook fails `tickHookOnVsReference`; a hang dumps threads at 13 min), head green twice on every job, then the first 5-run streak on the P0.1 SHA. **Riskiest:** the frame-hook ratio gates' self-check on shared runners (AC1d.4 allows the backstop-only fallback, recorded). **Effort:** ~3 (in progress).

### WS-K: shared contracts (SPEC "Shared contracts" C1-C8, X4). Branch `feat/v05-contracts`, worktree `rigtune-contracts5`.
**Owns until it merges:** everything in "The contracts commit" below. Afterwards each piece passes to the workstream named there. Deliverable: `docs/v0.5/design/ws-k.md` listing every signature, stub, key block, skeleton method and fixture folder as landed (every workstream reads it). **Riskiest:** the lazy holder without growing `renderThreadInitCpuMs` (measure before/after on CI; the budget is 150 ms, max observed 131.1). **Effort:** ~1.5.

### WS-L1: launcher policy, advice, Undo rule, opt-in, news (SPEC 4a, 4b, 4c, 4e, 4j.1-4j.2; RW-2, RW-14). Branch `feat/v05-launcher-policy`, worktree `rigtune-l1`. Wave A.
**Owns:** `core/launcher/ModFilesPolicy`, `InstanceEvidence`, `LauncherModText` (stubs from WS-K), `LauncherInfo` (`modStepsKey`), `client/probe/LauncherProbe` (PENDING, evidence listing), `client/ui/LauncherLines` (steps lines), new `core/report/LauncherModAdvice`, `core/report/ShareReport` (mod-files line), `core/history/UndoPlanner` (policy skip, RW-14), `client/undo/UndoService`/`client/ui/UndoScreen` (launcher reason + steps line), `client/launcher/ModFilesService` (WS-K skeleton: policy cache, MOD_FILES_NEWS), `client/notice/ModFilesNewsNoticeSource`, the "Mod files" row in `RigTuneSettingsScreen` (its own method), the opted-in warning in RigTuneScreen's existing offline line (`RigTuneScreen.java:308-312`, one method), PreviewScreen's two launcher lines (4b count line and 4h's fan-out line: one method), `LauncherManagedGameTest` (its WS-L1 methods), the Modrinth-App/GDLauncher desync model oracle test (AC4j.1), lang `rigtune.launcher.mod_steps.*`, `rigtune.launcher.mod_files.*`, `rigtune.undo.reason.launcher_managed`, `rigtune.undo.reason.not_disabled_by_rigtune`, `rigtune.settings.mod_files*`. Fixture set `ws-l1` (settings.json with the opt-in).
**RealController edits (marked places):** the `LauncherModAdvice` call before `ModrinthOffAdvice` in `rebuild()`, the apply guard, `modFiles()` delegation, the undo state's `modFiles()`.
**Depends on:** WS-K. Reads `FirstRunService.status()` (stub until WS-F merges). **Merges before WS-L2.**
**Riskiest:** PENDING (a timed-out detection) must never flicker to RIGTUNE; and the 240-scenario golden report must stay byte-identical under RIGTUNE. **Effort:** ~3.

### WS-L2: helper and repair (SPEC 4d, 4f, 4g, 4h, 4j.3 prep; RW-1; 3f's forcing writer). Branch `fix/v05-launcher-helper`, worktree `rigtune-l2`. Wave A.
**Owns:** `core/apply/ApplyExecutor`, `ApplyHelper`, `HelperLauncher`, `ApplyResult` (failed vs dropped counts), `UnfinishedGroups` (forcing writer, SPEC 3f), new `core/history/LauncherRepair`, `client/launcher/LauncherRepairService` (WS-K skeleton: held groups, repair findings, outside changes), new `client/awareness/OutsideChanges` (snapshot/compare logic, using the WS-K `AwarenessStore` accessors), `client/notice/HeldModChangesNoticeSource`, `LauncherRepairNoticeSource`, `OutsideChangesNoticeSource`, RigTuneClient's helper-launch property and next-launch toast texts (`RigTuneClient.java:158-170, :215-230`, in their own methods) and its CLIENT_STOPPING snapshot call, `LauncherManagedGameTest`'s WS-L2 methods, `AwarenessGameTest`'s 4h method, lang `rigtune.repair.*` (incl. the held-changes notice), `rigtune.outside.*`, the helper toast keys it changes in `rigtune.toast.*`. Tests: `RealWorldFixTest` and `RealWorld20260927Test` taken from `C:/Dev/Worktrees/rigtune-realworld` (uncommitted there; diff at `<scratch>/realworld/fix-prototype.diff`), with the copied instance data **anonymised into `src/test/resources/realworld/`** (file names only, no paths or user names), crash-replay tests (AC4f.2), `ApplyExecutorHoldTest`, `LauncherRepairTest`, `OutsideChangesTest`. Fixture set `ws-l2` (pending.json with a held group; awareness.json with the options snapshot and P0.4 notice keys).
**Depends on:** WS-K; the real policy from WS-L1 (test with a forced policy until WS-L1 merges). **Merges after WS-L1.** After it merges, the coordinator removes the `rigtune-realworld` worktree.
**Riskiest:** RW-1's crash-replay semantics (0.4+ deaths vs 0.1-0.3-shaped pending.json) and keeping the helper core + Gson. **Effort:** ~3.

### WS-S: Stutter Doctor fixes (SPEC 2S: L1, SD-1..SD-6, NEW-1 unit part, RW-10, RW-11; RW-15's capture side). Branch `fix/v05-stutter`, worktree `rigtune-stutter5`. Wave A.
**Owns:** `client/stutter/*` (StutterService, StutterCapture, StutterHooks incl. the WS-K seam `benchmarkStepExcluded(boolean)` and the RW-11 settings/reload event, StutterMonitor, GcListener, ThreadSampler, BuildBacklog, DevStutter), `core/stutter/*` except the `Fix*`/`SessionOutcome` files (StutterAnalyzer, StutterRings, FrameRing, Attributor, GcKind, StutterReport's compact constructor (fields from WS-K), StutterSummary, StutterStore), `client/ui/StutterScreen`, `StutterGameTest`, lang `rigtune.stutter.*` except `rigtune.stutter.fix.*` (incl. `rigtune.stutter.window.*`, `rigtune.stutter.tag.settings_changed`). Fixture set `ws-s` (stutter.json with the RW-11 fields and tag). NEW-1's real measurement is Phase 5 (AC2S.11 real part).
**Depends on:** WS-K. **Then:** the same owner continues as WS-S2 (StutterService stays one owner).
**Riskiest:** SD-1's coverage denominators without an allocation per frame; RW-11's per-tick check inside the tick budgets. **Effort:** ~2.5.

### WS-S2: C20 Stutter Doctor one-click fixes (SPEC 5, AC5.1-AC5.16 minus the rules side). Branch `feat/v05-stutter-fixes`, worktree `rigtune-stutterfix`. Early core in Wave A, client in Wave B.
**Owns:** new `core/stutter/FixSpec` (skeleton + constants from WS-K), `FixGate`, `FixOffers`, `SessionOutcome`, `FixComparison`, `FixConditions`, `FixStore` (shell from WS-K), `FixTracker`, `FixHold`, `FixOffer` (skeleton from WS-K), `StutterFacts.causeSpikes`, `StutterView` fields, the `causeSpikesAtLeast` evaluation method in `ConditionEvaluator` (stub from WS-K), `client/stutter/StutterFixService` (WS-K skeleton), the C20 parts of StutterService/StutterMonitor/StutterHooks/StutterScreen (after WS-S merges: same owner), RealController's `FixHold` line after `ServerCap`, HistoryModel's fix label + HistoryScreen's one branch (cut 2nd; after WS-H merges), `StutterFixGameTest`, lang `rigtune.stutter.fix.*`, the LangCheckTest family line for the verdict/skip keys. Fixture set `ws-s2`.
**Early (Wave A, new files only):** FixComparison (+ the exact binomial reference values), FixConditions, FixTracker, FixGate, FixSpec validation, FixStore, FixHold with unit tests. **Client (Wave B):** after WS-S merges.
**Depends on:** WS-K; WS-S (client); WS-R's validator + r17 seeds (use fixture rules until then); WS-H (the History label only). Real calibration (AC5.14) is Phase 5.
**Riskiest:** real evidence on the one PC (AC5.14; the thresholds are UNVERIFIED starting values) and the comparison's wording when the answer is "no clear change". **Cut order** per SPEC 5 (DH fix first). **Effort:** ~5.75 (MVP ~3.5), of which ~2 early.

### WS-P: profile and battery fixes (SPEC 2P: PF-1..PF-5, Latent 1). Branch `fix/v05-profiles`, worktree `rigtune-profiles5`. Wave A.
**Owns:** `client/profile/ProfileService` (PF-1, PF-2, PF-3, PF-5 gate; `BATTERY_TOAST_ID` made public for WS-E), `core/profile/*` (ProfileStore, ProfileSwitch, ShareKeys (+ the Latent 1 comment and `ShareKeysTest` pins), ShareCode (PF-4), BatteryPrompt), the "Battery offer" row in `RigTuneSettingsScreen` (its own method), `ProfilesGameTest`, lang `rigtune.settings.battery_offer*` and edits in `rigtune.profile.*`/`rigtune.battery.*`. **Then:** continues as WS-P2 (ProfileService stays one owner).
**Depends on:** WS-K. **Blocks:** WS-E's BatteryFlowGameTest (needs PF-1). **Riskiest:** PF-5's three gates without widening key 22. **Effort:** ~1.5.

### WS-P2: C16 per-server profile offers (SPEC 7, AC7.1-AC7.18). Branch `feat/v05-server-profiles`, worktree `rigtune-serverprof`. Early core in Wave A, client in Wave B.
**Owns:** `core/server/ServerProfileStore` (shell from WS-K), new `core/profile/ServerProfilePrompt`, `ServerProfilesView` (skeleton from WS-K), `client/server/ServerProfileService` (WS-K skeleton), `client/notice/ServerProfileNoticeSource`, new `client/ui/ServerProfilesScreen`, `ProfilesScreen` (row 3), `ServerLimitsTracker` (two visibility changes), ProfileService's two appended read-only methods, RealController's `deleteProfile` line, RigTuneClient's JOIN/DISCONNECT registration (one lambda each, lazy per X4), `ServerProfilesGameTest`, its A11y method, lang `rigtune.profile.servers*` / `rigtune.profile.server.*`. Fixture set `ws-p2` (server-profiles.json, one entry per kind).
**Early (new files only):** ServerProfileStore, ServerProfilePrompt, ServerProfileNoticeTest's builder, their unit tests. **Client:** after WS-P merges. **Riskiest:** real JOIN timing (AC7.16, Phase 5); the dedicated-server game test's CI time (fold into ServerLimitsGameTest's server session if 1g's time rule bites). **Effort:** ~3.

### WS-B: benchmark fixes and benchmark-screen accessibility (SPEC 2B: BH-1, BH-2, RW-5..RW-9, RW-15's benchmark side; 2A: L3). Branch `fix/v05-benchmark`, worktree `rigtune-bench5`. Wave A.
**Owns:** `client/benchmark/*` (BenchmarkController, BenchmarkConditions, BenchmarkWorld (worldFresh), MarkerRestore, ClientKnobs, TrendService, KeepSettings), `core/benchmark/*` except `BenchmarkHistory` (RenderDistancePlanner, BenchmarkTrend, ChangeWindow, TrendText, RestoreMarker/ModToggles if RW-6's pause ships), `client/compat/DhCompat` (RW-6), `client/ui/BenchmarkResultScreen`, `BenchmarkHistoryScreen`, `BenchmarkTrendLines`, `TrendChart`, `BenchmarkGameTest`, `BenchmarkHistoryGameTest` (outside ws-ci's network helper), its A11y method (AC2A.1-2), lang `rigtune.benchmark.*` and `rigtune.benchmark.trend.*`. RW-15: calls the WS-K seam `StutterHooks.benchmarkStepExcluded(true/false)` around a timed-out step (WS-S implements it). RW-6's "no stutter advice on benchmark-world captures" is WS-S's (StutterService.analyze); WS-B only detects and names. Fixture set `ws-b` (benchmarks.json with the three context fields).
**Depends on:** WS-K. **Blocks:** WS-T's client part. **Riskiest:** RW-5's re-measure inside the deadline, and L3's table as rows without changing the pixels (AC2A.2). RW-6's pause is decided by javap on DH 3.3.2 (detect-and-name ships regardless; AC2B.5's real run is Phase 5). **Effort:** ~4.

### WS-T: C09 Measured Try It (SPEC 6, AC6.1-AC6.17). Branch `feat/v05-try-it`, worktree `rigtune-tryit`. Early core in Wave A, client in Wave B.
**Owns:** new `core/tryit/*` (TryIt, TryItStore (shell from WS-K), Triable, TryItFlow, TryItVerdict, TryItText, TryItView (skeleton from WS-K)), `client/tryit/TryItService` (WS-K skeleton; sets the `Busy` Try-It hook), `client/notice/TryItNoticeSource`, new `client/ui/TryItScreen`, `core/benchmark/BenchmarkHistory` (`after`, `openBefore` filter), `BenchmarkMenuScreen` (one filter), BenchmarkController's outcome hook (after WS-B merges), PreviewScreen's plain footer (its own method), RigTuneClient's tick line and CLIENT_STARTED submit, `TryItGameTest`, its A11y method, lang `rigtune.tryit.*`. Fixture set `ws-t` (benchmarks.json with `tryit-` pairs, history.json, pending.json, tryit.json).
**Early (new files only):** core/tryit/* with TriableTest, TryItFlowTest, TryItVerdictTest, TryItStoreTest. **Client:** after WS-B merges.
**Scope:** the full design incl. RESTART; the same-session fallback (SPEC 6 "Scope that ships") only by coordinator decision. **Riskiest:** chaining two runs through BenchmarkController's finish/show path under the harness's AWAITING_EXIT hand-off (TryItGameTest blocks 1, 2, 6 first). **Effort:** ~4.75, of which ~1.25 early.

### WS-H: apply/history leftovers (SPEC 2H: L5 test, L7, L8, L9, RW-3, RW-4). Branch `fix/v05-history`, worktree `rigtune-history5`. Wave A.
**Owns:** `core/history/Journal` (L8 fold), `HistoryModel` (L8 labels; WS-S2 adds its separate fix-label method later), `LegacyImport`, `StagedChanges` (RW-4), `client/undo/Staging` (L7, RW-3), `client/RigTunePreLaunch` (RW-3 count), `core/modrinth/DownloadPlanner`, `DependencyResolver`, `StagedProjects` (L9), RealController's `discardPending` message and the one RW-3 call next to `dropQueuedUpdates`, `PreviewGameTest` (L5 check), lang edits in `rigtune.history.*`, `rigtune.status.*` (incl. `discarded_with_kept` and RW-3's drop toast), `rigtune.preview.note.downloads`. Fixture set `ws-h` (history.json with a baseline entry carrying `foldedEntryIds`).
**Not owned:** UndoPlanner (WS-L1), ApplyExecutor (WS-L2). **Depends on:** WS-K. **Riskiest:** L8 keeping the 400-seed Undo-all property test and the released 0.3.0/0.4.0 Journal round trip; L9's red test against today's behaviour first. **Effort:** ~2.

### WS-R: rules (SPEC 2S L2, 2R L4, C20's rules side AC5.1, 4i). Branch `feat/v05-rules`, worktree `rigtune-rules5`. Wave A.
**Owns:** `rules/source/knowledge.json`, `rules/rules-v1.json`, `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/REVIEW.md`, `tools/update_rules.py`, `tools/check_rules_v1.py`, `tools/tests/*` (rules tests), `docs/RULES_SCHEMA.md` (stutterFixes, causeSpikesAtLeast, the chunksLoading tag), `SchemaConsistencyTest`, `LegacyRulesParseTest`, the pinned `v040/core/rules/` parser test (copies from WS-K), `RulesV1DifferentialTest`, `KnowledgeV2ScenarioTest`, the StutterAdvisor scenario tests for the L2 seed (C1r/C3r/A-control fact fixtures), the 4i pinned-copy test (v020/v030/v040 `modVersion` on `rigtune`). **One revision, r17**, regenerated once per merge; `./gradlew build` after every regeneration.
**Content:** L2 (`chunksLoading` in the updater's tag vocabulary + the `stutter-chunks-loading-tag` seed), L4 (7 reason strings), C20's `stutterFixes` validator + the three seeds with sf §2.2's starting thresholds (the DH entry last, removable), 4i's warning only if its test passes (else the decision is recorded). WS-S2's AC5.14 calibration returns new thresholds to WS-R (a follow-up regeneration, or the coordinator's).
**Depends on:** WS-K (Java constants for SchemaConsistencyTest ties). **Merges early.** **Riskiest:** 4i's reach test on the pinned old copies. **Effort:** ~1.5.

### WS-W: awareness, drivers, settings write, launch-time advice (SPEC 2W: AW-1, AW-2, Latent 2; 2D; 2R L6; 2L RW-16a). Branch `fix/v05-awareness`, worktree `rigtune-aware5`. Wave A.
**Owns:** `client/awareness/AwarenessService` (AW-1; the `SESSION_ONLY_PREFIXES` constant from WS-K), `client/ui/NoticeScreen` (AW-2 callback), `core/awareness/Fingerprint` (Latent 2), `core/hardware/DriverVersionParser` + `src/test/resources/drivers/real-strings.tsv`, `client/StartupNotices` and RigTuneClient's privacy-save line (L6), `client/probe/HardwareProbe` (2L detection), new `client/mixin/CrashReportMixin` + its one line in `rigtune.client.mixins.json`, `client/ui/ToolsScreen` (2L lines; C18's lines in WS-W2), `AwarenessGameTest`'s WS-W methods, lang edits in `rigtune.awareness.*`, `rigtune.startup.perf_counters.*`. **Then:** continues as WS-W2.
**Depends on:** WS-K. **Riskiest:** whether the `CrashReport.preload` mixin applies before Fabric's preLaunch has finished (UNVERIFIED; the advice ships without the number if not). **Effort:** ~2.

### WS-W2: C18 launch-time regression alerts (SPEC 9, AC9.1-AC9.8). Branch `feat/v05-launch-alerts`, worktree `rigtune-launchalert`. Wave B (after WS-W merges). **First P1 to cut.**
**Owns:** new `core/footprint/StartupTrend`, `client/footprint/StartupTimes` (`View.assessment`), `client/notice/StartupRegressionNoticeSource` (WS-K skeleton; its detail adds 2L's line on such a PC), ToolsScreen's regression lines, `AwarenessGameTest`'s C18 method, its A11y assertions in the Tools A11y method, lang `rigtune.startup.regression*`, `rigtune.startup.notice.*`. Fixture set `ws-w2` (awareness.json with `acknowledgedStartupRegressions`; accessors from WS-K). **Riskiest:** the estimated floor/MIN_RUNS on real launches (AC9.8, Phase 5). **Effort:** ~2.

### WS-F: C02 first-time Apply trust flow (SPEC 8, AC8.1-AC8.18). Branch `feat/v05-first-apply`, worktree `rigtune-firstapply`. Wave A.
**Owns:** new `core/history/FirstRun`, `client/FirstRunService` (WS-K skeleton), `client/notice/FirstRunNoticeSource`, new `client/ui/FirstApplyScreen`, `HowItWorksScreen`, RigTuneScreen's `applySelected` (about 6 lines), RealController's `applied()` call at the end of `apply(selected, entryId)` and the `load()` submission, `FirstApplyGameTest` (first entrypoint), its A11y methods, the 0.4.0 list-height baseline for AC8.18 (measured once on a throwaway branch from the `v0.4.0` tag, recorded as a constant), lang `rigtune.firstrun.*`. Fixture set `ws-f` (awareness.json with `firstrun.guide`).
**Depends on:** WS-K (`modFiles()` stub, `guideLine` stub); AC8.14's real-policy check and the 640×480 screenshot with the opted-in line after WS-L1 merges. **Riskiest:** FirstApplyGameTest must see a fresh run dir (first entrypoint; fails under `CI` otherwise). **Effort:** ~3.

### WS-E: verification gaps and publishing machinery (SPEC 3a-3f, 3h's release.yml; AC4j.3's E2E leg; the v050-written convention). Branch `test/v05-e2e`, worktree `rigtune-e2e5`. Early start, then Wave A.
**Owns:** `tools/e2e/*` (cherry-picks 25243b63 + 1f4b6b3a from `research/v05-verify`, NOT its experiment workflow; new `e2e_matrix.py`, `compat040.py`, `compat/Compat040.java`; `written.py` with the fixture root as a parameter; the `helper-kill`, generated-seed, `v010-dh-app-reinstalled`, reverse-check and version-pin scenarios; Python tests), `src/e2e*` drivers (the downgrade driver against 0.4.0), new `.github/workflows/e2e.yml`, `release.yml` (build → e2e → publish; CDN sha512 + metadata checks), the `e2e` job and compat040 step in `build.yml` (after ws-ci merges), `LanGuestGameTest` (+ the Realms block), `BatteryFlowGameTest` (after WS-P's PF-1 merges) and the tmpfs OSHI release-tier leg, `A11yGameTest`'s high-contrast method (3f), the snapshot-canary fixture-manifest test, `src/test/resources/v050-written/README.md` convention (from WS-K) and placeholders, docs/v0.5/verification/{e2e,server,battery}/.
**Depends on:** ws-ci (build.yml), WS-K (game-test registration), WS-P (battery), WS-L1/WS-L2 (the launcher-brand E2E leg), every workstream's fixtures (compat040 grows as sets land). **Riskiest:** the release tier's ~23 jobs finishing first-try on the release PR, and release.yml's restructure (actionlint + the real release run). **Effort:** ~5.

### Not workstreams (listed, scheduled by the coordinator)
- **Docs (Phase 7):** README (features, "What has been verified", known limits incl. the speech rewording (3f), known issues (4i), Tools/Profiles/Stutter text), CHANGELOG [0.5.0], DESIGN.md fold-ins from every `docs/v0.5/design/<ws>.md`, docs/modrinth/body-0.5.md, GitHub release notes. One docs agent, from the design docs' "Docs" sections.
- **Phase 5 items** (below).

---

## The contracts commit (WS-K): exact contents
Everything a Wave A workstream needs from a shared file, so none of them edits a shared file beyond its marked place. Each item compiles, has a test where stated, and passes LangCheckTest.

1. **NoticePriority**: the 14 slots in SPEC C3's order (`BATTERY_OFFER, SERVER_PROFILE, HELD_MOD_CHANGES, LAUNCHER_REPAIR, FIRST_RUN, SERVER_LIMIT, TRY_IT, BENCHMARK_REGRESSION, STARTUP_REGRESSION, HARDWARE_CHANGED, SETTINGS_CHANGED_OUTSIDE, MOD_FILES_NEWS, WHATS_NEW, BENCHMARK_STALE`); `NoticeBoardTest` pins the order (AC-X.3).
2. **Lazy holder (X4)**: `client/V05Services` held by RealController; one synchronized, create-on-first-use getter per service skeleton: `ModFilesService` (WS-L1), `LauncherRepairService` (WS-L2), `FirstRunService` (WS-F), `TryItService` (WS-T), `ServerProfileService` (WS-P2), `StutterFixService` (WS-S2). Each skeleton's constructor only stores the controller. `V05Services.resolvedCount()` for FootprintStats.
3. **Lazy notice list**: `NoticeCenter` gets the v0.5 sources through one `Supplier<List<NoticeSource>>` resolved on the first `notices()` call; the combined list is in C3 order. Skeleton sources (each returns null, `act` no-op): `ServerProfileNoticeSource`, `HeldModChangesNoticeSource`, `LauncherRepairNoticeSource`, `FirstRunNoticeSource`, `TryItNoticeSource`, `StartupRegressionNoticeSource`, `OutsideChangesNoticeSource`, `ModFilesNewsNoticeSource`.
4. **FootprintStats flag + FootprintGameTest assertion** (after ws-ci merges): the holder and the lazy list unresolved at `initEnd` and when the CLIENT_STARTED handler returns (AC-X.2).
5. **Busy (C8)**: `client/Busy.refusal(RealController)` returning `@Nullable Text` in C8's order; `static volatile BooleanSupplier tryItRunning = () -> false` (WS-T sets it); `ProfileService.refusal()` delegates; new key `rigtune.tryit.refused.running`; `BusyTest` (AC-X.1).
6. **RigTuneController defaults** (StubController and A11y's stub compile unchanged) + one-line RealController delegations (to the skeletons, returning the defaults until filled):
   - C02: `boolean firstApplyPending()` (false), `Component apply(List<Recommendation> selected, String entryId)` (delegates to `apply(selected)`; RealController's existing method gets `@Override`), `boolean downloading()` (false; RealController's existing method gets `@Override`).
   - P0.4: `ModFilesPolicy modFiles()` (RIGTUNE).
   - C20: `ApplyPreview previewStutterFix(FixOffer.Offer offer)` (`ApplyPreview.EMPTY`), `Component applyStutterFix(FixOffer.Offer offer)` (a "nothing" status), `void dismissStutterFix(String entryId)`.
   - C09: `TryItView tryIt()` (`TryItView.EMPTY`), `@Nullable Text tryItRefusal(Recommendation rec)` (a not-available text), `Component startTryIt(Recommendation rec, BenchmarkRequest.Scene scene)`, `void tryItMeasureNow()`, `Component tryItKeep()`, `void tryItCancel()`.
   - C16: `ServerProfilesView serverProfiles()` (`ServerProfilesView.EMPTY`), `Component rememberServerProfile(@Nullable String profileId)`, `Component forgetServerProfile(String key)`, `Component forgetAllServerProfiles()`.
7. **Type stubs**: `core/launcher/ModFilesPolicy` (enum RIGTUNE, LAUNCHER, PENDING; `of(...)` returns RIGTUNE), `core/launcher/InstanceEvidence(boolean packwizIndex)`, `core/launcher/LauncherModText.guideLine(policy, launcher)` (returns null); `core/stutter/FixOffer` (sealed: `Offer(adviceId, key, from, to, boolean now)`, `NotYet(adviceId, Reason, List<String> args)`), `core/stutter/FixSpec` (constants `KEYS`, `FEATURE = "stutter-fix"`, the field-name sets the updater mirrors), `core/tryit/TryItView` (record + EMPTY), `core/profile/ServerProfilesView` (record per sp §2.3 + EMPTY).
8. **New state-file shells** on `StateStore`/`JsonStateFile` (X7), each with name, cap and `formatVersion` 1, an empty load, `writable()`, and one shared parametrised contract test (missing, corrupt → `.bad`, newer → read-only, over 4 × cap left alone, unknown fields kept): `core/stutter/FixStore` (`stutter-fixes.json`, 32 KiB), `core/tryit/TryItStore` (`tryit.json`, 16 KiB), `core/server/ServerProfileStore` (`server-profiles.json`, 16 KiB, salt handling left to WS-P2).
9. **Optional record fields, old constructors kept, round-trip tests, and the pinned 0.2.0/0.3.0 readers still reading them:** `JournalEntry.foldedEntryIds` (`@Nullable List<String>`); `BenchmarkRecord.Context.worldFresh`, `dhGenerating` (`@Nullable Boolean`), `stagedAtStart` (`@Nullable List<String>`); the stutter session's `settingsAtStart`/`settingsAtEnd` (`@Nullable Map<String,String>` on `StutterReport`); `ClientSettings.modFilesByRigTune` (boolean, default false); `AwarenessStore` accessors `acknowledgedStartupRegressions()`/`acknowledgeStartupRegression(String)` (capped like `acknowledgedRegressions`) and `optionsAtExit()`/`setOptionsAtExit(Map<String,String>)` (capped at 64 keys).
10. **Rules model**: `RulesDocument.stutterFixes` (`@JsonAdapter(LenientSection.class) List<StutterFix>`, `StutterFix`/`FixSet` per sf §2.2, numbers as `JsonElement`; null when absent); `Condition.causeSpikesAtLeast` (`Map<String,String>`) included in `ConditionEvaluator.hasStutterKey`, evaluated as UNKNOWN (stub); minimal `tools/update_rules.py` key sets so SchemaConsistencyTest stays green (no validation, no content); the pinned `src/test/java/.../v040/core/rules/` copies (`git show v0.4.0:` of RulesDocument, LenientSection, ConditionAdapterFactory, Condition, BudgetedChars, Impact; package-renamed as v030) and a `LegacyParserTest` that reads today's r16 with them.
11. **Seams**: `StutterHooks.benchmarkStepExcluded(boolean)` (no-op; WS-S implements, WS-B calls); `AwarenessService.SESSION_ONLY_PREFIXES` with `"server-profile:"` and `dismiss` skipping it (fully implemented, tested).
12. **en_us.json prefix blocks** (SPEC C5) with each skeleton's keys: `rigtune.stutter.fix.*`, `rigtune.tryit.*`, `rigtune.profile.servers*`/`rigtune.profile.server.*` (after `rigtune.profile.unnamed`), `rigtune.firstrun.*`, `rigtune.startup.regression*`, `rigtune.startup.notice.*`, `rigtune.startup.perf_counters.*`, `rigtune.launcher.mod_steps.*`, `rigtune.launcher.mod_files.*`, `rigtune.settings.mod_files*`, `rigtune.repair.*`, `rigtune.outside.*`, `rigtune.settings.battery_offer*`; README's key-area list is the docs workstream's.
13. **WordingTest** additions (X5): `rigtune.tryit.` in the correlation prefixes; the `rigtune.stutter.fix.` word list ("fixed the", "proves", "guarantee").
14. **Game tests** (after ws-ci merges for the shared classes): the 7 new classes registered in `src/gametest/resources/fabric.mod.json` in SPEC C6's order, each with one empty test (`FirstApplyGameTest` first; `StutterFixGameTest` after StutterGameTest; `TryItGameTest` after BenchmarkHistoryGameTest; `LanGuestGameTest`, `ServerProfilesGameTest` after ServerLimitsGameTest; `BatteryFlowGameTest`, `LauncherManagedGameTest` after AwarenessGameTest; FootprintGameTest, A11yGameTest last). Empty skeleton methods, each called once from its class's entry, one per owner: in `A11yGameTest` (`walkStutterFix` WS-S2, `walkTryIt` WS-T, `walkServerProfiles` WS-P2, `walkFirstApply` and `walkHowItWorks` WS-F, `walkToolsStartup` WS-W then WS-W2, `walkBenchmarkScreens` WS-B, `walkBatteryOfferRow` WS-P, `walkModFilesRowAndNews` WS-L1, `walkLauncherNotices` WS-L2, `highContrastRunningGame` WS-E), in `AwarenessGameTest` (`awarenessFixes` WS-W, `startupRegression` WS-W2, `settingsChangedOutside` WS-L2), in `LauncherManagedGameTest` (`policyAndAdvice` WS-L1, `heldAndRepair` WS-L2).
15. **Fixtures**: `src/test/resources/v050-written/README.md` (one folder per set; set names `ws-l1, ws-l2, ws-s, ws-s2, ws-p2, ws-b, ws-t, ws-h, ws-w2, ws-f`; files named as in `config/rigtune/`; `${INSTANCE}` path token; history.json from several sets merged by `at`; timestamps in the past; placeholders in `v050-written/placeholder/<set>/` replaced by the real set) and `src/test/resources/realworld/README.md` (the anonymisation rule for WS-L2).
16. `docs/v0.5/design/ws-k.md`: the contracts as landed.

---

## Ownership (one workstream per file)
Paths: `client/…` = `src/client/java/io/github/chaotix345/rigtune/client/…`; `core/…` = `src/main/java/io/github/chaotix345/rigtune/core/…`; `gametest/…` = `src/gametest/java/io/github/chaotix345/rigtune/gametest/…`. A file not listed and not new is frozen: ask the coordinator first. New files belong to the workstream whose section names them.

| file(s) | owner | notes |
|---|---|---|
| `.github/workflows/build.yml` | ws-ci, then WS-E | WS-E adds only the `e2e` job, compat040 and the E2E cache steps |
| `.github/workflows/e2e.yml` (new), `release.yml` | WS-E | |
| `.github/workflows/snapshot-canary.yml`, `update-rules.yml` | frozen | |
| `build.gradle`, `gradle.properties`, `tools/ci/*`, `.github/ci/*` | ws-ci | after ws-ci: WS-E only if the e2e wiring needs it; `mod_version` bump = coordinator at release |
| `tools/footprint-budgets.json`, FootprintBudgetsTest, FrameHookBudgetTest, ThreadSamplerTest | ws-ci | frozen after ws-ci |
| `gametest/FootprintGameTest` | ws-ci, then WS-K (X4 assertion) | |
| `gametest/GameTestNet` + the network helpers in A11y/Awareness/BenchmarkHistory/Jvm/Preview/Profiles/ServerLimits/Stutter/Ui game tests | ws-ci | afterwards each class passes to its owner below |
| `tools/e2e/**`, `src/e2e*/**`, `tools/e2e/tests/**` | WS-E | FakeModrinth(+Test) is ws-ci's until it merges, then WS-E |
| `core/modrinth/HttpModrinthClient`, `OnlineDataFetcher` | ws-ci | frozen after |
| `core/modrinth/DownloadPlanner`, `DependencyResolver`, `StagedProjects` | WS-H | L9 |
| `core/notice/NoticePriority`, `NoticeBoard`, NoticeBoardTest, `client/notice/NoticeCenter` | WS-K | frozen after |
| `client/RealController` | hotspot | see Hotspots |
| `client/ui/RigTuneController`, `gametest/StubController` | WS-K | frozen after (ask the coordinator for a truly new method) |
| `client/ui/RigTuneScreen` | hotspot | WS-F (`applySelected`), WS-L1 (offline-line method) |
| `client/ui/RigTuneSettingsScreen` | hotspot | WS-P (battery row method), WS-L1 (mod-files row method) |
| `client/ui/PreviewScreen` | hotspot | WS-T (plain footer), WS-L1 (launcher lines method) |
| `client/ui/ToolsScreen` | WS-W, then WS-W2 | 2L lines, then C18 lines (separate methods) |
| `client/RigTuneClient` | hotspot | see Hotspots |
| `client/RigTunePreLaunch` | WS-H | RW-3 count |
| `client/StartupNotices` | WS-W | L6 |
| `client/SettingsSaver`, `ClientSettings` | WS-K (field) | frozen after |
| `client/FootprintStats` | WS-K | |
| `client/Busy`, `client/V05Services` (new) | WS-K | WS-T sets the Busy hook only |
| `client/awareness/AwarenessService` | WS-W | `SESSION_ONLY_PREFIXES` landed by WS-K |
| `client/awareness/OutsideChanges` (new) | WS-L2 | |
| `core/awareness/AwarenessStore` | WS-K | accessors for C18 and 4h landed; frozen after |
| `core/awareness/Fingerprint` | WS-W | Latent 2 |
| `core/awareness/ChangeDetector`, `WhatsNew` | frozen | |
| `client/ui/NoticeScreen` | WS-W | AW-2 |
| `core/hardware/DriverVersionParser`, `src/test/resources/drivers/**` | WS-W | 2D |
| `client/probe/HardwareProbe` | WS-W | 2L |
| `client/probe/LauncherProbe`, `core/launcher/*`, `client/ui/LauncherLines` | WS-L1 | |
| `client/probe/PowerWatcher` | frozen | WS-E drives it from its game test only |
| `client/mixin/CrashReportMixin` (new) | WS-W | |
| `rigtune.client.mixins.json` | hotspot | one line per new mixin class: WS-W (CrashReportMixin); WS-S only if RW-11's reload event needs a mixin |
| `client/launcher/ModFilesService`, `client/notice/ModFilesNewsNoticeSource`, `core/report/LauncherModAdvice` | WS-L1 | |
| `core/report/ShareReport` | WS-L1 | mod-files line |
| `core/report/ModrinthOffAdvice` | frozen | |
| `core/history/UndoPlanner`, `client/undo/UndoService`, `client/ui/UndoScreen` | WS-L1 | |
| `core/apply/ApplyExecutor`, `ApplyHelper`, `HelperLauncher`, `ApplyResult`, `UnfinishedGroups` | WS-L2 | helper-safe; HelperLauncherTest |
| `core/apply/PendingActions`, `ApplyLock`, patchers | frozen | no new op type |
| `core/history/LauncherRepair`, `client/launcher/LauncherRepairService`, the three P0.4 notice sources, `src/test/resources/realworld/**` | WS-L2 | |
| `core/history/Journal`, `LegacyImport`, `StagedChanges`, `client/undo/Staging` | WS-H | |
| `core/history/HistoryModel`, `client/ui/HistoryScreen` | WS-H, then WS-S2 | WS-S2 adds a separate fix-label method/branch after WS-H merges |
| `core/history/JournalEntry` | WS-K (field) | frozen after |
| `core/history/FirstRun` (new), `client/FirstRunService`, `FirstRunNoticeSource`, `FirstApplyScreen`, `HowItWorksScreen` | WS-F | |
| `client/stutter/*` | WS-S, then WS-S2 | one owner (same agent) |
| `core/stutter/*` except `Fix*`, `SessionOutcome` | WS-S, then WS-S2 (StutterFacts, StutterView, StutterAnalyzer's C20 counts) | |
| `core/stutter/Fix*`, `SessionOutcome` | WS-S2 | shells/skeletons from WS-K |
| `client/ui/StutterScreen` | WS-S, then WS-S2 | |
| `core/rules/ConditionEvaluator`, `Condition`, `RulesDocument` | WS-K (fields, stub), then WS-S2 (the evaluation method) | |
| `rules/**`, `src/main/resources/rigtune/rules-v2.json`, `tools/update_rules.py`, `tools/check_rules_v1.py`, `tools/tests/**`, `docs/RULES_SCHEMA.md`, SchemaConsistencyTest, LegacyRulesParseTest, `v040` parser test, RulesV1DifferentialTest, KnowledgeV2ScenarioTest | WS-R | the only rules owner; r17 |
| `core/recommend/Recommender` | frozen | every v0.5 change is a post-step outside it (4b, C20 FixHold); the golden report must stay identical |
| `core/recommend/ServerCap`, `SettingValues` | frozen | |
| `client/profile/ProfileService`, `core/profile/*` (existing) | WS-P, then WS-P2 | WS-P2 appends two read-only methods |
| `core/profile/ServerProfilePrompt`, `ServerProfilesView`, `core/server/ServerProfileStore`, `client/server/ServerProfileService`, `ServerProfileNoticeSource`, `ServerProfilesScreen` | WS-P2 | |
| `client/ui/ProfilesScreen`, `client/server/ServerLimitsTracker` | WS-P2 | row 3; two visibility changes |
| `core/server/ServerLimitsStore` | frozen | C16 calls its package-private `key()` |
| `client/benchmark/*`, `core/benchmark/*` except `BenchmarkHistory`, `client/compat/DhCompat`, `client/ui/Benchmark{Result,History}Screen`, `BenchmarkTrendLines`, `TrendChart` | WS-B | BenchmarkController gets WS-T's outcome hook after WS-B merges |
| `core/benchmark/BenchmarkHistory`, `client/ui/BenchmarkMenuScreen`, `core/tryit/*`, `client/tryit/*`, `TryItNoticeSource`, `TryItScreen` | WS-T | |
| `core/benchmark/BenchmarkRecord` | WS-K (fields) | frozen after |
| `core/footprint/StartupTrend` (new), `client/footprint/StartupTimes`, `StartupRegressionNoticeSource` | WS-W2 | |
| `src/main/resources/assets/rigtune/lang/en_us.json` | hotspot | each workstream inside its C5 block (WS-K created them) |
| `src/test/java/.../LangCheckTest.java` | WS-K; WS-S2 adds one family line | |
| `src/test/java/.../WordingTest.java` | WS-K | frozen after |
| `src/gametest/resources/fabric.mod.json` | WS-K | frozen after |
| game-test classes | their owner | Profiles→WS-P; Stutter→WS-S; Benchmark, BenchmarkHistory→WS-B; Preview→WS-H; Awareness, A11y, LauncherManaged→per skeleton method (item 14); FirstApply→WS-F; StutterFix→WS-S2; TryIt→WS-T; LanGuest, BatteryFlow→WS-E; ServerProfiles→WS-P2; all others frozen |
| `src/test/resources/v050-written/<set>/` | the set's workstream | README/placeholders WS-K/WS-E |
| `docs/v0.5/design/<ws>.md` | that workstream | |
| `docs/v0.5/verification/<area>/` | the workstream or P5 agent producing the evidence | |
| README.md, CHANGELOG.md, DESIGN.md, docs/modrinth/** | docs workstream (Phase 7) | |

---

## Hotspots (shared files: small edits in marked places; logic in new classes)
| file | who edits (after WS-K) | rule |
|---|---|---|
| `client/RealController.java` | WS-L1 (`LauncherModAdvice` call before `ModrinthOffAdvice` in `rebuild()`; apply guard; undo-state policy), WS-H (`discardPending` message; one RW-3 call next to `dropQueuedUpdates`), WS-F (`applied()` at the end of `apply(selected, entryId)`; `load()` submission in `start()`), WS-S2 (`FixHold.apply` wrapping the `ServerCap.apply` result), WS-P2 (one line in `deleteProfile`) | one-line calls into the owner's class; never restructure; the delegations and notice list are WS-K's |
| `client/RigTuneClient.java` | WS-W (privacy save via SettingsSaver), WS-L2 (helper `holdFileOps` property; toast texts; CLIENT_STOPPING snapshot call), WS-T (tick line; one CLIENT_STARTED submission), WS-P2 (JOIN/DISCONNECT registration) | each in its own helper method; X4: registrations and submissions only |
| `client/ui/RigTuneScreen.java` | WS-F (`applySelected`), WS-L1 (the offline-line method's opted-in branch) | different methods; no new header line, no footer button |
| `client/ui/RigTuneSettingsScreen.java` | WS-P (battery-offer row), WS-L1 (mod-files row) | one method each; rows appended in that order |
| `client/ui/PreviewScreen.java` | WS-T (plain-mode footer), WS-L1 (launcher lines) | disjoint methods; the Confirm overload unchanged |
| `client/ui/ToolsScreen.java` | WS-W (2L lines), WS-W2 (C18 lines) | sequential (WS-W2 after WS-W); separate methods |
| `client/ui/RigTuneController.java`, `NoticePriority`, `NoticeCenter`, `gametest fabric.mod.json`, `WordingTest` | WS-K only | a truly new method goes through the coordinator |
| `assets/rigtune/lang/en_us.json` | everyone, inside their own C5 block; edits to existing blocks only by that block's owner (WS-B `rigtune.benchmark.*`, WS-H `rigtune.history.*`/`rigtune.status.*`/`rigtune.preview.note.downloads`, WS-W `rigtune.awareness.*`, WS-S `rigtune.stutter.*`, WS-P `rigtune.profile.*`/`rigtune.battery.*`, WS-L2 `rigtune.toast.*`, WS-E `rigtune.server.*` for the DH note) | alphabetical inside the block; never at the end of the file |
| `core/recommend/Recommender.java` | nobody | frozen (golden report) |
| `core/history/UndoPlanner.java` | WS-L1 | policy skip + RW-14 only |
| `core/apply/ApplyExecutor.java`, `ApplyHelper`, `HelperLauncher` | WS-L2 | helper-safe (core + Gson) |
| `core/history/Journal.java`, `HistoryModel.java` | WS-H, then WS-S2 (label method only) | Journal.cap/fold semantics unchanged apart from L8 |
| `client/stutter/StutterService.java` | WS-S then WS-S2 (same agent) | |
| `client/profile/ProfileService.java` | WS-P then WS-P2 (same agent) | |
| `client/benchmark/BenchmarkController.java` | WS-B, then WS-T (outcome hook in `show()` and the CURRENT-cancel branch only) | |
| `core/rules/ConditionEvaluator.java` | WS-S2 (one private method + one dispatch line) | |
| `rules/source/knowledge.json` + every regeneration | WS-R only | one revision (r17) per merge; `./gradlew build` after |
| `build.yml`, `build.gradle` | ws-ci until merged; then WS-E (e2e job, compat040, E2E caches) | disjoint steps; no automatic retries of tests |
| `release.yml` | WS-E | tag == `mod_version` guard and sha512 verify kept |
| `gametest/A11yGameTest.java`, `AwarenessGameTest.java`, `LauncherManagedGameTest.java` | the owner of each skeleton method (contracts item 14) | only your method's body |

---

## Local runs, the game-test lock, CI polling
- **ONE Minecraft client at a time, machine-wide.** Before any local game launch (`runClientGameTest`, `runProductionClientGameTest`, autorun benchmarks, production smokes, E2E): `mkdir C:/Dev/Worktrees/.gametest-lock` (atomic; fails if held) and write `owner.txt` inside (`agent: <ws>`, `worktree: <path>`, `started: <date -Is>`). If mkdir fails, do other work and retry at most every 2 minutes; give up after 45 minutes and report. Release it IN THE SAME COMMAND as the run, pass or fail: `<run>; rc=$?; rm -f C:/Dev/Worktrees/.gametest-lock/owner.txt; rmdir C:/Dev/Worktrees/.gametest-lock; exit $rc`. Long runs: `run_in_background` with the release chained in, then poll the log. Kill only your own orphaned clients (command line contains your worktree path); never touch other java processes (the user's `fabric-server-launcher.jar`, Gradle daemons).
- **Prefer CI for game tests.** Every push runs every game-test class on the three Linux legs. Local runs are for E2E on Windows, real-PC checks and Phase 5. Local 26.3: `ALSOFT_DRIVERS=null` and SPEC X9's retry policy (up to 5 fresh attempts, each listed); don't run the 26.3 E2E locally.
- Harness quirks: render distance reset to 5; tick sync makes 1 % lows meaningless (never assert FPS numbers or verdict kinds from a live run); world-exit deadlocks with Xaero's World Map or DH loaded (harness only); `runClientGameTest` wipes its run dir; dedicated servers in game tests use a free port (never 25565: the user's own server) and write `eula.txt=true` only in the wiped run dir.
- **CI polling:** never wait for a notification. `gh run list --branch <b> --limit 3` then `gh run watch <id> --exit-status` (in the background with a timeout) and poll. Never `gh run rerun`: a red job after ws-ci is a real failure or a P0.1 bug; report it to the coordinator (who alone may re-run, and records why). Don't dispatch `build.yml` yourself; the coordinator runs the streaks with a push freeze on `feat/v0.5.0` (announced; hold your merges into the integration branch then).
- **Merge protocol:** branch from `origin/feat/v0.5.0`; merge (never rebase after push) `origin/feat/v0.5.0` into your branch before finishing and whenever a hotspot you share has moved; resolve keeping both intents; `./gradlew build`; push; CI green on every job. The coordinator merges `--no-ff` after checking CI, screenshots, fixtures and the design doc, then runs `./gradlew build` on `feat/v0.5.0` and pushes.
- **Progress and watchdog:** append a timestamped line to `<scratch>/<ws>/progress.log` at least every 10 minutes. The coordinator registers you in `<scratch>/agents.txt` (`name=<dir>;<dir>@<branch>`); `run_watchdog.sh` (stall 25 min, lock 12 min) flags a silent agent or a held lock. If blocked, write the blocker into your design doc and report it; never sit idle.

---

## Phase 5 (verification; coordinator + P5 agents, serial under the lock; user steps as noted)
Not workstreams; each result lands in `docs/v0.5/verification/<area>/` and PROGRESS.
- CI: the RC 5-run streak (AC1g.2) with a push freeze; the E2E release tier on the release PR (AC3a.4, AC3b.3, AC4j.3, AC3e.2, AC3f.5 Linux, AC3f.7); the 26.3 stutter-script run (AC3f.1); regenerate every `v050-written` set from the RC and re-run compat040/compat030.
- Real runs on the dev PC (a P5 agent under the lock; never the user's instance): NEW-1 generational Shenandoah strings (AC2S.11); RW-6 DH benchmark (AC2B.5); 2L perf-counter advice with the Perflib export (AC2L.5); the DH server note (AC3f.4); the Windows seeded E2E with a real held handle (AC3a.5) and the Windows helper-kill (AC3f.5); P0.4 simulated-brand and `.index/` runs + the read-only repair-list check (AC4j.4); C20 calibration (AC5.14); C09 A/A + NOW + RESTART (AC6.16); C16 real JOIN on a non-default port (AC7.16); C02 fresh-instance run at three sizes + 26.3 Apply half (AC8.17); C18 launches (AC9.8).
- User steps (UNVERIFIED unless the user runs them; the coordinator prepares the kits): the laptop run (3g, AC3g.1-AC3g.8); the Modrinth App throwaway-instance check (AC4j.5); the 10-minute Windows Narrator listen (AC3f.2).
- Coordinator: the snapshot canary's first scheduled run (Wed 2026-09-30 05:00 UTC, AC3f.8).
- Read-only real-instance check (logs, config/rigtune) and the moderation status (AC3h.1/AC3h.3).

## Critical path and expected wall-clock ordering
1. **Now:** ws-ci finishing (proofs, green twice); WS-K and WS-E's early `tools/e2e` work in parallel.
2. **ws-ci merges → first streak (≈1-2 h, push freeze) → WS-K merges** (its ws-ci-dependent parts rebased by merge).
3. **Wave A fan-out (≈9 agents + WS-E):** shortest first to merge: WS-R, WS-H, WS-P (≈1.5-2 days of agent work), then WS-W, WS-S, WS-L1, then WS-L2, WS-F, WS-B (≈3-4). The early pure-core parts of WS-S2, WS-T and WS-P2 run alongside.
4. **Wave B:** WS-P2 client after WS-P (short); WS-W2 after WS-W; WS-S2 client after WS-S; WS-T client after WS-B.
5. **Critical path:** ws-ci → WS-K → WS-B (~4) → WS-T client (~3.5 after its early core), in parallel with ws-ci → WS-K → WS-S (~2.5) → WS-S2 client (~3.5) → AC5.14's real calibration (Phase 5, may need a WS-R threshold regeneration and a re-run). Mitigations: WS-B puts BenchmarkController's hook-relevant changes (RW-5, RW-15, RW-8) first and merges them as soon as green; WS-S merges its StutterService fixes before SD-2/RW-11 polish; the early pure-core starts; C18 (WS-W2) is the first cut if Wave B slips, then each feature's own cut list (SPEC "P1 cut order").
6. **Phase 5** starts when every P0 workstream and the P1s that ship have merged; the release candidate is the SHA after Phase 5 fixes and the review rounds' fixes.

## Self-review against the SPEC
- 1 → ws-ci. X4/C3/C8 and the Shared contracts → WS-K.
- 2S → WS-S (L1, SD-1..6, NEW-1, RW-10, RW-11; RW-15 capture side) + WS-R (L2 rules); 2P → WS-P; 2W → WS-W; 2B → WS-B (RW-15 benchmark side, RW-6 detection); 2A → WS-B; 2H → WS-H; 2R → WS-R (L4), WS-W (L6), docs (L20's DESIGN paragraph, from WS-S's design doc); 2L → WS-W; 2D → WS-W.
- 3a-3c, 3e, 3f → WS-E (3f's forcing writer → WS-L2; the DH note wording → WS-E after the Phase 5 run; speech → docs); 3d → WS-E; 3g → Phase 5 user step; 3h → WS-E (release.yml) + coordinator (publish).
- 4a-4c, 4e, 4j.1-4j.2 → WS-L1; 4d, 4f, 4g, 4h → WS-L2; 4i → WS-R (+ docs for the known-issue text); 4j.3 → WS-E; 4j.4-4j.5 → Phase 5.
- 5 (C20) → WS-S2 + WS-R (AC5.1); 6 (C09) → WS-T; 7 (C16) → WS-P2; 8 (C02) → WS-F; 9 (C18) → WS-W2; 10 (P2) → only after every P0/P1 AC is green, each with its own research note first.
