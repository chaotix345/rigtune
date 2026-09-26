# v0.4 research: benchmark history & regression alerts (P1 item 7), accessibility (P2 item 11)

Research only, no product code changed. Repo checked out at `feat/v0.4.0` (= v0.3.0, tag `v0.3.0`). Vanilla API claims verified with `javap` (JDK 25.0.4.1, `C:/Dev/Tools/jdk/jdk-25.0.4.1+1`) against the real, Mojang-mapped client jars `C:/Users/Admin/.gradle/caches/fabric-loom/26.2/minecraft-client.jar` and `.../26.3/minecraft-client.jar`, and against `fabric-client-gametest-api-v1-6.0.7+4be74c3f5d.jar` (`C:/Users/Admin/.gradle/caches/modules-2/...`) for the gametest API. Anything not confirmed this way is marked **UNVERIFIED** with the reason. No game was launched; no `./gradlew --stop` was run.

Part A builds directly on `docs/research/v0.3/benchmark.md` §3 (the median-comparison design that shipped only the `context` field in 0.3.0 and explicitly deferred its UI "to v0.4" — see that doc's §5 table and draft ACs AC8.11–AC8.13). This doc validates that design's noise-floor formula against real repeated-measurement data, closes the "what changed in between" gap it didn't address, and designs the trend chart and regression alert the v0.4 brief asks for.

## TL;DR
- **Noise (A2):** across 6 real same-session, same-knobs repeat pairs (`docs/v0.3/verification/p5/e-shaders/*.txt`, `docs/v0.2/verification/{f-26.2,d2-26.3-dh}/benchmark-log.txt`), 1%-low CV ranges 1.7%–12.3% (median ≈ 4.2%), avg-FPS CV ranges 0%–2.1% (median ≈ 0.4%). This is a **lower bound** (same launch, ~1 minute apart — no restart/driver/OS jitter), but it lines up well with the 5% default the code and the v0.3 doc already assume. Recommend keeping `docs/research/v0.3/benchmark.md`'s rule — `noise floor = 2 × max(latest.cv or 5%, 1.4826 × MAD(comparable lows)/median)`, requiring ≥ 3 comparable runs — and flag it for retuning once real cross-session history exists.
- **Missing for "what changed" (A1/A3):** `BenchmarkRecord` has no mod-set fingerprint and no anchor into `history.json`. Journal entries (`JournalEntry`/`JournalChange`) already record every RigTune-driven mod/setting change with a timestamp and RigTune/MC version, which is enough to *name* changes RigTune itself made between two runs — but nothing records changes made outside RigTune (manual jar drop, driver update), so a regression can be real and unattributable. Propose two new **optional** fields on `BenchmarkRecord.Context` (`modSetHash`, `journalCursor`); verified (by an existing repo test) that 0.3.0's/0.2.0's Gson readers silently ignore unknown JSON fields, so this needs no schema bump.
- **A11y headline (B1):** of 7 screens, 2 (`BenchmarkMenuScreen`, `RigTuneSettingsScreen`) use vanilla widgets only and are already fully keyboard/narrator accessible. The other 5 (`RigTuneScreen`, `HistoryScreen`, `PreviewScreen`, `UndoScreen`, `BenchmarkResultScreen`) have **8 custom list-row classes, and not one produces real narration**; `ContainerObjectSelectionList.Entry#updateNarration` is package-private in vanilla, so mod code can *only* narrate via `narratables()` — and only 1 of the 8 (`RecommendationEntry`) returns a narratable child at all, a `Checkbox` built with an **empty label** (`RigTuneScreen.java:549`), so even that one row narrates nothing identifying. `BenchmarkResultScreen`'s entire results table and chart are hand-painted in `extractRenderState` with no widget backing whatsoever — unreachable by keyboard, unnarrated. Zero game tests touch focus or narration today (grepped).
- **High contrast (B2):** vanilla 26.2/26.3 both expose `Options.highContrast()` and `Options.highContrastBlockOutline()` (both `OptionInstance<Boolean>`, confirmed identical on both versions via `javap`). The block-outline option is a plain boolean RigTune can read directly to boost its own palette; the resource-pack option is not directly usable by RigTune's hand-drawn colors.
- **Fit recommendation (B4):** full P2 item 11 (nav + narration + high-contrast + tests, all 7 screens) is roughly **11–14 engineer-days**, dominated by rebuilding `BenchmarkResultScreen` as real widgets (2–3 days) and per-screen game tests (~5 days). Given P0/P1 (items 1–10) already load v0.4, recommend landing only the two cheap, screen-agnostic wins now (the empty-checkbox-label fix, and reading `highContrastBlockOutline`) and deferring the rest of item 11 to v0.5.

---

# Part A — Benchmark history and regression alerts (P1 item 7)

## A1. `benchmarks.json` schema across 0.2.0 and 0.3.0, and what's missing for "what changed in between"

### Schema as it exists
`BenchmarkRecord` (`src/main/java/io/github/chaotix345/rigtune/core/benchmark/BenchmarkRecord.java`):

- **0.2.0** (`git show v0.2.0:.../BenchmarkRecord.java`): `id, createdAt, rigtuneVersion, mcVersion, mode, scene, phase, pairId, targetFps, targetMet, knobs, result, costs, notMeasured, world, deadlineHit`. No `context`.
- **0.3.0 (current, `feat/v0.4.0`)**: identical, plus one appended field, `@Nullable Context context` (`BenchmarkRecord.java:19`), where `Context(boolean dhRendering, boolean shaders, @Nullable String shaderPack, int width, int height, boolean fullscreen, int protocol)` (`:51-54`). A compatibility constructor without `context` is kept for old call sites (`:56-61`).

What identifies a run for comparison, and where it lives:
- **MC version** — top-level `mcVersion` (e.g. `"26.2"`).
- **Scene** — top-level `scene`, the `BenchmarkRequest.Scene` enum name (`CURRENT` or `BENCHMARK_WORLD`).
- **Render/simulation distance** — *not* top-level: `knobs.get("renderDistance").value()` / `knobs.get("simulationDistance").value()` (`BenchmarkRecord.KnobResult`, `:28-30`) — the setting the run actually used, with per-knob stats measured at that value (`BenchmarkRecords.of`, `BenchmarkRecords.java:30-33`).
- **RigTune version** — top-level `rigtuneVersion` (e.g. `"0.3.0+mc26.2"`, bundles the mod and MC line the same way `JournalEntry.rigtuneVersion` does).
- **Context** (0.3.0+, optional) — `dhRendering, shaders, shaderPack, width, height, fullscreen, protocol` — deliberately excludes mods/drivers "since detecting their effect is the point of the comparison" (`BenchmarkRecord.java:14-15`, echoing `docs/research/v0.3/benchmark.md:183`).
- **Mod set** — **not recorded anywhere in `benchmarks.json`.** Nothing lists which mod ids/versions were installed or enabled when a run happened.

So "same scene, RD, SD, context" (the task's comparability bar) is fully answerable from the existing schema — `BenchmarkHistory.chart()` just doesn't filter on all of it yet (that's a code gap, see A4, not a schema gap).

### What's missing for "what changed in between"
1. **Journal (`history.json`) already names RigTune-driven changes.** `JournalEntry(id, at, kind, rigtuneVersion, mcVersion, undoOf, changes)` (`JournalEntry.java:7`) records every `apply`/`benchmark`/`undo`/`legacy-import` event with a UTC timestamp (`at`) and versions; `JournalChange` (`JournalChange.java:9`) records each setting change (`key, before, after`) or mod-file action (`enable`/`disable`, `modId`, `file`, `resultFile`). `HistoryModel.build()` (`HistoryModel.java:108`) already turns raw entries into labelled rows (`Row.SETTING/ADDED/DISABLED/REENABLED/UPDATED`) exactly matching what a "what changed" narrative needs — this is reusable wholesale (A3).
2. **But Journal only sees changes RigTune itself made.** A user dropping/removing a jar by hand, another mod manager, a manual `options.txt`/shaderpack edit, or a driver/OS update between two benchmark runs is invisible to both `history.json` and `benchmarks.json`. A regression alert built only from Journal can name *some* causes confidently and would wrongly imply "nothing changed" for the rest.
3. **No anchor between the two files.** Correlating a `BenchmarkRecord.createdAt` with `JournalEntry.at` by timestamp window works but is fragile (clock changes, DST, a hand-edited `benchmarks.json` or `history.json` — both formats explicitly tolerate hand edits/corruption per their `.bad`/`.newer` handling).

**Proposed optional additions to `BenchmarkRecord.Context`** (all optional, all ignored by readers that don't know them — see verification below):
- `journalCursor: String` — the `id` of the newest `history.json` entry as of the benchmark. Turns "changes between run A and run B" into an exact walk of `history.json` entries between two ids (inclusive/exclusive), immune to clock skew, instead of a timestamp-range guess.
- `modSetHash: String` — a hash (e.g. SHA-256) over the sorted `(modId, version)` pairs of every enabled mod jar at benchmark time. Cheap, privacy-preserving (no mod list leaves the machine; it's local history only), and gives the regression alert a way to say "something outside RigTune changed too" even when Journal shows nothing, by comparing hashes between the two runs' `Context`.
- Optionally `driverVersion` (best-effort, from `HardwareProbe`) to separate "a driver update happened" from "a mod/setting changed" in the same window; lower priority than the two above.

**Verified that unknown/optional fields are safe (no schema bump needed).** `BenchmarkHistory.load()` parses via `GSON.fromJson(json, FileFormat.class)` where `FileFormat` is a plain record (`BenchmarkHistory.java:37,90`) — vanilla Gson silently drops JSON keys with no matching record component. This isn't just inferred: `BenchmarkCompatibilityTest.theContextRoundTrips`/`the020ReaderLoadsAFileWrittenBy030` (`BenchmarkCompatibilityTest.java:74-90`) already proves it for the existing `context` field — 0.3.0 writes a run with `context`, and 0.2.0's own pinned reader (`io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory`, a frozen copy of 0.2.0's code with no `context` field at all) loads the same file, produces 4 runs, doesn't quarantine it as `.bad`, and round-trips every other field byte-for-byte once `context` is stripped from the comparison (`:84-86`). Adding `modSetHash`/`journalCursor` the same way (new keys inside the existing optional `context` object, `schemaVersion` staying `1`) follows exactly this precedent. I did not add a new field and re-run this test myself (read-only task) — flagging that the *general* Gson-drops-unknown-keys behavior is standard and demonstrated here for `context` specifically, not independently re-verified for a hypothetical new key, though the mechanism is identical either way.

## A2. Noise threshold, from real repeated-measurement data

No two full, separately-launched `BenchmarkRecord`s with identical settings exist in the repo (there's no accumulated `benchmarks.json` history to mine — same gap the v0.3 doc noted, `docs/research/v0.3/benchmark.md:177`). What *does* exist is same-session repeat data: every verification log samples the same `Knobs` more than once, a few seconds to ~1 minute apart, while `chosen`/`REPEAT` steps stay at fixed settings.

Computed from the raw per-step FPS lines (not the rounded `Aggregate.cv` the code prints, though they match closely — see below):

| source | knobs | avg FPS CV | 1%-low CV | code's logged `result.cv` (2-repeat, lows only) |
|---|---|---|---|---|
| `docs/v0.2/verification/f-26.2/benchmark-log.txt` | RD32/SD12, shaders on | 0.0% (682, 682) | 2.6% | 0.02638 |
| `docs/v0.2/verification/d2-26.3-dh/benchmark-log.txt` | RD5/SD12, DH on | n/a (≈1 FPS, no useful precision) | — | 0.04499 |
| `docs/v0.3/verification/p5/e-shaders/cr-heavy-450-benchmark-log.txt` | RD10/SD12, shaders on | 0.41% | 1.9% | 0.01725 |
| `docs/v0.3/verification/p5/e-shaders/cr-500-benchmark-log.txt` | RD17/SD12, shaders on | 1.6% | 4.0% | 0.03936 |
| `docs/v0.3/verification/p5/e-shaders/calib-benchmark-log.txt` (4 same-knob samples, RD32/SD12) | RD32/SD12, shaders on | 1.8% (n=4) | 15.0% (n=4; RENDER_DISTANCE/SIMULATION_DISTANCE/REPEAT/REPEAT — the two REPEATs alone: 5.3%) | 0.05295 |
| `docs/v0.3/verification/p5/e-shaders/makeup-550-benchmark-log.txt` | RD10/SD10, shaders on | 2.1% | 12.3% | 0.12312 |

(The `calib` row's 4-sample figure includes the RENDER_DISTANCE/SIMULATION_DISTANCE steps, which are less comparable than two REPEATs at literally the same step kind — the "2 REPEATs only" number, 5.3%, is the fairer one and is what the code's own `result.cv` reports.)

**Reading:** avg-FPS CV is small and tight (0%–2.1%, median ≈0.4%) — averages barely move run to run. 1%-low CV is 5–30× noisier (1.7%–12.3% across the 5 two-repeat pairs, median ≈4.2%) — tail latency is inherently noisier than the mean, which is exactly why the task (and the existing code) singles out 1% lows for the regression rule. This is same-launch noise only (no client restart, no driver/OS state change, no different time of day) — real cross-session noise is a **lower bound** here and is very likely higher in practice.

**Threshold rule (confirming/keeping `docs/research/v0.3/benchmark.md`'s AC8.13 design, not replacing it):**

```
noise floor % = 2 × max(latest.cv or NOISY_CV(5%), 1.4826 × MAD(comparable 1%-lows) / median(comparable 1%-lows)) × 100
show a delta only if: n(comparable runs) >= 3  AND  |gainPercent(median, latest.low)| >= noise floor
```

This is literally `BenchmarkMath.gain()`'s existing "2× the larger CV" rule (`BenchmarkMath.java:74-79`, already used for before/after gains) generalized from "the two sides' own CVs" to "the latest run's CV vs. a robust (MAD-based) spread estimate of the comparable history," using `BenchmarkMath.NOISY_CV = 0.05` (`:11`) as the same default floor when a side has no CV. The measured medians above (≈4.2% for 1% lows) sit close enough to that existing 5% default that I see no evidence to change it now; the honest caveat is that this is same-session data, so the floor should be revisited once real historical `benchmarks.json` data (separate launches, days apart) accumulates in the wild — worth a one-line TODO in the implementation, not a blocker.

## A3. Regression rule for 1% lows, and naming what changed in between

**Regression definition** (extends the noise rule above with a direction and a "since when"):

```
Regression = latest.result != null
  && comparable(latest) with >= 3 runs (A2's set: same scene, mcVersion, RD, SD, context)
  && median = median(comparable[i].result.onePercentLowFps)
  && delta = gainPercent(median, latest.result.onePercentLowFps)
  && delta <= -noiseFloor%   // a *drop*, not just any deviation
  -> alert, anchored at the most recent comparable run before `latest` ("since <that run's createdAt>")
```

**Naming what changed between "most recent comparable run" and `latest`:**
1. Read `history.json` via the existing `Journal`/`HistoryModel` (`Journal.entries()`, `HistoryModel.build()`), and take entries with `at` in `(baseline.createdAt, latest.createdAt]` — or, once `journalCursor` (A1) exists, entries strictly after `baseline.context.journalCursor` up to `latest.context.journalCursor`, avoiding the timestamp-window fragility.
2. For each entry, `HistoryModel.Entry.kindKey()`/`Change` rows already produce human labels: `SETTING` (`label: before -> after`), `ADDED`/`DISABLED`/`REENABLED`/`UPDATED` (mod file changes) — feed straight into an alert line, e.g. "Since Sep 24: Sodium 0.6.5 → 0.6.6 (apply), render distance 12 → 16." Also diff `entry.rigtuneVersion()` across the window: a jump with no matching `apply` entry (e.g. a self-update) is itself worth naming ("RigTune 0.3.0 → 0.3.1").
3. If `modSetHash` (A1) differs between `baseline.context` and `latest.context` but the Journal window shows no matching `FILE` change, say so explicitly rather than silently attributing the regression to nothing: "something outside RigTune changed too (mod folder hash differs)". Without this, a regression caused by a manually-dropped mod or a driver update would show a real, correctly-detected FPS drop with an empty/misleading "nothing changed" explanation — worse than no explanation.
4. If the window is empty and mod-set hash matches, say "no change recorded — possibly a driver, OS, or environmental change" rather than nothing.

## A4. Chart rendering today, and the new design

**Today** (`BenchmarkResultScreen.java`): `chartRuns` is `BenchmarkStore.history().chart(scene, mcVersion, CHART_RUNS=10)` (`:65`), and `BenchmarkHistory.chart()` filters **only** by `scene` and `mcVersion` (`BenchmarkHistory.java:172-176`) — not RD, SD, or context. `drawChart()` (`:241-288`) paints two bars per run (avg, 1% low) scaled to the max value seen, a legend, and first/last date labels (`chartDate`, `:304-313`); the current run is highlighted if present. This whole thing is hand-painted inside `extractRenderState` — it is not a `GuiEventListener`/`NarratableEntry` at all (ties directly into Part B: unreachable by keyboard, unnarrated). There is no trend line, no noise/median indicator, and no regression callout anywhere.

**New design:**
- **Comparable-set filter** (code gap, not schema gap): extend `BenchmarkHistory` with a `comparable(BenchmarkRecord latest, int max)` method filtering on scene + mcVersion + RD + SD + `context` equality (exactly AC8.12's spec, `docs/research/v0.3/benchmark.md:252`), reusing/replacing today's scene+mcVersion-only `chart()`. When fewer than the requested runs are comparable, show a "N comparable, M with different settings not shown" note rather than silently mixing incomparable runs into a trend line (today's bar chart does mix them, silently).
- **Per-context trend line:** overlay a thin polyline (median-smoothed or raw) for 1%-low and avg across the comparable set on the existing bar chart, plus the flat median line from A2/A3 (the v0.3 draft explicitly said *not* to draw the median on the old mixed-context chart, `:192` — with a properly filtered comparable set this restriction no longer applies).
- **Where the alert shows:** primarily inline on `BenchmarkResultScreen`, right under the existing gain line (`out.add(gain...)`, `BenchmarkResultScreen.java:141-149`) — this is already the screen that computes and narrates gains, so a regression line ("1% lows −18% vs. your usual 543 — since Sep 24: Sodium 0.6.5→0.6.6") fits the existing `Line`/color pattern (`COLOR_FAIL`/`COLOR_WARN`) with no new screen. Secondarily, a single-line warning banner on `RigTuneScreen`'s header (which already conditionally shows a `COLOR_WARNING`/`ChatFormatting.GOLD` line for `network_off`/`modrinth_off`, `RigTuneScreen.java:222-227`) so a regression is visible without opening the benchmark menu — gate this behind a `ClientSettings` toggle (new field, same pattern as `startupToast`, `ClientSettings.java:27`) so it can be turned off, and only show it once per regression until acknowledged (avoid alert fatigue on every screen open).
- Do **not** put the trend chart or alert only in History — History has no numeric chart today (`HistoryScreen` is a pure text/list journal) and duplicating benchmark math there is unnecessary; History is used from A3's design for *why*, not *where it's shown*.

## A5. Files/classes and test plan

**Touch points:**
- `src/main/java/io/github/chaotix345/rigtune/core/benchmark/BenchmarkRecord.java` — extend `Context` with optional `modSetHash`/`journalCursor`.
- `src/main/java/io/github/chaotix345/rigtune/core/benchmark/BenchmarkHistory.java` — add `comparable(latest, max)` (RD/SD/context-aware), alongside/replacing `chart()`.
- New `src/main/java/io/github/chaotix345/rigtune/core/benchmark/BenchmarkTrend.java` (or extend `BenchmarkMath`) — the noise-floor/regression math from A2/A3 (`median`, `MAD`, `noiseFloorPercent`, `Regression(median, latestLow, deltaPercent, baselineRunId)`).
- New small type, e.g. `core/history/ChangeSummary` or a method on `HistoryModel`, to turn a `(fromEntryIdOrAt, toEntryIdOrAt)` window into the "what changed" line list from A3, reusing `HistoryModel.Row`/labels.
- `src/client/java/io/github/chaotix345/rigtune/client/ui/BenchmarkResultScreen.java` — `drawChart()` gains the trend line + median; new regression `Line`.
- `src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneScreen.java` — optional header regression banner, gated by a new `ClientSettings` field.
- Where the mod-set hash is computed: reuse `ModJars`/`JarInfo` enumeration of the mods folder (`core/history/JarInfo.java`, `core/apply/ModJars.java`) at benchmark time.

**Test plan (mirrors existing patterns, e.g. `BenchmarkHistoryTest`, `BenchmarkCompatibilityTest`, `BenchmarkResultScreenTest`):**
- Unit: `BenchmarkHistory.comparable()` returns only matching scene+mcVersion+RD+SD+context runs, oldest-first, capped at `max` (fixture with a mixed history, mirrors `BenchmarkHistoryTest`'s existing `chart()` tests).
- Unit: noise-floor/regression math — MAD/median on a fixed array, the 2×-floor truth table (below floor → no alert; at/above → alert; n<3 → no alert regardless of delta), matching `BenchmarkMathTest`'s style.
- Unit: change-window extraction — a `history.json` fixture with several entries, assert the returned labels/ordering for a given `(from, to)` window, including the "RigTune version bumped with no apply entry" and "mod-set hash differs, no Journal match" cases.
- Compatibility: extend `BenchmarkCompatibilityTest` with a fixture carrying the new optional `Context` fields, proving the pinned 0.2.0/0.3.0 readers still load it unchanged (same pattern as the existing `context` tests) — this directly re-validates the "no schema bump" claim in A1 for the new fields, which I did not test myself.
- Screen: extend `BenchmarkResultScreenTest` for the new `Line`s (regression text, "in line with usual" text) via a truth table on `BenchmarkController.Outcome` fixtures, matching AC8.9's style for the shader-advice line.
- Game test: one screenshot-based `BenchmarkGameTest` case with a seeded `benchmarks.json` fixture (≥3 comparable runs plus a regressed latest run and a `history.json` fixture with a mod update in between) asserting the result screen renders the trend line and the "since <change>" text (extends the existing autorun/game-test pattern in `docs/v0.3/verification/benchmark/README.md`).

---

# Part B — Accessibility (P2 item 11)

## B1. Inventory: every RigTune screen and custom widget/list

| Screen | Custom widgets/lists | Focus/tab today | Narration today |
|---|---|---|---|
| `RigTuneScreen.java` | `RecommendationList` (`ContainerObjectSelectionList`) → `CategoryEntry`, `RecommendationEntry`; vanilla `Button`/`CycleButton`/`Checkbox` elsewhere | Buttons/CycleButton fully tab/arrow-navigable (vanilla). `CategoryEntry` has no children → **unreachable by keyboard** (`children()` returns `List.of()`, `:514-517`). `RecommendationEntry` is reachable only via its one `Checkbox` child (`:620-623`). | `CategoryEntry.narratables()` = `List.of()` (`:518-522`) → silent. `RecommendationEntry.narratables()` = `[checkbox]` (`:625-628`), but the checkbox is built with **`Component.empty()`** as its label (`:549`) — narrates on/off + usage hint only, never which recommendation. |
| `HistoryScreen.java` | `HistoryList` → `EntryRow`, `ChangeRow` | Both have `children() = List.of()` (`HistoryList.Row`, `:363-366`) → **entirely unreachable by keyboard**; `EntryRow`'s selection (`mouseClicked`, `:412-419`) has no keyboard equivalent at all. | Both inherit `narratables() = List.of()` (`:367-371`) → silent. |
| `PreviewScreen.java` | `PreviewList.Row` (headings + content lines) | `children() = List.of()` (`:332-334`) → unreachable (read-only content, but the *screen's entire purpose* — what Apply will do — is invisible to a screen reader). | `narratables() = List.of()` (`:336-339`) → silent. |
| `UndoScreen.java` | `UndoList` → `SectionEntry`, `ItemEntry` | `children() = List.of()` on the shared `Entry` base (`:219-222`) → unreachable. | `narratables() = List.of()` (`:223-227`) → silent — on the screen that confirms exactly what an Undo will do. |
| `BenchmarkMenuScreen.java` | none (vanilla `Button`/`CycleButton` only) | Fully vanilla-navigable. | Fully vanilla-narrated. |
| `RigTuneSettingsScreen.java` | none (vanilla `CycleButton`/`Button` only) | Fully vanilla-navigable. | Fully vanilla-narrated. |
| `BenchmarkResultScreen.java` | none registered as a widget — the results table (`drawTable`) and the 10-run chart (`drawChart`) are painted directly in `extractRenderState` (`:180-288`) with no backing `GuiEventListener`/`NarratableEntry` | Only the `Use`/`Keep`/`Done` buttons at the bottom are real widgets. **The entire results table and chart are unreachable by keyboard** — nothing to tab to. | Same content is **entirely unnarrated** — a screen reader gets the title text drawn once but nothing about which render distance passed, the FPS numbers, or history. |

**Headline:** 2 of 7 screens (`BenchmarkMenuScreen`, `RigTuneSettingsScreen`) are already fully accessible because they use vanilla widgets exclusively. The other 5 screens have **8 custom `Entry`/`Row` classes**, none of which narrate anything useful, plus one screen (`BenchmarkResultScreen`) whose primary content isn't in the widget tree at all. **Zero game tests exercise keyboard focus or narration** today (`grep -rn "Tab\|Focus\|Narration" src/gametest` → no matches; confirmed by reading `UiGameTest.java` in full, 422 lines, no focus/narration assertions).

## B2. Vanilla 26.2/26.3 APIs, verified via `javap`

All confirmed identical on 26.2 and 26.3 unless noted.

- **`NarratableEntry`** (`net.minecraft.client.gui.narration`) extends **`NarrationSupplier`** (one method: `void updateNarration(NarrationElementOutput)`) and **`TabOrderedElement`** (`int getTabOrderGroup()`). Confirmed via `javap`.
- **`NarrationElementOutput`**: `add(NarratedElementType, Component|String|Component...)` (defaults) + abstract `add(NarratedElementType, NarrationThunk<?>)` and `nest()`.
- **`NarratedElementType`**: enum `TITLE, POSITION, HINT, USAGE`.
- **`GameNarrator`** lives at **`net.minecraft.client.GameNarrator`**, *not* under `gui.narration` — easy to get wrong from memory; confirmed by directory listing + `javap`. It wraps `com.mojang.text2speech.Narrator` and exposes `sayChatQueued`/`saySystemNow`/etc. — this is the actual TTS sink, not something a game test should assert against directly.
- **`ContainerObjectSelectionList<E>`** (extends `AbstractSelectionList<E>`): `nextFocusPath(FocusNavigationEvent)`, `updateWidgetNarration(NarrationElementOutput)` — both **public**. `ContainerObjectSelectionList.Entry<E>`: `abstract List<? extends NarratableEntry> narratables()` (the only extension point mod code can use), plus `focusPathAtIndex`/`nextFocusPath` (public), and **`void updateNarration(NarrationElementOutput)` with no access modifier — package-private**. Since RigTune's `Entry` subclasses live in `io.github.chaotix345.rigtune.client.ui`, a different package, **they cannot override `updateNarration` at all**; `narratables()` is the only door, confirming why every row above is silent by construction, not by oversight-that-could-be-patched-in-place.
- **`ComponentPath`**: `leaf(GuiEventListener)`, `path(ContainerEventHandler, ComponentPath)`, `path(GuiEventListener, ContainerEventHandler...)`, `component()`, `applyFocus(boolean)`, `leafComponent()`. **`FocusNavigationEvent`** has two concrete record implementations confirmed present: `TabNavigation(boolean forward)` and `ArrowNavigation(ScreenDirection direction, ScreenRectangle previousFocus)` — vanilla 26.x's unified focus system natively supports both Tab and arrow-key navigation; RigTune inherits this for free for any widget that's actually in the tree.
- **`GuiEventListener`**: `nextFocusPath`, `getCurrentFocusPath()` (default, walks the focus chain — usable from a test on the `Screen` itself with no extra plumbing), `isFocused()`/`setFocused(boolean)` (abstract), `getRectangle()` (default).
- **High contrast**: `net.minecraft.client.Options` has `highContrast()` and `highContrastBlockOutline()`, both returning `OptionInstance<Boolean>`, confirmed byte-identical field/method signatures on 26.2 and 26.3. `highContrast` is Mojang's "High Contrast" **resource pack** toggle (swaps textures — not directly usable to recolor RigTune's own custom-drawn UI). `highContrastBlockOutline` is a plain, resource-pack-independent boolean (`minecraft.options.highContrastBlockOutline().get()`) that RigTune could read to switch its own palette (e.g., stronger `COLOR_WARNING`/`COLOR_FAIL` values, a visible focus ring on custom-painted content) without depending on any pack being installed.
- **Correction of an assumption I made and disproved:** `Screen.getNarrationMessage()` looked, from its name, like a way for a test to pull the full accumulated narration text. `javap -c` shows its body is exactly `return getTitle();` — it's the screen-title override point (used by things like confirmation screens), **not** the narration collector. The real mechanism is `net.minecraft.client.gui.narration.ScreenNarrationCollector`, which is a standalone **public** class: `public ScreenNarrationCollector()`, `public void update(Consumer<NarrationElementOutput>)`, `public String collectNarrationText(boolean onlyNew)`. Since `ContainerObjectSelectionList.updateWidgetNarration(NarrationElementOutput)` is public, a test can do `new ScreenNarrationCollector().also(c -> c.update(list::updateWidgetNarration))` then `collectNarrationText(false)` to get the exact text vanilla's narrator pipeline would produce for that list, entirely through public API, no reflection — see B3.
- **Controller support**: I found no gamepad/controller input API anywhere in the 26.2/26.3 client jar (searched `Options`, `GuiEventListener`, the `navigation`/`input` packages) — **UNVERIFIED as an exhaustive negative** (absence of evidence in a targeted search, not a documented "vanilla has none" statement), but consistent with common knowledge that vanilla Java Edition has no native controller support. **Controlify** (external mod, listed as a "Functional" mod in this repo's own compatibility notes, `docs/research/knowledge.md:163`) is the addressed path. Per its own public pages (WebSearch, 2026-09-26 — I could not access its source, so the exact vanilla hooks it calls are **UNVERIFIED**): it drives arbitrary vanilla *and modded* screens generically via controller-to-virtual-mouse-cursor emulation ("cursor snapping" to widgets) plus keyboard-key emulation and an on-screen keyboard, rather than a special per-mod integration API. Practical implication: RigTune doesn't need Controlify-specific code; "controller-friendly" reduces to the same two things keyboard-only users need — real hit-boxes (already true, every row extends `ContainerObjectSelectionList.Entry`, which has `getRectangle()`/`isMouseOver`) and a complete Tab/Arrow focus chain (today's gap, since childless entries have nothing for a synthetic Tab press — or a cursor-snap target list, if Controlify builds one from the focus tree — to land on).

Sources: [Controlify (Modrinth)](https://modrinth.com/mod/controlify), [Controlify site](https://controlify.isxander.dev/), [Controlify — isxander.dev](https://www.isxander.dev/projects/controlify), [Modded MC Wiki](https://moddedmc.wiki/en/project/controlify/latest/docs).

## B3. Driving and asserting keyboard nav + narration from a client game test

Confirmed via `javap` on `fabric-client-gametest-api-v1-6.0.7`:
- `TestInput`: `pressKey(int)`, `pressKey(KeyMapping)`, `holdShift()`/`releaseShift()`, `holdKey`/`releaseKey` variants, `typeChar(s)`, `scroll`, `moveCursor`/`setCursorPos`. `com.mojang.blaze3d.platform.InputConstants.KEY_TAB` and `KEY_ESCAPE` both confirmed present (int constants). This repo already uses exactly this pattern: `context.getInput().pressKey(InputConstants.KEY_ESCAPE)` in `BenchmarkGameTest.java:336`, `RigTuneClientGameTest.java:138`, `ProductionSmoke.java:105` — so `context.getInput().pressKey(InputConstants.KEY_TAB)` (optionally wrapped in `holdShift()`/`releaseShift()` for reverse tab order) is the natural, already-idiomatic way to drive focus.
- `ClientGameTestContext.computeOnClient(FailableFunction<Minecraft, T, E>)` / `runOnClient(...)`: confirmed present, for reading state off the render thread inside a test.

**Reading focus:** `GuiEventListener.getCurrentFocusPath()` is a public default method already on `Screen` (which implements the `GuiEventListener`/`ContainerEventHandler` chain) — `context.computeOnClient(mc -> mc.screen.getCurrentFocusPath())` gives a `ComponentPath` whose `leafComponent()` identifies exactly which widget (down into a list entry's child) is focused, with no product-code changes needed.

**Reading narration:** the public, verified path from B2 — construct a `ScreenNarrationCollector`, call `update(theList::updateWidgetNarration)`, then `collectNarrationText(false)`. This needs the test to reach the screen's `ContainerObjectSelectionList` instance; the codebase already has a convention for exactly this (test-only accessors, not reflection): `HistoryScreen.entryRow(String)`/`changeRowText()` (`HistoryScreen.java:81-104`), `PreviewScreen.list()`/`rowText()` (`PreviewScreen.java:76-98`). Recommend adding an equivalent `list()`/`narrationText()`-style accessor to `RigTuneScreen`, `UndoScreen`, and (once it has a real list) `BenchmarkResultScreen`, matching this existing pattern.

**Caveat (explicitly unverified without running the game):** I confirmed the *types and public signatures* by static analysis of the real jars, not the runtime *behavior* of `updateWidgetNarration` — e.g., whether it narrates only the focused/hovered entry vs. every visible entry vs. only entries flagged dirty since the last call. That distinction affects exactly how a test should drive focus before reading narration, and should be confirmed empirically in the implementation phase (a throwaway logging test run), not assumed from the method signature.

**End-to-end shape of a test** (mirrors `UiGameTest.java`'s existing setup, e.g. `Screens`/`ClientGameTestContext` use around `:20-40`): `context.setScreen(() -> new RigTuneScreen(...))` → `context.getInput().pressKey(KEY_TAB)` N times → `computeOnClient` to assert the focus path advances into the list and each checkbox in turn (never silently skipping a childless row, which would itself indicate a still-broken entry) → at a chosen focus, assert the collected narration text contains the recommendation's title/impact, not just "checkbox".

## B4. Effort per screen, and fit for v0.4

| Screen | Work needed | Estimate |
|---|---|---|
| `BenchmarkMenuScreen` | none (already vanilla-only); add a game test | 0.25 d |
| `RigTuneSettingsScreen` | none (already vanilla-only); add a game test | 0.25 d |
| `RigTuneScreen` | `CategoryEntry`: decide divider policy (vanilla precedent allows a non-focusable divider that still narrates once via a narratable child, `TITLE` only, no control). `RecommendationEntry`: give the `Checkbox` a real label carrying title+impact+reason instead of `Component.empty()` (`:549`) without duplicating the custom-drawn visual text; verify tab order across category headers and rows. | 1.5–2 d |
| `HistoryScreen` | `EntryRow` needs a keyboard-equivalent activation for what `mouseClicked` does today (`:412-419`) plus narration; `ChangeRow` needs narration only (read-only). | 1–1.5 d |
| `PreviewScreen` | Read-only; needs narration only, but grouping raw lines into meaningful chunks ("Now: 3 settings; render distance 12 → 16" as one utterance) is a design pass, not just plumbing. | 1 d |
| `UndoScreen` | `SectionEntry` (divider, same policy as `CategoryEntry`) + `ItemEntry` (read-only) need narration only — the highest-stakes screen to leave silent (it's the "are you sure" for Undo). | 0.75–1 d |
| `BenchmarkResultScreen` | The hard one: the results table and chart have no widget backing at all. Making them keyboard/narrator accessible means rebuilding the table as a real `ContainerObjectSelectionList` (one row per RD measurement) and giving the chart an equivalent textual/list form for narration. Close to a partial rewrite of the screen's render path. | 2–3 d |
| High contrast | Read `highContrastBlockOutline`; re-palette the ~6 screens' color constants and add a visible focus indicator for any newly-focusable custom content. | 1 d |
| Game tests (all screens, per B3's design) | ~0.5–1 d/screen once the test-accessor convention exists | 4–5 d |

**Total: roughly 11–14 engineer-days**, dominated by `BenchmarkResultScreen`'s rebuild and the cross-cutting test work.

**Recommendation:** P2 item 11 is correctly scored a stretch item. v0.4's own working log already stacks P1 items 4–10 (profiles/share codes, Stutter Doctor, JVM/GC advice, this benchmark-history item, server-aware advice, driver-condition awareness, RigTune's footprint budget) ahead of it; a ~2-week item doesn't fit alongside that load. Concretely:
1. **Land two cheap, independent, high-value fixes regardless of the rest:** the `RecommendationEntry` checkbox's empty label (`RigTuneScreen.java:549`) is a one-line, low-risk fix that immediately makes the RigTune screen's main list say *something* meaningful, and reading `highContrastBlockOutline` for RigTune's own palette is small and screen-agnostic.
2. **Do not attempt `BenchmarkResultScreen`'s rebuild or full game-test coverage in v0.4.** Defer the bulk of item 11 to v0.5, sized at ~2 weeks, calling out `BenchmarkResultScreen` explicitly as the long pole and the test-accessor convention (B3) as the prerequisite that makes the rest of the work verifiable.

## B5. Files/classes and test plan

**Touch points:**
- `src/client/java/io/github/chaotix345/rigtune/client/ui/RigTuneScreen.java` — `RecommendationList.CategoryEntry`/`RecommendationEntry` (`:492-629`), the empty-label `Checkbox` at `:549`.
- `.../HistoryScreen.java` — `HistoryList.Row`/`EntryRow`/`ChangeRow` (`:362-457`).
- `.../PreviewScreen.java` — `PreviewList.Row` (`:294-340`).
- `.../UndoScreen.java` — `UndoList.Entry`/`SectionEntry`/`ItemEntry` (`:218-280`).
- `.../BenchmarkResultScreen.java` — `drawTable`/`drawChart` (`:205-288`), the part needing the widget rebuild.
- `net.minecraft.client.Options.highContrastBlockOutline()` — new read, likely surfaced through a small helper alongside `SettingsBridge`/`HardwareProbe` (client package) rather than scattered `minecraft.options` calls.

**Test plan:**
- Unit/screen tests (JVM, no game): for each row class, a narration-content test once `narratables()`/labels change — assert the produced `Component`s' string content includes the expected identifying text (title, impact, status), following the existing `BenchmarkResultScreenTest` truth-table style.
- Game tests (new, per B3): for each of the 5 non-trivial screens, one test that opens the screen, presses Tab N times, and asserts (a) focus never gets stuck/skips a whole row and (b) `ScreenNarrationCollector.collectNarrationText(false)` on the focused row's owning list contains the expected substring (e.g. a recommendation's title) — extending the existing `UiGameTest.java`/`HistoryGameTest.java`/`PreviewGameTest.java`/`UndoGameTest.java` files rather than new top-level test classes, matching the one-file-per-screen convention already in `src/gametest`.
- High contrast: a screenshot-comparison game test (`assertScreenshotEquals`/`assertScreenshotContains`, already used elsewhere in `src/gametest`) with `highContrastBlockOutline` toggled on, asserting the re-palette actually changes rendered pixels, not just that the option is read.
