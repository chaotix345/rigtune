# WS-F: C02, the first-time Apply trust flow (v0.5)

Branch `feat/v05-first-apply` (worktree `rigtune-firstapply`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/SPEC.md 8 (AC8.1-AC8.18), PLAN "WS-F". Sources: docs/research/v0.5/feature-first-apply.md (fa), the
contracts as landed (docs/v0.5/design/ws-k.md).

This file is first the TDD task plan (committed before any code), then, once done, the deviations, residuals,
UNVERIFIED items, the Docs text, the footprint deltas and the AC table with evidence.

## What WS-F fills in (the contracts' stubs) and adds

- `core/history/FirstRun` (WS-K's `Status` enum): the pure `isNew(state, entries, lastApplyExists, pendingExists)` and a
  reader over a `Journal` and the config dir.
- `client/FirstRunService` (WS-K skeleton): UNKNOWN/NEW/RETURNING in memory (an `AtomicReference`); `load()` (the start
  hook's step, on `Probes.EXECUTOR`) does `compareAndSet(UNKNOWN, NEW or RETURNING)` and records the thread it ran on;
  `applied(ApplyFacts)` (the after-apply hook, last) sets RETURNING; `firstApplyPending()` = NEW; a failing read gives
  RETURNING (never shows either piece by mistake); one test seam, `forceStatusForTests(Status)` (FirstApplyGameTest restores
  the fresh state through it when it isn't the first class in its JVM, SPEC C6 / SPEC-19). Nothing persisted; no
  settings.json field (AC8.10).
- `client/notice/FirstRunNoticeSource` (WS-K skeleton): the guide (`firstrun.guide`, `NoticePriority.FIRST_RUN`), shown
  while NEW with a report that has at least one appliable recommendation; the detail follows `controller.modFiles()`
  (RIGTUNE: `…notice.detail`; LAUNCHER and PENDING: `…notice.detail.settings`), joined with
  `LauncherModText.guideLine(policy, launcher, settings.modFilesByRigTune)` except under PENDING; actions How it works
  (opens HowItWorksScreen over whatever screen is open) and Got it (`dismissNotice`, stored in awareness.json);
  not dismissible otherwise. A static `notice(...)` builder and `shows(...)` predicate carry the logic for unit tests.
- New `client/ui/FirstApplyScreen`: opened only by `RigTuneScreen.applySelected` (about 6 lines: `first =
  controller.firstApplyPending()`, a pre-minted entry id, `apply(chosen, entryId)`, then the screen). One RowList: the
  status row (Apply's status, then the controller's newer status), "In effect now" / "At the next restart" / "Undone or
  cancelled" sections of the entry's HistoryModel changes drawn with HistoryScreen's own `describe`/`failureText`/
  `statusColor` at HistoryScreen's widths, then the notes (restart iff a STAGED row; no-restart iff none and no download;
  downloading while `downloading()`; no-mod-files under LAUNCHER/PENDING; the undo hint last) or, without the entry, the
  History message. The history is read off-thread (`Probes.EXECUTOR`, as HistoryScreen) and reloaded when the screen comes
  back from Undo/History, when the controller's status changes or `downloading()` flips. Buttons Undo this Apply /
  History… (that entry selected) / Done (and Esc). Open narration: title, summary, restart outcome.
- New `client/ui/HowItWorksScreen`: a static RowList page whose rows follow `modFiles()` (fa §2.3/§2.5 without the
  dropped updates-only row), Done back to the opener.
- en_us.json `rigtune.firstrun.*` (the block starts after its anchor `rigtune.header.offline`; alphabetical inside). All
  keys are written out literally (no dynamic family: `V05LangFamilies.firstRun` stays empty, recorded here).
- Game tests: `FirstApplyGameTest` (first entrypoint; returns under `rigtune.smoke`), A11yGameTest's `walkFirstApply` and
  `walkHowItWorks`.
- Fixture set `ws-f` (`awareness.json` with `firstrun.guide` in `dismissed`, + `expect.json`), written/compared by
  `FirstRunNoticeSourceTest` (the one regeneration switch).

Not touched (AC8.10 and the hotspot rules): `ClientSettings`, `StartupNotices`, `HistoryScreen`, `HistoryModel`,
`Journal`, `UndoScreen`, `NoticeScreen`, `RigTuneClient`, `RealController`, `RigTuneController`, `V05Services`,
`V05Hooks`, `tools/footprint-budgets.json`. RigTuneScreen: `applySelected` only.

## TDD task plan

Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot; `:26.2:gametestClasses` for the game-test
sources; the full build and the game tests are CI's. No `//? if` block expected (fa §4: every API used is the same on
26.2 and 26.3, checked again with javap: `Screen.getNarrationMessage()`, `triggerImmediateNarration(boolean)`,
`setInitialFocus()`, `rebuildWidgets()`, `tick()`, `shouldCloseOnEsc()`; `EntrypointContainer.getDefinition()` in
fabric-loader 0.19.5).

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| F0 | This plan | `docs/v0.5/design/ws-f.md` | - | protocol 2 |
| F1 | `FirstRun.isNew` + `FirstRun.read(Journal, configDir)` | `core/history/FirstRun` | `FirstRunTest`: MISSING and OK-empty → new; one entry of each kind (apply, undo, benchmark, legacy-import, baseline) → returning; CORRUPT, NEWER, UNREADABLE → returning; last-apply.json or pending.json present → returning; the 0.1.0 fixtures (`V010Fixtures`: last-apply.json, pending.json) and every `v040-written` set with a history.json/pending.json → returning (red: no `isNew`) | AC8.2 (unit), AC8.5 (after a restart) |
| F2 | FirstRunService | `client/FirstRunService` | `FirstRunServiceTest`: UNKNOWN until `load()`; `load()` on an empty config → NEW, `firstApplyPending()`; `applied()` → RETURNING; a `load()` finishing after `applied()` stays RETURNING; a read that throws → RETURNING; `load()` records its thread; the seam (red: skeleton answers UNKNOWN/false) | AC8.5, AC8.9 (unit), AC8.15 (unit half) |
| F3 | The guide notice + its keys + fixture set `ws-f` | `client/notice/FirstRunNoticeSource`, en_us.json, `src/test/resources/v050-written/ws-f/` | `FirstRunNoticeSourceTest`: key, FIRST_RUN, message, two actions with en_us.json's English, not dismissible; detail per policy (RIGTUNE ± a guide line, LAUNCHER with and without a guide line, PENDING never joins one and never says "manages" or "mod files"); `shows()` only while NEW with ≥ 1 appliable recommendation; Got it through NoticeCenter + AwarenessStore stores `firstrun.guide` and a new NoticeCenter over the same file hides it; it survives 10 more dismissals through the same `AwarenessStore.dismiss` path 0.4.0 has (unchanged since v0.4.0: `git diff v0.4.0` adds accessors only); the `ws-f` set compared/regenerated (red: skeleton returns null) | AC8.1 (unit), AC8.4 (unit), AC8.13, AC8.14 (guide) |
| F4 | FirstApplyScreen + RigTuneScreen's hook + keys | `client/ui/FirstApplyScreen`, `RigTuneScreen.applySelected`, en_us.json | `FirstApplyScreenTest` over the screen's static content builder: all APPLIED → "In effect now" + no-restart note, no restart note; mixed → both sections + restart note; downloading → downloading note, no no-restart note; REVERTED/DISCARDED/ABANDONED → "Undone or cancelled"; rows keep journal order inside a section and equal `HistoryScreen.describe`/`failureText`; LAUNCHER/PENDING add the no-mod-files note, RIGTUNE doesn't; entry missing with OK → `…applied.nothing` (while downloading: the downloading note instead); CORRUPT/NEWER/UNREADABLE/null → History's messages; the undo hint last; the loader runs `controller.history()` on the executor it's given, never the caller (queued executor); open narration = title, summary, restart outcome. Source checks: `new FirstApplyScreen(` appears only in `RigTuneScreen.applySelected`, which reads `firstApplyPending()` before `apply(chosen, entryId)` (red: no class) | AC8.6 (unit), AC8.7, AC8.9 (unit), AC8.11, AC8.14 (note), AC8.15 (unit) |
| F5 | HowItWorksScreen + keys | `client/ui/HowItWorksScreen`, en_us.json | `HowItWorksScreenTest`: the rows per policy (RIGTUNE: ticked, now, restart, mods, + a guide line row when non-null; LAUNCHER: ticked.settings, now, restart, + the guide line row; PENDING: ticked.settings, now, restart, no mods row), then preview and undo (red: no class) | AC8.14 (explainer) |
| F6 | FirstApplyGameTest main case (network off) | `gametest/FirstApplyGameTest` | CI, 3 legs: fresh run dir → NEW (or, when not the first entrypoint, the seam; under `CI` as the first entrypoint a non-fresh dir fails); the guide on the real RigTuneScreen at 1280×720, 854×480, 640×480 (scale 2) with `font.width` of the message within its room, screenshots, "…" and NoticeScreen with both actions at 640×480, Tab narrates message + detail; How it works and back; Apply (the real button) → FirstApplyScreen for the newest entry, its rows vs HistoryScreen's `changeRowText()` for that entry, notes vs the rows, screenshots; Undo this Apply → UndoScreen with a plan for the entry; History… → that entry selected; Done → RigTuneScreen with Apply's status line and no guide; a second Apply opens nothing; the seam back to NEW, Got it → awareness.json; a direct `controller.apply` retires NEW without the screen; FirstRunService.load ran off the render thread; X12 layout checks of both screens at the 3 sizes + 854×480@3; cleanup `undo(undoPlanFor(entryId))` | AC8.1, AC8.3, AC8.4, AC8.5, AC8.6, AC8.7 (the branch CI produces), AC8.8, AC8.9, AC8.15, AC8.16 |
| F7 | AC8.18 list height (code-deciding run first) | `gametest/FirstApplyGameTest` | **Code-deciding run** (under the game-test lock, before the constant is written): a throwaway branch from the `v0.4.0` tag with one measuring game-test class (the StubController's report, RigTune's network off, no notice, 640×480 scale 2) logs the RecommendationList's height → the constant `V040_LIST_HEIGHT_640x480`. Then: the stub report with only the guide as notice ≥ constant − 16, after Got it ≥ constant, under RIGTUNE and LAUNCHER (canned `modFiles()`), screenshots | AC8.18 |
| F8 | A11y walks | `A11yGameTest.walkFirstApply`, `walkHowItWorks` (their bodies and helpers below them only) | CI: FirstApplyScreen over A11yController's history fixture (entry e2: Render distance APPLIED, Lithium STAGED): Tab reaches every row in order and each narrates its text; the Tab after the last row leaves the list; open narration has title, summary and the restart note; high-contrast screenshot. HowItWorksScreen: the same walk (RIGTUNE and PENDING) | AC8.12 |
| F9 | Finish | this file, `docs/v0.5/verification/README.md` (first-apply section) | merge `origin/feat/v0.5.0`, targeted tests, CI green on every job and leg, screenshots looked at, code review | protocol 6 |

Cross-workstream (PLAN "Cross-workstream ACs"): AC8.14's real-policy check and AC8.3's 640×480 screenshot with P0.4's
opted-in line need WS-L1's policy and header line; the later of WS-F/WS-L1 to merge closes them. FirstApplyGameTest takes
that screenshot already (the stub's report, `modFilesByRigTune` on, after the network switch is restored), so it shows
the line as soon as WS-L1 lands. AC4b.6 (never for a NEW player) reads this workstream's real `status()`.

Cut order if time runs short (SPEC 8): the explainer, then live reload during downloads, then the launcher wording, then
the guide; the confirmation, its History row functions, the restart logic and the a11y walk are never cut.

---

# As built

## The 0.4.0 list-height baseline (AC8.18's code-deciding run)
- 2026-09-27, local, under the game-test lock: a throwaway worktree (in the scratch dir) on the local branch
  `throwaway/ws-f-v040-listheight` from the `v0.4.0` tag (01839d70), never pushed, with one game-test class,
  `ListHeightProbeGameTest`, as the only entrypoint: RigTune's network switched off (0.4.0's own way: `ClientSettings`
  + `settingsChanged()`), then `new RigTuneScreen(new TitleScreen(), new StubController(RigTuneClient::hardware))` with no
  notice, the list's height read from the screen's `ContainerObjectSelectionList`. `./gradlew :26.2:runClientGameTest`
  (26.2, Windows, GUI scale set per size). The probe's source is commit d3d4b89c on that local branch; the worktree was
  removed after the run and the branch is left for the coordinator to delete (a force delete is blocked by the hooks).
- Result (log lines `ListHeightProbe v0.4.0 …`): 640×480 scale 2 (320×240 scaled): list y 76, **height 76**, 4 header
  lines, no notice; 854×480 scale 2: height 100; 1280×720 scale 2: height 220; 854×480 scale 3: height 100. The 640×480
  screenshot shows the header's 4 lines (CPU, GPU, tier + display/rules, "Offline (network off in settings)…"), one
  recommendation visible, the 8-button footer in 3 rows. The arithmetic agrees (fa §1.5: footer top 168, status 156, list
  bottom 152; header bottom 30 + 4 × 10 + 2 = 72, list top 76).
- `RigTuneScreen.java` at `v0.4.0` and at this branch's base (a7613410) are identical (`diff` empty), so the constant
  holds for the RIGTUNE layout today; the check guards what C02 and P0.4 add.
- Constant: `FirstApplyGameTest.V040_LIST_HEIGHT_640X480 = 76`; the guide may cost one notice line (16 px): ≥ 60 with the
  guide, ≥ 76 once it's dismissed, under RIGTUNE and LAUNCHER.

## What landed (files)
- `core/history/FirstRun` (`isNew(state, entries, lastApplyExists, pendingExists)`, `isNew(Journal, configDir)`).
- `core/history/Journal`: one additive, marked package-private method, `holdsNoEntries()` (the coordinator's approved
  review L4): history.json read once for `FirstRun.isNew`, so a file that reads OK once and fails a second read can't
  make a returning player new.
- `client/FirstRunService` (fills WS-K's skeleton: `status()`, `firstApplyPending()`, `load()`, `applied(ApplyFacts)`;
  new `loadedOn()` and the test seam `forceStatusForTests(Status)`).
- `client/notice/FirstRunNoticeSource` (fills WS-K's skeleton; public `KEY`, `HOW`, `GOT_IT`, `notice(policy,
  guideLine)`; package-private `shows(...)`, `current(...)`).
- New `client/ui/FirstApplyScreen`, `client/ui/HowItWorksScreen` (its constructor takes the opt-in the guide read, so
  the guide and the page can't disagree).
- `client/ui/RigTuneScreen`: `applySelected` (6 lines) and the `ChangeRecorder` import, nothing else.
- en_us.json: 25 keys `rigtune.firstrun.*` after the anchor `rigtune.header.offline`, alphabetical.
- Tests: `FirstRunTest`, `FirstRunServiceTest`, `FirstRunNoticeSourceTest`, `FirstApplyScreenTest`, `HowItWorksScreenTest`;
  game tests `FirstApplyGameTest`, `A11yGameTest.walkFirstApply`/`walkHowItWorks` (+ the helper
  `highContrastScreenshot` below them; qualified class names instead of new imports, so the shared import block is
  untouched).
- Fixture set `src/test/resources/v050-written/ws-f/`: `awareness.json` (`dismissed: ["firstrun.guide"]`, written by
  `FirstRunNoticeSourceTest.theFixtureSetIsWhatThisVersionWrites` through `AwarenessStore.dismiss`), `expect.json`
  (`AwarenessStore` keeps `dismissed`, no `.bad`).
- `V05LangFamilies.firstRun`: stays empty: every `rigtune.firstrun.*` key is written out literally, so LangCheckTest needs
  no family.
- Untouched (AC8.10, hotspot rules): `ClientSettings`, `StartupNotices`, `RealController`, `RigTuneClient`,
  `RigTuneController`, `V05Services`, `V05Hooks`, `HistoryScreen`, `HistoryModel`, `UndoScreen`, `NoticeScreen`,
  `tools/footprint-budgets.json` (`git diff origin/feat/v0.5.0 HEAD` on them is empty); `Journal` gets only the method
  above.
- One `//? if >=26.3` block (X9): `FirstApplyGameTest.focusedNarration`, A11yGameTest's own idiom for
  `ScreenNarrationCollector.update`, which takes a `NarrationTrigger` only on 26.3. The mod's code has none.

## Deviations
- **"No restart needed" also needs nothing undone and one row in effect.** AC8.7 says the note shows iff no row is STAGED
  and no download runs. After Undo this Apply from the confirmation the rows read Undone/Cancelled, and saying they're in
  effect would then be false (X3), so the note also needs every row still applied (and at least one). Before an Undo (the
  case the AC describes) the two rules agree; `FirstApplyScreenTest.undoneOrCancelled` pins the difference.
- **The no-restart note's wording** is "Everything listed here is in effect now. No restart needed." instead of SPEC 8's
  "All of it is in effect now. No restart needed." (review M1, accepted by the coordinator): a setting Apply couldn't
  write isn't journaled, so after a partly failed Apply "all of it" would claim too much (X3). The note also stays away
  when the status reports something wasn't done (`FirstApplyScreen.reportsFailure`: `rigtune.status.some_failed`,
  `download_failed`, `busy`, `scan_failed` anywhere in the joined status), and that status row is drawn white instead of
  the applied green. So the full rule is: no row waiting for the restart, no download running, no failure reported,
  nothing undone, at least one row in effect.
- **The notes follow the rows**: "This Apply didn't change any mod files." only when every row is a setting row (review
  L5); the undo hint only when the entry is undoable (L7); the downloading note's text is "Mod downloads are still
  running; the ones that finish are added to this list." (a failed download adds nothing).
- **Downloads only, nothing recorded yet**: with the entry not in history.json while downloads run (an Apply of only
  AddMod/UpdateMod: nothing is journaled until they finish), the confirmation shows the downloading note instead of
  "Nothing was recorded for this Apply." (which would be false), then reloads when they finish.
- **Got it is checked by the source.** `NoticeBoard.select` always shows a notice that isn't dismissible (0.4's rule, for
  notices like the regression alert whose Got it is their own acknowledgement), so `FirstRunNoticeSource.current()` leaves
  the guide out once `firstrun.guide` is in awareness.json's `dismissed`. It reads that set (the same small file
  NoticeCenter reads on every `notices()` call) only when everything else says the guide would show, i.e. for a new player
  at a screen init; a returning player's `current()` does no I/O (`FirstRunNoticeSourceTest.theStoredDismissalHidesIt`).
- **The guide message is shortened** to "New? History… lets you undo each Apply." (the coordinator's decision, review
  L10: at least 20 % margin at 640×480 scale 2). SPEC-33's "New to RigTune? History… lets you undo each Apply." is 262 px
  in vanilla's font, 4 px under its 266 px room there (measured on all three legs); the new one is 206 px (22.6 % margin;
  measured locally on 26.2 and computed from the font's glyph advances, which gave 262 for the old one too), in rooms of
  266 / 280 / 365 px at 640×480 / 854×480 / 1280×720 scale 2 with no other notice. A "+N more" button (another notice at
  the same time) takes about 50 px, so 854×480 still fits (230 px left). A longer translation clips as every notice does:
  the full text stays in the tooltip, the narration and NoticeScreen.
- **The test seam** `FirstRunService.forceStatusForTests(Status)` is public production API, used only by
  FirstApplyGameTest (SPEC C6/SPEC-19's "restores the fresh state it needs through a test seam if it isn't first"), and
  a second time inside the test to exercise Got it after the first Apply (a fresh player's guide is gone once they apply).
- **AC8.16 and the entrypoint position**: SPEC-19 (amendment) says no class may depend on its position, while AC8.16 says a
  non-fresh dir fails under CI. FirstApplyGameTest does both: it fails a non-fresh dir only when it's the first
  `fabric-client-gametest` entrypoint under `CI` (`EntrypointContainer.getDefinition()`, fabric-loader 0.19.5, javap);
  anywhere else it logs a WARN and restores a new player through the seam.
- **The status line in the game test** is read reflectively from `RigTuneScreen.status` (a private field), since the
  hotspot rules leave RigTuneScreen's API as 0.4 had it (no getter added).
- **A11yGameTest**: the two walks use fully qualified class names for `FirstApplyScreen`, `HowItWorksScreen` and `Screen`
  instead of new imports, so WS-F's edit stays inside its own methods and the helper below them.

## Residuals (known, not fixed)
- A benchmark "Keep" records a journal entry without going through `apply`, so after it the guide stays until the next
  launch and the next Apply press still opens the confirmation (fa §2.1; harmless: the entry is real and the next launch
  reads it as returning).
- A player who deletes history.json (and has no last-apply.json or pending.json) is new again (fa §2.1).
- An Apply refused because downloads are still running would leave the player NEW; a new player can't have downloads
  running (they only start from an Apply, which retires NEW), so the button's confirmation can't open on a refusal in
  practice.
- The guide's line is clipped at 854×480 when another notice brings a "+N more" button (see Deviations); the full text is
  in the tooltip, the narration and NoticeScreen.
- ABANDONED ("Not applied") rows would be grouped under "Undone or cancelled" (review L5); they can't occur in the
  session the confirmation opens in (the helper marks them at the next exit).
- The seam's use in FirstApplyGameTest's Got it block means the in-game Got it is exercised on a player made new again
  after the first Apply (the fresh-player Got it path is the same code: `NoticeCenter.act` → `act(GOT_IT)` →
  `dismissNotice`).

## UNVERIFIED
- AC8.17 (the dev-PC fresh-instance run at the three sizes, one Narrator pass, the 26.3 Apply half, the read-only copy of
  the real instance's config/rigtune): Phase 5's rolling P5 run after WS-F merges (PLAN "Phase 5").
- AC8.14 with P0.4's real policy and wording, and AC8.3's 640×480 screenshot with the opted-in line: WS-L1's
  `LauncherModText.guideLine` and header line aren't merged yet (the stubs answer null/RIGTUNE). Tested here with stub
  policies and canned sentences; FirstApplyGameTest's `firstapply-guide-optedin-640x480-scale2` and `firstapply-list-*`
  screenshots pick the real ones up when WS-L1 lands. Closes in the later of WS-F/WS-L1 (PLAN "Cross-workstream ACs").
- AC8.4's compat040 half: WS-E's `compat040` interpreter isn't merged; the `ws-f` set's `expect.json` asks it for
  `AwarenessStore` keeping `dismissed` and no `.bad`. There is no check kind yet for "`dismissed` still contains
  `firstrun.guide` after 0.4.0 writes 10 more"; the unit half (`FirstRunNoticeSourceTest.theDismissalSurvivesTenMore`)
  runs that on `AwarenessStore.dismiss`, which `git diff v0.4.0` shows unchanged (only accessors were added). A
  `dismissedContains` check kind would close it in compat040 (WS-E, through the coordinator).
- compat030: run locally 2026-09-28 over the `v040-written` sets with `firstrun.guide` merged into `ws-w`'s awareness.json
  (the v0.5 union rule): RESULT PASS, "0.3.0 reading them changed no file: 9 file(s) unchanged" (0.3.0 never reads
  awareness.json).

## Docs (for the docs workstream)
- **README, Usage** (next to the Apply/Preview/History paragraph): "The first time you use RigTune, a notice above the
  list explains what Apply changes and how to undo it (How it works…; Got it hides it). After your first Apply, RigTune
  shows that Apply's changes: what's in effect now, what waits for the next restart (and only then does it ask for one),
  with Undo this Apply and History… right there. Neither shows again once you've applied anything, and neither shows if
  you used RigTune before."
- **README, key areas**: add `firstrun` (the first-run guide, How Apply works, Your first Apply).
- **CHANGELOG [0.5.0], Added**: "First-time Apply guide and confirmation (C02): a one-time notice for new players before
  their first Apply (How it works, Got it), and after the first press of Apply a screen listing that Apply's changes as
  History shows them, grouped into in effect now / at the next restart, with Undo this Apply. Nothing new is stored: new
  players are those with no RigTune history; Got it is an ordinary notice dismissal."
- **DESIGN.md, new section "First-time Apply (0.5)"**: Who is new comes from disk, not a flag (`core/history/FirstRun`:
  history.json missing or empty and no last-apply.json or pending.json; an unreadable history counts as returning).
  `client/FirstRunService` keeps UNKNOWN/NEW/RETURNING in memory: read once on `Probes.EXECUTOR` by the v0.5 start hook
  (`compareAndSet`, so a slow read can't undo an Apply), set RETURNING by the after-apply hook on every Apply by any path;
  settings.json gets nothing. The guide is `FirstRunNoticeSource` at `NoticePriority.FIRST_RUN` (5th): shown while NEW with
  something appliable, not dismissible except by Got it (an awareness.json dismissal the source checks itself, since the
  notice line always shows a notice that can't be dismissed); its detail follows `modFiles()` and joins P0.4's
  `LauncherModText.guideLine`, never under PENDING. `HowItWorksScreen` is a static RowList page. `FirstApplyScreen` is
  opened only by RigTuneScreen's Apply button (`firstApplyPending()` read before `apply(chosen, entryId)` with a pre-minted
  entry id), never by `RealController.apply`, so profile switches, Try it and stutter fixes retire NEW without it. It
  lists the entry's `HistoryModel` changes with HistoryScreen's own row functions at History's widths, grouped by status,
  with notes that never imply an unneeded restart, reads the history off-thread and reloads when downloads finish or the
  player comes back from Undo/History. Data flow step 4 (Apply): "…and a new player's first Apply press opens Your first
  Apply for that entry."
- **DESIGN.md, Accessibility**: both new screens are RowLists whose every row is a Tab stop (FirstApplyScreen's section
  headings too); the confirmation narrates its title, summary and restart outcome when it opens.

## Self-review (code-reviewer subagent on `git diff origin/feat/v0.5.0 HEAD -- src` at 5ce05adb)
No high findings; 3 medium, 5 low, sent to the coordinator with the dispositions below (2026-09-28). Two earlier
reviewer runs ended without handing their report back; the third wrote it to the scratch dir.
- M1 (the no-restart note after a partly failed Apply): reworded, see Deviations. Fixed in 4a2894f2.
- M2 (the seam's NEW leaking to later classes after a mid-test failure): the finally forces RETURNING. Fixed.
- M3 (Got it's dismissal left in awareness.json; a reused dev run dir): the finally takes out a dismissal the test
  added, and the non-fresh path clears a leftover one. Fixed. (The dev `runClientGameTest` run dir did come back fresh
  between the local runs here; the fix makes the class safe either way.)
- L4 (the no-mod-files note re-read `modFiles()` on each reload): the policy is read once, when the screen opens right
  after the Apply. Fixed.
- L5 (ABANDONED rows under "Undone or cancelled"): not changed: ABANDONED needs a helper run at exit, and the
  confirmation opens once, right after the Apply, in the same session. Residual below.
- L6 (HowItWorksScreen reads the opt-in from `ClientSettings.shared(FabricLoader configDir)` while the notice reads
  `controller.settings()`): proposed to leave; the coordinator's decisions asked for one source, so the page now takes the
  opt-in from the guide that opens it (`controller.settings().modFilesByRigTune`).
- L7 (A11yGameTest's high-contrast helper): try/finally. Fixed.
- L8 (the game test's note check looser than the screen's rule): mirrors the rule now, read with the screen. Fixed.

## The coordinator's decisions (COORDINATOR-DECISIONS.md, two review rounds) and what was done
- First round (0 H, 3 M, 9 L): M1 reworded + the note kept away on a reported failure (above); M2 the seam path and the end
  of `gotIt()` take `firstrun.guide` back out of awareness.json through `AwarenessStore.update`, and the finally does too if
  the test added it (passes on a re-used run dir); M3 this file and the verification README section; L4 `Journal.holdsNoEntries`
  (one read); L5 the no-mod-files note only with setting rows; L6 the status row white when it reports a failure; L7 the undo
  hint only when undoable; L8 a reload keeps the old rows, scroll and focused row while `view != null`; L9 the game test
  reads `downloading()` and the notes in one `computeOnClient`, checks `!screen.loading()` and mirrors the full no-restart
  rule; L10 the shorter guide message; L11 below; L12 HowItWorksScreen's opt-in from the guide, `Palette.of(0xFFFFFFFF)`
  for its text, the downloading wording, the `//? if` note above.
- Second round (0 H, 1 M, 5 L): M1 the finally forces RETURNING; L2 as M2 above; L3 the notes keep the `downloading()` value
  of the read they go with until the new read arrives (no brief false "No restart needed" or "Nothing was recorded"); L4 a new
  controller status replaces Apply's only when downloads were running and just stopped (a rebuild's own status notes aren't
  this Apply's); L5 the high-contrast helper's try/finally; L6 the history-load waits are 600 ticks.
- L11, the 26.3 Vulkan leg's time: the client game-test step took 464 s (run 36333050582) and 460 s (36334013962), jobs
  9m42s / 9m39s; FirstApplyGameTest's share 12.3 s. 26.2 OpenGL: 512 / 557 s; 26.3 OpenGL: 481 / 513 s. All well under the
  14-minute cap: no need for the split.

## Footprint deltas (against ws-k.md's per-leg baseline, run 36310249248)
From CI run 36322454300 (this branch, merged with `origin/feat/v0.5.0` at de597c28: WS-P2's, WS-L1's and WS-S's early
merges are in it too), `footprint-<mc>-<backend>.json`:

| leg | renderThreadInitCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference | v05RenderThreadResolve / holder made on |
|---|---|---|---|---|---|
| 26.2 OpenGL | 108.45 (+26.3) | 61.96 (+25.6) | 182.98 (+47.5) | 1.579 (+0.098) | null / RigTune worker |
| 26.3 OpenGL | 98.16 (+16.0) | 43.82 (+16.8) | 176.90 (+23.7) | 1.623 (−0.123) | null / RigTune worker |
| 26.3 Vulkan | 109.11 (−10.9) | 31.13 (−8.8) | 184.99 (−15.7) | 1.599 (+0.064) | null / RigTune worker |

Reading: every value is inside its budget (150 / 141 / 300 / 2.05) and inside the runner-to-runner spread ws-k.md records
(renderThreadInitCpuMs 63.5-112.9 across runs of near-identical code; the deltas change sign between legs). WS-F adds no
render-thread init work (FirstRunService is made by the start hook's worker task; the X4 flag stayed null on all three
legs), no tick or frame work, and to `workerCpuMs5s` only `FirstRunService.load` (history.json read, two `Files.exists`).

## CI runs and what was looked at
- **36322454300** (a1e2f35f: F0-F8 + `origin/feat/v0.5.0` at de597c28): all 8 jobs green (java both nodes: 1997 tests,
  2 skipped, 0 failures each; the 3 client game-test legs; python, rules, gametest-matrix). Downloaded
  `gametest-screenshots-26.2-OpenGL` and `-26.3-Vulkan`, the three `gametest-logs-*` and `footprint-*`, `test-reports`.
  Looked at: `firstapply-guide-{1280x720,854x480,640x480}-scale2` (the guide line, "…" at 640×480; the first title
  screen's suggestions toast covered part of the 640×480 line, so the test now clears the toasts before the screenshots),
  `firstapply-guide-noticescreen-640x480-scale2` (both actions), `firstapply-how-*` (paragraphs wrap, scroll bar at
  640×480), `firstapply-confirmation-{1280x720,640x480,854x480}` (8 applied + 1 staged Sodium row, restart note,
  undo hint; a focused button's tooltip covered a row at 640×480 and 854×480, so the layout screenshots now clear the
  focus first), `firstapply-history-854x480-scale2` (the same 9 rows), `firstapply-list-{rigtune,launcher}-
  {guide,dismissed}-640x480-scale2` (60 px with the guide, 76 px without), `firstapply-guide-optedin-640x480-scale2`
  (network on; no opted-in line until WS-L1), `a11y-first-apply-focus`, `a11y-hc-first-apply`, `a11y-how-it-works-focus`,
  `a11y-hc-how-it-works` (high-contrast colours). Logs: on every leg the fresh path (no seam WARN), the guide's message
  262 px in 365/280/266 px rooms with 0 other notices, the first Apply's statuses `[APPLIED ×8, STAGED]`, History's rows
  equal to the confirmation's, both undos done, the list heights, A11y's walks (7, 6 and 5 rows).

- **36328625684** (43e73fbf: + the toast/focus screenshot fix, the reload fix and `origin/feat/v0.5.0` at 3a67ef64):
  all 8 jobs green (java: 2171 tests, 2 skipped, 0 failures on each node). Looked at the 26.2 OpenGL
  `firstapply-guide-640x480-scale2` (the guide line clear, "…" beside it) and `firstapply-confirmation-640x480-scale2`
  (no tooltip over the rows now; the staged row scrolls below the fold at 320×240, the list's scroll bar shows it); logs
  on all 3 legs: the fresh path, FirstApplyGameTest 11.1-11.6 s, A11yGameTest's walks. Footprint: renderThreadInitCpuMs
  109.92 / 94.04 / 86.89, clientStartedWallMs 42.71 / 30.67 / 46.09, workerCpuMs5s 158.32 / 170.58 / 194.80,
  tickHookOnVsReference 1.445 / 1.457 / 1.467 (26.2 GL / 26.3 GL / 26.3 VK), the X4 flag null, the holder made on the
  RigTune worker.
- **36333050582** (e1ac2d7a: the review fixes + `origin/feat/v0.5.0` at 690b8f4c, WS-R's r17 included): all 8 jobs green
  (java: 2189 tests, 2 skipped, 0 failures on each node). Looked at the 26.2 OpenGL `firstapply-confirmation-1280x720-scale2`
  (8 applied + 1 staged, the restart note, the undo hint), `firstapply-after-854x480-scale2` (Apply's status line kept, no
  guide, "Discard pending" for the staged op); logs: the fresh path on all 3 legs, FirstApplyGameTest 12.3-13.6 s,
  A11yGameTest's walks. Footprint: renderThreadInitCpuMs 98.74 / 116.54 / 110.41, clientStartedWallMs 48.62 / 19.95 /
  40.51, workerCpuMs5s 209.44 / 205.08 / 210.99, tickHookOnVsReference 1.692 / 1.557 / 1.565 (26.2 GL / 26.3 GL / 26.3 VK),
  the X4 flag null, the holder made on the RigTune worker (every value inside its budget; the spread between this run
  and the two before it on nearly the same WS-F code is ±20 ms and ±50 ms of workerCpuMs5s, the other workstreams'
  merges included).

## AC table
| AC | status | evidence |
|---|---|---|
| AC8.1 (fresh instance: the guide is the top notice with How it works and Got it) | verified | FirstApplyGameTest `newPlayer` + `guideAtEverySize` on 3 legs (fresh run dir → NEW, shownNotice = firstrun.guide at 3 sizes); FirstRunNoticeSourceTest.whenItShows/theGuide |
| AC8.2 (any history entry, last-apply.json, pending.json, or an unreadable history: neither piece) | verified (unit) | FirstRunTest (each kind, CORRUPT/NEWER/UNREADABLE, 0.1.0 fixtures, every v040-written set); FirstRunServiceTest.aReturningPlayer; `shows()`/`firstApplyPending()` false for RETURNING |
| AC8.3 (message not clipped at the 3 sizes; "…" + NoticeScreen below 400 px; screenshots) | verified; the opted-in-line screenshot closes with WS-L1 | FirstApplyGameTest: `font.width` of the message within its room at each size on 3 legs (262 px in 365/280/266 px before the review's shortening; 206 px after it, final run); "…" at 640×480; NoticeScreen lists both actions; screenshots `firstapply-guide-*`, `firstapply-guide-noticescreen-640x480-scale2`; `firstapply-guide-optedin-640x480-scale2` taken (no opted-in line until WS-L1's header line lands) |
| AC8.4 (Got it hides it for good, in awareness.json, survives 0.4.0's AwarenessStore writing 10 more) | verified (unit + game); compat040 half UNVERIFIED | FirstRunNoticeSourceTest.gotItHidesTheGuideForGood/theStoredDismissalHidesIt/theDismissalSurvivesTenMore; FirstApplyGameTest `gotIt` (awareness.json `dismissed` has firstrun.guide; still hidden on the next screen); set ws-f + expect.json (compat040 not merged) |
| AC8.5 (after the first Apply by any path the guide is gone, in the session and after a restart) | verified | FirstApplyGameTest: after the Apply, back on the RigTune screen, no guide and no stored dismissal; `directApplyRetiresNew`; FirstRunServiceTest (applied → RETURNING; a late load stays RETURNING); FirstRunTest (an apply entry → returning after a restart) |
| AC8.6 (the Apply button opens the confirmation for the entry Apply journaled; rows = History's rows at the same size) | verified | FirstApplyGameTest `confirmation`: the entry id is the newest history entry's; statuses grouped from the entry; `changeRowText()` equal to HistoryScreen's for that entry at 854×480 on 3 legs; FirstApplyScreenTest (row functions are HistoryScreen's) |
| AC8.7 (restart note iff STAGED; no-restart iff none and no download; downloading note, reload) | verified (unit; game for CI's branch) | FirstApplyScreenTest (all branches incl. downloads and downloads-only); FirstApplyGameTest on 3 legs: 1 STAGED row → restart note, no no-restart note. See Deviations (nothing undone) |
| AC8.8 (Undo this Apply → UndoScreen for the entry; History… with the entry selected; Done/Esc → RigTuneScreen with Apply's status line) | verified | FirstApplyGameTest `confirmation` on 3 legs |
| AC8.9 (at most once per session, never for RETURNING; profile switch, stutter fix, Try it, direct apply never open it but retire the guide) | verified | FirstApplyScreenTest.onlyTheApplyButtonOpensIt (source check: only RigTuneScreen.applySelected constructs it, after reading firstApplyPending before apply(chosen, entryId)); FirstApplyGameTest `secondApplyOpensNothing`, `directApplyRetiresNew`; the after-apply hook runs for every Apply path (V05HooksTest, ws-k.md 11c) |
| AC8.10 (no settings.json field, no new file under config/rigtune/) | verified (review + unit) | `git diff origin/feat/v0.5.0 HEAD` on ClientSettings/StartupNotices empty; FirstRun reads only; the one write is a notice dismissal in awareness.json |
| AC8.11 (entry missing / history unreadable: Apply's status + the matching message) | verified (unit) | FirstApplyScreenTest.withoutTheEntry |
| AC8.12 (Tab stops that narrate, Tab after the last row leaves; the guide narrates message + detail; open narration; PaletteTest) | verified | A11yGameTest.walkFirstApply (7 rows) / walkHowItWorks (6 and 5 rows) on 3 legs; FirstApplyGameTest `tabUntilNarrates` (message + detail); FirstApplyScreenTest.theOpenNarration + the walk's open-narration check; PaletteTest green |
| AC8.13 (keys under rigtune.firstrun.*; LangCheckTest, WordingTest, PseudoLocaleTest) | verified | CI java job both nodes |
| AC8.14 (guide detail, explainer rows, confirmation note follow modFiles(); nothing about mod files under PENDING) | verified with stub policies; the real policy closes with WS-L1 | FirstRunNoticeSourceTest.theDetailFollowsWhoChangesModFiles, HowItWorksScreenTest, FirstApplyScreenTest.theLauncherManagesTheMods; A11yGameTest.walkHowItWorks (RIGTUNE vs PENDING) |
| AC8.15 (FootprintGameTest unchanged budgets; onTick unchanged; load and the history read on Probes.EXECUTOR) | verified | footprint on 3 legs (flag null, holder made on RigTune worker); FirstApplyGameTest checks `loadedOn()` starts with "RigTune worker"; FirstApplyScreenTest.theHistoryIsReadOnTheExecutor (queued executor); FirstRunServiceTest.theLoadRecordsItsThread; RigTuneClient untouched |
| AC8.16 (first entrypoint, fresh dir, network off on 3 legs, cleanup with undo) | verified | FirstApplyGameTest on 3 legs (GameTestNet off; both applies undone through `undo(undoPlanFor(id))`); first in fabric.mod.json (WS-K) |
| AC8.17 (dev-PC fresh-instance run, Narrator pass, 26.3 Apply half, real-instance copy) | UNVERIFIED (Phase 5, P5 agent) | — |
| AC8.18 (640×480@2 list ≥ 0.4.0's height − 16 with the guide, ≥ 0.4.0's once dismissed, RIGTUNE and LAUNCHER) | verified | 0.4.0's 76 px measured on the throwaway branch (above); FirstApplyGameTest `listHeight` on 3 legs: 60 / 76 / 60 / 76 px |
| AC4b.6's C02 half (the real `status()`) | ready for WS-L1 | FirstRunService.status() is live (UNKNOWN → NEW/RETURNING) |
