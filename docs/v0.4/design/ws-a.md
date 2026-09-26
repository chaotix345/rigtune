# WS-A design notes: deferred defects + external-review UI fixes (SPEC 2a-2g, 2j, 2m, 2n)

Branch `fix/v04-deferred`. Plan: docs/v0.4/plans/ws-a.md. Every fix has a test that failed first (compile red for new
APIs, a behavioural red for 2e, 2f and 2n). Evidence: CI runs on the branch (build.yml, every leg), the screenshots
named below, docs/v0.4/verification/ws-a/.

## 2d: the incompatibility checks see what earlier Applies staged (first functional fix)
- `DownloadPlanner` sets `projectId`/`versionId` on every ENABLE_FILE op it makes (addition, dependency, update;
  `Op.withProjectId`/`withVersionId` from WS-K; every copy method already carries them, PendingOpModrinthIdsTest).
- New `core/modrinth/StagedProjects(projects, projectByVersion)`: `fold(PendingActions)` (ENABLE_FILE ops with a
  projectId; DISABLE_FILE, patches and 0.3.0-written ops count for nothing) and `read(pendingFile)` = load, then
  `relocated(InstanceDirs.modsDirOf, configDirOf)` (the view the helper and Staging use), unreadable = NONE.
- `DependencyResolver.withStaged(StagedProjects)` (amendment A-H1): a separate set read only by `refuseIncompatible`
  (so `resolve` and `checkUpdate`), never `seen`/installedProjects. Forward: a new version declaring a staged project,
  or a staged version id (known from pending.json itself, no Modrinth call), incompatible -> refused with the new
  `rigtune.download.incompatible_staged` ("... %s, which is waiting for a restart"). Reverse (A-M1): the staged
  versions are fetched once per resolver (= one plan run) through the new `ModrinthClient.versions(ids)` (`GET
  /v2/versions?ids=[...]`, checked in docs.modrinth.com "Get multiple versions"; Http, Gated and LookupOnly implement
  it; the default throws) and checked like the installed mods' own declarations
  (`rigtune.download.staged_incompatible`). A failed lookup (Modrinth off, network off, a fake server without the
  endpoint) is logged at info without a stack trace and leaves the forward check only.
- A staged version of the project being replaced (an update) or of the same project as the version checked is skipped,
  as for installed mods; `updated`/`needed` apply as for installed versions (a batch update replaces a staged enable
  of the same mod at merge).
- RealController: `download()` builds its resolver `.withStaged(StagedProjects.read(pendingFile))`; `preview()` passes
  the same through a new `DownloadInputs` component (old constructor kept) to `PreviewDownloads`. Those are the only
  RealController edits for 2d.
- AC2d.4: a dependant of a staged mod still resolves it, its op repeats the staged one and `PendingActions.merge`
  joins the groups; `remove` of the first Apply's op drops both (DownloadPlannerTest).
- Residuals (documented, safe direction): staged DISABLE_FILE ops aren't folded; with Modrinth off only the forward
  check runs; WS-H's fake Modrinth has no `/v2/versions`, so e2e runs exercise the forward check only.

## 2n: a second Undo last on a staged key
- Reproduced WS-H's dev-undo-after-restart-040-profiles failure as UndoPlannerTest first ("You changed it since (it's
  now 4)"). Fix: in `planSettings` a staged (non-immediate) key's current value is `afterRestart`: the last
  still-pending op in pending.json that sets it, in the helper's order, leaving out ops this plan discards
  (`Builder.discardOpIds`, filled by `planStaged` before `planSettings`), else the file's value. New
  `UndoPlanner.State.keyOf(op, keyInFile)` (default null = the old behaviour); GameState maps it with
  `Staging.targetOf` (the same file -> namespace mapping as journaling). Undo all from the same point, an external
  change (still SKIPs) and a discarded staged change are tested. The released 0.3.0 keeps the bug (AC2n.2 is WS-H's
  Phase 5 run).

## 2e: two ticked items that can't go in together
- `DownloadPlanner.refusedTogether` before the loop: ticked updates pairwise on their new versions
  (`DependencyResolver.incompatible`, either side declaring it), ticked additions pairwise on the rules' conflicts and
  on their own root versions (looked up through the resolver's new per-run answer cache, so nothing is asked twice).
  Both sides are refused before any download, each naming the other; the rest proceed. `rigtune.download.conflicts`
  now reads "it conflicts with %s, which is ticked too; tick only one of them" (the old "later one fails" check and
  `Batch.added` are gone). A dependency-level Modrinth incompatibility between two additions is still found while
  resolving and fails the later one (documented). PreviewDifferentialTest and PreviewDownloadsTest follow.

## 2f
- ApplyExecutor: "Gave up after N tries: ..." (:499) and "Gave up after 3 restarts: ..." (:186); a test reads the
  helper's sources for "attempt(s)"/"failed attempts". The v0.1.0 real-instance fixture keeps its wording (data).

## 2c: mod names
- `ModJars.nameOf` (fabric.mod.json `name`, modIdOf's null-on-failure contract, logs at debug) through
  `ModJars.sanitizeName` (P-L1: no U+00A7, no Cc/Cf/Cs/Co/Cn/Zl/Zp, whitespace runs as one space, <= 64 code points,
  empty = null). `readModId`/`readField` still log nothing (helper-safe). `StagedChanges.of(..., modNameOf, ...)`
  (old overload kept) reads `op.from()` for an enable and `op.path()` for a disable; Staging passes `ModJars::nameOf`;
  `LegacyImport.entry` gains a best-effort overload. `HistoryModel.Change.name` (old constructor kept,
  `shownName()`), sanitised again on display (history.json is the player's file); an update shows the new jar's name.

## 2a
- `HistoryModel.anyUndoable(View)`; HistoryScreen's Undo last / Undo all `active = !loading && anyUndoable`.

## 2b: Preview labels
- Coordinator-approved C4 addition: `RigTuneController.settingLabels()` (default `Labels.RAW`, in its own block);
  RealController returns `Labels.of(new GameState(...))` (History's labels); its private helper was renamed
  `rulesSettingLabels()` to free the name. WS-P's Preview confirm overload reuses it for imported profiles.
- PreviewScreen's row rendering only: `settingRow(setting, prefix, labels)`; the file picks the namespace
  (options.txt -> `vanilla.`, `ConfigTargets` -> sodium/dh/iris); an unknown file keeps the raw key. Vanilla rows use
  the game's captions, so they match the main list's own titles (PreviewGameTest compares them).

## 2g
- `org.prismlauncher.instance.name` alone -> PRISM; `multimc.instance.title` or INST_ID/INST_NAME -> new
  `Launcher.MULTIMC` (PolyMC sets only INST_*, launcher-steps.md finding 6, so it is named MultiMC); brand
  `LauncherSignals.GDLAUNCHER_BRAND` -> new `Launcher.GDLAUNCHER`. Instance-file detection is unchanged (MultiMC
  instance files without any signal still read as Prism; the signals always come first).
- Strings from docs/research/v0.4/launcher-steps.md: names, `steps.multimc` (MultiMC 0.6.16 labels), `steps.gdlauncher`
  (Carbon develop 35533d7), `steps.curseforge.pack` ("choose Custom RAM Allocation"), `steps.official` (Mojang's
  path, "then Save"). After WS-J merged (coordinator): `jvm_steps.multimc` and `jvm_steps.gdlauncher` from the same
  file, and `LauncherInfo.typedXmxWins` covers GDLauncher too (Carbon pushes its -Xmx, then appends the typed arguments
  verbatim), so its ram-* advice gets WS-J's typed -Xmx note like the Modrinth App.

## 2j: no bottleneck language
- `TierBasis.lowest(TierResult)` (every tied component, gpu/cpu/mem order); `CpuClassifier.classifyDetailed` (classify
  delegates to it); Recommender fills `Report.tierBasis` at the Report (same classifiers as WS-P's `context()`).
- Header: `rigtune.header.tier_estimate` ("Estimated tier N/5 · lowest estimated component: CPU"); the badge's one
  tooltip (`rigtune.header.tier_basis.*`: table match / fallback estimate from N threads [at X GHz when the clock
  lowered it] / thread count unknown; memory tier with the heap) is the `List<Component>` from
  `RigTuneScreen.tierTooltip(Report)`, passed as `extraLines` to WS-B's `BenchmarkTrendLines.badgeTooltip`, which adds
  the last-benchmark line: one call, one tooltip, wherever the badge is drawn (title row or the header line). Over the
  badge the clipped header line's own tooltip is suppressed (the first tooltip set in a frame wins; seen in CI). The
  hook now splits the joined tooltip into lines (`font.split`, 240 px): as one Component the line breaks were drawn as
  a glyph on a single line (CI screenshot ui-tier-tooltip-640x480-scale2 on run 36227160333). The longer badge no longer fits the title row at the 3 standard sizes, so it leads the
  display/rules line, which is clipped at 640x480@2 (full text on hover, as before).
- ShareReport: "- Estimated tier N/5 · lowest estimated component: GPU, CPU · goal ..."; ProductionSmoke's log line
  reworded. `rigtune.limit.*` stay as the component names.
- WordingTest (X4): en_us.json values and share reports over three hardware profiles x every goal (bundled rules) never
  say "limited by"/"bottleneck"; `rigtune.stutter.*`, `rigtune.benchmark.trend.*`, `rigtune.awareness.*` and
  `rigtune.startup.*` never "caused"/"because of".

## 2m
- The checkbox's message is set after `build()` to `rigtune.screen.recommendation` ("%s (%s impact)"): vanilla's
  Checkbox draws the MultiLineTextWidget made at construction (empty) and narrates `getMessage()` (javap on 26.2 and
  26.3), so the row isn't drawn twice. CI narration with the render distance row focused: "Render Distance: 16 → 12
  (High impact) button. Selected list row 12 out of 16. Press Enter to uncheck". `ScreenNarrationCollector.update`
  takes a `NarrationTrigger` on 26.3 (Stonecutter block in UiGameTest).
- X-L2 pixel check: the first checkbox row of ui-stub-pending-1280x720-scale2, run 36217772298 (feat/v0.4.0 right after
  WS-K) vs run 36225362110: 0 of 56,640 pixels differ (crops in docs/v0.4/verification/ws-a/).

## "Written by 0.4" set and the released-jar harness
- `src/test/resources/v040-written/ws-a/{pending.json,history.json}` are written by V040WrittenWsaTest through the real
  planner, staging, helper (ApplyExecutor) and journal (an applied disable with modName, then a staged Modrinth addition
  with projectId/versionId and modName), normalised to WS-H's convention; the test fails if the committed files drift
  (delete and rerun to regenerate) and has the pinned 0.1.0/0.3.0 readers read them.
- `tools/e2e/compat030.py` (origin/test/e2e-v04) on this set + WS-H's placeholders, released
  rigtune-0.3.0+mc26.2.jar (sha256 5717f65c...): RESULT PASS, all 9 checks (Journal state OK 3/3 entries, PendingActions
  1/1 op with type, id and mod id, no file changed).

## Self-review
A code-reviewer subagent: 0 high, 0 medium, 8 low. Fixed: a one-sided `conflicts` predicate now refuses both additions
(either direction counts); the name sanitiser turns tabs/newlines into a space and drops a formatting code's letter with
its `§`; `ModJars.nameOf` also catches RuntimeException (a cosmetic name never aborts staging or the legacy import);
V040WrittenWsaTest fails in CI instead of writing a missing set. Left as documented residuals (safe direction or out of
2e's scope): a dependency-level Modrinth incompatibility between two additions still fails the later one; a staged
version's declarations still count when the same batch re-resolves that project to a newer version (over-blocking);
`checkUpdate` against earlier batch versions is now reached only after the pairwise pre-check (no test of that branch
alone). The badge hover width is already clipped to the column.
