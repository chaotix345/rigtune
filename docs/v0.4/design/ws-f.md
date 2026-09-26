# WS-F: footprint guard + startup-time trend (SPEC 10, 13) as landed

Branch `feat/footprint`. Evidence: docs/v0.4/verification/footprint/README.md (calibration table, gate proofs, the X5
map). Plan: docs/v0.4/plans/ws-f.md.

## What landed

- **`client/FootprintStats`**: no Minecraft types, because it loads in preLaunch.
  - Hooks: `preLaunchStart()`/`preLaunchEnd(long)`, `initStart()`/`initEnd(long)` (wall + current-thread CPU; only
    the first call per JVM counts), and `clientStarted(Runnable)`, which snapshots the CPU of every "RigTune…" thread,
    times RealController.start and closes the window `WINDOW_MILLIS` = 5 s later on the JDK's delayed executor (not a
    RigTune thread).
  - Output: `snapshot()` returns `Snapshot` (UNSET = -1 for anything not measured), and one INFO line when the window
    closes. The first `ThreadMXBean` call's cost is kept apart as `mxInitNs` (0.5-0.7 ms).
- **Hotspot edits.**
  - RigTuneClient: one line at the start and one at the end of `onInitializeClient` (the body is otherwise unchanged,
    per the coordinator). The CLIENT_STARTED line is now
    `minecraft -> FootprintStats.clientStarted(() -> real.start(minecraft))`, followed by `registerStartupTime(real)`,
    a helper that registers the AFTER_INIT title-screen hook. `onTick` is public, so the game test can time it.
  - RigTunePreLaunch: a start line and an end line.
- **Startup times (SPEC 13).**
  - `core/footprint/StartupTimesStore` handles startup-times.json on `JsonStateFile`: 16 KiB, the last 30 runs,
    oldest dropped (more if the byte cap needs it); corrupt goes to `.bad` and a newer file is read-only; unknown
    top-level fields are kept. Runs without `at` or with `ms <= 0` are ignored; per-run unknown fields aren't kept
    (nothing but this store reads the file). It also provides `summarize()`: the last run, the median of the last 10,
    and `modSetChanged` only when the last two known hashes differ.
  - `client/footprint/StartupTimes` does the recording. The first TitleScreen AFTER_INIT per JVM reads
    `RuntimeMXBean.getUptime()` and writes on `Probes.EXECUTOR`. `mods` counts top-level non-builtin mods;
    `modSetHash` is `core/model/ModSetHash` (the coordinator's shared helper) over every non-builtin loaded mod,
    nested ones included. `view()` is cached and synchronized with the invalidation after a record.
- **ToolsScreen** startup section:
  - `rigtune.startup.last` ("Last launch %s s") with one run, else `rigtune.startup.last_median` ("… · median of the
    last N: %s s", N = min(10, runs));
  - `rigtune.startup.mod_set_changed` ("may be related") in a highlight colour;
  - `rigtune.startup.advice` (general advice only, never a mod name), wrapped to the button width and clipped above
    Done.
  - For tests: `startupLine()`, `startupDetail()`.
- **`core/footprint/FootprintBudgets`** parses tools/footprint-budgets.json strictly: mode warn|fail, and every limit
  ≥ 0 and ≤ its ceiling. `check(Map)` skips null (unmeasured) values; `enforce` throws one AssertionError in fail mode.
  `-Drigtune.footprint.budgetsFile` (the property is camelCase, because LangCheckTest would read a dotted lower-case
  string as a translation key); otherwise the file is found by walking up from the working directory.
- **Tests.** `FrameHookBudgetTest`, `FootprintBudgetsTest` (pins the SPEC ceilings), `StartupTimesTest`,
  `StartupTimesFixtureTest` (the ws-f set; delete the file and rerun to regenerate), `HttpModrinthClientLazyTest`.
  FootprintGameTest's fields are listed in the verification README.
- **build.gradle** (one block after runProductionClientGameTest, plus the service class next to `InjectedFs`): the
  budgets property and input on `test`; on `runProductionClientGameTest`, the budgets property and
  `-Drigtune.rules.baseUrl` from `RulesFixtureServer`. That service is a shared BuildService per project that serves
  rules/rules-v2.json and rules/rules-v1.json on 127.0.0.1 with a free port; it starts when the jvmArgs provider is
  read and stops at the end of the build. WS-J's `-PgametestJvmArgs` hook is a separate block.
- **build.yml**: one step, "Upload footprint numbers", which uploads artifact `footprint-<mc>-<backend>`.
- **`HttpModrinthClient`** (not owned by any workstream): both HttpClients are built lazily with double-checked
  volatiles; package-private `httpClientsBuilt()` exists for the test.

## Deviations (coordinator decisions)

1. Modrinth is on during the 5 s window. All game tests share one JVM, and PreviewGameTest needs live Modrinth.
2. The loopback rules fixture applies to every production game test. No game test asserts the rules source, and every
   leg stayed green.
3. Render-thread init ceilings are 400 ms wall / 150 ms CPU, not 25/15. The time is class loading (see the
   verification README). Wall time is a loose backstop because of runner descheduling; the CPU gate is the tight one.
4. Budgets are 2 × the largest value observed, never above the ceiling. heapGrowthAfterCyclesBytes uses 2 × the
   largest |delta|, and leakSuspects (limit 0) is the exact per-cycle leak check.
5. AC10.2 is proven with a 420 ms sleep (budget + about 50 ms) instead of 50 ms, plus a 200 ms CPU busy loop for the
   CPU gate.
6. `rigtuneClassBytesIdle` is shallow: RigTune's own objects only, not the JDK objects they hold. Per F-M1 it's a
   diagnostic under the SPEC's 8 MiB cap. Retention is gated by the whole-heap delta and the instance growth.
7. The tick hook is measured in-game with a ToolsScreen open, on the play path: not the title screen and not
   RigTuneScreen, so it returns after the title check. There's no JUnit tick case: `onTick` needs a Minecraft instance.
   Item 5's tick additions come with F-L1.
8. The ToolsScreen's text lines aren't widgets, so UiGameTest's layout check doesn't cover them. FootprintGameTest
   checks that each line fits the width at the 3 sizes.

## Self-review

A code-reviewer subagent reviewed `eb3054f` and found 0 high, 4 medium and 8 low.
Fixed:
- the wall clock now starts before the instrumentation's own work (the first ThreadMXBean call, and the thread scan
  at CLIENT_STARTED), so that work counts;
- FrameHookBudgetTest takes the fewest hot-run bytes of any run, so a recompile inside one run can't flake it;
- closeWindow can't throw;
- StartupTimes fills its view on the worker after recording, so the Tools screen usually reads no file;
- the ws-f fixture test fails when the fixture is missing or stale, and rewrites it only with
  RIGTUNE_WRITE_FIXTURES=1;
- added FootprintStatsTest.
Kept, with reasons:
- the rewritten CLIENT_STARTED line and the `registerStartupTime(real)` line (the coordinator approved timing
  `real.start`; one added line, like every other workstream's);
- `workerCpuMs5s` counts only live "RigTune…" threads, as SPEC 10 lists them (documented in the verification README);
- the first-TitleScreen rule (SPEC 13's wording);
- the vacuous PowerWatcher check until WS-P merges;
- the histograms taken without draining the executors (idle by then).

## Follow-up (F-L1, after WS-S merges; WS-F's own branch)

- FrameHookBudgetTest: add `StutterMonitor.onFrame` to the loop, with the monitor off and on
  (`frameHookNsPerCallOn`, `frameHookAllocBytesOn`).
- FootprintGameTest: in a singleplayer world, monitor on, record `StutterMonitor.retainedBytes()`, sampler CPU per
  60 s and the tick hook; monitor off again, record retainedBytes plus the heap back within idle + 256 KiB.
- Then recalibrate those budgets (now = ceilings).

## UNVERIFIED

- Launch-to-title and the startup A/B are from the research (26.2, one Windows PC, n = 10 per arm), not re-run here.
- The monitor-on numbers (AC10.4's monitor part) wait for item 5 (F-L1).
- Budgets beyond the 3 calibration runs per leg: a future runner image or Mesa version may shift the numbers.
