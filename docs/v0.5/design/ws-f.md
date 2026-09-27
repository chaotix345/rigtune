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
