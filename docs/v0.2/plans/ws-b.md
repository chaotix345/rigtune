# WS-B: Undo RigTune + 0.1.0 migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development for every task. Steps use checkbox (`- [ ]`) syntax. Executed inline by the WS-B agent in `C:/Dev/Worktrees/rigtune-undo` (branch `feat/undo`).

**Goal:** Every change RigTune makes is journaled in `config/rigtune/history.json` with before/after and a correct status, and the player can undo the last apply or everything, with 0.1.0 state migrated (SPEC item 3, AC3.1–AC3.4).

**Architecture:** Pure logic in `core/history` (journal file, status updates, staged-change mapping, undo planner with a simulated mods folder, legacy import), all helper-classpath-safe where the helper reaches it. `core/apply` gains a reentrant `ApplyLock`, merge id mapping, group removal and `OpResult.resultPath`. The client glue lives in new `client/undo/*` classes; hotspot files (`RealController`, `RigTuneClient`, `RigTunePreLaunch`) get delegating edits only. `UndoScreen` lists the plan and confirms.

**Tech Stack:** Java 25, Gson, JUnit 5, Fabric client game tests, Stonecutter (26.2 committed, 26.3 generated).

**Spec:** docs/v0.2/SPEC.md item 3 + "Amendments from the plan review" (item 3); docs/v0.2/plan-review.md H4, H5, M4–M9, L2, L3, L8, L9; docs/DESIGN.md "Apply pipeline".

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-undo`; commit per task; never commit while Stonecutter is switched; `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before `./gradlew`; `./gradlew build` must pass for 26.2 and 26.3.
- `core/` has no Minecraft imports. Code reachable from `ApplyExecutor`/`ApplyHelper` uses only the JDK, Gson and RigTune core classes that never touch `RigTune.LOGGER`, Fabric or MC (M5).
- Journal: `config/rigtune/history.json`, `formatVersion: 1`, last 50 entries, every read-modify-write under `config/rigtune/apply.lock`, written with `AtomicFiles`.
- Hotspots: `RealController`, `RigTuneClient`, `en_us.json` (keys `rigtune.undo.*`, alphabetical), `src/gametest/resources/fabric.mod.json` (one line). Surgical edits only.
- Don't edit SPEC.md / PLAN.md / PROGRESS.md. Design notes go to `docs/v0.2/design/ws-b.md`.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`.

## File map
| file | responsibility |
|---|---|
| core/apply/ApplyLock.java (modify) | reentrant per thread and path inside one JVM (M4) |
| core/apply/PendingActions.java (modify) | `Merged.survivingIds` + `replaced` (H5), `remove(opIds)` (whole groups), `discard` returns the dropped ops, public `retireDownload` |
| core/apply/ApplyResult.java (modify) | `OpResult.resultPath` (M6), 3-arg constructor kept |
| core/apply/ApplyExecutor.java (modify) | set `resultPath`; best-effort journal update after `writeRemaining` + `result.save` (M5) |
| core/apply/ApplyHelper.java (modify) | `log` visible to the executor's journal hook |
| core/history/Journal.java (new) | history.json I/O, lock, cap, `.bad`, newer format read-only; implements `ChangeRecorder` |
| core/history/HistoryUpdates.java (new) | pure status transitions: helper results, discards, reverted, startup reconciliation |
| core/history/StagedChanges.java (new) | pure: journal changes for a merge (surviving ids, groups, config `before` after staged ops) |
| core/history/JarInfo.java (new) | a jar's fabric.mod.json id, provides (+ nested ids), depends |
| core/history/UndoPlanner.java (new) | pure planner: selection, chains, staged group removal, simulated folder, net ops, M9 checks |
| core/history/UndoPlan.java (modify) | `undoOf`, `Item.changeIds/opIds` (compat constructors kept) |
| core/history/JournalChange.java (modify) | `withGroup`, `withResultFile` helpers |
| core/history/LegacyImport.java (new) | the one-time `legacy-import` entry from 0.1.0's last-apply.json + leftover pending ops |
| client/undo/ClientJournal.java (new) | the game's Journal (versions, logger) |
| client/undo/Staging.java (new) | stage/merge/record under the lock, discard, unstage groups |
| client/undo/VanillaChanges.java (new) | whole-snapshot diff around `SettingsBridge.applyVanilla` (M7) |
| client/undo/ModsFolder.java (new) | `UndoPlanner.Folder` from the mods dir + Fabric metadata |
| client/undo/UndoService.java (new) | `undoPlan(all)`, `undo(plan)` (re-plan + execute under the lock) |
| client/undo/HistoryStartup.java (new) | preLaunch: legacy import once, reconciliation |
| client/ui/UndoScreen.java (replace stub body) | list plan items, Confirm/Cancel, result |
| client/RealController.java, RigTuneClient.java, RigTunePreLaunch.java (surgical) | entry id per apply, delegate stage/discard/undo, install recorder, startup trigger |
| src/gametest/.../UndoGameTest.java (new) + fabric.mod.json line | AC3.4 |
| src/gametest/.../RigTuneClientGameTest.java (1 method) | the fake helper holds the lock from another thread (reentrancy) |
| src/test/resources/v010/* (new) | hand-written 0.1.0 fixtures (templated paths) |

---

### Task 1: Reentrant ApplyLock (M4)
**Files:** Modify `core/apply/ApplyLock.java`; Test `core/apply/ApplyLockTest.java`.
**Produces:** `ApplyLock.acquire(Path, Duration)` unchanged signature; same thread re-acquires at once (hold count), other threads/processes wait as before; the OS lock is released only when the outermost holder closes.
- [ ] Tests: `sameThreadReentersWithoutWaiting` (nested acquire with `Duration.ZERO` returns non-null; after inner close the lock is still held: a child JVM or other thread can't take it; after outer close it can); `otherThreadWaitsThenGivesUp` (replaces `secondHolderWaitsThenGivesUp`: second holder on another thread gets null after the wait); `differentPathsAreIndependent`. Keep the two helper-process tests.
- [ ] Run, see them fail (nested acquire returns null today).
- [ ] Implement: `static final ConcurrentHashMap<Path, Shared>` keyed by `toAbsolutePath().normalize()`; `Shared { ReentrantLock jvm; FileChannel channel; FileLock lock; }`. `acquire`: `jvm.tryLock(wait)` (interrupted → null); hold count > 1 → return a handle; else poll the OS lock until the deadline (as today), on failure `jvm.unlock()` and return null. `close()`: idempotent per handle; outermost releases the FileLock and closes the channel, then `jvm.unlock()`.
- [ ] Fix `RigTuneClientGameTest.checkNotices`: take the fake helper's lock on another thread (a latch holds it while `onPreLaunch` runs on the test thread), since the same thread now re-enters.
- [ ] `./gradlew :26.2:test --tests '*ApplyLockTest'` green; commit.

### Task 2: Journal store
**Files:** Create `core/history/Journal.java`; Test `core/history/JournalTest.java`.
**Produces:**
```java
public final class Journal implements ChangeRecorder {
  public static final int FORMAT_VERSION = 1, MAX_ENTRIES = 50;
  public static final Duration LOCK_WAIT = Duration.ofSeconds(2);
  public interface Log { void warn(String message, Throwable error); }
  public Journal(Path configDir, String rigtuneVersion, String mcVersion, Log log);
  public static Path file(Path configDir);            // <config>/rigtune/history.json
  public boolean exists();
  public List<JournalEntry> entries();                // empty when missing, corrupt or newer
  public boolean readOnly();                          // newer formatVersion
  public boolean update(UnaryOperator<List<JournalEntry>> change) throws IOException; // under the lock; creates the file; false if lock busy or read-only
  public boolean updateExisting(UnaryOperator<List<JournalEntry>> change) throws IOException; // the helper: no-op if missing
  @Override public void record(String entryId, String kind, List<JournalChange> changes); // appends to entry id or creates it; logs failures
  static List<JournalEntry> cap(List<JournalEntry> entries); // prune oldest terminal first
}
```
- [ ] Tests: `recordCreatesAnEntryAndAppendsBySameId` (two records, one entry, kind/versions/at set); `emptyRecordCreatesNothing`; `capKeepsTheNewest50PreferringToDropTerminalEntries` (60 entries, a STAGED one among the oldest survives); `corruptFileIsKeptAsBadAndStartsFresh` (`history.json.bad` holds the old bytes); `newerFormatIsReadOnlyAndNeverOverwritten` (bytes unchanged after `record`); `nullChangesAndEntriesAreDropped` (L8: `[null]` inside changes/entries doesn't send the file to `.bad`); `updateTakesTheLock` (another thread holding the lock → `update` returns false after LOCK_WAIT); `nestedUpdateUnderAHeldLock` (outer `ApplyLock` on the same thread → update works).
- [ ] Implement with `record HistoryFile(int formatVersion, List<JournalEntry> entries)`, `PendingActions`-style pretty Gson, `AtomicFiles.writeString`. Terminal entry = no STAGED change, and (kind undo or no APPLIED change). Prune order: oldest terminal, then oldest non-STAGED, then oldest.
- [ ] Green; commit.

### Task 3: Status transitions (pure)
**Files:** Create `core/history/HistoryUpdates.java`; modify `JournalChange.java` (`withGroup`, `withResultFile`); modify `ApplyResult.OpResult` (+`resultPath`, 3-arg ctor); Test `core/history/HistoryUpdatesTest.java`.
**Produces:**
```java
static List<JournalEntry> applyResults(List<JournalEntry> entries, List<ApplyResult.OpResult> results); // STAGED→APPLIED (OK/SKIPPED, +resultFile, +group) / ABANDONED; APPLIED undo change → reverted one REVERTED
static List<JournalEntry> discard(List<JournalEntry> entries, Collection<String> opIds);              // STAGED → DISCARDED
static List<JournalEntry> revert(List<JournalEntry> entries, Collection<String> changeIds);            // → REVERTED
static List<JournalEntry> reconcile(List<JournalEntry> entries, Set<String> pendingOpIds, List<ApplyResult.OpResult> lastApply); // results first, then lost STAGED → ABANDONED
static List<JournalEntry> append(List<JournalEntry> entries, JournalEntry entry);
```
- [ ] Tests: `okAndSkippedBecomeApplied`, `failedStaysStaged`, `abandonedBecomesAbandoned`, `onlyStagedChangesMove` (a DISCARDED one isn't revived), `disableGetsTheActualResultFile` (`x.jar.disabled.1`), `helperGroupIsRecorded`, `appliedUndoChangeRevertsItsTarget`, `discardMarksStagedByOpId`, `reconcileAppliesLastApplyThenAbandonsLostOps`, `reconcileKeepsOpsStillPending`.
- [ ] Implement; green; commit.

### Task 4: Merge mapping, group removal, discard ids (H5) + helper journal update (M5, M6)
**Files:** Modify `PendingActions.java`, `ApplyExecutor.java`, `ApplyHelper.java`; Tests `StagingMergeTest.java` (extend), `core/history/HelperJournalTest.java` (new).
**Produces:**
```java
public record Merged(PendingActions plan, List<Path> superseded, Map<String, String> survivingIds, List<Op> replaced)
public record Removed(PendingActions plan, List<Op> removed)
public Removed remove(Collection<String> opIds)            // every op sharing a group with one of them
public static List<Op> discard(Path pendingFile, Duration lockWait) // dropped ops; null when the lock is busy
public static void retireDownload(Op op, Path modsDir)     // now public
OpResult(Op op, Status status, String message, String resultPath)
```
- [ ] Tests (merge): `survivingIdsMapARepeatedOpToTheStagedOne`, `replacedEnableIsReported`, `newOpsMapToThemselves`; (remove) `removeTakesTheWholeGroup`, `removeUngroupedTakesOnlyThatOp`; (discard) update the existing test to the list return (5 ops, null when busy).
- [ ] Tests (executor): `disableReportsTheActualTarget` (`.disabled.1` when taken), `helperUpdatesTheJournalAfterARun` (in-process `ApplyExecutor.run` with a journal holding STAGED changes → APPLIED/ABANDONED/unchanged, `resultFile` set), `journalFailureNeverFailsTheApply` (history.json is a directory → run still returns results, pending/last-apply written).
- [ ] Test `HelperJournalTest.helperWithOnlyOurJarAndGsonUpdatesHistory`: build a jar from the main classes (like `HelperLauncherTest.jarOf`), plan with ENABLE, DISABLE (+ an abandoned duplicate), PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES; journal with a STAGED change per op; launch via `HelperLauncher.launch(config, pending, [jar, gson], deadPid)`; assert exit, no `NoClassDefFoundError` in helper.log, and each change's status equals the mapping of its op's last-apply status.
- [ ] Implement: merge tracks `target` ids and filters to ids still present; `remove` collects groups; `ApplyExecutor.run` calls `HistoryUpdates.applyResults` through `new Journal(configDir, null, null, log).updateExisting(...)` inside `try { } catch (Throwable t)` after `result.save`.
- [ ] Green; commit.

### Task 5: Recording staged changes (H4, H5, every ConfigTargets namespace)
**Files:** Create `core/history/StagedChanges.java`, `client/undo/Staging.java`, `client/undo/ClientJournal.java`, `client/undo/VanillaChanges.java`; modify `RealController.java` (entry id per apply, `stage(ops, ids, entryId)` delegate, downloads carry the id, discard marks DISCARDED), `RigTuneClient.java` (install recorder); Tests `core/history/StagedChangesTest.java`, `client/undo/StagingTest.java`, `client/undo/VanillaChangesTest.java`.
**Produces:**
```java
// core/history/StagedChanges
public interface ConfigKeys { String key(Op op, String keyInFile); String current(Op op, String keyInFile); } // key: namespaced ("sodium.x") or null
public record Outcome(List<JournalChange> changes, List<String> discardedOpIds)
public static Outcome of(PendingActions base, List<Op> incoming, PendingActions.Merged merged, ConfigKeys config,
                         Function<Path, String> modIdOf, Set<String> journaledOpIds)
// client/undo/Staging
public Staging(Path configDir, Path pendingFile, List<ConfigTargets.Target> targets, Journal journal)
public boolean stage(List<Op> ops, String entryId)                 // lock → relocate → merge → save → retire → record (kind apply)
public PendingActions.Merged stageLocked(List<Op> ops)             // caller holds the lock; no recording
public @Nullable List<Op> discard()                                // under the lock; DISCARDED in the journal
public List<Op> unstage(Collection<String> opIds)                  // caller holds the lock; removes whole groups, retires downloads, DISCARDED
// client/undo/VanillaChanges
static List<JournalChange> diff(Map<String, String> before, Map<String, String> after) // every changed key, "vanilla." prefix, APPLIED
public static Map<String, SettingsBridge.Result> apply(String entryId, Map<String, String> values) // snapshot → applyVanilla → snapshot → record
```
- [ ] Tests (StagedChanges): `enableAndDisableAreRecordedWithSurvivingIdsAndGroups`, `dedupedOpRecordsTheExistingIdOnceOnly` (already journaled → nothing new), `configBeforeAccountsForAlreadyStagedOps` (0→4 staged, then 6: before 4), `noOpConfigChangeIsNotRecorded`, `replacedEnableIsDiscarded`, `disableReadsTheJarsModId`, `unknownConfigFileIsSkipped`.
- [ ] Tests (Staging, temp instance dirs, fake Targets): `stageRecordsAfterTheMerge` (pending.json op ids == journal op ids), `stageWhileTheHelperHoldsTheLockRecordsNothing`, `discardMarksDiscarded`, `unstageRemovesTheWholeGroupAndRetiresDownloads`, `relocatedDropsAreDiscarded`.
- [ ] Tests (VanillaChanges.diff): `recordsEveryChangedKeyIncludingPresetSideEffects`, `unchangedKeysAreNotRecorded`.
- [ ] Implement; RealController: `String entryId = ChangeRecorder.newEntryId();` in `apply`, `VanillaChanges.apply(entryId, vanilla)` instead of `SettingsBridge.applyVanilla(vanilla)`, `stage(..., entryId)`, `startDownloads(downloads, entryId)` → `finishDownloads(result, error, entryId)`, `discardPending` via `staging.discard()`. RigTuneClient: `ChangeRecorder.install(ClientJournal.get())`.
- [ ] Green; commit.

### Task 6: UndoPlanner (pure; M8, M9, L2, L3)
**Files:** Create `core/history/UndoPlanner.java`, `core/history/JarInfo.java`; modify `UndoPlan.java`; Test `core/history/UndoPlannerTest.java`.
**Produces:**
```java
public record UndoPlan(boolean all, String undoOf, List<Item> items)       // + (all, items) ctor
public record Item(String description, Action action, String reason, boolean needsRestart,
                   List<String> changeIds, List<String> opIds)             // + 4-arg ctor
public record JarInfo(String id, Set<String> provides, Set<String> depends) { static JarInfo read(Path jar) }
public final class UndoPlanner {
  public interface State { String setting(String key); boolean immediate(String key); Folder folder(); }
  public interface Folder { Path dir(); Set<String> files(); JarInfo jar(String fileName); Set<String> providedElsewhere(); }
  public record Revert(String changeId, JournalChange undo, String opRef)  // opRef: an op id in fileOps, or null (immediate / no-op)
  public record Script(Map<String, String> immediate, Map<String, String> staged, List<Op> fileOps,
                       Set<String> discardOpIds, List<Revert> reverts)
  public record Result(UndoPlan plan, Script script)
  public static Result plan(List<JournalEntry> entries, List<Op> pending, State state, boolean all)
  public static Result recheck(UndoPlan shown, List<JournalEntry> entries, List<Op> pending, State state)
}
```
Rules: selection (last = newest non-undo entry not yet undone whose plan has a non-SKIP item; all = every non-undo entry); a change already reverted or with a STAGED/APPLIED undo change pointing at it is left out; settings chain newest→oldest, skip when current ≠ latest after ("you changed it since"), stop where older.after ≠ newer.before, before absent → skip (L2), vanilla key outside `SettingKeys` → skip; STAGED → remove its pending.json group (group mates from other entries listed), RigTune op in the group → skip (L3), op missing → skip; APPLIED files per original group, disables first, all-or-nothing per group, simulated folder with `.disabled`/`.disabled.N` naming (M6: `resultFile`), RigTune by modId or jar → skip, M9 duplicate id / lost depends → skip; net ops per content, one new group per connected set of original groups, enables carry the jar's mod id.
- [ ] Tests: `lastPicksTheNewestUndoableEntry`, `lastPassesOverAnEntryWithNothingUndoable`, `lastSkipsEntriesAlreadyUndone`, `vanillaRevertsToBefore`, `userChangedSettingIsSkipped`, `chainUndoAllStopsAtAUserEdit` (12→16, user 10, 10→20 → back to 10), `graphicsPresetKeysAreAllListed` (non-allowlisted key → skip with reason), `configRevertNeedsRestart`, `configAbsentBeforeIsSkipped`, `stagedChangeRemovesItsWholeGroupIncludingOtherEntries`, `stagedRigTuneUpdateIsNeverDropped`, `missingStagedOpIsSkipped`, `updateChainAtoBtoCUndoneToA` (different names, net ops {disable c, enable a}), `sameNameUpdateChainUsesResultFiles` (mod.jar ×3 with `.disabled`/`.disabled.1`), `missingDisabledFileIsSkipped`, `targetAlreadyExistsIsSkipped`, `groupIsAllOrNothing`, `rigTuneJarIsNeverUndone` (modId and jar-read cases), `undoThatLosesADependencyIsSkipped`, `undoThatDuplicatesAModIdIsSkipped`, `recheckSkipsAnItemWhoseStateChanged`, `recheckDoesNotAddItemsThatWereNotShown`, `recheckSkipsAStagedGroupThatChanged`.
- [ ] Implement; green; commit.

### Task 7: Undo execution + UndoScreen + controller
**Files:** Create `client/undo/UndoService.java`, `client/undo/ModsFolder.java`; replace body of `client/ui/UndoScreen.java`; modify `RealController.java` (`undoPlan`, `undo` delegates; busy while downloading; clear `staged`, recount), `en_us.json` (`rigtune.undo.*`); Test `client/undo/UndoServiceTest.java` (fake State/vanilla applier).
**Produces:** `UndoService(Path configDir, Path pendingFile, Staging staging, Journal journal, Supplier<UndoPlanner.State> state, VanillaApplier vanilla)`; `@Nullable UndoPlan plan(boolean all)`; `Outcome undo(UndoPlan shown)` with counts (now, afterRestart, discarded, skipped) or busy.
Execution under the lock: recheck → `staging.unstage(discardOpIds)` → vanilla now (ok → undo change APPLIED + originals REVERTED) → config reversals through each Target's stager + `fileOps` through `staging.stageLocked` → undo changes STAGED with surviving op ids (or APPLIED for no-op contents) → one `kind: undo` entry (`undoOf` = entry id or `all`).
- [ ] Tests: `undoLastDiscardsStagedAndRevertsVanilla` (journal statuses, pending.json), `undoAllStagesFileReversalsAsOneGroupPerComponent`, `undoIsJournaledWithReverts`, `helperResultMarksOriginalsReverted` (run ApplyExecutor on the staged reversal → REVERTED), `secondUndoLastTargetsTheOlderApply`, `busyLockDoesNothing`.
- [ ] UndoScreen: title, subtitle (last apply date / everything), scrollable sections (Undone now / After a restart / Staged changes to cancel / Skipped, with reasons), status line, buttons `Undo (N)` + Cancel; after confirm shows the result and Done. Fits 854×480@2, 1280×720@2/3.
- [ ] Green; commit.

### Task 8: 0.1.0 migration + startup (L2, H5 reconciliation)
**Files:** Create `core/history/LegacyImport.java`, `client/undo/HistoryStartup.java`, `src/test/resources/v010/{pending.json,pending-pre-groups.json,last-apply.json,rigtune.json,rules-cache.json,helper/…}`; modify `RigTunePreLaunch.java` (call HistoryStartup while holding the lock; testable `readState(configDir, stillRunning, lastShownApply)`); Tests `core/history/LegacyImportTest.java`, `client/undo/MigrationV010Test.java`.
**Produces:** `static @Nullable JournalEntry LegacyImport.entry(ApplyResult lastApply, @Nullable PendingActions leftover, Function<Path,String> modIdOf, StagedChanges.ConfigKeys config, String rigtuneVersion, String mcVersion)`; `HistoryStartup.run(Path configDir, Journal journal, boolean lockHeld, StagedChanges.ConfigKeys config)`.
- [ ] Fixtures: paths templated as `${MODS}`/`${CONFIG}` (JSON-escaped at load); 0.1.0 record shapes from `git show v0.1.0:…` (Op without `resultPath`; ops with and without `id`/`group`/`modId`/`attempts`); last-apply with a RigTune self-update group, a plain disable (`Disabled x -> x.jar.disabled.1` message), an add, a PATCH_JSON and a FAILED op.
- [ ] Tests: `pendingWithAndWithoutIdsLoads`, `lastApplyLoads`, `rigtuneJsonKeepsGoalAndLastShownApply`, `rulesCacheLoads`, `helperDirFromV010IsReplaced`, `preLaunchReportsUnseenResultAndLeftovers`, `v02HelperExecutesAV010Plan`, `legacyImportExcludesRigTuneAndPatchJson`, `legacyImportParsesTheDisabledName`, `legacyImportRecordsLeftoverOpsAsStaged`, `importRunsOnce`, `noImportWithoutTheLock`, `startupReconcilesLostOps`, plus `capturedFixturesFromWsGAreReadToo` (iterates `src/test/resources/v010/captured/` when present).
- [ ] Green; commit.

### Task 9: Game test `UndoGameTest` (AC3.4)
**Files:** Create `src/gametest/java/.../UndoGameTest.java`; add one line to `src/gametest/resources/fabric.mod.json`.
- [ ] Flow (title screen, real controller): discard leftovers; apply `vanilla.entityShadows` flip + `sodium.performance.use_fog_occlusion` flip; assert history.json (vanilla APPLIED, sodium STAGED with the pending op id); open `UndoScreen(last)`, screenshot `undo-last`; confirm; assert entityShadows restored, the sodium op gone from pending.json, statuses DISCARDED/REVERTED, an undo entry. Then apply the sodium change again, run `ApplyExecutor` in-process (the helper's path) so it becomes APPLIED, open Undo last (screenshot `undo-restart`), confirm, assert a staged PATCH_JSON back to the old value. Screenshot `undo-all` at 854×480@2 and 1280×720@2/3.
- [ ] Take `C:/Dev/Worktrees/.gametest-lock` (mkdir + owner.txt), run `:26.2:runClientGameTest` then `:26.3:runClientGameTest`, release, read the screenshots.

### Task 10: Review, rebase, CI, design notes
- [ ] code-reviewer subagent on `git diff origin/feat/v0.2.0...HEAD`; fix high/medium.
- [ ] Rebase onto `origin/feat/v0.2.0`, `./gradlew build`, push, `gh run watch --exit-status`.
- [ ] `docs/v0.2/design/ws-b.md` (design, deviations, UNVERIFIED items).

## Self-review
- AC3.1 → Tasks 2–5 (recording apply/benchmark via `record`, helper updates, discard). AC3.2 → Tasks 6–7 (+ the helper-run test in Task 7). AC3.3 → Task 8 (+ WS-G captured files picked up). AC3.4 → Task 9.
- H4 (Task 5), H5 (Tasks 4, 5, 8), M4 (1), M5 (4), M6 (3, 4, 6), M7 (5), M8 (6, 7), M9 (6), L2 (6, 8), L3 (6), L8 (2), L9 (6: UndoPlan.Action used by the screen).
