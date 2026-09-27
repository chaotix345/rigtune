# WS-L2: the helper and the repair notice (v0.5 P0.4)

Branch `fix/v05-launcher-helper` (worktree `rigtune-l2`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/PLAN.md "WS-L2" (SPEC 4d, 4f, 4g, 4j.3 prep; RW-1; 3f's forcing writer; 2V's ws-g3 L8 message,
AC2V.2). Research: docs/research/v0.5/real-world-2026-09-27.md §2 (RW-1, the prototype), launcher-managed-mods.md §4.3
("Exit / helper"), §5 (repair). The prototype and its repro tests come from `C:/Dev/Worktrees/rigtune-realworld`
(uncommitted; `<scratch>/realworld/fix-prototype.diff`). Merge order: WS-H (`RigTunePreLaunch.readState`) and WS-L1 (the
real policy, the realworld fixtures) merge before this branch; it merges `origin/feat/v0.5.0` when they land.

This file is first the TDD task plan (committed before any code), then, as the work lands, the decisions, deviations,
residuals, UNVERIFIED items, Docs text, footprint deltas and the AC table.

## TDD task plan

Each task: the red test first (for RW-1 the realworld repro tests, which fail against 0.4's behaviour), then the code,
then a commit. Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot (no version-specific code is
planned: X9); the full build and the game tests are CI's. No code-deciding real run is named for WS-L2.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| L2-1 | RW-1: an enable that only *looks* done (download gone, target there) counts as RigTune's only when RigTune's own records prove the rename: a recorded rename in effect in `unfinished-groups.json` (the existing `earlier` path) or the same op id OK, or SKIPPED with a `resultPath`, in the `last-apply.json` this run replaces; else the group is ABANDONED "…installed another way" (the `duplicateProblem` path; nothing in `mods/` touched). A redo proven by `last-apply.json` reports "Already done earlier" **with** a `resultPath`, so a second death in the same window still proves it | `core/apply/ApplyExecutor` | `RealWorldFixTest` (package `core.history`; the helper cases of the realworld tests: the real run (A) now ABANDONED ×2, History "Not applied", nothing undoable, `mods/` untouched; the control with the download (B); the Ixeris addition installed through the app (E) not claimed, Undo stages no disable; a recorded rename stays done (F); a redo of the last run's own renames stays done). Red: A, E | AC4f.1 |
| L2-2 | Crash replay (coordinator decision): every kill point of a 0.4+ helper and the 0.1.0-0.3.x shape | tests only (fixes, if any, in `ApplyExecutor`) | `ApplyExecutorCrashReplayTest`: a helper killed (an `Error` thrown by the mover after its rename, so nothing after it runs) (1) after its renames, before `last-apply.json`: the next run completes the group from `unfinished-groups.json`; (2) after `last-apply.json`, before the prune: both records; (3) after the prune, before the `pending.json` rewrite: done through `last-apply.json`, with the reconcile preLaunch does in between; (4) (3) twice in a row: still done (the carried `resultPath`); (5) a 0.1.0-shaped `pending.json` (no attempts, no `unfinished-groups.json`) whose old helper died after its rename, with no `last-apply.json` or one of an earlier run: ABANDONED "installed another way", `mods/` untouched, the journal "Not applied", Undo offers nothing (the documented residual, the safe side); (6) a 0.1-0.3 death mid-group (disable done, download still there): the group completes; (7) an unreadable `last-apply.json`: the safe side. Plus, unchanged: `ApplyExecutorTest` (the finished-plan rerun, the L2 death), `ApplyGroupsTest` ("disable done by someone else"), `HelperCompat030Test` | AC4f.2 |
| L2-3 | The next launch's toasts: "N of M changes failed" counts FAILED ops only; ABANDONED ones only in the dropped toast; the applied toast counts the applied ops (today it is the total) | `core/apply/ApplyResult` (`counts()`), new `client/HelperToasts`, `client/RigTuneClient.showNotices` | `HelperToastsTest`: ABANDONED-only (the dropped toast only), FAILED-only, mixed, all applied (today's toast, unchanged) | AC4f.3 |
| L2-4 | 4d, helper side: `-Drigtune.helper.holdFileOps=true` on the 0.5 helper's command line under LAUNCHER and PENDING; the helper then runs the groups without a mod-file op and leaves the others in `pending.json`, attempts unchanged, and out of `last-apply.json` (no new status); a run that ran nothing writes no `last-apply.json` (no "applied 0" toast). Not held (see "Decisions"): a group RigTune's records show half done or done already (a recorded rename in effect, PartlyApplied's failed rollback, an op id done in the previous `last-apply.json`): the helper finishes or rolls it back as in 0.4 | `core/apply/HelperLauncher` (`holds(policy)`, the property), `ApplyHelper` (reads it), `ApplyExecutor` (`run(plan, pending, hold)`, `fileGroupOps`, `heldGroups`) | `ApplyExecutorHoldTest` (file groups stay with attempts unchanged, patches apply, `last-apply.json` has the patches only, an all-held run leaves `last-apply.json` alone, a half-done and a done-before group run, a group with a file op and a patch is held whole, no hold = 0.4); `HelperLauncherTest`: the property exactly under LAUNCHER and PENDING, the classpath still our jar and Gson; a child-JVM helper with only those two and the property holds the group and applies the patch | AC4d.1 |
| L2-5 | 4d, client side: the exit launch passes the policy (`RigTuneClient.launchHelperIfPending`; a failure to read it holds, the safe side). preLaunch (`readState`) counts the leftover ops in mod-file groups apart; when there are any, its WARN and the title-screen leftover toast wait until the policy is known (their own END_CLIENT_TICK listener, registered only then, returning at once when idle; never on `RigTuneClient.onTick`'s timed path): RIGTUNE → today's WARN and toast; LAUNCHER → "N mod changes are waiting for your choice" (WARN and toast), never "retried at the next exit", plus today's toast for the other leftovers | `client/RigTunePreLaunch` (after WS-H's RW-3 change), `client/RigTuneClient` (`launchHelperIfPending`, `showNotices`), `client/HelperToasts`, `client/launcher/LauncherRepairService` | `RigTunePreLaunchTest` (the split count; no "retried" WARN for file ops), `HelperToastsTest` (per policy: the texts and counts), game test (L2-10) | AC4d.4 (unit part) |
| L2-6 | HELD_MOD_CHANGES: "N mod changes from an earlier Apply are waiting: <launcher> manages this instance's mods." under LAUNCHER/PENDING, computed on a worker (pending.json, the records, the mods listing; `ApplyExecutor.heldGroups`, the helper's own rule), shown after the next rebuild; not dismissible (the changes wait for a choice). **Cancel them**: the held ops through `Staging.unstageLocked` (downloads → `.rigtune-superseded`, journal DISCARDED; half-done groups aren't held, so never cancelled). **Let RigTune apply them**: the opt-in on through `SettingsSaver` (WS-L1's method once it lands) | `client/launcher/LauncherRepairService`, `client/notice/HeldModChangesNoticeSource`, lang `rigtune.repair.held.*` | `LauncherRepairServiceTest` (unit, off the render thread: the held set, the notice text per launcher, Cancel's effect on `pending.json`/downloads/journal, the opt-in), game test (L2-10) | AC4d.2 (unit part) |
| L2-7 | 4g: `core/history/LauncherRepair` (pure; RigTune's own records only: `history.json` APPLIED file changes, the mods folder through `UndoPlanner.Folder`/`JarInfo`): **pairs in effect** (an APPLIED group's disable `o` with a `resultFile` and an enable `n` of the same mod id, a legacy disable's id read from its `.disabled` jar, both files there), **added jars** (an APPLIED enable in a group without a disable, still there, not RigTune), **disabled-only** (an APPLIED disable whose `resultFile` is there, no pair); a change whose file is gone is repaired; a disable without a `resultFile` was no rename of RigTune's (RW-14) and is never a finding. The notice (LAUNCHER only, only with a finding the launcher needs a step for): per-launcher text (Modrinth App: "delete the old disabled copy in the app" first, the three-step order as the fallback; Prism, GDLauncher, ATLauncher, CurseForge per lm §5.2; a generic line for a packwiz instance of another launcher), **Copy list** (file names only, escaped), Dismiss by a key built from the set of findings | new `core/history/LauncherRepair`, `client/launcher/LauncherRepairService`, `client/notice/LauncherRepairNoticeSource`, lang `rigtune.repair.*` | `LauncherRepairTest`: each finding and "repaired" from fixtures; the anonymised real instance (history.json after 0.4.0's helper run + the mods listing) gives exactly one pair (Entity Culling 1.11.1 → 1.11.2) and 7 added jars; the text per launcher; Copy list = names only, escaped; the key changes with the set; `LauncherFilesSourceTest`: no launcher database or launcher file read outside `InstanceFiles`/the `.index/` listing | AC4g.1, AC4g.2 (unit), AC4g.3 |
| L2-8 | 3f: `unfinished-groups.json` through a forcing writer before the first rename: the temp file's `FileChannel.force(true)` before the atomic move, then the folder fsync'd where the OS allows it (Linux; Windows can't open a directory as a channel, which is ignored) | `core/apply/UnfinishedGroups` (`writeDurably`, `DURABLE`; planned in `AtomicFiles`, which isn't WS-L2's: see "Deviations"), `ApplyExecutor` (the seam) | `UnfinishedGroupsDurableTest.aDurableWriteForcesTheFileBeforeTheMoveAndThenItsFolder` (a recording syncer), `ApplyExecutorTest.theRecordIsForcedToDiskBeforeTheFirstRename` (a mover that checks the recording writer saw the rename first) | AC3f.6 |
| L2-9 | 2V ws-g3 L8: a rollback whose moved file vanished says it was moved or deleted meanwhile, not "the original name is taken" | `core/apply/ApplyExecutor.rollback` | `ApplyExecutorTest.aRollbackWhoseFileVanishedSaysSo`, `…aRollbackOntoATakenNameKeepsItsMessage` | AC2V.2 |
| L2-10 | Game tests: `LauncherManagedGameTest.heldAndRepair` (a seeded 0.4-written file group under LAUNCHER: the notice, Cancel them → no file op left, the download `.rigtune-superseded`, the journal DISCARDED; a second seed: Let RigTune apply them turns the opt-in on; the leftover toast's wording; the repair notice from seeded records: text, Copy list on the clipboard, Dismiss; everything put back). `A11yGameTest.walkLauncherNotices` (NoticeScreen with both notices: Tab order, narration, the three sizes + 854×480@3). Forced policy until WS-L1 merges, then the real one (`minecraft.launcher.brand=theseus`, rescan, wait for `modFiles() != PENDING`) | `gametest/LauncherManagedGameTest` (its method), `gametest/A11yGameTest` (its method) | the game tests themselves, 3 legs, screenshots looked at | AC4d.2, AC4d.4, AC4g.2 (game parts) |
| L2-11 | Fixtures: `v050-written/ws-l2/` from the tests (`pending.json` with a held group as the helper leaves it, `awareness.json` with the repair notice's dismissed key) + `expect.json` (0.4.0's `PendingActions` reads it, its `ApplyHelper` applies the held group, its `AwarenessStore` keeps `dismissed`); compat030, and compat040 once WS-E lands it | `src/test/resources/v050-written/ws-l2/` | the writing tests compare by default, `RIGTUNE_REGENERATE_FIXTURES=1` writes | AC4d.3 (with WS-E's interpreter) |

Lang: `rigtune.repair.*` (the held-changes notice included) starts after the anchor `rigtune.toast.busy.body`
(ws-k.md 13), alphabetical inside; the held toast keys go into `rigtune.toast.*` next to `leftover`; the per-launcher
repair steps are a dynamic family registered in `V05LangFamilies.launcherRepair`.

## Decisions (all five accepted by the coordinator, 2026-09-27)

- **What the helper holds (4d).** A group with a mod-file op (ENABLE_FILE or DISABLE_FILE) is held whole, its config
  patches too (a group is all-or-nothing). Not held: a group RigTune's own records show half done or done already (a
  recorded rename in effect in `unfinished-groups.json`, PartlyApplied's failed rollback, or an op id done in the
  previous `last-apply.json`). Holding one would leave a mod missing (an old jar disabled, its replacement never
  enabled) until the player chooses, and Cancel can't drop it either (Discard pending keeps such groups, audit M2): the
  rename already happened, so finishing it (or rolling it back if it fails) is the consistent state. `ApplyExecutor`
  has the one rule (`heldGroups`); the notice and the counts use it too.
- **A held-only exit writes no `last-apply.json`.** An empty result would show "RigTune applied 0 change(s)" at the next
  start. SPEC-31 only says held ops stay out of it.
- **The HELD_MOD_CHANGES notice isn't dismissible.** The changes wait for one of the two choices; hiding the notice
  would leave them waiting unseen.
- **The repair notice shows only for a finding the launcher needs a step for:** a pair in effect (every launcher but
  ATLauncher, whose record follows the new jar), a disabled-only change (ATLauncher, where such a mod is invisible).
  Added jars are listed in the detail when the notice shows ("nothing to do") but never raise it alone.
- **preLaunch's WARN waits with the toast.** At preLaunch the policy isn't known (detection answers later, PENDING
  meanwhile), so for a plan with mod-file ops both the WARN and the leftover toast wait until it is; a plan with config
  patches only keeps today's preLaunch WARN and toast.

## Questions and dependencies (answered)

- WS-L1: the method "Let RigTune apply them" calls when the opt-in goes on. Until WS-L1 merges, the service does what the
  settings row does: settings.json through `SettingsSaver`, then a rebuild. The leftover listener reads the policy through
  the `ModFilesService` it resolved once, never through `V05Services`' synchronized getter per tick.
- Coordinator: RealController's carried-over count after Cancel them. The chosen fix is (a), a recorded exception to the
  frozen file: `public void stagedChanged()` (recount + rebuild, on the render thread), called after Cancel them.
- WS-E (4j.3 prep, agent `r-verify`, relayed by the coordinator): the notice keys and action ids are stable
  (`held-mod-changes` with `cancel`/`apply`; `launcher-repair:<hash>` with `copy`), so the E2E can act on a notice
  through `noticeAction(key, id)`. The appliesGroup stand-in jars for ws-l2's `expect.json` are WS-E's to answer.

## As landed

| task | commit | files | tests |
|---|---|---|---|
| L2-1 RW-1 | 0782fb05 | `core/apply/ApplyExecutor` (`doneBefore`, `installedElsewhere`, `doneByLastRun`) | `RealWorldFixTest` (5) |
| L2-2 crash replay | 2721ac63 | tests only | `ApplyExecutorCrashReplayTest` (11; 8 fail on 0.4.0's `ApplyExecutor`) |
| L2-9 AC2V.2 | eb41653a | `ApplyExecutor.rollback` | `ApplyExecutorTest.aRollbackWhoseFileVanishedSaysSo`, `…OntoATakenNameKeepsItsMessage` |
| L2-8 forcing writer | 13370ff6 | `UnfinishedGroups` (`DURABLE`, `writeDurably`), `ApplyExecutor` (the writer seam) | `UnfinishedGroupsDurableTest` (3), `ApplyExecutorTest.theRecordIsForcedToDiskBeforeTheFirstRename` |
| L2-4 hold | 636bf5bf | `ApplyExecutor` (`run(plan, pending, hold)`, `held`, `fileGroupOps`, `heldIndexes`), `ApplyHelper`, `HelperLauncher` (`HOLD_FILE_OPS_PROPERTY`, `holds`, `launch(…, policy)`) | `ApplyExecutorHoldTest` (8), `HelperLauncherTest` (+2, one a child-JVM helper) |
| L2-3 toasts | 7550472e | `ApplyResult.counts()`, new `client/HelperToasts`, `RigTuneClient.showNotices`, lang `rigtune.toast.held.*` | `HelperToastsTest` (10) |
| L2-7 repair core | 159d344c | new `core/history/LauncherRepair`, lang `rigtune.repair.*` | `LauncherRepairTest` (14), `LauncherFilesSourceTest` |
| L2-5, L2-6 client | 607d2fc4 | `client/launcher/LauncherRepairService`, `HeldModChangesNoticeSource`, `LauncherRepairNoticeSource`, `RigTuneClient` (`launchHelperIfPending`, `showNotices`), `RigTunePreLaunch.readState` | `LauncherRepairServiceTest` (11), `RigTunePreLaunchTest` (+2) |
| L2-10 game tests | 53bb8d47, b6bf6e6a | `LauncherManagedGameTest.heldAndRepair`, `A11yGameTest.walkLauncherNotices` | the game tests, 3 legs |
| L2-11 fixtures | 2cc800e6 | `src/test/resources/v050-written/ws-l2/` | `V050WrittenWsl2Test` |

| review fixes | abaefb61 | the files above, `client/RealController` (`stagedChanged`) | `LauncherRepairServiceTest` (15), `ApplyExecutorTest` (+2), `UnfinishedGroupsDurableTest` (+1), `LauncherRepairTest` (the key per launcher) |

## Frozen files touched

- `client/RealController.java`: `public void stagedChanged()` (recount the staged changes and rebuild, on the render
  thread), next to `discardPending`. The coordinator approved it as an exception on 2026-09-27: after Cancel them, the
  post-Apply "restart to apply N" count must follow pending.json. `LauncherManagedGameTest.heldAndRepair` checks the count
  with the seeded group (2) and after Cancel them (none).

## Deviations

- The forcing writer is `UnfinishedGroups.writeDurably` / `DURABLE`, not `AtomicFiles.writeStringDurably`. The PLAN
  names `UnfinishedGroups` for it, and `AtomicFiles` isn't WS-L2's file.
- Cancel them removes the held ops itself, under the apply lock, matched with `sameOp`, instead of calling
  `Staging.unstageLocked`, which matches op ids only. An op 0.1.0's first builds staged without an id would otherwise
  stay (review item 9). The effects are the same: downloads `.rigtune-superseded` unless a kept op still uses them,
  journal changes DISCARDED.
- The repair notice's dismiss key depends on the launcher: the pairs, or for ATLauncher its disabled copies. It follows
  exactly what `actionable()` counts (review item 4).
- "Mod changes" in the held notice and toast counts an update pair (a disable and an enable of one group) as one change,
  as History shows it (`LauncherRepair.modChanges`). The ops count stays in preLaunch's split and helper.log.
- `LauncherManagedGameTest` can't clear the in-memory dismissal (`AwarenessService`'s session set has no API). Its fixture
  names are new at every run instead, so that key can never match another notice. awareness.json is restored by bytes.
- The game test uses the forced policy (`LauncherRepairService.overridePolicy`) until WS-L1 merges; see "Still to do".

## Residuals

- **Crash replay: a 0.1.0-0.3.x helper killed after its renames** (no `unfinished-groups.json`, no `last-apply.json` of
  that run). The 0.5 helper drops the group as installed another way. The folder is already right (the new jar is
  enabled), but History says "Not applied" and Undo won't offer it. This is the documented residual of AC4f.2 and the
  safe side: RigTune never claims a jar it can't prove.
- **A group half done with no record** (a 0.1.0-0.3.x helper killed between the two renames of a group with no
  download left, or a failed write of a 0.4+ record) is held under LAUNCHER, with the mod missing until the player
  chooses. Cancel them then makes it permanent, which looks the same as the launcher disabling the mod. Discard pending
  does the same in 0.4. The common form, a failed rollback of 0.1-0.3 (`PartlyApplied`), and every recorded 0.4+ group
  are finished instead of held.
- **The leftover listener's footprint keys** (its idle tick: one volatile read, 0 bytes, unit-tested) come with the
  footprint checkpoint after Wave B (SPEC 1h). FootprintGameTest's run never registers the listener.
- **Stats on screen init/rebuild** (the coordinator's X8 ruling, 2026-09-27): under LAUNCHER or PENDING, the notices'
  `current()` stat pending.json, the mods folder and history.json (three stats), on screen init and rebuild only, never
  per frame. Under RIGTUNE they do no I/O.

## UNVERIFIED

- A physical power cut between a rename and its record (SPEC 3f): the forcing writer is tested; the event itself needs a
  VM with a lossy disk.
- AC4f.4 (the seeded E2E `v010-dh-app-reinstalled`) and AC4j.3 (the brand leg: held, then cancelled through the notice)
  are WS-E's release-tier runs.
- AC4d.3 (the released 0.4.0 helper applies ws-l2's held group) waits for WS-E's `expect.json` interpreter. compat030
  (the released 0.3.0 jar) passes with the set merged into v040-written's (PendingActions reads the held ops; nothing
  changed).
- The Modrinth App, Prism, GDLauncher, ATLauncher and CurseForge steps in the repair text come from lm §5.2, read from
  those launchers' source and locale files; CurseForge's are from secondary sources. None was checked in a running app
  (AC4j.5 is the user's step).

## Docs (for the docs workstream)

- DESIGN "Apply pipeline", the helper's list: "An enable already in place counts as RigTune's only when RigTune's own
  records prove the rename: a recorded rename still in effect in `unfinished-groups.json`, or the same op done by the
  run whose `last-apply.json` this run replaces. Otherwise the group is dropped: the jar was installed another way (by
  the launcher or the player), and nothing in `mods/` is touched (0.5, RW-1). A redo proven by `last-apply.json` records
  where the file went, so a second death in the same window still proves it."
- DESIGN "Apply pipeline": "`unfinished-groups.json` is forced to disk (the temp file's `FileChannel.force(true)` before
  the atomic move, then the folder where the OS allows it) before the first rename it names (0.5, 3f)."
- DESIGN "Apply pipeline", held groups (0.5, 4d): "In an instance whose launcher keeps its own list of mods (policy
  LAUNCHER, or PENDING while detection hasn't answered), the game starts the helper with `-Drigtune.helper.holdFileOps=true`.
  The helper then runs only the groups without a mod-file op. The others stay in `pending.json` with their attempts
  unchanged and never appear in `last-apply.json`; an exit that ran nothing writes none. A group RigTune's records show
  half done or done already is finished as in 0.4. Only the 0.5 helper reads the property; 0.4.0 and older apply held
  groups at their next exit."
- DESIGN "Next launch": "'RigTune: N of M changes failed' counts failed changes only; dropped ones (failed 3 times, or
  installed another way) appear only in the dropped toast (0.5). With leftover mod-file changes, preLaunch's WARN and
  the title screen's leftover toast wait until the launcher is known: 'N mod changes wait for your choice' where the
  launcher keeps its own list of mods, today's 'retried at the next exit' otherwise."
- DESIGN, a new "Launcher repair (0.5, 4g)" paragraph: "From RigTune's own records only (history.json's applied file
  changes and the mods folder; never a launcher's database or metadata), RigTune finds update pairs still in effect,
  jars it added, and copies it disabled. A disable without a `resultFile` was no rename of RigTune's. Under LAUNCHER, and
  only when the launcher needs a step, the LAUNCHER_REPAIR notice gives that launcher's steps. For the Modrinth App the
  first step is to delete the old disabled copy in the app, with the three-step order as the fallback. It offers Copy
  list (file names only) and Dismiss, which lasts until the set of findings changes. RigTune never renames, moves or
  deletes the old copies itself."
- DESIGN, HELD_MOD_CHANGES: "N mod changes from an earlier Apply are waiting: <launcher> manages this instance's mods",
  with Cancel them (drops them from pending.json; downloads `.rigtune-superseded`; History 'Cancelled') and Let RigTune
  apply them (turns the per-instance opt-in on; they apply at the next exit). Not dismissible.
- DESIGN, 2V: "A rollback whose moved file vanished says it was moved or deleted meanwhile; one whose file is already
  back under its own name says so."
- README "Known limits": "A RigTune 0.1-0.3 helper killed after renaming a mod (a power cut, Task Manager) leaves that
  change 'Not applied' in History although the mod is in place: 0.5 only counts a rename it can prove. A group such a
  helper left half done, in a launcher-managed instance, waits with the mod missing until you cancel it or let RigTune
  apply it."
- CHANGELOG [0.5.0]: "Downgrading to 0.4.0 or 0.3.0: their helper applies mod changes 0.5 was holding for your choice at
  its next exit."

## Footprint deltas (against ws-k.md's baseline, WS-K head run 36310249248)

From CI run 36327592019 (this branch merged with feat/v0.5.0 @ 3a67ef64), the game-test logs' FootprintGameTest JSON.
`v05RenderThreadResolve` is null on all three legs.

| leg | renderThreadInitCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference |
|---|---|---|---|---|
| 26.2 OpenGL | 64.8 (baseline 82.2: -17.4) | 31.0 (36.4: -5.4) | 124.5 (135.5: -11.0) | 1.441 (1.481) |
| 26.3 OpenGL | 108.2 (82.2: +26.0) | 12.9 (27.0: -14.1) | 179.7 (153.2: +26.5) | 1.610 (1.746) |
| 26.3 Vulkan | 97.0 (120.0: -23.0) | 48.4 (39.9: +8.5) | 170.2 (200.7: -30.5) | 1.472 (1.535) |

The earlier run 36325285181 (before the merge) gave 114.4/110.6/119.8, 38.9/29.2/43.8, 187.4/161.9/192.3,
1.572/1.596/1.548. Both runs move in both directions by more than any code in them could cause. ws-k.md already
measured 63.5-112.9 on near-identical code across runs. WS-L2 adds no work at init or CLIENT_STARTED: preLaunch's split
runs only when `pending.json` exists; the service is resolved on the first `notices()` ask or at the title screen; the
tick listener exists only after a leftover with mod-file ops. Every value stays inside its budget (150 / 141 / 300 /
2.05).

## CI runs and screenshots looked at

- 36334218896 (the review fixes and the stagedChanged hook, merged with feat/v0.5.0 @ 690b8f4c): green on every job and
  leg.
- 36319156664, 36323550509: green (the core and client tasks). 36325285181 (the game tests) and 36327592019 (merged
  with feat/v0.5.0): green on every job and all three legs, with 2207 unit tests per node, 3 skipped. One of the three is
  `LauncherRepairTest.theRealInstance`, which needs WS-L1's fixtures.
- `gametest-screenshots-26.2-OpenGL` of 36325285181 and `-26.3-Vulkan` of 36327592019: `launcher-held-notice-*` (the
  notice line at 1280×720, 640×480 (the "..." button) and 854×480, message cut with "..." and the detail as tooltip,
  inside the screen); `launcher-leftover-toast-launcher` / `-rigtune` (36325285181 caught the toast mid-slide-in; b6bf6e6a
  waits past it, and 36327592019 shows it whole: "RigTune: 1 mod change(s) wait for your choice" with its body, and
  today's "2 change(s) not applied"); `a11y-launcher-held-*` (the notice line focused) and `a11y-launcher-notices-*`
  (NoticeScreen with both notices, the focused row framed).

## Still to do before the coordinator merges this branch (after WS-H and WS-L1)

- Merge origin/feat/v0.5.0 once WS-H and WS-L1 are in. The `readState` split then goes over WS-H's runnable (non-stale)
  ops, and its 4-argument overload and mine are resolved into one.
- Switch `LauncherRepairTest.theRealInstance` and `RealWorldFixTest` to WS-L1's `RealWorldFixtures` (skipped in CI until
  then; green locally on the scratch copy), and `heldAndRepair` from the forced policy to the real one (the `theseus`
  brand, a rescan, `modFiles() != PENDING`).
- Re-run `LauncherFilesSourceTest` with WS-L1's `.index/` listing in (its allow-list names `InstanceEvidence` and
  `LauncherProbe`).

## AC table

| AC | status | evidence |
|---|---|---|
| AC4f.1 (the real case ABANDONED, History Not applied, nothing undoable, mods/ untouched; the last run's own redo stays done) | verified | `RealWorldFixTest` (5), CI 36327592019 java job, both nodes |
| AC4f.2 (crash replay: record, last-apply.json, 0.1-0.3 residual; ApplyExecutorTest/ApplyGroupsTest/HelperCompat030Test unchanged) | verified | `ApplyExecutorCrashReplayTest` (11; 8 red on 0.4.0's code), the three unchanged classes green |
| AC4f.3 (failed/dropped toasts over ABANDONED-only, FAILED-only, mixed) | verified | `HelperToastsTest` |
| AC4f.4 (E2E `v010-dh-app-reinstalled`) | UNVERIFIED here | WS-E's release tier |
| AC4d.1 (hold: file groups stay, attempts unchanged, patches apply; the property exactly under LAUNCHER/PENDING, classpath our jar + Gson) | verified | `ApplyExecutorHoldTest` (8), `HelperLauncherTest` (the property per policy; a child-JVM helper with our jar and Gson) |
| AC4d.2 (the notice; Cancel them: no file op, `.rigtune-superseded`, DISCARDED; Let RigTune apply them: opt-in) | verified with the forced policy; with the real policy closes here after WS-L1 merges (Cross-workstream ACs) | `LauncherManagedGameTest.heldAndRepair` (3 legs, 36325285181, 36327592019), `LauncherRepairServiceTest` |
| AC4d.3 (compat040: 0.4.0's helper applies a held group) | set committed (`v050-written/ws-l2` + `expect.json`); closes when WS-E's interpreter merges | compat030 PASS on the set |
| AC4d.4 (no "will be retried" WARN or toast for held groups, only the waiting wording; nothing in last-apply.json; today's toast under RIGTUNE) | verified | `RigTunePreLaunchTest` (+2), `HelperToastsTest`, `LauncherRepairServiceTest` (the listener), `ApplyExecutorHoldTest` (no result written), `heldAndRepair` (the toasts under LAUNCHER and RIGTUNE, screenshots) |
| AC4g.1 (findings per kind and repaired; the real instance: 1 pair, 7 added) | verified (the real-instance case locally until WS-L1's fixtures are in) | `LauncherRepairTest` (14) |
| AC4g.2 (only under LAUNCHER with a finding; text per launcher; Copy list names only, escaped; Dismiss until the set changes) | verified | `LauncherRepairTest`, `LauncherRepairServiceTest`, `heldAndRepair` (3 legs) |
| AC4g.3 (no launcher database or file read beyond InstanceFiles and `.index/`) | verified | `LauncherFilesSourceTest` |
| AC3f.6 (the record through the forcing writer before the first rename) | verified | `UnfinishedGroupsDurableTest` (4), `ApplyExecutorTest.theRecordIsForcedToDiskBeforeTheFirstRename` |
| AC2V.2 (the vanished-file rollback message; name-taken keeps its own) | verified | `ApplyExecutorTest` (4 rollback cases) |
| X6 for the two notices (Tab stops, narration, the three sizes) | verified | `A11yGameTest.walkLauncherNotices` (3 legs) |
