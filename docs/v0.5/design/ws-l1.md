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
1. UiGameTest's `findCycle` (frozen file) must see list rows for milestone 1 (about 6 lines). Included in milestone 1,
   which the coordinator merged (e01e70dc).
2. The late detection needs RealController to record the answer before its one rebuild: (a) ~4 lines in
   `RealController.launcherDetected`, or (b) `controller.rescan()` from ModFilesService. Then also the share line
   (`RealController.shareReport` passing `ModFilesService.shareLine()`). See "Open with the coordinator" below.

---

# What landed

Paths: `client/…` = `src/client/java/io/github/chaotix345/rigtune/client/…`, `core/…` = `src/main/java/io/github/
chaotix345/rigtune/core/…`, `gametest/…` = `src/gametest/java/io/github/chaotix345/rigtune/gametest/…`.

| # | commit | what |
|---|---|---|
| M1 | c757a2ff (merged e01e70dc) | `client/ui/RigTuneSettingsScreen`: `SettingsList extends RowList` (a `WidgetRow` per switch, the switch its Tab stop; a `NoteRow` with a `RowFocus` for the note, now the last row so it scrolls in view instead of being dropped when there's no room); WS-P's insertion point above `stutterMonitorRow(rows, column)`, WS-L1's after it. `gametest/A11yGameTest.walkModFilesRowAndNews` (walk + layout at the three sizes and 854x480@3), `gametest/UiGameTest.findCycle` looks inside list rows. |
| L1 | aedf6ce9 | `core/launcher/ModFilesPolicy.of` (the table; `launcherManages()`), `core/launcher/InstanceEvidence.list/listAsync/scan` (the bounded `.index/` listing). |
| L2 | db567a7a | `client/probe/LauncherProbe`: one session = detection + listing (both on `Probes.EXECUTOR`, the mods folder resolved there); a probe answers when both have, else `NOT_YET` at the 3 s cap; `answer()`, `evidence()`, `onLateAnswer`. `client/launcher/ModFilesService.policy()/withoutOptIn()/optedIn()`. |
| L3 | 0bc99252 | `core/report/LauncherModAdvice.apply/guard/kindOf/advised`, `core/launcher/LauncherModText` (`launcherName`, `nameOrYours`, `guideLine`, `previewLine`, `shareLine`), `LauncherInfo.MOD_KINDS/modStepsKey`, `client/ui/LauncherLines.modStepsLine` (and `adviceLine` calls it first), en_us.json `rigtune.launcher.mod_files.*`/`mod_steps.*`, `V05LangFamilies.launcherPolicy`. |
| L4 | 8633b5e7 | `client/ui/PreviewScreen.launcherLines`, `core/report/ShareReport` (the `modFiles` overload), `ModFilesService.shareLine()`. |
| L5 | 4315cd0f | `core/history/UndoPlanner` (the launcher skip, RW-14, `State.launcher()`, `launcherStepsKind`), `client/undo/GameState.launcher()`, `client/ui/UndoScreen` (steps after the reason), `LauncherLines.undoStepsLine`, en_us.json `rigtune.undo.reason.launcher_managed*`/`not_disabled_by_rigtune`. |
| L6 | 61c026d3, a6ed4359 | `src/test/resources/realworld/2026-09-27/**` + README, `core/history/RealWorldFixtures` (test loader, for WS-L2 too), `RealWorldUndoTest`, `RealWorldFixturesTest`. |
| L7 | 09b8e60d | the Settings "Mod files" row, RigTuneScreen's opted-in line, `v050-written/ws-l1/{settings.json,expect.json}`, `ModFilesOptInFixtureTest`, `ModFilesRowTest`. |
| L8 | 2c58b210 | `ModFilesService.news/newsNotice`, `client/notice/ModFilesNewsNoticeSource`. |
| L9 | 20e1b922 | `LauncherDesyncTest` (the Modrinth App and GDLauncher models), `ModFileOpsSourceTest`. |
| L10 | 2a8bfbec | `gametest/LauncherManagedGameTest.policyAndAdvice`, the A11y walk of the Mod files row and MOD_FILES_NEWS; `LauncherProbe.onLateAnswer` as a standing listener (once per detection, only after a probe hit the cap), set by `ModFilesService` (an interim rescan; replaced by R1). |
| merge | e7e33b18 | `origin/feat/v0.5.0` (WS-P's battery-offer row next to the Mod files row in RigTuneSettingsScreen; both kept). |
| R1 | 4a7511f4 | Review H1, M2, M4, L6, L7, L13, L14. `RealController.launcherDetected` (approved exception) records through `LauncherProbe.record()` (a stale NOT_YET never overwrites an answer in) and registers `onLateAnswer(late -> { launcherDetected(late); rebuild(); })`: one rebuild, on `Probes.EXECUTOR`, once per detection. `LauncherProbe.recorded()` is the one launcher the policy (`ModFilesService`), the report's advice and Undo's skip (`GameState`) read, the same value `RealController.launcher()` shows. A probe times out on the detection alone; the `.index/` listing is one task on the worker pool (`startListing`), and a listing that comes after the probe counts as a late answer too. `RigTuneController.modFilesOptedIn()` (default false; approved hotspot edit) gates the opted-in header line; `RealController.shareReport` passes the escaped `- Mod files:` line. `InstanceEvidence.listAsync` and the ModFilesService rescan workaround are gone. |
| R2 | a1fbff45 | Review M3, L8, H1 (Undo). The launcher skip runs per group after the RigTune-jar check (RigTune's own update keeps "RigTune never undoes its own update", no launcher steps); an RW-14 group mate gets `rigtune.undo.reason.not_disabled_by_rigtune.together`; `UndoPlanner.launcherOf(item)` gives the Undo screen's steps the launcher the item's reason names. |
| R3 | 1fe3f99f | Review L9, L15, L16, X12. `RigTuneSettingsScreen.showingModFiles()` (the news' Settings… focuses the Mod files row and scrolls to it); `rigtune.settings.mod_files.tooltip.pending`, `rigtune.launcher.mod_files.news.detail.unnamed`; A11yGameTest checks the row's tooltip, news -> settings at 854x480@2 and 1280x720@3, and Tab to the last row at the scrolling size; `V05TestContext.SCROLLING` = 1280x720@3 (approved exception). |
| R4 | e83dfa6c | Review M5, L10, L11, L12: tests that can fail (advised rows must exist, each with the app's note), a named-file allowlist that also sees a file-op type passed as an argument, case-insensitive fixture words (plus the account and user names), LauncherManagedGameTest's cleanup through SettingsSaver + flush, the window size and pending.json put back. |
| merge | 16bd9f85 | `origin/feat/v0.5.0` at c59b8b93 (WS-B, WS-F, WS-S, the JDK retry). |
| R5 | 97566dc6 | WS-F's two `guideLine`/HowItWorks callers pass `controller.modFilesOptedIn()` instead of the bare settings flag (review M2's rule, applied to C02's guide after the merge). |
| R6 | 91db46e0 (pushed with merge 043fe436) | LauncherManagedGameTest's MOD_FILES_NEWS check with WS-F merged: NEW and RETURNING forced through `FirstRunService.forceStatusForTests` (then put back): no news for NEW, the news naming the Modrinth App for RETURNING, and its Settings… (the real notice source) focuses the Mod files row. The old "never before WS-F" check failed locally once WS-F's status was real (the local run folder is a returning player's). |

Red first: M1's walk failed on the old screen (no list), and on the first run of the new one (Tab skipped the greyed-out
switches: the walk now expects exactly the active rows, as vanilla gives an inactive widget no Tab stop); L1-L9's tests
failed to compile against the WS-K stubs or failed on their identity answers (the LauncherModAdvice cells, the policy
table, UndoPlannerPolicyTest, RW-14's UndoPlannerTest case, the desync model under LAUNCHER); the real-world tests failed in
CI run 36328216699 because `.gitignore` drops `config/` folders (fixed in a6ed4359); L10's first local run hung on a
test-thread `undoPlan` (the harness runs the test thread and the render thread one at a time; now planned on the render
thread) and then caught the late-detection race below.

## Tests (unit, per class)

| class | cases | AC |
|---|---|---|
| `core/launcher/ModFilesPolicyTest` | 8 (every Launcher x .index/ {absent, present, not listed} x opt-in x detection) | AC4a.1 |
| `core/launcher/InstanceEvidenceTest` | 11 (missing, empty, a directory named x.pw.toml, a symlink, a symlinked .index/, 10,000 entries stop at 256, first match, a file or unreadable .index/, the given executor, a refusing executor) | AC4a.2 |
| `client/probe/LauncherProbeTest` | 15 (the 0.3 cases; NOT_YET -> PENDING at the cap; the probe waits for the detection alone; nothing answered; a slow detector: exactly one rebuild off the caller's thread with the final policy in the report; a stale NOT_YET after the late answer keeps it; a late listing rebuilds once; in time isn't late; a listener set after the answer; a reset; the listing on the given executor) | AC4a.3 (unit), H1 |
| `client/launcher/ModFilesServiceTest` | 7 (+ an opt-in while PENDING isn't "opted in") | AC4a.3 (unit), 4e, AC4b.4 |
| `core/report/LauncherModAdviceTest` | 7 (3 policies x 7 rows x Modrinth {on, off, network off}, composed with ModrinthOffAdvice; RIGTUNE the same object; English; unnamed; blank reason; the guard; advised) | AC4b.1, AC4b.2 (unit) |
| `core/launcher/LauncherModTextTest` | 6 (guideLine, who is named, preview line, share line, a steps key per launcher and kind, the labels pinned) | AC4b.3, AC4b.4, AC4b.5 |
| `client/ui/LauncherLinesModStepsTest` | 4 | 4b, 4c |
| `core/report/ShareReportModFilesTest` | 3 (+ the line escaped like every field) | AC4b.4 |
| `client/launcher/ModFilesNewsTest` | 4 | AC4b.6 (unit) |
| `core/launcher/ModFileOpsSourceTest` | 2 (+ the scan sees a type passed as an argument) | AC4b.7 |
| `core/history/UndoPlannerPolicyTest` | 9 (+ RigTune's own update under LAUNCHER keeps its reason; an RW-14 group mate says what it went with) | AC4c.1, AC4c.3 (unit) |
| `core/history/UndoPlannerTest` | the fallback case now RW-14's | AC4c.3 |
| `core/history/RealWorldUndoTest`, `RealWorldFixturesTest` | 4, 2 | AC4c.2, AC4c.3, PLAN-11 |
| `client/ui/ModFilesRowTest` | 2 (+ the tooltip waits for the launcher check) | AC4e.1 (unit) |
| `client/ModFilesOptInFixtureTest` | 2 | AC4e.3, the `ws-l1` set |
| `core/launcher/LauncherDesyncTest` | 3 | AC4j.1 |
| `core/launcher/LauncherScenarioTest`, `core/V05StubsTest`, `client/V05HooksTest`, `client/V05ServicesTest` | updated: the new launcher keys counted; the stubs' RIGTUNE-everywhere pins narrowed to what stays 0.4 | - |

Full 26.2 unit suite locally before the L1-L6 push: 1976 tests, 0 failures, 2 skipped (the pre-existing one and
InstanceEvidenceTest's POSIX-permission half on Windows). After the review fixes and the c59b8b93 merge: 26.2 and 26.3
2555 tests each, 0 failures, 3 skipped. Local game tests on 26.2 (under the lock): LauncherManagedGameTest and
A11yGameTest pass (A11y logs "settings 1280x720@3: the list scrolls (GUI scale 3, max scroll 46)" and "news -> settings
at 854x480@2 / 1280x720@3: row 8 of 10, GUI scale 2 / 3"); screenshots looked at: `a11y-news-settings-*` (the Mod files
row focused inside the list, its help as the tooltip), `settings-1280x720-scale3-last-row-focused` (the note row framed,
fully inside the list).

## Deviations

1. **Wording, not meaning.** SPEC 4b's "<launcher> manages this instance's mods: ..." reads "This instance's mods are
   managed by <launcher>: ..." (and "your launcher" when none is named): en_us.json's launcher names carry their article
   ("the Modrinth App"), so they can't start a sentence. Same for the Undo reason and the opted-in sentence
   ("RigTune changes mod files here; <launcher>'s own list may go out of date.").
2. **`ModFilesPolicy.of` takes a nullable evidence** (the `.index/` listing not answered yet): a launcher that would be
   RIGTUNE stays PENDING until the listing has answered, so a packwiz instance is never briefly RIGTUNE. The probe waits
   for the detection alone (review L6: 0.4's memory and Java-arguments advice keep their launcher); a listing that comes
   after the probe is a late answer (one rebuild).
3. **Who is named.** A packwiz index under the official launcher or an unknown one is "your launcher", with no steps (the
   index isn't the official launcher's record); PolyMC is named MultiMC with MultiMC's labels, as 0.4 does (UNVERIFIED
   for PolyMC's own UI).
4. **A fifth steps kind, `enable`** (and `self_update`, the Modrinth App only): the Undo screen needs the launcher's
   "turn it back on" steps for a skipped disable. Keys `rigtune.launcher.mod_steps.<launcher>.{add,update,disable,enable}`
   plus `modrinth_app.self_update`.
5. **Preview's heading** is `rigtune.launcher.mod_files.preview.heading` ("Mod files"), in WS-L1's block, not a new
   `rigtune.preview.section.*` key in a block that isn't WS-L1's.
6. **Undo last under LAUNCHER** keeps its rule "the newest entry with anything left to revert": an entry with only mod
   files has nothing RigTune can revert there, so Undo last goes on to an older entry with settings; Undo all and Undo
   this list the skipped changes with the launcher's reason and steps.
7. **RW-14 also in `waitingFile`**: an applied disable without a `resultFile` no longer guesses `<file>.disabled` when it
   checks for staged moves either (the change is skipped anyway).
8. **"Opted in" means the opt-in where it matters** (review M2): `ModFilesService.optedIn()` is the flag AND a launcher
   known to keep its own record; RigTuneScreen's header, the share line and C02's guide (FirstRunNoticeSource,
   HowItWorksScreen) all read it through `RigTuneController.modFilesOptedIn()`, so an opt-in turned on while PENDING
   says nothing until the launcher is known.
9. **The settings note** is the list's last row (always reachable by scrolling and Tab) instead of a line drawn under
   the switches only when there was room (it wasn't drawn at 640x480 or 854x480 before).
10. **RigTune's own update row** under LAUNCHER shows as advice even where the helper can't swap RigTune's jar
    (`selfFileActions` false): `withoutStaged` hides only an appliable UpdateMod/DisableMod of RigTune.
11. **Frozen-file exceptions, all approved by the coordinator:** `UiGameTest.findCycle` searches a list's rows (merged
    with milestone 1); `RealController.launcherDetected` (record + the late-answer listener), `.shareReport` (the share
    line) and `.modFilesOptedIn()`; `RigTuneController.modFilesOptedIn()` (default false) with `ForwardingController`'s
    override; `V05TestContext.SCROLLING` = 1280x720@3 (the game caps 854x480 at GUI scale 2, so the old 854x480@3 never
    ran at scale 3). Each is marked "v0.5 WS-L1" in the code. Not approved in advance, one line each and reported: WS-F's
    two `optedIn` arguments (R5).
12. **One launcher source** (review H1): `LauncherProbe` keeps the launcher RealController recorded; the policy, the
    report's LauncherModAdvice, Undo's skip and the Undo screen's steps (from the item's own reason) all use it. Before
    the first probe it reads null (PENDING), where `RealController.launcher()` reads UNKNOWN as in 0.4.
13. **`InstanceEvidence.listAsync` is gone** (review L14): the listing is one `LauncherProbe` task on the worker pool,
    which AC4a.2's executor test (`LauncherProbeTest.theListingRunsOnTheGivenExecutor`) now covers directly.
14. **LauncherManagedGameTest's advised rows** (review M5) are the offline catalog's add rows, there in every run (18 on
    the test instance); the update and disable notes are LauncherModAdviceTest's (every kind x policy), since the game
    test instance has no mod the rules would update or disable offline.

## Open with the coordinator

Nothing. The late detection and the share line were approved and landed in R1; the full-diff review's findings (H1,
M2-M5, L6-L16) and X12 landed in R1-R5.

## Residuals

- A detection slower than the 3 s cap: the report is PENDING until the answer, then exactly one rebuild follows (R1).
- MOD_FILES_NEWS reads WS-F's real `FirstRunService.status()` since the 16bd9f85 merge (AC4b.6's "never for a NEW
  player" is WS-F's FirstRunTest plus ModFilesNewsTest).
- A Windows-only flake seen once in the full local suite after merging origin/feat/v0.5.0: WS-S's
  `StutterServiceTest.aFinishedBenchmarkReportsItsDhWorldGenCpu` couldn't delete its JUnit temp folder (a file still
  open); it passes on its own. Not WS-L1's; reported to the coordinator.

## UNVERIFIED

- The launchers' labels in the running apps: the Modrinth App's from its v0.21.5 locale files (where each control sits
  from lm §1/§5.2; AC4j.5 is the user's check), Prism's, GDLauncher's and ATLauncher's from their source at lm's pins,
  MultiMC's from lm's sub-agent (and PolyMC, named MultiMC, not checked at all), CurseForge's from secondary sources.
- Unknown launchers (HMCL, SKLauncher, TLauncher, ...): assumed folder-only unless `.index/` is there (lm §9).

## Docs (for the docs workstream)

- **README, "What's new in 0.5" / features:** "RigTune no longer changes mod files behind your launcher's back. In the
  Modrinth App, the CurseForge app, ATLauncher and GDLauncher (and in any instance with packwiz metadata,
  `mods/.index/*.pw.toml`, such as Prism's), mod installs, updates and disables become advice with your launcher's own
  click steps, and Undo leaves mod files RigTune changed in 0.1-0.4 to the launcher too. Settings still apply in one
  click. Settings -> Mod files -> "Let RigTune change them anyway" brings the old behaviour back for that instance."
- **README, known limits:** "The launchers' button names come from their source code and locale files, not from the
  running apps; CurseForge's are from its help pages." "A launcher that takes longer than 3 s to detect shows 'Checking
  which launcher manages this instance's mods' until it answers."
- **DESIGN.md, a new "Mod files and the launcher (0.5)" section:** the policy table (opt-in, `.index/` evidence first,
  PENDING, the launchers that keep a record, else RIGTUNE); LauncherProbe's session (detection + bounded listing, NOT_YET
  at the cap, one late-answer listener); the report post-step LauncherModAdvice (before ModrinthOffAdvice) and the apply
  guard (fails closed); Undo's rule (applied mod-file changes skipped with the launcher's reason and steps; staged ones
  still cancelled) and RW-14 (an applied disable without `resultFile` was no rename of RigTune's; no `.disabled`
  guess); the opt-in (`settings.json` `modFilesByRigTune`, SettingsSaver, dropped by a 0.4.0 rewrite: off, the safe side);
  MOD_FILES_NEWS for returning players; the real-world fixtures (file names only). "Undo" gains: "In an instance whose
  launcher keeps its own record of the mods, Undo leaves the mod files RigTune changed to the launcher (with its steps)."
- **CHANGELOG [0.5.0]:** the above in one line each; plus "A downgrade to 0.4.0 turns the opt-in off (0.4.0 drops the
  field) and changes jars again in such an instance (0.4's behaviour)."

## CI runs, screenshots looked at

| run | head | result | what I looked at |
|---|---|---|---|
| 36320295779 | c757a2ff (M1) | all 8 jobs green | `settings-{1280x720,640x480,854x480}-scale2(-scrolled)`, `settings-854x480-scale3(-scrolled)` on 26.2 GL and 26.3 Vulkan; UiGameTest's `ui-settings-*`, `ui-modmenu-settings`: the rows inside the list, the list scrolls to the note, the footer clear, labels readable (26.3 keeps a row focused in my layout shots after a resize: cosmetic, the layout checks pass) |
| 36328216699 | 61c026d3 (L1-L6) | java red: the real-world fixtures missing (`.gitignore`), fixed in a6ed4359 | the failing tests' log |
| 36329802402 | 2c58b210 (L1-L8) | all 8 jobs green | the footprint JSON of the three legs |
| 36336426845 | the L10 + merge + doc head | all 8 jobs green | `launcher-managed-rows-*` (26.2 GL, 26.3 Vulkan: the app's note in the reason and the green "In the Modrinth App: ..." line under every Add row), `launcher-managed-undo-854x480-scale2` (the fixture change skipped with the reason and the app's Disable steps), `launcher-managed-settings-854x480-scale2`, `launcher-managed-opted-in-*` (the opted-in line in the one warning slot, Apply counts the mod rows again), `a11y-settings-mod-files-854x480-scale2` (the row focused, the help as its tooltip), `a11y-mod-files-news-854x480-scale2`, `settings-*` with WS-P's battery row and the Mod files row |
| 36364315401 | 043fe436 (R1-R6 + merge 3f42974f) | all 8 jobs green on attempt 1 | `launcher-managed-news-settings-854x480-scale2` (26.2 GL, 26.3 Vulkan: the news' Settings… lands on the Mod files row, focused, scrolled into the list; WS-W's "Settings changed outside the game" toast is over the title, as in the other launcher-managed shots), `a11y-news-settings-{854x480-scale2,1280x720-scale3}` (the row focused with its help as the tooltip, GUI scale 3 at 1280x720), `settings-1280x720-scale3(-scrolled,-last-row-focused)` (the note row framed and fully inside the list; on 26.3 the Benchmark scene switch also keeps a focus frame and its tooltip after the walk's clearFocus, the 26.3 list behaviour seen since M1: cosmetic in these shots, the checks pass), `launcher-managed-undo-854x480-scale2` (the fixture enable skipped with the app's reason and its Disable steps) |

## Footprint, local (review L7; 26.2 OpenGL, this Windows machine, `versions/26.2/build/run/clientGameTest`)

| run | renderThreadInitCpuMs | renderThreadInitWallMs | clientStartedWallMs | workerCpuMs5s | v05RenderThreadResolve | v05HolderCreatedOn |
|---|---|---|---|---|---|---|
| after R1-R5 (with LauncherManaged, A11y) | 62.5 | 110.2 | 161.9 | 390.6 | null | RigTune worker |
| after R1-R5 (FootprintGameTest alone) | 154.1 | 154.1 | 99.1 | 328.1 | null | RigTune worker |
| before the fixes (M1's and L10's five local runs, the log's summary line) | 62.5-123.1 | 100.8-432.5 | 25.8-176.6 | 265.6-359.4 | - | - |

Locally the gate fails either way: workerCpuMs5s was over its 300 ms budget in every local run before the fixes too, and
the other two swing across their budgets from run to run (Windows counts thread CPU in 15.6 ms steps, and other agents'
builds share the machine). The budgets are calibrated on CI's runners, where they're enforced; nothing RigTune does on
the render thread at startup is new here (`v05RenderThreadResolve` null: the listing runs, and loads InstanceEvidence,
on `Probes.EXECUTOR`).

## Footprint (per leg, from `footprint-<mc>-<backend>.json`; baseline: ws-k.md's WS-K head run 36310249248)

| leg | key | baseline | 36329802402 (L1-L8, before merging feat/v0.5.0) | 36336426845 (after the merge: other workstreams' code too) | 36364315401 (the review round, WS-B/F/S merged too) |
|---|---|---|---|---|---|
| 26.2 OpenGL | renderThreadInitCpuMs | 82.2 | 105.1 | 110.0 | 98.9 |
| 26.2 OpenGL | clientStartedWallMs | 36.4 | 40.9 | 51.1 | 27.8 |
| 26.2 OpenGL | workerCpuMs5s | 135.5 | 190.5 | 233.3 | 218.8 |
| 26.2 OpenGL | tickHookOnVsReference | 1.481 | 1.571 | 1.582 | 1.563 |
| 26.3 OpenGL | renderThreadInitCpuMs | 82.2 | 113.9 | 110.6 | 104.6 |
| 26.3 OpenGL | clientStartedWallMs | 27.0 | 37.7 | 27.3 | 46.4 |
| 26.3 OpenGL | workerCpuMs5s | 153.2 | 188.3 | 193.0 | 221.3 |
| 26.3 OpenGL | tickHookOnVsReference | 1.746 | 1.603 | 1.544 | 1.582 |
| 26.3 Vulkan | renderThreadInitCpuMs | 120.0 | 111.9 | 107.6 | 118.5 |
| 26.3 Vulkan | clientStartedWallMs | 39.9 | 66.8 | 47.3 | 58.4 |
| 26.3 Vulkan | workerCpuMs5s | 200.7 | 180.4 | 190.0 | 208.7 |
| 26.3 Vulkan | tickHookOnVsReference | 1.535 | 1.580 | 1.430 | 1.614 |

`v05RenderThreadResolve` null and `v05HolderCreatedOn` "RigTune worker" on every leg of all three runs (X4), no budget
violation in 36364315401. Reading: every
value keeps its budget (150 / 141 / 300 / 2.05). WS-L1 adds no render-thread work at startup beyond one more future in
`LauncherProbe.probeAsync` (the listing is a lambda that runs, and loads InstanceEvidence, on Probes.EXECUTOR) and no tick
or frame work; its worker work is one bounded directory listing. The spread across runs of near-identical code (ws-k.md:
63.5-112.9 ms for 26.2's renderThreadInitCpuMs) is larger than these differences, which go both ways across the legs.

## AC table

| AC | status | evidence |
|---|---|---|
| PLAN-4 (milestone 1: settings as a scrolling RowList, insertion points) | verified | merged e01e70dc; CI 36320295779; A11yGameTest settings walk + layout at the three sizes and 854x480@3 |
| AC4a.1 (the table, every Launcher x .index/ x opt-in x detection) | verified | ModFilesPolicyTest (CI java job, both nodes) |
| AC4a.2 (the bounded .index/ listing) | verified | InstanceEvidenceTest (the unreadable half runs on Linux CI; skipped on Windows) |
| AC4a.3 (timeout gives PENDING, never RIGTUNE; the late answer: one rebuild, final policy) | verified | LauncherProbeTest (NOT_YET -> PENDING; a slow detector: exactly one rebuild, off the caller's thread, the final policy in the report; a stale NOT_YET keeps the answer); LauncherManagedGameTest.redetect waits for the report with the recorded launcher (local, CI) |
| AC4b.1 (the rows, 3 policies x 7 kinds x Modrinth on/off/network off; RIGTUNE identical; golden report) | verified | LauncherModAdviceTest; RIGTUNE returns the same Report object and Recommender is unchanged, so the 240-scenario golden test (unchanged, CI) holds |
| AC4b.2 (Apply refuses a crafted add/update/disable, stages nothing) | verified | LauncherModAdviceTest.theGuardDrops...; LauncherManagedGameTest.refusedApply on 3 legs (mods/ SHA-256 identical, no file op) |
| AC4b.3 (a steps key per launcher and kind; the labels pinned) | verified | LauncherModTextTest.aStepsKeyForEveryLauncherAndKind, theStepsUseTheLaunchersOwnLabels; LangCheckTest (V05LangFamilies' mod_steps family) |
| AC4b.4 (Preview's line and count; the share report's line per policy) | verified | LauncherModTextTest.thePreviewLine, LauncherModAdviceTest.advised...; ShareReportModFilesTest (escaped), ModFilesServiceTest.theShareLine...; RealController.shareReport passes it (R1) |
| AC4b.5 (guideLine) | verified | LauncherModTextTest.theGuideLine |
| AC4b.6 (MOD_FILES_NEWS once for RETURNING under LAUNCHER only) | verified | ModFilesNewsTest (incl. the unnamed launcher's detail), WS-F's FirstRunTest; game: not shown to this NEW test instance (LauncherManagedGameTest), narrated on NoticeScreen and its Settings… opens the settings on the Mod files row (A11yGameTest) |
| AC4b.7 (no file-op staging outside lm §4.3's classes) | verified | ModFileOpsSourceTest |
| AC4c.1 (Undo under LAUNCHER/PENDING; staged still cancelled; mixed entry) | verified | UndoPlannerPolicyTest; LauncherManagedGameTest.undoSkipsAppliedModFiles (3 legs) |
| AC4c.2 (the real history.json: no file op under LAUNCHER; RIGTUNE = today's minus RW-14) | verified | RealWorldUndoTest |
| AC4c.3 (RW-14; variant D; the fallback fixtures updated) | verified | UndoPlannerPolicyTest.aDisableWithoutAResultFile..., RealWorldUndoTest.variantD..., UndoPlannerTest.aDisableWithoutAResultFileIsNotReEnabledFromAGuessedName |
| AC4e.1 (the row under the stated policies, two choices, help, Tab stop narrating its state; saved through SettingsSaver) | verified | ModFilesRowTest; A11yGameTest (Tab + narration); LauncherManagedGameTest.optIn (SettingsSaver flush, settings.json) |
| AC4e.2 (opted in: report = RIGTUNE's, the warning line with its precedence, one line at the three sizes) | verified (Apply/Undo/helper as 0.4 follow from RIGTUNE: LauncherModAdvice identity, UndoPlanner's RIGTUNE path; the helper's hold is WS-L2's) | LauncherManagedGameTest.optIn, screenshots `launcher-managed-opted-in-*` |
| AC4e.3 (settings.json round trip; a 0.4-shaped file reads false; compat040 reads it) | verified here; compat040 when WS-E's interpreter lands (the set's expect.json is committed) | ClientSettingsTest (WS-K), ModFilesOptInFixtureTest; compat030 PASS locally with the ws-l1 settings.json (0.3.0's ClientSettings, no .bad, no file changed) |
| AC4j.1 (the Modrinth App / GDLauncher desync models) | verified | LauncherDesyncTest |
| AC4j.2 (LauncherManagedGameTest on every leg) | verified | CI 36336426845 and 36364315401, 3 legs (the theseus brand, the steps, the refused Apply, mods/ unchanged, Undo's reason, the opt-in, .index/ without a brand, restored) |
| RW-2, RW-14 | verified | as AC4c.1-AC4c.3 |
| PLAN-11 (the templated real-world fixtures) | verified | src/test/resources/realworld/ + README; RealWorldFixturesTest |

## Review-11 fixes

| id | result | commit | the test that failed first |
|---|---|---|---|
| FEAT-1 (M) | FIXED | e04cde55 | `ModFilesNewsTest.aNewPlayersFirstApplyDoesntBringTheNews`: on the old code, after `FirstRunService.applied()` the notice source's status is RETURNING and `news()` returned MOD_FILES_NEWS ("a new player's first Apply shows the 0.4-upgrade news"). |

- **The fix.** `FirstRunService.loadedStatus()` keeps what `load()` read (UNKNOWN when an Apply came first: no news that
  session), and `ModFilesNewsNoticeSource` asks `news()` with it, never with the live status an Apply turns RETURNING.
  A NEW read also stores `launcher.mod_files_news` in awareness.json's dismissals (on `load()`'s worker, as the notice's
  own x would), so later launches, which read RETURNING from the records this one leaves, stay quiet too.
- **Deviation from the suggested fix:** the dismissal is stored when `load()` reads NEW, not when an Apply moves NEW to
  RETURNING. That covers the Apply (and a battery or server-profile switch) and also a new player whose first session
  only kept a benchmark or a baseline: those journal entries make the next launch RETURNING without any Apply.
- **Tests.** `FirstRunServiceTest.aNewPlayerNeverGetsTheModFilesNews` (NEW read -> dismissal stored; after `applied()`
  `loadedStatus()` is still NEW; a next FirstRunService over the same folder reads RETURNING and `NoticeBoard` hides the
  news), `.aReturningReadStoresNoDismissal`, `.aLoadAfterAnApplyStaysReturning` (+ `loadedStatus()` UNKNOWN);
  `LauncherManagedGameTest.modFilesNews` (a NEW load stored the dismissal; forced NEW then `applied()`: RETURNING and no
  news; the dismissal is taken out for the RETURNING check and put back). `forceStatusForTests` now sets the loaded
  status too.
- **Owner's file.** `FirstRunService` is WS-F's; the finding names it. The change is marked "v0.5 WS-L1 (review-11
  FEAT-1)".
