# Verification of the apply-pipeline audit (docs/research/v0.4/audit-apply-pipeline.md)

Adversarial check of all 14 findings, 2026-09-26. Read-only: no builds, no Java/Gradle/Minecraft runs.

- **Code state.** `src/` is byte-identical between the audited 22cc915 and feat/v0.4.0 HEAD d2f789a (`git diff --stat 22cc915 HEAD -- src` is empty), so the audit's line numbers hold at HEAD. Against v0.3.0, the in-scope core files differ only in `PendingActions.Op.projectId/versionId` and `JournalChange.modName`, so every finding is also in the released 0.3.0.
- **Branches in flight** (read with `git show`):
  - WS-P, origin/feat/profiles: M1 is already fixed there (39c631e changes `PendingActions.merge`; a81c96e adds `EffectiveSettings`). Neither is merged.
  - WS-A, origin/fix/v04-deferred: 2n (7794d2c, `UndoPlanner.afterRestart`), 2d and 2e are in progress in DownloadPlanner, DependencyResolver and RealController.
- **Real-world data.** I read (never wrote) the user's real instance: `%APPDATA%/ModrinthApp/profiles/Fabric 26.2/mods` and `logs/latest.log` (Fabric Loader 0.19.5, 163 mods, last run 2026-09-25 09:08).

## Summary

Verdicts: 14 CONFIRMED, 0 PARTLY, 0 REFUTED. Several likelihoods are lower than the audit's ordering suggests. H2 is more likely than the audit says, because Iris pins Sodium too, not only Nvidium.

| id | verdict | realistic severity | likelihood | covered by | proposed owner |
|---|---|---|---|---|---|
| H1 | CONFIRMED (A and B) | game won't start | A: low (an update must add a required project that isn't installed). B: very low (the bundled rules offer no disable that anything depends on) | none (2d/2e/A-H1 cover incompatibilities only) | WP-1 (A), WP-2 (B) |
| H2 | CONFIRMED | game won't start | low to medium. Iris 1.11.4 declares `sodium: ["0.9.x"]`, so any Sodium 0.10 released for 26.2/26.3 before Iris catches up breaks Iris users. The Nvidium case is niche (opt-in, NVIDIA Turing or newer) | none. DESIGN.md:228 documents "ignores version pins" for additions only. Review-3 made Nvidium opt-in, which doesn't cover the update side | WP-1 |
| H3 | CONFIRMED; Fabric side verified on the real instance | game won't start, only if the added mod's range excludes the nested copy (otherwise the drop is harmless) | low. Main case: Distant Horizons users without a standalone Fabric API (DH 3.3.x nests fabric-api 0.149.0) | none | WP-1 |
| H4 | CONFIRMED | game won't start, no RigTune recovery | very low (needs a sharing violation on a group's 2nd+ rename, plus a kill or a failed rollback inside that window) | none | WP-3 (new; nobody owns group logic) |
| H5 | CONFIRMED | game won't start, no RigTune recovery | low (Undo of a mod and its dependency, with only one of the two disables failing for 30 s) | none. A-H1 fixed the forward direction only | WP-2 |
| M1 | CONFIRMED at HEAD | wrong setting silently wins (e.g. shaders on in Battery); nothing breaks | rare today; routine with profiles if unfixed. Also breaks 2n's fix on the 3rd alternating Undo last (see M1) | coordinator decision after the audit; **fixed on feat/profiles** (39c631e + a81c96e), not merged | WS-P (in flight) |
| M2 | CONFIRMED | a mod left `.disabled` and shown as "Cancelled"; the file isn't lost | very low (needs H4's state, a game that still starts, and an Undo/Discard before the next exit) | none | WP-2 |
| M3 | CONFIRMED | wrong entry offered (visible before confirming); an older entry is half-undone if confirmed; "no longer in the mods folder" is false | low to medium | partly: 2n (the settings side only) | WP-2 |
| M4 | CONFIRMED | an incompatible pair gets staged; the game won't start only if the pair also has a fabric.mod.json `breaks` | low (Apply within seconds of start, or after a failed lookup, which isn't retried until a rescan) | none (2d's fold doesn't need online data, but `refuseIncompatible` does) | WP-1 |
| M5 | CONFIRMED | "Disable X" silently becomes "Update X"; recoverable at the next start | low | none | WP-4 |
| M6 | CONFIRMED | Undo all becomes silently incomplete (pre-RigTune values and added mods no longer undoable); nothing breaks | rare before 0.4; weeks to months for players who accept the battery offer | none (SPEC 4/P-H1 don't mention the cap) | WP-5 (new) |
| M7 | CONFIRMED (no check exists) | game won't start if anything depends on the old id | very low (not seen in real data) | none | WP-1 |
| L1 | CONFIRMED | mislabel; the Undo target shown before confirming can surprise | very low | none | WP-5 |
| L2 | CONFIRMED | applied changes shown as "Not applied" and not undoable; files are fine | very low (mostly a full disk when every op finished, so pending.json is deleted rather than rewritten) | none | WP-3 |

---

## H1: an update never resolves its new version's required dependencies; a disable never checks dependants

**Verdict: CONFIRMED (both scenarios).**

**Evidence, scenario A (update):**
- `core/modrinth/DownloadPlanner.java:186-218`: `updateMod` calls only `resolver.checkUpdate(next, …)` (:205), then fetches and stages `{disable, enable}` (:214-215). Unlike `addMod` (:141), it never calls `resolver.resolve`.
- `DependencyResolver.java:138-142` leads to `refuseIncompatible`, whose loop skips every non-`incompatible` entry (:148-151).
- `Recommender.java:416-417` ticks `UpdateMod` by default.
- Nothing else filters an update by its new dependencies. `updates()` (:379-419) checks only installed, queued and `skipUpdateWhen`. `OnlineDataFetcher.isUpdate` (:179-185) checks only date and stability.
- Fabric refuses to start when a `depends` id is missing, and the helper runs only from `CLIENT_STOPPING` (`client/RigTuneClient.java:81-85`), so RigTune can't recover.

**Evidence, scenario B (disable):**
- `RealController.java:437-441` stages a bare `Op.disableFile`.
- `Recommender.disable` (:466-477) and its callers `obsolete()` (:274-286) and `avoided()` (:288-302) never read `depends`.
- The only `getDepends` reader is `client/undo/ModsFolder.java:103`, used by Undo's `UndoPlanner.violations` (:708-745).
- With the bundled rules, the disables are obsolete indium/starlight/phosphor/optifabric (none has a 26.x build, so they can't be loaded) and avoided nvidium/renderscale/lambdynlights (no known dependants). So B needs a rules change or a player add-on.

**Likelihood:**
- A: the new required project must not be installed. The common libraries (fabric-api, cloth-config, yacl) are usually installed already. Adding a required dependency within one MC version is uncommon; I found no instance in the research data.
- B: very low with the current rules, but remote rules can add an avoid entry weekly, and the Recommender would then disable a library with no guard.

**Test:**
- A: `DownloadPlannerTest`. The fake client's version map has `mV` (project M) with `required("LIB")`; LIB is not in `installedProjects`; plan an `update("m-1.jar", mV)`. Today ops = `[disable m-1.jar, enable mV.jar]`. Expected: a refusal (minimal fix), or `[disable, enable mV, enable libV]` in one group (full fix).
- B: a pure test of the new folder check (below): jars `x.jar` (id x) and `y.jar` (id y, depends x), disable `x.jar` → refused with "y would be missing x".

**Fix:**
- A, minimal: in `updateMod`, after `checkUpdate`, refuse when `next` has a `required` dependency whose `projectId` is neither in `attempt.projects` nor staged in the batch (`groupOfProject`): "needs %s, which isn't installed". Full fix: pull `addMod`'s per-version loop (:145-183) into a helper and resolve those dependencies into the update's group. Do it together with H3's fix, otherwise a library present only nested is dropped again.
- B: before staging a `DisableMod`, run the "would this folder start?" check. Extract `UndoPlanner.violations` into a core `FolderCheck` so Apply and Undo share it, and refuse with a Preview skip reason.

**Owner / overlap:**
- A: DownloadPlanner and DependencyResolver belong to WS-A, whose branch rewrites `plan()`, `addMod` and `updateMod` for 2d/2e. Either WS-A takes it before it finishes, or WP-1 starts after WS-A merges.
- B: RealController.apply (a hotspot: WS-P's `apply(selected, entryId)`) and UndoPlanner (WS-A's 2n). Put it in WP-2 with H5 because they share `FolderCheck`, and start after WS-A and WS-P merge.

## H2: an update ignores installed mods' version pins on the mod being updated

**Verdict: CONFIRMED.**

**Evidence (code):**
- `DependencyResolver.java:148-151`: only `incompatible` entries are read.
- `:177-187`: installed mods are checked only with `declaresIncompatible`.
- `:118-121`: a required project present at any version counts as satisfied.
- No fabric.mod.json version range is read anywhere; `ModsFolder` reads ids only.

**Evidence (the pins are real):**
- Nvidium: docs/research/v0.2/triage.md:240-244 and :348 (live-checked 2026-09-25): nvidium 0.4.4-beta7-26.3 declares `depends: {sodium: ["0.9.2"]}`, and its Modrinth dependency points at the Sodium version `mc26.3-0.9.2-fabric`. That the bare-version form is an exact match comes from triage.md and review-3; I didn't re-check Fabric's parser in this session.
- **New: Iris.** The real instance's `iris-fabric-1.11.4+mc26.2.jar` declares `depends: {"sodium": ["0.9.x"]}`. A 0.9.3 update is fine, but a Sodium 0.10.x offered for the same MC version before Iris updates makes Fabric refuse to start. This happened in practice at Sodium 0.5 → 0.6 on 1.21.1, a common support issue. Iris is far more widely installed than Nvidium.
- Nvidium is now `defaultSelected: false` and offered only on GTX 16/RTX with Sodium and no shaders (knowledge.json:326-344, review-3 rules-accuracy-3). So the "Install Nvidium + pre-ticked Update Sodium" batch needs an explicit tick. An already-installed Nvidium plus a pre-ticked Sodium update needs nothing unusual.

**Likelihood:**
- Nvidium: low (niche hardware and opt-in), but certain on every Sodium release that comes before Nvidium's.
- Iris/Sodium: medium-low (a window of days, a few times per MC version).
- Any other mod pinning a dependency tightly counts too.

**Test:**
- `DependencyResolverTest`: installed `{s092 (SODIUM), n1 (NVIDIUM, dependencies [required versionId s092])}`; `checkUpdate(s093, …)` must throw (today it passes).
- `DownloadPlannerTest` with an injected range predicate `(modId, version) -> problem`: installed Iris requires `sodium 0.9.x`; an update to 0.10.0 is refused and 0.9.3 is staged.

**Fix:**
- Minimal (pure core, covers exact Modrinth pins such as Nvidium's): in `checkUpdate`, refuse when another installed version has a `required` dependency whose `versionId` equals the installed version being replaced: "%s needs this exact version of %s".
- Full: RealController builds a predicate from `FabricLoader.getAllMods()`: each mod's `getDepends()`/`getBreaks()` on the updated id, matched against the new jar's fabric.mod.json `version` (read next to `modIdOf` in ModJars) with Fabric's `ModDependency.matches`. Pass it into DownloadPlanner and refuse with "%s needs %s %s".

**Owner / overlap:** WP-1. It collides with WS-A (DependencyResolver helpers, DownloadPlanner, the RealController `download()` call site), so sequence after WS-A, or hand it to WS-A if still open.

## H3: a library bundled inside another mod makes the planner delete the standalone copy an added mod needs

**Verdict: CONFIRMED. The "needs a test" premise is verified empirically.**

**Evidence (code):**
- `ModScanner.java:47-50`: a nested mod is kept with a null file and sha1. It comes from `FabricLoader.getAllMods()` (loaded mods), so it's in the scan exactly when the nested copy is the one Fabric loaded.
- `OnlineDataFetcher.java:61-66`: only hashed mods are looked up, so a nested library's project is never in `installedProjects`, and the resolver re-resolves and downloads it.
- `RealController.java:566-570` (download) and `:764-768` (preview): `loadedIds` includes nested ids.
- `DownloadPlanner.java:174-178` then drops and deletes the download (`dropDuplicate`).

**Evidence (Fabric side), from the real instance:**
- Distant Horizons 3.3.0 (`fabric-26.2.jar`), and the official 3.3.2 Modrinth file sitting there as `.rigtune-pending`, both nest `fabric-api-0.149.0+26.2.jar`. The instance also has a top-level `fabric-api-0.161.0+26.2.jar`.
- `latest.log` shows "Fabric Loader 0.19.5 … fabric-api 0.161.0+26.2" loaded, and the game started.
- `freecam-fabric-1.4.1` nests `cloth-config-fabric-26.2.155` next to a top-level `cloth-config-26.2.155`, and the log loads cloth-config 26.2.155 once.
- So a top-level copy next to a nested one is accepted, and the (newer) top-level copy wins.

**Likelihood:**
- The precondition: a library present only nested, an addition that requires it on Modrinth, and the addition's fabric.mod.json range excluding the nested version.
- Realistic case: a DH user without a standalone Fabric API (DH + Sodium + Iris packs don't need one) adding a mod that requires `fabric-api >= 0.15x`.
- Overlap in the real instance: 2 of 57 jars (fabric-api, cloth-config), both also top-level, so H3 wouldn't fire there.
- Nested-only libraries in that instance (conditional-mixin ×6, mixinsquared, spruceui, xaerolib, TRender, battery, yumi) aren't libraries the bundled additions require (unverified).
- Overall low. Severity is high only when it fires; otherwise the drop is harmless.

**Test:**
- Extract the id set into a pure helper (e.g. `DownloadInputs.topLevelIds(List<InstalledMod>)`). An `InstalledMod` with file and sha1 null is excluded.
- `DownloadPlannerTest.libraryUsers` with `loadedIds={"lib"}` gives `[aV.jar]` today. With `loadedIds={}` it gives `[aV.jar, libV.jar]` in one group.

**Fix (minimal, no new field):** build `loadedIds` from top-level mods only. `InstalledMod`'s own Javadoc says sha1 is null exactly for built-in and nested mods, so use `scanned.stream().filter(m -> m.sha1() != null || m.file() != null)` at both call sites. A top-level jar whose hash failed would then be downloaded again, and the helper's `duplicateProblem` still abandons it at exit, so that stays safe.

**Owner / overlap:** WP-1. The two lines sit in RealController `download()`/`preview()`, exactly where WS-A adds its `StagedProjects.fold` calls, so do it in WS-A's branch or after it merges.

## H4: a group stays half-applied while the helper retries in place

**Verdict: CONFIRMED.**

**Evidence:**
- `ApplyExecutor.java:330-389`: disables run first (`rank`, :391-400). When op k fails, `retrying` (:484-500) keeps backing off in place while the earlier renames of the group are already done. The sharing budget is 30 s (:40-42, :119-136), and the rollback has its own 30 s (:415-434).
- Nothing records a group in progress; pending.json and last-apply.json are written only after every group has run (:146-152).
- The helper starts only from `CLIENT_STOPPING` (`RigTuneClient.java:81-85`). preLaunch doesn't run if Fabric's resolution fails, and it has no repair step anyway (`RigTunePreLaunch.java:31-75`).
- Unchanged since v0.3.0.

**Likelihood:** very low.
- The window exists only while a group's second or later rename hits a sharing violation. The known real-world failure (a 27 MB jar, per the comment at :36-39) was a *disable*, which runs first and leaves nothing half done.
- For an enable to fail, something must hold the just-downloaded `.rigtune-pending` file open.
- Then either a kill (shutdown or Task Manager) inside that window, or a failed rollback of a file the helper renamed itself seconds earlier.
- The "relaunch inside the window" branch is recoverable, as the audit says.
- Severity when it fires is high: updating Fabric API or Sodium (with Iris installed) leaves a folder that won't start.

**Test:** `ApplyGroupsTest`, as the audit describes.
- Setup: a `Mover` that throws `FileSystemException` twice on the ENABLE of `{disable old.jar, enable new.jar.rigtune-pending → new.jar}`, and a `Sleeper` that snapshots mods/ at every sleep.
- Assert: no snapshot lacks both `old.jar` and `new.jar`.
- A second case: the rollback also throws. The run must not return with `old.jar.disabled` and `new.jar.rigtune-pending` both present.

**Fix (minimal):** in `runGroup`, once a group's first rename has succeeded, don't retry in place. On any failure, roll back at once (the rollback keeps its full budget) and re-run the whole group from its untouched state under a single sharing budget. The half-applied window then shrinks to the time between two back-to-back renames.
- Equivalent alternative: first rename each enable's `.rigtune-pending` to a still-ignored `.rigtune-ready` under the full budget, then do the disables and the final renames back to back.
- The audit's shutdown-hook idea is UNVERIFIED (whether a console-less helper gets CTRL_SHUTDOWN_EVENT) and isn't needed for the minimal fix.

**Owner / overlap:** WP-3, a new follow-up (nobody owns the group logic). ApplyExecutor is also touched by WS-A's 2f wording change (:186, :499) and the tests asserting it, so start after WS-A merges. It must stay helper-safe (core + Gson; HelperLauncherTest), and 0.1.0's pending.json group semantics are unchanged.

## H5: Undo stages a library and the mod that needs it in separate groups

**Verdict: CONFIRMED.**

**Evidence:**
- `UndoPlanner.java:572-607` accepts groups newest first against a cumulative simulation. Sodium Extra's disable is accepted first, so Sodium's disable then passes `violations`.
- `netOps` (:751-790) joins groups only through a shared file content (:758-766); a dependency edge never joins them.
- For two "Undo last" in one start, `violations` treats a still-pending DISABLE as already done (:690-711).
- `ApplyExecutor.execute` (:228-243) runs every group on its own.
- Traced for the audit's case (Sodium, then Sodium Extra, then Undo all): two groups, `{disable sodium-extra.jar}` running first (the TreeSet order puts "sodium-extra" before "sodium.") and `{disable sodium.jar}`. If the first fails after its 30 s budget and the second succeeds, Sodium Extra is active without Sodium.
- The helper's retry doesn't help: the game won't start, so there is no next exit.

**Likelihood:** low. A disable failing for the full 30 s does happen (the reason the sharing budget exists), but only one of the two may fail, and the player must undo a dependant together with its dependency. Severity when it fires: high.

**Test:** `UndoPlannerTest`.
- Folder `sodium.jar` (id sodium) and `sodium-extra.jar` (id sodium-extra, depends sodium); entries e1 (ENABLE sodium.jar, g1) and e2 (ENABLE sodium-extra.jar, g2), both APPLIED.
- `plan(all).script().fileOps()` must have one distinct group; today it has two.
- Second case: pending `disableFile(sodium-extra.jar).inGroup("u1")` with e2's undo STAGED. `plan(last)` must put sodium.jar's disable in group u1, or refuse it; today it creates a new independent group.

**Fix (minimal):**
- `netOps`: one group id for all file ops of one undo plan (drop the union-find grouping; all-or-nothing like the forward direction). The `anchors` map then has a single entry.
- `violations`: when the check passes only because a still-pending DISABLE is treated as done, give the new ops that op's group id. `merge` keeps incoming groups, so they join it.

**Owner / overlap:** WP-2, after WS-A merges. WS-A owns UndoPlanner for 2n and changed `build()`'s `planSettings` call line; the H5 edits are in `planFiles`/`netOps`/`violations`, which are disjoint but in the same file.

## M1: staged config changes A → B → A before a restart end at B

**Verdict: CONFIRMED at HEAD and v0.3.0; fixed on feat/profiles, not merged.**

**Evidence (HEAD):**
- The stagers make one op per key (`SodiumConfigPatcher.java:134-154`, `PropertiesConfigPatcher.java:56-75`, same in Toml).
- `PendingActions.merge` (:164-174) dedupes against *any* `sameChange` op, so op3 `{k:false}` matches op1 and is dropped (a patch op has no group, so there's no regroup).
- `ApplyExecutor.execute` runs ungrouped patches in plan order (:228-243), so the file ends at op2.
- `StagedChanges.of` (:41-44) maps op3 to op1's already-journaled id and records nothing.
- Plain Applies reach this only after a crash, because `withoutStaged` hides staged `set:` rows (`RealController.java:390-400`). Profile switches reach it routinely.
- The audit's design note is also right: `SettingsBridge.read` returns file values for config keys (:54-63, :77-83).

**Fix in flight (WS-P, coordinator decision on audit M1):**
- 39c631e: `PendingActions.merge` uses `repeatOf`, so a patch repeats only the *last* pending op for its file and key. An immediate repeat is still dropped, and P-H1's "no replacement" still holds.
- a81c96e: `core/profile/EffectiveSettings` gives switches, Preview, templates and saves the last-pending-op value.
- Both parts are needed. With `EffectiveSettings` alone, switch 3 emits `{k:false}`, and HEAD's `merge` would still drop it against op1.
- Note for the record: this changes `PendingActions.merge`, which the plan said P-H1 leaves unchanged. It's a narrower change than the replacement P-H1 rejected, and it is WS-P's hotspot per PLAN.

**Interaction with 2n (exact):**
- WS-A's 2n fix (7794d2c, `UndoPlanner.afterRestart`) takes a staged key's current value from the last still-pending op, the same rule as `StagedChanges.stagedValue` and `EffectiveSettings`. That rule is correct only if `merge` never drops a new op against an older, overridden one.
- Trace with HEAD's `merge`:
  - History: e1 k T→F, e2 F→T, e3 T→F, all applied; the file says F.
  - Undo last ×3 in one start stages u1 `{k:T}`, then u2 `{k:F}` (current = u1), then u3 `{k:T}` (current = u2).
  - `merge` drops u3 as a repeat of u1 (`UndoService.java:98-101` stages through `Staging.mergeLocked`). The undo change records opId u1.
  - The helper runs u1 then u2 and ends at F, while History says all three were undone.
- So once 2n lands, the third alternating Undo last is still wrong until WS-P's `merge` fix is also on feat/v0.4.0. AC2n.1 tests two undos only.
- **One fix covers both:** the `merge` last-op rule (39c631e), plus one shared "effective staged value" function. Today there are three copies: `StagedChanges.stagedValue`, `EffectiveSettings.of` and `UndoPlanner.afterRestart`. Fold them into one later (not blocking).

**M6 is independent:**
- M6 cuts the *tail* of the same `planSettings` chain: the target becomes the oldest *surviving* `before`.
- 2n and M1 act on the *head*: the current value and what is pending.
- The cap never evicts an entry with STAGED changes before its third pass (`Journal.java:252-268`), so eviction never touches what 2n reads.
- No single fix covers M6 as well.

**Test:**
- WS-P's `PendingMergeRepeatTest.aToBToAToBKeepsTheLastB` covers the merge rule.
- Add one joint test once both branches have merged (coordinator, or WS-A while still open): `UndoPlannerTest` with a stager-shaped merge.
  - Setup: e1/e2/e3 as above, all APPLIED, state value F.
  - Call `plan(last)` three times, each time merging `script.staged()` as an op into the pending list and appending the undo entry.
  - Assert: the pending list's last op for k sets T, and it has 3 ops.

**Owner / overlap:** WS-P (in flight). The only overlap is the joint test.

## M2: Undo or Discard during a half-done update drops the group and never re-enables the old jar

**Verdict: CONFIRMED (conditional on H4's state).**

**Evidence:**
- A failed rollback leaves `x-1.jar.disabled` (`ApplyExecutor.java:415-434`), and both ops stay in pending.json as FAILED (:198-215).
- The journal changes stay STAGED (`HistoryUpdates.java:54`).
- `UndoPlanner.planStaged` (:373-421) discards the whole group without looking at the folder, as do `Staging.unstageLocked`/`discard` (:171-192, :244-255) and `PendingActions.discard` (:283-302).
- The download becomes `.rigtune-superseded`, `x-1.jar.disabled` stays, and History says DISCARDED ("Cancelled").

**Likelihood:** very low. It needs H4's state, a game that still starts (nothing depends on X), and an Undo or Discard before the next exit; the next exit would otherwise finish the group (disable SKIPPED_ALREADY_DONE, enable OK). Severity: low to medium. The mod is disabled and mislabelled, but the file is kept.

**Test:** as the audit describes.
- `UndoPlannerTest`: pending `{disable x-1.jar, enable x-2.jar.rigtune-pending → x-2.jar}` in group g, both changes STAGED, folder `{x-1.jar.disabled, x-2.jar.rigtune-pending}`.
- Today `plan(last)` discards both and stages no file op.
- Expected: a re-enable of `x-1.jar.disabled` in the plan, or a SKIP with "partly applied at the last exit; restart once, then undo it".
- The same shape in `StagingTest` for `discard()`.

**Fix:** the skip is the smallest change. In `planStaged`, when a group's DISABLE path is gone and its `.disabled` target exists while the group's ENABLE is still pending, skip with a new reason. `Staging.discard` keeps such a group, or stages the re-enable.

**Owner / overlap:** WP-2, after WS-A (UndoPlanner, Staging).

## M3: a second "Undo last" in one start skips an entry that is waiting for the first undo's staged op

**Verdict: CONFIRMED.**

**Evidence (traced):**
- B's changes are excluded as being reverted (:256-263).
- `planFiles` builds its simulated folder from disk only (:562-568). A's "disable x-1.jar" is FILE_GONE (:618-624).
- `UndoPlan.isEmpty()` means "all items are SKIP" (`UndoPlan.java:64-66`), so `plan()` falls through to C (:127-140).
- Undo all from the same point also skips A with the false "x-1.jar is no longer in the mods folder".
- If A also had a vanilla change, the partial undo puts A in `ctx.undone`, and Undo last never offers it again (Undo this/all still can after a restart).

**Likelihood:** low to medium. Updates are ticked by default, so "add X → later update X → Undo last twice" is a plausible path. Severity: low. The plan is shown before confirming, nothing breaks, and after a restart A is offered again unless it was partly undone.

**Test:** `UndoPlannerTest`, as the audit describes.
- Entries C (renderDistance 12→16), A (ENABLE x-1.jar, g1), B (DISABLE x-1.jar → x-1.jar.disabled, ENABLE x-2.jar, g2), plus an undo entry for B with STAGED changes and its pending ops.
- Folder `{x-2.jar, x-1.jar.disabled}`.
- Today `plan(last).plan().undoOf()` is C's id; expected: A's id, or A's plan with a "waiting for a restart" skip and no fall-through.

**Fix (minimal):** in `planFiles`, a file that is the target (`to`) of a still-pending ENABLE, or the `path` of a still-pending DISABLE, gets a new "waits for the restart" skip. `plan()` returns an entry whose skips include that reason instead of falling through.
- The audit's first option (overlay pending ops onto the simulation) is not minimal: `netOps` would have to compute its renames from the post-pending names, or it cancels the pending re-enable.

**Owner / overlap:** WP-2, after WS-A. It is the file-side twin of 2n, in the same file; WS-A could absorb it into 2n only if it stays that small.

## M4: Apply before the Modrinth lookup finishes, or after it failed, skips every installed-side incompatibility check

**Verdict: CONFIRMED.**

**Evidence:**
- `online` starts as `Result.offline()` (`RealController.java:135`), and a failed lookup ends there too (`OnlineDataFetcher.java:121-130`).
- The lookup gate re-runs only for a new scan or new rule slugs (`OnlineLookupGate.java:37-48`), so a transient failure leaves the whole session on offline data until a Rescan.
- `startDownloads`/`download` (:514-574) never check `data.data().online()`. The resolver is built with `installedVersions = {}` and `installedProjects = {}`, while `resolve()` still calls Modrinth live.
- So `refuseIncompatible`'s installed checks (:152-163, :177-187) see nothing. Updates can't slip through: offline data has no updates, and `updateVersions` is empty, so they are refused as "stale".
- The rules-level checks (`conflictsWith`, avoid) still work offline, so only Modrinth-only incompatibilities are missed.

**Likelihood:** low (clicking Apply within the first seconds, or after a failed lookup). Severity: medium. It is game-won't-start only when the pair also has a fabric.mod.json `breaks`.

**Test:** make the guard a pure static (e.g. `DownloadGuard.refusal(boolean online, List<Recommendation>)`, or a check in `DownloadInputs`). With online=false and an AddMod: refused, and the fetcher is never called. With online=true: passes. PreviewGameTest can delay the fake Modrinth's `/version_files`.

**Fix:** in `startDownloads` (and Preview's download rows), refuse Add/Update while `!data.data().online()` and Modrinth is allowed: "Modrinth data isn't loaded yet; try again in a moment". Optionally trigger the lookup again.

**Owner / overlap:** WP-1. It's the same RealController method as WS-A's fold call and must stay a one-line delegation (hotspot rule).

## M5: a later "Disable X" is absorbed into an already staged "Update X"

**Verdict: CONFIRMED.**

**Evidence:**
- `RealController.java:437-441` makes an ungrouped `Op.disableFile(x-1.jar)`.
- `merge` (:164-174) finds `sameChange` with G1's DISABLE; the op's group is null, so it is dropped with no regroup.
- `StagedChanges` records nothing, because G1's disable op is already journaled.
- `withoutStaged` filters by recommendation id only (:390-400), and update and disable are exclusive only within one report (`Recommender.java:388`).
- The path is real: `rescan()` is public (:251) and re-probes flags (`HardwareProbe.java:123` `shaders-enabled`), so turning shaders on can make Nvidium's `avoidWhen` match. That disable is ticked by default, because `avoidSelected` is absent.

**Likelihood:** low. Severity: low to medium. X comes back as 2.0, and "Disable X" is offered again at the next start.

**Test:** `PendingActionsTest`: base `group(disableFile(a1), enableFile(a2p, a2).withModId("a"))`, then merge `[disableFile(a1)]`. Today the ENABLE with modId "a" remains. The fix belongs to the caller (RealController/Staging), so add a `StagingTest` case asserting the update group is removed before the disable is staged.

**Fix:** in `RealController.apply`, for a `DisableMod` whose file is the DISABLE of a staged group that also ENABLEs a jar, first unstage that group (`Staging.unstageLocked` with its op ids, which also retires the download), then stage the disable. Alternatively refuse with "an update of %s is staged; undo it first".

**Owner / overlap:** WP-4. RealController.apply is WS-P's hotspot (`apply(selected, entryId)`), so start after WS-P merges.

## M6: profile switches fill the 50-entry cap, evicting the first Apply

**Verdict: CONFIRMED.**

**Evidence:**
- `Journal.java:31`: `MAX_ENTRIES = 50`.
- `cap` (:252-268): pass 1 drops "finished" entries, meaning no STAGED changes and either an undo entry or no APPLIED change. An apply entry with APPLIED changes is never finished. Pass 2 then drops the oldest entries without STAGED changes, which catches the first Apply.
- `UndoPlanner.planSettings`'s chain (:470-500) targets the oldest surviving `before`, and the evicted Apply's added jars are no longer in any entry.
- Every switch is one apply entry (SPEC 4). WS-P's ProfileService goes through `controller.apply(recs, entryId)`, per feat/profiles `ProfileService.java:318`.
- Only partial mitigation: WS-P saves "My settings" before the first switch (a81c96e). That is usually *after* RigTune's first Apply, covers only the managed keys, and never covers mods.

**Why the obvious fixes fail:**
- Pinning the first Apply alone doesn't help: evicting the switch entries between it and the kept ones breaks the chain's contiguity, and Undo all stops with CHANGED_BETWEEN.
- Evicting switch entries preferentially has the same problem.

**Likelihood:** reachable in weeks to months for a laptop player who accepts the battery offer on unplug and plug-in (2 entries per cycle), sooner with Applies and undos on top. Severity: medium (silently incomplete Undo all; nothing breaks).

**Test:** `JournalTest` plus `UndoPlannerTest`.
- Entry A (renderDistance 12→8, ENABLE x.jar), then 50 entries alternating 8→10 and 10→8, added through `Journal.update`.
- `plan(all)` should restore 12 and disable x.jar. Today it restores 8 and leaves x.jar.

**Fix:** in `Journal.cap`, fold instead of drop. The entries pass 2 would evict become one leading "baseline" entry (kind `apply`, so 0.3.0 reads it; new id):
- Settings: per key, collapse the contiguous chain of evicted APPLIED changes into one change, from the oldest `before` to the newest `after`. Walk back from the newest evicted change and stop at the first break, as `planSettings` would.
- Files: copy the APPLIED, not-reverted file changes as they are (ids and groups kept).
- It is pure core, so it stays helper-safe.
- A cheaper stopgap if v0.4 can't take it: a higher cap plus a documented limit. That only delays the problem.

**Owner / overlap:** WP-5, a new follow-up; nobody in Wave A owns `Journal.java`. It touches the pinned-0.3.0 compat tests (WS-P's V030CompatTest) and WS-H's downgrade fixtures (a baseline entry must round-trip through 0.3.0), so start after WS-P merges.

## M7: an update's jar is never checked to be the same mod

**Verdict: CONFIRMED (the check is missing; not observed with real data).**

**Evidence:**
- `OnlineDataFetcher.java:88-91` takes `next.primaryFile()`.
- `DownloadPlanner.java:206-215` reads `jarModId` and stages the ENABLE with it, never comparing it with `update.modId()`.
- The helper's check (`ApplyExecutor.java:346-348`) compares the jar with that same `jarModId`, so it can't catch this.

**Likelihood:** very low. It needs the installed jar to be a secondary file of its Modrinth version, or a mod-id rename within one MC version. A primary file with no fabric.mod.json is already refused (`notAMod`). Severity: high when it fires (dependants of the old id fail).

**Test:** `DownloadPlannerTest`: `update("m-1.jar", …)` (modId "m") whose fixture jar declares id "other" → refused. Today it is staged.

**Fix:** refuse unless `jarModId.equals(update.modId())` or the new jar `provides` it (read with `JarInfo`/ModJars). About 3 lines in `updateMod`.

**Owner / overlap:** WP-1. It's WS-A's file, and the same `updateMod` lines WS-A edits (`withProjectId`/`withVersionId`).

## L1: the "Imported from 0.1" entry is created whenever history.json is missing

**Verdict: CONFIRMED.**

**Evidence:**
- `ClientJournal.java:21-22` always supplies `HistoryStartup.legacyEntry`.
- `Journal.update` calls `first()` for MISSING only (:139-144, :173-184); a CORRUPT file does not trigger it.
- `legacyEntry` (`HistoryStartup.java:65-76`) and `LegacyImport.entry` (:29-43) read last-apply.json and pending.json with no version check.
- Case (ii): `Staging.stage` saves pending.json (`mergeLocked`, :150-151) before `journal.update`, so `first()` reads the new ops. `StagedChanges` then skips them, because `stagedOpIds(entries)` already contains them (:100).

**Likelihood:** very low (the player deletes history.json, or a staging is the first journal write while a helper held the lock at first start). Severity: low (mislabel and wrong time; the changes stay tracked; the Undo plan is shown before confirming).

**Test:**
- `JournalTest`/HistoryStartup: a 0.4-shaped last-apply.json (with `resultPath`) and no history.json → no legacy entry.
- `StagingTest`: the first `stage()` with no history.json → the ops' changes are under the Apply's entry id.

**Fix:** build the legacy entry only when the files show a 0.1.x origin, for example no `resultPath` in any result and no `helper/` copy of a ≥0.2 jar. In `Staging.stage`, pass the pre-merge `base` to the first-entry supplier.

**Owner / overlap:** WP-5. WS-A touched `LegacyImport` (2c `modName`) and `HistoryStartup` (1 line), so start after WS-A.

## L2: the helper rewrites pending.json before last-apply.json

**Verdict: CONFIRMED.**

**Evidence:**
- `ApplyExecutor.run` (:146-153) runs `writeRemaining` (which deletes pending.json when nothing remains, :216-217), then `result.save`, then `updateJournal`.
- If the save throws, the journal isn't updated.
- `HistoryUpdates.reconcile` (:75-78) replays the *old* last-apply.json, then marks STAGED changes without a pending op ABANDONED.
- UndoPlanner considers only STAGED/APPLIED changes (:256-263).
- A full disk hits exactly this order: the deletion succeeds without space, and the atomic temp write then fails.

**Likelihood:** very low. Severity: low (History says "Not applied" and RigTune can't undo those changes, but the files are right).

**Test:** `ApplyExecutorTest`: all ops succeed, and the last-apply.json path is unwritable (a directory with that name). Then `HistoryUpdates.reconcile(entries, pendingIds, <whatever last-apply now holds>)`. Expect APPLIED; today ABANDONED.

**Fix:** save last-apply.json before `writeRemaining`. If the helper dies between the two writes, the ops re-run at the next exit idempotently (SKIPPED_ALREADY_DONE, no-op patches), and `reconcile` has already marked them APPLIED.
- Edge to accept: a patch that re-runs could overwrite a value the player changed in between (e.g. in Sodium's menu). That window is the same microseconds as today's.

**Owner / overlap:** WP-3, with H4 (same file; after WS-A's 2f).

---

## Recommended work packages (confirmed fixes, sequenced around the running workstreams)

| WP | findings | files | start when | collision notes |
|---|---|---|---|---|
| **WP-0 (in flight)** | M1 | PendingActions.merge (39c631e), core/profile/EffectiveSettings, ProfileService | WS-P merges | After both WS-P and WS-A are on feat/v0.4.0: the joint M1×2n test (3 alternating Undo last). Later cleanup: one shared effective-value function instead of three copies. |
| **WP-1 resolver completeness** | H1-A, H2, H3, M4, M7 | DownloadPlanner, DependencyResolver, ModJars (read the version), RealController `download()`/`preview()`/`startDownloads` (loadedIds filter, online guard, range predicate), a new client helper building the fabric.mod.json range predicate | WS-A merges, or WS-A takes it before finishing (it owns every file here) | Every file overlaps WS-A's 2d/2e diff. RealController edits stay one-line delegations (the hotspot rule; WS-W edits `rebuild()`, WS-P edits `apply`). Priority for v0.4: H2 (Iris/Sodium) and H3's two-line fix first, then H1-A (minimal refusal), then M7 and M4. |
| **WP-2 undo file safety** | H5, M3, M2, H1-B | UndoPlanner `planFiles`/`netOps`/`violations`/`planStaged` (+ extracting `violations` into a core `FolderCheck`), Staging `discard`/`unstageLocked`, RealController.apply's DisableMod branch (one call to `FolderCheck`) | WS-A (2n) and WS-P merge | Same file as WS-A's 2n (different methods; `build()`'s call lines are adjacent). The DisableMod check touches WS-P's `apply` hotspot, so keep it to one call. |
| **WP-3 helper atomicity** | H4, L2 | ApplyExecutor `runGroup`/`retrying`/`rollback`/`run`, ApplyGroupsTest, ApplyExecutorTest | WS-A merges (2f rewords :186/:499 and the tests asserting it) | Nobody owns this; a new small workstream. Helper-safe (HelperLauncherTest); 0.1.0's pending.json shape unchanged. |
| **WP-4 apply-time staging conflicts** | M5 | RealController.apply (DisableMod branch), Staging (unstage the update group) | WS-P merges | Can merge into WP-2's RealController touch to keep a single hotspot edit. |
| **WP-5 history retention** | M6, L1 | Journal.cap (fold), HistoryStartup/LegacyImport (legacy gate), Staging.stage (pass `base` to the supplier) | WS-P and WS-A merge | Touches V030CompatTest (WS-P) and WS-H's downgrade fixtures (the baseline entry must round-trip through the pinned and released 0.3.0 Journal); needs a re-run of WS-H's compat030 harness. |

Suggested order before the v0.4 RC:
1. WP-0 (already done, merge it).
2. WP-1: H2 and H3 first, because Iris users and DH users without a standalone Fabric API are real populations and the game won't start.
3. WP-2: H5.
4. WP-5: M6, because profiles make it reachable.
5. WP-3: H4 and L2.
6. The rest.

H4, M2, M7, L1 and L2 are very low likelihood and could be deferred with a written reason if the schedule is tight.
