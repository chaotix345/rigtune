# RigTune v0.5.0: plan review (feasibility, ownership, contracts, waves, logistics)

Reviewer: prev-plan (independent; did not write the PLAN). Scope: docs/v0.5/PLAN.md (fb86278f) against docs/v0.5/SPEC.md, the user's requirements (scratchpad/spec/user-v05-requirements.md), docs/PROGRESS.md (v0.5 and Lessons), docs/v0.4/PLAN.md + plan-review.md, the research files' effort estimates, and the code on `feat/v0.5.0` (f291c315) plus ws-ci's branch head (`feat/v05-ci` c2496742: build.yml, tools/ci/offline.sh, tools/footprint-budgets.json, ws-ci.md). Read-only: no gradle, no Minecraft, no git state change. File:line references are to f291c315 unless `feat/v05-ci:` is named.

Coordinator decision taken into account: the first 5-run streak runs on the SHA where ws-ci AND WS-K have merged; Wave A agents work on their branches meanwhile and only merges into `feat/v0.5.0` wait for the streak.

**Result: 4 high, 15 medium, 6 low.**

## Summary

| id | sev | one line |
|---|---|---|
| PLAN-1 | HIGH | The X4 flag ("holder unresolved when the CLIENT_STARTED handler returns") races the executor submissions the PLAN itself puts in that handler (WS-F `load()`, WS-T first derive, 4h's compare): an intermittent red FootprintGameTest that the first streak can't see |
| PLAN-2 | HIGH | LanGuestGameTest's multicast LAN discovery runs inside ws-ci's loopback-only namespace, where `lo` has no multicast and no route; the research proof ran outside it. The fix is in ws-ci's frozen `tools/ci/offline.sh` |
| PLAN-3 | HIGH | Seven new game-test classes (TryIt's benchmark pairs, two dedicated-server classes) push legs past the 15-min step timeout (slowest green step today 11.7 min); SPEC 1g's two-JVM split has no owner once ws-ci merges |
| PLAN-4 | HIGH | RigTuneSettingsScreen can't fit PF-2's row at 640×480@2 (7 fixed rows end at y 204, footer at 212), let alone WS-L1's too; two parallel owners, "rows appended", no relayout owner |
| PLAN-5 | MEDIUM | RealController `rebuild()`/`apply()`/`start()`: 3-4 editors inside ~20 lines each; RW-3 is not a one-liner; 4h's Apply-status line and start-time compare have no owner |
| PLAN-6 | MEDIUM | Call-site collisions not covered by "own method": RigTuneClient registrations/toasts (WS-T's title toast unlisted), LangCheckTest `families()` (WS-L1, WS-L2, WS-T need family lines; only WS-S2 allowed) |
| PLAN-7 | MEDIUM | WS-K item 12 can't land "blocks with each skeleton's keys": LangCheckTest fails every unused key and en_us.json has no physical blocks; it forces premature wording |
| PLAN-8 | MEDIUM | L8 (WS-H) needs WS-P's ProfileService/ProfileStore: `journalIds()` prunes folded switch labels; SPEC C7 lists ProfileStore as shared (PF-5, L8), the PLAN dropped it |
| PLAN-9 | MEDIUM | AC2R.2's grep test (WS-W) fails on `BenchmarkMenuScreen.java:71` (raw `settings.save`), a WS-T file (Wave B) |
| PLAN-10 | MEDIUM | RW-6 detection (WS-B) needs a "DH-World Gen" bucket that ThreadSampler (WS-S) doesn't have (`ThreadSampler.java:108` lumps every `DH-*`): hidden WS-B → WS-S dependency, not a WS-K seam |
| PLAN-11 | MEDIUM | The real-world tests/fixtures are split across WS-L1 (Undo cases, merges first) and WS-L2 (owns the files, merges after): AC4c.2/AC4c.3 can't close in WS-L1 |
| PLAN-12 | MEDIUM | Hidden ordering and dangling ACs (AC4b.6, AC8.14, AC2B.9, every "compat040 reads…" AC); `FirstRunService.status()` isn't in the contracts list |
| PLAN-13 | MEDIUM | Game-test wrappers: A11y owners can't add canned views without editing the shared `A11yController`; existing wrappers silently return the new defaults instead of forwarding |
| PLAN-14 | MEDIUM | Footprint headroom has no allocation: `workerCpuMs5s` 224.9 of a 300 ceiling; RW-11 (P0) and TryIt's tick against `tickHookOnVsReference` 1.95 in a frozen file the classifier won't loosen; ToolsScreen already clips at 4 lines |
| PLAN-15 | MEDIUM | "Same owner continues" (S→S2, P→P2, W→W2) contradicts "early core in Wave A in parallel"; if one agent, C20 becomes the critical path (~8.3 days + calibration) |
| PLAN-16 | MEDIUM | Under-sized: WS-B (L3 alone is 2-3 days per lo), WS-L2 (4h is a feature), WS-L1, WS-S, WS-E; move 4h to WS-W and make WS-B's part-1 merge an explicit milestone |
| PLAN-17 | MEDIUM | CI capacity: ~12 design-doc pushes land exactly during the first streak; release-tier dry runs (~23 jobs) have no schedule or `max-parallel`; the streak's "≈1-2 h" is optimistic |
| PLAN-18 | MEDIUM | Local builds: ~12 concurrent `./gradlew build` (2 GB daemons, both nodes, ~1850 tests ×2) on a 31 GB / 16-thread PC, with timing gates in the unit suite |
| PLAN-19 | MEDIUM | Phase 5 holds runs that decide code (NEW-1, RW-6's pause, C20/C09/C18 constants); the whole real-run list is serial under one lock and starts only after everything merges |
| PLAN-20 | LOW | v050-written: no `ws-p` set (PF-5 writes radius 4096); set names differ from the SPEC ACs; multi-set files have no merge rule; two regeneration mechanisms from v0.4 |
| PLAN-21 | LOW | PLAN text still says "WS-K merges after the streak"; `ci_streak.py`'s job list includes WS-E jobs absent on the P0.1 SHA |
| PLAN-22 | LOW | WS-E's cherry-pick 25243b63 also edits build.yml (and 1f4b6b3a the experiment workflow): a plain cherry-pick breaks "tools/e2e only" |
| PLAN-23 | LOW | Frozen files some AC needs: `GameState` (UndoPlanner.State), `snapshot-canary.yml` (AC3f.8 test), `ubuntu-latest` in frozen workflows (AC1c.3), PreviewScreen `populate()` (L5); `tools/tests/**` has three creators |
| PLAN-24 | LOW | Review hand-backs through the coordinator stall agents at finish; screenshot evidence needs named artifacts and a defined "before" baseline (AC2A.2) |
| PLAN-25 | LOW | Minor contract details: V05Services created at init and read by FootprintStats (class-load), skeleton method signatures in A11yGameTest, BenchmarkTrend statics reused by WS-T/WS-W2, notice details tooltip-only |

---

## PLAN-1 (HIGH): the X4 footprint flag races the PLAN's own CLIENT_STARTED submissions

**Where.** Contracts item 4 (PLAN:156); Hotspots row RealController "WS-F … `load()` submission in `start()`" (PLAN:259); WS-T "CLIENT_STARTED submit" (PLAN:116); SPEC X4.2/X4.3, AC-X.2, AC6.12, 8 ("submitted from CLIENT_STARTED").

**Problem.**
- The whole CLIENT_STARTED handler is `FootprintStats.clientStarted(() -> real.start(minecraft))` (`RigTuneClient.java:82`; `FootprintStats.java:85-98`). The PLAN puts WS-F's `FirstRunService.load()` submission inside `start()`, and WS-T submits its first derive from CLIENT_STARTED. SPEC 4h's start-time comparison ("before the player can change anything") has no location at all; the natural place is the same handler.
- Each of those services lives behind `V05Services` (contracts item 2). A task submitted to `Probes.EXECUTOR` resolves the holder on a worker thread whenever a worker is free, which can be before `start()` returns (it still runs `reloadRules()` and `rescan()` after the submission, `RealController.java:189-193`).
- X4.3 as written ("resolved before the CLIENT_STARTED handler returned → the leg fails") then fails nondeterministically: when both executor threads are busy with the OSHI probe and the mod scan the task waits and the leg passes, and when one is free it fails. That's a flake in exactly the P0.1 category ("never red for reasons outside the code").
- The first streak (ws-ci + WS-K SHA) cannot catch it: at that SHA the skeletons submit nothing. It appears in Wave A, looks like a flake, and can then break the RC streak.
- Related: the tick path. If `RigTuneClient.onTick` reaches TryIt through the holder, the holder resolves on the first tick of every launch (render thread), which makes it lazy in name only. Also, `V05Services.resolvedCount()` read by FootprintStats class-loads a v0.5 class during init (X4.1 says "or class-loaded").

**Fix (contracts commit).**
1. Define the flag as "resolved on the render thread during preLaunch/init/the CLIENT_STARTED handler". V05Services sets a static in FootprintStats when a getter runs on the render thread inside those windows (FootprintStats never references V05Services). Resolution on a worker counts in `workerCpuMs5s` instead, which is the render-thread-free cost X4 cares about.
2. Or give WS-K a `V05Services.afterStart(Consumer<V05Services>)` queue that FootprintStats drains only after the handler's `finally`. Owners must use it instead of submitting directly from `start()`.
3. Add a unit test for the flag's semantics: a worker resolving during the window doesn't trip it, the render thread does.
4. Put 4h's start-time compare on the same mechanism, owned by whoever owns 4h (PLAN-16).
5. The tick path checks a static volatile flag owned by TryItService (set by the derive when a try is open) and never touches the holder while no try is open.

## PLAN-2 (HIGH): LAN discovery can't work inside ws-ci's loopback-only namespace

**Where.** WS-E owns LanGuestGameTest (PLAN:141); ws-ci owns `tools/ci/*` (PLAN:185, "after ws-ci: WS-E only if the e2e wiring needs it"); SPEC 1a (the game-test step under `unshare --net`) and 3d / AC3d.1 (every leg).

**Problem.**
- `feat/v05-ci:tools/ci/offline.sh` runs the game under `unshare --net` and brings up only loopback (`ip link set lo up`).
- LanGuestGameTest uses vanilla's `LanServerPinger`, which sends to the multicast group 224.0.2.60:4445, and `LanServerDetection`'s `MulticastSocket(4445).joinGroup` (verification-gaps.md:265-266).
- Linux's `lo` has no MULTICAST flag by default and the new namespace has no route for 224.0.0.0/4, so the join and the send should both fail (UNVERIFIED here; a one-step probe settles it).
- The research's proof ran on the runner's normal network ("received from 10.1.0.15", verification-gaps.md:283-284), not inside the namespace that ws-ci has since added.
- Result: AC3d.1 is red on all 3 legs, or WS-E is tempted to run that class outside the namespace, which would weaken 1a. The fix lives in a file WS-E may not edit.

**Fix.**
1. ws-ci (still unmerged) adds to offline.sh, inside the namespace: `ip link set lo multicast on && ip route add 224.0.0.0/4 dev lo`. This is still loopback-only, so no new outside network.
2. Add a CI probe to ws-ci's proofs: a tiny Java or Python multicast send and receive under offline.sh.
3. If ws-ci can't take it before merging, name WS-E as the owner of that one line in the Ownership table.

## PLAN-3 (HIGH): the game-test legs will outgrow the 15-min step timeout, and the split has no owner

**Where.** SPEC 1e ("step timeout 15 min, job 25 min") and 1g ("if the median game-test step exceeds 12 minutes after P1 lands, the classes are split over two JVMs per leg"); PLAN Ownership (build.yml → ws-ci then WS-E for the e2e job only; build.gradle and gradle.properties → ws-ci; `tools/gametest_matrix.py` unlisted, so frozen).

**Problem.**
- Today the game-test step takes 456-496 s median and up to 699 s; the slowest green step was 11.7 min (ci-robustness.md:494-509). So there are about 3 minutes of headroom under 15 min on the worst leg.
- v0.5 registers 7 new classes (contracts item 14): TryItGameTest (7 blocks, blocks 1-4 each a before/after benchmark pair; BenchmarkGameTest's runs alone take 150-190 s), LanGuestGameTest (a dedicated server, a restart on another port, then `publishServer`), ServerProfilesGameTest (a dedicated server, three connections), FirstApply, StutterFix, BatteryFlow and LauncherManaged. Awareness and A11y also gain blocks.
- A conservative +6 to +10 min per leg puts the median past 12 min and the worst leg past 15 min during Wave B.
- The two-JVM split needs build.gradle (a per-class skip property), build.yml and gametest_matrix.py (a part dimension). Nobody owns those after ws-ci merges, so every branch goes red "for time", and the RC streak with it.

**Fix.**
1. ws-ci builds the split now, dormant: a `-Drigtune.gametest.only=`/`skip=` class filter, a `part` column in gametest_matrix.py (one part today), and 1e's apply-lock wait between parts.
2. Name an owner (WS-E, or a coordinator follow-up) who flips it to two parts when a merge's median passes 12 min.
3. Each leg logs per-class wall time, so the split is chosen from data.
4. FirstApplyGameTest stays first in part 1 (AC8.16's fresh-run-dir check).
5. Add to the P1 cut order: before cutting a feature for CI time, move its long blocks (TryIt blocks 3-4) to the release tier.

## PLAN-4 (HIGH): RigTuneSettingsScreen has no room for the two new rows at 640×480@2

**Where.** Hotspots "RigTuneSettingsScreen: WS-P (battery-offer row), WS-L1 (mod-files row) | one method each; rows appended in that order" (PLAN:262); SPEC X12 (fits at 640×480@2, "a layout check (every widget inside the screen, no overlaps)"); AC2P.2, AC4e.1/AC4e.2.

**Problem.**
- The screen is hand-laid-out: `TOP = 36`, `ROW = 20`, `GAP = 4` (`RigTuneSettingsScreen.java:23-25`), with 7 unconditional rows (network, rules, Modrinth, startup toast, goal, scene, stutter monitor, :46-96). They end at y = 204 on a 320×240 scaled screen. The Done button sits at `height - 28` = 212 (:98).
- PF-2's row alone would span 204-224 and overlap Done. WS-L1's row (LAUNCHER/PENDING or opted in; LauncherManagedGameTest and A11y show it) makes it 228-248, off-screen.
- The note line already disappears at this size (:150).
- Satisfying X12 needs a relayout, a scrolling RowList or a second column. Two parallel workstreams would each have to do it, or the first one to merge does it and the second rebases its row onto a restructured screen. Neither estimate includes it.

**Fix.**
1. Put the relayout in the contracts commit: RigTuneSettingsScreen becomes a RowList of rows (each a RowFocus Tab stop), with two empty placeholder methods `batteryOfferRow(...)` (WS-P) and `modFilesRow(...)` (WS-L1). Their call lines exist already and each returns no row until filled.
2. The A11y walk and the three-size layout check come with it.
3. Alternatively, WS-P does the relayout first as a small early merge and WS-L1 adds its row after. Either way, write it into the Hotspots row.

## PLAN-5 (MEDIUM): RealController's shared methods are denser than "one-line calls" allows

**Where.** Hotspots RealController row (PLAN:259); SPEC C7.

**Problem.**
- **`rebuild()`** (`RealController.java:318-352`):
  - WS-S2 wraps `ServerCap.apply(...)` on :335.
  - WS-L1 inserts LauncherModAdvice into the ternary at :347-348.
  - WS-H's RW-3 "one call next to dropQueuedUpdates" (:334) must also report its drops ("one status toast"). Today that goes through the `Rebuilt` record (:354) and `droppedQueuedUpdates` (:343-345, :378-382), so it needs a new record field and a status branch: 5-10 lines touching the same lines as WS-S2 and WS-L1. Three branches will conflict there and the rule "never restructure" can't hold for RW-3.
- **`apply(selected, entryId)`** (:422-499):
  - WS-L1's guard goes at the top.
  - WS-F's `applied()` goes before `return join(parts)` (:498).
  - 4h's "Apply status adds: If the Modrinth App syncs game settings…" (SPEC 4h, AC4h.4) must go into the same `parts` block (:486-497). No workstream owns it: the PLAN gives WS-L1 only Preview's fan-out line.
- **`start()`** (:189-193): WS-F's submission, and 4h's start-time compare (unowned; see PLAN-1).

**Fix.** WS-K lands identity seams in the future owners' new classes, called once from RealController. Owners then fill only their own files:
- `LauncherModAdvice.apply(report, policy)` returning the report unchanged;
- `FixHold.apply(report, hold)`;
- `StaleGroups.drop(staging, scanned)` returning an empty list, plus a `Rebuilt.stale` field and its status branch (WS-H);
- `ApplyNotes.extra(parts, controller)` for 4h's line (owner per PLAN-16);
- `V05Services.afterStart(...)` (PLAN-1).

List the owner of the Apply-status fan-out line in the Hotspots table.

## PLAN-6 (MEDIUM): call-site collisions that "each in its own helper method" doesn't prevent

**Where.** Hotspots RigTuneClient and LangCheckTest rows (PLAN:260, :245).

**Problem.**
- **RigTuneClient.**
  - WS-P2 (JOIN/DISCONNECT registration), WS-T (CLIENT_STARTED registration) and WS-L2 (CLIENT_STOPPING snapshot inside the lambda at :84-89) all add call lines into `onInitializeClient` between :82 and :94. The helper methods are separate, but the call lines are adjacent, and git treats adjacent changes as a conflict.
  - WS-T's "one title toast per launch in READY, RETRYING or NOT_APPLIED after a restart" (SPEC 6 UI) is a title-screen toast. It belongs in `onTick`/`showNotices` (:173-248), where WS-L2 changes the helper toasts (:212-248) and WS-W changes :192. The PLAN doesn't list it.
- **LangCheckTest.** Families are registered by hand in `families()` (`LangCheckTest.java:546-588`). A key built from a value must be registered, or `everyEnUsKeyIsUsed` (:607-611) fails. Several workstreams build such keys:
  - WS-L1's `rigtune.launcher.mod_steps.<launcher>.{add,update,disable}`;
  - WS-L2's per-launcher repair steps;
  - WS-T's stages, verdicts and NOT_COMPARABLE causes;
  - likely WS-P2's six This-server states.

  The PLAN lets only WS-S2 add a family line (PLAN:245) and freezes the rest.

**Fix.**
1. WS-K pre-creates, like the A11y skeletons, one empty per-owner method **and its call line** in RigTuneClient: `registerServerProfiles(real)` (WS-P2), `registerTryIt(real)` and `tryItTitleToast(mc)` (WS-T), `stoppingSnapshot(real)` (4h owner), `helperToasts(...)` (WS-L2).
2. In LangCheckTest, WS-K adds `v05Families(out)` with one commented line per owner (WS-L1, WS-L2, WS-S2, WS-T, WS-P2, WS-W2, WS-F), each followed by a blank line.
3. Update the Hotspots table to match.

## PLAN-7 (MEDIUM): WS-K can't land lang "blocks" without landing unused keys

**Where.** Contracts item 12 (PLAN:169); the Global Constraints "UI text … inside YOUR prefix block (created by the contracts commit)".

**Problem.**
- en_us.json is grouped by feature, not globally sorted. Prefixes recur (`rigtune.screen.*`, `rigtune.status.*`, `rigtune.header.*` appear in several places), and existing blocks aren't alphabetical (`rigtune.status.*` at :378-392).
- A "block" therefore only exists as keys. LangCheckTest fails any key that no code writes (`everyEnUsKeyIsUsed`, :607-611). Skeleton notice sources return null and use no text.
- So item 12 as written either fails CI, or makes WS-K invent English for ~15 blocks and reference it from skeleton code. That is wording the owners should write during TDD (WordingTest and X3 apply to it).

**Fix.**
1. ws-k.md names an **anchor**, an existing key after which each new block starts (for example `rigtune.profile.servers*` after `rigtune.profile.unnamed`, as C5 already says).
2. WS-K lands only the keys its own code uses (`rigtune.tryit.refused.running` for Busy).
3. "Alphabetical inside the block" applies to new blocks. Owners of existing blocks insert next to related keys.
4. Two owners must never share an anchor. Check that `rigtune.settings.battery_offer*` (WS-P) and `rigtune.settings.mod_files*` (WS-L1) have at least one unchanged line between them, `rigtune.settings.goal` at :56.

## PLAN-8 (MEDIUM): L8 needs WS-P's ProfileService, which the PLAN doesn't give WS-H

**Where.** WS-H section and "Not owned" (PLAN:121-122); Ownership "`client/profile/ProfileService`, `core/profile/*` | WS-P, then WS-P2" (PLAN:236); SPEC 2 intro and C7 (`core/profile/ProfileStore.java | PF-5, L8`).

**Problem.**
- Profile labels live in profiles.json `switches` keyed by entry id. On every switch, `ProfileService.recordSwitch(..., journalIds())` prunes labels whose ids the journal no longer has (`ProfileService.java:346, :597-605`; `ProfileStore.java:283-306`).
- After a fold, the folded switch ids are gone from the journal, so the next switch deletes exactly the labels L8 must resolve through `foldedEntryIds`. AC2H.3 then passes in a unit test of HistoryModel but loses the label in the real flow.
- The fix touches `journalIds()` (WS-P's file) or `ProfileStore.prune` (WS-P's file). The SPEC flagged the file as shared; the PLAN silently dropped it.
- lo's L8 row also lists ProfileStore (v04-leftovers.md:27).

**Fix.**
1. Add a Hotspots row "ProfileService.journalIds() (WS-H: include every baseline's `foldedEntryIds`)", or have WS-K add a `Journal.liveAndFoldedIds()` accessor now and change `journalIds()` to call it (one line), so WS-H fills only Journal.
2. Add a test: switch, fold past `MAX_ENTRIES`, switch again, and the first label still resolves.

## PLAN-9 (MEDIUM): AC2R.2's grep test fails on a WS-T file

**Where.** WS-W owns L6 (PLAN:130); Ownership gives `client/ui/BenchmarkMenuScreen` to WS-T (PLAN:241); SPEC AC2R.2 ("a unit test over `src/client` finds no `settings.save(` call outside `SettingsSaver`") and X8.

**Problem.** Besides StartupNotices (WS-W's), `BenchmarkMenuScreen.java:69-71` saves `settings.json` raw on the render thread when the scene changes. WS-W's grep test fails until that line changes. The file belongs to WS-T, whose client part is Wave B. This is also an X8 violation that no item lists.

**Fix.** Give that one line (→ `SettingsSaver.shared().save(...)`) to WS-W in the Hotspots table, or to WS-K in the contracts commit. Tell WS-T it has already changed.

## PLAN-10 (MEDIUM): RW-6's detection needs a sampler change in WS-S's files

**Where.** WS-B RW-6 "detect and name" (PLAN:112); SPEC 2B RW-6 ("DH world-generation thread CPU in the benchmark capture's sampler (thread names starting `DH-World Gen`) sets `Context.dhGenerating`"); contracts item 11 (only `benchmarkStepExcluded`).

**Problem.**
- The sampler classifies every `DH-*` thread into one bucket (`ThreadSampler.java:108`: `name.startsWith("DH-") ? StutterRings.S_DH : S_OTHER`).
- Telling world generation apart from DH's rendering and LOD threads needs a new bucket in ThreadSampler/StutterRings, and a way to read it from the benchmark capture's report. Those are WS-S's files (PLAN:93).
- So WS-B depends on WS-S inside Wave A, and neither section says so. The existing `dh` tag can't stand in: rendering-only DH work would set `dhGenerating` and exclude runs from the trend for the wrong reason.

**Fix.**
1. Add a seam to the contracts: `StutterHooks.benchmarkDhGenCpuMs()` returning `@Nullable Long` (a stub returning null; WS-S implements, WS-B reads). Write the new bucket into WS-S's section.
2. Or move RW-6's detection into WS-S and have WS-B only name it on the result screen.
3. Name AC2B.4/AC2B.5's closing owner (PLAN-12).

## PLAN-11 (MEDIUM): the real-world tests and fixtures straddle WS-L1 and WS-L2

**Where.** WS-L2 owns `RealWorldFixTest`, `RealWorld20260927Test` and `src/test/resources/realworld/` (PLAN:88); WS-L1 owns UndoPlanner/RW-14 (PLAN:82) and merges first (PLAN:84); SPEC AC4c.2 ("`RealWorld20260927Test` becomes a regression test"), AC4c.3 (rw's variant D).

**Problem.**
- `scratchpad/realworld/RealWorld20260927Test.java` holds the RW-1 helper cases (ApplyExecutor, WS-L2) **and** the RW-2/RW-14 Undo cases: `undoOnTheCopiedInstance`, `hadTheAppDisabledTheOldJarUndoWouldSwapDhBack`, `anAdditionInstalledThroughTheAppIsDisabledByUndo`. Those are WS-L1's UndoPlanner ACs.
- It reads the live scratch copy (`SCRATCH.resolve("instance-copy")`, :33-34). That data becomes the anonymised `realworld/` resources, which WS-L2 owns.
- WS-L1 merges first, so it either can't close AC4c.2/AC4c.3 or has to create WS-L2's resources itself. That means two anonymisations, or an ownership conflict.

**Fix.**
1. The anonymised `src/test/resources/realworld/` data (and its README rule, contracts item 15) lands with WS-K, or with WS-L1 as its first task.
2. Split the test: `RealWorldUndoTest` (WS-L1: RW-2, RW-14, variant D) and `RealWorldFixTest` plus the helper cases (WS-L2).

## PLAN-12 (MEDIUM): hidden ordering inside Wave A leaves ACs with no closing owner

**Where.** Depends-on lines of WS-L1, WS-F, WS-B, WS-S and WS-E; contracts items 2 and 7.

**Problem.**
- **AC4b.6** (WS-L1): "never for a NEW player … game test with seeded files". This needs the real `FirstRunService.load()` (WS-F). WS-L1 "reads FirstRunService.status() (stub until WS-F merges)", but `status()` and its enum (UNKNOWN/NEW/RETURNING) aren't in the contracts list (items 2 and 7 give the skeleton only a constructor).
- **AC8.14** (WS-F) needs WS-L1's real policy. The two workstreams each need the other's real code for one AC.
- **AC2B.9** needs WS-B's call and WS-S's seam implementation. It can only pass once both have merged.
- **Every "compat040 reads …/applies …" AC** is implemented in WS-E's `Compat040.java`: AC2S.4, AC2S.13, AC2B.2, AC2H.3, AC4d.3 (the released 0.4.0 helper applying a held group, which needs a mods-folder fixture), AC4e.3, AC4h.3, AC5.2, AC5.13, AC6.11, AC7.14, AC8.4, AC9.4. WS-E is one agent tracking about 13 other workstreams' expectations.

**Fix.**
1. Contracts: `FirstRun.Status` enum plus `FirstRunService.status()` returning UNKNOWN.
2. For each cross-AC, name the closing owner (the later of the two to merge) in that workstream's section. Examples: WS-F closes AC4b.6's game test; the later of WS-B and WS-S closes AC2B.9.
3. Make Compat040 data-driven. Each `v050-written/<set>/expect.json`, owned by the set's workstream, lists the 0.4.0 class to run and the expected outcome (state OK, entry count, "no `.bad`", "plans Undo this on entry X", "helper applies group G"). WS-E's Java only interprets it.

## PLAN-13 (MEDIUM): game-test controller wrappers block the A11y walks and hide the new defaults

**Where.** Contracts items 6 and 14; Ownership "RigTuneController, StubController | WS-K | frozen after" (PLAN:194); "A11y, Awareness, LauncherManaged → per skeleton method … only your method's body" (PLAN:248, :278).

**Problem.**
- The A11y walks for TryItScreen, ServerProfilesScreen ("A11yGameTest with a canned view", AC7.11), FirstApplyScreen (modFiles LAUNCHER/PENDING, AC8.12/AC8.14) and StutterScreen's fix rows need canned `tryIt()`, `serverProfiles()`, `modFiles()` and `stutter()` data.
- A11yGameTest routes everything through one private shared wrapper, `A11yController implements RigTuneController` (`A11yGameTest.java:550`). Owners may edit "only their method's body".
- Every other wrapper also implements the whole interface by hand: JvmGameTest:298, LauncherGameTest:230, PreviewGameTest:508, ProfilesGameTest:328, UiGameTest:604. These wrappers wrap the real controller but won't forward the v0.5 **default** methods, so a wrapper around RealController silently answers `modFiles()` RIGTUNE, `firstApplyPending()` false and `downloading()` false.

**Fix.**
1. WS-K adds an abstract `ForwardingController` in `src/gametest` that forwards every method, v0.5 defaults included, to a delegate. Convert A11yController to it.
2. Each owner subclasses it anonymously inside its own skeleton method.
3. Pass the skeleton methods one context record (context, stub, real controller, configDir, size helpers), so owners never need to change a call line.

## PLAN-14 (MEDIUM): nobody budgets the footprint headroom that several workstreams spend

**Where.** Global Constraints X4 (PLAN:19); SPEC X4.4, AC2S.13 (RW-11's per-tick check), AC6.12, AC2L.2/AC9.5.

**Problem.**
- **`workerCpuMs5s`.** Its ceiling is 300 and can't move. Max observed is 224.9 (ci-robustness.md:362) and 186.72 in the v0.4 calibration (docs/v0.4/verification/footprint/README.md:93).
  - v0.5 adds work inside the 5 s window: WS-F's `load()`, WS-T's derive, 4h's compare (a key set from history.json plus options.txt), class loading of V05Services and the services it resolves, and ModFilesService's `.index/` listing.
  - FirstApplyGameTest, now first, opens RigTuneScreen and applies early in the measured JVM.
  - The fake Modrinth frees some headroom (TLS gone), but no workstream owns the sum, so the last one to merge pays.
- **`tickHookOnVsReference`.** It gates "the monitor-on END_CLIENT_TICK work" against a fixed reference with a limit of 1.95; the 1× ratios reach 1.577 on the worst runner model (budgets file on `feat/v05-ci`). That leaves about 23 % headroom.
  - RW-11's per-tick settings check (a P0 item) and `TryItService.tick` both add work to that path. A reflective Iris or DH read per tick could double it.
  - The file is ws-ci's and frozen, and PROGRESS's Lessons record that the permission classifier refuses loosening CI gates. A legitimate P0 fix could therefore end with no path to green.
- **ToolsScreen.** It already clips its startup detail to the room above Done: at 640×480@2 that is about 4 lines (`ToolsScreen.java:40-75`, `room`). 2L adds roughly 5-7 wrapped lines (Windows-only, always on for this PC) and C18 adds 2. AC2L.2/AC9.5 require every line to be a Tab stop, so ToolsScreen needs a RowList relayout that nobody has sized.

**Fix.**
1. WS-K records the post-ws-ci per-leg baseline of `workerCpuMs5s`, `renderThreadInitCpuMs` and `tickHookOnVsReference` in ws-k.md. Each workstream reports its delta in its design doc (from the leg's footprint JSON).
2. RW-11 compares cached primitives: it polls Iris and DH state only on `settingsChanged()`, on resource reload, or at most once per second. TryIt's tick early-returns on a static volatile flag (PLAN-1).
3. Agree now, under the user's existing approval of a measurement redesign, how the reference ratio is re-baselined if a legitimate tick addition lands. Record it in the budgets' `about` text, so it isn't discovered as a "loosening" at merge time.
4. Size the ToolsScreen relayout into WS-W (WS-W2 inherits it).

## PLAN-15 (MEDIUM): "same owner continues" contradicts "early core in parallel"

**Where.** WS-S "Then: the same owner continues as WS-S2" (PLAN:94); WS-P (PLAN:104); WS-W (PLAN:130); Phases diagram "early pure-core starts of the Wave B features" (PLAN:53-54); critical path (PLAN:305).

**Problem.**
- If WS-S and WS-S2 are one agent, WS-S2's early core can't run alongside WS-S. The C20 chain becomes WS-S (2.5, or about 3.5 per PLAN-16) + WS-S2 (5.75) ≈ 8.3-9.3 agent-days + AC5.14's calibration loop. That makes it the real critical path, longer than WS-B → WS-T.
- If they are two agents, the phrase "one owner" is wrong and a handover is needed: branch `feat/v05-stutter-fixes` must merge `origin/feat/v0.5.0` after WS-S merges, and the S2 agent takes StutterService from then on. The same applies to P/P2 and W/W2.

**Fix.** Write it down: the S2, P2 and W2 agents are separate agents who start their early cores in Wave A and become the sole owners of the predecessor's hotspot files once the predecessor merges. That ownership is sequential, never concurrent. Recompute the critical path with the corrected sizes.

## PLAN-16 (MEDIUM): several workstreams are under-sized; one change rebalances the P0.4 chain

**Where.** Workstream "Effort" lines.

**Evidence and estimates.**

| ws | PLAN | likely | why |
|---|---|---|---|
| WS-B | ~4 | 5-6 | L3 alone is "L (SPEC's own 2-3 day estimate for the table)" (v04-leftovers.md:155). On top: BH-1, BH-2, RW-5's re-measure inside the deadline, RW-6 (detect, plus javap and an optional pause), RW-7, RW-8, RW-9, RW-15, two game tests and an A11y walk. It is on the critical path to WS-T's client part |
| WS-L2 | ~3 | 4-4.5 | RW-1, crash replay, the hold property, the held notice and its two actions, LauncherRepair for 5 launchers, the repair notice with Copy list, **4h** (exit snapshot, start compare, notice, re-apply, Modrinth App steps), the forcing writer, the toasts, the anonymised fixtures, 2 game-test methods and an A11y walk |
| WS-L1 | ~3 | ~4 | AC4j.1's DB-free models of the Modrinth App and GDLauncher, steps for 5 launchers × 3 kinds, the Preview, share-report and settings rows, MOD_FILES_NEWS, the UndoPlanner policy, RW-14 |
| WS-S | ~2.5 | ~3.5 | SD-1's allocation-free whole-capture counters, RW-11 (event + tick + fields + compat), NEW-1's measurement (PLAN-19), the RW-6/RW-15 seams |
| WS-E | ~5 | 6+ | 8 "M" items in vg's table plus release.yml's restructure; PLAN-2; the release-tier dry runs (PLAN-17) |

**Fix.**
1. Move **4h** (OutsideChanges, OutsideChangesNoticeSource, the CLIENT_STOPPING snapshot, AwarenessGameTest's `settingsChangedOutside` method, `rigtune.outside.*`) from WS-L2 to WS-W. WS-W is sized ~2, already owns awareness.json's service, AwarenessGameTest and NoticeScreen, and 4h is an awareness feature. This shortens WS-L2, which can only merge after WS-L1.
2. Make WS-B's "part 1" an explicit milestone with its own AC subset: BenchmarkController's RW-5, RW-8 and RW-15 plus the context fields, merged as soon as green. WS-T's client part starts from part 1. L3's table and chart rows come last.

## PLAN-17 (MEDIUM): CI capacity peaks exactly during the streak

**Where.** Workstream protocol step 2 ("Commit and push [the TDD plan] before any code"); PLAN:61, :286, :302; the coordinator's streak decision.

**Problem.**
- build.yml's concurrency group is per ref (`build-${{ github.ref }}`, cancel-in-progress, `feat/v05-ci:.github/workflows/build.yml:16-18`), so branches don't cancel each other, but they all draw on the account's concurrent-job cap. A run is 8 jobs today and 10 with WS-E's two E2E push jobs. The cap is 20 on GitHub's Free plan (from GitHub's documentation, not checked for this account).
- Under the coordinator's decision, the first streak starts when WS-K merges, which is also when ~12 agents push their docs-only design commits. That is ~12 full runs on unchanged code queued ahead of or between the streak's dispatches.
- WS-E will need release-tier dry runs before the release PR (AC3a.4 "first attempt" is its riskiest point). About 23 jobs of up to 20 min each would occupy every runner, and nothing schedules them or sets `max-parallel`.
- 5 sequential runs of about 12-15 min, plus queueing, make "≈1-2 h" (PLAN:302) optimistic: plan for 3-4 h of blocked merges.

**Fix.**
1. Docs-only pushes carry GitHub's `[skip ci]` commit-message marker (verify once on a scratch push), or the design doc is pushed together with the first code task.
2. e2e.yml gets `max-parallel` (for example 6). Release-tier dispatches run only when the coordinator schedules them: never during a streak, once after Wave B on `feat/v0.5.0`.
3. Agents hold non-urgent pushes while a streak runs.
4. Update the critical-path estimate.

## PLAN-18 (MEDIUM): local build contention on one PC

**Where.** Global Constraints "`./gradlew build` must pass for BOTH MC versions"; the workstream protocol.

**Problem.**
- The PC has 31 GB of RAM and 16 threads (checked). `gradle.properties` has `org.gradle.jvmargs=-Xmx2G` and `parallel=true`. Each worktree's full build compiles both nodes and runs about 1850 tests twice in forked test JVMs.
- Twelve concurrent builds need about 12 daemons plus their test JVMs, beyond the RAM, and `--stop` is forbidden.
- The unit suite contains timing gates: FrameHookBudgetTest's ns limits and ThreadSamplerTest. On a saturated PC they can go red for reasons outside the code, and agents will chase them.

**Fix.**
1. A build-slot semaphore like the game-test lock: `mkdir C:/Dev/Worktrees/.build-slot-{1..4}`, released in the same command.
2. While iterating, agents run targeted tests (`./gradlew :26.2:test --tests '<their classes>'`). The full build runs once per push, in a slot.
3. A local red timing gate with a green CI is noted in the design doc, not debugged.
4. Stagger the Wave A start by a few minutes per agent.

## PLAN-19 (MEDIUM): Phase 5 holds runs that decide code, and runs serially at the very end

**Where.** WS-S "NEW-1's real measurement is Phase 5" (PLAN:93); WS-B "RW-6's pause is decided by javap" (PLAN:113); Phase 5 list (PLAN:292-298); PLAN:306.

**Problem.**
- NEW-1's decision rule (SPEC 2S, Open question 5) is "measure first, then either the GcKind fix or fail closed". Putting the 10-minute Shenandoah run in Phase 5 means WS-S merges without knowing which code to write.
- SPEC RW-6 requires "javap **and one real DH run**" before the pause ships, but the PLAN decides on javap alone. A failing Phase 5 run would then mean new restore-marker code after Wave A.
- AC5.14 (C20), AC6.16 (C09) and AC9.8 (C18) can each send constants back: thresholds via a WS-R regeneration, `MIN_CV`, the floor and `MIN_RUNS`.
- The real-run list is long and serial under one lock: NEW-1, the RW-6 DH run, 2L, the DH server note, the Windows seeded E2E and helper-kill, P0.4's two dev-instance runs, C20 calibration (≥300 s play before, 5-20 min after, a restart, maybe a repeat), C09 A/A (5 pairs) + NOW + RESTART, C16's JOIN, C02 at 3 sizes + Narrator + the 26.3 half with retries, C18's 6+ launches. Estimated 6-8 h of lock time, and it starts only after every workstream has merged, followed by fixes and review rounds.
- The watchdog's 12-minute lock alarm (PLAN:288) will fire on each legitimate long hold.

**Fix.**
1. Move NEW-1's run into WS-S as its first task: one lock slot, about 15 min.
2. For RW-6, either one lock slot in WS-B or ship detect-and-name only and record the pause as not shipped. Never ship a pause verified by javap alone.
3. Run Phase 5 on a rolling basis: a standing P5 agent runs each feature's real check right after that feature merges (C20 calibration immediately after WS-S2 merges), so constant changes land before the review rounds.
4. Put `expected_minutes` in the lock's `owner.txt` and have the watchdog honour it.

## PLAN-20 (LOW): v050-written fixture gaps

**Where.** Global Constraints "Fixtures" (PLAN:26); contracts item 15 (PLAN:172); SPEC 3b, AC5.13, AC6.11, AC7.14.

**Problem.**
- There is no `ws-p` set, although PF-5 writes profiles.json with a DH radius up to 4096. SPEC's state table says "0.4.0 drops a radius above 512 on read … no crash", and only a v0.5-written profiles.json can show it. v0.4 had `v040-written/ws-p`.
- The SPEC's ACs name the sets `stutter-fixes/`, `tryit/` and `server-profiles/`; the PLAN names them `ws-s2`, `ws-t` and `ws-p2`.
- awareness.json comes from three sets (ws-l2 or 4h's owner, ws-w2, ws-f) and benchmarks.json from two (ws-b, ws-t), but the README defines a merge only for history.json. The downgrade E2E needs one file each.
- v0.4 used two regeneration mechanisms: `RIGTUNE_REGENERATE_FIXTURES=1` (StutterWrittenFixtureTest:78) and "write to build/…, compare, copy" (V040WrittenWsWTest:24).

**Fix.**
1. Add `ws-p` (profiles.json with a 1024 radius and `battery.previousProfile` set by PF-1).
2. Use one naming scheme in both the SPEC and the PLAN.
3. The README defines per-file merge rules: awareness.json as a union of `dismissed` and the arrays; benchmarks.json concatenated by id.
4. One regeneration switch that the coordinator runs in Phase 5.

## PLAN-21 (LOW): the PLAN text contradicts the streak decision; the streak's job list isn't per-SHA

**Where.** PLAN:61 ("WS-K merges after the streak") and :302; SPEC 1g's `ci_streak.py` rules ("…the E2E push jobs, compat040…").

**Problem.**
- The coordinator's decision puts the first streak on the ws-ci + WS-K SHA, but the PLAN still has WS-K merging after the streak.
- The P0.1 SHA has no E2E push jobs and no compat040 (WS-E's). If `ci_streak.py` hard-codes them, no run qualifies there.

**Fix.**
1. Amend both lines.
2. `ci_streak.py` takes the required job set from the run's own job list plus a minimum set (the ws-ci jobs); the RC streak adds WS-E's jobs.

## PLAN-22 (LOW): WS-E's cherry-picks aren't "tools/e2e only"

**Where.** Phase 3 detail "WS-E starts early on `tools/e2e/*` only (the two cherry-picks…)" (PLAN:66).

**Problem.**
- `25243b63` also changes `.github/workflows/build.yml`: it downloads four more old jars and adds their sha256s. That touches ws-ci's file, and ws-ci's own build.yml already rewrites that block with an e2e-old cache.
- `1f4b6b3a` edits `r-verify-experiment.yml`, which must not come along at all (modify/delete conflict).

**Fix.** `git cherry-pick -n` both commits, then restore build.yml, remove the experiment workflow, and commit. The old-jar list and cache key move into WS-E's post-ws-ci build.yml edit.

## PLAN-23 (LOW): frozen or unlisted files that some AC needs

**Where.** Ownership table (PLAN:177-252).

**Problems.**
- `UndoPlanner.State.modFiles()` (4c) is implemented by `client/undo/GameState.java:21`, constructed at `RealController.java:162-164`. The file is unlisted, so frozen. Give it to WS-L1.
- AC3f.8's "unit test with a fixture manifest covers latest.snapshot == latest.release" is about logic that exists only as inline bash/jq in frozen `snapshot-canary.yml:64-78`. Either WS-E may extract it into a script (one workflow edit), or the test runs the embedded step with a fake curl. Decide which.
- AC1c.3's "no `ubuntu-latest` in the workflow files" meets frozen `snapshot-canary.yml:46` and `update-rules.yml:18` (and `release.yml:13`, WS-E's). Scope the test to build.yml and e2e.yml, or unfreeze those two lines for ws-ci.
- PreviewScreen `populate()` (:231-251) gets WS-L1's count and fan-out lines, while WS-H's L5 unit test ("shown whenever Preview lists at least one download, and never otherwise") likely needs a text function extracted from the same method. SPEC C7 lists L5 for PreviewScreen; the PLAN's Hotspots row omits WS-H.
- `tools/tests/**` is listed WS-R-only, but ws-ci (Python tests over build.yml, `ci_streak.py`) and WS-E (the canary and release.yml tests) create files there. Say "new test files belong to their creator".
- `docs/v0.5/verification/README.md` is linked from AC1g.2 and edited by every workstream (the v0.4 X-L1 lesson). Give it one section per area, created by WS-K.

## PLAN-24 (LOW): finish-line stalls and evidence checking

**Where.** Workstream protocol steps 5-6 (PLAN:41-42); the coordinator's merge check (PLAN:287).

**Problem.**
- Code-review findings "go to the coordinator, who forwards them with decisions". With about 12 agents finishing close together, agents wait on the coordinator.
- The coordinator then checks screenshots for about 14 workstreams × 3 legs.
- AC2A.2 ("differ from the pre-change CI screenshots only by the focus frame") has no defined "before". The v0.4 X-L2 lesson was to fix a baseline run.

**Fix.**
1. Agents fix HIGH and MEDIUM review findings immediately and report; the coordinator only overrides.
2. Each design doc lists the run id and the artifact names it looked at, with what it checked.
3. AC2A.2's baseline is the CI screenshots of the run on the WS-K merge SHA, compared by crop.

## PLAN-25 (LOW): small contract details

**Problems.**
- **V05Services at init.** "Held by RealController" suggests `new V05Services(this)` in the constructor, and the counter is read by FootprintStats. Both class-load a v0.5 class during init. Harmless in cost, but X4.1 forbids it literally. See PLAN-1's inverted flag.
- **Skeleton signatures.** The A11y, Awareness and LauncherManaged skeleton methods need a fixed, rich signature (PLAN-13's context record).
- **BenchmarkTrend statics.** `BenchmarkTrend.noiseFloorPercent`, `median` and `mad` are reused by WS-T (TryIt's trend floor) and WS-W2 (StartupTrend) while WS-B owns and changes BenchmarkTrend. Freeze those three signatures in the Hotspots table.
- **Notice details.** NoticeScreen shows a notice's detail only as a hover tooltip (`NoticeScreen.java:87-89, :137-139`). LAUNCHER_REPAIR's step list with file names, 4h's changed-settings list and C18's cause line would live there. Decide now whether P0.4's notices need inline detail rows. If so, that's a NoticeScreen change by a second owner (WS-W owns it for AW-2).
- **The pinned `v040/core/rules/` copies** compile against current `core.model` (v0.4's K-L1). Record in ws-k.md which current classes they import.

---

## Checked

- **Read.** PLAN.md (all), SPEC.md (all 815 lines incl. X1-X12, C3, C8, sections 1-10, Shared contracts, Not fixed), the user's requirements, PROGRESS.md (v0.5 section, the v0.2-v0.4 logs, Lessons), the v0.4 plan-review (all findings and the cross-cutting section), the v0.4 merge timeline (`git log --merges`: Wave A ran about 4 h wall-clock after WS-K), and the research effort tables (feature-stutter-fixes §8, feature-try-it §8, fa/sp/la headers, v04-leftovers L-table, verification-gaps summary, ci-robustness §7 and :362).
- **ws-ci's branch (`feat/v05-ci`, c2496742).** Changed files (28, incl. FakeModrinth under tools/e2e, ten game-test classes, `gametest/fabric.mod.json`), build.yml concurrency, timeouts and caches, `tools/ci/offline.sh`, footprint budgets (workerCpuMs5s 300/300, tickHookOnVsReference 1.95, rigtuneClassBytesIdle 109,296), ws-ci.md excerpts.
- **WS-E's cherry-picks.** 25243b63 and 1f4b6b3a (files touched).
- **RealController.** Constructor (:149-182), `start` (:189-193), `rescan` (:254-284), `rebuild` (:318-352), `dropQueuedUpdates` (:364-376), `apply` (:416-499), `discardPending` (:667-683); the existing `apply(selected, entryId)` and `downloading()` confirm C4's "gets @Override".
- **RigTuneClient.** Init (:70-105), CLIENT_STARTED/STOPPING (:82-89), onTick (:173-210), showNotices (:212-248), `launchHelperIfPending` (:158-170).
- **FootprintStats.** `clientStarted`, the 5 s window.
- **Notices.** NoticeCenter, NoticeSource, Notice (actions can open screens via `controller.minecraft()`), NoticePriority (6 slots today), NoticeScreen (detail as tooltip).
- **LangCheckTest.** `families()`, `everyEnUsKeyIsUsed`; en_us.json grouping (not globally sorted; neighbourhoods of the C5 prefixes).
- **WordingTest.** CORRELATION_PREFIXES; no empty-prefix check, so item 13 is safe.
- **RigTuneScreen.** Offline line inside `header()` (:307-312, fine as one branch); row launcher line via `LauncherLines.adviceLine` (:785), so WS-L1's steps need no RigTuneScreen change; `applySelected` (:561).
- **Screen layouts.** RigTuneSettingsScreen (PLAN-4); ToolsScreen (PLAN-14); PreviewScreen `populate`/footer (:154-165, :231-251); `UndoScreen(parent, controller, entryId)` exists (:68); `HistoryScreen.select(entryId)` exists (:80), so AC8.8 needs no HistoryScreen edit; HistoryScreen `describe`/`failureText` are reachable from `client/ui`.
- **Persistence and helpers.** ProfileService `journalIds`/`recordSwitch` and ProfileStore `prune` (PLAN-8); `BenchmarkMenuScreen:71` raw save (PLAN-9); `ThreadSampler:108` (PLAN-10); `GameState implements UndoPlanner.State` (PLAN-23); `Staging.unstageLocked`/`dropQueuedUpdates`/`discard` (4d's Cancel path exists); PowerWatcher `start(List<Battery>, …)` is public (3e needs no PowerWatcher change); ChangeDetector treats a blank renderer as unknown (Latent 2 needs no ChangeDetector change).
- **Game tests.** Current `src/gametest/resources/fabric.mod.json` (16 classes); RigTuneClientGameTest's first-run assumptions: `checkNotices` fakes last-apply.json late, so FirstApplyGameTest first is compatible (only the "title-toasts" screenshot's content moves); BenchmarkGameTest's `modSetHash` check is unaffected by a non-empty history; StubController is final and A11yController and five other wrappers implement the full interface.
- **Pinned copies.** v010, v020 and v030 in `src/test/java` (no v020 rules/Recommender; v030 has Recommender and ConditionEvaluator); `RecommenderGoldenReportTest` pins rules r13, so WS-R's L4 rewording can't change the golden report (no conflict with WS-L1's byte-identical check).
- **Fixtures.** v040-written sets (ws-a, ws-b, ws-f, ws-p, ws-s, ws-w) and their two regeneration styles.
- **Workflows.** `runs-on`/`java-version` across all four; snapshot-canary's inline skip logic.
- **Real-world data.** `scratchpad/realworld/RealWorld20260927Test.java` (test names, scratch-path reads) and `fix-prototype.diff` (ApplyExecutor only).
- **PC.** 31.1 GB RAM, 16 logical processors; `gradle.properties` (`-Xmx2G`, parallel).
- **Not checked (out of scope or needs running).** Multicast on `lo` inside a netns (PLAN-2 says how to probe it); the GitHub plan's job cap and `[skip ci]` (PLAN-17 marks both to verify); javap of `CrashReport.preload` on both nodes (WS-W's own UNVERIFIED); DH 3.3.2's world-gen switch (WS-B's).
