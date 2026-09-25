# WS-A: deferred defects (3a, 3b, 3c) and the raw game version: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development for every task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the three deferred v0.2 defects (SPEC 3a-3c as amended by A-H1, A-M1, A-M2, A-L1) and make every Modrinth game-version query use Loader's raw game version.

**Architecture:** Pure logic stays in `core/modrinth` (DependencyResolver's post-update view, DownloadPlanner's update-first batch with group joins, OnlineDataFetcher.Result's extra data) and in small client classes that don't touch Minecraft (Staging's drop rule, a new `StagedRecommendations` for the staged bookkeeping, OnlineLookupGate's version seam). RealController only wires them.

**Tech Stack:** Java 25, JUnit 5, Gson; Fabric Loader 0.19.5 (`FabricLoader.getRawGameVersion()` checked with javap on fabric-loader-0.19.5.jar).

**Spec:** docs/v0.3/SPEC.md (3a, 3b, 3c, item 1's raw-version bullet, amendments A-H1, A-M1, A-M2, A-L1); docs/v0.3/plan-review.md (WS-A); docs/reviews/review-4.md (deferred lows).

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-fixes` on `fix/v03-deferred`. Don't edit build.gradle, stonecutter.gradle, settings.gradle, gradle.properties, .github/workflows/*.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; committed Stonecutter version stays 26.2.
- `core/` has no Minecraft imports. No config-file format change; pending.json/journal untouched in shape.
- `UpdateInfo` and every signature the e2e drivers use (`RigTuneController`, `Action.UpdateMod`, `UpdateInfo` accessors, `UndoScreen(Screen, RigTuneController, boolean)`) stay unchanged.
- No new Modrinth call or endpoint (A-M2).
- Every fix test-first: the new test fails (assertion or compile) on the old code.
- Commit after each task with the trailers `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`. No force push.
- Baseline (0681c4c): `./gradlew build` green, 848 unit tests per version.

## File map
| file | change |
|---|---|
| src/client/.../client/OnlineLookupGate.java | raw-version seam: `OnlineLookupGate(Supplier<String> rawGameVersion)`, `Lookup.gameVersion()`, `modrinthGameVersion(String normalized)` |
| src/main/.../core/modrinth/OnlineDataFetcher.java | `Result` keeps `installedVersions` and `updateVersions` (by version id, dependencies included) + `projectIdsByVersionId()`; old 3-arg constructor kept |
| src/main/.../core/modrinth/DependencyResolver.java | installed versions with project + dependencies; post-update view (`Resolution`, `updatesNeeded`); `checkUpdate`; installed mods' own declarations; names without raw version ids |
| src/main/.../core/modrinth/DownloadPlanner.java | updates before additions; update versions checked and added to the batch; additions join the updates they rely on; `Result.opIds` |
| src/client/.../client/undo/Staging.java | `stage` returns the `Merge`; `dropQueuedUpdates(queued, loaded)` |
| src/client/.../client/StagedRecommendations.java (new) | recommendation id -> surviving op ids; `retainPending`, `unowned`, `droppedModNames` |
| src/client/.../client/probe/ModScanner.java | `loadedIds()` |
| src/client/.../client/RealController.java | wiring only |
| src/main/resources/assets/rigtune/lang/en_us.json | `rigtune.status.queued_update_dropped` says "change" (A-L1) |
| tests | OnlineLookupGateTest, OnlineDataFetcherTest, DependencyResolverTest, DownloadPlannerTest, StagingTest, new StagedRecommendationsTest |

---

### Task 1: raw game version for Modrinth queries (item 1, A-L1)

**Files:** Modify OnlineLookupGate.java, RealController.java (constructor field, fetchOnline, startDownloads). Test OnlineLookupGateTest.java.

**Interfaces:** Produces `OnlineLookupGate(Supplier<@Nullable String> rawGameVersion)`, `record Lookup(List<InstalledMod> mods, List<String> slugs, HardwareProfile hardware, String gameVersion)`, `String modrinthGameVersion(String normalized)`.

- [ ] Step 1: failing tests in OnlineLookupGateTest (gate built with `() -> "26.4-snapshot-1"`, hardware `mcVersion = "26.4-alpha.1"`):
```java
@Test
void modrinthIsAskedForTheRawGameVersionAndConditionsKeepTheNormalizedOne() {
	OnlineLookupGate gate = new OnlineLookupGate(() -> "26.4-snapshot-1");
	Fixtures.Hw rig = Fixtures.userRig();
	rig.mcVersion = "26.4-alpha.1";
	OnlineLookupGate.Lookup lookup = gate.next(mods, rules(5, "sodium"), rig.build());
	assertEquals("26.4-snapshot-1", lookup.gameVersion());
	assertEquals("26.4-alpha.1", lookup.hardware().mcVersion());
	assertEquals("26.4-snapshot-1", gate.modrinthGameVersion("26.4-alpha.1"));
}

@Test
void withoutARawGameVersionTheNormalizedOneIsUsed() {
	assertEquals("26.2", new OnlineLookupGate(() -> null).modrinthGameVersion("26.2"));
	assertEquals("26.2", new OnlineLookupGate(() -> " ").next(mods, rules(5, "sodium"), hw).gameVersion());
}
```
Existing tests construct the gate with `() -> null`.
- [ ] Step 2: `./gradlew :26.2:test --tests '*OnlineLookupGateTest'` fails (compile).
- [ ] Step 3: implement: the gate stores the supplier; `next` fills `gameVersion` with `modrinthGameVersion(hardware.mcVersion())`; `modrinthGameVersion` returns the raw version unless null/blank. RealController: `new OnlineLookupGate(() -> FabricLoader.getInstance().getRawGameVersion())`; `fetchAll(..., lookup.gameVersion())`; `startDownloads` uses `onlineLookups.modrinthGameVersion(hw == null ? HardwareProbe.minecraftVersion() : hw.mcVersion())`.
- [ ] Step 4: tests pass. Step 5: commit `fix(v0.3): Modrinth queries use Loader's raw game version`.

### Task 2: OnlineDataFetcher.Result keeps the Modrinth versions (A-H1 step 2, A-M2)

**Files:** Modify OnlineDataFetcher.java. Test OnlineDataFetcherTest.java.

**Interfaces:** Produces `Result(OnlineData data, Map<String,String> projectIdsByModId, Map<String,String> versionIdsByModId, Map<String,ModrinthVersion> installedVersions, Map<String,ModrinthVersion> updateVersions)` (both maps keyed by version id), `Map<String,String> projectIdsByVersionId()`, the old 3-arg constructor (empty maps). `UpdateInfo` unchanged.

- [ ] Step 1: failing test `keepsTheInstalledAndUpdateVersionsWithTheirDependencies`: current `sod1` (project AANobbMI) declaring `incompatible("K")`, latest `sod2` declaring `new Dependency(null, "k1", "incompatible")`; asserts `result.installedVersions().get("sod1").dependencies()`, `result.updateVersions().get("sod2").dependencies()`, `result.projectIdsByVersionId() == Map.of("sod1","AANobbMI","lit1","gvQqBUqZ")`, `updateVersions` has no entry for lithium (no update), `Result.offline()` maps empty.
- [ ] Step 2: fails (compile). Step 3: fill both maps in `fetchAll` (current -> installedVersions when `cur.id() != null`; next -> updateVersions when `isUpdate`). Step 4: pass. Step 5: commit.

### Task 3: DependencyResolver: installed versions, post-update view, update checks, names (3b per A-H1, 3c per A-M2)

**Files:** Modify DependencyResolver.java. Test DependencyResolverTest.java.

**Interfaces:**
- `DependencyResolver(ModrinthClient, String loader, String gameVersion, Map<String, ModrinthVersion> installedVersions)` (by version id; the Set constructor stays and maps each id to a version with no project and no dependencies).
- `record Resolution(List<ModrinthVersion> versions, Set<String> updatesNeeded)`.
- `Resolution resolve(String slug, Set<String> installedProjectIds, List<ModrinthVersion> batch, Set<String> updatedProjects) throws IOException` (`updatedProjects`: installed projects this batch has staged an update of; `updatesNeeded`: those whose update the resolution relies on). The 2- and 3-arg `resolve` keep their behaviour (`updatedProjects` empty).
- `void checkUpdate(ModrinthVersion update, Set<String> installedProjectIds, List<ModrinthVersion> batch) throws IOException`.
- `static ModrinthVersion known(String id, String projectId)` (a version with only its id and project, package-private).

Rules (the view is "installed, with this batch's updates applied"):
- found version F declares `incompatible` D:
  - D names a version installed at project P: refused, unless P is in `updatedProjects` (then P goes into `updatesNeeded`);
  - D names a project that is installed: refused;
  - D matches anything going in together (batch + found): refused.
- an installed version I (project P) declares F incompatible (by version or project): refused unless P is in `updatedProjects` (then `updatesNeeded`); covers the review's known gap.
- a batch version declares F incompatible: refused.
- `checkUpdate(U of project P)`: the same three directions, ignoring P's own installed versions; an installed version of another project still counts even when that project is also being updated (conservative: the update is offered again next launch).
- Names: a project by its title, then slug, then its id (unchanged); a version-only dependency by the project it belongs to, from local data only (installed map, then the versions going in together), then title, slug, "another mod". Never a version id.

- [ ] Step 1: failing tests:
  - `aVersionOnlyIncompatibilityNamesTheProjectNeverTheVersionId` (AC3.3): installed `Kx9mP2qR` of project `AANobbMI` (title "Sodium"), addition declares `new Dependency(null, "Kx9mP2qR", "incompatible")`: message is "Modrinth marks A Mod as incompatible with Sodium, which is installed" and `Pattern.compile("Kx9mP2qR|AANobbMI")` finds nothing.
  - `aVersionOnlyIncompatibilityWithAnUnknownProjectSaysAnotherMod`: Set constructor with `Kx9mP2qR` (fails on old code: the id is shown).
  - `anIncompatibilityWithTheOldVersionOfAnUpdatedModNeedsThatUpdate` (AC3.2 i, resolver level): installed `a1` of A; B declares `Dependency("A","a1")`; `resolve("b", {A}, [a2], {A})` returns `[bV]` with `updatesNeeded == {A}`; with `updatedProjects` empty it's refused.
  - `anIncompatibilityWithTheVersionBeingUpdatedToIsRefused` (ii): B declares `Dependency("A","a2")`, batch `[a2]`, updated `{A}` -> "Modrinth marks B and A as incompatible, and both would be installed".
  - `anUpdateDeclaringAnInstalledProjectIncompatibleIsRefused` (iii): `checkUpdate(a2 incompatible K, {A,K}, [])` refused naming K; `checkUpdate(a2 incompatible version k1)` with k1 installed refused; `a2` declaring its own old `a1` passes.
  - `anUpdateIsCheckedAgainstTheBatchBothWays`: batch `c2` declares `a2` -> refused; `a2` declares `c2` -> refused.
  - `anInstalledModsOwnIncompatibilityCounts`: installed `k1` (K) declares `incompatible("B")` -> `resolve("b")` refused; K updated -> `updatesNeeded == {K}`; `checkUpdate(a2)` with installed `k1` declaring `a2` -> refused.
- [ ] Step 2: run, see them fail. Step 3: implement. Step 4: whole DependencyResolverTest green. Step 5: commit.

### Task 4: DownloadPlanner: updates first, updates in the batch, additions join updates (3b per A-H1), op ids per recommendation (A-M1)

**Files:** Modify DownloadPlanner.java, RealController.java (download wiring). Test DownloadPlannerTest.java.

**Interfaces:**
- Consumes Task 3 (`resolve(..., updatedProjects)`, `checkUpdate`, `known`) and Task 2 (`Result.installedVersions()`, `updateVersions()`).
- Produces `DownloadPlanner(DependencyResolver, Path modsDir, Fetcher, BiPredicate<String,String> conflicts, Map<String, ModrinthVersion> updateVersions)`; `Result(List<Op> ops, List<String> ids, List<String> errors, Map<String, List<String>> opIds)` (recommendation id -> its op ids; own ops, or the ops of the groups it joined when it has none) with the 3-arg constructor kept.

Behaviour: `plan` runs every UpdateMod (selection order) before any AddMod (selection order). `updateMod` checks the update's full version (`updateVersions.get(newVersionId)`, else `known(newVersionId, projectId)`) with `checkUpdate` before downloading; a committed update adds its version to `batch.versions` and its project to `batch.groupOfUpdate` (project -> group, remapped on joins like the other group maps). `addMod` resolves with `updatedProjects = batch.groupOfUpdate.keySet()` and joins the group of every project in `updatesNeeded`.

- [ ] Step 1: failing tests (installed A at `a1`; A's update to `aV`, file `aV.jar`; B's `bV` declares `Dependency("A","a1","incompatible")`):
  - `anAdditionIncompatibleOnlyWithTheOldVersionJoinsTheUpdatesGroup` (i): both staged, one group, ops disable a1 jar, enable aV, enable bV.
  - `anAdditionIncompatibleWithTheVersionBeingInstalledIsRefused` (ii): B declares `Dependency("A","aV")`: B refused naming A's title, update staged.
  - `anUpdateWhoseVersionDeclaresAnInstalledProjectIncompatibleIsRefused` (iii): before its download.
  - `theSelectionOrderDoesNotChangeTheOutcome` (iv): `plan(add B, update A)` equals `plan(update A, add B)` in staged ids (as sets), op targets and group count.
  - `aFailedUpdateDownloadRefusesTheAdditionThatReliedOnIt` (v): `aV.jar` fails: no ops, two errors, B's names A.
  - `eachRecommendationRecordsItsOpIds`: `result.opIds()` maps update and addition to their own op ids.
- [ ] Step 2: fail. Step 3: implement + RealController `download(...)` passes `online.installedVersions()` / `online.updateVersions()` (captured with `online` before the async call). Step 4: DownloadPlannerTest green. Step 5: commit.

### Task 5: Staging: drop only the update of a loaded mod; `stage` returns the Merge (3a, A-M1)

**Files:** Modify Staging.java. Test StagingTest.java.

**Interfaces:** `@Nullable Merge stage(List<Op> ops, String entryId)` (null when it couldn't stage); `@Nullable List<Op> dropQueuedUpdates(Set<String> queuedModIds, Set<String> loadedModIds)`.

- [ ] Step 1: failing tests:
  - `anAdditionOfAnUnloadedModWithAStaleQueuedJarStaysStaged`
  - `anUndoReEnableOfAnUnloadedModStaysStaged`
  - `anUpdateOfALoadedModWithAQueuedUpdateIsDropped` (the existing DH test with `loaded = {distanthorizons, y}`)
  - `anUndoReEnableOfALoadedModWithAQueuedUpdateIsDropped` (A-L1: why the notice says "change")
  - `anAdditionJoinedToADroppedUpdateIsDroppedWithIt`
  - `stagingReturnsTheMergeWithTheSurvivingOpIds`
  Existing `assertTrue(staging.stage(...))` become `assertNotNull`.
- [ ] Step 2: fail. Step 3: implement (filter `modId in queued && modId in loaded`). Step 4: green. Step 5: commit.

### Task 6: staged bookkeeping from pending.json op ids (A-M1) and the notice (A-L1)

**Files:** Create src/client/.../client/StagedRecommendations.java; modify RealController.java, ModScanner.java (`loadedIds()`), en_us.json. Test src/test/.../client/StagedRecommendationsTest.java.

**Interfaces:**
```java
final class StagedRecommendations {
	void add(Map<String, List<String>> opIdsByRecommendation, Map<String, String> survivingIds);
	boolean contains(String id);
	int size();
	Set<String> ids();
	Set<String> opIdsOf(String id);
	Set<String> retainPending(Collection<Op> pendingOps); // returns the recommendation ids that stopped being staged
	int unowned(Collection<Op> pendingOps);                // pending ops no staged recommendation owns (carried over)
	static List<String> droppedModNames(List<Op> dropped, Set<String> queued, List<InstalledMod> scanned);
}
```
RealController: `staged` becomes a `StagedRecommendations`; `stage(ops, opIdsByRecommendation, entryId)` records `merge.merged().survivingIds()`; `apply` maps each immediate op to its recommendation ids (a disable's id; a config op's keys through `configIds`); `recountStaged()` after a queued drop, an undo and a discard: `staged.retainPending(pendingOps)`, `carriedOverOps = staged.unowned(pendingOps)`; `dropQueuedUpdates` passes `ModScanner.loadedIds()`; the notice names only the dropped queued mods.

- [ ] Step 1: failing tests (real Staging in a temp dir):
  - `afterADropEveryStagedIdStillHasAnOpInPendingJson` (AC3.1 last clause): stage `update:x` (loaded, queued), `add:y`, and `add:b` joined to `update:a`'s group; drop with queued `{x, a}`, loaded `{x, a}`; after `retainPending`, for every id in `ids()` at least one of `opIdsOf(id)` is in pending.json, and none of `update:x`, `update:a`, `add:b` remain.
  - `aRepeatedChangeKeepsTheStagedOpsId`: a second staging repeating a staged op records the surviving (old) op id.
  - `anEnableReplacedByAnUndoIsNoLongerStaged`: `mergeLocked` of an undo re-enable with the same mod id replaces the staged enable; `retainPending` drops the recommendation.
  - `carriedOverOpsAreTheOnesNoStagedRecommendationOwns`.
  - `theNoticeNamesOnlyTheQueuedModsAndSaysChange`: `droppedModNames` gives the loaded names of the queued mods only; en_us `rigtune.status.queued_update_dropped` contains "change" and not "update of %s".
- [ ] Step 2: fail. Step 3: implement + wire. Step 4: green. Step 5: commit.

### Task 7: design doc, self-review, finish
- [ ] docs/v0.3/design/ws-a.md: design, deviations, known gaps (update-vs-update installed old versions judged conservatively; an addition against an update staged in an earlier Apply is judged pre-update; offline availability map keyed by the normalized version misses only on snapshots; e2e not re-run).
- [ ] `./gradlew build` green on 26.2 and 26.3; push; `gh run watch <id> --exit-status`.
- [ ] Dispatch a code-reviewer subagent on `git diff feat/v0.3.0...HEAD`; keep working; fix high/medium findings forwarded by the coordinator.
- [ ] When the coordinator says WS-0 merged: `git merge origin/feat/v0.3.0`, build, push, CI green on every job (incl. the game-test legs).
