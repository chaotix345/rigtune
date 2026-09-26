# RigTune apply-pipeline audit: feat/v0.4.0 at 22cc915

Read-only, 2026-09-26. The code was read from `git show origin/feat/v0.4.0:<path>` (exported to a scratch copy with `git archive`). Nothing was changed, and no gradle, Java or Minecraft process was run.

Line numbers below are for origin/feat/v0.4.0. In scope: `core/apply`, `core/history` and `core/modrinth`, `client/undo`, and RealController's apply and download paths. Apart from the new `Op.projectId`/`versionId`, `JournalChange.modName` and RealController's v0.4 delegations, this code is identical to the released v0.3.0 (`git diff v0.3.0 origin/feat/v0.4.0`). So every finding below is also in 0.3.0. `ProfileService` is still a stub on this branch; where a finding becomes the normal path once WS-P lands, it says so.

Excluded as already known: SPEC §2 (2a-2n), the plan-review amendments (A-H1, A-M1, A-L1, P-H1, P-L1, 2n), reviews 1-6, and ws-a.md "Known gaps".

Method: I read the pipeline end to end, and three independent reviewers covered the apply pipeline, undo/history and the resolver. I re-checked every finding against the code before including it. Two findings were found independently by two reviewers (H1, M5), and one by a reviewer and by me (M1). One reviewer claim was narrowed: M2's "abandoned after 3 runs" variant is wrong, because the next run turns the leftover disable into APPLIED, which is undoable. Where a finding needs a runtime check, it says "needs a test to confirm".

## Summary

| # | Sev | Area | Finding |
|---|---|---|---|
| H1 | high | resolver/apply | An update never resolves its new version's required dependencies, and a disable never checks what depends on the disabled mod. Updates are ticked by default, so a new required library means the game won't start. |
| H2 | high | resolver | An update ignores installed mods' version pins. Nvidium pins `sodium: [0.9.2]`, so the pre-ticked Sodium update stops the game from starting. |
| H3 | high | resolver | A library bundled inside another mod (jar-in-jar) counts as "loaded", so the standalone copy an added mod needs is deleted after download. |
| H4 | high | apply | A group stays half-applied while the helper retries in place (up to about 30 s, plus a rollback). If the helper is killed then, or its rollback fails, a mod or its library is missing. If anything depends on it, Fabric won't start and the helper never runs again. |
| H5 | high | undo | Undo stages the library and the mod that needs it in separate groups. If only the dependant's disable fails, the game won't start, with no recovery. |
| M1 | medium | apply | Staged config changes A → B → A for one key before a restart end at B, because `merge` dedupes the third patch against the first. History records nothing for the third. This becomes the normal path with WS-P's profile switches, and SPEC §4's switch design adds a second path to the same result. |
| M2 | medium | undo | Undo/Discard during a half-done update (H4) drops the group and never re-enables the old jar. The mod is gone and History says "Cancelled". |
| M3 | medium | undo | A second "Undo last" in one start skips an entry whose file is restored only by the first undo's staged op, and undoes an older, unrelated entry instead. This is the mod-file side of 2n. |
| M4 | medium | resolver | Applying before the Modrinth lookup finishes (or after it failed) runs the resolver with no installed data, so every incompatibility check against installed mods is skipped. |
| M5 | medium | apply/resolver | A later "Disable X" is absorbed into an already-staged "Update X" group, so X comes back as the new version. |
| M6 | medium | undo/history | Profile switches fill the 50-entry cap, which evicts the first Apply. Undo all can then no longer restore the pre-RigTune values or remove mods that Apply added. |
| M7 | medium (needs a test to confirm) | resolver | An update's downloaded jar is never checked to be the same mod id as the jar it replaces. |
| L1 | low | history | The "Imported from 0.1" entry is created whenever history.json is missing (deleted, or a busy lock on the first start), not only on an upgrade from 0.1.x. |
| L2 | low | apply/history | The helper rewrites pending.json before last-apply.json. If it dies or fails in between, History marks changes that were applied as "Not applied", and Undo can't revert them. |

H1-H5 are ordered by likelihood:
- H1 (the update case) and H2 need no unusual event: the default ticks and one release on Modrinth are enough.
- H3 needs a bundled library that's older than the added mod requires.
- H4 and H5 need a rename to fail, or the helper to be killed, inside a retry window.

All five end the same way. Fabric refuses to start before any RigTune code runs, and the helper is only started from `CLIENT_STOPPING` (`client/RigTuneClient.java:79-82`), so RigTune can neither undo nor finish the change. The player has to repair mods/ by hand. The fixes for H1-H3 and H5 share one building block: a "would this folder start?" check before staging. `UndoPlanner.violations` (`core/history/UndoPlanner.java:706-745`) already does this for Undo, and Apply has no equivalent.

---

## H1 (high): an update never brings in the new version's required dependencies, and a disable never checks what depends on the mod

**Scenario A: an update adds a requirement** (needs only a mod release)
1. Mod X 1.0 is installed. X 2.0 on Modrinth adds a required dependency on library L, which isn't installed.
2. RigTune offers "Update X", ticked by default (`core/recommend/Recommender.java:415-416`: `new Action.UpdateMod(...), true`).
3. The player clicks Apply. The planner stages {disable x-1.0.jar, enable x-2.0.jar} and nothing for L.
4. At the next start Fabric fails ("X requires L, which is missing"). RigTune never loads, so Undo isn't reachable.

**Scenario B: a disable of a mod something depends on**
1. The rules offer "Disable X", for an avoided or obsolete mod.
2. Another installed mod depends on X, directly or through a jar-in-jar that X provides.
3. Apply stages a bare `DISABLE x.jar`, and the next start fails the same way.

With the bundled rules the avoided mods are nvidium, renderscale and lambdynlights, and no dependants of those are known. So scenario B needs a remote rules change or an add-on the player installed.

**Evidence**
- `core/modrinth/DownloadPlanner.java:186-218`: `updateMod` calls only `resolver.checkUpdate(next, attempt.projects, attempt.batch.versions)` (:205), then fetches and adds the two ops (:214-215). Unlike `addMod` (:141), it never calls `resolver.resolve`.
- `core/modrinth/DependencyResolver.java:138-142` → `refuseIncompatible`, whose loop starts with `if (!dep.incompatible()) { continue; }` (:148-151). Required dependencies of an update are never looked at. The data is available: `updateVersions` holds the full new version with its dependencies (`OnlineDataFetcher.java:88-92`).
- `client/RealController.java:437-441`: a `DisableMod` becomes `Op.disableFile(disable.file())` with no check. `core/recommend/Recommender.java:465-476` (`disable`) doesn't check dependants either. Neither the recommend package nor RealController reads any mod's `depends`.
- `core/apply/ApplyExecutor.java:291-309`: the helper checks duplicate mod ids only.
- Contrast: `core/history/UndoPlanner.java:706-745` refuses an undo that would leave an active jar without a `depends` id.

**Why:** dependency completeness is checked for additions (the resolver) and for Undo (`violations`), but not for updates or disables.

**Fix:**
- Updates: after `checkUpdate`, resolve `next`'s required dependencies the way `addMod` does. Download them, check their mod ids, put them in the update's group, and refuse if one can't be resolved. Pull `addMod`'s per-version loop (`:145-183`) into a helper that both paths call.
- Disables (and updates, as a final check): before staging, simulate the folder with `ModsFolder` plus the new jar's `JarInfo`, and run the `violations` check. Refuse, or show a Preview skip reason, when a `depends` id would go missing.

**Test:**
- DownloadPlannerTest: an update whose new version has `required("LIB")`, with LIB not installed, stages `[disable m-1.jar, enable mV.jar, enable libV.jar]` in one group. With LIB having no compatible version, the update is refused. Today the result is `[disable, enable mV.jar]` only.
- A planner/preview test: a folder where jar Y depends on X, with "disable:X" selected → refused/skipped. Today it's staged.

---

## H2 (high): an update ignores installed mods' version pins on the mod being updated

**Scenario**
1. Nvidium is installed (RigTune itself recommends it once Sodium is present). Its fabric.mod.json has `"sodium": ["0.9.2"]`, an exact pin (`docs/research/v0.2/triage.md:240-244, 348`).
2. Sodium 0.9.3 is released. RigTune offers "Update Sodium", ticked by default.
3. The player applies. At the next start Fabric fails: "Nvidium requires [0.9.2] of sodium, but only the wrong version is present: 0.9.3".

The same happens when "Install Nvidium" and the pre-ticked "Update Sodium" are applied together. More generally, it happens for any installed mod whose `depends` range excludes the update's version, or whose `breaks` range includes it.

**Evidence**
- `DependencyResolver.java:148-151`: only `incompatible` entries are checked.
- `DependencyResolver.java:177-187`: installed mods are checked only for `declaresIncompatible(mine, version)`. A `required` entry pinned to the replaced version is never examined.
- `DependencyResolver.java:118-121`: a required project that is present at any version counts as satisfied.
- Nothing in the pipeline reads fabric.mod.json version ranges (the only `getDepends` use is ModsFolder, for undo, and it reads ids only).
- DESIGN.md:228 documents that the resolver "ignores dependency version pins", but only for the addition side and without noting that the game then won't start. The update side isn't documented.

**Fix:** before staging an update, read the new jar's fabric.mod.json version (it's downloaded at `DownloadPlanner.java:206`). Check it against every loaded mod's `depends`/`breaks` on that id, using Fabric's own `ModDependency.matches(Version)` on the client (`FabricLoader.getAllMods()` → `getMetadata().getDepends()`/`getBreaks()`). Refuse with "%s needs %s %s" when it doesn't match. This covers Modrinth `version_id` pins and fabric.mod.json ranges alike. For additions, do the same for the added mod's own `depends` against the installed version.

**Test:** planner unit test with an injected "dependency predicate" function: installed Nvidium requires sodium `0.9.2`, update to 0.9.3 → refused. Also a test with no pin → staged. And DependencyResolverTest: `installed = {s092 (SODIUM), n1 (NVIDIUM, required versionId s092)}`, `checkUpdate(s093, …)` must throw (today it passes).

---

## H3 (high): a library bundled inside another mod (jar-in-jar) makes the planner delete the standalone copy an added mod needs

**Scenario**
1. Mod Y ships library L 1.5 nested inside its jar (jar-in-jar; common for cloth-config and midnightlib).
2. The player adds mod B, which requires L at a newer version (for example `>=1.7`).
3. The resolver downloads the latest L, because nested mods have no hash and so aren't in `installedProjects`.
4. The planner then sees L's mod id among the "loaded" ids and deletes the download.
5. B is staged alone. At the next start Fabric fails: B needs a newer L than the bundled one.

**Evidence**
- `client/probe/ModScanner.java:47-49`: nested mods stay in the scan (`nested` → no file or hash, but the id is kept). DESIGN.md:238 says the same.
- `client/RealController.java:567-570` (Apply) and `:765-768` (Preview): `loadedIds` is built from every scanned mod, nested ones included.
- `core/modrinth/DownloadPlanner.java:173-178`: `if (!attempt.modIds.add(jarModId)) { … dropDuplicate(pending); continue; }`.
- DESIGN.md:245 justifies that check with "a second top-level jar with the same id stops Fabric from starting". A bundled copy isn't top-level. The helper's own duplicate check already counts top-level jars only (`ApplyExecutor.java:256-271`).

**Fix:** mark nested mods in `InstalledMod` (ModScanner already computes `nested`), and build the planner's `loadedIds` from top-level mods only.

**Needs a test to confirm:** that Fabric Loader 0.19.x accepts a top-level jar next to a nested copy of the same id and loads the newer one. The reviewer found `ROOT_FORCELOAD`/`NEWER_ACTIVE` in ModSolver's class strings. A game test with a jar that bundles a library and the same library top-level would confirm it.

**Test:** unit test of the loadedIds computation (a nested InstalledMod is excluded). DownloadPlannerTest `libraryUsers()` with `loadedIds={"lib"}` gives `[aV.jar]` today and `[aV.jar, libV.jar]` in one group once the fix passes top-level ids only.

---

## H4 (high): a group stays half-applied while the helper retries in place; a killed helper or a failed rollback leaves a mod or its library missing

**Scenario**
1. A staged update of Sodium: group {disable sodium-old.jar, enable sodium-new.jar.rigtune-pending → sodium-new.jar}. An addition group {enable mod, enable lib} has the same exposure.
2. The player quits. The helper renames sodium-old.jar → sodium-old.jar.disabled (disables run first).
3. The enable's rename hits a sharing violation (AV scanner, indexer, the Modrinth App). The helper backs off in place for up to about 30 s. A failed enable is then rolled back on a second budget of about 30 s.
4. During that window mods/ has neither Sodium jar. Outcomes:
   - **Killed:** the helper is killed (PC shutdown right after quitting, Task Manager, a launcher job object). Nothing ever restores the folder.
   - **Rollback fails:** the enable gives up and the rollback rename also fails. The helper returns "Rollback failed … was left as it is", and pending.json keeps the group.
   - **Relaunched:** the player starts the game inside the window. Fabric picks the jars before preLaunch's 5 s wait, so the start fails if anything depends on Sodium. This one is recoverable: relaunch after the helper finishes.
5. For the first two: Sodium is missing (old disabled, new still `.rigtune-pending`). Iris, Nvidium or Sodium Extra depend on Sodium, so Fabric refuses to start before any entrypoint runs.
6. The helper is launched only from `CLIENT_STOPPING`, which never fires, and preLaunch never runs. pending.json would finish the update at the next clean exit, but there is none.

Updating Fabric API (depended on by most mods) makes step 5 certain.

**Evidence**
- `core/apply/ApplyExecutor.java:328-389`: the group runs disable first (`rank`, :391-400), then enable. On a failure, earlier renames are rolled back (:379-387).
- `ApplyExecutor.java:367-388`: op k is retried (`retrying`, :484-500) after the earlier renames are already done.
- `ApplyExecutor.java:36-42, 119-136`: the sharing-violation budget is `SHARING_RETRY_BUDGET_MILLIS = 30_000`.
- `ApplyExecutor.java:415-434`: rollback has its own budget. On failure the op is FAILED "Rollback failed (…); <file> was left as it is".
- `ApplyExecutor.java:146-154`: pending.json and last-apply.json are rewritten only after every group has run. Nothing records a group in progress.
- `client/RigTuneClient.java:79-82`: the helper starts only from `CLIENT_STOPPING`.
- `client/RigTunePreLaunch.java:38`: "Fabric has already picked the mod jars by now".
- DESIGN.md: "Operations that are still pending (e.g. the helper was killed) are reported so the player can finish them manually". That report is shown in game, which never starts here.

**Why:** the renames of a group can't be atomic, and the in-place retry budget sits between them. Recovery depends on a clean exit of a game that may not start.

**Fix (minimal):**
- Keep the half-applied window to the time between two renames. After a group's first successful rename, don't retry in place: on any error, roll back at once and retry the *whole group* under the backoff budget.
- Or equivalently, first "prepare" each enable by renaming its `.rigtune-pending` jar to a name Fabric still ignores (for example `.rigtune-ready`) with the full retry budget, then do the disables and final renames back to back.
- For a failed rollback, keep retrying it on the full budget. Also register a JVM shutdown hook in the helper that rolls back the current group's completed renames. Windows sends CTRL_SHUTDOWN_EVENT at shutdown; whether it reaches this process needs a test to confirm.

**Test:** ApplyGroupsTest with a `Mover` that throws a sharing `FileSystemException` twice on the ENABLE, and a `Sleeper` that snapshots mods/ at each sleep. No snapshot may lack both the old and the new jar (today every snapshot does). A second case where the rollback also fails: the helper must not return with the old jar disabled and the new one not enabled.

---

## H5 (high): Undo can disable a library and the mod that needs it in separate groups; a partial failure leaves the dependant active without it

**Scenario**
1. Apply 1 adds Sodium. Restart.
2. Sodium Extra is offered only once Sodium is present (`recommendWhen: {modPresent: ["sodium"]}`). Apply 2 adds it. Restart.
3. The player uses Undo all, or Undo last twice in one start.
4. The undo stages {disable sodium-extra.jar} and {disable sodium.jar} as two independent groups.
5. At exit the helper runs them independently. If sodium-extra.jar's rename fails (after the ~30 s budget) and sodium.jar's succeeds, Sodium Extra is active without Sodium. Fabric refuses to start, with no recovery (see H4 step 6).

**Evidence**
- `core/history/UndoPlanner.java:572-607`: groups are accepted newest first against a cumulative simulation (`sim.clear(); sim.putAll(trial)`, :604-605). Sodium's group is checked against a folder where Sodium Extra is already disabled, so it passes.
- `UndoPlanner.java:751-790` (`netOps`): groups are joined by union-find over shared file content only (:758-766). A dependency edge never joins two groups (:785).
- `UndoPlanner.java:690-711`: the dependency check treats jars that still-staged DISABLE ops will disable as already gone (`staged.disabled().forEach(sim::remove)`). This is how the second of two "Undo last" in one start passes.
- `core/apply/ApplyExecutor.java:238-241`: every group runs on its own. A failed group doesn't stop the next.
- The forward direction was fixed for this (A-H1: a dependant joins its dependency's group, `DownloadPlanner.java:29-34`). The undo direction wasn't.

**Fix (minimal):**
- `netOps`: put all file ops of one undo into a single group, so it's all-or-nothing like the forward direction.
- `violations`: don't treat a still-staged DISABLE as done when checking dependants; alternatively, join the new op to that staged op's group.

**Test:**
- UndoPlannerTest: folder with `sodium.jar` (id sodium) and `sodium-extra.jar` (id sodium-extra, depends sodium), entries e1 (enabled sodium.jar, g1) and e2 (enabled sodium-extra.jar, g2). `plan(all).script().fileOps()` must have one distinct group (today: two).
- Same with a pending `disableFile(sodium-extra.jar).inGroup("u1")` from a first undo: the second `plan(last)` must not stage sodium.jar's disable in a new independent group.

---

## M1 (medium): staged config changes A → B → A for one key before a restart end at B

**Scenario** (Iris shown; DH `rendererMode` and every Sodium key behave the same)
1. `iris.properties` has `enableShaders=true`.
2. Switch to Battery. This stages op1 `{enableShaders: false}`.
3. Switch to Quality. This stages op2 `{enableShaders: true}`.
4. Switch to Battery again. op3 `{enableShaders: false}` equals op1, so `merge` drops it.
5. Restart. The helper applies op1 then op2, so shaders are ON while Battery is the active profile. The player's last choice is silently lost.
6. History has no change for the third switch. If that was its only staged change, there's no entry at all, and a profile label keyed by that entry id points at nothing.

**Reachability:**
- Today, a staged `set:<key>` recommendation is hidden for the rest of its session (`client/RealController.java:390-394, 607`). So plain Applies reach this only when the helper didn't run between sessions (a crash) and the goal changed in between.
- With WS-P's profile switches, which build their own `SetSetting` recommendations (SPEC §4), it's the normal path.

**Evidence**
- `core/apply/PendingActions.java:164-174`: `Op same = merged.stream().filter(existing -> existing.sameChange(op)).findFirst()…; if (same != null) { … continue; }`. This matches any staged op, not the last one for that key.
- `PendingActions.java:97-100`: `sameChange` compares type, from/to/path and patches only.
- `core/apply/ApplyExecutor.java:228-243`: patch ops have no group ("op:i") and run in plan order.
- `core/history/StagedChanges.java:41-44`: the third op maps to op1's id, which is already journaled, so it's skipped. `client/undo/Staging.java:102`: no changes means no entry.
- SPEC P-H1 kept `merge` unchanged after reasoning only about two switches (A → B). AC4.11 tests only Battery → Max FPS. `StagingMergeTest` and `PendingActionsTest` have no A → B → A case.

**Design note for WS-P (spec, not yet code):** SPEC §4 (docs/v0.4/SPEC.md:109) builds a switch's changes against `SettingsBridge.read`. For config keys that returns the on-disk file values (`client/probe/SettingsBridge.java:54-62, 77`), not the staged effective ones. So Battery (staged `false`) followed by "My settings" (target `true` = on disk) emits no change at all. op1 stays, and the restart applies Battery's value while "My settings" is active. The same flaw from the other side.

**Fix:**
- In `merge`, dedupe a PATCH_* op only when `same` is the last op in `merged` that sets that (path, key); otherwise append it as a new op. Stagers make one op per key, so the check is simple.
- In WS-P, diff a switch against the effective value: the last staged op for the key, else the file. This is the same `stagedValue` logic 2n uses.

**Test:**
- PendingActionsTest: merge `{k:false}`, then `{k:true}`, then `{k:false}` → three ops, the last sets `false`, and `survivingIds[op3] == op3`.
- ApplyExecutorTest on a real iris.properties → `k=false`.
- StagingTest: the third stage records a change true → false.
- WS-P test: Battery → My settings before a restart → the effective value after the helper is My settings'.

---

## M2 (medium): Undo or Discard during a half-done update drops the group and never re-enables the old jar

**Scenario:** state after H4's "rollback fails" case, or after a kill when the game still starts because nothing depends on the mod:
1. x-1.jar is now x-1.jar.disabled, x-2.jar is still `.rigtune-pending`, and both ops remain in pending.json (FAILED stays).
2. Both journal changes are still STAGED.
3. The player presses Undo last (or Discard pending). The whole group is dropped and its changes are marked DISCARDED ("Cancelled").
4. x-1.jar.disabled is never renamed back, so the mod is gone.

This window lasts only until the next exit: on the next run the disable returns SKIPPED_ALREADY_DONE, becomes APPLIED, and an undo can then re-enable it.

**Evidence**
- `core/apply/ApplyExecutor.java:415-434`: a rollback failure leaves the file disabled.
- `ApplyExecutor.java:198-215`: FAILED ops stay in pending.json.
- `core/history/HistoryUpdates.java:54`: `case FAILED -> c` (stays STAGED).
- `core/history/UndoPlanner.java:373-421` (`planStaged`): the whole group is discarded, without looking at the folder.
- `client/undo/Staging.java:171-191, 244-255` and `core/apply/PendingActions.java:283-302`: unstage/discard do the same.

**Fix:** in `planStaged` and `Staging.discard`, when a group's DISABLE_FILE path is gone and a `.disabled` of it exists, stage a re-enable of it together with the discard. The alternative is to skip with "partly done at the last exit; restart once, then undo it".

**Test:** UndoPlannerTest with pending `{disable x-1.jar, enable x-2.jar.rigtune-pending → x-2.jar}` in group g, both changes STAGED, and folder `{x-1.jar.disabled, x-2.jar.rigtune-pending}`. Today `last()` discards {d, e} and stages no file op. Expect a re-enable of x-1.jar.disabled, or a skip. StagingTest for `discard()` likewise.

---

## M3 (medium): a second "Undo last" in one start skips an entry that's only waiting for the first undo's staged op, and undoes an older, unrelated entry instead

**Scenario**
1. Entry C changes render distance 12 → 16.
2. Entry A adds X (x-1.jar). Restart.
3. Entry B updates X: x-1.jar → x-1.jar.disabled, and x-2.jar added. Restart.
4. Undo last undoes B: {re-enable x-1.jar, disable x-2.jar} is staged.
5. Undo last again, in the same start:
   - B is excluded (its changes are being reverted).
   - A's reversal "disable x-1.jar" is skipped with "x-1.jar is no longer in the mods folder", because the file comes back only at the next exit.
   - A's plan is all skips, so `plan()` falls through to C, and the Undo screen offers to revert render distance.
   - If A also had a vanilla setting, A is undone without its mod. Undo last never offers A again (Undo all/this still can after a restart).

This is the mod-file side of 2n. 2n's fix is scoped to staged setting keys in `planSettings`.

**Evidence**
- `core/history/UndoPlanner.java:256-263`: B's changes are excluded (`beingReverted`).
- `UndoPlanner.java:562-568`: the simulated folder comes from disk (`folder.files()`) only.
- `UndoPlanner.java:618-624`: `sim.remove(file) == null` → FILE_GONE.
- `UndoPlanner.java:127-140` with `UndoPlan.java:64-66`: `isEmpty()` means "all items are SKIP", so the plan continues to the next older entry.

**Fix:**
- Build the simulated folder with the still-pending ENABLE/DISABLE ops applied (the same overlay `violations` already uses, `staged(...)` at :690-704). Then A's disable finds x-1.jar.
- Alternatively, skip A with "waiting for a restart" and don't fall through past it.

**Test:** UndoPlannerTest with entries C (renderDistance 12 → 16), A (enabled x-1.jar, g1) and B (disabled x-1.jar → x-1.jar.disabled, enabled x-2.jar, g2), plus an undo entry for B with STAGED changes and its pending ops. Folder {x-2.jar, x-1.jar.disabled}. Today `plan(last).plan().undoOf()` is C; expect A.

---

## M4 (medium): Apply before the Modrinth lookup finished (or after it failed) skips every incompatibility check against installed mods

**Scenario**
1. The game starts. The report is built with `online = Result.offline()`, either before `fetchOnline` returns or after a failed lookup.
2. "Install X" is still offered from the bundled availability map.
3. The player applies. `download()` builds the resolver from `data.installedVersions()` = {} and `installedProjects` = {}.
4. Every installed-side check in `refuseIncompatible` finds nothing, so X is staged next to an installed mod Modrinth marks incompatible. That's the case review 4's rules-accuracy-2 check was added to stop.

**Evidence**
- `client/RealController.java:135`: `online = OnlineDataFetcher.Result.offline()`.
- `core/modrinth/OnlineDataFetcher.java:121-130`: a failed lookup ends as `Result.offline()`.
- `core/recommend/Recommender.java:506-517`: availability falls back to `rules.availability`.
- `RealController.java:514-520, 563-565`: `startDownloads`/`download` pass the data on without checking `data.data().online()`.
- `core/modrinth/DependencyResolver.java:152-163, 177-187`: these checks need the installed data.

**Fix:** refuse Add/Update downloads while `!data.data().online()`, with a "Modrinth data isn't loaded yet; try again in a moment" status. Alternatively, run the lookup synchronously inside `download()` first.

**Test:** unit test on the guard (online=false → Add refused, fetcher never called). A game test can delay the fake Modrinth's `/version_files` and click Apply.

---

## M5 (medium): a later "Disable X" is absorbed into an already-staged "Update X", so X comes back as the new version

**Scenario**
1. Apply 1 stages Update X: group G1 {DISABLE x-1.jar, ENABLE x-2.jar}.
2. Later in the same session "Disable X" appears. For example, a rescan after turning shaders on makes an `avoidWhen` match (RenderScale, Nvidium), or newer remote rules add an avoid entry. `withoutStaged` hides only the `update:x` id.
3. Apply 2 creates `Op.disableFile(x-1.jar)` with no group. In `merge`, `sameChange` matches G1's DISABLE, and because `op.group()` is null the op is simply dropped.
4. At exit G1 enables x-2.jar. The player asked for X disabled and is running X 2.0. "Disable X" is offered again at the next start, so this is recoverable.

**Evidence**
- `client/RealController.java:437-441`: `Op.disableFile(disable.file())`, ungrouped.
- `core/apply/PendingActions.java:164-174`: dedupe by `sameChange`.
- `RealController.java:390-394`: `withoutStaged` filters by recommendation id only.
- `core/recommend/Recommender.java:387`: update and disable are exclusive only within one report.

**Fix:** in RealController.apply, when a DisableMod targets a file whose staged DISABLE belongs to a group that also enables the same mod id, unstage that group (retiring its download) before staging the disable. The alternative is to refuse with "an update of X is staged; undo it first".

**Test:** PendingActionsTest/StagingTest: base `group(disableFile(a1), enableFile(a2p, a2).withModId("a"))`, then stage `disableFile(a1)` → no ENABLE with modId "a" remains. Today it does.

---

## M6 (medium): profile switches fill the 50-entry history, evicting the first Apply, so Undo all can't get back to the player's own settings

**Scenario**
1. The first Apply changes render distance 12 → 8 and adds x.jar.
2. The player then switches profiles often. For example, accepting the battery offer on unplug and on plug-in gives 2 entries per cycle, and each switch is an `apply` entry (SPEC item 4; undo entries count too).
3. On the 51st entry, the cap evicts the first Apply, whose changes are all APPLIED and none STAGED.
4. Undo all now restores render distance only to the oldest surviving `before` (8, not 12). x.jar is never disabled, and History no longer shows that Apply.

**Evidence**
- `core/history/Journal.java:31`: `MAX_ENTRIES = 50`.
- `Journal.java:252-272`: pass 1 drops only "finished" entries (an apply entry with APPLIED changes never is). Pass 2 drops the oldest entries without STAGED changes.
- `core/history/UndoPlanner.java:470-501`: the chain's target is the oldest surviving `before`.

**Why:** the cap is documented and predates profiles. v0.4's switches make it reachable within weeks for a laptop user.

**Fix:** when evicting, fold the evicted entries' still-in-effect APPLIED changes into one kept "baseline" entry instead of dropping them. That's the oldest `before` per settings key, plus file changes not yet reverted. Alternatively, never evict an entry that still has APPLIED, non-reverted changes that no later change superseded.

**Test:** Journal + UndoPlanner: entry A (render distance 12 → 8, enable x.jar), then 50 switch entries alternating 8 → 10 / 10 → 8 through `Journal.update`. `plan(all)` should restore 12 and disable x.jar. Today it restores 8 and leaves x.jar.

---

## M7 (medium; needs a test to confirm it happens with real Modrinth data): an update's jar is never checked to be the same mod

**Scenario:** the installed jar is a secondary file of its Modrinth version, or the project changed its mod id. The update takes the latest version's `primaryFile()`, which declares a different fabric mod id. RigTune then disables the installed mod and enables a different one, and every mod that depends on the old id fails to start.

**Evidence**
- `core/modrinth/OnlineDataFetcher.java:88-91`: `ModFile file = next.primaryFile()`.
- `core/modrinth/DownloadPlanner.java:207-215`: `jarModId` is read and used for the ENABLE but never compared with `update.modId()`.
- `core/apply/ApplyExecutor.java:346`: the helper compares the jar with the staged id, which is that same `jarModId`.

**Fix:** refuse unless `jarModId` equals `update.modId()`, or the new jar `provides` the old id (via `JarInfo`).

**Test:** DownloadPlannerTest: `update("m-1.jar", …)` whose downloaded fixture jar has id "other" → refused. Today it's staged.

---

## L1 (low): the "Imported from 0.1" entry is created whenever history.json is missing

**Scenario:**
- (i) The player deletes history.json. The next start recreates it with a legacy-import entry built from the 0.4 helper's last-apply.json and pending.json. If the last run was an undo, its re-enable/disable are recorded as "Imported from 0.1" applied changes, and Undo last would redo the undone update. The Undo screen shows this before confirming.
- (ii) preLaunch couldn't get the lock at the first start (a helper still running), so the first journal write is a staging. `Staging.stage` saves pending.json before the journal update. The legacy import then reads the new Apply's ops as "0.1 leftovers" and journals them under the legacy entry with the old run's time. The Apply's own record skips them as already journaled.

**Evidence**
- `client/undo/ClientJournal.java:21-22`: the first-entry supplier is always `HistoryStartup.legacyEntry`.
- `core/history/Journal.java:139-144, 173-184`: it's called whenever the file is MISSING.
- `client/undo/HistoryStartup.java:44-46, 65-76`: `legacyEntry` reads pending.json and last-apply.json with no version check.
- `core/history/LegacyImport.java:29-43`.
- `client/undo/Staging.java:84-86`: merge and save come before the journal update.
- `core/history/StagedChanges.java:41-44`: already-journaled ops are skipped.

**Fix:** build the legacy entry only when the files show a 0.1.x origin (for example, last-apply.json or rigtune.json predates 0.2 and no `helper/` copy of a ≥0.2 jar exists). In `Staging.stage`, have `first()` see pending.json as it was before the merge.

**Test:** Journal/HistoryStartup: a 0.4-shaped last-apply.json with history.json absent → no legacy entry. StagingTest: the first `stage()` with no history.json → the ops are under the Apply's entry id.

---

## L2 (low): the helper rewrites pending.json before last-apply.json; dying or failing in between turns applied changes into "Not applied"

**Scenario**
1. The helper applies every op. `writeRemaining` deletes or rewrites pending.json.
2. Then `result.save(last-apply.json)` fails, or the process is killed. The save can fail on a full disk (the temp write needs space), or when AccessDenied lasts longer than AtomicFiles' 10 × 100 ms retries. A failed save throws out of `run`, so `updateJournal` doesn't run either.
3. At the next start, `reconcile` replays the *previous* last-apply.json, which doesn't match. It then marks every STAGED change whose op isn't in pending.json as ABANDONED.
4. The files were changed, but History says "Not applied", and Undo considers only STAGED/APPLIED changes, so it can't revert them.

**Evidence**
- `core/apply/ApplyExecutor.java:149-152`: `writeRemaining(...)` → `result.save(...)` → `updateJournal(...)`. `:216-219` deletes or saves pending.json.
- `core/history/HistoryUpdates.java:75-78`: `reconcile` → ABANDONED for STAGED changes not in pending.json.
- `core/history/UndoPlanner.java:256-263`: candidates are STAGED or APPLIED only.

**Fix:** save last-apply.json before rewriting pending.json. If the helper dies in between, pending.json still holds the ops: `reconcile` marks them APPLIED from the new last-apply.json, and the next run is idempotent (SKIPPED_ALREADY_DONE, no-op patches).

**Test:** ApplyExecutorTest: make the last-apply.json path unwritable after all ops succeed, then run `HistoryUpdates.reconcile` with the resulting files. Expect APPLIED; today ABANDONED.

---

## Checked and found OK

- **Helper lifecycle:** it waits for the game pid without the lock, settles 2 s, then holds the OS file lock (reentrant per thread, released by the OS on kill). It runs from copies in `config/rigtune/helper/` and falls back to a fresh copy name if an old helper still runs. If copying fails, the plan stays pending.
- **Writes:** `AtomicFiles` uses a temp file plus ATOMIC_MOVE, retrying on AccessDenied. pending.json is re-read before it's rewritten, and only the ops that ran are removed. Failed ops get `attempts+1`, and a group is abandoned as a whole after 3 runs. A kill before the pending.json rewrite re-runs idempotently (SKIPPED_ALREADY_DONE, no-op patches).
- **Config patchers:**
  - Sodium JSON keeps each field's JSON type and refuses one that doesn't fit.
  - The properties writer emits pure ASCII (`Properties.store(OutputStream)` escapes everything above `~`), so writing it as UTF-8 is safe.
  - TOML splices only the value token, refuses unknown shapes, duplicate keys and undecodable sections, and never adds a key.
  - Refusals are RuntimeExceptions, so they aren't retried within a run.
- **Downloads:** SHA-512 is required and checked before an atomic move. The temp file is deleted on failure, and redirects are allow-listed per hop. Downloads only ever write `<name>.jar.rigtune-pending`. A replaced download is renamed `.rigtune-superseded`, never deleted.
- **Groups and duplicates:** groups are all-or-nothing within one helper run, with disables first and rollback in reverse. Replacement by mod id retires the old pending jar and takes over its group. The duplicate-id guard at exit covers a jar the user installed or updated by hand, and an enable staged without a mod id (the jar's own id is read).
- **Paths:** containment and relocation compare canonical (real) paths on both sides, so symlinked or junctioned instance folders don't drop ops. `InstanceDirs` matches Fabric's `-Dfabric.modsFolder` handling, and the helper receives it.
- **History:** startup reconciliation (last-apply.json replayed, then STAGED-but-not-pending → ABANDONED). Journal corrupt → `.bad`, newer formatVersion never overwritten.
- **Vanilla options:** applied and saved at once, journaled from before/after snapshots.
- **Compatibility:**
  - 0.1.0's pending.json has ids, groups and mod ids, in disable-then-enable order, so the current executor reads it as intended.
  - 0.3.0 and 0.4 read each other's pending.json/last-apply.json; the optional ids are ignored or left null.
  - An unknown op type becomes "unknown operation" and is abandoned after 3 runs.
  - Every `Op` copy method carries `projectId`/`versionId`, and every `JournalChange` copy carries `modName`.
- **Two clients on one instance, or a shared mods folder:** an open jar blocks the disable and the group rolls back. Only delays; no breakage found.
- **Undo (settings side):** chain walking, "already original" vs "changed since", graphicsPreset write-back. Cross-restart Undo last ×2 on config keys is correct (the same-start case is 2n).
- **Not scored:**
  - Journal entries carry no instance id, so with a config folder shared across instances (symlinked), undo in one instance plans the other's entries by file name.
  - An abandoned undo re-enable's reason says "its download is renamed to .rigtune-superseded", which isn't true for a `.disabled` source (`ApplyExecutor.java:304-305` vs `PendingActions.java:266-268`).
