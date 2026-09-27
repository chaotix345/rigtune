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

## 3. Deviations, residuals, UNVERIFIED
(filled as the work lands)

## 4. Docs (for the docs workstream)
(filled as the work lands)

## 5. Footprint deltas (against ws-k.md section 16's per-leg baseline, run 36310249248)
(filled from this branch's CI runs)

## 6. AC table
(filled at the end)
