# Real-world sessions 2026-09-27: RigTune 0.4.0's first two launches after 0.1.0

The user's Modrinth App instance `Fabric 26.2`: Ryzen 7 7800X3D, RX 7800 XT, 32 GB, 2560x1440 at 180 Hz, 52 mod jars
(170 Fabric mods counting nested ones), including Sodium, Iris, Distant Horizons 3.3.2, C2ME, ModernFix and FastQuit.
Two sessions, local time (UTC+10); times in the JSON files are UTC:
- **Session 1 (10:43–11:08):** the first 0.4.0 launch after 0.1.0, with one benchmark.
- **Session 2 (11:30–12:24):** the second launch, steady state (§11).

**Inputs.**
- Read-only copies: session 1 in `<scratch>/realworld/instance-copy/`, session 2 in `instance-copy-2/`. Each has
  config/rigtune/*, logs/latest.log and mods-listing.txt.
- The live instance (%APPDATA%/ModrinthApp/profiles/Fabric 26.2), read without writing anything: rigtune.json, file
  timestamps, saves/rigtune-benchmark, the FastQuit and DH jars (inspected with javap), and every rotated log back to
  2026-07-09 (43 launches, for §12).
- This PC's performance-counter registry values and services, read-only, for §12.
- Expectations compared against: the seeded E2E (`docs/smoke/self-update/final-v010-seeded-to-040/`,
  `docs/v0.4/verification/p5c/README.md`), docs/DESIGN.md and docs/v0.4/SPEC.md.

**Code references.** Line numbers are for `feat/v0.5.0` (no src/ changes between e4832c60 and 9d6dd2c2). Short paths:
- `core/` = `src/main/java/io/github/chaotix345/rigtune/core/`
- `client/` = `src/client/java/io/github/chaotix345/rigtune/client/`

**Proof.** Throwaway worktree `C:/Dev/Worktrees/rigtune-realworld`, branch `research/v05-realworld`, not committed and not
pushed.
- `RealWorld20260927Test` has 6 tests, all green. They reproduce the helper run, the History view and Undo last / Undo
  all / Undo this on the copied `history.json` and mods folder, plus two variants and two controls.
- A prototype of the fix for RW-1, with `RealWorldFixTest`: in the full unit suite (1850 tests), the only failures are
  the 2 reproduction tests that assert today's behaviour.
- The test dump and the prototype diff are in `<scratch>/realworld/test-output.txt` and `fix-prototype.diff`.

**Result.**
- **2 high:** RW-1 (the helper records the Modrinth App's DH install as RigTune's own change) and RW-2 (Undo on the
  imported 0.1.0 entry would rename files the app manages).
- **4 medium:** RW-3, RW-5, RW-6, RW-11.
- **7 low:** RW-4, RW-7, RW-8, RW-9, RW-10, RW-14, RW-15.
- **1 info:** RW-12 (FastQuit: not a RigTune bug).
- **1 lead:** RW-16 (launch time). Windows performance counters are switched off on this PC, and vanilla's startup system
  report waits 5–7 s for them at most launches. That was 7 s, or 25%, of session 2's 27.9 s to the title screen. RigTune could detect this
  read-only and skip the wait using OSHI's own documented switches. Measured with a replica of vanilla's calls: 6.3 s
  down to 0.8 s. See §12.
- RW-13 is a knowledge note, not counted.
- No RigTune exception, crash or ERROR line in either session's log.
- Session 2 had nothing new except the false "RigTune applied 2 change(s)" toast from RW-1. The journal didn't change
  at its exit, and no helper ran.

**Advice for the user now:** don't use Undo last, Undo all or Undo this on the "Imported from 0.1" entry in History
until P0.4 (launcher-managed mods) lands. See RW-2.

## Findings

| id | sev | what the player saw or could hit | root cause | proposed fix | test |
|---|---|---|---|---|---|
| RW-1 | HIGH | At exit, the helper reported 0.1.0's stale DH group as done: `SKIPPED_ALREADY_DONE` twice. History now shows "Disabled fabric-26.2.jar / Added DistantHorizons-3.3.2-…jar: Applied". The next launch (11:30) showed the toast "RigTune applied 2 change(s)", but RigTune renamed nothing: the Modrinth App put that file there. Latent: Undo then treats the app's jars as RigTune's. Two variants proven: had the player *disabled* DH 3.3.0 in the app instead of removing it, Undo swaps DH 3.3.2 back to 3.3.0; an addition installed through the app under the staged name gets disabled by Undo last. | DESIGN.md:114 says a group whose mod is already installed another way is abandoned. The code only checks that when the download still exists: `core/apply/ApplyExecutor.java:396` reads the mod id only if `op.from()` exists, and `:342-343` skips enables without one. The group then falls to `enable()` `:677-679` ("already enabled": download gone + target present counts as done) and `disable()` `:692-693` ("already gone"), neither of which asks for proof. `core/history/HistoryUpdates.java:38-51` maps SKIPPED to APPLIED. | An enable whose download is gone while its target exists counts as done only when RigTune's own records prove the rename: a recorded rename still in effect (`unfinished-groups.json`, the existing `earlier` path), or the same op id OK in the previous `last-apply.json`. Otherwise the group is ABANDONED with "…is already in the mods folder and RigTune has no record of putting it there, so it was installed another way", like `duplicateProblem`. Prototype: 46 lines in ApplyExecutor. | `RealWorld20260927Test` (repro and variants); `RealWorldFixTest.theRealCaseIsDroppedAsInstalledAnotherWay` / `aRedoOfTheLastRunsOwnRenamesStaysDone`; the existing `ApplyExecutorTest`/`ApplyGroupsTest`/`HelperCompat030Test` stay green |
| RW-2 | HIGH (P0.4) | History offers Undo on the "Imported from 0.1" entry. Undo last / Undo all / Undo this would disable BBE, More Culling, Async Logger, FastQuit, Ixeris, Structure Layout Optimizer and ResourcefulConfig, and swap Entity Culling 1.11.2 (reinstalled through the app) back to 1.11.1. Those renames recreate the "belongs to another content item" desync. The DH rows are skipped only by luck ("fabric-26.2.jar.disabled is no longer in the mods folder"). | The legacy import makes 0.1.0's file changes undoable (`core/history/LegacyImport.java:105-139`). `core/history/UndoPlanner.java:645-733` has no notion of a launcher-managed jar. | Part of P0.4 (`docs/research/v0.5/launcher-managed-mods.md`): in a launcher-managed instance, file reversals become launcher advice, not renames. At minimum, never undo a jar the launcher's DB links. | `RealWorld20260927Test.undoOnTheCopiedInstance` (the plan as it is today); a P0.4 test that stages no rename of an app-linked jar |
| RW-3 | MEDIUM | At launch: WARN "2 staged RigTune change(s) were not applied; they will be retried at the next exit", two per-op WARNs replaying 0.1.0's three-day-old lock failure, the toast "RigTune: 2 change(s) not applied / They'll be retried when you exit…", and History "Waiting for restart" with that stale reason. None of it could ever be retried: the download was deleted and DH 3.3.2 was already loaded from the app's jar. The seeded E2E expected an early drop notice ("Cancelled RigTune's pending change to Distant Horizons…"). | The only early drop is `Staging.dropQueuedUpdates` (`client/undo/Staging.java:217-245`, called from `client/RealController.java:334`), which needs a build in `mods/update/`, and the user had deleted that folder. `client/RigTunePreLaunch.java:119-124` counts pending ops without checking them. | At rebuild (next to dropQueuedUpdates), unstage a group whose enable's download is missing, or whose mod id is loaded from a jar other than the op's own. Keep the group-safety rule (never a half-done group). Journal it DISCARDED or ABANDONED and show a status notice ("…is already installed (<file>)"). preLaunch leaves such ops out of its count. | Staging unit test; a seeded E2E variant `v010-dh-app-reinstalled` (no `mods/update`, no download, DH at the staged name) |
| RW-4 | LOW | History shows the 0.1.0 updates of DH, Mod Menu, YACL and Zoomify as two unrelated rows each ("Disabled modmenu-20.0.2.jar" plus "Added Mod Menu"; for DH, a bare file name) instead of "Updated <mod>: old → new". | Import reads the disable's mod id from the jar, which the app had removed (`core/history/LegacyImport.java:127-133`, and the staged path through `StagedChanges`). Pairing needs a mod id on both halves (`core/history/HistoryModel.java:191`). | Pair a disable without a mod id with the single enable of its group, or give it that enable's mod id at import. | HistoryModelTest / LegacyImportTest with the old jar missing |
| RW-5 | MEDIUM | The benchmark recommended "Use render distance 31" over the player's 32 ("Converged: 31 meets the target, 32 does not"), although 31 ran at 761 FPS avg / 326 FPS 1% low against a 170 target. The 32 step was measured while the fresh world was still generating (settle timed out, 1116 of 3001 chunks missing, 1% low 36). The player kept 32. | An incomplete step counts as a fail: `core/benchmark/RenderDistancePlanner.java:63` (`passed = complete && …`), `:122-130` (`lowestFail`), then a bisection below it (`:53`). `client/benchmark/BenchmarkController.java:533` passes `lastSettle.complete()`, and `core/benchmark/SettleCheck.java:24-25` allows 2% missing. This is the documented E-M1 behaviour, and `RenderDistancePlannerTest.java:173-192` asserts it; it caps any first run whose start RD can't generate in 20 s at start−1. | Treat an incomplete step as not measured. Re-measure it once after the lower steps have loaded the terrain, outside `maxRdSteps` if the deadline allows (the run used 186 of 300 s). Alternatively give the first step in a just-created world a longer settle, shown as "preparing the world". | RenderDistancePlannerTest: record(32, incomplete), 17…31 pass ⇒ next()==32, then a complete pass ⇒ "Target met at the maximum (32)" |
| RW-6 | MEDIUM | DH's distant world generation ran through the whole benchmark: 8 `DH-World Gen Thread`s, and the benchmark save's DistantHorizons.sqlite reached 68 MB in 3 minutes. 40 of 58 benchmark spikes are tagged `dh`. At world exit the render thread spent 6 s in the teardown (10:48:02→10:48:08), which ended with DH closing its databases (sqlite mtime 10:48:08.46). The context records `dhRendering=false` (DH *rendering* was off), so nothing shows that DH was generating. | `client/compat/OptionalMods.java:80-83` / `DhCompat.java:25` check rendering only. The benchmark world doesn't touch DH's world-generator setting. The `dh` tag needs DH threads ≥ 1 core with the process ≥ 85% of cores (`core/stutter/Attributor.java:223-227`), so the CPU was saturated. | In the benchmark world, override DH's `enableDistantWorldGeneration` to false and restore it like `renderingEnabled` (DhCompat plus the RestoreMarker target). Record `dhGenerating` in the context. Don't evaluate stutter advice on benchmark-world captures, which would blame the player's DH for RigTune's own scene. | ModToggles/RestoreMarker unit tests for the new target; the DH verification leg |
| RW-7 | LOW | "Results were noisy (7% spread): close background apps and retry." The noise came from RigTune's own scene (DH generation plus first-time chunk generation), not from background apps. | `core/benchmark/BenchmarkMath.java:11,66` (cv 0.0707 > 0.05); `client/ui/BenchmarkResultScreen.java:243-246`. | Name the cause when `dh`/`chunksLoading`-tagged spikes dominate the capture ("Distant Horizons was building terrain"), else keep the generic line. | BenchmarkResultScreen / notice text test |
| RW-8 | LOW | This first run was in a benchmark world created at 10:44:48; later runs reuse that save (`client/benchmark/BenchmarkWorld.java:108-117`). Run 1 (world plus LOD generation) and later runs aren't comparable in the trend. | No `worldFresh` context field. | Record `worldFresh`, and leave such a run out of the trend median. | TrendService unit test |
| RW-9 | LOW | The result screen doesn't say it was measured with DH rendering off, but the player plays with DH on: DH's renderer came up at 10:48:54 in THE ONE, and the r16 rules cap vanilla RD at 12 when DH renders. | Screen text. | "Measured with Distant Horizons rendering off" when `dhRendering=false` and DH is installed. | Screen text test |
| RW-10 | LOW | The Stutter Doctor screen would show a "Chunk loading 0%" bar for the monitor session (`"chunkLoad": 0.0`), and the shown shares add up to 101%. | Shares are rounded to 2 decimals (`core/stutter/StutterAnalyzer.java:94`); StutterScreen draws every non-null share (`client/ui/StutterScreen.java:262-267`); Copy summary skips ≤ 0 (`StutterSummary.java:57`). | Skip shares that round to 0 on the screen too. | StutterAnalyzerTest / StutterSummaryTest |
| RW-11 | MEDIUM | One monitor capture (831 s of gameplay) spans two setting changes: the last Complementary Reimagined toggles (Iris compile error "dhProjection undeclared", shaders disabled; the last pipeline rebuild at 10:51:02) and RD 32→12 at 10:53:57. The worst spikes (236.6 / 108.5 / 108.1 / 79.4 ms, t=70.6–74.7 s ≈ 10:51:01.6–10:51:05.7) sit within 4 s of the Iris pipeline rebuild at 10:51:02, yet they are labelled `tick:medium` + moving fast. | stutter.json stores no settings context, and advice uses the end-of-session settings (`client/stutter/StutterService.java:363-373`). | Tag a "settings changed / resource reload" event like `afterTeleport`, and store RD, shaders and DH at the start and the end. | StutterAnalyzer test with a reload event |
| RW-12 | INFO | FastQuit's WARN `"RigTune Benchmark" was not registered in currently saving worlds!`, then "Waiting for … to finish saving" and a false "allowing a world to load while another is currently being saved". | FastQuit 3.1.5's own ordering race (javap): the server thread's remove runs before the render thread's add, whenever the client teardown (here DH closing its databases) outlasts the server stop. It happened for THE ONE too, after the player's own Save and Quit. RigTune's exit is the vanilla one (`client/benchmark/BenchmarkWorld.java:281` `disconnectFromWorld`). No server kept running. | None needed. Optional: pass `ClientLevel.DEFAULT_QUIT_MESSAGE` at `BenchmarkWorld.java:281` so it matches PauseScreen exactly, and note the WARN in DESIGN's Benchmark section. | none |
| RW-13 | note | The session shows several knowledge gaps. See §9. | n/a | Knowledge research | n/a |
| RW-14 | LOW | Undo would guess `<file>.disabled` for a disable that RigTune found "already gone" (no `resultFile`). In variant D that file is the app's own disable, so Undo re-enables a jar RigTune never disabled. | `core/history/UndoPlanner.java:817` falls back to `file + ".disabled"`. As far as the code shows, since v0.2.0 an APPLIED disable without `resultFile` means RigTune did no rename: 0.2+ helpers record `resultPath` on every OK (`HistoryUpdates.java:46-49`), and the legacy import computes one for 0.1.0 OKs (`LegacyImport.java:143-152`). | Skip it: "RigTune didn't disable %s (it was already gone)". Update the UndoPlannerTest fixtures that rely on the fallback. | UndoPlannerTest |
| RW-15 | LOW | All 10 worst benchmark spikes (t = 1.3–15.9 s, 37–59 ms) fall inside the RD-32 sweep measured on unsettled terrain (10:45:17–10:45:33). They make up the benchmark's Stutter Doctor line ("58 spikes… GC 35%"). | Every sweep goes into the stutter capture, including a step whose settle was incomplete (`client/benchmark/BenchmarkController.java:502,520`). | Don't record the sweeps of an incomplete step, or tag them `unsettled` and leave them out of shares and advice. | BenchmarkController / StutterService test |
| RW-16 | LEAD (launch time) | Every launch since the instance's first (2026-07-09, 43 launches): vanilla's `CrashReport.preload` → `SystemReport` on the main thread spends 5–7 s (median 6 s; 0–2 s when warm) on OSHI queries that fail: PDH `0xC0000BB8`, then WMI "Invalid Query", then NPEs on `OSProcess`. Here it was 7 s of the 27.9 s to the title screen. | Not RigTune: `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Perflib` "Disable Performance Counters" = 1 (REG_DWORD, read-only check). Microsoft documents this entry as switching off all registry-based performance counters on the system. OSHI only checks the per-service keys (here PerfOS has a REG_SZ "0", hence its "Invalid registry value type" WARN), so it tries PDH and then a WMI table that doesn't exist. RigTune's own OSHI calls don't pay this (§12.3). | (a) Detect it read-only: the Perflib value, plus a timing of `CrashReport.preload`. (b) Only then, in preLaunch, set OSHI's documented `oshi.os.windows.perfos.disabled` / `perfproc.disabled` = true, so vanilla skips the dead queries with the same results. (c) Advice text citing Microsoft; the Windows-side fix is UNVERIFIED on Windows 11. Not `lodctr /R` (§12.4). | Standalone replica of vanilla's calls on this PC (`<scratch>/realworld/oshi/probe`); a unit test of the detector with a fake registry; one real launch to confirm the in-game saving |

## 1. Timeline

| when (local) | what | evidence |
|---|---|---|
| 09-25 09:08:50–09:09:01 | 0.1.0 Apply: 17 ops, 15 OK. The DH group (disable `fabric-26.2.jar` = DH 3.3.0, enable the 3.3.2 download) FAILED: DH's own updater held the jar. | 0.1.0 last-apply (imported, `history.json` entry at 2026-09-24T23:09:01Z); `rigtune.json` lastShownApply |
| 09-27 before 10:15 | The app's Update failed ("…belongs to another content item"). The user fixed the instance in the app: removed the colliding copies and `fabric-26.2.jar`, deleted RigTune's `.rigtune-pending` and `mods/update/`, reinstalled DH 3.3.2 (exact staged name) and Entity Culling. | coordinator notes (PROGRESS.md:11); mods-listing.txt |
| 10:15 | 0.4.0 replaced 0.1.0 (through the app, not RigTune's self-update). | coordinator |
| 10:43:33 | Launch: Fabric Loader 0.19.5, 170 mods. | log:1-2 |
| 10:43:39 | preLaunch: 3 WARNs (RW-3). History created with the legacy import: 15 APPLIED and 2 STAGED. | log:274-276; history.json |
| 10:43:41–48 | Vanilla `SystemReport` OSHI stall, 7 s on the main thread (Windows performance counters are switched off; RW-16, §12). Not RigTune; it's in every log back to 2026-07-09. | log:282-310 |
| 10:43:59 | RigTune worker: Java 25.0.3 Azul, G1 (Java's choice), 0 argument notes; launcher Modrinth App; hardware line. | log:601-604 |
| 10:44:03 | Title screen: "Launch to title screen: 32284 ms (52 mods)". | log:645 |
| 10:44:04 | "RigTune startup footprint: preLaunch + init 85.1 ms on the render thread (CPU 46.9 ms), client start 19.7 ms; RigTune threads used 234.4 ms of CPU in the first 5 s". | log:646 |
| 10:44:33 | awareness.json written (what's-new baseline seeded silently). | live file mtime |
| 10:44:48 | Benchmark world created (new DH sqlite, "Found new data pack"). | log:657-712 |
| 10:44:56 | "Benchmark started: TUNE in BENCHMARK_WORLD, target 170.0 FPS (uncapped), start Knobs[renderDistance=32, simulationDistance=12, dhRendering=false, shaders=false]". | log:~826 |
| 10:45:16 | RD 32 settle timed out (1116 of 3001 chunks missing). 10:45:17: Stutter Doctor capture on (benchmark). | log:833-834 |
| 10:45:33–10:48:02 | RD 32: 370/36 FPS (incomplete). 17: 1249/339. 24: 947/223. 28: 851/339. 30: 782/286. 31: 761/326. Two repeats at 31: 760/334 and 755/303. "Benchmark finished: chosen … 31 … target … met true". | log:837-877 |
| 10:48:02 | Stutter Doctor (benchmark): 58 spikes, gc 35% / unknown 65%. `disconnectFromWorld`. Server stopped, then the FastQuit WARN. | log:874-937 |
| 10:48:08 | Client teardown done (DH closed its 3 databases); FastQuit adds the world. The result screen shows "Use render distance 31" / "Keep current (32)". | log:938-943 |
| 10:48:40–41 | The player opens THE ONE. FastQuit waits for nothing (no-op) and logs a false WARN. Joined at RD 32, so they kept 32 (no benchmark entry in history.json). | log:945-1000 |
| 10:48:48–10:51:06 | Pause menus: DH renderer set up (10:48:54). Complementary Reimagined toggled; 3 × Iris compile errors (dhProjection), shaders disabled. | log:1150-1330 |
| 10:49:51 | The player turned the Stutter Doctor monitor on (settings.json mtime 10:49:51.357). Capture on (monitor). | log:1209; live mtime |
| 10:53:57 | The player set RD 12 ("Flushed changes to Minecraft configuration"). | log |
| 11:08:41–43 | Save and Quit. FastQuit WARN for THE ONE. "Stutter Doctor: session saved (OK): 132 spikes in 831 s of gameplay…". | log:1529-1572 |
| 11:08:44 | Quit Game: "Started the RigTune apply helper for …pending.json". | log:1577 |
| 11:08:48 | Helper: SKIPPED_ALREADY_DONE × 2; pending.json deleted; history.json: STAGED → APPLIED (RW-1). | helper.log; last-apply.json; history.json |
| 11:30:33 (next session) | Toast "RigTune applied 2 change(s) / RigTune's staged changes were applied." (live rigtune.json: lastShownApply = 01:08:48Z). No RigTune WARN lines. | live rigtune.json and latest.log |

## 2. Apply helper, History and Undo (RW-1, RW-2, RW-4, RW-14)

### 2.1 Why "already done" won over "abandoned"

`ApplyExecutor.runGroup` (`core/apply/ApplyExecutor.java:380-498`) runs in this order:
1. Earlier renames recorded in `unfinished-groups.json` (`:386`).
2. Refusals (`:391-415`).
3. The duplicate / "installed another way" check (`:416-425`).
4. The ops themselves (`:429-497`).

The duplicate check only looks at enables whose mod id it has read, and it reads that id from the download:

```java
// core/apply/ApplyExecutor.java:396-397
if (problems[i] == null && op.type() == PendingActions.Type.ENABLE_FILE && Files.exists(Path.of(op.from()))) {
    modIds[i] = jarModId(Path.of(op.from()));
// :342-343 (duplicateProblem)
if (modIds[i] == null) {
    continue;
```

The user had deleted `DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending`, so `modIds` stayed null. The staged
`modId` "distanthorizons" is there, but it isn't used here. The group then reached the op code, where both "already done"
branches accept any state that looks done:

```java
// :677-680 enable(): download gone + target present = done
return new Applied(Files.exists(to) ? new OpResult(op, Status.SKIPPED_ALREADY_DONE, to.getFileName() + " is already enabled") …
// :692-693 disable(): jar gone = done
return new Applied(new OpResult(op, Status.SKIPPED_ALREADY_DONE, path.getFileName() + " is already gone"), null);
```

`HistoryUpdates.applyResults` (`core/history/HistoryUpdates.java:38-51`) maps OK and SKIPPED_ALREADY_DONE alike to
APPLIED. A SKIPPED disable has no `resultPath`, so the change keeps no `resultFile`.

The helper's own classpath (`config/rigtune/helper/0-rigtune-0.4.0+mc26.2.jar`) is the 0.4.0 release, so this is the
released behaviour.

**Proof** (`RealWorld20260927Test.theRealHelperRunIsReproduced`). The instance is rebuilt as it was: the app's DH jar at
the staged name, no `fabric-26.2.jar`, no download, and 0.1.0's `pending.json` (the op ids of
`seeded-pending.json`). The legacy import is made by `LegacyImport.entry`, and then `ApplyExecutor.run` runs. The result
matches helper.log word for word:

```
history before exit: [disable fabric-26.2.jar STAGED (no modId), enable DistantHorizons-3.3.2-26.2-fabric-neoforge.jar STAGED]
  SKIPPED_ALREADY_DONE DISABLE_FILE: fabric-26.2.jar is already gone
  SKIPPED_ALREADY_DONE ENABLE_FILE: DistantHorizons-3.3.2-26.2-fabric-neoforge.jar is already enabled
history after exit:  [disable fabric-26.2.jar APPLIED (no modId), enable DistantHorizons-3.3.2-26.2-fabric-neoforge.jar APPLIED]
```

**Control** (`withTheDownloadStillThereTheGroupIsAbandoned`). With the download still there, the same instance gives
ABANDONED twice ("Dropped: mod distanthorizons is already installed as DistantHorizons-3.3.2-…jar…"). The download
becomes `.rigtune-superseded` and History says "Not applied". This is DESIGN.md:114's behaviour, and it depends only on
whether the download still exists.

### 2.2 What history.json and History say now

The copied history.json has one `legacy-import` entry, dated 2026-09-24T23:09:01Z (0.1.0's run), with 17 changes, all
APPLIED. `HistoryModel.build` on the copy (test C) gives:

```
entry legacy-import 2026-09-24T23:09:01.530708800Z undoable=true settings=0 mods=16
  ADDED APPLIED BBE / More Culling / Async Logger / FastQuit / Ixeris / Structure Layout Optimizer / Resourcefulconfig
  UPDATED APPLIED EntityCulling -> entityculling-fabric-1.11.2-mc26.2.jar
  DISABLED APPLIED modmenu-20.0.2.jar            ADDED APPLIED Mod Menu
  DISABLED APPLIED yet_another_config_lib_v3-3.9.6+26.2-fabric.jar   ADDED APPLIED YetAnotherConfigLib
  DISABLED APPLIED zoomify-2.16.1+26.2.jar       ADDED APPLIED Zoomify
  DISABLED APPLIED fabric-26.2.jar               ADDED APPLIED DistantHorizons-3.3.2-26.2-fabric-neoforge.jar
```

- **The DH rows are false.** Under "Imported from 0.1" they claim RigTune 0.1.0 updated DH on 09-24/25. The app did it
  on 09-27. Undo last / Undo all are active (`HistoryModel.anyUndoable` is true).
- **RW-4, four updates show as unpaired rows.** DH, Mod Menu, YACL and Zoomify each show as two rows instead of
  "Updated …". Their disable halves have no mod id: the old jars were gone when 0.4.0 imported them
  (`LegacyImport.java:127-133` reads the id from the file). `HistoryModel.partner` returns null without a mod id
  (`HistoryModel.java:191`). The DH enable also has no display name, because the import reads it from the missing
  download.
- **The E2E never saw this.** Its seed kept `fabric-26.2.jar` and a DH build in `mods/update/`, so both DH changes had
  `modName` "Distant Horizons" and ended DISCARDED.

### 2.3 What Undo would do now (RW-2)

`UndoPlanner.plan(…, all=false)`, `plan(…, all=true)` and `planEntry(…, legacy)` were run on the copied history.json, with
a folder built from mods-listing.txt (test C). All three give the same plan:
- **Revert:**
  - disable entityculling 1.11.2 and re-enable 1.11.1;
  - disable ResourcefulConfig, Structure Layout Optimizer, Ixeris, FastQuit, Async Logger, More Culling and BBE.
- **Skip:**
  - the DH group: "fabric-26.2.jar.disabled is no longer in the mods folder";
  - the Mod Menu, YACL and Zoomify groups: "<old>.disabled is no longer in the mods folder".
- **Staged file ops:** 8 disables and 1 enable. None of them touches `DistantHorizons-3.3.2-26.2-fabric-neoforge.jar`.

**Would an Undo disable the app's DH jar?** Not today, but only by luck. The update group is undone all-or-nothing
(`UndoPlanner.java:670-713`), and its disable half can't find `fabric-26.2.jar.disabled`, because the player *removed* the
old jar. Two variants the tests prove:
- **D. The player disabled DH 3.3.0 in the app instead** (the app renames it to `fabric-26.2.jar.disabled`, the same
  convention). The history is identical. Undo this / Undo last stage
  `DISABLE DistantHorizons-3.3.2-…jar` + `ENABLE fabric-26.2.jar.disabled → fabric-26.2.jar`: they swap the app's DH 3.3.2
  back to 3.3.0. This comes from RW-1 plus the `.disabled` fallback (`UndoPlanner.java:817`, RW-14).
- **E. An addition 0.1.0 staged (Ixeris)** whose download the player deleted before installing the mod through the app.
  RigTune's download and the app use the same Modrinth file name. Helper: "…is already enabled" → APPLIED → Undo last
  stages `DISABLE Ixeris-4.6.8+26.2-fabric.jar`.

**What RW-2 would break.** Entity Culling 1.11.2 was reinstalled through the app (PROGRESS.md:11), and the added jars are
unlinked rows or re-added entries in the app's DB. Every rename that Undo stages here is a rename behind the Modrinth
App's back, which is exactly the P0.4 failure. This is P0.4's to design (`docs/research/v0.5/launcher-managed-mods.md`).
It is listed here because this instance has it today and the user was already told not to press Undo.

### 2.4 The fix for RW-1 (prototype in the worktree)

**Rule.** An op that only *looks* done is RigTune's only when RigTune's own records prove the rename:
- a recorded rename still in effect in `unfinished-groups.json`: the existing `earlier` / `doneEarlier` path,
  `ApplyExecutor.java:386, 443-444, 562-563`; or
- the same op id with status OK (or SKIPPED with a `resultPath`, i.e. "Already done earlier") in the `last-apply.json`
  that this run is about to replace. That covers a helper that died after pruning the record (`:169`) and before
  rewriting `pending.json` (`:170`).

**Otherwise** an enable whose download is gone while its target exists means the jar was installed another way. The
group goes the `duplicateProblem` way: ABANDONED, except renames an earlier run did. The core of the prototype (46 lines,
`fix-prototype.diff`):

```java
// runGroup, before duplicateProblem:
String elsewhere = installedElsewhere(ops, order, earlier, doneBefore);
String duplicate = elsewhere != null ? elsewhere : duplicateProblem(ops, order, modIds, installed);

private static String installedElsewhere(List<Op> ops, List<Integer> order, Map<Integer, Undo> earlier, Set<String> doneBefore) {
    for (int i : order) {
        Op op = ops.get(i);
        if (op.type() != PendingActions.Type.ENABLE_FILE || earlier.containsKey(i) || op.id() != null && doneBefore.contains(op.id())) {
            continue;
        }
        if (!Files.exists(Path.of(op.from())) && Files.exists(Path.of(op.to()))) {
            return fileName(op.to()) + " is already in the mods folder and RigTune has no record of putting it there, so it was installed another way";
        }
    }
    return null;
}
```

**Result with the fix.**
- In the real case: ABANDONED twice, History "Not applied: …installed another way", and `UndoPlanner.undoable` is empty
  for the entry. Nothing in `mods/` is touched.
- The next launch would show two toasts:
  - "RigTune dropped 2 change(s) / They failed 3 times or the mod was installed another way", which is true.
  - "RigTune: 2 of 2 changes failed / See config/rigtune/helper.log", because `ApplyResult.failedOps()` counts ABANDONED
    ops (`core/apply/ApplyResult.java:33-35`; `client/RigTuneClient.java:215-230`). This one is misleading today for any
    abandoned group. Follow-up (LOW): count only FAILED in the "failed" toast.
- Test runs:
  - `RealWorldFixTest`: 2/2 green.
  - `core.apply.*` and `core.history.*`: 423 tests; the only failures are the 2 reproduction tests that assert today's
    behaviour.
  - Full unit suite: 1850 tests, the same 2 failures, 1 skipped.
  - Every existing idempotence and redo test stays green: `ApplyExecutorTest:83` (a second run of a finished plan: its
    OKs are in `last-apply.json`), `:348` (the L2 death: the record is kept), `ApplyGroupsTest:157-168` (a disable done
    by someone else still lets RigTune's enable run), and `HelperCompat030Test`.

**Compatibility with 0.1.0-0.3.0 `pending.json`.** They never have `unfinished-groups.json`.
- **Groups whose download is still there** go through the unchanged path (OK, or the duplicate check).
- **Groups a 0.1.0-0.3.0 helper renamed and reported OK in `last-apply.json` but left in `pending.json`** (a death
  between its two writes) are covered by `doneBefore`, matched by op id. 0.1.0's `last-apply.json` ops carry ids too:
  this instance's legacy import used them (`LegacyImport.java:114-126`, and every imported change has an `opId`).
- **The residual:** a 0.1.0-0.3.0 helper killed between its rename and its `pending.json` rewrite, with no
  `last-apply.json` from that run. The 0.4+ helper then reports ABANDONED ("installed another way") for a jar RigTune did
  put there. The folder is already right (the mod is enabled). Only History says "Not applied" and Undo won't offer it,
  which is the safe side.
- **Ops without ids** (pre-id 0.1.0 dev builds) can't be matched and are untracked in History anyway.
- **The disable half of a mixed group** (old jar already gone, new download present) keeps today's behaviour: the group
  applies.

**Follow-ups:**
- RW-14: Undo's `.disabled` guess.
- RW-3: drop such groups at launch, so the player never sees "will be retried" for them.
- DESIGN.md:114 should say "an enable already in place without RigTune's record counts as installed another way".
- Add a seeded E2E `v010-dh-app-reinstalled`, built from this exact instance: DH at the staged name, no download, no
  `mods/update`, `fabric-26.2.jar` removed; a second leg with it disabled instead. Expected: the drop notice at launch,
  DISCARDED/ABANDONED in History, and no helper rename at exit.

## 3. Legacy import and preLaunch vs the seeded E2E

**The seeded E2E** (`final-v010-seeded-to-040`, PASS):
- The 0.1.0 helper ran once more during the update phase (the self-update), so `last-apply.json` held only the DH group
  (FAILED) and RigTune's own update.
- The legacy import had 2 changes. `dropQueuedUpdates` unstaged the group at launch, because DH's own 3.3.2 build was in
  `mods/update/`, and showed the notice "Cancelled RigTune's pending change to Distant Horizons: it has an update of its
  own waiting in mods/update".
- History: DISCARDED ×2. Nothing was renamed at exit.

**The real session** differs in four ways:

| | seeded E2E | real 2026-09-27 | verdict |
|---|---|---|---|
| how 0.4.0 arrived | RigTune's own self-update (0.1.0's helper) | the Modrinth App | expected |
| last-apply.json at the first 0.4.0 start | the self-update run (DH FAILED, RigTune OK) | 0.1.0's 09-24 run (15 OK, 2 FAILED) | expected; the import brought **15 APPLIED changes** the E2E never exercised, on files the app has since changed (RW-2, RW-4) |
| preLaunch WARNs | "2 staged … retried at the next exit" + 2 per-op lines, "restart attempt **2** of 3" | the same 3 lines with "attempt **1** of 3" (no extra 0.1.0 helper run) | the numbering is right. The text is stale: it replays a 3-day-old lock failure for files that no longer exist (RW-3) |
| the DH group at launch | dropped (queued update in `mods/update/`), notice shown, DISCARDED | stayed staged (the user had deleted `mods/update/` and the download); leftover toast; History "Waiting for restart" with the stale reason | divergence (RW-3) |
| the DH group at exit | nothing to run | SKIPPED ×2 → APPLIED | divergence (RW-1) |

**Other E2E checks that held in reality:**
- The goal was kept (BALANCED).
- `lastShownApply` was already 0.1.0's run, so no stale applied/failed toast appeared at 10:43.
- `lastWarnedApply` was set once, so the WARNs don't repeat.
- No crash report.
- The next start logged no WARN.

## 4. Notices and toasts the player saw or could see

- **10:43–10:44, first 0.4.0 start:**
  - The leftover toast "RigTune: 2 change(s) not applied / They'll be retried when you exit, or use Discard pending in
    RigTune." (`client/RigTuneClient.java:242-246`, count from `RigTunePreLaunch.java:122`). Misleading (RW-3).
  - No applied/failed toast (lastShownApply already equal) and no busy toast.
  - No what's-new notice: an upgrade from 0.1.0 has no baseline, so it is seeded silently at r16
    (`client/awareness/WhatsNew.java:20-26,77-120`). By design.
  - The privacy notice had already been shown (settings.json `privacyNoticeShown: true`).
  - The suggestions toast ("rigtune.toast.title", with `startupToast: true`) depends on the report's important count; the
    log doesn't show it.
- **While staged:** the RigTune screen had "Discard pending" (2 carried-over ops). History showed the DH group "Waiting
  for restart" with "Last attempt failed: Gave up after 10 attempt(s): … being used by another process (try 1 of 3 at
  restart)" (`client/ui/HistoryScreen.java:373`). That is stale (RW-3).
- **10:48:08, benchmark result screen**
  (`client/ui/BenchmarkResultScreen.java:135-151,228-231,243-246,297-299,338-373`):
  - "Render distance 31 keeps 1% lows at or above the target", "Target: 170 FPS", and the 32 row marked ✘* with "Some
    of the terrain hadn't loaded in time" (RW-5).
  - "Results were noisy (7% spread): close background apps and retry." (RW-7).
  - The Stutter Doctor line: 58 spikes, likely cause GC 35%.
  - Buttons "Use render distance 31" / "Keep current (32)". The player kept 32.
- **In game:** no RigTune chat. DH's chat nags (high vanilla RD, "G1 Garbage collector detected") are DH's own.
- **11:08, Stutter Doctor session:** saved; no advice ("No advice for this session"). The Stutter screen would show a
  "Chunk loading 0%" bar (RW-10).
- **11:30, next start (session 2):** "RigTune applied 2 change(s) / RigTune's staged changes were applied." This is
  false (RW-1). rigtune.json `lastShownApply` = 2026-09-27T01:08:48.2923068Z, which is the 11:08 helper run; the file was
  written at 11:30:33.
- **Session 2, nothing else:** no preLaunch WARN, no leftover toast, no what's-new (awareness.json unchanged, r16), no new
  benchmark or trend notice (benchmarks.json unchanged). Stutter Doctor saved the session with no advice.

## 5. Benchmark (RW-5 to RW-9, RW-15)

**Numbers.** Sodium, no shaders, DH rendering off, 2560x1440 fullscreen, uncapped (frame limit 260 = vanilla
"unlimited"):

| RD | settle | avg FPS | 1% low | client chunks |
|---|---|---|---|---|
| 32 | **timed out**: 1116/3001 missing after 20 s | 370 | 36 | 3479 |
| 17 | 797/797, 2.0 s | 1249 | 339 | 1169 |
| 24 | 1653/1653, 3.5 s | 947 | 223 | 2181 |
| 28 | 2289/2289, 4.5 s | 851 | 339 | 2905 |
| 30 | 2629/2629, 5.9 s | 782 | 286 | 3297 |
| 31 | 2821/2821, 5.4 s | 761 | 326 | 3501 |
| 31 ×2 repeats | 2.0 s each | 760 / 755 | 334 / 303 | 3501 |

Result: avg 757.5, 1% low 318.5, p99 2.36 ms, cv 0.0707, deadline not hit; 186 s of the 300 s budget used.

**Plausibility.** The numbers are plausible for a 7800X3D + RX 7800 XT on Sodium at these distances, as a flat
fly-over scene. The RD-32 row is world generation, not rendering: the 1% low is 36 FPS against 303-339 at every settled
distance.

**Target 170.** `min(refreshRateCap(180), 240)`, and a multiple of 10 ≥ 100 minus 10 (`core/…/SettingValues.java:51-57`,
called from `BenchmarkController.java:246`). As designed.

**The five problems:**
- **RW-5, the RD 31 recommendation is an artefact of the fresh world.** An incomplete step can't pass
  (`RenderDistancePlanner.java:63`), so it becomes `lowestFail` (`:122-130`). The planner bisects under it and converges
  on 31/32 with "32 does not [meet the target]" (`:117`), which is false.
- **RW-6, DH wasn't quiet.** The benchmark switches DH *rendering* only in DH_OFF steps, and none ran. DH's LOD
  *generation* ran the whole time: 8 world-gen threads, "World generator thread pool shutdown with [5] incomplete tasks"
  at exit, a 68 MB sqlite in the benchmark save, and 40 of 58 spikes tagged `dh` (process ≥ 85% of 16 cores). The 6 s
  render-thread teardown at exit (10:48:02→10:48:08) ends with DH closing its databases, most likely flushing that data.
  It also made FastQuit's race wider (§6).
- **RW-15.** The stutter capture records every sweep, including the unsettled RD-32 one, and that sweep holds all 10 of
  the worst spikes.
- **RW-8.** The world was created on this run (10:44:48, marker `rigtune-benchmark.json` {"mcVersion":"26.2","seed":8675309});
  later runs reuse it (`BenchmarkWorld.java:108-117`). So this run's trend point includes first-time generation.
- **RW-9.** The rules cap vanilla RD at 12 when DH renders, and the player turned DH rendering on in THE ONE right
  after. A DH-off RD tune doesn't carry over, and the screen doesn't say that.

## 6. FastQuit and the benchmark world exit (RW-12)

**How FastQuit 3.1.5 tracks worlds** (decompiled with javap; copy in `<scratch>/realworld/fastquit/`):
- **Add, render thread:** `MinecraftMixin.fastquit(IntegratedServer)` redirects `IntegratedServer.isShutdown()` inside
  26.2's `Minecraft.disconnect(Screen,ZZ)`. That loop runs *after* `ClientPacketListener.close()`, the HUD teardown,
  `level = null` and `server.halt(false)`. It puts the server into the static `FastQuit.savingWorlds` map and logs
  `Disconnected "X" from the client.`
- **Remove, server thread:** `MinecraftServerMixin.fastquit$finishSaving`, at the RETURN of `onServerExit`. When the
  entry isn't there it logs `"X" was not registered in currently saving worlds!` and returns, without the "Finished
  saving" toast.
- **The race:** the server starts stopping at `ClientLevel.disconnect` ("Stopping singleplayer server as player logged
  out"). So whenever the server finishes before the client's teardown reaches the loop, the remove comes before the add,
  and the entry stays for the rest of the process.

**Both worlds hit it:**
- **Benchmark:** the server was done at 10:48:02 (level.dat mtime 10:48:02.890), and the add came at 10:48:08, after DH
  closed its databases (the sqlite mtime is 10:48:08.46).
- **THE ONE:** the same race after the player's own Save and Quit, with about a 1 s gap (log:1563 WARN, 1569 add).

**Stale entries are harmless.**
- Ixeris is on FastQuit's conflict list, so "allow multiple servers" is off, and `LevelStorageSourceMixin` calls
  `FastQuit.wait(all)` before THE ONE opened. Its predicate `!server.isShutdown()` is false for a stopped server, so it
  returns immediately: no visible wait and nothing blocked.
- FastQuit then logs a false "allowing a world to load while another is currently being saved".
- At quit, `FastQuit.exit()` joins finished threads.
- Cost: the stopped benchmark `IntegratedServer` stays strongly referenced in a static map (not quantified; its chunks
  were unloaded).

**RigTune's exit is the vanilla one.** `BenchmarkWorld.java:281` calls `minecraft.disconnectFromWorld(…("menu.savingLevel"))`,
the same method as PauseScreen's Save and Quit; only the reason text differs.
- The benchmark world is never deleted after a run (only when the marker is missing or from another MC version,
  `BenchmarkWorld.java:109-117,166-176`), and `createAccess()` goes through FastQuit's wait anyway, so there is no race
  with a background save.
- The sampler thread lists confirm that no server kept running. There is one `Server thread` at 10:45:18 and at
  10:49:51. The benchmark's `C2ME Storage #1-#9` stopped at 10:48:02, and THE ONE used #10-#18.
- The growth of `DH-ChunkSaveIgnoreTimer` (6→12) and `DH-World Gen Progress Updater` (3→6) is DH leaking timers per
  world, which is DH's own.

**Session 2 confirms the model.**
- At 12:24:34 the server had 729 + 2203 chunks to unload, so it finished *after* the client's teardown.
- The order was right: `Disconnected "THE ONE" from the client` (render thread, 12:24:35), then `Finished saving "THE ONE"
  (0s)` (server thread, 12:24:35).
- So there was no WARN, the "done" toast showed, and at quit there was no "Waiting for…" line.
- Which side wins depends only on how long each teardown takes.

**Verdict.** This is a FastQuit + DH quirk, not a RigTune bug. The benchmark needs no FastQuit-specific change; RW-6
(no DH generation in the benchmark world) would shorten the teardown that widens the race.

## 7. Stutter Doctor (RW-10, RW-11; SD-1)

**Sessions (stutter.json):**

| source | started (UTC) | session / gameplay s | frames | avg / 1% low | spikes (minor/major/severe) | lost | causes | tags | advice |
|---|---|---|---|---|---|---|---|---|---|
| benchmark | 00:45:17 | 164.7 / 128.4 | 103,964 | 809.8 / 318.5 | 56/2/0 (hitches 30) | 1.5 s | gc 0.35, unknown 0.65 | dh 40, chunksLoading 30 | [] |
| monitor | 00:49:51 | 1131.8 / 830.9 | 137,039 | 164.9 / 121.2 | 126/3/3 (hitches 104) | 3.3 s | gc 0.60, tick 0.18, chunkLoad 0.0, unknown 0.23 | dh 7, chunksLoading 47, movingFast 52 | [] |
| monitor, session 2 | 01:30:46 | 3229.1 / 3019.0 | 507,932 | 168.2 / 125.8 | 54/2/1 (hitches 29) | 1.6 s | gc 0.04, tick 0.02, unknown 0.94 | chunksLoading 6, movingFast 1 | [] |

**What it concluded.**
- Monitor session: 3.3 s lost in 831 s (0.4%), 60% of the claimed time GC, with no full GC, no stall, a live set of 37%
  and 1 explicit GC. Advice [] is correct under the r16 rules:
  - `ram-stutter-gc-heap` needs a full GC, a stall or a live set ≥ 75%;
  - `stutter-gc-explicit` needs ≥ 2 explicit GCs;
  - `stutter-dh-threads` needs dh ≥ 40% (7/132 = 5%).
  - "No advice for this session" is an honest verdict. No false claim, except the 0% bar (RW-10).
- Benchmark session: dh = 69% of spikes. `stutter-dh-threads` most likely failed on `cpuContentionShare < 30`
  (`StutterAnalyzer.java:359-365`; the ring data isn't stored, so this can't be proven). Had it fired, it would have
  blamed the player's DH for RigTune's own scene (RW-6).

**How the session started.** The monitor was off by default (`ClientSettings.java:37`), so `benchmarkFinished` didn't
start a session (`StutterService.java:270`). The player turned it on at 10:49:51 (settings.json mtime). The "70 s gap"
after joining THE ONE is the player's own action, not a start delay.

**SD-1 dilution** (audit, `docs/research/v0.5/audit-v040-features.md:78`) is barely visible here:
- The sampler ring (4096 × ≥ 250 ms ≥ 1024 s) against a 1131.8 s capture lost at most the first ~108 s. That is exactly
  where the worst spikes are (t = 65–75 s), so those can't get a `dh`/contention tag.
- The GC ring still held t = 74.7 (that spike has `gc:high`).
- The advice didn't change. SD-2: 137,039 frames > the 131,072-frame ring, so the 1% low leaves out about the first 36 s.
- **Session 2 shows SD-1 and SD-2 plainly** (3229 s capture):
  - **SD-1:** the thread sampler ring (4096 samples × ≥ 250 ms, `ThreadSampler.java:29`, `StutterRings.java:10`) holds
    at most the last ~1024 s. So the first ~2200 s of the session have no thread samples, and no spike there can get a
    `dh` or contention tag.
  - Nine of the ten worst spikes (t = 20.6–608.4 s, including the worst, 284.6 ms at t = 518.7 s ≈ 11:39:25) fall in
    that uncovered stretch. They carry only `render:low`, `tick` or GC notes, and the session ends up 94% "unknown".
  - The GC ring didn't wrap: spikes at t = 20.6 and 30.7 s still carry `gc:high` / `gc:medium`. At a ~168 FPS cap with a
    19% live set there were few GCs, so GC attribution held.
  - **SD-2:** 507,932 frames against the 131,072-frame ring (`FrameRing.java:17`). The 1% low (125.8 FPS) describes
    only the last ~13 min of a 54-min session, while avg FPS covers all of it.
  - The advice ([]) is still right: 1.6 s lost in 3019 s is 0.05%. But the "unknown 94%" headline is mostly what the
    rings can't see. Both findings are already CONFIRMED in `audit-v040-verification.md:37-38`. This is real-world
    corroboration, not a new finding.
- **Session 1 vs 2:**
  - 132 spikes / 831 s (0.16/s, 0.40% lost) vs 57 / 3019 s (0.019/s, 0.05% lost).
  - The play differed. Session 1: RD 32 until 10:53, shader toggles, elytra flight (`movingFast` 52, `chunksLoading`
    47), right after a benchmark. Session 2: RD 12 from the start, DH on, mostly mining in the Nether (one dimension
    change at 11:31:24, `movingFast` 1).
  - The live set was 37% → 19%, and GC share 60% → 4%.
  - Nothing here points at RigTune; the settings and activity explain the difference.

**RW-11.** See the table. The capture spans shader toggles with Iris compile failures and an RD change. The 236.6 ms
"tick" spike coincides with the Iris pipeline rebuild at 10:51:02.

**Expected, not bugs:**
- Histogram bucket 0 is < 4.17 ms (`FrameRing.java:46-47`), which a ~165 FPS capped session never reaches.
- "timers seen 1111111" vs "11111" is `toBinaryString(phaseSeen)`; bits 5-6 are the frame limiter, active only when capped.
- Hitches are spikes less than 100 ms apart grouped together (`SpikeDetector.java:16,122-133`).

## 8. Awareness, startup, footprint, JVM, launcher, rules

- **awareness.json:**
  - The fingerprint is correct: AMD / RX 7800 XT / 26.8.1 driver / OpenGL / 7800X3D / 31,849 MB.
  - `lastSeenRulesRevision` 16 equals the bundled rules-v2 r16 and `rules-v2-cache.json` r16.
  - `lastSeenRecommendationIds` lists every rule id, including irrelevant ones such as `advice:battery`. That is by
    design: it is the "seen" baseline (`WhatsNew.java:20-26`).
  - 0.1.0's v1 `rules-cache.json` (r4) is still on disk and unused.
- **Startup times:**
  - "32284 ms (52 mods)" is JVM uptime at the first TitleScreen.
  - "52 mods" is top-level, non-builtin mods (`client/footprint/StartupTimes.java:63-72`) = the 52 jars (53 listing
    lines minus `entityculling-…1.11.1….jar.disabled`). Fabric's 170 includes nested and builtin mods.
  - ModernFix's "Game took 85.152 seconds to start" fires at the first world join, in every log of this instance
    including 0.1.0's. Its "total 4.88 s < main menu to in-game 5.88 s" shows its own start time was unset; that is
    ModernFix's quirk.
  - The next start (11:30) took 27,870 ms. The startup-times trend now has 2 runs with the same modSetHash:
    32,284 → 27,870 ms. See §11 for where the 4.4 s went.
- **Footprint:** init 85.1 ms (budget 368), CPU 46.9 (150), client start 19.7 (141), worker CPU 234.4 ms in 5 s (300,
  78%), all within `tools/footprint-budgets.json`. 11:30: 51.5 / 42.4 / 15.4 / 218.8. During play RigTune had 6 threads:
  worker ×2, network, rules, stutter sampler, and settings (which lives 10 s after a save).
- **JVM:** "Java 25.0.3 (Azul Systems, Inc.), G1 (Java's choice), 0 argument notes []" with a 6144 MB heap. No `jvm-*`
  rule can fire, since they need argument flags. `ram-distant-horizons` needs a heap ≤ 5500 MB. Hardware flags include
  `sodium-workaround:AMD_GAME_OPTIMIZATION_BROKEN`, so only the informational `sodium-amd-game-optimization` matches.
  DH nags "G1 … use ZGC" in chat while RigTune says nothing: a knowledge question (§9), not a bug.
- **Launcher:** "RigTune: launcher Modrinth App" is correct. The E2E's dev environment logged "not recognised".
- **Rules:** r16 was used (bundled and cached agree). The network was on.

## 9. Knowledge notes (RW-13, not bugs; unverified, for the knowledge workstream)

- Complementary Reimagined r5.9.3 + DH 3.3.2 + Iris 1.11.4: `dh_terrain.vsh` fails ("'dhProjection' : undeclared
  identifier") and Iris disables shaders, three times in this session. A candidate for `shaders-distant-horizons`-style
  advice, once the fixed versions are verified.
- G1 + DH: DH warns at every world join; RigTune has no stance. Needs research (heap 6 GB, 7800X3D) before any advice.
- Windows performance counters switched off → about 6 s of vanilla launch time: now RW-16, analysed in §12. The earlier
  guess of "broken counters, lodctr /r" was wrong for this PC.
- C2ME disables `ioSystem.gcFreeChunkSerializer` because of Architectury (log:207): informational.

## 10. latest.log: RigTune WARN/ERROR lines

RigTune's WARNs:
- The 3 preLaunch lines (log:274-276, RW-3).
- "Benchmark settle RENDER_DISTANCE … timed out after 20.0 s …; this step can't count as a pass" (log:833, RW-5).

There are no RigTune ERRORs and no stack frame from `io.github.chaotix345` anywhere in the log. The 4 ERROR lines are
vanilla/OSHI (log:290) and Iris (log:1176, 1223, 1298).

## 11. Session 2 (11:30–12:24): the second 0.4.0 launch, steady state

**Inputs.** `instance-copy-2/`, copied at 12:26:
- `mods-listing.txt` is identical to session 1.
- helper.log, last-apply.json, history.json, awareness.json, benchmarks.json and settings.json are byte-identical to the
  session-1 copies.
- Only rigtune.json, startup-times.json and stutter.json changed.
- There is no pending.json and no unfinished-groups.json.

| when (local) | what | evidence |
|---|---|---|
| 11:30:06 | Launch (170 mods). | log:1 |
| 11:30:11–18 | Vanilla OSHI stall on the main thread, 7 s (RW-16). | log:~282-300 |
| 11:30:18 | "ModernFix reached bootstrap stage (13.01 s after launch)". | log:354 |
| (preLaunch) | No WARN: no pending.json since 11:08. The journal reconcile had nothing to change (history.json byte-identical). | log; copies |
| 11:30:29 | RigTune worker: the same Java / launcher / hardware lines as session 1. | log:598-601 |
| 11:30:33 | "Launch to title screen: 27870 ms (52 mods)". rigtune.json `lastShownApply` set to the 11:08 run: the false "RigTune applied 2 change(s)" toast (RW-1). | log:642; rigtune.json |
| 11:30:34 | Footprint: preLaunch + init 51.5 ms on the render thread (CPU 42.4 ms), client start 15.4 ms, RigTune threads 218.8 ms CPU in 5 s. | log:643 |
| 11:30:42–46 | THE ONE joined at RD 12 / SD 12. DH renderer up (11:30:45). Stutter Doctor capture on at 11:30:46 (the monitor setting stayed on). 214 threads: RigTune has 5 (worker ×2, network, rules, sampler). | log:692-863 |
| 11:31:24–12:24 | To the Nether, then mining. The "Mismatch in destroy block pos" WARNs are the vanilla server's. | log |
| 12:24:34–35 | Save and Quit. FastQuit in the right order, "Finished saving "THE ONE" (0s)" (§6). "Stutter Doctor: session saved (OK): 57 spikes in 3019 s of gameplay". | log:1040-1092 |
| 12:24:40 | Quit Game. No "Started the RigTune apply helper": nothing was staged. | log:1095-1098 |

| | session 1 | session 2 |
|---|---|---|
| launch to title (JVM uptime) | 32,284 ms | 27,870 ms |
| JVM start → start of the OSHI block (JVM start = ModernFix bootstrap time − its "s after launch") | 10.7 s | 6.0 s |
| vanilla OSHI block | 7 s | 7 s |
| ModernFix bootstrap | 17.71 s | 13.01 s |
| bootstrap → title | ~15 s | ~15 s |
| RigTune preLaunch + init (render thread) / CPU | 85.1 / 46.9 ms | 51.5 / 42.4 ms |
| client start | 19.7 ms | 15.4 ms |
| RigTune threads, CPU in the first 5 s | 234.4 ms | 218.8 ms |
| RigTune notices | leftover toast, 3 WARNs (RW-3), benchmark result screen | "applied 2 change(s)" toast (false, RW-1) |
| Stutter Doctor | 132 spikes / 831 s, advice [] | 57 spikes / 3019 s, advice [] (§7) |
| helper at exit | ran: SKIPPED ×2 → history STAGED→APPLIED (RW-1) | none; history.json, last-apply.json and helper.log unchanged (live mtimes still 11:08:48) |
| FastQuit | the race: 2 WARNs, 2 stale entries | the right order, no WARN |

**Readings:**
- **The 4.4 s faster launch** all came before the OSHI block: 10.7 → 6.0 s of early loading. Timestamps are
  whole seconds, so ±1 s. The OSHI block (7 s) and bootstrap→title (~15 s) were the same both times. A warmer OS file
  cache on the second launch is plausible (session 1 was the first launch after the app re-synced the instance and
  0.4.0 was installed); UNVERIFIED.
- **RigTune's own footprint** fell 85.1 → 51.5 ms. Session 1's preLaunch did one-time work: it created history.json
  with the legacy import under the apply lock, and logged the carried-over failures. That plausibly explains part of the
  difference; it isn't isolated. Both are well within `tools/footprint-budgets.json`.
- **Nothing new diverged.** Session 2 is what the design predicts for a steady-state start, apart from the RW-1 toast.

## 12. Launch time: Windows performance counters are off (RW-16)

### 12.1 What it costs, measured in every launch log

Vanilla 26.2's `Main.main` calls `CrashReport.preload()` (javap: `net.minecraft.client.main.Main`, the
`invokestatic CrashReport.preload` at bytecode offset 749). That builds a crash report, and its `SystemReport` queries
OSHI on the main thread. This happens before any mod's client init, so RigTune's footprint doesn't include it.

The block, from the first OSHI line to the last, in whole seconds:

| log | RigTune | block | step that waits |
|---|---|---|---|
| 2026-09-25-1 (09:03) | 0.1.0 | 09:03:33 → 39, **6 s** | "Disabling further attempts to query Paging File" → "COM exception: Invalid Query … Win32_PerfRawData_PerfOS_PagingFile": 4 s |
| 2026-09-27-1 (10:43, session 1) | 0.4.0 | 10:43:41 → 48, **7 s** | the same: 5 s |
| latest (11:30, session 2) | 0.4.0 | 11:30:11 → 18, **7 s** | the same: 6 s |
| all 43 launch logs since 2026-07-09 | before and with RigTune | median **6 s**; 31 launches at 5–9 s, 12 at 0–2 s | the WMI paging-file fallback, 4–6 s cold |

- Half of the 0–2 s launches are relaunches within minutes of another launch (09-13 12:35/12:38/12:41, 09-20
  22:12/22:17, 08-30 14:58). That fits WMI being warm; see the probe's warm run (§12.5). The other six are UNVERIFIED.
- The WARN is in the instance's very first log (2026-07-09), so this PC has had it since before RigTune.
- The two launch logs without the block (09-13-2, 09-13-5) are tiny (under 1 KB compressed): launches that stopped
  early.

### 12.2 Why: this PC's performance counters are switched off (read-only checks)

**Registry values:**
- `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Perflib`: **"Disable Performance Counters" = 1 (REG_DWORD)**.
  Also there: Last Counter 19102. The 009 name table is intact: its `Counter` value holds 13,907 strings (index/name pairs).
- `HKLM\SYSTEM\CurrentControlSet\Services\PerfOS\Performance`: "Disable Performance Counters" = "0" as **REG_SZ**.
  PerfProc has 0 as a REG_DWORD; PerfDisk has no value.

**The counters fail everywhere:**
- `typeperf "\Processor(_Total)\% Processor Time" -sc 1` (and the Paging File and Process counters): "Error: No valid
  counters".
- `Get-Counter '\Processor(_Total)\% Processor Time'`: "Internal performance counter API call failed. Error: c0000bb8".
- `Get-Counter -ListSet *`: "error 00000422". That is ERROR_SERVICE_DISABLED. Microsoft's description of the Perflib
  entry (§12.4) says that with value 1 "the system returns an error to the program explaining that the Performance
  Library (Perflib) service is disabled".

**How OSHI 6.9.0 reacts** (javap of `oshi.driver.windows.perfmon.PerfmonDisabled`):
- It checks only the per-service `…\Services\{PerfOS,PerfProc,PerfDisk}\Performance` values, never the Perflib one.
- It warns "Invalid registry value type detected for PerfOS counters. Should be REG_DWORD. Ignoring" (the REG_SZ above)
  and treats the counters as enabled.
- So `PagingFile.querySwapUsed` tries PDH ("Failed to add PDH Counter … 0xC0000BB8") and then its WMI backup table.
  After ~5 s cold, WMI answers "Invalid Query": that perf table isn't there with counters off.
- `getCurrentProcess` then fails the same way: first HKEY_PERFORMANCE_DATA, then PDH, then WMI, so the process is null
  and vanilla logs the three NPE WARNs.

**Who set it:** unknown. As circumstantial evidence, Perflib's own events list extensible-counter DLLs that are missing
(sysmain.dll, bitsperf.dll, msdtcuiu.dll), which suggests Windows components were removed, as some "debloat" tools do.
UNVERIFIED. RigTune must not guess or blame.

**Side effect of these checks:** my typeperf/Get-Counter runs made Windows write two Perflib warning events (1008:
WmiApRpl, MSDTC) to the Application log at 13:20:20. No registry value, service or file was changed.

### 12.3 Does RigTune pay it too? No

- **Timing:** RigTune's OSHI probe runs on the "RigTune worker" executor at CLIENT_STARTED
  (`client/RigTuneClient.java:119`; `client/probe/HardwareProbe.java:56-58`), after vanilla's block. Its "Hardware:"
  line comes in the same second as the Java line in both sessions (10:43:59, 11:30:29).
- **Calls:** `HardwareProbe.probeSlow` (`:164-205`) uses these, per javap of OSHI 6.9.0:
  - processor identifier: WMI `Win32_Processor`, a CIMv2 class, not a perf table;
  - max frequency: PowrProf `CallNtPowerInformation`;
  - processor counts: `GetLogicalProcessorInformationEx`;
  - memory total;
  - power sources: PowrProf;
  - graphics cards: registry.

  None of these reaches `oshi/driver/windows/perfmon/*`. The PDH-backed OSHI calls are current frequency, load ticks,
  load average, swap used and process data, and RigTune calls none of them.
- **The JDK:** `ThreadSampler` uses `getProcessCpuTime` (GetProcessTimes). No RigTune code calls the JDK's
  `getCpuLoad` / `getProcessCpuLoad`, which read PDH counters on Windows.
- **The logs:** in both 0.4.0 sessions the OSHI WARN block appears exactly once, on `main`, at startup.

### 12.4 What Microsoft documents (and what it doesn't)

- **Perflib entry.** "Disable Performance Counters Entry", in the Windows Server 2003 Registry Reference (archived,
  last updated 2009): https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2003/cc737243(v=ws.10)
  - Key `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Perflib`, REG_DWORD, range 0 | 1, default 0.
  - "0: Enables performance counters. Programs can retrieve performance data from the registry. 1: Disables performance
    counters."
  - "To make changes to this entry effective, restart Windows."
  - "This entry affects all performance counters on the system."
- **Per-service entry.** "Disable Performance Counters", same reference:
  https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2003/cc784382(v=ws.10)
  - `HKLM\SYSTEM\CurrentControlSet\Services\<service>\Performance`, REG_DWORD 0 | 1; 2 = the 32-bit version disabled, 4 =
    the 64-bit version disabled.
  - "To enable or disable all registry-based performance counters on the system, add the Disable Performance Counters
    Entry entry to the Perflib subkey."
- **Current troubleshooting article.** "Manually rebuild performance counters…" (KB 2554336):
  https://learn.microsoft.com/en-us/troubleshoot/windows-server/performance/manually-rebuild-performance-counters
  - Its first resolution step is "Ensure that the counters aren't disabled in the registry … this value should be set to
    0 … A value of 1 means the counter is disabled".
  - Only after that does it cover rebuilding corrupted libraries: at an administrative prompt, `lodctr /R` in system32
    and in sysWOW64, `WINMGMT.EXE /RESYNCPERF`, then restart the `pla` and `winmgmt` services.
- **`lodctr` on this PC.** Command reference: https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/lodctr.
  Local `lodctr /?` shows `/R` ("Rebuilds perf registry from scratch based on current registry settings and backup INI
  files"), `/Q[:service]`, and `/E:<service>` / `/D:<service>` ("Enables / Disables the performance counter provider").
  It has no switch for the Perflib-wide entry.

**For this PC:**
- The documented Windows-side fix is to put the Perflib entry back to its default 0 and restart Windows.
  - **UNVERIFIED on Windows 11.** The reference is for Windows Server 2003, though this PC behaves exactly as it
    describes. I did not change the value to prove that the stall disappears; that needs the user, as an administrator.
  - **UNVERIFIED** whether WMI's perf tables come back on their own afterwards or need `winmgmt /resyncperf`. The
    article pairs that command with rebuilds, not with this setting.
- **`lodctr /R` is not the fix here.** The name table is intact, Microsoft puts "make sure they aren't disabled" first,
  and nothing documents `/R` resetting the Perflib entry.
- **The REG_SZ PerfOS value:** its documented type is REG_DWORD, and how Windows reads a REG_SZ there is undocumented.
  Say "unusual type" at most.
- **RigTune must never write the registry.** It is HKLM, needs an administrator, is system-wide, and someone may have
  set it on purpose.

### 12.5 What RigTune could honestly do (proposal; design call for the coordinator)

**a. Detect, read-only.** On Windows, in preLaunch, read the Perflib entry with JNA's `Advapi32Util`. JNA and
jna-platform ship with Minecraft; OSHI's `PerfmonDisabled` reads the per-service values the same way. A REG_DWORD ≠ 0
means counters are off. Also read the three per-service entries: a DWORD ≠ 0 means that service's counters are off;
another type means "unusual" only. The read costs microseconds and needs no administrator.

**b. Measure.** Time `CrashReport.preload()` with a tiny HEAD/RETURN mixin and store it as `systemReportMs` in
startup-times.json. Then advice can say "7.0 s at this launch" instead of guessing, and the launch-time alerts work
(C18, `docs/research/v0.5/feature-launch-alerts.md`) can split launch time into vanilla and mods. UNVERIFIED that the
mixin applies that early (Fabric preLaunch runs before `Main.main`, so it should); one real launch confirms it.

**c. Skip the wait, without touching Windows.** Only when (a) finds counters off, set OSHI's own documented switches in
preLaunch, before vanilla's report: `oshi.os.windows.perfos.disabled=true` and `oshi.os.windows.perfproc.disabled=true`.
Set them through `oshi.util.GlobalConfig.set` as well as system properties.
- OSHI's `oshi.properties` says: "If counters are either intentionally disabled, or the application depending on OSHI
  does not require any of the relevant performance counters, setting these values to true will skip querying these
  counters and return 0 values for the associated metrics. No log messages will be generated."
- **Measured on this PC.** A standalone replica makes the OSHI calls vanilla 26.2's `SystemReport` makes (javap of
  `net.minecraft.SystemReport`), in order: OSHI 6.9.0, JNA 5.17.0, JDK 25, no Minecraft.
  `<scratch>/realworld/oshi/probe/Probe.java`, output in `probe-output.txt`:

  | run | swap used (PerfOS) | current process (PerfProc) | all of vanilla's OSHI calls |
  |---|---|---|---|
  | cold, default | 5,091 ms (WMI "Invalid Query") | 623 ms → null | **6,294 ms** |
  | cold, switches on | 0 ms | 147 ms → null | **774 ms** |
  | warm (2 s later), default | 672 ms | 705 ms → null | **1,943 ms** |
  | warm, switches on | 0 ms | 142 ms → null | **707 ms** |

  The results are identical with and without the switches: swap used 0, process null, and vanilla's crash-report fields
  are "unknown" either way. The saving is about 5.5 s cold and 1.2 s warm.
- **Caveats:**
  - The switches must be set before OSHI's `PerfmonDisabled` class initialises, which is at vanilla's first perfmon
    query. C2ME already uses OSHI at mixin time ("CPU name" at 10:43:36), which initialises `GlobalConfig`; so system
    properties alone would be too late, and `GlobalConfig.set` is needed. UNVERIFIED in game until one real launch.
  - It affects every OSHI user in the process: they get 0 or empty PerfOS/PerfProc metrics instead of a slow failure.
    That only happens where Windows itself reports those counters off, so nothing that works today stops working (PDH
    and WMI both fail on this PC).
  - Give it an opt-out setting and one INFO line ("Windows performance counters are off; told OSHI not to query them").
- **Not proposed:** `oshi.util.wmi.timeout`. It would cut every WMI query short, including ones that work.

**d. Advise.** Windows only, when (a) is true and (b) measured ≥ 2 s, as launch-time advice:
> "Windows performance counters are turned off on this PC (the Perflib setting 'Disable Performance Counters' is 1).
> Minecraft's crash-report setup waited 7.0 s for them at this launch; RigTune now skips that wait. Microsoft documents
> this setting (link): 0 is the default, and Windows needs a restart after a change. RigTune doesn't change Windows
> settings."

Use a new probe flag `windows-perf-counters-off` in `HardwareProfile.flags`, and a rules-v2 advice gated on
`"when": {"flags": ["windows-perf-counters-off"]}`, the way `sodium-amd-game-optimization` is gated. Older clients
never emit the flag, so the rule stays silent for them. Say nothing about who turned the counters off.

**e. Tests:**
- A detector unit test with a fake registry reader: 1 / 0 / REG_SZ / absent / not Windows.
- "Switches set only when detected": a unit test on the preLaunch hook with the reader faked.
- The probe as a manual Windows check.
- One launch on this PC, done by the user, not an agent: the OSHI block should be gone from latest.log, and launch to
  title should drop by about 5–6 s cold.

## Reproduce

```
cd C:/Dev/Worktrees/rigtune-realworld            # branch research/v05-realworld (throwaway, not committed)
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.history.RealWorld20260927Test"   # before the prototype
./gradlew :26.2:test --tests "io.github.chaotix345.rigtune.core.history.RealWorldFixTest"        # with the prototype
```

- The worktree currently has the prototype applied (`src/main/…/ApplyExecutor.java`). With it, the 2 reproduction tests
  fail by design; `git -C C:/Dev/Worktrees/rigtune-realworld stash` restores today's behaviour.
- The tests read the copied instance from `<scratch>/realworld/instance-copy` (tests C and D skip if it's gone).
- RW-16 probe (read-only; no Minecraft, no gradle): in `<scratch>/realworld/oshi/probe`, run
  `java -Dlog4j2.level=WARN -cp "oshi-core-6.9.0.jar;jna-5.17.0.jar;jna-platform-5.17.0.jar;slf4j-api-2.0.17.jar;log4j-api-2.26.0.jar;log4j-core-2.26.0.jar;log4j-slf4j2-impl-2.26.0.jar" Probe.java`,
  then the same with `-Doshi.os.windows.perfos.disabled=true -Doshi.os.windows.perfproc.disabled=true`.
- The launch-log measurements for §12.1 were made with a script over the live instance's logs (read-only). Session
  copies of 09-20 to 09-27 are in `<scratch>/realworld/oshi/*.log`.
- The Microsoft pages quoted in §12.4 are saved as text in `<scratch>/realworld/msdocs/`.
- Copies of both test files, the diff and the dump are in `<scratch>/realworld/`.
