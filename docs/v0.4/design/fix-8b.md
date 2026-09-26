# fix-8b: review round 2 fixes (stutter, UI/a11y, awareness, footprint, profiles) and Phase 5 follow-ups

Branch `fix/review-8b` (from `origin/feat/v0.4.0` @ e7c44b0). Findings: docs/reviews/review-8.md (PR-1, PR-2, ST-1,
ST-2, ST-3, JW-2, BF-1, UV-1..UV-4, SE-2) and docs/v0.4/verification/P5A-FINDINGS.md / P5B-FINDINGS.md (P5A-F2, P5A-F3,
P5B-F3, P5B-F4), plus the coordinator's P5-A F5 (footprint budget under ZGC). Fix-8a owns the apply/compat/security
core items (AH-1, CR-1, JW-1, SE-1/3/4, CR-2); nothing here touches core/apply, core/history, core/modrinth, core/store,
tools/ or rules/.

## What landed (each with a test that failed first)

| item | fix | tests |
|---|---|---|
| PR-1 | `PreviewScreen.Confirm` gains a `ready` check (old 4-arg constructor kept, ready = true); Apply and Save only are active only when the preview loaded, didn't fail and `ready` holds. ProfileImportScreen's ready = the import is `ok()`. | ProfilesGameTest: a well-formed code while ProfileService refuses as not ready: the note shows, Apply and Save only inactive (screenshot `profiles-import-not-ready`). |
| PR-2 | ProfileService's offer is an `AtomicReference`; the render thread clears only the offer it read (`retire` = compare-and-set), in batteryNotice, batteryAction and after a switch (the offer read at the switch's start). | ProfileServiceOfferTest (stale read clears nothing; 200 contended rounds). |
| ST-1 | ThreadSampler.stop() never joins: each start gets its own Worker holding its own rings; stop takes them away and interrupts; the thread ends on its own and writes nothing after that (the rings are re-read after the JDK calls). Game tests wait up to 100 ticks for the thread to be gone. | ThreadSamplerTest.stopNeverWaitsAndAStoppedWorkerStaysOutOfTheNextCapture (a source that ignores the interrupt: stop returns in < 100 ms, was ~1000 ms; the old worker writes nothing into its own or the next capture's rings). |
| ST-2 | FrameRing keeps one `int` per frame next to its end: packets/ticks/render excess over the running baselines (9-bit codes of 64 ns units, 4-bit mantissa, rounded down, so at most 1/16 under and never over) and the frame's chunk loads (5 bits, saturating at 31). StutterAnalyzer uses a spike's candidate record when it has one (exact, as before) and the frame's phase word otherwise, so a spike still in the frame ring keeps its evidence however many candidates follow. Candidate records remain the history older than the frame ring. | StutterAnalyzerTest.aSpikeKeepsItsPhasesAfterItsCandidateRecordIsEvicted (red first: claims 0); FrameRingPhasesTest (encoding bounds, alignment, wrap); FrameRingAllocationTest (0 bytes over 1 M frames; retained bytes). |
| ST-3 | The frame and phase baselines re-warm after an excluded span or a pause of at least 1 s (world loading, a dimension change, Pause): they reseed from the first gameplay frame and use alpha 1/4 for 16 frames, then 1/32. A shorter exclusion (a menu glance) keeps them. The same fast warm-up applies at a capture's start. A frame with no phase baseline yet has no excess. | FrameRingPhasesTest.phaseBaselinesReWarmAfterALongExcludedSpan / aShortExclusionKeepsTheBaselines / phaseBaselinesReWarmAfterAPause. |
| JW-2 | ChangeDetector: when the backend switched and both drivers parse to the same family, only the parts both versions have are compared (GL `550.54.14` vs Vulkan `550.54` is the same driver). On one backend every part still counts; a real update across a switch still raises the notice. | ChangeDetectorTest.aBackendSwitchComparesOnlyThePartsBothBackendsReport (incl. the P5-A AMD pair GL `Context 26.8.1.260810` / Vulkan `AMD proprietary driver 26.8.1`). |
| BF-1 | `ModSetHash.ofLoadedMods` (RigTune's own id left out) is the one hash for benchmarks.json and startup-times.json. | ModSetHashTest.theLoadedModsHashLeavesRigTuneOut; FootprintGameTest: the recorded startup hash equals `BenchmarkConditions.modSetHash()`. |
| UV-1 | `RowFocus.frame` gives the four 1 px edges around exactly the row (the bottom edge was a row short). | RowFocusTest.theFocusFrameOutlinesTheWholeRow (every edge pixel once, nothing inside). |
| UV-2 | `RowFocus.standalone(message, x, y, w, h)`: a Tab stop placed over a line of text, narrating it, drawing only its focus frame while focused, never taking clicks. RigTuneScreen's notice line (message + detail; inline and behind the narrow "…" button) and each NoticeScreen row get one, ahead of their buttons. | A11yGameTest.standaloneText: at 854x480@2 and 640x480@2 Tab reaches a stop whose narration (ScreenNarrationCollector) holds the notice's message and detail; NoticeScreen the same; screenshots `a11y-notice-focus-*`. RowFocusTest.aStandaloneFocusNarratesAndSitsWhereItIsPlaced. |
| UV-3 | BenchmarkHistoryScreen: one standalone stop per line (note, trend/regression, changes, last benchmark, rerun marker) over the rows it wraps to. The chart stays painted (SPEC "Deferred": v0.5). | A11yGameTest: every `shownLines()` text is narrated by some Tab stop. |
| UV-4 | ToolsScreen: the startup line and each note under it (mod-set note, advice) are standalone stops. | A11yGameTest: the startup line and the advice are narrated. |
| SE-2 | `core/model/SafeText.clean`: drops a formatting code (U+00A7 and the character after it), control, format (bidi overrides and isolates, U+200B, U+FEFF, ...), private-use, surrogate and unassigned characters; tabs, line breaks and line/paragraph separators become a space; the joiners U+200C/U+200D and anything else are kept (the unchanged string is returned as is). `client/ui/SafeLiteral.of` wraps it. | SafeTextTest; TextsTest.outsideTextIsInert. |
| P5A-F2 | A never-claiming tag `chunksLoading` ("chunks loading"): chunk loads within 250 ms of the spike's frame, from the frame ring's phase words (or, for spikes older than the frame ring, the candidate record's own and previous frame's loads). Causes read "N of M spikes happened while chunks were loading (not measured)" (one spike: "The spike happened while chunks were loading (not measured)"). Its worst-spike note is `chunksLoading:context`, left out next to a chunk-loading claim or note (which already says so). Copy summary: the same sentence. | AttributorTest.chunksLoadingNearbyIsATagThatClaimsNothing; StutterAnalyzerTest.hitchesWhileChunksLoadCarryTheTagAndClaimNothing (a synthetic teleport trace: the 8 post-teleport hitches tagged, the earlier one not, causes stay 100 % not explained) and theTagSurvivesInCandidateRecordsOlderThanTheFrameRing; StutterScreenTextTest; StutterSummaryTest. |
| P5A-F3 | A monitor session that a benchmark run interrupted (`Capture.aroundBenchmark`, set when the benchmark capture starts) is saved only with the "enough data" gameplay time, 2 minutes, however few spikes (`StutterStore.worthSaving`; self-review M1: a long smooth session after a benchmark in the player's own world is kept). The run's own capture (source `benchmark`) is saved and becomes the summary StutterScreen shows. Every other session is saved as before, short or not (AC5.7 needs its short session). | StutterStoreTest.aShortSessionAroundABenchmarkIsNotSaved; BenchmarkGameTest: the Measure after run with the monitor on leaves a `benchmark` summary and no `monitor` one, checked after the session's end was handled (`StutterHooks.sessionsEnded`). |
| P5B-F3 | Singular/plural keys: `rigtune.stutter.count.{spikes,freezes,hitches}.{one,many}` inside `rigtune.stutter.header.spikes`, `rigtune.stutter.tag.one`, `rigtune.stutter.benchmark.{spikes,unexplained}.one`; the Copy summary's counts too. | StutterScreenTextTest (at en_us.json's text), StutterSummaryTest.singularCountsAndTheChunkTag. |
| P5B-F4 | BenchmarkResultScreen wraps every status line to the width (`font.split`). Over the row budget (the table keeps its header and 3 rows, or the chart its room) the trend's later lines give way first (review M3, as before), then the line wrapped to the most rows folds back to one clipped row whose full text is its tooltip (`fit`). | BenchmarkResultScreenTest.statusLinesWrapWithinTheirRows; BenchmarkHistoryGameTest: at the 3 sizes every status row fits the width and the table keeps its room; no line is clipped at 1280x720 and 854x480; screenshots `bench-result-wrapped-*`. |
| P5-A F5 | FootprintBudgets.forCompressedOops(false) doubles each shallow-size budget (`SHALLOW_SIZE_KEYS` = rigtuneClassBytesIdle), capped at its ceiling; every other budget, the leak checks included, is unchanged. FootprintGameTest reads `UseCompressedOops` (HotSpotDiagnosticMXBean; unreadable = compressed) and records it as `compressedOops`. Why: without compressed oops (ZGC always, heaps over 32 GB) references are 8 bytes and headers larger, so the same objects measure up to about twice as big (the local ZGC run: 120,224 B vs about 72 KB under G1, no instance growth). | FootprintBudgetsTest.shallowSizeBudgetsDoubleWithoutCompressedOops (the ZGC run's 120,224 B fails the G1 limit, passes the doubled one). |

## SE-2: where outside text is drawn (grep of `Component.literal(` and `Texts.component(` in src/client)

Everything below now goes through `SafeLiteral`/`Texts.component` (which cleans literals and string arguments):
- Texts.component (Text.Literal values and String args): RigTuneScreen recommendation titles/reasons (rules), JvmScreen
  advice titles/reasons (rules), notices (what's-new names from the rules, driver strings, server numbers),
  BenchmarkHistoryScreen/BenchmarkResultScreen trend lines (History labels, mod names), PreviewScreen download/skip
  titles, ProfilesScreen names, LauncherLines.
- StutterScreen: the fired stutterAdvice title and text (rules feed; the direct `Component.literal` SE-2 names).
- PreviewScreen: `labels.label(key)` and `labels.value(...)` (rules `settingLabels`), file names, raw values.
- HistoryScreen.describe: setting labels (rules `settingLabels`), mod and file names, values; the profile label.
- BenchmarkHistoryScreen: a context key without an example run (benchmarks.json data).
- RigTuneScreen: CPU/GPU names in the header; JvmScreen: JVM vendor, collector and flag names; LauncherLines: the found
  flag names; ProfileImportScreen: the decoded (already sanitised) name.
Left as they are: literals of numbers and RigTune's own format strings (tier digit, percentages, " · " separators).

## Decisions and deviations

1. **ST-2 by a per-frame phase word, not a bigger candidate ring.** A candidate ring smaller than the frame ring can
   always be outrun (an alternating 10/30 ms pattern makes half the frames candidates and none spikes), and a ring as big
   as the frame ring would be 10 MiB. The phase word makes the guarantee structural (a spike's evidence lives exactly as
   long as its frame) for 4 bytes a frame: retained bytes with the monitor on go from 1,982,464 to 2,506,752 B, under
   `monitorOnRetainedBytes`' limit of 2,621,440 B (2.5 MiB, = its ceiling; WS-F's rule 2 x max would be above the
   ceiling, so the limit stays; no budget file change). The benchmark's ring grows by 128 KiB. The unit test's bound now
   uses the budget's 2.5 MiB (it had 2,500,000 decimal). Tooltips and README say "about 2.5 MB". Per-frame cost: 24.96
   ns/call monitor on, 162.6 ns with the phase timers (local FrameHookBudgetTest; budgets 75 / 400).
2. **Per-frame excess is rounded down** (at most 1/16 under): a fallback claim never exceeds what was measured.
3. **ST-3's threshold is 1 s of excluded time or pause** (measured from the last gameplay frame, so a Pause, which skips
   frames, counts). The fast warm-up (alpha 1/4 for 16 frames) also runs at a capture's start, where every baseline was
   already fresh.
4. **P5A-F2's window is 250 ms** (the sampler's period; the P5-A C4 run loaded 637 chunks in 12 s). The tag isn't in
   tools/update_rules.py's `stutterTaggedShareAtLeast` vocabulary (tools/ is fix-8a's), so no rule can use it yet; the
   Java evaluator accepts it (Attributor.TAGS). A follow-up can add it to the updater's set.
5. **P5A-F3 keeps benchmark-interrupted short sessions out** rather than dropping every short session: AC5.7 (StutterGameTest)
   requires leaving the world to write its (short) session. The benchmark's own capture becomes StutterScreen's saved
   summary after a run.
6. **JW-2 applies the common prefix only across a backend switch**; on one backend a different number of parts is a
   different reported driver.
7. **BF-1's one-time effect:** the first launch of this build sees a different hash than the previous (RigTune-included)
   run, so the Tools line says "the mod set changed" once; 0.4.0 isn't released, so only dev instances see it.
8. **UV-2 to UV-4 add Tab stops.** On RigTuneScreen with a notice, the notice text is now the stop after the settings
   and goal buttons, and after keyboard use a rebuilt screen puts the initial focus there (vanilla's first-widget rule).
   The game tests' "label fits" layout check skips RowFocus widgets (they draw no label; their message is narration).
9. **PR-1's `ready` also gates Apply** (Apply already needed a non-empty preview; the not-ready preview is empty).
10. **P5B-F4 clips only as a last resort**, with the full line as a tooltip, so the table keeps its header and 3 rows.

## Self-review

A code-reviewer subagent reviewed the three fix commits: 0 high, 1 medium, 6 low, 2 nits. Fixed:
- M1: the P5A-F3 gate dropped a long, smooth session that merely overlapped a benchmark in the player's own world
  (`enoughData` needs 3 spikes too). It now needs only the 2 minutes of gameplay (test case added).
- L2: StutterGameTest's "no sampler thread after leaving" waits for the thread like the other checks (stop no longer
  joins).
- L3: BenchmarkGameTest's P5A-F3 check waits until the benchmark world's session end was handled
  (`StutterHooks.sessionsEnded`), so it can't pass before the decision ran.
- L5: SafeText keeps ZWNJ/ZWJ (emoji sequences, Persian and Indic names).
- L6: BenchmarkHistoryScreen counts a line's rows exactly as it draws them (an empty line adds no stop and no row).
- L7: a switch from the battery offer passes the offer it already retired instead of re-reading the slot.
- Nits: a misplaced comment (A11yGameTest) and import order (FootprintGameTest).
Kept, with reasons:
- L4 (BF-1's one-time "mod set changed" on the first launch of this build): startup-times.json is new in 0.4 and only
  0.4.0-dev builds wrote the RigTune-included hash, so released 0.4.0 users never see it; a hash-scheme marker in the
  file isn't worth it for dev instances (decision 7 above).

## AC5.8 C amendment (text for the coordinator, SPEC amendments)

> AC5.8 C (amended after P5A-F2): the teleport script's hitches in the 30 s after the teleport carry the tags "after
> teleport" and "chunks loading" (the causes read "N of M spikes happened while chunks were loading (not measured)",
> chunk loads > 0 in the evidence); chunk loading or chunk building is claimed in milliseconds only where it was measured
> (a packets-phase excess with chunk loads, or a Sodium backlog / waiting Chunk Updates mode); none is claimed as GC
> without an overlapping pause; the save window is recorded; the unexplained remainder is shown. A fast PC whose builder
> threads keep up (no backlog) passes with the time "not explained" plus both tags.

## Verification

- Unit tests: 26.2 1807 (1 skipped), 26.3 1807 (1 skipped), all green locally (`:26.x:test`), gametest sources compile
  on both. Baseline before: 1777.
- CI run 36250644430 (the three fix commits merged with origin/feat/v0.4.0): every job green (java, python, rules,
  gametest-matrix, client game tests 26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan). Screenshots looked at (26.2 leg):
  `bench-result-wrapped-*` (854x480 and 1280x720: every line wrapped, none clipped; 640x480@2: two lines wrapped, four
  folded to one clipped row with tooltips, so the table keeps its header and 3 rows; before, six were clipped with no
  tooltip), `a11y-notice-focus-*` (the focus frame round the notice text, inline and behind "…"),
  `a11y-benchmark-history-focus-*`, `profiles-import-not-ready` (the note, Apply and Save only greyed),
  `stutter-1280x720-scale2` (plural header). Footprint artifact (26.2): monitorOnRetainedBytes 2,506,896 B,
  rigtuneClassBytesIdle 73,376 B, compressedOops true, no violations.
- The follow-up commit with the self-review fixes: CI run in the hand-back.

## UNVERIFIED

- AC5.8 C re-run with the tag on the dev machine (Phase 5 re-run after this merges).
- Speech from a real screen reader (the tests check the text vanilla's narration pipeline collects, as ws-x).
- The new tag in rules (needs tools/update_rules.py's vocabulary, fix-8a's area).
