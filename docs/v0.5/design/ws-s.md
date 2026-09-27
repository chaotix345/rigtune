# WS-S: Stutter Doctor fixes (v0.5)

Branch `fix/v05-stutter` (worktree `rigtune-stutter5`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/PLAN.md "WS-S", SPEC 2S (L1, SD-1..SD-6, NEW-1, RW-10, RW-11, the RW-6 "DH world generation" sampler
bucket, AC2S.1 and AC2S.5-AC2S.14; L2 and AC2S.2-AC2S.4 are WS-R's) and RW-15's capture side (the contracts' seam
`StutterHooks.benchmarkStepExcluded`, AC2B.9 closes with the later of WS-B and WS-S). The verifier's corrected fixes
(docs/research/v0.5/audit-v040-verification.md) are the ones built. After this branch merges, WS-S2 (C20) owns
StutterService and the other shared stutter files.

This file is first the TDD task plan (committed before any code), then, as the work lands, the deviations, residuals,
UNVERIFIED items, the Docs text, the footprint deltas and the AC table with evidence.

## 0. The code-deciding run: NEW-1 (AC2S.11, done before any code)

Two real 26.2 clients on the dev PC (JDK 25.0.4, `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational -Xlog:gc`),
under the game-test lock, each a plain client running DevAutorun's `benchmark-world-tune` (the benchmark world, the full
default Tune, RD 12 → 32) with a measurement-only java agent that logs every GarbageCollectorMXBean notification's raw
strings and the heap pools before/after; the notifications are matched to the GC log's cycle kinds (Young, Old marking,
Global) by time. Run 1: default heap (7964 MB), 37 cycle notifications (4 global, 20 young, 12 young with an old
marking, 1 global from the dev `System.gc()`); run 2: `-Xmx2G`, 99 (4 global, 59 young, 35 + 1 with old marking).
Evidence: docs/v0.5/verification/stutter/new1-generational-shenandoah/.

**Result.** Every cycle notification, young, old-marking and global alike, is `Shenandoah Cycles | end of GC cycle |
Concurrent GC`; the only other cause seen is `System.gc()` on the explicit global cycle. The pause bean's notifications
(`Shenandoah Pauses | Init Mark / Final Mark / Init Update Refs / Final Update Refs | Concurrent GC`) carry no generation
either. The pools are `Shenandoah Young Gen` and `Shenandoah Old Gen` (HEAP), so the old pool is always "found", and
its usage after a young cycle includes floating garbage: RigTune's live set read 13 % (run 1; the log's old marking
found 752-873 MB live, 9-11 % of the heap) and 33 % (run 2; ~22 % by the marks' median).

**Decision (SPEC 2S NEW-1, Open question 5).** The notifications don't tell young from global/old cycles, so no GcKind
classifier is built: under generational Shenandoah (detected from the heap pool names captured once when the listener
starts: a `Shenandoah … Gen` pool) no notification is a live-set sample (MAJOR is never set), so `liveSetPercent` stays
UNKNOWN and `ram-stutter-gc-heap`'s live-set branch can't fire (fail closed). Full and degenerated collections keep
their FULL flag (the counts are unaffected); non-generational Shenandoah is unchanged apart from SD-6.

## 1. TDD task plan

Each task: the red test first (reusing the audit-verify throwaway tests where they exist), the fix, green, one commit.
Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot (`:26.3:` too where a class touches a 26.2/26.3
API difference: none expected); the full build and the game tests are CI's.

| # | task | files | red test(s) | closes |
|---|---|---|---|---|
| S0 | NEW-1 measurement (section 0) | docs/v0.5/verification/stutter/new1-generational-shenandoah/ | (real run) | AC2S.11 (real part) |
| S1 | **Early merge.** The "DH world generation" sampler bucket: `DH-World Gen…` threads get their own group; the `dh` tag and the busiest-group note still count every `DH-*` thread; the benchmark capture reports the bucket's CPU over its recorded sweeps | `StutterRings` (new sample slot, stride 13 → 14), `ThreadSampler.group`, `StutterAnalyzer` (dh = both slots; `dhWorldGenCores` over the unpaused spans), `StutterService`/`StutterHooks` (pause events on the benchmark capture's sweep transitions; `lastBenchmarkDhWorldGenCores()`) | `ThreadSamplerTest.dhWorldGenThreadsGetTheirOwnBucket`, `.aSteadyStateSampleAllocatesNothing` (DH names added), `StutterAnalyzerTest.dhWorldGenCpuOverTheRecordedSweepsOnly`, `.worldGenCpuStillTagsDh`, `FrameRingAllocationTest.retainedBytesCountTheRings` (new size, still within 2.5 MiB) | AC2S.14 |
| S2 | L1: `end()` survives a failing copy | `StutterService.end`, `StutterCapture` (copy seam) | `StutterServiceTest.aFailingCopyLosesOnlyThatSummaryAndSaysSo` (no exception out of `benchmarkStarted`, the specific WARN once, no capture held, the next session starts) | AC2S.1 |
| S3 | SD-3 + SD-4: `savedState` only where `saved` is assigned; `loadSaved` checks the generation | `StutterService` (+ an injectable io executor) | `StutterServiceTest.sd3AnUnsavedSessionStillShowsTheSavedSummary`, `.sd4ClearWhileTheSavedLoadIsQueuedKeepsItCleared` | AC2S.7, AC2S.8 |
| S4 | SD-5: null values dropped from `causes` and `tags` | `StutterReport` compact constructor | `StutterStoreTest.aHandEditedSessionWithNullsReadsSafely` extended (`spikes.minor = 1`, `"unknown": null`; `StutterSummary.text` too) | AC2S.9 |
| S5 | SD-6 + NEW-1's code: heap pools only without an old pool; no live-set sample under generational Shenandoah | `GcListener` (heap-pool names captured in `start()`), `GcKind` | `GcListenerTest.oldGenerationUsedSumsOnlyTheHeapPools` (Shenandoah 1 GB + Metaspace 400 MB + CodeHeap 100 MB → 1 GB; G1 and ZGC unchanged), `GcKindTest.generationalShenandoahIsNeverALiveSetSample` (each recorded string) | AC2S.10, AC2S.11 (unit) |
| S6 | SD-1: whole-capture full/explicit/stall counters (and the live-set samples) in `StutterRings.gc`; GC claim share over the spikes newer than the oldest held GC record, `dh`/`cpuContention` shares over the spikes newer than the oldest held sample; never "unmeasured" because a ring wrapped; `chunksLoading` keeps the whole capture | `StutterRings`, `StutterAnalyzer` | `StutterAnalyzerTest.sd1FullGcSurvivesTheGcRingWrapping`, `.sd1DhShareCountsTheCoveredSpikes`; `FrameRingAllocationTest`, `StutterMonitorTest` still 0 bytes per frame; a GC-record write still 0 bytes | AC2S.5 |
| S7 | SD-2: frames, average and 1 % low over the frame ring's window; "over the last %s" when the capture is longer | `StutterAnalyzer`, `StutterScreen` header, `StutterSummary`, lang `rigtune.stutter.window.*` | `StutterAnalyzerTest.sd2OnePercentLowIsNeverAboveTheAverage`, `StutterSummaryTest.theWindowIsNamedOnlyWhenTheCaptureIsLonger`, `StutterScreenTextTest.windowLine` | AC2S.6 |
| S8 | RW-10: no 0 % cause rows; shown whole percentages total ≤ 100 | `StutterScreen` (a static text function) | `StutterScreenTextTest.rw10NoZeroRowAndAtMostOneHundred` (gc 0.60, tick 0.18, chunkLoad 0.0, unknown 0.23) | AC2S.12 |
| S9 | RW-11: settings changes and resource reloads tag the next 10 s `settingsChanged` (never claims); `settingsAtStart`/`settingsAtEnd`; the report and advice say so | `StutterRings` (event kind), `Attributor` (the tag, outside the rules' `TAGS`), `StutterAnalyzer`, new `client/stutter/SettingsWatch` (its own END_CLIENT_TICK listener, registered when the first session starts), `StutterCapture`/`StutterService` (the maps), `StutterScreen`, `StutterSummary`, lang `rigtune.stutter.tag.settings_changed`, `rigtune.stutter.settings.*` | `StutterAnalyzerTest.rw11AReloadTagsTheNextTenSecondsAndClaimsNothing`, `StutterStoreTest.theSettingsFieldsRoundTripAndA04SessionStillReads`, `SettingsWatchTest` (a change → one event; unchanged → none; 0 bytes per tick; Iris/DH read at most once a second), `StutterScreenTextTest.settingsChangedLine` | AC2S.13 (unit parts) |
| S10 | RW-15 capture side: `benchmarkStepExcluded(true)` keeps that step's frames out of the benchmark capture (combined with the sweep flag), counts the steps; the benchmark line names them | `StutterService`, `StutterHooks`, `StutterScreen.benchmarkLine`, lang `rigtune.stutter.benchmark.excluded*` | `StutterServiceTest.anExcludedStepRecordsNoFrames`, `StutterScreenTextTest.theBenchmarkLineNamesExcludedSteps` | AC2B.9 (WS-S part; closes with the later of WS-B/WS-S) |
| S11 | Fixtures: `v050-written/ws-s/stutter.json` (a session with the RW-11 fields and tag) + `expect.json`, written by a test | `src/test/resources/v050-written/ws-s/`, `StutterWrittenV050Test` | the test compares (regenerates under `RIGTUNE_REGENERATE_FIXTURES=1`) | AC2S.13 (compat040 part, once WS-E's interpreter merges) |
| S12 | StutterGameTest: RW-11 in a real world (render distance changed mid-session → the event, the maps), the window/settings lines at the X12 sizes | `gametest/StutterGameTest` | (game test, 3 legs) | AC2S.13 (in game), X12 |

## 2. Design notes (decided before the code)

- **S1 bucket.** DH 3.3.2 names every pool thread `"DH-" + pool + " Thread…"` (`DhThreadFactory`, javap of the jar the
  audit used); its pools are Network Compression, Network Client Handler, IO, Render Loader, LOD Builder, Update
  Propagator, World Gen, Beacon Culling, Full Data Migration and Cleanup (`ThreadPoolUtil`). So `DH-World Gen` is the one
  world-generation prefix in 3.3.2 (other DH versions: UNVERIFIED). The new slot `S_DH_WORLD_GEN` is appended at index 13
  so every existing index stays; `GROUPS` (the contention note's group names) is unchanged and world-gen CPU counts as
  `dh` there and in the `dh` tag, whose meaning doesn't change. The ring grows by 4096 × 8 = 32 KiB (2,539,664 bytes with
  a session, under `monitorOnRetainedBytes` 2,621,440). "During the sweeps": the benchmark capture now writes
  PAUSE_BEGIN/PAUSE_END events when its recording flag flips (as the session's Pause already does); the analysis averages
  the world-gen CPU over the sample windows outside those pauses. `StutterHooks.lastBenchmarkDhWorldGenCores()` (a
  `@Nullable Double`, core-equivalents; null when nothing was sampled) is set by `benchmarkFinished` before
  BenchmarkController builds its record, so WS-B reads it for `Context.dhGenerating` (its threshold is WS-B's).
- **S7 window.** No new stutter.json field (SPEC: "stutter.json keeps its fields"): when the frame ring wrapped,
  `frames`, `avgFps` and `onePercentLowFps` are the held window's gameplay frames; otherwise all three are exactly as
  in 0.4. The histogram still counts every gameplay frame of the capture, so "the ring wrapped" is exactly
  `Σ histogramCounts > frames`, and the window's length is `frames / avgFps`. A 0.4 session always has
  `Σ histogramCounts == frames`, so it never gets the label.
- **S9 settings.** The four values: `renderDistance`, `simulationDistance` (options, cached ints), `shaders` (Iris
  `isShaderPackInUse`, only when Iris is loaded) and `dhRendering` (only when DH is loaded), as strings. Iris's API v0
  has no pack name (javap of `IrisApi`/`IrisApiConfig`), so a pack switch with shaders on is not detected (residual).
  A resource reload = vanilla's loading overlay appearing (`Minecraft.getOverlay()`, a field read) while a session runs:
  no mixin and no reload listener. The listener is registered once, on the first session start (never at init), and
  returns at once without a session; per tick it compares two cached ints and the overlay reference; Iris and DH are read
  on `settingsChanged()` and at most once a second. `settingsChanged` is not in `Attributor.TAGS` (the rules' tag
  vocabulary, SchemaConsistencyTest's tie to the updater), so no rule can condition on it and 0.4.0's screen, which shows
  only its own tags, ignores it.
- **S10 semantics.** `benchmarkStepExcluded(true)` from BenchmarkController when a step's settle timed out (before its
  sweeps), `false` when the next step starts or the re-measure runs; the capture is paused while excluded whatever the
  sweep flag says; each false → true counts one step; the count resets when a run starts.

## 3. As landed: what changed where

- **S1 (early merge, cc3f52e0).** `StutterRings.S_DH_WORLD_GEN` (13, stride 14); `ThreadSampler.DH_WORLD_GEN`
  (`"DH-World Gen"`); `StutterAnalyzer.samples` counts both DH slots as `dh` (tag and busiest group);
  `StutterAnalyzer.dhWorldGenCores(Input)` and `Result.dhWorldGenCores` (a fourth component: WS-S2's SessionOutcomeTest
  builds it); `StutterService` writes PAUSE_BEGIN/PAUSE_END when the benchmark capture's recording flips (and a
  PAUSE_BEGIN when it is created, paused); `StutterHooks.lastBenchmarkDhWorldGenCores()`.
- **S2 L1.** `StutterService.end` catches a failing `StutterCapture.stop` (WARN "the running session's summary was lost:
  its capture couldn't be copied", counted in `sessionsEnded`); `StutterCapture.copier` is the test seam.
- **S3 SD-3/SD-4.** `savedState = DONE` only where `saved` is assigned; `loadSaved` keeps a load only when the generation
  still matches; the io chain's executor is injectable (package-private constructor).
- **S4 SD-5.** `StutterReport`'s compact constructor drops null values from `causes`, `tags`, `settingsAtStart`,
  `settingsAtEnd` (the map itself when none is null, so the analyzer's order stays).
- **S5 SD-6, NEW-1.** `GcListener` captures the heap pools' names once per `start()`; `oldGenerationUsed(after, heapPools)`
  sums the old pool, else the heap pools, else nothing (no sample). `GcKind.classify(bean, action, cause,
  shenandoahGenerational)` drops MAJOR for Shenandoah beans in the generational mode; `GcKind.shenandoahGenerational(pools)`.
- **S6 SD-1.** `StutterRings.gc` counts full/explicit/stall collections (the analyzer's rules) and keeps live-set samples in
  their own 256-record ring (`LIVE_STRIDE` 3: received, bytes, flags); `Snapshot.totals` (null in hand-built snapshots)
  carries them with each ring's `added()`. The analyzer uses the counters once the GC ring wrapped, the live ring always;
  the rules' `gc` claimed share is over the spikes ending at or after the oldest held GC record (once wrapped), and the
  `dh`/`cpuContention` tagged shares over those ending at or after the oldest held sample's window (once wrapped). The
  screen's causes and tag counts stay whole-capture.
- **S7 SD-2.** When the frame ring wrapped, `frames`, `avgFps`, `onePercentLowFps` are the held window's;
  `StutterReport.windowSeconds()` (`Σ histogramCounts > frames` → `frames / avgFps`); `StutterScreen.framesLine` uses
  `rigtune.stutter.window.frames`; Copy summary appends "(over the last m:ss)".
- **S8 RW-10.** `StutterSummary.percentages(causes)`: rounded whole percentages, none at 0, the excess over 100 taken off
  the largest; used by the screen's cause rows (`StutterScreen.causeRows` for the tests), Copy summary and the benchmark
  line.
- **S9 RW-11.** `StutterRings.SETTINGS_CHANGED` (9, value = what changed); `Attributor.SETTINGS_CHANGED`
  (`"settingsChanged"`, in `REPORT_TAGS`, not in `TAGS`), `Context.settingsChanged` (a spike ending in (t, t + 10 s]);
  `StutterReport.RENDER_DISTANCE`/`SIMULATION_DISTANCE`/`SHADERS`/`DH_RENDERING`, `settingChanges()`, `onOff()`;
  `client/stutter/SettingsWatch` (the listener, `State` for the tests, `values()` for the maps, `cost()` for the game
  test); `StutterMonitor.Capture.settingsAtStart`, `StutterCapture.Copy.settingsAtStart`, `Machine.settingsNow`; the
  screen's settings line (`StutterScreen.settingsLine`) and advice note, Copy summary's two lines; lang
  `rigtune.stutter.settings.*` and `rigtune.stutter.tag.settings_changed`.
- **S10 RW-15.** `StutterService.benchmarkStepExcluded` / `StutterHooks.benchmarkStepExcluded` (the WS-K seam, filled),
  `lastBenchmarkExcludedSteps()`; `StutterScreen.benchmarkLine(r)` = `benchmarkLine(r, StutterHooks.lastBenchmarkExcludedSteps())`
  with `rigtune.stutter.benchmark.excluded.one`/`.many`. BenchmarkResultScreen needs no change for the line; WS-B's
  BenchmarkController only has to call the seam.
- **S11.** `src/test/resources/v050-written/ws-s/` (`stutter.json`, `expect.json`) from `V050WrittenWsSTest` (the
  26.3 node compares the same bytes). compat030 against the set: the released 0.3.0 jar (sha256 5717f65c…, as pinned in
  build.yml) on the v040-written instance with ws-s's `stutter.json` in place of 0.4's (compat030's `written.py` doesn't
  know the v0.5 sets yet): `RESULT PASS`, "0.3.0 reading them changed no file: 9 file(s) unchanged". No
  `placeholder/ws-s/` exists, so none was deleted.
- **S12.** StutterGameTest: after the capture checks, the render distance +2 → a SETTINGS_CHANGED event; the settings
  check's cost (`StutterHooks.settingsCheckCost`); after Stop the saved session's `settingChanges()` is exactly that
  change; the render distance is put back.

## 4. Deviations, residuals, UNVERIFIED

**Deviations.**
- NEW-1's runs were ~3 minutes each (DevAutorun's full Tune), not the SPEC's 10-minute session; between the two runs
  every generational cycle kind occurred (young, old marking, global from a heuristic and from `System.gc()`), and the
  strings never differed (section 0).
- SD-2: when the frame ring wrapped, `frames` (as well as the average and the 1 % low) is the window's count, so the
  header's three numbers describe one window; the window is read off the existing fields (no stutter.json field).
- SD-1: only the rules' facts use the coverage-restricted denominators; the screen's cause shares and tag counts stay
  over the whole capture (shares of the lost time and "n of N spikes", honest as they are). The live-set samples get a
  256-record ring (+6 KiB; the verifier's "a few longs" can't hold a median).
- RW-10 is applied to Copy summary and the benchmark result line too (the same numbers everywhere).
- RW-11: the map keys are `renderDistance`, `simulationDistance`, `shaders`, `dhRendering` (the benchmark's knob names),
  not settings keys; a resource reload is vanilla's loading overlay appearing or going away (`minecraft.gui.overlay()`,
  javap'd on 26.2 and 26.3), so no mixin and no reload listener; a change is dated when the old value was last seen
  (the tick before, or the last Iris/DH read up to 1 s before), so a pipeline rebuild's own spike falls in the window.
- RW-11's listener cost is measured in StutterGameTest (logged, 0 bytes asserted), not under FootprintGameTest keys
  (ws-ci/WS-K's file; asked the coordinator).
- TDD: the tasks that added an API (S1, S9, S10, S11) went red by not compiling; SD-2's assertion red is the audit's run
  on this code (avg 80, 1 % low 200); every bug fix (L1, SD-1, SD-3..SD-6, NEW-1, RW-10) was run red first with today's
  behaviour.

**Residuals.**
- Iris' API v0 has no pack name, so switching shader packs with shaders on isn't detected (only on/off).
- The events ring (4096) can also wrap in a very long session (saves, teleports, movement, settings changes); not in
  SD-1's scope.
- `DH-World Gen` is DH 3.3.2's world-generation prefix (javap); other DH versions UNVERIFIED.
- Pre-existing: Copy summary's `%n` gives CRLF on Windows for some lines and LF for others; the histogram's "(1 frames)".

**UNVERIFIED.**
- AC2S.13's compat040 part (0.4.0's StutterStore and StutterSummary read the ws-s set): `expect.json` has the
  StutterStore check; a StutterSummary check kind needs WS-E's interpreter (asked the coordinator).
- RW-11 with Iris or Distant Horizons loaded (the shaders and DH bits): unit-tested only; CI loads neither.
- AC2B.9 closes with WS-B's BenchmarkController call.

## 5. Docs (for the docs workstream)

DESIGN.md "Stutter Doctor", a v0.5 paragraph:
> **v0.5.** The sampler has a "DH world generation" group (`DH-World Gen…` threads), still DH work for the `dh` tag; a
> benchmark reports its CPU over the recorded sweeps for the run's `dhGenerating`. Long sessions: full, explicit and
> stall collections are counted over the whole capture and live-set samples keep their own ring; the rules' GC, DH and
> CPU-contention shares are taken over the spikes the rings still cover. Once a capture outgrows the frame ring the
> header's frames, average and 1 % low describe the ring's window and say "over the last m:ss". Cause percentages never
> show 0 % and never total more than 100. A settings change (render or simulation distance, shaders on/off, DH
> rendering) or a resource reload tags the spikes of the next 10 s "settings changed" (never claims time); a session
> stores those four values at its start and end, and the report names what changed. Under generational Shenandoah the
> live set isn't measured (its young and global cycles notify alike). A benchmark step whose settle ran out of time is
> left out of the benchmark's capture and the result line says so.

README known limits:
- "Stutter Doctor doesn't measure the live set under generational Shenandoah (`-XX:ShenandoahGCMode=generational`): the
  JVM's notifications don't tell young from full collections."
- "Switching shader packs while shaders stay on isn't recognised as a settings change (Iris doesn't report the pack)."

## 6. Footprint deltas (against ws-k.md section 16's per-leg baseline, run 36310249248)

WS-S adds no init work: SettingsWatch loads and registers when the first session starts; the rest is inside existing
classes on their existing paths. `monitorOnRetainedBytes` grows by 38,912 bytes by design (the DH slot 32,768, the live
ring 6,144): 2,545,808 of 2,621,440.

| leg | key | baseline | 36319389803 (6496f2cd, S1) | 36328669112 (f9aada54, S1-S12) |
|---|---|---|---|---|
| 26.2 OpenGL | renderThreadInitCpuMs | 82.2 | 109.3 | 97.9 |
| 26.2 OpenGL | clientStartedWallMs | 36.4 | 53.1 | 37.7 |
| 26.2 OpenGL | workerCpuMs5s | 135.5 | 208.9 | 173.6 |
| 26.2 OpenGL | tickHookOnVsReference | 1.481 | 1.712 | 1.604 |
| 26.3 OpenGL | renderThreadInitCpuMs | 82.2 | 85.0 | 105.0 |
| 26.3 OpenGL | clientStartedWallMs | 27.0 | 24.4 | 45.7 |
| 26.3 OpenGL | workerCpuMs5s | 153.2 | 138.6 | 174.1 |
| 26.3 OpenGL | tickHookOnVsReference | 1.746 | 1.348 | 1.591 |
| 26.3 Vulkan | renderThreadInitCpuMs | 120.0 | 82.7 | 101.7 |
| 26.3 Vulkan | clientStartedWallMs | 39.9 | 11.7 | 33.0 |
| 26.3 Vulkan | workerCpuMs5s | 200.7 | 141.4 | 180.1 |
| 26.3 Vulkan | tickHookOnVsReference | 1.535 | 1.451 | 1.570 |
| all | monitorOnRetainedBytes | 2,506,896 | 2,539,664 | 2,545,808 |
| all | RW-11 settings check (StutterGameTest) | (new) | | 50.7 / 89.8 / 48.2 ns per call; 32 bytes per 100,000 calls |

Every value moves inside ws-k.md's runner-to-runner spread (e.g. 26.2 renderThreadInitCpuMs 63.5-112.9 on 0.4's code)
in both directions, and every budget keeps its margin; `v05RenderThreadResolve` null on every leg.

**Screenshots looked at.** `gametest-screenshots-26.2-OpenGL` of 36319389803 (`stutter-1280x720-scale2`: unchanged
screen) and of 36328669112 (`stutter-640x480-scale2`: the new "Settings changed during this session (render distance
5 → 7)" row wraps inside the column, the buttons below the list; `stutter-saved`: the second session, no change, no row).

## 7. AC table

| AC | status | evidence |
|---|---|---|
| AC2S.1 (L1) | verified | StutterServiceTest.aFailingCopyLosesOnlyThatSummaryAndSaysSo (red: the exception reached benchmarkStarted); CI java job |
| AC2S.2-AC2S.4 (L2) | WS-R's | — |
| AC2S.5 (SD-1) | verified | StutterAnalyzerTest.sd1FullGcSurvivesTheGcRingWrapping, .sd1DhShareCountsTheCoveredSpikes (100 % ≥ 40, dh measured), .sd1GcShareCountsTheSpikesTheGcRingCovers, .aGcRecordAllocatesNothing; FrameRingAllocationTest, StutterMonitorTest 0 bytes per frame |
| AC2S.6 (SD-2) | verified | StutterAnalyzerTest.sd2OnePercentLowIsNeverAboveTheAverage (+ no label under the ring), StutterSummaryTest.theWindowIsNamedOnlyWhenTheCaptureIsLonger, StutterScreenTextTest.theFramesLineNamesTheWindow |
| AC2S.7 (SD-3) | verified (unit) | StutterServiceTest.sd3AnUnsavedSessionStillShowsTheSavedSummary (red: report null) |
| AC2S.8 (SD-4) | verified | StutterServiceTest.sd4ClearWhileTheSavedLoadIsQueuedKeepsItCleared (injectable executor; red: the summary came back) |
| AC2S.9 (SD-5) | verified | StutterStoreTest.aHandEditedSessionWithNullsReadsSafely (spikes.minor 1, "unknown": null; Copy summary text) |
| AC2S.10 (SD-6) | verified | GcListenerTest.oldGenerationUsedSumsOnlyTheHeapPools (red: 1546 MB instead of 1024) |
| AC2S.11 (NEW-1) | verified | real runs: docs/v0.5/verification/stutter/new1-generational-shenandoah/; GcKindTest.generationalShenandoahIsNeverALiveSetSample |
| AC2S.12 (RW-10) | verified | StutterScreenTextTest.rw10NoZeroRowAndAtMostOneHundred (red: "Chunk loading 0 %", 101 %), StutterSummaryTest.rw10PercentagesNeverTotalOverOneHundred |
| AC2S.13 (RW-11) | unit + game test verified; compat040 part closes with WS-E's interpreter | StutterAnalyzerTest.rw11AReloadTagsTheNextTenSecondsAndClaimsNothing, StutterStoreTest.theSettingsFieldsAndTagRoundTripAndA04SessionStillReads, SettingsWatchTest (4), StutterScreenTextTest.settingsChangedLines, StutterSummaryTest.rw11SettingsChangesAreNamed; StutterGameTest on 3 legs (run 36328669112); v050-written/ws-s + expect.json |
| AC2S.14 (DH bucket) | verified | ThreadSamplerTest.dhWorldGenThreadsGetTheirOwnBucket, .aSteadyStateSampleAllocatesNothing (DH names), StutterAnalyzerTest.dhWorldGenCpuOverTheRecordedSweepsOnly, .worldGenCpuStillCountsAsDh, StutterServiceTest.aFinishedBenchmarkReportsItsDhWorldGenCpu; merged early (cc3f52e0) |
| AC2B.9 (RW-15) | WS-S part verified; closes with WS-B | StutterServiceTest.anExcludedStepRecordsNoFrames, StutterScreenTextTest.theBenchmarkLineNamesExcludedSteps |

CI: run 36319389803 (S1, 6496f2cd) and 36325283747 (S1-S8, 8bd62a8d) and 36328669112 (S1-S12, f9aada54): every job
green on all three legs.
