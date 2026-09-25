# WS-P: dry-run preview Implementation Plan

> **For agentic workers:** executed inline by the WS-P agent (superpowers:test-driven-development per task). Steps use checkbox (`- [ ]`) syntax.

**Goal:** A **Preview** button next to Apply opens a screen listing exactly what Apply would do for the ticked items (settings written now, config files patched at the next restart, jars downloaded into `mods/`, jars renamed to `.disabled`), writing nothing and downloading nothing.

**Architecture:** The model is built in pure core (`core/preview/`) from the same inputs and the same code Apply uses: RealController.apply's partition of the selection (mirrored one-to-one), the ConfigTargets stagers (`SodiumConfigPatcher`/`TomlConfigPatcher`/`PropertiesConfigPatcher.stage`, which only read), `ApplyExecutor.disabledTarget` for the `.disabled` name, and the real `DownloadPlanner` + `DependencyResolver` (read-only) run through a new `core/modrinth/DryRunPlanner` whose fetcher returns a path that is never created and whose client refuses `download()`. The client adds one controller method (`preview`), one footer button, and `PreviewScreen`.

**Tech Stack:** Java 25, Fabric (Mojang names), Stonecutter (26.2 active), Gson, JUnit 5, Fabric client game tests.

**Spec:** docs/v0.3/SPEC.md item 13 (AC13.1, AC13.2) and the amendments X-M2 (footer, 640x480@2 screenshots) and X-L3 (WS-P after or beside WS-G).

## Global Constraints
- Branch `feat/preview`, worktree `C:/Dev/Worktrees/rigtune-preview`; never commit to main or feat/v0.3.0; no force push; merge (never rebase) `origin/feat/v0.3.0`.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; committed Stonecutter version 26.2.
- `core/` has no Minecraft imports. UI text only from en_us.json, keys under `rigtune.preview.*`, inserted in alphabetical position (between `rigtune.history.*` and `rigtune.status.*`), never appended at the end.
- READ-ONLY for WS-P (WS-G owns them): DownloadPlanner, DependencyResolver, Recommender, UndoPlanner, ShareReport. Calling them is fine; a new class in their package that calls a package-private constructor is not a modification.
- Hotspots: RealController gets one method (`preview`) under a `// v0.3 (WS-P)` comment; RigTuneController one `default` method under `// v0.3 (WS-P)`; RigTuneScreen one footer line right after Apply (plus a helper, a field and one line keeping its active state in step with Apply). `ApplyExecutor.disabledTarget` becomes public (visibility only; helper-safe).
- Nothing written, nothing downloaded: the preview never creates, renames, deletes or patches a file, and never calls `ModrinthClient.download`. Modrinth metadata lookups (the resolver) happen only when `settings.modrinthAllowed()`.
- Local game-test runs are optional (CI covers them); if run, only under the lock protocol (PLAN Global Constraints).
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`.

## Footer decision (AC13.2)
RigTuneScreen's footer fits `perRow = clamp((column + 4) / 92)` buttons per row, column = min(width - 32, 480). With Preview added (8 buttons, 9 with Discard pending):
- 640x480@2 (320 wide, column 288): 3 per row, 93 px, 3 rows before and after (no extra row).
- 854x480@2 (427, column 395): 4 per row, 95 px; 2 rows (3 rows only while Discard pending is also shown: 120 px).
- 1280x720@2 and wider (column 480): 4 per row at 117 px, or 5 per row at 92 px with Discard.
Every width stays >= 92 px (ReportGameTest needs >= 91 for "Report a problem"), so Preview is an ordinary footer button right after Apply, not a split button (a split of a 93 px slot would leave about 45 px, too narrow for "Apply (12)"). PreviewGameTest asserts this at all three sizes.

---

### Task 1: Core preview of settings, config patches and disables

**Files:**
- Create: `src/main/java/io/github/chaotix345/rigtune/core/preview/ApplyPreview.java`
- Create: `src/main/java/io/github/chaotix345/rigtune/core/preview/PreviewPlanner.java`
- Modify: `src/main/java/io/github/chaotix345/rigtune/core/apply/ApplyExecutor.java` (`disabledTarget` public)
- Test: `src/test/java/io/github/chaotix345/rigtune/core/preview/PreviewPlannerTest.java`

**Interfaces:**
- Produces:
  - `record ApplyPreview(List<Setting> now, List<Setting> atRestart, List<Download> downloads, List<Disable> disables, List<Skipped> skipped, boolean resolved)`; `record Setting(String recommendationId, Path file, String key, @Nullable String oldValue, String newValue)`; `record Download(String recommendationId, String title, @Nullable String fileName, @Nullable Path target, boolean dependency)` (fileName null = "a file from Modrinth", not looked up); `record Disable(String recommendationId, String title, Path file, Path disabledAs)`; `enum Reason {NOTHING_TO_APPLY, NOT_CHANGEABLE, UNKNOWN_SETTING, UNCHANGED, REFUSED, OUTSIDE_MODS, DOWNLOAD_FAILED, NO_NEW_FILES}`; `record Skipped(String recommendationId, String title, Reason reason, @Nullable String detail)`; `ApplyPreview.EMPTY`, `isEmpty()`, `Set<Path> filesNow()`, `Set<Path> filesAtRestart()` (config files, download targets, each disabled jar and its `.disabled` name), `static String relative(Path base, Path file)` (forward slashes; the file name alone outside base).
  - `PreviewPlanner(Path optionsFile, Map<String,String> vanillaNow, List<ConfigFile> configFiles, Path modsDir, @Nullable DownloadInputs downloads)`; `record ConfigFile(String prefix, Path file, BiFunction<Path, Map<String,String>, SodiumConfigPatcher.Staged> stager, Function<Path, Map<String,String>> reader)`; `ApplyPreview preview(List<Recommendation> selected)`.
- Consumes: `SettingKeys.changeable/safeValue`, the three `stage`/`readValues` functions, `SafeFileNames.isDirectChild`, `ApplyExecutor.disabledTarget`.

Partition (mirrors RealController.apply, in the same order of cases): `SetSetting` with `vanilla.` → now; `SetSetting` whose prefix matches a ConfigFile → at restart through its stager; `DisableMod` directly in modsDir → disable (outside → OUTSIDE_MODS); `AddMod`/`UpdateMod` → downloads (Task 2); anything else → NOTHING_TO_APPLY.

- [ ] Step 1: failing tests: `vanillaSettingsAreWrittenNowToOptionsTxt` (renderDistance 12 → 8 from vanillaNow, key without prefix, file = optionsFile), `aVanillaSettingRigTuneDoesntChangeIsSkipped` (vanilla.fov → NOT_CHANGEABLE), `anUnknownVanillaSettingIsSkipped`, `aValueAlreadySetIsSkipped` (UNCHANGED), `sodiumDhAndIrisKeysArePatchedAtRestart` (real stagers over temp files; old from the file), `aValueTheFileRefusesIsSkippedWithTheReason` (sodium number ← "fast" → REFUSED, detail from the patcher), `aDisableRenamesTheJarAtRestart` (file.jar → file.jar.disabled, or `.disabled.1` when taken), `aDisableOutsideModsIsSkipped`, `anAdviceIsSkipped` (Action.None → NOTHING_TO_APPLY), `thePreviewWritesNothing` (sha-256 tree of the instance before == after), `relativeUsesForwardSlashes`.
- [ ] Step 2: run `./gradlew :26.2:test --tests '*PreviewPlannerTest'` → compile failure.
- [ ] Step 3: implement ApplyPreview + PreviewPlanner (settings/config/disable parts); `disabledTarget` public.
- [ ] Step 4: tests pass.
- [ ] Step 5: commit `feat(preview): core ApplyPreview for settings, config patches and disables (SPEC 13)`.

### Task 2: Downloads through the real planner, dry

**Files:**
- Create: `src/main/java/io/github/chaotix345/rigtune/core/modrinth/DryRunPlanner.java`
- Create: `src/main/java/io/github/chaotix345/rigtune/core/preview/DownloadInputs.java`
- Create: `src/main/java/io/github/chaotix345/rigtune/core/preview/LookupOnlyClient.java`
- Modify: `src/main/java/io/github/chaotix345/rigtune/core/preview/PreviewPlanner.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/preview/PreviewDownloadsTest.java`, `src/test/java/io/github/chaotix345/rigtune/core/preview/PreviewFakeModrinth.java`

**Interfaces:**
- Produces: `DryRunPlanner.plan(DependencyResolver resolver, Path modsDir, BiPredicate<String,String> conflicts, Map<String, ModrinthVersion> updateVersions, Map<String,String> modIdsByFile, List<Recommendation> recs, Set<String> installedProjects, Set<String> loadedIds, Map<String,String> stagedJars) -> DownloadPlanner.Result` (uses DownloadPlanner's package-private constructor: the fetcher validates the name with `SafeFileNames.resolveJar` and returns a sibling path that is never created; the mod id comes from `modIdsByFile` (an update's jar is its mod) or a unique `rigtune-preview:<n>` id). `record DownloadInputs(ModrinthClient client, boolean lookups, String loader, String gameVersion, Map<String, ModrinthVersion> installedVersions, Map<String, ModrinthVersion> updateVersions, Set<String> installedProjects, Set<String> loadedIds, Map<String,String> stagedJars, BiPredicate<String,String> conflicts)` — the same values RealController.download builds. `LookupOnlyClient(ModrinthClient)`: delegates the lookups, throws from `download`, remembers `latestVersion` answers (to tell an addition's own file from its dependencies).
- Consumes: DownloadPlanner.Result (`ops`, `ids`, `errors`, `opIds`), DependencyResolver 4-arg constructor with installed versions.

Mapping: each ENABLE_FILE op → Download (target = `to`, attributed to the first recommendation whose `opIds` holds it; `dependency` when its file isn't the recommendation's own); each DISABLE_FILE op → Disable; each failed recommendation (in the planner's order: updates, then the rest) → DOWNLOAD_FAILED with the planner's message minus its `title: ` prefix; a planned recommendation with no file of its own → NO_NEW_FILES. With lookups off, updates still go through the planner (local data only) and each addition is one Download with fileName null, `resolved = false`.

- [ ] Step 1: failing tests: `anUpdateDownloadsItsFileAndDisablesTheOldJar`, `anAdditionListsItsFileAndItsRequiredDependency` (dependency flagged; fake `download` never called; `latestVersion` calls only), `anAdditionWhoseFileIsAlreadyThereHasNoNewFiles`, `aRefusedDownloadIsSkippedWithThePlannersReason` (update target taken), `twoConflictingAdditionsRefuseTheLaterOne`, `withLookupsOffAnAdditionIsAFileFromModrinthAndNothingIsAskedOfModrinth`, `downloadsWriteNothing` (tree hash unchanged, no `.rigtune-pending`).
- [ ] Step 2: run → fail.
- [ ] Step 3: implement.
- [ ] Step 4: pass.
- [ ] Step 5: commit `feat(preview): downloads through the real DownloadPlanner and resolver, dry (SPEC 13)`.

### Task 3: AC13.1 differential test

**Files:**
- Test: `src/test/java/io/github/chaotix345/rigtune/client/PreviewDifferentialTest.java`

A temp instance (`mods/` with sodium-0.5.jar (id sodium) and old-mod.jar; `config/` with sodium-options.json, DistantHorizons.toml, iris.properties; options.txt). Selection: vanilla renderDistance, a Sodium key, a DH key, an update (sodium-0.5 → sodium-0.6), a disable (old-mod.jar), an addition (lithium) with a required dependency (fabric-api) through a fake Modrinth client whose `download` writes a real test jar. Then:
1. `preview = planner.preview(selection)`; the tree is unchanged and the fake saw no download.
2. The real staging, as RealController.apply does it: the ConfigTargets stagers → ops, `Op.disableFile`, `Staging.stage(...)` (client/undo, real lock and journal), `DownloadPlanner` (public constructor, fetch = resolveJar + client.download) → `Staging.stage(result.ops())`.
3. Assert: the files named by pending.json's ops (ENABLE `to`, DISABLE `path` + its `.disabled`, PATCH `path`) == `preview.filesAtRestart()`.
4. Run `new ApplyExecutor(2, 1).run(plan, pending)` (the helper's executor) and diff the instance tree (excluding `config/rigtune/`): added ∪ removed ∪ changed == `preview.filesAtRestart()`; `preview.filesNow()` == {options.txt} with exactly the selected vanilla keys (the vanilla write needs Minecraft's Options: PreviewGameTest checks it in game).

- [ ] Step 1: write the test; run → it must pass against Tasks 1-2 (a differential test, not new behaviour); break the mapping deliberately once (e.g. drop the `.disabled` name) to see it fail, then restore.
- [ ] Step 2: commit `test(preview): AC13.1 differential test, preview vs real staging + helper in a temp instance`.

### Task 4: Client glue: controller method, PreviewScreen, footer button, lang keys

**Files:**
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneController.java` (`default ApplyPreview preview(List<Recommendation> selected) { return ApplyPreview.EMPTY; }` under `// v0.3 (WS-P)`)
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/RealController.java` (`preview`: vanilla values read on the render thread, `ConfigTargets.all(configDir)` → ConfigFile, the same inputs as `download()`, options.txt = game dir)
- Create: `src/client/java/io/github/chaotix345/rigtune/client/ui/PreviewScreen.java`
- Modify: `src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneScreen.java` (one footer line after Apply + `previewButton()` helper + active state)
- Modify: `src/main/resources/assets/rigtune/lang/en_us.json` (`rigtune.preview.*`)

PreviewScreen: title, subtitle ("Nothing has been changed or downloaded."), a scrolling list (HistoryScreen's pattern) of rows: section headings (Written now / Changed at the next restart / Downloaded now, added to mods/ at the next restart / Renamed to .disabled at the next restart / Not changed), file rows (paths relative to the game dir), setting rows `key: old → new`, download and disable rows, skipped rows with the reason; a note under downloads; loading / empty / error messages; one Done button back to the RigTune screen (its ticks kept). The preview runs on `Probes.EXECUTOR`. Accessors for the game test: `preview()`, `loading()`, `rowText()`.

- [ ] Step 1: implement; `./gradlew build` (both versions compile, unit tests green).
- [ ] Step 2: commit `feat(preview): Preview button next to Apply and the preview screen (SPEC 13)`.

### Task 5: PreviewGameTest (AC13.2)

**Files:**
- Create: `src/gametest/java/io/github/chaotix345/rigtune/gametest/PreviewGameTest.java`
- Modify: `src/gametest/resources/fabric.mod.json` (one entrypoint line, last)

Steps in the test (returns early under `rigtune.smoke`):
1. The real RigTune screen at 640x480@2, 854x480@2, 1280x720@2: Preview is right after Apply in the same row, same active state, every footer button's label fits, width >= 91, inside the screen, no overlaps; screenshot `preview-footer-<size>`.
2. A canned preview (every section, long DH keys and file names, a dependency, a skipped item) on PreviewScreen at the three sizes: rows inside the list, no widget overlaps, text clipped/wrapped inside the column; screenshot `preview-<size>`.
3. The real controller: press Preview with the report's default ticks; wait until loaded; options.txt, `config/**` and `mods/**` byte-identical before/after and no `.rigtune-pending` file; screenshot `preview-real`.
4. Vanilla end-to-end: after `options.save()` as a baseline, preview a renderDistance change → one Setting (options.txt, renderDistance, current → new); apply it through the real controller; the keys that changed in options.txt == {renderDistance}; restore.

- [ ] Step 1: write; push; CI green on every leg; download the screenshot artifacts and look at them.
- [ ] Step 2: commit `test(preview): PreviewGameTest screenshots at 3 sizes, footer fit, real preview writes nothing (AC13.2)`.

### Task 6: Review, design notes, merge, verify
- [ ] Dispatch a code-reviewer subagent on `git diff feat/v0.3.0...HEAD` (hand-back to the coordinator); keep working; fix high/medium findings.
- [ ] Write `docs/v0.3/design/ws-p.md` (design, the footer decision, known limits, evidence).
- [ ] Merge `origin/feat/v0.3.0` (keep both sides), `./gradlew build`, push, CI green on every job; record unit test counts per version.
