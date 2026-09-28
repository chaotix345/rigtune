# C18 — Launch-time regression alerts: design research

Summary (10 lines): Today `StartupTimesStore`/`StartupTimes` (`core/footprint/StartupTimesStore.java`,
`client/footprint/StartupTimes.java`) already record 30 launches and show "last · median of 10" plus a bare
`modSetChanged` boolean on the Tools screen — no magnitude, no noise floor, no notice. The critic (brainstorm.md
§9.4) is right: `BenchmarkTrend.assess()` is hardwired to `BenchmarkRecord`/`Context` and cannot run against
`StartupTimesStore.Run`; only its pure `median()`/`mad()` (and the arithmetic shape of `noiseFloorPercent()`) are
truly reusable. This doc adds one small new pure classifier (`core/footprint/StartupTrend`), one new
`NoticeSource`, one new `NoticePriority` slot, and a handful of `awareness.json`/`en_us.json` additions — no
change to `startup-times.json`'s on-disk shape at all. Comparable-by is MC version only (not RigTune version,
not Java — no Java-version field exists today and adding one is cut from this cycle, see §9). The noise floor and
minimum-run count need new, explicitly-estimated constants (launch time is much noisier than benchmark FPS, per
docs/research/v0.4/footprint.md's own single-PC data) — flagged honestly as unverified pending real player data,
mirroring WS-B's own "retune once real cross-session history exists" precedent. No render-thread work is added
(classification rides the existing off-thread `StartupTimes.record()`/cached `view()` path). 26.2/26.3 are
identical for every file this touches (verified: zero Stonecutter `//? if` markers in the footprint/notice
source). Effort: ~2 agent-days, matching the brainstorm's estimate, once the classifier is scoped as new-but-small
rather than a call into `BenchmarkTrend.assess()`. Riskiest part is honestly calibrating the noise floor without
real multi-launch player data — not any implementation mechanic.

---

## 1. The brainstorm's open questions and the critic's correction

### 1.1 Critic's correction (brainstorm.md §9.4), confirmed against the actual code

The pitch's "reuses the exact noise-floor/regression math already built for `BenchmarkTrend`" overstates the
reuse, exactly as the critic found:

- `BenchmarkTrend.assess()` (`core/benchmark/BenchmarkTrend.java:237-261`) takes a `BenchmarkRecord` and a
  `List<BenchmarkRecord>`, and its baseline/comparability come from `comparable()`/`differences()`
  (`BenchmarkTrend.java:128-172`), which compare `mcVersion`, `scene`, render/simulation-distance knobs and
  `BenchmarkRecord.Context` (resolution, fullscreen, shaders+pack, Distant Horizons, protocol). None of that
  exists on `StartupTimesStore.Run` (`core/footprint/StartupTimesStore.java:23-25`: `at, ms, mcVersion,
  rigtuneVersion, mods, modSetHash` — no scene, no distances, no `Context`). `assess()` will not compile against
  `Run` and must not be called as-is.
- Only `median(double...)`, `mad(double...)` (`BenchmarkTrend.java:188-206`) are genuinely drop-in: pure
  functions over `double...`, no `BenchmarkRecord` dependency. `noiseFloorPercent(@Nullable Double latestCv,
  double... baselineLows)` (`BenchmarkTrend.java:209-214`) is *arithmetically* reusable (`2 × max(cv, spread) ×
  100`) but its fallback constant, `BenchmarkMath.NOISY_CV = 0.05` (`BenchmarkMath.java:11`), is calibrated from
  same-session 1%-low FPS noise (docs/research/v0.4/footprint.md's sibling research, cited in
  `BenchmarkTrend.java:22-24`: "same-session 1%-low CVs were 1.7-12.3%, median 4.2%"), not launch-time noise.
  There is also no per-launch "cv" to pass as `latestCv` — a launch produces one number, not a sampled
  distribution the way a benchmark sweep does — so every call from launch-time code would pass `null` and always
  fall back to that FPS-tuned constant unless a launch-specific one is supplied.
- **Verdict for this design:** write a small new classifier, `core/footprint/StartupTrend`, that calls
  `BenchmarkTrend.median()`/`mad()` directly (genuine reuse) and its own comparability/floor logic (new, small,
  the "small new classifier" the critic asked for) rather than `BenchmarkTrend.assess()`.

### 1.2 What is comparable

**MC version only.** Reasoning:
- Render/simulation distance, resolution etc. don't exist as a concept for "time to reach the title screen" (no
  world is loaded yet) — there's nothing else to compare on.
- **RigTune version is not part of comparability** — a RigTune update slowing its own startup is exactly one of
  the things this feature should be able to flag, not something that silently splits the baseline. Instead it's
  annotated the same lightweight way `ChangeWindow.rigtuneChanged()` already annotates a RigTune version bump for
  benchmarks (`core/benchmark/ChangeWindow.java:33-35`, comparing two version strings directly) — no new field
  needed, `StartupTimesStore.Run.rigtuneVersion()` already exists.
- **Java version is not tracked at all today** — `StartupTimesStore.Run` has no field for it. Adding one is
  scoped out of this cycle (see §9); this directly answers the brainstorm's open question #2 ("suppress or
  co-annotate a Java/driver update"): **suppressed this cycle** — the wording only ever names a mod-set or
  RigTune-version change because those are the only two signals actually stored, which stays honest (the message
  never claims a cause it can't back with data) at the cost of not catching a Java/driver-caused regression's
  likely cause. Documented as a known limitation, not silently dropped.
- **Mod-set hash is not part of comparability** (mirrors `BenchmarkTrend.java:20`'s "the mod-set hash never
  splits runs" for benchmarks) — its role is the *explanation*, not the grouping.

### 1.3 Minimum run count and threshold, given launch time's natural variance

`docs/research/v0.4/footprint.md` §2.1-2.2 is the only real launch-time variance data in the repo: 10 launches per
arm on one quiet Windows desktop gave a combined range of 14124-16296 ms around a ~14500 ms median — roughly
**±7.5% (≈15% peak-to-peak)** even with a warm disk cache and no other load, and the real with/without RigTune
delta (162-176 ms, ~1.1-1.2%) was "not safely distinguishable from zero" at that sample size. This is
**considerably noisier** than the benchmark side's same-session 1%-low CVs (1.7-12.3%, median 4.2%, per
`BenchmarkTrend.java:22-24`), which is the basis for `BenchmarkMath.NOISY_CV = 0.05`.

**Recommendation (ESTIMATE, ties to §9's ask for a decision):**
- `StartupTrend.MIN_RUNS = 5`, not `BenchmarkTrend.MIN_RUNS`'s 3. With only 3 launches, a MAD-based spread
  estimate is itself too noisy to trust for a signal this variable; 5 is still well under
  `StartupTimesStore.MEDIAN_OF = 10` (the window already shown on Tools) and `MAX_RUNS = 30` (the file cap), so it
  costs nothing structurally.
- `StartupTrend.MAX_RUNS = StartupTimesStore.MEDIAN_OF` (10) — the same baseline window already shown as "median
  of your last 10", so the notice's "usual" number and the passive Tools line's number are always the same figure.
- Floor: reuse `BenchmarkTrend.noiseFloorPercent(null, baselineMs...)` as-is (i.e. accept its built-in
  `NOISY_CV` fallback, giving a floor of **at least 10%**, since `2 × 0.05 × 100 = 10`) rather than inventing a new
  constant. This is a deliberate, honest compromise: it borrows a constant calibrated for a different signal, but
  doing so (a) keeps the implementation genuinely small (literally reusing the function, not just its shape,
  answering the effort question), and (b) 10% is already in the right order of magnitude next to footprint.md's
  observed ~15% peak-to-peak spread (a peak-to-peak range is naturally wider than a 2×MAD-based floor). **This
  constant is an estimate, not a measurement — flag it to the spec/build agent as the first thing to revisit once
  real multi-launch player data exists**, exactly mirroring WS-B's own shipped caveat ("the noise floor is from
  same-session data; retune once real cross-session history exists" — WS-B's design doc, `docs/v0.4/design/ws-b.md`
  §"UNVERIFIED"). If the spec agent has budget to do better than "borrow the benchmark constant", the more
  defensible number from footprint.md's own data would be a dedicated `StartupTrend`-only constant in the 12-15%
  range — but that is new tuning work the 2-day estimate may not have room for, hence the recommendation to reuse
  first and revisit later.

### 1.4 Wording

Always a number plus "may be related to `<X>`", never a diagnosis, per the honesty rule and matching
`TrendText.regression()`/`TrendText.changes()`'s existing shape exactly. Proposed `en_us.json` keys (area
`rigtune.startup`, per README.md:280's key-area convention: `startup` = the launch time):

| Key | English | Args |
|---|---|---|
| `rigtune.startup.regression` | "Launch time %s%% higher than usual (%s s vs your usual ~%s s)" | deltaPercent, latestSeconds, medianSeconds |
| `rigtune.startup.regression.mod_count_changed` | "May be related to your mod set changing (%s → %s mods) since your last launch" | previous mod count, latest mod count |
| `rigtune.startup.regression.mod_set_changed` | "May be related to your mod set changing since your last launch" | (hash differs, same count) |
| `rigtune.startup.regression.rigtune_changed` | "RigTune %s → %s since your last launch" | previous version, latest version |
| `rigtune.startup.regression.no_change` | "No change recorded since your last launch; possibly another program running, a cold disk cache, or a driver/OS update" | — |
| `rigtune.startup.notice.tools` | "Tools…" | (notice action label) |
| `rigtune.startup.notice.acknowledge` | "Got it" | (notice action label) |

These mirror `rigtune.benchmark.trend.regression`/`.changes`/`.rigtune`/`.no_change`/`.notice.rerun`/
`.notice.acknowledge`'s exact shape (`en_us.json:644` and around; `TrendText.java:62-118`). `mod_count_changed`
fires when `Run.mods()` differs between the comparison point and the latest run; `mod_set_changed` (no counts)
fires when only `modSetHash()` differs (count unchanged, e.g. one mod swapped for another) — two keys, not one
with conditional args, because `LangCheckTest` ties one key to one fixed argument list
(`src/test/java/io/github/chaotix345/rigtune/LangCheckTest.java:47-51`, rule (e)). At most one cause line shows
(mod-set change is checked first, then RigTune version, else `no_change`) — never stacking two guesses, which
would read like RigTune is fishing for a cause.

### 1.5 Where it shows

- **A notice**, new `NoticePriority.STARTUP_REGRESSION`, inserted **after `BENCHMARK_REGRESSION`, before
  `HARDWARE_CHANGED`** (`core/notice/NoticePriority.java:4-11` currently: `BATTERY_OFFER, SERVER_LIMIT,
  BENCHMARK_REGRESSION, HARDWARE_CHANGED, WHATS_NEW, BENCHMARK_STALE`) — grouped with the other regression-style
  alert, below the two safety/correctness-affecting slots (battery, server limit). `NoticePriority` is a plain
  enum with no persisted ordinal anywhere (`Notice`/`NoticeAction` records are computed fresh per screen init,
  never serialized — confirmed by reading `core/notice/Notice.java` and how `NoticeCenter.notices()` builds them
  live), so inserting a new constant anywhere in the list is compat-safe.
- **Non-dismissible except by acknowledging** (mirrors `RegressionNoticeSource`, not `BenchmarkStaleNoticeSource`
  — a launch-time regression is a one-off-per-session event like a benchmark regression, not a recurring "needs a
  rerun" marker, so the softer dismissible-and-refires pattern doesn't fit). Two actions (the `Notice`/UI cap is 2,
  per `core/notice/Notice.java`'s doc comment and `RigTuneScreen`'s rendering): **"Tools…"** (opens `ToolsScreen`,
  mirroring `BenchmarkStaleNoticeSource`'s `RERUN` → `BenchmarkMenuScreen` pattern exactly) and **"Got it"**
  (acknowledges, mirrors `RegressionNoticeSource.ACKNOWLEDGE`).
- **Also on the Tools screen**, extending the existing passive detail block (`ToolsScreen.java:93-100`,
  `startupDetail(StartupTimes.View)`) with the same regression + cause line(s) whenever the latest run is
  classified `SLOWER`, in the existing `COLOR_NOTE` highlight color (`ToolsScreen.java:25`) that
  `mod_set_changed` already uses — so a player who acknowledged (or never saw) the notice can still see the fact
  by opening Tools directly, exactly the same relationship `BenchmarkResultScreen` has to
  `RegressionNoticeSource` (the screen always shows the trend; the notice is the proactive nudge).

### 1.6 What the player can do about it

Nothing is applied — advice-only throughout, matching the brainstorm's own framing. The two notice actions are
"Tools…" (points at the existing startup detail — mods count/RigTune version, i.e. "what changed") and "Got it"
(acknowledge). No "disable a mod" button, no auto-anything: consistent with C18's own "Undo & safety: advice-only"
claim and the project's non-goal of applying fixes here.

### 1.7 Outliers

A single unusually slow launch (antivirus scan, background update, etc.) is handled the same way `BenchmarkTrend`
already handles a real-but-uncaused benchmark regression: `median()`/`mad()` are robust statistics (a lone outlier
in the *baseline* barely moves the median or MAD), and when the *latest* run is the outlier, the message is still
literally true ("this launch was slower than your usual by more than the noise floor") without claiming a
permanent regression — the wording never says "RigTune got slower", only "launch time is higher than usual [this
time]". When nothing tracked (mod set, RigTune version) actually changed, `rigtune.startup.regression.no_change`
says so plainly, including "possibly another program running" as one of the honest, named possibilities — this
directly mirrors `TrendText.java:82` ("No change recorded; possibly a driver, OS or other change"). Because each
launch re-evaluates independently and the notice's key is per-run (`startup.regression.<run "at" timestamp>`), an
outlier notice is automatically superseded by the next launch's own (better or worse) assessment — no stacking,
no need for a separate "was this a fluke" mechanism.

### 1.8 `startup-times.json`'s needs

**No format change.** Every field the classifier needs (`at, ms, mcVersion, rigtuneVersion, mods, modSetHash`)
already exists in `StartupTimesStore.Run` (`StartupTimesStore.java:23-25`) and is already written by
`StartupTimes.record()` (`client/footprint/StartupTimes.java:60-82`). 0.4.0 keeps reading and writing this file
identically after a downgrade — this is a stronger compat story than the original pitch's "purely additive"
framing, since it turns out to need *zero* new fields, not just optional ones.

The only new persisted state is in `awareness.json` (see §2), which already has the identical, precedented
pattern for exactly this kind of thing (`acknowledgedRegressions`).

---

## 2. Design: classes to add/change, data, UI, wording, threading, error handling

### 2.1 New: `core/footprint/StartupTrend` (pure, no Minecraft types — mirrors `core/benchmark/BenchmarkTrend`)

```java
package io.github.chaotix345.rigtune.core.footprint;

public final class StartupTrend {
    public static final int MIN_RUNS = 5;                                   // §1.3 — ESTIMATE
    public static final int MAX_RUNS = StartupTimesStore.MEDIAN_OF;         // 10, reuses the existing window

    public enum Kind { NO_RUN, TOO_FEW, IN_LINE, IMPROVEMENT, SLOWER }
    public enum Cause { NONE, MOD_COUNT, MOD_SET, RIGTUNE_VERSION }         // "may be related to", first match wins

    public record Assessment(Kind kind, int baselineRuns, @Nullable Double medianMs, @Nullable Double latestMs,
            @Nullable Double deltaPercent, @Nullable Double floorPercent, Cause cause,
            @Nullable Integer previousMods, @Nullable Integer latestMods,
            @Nullable String previousRigtuneVersion, @Nullable String latestRigtuneVersion) { }

    // The comparable (same mcVersion) runs before `latest`, newest MAX_RUNS, oldest first. Mirrors
    // BenchmarkTrend.baseline()'s shape exactly, without a `runs()`-relative id lookup (StartupTimesStore.Run has
    // no id; runs are already in append order, so "before latest" is simply "runs.subList(0, indexOf(latest))",
    // and in practice the caller always passes `runs` with the latest as the last element).
    public static List<StartupTimesStore.Run> baseline(List<StartupTimesStore.Run> runs) { ... }

    public static Assessment assess(List<StartupTimesStore.Run> runs) {
        // runs: oldest-first, as StartupTimesStore.runs() already returns them.
        // Guards baseline.isEmpty() before calling BenchmarkTrend.median()/mad() (which throw on empty input,
        // BenchmarkTrend.java:190) — mirrors BenchmarkTrend.assess()'s own MIN_RUNS check at BenchmarkTrend.java:243.
    }
}
```

- Reuses `BenchmarkTrend.median(double...)`, `BenchmarkTrend.mad(double...)`,
  `BenchmarkTrend.noiseFloorPercent(null, baselineMs...)` verbatim (all public, pure, package-independent) — the
  genuine reuse the critic asked to scope correctly, not a call into `assess()`.
- `Cause` resolution: compare the latest run against the **newest comparable run before it** (the same run that
  anchors the median/floor) — mod count differs → `MOD_COUNT`; else mod-set hash differs → `MOD_SET`; else
  RigTune version differs → `RIGTUNE_VERSION`; else `NONE`. First match wins (never stacks two guesses, §1.4).
- `IMPROVEMENT` is computed for symmetry/testability (mirrors `BenchmarkTrend.Kind.IMPROVEMENT`) but is not
  surfaced anywhere in v1 — cut first if time is short (§9); the classifier costs nothing extra to compute it
  since the same `delta`/`floor` comparison already produces it.

### 2.2 Modify: `client/footprint/StartupTimes.View` and `StartupTimes`

Add an `@Nullable StartupTrend.Assessment assessment` field to `View` (in-memory only, not persisted — the record
isn't Gson-serialized, it's the client-side read model `ToolsScreen`/the new `NoticeSource` consume). Computed in
`summarize()` (`StartupTimes.java:97-100`) alongside the existing `StartupTimesStore.summarize(...)` call, using
`store().runs()` (already read there) — no new file read, no new thread hop: it rides the exact same
`refresh()`/`record()` off-render-thread path that already exists (`StartupTimes.java:56-58`,
`CompletableFuture.runAsync(() -> record(ms), Probes.EXECUTOR)`), and the exact same `synchronized` cache
`view()` already provides (`StartupTimes.java:86-91`). **Nothing new touches the render thread beyond what
`StartupTimes` already hooks** (the title-screen callback), satisfying the footprint constraint directly.

### 2.3 New: `client/notice/StartupRegressionNoticeSource`

Mirrors `RegressionNoticeSource`/`BenchmarkStaleNoticeSource` exactly:

```java
public final class StartupRegressionNoticeSource implements NoticeSource {
    public static final String KEY_PREFIX = "startup.regression.";
    public static final String TOOLS = "tools";
    public static final String ACKNOWLEDGE = "acknowledge";

    private final RealController controller;
    private volatile @Nullable String runAt;

    @Override
    public @Nullable Notice current() {
        StartupTimes.View view = controller.startupTimesService().view(); // already-cached, cheap
        StartupTrend.Assessment a = view.assessment();
        if (a == null || a.kind() != StartupTrend.Kind.SLOWER || controller.awarenessService().acknowledgedStartupRegression(runAtKey))
            { runAt = null; return null; }
        runAt = ...; // the latest run's `at` field, the natural stable per-launch key
        return new Notice(KEY_PREFIX + runAt, NoticePriority.STARTUP_REGRESSION, /* rigtune.startup.regression */,
                /* the cause line as detail, or null */,
                List.of(new NoticeAction(TOOLS, Text.of("rigtune.startup.notice.tools", "Tools…")),
                        new NoticeAction(ACKNOWLEDGE, Text.of("rigtune.startup.notice.acknowledge", "Got it"))), false);
    }

    @Override
    public void act(String actionId) { /* ACKNOWLEDGE -> awarenessService().acknowledgeStartupRegression(runAt);
                                            TOOLS -> minecraft.gui.setScreen(new ToolsScreen(...)) */ }
}
```

Registered in `RealController`'s `NoticeCenter` construction (`RealController.java:179-181`), one more entry in
the `List.of(...)` alongside the other six sources — one line, matching how every prior notice source was added.

### 2.4 Modify: `core/notice/NoticePriority`

Add `STARTUP_REGRESSION` between `BENCHMARK_REGRESSION` and `HARDWARE_CHANGED` (§1.5).

### 2.5 Modify: `core/awareness/AwarenessStore`

Add `ACKNOWLEDGED_STARTUP_REGRESSIONS = "acknowledgedStartupRegressions"` and
`acknowledgedStartupRegressions()`/`acknowledgeStartupRegression(String at)`, mechanically identical to the
existing `acknowledgedRegressions()`/`acknowledgeRegression(String runId)` pair
(`core/awareness/AwarenessStore.java:113-141`) — same `MAX_ACKNOWLEDGED` cap (reuse the constant), same
dedup-and-append-newest `update()` pattern, added to `withDefaults()` (`AwarenessStore.java:56-64`) as one more
`JsonArray` default.

### 2.6 Modify: `client/ui/ToolsScreen`

Extend `startupDetail(StartupTimes.View)` (`ToolsScreen.java:93-100`) to append the regression + cause line(s)
(same `Component.translatable` calls the notice uses, `COLOR_NOTE`-highlighted) when
`view.assessment().kind() == SLOWER`, ahead of the existing `mod_set_changed`/`advice` lines. No new widget type:
these ride the exact same `RowFocus.standalone` Tab-stop mechanism every other startup detail line already uses
(`ToolsScreen.java:66-72`), so `A11yGameTest`'s existing Tab-walk of this screen (`A11yGameTest.java:274-283`)
needs only one more assertion, not new plumbing.

### 2.7 Threading

All classification happens inside `StartupTimes.summarize()`, called only from `record()` (already on
`Probes.EXECUTOR`, `StartupTimes.java:57`) and lazily from `view()` under its existing `synchronized` cache
(`StartupTimes.java:86-91`) — by the time any screen or `NoticeSource` reads it, it's a plain field read.
`NoticeSource.current()` is asked "on screen init/rebuild only, never per frame" (`NoticeSource.java:7`
contract) and must "keep it cheap" — reading the cached view and building one `Notice` record satisfies that
trivially, same cost class as every existing `NoticeSource`.

### 2.8 Error handling

- `StartupTrend.assess()` must guard `baseline.isEmpty()`/`baseline.size() < MIN_RUNS` before calling
  `BenchmarkTrend.median()`/`mad()` (which throw `IllegalArgumentException` on empty input,
  `BenchmarkTrend.java:189-191`) — exactly the same care `BenchmarkTrend.assess()` itself takes at
  `BenchmarkTrend.java:242-243`.
- `NoticeCenter` already logs-and-skips any throwing `NoticeSource` (`NoticeCenter.java`'s `current(NoticeSource)`
  helper, try/catch around `source.current()`), so a bug in the new source can't take down the notice line —
  no new error handling needed there, it's inherited for free.
- `AwarenessStore.update()` never throws on a corrupt/newer file (`writable()` gates it, matching every other
  `JsonStateFile`-family store) — the acknowledgement simply doesn't persist in that case, same as today's
  `acknowledgeRegression`.

---

## 3. Compatibility

- **`startup-times.json`**: byte-for-byte unchanged shape (§1.8) — 0.4.0 keeps reading/writing it after a
  downgrade with zero new fields, a stronger guarantee than the original pitch claimed.
- **`awareness.json`**: one new optional top-level array (`acknowledgedStartupRegressions`), added via
  `withDefaults()` exactly like `acknowledgedRegressions` was for benchmarks. `formatVersion` stays 1 (no bump);
  `StateStore`'s "unknown fields at any depth survive" guarantee (`DESIGN.md:201`) is the same mechanism already
  proven for `acknowledgedRegressions` itself, which was itself new relative to whatever awareness.json looked
  like before WS-B. A 0.4.0 jar reading a 0.5-written file ignores the new array and preserves it on its own
  rewrites (per `StateStore`'s documented contract); a 0.5.0 client reading a 0.4.0-written file (missing the
  field) treats it as absent → empty set, no crash (mirrors `AcknowledgedRegressionsTest`'s
  "unknownFieldsSurviveAndBadEntriesAreIgnored" case, `core/awareness/AcknowledgedRegressionsTest.java:42-51`).
- **`NoticePriority`**: a plain enum, never serialized (§1.5) — inserting a constant is always compat-safe.
- **No rules-schema, journal, pending.json or helper involvement at all** — this feature never enters the
  Apply/Undo pipeline, so none of the `docs/RULES_SCHEMA.md` `requires`/fail-closed machinery applies.

## 4. 26.2 vs 26.3 differences

**None expected, and none exist in any file this design touches today.** Verified: `grep -rn "//? if"` across
`core/footprint/`, `client/footprint/`, `client/notice/` and `core/notice/` returns zero Stonecutter version
markers, and `grep -rl` for `StartupTimes`/`NoticePriority`/`BenchmarkTrend` inside `versions/26.2/src` and
`versions/26.3/src` (the per-version override trees) returns nothing — every file involved lives only in the
shared `src/main`/`src/client` trees. `StartupTimesStore.Run.mcVersion()` is populated from
`FabricLoader.getInstance().getRawGameVersion()` (`StartupTimes.java:74`), which already differs correctly per
build without any version-specific code in this feature.

## 5. Test plan

1. **Unit — `StartupTrendTest`** (new, mirrors `BenchmarkTrendTest`'s structure): synthetic `Run` sequences via a
   small `run(i, ms, mods, hash, mcVersion, rigtuneVersion)` helper (mirrors `StartupTimesTest.run(...)`,
   `StartupTimesTest.java:27-29`):
   - fewer than `MIN_RUNS` comparable runs → `TOO_FEW`, no cause computed;
   - a run of a different `mcVersion` never enters the baseline (comparable-by check);
   - a delta within the floor → `IN_LINE`; past it (slower) → `SLOWER`; past it (faster) → `IMPROVEMENT`;
   - `Cause` resolution: mod count differs → `MOD_COUNT`; same count, hash differs → `MOD_SET`; only
     `rigtuneVersion` differs → `RIGTUNE_VERSION`; nothing differs → `NONE`;
   - an all-identical baseline (`mad() == 0`) doesn't divide by zero (mirrors `BenchmarkTrend.noiseFloorPercent`'s
     `median > 0 ? ... : 0` guard, `BenchmarkTrend.java:212`);
   - one extreme baseline outlier doesn't blow the floor open unreasonably (median/MAD robustness).
2. **Unit — `AwarenessStore`**: extend or sibling `AcknowledgedRegressionsTest` with the same three cases
   (persists-and-bounded, unknown-fields-survive, newer-file-never-written) for
   `acknowledgedStartupRegressions`/`acknowledgeStartupRegression`.
3. **Unit — the notice source**: a small `StartupRegressionNoticeSourceTest` (no existing dedicated test for its
   siblings was found — `RegressionNoticeSource`/`BenchmarkStaleNoticeSource` appear untested at the unit level,
   covered instead by game tests — so this would be the first of its kind; alternatively fold its cases into
   game-test coverage only, matching the existing precedent, to avoid inventing a new test shape the codebase
   doesn't otherwise use). **UNVERIFIED which the spec agent should prefer** — flagging both options rather than
   asserting one.
4. **Fixture/compatibility**: no new fixture needed for `startup-times.json` (unchanged shape,
   `StartupTimesFixtureTest` stays green untouched). If `AwarenessStore`'s own fixture test exists for
   `acknowledgedRegressions`, extend it the same way; if not, `AcknowledgedRegressionsTest`'s existing
   round-trip style is sufficient evidence for a JsonObject-based store like this one.
5. **A11yGameTest**: extend the existing Tools-screen block (`A11yGameTest.java:241-283`) with one more narration
   assertion for the new regression line, seeded via a synthetic `startup-times.json` written directly to the
   game-test's config dir before opening `ToolsScreen` (the same "seed a state file by hand, then open the
   screen" pattern `StutterGameTest`/`ProfilesGameTest` already use for their own state files).
6. **A client game test for the notice** (new, or a case added to an existing suite): seed `startup-times.json`
   with a controlled sequence (≥ `MIN_RUNS` comparable runs, then one clearly-slower run with a mod-count bump),
   open `RigTuneScreen`, assert the `STARTUP_REGRESSION` notice shows with the right numbers, that "Tools…"
   opens `ToolsScreen`, and that "Got it" makes it disappear on the next screen init (and persists across a
   `NoticeCenter` rebuild, proving the `awareness.json` round-trip). Fully deterministic, no GPU/hardware
   dependency, no restart needed (unlike C09) — everything is driven by the seeded JSON file.
7. **Manual real-PC check** (per the brief's ask for "a real check on the dev PC"): on the actual dev machine,
   change the installed mod count between two real launches (add/remove a batch of mods, matching the brainstorm's
   own suggested manual check) and confirm the notice fires with the correct magnitude and cause line; separately,
   launch several times with **no** change and confirm it does *not* false-positive under the chosen floor/MIN_RUNS
   — this is the first real opportunity to sanity-check §1.3's estimated constants against actual (if
   single-machine) noise, the same kind of one-PC calibration WS-F itself relied on for its own budgets.
8. **`FootprintGameTest`/`FrameHookBudgetTest`**: expected to stay green **unmodified** — this feature adds no
   render-thread hook, no new mixin, nothing to `tools/footprint-budgets.json`. Worth an explicit assertion in the
   PR description that these suites weren't touched, as evidence the "nothing on the render thread beyond the
   existing StartupTimes hook" constraint held.
9. **`LangCheckTest`**: automatic once the new keys exist in `en_us.json` with matching English text and the code
   writes them out via `Text.of`/`Component.translatable` (no test code to write, just keys to add correctly).

## 6. Draft acceptance criteria

(Numbered `AC18.<n>` after the brainstorm's candidate id, since v0.5's SPEC hasn't assigned an item number yet —
the spec agent should renumber to whatever section v0.5's SPEC gives this feature.)

- **AC18.1** — With fewer than `StartupTrend.MIN_RUNS` comparable (same `mcVersion`) launches before the latest,
  no regression is claimed anywhere: the notice is absent and the Tools screen's detail shows no regression line.
- **AC18.2** — With at least `MIN_RUNS` comparable launches, a latest launch time whose delta below the baseline
  median exceeds the noise floor is `SLOWER`; within the floor is `IN_LINE`; above it (faster) is `IMPROVEMENT`
  (computed, never surfaced in v1 per §9); a launch of a different `mcVersion` never enters the baseline.
- **AC18.3** — A `SLOWER` assessment shows a `NoticePriority.STARTUP_REGRESSION` notice with the numeric
  magnitude ("Launch time N% higher than usual (X s vs your usual ~Y s)"), non-dismissible except by its own
  "Got it" action, and offering a "Tools…" action that opens `ToolsScreen`.
- **AC18.4** — The notice/Tools-screen line says "may be related to your mod set changing (A → B mods)" only
  when the mod count differs from the comparison run, "…mod set changing" (no counts) only when the hash differs
  with an unchanged count, "RigTune A → B" only when `rigtuneVersion` differs, and the plain "no change recorded"
  line when none of the three differ — never more than one cause line, never a diagnosis.
- **AC18.5** — Acknowledging ("Got it") a run's notice persists in `awareness.json`
  (`acknowledgedStartupRegressions`) and it does not reappear for that same run's key on a later screen init; a
  later launch's own `SLOWER` assessment (a new key) still fires independently.
- **AC18.6** — `startup-times.json`'s on-disk shape is unchanged from 0.4.0 (no new fields); a 0.4.0 jar can read
  and write it after installing then downgrading from 0.5.0, with no fixture change to
  `StartupTimesFixtureTest`.
- **AC18.7** — `awareness.json` gains only the new optional `acknowledgedStartupRegressions` array; a 0.4.0 jar
  reading a 0.5-written file preserves the field on its own rewrites (unknown-field survival); a 0.5.0 client
  reading a 0.4.0-written file (field absent) treats it as an empty set, never throwing.
- **AC18.8** — `LangCheckTest` passes: every new `rigtune.startup.*` key is present with matching English text
  and correct argument shape, and no key is unused.
- **AC18.9** — `FootprintGameTest` and `FrameHookBudgetTest` pass unmodified against `tools/footprint-budgets.json`
  — no new render-thread hook, no budget change.
- **AC18.10** — `A11yGameTest`'s existing Tab-walk of `ToolsScreen` (`A11yGameTest.java:274-283`) still reaches
  the (now longer) startup detail block via `RowFocus.standalone`, narrating the new line(s) exactly as it
  narrates today's `mod_set_changed`/`advice` lines.

## 7. File ownership and flagging hotspots

**New files:**
- `core/footprint/StartupTrend.java` — the classifier (§2.1).
- `src/test/java/.../core/footprint/StartupTrendTest.java`.
- `client/notice/StartupRegressionNoticeSource.java` — the notice source (§2.3).

**Modified files (small, additive changes only):**
- `client/footprint/StartupTimes.java` — `View` gains an `assessment` field; `summarize()` computes it (§2.2).
- `core/notice/NoticePriority.java` — one new enum constant (§2.4). **Flagging as a hotspot only in the sense
  that every notice-priority-aware call site (`NoticeBoard.select`'s `Comparator.comparingInt(ordinal)`,
  `NoticeCenter`) is ordinal-based and generic — confirmed no site hardcodes an ordinal number or assumes exactly
  6 values, so this is a safe, mechanical insert, not a real hotspot risk.**
- `core/awareness/AwarenessStore.java` — one new array field + two accessor methods (§2.5), same shape as the
  existing `acknowledgedRegressions` pair immediately above/below it in the file.
- `client/RealController.java:179-181` — one more `NoticeSource` in the existing `List.of(...)` construction.
  **This is the file the brainstorm itself calls a hotspot** (also touched by C20/C09/C02 per §4 of
  brainstorm.md), but — like those three — this change only *adds a list entry* to an existing constructor call;
  it doesn't change `RealController`'s public API or any method signature, so it's a low-conflict, easily
  re-ordered one-line addition even if several agents' diffs touch this same `List.of(...)` in the same cycle.
- `client/ui/ToolsScreen.java` — `startupDetail(...)` gains conditional lines (§2.6); no new widgets, no layout
  restructuring (reuses the existing wrap-to-room logic at `ToolsScreen.java:60-75` verbatim).
- `src/main/resources/assets/rigtune/lang/en_us.json` — 7 new keys (§1.4).
- `src/gametest/java/.../gametest/A11yGameTest.java` — one more assertion in the existing Tools-screen block.

**Not touched:** `RulesDocument`/rules-v2.json (no rules involvement), `Journal`/`HistoryModel`/`pending.json`
(no Apply path), `RigTuneScreen`'s footer (Tools already owns this feature's screen real estate per
`DESIGN.md:199`'s "Features add nothing to RigTuneScreen's footer" rule), `tools/footprint-budgets.json` (no new
hook to budget).

## 8. Effort and risk

**~2 agent-days**, matching the brainstorm's estimate once correctly scoped (per §1.1, as new-but-small rather
than a delegation to `assess()`):
- `StartupTrend` + its unit tests: ~0.5 day.
- `NoticePriority`/`StartupRegressionNoticeSource`/`AwarenessStore` additions + their tests: ~0.5 day.
- `ToolsScreen` wording/wiring + `en_us.json` + `LangCheckTest` pass + `A11yGameTest` extension: ~0.5 day.
- Game test(s), manual real-PC check, polish: ~0.5 day.

**Riskiest part:** not any implementation mechanic (every piece has a direct, working precedent to copy —
`RegressionNoticeSource`, `BenchmarkStaleNoticeSource`, `AwarenessStore.acknowledgedRegressions`,
`BenchmarkTrend`'s pure functions) but **honestly calibrating `MIN_RUNS`/the noise floor without real multi-launch
player data** (§1.3). This is the same class of risk WS-F and WS-B both explicitly hit and shipped anyway with a
documented estimate and a "retune later" note (`ws-f.md` §UNVERIFIED, `ws-b.md` §UNVERIFIED) — not a blocker, but
the one place a reviewer should expect an "ESTIMATE, not measured" caveat rather than a confident number, and the
first thing to revisit if the shipped feature turns out too chatty (false positives) or too quiet (misses real
regressions) once real usage data exists.

## 9. What to cut first

In priority order, if the 2-day budget is tight:
1. **Java/driver-version co-annotation** — already scoped out (§1.3): no field exists today, and adding one is
   real new work (a new optional `StartupTimesStore.Run` field, a synchronous `System.getProperty("java.version")`
   read at record time, a new wording key) that the brainstorm's own 2-day estimate doesn't obviously have room
   for. Cut for this cycle; note as a known limitation in whatever v0.5 CHANGELOG/README "Known limits" list
   already exists (the 0.4 pattern per `README.md:232`'s "No per-mod startup times" line).
2. **The `IMPROVEMENT`/"faster than usual" callout on the Tools screen** — the classifier computes it for free
   (§2.1), but surfacing it doubles the wording/testing surface for something that isn't the stated player
   problem (a regression alert). Cut the *display* first; keep the *computation* (it costs nothing extra and
   keeps the classifier symmetric/testable).
3. **The dedicated `StartupRegressionNoticeSourceTest` unit test** (§5 item 3) if the codebase's existing
   pattern (no unit tests for sibling notice sources, game-test coverage only) turns out to be the house style for
   a good reason — fold its cases into the game test instead rather than inventing a new test shape.
4. **Raising `MIN_RUNS` to 5 and reasoning through a launch-specific floor constant** — the cheapest (but least
   honest) fallback is reusing `BenchmarkTrend.MIN_RUNS` (3) and its default floor as-is with zero new constants;
   listed last because it's the single biggest honesty/false-positive risk of anything in this doc, so it should
   only be cut if every other line item above is already cut and the 2-day budget is still tight.

## Open questions for the coordinator / spec agent

1. §5 item 3: whether `StartupRegressionNoticeSource` gets its own unit test or follows the sibling notice
   sources' game-test-only precedent — genuinely unclear from the existing codebase, flagged rather than guessed.
2. §1.3's noise-floor constant is an estimate reusing `BenchmarkMath.NOISY_CV` (10% effective floor) rather than a
   number derived from launch-time-specific data; the manual real-PC check (§5 item 7) is the first chance to
   sanity-check it, not a substitute for real multi-launch player telemetry this project doesn't collect.
3. No `docs/research/v0.5/launcher-managed-mods.md` exists yet at the time of this research (checked: the file is
   not present in `docs/research/v0.5/`). Based on what this feature actually touches (read-only
   `FabricLoader.getInstance().getAllMods()` calls already made by `StartupTimes.record()` today, `at/ms/mods/
   modSetHash` bookkeeping, and notices) **no interaction is expected** with a "RigTune never fights the launcher"
   change, since this feature never enables/disables/downloads a mod jar — but this should be re-checked once that
   doc exists, per the brief's ask.
