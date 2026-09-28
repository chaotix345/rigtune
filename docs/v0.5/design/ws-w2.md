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

---

# As landed

Paths as in PLAN "Ownership". One commit per task: plan 102f8de3, T1 cb97ae3b, T2 78ff8fd2, T3 c18445f2, T4 5ad92632, T5
2d3b2527, T6 4cb7a26b; the code review's findings and RW-19 66c78c13 (merges of `origin/feat/v0.5.0`: c96053a3 and the
later ones in the log). No new `//? if` block, no new thread, no network, no per-tick or per-frame work, no render-thread
init work (X4).

## What landed

| item | change | files | tests (red first) |
|---|---|---|---|
| T1 trend | `StartupTrend` (pure): comparable = the same MC version; the window is the newest 10 comparable runs before the latest, from 5; floor = `BenchmarkTrend.noiseFloorPercent(null, …)` (≥ 10 %, `median`/`mad` read-only); SLOWER past the floor ("more than": at the floor is IN_LINE); IMPROVEMENT computed, never shown; the first-match cause (mod count, then hash, then RigTune version; a value either run didn't record claims nothing); `regression`/`cause` build the lines; `key`, `describe` (the log line) | `core/footprint/StartupTrend` | `StartupTrendTest` (16 cases, AC9.1's list plus the streak, RW-19 and the real series below) |
| RW-19 | runs gain the optional `preloadMs` (vanilla's crash-report setup at that launch, 2L's `PreloadTimer`; the 6-argument `Run` constructor kept, null never written, so `StartupTimesFixtureTest` and 0.4's set are byte-identical); the trend leaves it out when the latest run and at least 5 runs of the window recorded it, and then says so ("(…, not counting Minecraft's crash-report setup)") | `core/footprint/StartupTimesStore` (the record only), `StartupTrend`, `client/footprint/StartupTimes` (records it) | `StartupTrendTest.theCrashReportSetupIsLeftOutWhenTheLatestAndFiveBaselineRunsRecordedIt`, `.thePlayersRealLaunches` |
| H1 streak | a slow streak is one regression: while the previous comparable launch was SLOWER against its own window too, the streak's first launch gives the key and the cause ("Slower for your last N launches; may be related to your mod set changing (a → b mods) before the first of them", and the mod-set, RigTune and no-change variants); the streak ends at the first launch that isn't SLOWER | `StartupTrend` | `StartupTrendTest.aSlowStreakIsOneRegression` (the reviewer's example: N MOD_COUNT; N+1..N+4 N's key and cause; N+5 IN_LINE), `.aStreakEndsAtAnInLineLaunchAndTheNextSlowdownIsANewOne` |
| T2 view | `StartupTimes.View` gains `assessment` (the 4-argument constructor kept: FootprintGameTest builds one), computed in `summarize()` on the existing worker path; `computed()` returns the view without I/O (the notice source's read); the acknowledgements live in memory, filled from awareness.json in `summarize()` only for a SLOWER launch; `acknowledge(key)` writes `AwarenessStore.acknowledgeStartupRegression` (a click, as `TrendService.acknowledge`); the launch's INFO line names the crash-report setup and the assessment | `client/footprint/StartupTimes` | `StartupTimesAckTest` (the view; kept for the next launch; the streak keeps the key; a later slowdown after an in-line launch isn't covered; a 0.4.0 awareness.json without the array) |
| T3 notice | fills WS-K's skeleton: the key checked against the acknowledgements before the notice is built; message = the regression line; detail = exactly one cause line plus, while Windows' performance counters are off, ONE 2L line (this launch's measured crash-report setup, else what the setting is); Tools… opens ToolsScreen, Got it acknowledges; not dismissible; a static builder for the unit test | `client/notice/StartupRegressionNoticeSource`, en_us.json | `StartupRegressionNoticeTest` (5); LangCheckTest, WordingTest, PseudoLocaleTest |
| T4 Tools | while SLOWER, the two lines are rows under the startup line (`COLOR_NOTE`, `RowFocus` Tab stops); once the trend compares, the startup line's median is the trend's usual (review L3: "median of the last N" = the window it compared with, so the two "usual" numbers agree); 0.4's "mod set changed" note gives way to a cause line that names the same change | `client/ui/ToolsScreen` | `A11yGameTest.walkToolsStartup` → `startupRegressionRows` |
| T5 game test | `AwarenessGameTest.startupRegression` (below) | `AwarenessGameTest` (my method and its helpers) | CI, 3 legs |
| T6 fixtures | `v050-written/ws-w2/`: awareness.json (`acknowledgedStartupRegressions`, written by StartupTimes' Got it), startup-times.json (runs with `preloadMs`), expect.json (0.4.0's `AwarenessStore` keeps the array; its `StartupTimesStore` reads 6 runs, no `.bad`) | the set, `V050WrittenWsW2Test` | compares by default; `RIGTUNE_REGENERATE_FIXTURES=1` rewrites |

en_us.json: 14 keys in `rigtune.startup.notice.*` and `rigtune.startup.regression*`, right after the anchor
`rigtune.startup.mod_set_changed`, alphabetical. Every key is written out in the code, so `V05LangFamilies.launchAlerts`
stays empty (no family is built from a value).

**Game tests (my methods only).** `AwarenessGameTest.startupRegression`, with the real controller and the network off: a
seeded startup-times.json behind the real `StartupTimes` (which then recomputes, as its worker does after a launch): 4
comparable launches → no notice and no Tools row; 5 of 10 s and one of 14.5 s with 12 more mods → the notice "Launch time
45% higher than usual (14.5 s vs your usual ~10.0 s)" with exactly "May be related to your mod set changing (70 → 82 mods)
since your last launch", Tools… and Got it, not dismissible, screenshots at the three sizes; Tools… opens ToolsScreen with
the same two rows and leaves the notice; with the counters off (seeded) the detail adds one 2L line; Got it: gone on the
rebuilt line, after reopening and after a rescan, the key in awareness.json, a new `StartupTimes` (the next launch's)
reads it back; a slower launch right after is the same streak (no notice); after an in-line launch a slowdown has its own
key and "No change recorded since your last launch…". startup-times.json and the real view are put back (FootprintGameTest
later checks this launch's one run), awareness.json by `runTest`. `A11yGameTest.walkToolsStartup` → `startupRegressionRows`
(after WS-W's 2L block): a canned SLOWER view at 1280x720@2, 640x480@2, 854x480@2 and 1280x720@3: the two rows under the
startup line ("median of the last 6: 14.5 s", the trend's usual), no generic mod-set note, every row fits, the widgets
inside the screen and apart; Tab reaches every row and each narrates its text, Tools' Tab order (buttons, rows, Done);
focus and high-contrast screenshots (the `highContrastScreenshot` helper); a slow streak's longer line fits at 640x480;
TOO_FEW and 0.4's view: no row.

## Code review (1 H, 1 M, 6 L; the coordinator's decisions 2026-09-28 11:05: fix all, with RW-19)
| # | finding / decision | fix | commit |
|---|---|---|---|
| H1 | A slow streak raised a new notice at every launch (each its own key) | a SLOWER launch whose previous comparable launch was SLOWER too keeps the streak's first key and cause; the streak ends at the first launch that isn't SLOWER; `aSlowStreakIsOneRegression` (the reviewer's example), `aStreakEndsAtAnInLineLaunchAndTheNextSlowdownIsANewOne`, `StartupRegressionNoticeTest.aSlowStreakKeepsItsFirstKey`, AwarenessGameTest's streak step | 66c78c13 |
| M2 + RW-19 | AC9.8's false-alarm evidence from the player's real launch series; subtract the crash-report setup | `preloadMs` recorded and left out of the comparison (above); `thePlayersRealLaunches` over the 44 real launches (the realworld agent's `launch-times.tsv`, committed as `src/test/resources/startup/real-launch-times-2026-09-28.tsv`); the record in docs/v0.5/verification/startup/ | 66c78c13 |
| L3 | Tools' median (last 10 including the latest) and the notice's "usual" (10 before it) disagreed | the startup line uses the assessment's window once it compares | 66c78c13 |
| L4 | 0.4's mod-set note hidden when the cause names the mod set | kept, listed under Deviations (only for a one-launch regression: a streak's cause names an earlier change) | 66c78c13 |
| L5 | the detail carried all of 2L's text | one line: the measured time, else what the setting is | 66c78c13 |
| L6 | no test that a later slowdown isn't covered by an earlier Got it | `StartupTimesAckTest.anAcknowledgementIsKeptForTheNextLaunch` extended; StartupTimes' comment says when the stored acknowledgement matters (a streak keeps its first launch's key across launches; a launch that records no run shows the same latest run again) | 66c78c13 |
| L7 | this file | this section and the ones below | this commit |
| L8 | lang spacing; the high-contrast helper; the key checked before the notice is built | done | 66c78c13 |

## Deviations
- **The streak lines' wording** (H1): "Slower for your last N launches; may be related to your mod set changing (a → b mods)
  before the first of them" (and "…; RigTune a → b before the first of them", "…; no change recorded before the first of
  them; possibly…") instead of the review's "%s launches ago", which reads "1 launches ago" at a streak's second launch; N
  is always at least 2 here. A streak whose first launch had no recorded change keeps NONE, with the streak wording.
- **RW-19's message**: when the setup is left out, the numbers are the compared ones and the line says so ("Launch time
  40% higher than usual (14.0 s vs your usual ~10.0 s, not counting Minecraft's crash-report setup)"); Tools' startup
  line keeps the real launch time and the window's real median. The qualifier comes after the numbers because the notice
  line clips the end of a long message (run E's launch 9 showed the numbers cut off with it first). The subtraction needs
  the latest run and at least 5 runs of the window with `preloadMs`; the window's runs without it are then left out of the
  median (never mixed).
- **The 0.4 note** "The mod set changed since the previous launch (may be related)" is hidden while a one-launch
  regression's cause line names the mod set (L4: the same change twice); a streak's line names an earlier change, so the
  note stays.
- **The notice detail's 2L line** is one line (L5), not 2L's whole advice; the rest and Microsoft's pages are in Tools,
  which the notice's first action opens.
- **`core/footprint/StartupTimesStore`** (not in WS-W2's ownership row): RW-19's optional `Run.preloadMs` and the kept
  6-argument constructor, nothing else (the SPEC amendment names the field; the coordinator routed RW-19 here).
- **Tools' rows** are `ToolsList` rows with a `RowFocus` (WS-W's RowList), not `RowFocus.standalone` as SPEC 9's text says
  (the same deviation WS-W recorded for 2L's rows).
- **The game-test seam**: `StartupTimes.refresh()` is public (the game test seeds startup-times.json, then recomputes as the
  worker does after a launch).
- **AC9.8's "after adding a batch of mods"**: a first batch of 21 light mods added no measurable launch time (IN_LINE +2.5 %,
  run B); the batch that shows it is 39 of the player's mods (below).

## Residuals (not fixed, with why)
- A 0.4.0 rewrite of startup-times.json drops `preloadMs` (0.4's own record type), so after 0.5 → 0.4.0 → 0.5 the trend
  compares raw launch times until 5 new runs carry it again (the safe side: the raw floor is wider).
- The streak is recomputed from the runs at every launch (at most 30 short assessments on the worker, once per launch);
  nothing is stored for it.
- `rigtuneVersion` and the mod-set hash of the player's pre-0.4 launches don't exist, so the real series' causes are mod
  count or none (its fixture has no hash).
- The crash-report setup is not the only cold-start cost; a cold disk cache still widens the floor (the no-change line
  names it).

## UNVERIFIED
- The floor and MIN_RUNS on other PCs: the evidence is this dev PC's launches (AC9.8) and the player's 44 launches of one
  instance; both are Windows 11 with the performance counters off.
- compat040 on the ws-w2 set (WS-E's interpreter isn't merged; its `StartupTimesStore` check kind is new): the released
  0.4.0 classes were run by hand instead (below).
