# WS-F: footprint guard + startup-time trend (SPEC 10, 13) — task plan

Branch `feat/footprint`, worktree `rigtune-foot`. SPEC amendments F-M1 (retention = explicit accounting + whole-heap delta
after the histogram's full GC; the class histogram is a leak diagnostic) and F-L1 (monitor-on numbers in a follow-up
branch after WS-S merges) override the item text.

Key constraint: every client game test runs in ONE production client JVM, in fabric.mod.json order (FootprintGameTest is
15th). So everything tied to startup is captured by RigTune itself (`client/FootprintStats`) at the time it happens, and
the game test only reads it. The 5 s worker window is snapshotted by FootprintStats at CLIENT_STARTED + 5 s.

- [x] F1 `core/footprint/StartupTimesStore` (TDD, `StartupTimesTest`; the hash is the coordinator's shared `core/model/ModSetHash`): startup-times.json
      on `JsonStateFile` (16 KiB cap), last 30 runs, median of the last 10, the mod-set note only when the last two hashes
      differ, corrupt → `.bad`, newer → read-only and untouched (AC13.1).
- [x] F2 `client/footprint/StartupTimes`: records `RuntimeMXBean.getUptime()` at the first TitleScreen init (once per
      JVM, written on `Probes.EXECUTOR`), `view()` cached; RigTuneClient one registration in a helper; ToolsScreen's startup
      line + mod-set note + general advice (`rigtune.startup.*`, after `rigtune.impact.low`); LangCheckTest/WordingTest.
- [x] F3 `client/FootprintStats`: wall + render-thread CPU of RigTunePreLaunch, onInitializeClient and the CLIENT_STARTED
      handler (`timePreLaunch`/`timeInit`/`clientStarted`), CPU of every "RigTune*" thread at CLIENT_STARTED + 5 s (per name),
      one INFO log line. No Minecraft imports (it loads in preLaunch). RigTunePreLaunch/RigTuneClient bodies move into
      private methods wrapped by the timers.
- [x] F4 `tools/footprint-budgets.json` + `core/footprint/FootprintBudgets` (TDD, `FootprintBudgetsTest`): mode warn|fail,
      one limit + SPEC ceiling per metric, null metrics skipped, violations listed; limits never above ceilings (test).
- [x] F5 `FrameHookBudgetTest` (AC10.1): `FrameTimes.onFrame` 10 M calls: allocation over the cold-to-hot run (so an
      allocation escape analysis would remove later still shows), ns/call over a second hot run.
- [x] F6 `FootprintGameTest` (AC10.4, AC13.2): waits for the title screen, the report and the window; reads FootprintStats;
      tick hook ns/call + allocated bytes on the render thread; notice evaluation time; `gcClassHistogram` (DiagnosticCommand)
      RigTune-class bytes/instances + heap after that full GC at idle and after 20 RigTuneScreen/Tools open-close cycles
      (whole-heap delta, instance growth = leak suspects); no "RigTune power" thread without a battery, no sampler thread with
      the monitor off; startup-times.json has exactly one run from this launch and the hub line renders (screenshots at the
      3 sizes); writes `footprint/footprint-<mc>-<backend>.json`; gates through FootprintBudgets.
- [x] F7 build.gradle: `-Drigtune.footprint.budgetsFile` for `test` and `runProductionClientGameTest` (+ test input); a loopback
      rules fixture server (BuildService) → `-Drigtune.rules.baseUrl` on runProductionClientGameTest. build.yml: upload
      `footprint-<mc>-<backend>` artifact.
- [x] F8 v040-written set `src/test/resources/v040-written/ws-f/startup-times.json`, written by `StartupTimesFixtureTest`
      through the store (the test fails if the committed file is missing or differs; `RIGTUNE_WRITE_FIXTURES=1` rewrites it); run
      WS-H's compat030 harness against it.
- [x] F9 Calibration (AC10.3): one local 26.2 run under the machine lock (first in the lock order), 3 CI runs per leg;
      budget = min(ceiling, 2 × max observed); docs/v0.4/verification/footprint/README.md; switch to `fail`.
- [x] F10 Gate proofs (AC10.2): a 420 ms sleep (wall budget + 50 ms, coordinator) and a 200 ms CPU loop in onInitializeClient → red legs; `new long[1]` in onFrame → red unit test;
      both reverted; run URLs recorded.
- [x] F11 README "RigTune's own footprint" (the startup A/B + the guard's numbers and budgets); verification README maps every
      X5 always-on path to its measurement (AC10.6); `docs/v0.4/design/ws-f.md`; self-review; merge origin/feat/v0.4.0; CI green.
- [ ] F12 (follow-up branch after WS-S merges, F-L1): monitor on/off in FrameHookBudgetTest and FootprintGameTest
      (`StutterMonitor.retainedBytes()`, sampler CPU, tick hook with the monitor on).
