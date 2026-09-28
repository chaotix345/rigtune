# v0.5 feature research: C09 Measured Try It

Summary (10 lines):
1. **Design:** tick one setting, open Preview and press "Try it (measured)". RigTune runs a Measure (its own `tryit-` pair id), then an ordinary `RealController.apply(List.of(rec), entryId)`, then a second Measure with the same pair id, and shows the verdict with Keep or Revert. Revert is History's Undo this on `entryId`. There is no new apply, undo or pairing path.
2. **What can be tried:** vanilla settings in the same session, chained with no clicks between the runs, in the current world or the benchmark world. Sodium, DH and Iris settings span a restart and always use the benchmark world. Mod add, update and disable can't be tried, in either launcher mode. Also excluded: `maxFps`, `enableVsync` and `inactivityFpsLimit` (every benchmark runs uncapped) and `graphicsPreset`.
3. **Where the state lives:** the pair stays in `benchmarks.json` (unchanged schema) and the change stays in `history.json` (an ordinary `apply` entry). One new file, `tryit.json`, holds only the try's identity: pairId, entryId, key, from/to, scene, session id and two settings snapshots. The flow's stage is **derived** from the three files, so a crash or quit at any point resumes or closes cleanly.
4. **Honest verdict:** 1% lows ± a floor. The floor is 2 × the larger CV, never under 5 %, and never under the trend's floor when 3 or more earlier comparable runs exist. Better, Worse or No clear change. "No verdict" when anything besides the tried key differs: context (`BenchmarkTrend.differences` minus the key's own difference), mod set, a History entry in between, or another setting.
5. **UI:** one button in the plain Preview, a new `TryItScreen` (a RowList of step lines plus up to 3 footer buttons, fits 320×240 scaled), a `TRY_IT` notice and one title toast for the restart case. Nothing is added to the RigTuneScreen footer, its rows or ToolsScreen.
6. **Corrections to the pitch and critic:** besides reusing the pair, Try It needs 3 things. BenchmarkMenuScreen's "Measure after" must stop continuing a `tryit-` pair, or it would take over the pair. `BenchmarkMath.gain` has no minimum floor, and 2 repeats make a CV of 0.3 % look like precision. BenchmarkController needs a small hook so a Try It run doesn't open BenchmarkResultScreen.
7. **Compatibility:** there's no new op type, no new BenchmarkRecord field and no journal kind. 0.4.0 after a downgrade sees ordinary Measure pairs, applies and undos, and ignores `tryit.json`.
8. **Effort:** about 4.5 agent-days (range 4 to 5.5) for the full scope, or about 3 for the same-session-only fallback (cut 5).
9. **Riskiest part:** chaining two runs through BenchmarkController's finish/show path. This matters most in the benchmark world (leave, apply at the title screen, reopen) and under the game-test harness's AWAITING_EXIT hand-off. Next riskiest: cross-restart derivation against the helper's outcomes (FAILED retries, ABANDONED, and the open P0.4 helper divergence).
10. **Hotspots touched:** RealController (constructor, delegations, notice list), RigTuneController (defaults), PreviewScreen, BenchmarkMenuScreen (2 lines), BenchmarkController (hook), RigTuneClient (tick line), en_us.json, the gametest fabric.mod.json and NoticePriority (shared with C16 and C18). Untouched: Recommender, UndoPlanner, BenchmarkResultScreen (L3's a11y rebuild owns it), build.yml and build.gradle.

---

## 1. Open questions and the critic's corrections, checked against the code

### 1.1 "No pair-id / cross-restart mechanism exists" (brainstorm §3 P1.2 open question 1): the critic is right

The pair mechanism exists and survives restarts, because it's persisted, not held in memory:

- `BenchmarkRequest(Mode mode, Scene scene, String pairId)`: `core/benchmark/BenchmarkRequest.java:4-5`.
- `BenchmarkRecord.phase` ("before"/"after"/"single") and `pairId`: `core/benchmark/BenchmarkRecord.java:10-11,16-17`.
- `BenchmarkRecords.phase(request, history)`: a pairId run is the "after" once a "before" with that id is on disk (`core/benchmark/BenchmarkRecords.java:16-22`). `BenchmarkRecords.gain(before, after)`: `:74-79` of the same file.
- `BenchmarkHistory.before(pairId)` / `openBefore(scene, mc)` / private `paired()`: `core/benchmark/BenchmarkHistory.java:152-169`. These are read from `benchmarks.json`, and the same shape exists in the v0.2 snapshot (`src/test/java/.../v020/core/benchmark/BenchmarkHistory.java:152,157`).
- `BenchmarkController.outcome()` stores the run and finds its before (`client/benchmark/BenchmarkController.java:682-699`, `BenchmarkStore.add` at `:697`). `Outcome.gain()` is at `:126-128`.
- The result screen already prints the pair's gain: "Compared with before: 1% lows +X% (avg +Y%)" or "no significant change" (`client/ui/BenchmarkResultScreen.java:259-267`; `en_us.json:561-562`).

Try It therefore **reuses** the pair. It adds no second pairing: `tryit.json` stores a reference (the pairId string) plus what `benchmarks.json` can't hold (§2.4).

### 1.2 Three things the critic's "just wire Apply into the pair" misses

1. **The Benchmark menu would take over a Try It pair.** `BenchmarkMenuScreen.init` fills "Measure after" with `openBefore(scene, mcVersion)` (`client/ui/BenchmarkMenuScreen.java:81,87-90`), the newest before of the scene that has no after. While a Try It waits for its restart, pressing "Measure after" in the menu would complete the Try It's pair before the change took effect: a junk pair, and a Try It with an "after" already recorded. **Fix:** Try It pair ids start with `tryit-`, and the menu skips them (`BenchmarkHistory.openBefore(scene, mc, Predicate<String> pairFilter)`, a new additive overload). On 0.4.0 after a downgrade the menu would offer such a pair, which is harmless (it becomes an ordinary manual pair).
2. **`BenchmarkMath.gain` has no minimum floor.** Its floor is `2 × max(cvBefore, cvAfter)`, with 5 % used only when a CV is missing (`core/benchmark/BenchmarkMath.java:74-79`, `NOISY_CV` at `:11`). A Measure has 2 repeats (`Timing.DEFAULT`, `core/benchmark/Timing.java:11`), and a 2-sample CV of 0.3 % is luck, not precision. BenchmarkTrend's own note says same-session CVs measured 1.7-12.3 % (median 4.2 %) (`core/benchmark/BenchmarkTrend.java:22-24`). Try It's verdict gets a minimum floor (§2.6). BenchmarkMath is left unchanged, because 0.4's Measure pair uses it.
3. **A Try It run must not open BenchmarkResultScreen.** `finish()` always ends in `show(...)`: directly in the current world, or after `BenchmarkWorld.leave(...)` in the benchmark world (`BenchmarkController.java:638-653,666-680`). The CURRENT-scene cancel branch posts an overlay message (`:646-652`). Chaining before, apply and after needs a small outcome hook there (§2.3). Polling `lastOutcome()` would flash the result screen and show "Saved as before…" (`BenchmarkResultScreen.java:265-266`).

### 1.3 Where the pair id and its "waiting for restart" state live (brainstorm open question 2)

- Pair: `benchmarks.json`, as today (schemaVersion 1, no new field).
- Change and its restart status: `history.json`. The Try It's Apply is an ordinary `apply` entry under a pre-minted id. Staging marks it STAGED. After the helper runs, reconcile turns it APPLIED, ABANDONED or DISCARDED (`core/history/HistoryUpdates.java:60-71`; DESIGN "Journal, undo…").
- Everything else goes in a new `config/rigtune/tryit.json` (§2.4): which recommendation, key, from/to, entryId, scene, the game session that started it, and two managed-settings snapshots. "Waiting for restart" is not stored anywhere. It's derived: the entry's change is STAGED.
- **Quitting without restarting:** for Sodium, DH and Iris keys, quitting *is* the restart the flow asks for. If the player instead cancels (Cancel try, or History's Undo this on the entry), the staged op leaves `pending.json` and the change becomes DISCARDED (`HistoryUpdates.java:60-65`). Derivation then reports "cancelled; nothing was changed" and closes the try. A vanilla (same-session) try interrupted by a quit or crash is derived as INTERRUPTED (§2.5).

### 1.4 "Refuse while a benchmark or download runs": existing guards, composed, plus one new one (open question 3)

Existing: `BenchmarkController.unavailable(minecraft, scene)` (running, world busy, wrong scene; `BenchmarkController.java:298-309`), `BenchmarkController.running()` (`:368`), `RealController.downloading` (`RealController.java:146,423`, `downloading()` `:827`), and ProfileService's composition of the two (`client/profile/ProfileService.java:377-388`). **New:** "another Try It is open" (from `tryit.json`), plus the Try It-specific refusals in §2.2. `RealController.apply` itself doesn't check `BenchmarkController.running()`. That's fine, because Try It calls it only between its own runs.

### 1.5 Other pitch claims, checked

- "calls `RealController.apply(List.of(recommendation), entryId)` exactly as Apply does": confirmed, `RealController.java:422-500`. Vanilla values are applied and journaled now (`VanillaChanges.apply(entryId, …)`, `:456`). Sodium, DH and Iris values are staged as `PATCH_*` ops under `entryId` (`:466-480`, `Staging.stage`).
- "Revert = the existing Undo this": confirmed, `UndoScreen(parent, controller, entryId)` (`client/ui/UndoScreen.java:68`), planned by `RealController.undoPlanFor(entryId)` (`:722-734`) → `UndoPlanner.planEntry`. A staged change is cancelled (DISCARDED). An applied vanilla change reverts now. An applied config change stages its reversal for the next restart. A later change to the same key is skipped with "Changed again by a later apply" (DESIGN "Undo this (0.3)").
- "No new file format": **not quite.** One new state file is needed (§2.4). No existing format changes.
- Critic's cross-cutting a11y note (§9.5): answered by building TryItScreen on RowList/RowFocus from the start (§2.7).
- BH-1/BH-2 (audit, `audit-v040-features.md:234-286`): **no collision.** Try It never shows TrendText's `too_few`/`in_line` counts (BH-1), because its verdict is its own. A cross-restart Try It is refused while anything else is staged (§2.2), so a Try It run never starts with a foreign STAGED change inside its cursor window, which is BH-2's scenario. If BH-2's fix adds a context field (staged-at-start ids), Try It is unaffected. The Try It entry *is* inside its own after-run's window, which is correct.

---

## 2. Design

### 2.1 Flow at a glance

```
Preview (exactly one ticked, triable) ─► TryItScreen: intro (scene, duration, what changes) ─► Start
  │  mint pairId "tryit-<uuid>", entryId = ChangeRecorder.newEntryId(), snapshot managed settings
  │  write tryit.json (Probes.EXECUTOR) ─► then, on the render thread:
  ├─► Measure BEFORE  (BenchmarkRequest(MEASURE, scene, pairId); run saved to benchmarks.json, phase "before")
  │     outcome hook claims it (no BenchmarkResultScreen)
  ├─► RealController.apply(List.of(rec), entryId)          ← the one and only apply path
  │     NOW (vanilla):   applied + journaled now ─► next client tick: Measure AFTER (same pairId)
  │     RESTART (config): staged in pending.json, journaled STAGED ─► TryItScreen "Restart Minecraft to finish"
  │                       … restart; helper applies; reconcile → APPLIED …
  │                       title toast + TRY_IT notice ─► "Measure now" (benchmark world) ─► Measure AFTER
  └─► verdict (TryItVerdict over the two records + condition checks) ─► TryItScreen
        Keep   → tryit.json decision "kept" (nothing else written)
        Revert → UndoScreen(entryId) = History's Undo this (settings now; config at the next restart)
```

### 2.2 Which recommendations can be tried (`core/tryit/Triable`)

A pure check over `(Recommendation, Context)` returns `Kind` (NOW, RESTART) or a refusal `Text`. Its checks, in order:

| Case | Result | Why |
|---|---|---|
| Not `Action.SetSetting` (AddMod, UpdateMod, DisableMod, None) | refused `rigtune.tryit.refused.kind` | See §2.2.1. Same answer in launcher-managed and unmanaged instances. |
| `vanilla.maxFps`, `vanilla.enableVsync`, `vanilla.inactivityFpsLimit` | refused `…unmeasurable` | Every run lifts these to uncapped/off/minimized (`BenchmarkController.java:68-74,355`), so before and after would measure the same thing. |
| `vanilla.graphicsPreset` | refused `…preset` | It rewrites a dozen options (DESIGN "Undo this"). No rule sets it (DESIGN "Preview"). |
| other `vanilla.*` in `SettingKeys.VANILLA_ALLOWED` | **NOW** | Applied immediately (`RealController.java:436,456`). |
| `sodium.*`, `dh.*`, `iris.*` with `ConfigTargets.forKey(targets, key) != null` | **RESTART** | Staged for the helper (`:437-441,466-480`). |
| config key without a target file | refused `…no_file` | Apply would stage nothing. |
| `vanilla.simulationDistance`, CURRENT scene, connected to a remote server | refused `…remote_sd` | The server decides simulation distance. The benchmark itself only tunes SD in singleplayer (`BenchmarkController.java:708-710`). |
| RESTART kind and `controller.hasPendingChanges()` | refused `…pending` | Anything else staged would take effect at the same restart and be measured with the tried change. |
| benchmark running, world busy, wrong screen for the scene | the existing `BenchmarkController.unavailable` key | reused |
| `controller.downloading()` | `rigtune.status.busy` | reused |
| another try open (`tryit.json` `current` not closed) | refused `…open` (names it) | new |
| `ClientJournal.get().state()` not OK/MISSING | refused `…history` | Revert (Undo this) needs a writable journal (`Journal.java:49,109-116`). |
| `BenchmarkStore.history().unreadable()` | refused `…storage` | The before couldn't be saved (`BenchmarkStore.java:51-66`). |
| `tryit.json` not writable (newer, unreadable) | refused `…storage` | |
| rules/hardware not ready | `rigtune.profile.code.error.not_ready` | reused |

Staged recommendations never reach Preview, because the report drops them (`RealController.java:396`). So there's no "already staged" case.

**Scene.** NOW kind: TryItScreen's intro shows the same scene CycleButton as BenchmarkMenuScreen (`BenchmarkMenuScreen.java:64-76`). It defaults to CURRENT in a world and BENCHMARK_WORLD on the title screen, and doesn't write `settings.json`. RESTART kind: always BENCHMARK_WORLD (fixed seed, spot, noon, clear weather: `BenchmarkWorld.java:43-47`, setUp). The CURRENT scene can't be matched across a restart (another spot, time or weather, or another server). `BenchmarkWorld.supported()` is always true (`BenchmarkWorld.java:75-77`).

**Allowed differences** (kept out of "conditions changed"): `vanilla.renderDistance` → RENDER_DISTANCE, `vanilla.simulationDistance` → SIMULATION_DISTANCE, `iris.enableShaders` → SHADERS and SHADER_PACK, `dh.client.advanced.debugging.rendererMode` → DISTANT_HORIZONS. DH's `renderingEnabled` is saved as `rendererMode` (`client/compat/DhCompat.java:10`, seen with DH 3.3.2), and the context's `dhRendering` reads `renderingEnabled` (`OptionalMods.java:80-87`). Every other key allows none.

**Scene-content caveat keys** (a caveat line, not a refusal, when measured in the benchmark world): `vanilla.particles`, `vanilla.entityDistanceScaling`, `vanilla.entityShadows`, `vanilla.weatherRadius`, `vanilla.simulationDistance`. The benchmark world has no mobs, clear weather and zero random ticks (`BenchmarkWorld.java` setUp, game rules at `:285-293`).

#### 2.2.1 Why mod-file changes can't be tried in v0.5 (either mode)

- **Launcher-managed instances** (P0.4): add, update and disable become launcher advice (`Action.None`, not appliable), so there is nothing to try.
- **Unmanaged instances:** it would be technically possible (a before in the benchmark world, a staged download, restart, an after, and Revert as a staged disable plus another restart). It stays out of scope because:
  - Add and Update need Modrinth, which breaks "works with the network off".
  - DisableMod recommendations are conflict and safety fixes, not performance knobs.
  - Revert costs a second restart and runs into FolderCheck's dependency refusals.
  - A mod-set change is exactly what BenchmarkTrend treats as "cause unknown". A new renderer mod (Sodium) changes far more than one knob.
  - It would add a third branch to the riskiest part (§8).

  A v0.6 candidate ("Try Sodium, measured") can reuse the same derivation with a `FILE` kind.

### 2.3 Classes to add and change

**New, core (`src/main/java/.../core/tryit/`, pure, unit-tested):**

- `TryIt`: record, the stored identity. Fields: `id` (`t-<uuid>`), `pairId` (`tryit-<uuid>`), `entryId`, `recommendationId`, `key`, `from`, `to`, `kind` ("now"/"restart"), `scene` (enum name), `startedAt`, `session` (a per-JVM random id), `rigtuneVersion`, `mcVersion`, `settingsBefore` (Map), `settingsAfter` (Map, nullable), `decision` (nullable: "kept", "reverted", "cancelled", "failed"), `afterRunId` (nullable).
- `TryItStore`: `tryit.json` on `JsonStateFile` (§2.4). One writer: TryItService, through one ordered chain on `Probes.EXECUTOR`.
- `Triable`: §2.2. It also holds the allowed-difference map, the unmeasurable keys and the scene-content keys (static tables, unit-tested).
- `TryItFlow`: `derive(TryIt, List<BenchmarkRecord> runs, HistoryModel.View history, String session, boolean running) → TryItView` (§2.5). Pure.
- `TryItVerdict`: `of(before, after, List<BenchmarkRecord> runs, TryIt, HistoryModel.View) → Verdict(kind, lowPercent, avgPercent, floorPercent, differences, noisy)` (§2.6). It reuses `BenchmarkRecords.gain` inputs, `BenchmarkTrend.differences/baseline/noiseFloorPercent` (`BenchmarkTrend.java:128,209,218`), and `TrendText.differences` for names (`TrendText.java:138-139`).
- `TryItText`: every line as a `core/model/Text` with an explicit key per case, no dynamic key families (LangCheckTest finds literals), after the TrendText pattern.

**Changed, core:**

- `core/benchmark/BenchmarkHistory`: add `after(String pairId)` (the newest after of a pair) and `openBefore(String scene, String mcVersion, Predicate<String> pairFilter)`. The old `openBefore` delegates to the new one with `id -> true`. Additive only.
- `core/notice/NoticePriority`: add `TRY_IT` after `BATTERY_OFFER`. The enum isn't serialized (dismissals are stored by key: `Notice.java:9-11`). C18 inserts `STARTUP_REGRESSION` and C16 its own slot, so expect trivial merge conflicts.

**New, client:**

- `client/tryit/TryItService`: the orchestration. RealController only delegates to it, one line each (the v0.4 service rule, DESIGN "Tools hub…"). It holds a `volatile TryItView view` and a `volatile @Nullable BenchmarkRequest next` for the tick. Nothing happens in its constructor.
- `client/notice/TryItNoticeSource`: `NoticePriority.TRY_IT`. It reads `TryItService.view()` only, with no I/O at screen init.
- `client/ui/TryItScreen`: intro, progress and result (§2.7).

**Changed, client (hotspots marked ★):**

- ★ `client/benchmark/BenchmarkController`: add `static void setOutcomeHandler(@Nullable Predicate<Outcome>)`. In `show()` the handler is asked right after the restore-failed toast (`:667-670`) and before the cancelled toasts and the result screen. In the CURRENT-scene cancelled branch (`:646-652`) it's asked before the overlay message. If the handler returns true, the controller shows nothing else. About 10 lines. The existing behaviour is unchanged when the handler is null or returns false.
- ★ `client/ui/BenchmarkMenuScreen`: `openBefore(scene, mc, id -> !id.startsWith(TryIt.PAIR_PREFIX))` at `:81`.
- ★ `client/RealController`: construct `TryItService`, add `TryItNoticeSource` to the NoticeCenter list after the battery source (`:179-181`), and add delegations: `tryIt()`, `tryItRefusal(rec)`, `startTryIt(rec, scene)`, `tryItMeasureNow()`, `tryItKeep()`, `tryItCancel()`.
- ★ `client/ui/RigTuneController`: `default` versions of those delegations, so StubController and the game-test fakes compile unchanged (`RigTuneController.java:41-190` is all defaults).
- ★ `client/ui/PreviewScreen`: in plain mode (`confirm == null`, `:160-165`) the footer becomes [Try it (measured)] [Done]. The button is active iff `selected.size() == 1` and `controller.tryItRefusal(rec) == null`, and its tooltip is the refusal or the explanation.
- ★ `client/RigTuneClient`: `onTick` calls `TryItService.tick(minecraft)` after `BenchmarkController.tick` (`RigTuneClient.java:174`). `CLIENT_STARTED` schedules the first `tryit.json` derive on `Probes.EXECUTOR`.
- ★ `src/main/resources/assets/rigtune/lang/en_us.json`: §2.9.

Not touched: Recommender, UndoPlanner, UndoService, Staging, Journal, PendingActions, the helper, BenchmarkResultScreen, ToolsScreen, RigTuneScreen, rules files.

### 2.4 `config/rigtune/tryit.json`

```json
{
  "formatVersion": 1,
  "current": {
    "id": "t-2f1c…", "pairId": "tryit-9a0b…", "entryId": "e-…",
    "recommendationId": "setting:sodium.performance.chunk_build_defer_mode",
    "key": "sodium.performance.chunk_build_defer_mode", "from": "ALWAYS", "to": "ONE_FRAME",
    "kind": "restart", "scene": "BENCHMARK_WORLD",
    "startedAt": "2026-10-02T09:14:07Z", "session": "5c1e…",
    "rigtuneVersion": "0.5.0", "mcVersion": "26.2",
    "settingsBefore": { "vanilla.renderDistance": "12", "sodium.performance.chunk_builder_threads": "0", "…": "…" },
    "settingsAfter": null, "afterRunId": null, "decision": null
  },
  "recent": [
    { "id": "t-…", "key": "vanilla.renderDistance", "from": "16", "to": "12", "verdict": "better",
      "lowPercent": 11.4, "avgPercent": 8.0, "floorPercent": 6.2, "decision": "kept", "at": "2026-09-30T20:01:12Z" }
  ]
}
```

- **JsonStateFile rules** (`core/store/JsonStateFile.java:30-40`): formatVersion 1. Atomic writes. Cap 16 KiB, so reads stop at 64 KiB. Corrupt → `tryit.json.bad`, then empty. Newer → read-only, and Try It is refused (it couldn't record a decision). Unknown top-level fields are kept via `save(value, previous)`. Values are type-checked on read, because players edit files.
- **Caps:** `recent` holds at most 10 entries, oldest dropped first, and is pruned further while over the cap (the StartupTimesStore pattern, `core/footprint/StartupTimesStore.java:50-66`). Snapshots are limited to the `ShareKeys.MANAGED` keys (`core/profile/ShareKeys.java:152`, 32 keys), so `current` stays under 3 KiB.
- **One writer, ordered:** TryItService chains writes on `Probes.EXECUTOR` (`CompletableFuture` chain, like stutter.json's ordered chain). A step that must follow a write, such as starting the before run, runs in the chain's completion via `minecraft.execute`.
- **Snapshots:** `SettingsBridge.read(minecraft)` (file values, as ProfileService does, `ProfileService.java:519-528`) filtered to `ShareKeys.MANAGED`. `settingsBefore` is taken at Start and `settingsAfter` when the after run starts. The comparison leaves out the tried key and the three benchmark-lifted keys.

### 2.5 Stage derivation (`TryItFlow.derive`)

The stage is never stored; it's computed from `tryit.json` `current`, the pair in `benchmarks.json`, the entry in the History view, the current session id and `BenchmarkController.running()`:

| before run | entry `entryId` (its setting change) | after run | other | Stage | UI |
|---|---|---|---|---|---|
| none | none | – | same session, running | MEASURING_BEFORE | (GUI hidden) |
| none | none | – | otherwise | STOPPED_BEFORE | "Stopped. Nothing was changed." → closed as cancelled |
| yes | none | – | same session, apply pending (render thread) | APPLYING | – |
| yes | none | – | otherwise | STOPPED_BEFORE | as above (crash between runs, before the apply) |
| yes | STAGED, no failure | – | – | AWAITING_RESTART | "Restart Minecraft to finish" [Cancel try] [Done] |
| yes | STAGED with a helper failure (last-apply.json) | – | – | RETRYING | "Wasn't applied at the last restart (reason); tries again (n of 3)" [Cancel try] [Done] |
| yes | ABANDONED or DISCARDED | – | no undo entry | NOT_APPLIED | "The change wasn't applied (reason); nothing to revert" → closed as failed |
| yes | DISCARDED by an undo of `entryId` | – | – | CANCELLED | → closed as cancelled |
| yes | APPLIED | none | now kind, same session, running | MEASURING_AFTER | (GUI hidden) |
| yes | APPLIED | none | now kind, same session | READY (after cancelled) | [Measure again] [Keep] [Revert] |
| yes | APPLIED | none | now kind, CURRENT scene, other session | INTERRUPTED | no verdict. [Keep] [Revert] |
| yes | APPLIED | none | restart kind, or now kind in BENCHMARK_WORLD, other session | READY | [Measure now] [Cancel try] [Later] |
| yes | APPLIED | yes | decision null | RESULT | verdict. [Keep] [Revert] [Decide later] |
| yes | APPLIED, with a staged undo change reverting it | any | – | REVERT_PENDING | "Reverted; the old value comes back at the next restart" → closed as reverted |
| yes | REVERTED | any | – | REVERTED | → closed as reverted |
| gone (aged out of the 50 runs) | APPLIED | – | – | NO_BEFORE | no verdict. [Keep] [Revert] |
| any | entry gone (folded into a baseline) | – | – | NO_ENTRY | no Revert. [Done] → closed as kept |

"Closed" means TryItService moves `current` into `recent` with the decision, which is one write. The derivation runs on `Probes.EXECUTOR`, because `Journal.entries()` reads the file on every call (`Journal.java:98-106`) and `controller.history()` builds the view from it. It runs at CLIENT_STARTED, after every transition, on TryItScreen init, and when RigTuneScreen inits with `history.json`'s stamp changed (the TrendService memo pattern, `TrendService.java:60-80`). Its result goes into `volatile view`.

**Chaining (NOW kind):** the outcome handler (render thread) claims only outcomes whose `request().pairId()` equals the open try's pairId. It then does one of two things:
- Cancelled, throttled or restore failed: set the view and open TryItScreen. Nothing has been applied yet if it was the before run.
- Saved before: call `controller.apply(List.of(rec), entryId)`, send the overlay message "Try it: first measurement done…", take `settingsAfter`, and set `next = new BenchmarkRequest(MEASURE, scene, pairId)`.

`TryItService.tick` starts `next` on the following tick through `controller.startBenchmark(next)` (`RealController.java:620-626`). A refusal lands the flow in READY with the refusal text.

For the CURRENT scene, the player is snapped back to the measured spot by `finish()` (`BenchmarkController.java:616-622`), so the after starts at the same position. For BENCHMARK_WORLD, the handler already runs at the title screen (inside `leave`'s `then`, `:638-641`), so the apply happens there and `startBenchmark` reopens the world.

Whether the apply took is read from the journal entry by the next derive. `RealController.apply` returns only a status `Component`. That Component is kept in memory as the failure text for the same session.

**Quit, crash, "changed something else":** covered by the table. A crash during a run is restored by MarkerRestore as today; the run isn't saved, and derivation continues from the last saved record. A Tune, a profile switch, a Keep of a benchmark or another Apply between the two runs is seen by the conditions check (§2.6). None of them is refused, so the player stays free to do them, but the verdict is withheld.

### 2.6 Verdict (`TryItVerdict`)

- `low = gainPercent(before.1%low, after.1%low)`, `avg` likewise (`BenchmarkMath.gainPercent`).
- `floor = max(2 × max(cvBefore ?? 0.05, cvAfter ?? 0.05, MIN_CV) × 100, trendFloor)`, where `MIN_CV = 0.025`, so the floor is never under 5 %. `trendFloor = BenchmarkTrend.noiseFloorPercent(before.cv, lows of BenchmarkTrend.baseline(before, runs))` when that baseline has at least `MIN_RUNS` (3) runs, otherwise 0. This is the critic's §9.4 point: reuse the primitives, not `assess()`.
- Kinds: `BETTER` (low ≥ floor − 1e-9), `WORSE` (low ≤ −floor + 1e-9), `NO_CLEAR_CHANGE`, `NO_NUMBERS` (a record without a result), and `NOT_COMPARABLE` when any of these hold:
  1. `BenchmarkTrend.differences(before, after)` minus `Triable.allowed(key)` is non-empty (MC version, scene, RD/SD, resolution, fullscreen, shaders or pack, DH, protocol, not recorded).
  2. Both `modSetHash` values are known and differ.
  3. The History view has an entry newer than `entryId` and at or before `after.context().journalCursor()` that isn't an undo of `entryId` (a later Apply, profile switch, benchmark Keep or undo).
  4. `settingsBefore` and `settingsAfter` differ on any key except the tried one and the three benchmark-lifted keys.

  Each cause is named: TrendText's difference names, "the loaded mods", "a later change in History (Profile: Battery)", or "<label> changed".
- Caveats (lines, not verdicts):
  - `noisy` (either CV over 5 %): reuses `rigtune.benchmark.noisy`.
  - Scene content (§2.2).
  - DH loaded: "Distant Horizons builds distant terrain in the background, which can make runs vary."
  - Different sessions (restart kind): "a driver or background program change would show up here too."
  - Always: "Measured in one scene; busier places may differ."
- The regression notice for the after run is acknowledged when the verdict is first shown (`trendService.acknowledge(afterRunId)`, `TrendService.java:134-138`), so the notice slot doesn't repeat a number the player just saw. Without this, a WORSE try would raise BENCHMARK_REGRESSION too.
- Known limitation: a reverted WORSE after run stays in the trend's baseline for its context (`BenchmarkTrend.baseline`). With at least 3 runs the median/MAD is robust to one outlier. This is documented, not fixed.
- The harness caveat: tick sync makes 1% lows meaningless in the game-test harness (`BenchmarkGameTest.java:69-72`, PROGRESS lessons). That's a test limitation, not a player-facing caveat. Real play has no tick sync, so the UI doesn't mention it. Tests never assert a verdict kind from a live run; they seed records to get each kind (§5).

### 2.7 UI (640×480 at GUI scale 2 = 320×240 scaled; also 427×240 and 960×540)

**Preview (plain mode):** the footer becomes two buttons, each `min(120, (column − 4) / 2)` wide. The column is `min(width − 32, 480) = 288` at 320 wide, so 2 × 120 + 4 = 244 fits. "Try it (measured)" is on the left. When inactive, its tooltip is the refusal. When active, it says "Measures now, applies this change, measures again, then lets you keep or revert it." The Confirm-mode footer (profiles, imports) is unchanged.

**TryItScreen** (one class, one layout, for every stage):
- y=8: bold title "Try it (measured)". y=20: the change, "%s: %s → %s" via `controller.settingLabels()` (the History and Preview labels; `RealController.java:737-740`, reusing key `rigtune.undo.item.setting`), clipped to the width with a full-text tooltip.
- y=34 to `height − 32`: a `RowList` of lines. Every line is a `RowFocus` row (Tab stop, narration, 1 px focus frame, RowList's 26.2 narration block). The lines are:
  - the intro and scene/duration lines, or the step lines ("Measured before: 1% lows 84 FPS, average 142 FPS", "Applied: …" / "Waiting for a restart: …", "Measured after: …");
  - the verdict line, coloured PASS/FAIL/LABEL through `Palette.of`;
  - the caveat lines.

  At 240 high that's 174 px, about 14 rows of 12, and the list scrolls.
- Intro, NOW kind only: the scene CycleButton sits above the list (20 px). For CURRENT with the key `vanilla.renderDistance`, the list adds the existing `rigtune.benchmark.menu.scene.current.saves` line.
- Footer, y = `height − 28`: at most 3 buttons of `min(98, (min(width − 16, 304) − 8) / 3)`, which fits 3 × 98 + 8 = 302 at 320 wide. The sets are:
  - Start, Cancel
  - Cancel try, Done
  - Measure now, Cancel try, Later
  - Keep, Revert, Decide later
  - Measure again, Keep, Revert
  - Done
- Revert and Cancel try open `UndoScreen(this, controller, entryId)`, which lists exactly what will be reverted and what is skipped. Returning to TryItScreen re-derives the stage (REVERTED, REVERT_PENDING or CANCELLED closes the try).
- Esc goes back to the parent. The try stays open, and the notice brings the player back.

**Notice** (`TRY_IT`, not dismissible, like the regression notice at `RegressionNoticeSource.java:44-48`):
- READY / RETRYING / AWAITING_RESTART: "Try it: %s is in effect. Measure again to see what it did." [Measure now] [Open…], or "…is waiting for a restart" [Open…].
- RESULT: "Try it: your result for %s is ready." [Keep] [Open…].

It uses the existing notice line and, below 400 px wide, the "…" fallback into NoticeScreen.

**Toast:** one per launch, on the first TitleScreen tick, only in READY, RETRYING or NOT_APPLIED after a restart: "RigTune: Try it" / "Open RigTune to measure the change again." It's shown whatever the `startupToast` switch says, because it continues the player's own action.

**Accessibility:** RowList/RowFocus for every text line, and buttons as ordinary widgets. Every colour goes through `Palette.of`. There are no painted charts, so L3's gap doesn't apply. `A11yGameTest` gets a Tab-walk block (§5).

### 2.8 Threading and footprint

- Render thread: the button handlers, the outcome handler and the apply, which is already on the render thread for vanilla (`VanillaChanges`). `TryItService.tick` is `if (next == null && !toastDue) return;`: two volatile reads, no allocation. That keeps it inside `tickHookNsPerCall` (111 ns) and `tickHookAllocBytes` 0 (`tools/footprint-budgets.json`).
- `Probes.EXECUTOR`: the `tryit.json` reads and writes, derivation, `controller.history()` and the snapshots' file reads. `SettingsBridge.read` hops to the render thread for options, as ProfileService does.
- CLIENT_STARTED: only schedules the EXECUTOR derive, so there's no file I/O on the render thread (`clientStartedWallMs` 141 ms), and nothing is added to init (`renderThreadInitWallMs/CpuMs`).
- Idle memory: one small immutable view and the service, well inside `rigtuneClassBytesIdle` (109 KiB). The screen holds no static references, so `leakSuspects` stays 0.
- No monitor, no new thread, no per-frame code: the frame hook is the benchmark's existing one.

### 2.9 Wording: draft `en_us.json` keys (English)

WordingTest: add `"rigtune.tryit."` to `CORRELATION_PREFIXES` (`src/test/java/.../WordingTest.java:33`), so these keys never say "caused" or "because of". "limited by" and "bottleneck" are already banned everywhere.

```
"rigtune.tryit.button": "Try it (measured)",
"rigtune.tryit.button.tooltip": "Measures now, applies this change, measures again, then lets you keep or revert it.",
"rigtune.tryit.refused.one": "Tick exactly one suggestion to try it.",
"rigtune.tryit.refused.kind": "Only settings can be tried; mod changes can't.",
"rigtune.tryit.refused.unmeasurable": "Benchmarks always run with the frame rate uncapped, so this setting can't be measured.",
"rigtune.tryit.refused.preset": "This setting changes many others at once, so it can't be tried on its own.",
"rigtune.tryit.refused.no_file": "This setting's config file wasn't found.",
"rigtune.tryit.refused.remote_sd": "On a server, the server decides the simulation distance.",
"rigtune.tryit.refused.pending": "Other changes are waiting for a restart. Restart (or Discard them) first, so only this change is measured.",
"rigtune.tryit.refused.open": "Finish or cancel your other Try it first (%s).",
"rigtune.tryit.refused.history": "History can't be written right now, so this change couldn't be reverted.",
"rigtune.tryit.refused.storage": "Results can't be saved right now; see the log.",
"rigtune.tryit.title": "Try it (measured)",
"rigtune.tryit.intro": "RigTune measures your game now, applies this change, and measures again in the same place. Then you choose: keep it or revert it.",
"rigtune.tryit.scene": "Measure in",
"rigtune.tryit.scene.current": "Measured here, where you stand. The screen and controls are taken over while it measures; Esc stops it.",
"rigtune.tryit.scene.world": "Measured in the benchmark world: same spot, time and weather every time.",
"rigtune.tryit.duration.now": "Takes about 2 minutes.",
"rigtune.tryit.duration.restart": "This setting takes effect at the next start: measure now, restart Minecraft, then measure again (about a minute each).",
"rigtune.tryit.start": "Start",
"rigtune.tryit.step.before": "Measured before: 1%% lows %s FPS, average %s FPS",
"rigtune.tryit.step.after": "Measured after: 1%% lows %s FPS, average %s FPS",
"rigtune.tryit.step.applied": "Applied: %s",
"rigtune.tryit.step.staged": "Waiting for a restart: %s",
"rigtune.tryit.restart": "Quit and restart Minecraft. RigTune reminds you to measure again at the next start.",
"rigtune.tryit.retrying": "The change wasn't applied at the last restart (%s). RigTune tries again at the next restart (try %s of 3).",
"rigtune.tryit.not_applied": "The change wasn't applied (%s), so there's nothing to measure or revert.",
"rigtune.tryit.ready": "The change is in effect. Measure again to see what it did.",
"rigtune.tryit.ready.world": "Measure again from the title screen: the benchmark world opens from there.",
"rigtune.tryit.stopped_before": "Stopped before anything changed. Nothing was changed.",
"rigtune.tryit.stopped_after": "Stopped before the second measurement. The change is applied.",
"rigtune.tryit.verdict.better": "Better: 1%% lows %s (average %s), more than the ±%s these runs vary by.",
"rigtune.tryit.verdict.worse": "Worse: 1%% lows %s (average %s), more than the ±%s these runs vary by.",
"rigtune.tryit.verdict.none": "No clear change: 1%% lows %s (average %s), within the ±%s these runs vary by.",
"rigtune.tryit.verdict.conditions": "No verdict: something else changed between the two measurements (%s). The numbers are shown for reference only.",
"rigtune.tryit.verdict.interrupted": "No verdict: the game restarted between the two measurements in your own world, so they can't be matched.",
"rigtune.tryit.verdict.no_before": "No verdict: the first measurement is no longer in the benchmark history.",
"rigtune.tryit.verdict.no_numbers": "No verdict: one of the measurements has no result.",
"rigtune.tryit.changed.mods": "the loaded mods",
"rigtune.tryit.changed.history": "a later change in History (%s)",
"rigtune.tryit.changed.setting": "%s",
"rigtune.tryit.caveat.scene": "Measured in one scene; busier places may differ.",
"rigtune.tryit.caveat.world_content": "The benchmark world has no mobs and clear weather, so this setting may show little change there.",
"rigtune.tryit.caveat.dh": "Distant Horizons builds distant terrain in the background, which can make runs vary.",
"rigtune.tryit.caveat.sessions": "The two measurements were in different game sessions: a driver or background program change would show up here too.",
"rigtune.tryit.keep": "Keep",
"rigtune.tryit.revert": "Revert",
"rigtune.tryit.revert.tooltip": "Opens Undo this for the change. Settings go back now; Sodium, Distant Horizons and Iris settings at the next restart.",
"rigtune.tryit.later": "Decide later",
"rigtune.tryit.measure_now": "Measure now",
"rigtune.tryit.measure_again": "Measure again",
"rigtune.tryit.cancel": "Cancel try",
"rigtune.tryit.cancel.tooltip": "Undoes the change (History's Undo this) and ends this Try it.",
"rigtune.tryit.kept": "Kept: %s.",
"rigtune.tryit.reverted": "Reverted: %s.",
"rigtune.tryit.revert_pending": "Reverted: %s gets its old value at the next restart.",
"rigtune.tryit.overlay.between": "Try it: first measurement done. Applying the change and measuring again…",
"rigtune.tryit.notice.ready": "Try it: %s is in effect. Measure again to see what it did.",
"rigtune.tryit.notice.waiting": "Try it: %s is waiting for a restart.",
"rigtune.tryit.notice.result": "Try it: your result for %s is ready.",
"rigtune.tryit.notice.open": "Open…",
"rigtune.tryit.toast.title": "RigTune: Try it",
"rigtune.tryit.toast.body": "Open RigTune to measure the change again."
```

Reused: `rigtune.undo.item.setting`, `rigtune.benchmark.noisy`, `rigtune.benchmark.menu.scene.current.saves`, `rigtune.benchmark.scene.*`, `rigtune.benchmark.refused.*`, `rigtune.status.busy`, `rigtune.profile.code.error.not_ready`, and TrendText's difference names. There are no dynamic key families, so LangCheckTest's family list is unchanged.

### 2.10 Error handling

- Every refusal is a `Text` shown as a tooltip (Preview) or a status line (TryItScreen). Nothing throws to the screen.
- A throwing outcome handler is caught and logged in `show()`, and the controller falls back to its own screen. The try derives to READY or STOPPED on the next derive.
- `tryit.json` write failed: the step continues, because the sources of truth are the other two files, and the log names `tryit.json` (JsonStateFile's `name()`, no absolute path). A failed identity write *before* Start refuses the start with `…storage`.
- Apply failed: the in-memory status Component in the same session, else NOT_APPLIED from the journal.
- Undo refused or skipped: UndoScreen shows why (existing), and the try stays in RESULT.

---

## 3. Compatibility (0.1.x to 0.4.x, downgrade to 0.4.0)

| File | What Try It writes | 0.4.0 / older |
|---|---|---|
| `benchmarks.json` | ordinary Measure runs, phase before/after, `pairId` "tryit-…" (a free string) | schemaVersion stays 1, no new field. 0.2.0+ read it (`BenchmarkHistory.load`). 0.4.0's menu may offer a `tryit-` before as an open "Measure after", which is harmless. |
| `history.json` | one `apply` entry (the setting), later perhaps one `undo` entry | Known kinds only (`JournalEntry.java:9-12`). 0.3.0/0.4.0 list it and can Undo this it. |
| `pending.json` | `PATCH_JSON`/`PATCH_TOML`/`PATCH_PROPERTIES` for a config key; a revert stages the same types | No new `PendingActions.Type` (a plain Gson enum). The 0.4.0 helper applies them unchanged. |
| `awareness.json` | `acknowledgedRegressions` += the after run id | Existing field (`TrendService.java:129-138`). |
| `tryit.json` | new file | Unknown to every older version and never read. A downgraded 0.4.0 applies a staged try at its exit like any Apply. On re-upgrade, derivation picks the try up (READY / RESULT / REVERTED). |
| `settings.json`, `rigtune.json`, `profiles.json` | nothing | – |
| rules | nothing | No rule field, so `rules-v1.json` is untouched. Triability is client code over `SettingKeys`. |

**Compat test:** add a `src/test/resources/v050-written/tryit/` fixture set: `benchmarks.json` with a closed and an open `tryit-` pair, `history.json` with the Try It apply and its undo, a `pending.json` with the staged patch, and `tryit.json`. It feeds whichever released-jar harness v0.5 adds for 0.4.0. UNVERIFIED: that a `compat040` harness exists yet; today only `tools/e2e/compat030.py` does, fed by `v040-written/` (`compat030.py:1-9`). Checks:
- `BenchmarkHistory` loads without a `.bad`;
- `Journal`/`HistoryModel` list both entries;
- `UndoPlanner` Undo this on the Try It entry plans a revert;
- `PendingActions` loads;
- no file changed.

A JUnit test also loads the same `benchmarks.json` with the v0.2 snapshot classes (`src/test/java/.../v020/core/benchmark/BenchmarkHistory`).

---

## 4. 26.2 vs 26.3

- **No new Minecraft API.** Everything Try It uses is already used by shared code that Stonecutter compiles for both nodes:
  - `Button`, `Tooltip`, `CycleButton`, `Screen`, `GuiGraphicsExtractor.centeredText` (BenchmarkMenuScreen, ToolsScreen);
  - `SystemToast.add` and `LocalPlayer.sendOverlayMessage` (BenchmarkController);
  - `ClientTickEvents.END_CLIENT_TICK` (RigTuneClient);
  - RowList/RowFocus.

  So no javap check was needed. The only version-specific code reached is inherited: RowList's `//? if <26.3` narration block (`RowList.java:32`) and `InputConstants.MOUSE_BUTTON_LEFT` for row clicks (0 on 26.2, 1 on 26.3; DESIGN "Accessibility").
- **Sodium** `mc26.2-0.9.2` and `mc26.3-0.9.2` (`versions/*/gradle.properties:4`) are in the production game tests on both nodes, so a Sodium-key try is testable on every leg.
- **26.3 Vulkan leg:** the benchmark already runs there (BenchmarkGameTest on every leg), and Try It adds no rendering code.
- **Options:** `options.txt` byte-equality after a vanilla Revert is asserted per node (§5). UNVERIFIED until the game test runs: that `Options.save` is byte-deterministic on both versions. If it isn't, AC9.8 falls back to value equality of every `SettingKeys.VANILLA_ALLOWED` key, and the doc says so.

---

## 5. Test plan

**Unit (JUnit, `src/test/java/.../core/tryit/`):**
- `TriableTest`: every row of §2.2's table. Every `SettingKeys.VANILLA_ALLOWED` key is classified. For each rule key in `rules/rules-v2.json` (30 keys), the kind and allowed differences are asserted, so a new rule key fails the test until someone decides.
- `TryItFlowTest`: every row of §2.5 from `BenchmarkRecord`, `HistoryModel.View` and `TryIt` fixtures, including:
  - a helper FAILED attempt 2;
  - ABANDONED;
  - DISCARDED by an undo;
  - REVERT_PENDING;
  - the before aged out;
  - the entry folded into `baseline-…`;
  - a second after (the newest wins);
  - a stale session.
- `TryItVerdictTest`: a golden table covering:
  - floor = 5 % with tiny CVs;
  - floor = 2 × CV above 2.5 %;
  - the trend floor with 3 or more comparable earlier runs;
  - ±floor boundaries with 1e-9;
  - each NOT_COMPARABLE cause alone and combined (with the difference-name order);
  - `iris.enableShaders` allowing SHADERS and SHADER_PACK;
  - `vanilla.renderDistance` allowing RENDER_DISTANCE only;
  - NO_NUMBERS;
  - the noisy caveat.
- `TryItStoreTest`: JsonStateFile contract (missing, corrupt → `.bad`, newer read-only, over 4 × cap left alone, unknown fields kept, `recent` pruned to 10 and to 16 KiB, hand-edited wrong types ignored).
- `TryItTextTest`: every stage and verdict renders English with the right arguments; the pseudo-locale check.
- `BenchmarkHistoryTest`: `after(pairId)` (newest); `openBefore` with the filter skips `tryit-`.
- `WordingTest`: the `rigtune.tryit.` prefix is added.
- `LangCheckTest`: unchanged code, new keys present and used.
- `BenchmarkMenu` filter: covered by the game test below.

**Client game test `TryItGameTest`** (all legs: 26.2 GL, 26.3 GL, 26.3 Vulkan). It runs under Xvfb with `ClientSettings.networkEnabled = false` for the whole class, uses the SHORT benchmark config like BenchmarkGameTest (`BenchmarkGameTest.java:77`), and builds `Recommendation`s in code like PreviewGameTest (`PreviewGameTest.java:330-332`), with no rules or network. It never asserts a verdict kind from a live run. Placed before FootprintGameTest in the gametest `fabric.mod.json`.
1. **NOW, CURRENT scene:** harness world. Tick `vanilla.renderDistance` to RD−1 (and a second case, `vanilla.particles`). Preview → Try it → Start. Wait for the TryItScreen RESULT stage. Assert:
   - two runs with pairId `tryit-…`, phases before and after;
   - no `BenchmarkResultScreen` was ever opened (a screen-event listener);
   - the journal entry `entryId` has the setting APPLIED;
   - a verdict line exists.

   Then Revert → UndoScreen → confirm, and assert `options.txt` bytes equal the pre-Start snapshot and `tryit.json` `recent[0].decision == "reverted"`. Screenshots at 320×240, 427×240 and 960×540 scaled.
2. **NOW, BENCHMARK_WORLD:** from the title screen, drive the harness's AWAITING_EXIT twice (`runInBenchmarkWorld`'s `exitNow` pattern, `BenchmarkGameTest.java:406-425`). Assert the setting was applied at the title screen between the runs and the benchmark world was reused (marker mtime). Keep → only `tryit.json` changed (a whole-game-dir hash snapshot, PreviewGameTest's `hash()` pattern at `:230-253`).
3. **RESTART, Sodium key:** from the title screen, the before runs in the benchmark world, then assert:
   - pending.json holds one `PATCH_JSON` op, the journal has it STAGED under `entryId`, and the view is AWAITING_RESTART;
   - `sodium-options.json` bytes are unchanged;
   - the TRY_IT notice is on RigTuneScreen;
   - the Benchmark menu's "Measure after" is inactive (the `tryit-` filter).

   Cancel try → UndoScreen → confirm, and assert pending.json is back to its prior bytes or absent, the change is DISCARDED and the try closed as cancelled.
4. **RESTART after a simulated restart** (seeded files, the "seed a state file, then open the screen" pattern): write a `tryit.json` with another `session`, a before record with a chosen result, and a `history.json` entry with the Sodium change APPLIED. Open RigTuneScreen and assert the notice is READY and the toast is due. Measure now → an after run. Assert the RESULT stage, then Revert → pending.json has the reverse `PATCH_JSON` (old value), the view is REVERT_PENDING and the try is closed as reverted. Discard pending to clean up.
5. **Verdict screenshots:** seed records with fixed numbers for BETTER, WORSE, NO_CLEAR_CHANGE and NOT_COMPARABLE (resolution plus a later History entry). Open TryItScreen with no run and take a screenshot of each at 320×240. Assert no row is clipped without a tooltip.
6. **Interruption:** press Esc during the before → STOPPED_BEFORE, nothing journaled. Press Esc during the after (NOW) → READY with [Measure again] [Keep] [Revert].
7. **Refusals:** the Try it button is inactive for a DisableMod rec, for `vanilla.maxFps`, while `hasPendingChanges()` for a Sodium rec, and with a try open.

**`A11yGameTest`:** add a Tab walk of TryItScreen (every list row narrates, the footer buttons are reached, the focus frame is drawn) and a high-contrast pixel check that Palette maps its colours.

**`FootprintGameTest`:** unchanged. It must still pass with `TryItService.tick` on the tick path: `tickHookNsPerCall`, `tickHookAllocBytes` 0, `clientStartedWallMs`.

**One real run on the dev PC** (the implementer, `./gradlew :26.2:runClient` with Sodium, evidence in `docs/v0.5/verification/try-it/`):
1. **A/A calibration:** 5 plain Measure pairs in the benchmark world with no change. Record every |Δ1% low| against the 5 % floor. Expect 0 of 5 over. Otherwise retune `MIN_CV` and write down why.
2. **NOW:** in the player's own singleplayer world, a render-distance try. Compare its gain% with a manual Measure before/after at the same spot (same sign; magnitude within the floor).
3. **RESTART with a real restart:** a Sodium key try, restart, Measure now, verdict, Revert, restart. Check `sodium-options.json` has the old value, and History shows apply, undo and REVERTED.
4. Screenshots at GUI scale 2 on a 640×480 window.

**Optional:** a `tools/e2e` `--scenario tryit` (3 launches with a TryItDriver, like the `undo` scenario, `tools/e2e/README.md`) that automates run 3. It's cut first if time is short (§9).

---

## 6. Draft acceptance criteria

- **AC9.1** Plain Preview shows "Try it (measured)". It's active iff exactly one item is ticked and `Triable` returns no refusal. Its tooltip names the refusal. TriableTest covers every row of §2.2; the game test covers DisableMod, `vanilla.maxFps`, a Sodium key with pending changes, and a try already open.
- **AC9.2** NOW kind, CURRENT scene: after Start the player presses nothing until the verdict. The two runs share one `tryit-` pairId (before, then after). No BenchmarkResultScreen is opened. The setting is journaled APPLIED under the try's `entryId` through `RealController.apply`.
- **AC9.3** NOW kind, BENCHMARK_WORLD: the same, with the apply at the title screen between two world opens, and the benchmark world reused.
- **AC9.4** RESTART kind: the before runs in the benchmark world. The change is staged (one `PATCH_*` op) and journaled STAGED. The config file is untouched until exit. After a restart that applies it, one title toast and the TRY_IT notice offer "Measure now", which produces the paired after and the verdict.
- **AC9.5** The verdict floor is `max(2 × max(cvB, cvA, 0.025) × 100, trend floor when ≥ 3 earlier comparable runs)`. BETTER, WORSE and NO_CLEAR_CHANGE split at ±floor. The verdict line always shows the 1% low change, the average change and the floor. The noisy caveat appears when either CV > 5 %. TryItVerdictTest golden table.
- **AC9.6** NOT_COMPARABLE, naming each cause, when:
  - a context difference outside the key's allowed set exists;
  - both mod-set hashes are known and differ;
  - a History entry lies between the try's entry and the after's journal cursor;
  - a managed setting other than the key and the three uncapped keys differs between the snapshots.

  The numbers are shown "for reference only".
- **AC9.7** Keep changes no file except `tryit.json` (game-dir hash snapshot).
- **AC9.8** Revert opens UndoScreen for `entryId`:
  - Confirming a NOW-kind revert leaves `options.txt` byte-identical to before Start (the fallback is value-equal on every `VANILLA_ALLOWED` key, documented if taken).
  - Cancel try before a restart returns `pending.json` to its prior bytes (or absent), and the config file never changes.
  - A revert after the restart stages the reverse `PATCH_*` op, History shows it as waiting for a restart, and the try closes as reverted.
- **AC9.9** Interruptions follow §2.5:
  - before stopped → nothing applied;
  - after stopped (NOW) → [Measure again] [Keep] [Revert];
  - restart between NOW/CURRENT runs → INTERRUPTED, no verdict;
  - helper failure → RETRYING with "try n of 3";
  - ABANDONED or DISCARDED → NOT_APPLIED;
  - before aged out → NO_BEFORE;
  - entry folded → NO_ENTRY without Revert.

  TryItFlowTest covers every row.
- **AC9.10** BenchmarkMenuScreen's "Measure after" never continues a `tryit-` pair (game test 3, unit test on `openBefore` with the filter).
- **AC9.11** `tryit.json` follows JsonStateFile's contract (formatVersion 1, ≤ 16 KiB, `recent` ≤ 10, corrupt → `.bad`, newer → read-only and Try It refused, unknown fields kept), and no Try It state lives anywhere else.
- **AC9.12** Compatibility:
  - `benchmarks.json` schemaVersion stays 1 with no new BenchmarkRecord field;
  - Try It writes only `apply`/`undo` journal entries and existing op types;
  - the released 0.4.0 jar (or its snapshot classes) loads the `v050-written/tryit/` files without a `.bad`, and plans Undo this on the Try It entry;
  - 0.2's BenchmarkHistory loads the `benchmarks.json`.
- **AC9.13** FootprintGameTest passes unchanged budgets with the tick call added (0 bytes per 100,000 tick calls). No `tryit.json` I/O on the render thread at CLIENT_STARTED.
- **AC9.14** TryItScreen and the Preview footer fit 320×240, 427×240 and 960×540 scaled without overlap. Every text line is a RowFocus Tab stop that narrates its text, and colours go through `Palette.of` (A11yGameTest).
- **AC9.15** Every UI string is an `en_us.json` key (LangCheckTest). WordingTest checks the `rigtune.tryit.` prefix for "caused" and "because of", and all keys for "limited by" and "bottleneck".
- **AC9.16** TryItGameTest passes on 26.2 GL, 26.3 GL and 26.3 Vulkan with the network switched off. It asserts no FPS number or verdict kind from a live run.
- **AC9.17** When the verdict is first shown, the after run's regression notice (if any) is acknowledged in `awareness.json`.
- **AC9.18** Dev-PC run recorded: A/A calibration (5 pairs, all within the floor, or the floor retuned with the reason), one NOW try in the player's world compared with a manual Measure pair, and one RESTART try with a real restart, Revert and restart.

---

## 7. File ownership (every file touched; ★ = shared hotspot)

**New:**
- `src/main/java/io/github/chaotix345/rigtune/core/tryit/{TryIt,TryItStore,Triable,TryItFlow,TryItVerdict,TryItText}.java`
- `src/client/java/io/github/chaotix345/rigtune/client/tryit/TryItService.java`
- `src/client/java/io/github/chaotix345/rigtune/client/notice/TryItNoticeSource.java`
- `src/client/java/io/github/chaotix345/rigtune/client/ui/TryItScreen.java`
- `src/test/java/io/github/chaotix345/rigtune/core/tryit/{Triable,TryItFlow,TryItVerdict,TryItStore,TryItText}Test.java`
- `src/gametest/java/io/github/chaotix345/rigtune/gametest/TryItGameTest.java`
- `src/test/resources/v050-written/tryit/` (benchmarks.json, history.json, pending.json, tryit.json)

**Changed:**
- ★ `client/RealController.java`: service field, notice source in the list, delegations. **C16 and C18 edit the same notice list; C20 and C02 edit around `apply`.**
- ★ `client/ui/RigTuneController.java`: default methods.
- ★ `client/ui/PreviewScreen.java`: the plain-mode footer. **C02's trust flow may add to the Apply UX nearby; coordinate.**
- ★ `client/ui/BenchmarkMenuScreen.java`: one filter at `:81`.
- ★ `client/benchmark/BenchmarkController.java`: the outcome hook in `show()` and the CURRENT-cancel branch (not on the hotspot list, but the most delicate edit).
- ★ `client/RigTuneClient.java`: one tick line and one CLIENT_STARTED schedule.
- ★ `src/main/resources/assets/rigtune/lang/en_us.json`: about 60 keys.
- ★ `src/gametest/resources/fabric.mod.json`: TryItGameTest before FootprintGameTest.
- ★ `core/notice/NoticePriority.java`: `TRY_IT` (C16 and C18 also add slots).
- `core/benchmark/BenchmarkHistory.java`: `after()`, `openBefore(…, filter)`.
- `src/gametest/.../A11yGameTest.java`: Tab-walk block (shared with any v0.5 screen work, including L3).
- `src/test/java/.../WordingTest.java`: one prefix.
- `src/test/java/.../core/benchmark/BenchmarkHistoryTest.java` (or the existing history test class).
- Docs: `docs/DESIGN.md` (a "Measured Try It (0.5)" section, `core/tryit` in Architecture, the state-file list), `README.md` (feature + FAQ "why can't I try a mod"), `CHANGELOG.md`.

**Not touched:** Recommender, UndoPlanner, UndoService, Staging, Journal, PendingActions, ApplyHelper, ToolsScreen, RigTuneScreen, BenchmarkResultScreen (L3 owns it), `build.yml`, `build.gradle` (the gametest class is picked up from `fabric.mod.json`; the legs come from `tools/gametest_matrix.py`), rules.

**Cross-feature notes:**
- **C02:** its first-Apply confirmation must hook RigTuneScreen's Apply button, **not** `RealController.apply`. If it hooks `RealController.apply`, a Try It (and a profile switch) would pop it between two benchmark runs with the GUI hidden.
- **C20:** does measure → apply → measure with Stutter sessions. It should share the benchmark/download refusal, for example a small helper extracted from `ProfileService.refusal()` (`ProfileService.java:377-388`), rather than a third copy.
- **C18:** its `STARTUP_REGRESSION` slot goes after `BENCHMARK_REGRESSION`; `TRY_IT` goes after `BATTERY_OFFER`.
- **P0.4:** Try It is settings-only, so it behaves the same in launcher-managed and unmanaged instances. UNVERIFIED: the P0.4 fix of the helper divergence (SKIPPED_ALREADY_DONE on a group that should be ABANDONED, PROGRESS.md:14) concerns mod-file groups. If it changes reconcile for `PATCH_*` ops, re-run TryItFlowTest.

---

## 8. Effort and risk

| Part | Agent-days |
|---|---|
| core: TryIt, TryItStore, Triable, TryItFlow, TryItVerdict, TryItText + unit tests | 1.25 |
| BenchmarkHistory, NoticePriority, BenchmarkController hook, menu filter | 0.25 |
| TryItService (chaining in both scenes, restart derive, EXECUTOR chain, tick, toast) | 1.0 |
| TryItScreen, Preview button, notice source, lang | 0.75 |
| TryItGameTest (7 blocks), A11yGameTest block, compat fixtures | 1.0 |
| Dev-PC run + DESIGN/README/CHANGELOG | 0.5 |
| **Total** | **~4.75 (range 4 to 5.5)** |

This is more than the pitch's 3 and less than the judges' "3-5 with no pair mechanism". The pair is reuse, but derivation, chaining and the verification are real work.

**Riskiest part: chaining through BenchmarkController.** `finish()` runs on the render thread inside `onTick`, restores settings, ends the Stutter capture (`StutterHooks.benchmarkFinished`), and for the benchmark world defers `show()` until the world is left, which under the harness waits for the test thread (`BenchmarkWorld.java:253-267`). Mitigations:
- start the next run only from `TryItService.tick` on a later tick, never inside `finish()`/`show()`;
- the handler claims only its own pairId;
- game test blocks 1, 2 and 6 run on every leg.

**Second risk:** cross-restart derivation against the helper's real outcomes, where FAILED can leave the change STAGED with attempts, and ABANDONED closes it. The open P0.4 helper divergence shows reconcile can be wrong in the field; TryItFlow only reads the journal and so inherits any such error.

**Third risk:** verdict honesty on 2 repeats. It's handled by the 5 % minimum floor, the trend floor, and the A/A calibration on the dev PC.

---

## 9. What to cut first (in order)

1. `recent` display: the Preview tooltip "last tried: …". Keep the field in the format (optional). Saves about 0.1.
2. "Measure again" in READY-after-cancel and RESULT: the player can Revert and start a new try. Saves about 0.15.
3. The managed-settings snapshot check (condition 4 of §2.6): the context, mod-set and History checks remain. Saves about 0.25.
4. The title toast: the notice alone brings the player back. Saves about 0.1.
5. The optional `tools/e2e --scenario tryit` (automated real restart); the manual dev-PC run stays.
6. **RESTART kind entirely** (Sodium, DH and Iris keys): ship a same-session-only v1 (vanilla keys, both scenes). Saves about 1.25 (the derivation rows for STAGED/RETRYING/NOT_APPLIED/REVERT_PENDING, game test blocks 3-4, the notice and toast). This is the brainstorm's fallback. If taken, the Preview tooltip for a config key says "Only settings that apply right away can be tried in this version", and CHANGELOG/README say explicitly which scope shipped.

**Never cut:**
- the refusals (§2.2);
- the `tryit-` menu filter;
- the conditions check (context, mod set, History);
- the minimum floor;
- Revert through Undo this;
- TryItGameTest blocks 1, 2 and 6;
- the compat fixtures.
