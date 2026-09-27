# WS-L1: launcher policy, advice, Undo rule, opt-in, news (v0.5 P0.4)

Branch `feat/v05-launcher-policy` (worktree `rigtune-l1`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/PLAN.md "WS-L1" (SPEC 4a, 4b, 4c, 4e, 4j.1-4j.2; RW-2, RW-14; PLAN-4's milestone 1; PLAN-11's
templated real-world fixtures). Research: docs/research/v0.5/launcher-managed-mods.md (lm), real-world-2026-09-27.md §2
(rw), feature-first-apply.md §2.5 (fa). Contracts as landed: docs/v0.5/design/ws-k.md.

This file is first the TDD task plan (committed before any code), then, as tasks land, what landed, the deviations,
residuals, UNVERIFIED items, Docs text, footprint deltas and the AC table.

## TDD task plan

Each task: a test that fails first, then the code, then a commit. Local: `./gradlew :26.2:test --tests '<classes>'` in a
build slot (`:26.3:` too for any version-specific code; none expected: no new `//? if` block). Game tests run in CI (every
push, three legs); the screenshots are downloaded and looked at.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| M1 | **Milestone 1 (merges first):** RigTuneSettingsScreen becomes a scrolling `RowList` (`SettingsList`), no behaviour change: the seven switches are rows (each row's CycleButton is its Tab stop and narrates its value), the note and the footer stay; two marked insertion points (WS-P's battery-offer row, WS-L1's mod-files row). | `client/ui/RigTuneSettingsScreen`, `gametest/A11yGameTest.walkModFilesRowAndNews` (settings part), `gametest/UiGameTest.findCycle` (coordinator's OK needed: it finds the switches through `Screens.getWidgets`, which doesn't see list rows) | A11y walk (red: no list on the settings screen): Tab reaches every row in order, each narrates its label; layout check (every widget inside the screen, rows inside the list, nothing overlaps the footer) at 1280x720, 640x480, 854x480 @2 and 854x480 @3 (the list scrolls; its last row reachable) with screenshots `settings-*`; UiGameTest's settings checks unchanged | PLAN-4, X6, X12 for this screen |
| L1 | The policy table and the `.index/` evidence (pure) | `core/launcher/ModFilesPolicy`, `InstanceEvidence` | `ModFilesPolicyTest` (every row of the table in order: every `Launcher` x `.index/` {absent, present, not listed yet} x opt-in x detection {done, pending}; MULTIMC/OFFICIAL/UNKNOWN + `.index/` = LAUNCHER, pending + `.index/` = LAUNCHER, pending = PENDING never RIGTUNE); `InstanceEvidenceTest` (missing, empty, a directory named `x.pw.toml`, a symlink not followed, 10,000 entries stop at the 256 bound, unreadable = no exception, runs on the given executor, never the caller's thread) | AC4a.1, AC4a.2 |
| L2 | PENDING and the late answer: LauncherProbe's timeout is a distinct `NOT_YET` (never UNKNOWN-as-RIGTUNE); the `.index/` listing runs on its own; `ModFilesService.policy()` from the probe's answers + `settings.json`; a late answer is recorded and makes exactly one rebuild | `client/probe/LauncherProbe`, `client/launcher/ModFilesService` (+ the RealController line the coordinator decides) | `LauncherProbeTest.aStalledDetectorIsNotYetNeverUnknown`, `.aLateAnswerIsHandedOnOnce`, `.theIndexListingRunsOnItsOwn`; `ModFilesServiceTest` (pending -> PENDING; `.index/` listed while detection pends -> LAUNCHER; opt-in -> RIGTUNE; `withoutOptIn`) | AC4a.3 (unit), 4a |
| L3 | Mod-file rows become the launcher's steps (post-step), the apply guard, `guideLine`, the steps keys | `core/report/LauncherModAdvice`, `core/launcher/LauncherModText`, `LauncherInfo.modStepsKey`, `client/ui/LauncherLines`, en_us.json (`rigtune.launcher.mod_files.*`, `rigtune.launcher.mod_steps.*`), `V05LangFamilies.launcherPolicy` | `LauncherModAdviceTest` (3 policies x {AddMod, UpdateMod, DisableMod, `update:rigtune`, SetSetting, None, an UpdateMod outside the mods folder} x Modrinth {on, off, network off}, composed with ModrinthOffAdvice as `rebuild()` does: action class, ticked, note key, steps kind; RIGTUNE returns the same report object; the golden report unchanged); `guard` drops Add/Update/Disable under LAUNCHER/PENDING and keeps everything under RIGTUNE; `LauncherModTextTest` (LAUNCHER sentence, opted-in sentence, null for RIGTUNE and PENDING); `LauncherModStepsTest` (a steps key for every (launcher, kind) the policy can advise; the Modrinth App, Prism, GDLauncher and ATLauncher English pinned to the labels cited below) | AC4b.1, AC4b.2 (unit), AC4b.3, AC4b.5 |
| L4 | Preview's line and the share report's line | `client/ui/PreviewScreen.launcherLines`, `core/launcher/LauncherModText` (the preview count line), `core/report/ShareReport` | `LauncherModTextTest.previewLine` (count of advised mod rows, null under RIGTUNE), `ShareReportTest` (the mod-files line for LAUNCHER, PENDING, opted in; none for plain RIGTUNE) | AC4b.4 |
| L5 | Undo: APPLIED mod-file changes skipped under LAUNCHER/PENDING with the launcher reason + steps; STAGED still cancelled; RW-14 (no `.disabled` guess for an applied disable without `resultFile`) | `core/history/UndoPlanner`, `client/undo/GameState`, `UndoService`, `client/ui/UndoScreen`, en_us.json (`rigtune.undo.reason.launcher_managed*`, `rigtune.undo.reason.not_disabled_by_rigtune`) | `UndoPlannerPolicyTest` (an add, a disable, an update pair skipped with the launcher reason; staged changes cancelled; a mixed entry reverts its settings only; PENDING wording names no launcher); `UndoPlannerTest` RW-14 case (red: today it guesses `<file>.disabled` and re-enables the player's jar) and the fixtures that relied on the guess updated | AC4c.1, AC4c.3 (unit) |
| L6 | The templated real-world fixtures and `RealWorldUndoTest` | `src/test/resources/realworld/2026-09-27/**` + `README.md`, `core/history/RealWorldUndoTest` | `RealWorldUndoTest` (from rigtune-realworld's `RealWorld20260927Test`): under LAUNCHER, Undo last / Undo all / Undo this stage no file op; under RIGTUNE today's plan minus RW-14's skip; rw's variant D (the app's own `fabric-26.2.jar.disabled`) stages no rename of the app's jars; the fixtures contain no absolute path and no user name (a scan test) | AC4c.2, AC4c.3 |
| L7 | The opt-in row, its saving, the opted-in header line | `RigTuneSettingsScreen` (the mod-files row at its marked place), `client/launcher/ModFilesService` (`showOptInRow`, `setOptIn` through `SettingsSaver`), `client/ui/RigTuneScreen` (the offline line's opted-in branch), en_us.json (`rigtune.settings.mod_files*`), `src/test/resources/v050-written/ws-l1/{settings.json,expect.json}` | `ModFilesServiceTest.theRowShowsExactlyUnderLauncherPendingOrOptIn`, `.choosingSavesThroughSettingsSaver`; `ModFilesOptInFixtureTest` (writes/compares the `ws-l1` set with the one regeneration switch; a 0.4-shaped settings.json reads false; round trip); game test (AC4e.1/AC4e.2 part in `policyAndAdvice`) | AC4e.1, AC4e.2, AC4e.3 |
| L8 | MOD_FILES_NEWS | `client/notice/ModFilesNewsNoticeSource`, `ModFilesService.news()` | `ModFilesNewsTest` (once for RETURNING under LAUNCHER; never for NEW or UNKNOWN; never under RIGTUNE or PENDING; not after dismissal; its action opens the settings) | AC4b.6 (unit; the NEW part closes with WS-F's real `status()`) |
| L9 | The desync models (oracle) and the file-op source scan | test-only `core/launcher/ModrinthAppModel`, `GdLauncherModel`, `LauncherDesyncTest`; `ModFileOpsSourceTest` | `LauncherDesyncTest` (0.4's update pair reproduces the incident's collision in the app model and GDLauncher's "already installed"; under LAUNCHER RigTune's planned ops for the same report desync neither); `ModFileOpsSourceTest` (no `ENABLE_FILE`/`DISABLE_FILE` staging outside lm §4.3's classes) | AC4j.1, AC4b.7 |
| L10 | Game tests | `LauncherManagedGameTest.policyAndAdvice`, `A11yGameTest.walkModFilesRowAndNews` | brand `theseus` + rescan: no Add/Update/Disable appliable, each with the Modrinth App steps; the crafted Apply refused, `mods/` hash-identical, no file op in `pending.json`; a fixture applied file change skipped by Undo all with the launcher reason; the opt-in restores 0.4 (report equal, the header line, one warning line at the three sizes); no brand + `mods/.index/sodium.pw.toml` gives LAUNCHER; everything restored; the mod-files row and MOD_FILES_NEWS walked with Tab and narrated | AC4j.2, AC4a.3 (game), AC4b.2 (game), AC4e.1, AC4e.2 |
| L11 | Finish: merge `origin/feat/v0.5.0`, targeted tests, CI green on every job and leg, screenshots looked at, compat030 on the `ws-l1` set, footprint deltas, this file's AC table | this file | CI | - |

No code-deciding run (PLAN names none for WS-L1). No new `//? if` block expected.

### Decisions taken in the plan (from the SPEC, lm and the code)
- **Policy inputs.** `ModFilesPolicy.of(@Nullable LauncherInfo launcher, @Nullable InstanceEvidence evidence, boolean optIn)`:
  launcher null = detection hasn't answered; evidence null = the `.index/` listing hasn't finished. Order: opt-in ->
  RIGTUNE; `.index/` -> LAUNCHER; detection pending -> PENDING; Modrinth App, CurseForge, ATLauncher, GDLauncher ->
  LAUNCHER; listing not finished -> PENDING (a `.index/` can't be ruled out yet); else RIGTUNE.
- **Who is named.** LAUNCHER from a launcher signal names it; LAUNCHER from `.index/` names Prism and MultiMC (PolyMC is
  detected as MultiMC, as in 0.4) and says "your launcher" for the official launcher and Unknown (a packwiz index there
  isn't the official launcher's record). PENDING names nobody and gives no steps (SPEC-13).
- **Rows.** A transformed row keeps id, category, impact and title, becomes `Action.None`, unticked; its reason gets one
  sentence (`rigtune.launcher.mod_files.note.<kind>`: "This instance's mods are managed by %s: install it there." etc.,
  or the PENDING sentence). The "In <launcher>: <steps>" line under it comes from `LauncherLines` (as the memory steps),
  keyed `rigtune.launcher.mod_steps.<launcher>.<kind>`; `LauncherModAdvice.kindOf(Recommendation)` reads the kind back
  from the note's key, so an unrelated `Action.None` row (an update of a mod outside the mods folder under RIGTUNE) never
  gets steps. The launcher names in en_us.json carry an article ("the Modrinth App"), so the sentences put the name
  mid-sentence ("managed by the Modrinth App") rather than first (a wording deviation from SPEC 4b's "<launcher> manages",
  recorded below).
- **Steps labels** (AC4b.3; UNVERIFIED in the running launchers until AC4j.5 for the Modrinth App):
  - Modrinth App (locale files at v0.21.5, `scratchpad/r-launchers/mrloc`): Content (`app.instance.tab.content`), Browse
    content (`content.page-layout.browse-content`), Update (`button.update`), Update all (`content.page-layout.update-all`),
    Disable (`button.disable`), Enable (`button.enable`), Disabled filter (`content.filter.disabled`), Upload files
    (`content.page-layout.upload-files`); where each control sits is from lm §1/§5.2 and the locale only.
  - Prism Launcher 11.1.0: Edit... (`MainWindow.ui:396`), Mods tab, Download Mods (`ModFolderPage.cpp:73`), Check for
    Updates (`ModFolderPage.cpp:86`), Disable / Enable / Remove (`ExternalResourcesPage.ui:103-125`).
  - GDLauncher Carbon (`packages/i18n/locale/english/content.json`): Mods, Add Mod, Update / Update All Mods, Disable Mod,
    Enable, Delete.
  - ATLauncher 3.4.41.3 (`EditModsDialog.java`, `InstanceCard.java:75`): Edit Mods, Browse Mods (:316), Check For Updates
    (:328), Enable Selected (:339), Disable Selected (:344), Remove Selected (:349).
  - MultiMC 0.6.16 (lm §3.2, sub-agent): Edit Instance, Loader mods, Add / Remove / Enable / Disable (no update feature).
  - CurseForge: lm §3.5's secondary sources (UNVERIFIED).
- **Undo.** Under LAUNCHER/PENDING every APPLIED file change is skipped (`rigtune.undo.reason.launcher_managed` with the
  launcher, `.unnamed`, `.pending`); STAGED ones are still cancelled (pending.json and RigTune's own downloads only);
  settings revert as today. The screen adds the launcher's steps line for a skipped enable (its disable steps) or
  disable (its enable steps). RW-14 (every policy): an APPLIED disable without `resultFile` is skipped with
  `rigtune.undo.reason.not_disabled_by_rigtune` ("RigTune didn't disable %s (it was already gone)").
- **The opted-in line** reuses the header's single offline slot (precedence network off > Modrinth off > opted in) and
  shows only when the opt-in is on and the instance would otherwise be LAUNCHER or PENDING (the line is the
  `guideLine` opted-in sentence).
- **MOD_FILES_NEWS**: key `launcher.mod_files_news` (dismissed in awareness.json like any notice), under LAUNCHER only,
  RETURNING only; action "Settings…" opens RigTuneSettingsScreen at the mod-files row; dismissible.

### Questions sent to the coordinator (2026-09-27)
1. UiGameTest's `findCycle` (frozen file) must see list rows for milestone 1 (about 6 lines).
2. The late detection needs RealController to record the answer before its one rebuild: (a) ~4 lines in
   `RealController.launcherDetected`, or (b) `controller.rescan()` from ModFilesService.
