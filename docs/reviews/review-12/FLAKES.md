# Review 12: game-test race/flake hunt (worktree rigtune-review12 @ ac109a2d, read-only)

Scope: every class in src/gametest/java/io/github/chaotix345/rigtune/gametest/ plus GameTestWorlds, V05TestContext,
GameTestNet, TimedGameTests and the helper controllers. The smoke classes (ProductionSmoke, BenchmarkSmoke, DhConfigSmoke)
run only under runProductionSmoke (build.gradle:381, -Drigtune.smoke=true) and are not in build.yml, so they are out of P0.1.

Part split (GAMETEST_PARTS=2, build.yml:35; tools/gametest_matrix.py split(): classes[23k/2 : 23(k+1)/2]):
- part 1: FirstApply, RigTuneClient, Benchmark, Launcher, Undo, Ui, Report, History, Preview, Profiles, Stutter
- part 2: StutterFix, Jvm, BenchmarkHistory, TryIt, ServerLimits, LanGuest, ServerProfiles, Awareness, BatteryFlow,
  LauncherManaged, Footprint, A11y

Product facts the findings depend on:
- Probes.EXECUTOR has TWO threads (Probes.java:7). Two tasks submitted in order can run at the same time, so "the notice
  went away" never implies "the task that caused it has finished".
- StutterService's stutter.json work runs on its own ordered io chain (StutterService.java:195-197) on that pool. Every
  finished benchmark run's capture is saved there, whether or not the monitor is on (StutterService.java:439-444). StutterStore
  keeps at most MAX_SESSIONS = 5 (StutterStore.java:17).
- A minecraft.execute task queued from the render thread runs within the next client tick. A test-thread wait
  (waitTicks/waitFor) is the only time the client thread runs; a blocked test thread parks the client thread.

## Real races (6)

### FL-1 [LauncherManagedGameTest.java:570-571] The journal is read before Cancel them writes it (the CI failure in run 36394062135)
What: line 567 waits for `!hasFileOp(pending) && !Files.exists(download)`, then 570-571 read `ClientJournal.get().entries()`
right away. LauncherRepairService.cancelHeld (worker, via heldAction :191) writes in this order:
pending.json deleted or saved (:225-229), downloads retired (:230-235), and only then the journal
`updateExisting(HistoryUpdates.discard)` (:237-240). The journal update takes the 2 s journal lock and reads and writes
history.json, so it can finish several ticks after the rename the test waits for. The second wait (:572, the notice gone)
comes after the read, and it doesn't prove the write happened anyway (see FL-2).
Scenario: the worker renames the download, the test's next waitFor tick sees it, and statuses() still reads STAGED, STAGED.
Fix (a bounded wait for exactly that state; the check stays for its message):
    context.waitFor(mc -> statuses(held).equals(List.of(JournalChange.DISCARDED, JournalChange.DISCARDED)), 200);
    List<String> statuses = statuses(held);
    check(statuses.equals(...), ...);
Severity: BLOCKER (seen in CI)

### FL-2 [LauncherManagedGameTest.java:572-578] A fixed 3-tick wait assumes Cancel them's recount already ran
What: the notice's current() starts a refresh() on the SECOND pool thread as soon as pending.json's modification time
changes (LauncherRepairService.java:287-299 and 301-321). That refresh can empty `state.held()`, so the notice goes (:572)
while cancelHeld is still in the journal update, refreshNow (:247) or stagedChanged (:248). stagedChanged in turn queues
recountStaged on the render thread (RealController.java:762-767). If the Apply at :576 runs before that recount:
pendingChanges() = staged + max(0, carriedOverOps(=2, from the recount at :534) - unowned(heldOps()=[])) = 2, so the
status says "Restart Minecraft to finish applying 2 change(s)" (RealController.java:590-606), and :577 fails
("after Cancel them nothing waits").
Scenario: the notice empties on the other thread, and the three ticks run out before cancelHeld's journal write, findings
read and queued recount finish. FL-1's fix narrows this window but doesn't close it.
Fix: after FL-1's wait and the notice wait, do the recount yourself, as :534 does. It's deterministic: pending.json is
already gone (:567) and the held state is already empty (:572).
    context.runOnClient(mc -> real.stagedChanged());
    context.waitTicks(2);            // replaces waitTicks(3) at :575
Severity: WARNING

### FL-3 [StutterGameTest.java:148-151 and 171-173] The session count wait is a no-op at the 5-session cap, so latest() can be a stale session
What: waitForSessions waits for `sessions().size() >= Math.min(count, MAX_SESSIONS)` (:289-291). In part 1, and in a
one-JVM run, stutter.json already holds 5 sessions when this class starts: one per finished benchmark run
(StutterService.java:439-444). That's RigTuneClientGameTest's full run (its Esc run is cancelled and not saved) plus
BenchmarkGameTest's before, after, chunk Tune and current-world Tune. So `before` is 5, `min(before + 1, 5) == 5` is
already true, and :149 `latest()` reads the file while the Stop session's save may still be on the io chain.
StutterService.end (:289-340) analyses the whole session there (about 90 s including the AFK block, plus the fix evaluation)
before `store().add` (:324). The only margin is waitTicks(5) at :141 and the sampler wait at :145.
Scenario: the analysis takes longer than about 5 ticks on a slow runner. latest() is then BenchmarkGameTest's last
BENCHMARK capture, whose settingChanges are empty, and :150 fails. :171-173 have the same no-op wait. They pass anyway
because the Stop session is MONITOR too, so that check is weak.
Fix: wait for the exact session, as FootprintGameTest.java:463 does:
    String stopStart = StutterMonitor.session().startedAt().toString();   // before :140
    context.waitFor(mc -> new StutterStore(configDir).sessions().stream().anyMatch(r -> stopStart.equals(r.startedAt())), 400);
    StutterReport stopped = new StutterStore(configDir).sessions().stream().filter(r -> stopStart.equals(r.startedAt())).findFirst().orElseThrow();
Do the same with the second session's startedAt (take it at :164) in place of :171. (An alternative: record
StutterHooks.sessionsEnded() before each end and wait for +1; it's incremented after store.add, StutterService.java:333.)
Severity: BLOCKER (the fixed 5 sessions come from earlier classes in the part, and the window is a few ticks)

### FL-4 [StutterGameTest.java:94-104] The GC event is asserted after a fixed 80 ticks
What: System.gc() at :96, then waitTicks(40), a save and waitTicks(40), then checkCapture (:189-216) requires an explicit-GC
record in the rings. That record comes from the JDK's JMX notification thread
(GcListener.java:59 addNotificationListener; :91-102 handleNotification -> target.gc).
Scenario: the notification is delivered more than about 4 s late (a loaded runner, a stalled service thread), and :215
"a GC event with cause System.gc()" fails. That's unlikely.
Fix: move the explicit-GC scan into a predicate and use `context.waitFor(mc -> explicitGcSeen(gcCalled), 200)` before
checkCapture. Keep the 40 ticks after the save only for the save window, which is written synchronously.
Severity: NIT

### FL-5 [ServerProfilesGameTest.java:163-189] Connection 1 assumes the join lookup finished within 25 ticks
What: on JOIN, ServerProfileService.onJoin sends the lookup to the pool (ServerProfileService.java:65-76; lookup :85-101). On
connection 1 it finds no entry and does nothing, but nothing observable says it has finished. The test waits
`inTheWorld` + waitTicks(20) (:164-165), then calls `remember()` (:177), which writes the entry synchronously
(:186-200). If the lookup runs after that, `store().joined` finds the new Max FPS entry and `offers.offer(...)` creates an
offer on connection 1. `remember`'s `offers.forgot` only retires an offer that already exists. Then :189 "setting it
offers nothing now" fails, and a toast shows as well.
Scenario: both pool threads are busy at join (the join and ServerLimits rebuilds, and so on) for more than about 1.3 s.
That's unlikely.
Fix: this needs a seam. Add a completed-lookups counter to ServerProfileService (as StutterHooks.sessionsEnded does) and
use `waitFor(lookups() > before, 200)` after inTheWorld on connection 1. waitForOffer already covers connections 2-4.
Severity: WARNING

### FL-6 [BatteryFlowGameTest.java:248-254] A wall-clock bound measured through tick polling
What: `millis <= 5000` runs from just before `onBattery.set(true)` to the first client tick whose waitFor sees
the notice and the toast. The product part is bounded: the real PowerWatcher needs two 1 s polls
(Watch :383-389, period 1), then powerChanged sets the offer and queues the toast (ProfileService.java:273-297), so
about 1-2 s. The rest is how fast the harness gets to the next tick.
Scenario: the client ticks slowly (llvmpipe frames, a GC pause, first-time class loading) and the observed time goes past
5 s even though the product met the bound.
Fix: time the product side. Record the edge's System.nanoTime() in Watch's listener and the time the offer and toast
became visible, and assert edge-to-offer <= 5000 - pollPeriod. Or keep the bound but take the end time from the edge
plus the toast's tick. AC3e.1 stays covered either way.
Severity: WARNING

Summary: 2 blockers (FL-1, FL-3), 3 warnings (FL-2, FL-5, FL-6), 1 nit (FL-4). Overall: REQUEST CHANGES.

## Async state checked and found correctly waited (no change)
- TryItGameTest:360-367 and :252-264: the RESULT view is written in deriveNow before acknowledge()
  (TryItService.java:479-505), but the TryItScreen that awaitStage needs opens only through game.later(openScreen), which
  is queued after that whole io task (:596-599). So tryit.json's afterRunId and awareness.json's acknowledgement are written
  before the screen shows. Keep: waitFor(tryIt()==null) at :214/:254 means close() wrote tryit.json first
  (:378-384, close then deriveNow in one task).
- LanGuestGameTest:173-181: wasViewDistance==6 is set only after store().remember wrote the 4
  (ServerLimitsTracker.java:112-116), so onlyEntry() sees 4.
- ServerLimitsGameTest:191-196: one asynchronous write at join, waited by count. The "(was 6)" at :150 is computed
  synchronously from the previous live state (ServerLimitsTracker.java:94-96).
- StutterFixGameTest: every tracker state goes through waitForRecord (the FixStore write is on the ordered io chain).
  cleanUp's waits (dismissed, then stutter.json empty after Clear) drain the chain before stutter-fixes.json is restored.
- BenchmarkGameTest:209/215: the BENCHMARK capture is waited for by source and time, and sessionsEnded (incremented after
  store.add). BenchmarkStore.add is synchronous before lastOutcome is set (BenchmarkController.java:736-746, :671).
- AwarenessGameTest:158/:568: the asynchronous fingerprint commit (AwarenessService.java:216) is waited for. :457 joins
  compareAtStart before reading. :140/:153 rely on the 0.4 block's session dismissal, so no screen commits it early.
- FootprintGameTest:463: waits for the exact session by startedAt (the pattern FL-3 should copy).
- LauncherManagedGameTest:374/:395/:592 and UiGameTest:417-419: SettingsSaver.flush() before ClientSettings.load. setOptIn
  sets the field and queues the save in the same render-thread call (ModFilesService.java:66-74), and a save of the same
  settings object serialises the current values at write time (SettingsSaver.java:33-65). UiGameTest:73 polls with a bound.
- LauncherManagedGameTest:537/:553/:589: waitForNotice polls the render-thread notice list (the async refresh is
  triggered by the predicate itself). :567: Files.move is an atomic rename (PendingActions.java:278-289), so :569's
  superseded file exists.
- ServerProfilesGameTest connections 2-4: waitForOffer covers the join lookup. Every other store write
  (remember/forget/forgetAll/forgetProfile/act) is synchronous on the render thread.
- ProfilesGameTest: powerChanged sets the offer synchronously and only queues the rescan (ProfileService.java:286-297), so
  waitTicks(3) + waitFor(report) is correct. switchProfile, ProfileStore and undo are synchronous.
- FirstApplyGameTest:82: FirstRunService.load sets the status before its awareness.json write (FirstRunService.java:78-82).
  This class never reads that key, and LauncherManagedGameTest:304 reads it about 10 classes later in part 2.
- History/FirstApply screens reloading on return set `loading` synchronously in init (HistoryScreen.java:128-132,
  FirstApplyScreen.java:281-291), so waitFor(!loading) can't pass on the old view.
- Order-dependent but deterministic, not flaky: LauncherManagedGameTest:305 reads loadedStatus NEW in part 2 and
  RETURNING in a one-JVM run (FirstApplyGameTest's forceStatusForTests); it passes both ways. In a one-JVM run,
  StutterFixGameTest:458-459 writes the 5 earlier sessions back.

## Fixed-tick waits that look safe, and why
- RigTuneClientGameTest:134-135 (5 ticks) and :149-150 (30), BenchmarkGameTest:472-473 (20), then "uncapped while
  measuring": start() uncaps synchronously and each run lasts seconds to minutes.
- RigTuneClientGameTest:210-213: showNotices writes state.json synchronously (RigTuneClient.java:236-239). The 15 ticks
  are for the screenshot, and no async ClientState save (RealController.java:268, setGoal only) is in flight in this class.
- UndoGameTest:82/:102/:116, HistoryGameTest:202, ProfilesGameTest:198/:228: undo is synchronous on the render thread
  (UndoScreen.confirm -> controller.undo). The waits are for screenshots.
- UiGameTest:443-448 waitForSettledReport (40 ticks): every network switch calls settingsChanged, whose rescan nulls the
  report and bumps the generation synchronously (RealController.java:285-288). Any report after that was built under
  the new switches, so the 40 ticks are cosmetic. LauncherGameTest:141 is the same, between two waitFor(report).
- LauncherManagedGameTest:535 (2 ticks after stagedChanged): a minecraft.execute task queued from a render-thread task runs
  within the next tick.
- StutterGameTest:89 (100 ticks) and :164 (40): the session starts synchronously (setMonitor -> tick -> startSession).
  :110 (5): SettingsWatch's END_CLIENT_TICK listener checks every tick. :141 and FootprintGameTest:457 (5): stop()
  detaches the capture synchronously, and the save isn't asserted there.
- FirstApplyGameTest:324 (10) and :358 (5): FirstApplyScreen would open synchronously in the button handler, and
  afterApply sets RETURNING synchronously.
- ServerLimitsGameTest:133/:147/:205 (3 after rebuildAndWait): the notice is built on screen init from the live limits,
  which are set synchronously.
- AwarenessGameTest:302 (Got it) and :426-428 (Keep): synchronous acknowledgements.
- A11y tab()/pressKey + waitTicks(1): key events are handled in the next tick. setHighContrast: reloadResourcePacks sets
  the overlay synchronously, so waitTick + waitFor(overlay == null) is sound.
- Negative checks, where a late event would give a false pass, never a flake: LauncherManagedGameTest:261 (20), :684 (5,
  the PENDING gate is checked each tick), TryItGameTest:623 (5), BatteryFlowGameTest:145/:270, ServerProfilesGameTest:165-167
  and :303-304, BenchmarkGameTest:435.
- V05TestContext.resize / every resize(): waitTicks(3) after resizeGui, which lays out synchronously.
- GameTestWorlds: no state assertion; the hold is capped at 30 s (HOLD_LIMIT_NANOS).
