# WS-A plan: deferred defects + external-review UI fixes (SPEC 2a-2g, 2j, 2m, 2n)

Branch `fix/v04-deferred`, worktree `C:/Dev/Worktrees/rigtune-fixes4`. Binding: SPEC items 2a-2g, 2j, 2m, the
amendments A-H1, A-M1, A-L1, P-L1 (modName), 2n, X-L2, "Launcher steps"; ws-k.md names. Order: 2d first, then 2n,
then the rest. Every task: a test that fails first, then the fix; commit per task; push often (CI runs game tests).

## Task 1 (2d): staged projects in the incompatibility checks
Files: `core/modrinth/StagedProjects` (new), `DependencyResolver`, `DownloadPlanner`, `ModrinthClient` (+ Http, Gated,
`core/preview/LookupOnlyClient`, `DownloadInputs`, `PreviewDownloads`), `RealController.download()`/`preview()`,
en_us.json `rigtune.download.*`.
- [ ] Red: `StagedProjectsTest` (fold of a relocated pending.json fixture: ENABLE_FILE ops with projectId/versionId count;
  DISABLE_FILE, patches, 0.3.0-shaped ops and ops outside the instance don't; unreadable file = none).
- [ ] Red: `DownloadPlannerTest` two sequential plans sharing a pending.json: forward declaration refused naming the
  staged mod; reverse declaration refused (staged version fetched by id, Modrinth on); Modrinth off = forward only;
  0.3.0-shaped pending.json = v0.3 behaviour; AC2d.4 a dependant of a staged mod joins its group (merge + remove drops
  both); a version-specific forward declaration against a staged version id.
- [ ] Red: ENABLE_FILE ops carry `projectId`/`versionId` from DownloadPlanner (addition, dependency, update).
- [ ] Green: `StagedProjects(projects, projectByVersion)` + `fold(PendingActions)` + `read(Path pendingFile)` (relocated
  view); `DependencyResolver.withStaged(StagedProjects)`: a separate set read only by `refuseIncompatible` (resolve and
  checkUpdate), never `seen`; staged versions fetched lazily once per resolver via new `ModrinthClient.versions(ids)`
  (`GET /v2/versions?ids=`, verified in docs.modrinth.com), a failure = forward check only (logged, no stack trace);
  new messages `rigtune.download.incompatible_staged` / `rigtune.download.staged_incompatible`.
- [ ] Green: RealController `download()` and `preview()` pass `StagedProjects.read(pendingFile)` (one line each).
- [ ] AC2d.3: the pinned 0.1.0 and 0.3.0 PendingActions parse the planner-written file (fixture test, Task 11).

## Task 2 (2n): Undo last twice on a staged key
Files: `core/history/UndoPlanner` (+ `State.keyOf` default), `client/undo/GameState`.
- [ ] Red: `UndoPlannerTest` reproducing WS-H's run (two applies of one Sodium key applied at a restart, Undo last
  staged, Undo last again SKIPs "You changed it since (it's now 4)"); Undo all from the same point; external change SKIPs.
- [ ] Green: a staged key's current value = the last still-pending staged op for it (ops this plan discards left out),
  else the file's value; GameState maps op + key-in-file to its settings key (Staging.targetOf).

## Task 3 (2e): symmetric refusal
- [ ] Red: two mutually incompatible updates, both tick orders, both refused with `incompatible_both`, a third proceeds;
  two additions via rules `conflicts` and via Modrinth `incompatible`, both orders.
- [ ] Green: DownloadPlanner pre-checks ticked updates' target versions pairwise (`declaresIncompatible` both ways,
  made package-visible) and additions pairwise (rules conflicts, and resolved root versions' Modrinth incompatibility).

## Task 4 (2f): helper wording
- [ ] Red: move ApplyExecutorTest/ApplyGroupsTest/ApplyFailuresTest/HistoryScreenTest/HistoryGameTest assertions to
  "Gave up after N tries: " / "Gave up after 3 restarts: "; a test that no helper message says "attempt(s)"; the v0.1.0
  real-instance fixture still renders.
- [ ] Green: ApplyExecutor.java:186, :499.

## Task 5 (2c): mod names in History
Files: `ModJars.nameOf` + `ModJars.sanitizeName`, `StagedChanges.of(..., modNameOf, ...)`, Staging/HistoryStartup
call sites, LegacyImport, `HistoryModel.Change.name`, `HistoryScreen.describe`.
- [ ] Red: ModJarsTest (name read, missing/unreadable = null, `§`/controls stripped, 64 code points); StagedChangesTest
  (enable reads `op.from()`, disable reads `op.path()`, missing jar = null); JournalTest round trip with/without;
  HistoryModelTest prefers the name; HistoryGameTest row shows "Sodium".

## Task 6 (2a): History buttons
- [ ] Red: HistoryModelTest (empty / fully undone / one open apply) + HistoryGameTest (empty: both inactive, seeded: active).
- [ ] Green: `HistoryScreen.init()` `active = !loading && anyUndoable`.

## Task 7 (2b): Preview labels
Files: RigTuneController `settingLabels()` default (coordinator-approved), RealController one line, StubController,
PreviewScreen row rendering only.
- [ ] Red: a unit test of the row text helper (labelled key = `SettingValues.describe()` text; unlabelled = caption
  name); PreviewGameTest rows match the main list's strings, screenshots at 3 sizes.

## Task 8 (2g): MultiMC, GDLauncher, launcher strings
- [ ] Red: LauncherDetectorTest (MultiMC-only signals, PolyMC INST_* only, Prism pair, brand GDLauncher); ShareReport names
  them; LauncherGameTest RAM advice line with each, screenshots at 3 sizes.
- [ ] Green: `Launcher.MULTIMC`/`GDLAUNCHER`, `LauncherSignals.GDLAUNCHER_BRAND`, LauncherInfo keys; en_us strings from
  launcher-steps.md (`steps.multimc`, `steps.gdlauncher`, names, fixed `steps.curseforge.pack`, `steps.official`).

## Task 9 (2j): no bottleneck language
- [ ] Red: TierBasis unit tests (lowest components 5/5/5, 3/5/4, 4/4/5; basis per component matched/fallback);
  `CpuClassifier.classifyDetailed`; ShareReport line; WordingTest (X4); UiGameTest header at 3 sizes + tooltip text.
- [ ] Green: `rigtune.header.tier_estimate` + `rigtune.header.tier_basis.*`; Recommender fills `Report.tierBasis`;
  RigTuneScreen badge + one tooltip built as a `List<Component>` (WS-B appends its last-benchmark lines).

## Task 10 (2m): checkbox label
- [ ] Red: UiGameTest collects the list narration with a row focused (ScreenNarrationCollector over
  updateWidgetNarration): contains the title.
- [ ] Green: the checkbox's message = title + impact set after build (vanilla draws the construction-time textWidget,
  narrates getMessage(); verified with javap on 26.2 and 26.3), so the row is drawn unchanged.
- [ ] Pixel diff of the first recommendation row vs run 36217772298 (gametest-screenshots-26.2-OpenGL).

## Task 11: v040-written fixture set + released-jar harness
- [ ] `src/test/resources/v040-written/ws-a/{pending.json,history.json}` written by a test from the real planner and
  staging code (paths `${INSTANCE}`, fixed ids/times); a test keeps the committed files in sync; the pinned 0.1.0/0.3.0
  readers parse them; run tools/e2e/compat030.py (origin/test/e2e-v04) against them.

## Task 12: finish
- [ ] code-reviewer subagent; fix high/medium. Merge origin/feat/v0.4.0, `./gradlew build`, push, CI green, look at
  screenshots, `docs/v0.4/design/ws-a.md`.
