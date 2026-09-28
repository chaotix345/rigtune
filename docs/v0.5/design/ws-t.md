# WS-T: C09 Measured Try It (v0.5, SPEC 6)

Branch `feat/v05-try-it` (worktree `rigtune-tryit`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged). Scope:
docs/v0.5/SPEC.md 6 (AC6.1-AC6.17), C8 (Busy: Try It's hook and its caller entry), X4.4 (own tick listener); research
docs/research/v0.5/feature-try-it.md (ti). Scope shipped: the full design incl. RESTART (the user's decision of 2026-09-28: no
P1 cuts; SPEC 6 "Scope that ships").

Two phases (coordinator, 2026-09-27):
- **Phase 1 (Wave A): the pure core**, in `core/tryit/*` only (new files, plus WS-K's two shells there, `TryItStore` and
  `TryItView`). No existing file outside `core/tryit/` is touched (WS-B and WS-W change BenchmarkController,
  BenchmarkMenuScreen and PreviewScreen in parallel); so no en_us.json key yet: the core answers enums and numbers, and
  the words (TryItText + `rigtune.tryit.*`) come with the client in phase 2.
- **Phase 2 (Wave B, after WS-B's part 1 and WS-W merge):** the client part, the lang block, BenchmarkHistory, the game
  tests, the fixtures and AC6.16's run.

This file is first the TDD task plan (committed before any code), then, as tasks land, what landed.

## TDD task plan

Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot (core is version-independent: no `//?` block);
the full build is CI's.

### Phase 1: pure core (`src/main/java/io/github/chaotix345/rigtune/core/tryit/`)

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| T1 | The tryit.json model: `TryIt` (the stored identity, ti §2.4, plus `afterSession`), `TryIt.Decision`, `TryIt.Closed` (a `recent` row), and `TryItStore`'s typed accessors on WS-K's shell: `current()`, `recent()`, `open(TryIt)` (refused while a valid try is open), `change(id, UnaryOperator<TryIt>)`, `close(id, Closed)` (`recent` newest first, at most 10, pruned further to fit 16 KiB). Every value type-checked on read; unknown fields at any depth kept (inside `current` and each `recent` row too); snapshots only `ShareKeys.MANAGED` keys with safe values | `TryIt`, `TryItStore` | `TryItStoreTest`: a round trip of every field; missing file = no try, writable; `open` refused while one is open and on a newer file (read-only, never written); corrupt -> `.bad`; hand-edited wrong types ignored (a number for a string, a map value that isn't a string, a `current` without its ids, a `pairId` without `tryit-`); unknown fields at any depth survive `change` and `close`; `recent` pruned to 10 and to the byte cap (oldest first); a snapshot keeps only managed keys. WS-K's `V05StoreShellsTest` stays green | AC6.10 (unit) |
| T2 | Eligibility: `Triable.check(Recommendation, Context, @Nullable Scene)` -> `Result(kind, scene, refusal)` in SPEC 6's order: not SetSetting (KIND); `vanilla.maxFps`/`enableVsync`/`inactivityFpsLimit` (UNMEASURABLE); `graphicsPreset` (PRESET); another vanilla key outside `VANILLA_ALLOWED` or an unsafe key (UNSUPPORTED); other vanilla -> NOW; `sodium.`/`dh.`/`iris.` with a target file -> RESTART, without (NO_FILE); SD on a remote server in the CURRENT scene (REMOTE_SD); RESTART while anything is staged (PENDING); the shared busy check (BUSY: the client passes `Busy.refusal`'s answer); the scene unavailable (SCENE: `BenchmarkController.unavailable` for the scene); another try open (OPEN); journal not writable (HISTORY); benchmarks.json unreadable or tryit.json not writable (STORAGE). `selection(List)` (ONE unless exactly one ticked). Scenes: NOW offers CURRENT and BENCHMARK_WORLD (default: CURRENT in a world, else the benchmark world); RESTART always BENCHMARK_WORLD. The static tables: `allowed(key)` (RD -> RENDER_DISTANCE; SD -> SIMULATION_DISTANCE; `iris.enableShaders` -> SHADERS, SHADER_PACK; DH `rendererMode` -> DISTANT_HORIZONS), `LIFTED`, `sceneContent(key)` | `Triable` | `TriableTest`: each row alone and the order (a DisableMod while busy is KIND, a Sodium key while pending and busy is PENDING, ...); every `SettingKeys.VANILLA_ALLOWED` key and every setting key in `rules/rules-v2.json` and the bundled `rigtune/rules-v2.json` (every `key` field, every `settingLabels` key) has an explicit row in the test's table (kind or refusal, allowed differences, scene-content): a new rule key fails the test until someone decides; AddMod/UpdateMod/DisableMod/None refused as KIND; scenes and defaults | AC6.1 (unit) |
| T3 | The verdict: `TryItVerdict.of(TryIt, before, after, runs, journal entries)` -> `Verdict(kind, lowPercent, avgPercent, floorPercent, causes, caveats)`. Floor = max(2 x max(cvBefore or 0.05, cvAfter or 0.05, `MIN_CV` 0.025) x 100, the trend floor `BenchmarkTrend.noiseFloorPercent(before.cv, lows of BenchmarkTrend.baseline(before, runs))` when that baseline has >= 3 runs). BETTER / WORSE / NO_CLEAR_CHANGE at +-floor (1e-9); NO_NUMBERS (a run without a result); NOT_COMPARABLE with its causes in order: context differences (`BenchmarkTrend.differences` minus `Triable.allowed(key)`, in the enum's order), the mod sets (both hashes known and different), History entries after the try's entry up to the after run's `journalCursor` (by time when the cursor is gone), other than an undo of the entry, with a change that isn't DISCARDED/ABANDONED (oldest first), managed settings other than the key and the lifted three that differ between `settingsBefore` and `settingsAfter` (ShareKeys table order). Caveats: NOISY (either CV > 5 %), WORLD_CONTENT (a scene-content key in the benchmark world), DH (either run with Distant Horizons rendering or generating), SESSIONS (the after run in another session than the try started), SCENE (always) | `TryItVerdict` | `TryItVerdictTest` golden table: floor 5 % with tiny CVs; 2 x CV above 2.5 %; a missing CV counts 5 %; the trend floor with >= 3 earlier comparable runs (and not with 2); +-floor boundaries (1e-9); each NOT_COMPARABLE cause alone and all combined, in order; `iris.enableShaders` allows SHADERS and SHADER_PACK; `vanilla.renderDistance` allows RENDER_DISTANCE only (SD still counts); an undo of the entry and a DISCARDED entry don't count; the cursor window vs the time fallback; NO_NUMBERS; each caveat; the numbers are always there for the verdict line | AC6.5 (unit) |
| T4 | The stage: `TryItFlow.derive(TryIt, runs, History(state, entries, failuresByOpId), Live(session, measuring, applying))` -> `TryItView` (stage, the try, before, after, verdict, the change's status, its helper failure, same session), every row of ti §2.5; `TryItView.actions()` (ti §2.7's footer sets), `closing()` (the decision a closing stage records), `Stage.chainRunning()` (Busy's hook) | `TryItFlow`, `TryItView` | `TryItFlowTest`: every row of ti §2.5 incl. a helper FAILED attempt 2 (RETRYING, attempt kept), ABANDONED (NOT_APPLIED with the helper's reason), DISCARDED by Discard (NOT_APPLIED) and by an undo of the entry (CANCELLED), REVERT_PENDING, REVERTED, the before aged out (NO_BEFORE), the entry folded into a baseline (NO_ENTRY: its `foldedEntryIds`, or an after run proving the apply happened), a second after run (the newest wins), a stale session (INTERRUPTED for NOW/CURRENT; READY with Measure now for the benchmark world and RESTART); MEASURING_* and APPLYING only from the live flags; an unreadable history never closes a try (HISTORY_UNREADABLE); the footer sets and closing decisions per stage; `TryItView(null)` still equals EMPTY (V05StubsTest) | AC6.8 (unit) |

Phase 1 ends with: push, CI green on every job, a code-reviewer subagent's review (findings to the coordinator), a
report of at most 10 lines, then stop until "go on the client part".

### Phase 2: client (after WS-B's part 1 and WS-W merge)

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| T5 | `BenchmarkHistory.after(pairId)` (newest), `openBefore(scene, mc, Predicate<String> pairFilter)` (the old overload delegates with `id -> true`) | `core/benchmark/BenchmarkHistory` | `BenchmarkHistoryTest`: newest after; the filter skips `tryit-`; the old overload unchanged | AC6.9 (unit) |
| T6 | Words: `TryItText` (refusals, stage lines, verdict line with the low/avg change and the floor, causes, caveats; literal keys, no dynamic family) and the `rigtune.tryit.*` block after `rigtune.tools.title`, alphabetical | `core/tryit/TryItText`, en_us.json | `TryItTextTest` (every refusal, stage, verdict kind, cause and caveat in English with its arguments); LangCheckTest, WordingTest (`rigtune.tryit.` correlation prefix), PseudoLocaleTest | X3, X5, AC6.5's line |
| T7 | `TryItService` (WS-K skeleton): derive on `Probes.EXECUTOR` (start hook, after each transition, TryItScreen/RigTuneScreen init with history.json's stamp changed); one ordered write chain for tryit.json; `start` (Triable with `Busy.refusal`, mint ids, snapshot `ShareKeys.MANAGED` via SettingsBridge, write, then the before run); the outcome handler (claims only its pairId; before saved -> `RealController.apply(List.of(rec), entryId)`; NOW -> the after on a later tick); its own END_CLIENT_TICK listener registered when a try opens, returning at once on a static volatile flag (0 bytes); `Busy.tryItRunning`; `keep`/`cancel`/`measureNow`; the title toast (WS-K's title-screen stub, safe twice); the regression acknowledgement when the verdict is first shown | `client/tryit/TryItService` | `TryItServiceTest` (unit where it can: the tick returns without touching the holder; 0 bytes per 100,000 calls; the ordered chain); BusyTest's `CALLERS` gains TryItService | AC6.12, AC6.15, AC-X.1 (C09 caller) |
| T8 | BenchmarkController's outcome hook: `setOutcomeHandler(@Nullable Predicate<Outcome>)`, asked in `show()` after the restore-failed toast and in the CURRENT-cancel branch; null/false = today's behaviour; a throwing handler is logged and ignored | `client/benchmark/BenchmarkController` (after WS-B's part 1) | BenchmarkGameTest unchanged; TryItGameTest block 1 (no BenchmarkResultScreen) | AC6.2 |
| T9 | UI: `TryItScreen` (RowList of step/verdict/caveat lines, at most 3 footer buttons from `actions()`); PreviewScreen's `plainFooter` [Try it (measured)] [Done]; `TryItNoticeSource` (TRY_IT, not dismissible); BenchmarkMenuScreen's `tryit-` filter (after WS-W's save line) | `client/ui/TryItScreen`, `PreviewScreen` (own method), `client/notice/TryItNoticeSource`, `BenchmarkMenuScreen` | TryItGameTest block 5 (layout at the X12 sizes), A11yGameTest `walkTryIt` | AC6.9, AC6.13 |
| T10 | `TryItGameTest` blocks 1-7 (network off through `GameTestNet`, returns under `rigtune.smoke`; never asserts an FPS number or a live verdict kind; screenshots at the X12 sizes) and `walkTryIt` | `gametest/TryItGameTest`, `A11yGameTest.walkTryIt` | the game test on 3 legs | AC6.1-AC6.4, AC6.6-AC6.9, AC6.13, AC6.14 |
| T11 | Fixture set `ws-t` (benchmarks.json with an open and a closed `tryit-` pair, history.json with the apply and its undo, pending.json with the staged PATCH op, tryit.json) from the tests (`RIGTUNE_REGENERATE_FIXTURES=1`), `expect.json` (0.4.0 loads without `.bad`, plans Undo this on the try's entry); the v0.2 BenchmarkHistory reads the benchmarks.json; compat030 (+ compat040 once WS-E lands it) | `src/test/resources/v050-written/ws-t/` | `TryItWrittenFixtureTest` | AC6.11 |
| T12 | **Code-deciding run** (AC6.16, under the game-test lock, 26.2 + Sodium on the dev PC): 5 A/A Measure pairs in the benchmark world (sets `MIN_CV`: kept at 0.025 if every pair stays within the floor, else retuned with the reason), one NOW try in a singleplayer world against a manual Measure pair, one RESTART try with a real restart, Revert and a second restart | `docs/v0.5/verification/try-it/` | real run | AC6.16 |
| T13 | Finish: merge `origin/feat/v0.5.0`, footprint deltas (below), Docs text, AC table | this file | CI | AC6.17 (docs text) |

No new `//? if` block expected (ti §4). Code-deciding run: T12 only.

**Phase 2 additions (coordinator's decisions of 2026-09-28, COORDINATOR-DECISIONS.md):**
- T5b (core, red first in `TryItVerdictTest`): WS-B's M4 rule: a pair with either run left out of the trend
  (`BenchmarkTrend.excluded`: a fresh benchmark world, or DH generating) gets no verdict: NOT_COMPARABLE with the cause
  `Excluded(FRESH_WORLD | DH_GENERATING)`, named right after the conditions.
- Game tests leave worlds through `GameTestWorlds.create/leave` only; X12's scrolling check is at 1280x720@3 (854x480
  caps at scale 2); the TRY_IT notice source stats metadata only (history.json's modification time) and reads no file
  content on the render thread.
- T7: WS-K's `V05ServicesTest` line `tryIt().view()` stays as it is while it reads no file (the service answers its
  in-memory view); if that changes, only that line is replaced (marked "WS-T").
- AC6.12's own footprint keys: `FootprintGameTest` and `tools/footprint-budgets.json` are frozen (ws-ci/WS-K), so, as
  WS-S did for RW-11's listener, the idle tick is measured strictly in a unit test (0 bytes per 100,000 calls) and in
  `TryItGameTest` (0 bytes summed over the timed blocks, ns per call logged); `tryItTickNsPerCall`/`tryItTickAllocBytes`
  go to the post-Wave-B footprint checkpoint (SPEC 1h).

## Design decisions (phase 1)

- **The derivation reads the journal itself**, not HistoryModel.View (ti §2.3 named the View): it needs the change's
  key, the undo changes' `reverts` link and a baseline's `foldedEntryIds`, which the View drops. Its input is the
  journal state, `Journal.entries()` and `ApplyFailures.byOpId(last-apply.json)`, the same the View is built from.
- **HISTORY_UNREADABLE** (a stage ti §2.5 doesn't have): history.json unreadable, corrupt or from a newer RigTune. The
  try's entry can't be seen then, and the table would say STOPPED_BEFORE ("Nothing was changed") and close the try, which
  can be false; this stage closes nothing and waits for the next derive.
- **A missing entry** (review round, H1/M2/M3): in order, an undo of the entry says how it ended (the journal's cap drops
  a reverted or cancelled entry before its undo: its change of the key APPLIED -> REVERTED, STAGED -> REVERT_PENDING,
  else CANCELLED); NO_ENTRY (closes as kept) only when a baseline's `foldedEntryIds` (L8, WS-P) proves the fold; no
  before run -> MEASURING_BEFORE / STOPPED_BEFORE; the chain between the before and the apply (`measuring` or
  `applying`) -> APPLYING; STOPPED_BEFORE only when nothing shows the apply happened (no after run, no after snapshot)
  and the journal never reached its cap (a cap always leaves exactly `Journal.MAX_ENTRIES`, so fewer entries means none
  was ever dropped); otherwise **ENTRY_MISSING** (a new stage): the change may be in effect and the entry lost, nothing
  closes it but the player's Keep.
- **DISCARDED is CANCELLED** whether or not an undo entry exists (UndoService writes the discard and its undo entry
  separately; Discard pending writes none): only the player takes a staged change out of pending.json. A STAGED change
  whose op the helper ABANDONED at the last exit is NOT_APPLIED with that failure before the journal is reconciled.
- **Where the player stood** (coordinator's SPEC decision in the review round): a CURRENT-scene try records the block,
  dimension and server key at Start (`beforeSpot`) and when each after run starts (`afterSpot`); an after run
  elsewhere, or either spot unknown, gives no verdict (the MOVED cause, after the conditions). The player is never moved
  back. The benchmark world is always the same spot.
- **`afterSession`** in `current`: the session the newest after run started in (taken with `settingsAfter`), so the
  "different sessions" caveat is right for a NOW try in the benchmark world resumed after a restart, not only RESTART.
- **Triable answers enums** (`Refusal`, `Kind`): the client passes the shared busy check's and the benchmark's own
  refusal texts through (BUSY, SCENE), and TryItText words the rest in phase 2.
- **Verdict: which History entries count** between the try's entry and the after run's cursor: any entry except an undo
  of the try's entry, with at least one change that isn't DISCARDED or ABANDONED (a change that never took effect can't
  have moved the numbers; a STAGED one counts, so a verdict doesn't flip after the restart that applies it). Without the
  cursor the window is by time and fails closed: an entry whose time can't be read counts, and without the run's own
  time every later entry does.
- **Numbers a hand edit broke**: a 1 % low or average that isn't a positive finite number is NO_NUMBERS; a CV that isn't
  finite counts as missing (5 %); the trend's floor uses only usable lows, and needs 3 of them.
- **tryit.json writes** (review round): open, change and close decide from the open try as it is on disk inside the
  store's own read-modify-write (a check outside first spares a write when nothing matches); the raw `update()` stays
  public for WS-K's `V05StoreShellsTest` but takes the same lock; `recent` keeps the 10 newest usable rows (unusable ones
  stay where they are and don't count); `writable()` is false for a file over the 16 KiB cap.

## Phase 1 as landed

All in `core/tryit/` (pure: core model + Gson, no Minecraft import). Tests red first (compile failures: the API didn't
exist), then green in a build slot (`:26.2:test --tests 'io.github.chaotix345.rigtune.core.tryit.*'`: 75 tests), plus
WS-K's `V05StubsTest`, `V05StoreShellsTest`, `V05ServicesTest`, `RigTuneControllerDefaultsTest`, `LangCheckTest` and
`WordingTest` unchanged and green.

| task | commit | API (as the client part will call it) | tests |
|---|---|---|---|
| T1 | 16745790 | `TryIt(id, pairId, entryId, recommendationId, key, from, to, Kind kind, Scene scene, startedAt, session, rigtuneVersion, mcVersion, settingsBefore, beforeSpot, settingsAfter, afterSession, afterSpot, afterRunId)`; `TryIt.of(entryId, recommendationId, key, from, to, kind, scene, startedAt, session, rigtuneVersion, mcVersion, settingsBefore, spot)` mints `t-<uuid>` / `tryit-<uuid>`; `withAfter(settings, session, spot)`, `withAfterRun(runId)`; `TryIt.Spot(x, y, z, dimension, server)`; `TryIt.Kind {NOW, RESTART}`, `TryIt.Decision {KEPT, REVERTED, CANCELLED, FAILED}`, `TryIt.Closed.of(t, decision, verdict, low, avg, floor, at)`; `TryItStore.current()`, `recent()`, `open(t)`, `change(id, f)`, `close(id, closed)`, `MAX_RECENT` 10 | `TryItStoreTest` (13) |
| T2 | 36832873 | `Triable.check(rec, Context[, scene])`, `selection(List, Context)` -> `Result(kind, scene, refusal)`; `Refusal {ONE, KIND, UNMEASURABLE, PRESET, UNSUPPORTED, NO_FILE, REMOTE_SD, PENDING, BUSY, SCENE, OPEN, HISTORY, STORAGE}`; `Context(hasConfigFile, inWorld, remoteServer, pending, busy, sceneUnavailable, tryOpen, journalWritable, benchmarksReadable, storeWritable)`; `scenes(kind)`, `defaultScene(kind, inWorld)`, `allowed(key)`, `sceneContent(key)`, `LIFTED` | `TriableTest` (13) |
| T3 | 88553045 | `TryItVerdict.of(t, before, after, runs, entries)` -> `Verdict(kind, lowPercent, avgPercent, floorPercent, causes, caveats)`; `Kind {BETTER, WORSE, NO_CLEAR_CHANGE, NOT_COMPARABLE, NO_NUMBERS}`; `Cause` = `Condition(Difference)` / `Moved()` / `Mods()` / `Entry(entryId, kind)` / `Setting(key)`; `Caveat {NOISY, WORLD_CONTENT, DH, SESSIONS, SCENE}`; `MIN_CV` 0.025 | `TryItVerdictTest` (16) |
| T4 | 78ea6944 | `TryItFlow.derive(t, runs, History(state, entries, failuresByOpId), Live(session, measuring, applying))` -> `TryItView(stage, tryIt, before, after, verdict, changeStatus, failure, sameSession)`; `TryItView.actions()` (`Action {MEASURE_NOW, MEASURE_AGAIN, KEEP, REVERT, CANCEL_TRY, LATER, DECIDE_LATER, DONE}`), `closing()`, `Stage.chainRunning()`, `Stage.ENTRY_MISSING`, `Stage.HISTORY_UNREADABLE`; `TryItView(Stage)` and `EMPTY`/`UNAVAILABLE` as WS-K landed them | `TryItFlowTest` (25) |
| review | a99f46a6 | the review round's fixes above (1 H, 4 M, 5 L; coordinator's decisions) | `TryItFlowTest` (28), `TryItStoreTest` (15), `TryItVerdictTest` (19); 8 of the new or changed tests fail against the pre-review TryItFlow/TryItStore (checked by restoring them): the cap test, the missing-entry, undo, discarded, applying-while-measuring and abandoned rows, the unusable rows and the over-cap file |

What the client part must honour (phase B contracts of the derivation): `Live.measuring` stays true from the moment a
run of the pair is queued until its outcome has been handled (else a derive between the run's save and the handler
reads a stop); `settingsAfter`/`afterSession`/`afterSpot` are taken only when an after run starts **and only once the
try's History entry exists** (they are the proof the apply happened); `beforeSpot`/`afterSpot` are the player's block,
dimension and server key in the CURRENT scene (null in the benchmark world), and the player is never moved back; a
closing stage's `closing()` is written with `TryItStore.close` once the player has seen it; ENTRY_MISSING closes only
through the player's Keep.

## Phase 2 as landed

Merged `origin/feat/v0.5.0` three times (663a9a3d, 91441f58, 35a3a20b: WS-B, WS-W, WS-P, WS-H, WS-F, WS-S, WS-L1,
WS-L2, WS-P2, WS-W2 by then); the one conflict was PreviewScreen's imports (both kept).

| task | commit(s) | what | tests |
|---|---|---|---|
| T5/T5b | f487dcfb | `openBefore(scene, mc, pairFilter)` (the old overload delegates); BenchmarkMenuScreen's "Measure after" skips `tryit-` pairs (after WS-W's SettingsSaver line); `TryItVerdict.Cause.Excluded(FRESH_WORLD / DH_GENERATING)` (WS-B's M4 rule), named after the conditions. (`BenchmarkHistory.after(pairId)` landed here too and went in the review round: nothing but tests used it.) | `BenchmarkHistoryTest` (+1), `TryItVerdictTest.aRunLeftOutOfTheTrendMeansNoVerdict` |
| T6 | c9c1b8c2 | `TryItText` and the `rigtune.tryit.*` block (86 keys now), alphabetical after `rigtune.tools.title`; literal keys only (no dynamic family, so `V05LangFamilies.tryIt` stays empty) | `TryItTextTest` (9); LangCheckTest, WordingTest, PseudoLocaleTest red first (the keys missing), then green |
| T8 | 2e4bb584 | `BenchmarkController.setOutcomeHandler(Predicate<Outcome>)`, asked in `show()` after the restore-failed toast and in the current-world cancel branch; a throwing handler is logged and ignored | `BenchmarkControllerHandlerTest` (3) |
| T7/T9 | b1c1dd0f | `TryItService` (the derive; one ordered chain on `Probes.EXECUTOR` for the derive and every tryit.json write; the run chain from its own END_CLIENT_TICK listener; the outcome hook; Busy's hook; Keep/Done/Measure now; the title toast; the regression acknowledgement), `TryItScreen`, `TryItNoticeSource`, Preview's `plainFooter` [Try it (measured)] [Done] | `TryItServiceTest`, `BusyTest` (the caller list gains `tryit/TryItService.java`) |
| T10 | 4d89efdc, ab0ed614, 6a822535 | `TryItGameTest` blocks 1-7 + the idle tick's cost; `A11yGameTest.walkTryIt` | CI (below) |
| T11 | 6a822535 | the `ws-t` set + `expect.json` | `V050WrittenWsTTest` (3); compat030 PASS (below) |
| T12 | (dev driver) `TryItDevRun` | AC6.16's runs (`RIGTUNE_DEV_TRYIT`) | docs/v0.5/verification/try-it/ |
| review | ba556882, 7c1f9f95, 1a649a4a, c3e9ef1d | the phase 2 code review (below) | `TryItServiceTest` (9), `TryItTextTest.aNoteComesFirst` |

The fixture set (T11): the closed try is `vanilla.cutoutLeaves`, reverted (a first version tried render distance, which
ws-p's v040 set also changes: compat030's "Undo this" on ws-p's profile switch then failed; with a key no other set uses,
compat030 passes: `python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar`, RESULT PASS, 2026-09-28). The open
try is a RESTART try of Sodium's defer mode with its PATCH_JSON op staged.

## Code review, phase 2 (0 H, 6 M, 7 L, 3 nits; the coordinator's decisions: fix all, each M with a test that fails first)

To test the chain without a game, everything in `TryItService` that touches the game goes through a package-private
`TryItService.Game` (`RealGame`, nested, is the real one: Minecraft, the benchmark, RealController, the client's stores;
`Busy.refusal` stays in TryItService.java, so BusyTest's caller rule holds). `TryItServiceTest`'s `FakeGame` keeps the
files real (a temporary config folder: tryit.json, and history.json through a real `Journal`) and runs the executor and
the render thread as queues. Red first: the new tests ran against the refactored chain with each fix taken out again (a
scratch script), and failed for the reason named; then the fixes, green.

| item | fix | test (the red failure) |
|---|---|---|
| M1 a failed begin handled twice | a start that handed its (cancelled) outcome over already isn't lost again: `if (refused != null && measuring)` | `aStartThatFailedAfterHandingItsOutcomeOverIsHandledOnce` (2 screens, not 1) |
| M2 an apply exception | caught and logged; `applying` resets in a finally around the derive; History decides (a journaled change goes on to the after run) | `anApplyThatThrowsAfterJournalingIsDerived` (the try was closed as cancelled) |
| M3 Start never set the listener's flag | `start()` sets it, so a Start whose tryit.json write never answers ends at the timeout and Busy lets go | `aStartWhoseWriteNeverAnswersEndsAtTheTimeout` (still running after 602 ticks) |
| M4 derive() outside the chain | `derive() { io(this::deriveNow); }` | `theStartHooksDeriveRunsOnTheChain` (history.json read on the calling thread) |
| M5 Keep after Revert/Undo | Keep derives on the chain first and closes as KEPT only while Keep is still offered; its answer says "Kept" only when history.json hasn't changed since the view's derive. TryItScreen: after Undo this its buttons stay inactive until the derive it asks for (`RigTuneController.tryItRefresh`) replaces the view, 2 s at most | `keepAfterAnUndoIsNotRecordedAsKept` (KEPT recorded) |
| M6 the spot taken at queueing | a CURRENT-scene run records the spot in the tick right before `tryStart`, through the chain (`TryIt.withBeforeSpot/withAfterSpot`) | `theSpotIsWhereEachRunStarts` (before spot (100, 64, -200) though the player was at (180, 70, -200) when the run started); `movingBetweenTheRunsIsNotComparable` (the reviewer's scenario; passes either way, kept) |
| L7 | the apply closes the try (FAILED) only on APPLYING or NOT_APPLIED; HISTORY_UNREADABLE keeps it, and its Revert | |
| L8 | a queued or running run gives up after `RUN_CAP_TICKS` (20 min) however busy the benchmark world stays; a lost run leaves a note on the view (`TryItView.note`: "The measurement couldn't start: ..." / "The measurement ended without a result."), shown first | `TryItTextTest.aNoteComesFirst` |
| L9 | the title toast sets its flag, then re-checks whether the derive finished | |
| L10 | a Start that couldn't be recorded: the intro shows why (the STORAGE refusal as the view's note) | |
| L11 | the notice leaves out Measure now while the scene can't start; Keep's answer shows as a toast | |
| L12 | the game test waits for each decision (`awaitDecision`: `waitFor(decision() == X, 200)`) | |
| L13 | `TryItService.idle()`; TryItGameTest checks it before timing the idle tick (and the unit test before its million calls) | |
| nits | `BenchmarkHistory.after(String)` removed with its test; `stage.stopped_before` is one sentence; the impossible `controller == null` check is gone | |

The review round's first CI run (36373720761) failed on the three game-test legs, all at the same check: block 4
called `derive()` and read the view at once, which M4 made asynchronous ("READY after the restart, the toast due:
NONE"). c3e9ef1d waits for it (and `awaitStage` for the footer's derive after Undo this).

## CI runs and what was looked at

| run | head | result | notes |
|---|---|---|---|
| 36364220167 | first client push | game tests failed | the idle tick allocated 512 bytes (loops in a lambda, no warm-up: now WS-S's method); 26.3 Vulkan compared the apply's nanosecond time with the entry's to-the-second time as strings (now instants, 1 s leeway) |
| 36365941231 | fixes | 26.2 failed | `walkTryIt`'s Preview walk: the inactive Try it button isn't a Tab stop (the walk now uses a controller where Try it is available) |
| 36368977426 | 91441f58 | 8/8 green | options.txt byte-identical after the NOW revert on all 3 legs (the screenshot resize had changed guiScale: the game test now restores it); idle tick 0.47 / 0.28 / 0.36 ns per call (26.2 GL / 26.3 GL / Vulkan), 0 bytes over 48 x 20,000 calls, the empty loop 0 |
| 36373720761 | 35a3a20b (review) | 5/8: the 3 game-test legs failed | block 4's synchronous read (above) |
| 36374976257 | c3e9ef1d | 8/8 green | options.txt byte-identical after the NOW revert on all 3 legs; idle tick 0.38 / 0.23 / 0.23 ns per call (26.2 GL / 26.3 GL / Vulkan), 0 bytes over 48 x 20,000 calls, the empty loop 0 |

Screenshots looked at (the `gametest-screenshots-26.2-OpenGL` artifact of 36374976257): `0194_tryit-now-here-reverted`
(the steps: measured before, applied Render Distance 5 -> 4, measured after, "Reverted", [Done]); `0196_tryit-stopped-before`
("Stopped before anything changed.", one sentence now); `0198_tryit-stopped-after` ([Measure again] [Keep] [Revert],
"Stopped before the second measurement. The change is applied."); `0204_tryit-restart-ready` ([Measure now] [Cancel try]
[Later], the title-screen hint in grey); `0218_tryit-verdict-not-comparable-640x480-scale2` (the cause line "resolution, a
later change in History (Apply)" wraps inside the column, then the numbers "for reference only", the NOISY and scene
caveats; three buttons fit 320 scaled pixels). Earlier: 0190-0220 of 36368977426's 26.2 set (the same screens before the
review round).

## Footprint deltas (against ws-k.md's per-leg baseline, run 36310249248, and the integration head's own run)

WS-T adds no init work: `TryItService` is built by the lazy holder (X4); the start hook's derive reads tryit.json and,
with no try open, history.json's state only, on the chain; the tick listener registers when a try's first run is
queued. `TryItDevRun` loads only when `RIGTUNE_DEV_TRYIT` is set.

| leg | key | baseline | 36372793413 (85f8d39d, the integration head, no WS-T client code) | 36368977426 (91441f58) | 36374976257 (c3e9ef1d, final) |
|---|---|---|---|---|---|
| 26.2 OpenGL | renderThreadInitCpuMs | 82.2 | 119.7 | 116.1 | 103.7 |
| 26.2 OpenGL | clientStartedWallMs | 36.4 | 52.2 | 34.5 | 30.0 |
| 26.2 OpenGL | workerCpuMs5s | 135.5 | 232.7 | 226.3 | 227.6 |
| 26.2 OpenGL | tickHookOnVsReference | 1.481 | 1.584 | 1.611 | 1.549 |
| 26.3 OpenGL | renderThreadInitCpuMs | 82.2 | 79.4 | 83.9 | 62.7 |
| 26.3 OpenGL | clientStartedWallMs | 27.0 | 24.4 | 17.9 | 31.1 |
| 26.3 OpenGL | workerCpuMs5s | 153.2 | 186.9 | 202.8 | 158.2 |
| 26.3 OpenGL | tickHookOnVsReference | 1.746 | 1.502 | 1.472 | 1.291 |
| 26.3 Vulkan | renderThreadInitCpuMs | 120.0 | 105.1 | 114.1 | 64.1 |
| 26.3 Vulkan | clientStartedWallMs | 39.9 | 34.8 | 29.9 | 33.8 |
| 26.3 Vulkan | workerCpuMs5s | 200.7 | 229.9 | 247.7 | 166.0 |
| 26.3 Vulkan | tickHookOnVsReference | 1.535 | 1.544 | 1.481 | 1.295 |
| all | monitorOnRetainedBytes | 2,506,896 | 2,545,808 | 2,545,808 | 2,545,808 |

The integration head's own run is the fairer comparison (the baseline predates every Wave A/B merge): the final run is at
or under it on every key and leg; `workerCpuMs5s` had risen on 26.2 and Vulkan in 36368977426 against its base run
(36368332581: 169.0 / 203.2 / 184.6), which the integration head then measured without WS-T too (232.7 / 186.9 / 229.9),
so it's runner spread and the other merges, not the start hook's derive (which since reads only history.json's state
when no try is open). `v05RenderThreadResolve` null and no violations on every leg.

AC6.12's own keys (`tryItTickNsPerCall`, `tryItTickAllocBytes`) go to the post-Wave-B footprint checkpoint (SPEC 1h):
`FootprintGameTest` and `tools/footprint-budgets.json` are frozen. Measured meanwhile: 0 bytes per 1,000,000 idle calls
(unit, under the 64 KiB noise) and 0 bytes summed over 48 x 20,000 timed calls in TryItGameTest on 3 legs.

## Deviations

- **The derivation reads the journal directly**, and **ENTRY_MISSING / HISTORY_UNREADABLE** are stages ti §2.5 doesn't
  have (phase 1, above).
- **`TryItView.note`** (a ninth component; the eight-argument constructor stays): what this session saw go wrong (a run
  that couldn't start or ended without a result, a Start that couldn't be recorded), shown above the stage's lines.
- **`TryItService.Game`**: a package-private seam, so the chain is unit-tested; `RealGame` holds every game call.
- **`RigTuneController.tryItRefresh()`** (a default no-op; ForwardingController forwards): TryItScreen asks for a derive
  when it comes back from Undo this (review M5).
- **Keep's toast** from the notice (L11): the notice line has no status row.
- **`TryItDevRun`** (434 lines, `client/tryit/`): AC6.16's driver ships in the jar, inert: it's loaded only when the
  environment variable is set. The coordinator may prefer it removed before the RC (it's only needed to repeat
  AC6.16); nothing else refers to it but that one guarded call.
- **Footprint keys** to the 1h checkpoint (above), as WS-S did for RW-11.

## Residuals and UNVERIFIED

- **AC6.16's cold start (a residual for the coordinator):** a NOW try started 20 s after joining a world had a slow,
  uneven before run (+59 % against the manual pair's -1.3 %); its CV held the verdict to "no clear change". A slow but
  steady first run would not be caught: 0.5 doesn't check how long the player has been in the world before Start. The
  README line "play a minute first" (Docs) covers it for 0.5; a later version could wait for the world to settle or run
  a warm-up pass before a CURRENT-scene before run. docs/v0.5/verification/try-it/README.md has the numbers.
- **`TryItDevRun` in the jar** (Deviations): the coordinator's call whether it stays for the RC.
- **UNVERIFIED by WS-T:** compat040 on the `ws-t` set (WS-E's harness; the set's `expect.json` is in place); the real
  restart with a Distant Horizons or an Iris key (only Sodium ran for real; the other two are the same PATCH_JSON path in
  the tests); REMOTE_SD on a real server (unit only); AC6.16 ran in the 854x480 development window, not full screen, and
  in the development client, not the release jar.
- **Tested by review only:** L10 (a Start whose tryit.json write fails: it needs a failing disk) and L11 (the notice
  without Measure now, Keep's toast: the notice line isn't clicked in a game test). M5's screen half runs in every
  Revert and Cancel try of TryItGameTest (`awaitStage` waits for the buttons' derive) but "inactive meanwhile" isn't
  asserted: the derive takes milliseconds.
- **AC6.12's own footprint keys** wait for the 1h checkpoint (above).

## Docs (for the docs workstream)

- **Scope that shipped (AC6.17): the full design**, both kinds: NOW (applies at once; here or in the benchmark world)
  and RESTART (Sodium, Distant Horizons and Iris settings, across a restart).
- **CHANGELOG [0.5.0]**: "Try it (measured): tick one setting in Preview and press Try it (measured). RigTune measures
  your game, applies the change, measures again in the same place (where you stand, or the benchmark world), and shows
  how the 1 % lows and the average moved against a noise floor; then you Keep it or Revert it (History's Undo this).
  Sodium, Distant Horizons and Iris settings take a restart: RigTune measures first, stages the change, and reminds you
  to measure again after the restart. When anything else changed between the two runs (another setting, your mods,
  where you stood, a new benchmark world, Distant Horizons still generating) there's no verdict, and both numbers are
  shown."
- **README** (features): "Try it (measured): one setting at a time, measured before and after, with a noise floor so a
  small wobble doesn't read as a gain." **Known limits**: "A try compares two runs about a minute apart; a change inside
  the noise floor (at least 5 %, more when the runs varied) says 'no clear change'. In your own world, time of day,
  weather and mobs aren't controlled; the benchmark world is the fairer place. Right after joining a world the first
  measurement can be slow and uneven (chunks still loading): play a minute before a try where you stand. The graphics
  preset, the FPS limit, VSync and the inactivity limit can't be tried. One try at a time; a try that needs a restart
  waits for it."

## AC table

| AC | status | evidence |
|---|---|---|
| AC6.1 | verified | `TriableTest` (every ti §2.2 row, the order, 16 vanilla + 30 rule keys decided); TryItGameTest block 7 (the Preview button's refusals), 3 legs |
| AC6.2 | verified | TryItGameTest block 1 (no BenchmarkResultScreen: a screen-event listener; one `tryit-` pair; the entry APPLIED), 3 legs |
| AC6.3 | verified | block 2 (the apply at the title between two opens of the same benchmark world, its marker's mtime) |
| AC6.4 | verified | blocks 3 and 4 (staged, the file untouched until exit, the notice, the menu's filter; after a seeded restart READY with the toast due, Measure now); the real restart: AC6.16 |
| AC6.5 | verified | `TryItVerdictTest` (20), `TryItTextTest` (the verdict line with the low/avg change and the floor) |
| AC6.6 | verified | block 2 (a whole-game-dir hash: Keep changed only `config/rigtune/tryit.json`) |
| AC6.7 | verified | block 1 (options.txt byte-identical after the NOW revert on all 3 legs, 36368977426); block 3 (Cancel try: pending.json gone, DISCARDED, sodium-options.json never changed); block 4 (Revert stages the reverse PATCH_JSON) |
| AC6.8 | verified | `TryItFlowTest` (28); block 6 (Esc before: STOPPED_BEFORE, nothing journaled; Esc during the after: READY with Measure again, Keep, Revert) |
| AC6.9 | verified | `BenchmarkHistoryTest` (the filter), block 3 |
| AC6.10 | verified | `TryItStoreTest` (15), `V05StoreShellsTest`; review: Try It's own state is only in tryit.json (the pair is benchmarks.json's existing record, the change History's existing entry) |
| AC6.11 | verified (compat040: WS-E) | `V050WrittenWsTTest` (0.2's BenchmarkHistory loads the pairs, 0.3's Journal/HistoryModel plan Undo this on the open try's entry); compat030 PASS with the released 0.3.0 jar; compat040 reads the set's `expect.json` when WS-E runs it |
| AC6.12 | verified (own keys at the 1h checkpoint) | own END_CLIENT_TICK listener on a static volatile flag; `TryItServiceTest` (idle, 1,000,000 calls under the noise); TryItGameTest (0 bytes, ns per call logged, `idle()` checked first), 3 legs |
| AC6.13 | verified | block 5 (the verdict screens at 640x480, 854x480, 1280x720 at scale 2 and 1280x720@3), `A11yGameTest.walkTryIt`; colours through `Palette.of` |
| AC6.14 | verified | TryItGameTest on 3 legs, network off, no FPS number or live verdict kind asserted |
| AC6.15 | verified | block 4 (the after run's regression acknowledged when the verdict is first shown) |
| AC6.16 | verified (the NOW part by the warm run; the cold one is a residual) | docs/v0.5/verification/try-it/: A/A 5 of 5 within the floor (`MIN_CV` stays 0.025); NOW -1.9 % against the manual pair's -0.9 % (floor 24.7 %); RESTART: the old value back in sodium-options.json, History apply/undo/REVERTED |
| AC6.17 | text ready (review) | Docs, above |
