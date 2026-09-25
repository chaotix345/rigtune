# RigTune v0.2.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:writing-plans (write your workstream's detailed task plan first), superpowers:test-driven-development (every task), superpowers:verification-before-completion (before reporting). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship RigTune v0.2.0 for MC 26.2 and 26.3 with rules schema v2, Undo, Modrinth publishing, a verified self-update, and the P1 features, without regressing 0.1.0 users.

**Architecture:** One Stonecutter codebase (Phase 3) builds one jar per MC version. Pure logic lives in `core/` (unit-tested); `client/` is the thin MC layer. Features are split into workstreams with disjoint file ownership. Shared types (the "contracts", commit cae06b8 on `feat/v0.2.0`) are already in place, so workstreams compile against the same names.

**Tech Stack:** Java 25, Fabric Loom 1.17 (no mappings), Stonecutter 0.9.8, Gradle 9.5.1, Gson, JUnit 5; Python 3.11 stdlib for tools; GitHub Actions; Minotaur for Modrinth.

**Spec:** docs/v0.2/SPEC.md (acceptance criteria per item). Research: docs/research/v0.2/*.md.

## Global Constraints
- Branch from and rebase onto `origin/feat/v0.2.0`. Never commit to `main` or `feat/v0.2.0`; the coordinator merges.
- Worktrees are created manually by the coordinator: `C:/Dev/Worktrees/rigtune-<name>`. Work only in your own worktree.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for BOTH MC versions.
- Stonecutter: the committed active version is 26.2. Version-specific code uses `//? if >=26.3 {` blocks, only where the API differs (docs/research/v0.2/api-diff.md). Never commit while switched to another version.
- Compatibility: rules-v1.json must stay safe for 0.1.x; every file 0.1.0 wrote must still read correctly (SPEC top).
- `core/` has no Minecraft imports. New client logic goes in new classes; hotspot files get surgical, delegating edits only (see Hotspots).
- Tests: JUnit for core; client game tests for UI and behaviour (own class per workstream). Always rerun `./gradlew test` after regenerating rules.
- **ONE Minecraft client at a time, machine-wide.** Take the lock before any game launch: `mkdir C:/Dev/Worktrees/.gametest-lock` (atomic; fails if held). Write `owner.txt` inside (your name, worktree, time). If mkdir fails, don't wait in a loop: carry on with unit work and retry later. After the run, confirm your client exited, then `rm -rf C:/Dev/Worktrees/.gametest-lock`. Kill only your own orphaned clients (their command line contains your worktree path). Never touch other java processes (the user's `fabric-server-launcher.jar`, Gradle daemons).
- Game-test harness quirks: render distance reset to 5; tick sync makes 1% lows unrepresentative; world-exit deadlock with Xaero's World Map or Distant Horizons loaded (harness only); `runClientGameTest` wipes its run dir.
- Commit messages end with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`
- Language keys: add yours in alphabetical position, under your workstream's prefix (below), so parallel edits to `en_us.json` rarely collide.
- Design notes: write your workstream's design and deviations to `docs/v0.2/design/<ws>.md` (your own file). The coordinator folds them into DESIGN.md at the end. Only WS-A edits RULES_SCHEMA.md.
- Never write under %APPDATA%\ModrinthApp. Use the scratchpad for temp files: `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/097c9765-76fe-415d-a5ae-7debe28cb5de/scratchpad/<ws>/`.
- Windows/Git Bash: absolute paths; no Python string literals with Windows backslashes.

## Workstream protocol (every agent)
1. Read SPEC.md (your items plus the top), this plan (Global Constraints, your section, Hotspots), your research doc(s), and the contract files you consume.
2. Write your detailed task plan with superpowers:writing-plans to `docs/v0.2/plans/<ws>.md` in your worktree (tasks, files, test names, steps). Commit it.
3. Execute task by task with TDD, committing after each task.
4. Game tests: add or extend YOUR game-test class. Run them for 26.2 then 26.3 while holding the lock, and look at the screenshots.
5. Self-review: dispatch a code-reviewer subagent (superpowers:requesting-code-review) on your diff, then fix its high and medium findings.
6. Finish: rebase onto `origin/feat/v0.2.0` (resolve conflicts in hotspot files by keeping both sides' intent), `./gradlew build`, push, wait for CI green (`gh run watch`), and write `docs/v0.2/design/<ws>.md`. Return at most 15 lines: branch head, test counts per version, game-test result, CI URL, AC status per item (verified / not), and anything UNVERIFIED.

## Hotspots (shared files: surgical edits, delegate to new classes)
| file | who edits | how |
|---|---|---|
| client/RealController.java | A (loadRules), B (apply recording, undoPlan/undo), C (startBenchmark(req), latestBenchmark), D (apply routing for dh./iris.), E (shareReport, Modrinth gating) | a few lines each; logic lives in new classes |
| client/RigTuneClient.java | B (install ChangeRecorder), C (benchmark restore marker), E (toast gating, privacy toast) | a few lines each |
| src/main/resources/assets/rigtune/lang/en_us.json | all UI workstreams | alphabetical; prefixes: B `rigtune.undo.*`, C `rigtune.benchmark.*`, D `rigtune.dh.*`/`rigtune.iris.*`, E `rigtune.settings.*`/`rigtune.share.*`/`rigtune.screen.*` |
| src/gametest/resources/fabric.mod.json | every workstream that adds a game-test class | one entrypoint line each |
| build.gradle, versions/*/gradle.properties | C (compileOnly DH/Iris APIs), F (Minotaur) | separate blocks |
| .github/workflows/build.yml | A (rules jobs) | |
| .github/workflows/release.yml | F | |

---

## Wave A (starts when Phase 3 `feat/multi-version` is merged)

### WS-A: rules schema v2 (SPEC item 2, P0). Branch `feat/rules-v2`, worktree `rigtune-rules`. Model: opus.
**Owns:** core/rules/* (implementation), core/recommend/Recommender.java (`requires`, labels in titles), core/model/SettingKeys.java only if needed for labels, tools/update_rules.py, new tools/check_rules_v1.py, tools/tests/*, tools/README.md, rules/rules-v2.json + src/main/resources/rigtune/rules-v2.json (new, generated), removal of the bundled src/main/resources/rigtune/rules-v1.json (rules/rules-v1.json stays), .github/workflows/build.yml rules jobs, docs/RULES_SCHEMA.md, RealController.loadRules.
**Consumes:** the Condition v2 fields, `unknownFields`, `RulesDocument.settingLabels`, `requires` (contracts); `ClientState.remoteRulesAllowed()`.
**Tasks:**
1. Parsing: a Gson TypeAdapterFactory for Condition that records unknown keys into `unknownFields` (recursively for `not`/`anyOf`); `RulesLoader` accepts schemaVersion 1 and 2. Tests: unknown key at top level / inside not / inside anyOf poisons the whole condition; v1 doc parses unchanged.
2. Evaluation: `gpuModelMatches` (budgeted regex, ≤200 chars, same subject string as GpuClassifier), `displayPixelsAtLeast/AtMost`, `modVersion` and `mcVersionRange` using Fabric Loader's `Version.parse` / `VersionPredicate.parse`; the fail-closed poisoning; `requires` skipping in Recommender (supported feature set empty); labels used in setting titles. Tests for each, including invalid inputs → false, and avoidWhen poisoned → no disable.
3. Loading: bundled v2; cache `rules-v2-cache.json`; also read the 0.1.0 cache `rules-cache.json` as a candidate; remote v2 then remote v1 fallback; tie-break prefers v2, then remote > cache > bundled; `-Drigtune.rules.baseUrl` overrides `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/`; no request when `remoteRulesAllowed()` is false. Tests with a local HttpServer.
4. Updater: v2 output + v1 projection (the `v1` override, omission rules, same revision, REVIEW.md section (d)); tools/check_rules_v1.py; CI job `rules-v1-compat`; `rules-consistency` compares the v2 files. Python tests.
5. Generate rules-v2.json from the current knowledge (no content changes; WS-H does content). Test that both repo files parse and every settings key is allow-listed. Rerun `./gradlew test`.
6. Docs: RULES_SCHEMA.md (v2, projection, fail closed, `requires`, labels), tools/README.md.

### WS-B: Undo and 0.1.0 migration (SPEC item 3, P0). Branch `feat/undo`, worktree `rigtune-undo`. Model: opus.
**Owns:** core/history/* (journal store, UndoPlanner, LegacyImport; the records exist), core/apply/ApplyExecutor.java (journal update after a run), core/apply/PendingActions.java (remove ops by id/group; discard marks DISCARDED), client/ui/UndoScreen.java (replaces the stub, same constructor), client/undo/* (new), RigTunePreLaunch (legacy import trigger), RealController apply recording + `undoPlan`/`undo`, the ChangeRecorder install in RigTuneClient, src/test/resources/v010/* fixtures, game test `UndoGameTest`.
**Consumes:** JournalChange/JournalEntry/ChangeRecorder/UndoPlan (contracts); SettingsBridge (read-only).
**Tasks:**
1. Journal store: `config/rigtune/history.json` (formatVersion 1; last 50 entries); every read-modify-write under ApplyLock; AtomicFiles. Tests: append, cap, corrupt file (kept aside as `history.json.bad`, start fresh), newer formatVersion (read-only, never overwritten).
2. Recording: the ChangeRecorder implementation; RealController.apply records vanilla (before read immediately before writing), Sodium/any config patch ops (STAGED + opId), file ops (STAGED + opId + group). ApplyExecutor.run updates statuses by opId under the lock it already holds (OK/SKIPPED → APPLIED, ABANDONED → ABANDONED). Discard → DISCARDED. Tests incl. an executor run with a journal.
3. UndoPlanner (pure): last vs all; staged removal by group; applied reversal with a simulated mods folder; skip reasons (user changed it; file missing; target exists; RigTune's own jar). Tests: the update chain a→b→c undone to a; staged dependency group; a user-changed setting; missing .disabled file.
4. Undo execution: vanilla immediately via SettingsBridge; config and file reversal ops staged through the existing `stage()` path (a group per original group); the undo journaled with `reverts`; UndoScreen lists the plan's items with Confirm/Cancel.
5. Migration: fixtures from 0.1.0 (hand-written now from the 0.1.0 record shapes; WS-G adds captured real files later); tests for pending.json with and without ids/groups, last-apply.json, rigtune.json, rules-cache.json, helper dir; LegacyImport (runs once, excludes RigTune's own jars, skips PATCH_JSON).
6. Game test `UndoGameTest` (26.2 and 26.3): apply a vanilla + Sodium change via the controller, undo, check the values and pending.json; screenshot UndoScreen.

### WS-C: Benchmark v2 (SPEC item 6, P1). Branch `feat/benchmark-v2`, worktree `rigtune-bench`. Model: opus.
**Owns:** client/benchmark/*, core/benchmark/* (keep the BenchmarkRequest contract), client/ui/BenchmarkResultScreen.java, client/ui/BenchmarkMenuScreen.java (replaces the stub, same constructor), client/compat/DhCompat.java + IrisCompat.java (new; compileOnly APIs, loaded only when the mod is present), build.gradle/versions props compileOnly lines, RealController `startBenchmark(BenchmarkRequest)` + `latestBenchmark()`, the restore-marker hook in RigTuneClient, game test `BenchmarkGameTest`.
**Consumes:** BenchmarkRequest, BenchmarkSummary, ChangeRecorder (record "Keep" as kind `benchmark`), ClientState.benchmarkScene.
**Tasks:**
1. SPIKE FIRST: the benchmark world via `Minecraft.createWorldOpenFlows()` in a game test (create, enter, teleport, freeze time/weather, exit to title, reopen). If it can't be made reliable within this task, drop BENCHMARK_WORLD (the menu hides it) and record why in design/C.md.
2. Measurement protocol (settle, 1.5 s warm-up, 8 s × 2 phases, 2 final repeats, CV) in core with a fake frame source. Tests.
3. Coordinate descent (RD planner, then simulation distance (singleplayer only), then DH LOD, then the shader-cost report) under a deadline, with fake knobs. Tests: order, deadline, restore on every exit path.
4. DH/Iris knobs (DhCompat, IrisCompat; render thread; restore marker `config/rigtune/benchmark-restore.json`, restored at the next start if present). Unit-test the marker logic in core.
5. Measure mode + before/after pairs + gain / noise floor + `benchmarks.json` history (schema per benchmark.md §7 + version/mode/scene; cap 50). Tests.
6. UI: BenchmarkMenuScreen (Tune / Measure before / Measure after, scene choice), BenchmarkResultScreen with the chart and the gain line; "Keep" is recorded through ChangeRecorder. Game test `BenchmarkGameTest` on both versions, with screenshots.

### WS-D: Distant Horizons and Iris settings (SPEC item 7 code, P1). Branch `feat/dh-iris`, worktree `rigtune-dhiris`. Model: sonnet.
**Owns:** core/apply/TomlConfigPatcher.java + PropertiesConfigPatcher.java (replace the stubs; same signatures), a small TOML reader in core/apply (new), core/model/SettingKeys.java (allow the `dh.`/`iris.` namespaces with a safe key charset), client/probe/SettingsBridge.java (read `dh.*`/`iris.*` into the snapshot; config file paths), RealController.apply routing of `dh.`/`iris.` keys to staging (one op per key, validated like Sodium), tests.
**Consumes:** the PATCH_TOML/PATCH_PROPERTIES op types and executor dispatch (contracts); ChangeRecorder (the staged changes are recorded like Sodium's, via WS-B's generic path; if WS-B hasn't merged yet, call `ChangeRecorder.current().record(...)` directly).
**Tasks:**
1. TOML reader: sections, `key = value`, quoted vs bare tokens, comments, CRLF; dotted paths. Tests against a trimmed copy of a real DistantHorizons.toml (copy the user's file into test resources ONLY after removing anything personal; it's settings only).
2. TomlConfigPatcher: replace only the value token of an existing key in its section, preserving quoting; refuse missing keys; atomic write (AtomicFiles). `stage()` validation. Tests: quoted float stays quoted, bare int stays bare, enum string, missing key refused, idempotent (SKIPPED).
3. PropertiesConfigPatcher (java.util.Properties, ISO-8859-1). Tests.
4. SettingsBridge + SettingKeys + RealController routing. Tests for the key allowlist (no path traversal or control characters).

### WS-E: Settings screen, network switches, share report, RigTune screen buttons (SPEC items 8 and 10, P1). Branch `feat/settings-ui`, worktree `rigtune-ui`. Model: opus.
**Owns:** client/ui/RigTuneScreen.java (ALL new buttons: Settings, Copy report, Undo last, Undo all (open `new UndoScreen(this, controller, all)`), Benchmark (opens `new BenchmarkMenuScreen(this, controller)`)), client/ui/RigTuneSettingsScreen.java (new), client/compat/ModMenuIntegration.java, ClientSettings.java (beyond the contract fields), core/report/ShareReport.java (new), RealController.shareReport + Modrinth gating (fetchOnline/downloads when `modrinthAllowed()` is false: no requests, and Add/Update become advice) + the network-off header, RigTuneClient startup-toast gating + the one-time privacy toast, the README Privacy section, game test `UiGameTest`.
**Consumes:** RigTuneController defaults (undoPlan, undo(plan), settingsChanged, shareReport, startBenchmark(request), latestBenchmark), ClientSettings (contracts).
**Tasks:**
1. ShareReport (core, pure): Markdown per SPEC item 10, ≤2000 characters with "(N more)", no paths/user names. Tests.
2. Settings persistence + switches: defaults for a 0.1.0 rigtune.json (test); Modrinth gating in RealController (test with a fake client that fails if called).
3. RigTuneSettingsScreen + ModMenuIntegration (Mod Menu opens settings; settings links to the main screen).
4. RigTuneScreen buttons (layout must still fit 854×480 at GUI scale 2 and 1280×720 at scales 2 and 3, like the 0.1 tests). Copy report uses the version-correct clipboard API (verify for 26.3 in api-diff/javap).
5. Game test `UiGameTest` on both versions: open settings, toggle network off, check the header; press Copy report and read the clipboard; screenshots of every screen at the three sizes.

### WS-F: Modrinth publishing (SPEC item 4, P0). Branch `feat/modrinth`, worktree `rigtune-modrinth`. Model: sonnet.
**Owns:** build.gradle Minotaur block (per version: version_number `<ver>+mc<mc>`, game_versions, loaders fabric, dependency fabric-api, changelog from CHANGELOG.md), .github/workflows/release.yml (GitHub release, then one Modrinth publish per version as independent steps with `if: always()` semantics; skipped when the secret is absent), tools/modrinth_project.py (+ tests with a fake HTTP layer), docs/modrinth/ (body.md adapted from the README with the disclosure paragraph, gallery list with titles/descriptions), CHANGELOG.md skeleton.
**Authorised by the user (2026-09-25):** tools/modrinth_project.py reads `MODRINTH_TOKEN` from the Windows USER environment itself (`[Environment]::GetEnvironmentVariable("MODRINTH_TOKEN","User")` via a subprocess, or `os.environ` if present). The token never appears on a command line or in output. The script talks only to `https://api.modrinth.com`. A PreToolUse hook forbids curl with Authorization headers: don't use curl for authenticated calls, and don't try to get around the hook.
**Tasks:**
1. tools/modrinth_project.py with subcommands: `create` (draft project: slug rigtune, title RigTune, summary, categories optimization + additional utility, client required, server unsupported, MIT, source/issues URLs, body from docs/modrinth/body.md, icon), `gallery` (upload the selected screenshots with title/description), `sync-body`, `upload-version` (for the v0.1.0 jar: `0.1.0`, game_versions ["26.2"], loaders ["fabric"], fabric-api required, changelog), `submit` (move the draft to review; find the verified API call; if impossible, print the web-UI step), and `status`. Idempotent: `create` is a no-op if the project exists. Dry-run mode prints the payloads without the token. Unit tests with a fake transport.
2. Run it for real: create the draft, gallery, upload the EXACT released v0.1.0 jar (`gh release download v0.1.0`, sha256 8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950), then submit for review once body + gallery + v0.1.0 are in place. Record project id, URLs and status in docs/v0.2/design/F.md.
3. Minotaur in build.gradle for both versions; `./gradlew modrinth` wiring (dry run: `-PmodrinthDryRun` or Minotaur's debug mode, verified); release.yml as in the SPEC; a CI-safe no-token path.
4. Body/listing copy: accurate, no FPS promises, the disclosure paragraph, and alt text for the gallery.

### WS-G: Self-update E2E harness (SPEC item 5, P0). Branch `feat/self-update-e2e`, worktree `rigtune-e2e`. Model: opus.
**Owns:** tools/e2e/ (fake Modrinth HTTPS server, cert/truststore generation, hosts file, driver scripts), any driver mod sources it needs (their own source set or a tools subproject; never in the shipped jar), `-Drigtune.modrinth.baseUrl` in core/modrinth/HttpModrinthClient.java (0.2 only), docs/smoke/self-update/, captured 0.1.0 fixture files handed to WS-B's fixture folder (`src/test/resources/v010/captured/`).
**Tasks:**
1. The fake Modrinth server (Python stdlib `http.server` + `ssl`, or Java): the endpoints v0.1.0 calls (read HttpModrinthClient/OnlineDataFetcher at tag v0.1.0), serving a given 0.2.0 jar with its real hashes. Unit tests for its responses.
2. Point the UNMODIFIED released v0.1.0 jar at it with JVM properties only: `-Djdk.net.hosts.file` (api.modrinth.com, cdn.modrinth.com → 127.0.0.1) + `-Djavax.net.ssl.trustStore`. Prove it with a tiny Java program first (no game).
3. The driver: a production client run (like runProductionSmoke) in a scratch instance COPY with fabric-api 26.2 + rigtune-0.1.0.jar + a driver that reaches the report, applies the "Update RigTune" recommendation, and quits (0.1.0 classes: read the v0.1.0 source at the tag; the driver must compile against the v0.1.0 jar, not the current sources).
4. Assertions per SPEC item 5, then launch the built 0.2.0 on the same copy: goal kept, apply toast, history legacy import; capture the 0.1.0 files into WS-B's fixture folder.
5. The final run happens in Phase 5 against the merged integration build; in Wave A, prove the harness with any build of the current branch as the "update" jar.

## Wave B (after WS-A merges)

### WS-H: Knowledge (SPEC items 9, 7 knowledge, AC2.3). Branch `feat/knowledge`, worktree `rigtune-knowledge`. Model: sonnet.
**Owns:** rules/source/knowledge.json, rules/rules-v1.json, rules/rules-v2.json + bundled copy (regenerated), rules/REVIEW.md, new scenario test class `KnowledgeV2ScenarioTest`.
**Consumes:** WS-A's schema v2 and projection; WS-D's `dh.`/`iris.` allowlist (if WS-D hasn't merged, scenario tests still work with snapshot maps that contain those keys, provided SettingKeys allows them, so merge WS-D first when possible).
**Tasks:** the triage (triage.md §2 reviewIgnore, LambDynamicLights unticked avoid); Nvidium via `gpuModelMatches` with a `v1` override keeping the old gate; RenderScale for a mid-tier GPU at ≥1440p via `displayPixelsAtLeast` (v2 only); Ixeris reason for 26.3 via `mcVersionRange`; DH/Iris settings, clamps and advice from dh-iris.md; Sodium `settingLabels`. Regenerate (network), then `./gradlew test` (both versions), check_rules_v1, and the scenario tests from SPEC AC9.2. REVIEW.md (a) empty.

## Phase 5 (coordinator + WS-G): verification on the merged integration branch
Unit + game tests on 26.2 and 26.3 (screenshots reviewed by the coordinator); runProductionSmoke 26.2 with a COPY of the user's mods (`scratchpad/usermods`), and a 26.3 representative set downloaded from Modrinth; the self-update E2E final run.

## Self-review against the SPEC
- Item 1 → Phase 3 (in progress). Item 2 → WS-A (+ WS-H content). Item 3 → WS-B (+ WS-G fixtures). Item 4 → WS-F (+ the release in Phase 7). Item 5 → WS-G (+ Phase 5). Item 6 → WS-C. Item 7 → WS-D (code) + WS-H (knowledge) + WS-C (knobs). Item 8 → WS-E. Item 9 → WS-H. Item 10 → WS-E. Items 11–13 (P2) → after Phase 5 if time allows: CI game tests (xvfb), launcher-aware RAM advice, localisation notes.
- Contract names used above match the committed contract files (cae06b8).

---

## Plan-review fixes by workstream (MANDATORY, part of each workstream's tasks)
Read docs/v0.2/plan-review.md for the evidence behind each ID, and the "Amendments from the plan review" section at the end of SPEC.md. Updated contracts: `ChangeRecorder.record(entryId, kind, changes)`, `JournalChange.resultFile`, `UndoPlan.Item.action`, `RigTuneController.undo(UndoPlan)` and `settingsChanged()`, `ClientSettings` (config/rigtune/settings.json; ClientState keeps only goal and lastShownApply), and `ConfigTargets` (apply() already routes `sodium.`/`dh.`/`iris.` keys generically).

- **WS-A:**
  - H1: three-valued evaluation and the value vocabularies.
  - H2: per-field projection, per-type v1 whitelists, updater errors for nulls and unhandled fields, and the pinned v0.1.0 differential test.
  - H3: `avoidSelected`, honoured in `Recommender.avoided()`.
  - M14: mod versions in EvalContext, and `settingsChanged()` in RealController (reload the rules, then rescan).
  - M1/L1: document both rules in RULES_SCHEMA.md.
- **WS-B:**
  - H4: one entry id per Apply, passed through to the downloads.
  - H5: record in stage() after the merge (`Merged` id mapping and replaced ids; `discard` returns the dropped ids; `before` accounts for already-staged ops); startup reconciliation in preLaunch.
  - M4: ApplyLock reentrant within the JVM.
  - M5: journal code in the helper is helper-classpath-safe and best-effort, plus a helper-classpath test.
  - M6: `OpResult.resultPath`, stored in `resultFile`.
  - M7: diff the whole vanilla snapshot.
  - M8: `undo(plan)` re-checks each item.
  - M9: the dependency and duplicate check in UndoPlanner.
  - L2, L3.
  - WS-B also owns recording for every ConfigTargets namespace: `before` comes from `Target.reader()`. WS-D does no recording.
- **WS-C:**
  - M16: tune RD and SD only (SD with the cheap protocol); DH (`renderingEnabled` off) and shaders-off as cost reports that are always restored; a 5 min deadline.
  - M-risk: DH checks run from the title screen or a non-harness launch.
  - Cut order: benchmark-world first.
- **WS-D:**
  - Don't edit RealController. apply() already routes through ConfigTargets.
  - Implement `TomlConfigPatcher`/`PropertiesConfigPatcher` `readValues`, `stage` and `patchFile`, the SettingKeys allowlist, and SettingsBridge snapshot reading through `ConfigTargets.all(configDir)` readers.
  - L11: don't parse the TOML/properties on every render-thread `read()`. Cache the readings by file modification time.
- **WS-E:**
  - Use ClientSettings, not ClientState. The switches are `networkEnabled`, `remoteRules`, `modrinth` (lookups, update checks AND downloads) and `startupToast`; `benchmarkScene` holds the Scene enum names.
  - The settings screen calls `controller.settingsChanged()` after a change.
- **WS-F:**
  - M15: the user approved creating the project, uploading v0.1.0 and submitting for review. Upload v0.1.0 before anything 0.2.0.
  - L7: until 0.2.0 is uploaded, the body describes only 0.1.0's behaviour. Keep the 0.2 body in docs/modrinth/body-0.2.md for the release.
  - M11: the release job builds once and uploads byte-identical jars to GitHub and Modrinth (compare SHA-256s in the workflow).
- **WS-G:**
  - M10: don't use Loom's production run task as it is; only the instance's mods/ may load RigTune.
  - M12: also run 0.2.0-dev → a newer 0.2.0-dev build.
  - L5: 0.2 downloads only from `https://cdn.modrinth.com/`, unless the test base-URL property is set.
  - L4: the details in the review.
  - Phase 5 adds the end-to-end undo after a restart (M14).
- **Hotspots, additions:**
  - README.md: Phase 3 (Build from source), WS-E (Privacy), WS-F (reads it for the body only).
  - build.gradle / settings.gradle: WS-G's driver source set or subproject, as its own block.
