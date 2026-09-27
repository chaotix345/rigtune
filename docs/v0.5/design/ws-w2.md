# WS-W2: C18 launch-time regression alerts (v0.5)

Branch `feat/v05-launch-alerts` (worktree `rigtune-launchalert`), from `feat/v0.5.0` @ 94654c11 (WS-W, WS-H, WS-P, WS-R,
WS-K and ws-ci merged). Scope: docs/v0.5/PLAN.md "WS-W2"; SPEC 9 (C18, AC9.1-AC9.8), with 2L's line in the notice's
detail (AC2L.2's C18 half). C18 is the first P1 to cut (SPEC 9's cut order); nothing is cut without the coordinator.

This file is first the TDD task plan (committed before any code), then, as each task lands, what landed, the
deviations, the evidence and the AC table.

## Inputs read
PLAN (Global Constraints, protocol, WS-W2, contracts, Ownership, Hotspots, Cross-workstream ACs, Local runs, both
Amendments); SPEC (top, compatibility, X1-X12, C3, C8, Shared contracts, 2L, 9, both Amendments: X12 uses 1280x720@3
for scale-3 checks); docs/v0.5/design/ws-k.md (the `StartupRegressionNoticeSource` skeleton, AwarenessStore's
`acknowledgedStartupRegressions()`/`acknowledgeStartupRegression(String)`, the anchor `rigtune.startup.mod_set_changed`,
`V05LangFamilies.launchAlerts`, `AwarenessGameTest.startupRegression`, `A11yGameTest.walkToolsStartup`, the footprint
baseline of run 36310249248) and ws-w.md (ToolsScreen's `ToolsList.row(...)`, `PerfCounterAdvice.lines(...)`,
`HardwareProbe.perfCounters()`, `PreloadTimer.preloadMs()`); research la (docs/research/v0.5/feature-launch-alerts.md);
the code: `StartupTimesStore`, `StartupTimes`, `BenchmarkTrend` (`median`, `mad`, `noiseFloorPercent`, read-only),
`RegressionNoticeSource`/`TrendService` (the acknowledged-regression precedent), `ServerLimitNoticeSource(Test)` (the
static-builder test shape), `ToolsScreen`, `AwarenessGameTest`, `A11yGameTest`, `FootprintGameTest` (frozen: it builds
`new StartupTimes.View(15_125L, 14_517L, 12, true)`, so the 4-argument constructor stays).

## Design (SPEC 9 as specified; la §2)
- `core/footprint/StartupTrend` (pure, new): comparable = same MC version; baseline = the newest `MAX_RUNS`
  (= `StartupTimesStore.MEDIAN_OF`, 10) comparable runs before the latest; `MIN_RUNS` = 5; floor =
  `BenchmarkTrend.noiseFloorPercent(null, baselineMs...)` (so at least 10 %); delta = (latest − median) / median;
  kinds NO_RUN, TOO_FEW, IN_LINE, IMPROVEMENT (computed, never shown), SLOWER (delta more than the floor); cause against
  the newest comparable run before the latest, first match: mod count → MOD_COUNT, hash (both known) with the same count
  → MOD_SET, RigTune version (both known) → RIGTUNE_VERSION, else NONE. `regression(a)` and `cause(a)` build the two
  `Text` lines (la §1.4's wording), used by the notice and by Tools. MIN_RUNS and the floor are estimates, decided by
  AC9.8's real launches (below).
- `client/footprint/StartupTimes`: `View` gains `@Nullable StartupTrend.Assessment assessment` (the 4-argument
  constructor kept, = null), computed in `summarize()` on the existing worker path; `startup-times.json` unchanged. The
  launch's INFO line adds the assessment (kind, delta, floor, baseline size) so a player's latest.log and AC9.8 have the
  numbers. The acknowledgements: an in-memory set, filled from awareness.json in `summarize()` (the worker) only when
  the latest run is SLOWER; `acknowledge(key)` adds to it and writes `AwarenessStore.acknowledgeStartupRegression` (a
  click, like `TrendService.acknowledge`). `computed()` returns the view the worker computed, or null, without file I/O
  (the notice source's read; SPEC X8: no file I/O in `current()`).
- `client/notice/StartupRegressionNoticeSource` fills WS-K's skeleton: `current()` = the static builder
  `notice(Assessment, PerfCounters, @Nullable Long preloadMs)` over `computed()` unless acknowledged; key
  `startup.regression.<latest run's at>`; message "Launch time %s%% higher than usual (%s s vs your usual ~%s s)";
  detail = exactly one cause line, plus, while Windows' performance counters are off (2L), `PerfCounterAdvice`'s text
  lines (the links stay in Tools, which the notice's first action opens); actions Tools… (opens ToolsScreen) and Got it
  (acknowledges); not dismissible.
- `client/ui/ToolsScreen` (WS-W's RowList): its own method adds the regression and cause rows (`COLOR_NOTE`, each a
  `RowFocus` Tab stop) under the startup line while the latest run is SLOWER; `regressionLines()` for tests.
- en_us.json: `rigtune.startup.notice.*`, `rigtune.startup.regression*` right after the anchor
  `rigtune.startup.mod_set_changed`, alphabetical. Every key is written out in the code (no dynamic family, so
  `V05LangFamilies.launchAlerts` stays empty unless one appears).

## TDD task plan

Each task: the red test first, then the code, then a commit. Local: `./gradlew :26.2:test --tests '<classes>'` in a build
slot (no version-specific API is touched: `:26.3:` only for the gametest compile); the full build and every game test are
CI's.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| T0 | **AC9.8 run A (code-deciding, before T1's constants)**: 7 unchanged launches of a production client on the worktree's dev run dir (`versions/26.2/run`, never the player's instance) through `runProductionSmoke` with fabric-api + Sodium + Mod Menu, under the game-test lock; startup-times.json and each latest.log copied out; the planned assessment computed from the recorded numbers (MIN_RUNS 5, floor ≥ 10 %) | none (evidence, `<scratch>/ws-w2/ac98/runA/`) | the recorded launch times | decides MIN_RUNS and the floor |
| T1 | `StartupTrend` (pure) and its two text lines | `core/footprint/StartupTrend` | `StartupTrendTest`: < 5 comparable → TOO_FEW, no cause; another MC version never in the baseline; within the floor → IN_LINE (at the floor too); past it slower → SLOWER, faster → IMPROVEMENT; the baseline is the newest 10 before the latest; each cause and the first-match order (count before hash before RigTune; an unknown hash or version claims nothing); all-identical baseline (MAD 0) → floor 10 %, no division by zero; one extreme outlier leaves the floor at 10 %; no runs → NO_RUN; the lines' English (whole percent, one-decimal seconds) | AC9.1 |
| T2 | `StartupTimes.View.assessment`, the acknowledgements, `computed()`, the INFO line | `client/footprint/StartupTimes` | `StartupTimesAckTest`: the view carries the assessment; an acknowledgement persists (a new StartupTimes over the same config reads it back as acknowledged); a 0.4.0 awareness.json without the array → nothing acknowledged, and acknowledging adds the array keeping 0.4's fields; `computed()` is null until computed | AC9.3 (unit), AC9.4 (0.5 reads 0.4.0's file) |
| T3 | The notice | `client/notice/StartupRegressionNoticeSource`, en_us.json | `StartupRegressionNoticeTest`: SLOWER → key, STARTUP_REGRESSION, the numbers, exactly one cause line per cause, Tools… then Got it, not dismissible; TOO_FEW/IN_LINE/IMPROVEMENT/NO_RUN → none; counters off → the detail adds 2L's lines (the measured one only when measured), on → not; LangCheckTest and WordingTest | AC9.2 (unit), AC9.6, AC2L.2 (C18 half) |
| T4 | Tools' regression rows | `client/ui/ToolsScreen` (own method), `A11yGameTest.walkToolsStartup` (C18 assertions) | the walk with a canned SLOWER view at 1280x720@2, 640x480@2, 854x480@2, 1280x720@3: both rows present, fit, layout check; Tab reaches them and each narrates its text; with no assessment or TOO_FEW, none | AC9.5 |
| T5 | The game test | `AwarenessGameTest.startupRegression` | a seeded startup-times.json (5 comparable runs + a slower one with 12 more mods): the notice with the numbers and the mod-count line; screenshots at the three sizes; Tools… opens ToolsScreen with the rows; Got it: gone on reopen and after a rescan, the key in awareness.json, a fresh StartupTimes reads it as acknowledged; a later slower launch → a new key; 4 comparable runs → no notice and no rows; counters off (seeded) → the 2L line in the detail; startup-times.json put back and the real view refreshed | AC9.2, AC9.3 (game) |
| T6 | Fixture set `ws-w2` | `src/test/resources/v050-written/ws-w2/{awareness.json,expect.json}`, `V050WrittenWsW2Test` | written through `StartupTimes.acknowledge`'s store call; compares (regenerates with `RIGTUNE_REGENERATE_FIXTURES=1`); round trip; compat030; the released 0.4.0 `AwarenessStore` rewriting it keeps the array (manual, as WS-W's Keep040) | AC9.4 |
| T7 | **AC9.8 run B (after T5)**: in the same run dir, 2 more unchanged launches (the code assesses them: no notice), then the batch (+21 of the player's mods, read-only copies) → SLOWER with the magnitude and the mod-count line on RigTuneScreen's notice line and in Tools (the smoke's screenshots) | `docs/v0.5/verification/startup/` | the recorded numbers, logs, screenshots | AC9.8 |

AC9.7 (FootprintGameTest and FrameHookBudgetTest unmodified and green) is CI's on every push; no render-thread hook is
added (the assessment rides `StartupTimes`' worker path; the notice source is built by the lazy list).

No new `//? if` block expected (nothing version-specific: `ToolsList`, `Notice`, Gson). No new thread, no network, no
per-tick or per-frame work.

**Push plan.** The first push carries this doc and T1-T3; then T4-T6; nothing during a coordinator streak.
