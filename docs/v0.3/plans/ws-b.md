# WS-B: History screen + per-entry undo + failed-op reasons — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (this plan is executed inline by the WS-B agent), superpowers:test-driven-development for every task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A "What RigTune changed" History screen (list, details, statuses, failure reasons), per-entry "Undo this" with the B-H1 "superseded" rule, the once-per-apply WARN lines for failed helper ops, and the footer change that moves Undo last/Undo all into the History screen.

**Architecture:** Pure logic in `core/history` (UndoPlanner.planEntry + superseded pass, HistoryModel view model, ApplyFailures reader, Journal.state()); `client/` gets HistoryScreen, an entry-id path in UndoScreen, delegating RealController/RigTuneController/UndoService methods, the startup WARN lines in RigTunePreLaunch, and HistoryGameTest.

**Tech Stack:** Java 25, Fabric Loom 1.17 (Mojang names), Stonecutter 26.2/26.3 (no version-specific code expected: the GUI API is identical), Gson, JUnit 5.

**Spec:** docs/v0.3/SPEC.md items 6 and 3e, and its "Amendments from the plan review" (B-H1, B-M1, B-M2, B-M3, B-L1, X-M2), which override the item text. docs/v0.3/PLAN.md (Global Constraints, Hotspots, WS-B). docs/v0.3/plan-review.md (WS-B section).

## Global Constraints
- Worktree `C:/Dev/Worktrees/rigtune-history`, branch `feat/history`; never commit to `main`/`feat/v0.3.0`; no force push, no rebase after pushing (merge `origin/feat/v0.3.0`).
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; `./gradlew build` green on 26.2 and 26.3; committed Stonecutter version 26.2.
- Don't edit build.gradle, stonecutter.gradle, settings.gradle, gradle.properties, .github/workflows/*.
- `core/` has no Minecraft imports. Journal is helper-safe: core + Gson only; HelperLauncherTest must pass.
- No history.json / last-apply.json / pending.json format change. `rigtune.json` gains one optional field (`lastWarnedApply`) that 0.2.0's Gson ignores.
- UI text only through `assets/rigtune/lang/en_us.json`, new keys under `rigtune.history.*`, inserted in alphabetical position, never appended at the end of the file. Existing `rigtune.screen.undo_last`/`undo_all` keys are reused by the History screen's buttons.
- Keep `UndoScreen(Screen, RigTuneController, boolean)` (the e2eUndo driver uses it).
- New RigTuneController methods are `default` methods under a `// v0.3 (WS-B)` comment.
- Real instance: read `C:/Users/Admin/AppData/Roaming/ModrinthApp/profiles/Fabric 26.2/config/rigtune/last-apply.json` read-only; never write under %APPDATA%\ModrinthApp. Scratch: `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/4bf644a6-501a-4093-a81e-9e29a6010059/scratchpad/ws-b/`.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`.
- Local game tests optional (SPEC X-M1); CI's Linux legs are the evidence once WS-0 has merged.

## File map
| file | change |
|---|---|
| core/history/UndoPlanner.java | `planEntry`, `undoable`, superseded pass in `build()`, two reason constants |
| core/history/Journal.java | public `State` enum (+ `UNREADABLE`), `state()` |
| core/history/ApplyFailures.java (new) | failed/abandoned ops of last-apply.json: attempt n = attempts + 1, shortened reason, WARN line text |
| core/history/HistoryModel.java (new) | view model: entries newest first, kind/status label keys, change rows (update pairs), counts, failures, undoable |
| client/undo/UndoService.java | `planEntry(entryId)`, `history(lastApply, dirs)` |
| client/RealController.java | `undoPlanFor(entryId)`, `history()` (delegating) |
| client/ui/RigTuneController.java | defaults `undoPlanFor`, `history` |
| client/ui/UndoScreen.java | `UndoScreen(Screen, RigTuneController, String entryId)` |
| client/ui/HistoryScreen.java (new) | the screen |
| client/ui/RigTuneScreen.java | footer: "History…" replaces Undo last/Undo all |
| client/RigTunePreLaunch.java, client/ClientState.java | WARN lines once per `finishedAt` (`lastWarnedApply`) |
| lang/en_us.json | `rigtune.history.*` block after `rigtune.header.display_rules` |
| src/test/resources/v010/real-instance/{last-apply.json,README.md} (new) | templated copy of the user's real 0.1.0 last-apply.json |
| src/test/.../core/history/V010Fixtures.java | token segments stop at `:` (the real fixture's message has `x.jar.disabled: The process…`) |
| tests | UndoPlannerTest (+planEntry/superseded), HistoryModelTest, ApplyFailuresTest, JournalTest (+state), RigTunePreLaunchTest, ClientStateTest |
| src/gametest/.../HistoryGameTest.java (new) + fabric.mod.json entry; UiGameTest (press History… first) | |
| docs/v0.3/design/ws-b.md (new) | design notes, API for WS-H |

---

### Task 1: Journal.state()
**Files:** Modify `core/history/Journal.java`; Test `core/history/JournalTest.java`.
**Produces:** `public enum Journal.State { MISSING, OK, CORRUPT, NEWER, UNREADABLE }`, `public Journal.State state()` (never throws; UNREADABLE = an I/O error reading an existing file, logged through `Log`).
- [ ] Tests: `stateOfAMissingFile` (MISSING), `stateAfterAnUpdate` (OK), `stateOfACorruptFile` (`{not json` → CORRUPT, and no `.bad` file is made by reading), `stateOfANewerFile` (`{"formatVersion":2,"entries":[]}` → NEWER), `stateWhenTheFileCantBeRead` (history.json is a directory → UNREADABLE).
- [ ] Run `./gradlew :26.2:test --tests "*JournalTest"` → FAIL (no `state()`).
- [ ] Make the private enum public, add `UNREADABLE` (never returned by `read()`), add:
```java
public State state() {
	try {
		return read().state();
	} catch (IOException e) {
		log.warn("Could not read " + file, e);
		return State.UNREADABLE;
	}
}
```
- [ ] Tests pass; HelperLauncherTest passes. Commit `feat(history): Journal.state() for the History screen (B-M2)`.

### Task 2: planEntry, undoable and the superseded pass (AC6.1, B-H1)
**Files:** Modify `core/history/UndoPlanner.java`; Test `core/history/UndoPlannerTest.java`.
**Produces:**
- `public static Result planEntry(List<JournalEntry> entries, List<Op> pending, State state, String entryId)` — unknown id or an undo entry → `new UndoPlan(false, null, List.of())`; no candidate change → the same empty plan; else `build(ctx, candidates of that entry, …, all=false, undoOf=entryId, at=entry.at())`.
- `public static Set<String> undoable(List<JournalEntry> entries)` — ids of non-undo entries with at least one candidate change (STAGED, or APPLIED and not being reverted) (B-L1 "fully undone").
- `static final String SUPERSEDED = "Changed again by a later apply"`, `SUPERSEDED_GROUP = "Goes with a change a later apply changed again"`.
- Superseded pass, first thing in `build()` (so `recheck()` runs it too): a selected change is SKIP(SUPERSEDED) when a change of a later (higher index) non-undo entry, not itself selected, is STAGED or (APPLIED and not being reverted) and touches the same thing: same settings key; or file changes whose `{file, resultFile}` sets intersect; or the later change is an enable with the selected change's (non-null) `modId`. Every other selected change sharing a group with it (its journal `group`, or for a STAGED change the `group` of its op in pending.json) is SKIP(SUPERSEDED_GROUP). Only the rest go to planStaged/planSettings/planFiles. Changes of the same plan never supersede each other, so Undo everything is unchanged and Undo last only changes in its fall-through case.
- [ ] Failing tests (helpers `entryOf(id)` → `UndoPlanner.planEntry(entries, pending, state, id)`):
  - `planEntryOfTheNewestEqualsUndoLast` (same items and same script maps/op counts as `last()`),
  - `planEntrySkipsSettingsALaterApplyChangedAgain` (e1 rd 12→16, e2 rd 16→20 APPLIED, current 20 → e1's change SKIP with SUPERSEDED; `script().immediate()` empty),
  - `planEntryRevertsAnOlderAdditionStillPresent` (e1 adds a.jar, e2 adds b.jar; planEntry(e1) → REVERT a.jar, one DISABLE_FILE op for a.jar),
  - `planEntryOfAnUndoneEntryHasNothingToDo` (items empty, undoOf null),
  - `planEntryOfAStagedOnlyEntryDiscardsItsGroup` (DISCARD_STAGED with the op ids),
  - `planEntryOfAnUnknownIdIsEmpty`, `planEntryOfAnUndoEntryIsEmpty`,
  - `planEntrySkipsASameNameUpdateChain` (e1: disable mod.jar→mod.jar.disabled + enable mod.jar, group g1; e2: disable mod.jar→mod.jar.disabled.1 + enable mod.jar, g2; both e1 changes SKIP; no file ops),
  - `planEntrySkipsAnAppliedPatchAStagedOneChangedAgain` (e1 sodium.x 1→2 APPLIED; e2 sodium.x 2→3 STAGED op-2 in pending → e1 SKIP SUPERSEDED; `script().staged()` empty),
  - `planEntryUndoesAnOlderEntryOnceTheLaterOneWasUndone` (e2 rd REVERTED by u1 → planEntry(e1) REVERTs rd),
  - `planEntrySkipsAGroupMateOfASupersededChange` (SUPERSEDED_GROUP),
  - `laterEnableOfTheSameModSupersedes` (e1 disables x.jar modId x; e2 adds x2.jar modId x → SKIP),
  - `recheckSkipsAChangeSupersededSinceItWasShown`,
  - `undoEverythingIsNotAffectedBySuperseding` (chain e1 rd 12→16, e2 16→20 → all() reverts both to 12),
  - `undoableListsEntriesWithSomethingLeft` (undo entries, fully reverted and discarded-only entries excluded).
- [ ] Run → FAIL. Implement. Run the whole `UndoPlannerTest` + `UndoServiceTest` → PASS. Commit `feat(undo): per-entry undo plans and the superseded rule (AC6.1, B-H1)`.

### Task 3: last-apply.json failures + WARN lines (AC3.5, B-M1)
**Files:** Create `core/history/ApplyFailures.java`, `src/test/resources/v010/real-instance/last-apply.json` + `README.md`; Modify `V010Fixtures.java` (segment regex `[^"/\s:]+`), `client/ClientState.java` (`public volatile String lastWarnedApply;`), `client/RigTunePreLaunch.java`; Tests `core/history/ApplyFailuresTest.java`, `client/RigTunePreLaunchTest.java`.
**Produces:**
```java
public final class ApplyFailures {
	// status FAILED (still staged, retried) or ABANDONED; attempt = op.attempts + 1 (the op records the count from before the run);
	// target = mod id (enable) or file name; reason = the helper's message with the given folders' paths cut to file names.
	public record Failure(String opId, ApplyResult.Status status, PendingActions.Type type, String target, String reason, int attempt) {}
	public static List<Failure> of(ApplyResult result, List<Path> dirs);
	public static Map<String, Failure> byOpId(ApplyResult result, List<Path> dirs);
	public static String warnLine(Failure failure); // English, for latest.log
}
```
`RigTunePreLaunch.failureWarnings(ApplyResult, String lastWarned, List<Path> dirs) → List<String>` (empty when `finishedAt` equals `lastWarned`) and `warnOnce(Path configDir, Path modsDir, ClientState state, Consumer<String> log)` (logs, then remembers `finishedAt` and saves); called from `onPreLaunch` after `readState`.
- [ ] Make the fixture: a scratch Python script reads the real file read-only, replaces the instance root `C:\Users\Admin\AppData\Roaming\ModrinthApp\profiles\Fabric 26.2` (JSON-escaped) with `${INSTANCE}` and the rest of each such path's `\` with `/`, in `path`/`from`/`to` and inside `message`; asserts no `Admin`, `C:\\` or `Users` remains; writes with `\n`. README records the source, date, original sha256 and the edits.
- [ ] Failing tests: `theRealV010FailureGivesOneWarningPerFailedOp` (2 lines: `DISABLE_FILE fabric-26.2.jar`, `ENABLE_FILE distanthorizons`, both "attempt 1 of 3", reason names `fabric-26.2.jar` but no folder path), `theHandWrittenFixtureHasOneFailure` (ferritecore), `abandonedOpsSayNotApplied`, `okResultsGiveNothing`, `pathsOutsideTheFoldersAreKept`; RigTunePreLaunchTest `warnsOncePerFinishedAt` (first call logs 2, second 0, a new finishedAt logs again, `lastWarnedApply` saved), `noLastApplyNoWarning`.
- [ ] Implement; tests pass; commit `feat(history): log failed helper ops once per apply (3e, AC3.5)`.

### Task 4: HistoryModel view model (AC6.2)
**Files:** Create `core/history/HistoryModel.java`; Test `core/history/HistoryModelTest.java`.
**Produces:**
```java
public final class HistoryModel {
	public interface Labels { default String label(String key) {…} default String value(String key, String value) {…}; static Labels of(UndoPlanner.State s); }
	public enum Row { SETTING, ADDED, DISABLED, REENABLED, UPDATED }
	public record Change(Row row, List<String> changeIds, String status, String statusKey, String label, String before, String after,
			String file, String newFile, String modId, ApplyFailures.Failure failure) {}
	public record Entry(String id, String kind, String kindKey, String at, String rigtuneVersion, String mcVersion, String undoOf,
			String undoOfAt, int settings, int mods, boolean undoable, List<Change> changes) {}
	public record View(Journal.State state, List<Entry> entries) {}  // entries newest first
	public static View build(Journal.State state, List<JournalEntry> entries, Map<String, ApplyFailures.Failure> failures, Labels labels);
	public static String statusKey(String status); // rigtune.history.status.{applied,staged,abandoned,discarded,reverted,unknown}
	public static String kindKey(String kind);     // rigtune.history.kind.{apply,benchmark,undo,legacy_import,unknown}
}
```
Rows: setting → SETTING (label/before/after through Labels); enable → ADDED (REENABLED when it `reverts` something); disable → DISABLED; a disable + an ADDED enable of one entry with the same non-null group and mod id and the same status → one UPDATED row (file → newFile). Failures attach to STAGED (FAILED result) and ABANDONED (ABANDONED result) changes by op id. Counts: settings = SETTING rows, mods = other rows.
- [ ] Failing tests: `everyStatusHasALabelKey`, `everyKindHasALabelKey`, `entriesAreNewestFirst`, `countsSettingsAndMods` ("3 settings, 2 mods" with an update pair counted once), `anUpdateIsOneRow`, `anUndoReEnables`, `aFailedStagedChangeCarriesTheReason` (attempt 2 of 3), `anAbandonedChangeCarriesTheReason`, `undoableFollowsThePlanner`, `anUndoEntryNamesWhatItUndid`, `labelsAreUsed`.
- [ ] Implement; pass; commit `feat(history): history view model (AC6.2)`.

### Task 5: controller/service wiring + UndoScreen entry path
**Files:** Modify `client/undo/UndoService.java` (`planEntry`, `history`), `client/RealController.java` (`undoPlanFor`, `history`, `gameState()` helper), `client/ui/RigTuneController.java` (defaults), `client/ui/UndoScreen.java` (new constructor, subtitle); Test `client/undo/UndoServiceTest.java` (`planEntryPlansThatEntry`, `historyReadsStateEntriesAndFailures`, `aNewerHistoryHasNoEntryPlan`).
**Produces (for WS-H, B-M3):** `RigTuneController.undoPlanFor(String entryId) → @Nullable UndoPlan` (unavailable "rigtune.undo.busy" while downloading, "rigtune.undo.unavailable" for a newer history) then `controller.undo(plan)` exactly as Undo last; and `new UndoScreen(parent, controller, entryId)` whose Confirm does that. `RigTuneController.history() → @Nullable HistoryModel.View` (off the render thread).
- [ ] Tests → FAIL → implement → PASS; commit `feat(undo): plan and carry out the undo of one history entry`.

### Task 6: HistoryScreen, footer, lang keys
**Files:** Create `client/ui/HistoryScreen.java`; Modify `client/ui/RigTuneScreen.java` (History… button in the Undo buttons' place), `lang/en_us.json`.
- Layout: title + subtitle; one list (accordion): each entry row "Kind · local date time" + summary right-aligned + a grey "RigTune v · Minecraft v" line; the selected entry (default: the newest) expands its change rows (description, status label right-aligned and coloured, failure line "Last attempt failed: … (attempt n of 3)" / "Not applied: …"). Footer: Undo this (active when the selected entry is undoable) → `UndoScreen(this, controller, id)`; Undo last; Undo all; Done. Empty / corrupt / newer / unreadable / error: one centred read-only message. Reloads (off-thread) when first shown and when returning from an UndoScreen; keeps the selection by id and the scroll position.
- Test hooks for the game test: `view()`, `selected()`, `select(String id)`, static `failureText(Change)`.
- [ ] `./gradlew build` compiles on both versions; UndoScreenTest-style unit test for `HistoryScreen.summary`/`when` formatting if any pure helper is added. Commit `feat(history): History screen; History… replaces Undo last/Undo all on the main screen (X-M2)`.

### Task 7: game tests (AC6.3, AC3.5 screen half)
**Files:** Create `gametest/HistoryGameTest.java`; Modify `gametest/resources/fabric.mod.json` (one entrypoint line), `gametest/UiGameTest.java` (`checkSubScreens`: press `rigtune.history.open`, then `rigtune.screen.undo_last` / `undo_all` inside History).
- Seed (backing up and restoring history.json, last-apply.json and the options it touches): apply0 (simulationDistance REVERTED) → undo u0 → apply A (entityShadows s→!s APPLIED, renderDistance 5→6 APPLIED) → benchmark (renderDistance 6→8 APPLIED) → apply B (ENABLE fake-mod STAGED op `op-fake`, plus a DISABLE of the same group) with last-apply.json `op-fake` FAILED attempts 1; game options set to !s and 8.
- Checks: newest entry selected by default and its change shows "Last attempt failed: … (attempt 2 of 3)"; screenshots `history-854x480-scale2`, `history-1280x720-scale3`, `history-1280x720-scale2`, `history-640x480-scale2`, and `history-main-640x480-scale2` (the main footer); select A → Undo this → UndoScreen plan: REVERT entityShadows, SKIP renderDistance with "Changed again by a later apply"; screenshot; confirm → entityShadows == s, renderDistance == 8, a new undo entry with `undoOf == A.id` reverting the entityShadows change; back on History the new Undo entry is listed first (screenshot).
- [ ] Push; CI legs green (after WS-0 merges); download artifacts and look at the screenshots. Commit `test(history): HistoryGameTest (AC6.3)`.

### Task 8: design doc, self-review, finish
- [ ] `docs/v0.3/design/ws-b.md`: decisions (superseded rule, "fully undone", update pairing, UNREADABLE state, reason shortening, WARN dedupe, footer), the API WS-H drives, deviations, evidence.
- [ ] Dispatch a code-reviewer subagent on `git diff origin/feat/v0.3.0...HEAD`; fix high/medium findings.
- [ ] Merge `origin/feat/v0.3.0`, `./gradlew build` (both versions), push, CI green on every job.

## Self-review against the spec
- Item 6 list/details/labels/statuses → Tasks 4, 6. Per-entry undo via planEntry + existing UndoScreen + `undo(plan)` with recheck → Tasks 2, 5, 6. Superseded (B-H1) incl. all AC6.1 cases → Task 2. Undo entries not undoable; legacy import undoable (B-L1) → Task 2 (`planEntry`, `undoable`). Empty/corrupt/newer messages (B-M2 wording) → Tasks 1, 6. No format change → constraints.
- 3e/B-M1: WARN once per finishedAt, n = attempts + 1, ABANDONED reasons, real templated fixture + hand-written fixture → Task 3; screen shows "Last attempt failed: …" → Tasks 4, 6, 7.
- B-M3 API for WS-H → Task 5 + design doc. X-M2 footer + 640×480 scale 2 screenshot → Tasks 6, 7. AC6.4 moved to WS-G (B-L1); keys are still all lang keys.
