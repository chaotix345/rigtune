# WS-F2: footprint follow-up (SPEC 10 AC10.4, amendment F-L1) — task plan

Branch `feat/footprint-monitor`, worktree `rigtune-f2`, from `origin/feat/v0.4.0` @ 8869abc (WS-S merged). Binding:
SPEC 10 + amendments F-M1 (the monitor-on/off budget asserts on `StutterMonitor.retainedBytes()`; the whole-heap delta
and the class histogram are diagnostics/leak checks), F-M2 (budgets = 2 × max observed, never above the ceiling), F-L1,
S-M1 (the phase timers ship, `require = 0`). Product code stays untouched; test seams live in the test source sets.

- [ ] F2-1 `FrameHookBudgetTest` (JUnit, 10 M calls each, same method as the monitor-off case: allocation over the first
      10 M calls from cold beyond 4 KiB of JIT noise and over the hot runs, ns/call best of 5):
      - `frameHookWithTheMonitorOn`: what `DebugScreenOverlayMixin` runs per frame (`FrameTimes.onFrame` +
        `StutterMonitor.onFrame`) with a session capture on → `frameHookNsPerCallOn`, `frameHookAllocBytesOn`.
      - `frameHookWithTheMonitorOnAndThePhaseTimers`: the same plus every `MinecraftFrameMixin` call of a capped frame with
        one tick and a chunk load (packets pair, tick pair, render start, limiter pair, chunkLoaded) →
        `frameHookNsPerCallOnPhases`, `frameHookAllocBytesOnPhases` (new budgets, ceiling 200 ns / 0 B).
      - The session starts through a test-only accessor in `src/test/.../client/stutter/` (StutterMonitor's start/stop are
        package-private); stopped in `@AfterEach`. Occasional 60 ms frames exercise the candidate path.
- [ ] F2-2 `FootprintGameTest.sessionMonitor` (after the title-screen retention part): a singleplayer world
      (`worldBuilder().create()`), settle, heap after a full GC with the monitor off (in-world idle); monitor on
      (`controller.setStutterMonitor(true)`); the sampler thread must exist; its CPU over 60 s of wall time
      (`samplerCpuMsPer60s`); the END_CLIENT_TICK work with the monitor on (`RigTuneClient.onTick` + `StutterHooks.tick`,
      reflected, no screen open: the play path) → `tickHookNsPerCallOn`, `tickHookAllocBytesOn` (new budgets, ceiling 2 µs /
      0 B); `monitorOnRetainedBytes` = `StutterMonitor.retainedBytes()`; heap after a full GC (diagnostic); monitor off:
      no sampler thread, GC listener removed, `monitorOffRetainedBytes`, session saved, then the histogram: no live
      FrameRing / StutterRings / Capture / Copy / snapshots (`monitorOffLeftoverInstances`, limit 0); heap after a full GC
      (diagnostic). PowerWatcher: `PowerWatcher.isRunning()` false and no "RigTune power" thread without a battery, at the
      title and in the world (AC4.9/AC10.6); `hasBattery` recorded so the check isn't silently vacuous.
- [ ] F2-3 `tools/footprint-budgets.json`: the new keys (at their ceilings until calibrated); `FootprintBudgetsTest` pins
      their ceilings.
- [ ] F2-4 Calibration: 3 CI runs per leg (the java job for F2-1, the 3 game-test legs for F2-2) on the same commit (the
      branch + 2 scratch branches in parallel), plus 1 local 26.2 run under the machine lock if the lock is free; budget =
      min(ceiling, 2 × max observed); fail mode stays.
- [ ] F2-5 docs: verification README (calibration table for the monitor metrics, the X5 map with the monitor, the phase
      timers, PowerWatcher), README "RigTune's own footprint" monitor numbers, `docs/v0.4/design/ws-f2.md`.
- [ ] F2-6 Finish: code-reviewer subagent on the diff; merge `origin/feat/v0.4.0`; `./gradlew build`; push; CI green.
