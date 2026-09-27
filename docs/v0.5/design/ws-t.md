# WS-T: C09 Measured Try It (v0.5, SPEC 6)

Branch `feat/v05-try-it` (worktree `rigtune-tryit`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged). Scope:
docs/v0.5/SPEC.md 6 (AC6.1-AC6.17), C8 (Busy: Try It's hook and its caller entry), X4.4 (own tick listener); research
docs/research/v0.5/feature-try-it.md (ti). Scope shipped: the full design incl. RESTART, unless the coordinator decides
the same-session fallback (SPEC 6 "Scope that ships").

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
| review | (next) | the review round's fixes above (1 H, 4 M, 5 L; coordinator's decisions) | `TryItFlowTest` (28), `TryItStoreTest` (15), `TryItVerdictTest` (19); 8 of the new or changed tests fail against the pre-review TryItFlow/TryItStore (checked by restoring them): the cap test, the missing-entry, undo, discarded, applying-while-measuring and abandoned rows, the unusable rows and the over-cap file |

What the client part must honour (phase B contracts of the derivation): `Live.measuring` stays true from the moment a
run of the pair is queued until its outcome has been handled (else a derive between the run's save and the handler
reads a stop); `settingsAfter`/`afterSession`/`afterSpot` are taken only when an after run starts **and only once the
try's History entry exists** (they are the proof the apply happened); `beforeSpot`/`afterSpot` are the player's block,
dimension and server key in the CURRENT scene (null in the benchmark world), and the player is never moved back; a
closing stage's `closing()` is written with `TryItStore.close` once the player has seen it; ENTRY_MISSING closes only
through the player's Keep.

## Footprint deltas
(phase 2: `workerCpuMs5s`, `renderThreadInitCpuMs`, `clientStartedWallMs`, `tickHookOnVsReference` against ws-k.md's
per-leg baseline, run 36310249248.) Phase 1 adds no client code.

## Docs
(phase 2)

## AC table

| AC | status | evidence |
|---|---|---|
| AC6.1 | unit part verified; the game-test part (the Preview button's refusals) is phase 2 | `TriableTest` (every ti §2.2 row, the order, 16 vanilla + 30 rule keys decided) |
| AC6.5 | unit verified (the verdict line's words are phase 2's TryItText) | `TryItVerdictTest` |
| AC6.8 | unit verified; TryItGameTest block 6 is phase 2 | `TryItFlowTest` |
| AC6.10 | unit verified ("no Try It state lives in any other file" is a review item for phase 2) | `TryItStoreTest`, `V05StoreShellsTest` |
| AC6.2-AC6.4, AC6.6, AC6.7, AC6.9, AC6.11-AC6.17 | phase 2 | |
