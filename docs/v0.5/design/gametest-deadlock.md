# The client game-test deadlock on leaving a singleplayer world (v0.5, P0.1)

Run 36314730108 (26.2 OpenGL, docs-only commit 1de15adf) hung in ServerLimitsGameTest's world close; the 13-min watcher's
dump (the coordinator's copy: `scratchpad/streak/red-36314730108.clean.log`, from line ~1920) shows three threads waiting on
each other. Fix: branch `fix/v05-gametest-deadlock`.

## Root cause

Harness: fabric-client-gametest-api-v1 6.0.2+0f4f1dca9e (Fabric API 0.161.0+26.2) and the 26.3 build; `ThreadingImpl`,
`threading/MinecraftMixin`, `threading/MinecraftServerMixin` and `TestSingleplayerContextImpl` are identical at the tags
0.161.0+26.2 (6a8ed6bc) and 0.161.0+26.3 (84831249); the last change to MinecraftMixin is upstream 5844894939
(2026-07-28), so upstream still has it. Minecraft 26.2 from Loom's `minecraft-{common,clientonly}-deobf-26.2.jar` (javap).

1. `TestSingleplayerContextImpl.close()` runs `client.level.disconnect(…)` and `client.disconnect(…, false)` in a test task
   (`runOnClient`, the test phase). `MinecraftMixin#deferDisconnect` cancels the disconnect there (a singleplayer server
   exists and a task is running) and stores it; `postRunTasks` runs it right after the client enters the next tick phase.
2. `Minecraft.disconnect(Screen, boolean, boolean)` calls `IntegratedServer.halt(false)` before its busy-wait loop (where
   `MinecraftMixin#onDisconnectBusyWait` lets the client take part in the phases again). `IntegratedServer.halt` calls
   `executeBlocking(…)` (kick the other players), which queues a task on the server and joins it
   (`BlockableEventLoop.java:96`); only then `MinecraftServer.halt(false)` sets `running = false`.
3. In the same tick phase the server thread runs one loop of `runServer`: `processPacketsAndTick`, then `waitUntilNextTick`
   (`runAllTasks`, then `managedBlock(() -> !haveTime())`), then the harness's `MinecraftServerMixin#postRunTasks`, which parks
   in `ThreadingImpl.enterPhase(PHASE_TEST)` until the client and the test thread arrive too.
4. The server runs a queued task only if `MinecraftServer.shouldRun`: `task.getTick() + 3 < tickCount || haveTime()`, where
   `haveTime()` is `runningTask() || now < (mayHaveDelayedTasks ? delayedTasksMaxNextTickTimeNanos : nextTickTimeNanos)` and
   `pollTask()` sets `mayHaveDelayedTasks = false` once the queue is empty. So once the queue is empty and the tick is due,
   the server leaves `waitUntilNextTick` and parks.
5. If the render thread's halt task arrives after that, nobody runs it: the render thread waits in `join` for the server,
   the server waits in the phaser for the render thread, the test thread waits in the phaser for both. It happens when the
   server's tick is shorter than the render thread's work before `halt`: a paused server ("Saving and pausing game..." at
   11:13:46 in the failed run; ServerLimitsGameTest opens RigTune's screen, which pauses the game) ticks in no time.

## The same hang before

- The v0.4 production smoke S1 (26.2, the user's mods, Windows): `docs/v0.4/verification/smokes/26.2-user-mods/
  s1-exit-hang-jstack.txt` has the same three stacks, from `ProductionSmoke.run` → `TestSingleplayerContextImpl.close`.
- v0.4's run 36235561444 (26.3 OpenGL, BenchmarkGameTest, no dump): its last lines are "Saving and pausing game..." and
  then the test's `runOnClient(BenchmarkWorld::exitNow)`, which leaves the benchmark world through the same deferred
  `Minecraft.disconnect`. Consistent with this cause; not provable without a dump.

## Workaround (our test code)

`src/gametest/.../GameTestWorlds.java`: right before a world exit, the test thread queues a task on the integrated server
(`server.execute`) that holds the server thread in `managedBlock`, running every task that arrives, until
`!server.isRunning()` (set by the render thread's halt right after its task) or 30 s. The server runs that task in the
tick phase of the deferred disconnect; inside a running task `haveTime()` is true (`runningTask()`), so the halt's task runs
whenever it arrives. `GameTestWorlds.create(context)` replaces `context.worldBuilder().create()` (its `close()` holds
first), `GameTestWorlds.leave(context, BenchmarkWorld::exitNow)` replaces `context.runOnClient(BenchmarkWorld::exitNow)`;
`GameTestSourcesTest.singleplayerWorldsAreLeftThroughGameTestWorlds` keeps every class on them. Call sites: BenchmarkGameTest
(1 world, 3 exits), FootprintGameTest, ProductionSmoke, RigTuneClientGameTest, ServerLimitsGameTest, StutterGameTest (1 world
each). Dedicated-server worlds don't halt an integrated server and are unchanged.

## Proof

- Forced order (`HaltRaceGameTest` on `scratch/deadlock-repro`, never merged): the render thread halts the server 1 s into a
  tick phase, after the server has parked. Locally (Windows, 26.2, under the game-test lock) without the hold: deadlocked,
  the jstack shows the same three frames (render thread in `IntegratedServer.halt` → `executeBlocking`, server thread in
  `postRunTasks` → `enterPhase`, test thread in `runTick` → `enterPhase`). With the hold: 3 of 3 forced cycles and 5
  paused-world cycles passed. The CI runs (the scratch branch on all 3 legs, and this branch) are in the handoff.

## Upstream issue (text for Fabric API; not filed)

**Title:** Client game tests: closing a singleplayer world can deadlock (`IntegratedServer.halt` waits on the server
thread while the server is parked in the tick phaser)

**Body:**

fabric-client-gametest-api-v1 6.0.2+0f4f1dca9e (Fabric API 0.161.0+26.2) and 0.161.0+26.3; Fabric Loader 0.19.5; JDK 25.
The threading code involved is unchanged on the default branch.

Rarely (about once in several hundred CI runs for us, more often when the game is paused), a client game test hangs forever
in `TestSingleplayerContext.close()`, or in any `Minecraft.disconnect(...)` run from `runOnClient` while in singleplayer.
Thread dump:

```
"Render thread": CompletableFuture.join <- BlockableEventLoop.executeBlocking <- IntegratedServer.halt
    <- Minecraft.disconnect <- MinecraftMixin lambda$deferDisconnect <- Minecraft.postRunTasks (postRunTasksHook)
"Server thread": Phaser.arriveAndAwaitAdvance <- ThreadingImpl.enterPhase <- MinecraftServerMixin.postRunTasks
    <- MinecraftServer.runServer
"Test thread": Phaser.arriveAndAwaitAdvance <- ThreadingImpl.enterPhase <- ThreadingImpl.runTick
    <- ClientGameTestContextImpl.waitFor <- TestSingleplayerContextImpl.close
```

Cause: `MinecraftMixin#deferDisconnect` moves the disconnect to the next `PHASE_TICK`. There
`Minecraft.disconnect(Screen, boolean, boolean)` calls `IntegratedServer.halt(false)` before the busy-wait loop that
`onDisconnectBusyWait` hooks, and `halt` blocks in `executeBlocking` until the server thread runs its task. In the same
tick phase the server thread runs `waitUntilNextTick`, which runs queued tasks only while `haveTime()`
(`MinecraftServer.shouldRun`); once its queue is empty and the tick is due it returns and parks in
`MinecraftServerMixin#postRunTasks` → `enterPhase(PHASE_TEST)`. If the client submits the halt task after that point (a
paused integrated server ticks in almost no time), the task is never run, and the client never reaches the phaser.

Deterministic reproduction: open a singleplayer world, register a `ClientTickEvents.START_CLIENT_TICK` callback that sleeps
1 s and then calls `Minecraft.getSingleplayerServer().halt(false)` (what `Minecraft.disconnect` does), then
`context.waitTicks(1)`: it hangs every time with the stacks above.

Possible fixes: keep the client in the phase logic while it waits for the server in `halt` (as `onDisconnectBusyWait` does
for the busy-wait after it), e.g. run the deferred disconnect's `executeBlocking` as a submit plus a loop of
`postRunTasks()` until the future is done; or have `MinecraftServerMixin#postRunTasks` keep running server tasks
(`managedBlock`) while a client `executeBlocking` is pending instead of parking. Workaround we use: before closing, queue a
server task that runs `server.managedBlock(() -> !server.isRunning())`, so the server keeps running tasks until the halt has
happened.
