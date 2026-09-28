# WS-S2: C20 Stutter Doctor one-click fixes (v0.5)

Branch `feat/v05-stutter-fixes` (worktree `rigtune-stutterfix`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K
merged). Scope: docs/v0.5/SPEC.md 5 (C20), AC5.1-AC5.16 minus the rules side (AC5.1, AC5.2 and the seeds are WS-R's).
Research: docs/research/v0.5/feature-stutter-fixes.md (sf). Two phases: **Wave A** (now) the pure core in new files and
WS-S2's own skeleton files (`core/stutter/Fix*`, `SessionOutcome`); **Wave B** (after WS-S merges) the client part, the
C20 parts of the shared stutter files, and AC5.14's calibration run.

This file is first the TDD task plan (committed before any code), then the record of what landed.

## Wave A: TDD task plan (pure core, new files and the WS-K skeletons only)

Nothing here edits a file WS-S owns (`StutterAnalyzer`, `StutterFacts`, `StutterView`, `StutterService`, `StutterScreen`,
...). Where C20 needs a change there (the dominated-spike counts in `StutterFacts`, the evaluation line in
`ConditionEvaluator`), Wave A lands the pure logic in a `Fix*` class with its tests, and Wave B adds the one-line wiring.
Each task: red test first (compile failure for a new API, or the assertion for the stub's behaviour), then the code, then a
commit. Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot; no version-specific code (X9: no `//? if`).

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| S1 | The comparison: `SessionOutcome` (gameplay, hitches, lost ms, 60-s wall-time bins with their mean and sample variance; `plus` for accumulated after sessions, Chan's pooled update) and `FixComparison` (exact binomial CDF in log space, n capped at 100,000; pooled dispersion φ; effective counts h/φ rounded half up; verdict LESS / SAME / MORE with both rates, both lost-ms rates, φ, pLess, pMore) | new `core/stutter/SessionOutcome`, `FixComparison` | `SessionOutcomeTest` (bins from hitch starts, the partial last bin, one bin, `plus` equals the outcome of the concatenated bins), `FixComparisonTest` (the CDF against the exact values below; every case of the table below; the rounding rule; zero or one bin → φ = 1; C4's own φ = 14; the guard off; the n cap; no hitches on either side) | AC5.7 (unit) |
| S2 | Dominated spikes and `causeSpikesAtLeast`'s truth: `FixEvidence.dominatedSpikes(List<Attribution>)` (a cause, or `unknown` for the unexplained part, that claimed at least half of a spike's lost time) and `FixEvidence.causeSpikesAtLeast(wanted, counts, unmeasured)` → TRUE / FALSE / UNKNOWN | new `core/stutter/FixEvidence` | `FixEvidenceTest` (40 % doesn't count, 50 % and 60 % do; TRUE/FALSE; UNKNOWN for an unmeasured cause, a cause outside `Attributor.CAUSES`, a negative or fractional or non-numeric threshold; several entries AND together) | AC5.4 (the core half; Wave B wires it: `StutterFacts.causeSpikes`, `StutterAnalyzer`, `ConditionEvaluator.causeSpikes`) |
| S3 | `FixSpec` becomes the validated entry: `static List<FixSpec> of(RulesDocument)` (requires ⊇ {`stutter-fix`} and ⊆ `FixOffers.SUPPORTED_FEATURES`, an existing `stutterAdvice` id, the first entry per id, evidence present, key in `KEYS` and in `ShareKeys`, value xor step, a value that encodes in the key's table entry (kept in the table's spelling), a whole-number step ≠ 0 with \|step\| ≤ 8 on an INT key and its bound (min when negative, max when positive)); `@Nullable String target(@Nullable String current)` (value: null when already there; step: clamp(current + step, bound, table range), null unless it moves in the step's direction); the WS-K constants kept | `core/stutter/FixSpec`, new `FixOffers` (`SUPPORTED_FEATURES` only in this task) | `FixSpecTest` (the three sf §2.2 seeds parse; each refusal drops only its entry; an unknown feature skips; a key outside the allowlist refused (AC5.15); target: RD 7 → 6 at min 6, RD 6 → none, RD 4 at min 6 → none, RD 40 → none (outside the table's 2..32, the review's M3 follow-up); `always` → `ALWAYS`; value already current → none) | AC5.15 (client), AC5.3 (spec part) |
| S4 | The gate and the offers: `FixGate` (constants `MIN_HITCHES` 8, `MIN_GAMEPLAY_SECONDS` 300; `check(report, busy, storeWritable)` → the first of BENCHMARK, STORE, BUSY, LENGTH, or null) and `FixOffers.evaluate(specs, fired, report, ctx, effective, loadedMods, live, busy, storeWritable)` → per advice id an `Offer` (from the effective value, `now` for vanilla keys), a `NotYet(reason, args)` (the gate's, then SERVER with the server's view distance, then EVIDENCE), or nothing (silent: advice not fired; the key missing, not changeable or its mod not loaded (`ProfileSwitch.takesPart`); no target, i.e. the effective value, staged ops included, is already the target) | new `core/stutter/FixGate`, `FixOffers` | `FixGateTest` (each floor alone: 7 hitches, 299 s, a benchmark source, busy, a read-only store; 8 hitches and 300 s pass), `FixOffersTest` (AC5.3's list, each failing alone: evidence FALSE, evidence UNKNOWN (an unmeasured cause), advice not fired, key missing, mod not loaded, value already the target, a staged op setting the target, a server sending ≤ the target only on LAN/Realm/remote (never singleplayer); all passing → exactly one Offer with the right from/to/now; LENGTH's args) | AC5.3 (unit; the four one-line texts land with the UI in Wave B) |
| S5 | "Same conditions": `FixConditions` (MC version, mod-set hash, max heap, collector, window size and fullscreen, world kind, phase timing, GC listener, the managed settings + `iris.shaderPack`) and `differences(other, fixKey)` → reason ids in a fixed order (version, mods, memory, display, world, measurement, then each setting in `ShareKeys.V1` order, others after, the fixed key left out) with the setting's key and both values as args | new `core/stutter/FixConditions` | `FixConditionsTest` (each condition alone gives its reason id; the fixed key excluded; values compared as `SettingValues.same` ("12" = "12.0"); a key on one side only differs; the order is stable) | AC5.8 (conditions) |
| S6 | The tracker: `FixTracker.Record` and `State` (staged, measuring, compared, undone, not_applied, replaced, expired) and `advance(record, journalState, entries, @Nullable SessionEnd, now)`: the journal first (REVERTED → undone, DISCARDED/ABANDONED or no change for the key → not applied, the entry gone from a readable journal → tracking stops (expired; a compared record keeps its verdict), an unreadable journal decides nothing); 14 days after the apply → expired; sessions that started before the apply or aren't monitor sessions are ignored; staged → measuring at the first session that starts with key = target while the change is APPLIED (that session counts); key ≠ target at a measuring session's start or end → replaced; < 120 s or a differing condition → skipped with its reason (5 → expired); otherwise accumulated until clamp(before gameplay, 300 s, 1200 s), then compared with the verdict | new `core/stutter/FixTracker` | `FixTrackerTest` (every transition, incl. replaced; 5 skips / 14 days → expired; accumulation over two sessions to the target; a pre-apply session and a benchmark capture ignored; a dismissed record left alone; an unreadable journal leaves it) | AC5.8 (tracker), AC5.6 (the record's side) |
| S7 | The store's model: `FixStore.records()`, `active()`, `add(Record)` (replaces a record with the same entry id; keeps at most `MAX_RECORDS` 10 and fits under 32 KiB by dropping the oldest record that isn't active, never the active one), `update(entryId, change)` and `dismiss(entryId)`, written into the record's existing JSON object so unknown fields survive at every depth; values type-checked (a hand-edited junk value reads as absent, a record without its required fields is skipped); the shell's `read`/`update`/`writable` unchanged (V05StoreShellsTest) | `core/stutter/FixStore` | `FixStoreTest` (round trip of a record in each state; ≤ 10 records, the active one never dropped, the oldest finished first; the byte cap; unknown fields at the root, in a record, in `before`/`conditions`/`settings` survive an update; junk types; a newer file: records readable, nothing written, `writable()` false; corrupt → `.bad`) | AC5.9 |
| S8 | The main-list hold: `FixHold.apply(report, holds)` unticks a `SetSetting` on a held key whose new value moves it away from the fix's target (an enum: any other value; a number: back toward or past `from`), and appends `rigtune.stutter.fix.hold_reason`; other keys, same-direction numbers and a report without holds come back as the same instance | `core/stutter/FixHold`, en_us.json (`rigtune.stutter.fix.hold_reason`, the first key of the `rigtune.stutter.fix.*` block after `rigtune.stutter.count.spikes.one`) | `FixHoldTest` (reverse unticked with the reason and the date; same direction and other keys untouched; an enum held; no holds (the fix undone) → the same report; V05HooksTest's identity case still holds) | AC5.10 (unit) |

Wave A adds no class that loads at client init (X4): `FixHold` already ran on the rebuild's worker as WS-K's stub; the rest
is loaded only from Wave B's service. No lang key but `hold_reason` (its user is `FixHold`). No fixture set yet (nothing
is written by a running game until Wave B); `FixStoreTest` writes into a temp dir.

### The comparison, exactly (SPEC 5 + amendment SPEC-25; expected values recomputed before implementation)
- **Bins**: a session's hitches are counted per 60 s of wall time from the capture's start, by each hitch's start; the
  number of bins is max(1, ⌈session seconds / 60⌉) (a partial last bin counts). A side keeps bins, their mean and their
  sample variance (n − 1; 0 for one bin). Accumulated sessions combine with the pooled (Chan) update.
- **Dispersion**: φ = max(1, pooled variance / pooled mean) over the sides with at least 3 bins (pooled variance = Σ
  variance·(bins − 1) / Σ(bins − 1); pooled mean = Σ hitches in those bins / Σ bins); φ = 1 when neither side has 3 bins
  or the mean is 0. One φ for both sides.
- **Effective counts** e = ⌊h/φ + ½⌋ (nearest integer, halves up). n = e_before + e_after, capped at 100,000 (both scaled,
  the after count rounded, the before count the rest).
- **Test**: p0 = after gameplay / total gameplay; pLess = P(X ≤ e_after), pMore = P(X ≥ e_after), X ~ Bin(n, p0), from a
  log-space pmf recursion summed with log-sum-exp.
- **Verdict**: LESS when after rate ≤ 2/3 of before (compared as 3·after ≤ 2·before), pLess ≤ 0.05 and lost ms per minute
  not higher; MORE when after rate ≥ 3/2 of before and pMore ≤ 0.05; otherwise SAME ("no clear change"). Rates are
  hitches (and lost ms) per minute of gameplay, from the raw counts.

Expected values, from an exact reference (Python `fractions` + `math.comb`, no floating point; the script is below):

| case (hitches / gameplay s / lost ms / bins) | φ | e_before, e_after | pLess | pMore | verdict |
|---|---|---|---|---|---|
| A 4 / 352 / 235 / [1,1,0,1,0,1] vs A3 1 / 383 / 24 / [1,0,0,0,0,0,0] | 1 | 4, 1 | 0.16225028884317033 | 0.9748071893812105 | same |
| A with its real hitch times [3,0,1,0,0,0] vs A3 | 1.9359307 | 2, 1 | 0.4683861039255815 | | same |
| B 28 / 140 / 1507 / [12,12,4] then A | 1.7678571 | 16, 2 | 1.5161507257275237e-07 | | less |
| C4 14 / 131 / 533 / [0,0,14] vs 5 / 131 / 150 / [2,2,1] | 10.368421 | 1, 0 | 0.5 | 1.0 | same |
| the same, guard off (φ = 1) | 1 | 14, 5 | 0.0317840576171875 | | less |
| 8 / 300 / 400 / [2,2,1,2,1] → 0 / 300 / 0 / [0 ×5] | 1 | 8, 0 | 0.00390625 | | less |
| 10 / 300 / 500 / [2 ×5] → 20 / 300 / 1000 / [4 ×5] | 1 | 10, 20 | | 0.04936857335269451 | more |
| 20 / 600 / 1000 / [2 ×10] → 4 / 600 / 3000 (fewer hitches, more lost ms) | 1 | 20, 4 | 0.000771939754486084 | | same |
| one bin each: 10 / 50 / 300 / [10] → 0 / 50 / 0 / [0] | 1 | 10, 0 | 0.0009765625 | | less |

CDF spot values: P(X ≤ 5 \| 19, ½) = 0.0317840576171875; P(X ≤ 10 \| 30, ½) = 0.04936857335269451; P(X ≤ 1 \| 5,
383/735) = 0.16225028884317033; P(X ≤ 0 \| 8, ½) = 0.00390625; P(X ≤ 450 \| 1000, ½) = 0.0008652680424881588;
P(X ≤ 3 \| 2000, 1/1000) = 0.8572137667933241; P(X ≤ 60 \| 200, ¼) = 0.9546114398644328.

**Against the SPEC's numbers** (AC5.7): A vs A3 → no clear change, p 0.162 ✓ (with A's hitches spread over its bins; with
A's real hitch times φ is 1.94 and p 0.47, still no clear change); B then A → less ✓; 8 → 0 → less (p 0.0039) ✓; 10 → 20
→ more (p 0.049) ✓; fewer hitches but more lost ms → no clear change ✓; zero or one bin → φ = 1 ✓. **C4**: the SPEC's
"φ = 14" is C4's own dispersion (its bins alone, pinned in the test); pooled with a 5-hitch after side of the same length
(bins [2,2,1]) the rule gives φ = 10.37, and the result is the same: e = 1 vs 0, p = 0.5, no clear change; with the guard
off p = 0.032 → less ✓.

```python
from fractions import Fraction as F
from math import comb, floor
def cdf(k, n, p):
    return F(0) if k < 0 else F(1) if k >= n else sum(comb(n, i) * p**i * (1 - p)**(n - i) for i in range(k + 1))
def side(b):
    n = len(b); m = F(sum(b), n) if n else F(0)
    return n, m, (sum((F(x) - m)**2 for x in b) / (n - 1) if n > 1 else F(0))
def phi(sb, sa):
    use = [s for s in (sb, sa) if s[0] >= 3]
    if not use: return F(1)
    mean = sum(s[1] * s[0] for s in use) / sum(s[0] for s in use)
    return F(1) if mean <= 0 else max(F(1), sum(s[2] * (s[0] - 1) for s in use) / sum(s[0] - 1 for s in use) / mean)
def compare(hb, gb, lb, bb, ha, ga, la, ba, guard=True):
    ph = phi(side(bb), side(ba)) if guard else F(1)
    eb, ea = floor(F(hb) / ph + F(1, 2)), floor(F(ha) / ph + F(1, 2))
    p0 = F(ga) / (F(gb) + F(ga)); pl = cdf(ea, eb + ea, p0); pm = 1 - cdf(ea - 1, eb + ea, p0)
    rb, ra, lb_, la_ = F(hb) / gb, F(ha) / ga, F(lb) / gb, F(la) / ga
    return 'less' if ra * 3 <= rb * 2 and pl <= F(1, 20) and la_ <= lb_ else 'more' if ra * 2 >= rb * 3 and pm <= F(1, 20) else 'same'
```

### Decisions in Wave A (where the SPEC or sf leaves room)
- **Offer order of reasons** when several fail: silent preconditions first (no row at all), then BENCHMARK, STORE, BUSY,
  LENGTH (`FixGate`), SERVER, EVIDENCE (`FixOffers`). Each failing alone gives its own reason (AC5.3).
- **`causeSpikesAtLeast`'s vocabulary** is `Attributor.CAUSES`, as `stutterShareAtLeast`'s (sf §2.2): `unknown` counts the
  spikes whose unexplained part was at least half; `render` is always unmeasured (it never claims), so UNKNOWN.
- **Sessions before the apply don't count and aren't skips** (the immediate fix's restart ends the before session, whose
  start still has the old value; a staged fix's sessions before the restart), nor do benchmark captures.
- **A skipped session is any session that didn't count**, a short one included (SPEC: "5 skipped sessions ... →
  expired").
- **An entry gone from a readable journal** (folded by the 50-entry cap, or history.json deleted) ends tracking as
  "expired"; a compared record keeps its state and verdict.
- **A dismissed record** is neither active (the next fix can be offered) nor advanced.

## Wave A as landed

All eight tasks landed in order, each with its red run first (S1-S7: the new API didn't compile; S8: 3 of FixHoldTest's 5
cases failed against WS-K's identity stub) and green after, each its own commit (c0ab0e87 plan, then 89026741 S1,
4a33dc3e S2, 89c735f8 S3, e3363476 S4, 974cfc7b S5, 294561ba S6, 67888768 S7, 4fff5775 S8). Local check before the push
(build slot, `:26.2:test`): `core.stutter.*`, `core.rules.*`, `core.store.*`, `client.V05*`, LangCheckTest, WordingTest,
PseudoLocaleTest, PaletteTest: 51 suites, 426 tests, 0 failures. New tests: FixComparisonTest 15, SessionOutcomeTest 5,
FixEvidenceTest 4, FixSpecTest 11, FixGateTest 3, FixOffersTest 7, FixConditionsTest 8, FixTrackerTest 18, FixStoreTest 12,
FixHoldTest 5 (88).

Signatures (package `core/stutter`; everything pure, no Minecraft type):
- `record SessionOutcome(int sessions, double gameplaySeconds, int hitches, double lostMs, int bins, double binMean,
  double binVariance)`; `NONE`; `of(double gameplaySeconds, double sessionSeconds, double lostMs, long[] hitchStarts)`,
  `of(StutterAnalyzer.Result, long startNanos)`; `plus(SessionOutcome)`. Negative or non-finite values read as 0.
- `FixComparison`: `ALPHA` 0.05, `MIN_BINS` 3, `MAX_N` 100,000; `enum Kind { LESS, SAME, MORE }` (`id()` "less"/"same"/
  "more", `of(id)`); `record Verdict(Kind kind, double beforePerMinute, double afterPerMinute, double lostBeforePerMinute,
  double lostAfterPerMinute, double phi, double pLess, double pMore)` with `Verdict.of(kind, before, after, phi, pLess,
  pMore)` (rates from the sides); `compare(before, after)`; `dispersion(before, after)`; `binomialCdf(k, n, p)`.
- `FixEvidence`: `dominatedSpikes(List<Attributor.Attribution>)` → `Map<String, Integer>`; `causeSpikesAtLeast(Map<String,
  String> wanted, Map<String, Integer> counts, Set<String> unmeasured)` → `Truth`.
- `record FixSpec(String adviceId, Condition evidence, String key, @Nullable String value, int step, @Nullable Integer min,
  @Nullable Integer max)`: WS-K's `FEATURE`, `KEYS`, `FIELDS`, `SET_FIELDS` unchanged, + `MAX_STEP` 8; `of(RulesDocument)`,
  `now()`, `target(current)`.
- `FixGate`: `MIN_HITCHES` 8, `MIN_GAMEPLAY_SECONDS` 300; `check(StutterReport, boolean busy, boolean storeWritable)` →
  `FixOffer.@Nullable Reason`.
- `FixOffers`: `SUPPORTED_FEATURES` = {`stutter-fix`}; `evaluate(List<FixSpec>, Collection<String> fired, StutterReport,
  EvalContext, SettingsSnapshot effective, Set<String> loadedMods, @Nullable ServerLimits live, boolean busy, boolean
  storeWritable)` → `Map<String, FixOffer>` (LENGTH's args: "5:00", "8", the session's play, its hitches; SERVER's: the
  server's view distance).
- `record FixConditions(mc, modSetHash, heapMaxMb, collector, width, height, fullscreen, world, phaseTiming, gcMeasured,
  Map<String, String> settings)`, `SHADER_PACK`; `enum Reason { VERSION, MODS, MEMORY, DISPLAY, WORLD, MEASUREMENT, SETTING }`
  (`id()`, `of`); `record Difference(Reason, List<String> args)`; `differences(FixConditions other, String fixKey)`.
- `FixTracker`: `MIN_SESSION_SECONDS` 120, `MIN_AFTER_SECONDS` 300, `MAX_AFTER_SECONDS` 1200, `MAX_SKIPPED` 5, `MAX_AGE` 14
  days, `SHORT`; `enum State { STAGED, MEASURING, COMPARED, UNDONE, NOT_APPLIED, REPLACED, EXPIRED }` (`id()`, `of`,
  `tracking()`); `record Skip(String reason, List<String> args)`; `record Record(entryId, adviceId, key, from, to, Instant
  appliedAt, int rulesRevision, boolean now, State state, SessionOutcome before, FixConditions conditions, @Nullable
  SessionOutcome after, int skipped, @Nullable Skip lastSkip, @Nullable Verdict verdict, boolean dismissed)` with
  `active()`, `withState`, `dismiss()`; `record SessionEnd(Instant startedAt, String source, SessionOutcome outcome,
  FixConditions atStart, FixConditions atEnd)`; `afterTarget(before)`; `advance(Record, Journal.State, List<JournalEntry>,
  @Nullable SessionEnd, Instant now)`.
- `FixStore` (WS-K's shell kept): + `MAX_RECORDS` 10, `records()`, `active()`, `add(Record)`, `update(String entryId,
  UnaryOperator<Record>)`, `dismiss(String entryId)`. The file: `{"formatVersion": 1, "fixes": [{entryId, adviceId, key, from,
  to, appliedAt, rulesRevision, now, state, before{sessions, gameplaySeconds, hitches, lostMs, bins, binMean, binVariance},
  conditions{mc, modSetHash, heapMaxMb, collector, width, height, fullscreen, world, phaseTiming, gcMeasured, settings{}},
  after{...}?, skipped, lastSkip{reason, args}?, verdict?, phi?, pLess?, pMore?, dismissed}]}`.
- `FixHold.apply(Report, List<Hold>)` filled, + `holds(List<FixTracker.Record>, ZoneId)` (the review's M1); en_us.json
  `rigtune.stutter.fix.hold_reason` ("You set this on %s with the Stutter Doctor's fix; changing it here undoes that fix.").

CI on the pushed head 437a9655 (Wave A + a merge of origin/feat/v0.5.0 @ 1de15adf, docs only): run 36318487663, all 8
jobs green; unit tests 2001 per node (26.2 and 26.3), 0 failures, 2 ignored (as before). Footprint on that run against
ws-k.md's per-leg baseline (36310249248), `renderThreadInitCpuMs` / `clientStartedWallMs` / `workerCpuMs5s` /
`tickHookOnVsReference`: 26.2 GL 103.66 / 41.4 / 175.47 / 1.508 (baseline 82.2 / 36.4 / 135.5 / 1.481); 26.3 GL 91.83 /
29.02 / 142.81 / 1.46 (82.2 / 27.0 / 153.2 / 1.746); 26.3 Vulkan 88.59 / 10.89 / 145.6 / 1.604 (120.0 / 39.9 / 200.7 /
1.535); `v05RenderThreadResolve` null on all three. Wave A adds no code on any of these paths, so the differences are the
runner spread ws-k.md describes (26.2's 63.5-112.9 across near-identical code). No screen changed, so no screenshot to
look at in this phase.

Deviations from the plan text: `FixOffers.evaluate` landed in Wave A (pure, a new file; the PLAN's early list didn't name
it); `FixEvidence` is a new `Fix*` file holding `causeSpikesAtLeast`'s logic until Wave B can touch `StutterFacts` and
`ConditionEvaluator` (WS-S owns `StutterFacts` until it merges); `FixSpec` is now a record (WS-K's note allowed it).
Footprint: Wave A adds no init work and no tick or frame work; `FixHold` is the only one of these classes that runs in a
0.5 client today (the rebuild's worker, as WS-K's stub did) and returns at once, since `StutterFixService.holds()` is still
empty until Wave B. No new `//? if` block.

### Wave A: AC status (closes in Wave B unless verified)

| AC | status | evidence |
|---|---|---|
| AC5.3 | unit part verified (each failing condition alone, all passing → one Offer with from/to/now); the four one-line texts: Wave B (UI) | FixGateTest, FixOffersTest, FixSpecTest |
| AC5.4 | core half verified (40 % doesn't count, 60 % does; TRUE/FALSE/UNKNOWN); `StutterConditionTest`/`StutterAnalyzerTest` wiring: Wave B | FixEvidenceTest |
| AC5.6 | the record's side verified (REVERTED → undone, DISCARDED → not applied); the game test: Wave B | FixTrackerTest |
| AC5.7 | unit verified (the CDF and every case against the exact reference, above); the verdict lines and the Undo button: Wave B | FixComparisonTest, SessionOutcomeTest |
| AC5.8 | verified (conditions and the tracker's transitions, 5 skips / 14 days, accumulation) | FixConditionsTest, FixTrackerTest |
| AC5.9 | verified (X7, ≤ 32 KiB, ≤ 10 records, the active one kept, unknown fields at every depth, newer → read-only) | FixStoreTest, V05StoreShellsTest |
| AC5.10 | unit verified; on the real main list: Wave B (StutterFixGameTest) | FixHoldTest, V05HooksTest |
| AC5.15 | client side verified (FixSpec refuses a key outside the allowlist; a stored record with one is skipped); the updater's side is WS-R's | FixSpecTest, FixStoreTest |
| AC5.1, AC5.2 | WS-R | |
| AC5.5, AC5.11-AC5.14, AC5.16 | Wave B | |

### The pure-core review (coordinator's decisions, fixed before the phase-A hand-back)
A code-reviewer subagent read a7613410..4fff5775 (0 high, 4 medium, 6 low); the coordinator decided each; all are fixed in
the commit after 2aa200a6 with tests:
- **M1** `FixHold`: no hold once a comparison found more stutter (`holds(records, zone)`: staged, measuring, compared
  unless MORE, expired, dismissed ones too; never undone / not applied / replaced), and neutral wording with no claim about
  stutter (X3): "You set this on %s with the Stutter Doctor's fix; changing it here undoes that fix." (FixHoldTest
  `holdsFromTheTrackedFixes`).
- **M2** A record whose `appliedAt` is more than a day ahead of the clock (`FixStore.FUTURE`), `+1000000000-12-31` included,
  is skipped on read; `FixTracker` measures age with `Duration.between(appliedAt, now)`, so no date can overflow
  (FixStoreTest `anAppliedAtInTheFutureIsSkipped`, FixTrackerTest `extremeDatesDontThrow`).
- **M3** `FixHold` compares signs with `compareTo` (no subtraction: "1e99999999" costs nothing, FixHoldTest
  `hugeExponentsAreCheap`); a stored record whose `from` or `to` isn't a value of the key (`ShareKeys.encode`) is skipped
  (FixStoreTest `fromAndToMustBeValuesOfTheKey`). So that a real offer can always be stored, `FixSpec.target` now also
  refuses a current value outside the table's range (RD 40: no offer; the step the rule describes isn't the change then).
- **M4, a Wave B contract**: the client stamps `appliedAt` before it restarts the session for an immediate fix, and
  captures the next session's `atStart` after the settings write; a Wave B test pins that order. The core tolerance lands
  now: a session that starts at the fix's `from` within `FixTracker.SETTLE` (5 s) of `appliedAt` is ignored, never
  "replaced" (FixTrackerTest `aSessionStartingAtTheOldValueRightAfterTheApplyIsIgnored`).
- **L5** (decision): a staged fix whose first session after the helper applied it starts at another value becomes
  "replaced" at once. Waiting for an on-target session instead would hold the one-fix slot (no other offer) until the
  14-day expiry when the key really was changed (a profile switch, the Sodium menu before joining a world).
- **L6** `from`/`to` validated on read (M3); `skipped` is clamped to ≥ 0 in `FixTracker.Record`.
- **L7** A record this version can't read is never dropped by pruning (the header's promise; it may be a newer version's):
  only readable records count toward `MAX_RECORDS` (FixStoreTest `unreadableRecordsAreNeverDropped`).
- **L8** `FixSpec`'s compact constructor refuses an invalid spec, whoever builds it (FixSpecTest
  `theConstructorRefusesAnInvalidSpec`).
- **L9** The rounding is exact for φ's value (`BigDecimal` h/φ, HALF_UP): 3 / nextUp(6) → 0, where h/φ + 0.5 in doubles
  gives 1 (FixComparisonTest). φ itself stays a double (from the stored bin statistics); an absurd hand-edited variance
  can't make it infinite or NaN (clamped to `Double.MAX_VALUE`, NaN → 1; `anAbsurdVarianceStaysFinite`).
- **L10** Dates stay yyyy-MM-dd, the player's local day (TrendText's convention; `holds` takes the zone).
- Test fixtures' `appliedAt` moved into the past (2026-09-02), since a record more than a day ahead is now skipped.
- 11 new tests (99 C20 unit tests in all: FixComparisonTest 16, FixSpecTest 12, FixHoldTest 7, FixStoreTest 15,
  FixTrackerTest 21, the rest as above); locally `core.stutter.*`, `core.store.*`, `core.rules.*`, `client.V05*`,
  LangCheckTest, WordingTest, PseudoLocaleTest: 50 suites, 432 tests, 0 failures.

## Wave B: TDD task plan (client part; after WS-S merged, c59b8b93)

Resumed 2026-09-28 on feat/v05-stutter-fixes fast-forwarded to origin/feat/v0.5.0 @ c59b8b93 (WS-S, WS-R r17 with the three
`stutterFixes` seeds, WS-P, WS-H, WS-W, WS-B, WS-F merged). The coordinator's decisions for this phase
(`<scratch>/ws-s2/COORDINATOR-DECISIONS.md`) are folded in: WS-S's StutterService API and the `settingsChanged` tag, WS-B's
M4 rule (no verdict when either side is excluded), `GameTestWorlds.create/leave` only, the V05ServicesTest line rule, X12's
1280x720@3 scroll check, X8, the one shared busy check, AC5.14 as the code-deciding run. Each task: red test first, then the
code, then a commit; targeted tests in a build slot (`:26.2:test --tests ...`); game tests in CI (the user is playing: the
coordinator holds the game-test lock until their client exits).

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| B1 | Wire `causeSpikesAtLeast`: `StutterFacts.causeSpikes` (optional, the 10- and 12-argument constructors kept); `StutterAnalyzer` fills it through `FixEvidence.dominatedSpikes`; `ConditionEvaluator.causeSpikes` (the stub) → `FixEvidence.causeSpikesAtLeast(wanted, facts.causeSpikes(), facts.unmeasured())`. A spike tagged `settingsChanged` (WS-S's RW-11) never counts as dominated: it follows a settings change, not the fix's cause | `core/stutter/StutterFacts`, `StutterAnalyzer`, `FixEvidence`, `core/rules/ConditionEvaluator` (the one method) | `FixEvidenceTest` + a condition test (TRUE/FALSE; UNKNOWN unmeasured, unknown cause, fraction; a `not` over it never TRUE; UNKNOWN in the main list), `StutterAnalyzerTest` (40 % doesn't count, 60 % does; a settingsChanged spike doesn't), a scenario on the bundled r17 seeds (the Sodium seed offers at chunkBuild 40 % and 5 dominated spikes, not at 4) | AC5.4 |
| B2 | WS-B's M4 rule for C20 and the settingsChanged rule for outcomes: a session is *excluded* when it ran around a benchmark (`aroundBenchmark`) or Distant Horizons generated terrain in it (`DhGeneration.generating`); an excluded before side gets `NotYet(EXCLUDED)`, an excluded after session is skipped ("excluded"), so no verdict is ever computed across one. `SessionOutcome.of` leaves spikes tagged settingsChanged out of the hitches and the lost time, on both sides alike | `FixOffer` (+ `Reason.EXCLUDED`), `FixGate`, `FixOffers`, `FixTracker` (`SessionEnd.excluded`), `SessionOutcome` | `FixGateTest`, `FixOffersTest`, `FixTrackerTest`, `SessionOutcomeTest` cases | AC5.3, AC5.8 |
| B3 | The player-visible texts in core: `FixText` (offer line, now/restart line, profile note, the NotYet reasons with their args, the status lines, the block's heading/change/state/skip lines, Before/After rates, the three verdict lines with both rates, the Try narration) with the `rigtune.stutter.fix.*` keys (sf §2.7; the M1 hold wording already in) and their `V05LangFamilies.stutterFixes` family | new `core/stutter/FixText`, en_us.json, `V05LangFamilies` (its method) | `FixTextTest` (each reason's one line with its numbers; every verdict line shows both rates; LangCheckTest, WordingTest, PseudoLocaleTest) | AC5.3 (texts), AC5.7 (lines) |
| B4 | `StutterView` + `Map<String, FixOffer> fixes` and `FixTracker.@Nullable Record tracked` (the 7-argument constructor kept) | `core/stutter/StutterView` | `FixTextTest`/view cases (the old constructor has no fixes and no block) | |
| B5 | The service: `StutterFixService` (a cache of stutter-fixes.json read off the render thread; `preview`; `apply` (Busy.refusal first, then another fix staged or measuring, the store not writable, the effective value no longer `from`; `appliedAt` stamped before anything changes and before the session restarts, M4's contract; one `apply` entry; the record added on StutterService's io chain; a vanilla fix restarts the session, whose first 10 s are excluded like a level change); `dismiss`; `holds()` (the rebuild's worker; without a controller empty and no file read, so V05ServicesTest stays as it is); the session-end tracking and a throttled journal refresh). StutterService's C20 lines (Machine, Analysis, view, end, `restartSession`, the capture's world kind and conditions at start) and a game-test probe that injects an analysis (`StutterHooks.injectAnalysis`); `BusyTest.CALLERS` gains StutterFixService | `client/stutter/StutterFixService`, `StutterService`, `StutterMonitor.Capture`, `StutterHooks`, `BusyTest` | `StutterFixServiceTest` (StutterServiceTest's unconstructed controller: holds() without a controller; the recommendation an offer makes; the refusals in order), BusyTest | AC5.5, AC5.6 (service side), X4, X8 |
| B6 | The UI: new `ButtonRow` (its buttons are the Tab stops), StutterScreen's offer rows under each advice ("Try this fix…" whose narration names the change, opening PreviewScreen's Confirm), the NotYet line, and the "Your stutter fix" block at the top (change and date, state, Before/After `BarRow`s, the verdict, Undo this change… → `UndoScreen(this, controller, entryId)`, Dismiss) | new `client/ui/ButtonRow`, `client/ui/StutterScreen` | the game tests (B7) | AC5.7 (UI), AC5.11 |
| B7 | `StutterFixGameTest` (network off, bundled r17, injected analyses): the RD fix now (offer → Preview with one "now" row → Cancel writes nothing → Apply: one entry, RD 12 → 10, one measuring record, the session restarted after `appliedAt` with RD 10 at its start), the comparison (a mismatching session "didn't count", a matching one → "less" with both rates), Undo this change… → undone; the Sodium fix staged (one PATCH_JSON op, STAGED, record staged) → Discard pending → not applied; the negatives with their lines; the main list's hold; layout at 1280x720, 640x480, 854x480 @2 (+1280x720@3 scroll) with screenshots; `walkStutterFix` in A11yGameTest (Tab order Try → Undo → Dismiss, narration) | `gametest/StutterFixGameTest`, `A11yGameTest.walkStutterFix` | CI, 3 legs | AC5.5, AC5.6, AC5.7, AC5.10, AC5.11, AC5.12, AC5.16 |
| B8 | The `v050-written/ws-s2/` set from a test (a staged and an applied fix, their entries, the staged op) and its `expect.json` | new `V050WrittenWsS2Test`, `src/test/resources/v050-written/ws-s2/` | the set compares (regenerates with `RIGTUNE_REGENERATE_FIXTURES=1`) | AC5.13 (fixture half) |
| B9 | (cut 2nd) History's "Stutter fix: %s" label | `core/history/HistoryModel` (its own method), `client/ui/HistoryScreen` (one branch) | a HistoryModel test | |
| B10 | AC5.14's calibration run on the dev PC under the game-test lock (Sodium Chunk Updates = Immediate, the teleport driver, 6 minutes; offered, applied, restarted, compared, undone), recorded in docs/v0.5/verification/stutter-fixes/; new thresholds go back as a WS-R follow-up | docs | the real run | AC5.14 |

Cut order if needed (SPEC 5): the DH threads fix first (its seed stays in the rules; the client drops its key from
`FixSpec.KEYS`), then B9, then multi-session accumulation.

## Wave B as landed

Commits (each task red first, then green): a3f2f27e plan; 7ab76d81 B1; 029745ee B2; a9d6c73f B3; c580b30c B4; de6cb21f B5;
42165800 B6; b13a3cad B7; 1da8d2fe (the first CI run's findings: bars, status, the not-yet check); 097f9fd9 B8; b569d471
(footprint: the specs cache held a replaced RulesDocument); 043bea9b B10's driver; merges of origin/feat/v0.5.0 (WS-L1,
WS-H RW-20, WS-L2, WS-P2) in between, then d1623b1e (WS-S's RW-17/RW-18, WS-W2); f749ee53 the code review's fixes (below). Local: the full `:26.2:test` 2723 tests, 0 failures (after the review's fixes);
`:26.2`/`:26.3` client, test and game-test sources compile.

What landed, by file:
- core: `StutterFacts.causeSpikes` (13th component, the 10- and 12-argument constructors kept) filled by `StutterAnalyzer`
  through `FixEvidence.dominatedSpikes`; `ConditionEvaluator.causeSpikes` evaluates through `FixEvidence.causeSpikesAtLeast`;
  `FixOffer.Reason.EXCLUDED`, `FixOffer.Offer.profile` (5-argument constructor kept), `sameChange`, `withProfile`;
  `FixGate.check(report, excluded, busy, writable)`; `FixOffers.evaluate(..., excluded, ...)`; `FixTracker.SessionEnd.excluded`
  and the "excluded" skip; `SessionOutcome.of` leaves settingsChanged spikes out; `FixConditions.withMeasurement`,
  `settingsOf`; new `FixText` (every player-visible line, 50 `rigtune.stutter.fix.*` keys); `StutterView.fixes`/`tracked`.
- client: `StutterFixService` filled (the cache, `inputs`/`evaluate` on the analysis, `apply`, `preview`, `dismiss`, `holds`,
  `tracked`, `refresh`, `sessionEnded`); StutterService's C20 lines (`Machine.fixes`, `Analysis.fixes`, `view()`'s offers and
  block, `shownFixes()`, `startSession`'s world kind and start conditions, `restartSession`, `end()`'s tracking call in its own
  guard, `shutdown`'s bounded wait, the `analysisProbe`); `StutterMonitor.Capture.worldKind`/`fixAtStart`;
  `StutterHooks.injectAnalysis`; new `ButtonRow`; StutterScreen's offer rows, block, `fixButtons()`/`status()` for the
  tests; new dev-only `DevFixCalibration` (inert unless `-Drigtune.dev.stutterScript=fixcalibrate`).
- tests: FixTextTest, StutterViewFixesTest, StutterFixServiceTest, V050WrittenWsS2Test, cases in FixEvidenceTest,
  StutterAnalyzerTest, StutterConditionTest, FixOffersTest (the bundled r17 seeds), FixGateTest, FixTrackerTest,
  SessionOutcomeTest; BusyTest's CALLERS gains StutterFixService; StutterFixGameTest; A11yGameTest.walkStutterFix.
- fixtures: `src/test/resources/v050-written/ws-s2/` (history.json with the two `apply` entries, pending.json with the
  staged PATCH_JSON op, stutter-fixes.json with a measuring and a staged record) and its expect.json (Journal, HistoryModel,
  UndoPlanner on the applied entry, PendingActions, stutter-fixes.json unread).

### Decisions and deviations (Wave B)
1. **WS-B's M4 rule for C20** (coordinator): a session is *excluded* when it ran around a benchmark run (`aroundBenchmark`)
   or Distant Horizons generated terrain in it (`DhGeneration.generating`, the benchmark's own threshold): an excluded
   before side gets NotYet(EXCLUDED) ("…so it can't be compared. Play a session without either."), an excluded after
   session is skipped, so no verdict is computed across one. This also keeps the DH-threads fix from being offered while
   DH is generating terrain (the case its advice often sees); that fix is first in the cut order anyway.
2. **settingsChanged** (WS-S's RW-11 tag): such a spike never counts as dominated by a cause, and `SessionOutcome` leaves it
   out of the hitches and the lost time on both sides.
3. **Labels**: FixText names settings through `RigTuneController.settingLabels()` (History's labels, `HistoryModel.Labels`);
   the change line reuses `rigtune.rec.setting.title` ("%s: %s → %s").
4. WS-K's `StutterFixesModelTest` pinned the contracts stub (causeSpikesAtLeast always UNKNOWN); its assertion now pins the
   evaluation (FALSE for a measured cause that dominated nothing; UNKNOWN without facts and for an unknown cause).
5. **appliedAt is stamped to the second**, as a capture's `startedAt` is (StutterCapture), so the session restarted right
   after an immediate fix never reads as started before the apply (the M4 contract; StutterFixGameTest checks it).
6. **Tab order follows the visual order (X6)** (the coordinator's decision L10; the coordinator amends AC5.11's text): the
   block sits at the top of the list (sf §2.6), so with both on screen the stops are Undo this change…, Dismiss, then Try
   this fix…; walkStutterFix pins that order and the Try narration.
7. **A status too wide for the line under the title** (a fix's) opens the list as a wrapped row instead (seen truncated in
   run 36365859147's 854x480 screenshot); Copy summary's short status stays where it was.
8. **The Before/After bars' value is the hitch rate**; the lost time is its own line under them ("Time lost to stutter: %s
   ms a minute before, %s ms after."), since both numbers didn't fit the bar's value column at 640x480 (run 36365859147).
9. **Apply before returning to StutterScreen** (PreviewScreen's Confirm runnable), so its init already shows the new block.
10. **Offers from a just-ended session**: the saved analysis made at a session's end has its facts and gets offers too (sf
    §2.4.2 "live or just ended"); the before side's world kind comes from the capture's start, so leaving the world first
    doesn't lose it.
11. **The profile note** names the active *saved* profile when its settings contain the key and its switch is still in
    effect (`ActiveProfile.inEffect`); an active template isn't named (its values come from the rules).
12. **At quit** the last session's tracking is queued on the io chain like any other and `shutdown` waits for it (2 s,
    bounded), so stutter-fixes.json is never read or written on the render thread (X8).
13. **Footprint**: the fix service's specs cache holds the rules document weakly (run 36368258712 caught a replaced
    RulesDocument kept alive: `rigtuneClassBytesIdle` 127,344 > 109,296, all of it a second copy of the rules). The service
    is resolved from StutterService on the render thread at the first session start or view, never inside the startup
    window (`v05RenderThreadResolve` null on all legs). A session start reads the settings (SettingsBridge, mtime-cached)
    once for the start conditions.
14. **V05ServicesTest** unchanged: `holds()` without a controller answers empty and reads no file.
15. **B9 cut** (the cut order's 2nd): History's "Stutter fix: %s" label needs a line in RealController's history(), which
    isn't a contracts extension point; History still shows the entry's row ("Render Distance: 12 → 10") with the reason
    "Stutter Doctor: <advice title>".
16. The DH threads fix stays (not cut): its seed is in r17 and the client handles it like the others.

### The Wave B code review (coordinator's decisions, 2026-09-28 13:20)
The coordinator's reviewer read c59b8b93..HEAD (0 high, 5 medium, 9 low); all fixed in f749ee53 except the optional L14,
each medium with a test seen failing first (the failure named):
- **M1** Apply never crashes the game: `StutterFixService.gone(offer, shown, effective)` is the last check, and a settings
  read that throws (`SettingsBridge.read`, `effective()`, `ConfigTargets.all`) refuses the fix with "This fix can't be
  applied now…" instead; `adding` is cleared in a `finally` unless its write was queued; a session restart that throws
  only logs (the setting and the record stand; the next session is the first measured). StutterFixServiceTest
  `m1ASettingThatCantBeReadRefusesTheFix` (red: the IllegalStateException escaped). A malformed sodium-options.json reads
  as no value (`readSodium` never threw), so it refuses through the same line.
- **M2** `holds()` reads history.json once per call (StutterFixServiceTest `m2HoldsReadHistoryOnce`, red: 6 reads for 3
  fixes) through the `history` seam.
- **M3** State and entries come from ONE read: `Journal.snapshot()` (an additive edit to Journal, WS-F's pattern:
  `Snapshot(state, entries)`); `advanceAll` changes nothing when the read isn't OK or MISSING, so a fix never expires
  because history.json failed a moment (StutterFixServiceTest `m3AFailedHistoryReadNeverExpiresAFix`, red: EXPIRED;
  JournalTest `aSnapshotIsOneRead`).
- **M4** `end()`'s queued save keeps `session.fixAtStart` in a local, never the capture (StutterServiceTest
  `m4AQueuedSaveDoesNotHoldTheEndedCapture`: a weak reference to the ended capture clears while the save still waits on
  a hand-drained io queue; red before).
- **M5** StutterFixGameTest waits (bounded, 400 ticks) for the report the apply's rebuild makes, not 40 ticks.
- **L6** `cached()` and `holds()` read `adding` before `records` (the io chain reloads before it clears `adding`).
- **L7** `restartSession` keeps a paused session paused (`pause(true)`: the flag and PAUSE_BEGIN) (StutterServiceTest
  `l7ARestartedSessionStaysPaused`).
- **L8** The benchmark's capture, analysed on the render thread, gets no fix inputs (`machine(…, false)`): no pending.json
  read there.
- **L9** A new neutral line while the analysis or the records aren't there yet: "The Stutter Doctor isn't ready for this
  yet. Try again in a moment." (`rigtune.stutter.fix.status.later`); a refusal shows in the label color, the green only
  when the block now shows a new fix (`StutterScreen.statusColor()`).
- **L10** Decision: Tab order follows the visual order (X6): Undo this change…, Dismiss, Try this fix… (decision 6
  above); the coordinator amends AC5.11's text.
- **L11** StutterFixGameTest's `finally` dismisses the fixes it made, puts stutter-fixes.json back as it was, clears
  stutter.json (Clear) and writes back the sessions from before the test.
- **L12** No Undo for a fix that expired with its journal entry gone: `FixTracker.advance` marks it (`lastSkip` =
  `FixTracker.GONE`, never shown: only a tracking record shows its last skip) and `Record.undoable()` says so (FixTrackerTest
  `aFixWhoseEntryIsGoneHasNothingToUndo`, FixStoreTest `anEntryGoneExpiryRoundTrips`).
- **L13** DevFixCalibration's after step plays 60 s longer than the before step (450 s); while the record still measures
  it quits without the Undo and the next launch plays on (found in the first AC5.14 attempt, below).
- **L14** (optional) not done: the cached specs are replaced at the next analysis' `inputs()`; until then they hold three
  entries' conditions, and FootprintGameTest's idle budget passes with them.
- **RW-17 for C20** (the coordinator): a session the game throttled (idle) for longer than it was played is no comparison
  side: as the before side `FixGate` answers `FixOffer.Reason.IDLE` ("This session was idle (throttled) longer than it
  was played, so it can't be compared. Play a session without long breaks.", after EXCLUDED, before STORE); as an after
  session it is skipped with `FixTracker.IDLE` ("…it was idle (throttled) longer than it was played."). `Fixes.idle` and
  `SessionEnd.idle` carry it (the 6-argument SessionEnd constructor kept); a session without `idleSeconds` (0.4's, older)
  counts as no idle. FixGateTest `aMostlyIdleSessionIsNoBeforeSide` and `theRealAfkCaptureIsNoBeforeSide` (the shape of
  the real 2026-09-28 capture: 17.4 h AFK in 19 h, 96 hitches; 62,640 s idle against 4,569 s played is refused, while
  the same capture saved before RW-17 as 67,209 s of gameplay would pass), FixTrackerTest `aMostlyIdleSessionIsSkipped`
  (the same shape as an after session: skipped, where counted it would have diluted the rate to 0.09 hitches a minute
  and read as "less"), FixTextTest.
- Local after the fixes: the full `:26.2:test` 323 suites, 2723 tests, 0 failures; `:26.3` client, test and game-test
  sources compile.

### AC5.14: the calibration run (real, 2026-09-28)
Full record: docs/v0.5/verification/stutter-fixes/README.md (numbers, φ, p, the logs). On the dev PC (26.2, RX 7800 XT,
Sodium 0.9.2 with Chunk Updates = Immediate, the teleport driver `DevFixCalibration`), the Sodium fix was offered from
real stutter (chunk building 92 % claimed, 235 spikes it dominated, 216 hitches in 380 s), applied (staged), applied by the
helper at the restart, compared over 442 s of play (hitches 34.08 → 30.55 a minute, lost 1,057 → 941 ms a minute, φ 1.930,
pLess 0.230, pMore 0.808: **no clear change**) and undone. The r17 thresholds stand (the offer fired with a wide margin
both times; no WS-R follow-up). With Deferred no spike waits on chunk building any more (0.8 %, 2 spikes), but the
teleport stutter stays, now unexplained: why the comparison uses outcomes, never cause shares. A first attempt was lost to
a stale jar in the calibration instance and a window that lost focus (127 s of gameplay): fixed in the driver (L13).

### Docs (for the docs workstream)
- **README, features (Stutter Doctor)**: "One-click fixes: when the Stutter Doctor's advice points clearly at Sodium's Chunk
  Updates, render distance or Distant Horizons' thread count, it can offer *Try this fix…*: a preview, then an ordinary
  Apply you can undo in History. RigTune then compares your next play sessions under the same conditions (hitches and
  time lost a minute, never cause shares) and says *less stutter*, *no clear change* or *more stutter*, always with both
  numbers: a measured comparison, not proof. Everything stays on this PC; the fix is tracked in
  `config/rigtune/stutter-fixes.json`."
- **README, known limits**: the thresholds that decide when a fix is offered were checked on one PC (see
  docs/v0.5/verification/stutter-fixes/: there the Sodium fix moved the stutter's cause but gave no clear change under
  teleport play); a comparison needs the Stutter Doctor's monitor on and about 5-20 minutes of play under the same
  conditions (same mods, window, world kind and settings); a session around a benchmark run, while Distant Horizons
  generates terrain, or idle (throttled) longer than it was played doesn't count; History labels the entry as a plain
  Apply ("Stutter Doctor: …").
- **CHANGELOG [0.5.0], Added**: "Stutter Doctor: one-click fixes for three settings (Sodium's Chunk Updates, render distance,
  Distant Horizons' threads), each behind a three-part evidence check, previewed, undoable, and followed by an honest
  before/after comparison of your next play." **Compatibility**: "A fix is an ordinary Apply entry; after a downgrade to
  0.4.0 it shows as one, a staged fix is applied by 0.4.0's helper, and Undo this works. stutter-fixes.json is new and
  ignored by older versions."
- **DESIGN.md, Stutter Doctor (0.5)**: the three-layer gate (the advice fired; `FixGate`'s floor: a monitor session, not
  excluded, not mostly idle, ≥ 8 hitches, ≥ 300 s, one fix at a time, the store writable; the rules' fail-closed
  `evidence` with `causeSpikesAtLeast`); `FixSpec`'s allowlist; the fix as `RealController.apply` of one SetSetting
  (journal, Undo this, a staged PATCH op for Sodium/DH); `FixTracker`'s states (staged → measuring → compared; undone, not
  applied, replaced, expired) and "same conditions" (`FixConditions`); `FixComparison`'s quasi-Poisson test (φ over 60-s
  bins, h/φ rounded half up, one-sided exact binomial, LESS ≤ 2/3 and p ≤ 0.05 with lost time not higher, MORE ≥ 3/2 and
  p ≤ 0.05); WS-B's rule that an excluded session (around a benchmark, DH generating) never counts, and RW-17's for a
  mostly idle one; settingsChanged spikes left out; `FixHold` on the main list (none after "more"); threading
  (stutter-fixes.json only off the render thread, writes on StutterService's io chain; history.json read once per use
  with `Journal.snapshot`, a failed read changes no record); appliedAt stamped before the session restart.

### Residuals
- B9 cut: History shows a fix as a plain Apply entry (reason "Stutter Doctor: <advice title>").
- The render-distance and DH fixes have no real run (CI's StutterFixGameTest covers the render-distance path on 3 legs;
  the DH path is unit-tested only).
- L14 not done (see the review above).
- Only a fix that expires *because* its journal entry is gone loses its Undo button; a fix that expired by age or skips,
  or a compared one, whose entry History's 50-entry cap drops later keeps the button (UndoScreen then finds nothing).

### UNVERIFIED
- The thresholds on other PCs (one real run, one driver; AC5.14's README).
- Real 26.3 / Vulkan play (CI covers the flow with injected analyses on 26.2 GL, 26.3 GL and 26.3 Vulkan).

### AC status (Wave B)
| AC | status | evidence |
|---|---|---|
| AC5.3 | verified: each condition alone (unit), the one-line blocks on the real screen, the idle block (RW-17) | FixGateTest, FixOffersTest, FixTextTest, StutterFixGameTest `negatives`/`notYet` |
| AC5.4 | verified | StutterConditionTest, StutterAnalyzerTest, FixEvidenceTest |
| AC5.5 | verified (3 legs): the preview lists one change, Cancel writes nothing, Apply makes one entry and one record; Sodium: one staged PATCH_JSON op | StutterFixGameTest |
| AC5.6 | verified: the block's Undo → undone, Discard pending → not applied | StutterFixGameTest, FixTrackerTest |
| AC5.7 | verified: unit; the verdict line with both rates and the Undo button on screen | FixComparisonTest, FixTextTest, StutterFixGameTest |
| AC5.8 | verified (the idle skip added) | FixConditionsTest, FixTrackerTest |
| AC5.9 | verified | FixStoreTest, V05StoreShellsTest |
| AC5.10 | verified (unit); on the real main list when the stub tier proposes a longer render distance (the count is logged) | FixHoldTest, V05HooksTest, StutterFixGameTest `holdOnTheMainList` |
| AC5.11 | verified: 3 sizes + 1280x720@3 scrolled, the Tab order Undo, Dismiss, Try (L10: the visual order), the Try narration | StutterFixGameTest `checkLayout`, A11yGameTest `walkStutterFix` |
| AC5.12 | verified: network off, the bundled r17, the analyses injected through `StutterHooks.injectAnalysis` | StutterFixGameTest |
| AC5.13 | the `v050-written/ws-s2/` set and its expect.json (compat040 in CI); the E2E downgrade run is WS-E's | V050WrittenWsS2Test |
| AC5.14 | verified (real run): offered, applied, restarted, compared (no clear change), undone; thresholds stand | docs/v0.5/verification/stutter-fixes/ |
| AC5.15 | verified (client side); the updater's side is WS-R's | FixSpecTest, FixStoreTest |
| AC5.16 | FootprintGameTest and FrameHookBudgetTest pass with no budget change (CI, all legs) | CI |
| AC5.1, AC5.2 | WS-R | |

## Review-11 fixes (branch fix/v05-r11-ws-s2 from 111cb2be)
Findings in the coordinator's reviews/r11-STUTTER.md and r11-PERF.md; every HIGH/MEDIUM with a test seen failing on the
old code for the stated reason first.
- **STUTTER-1 / SEC-1 (HIGH), FIXED 811d1da1**: `FixStore.decode` skips an `appliedAt` before `FixStore.PAST`
  (2000-01-01, next to the FUTURE check); `FixText.day` reads "?" for an instant outside the zone's range (as
  `TrendText.date`), and `FixHold` dates its holds through it. Red: FixStoreTest `anAppliedAtInTheFarPastIsSkipped`,
  FixTextTest `aDateOutsideTheZonesRangeReadsAsUnknown`, FixHoldTest `aDateOutsideTheZonesRangeStillHolds`
  (DateTimeException: Invalid value for EpochDay).
- **STUTTER-2 (M), FIXED 22de3d01**: once both the frame ring and the candidate ring wrapped, only the frame ring's window
  has every spike, so `StutterAnalyzer.Result.covered` (a minimal edit to WS-S's analyzer, the 4-argument constructor
  kept) gives that window (its first frame, gameplay and wall length) and `SessionOutcome.of` counts spikes, gameplay and
  bins over it alone. FixGate's floor and FixTracker then see the same numbers. Red: SessionOutcomeTest
  `theRateCountsOnlyTheCoveredWindow` (the same play: 1.89 hitches a minute in wrapped rings, 17.18 in whole ones; with
  the fix the rates agree and the comparison is SAME). AC5.14's runs never wrapped the candidate ring (~250 candidates).
- **STUTTER-3 (M), FIXED 15ad31d3**: the before side is one setup. `FixOffer.Reason.CHANGED` ("Settings or the window
  changed during this session (or RigTune couldn't tell), so it can't be compared. Play a session without changes.")
  after IDLE: RW-11's start and end settings differ, or `StutterFixService.changedDuring(Capture.fixAtStart, now)` (a key
  read on one side only isn't a change; unknown start conditions fail closed). `Inputs.atStart` carries the capture's
  start conditions. StutterFixGameTest starts a fresh session after each resize before it expects the offer. Red:
  FixOffersTest `aSessionThatChangedItsSetupIsNoBeforeSide` (render distance 20 -> 12 during the session still gave both
  offers).
- **STUTTER-4 (M), FIXED 0582e35d**: WS-B's rule over the whole capture: `StutterRings` keeps the busiest 60-s block of
  Distant Horizons' world generation outside pauses as samples arrive (`Totals.dhWorldGenPeakCores`, pause state from its
  own PAUSE events; minimal edits to WS-S's rings and analyzer, `Result.dhWorldGenPeakCores`; the benchmark's
  `dhWorldGenCores` unchanged); `StutterFixService.excluded` fails closed when DH is loaded and nothing was sampled. The
  excluded lines now say "(or RigTune couldn't tell)". Red: StutterFixServiceTest
  `distantHorizonsGeneratingAnywhereInTheSessionExcludesIt` (40 minutes of generation early in an hour weren't excluded).
- **STUTTER-5 (M), FIXED 7fd8fc21**: "no clear change" says why (X3): `Verdict.fewerHitchesMoreLost` -> "No clear
  improvement: %s hitches a minute (was %s), but more time lost to stutter (%s ms a minute, was %s)…"; `clearButSmall`
  (a p-value <= 0.05 with the rate between 2/3 and 3/2) -> "A small change: … Measurable, but too small to call better or
  worse…"; the "within how much play sessions vary" line only when neither p-value is significant. Red: FixTextTest
  `noClearChangeSaysWhy`.
- **STUTTER-6 (L), FIXED 15ad31d3**: the floor (and LENGTH's numbers) goes by the outcome the comparison would take
  (settingsChanged spikes left out, the covered window): FixOffersTest `theFloorHoldsForTheOutcome`.
- **STUTTER-7 (L), FIXED 7c1bf887**: a session whose start or end snapshot lacks the fixed key is skipped
  (`FixTracker.UNREAD`, "RigTune couldn't read %s at its start or end"), never "replaced" (FixTrackerTest
  `aKeyMissingFromASnapshotIsUnknownNotReplaced`, red: REPLACED).
- **STUTTER-8 (L), FIXED 7c1bf887**: a hold stands only while the key's current value is the fix's target
  (`FixHold.Hold.staged` keeps it for a fix waiting for the restart) (FixHoldTest `noHoldOnceTheValueChangedByHand`, red:
  held).
- **STUTTER-9 (L), NOT FIXED**: the AFK onset's up to 0.5 s of throttled frames before SettingsWatch's check notices
  (one hitch per AFK break, on both sides alike) needs an IDLE event dated at the previous check and the analyzer to drop
  the spikes in between: WS-S's SettingsWatch and analyzer, not a cheap change. Residual.
- **STUTTER-10 (L), NOT FIXED**: counting "a setting changed and back" in an after session needs a whole-capture count of
  SETTINGS_CHANGED events (the event ring wraps like the sample ring) that leaves out the immediate fix's own change (its
  event lands in the restarted session, dated before its start). Not cheap or safe for a low. Residual; the start/end
  comparison and the settingsChanged tag stay.
- **STUTTER-11 (L), residual**: regression to the mean (the before side is chosen for being stuttery). The smallest honest
  mitigation, a second qualifying before session pooled with the triggering one, needs each session's outcome and
  conditions kept until a fix is offered: not small. The X3 wording check: the "less" line says "Play sessions differ, so
  this is a measured comparison, not proof."; "more" says "It may be unrelated, since sessions vary"; "no clear change"
  now says why. Recorded for the docs' known limits.
- **PERF-2 (M), FIXED bd7f1087**: without a tracked fix, `holds()` (every rebuild) and `advanceAll` (every 5 s while the
  Stutter Doctor is open) read no history.json; with one, once per call (JournalCache isn't on feat/v0.5.0 yet). Red:
  StutterFixServiceTest `withoutAFixHistoryIsNeverRead` (2 parses with no fix). The start hook's parses (PERF-2 c) are
  other owners' files.
- **PERF-4 (L), FIXED 9b7a909c**: SettingsWatch forgets the failed session once it ended (a minimal edit to WS-S's file)
  (SettingsWatchTest `aFailedSessionIsReleasedWhenItEnds`, red: the capture stayed reachable).
- **COMPAT-2 (M), FIXED on fix/v05-r11-ws-s2b**: C20 compares the graphics backend and GPU as the benchmark does (ws-b's
  BenchmarkTrend BACKEND/GPU rule): `FixConditions.backend`/`gpu` (optional, null when unknown and in older records; the
  11-argument constructor kept) are taken at the capture's start and at each analysis from `BenchmarkConditions.Graphics`
  (the same hardware probe), kept in stutter-fixes.json's conditions (not written when null; stutter.json is unchanged),
  and `FixConditions.Reason.GRAPHICS` ("it ran on another graphics API (OpenGL or Vulkan) or GPU") differs when both
  backends are known and differ, or both GPUs are known and differ (case and outer spaces aside) on the same or an
  unknown backend; unknown claims nothing. An after session on another backend is skipped; a before session whose
  backend moved (it can't within a game run) would read CHANGED. Red: FixConditionsTest
  `anotherGraphicsBackendOrGpuDiffers` (OpenGL vs Vulkan: no difference) and FixTrackerTest `anotherGraphicsBackendIsSkipped`
  (the Vulkan session was counted and compared). The ws-s2 set now carries the fields (regenerated); compat040 against the
  released 0.4.0: ws-s's and ws-s2's checks pass (stutter-fixes.json byte-identical, unread).
- New keys: `rigtune.stutter.fix.not_yet.changed`, `.skip.unread`, `.verdict.same_more_lost`, `.verdict.same_small`; the
  two excluded lines reworded.

## Review-12 fixes (branch fix/v05-r12-ws-s2 from ec370f3e)
Findings in reviews/r12-R12STUTTER.md (C20 can still claim "less stutter" falsely). The acceptance test is the reviewer's
own FixComparison model in Python (docs/v0.5/verification/stutter-fixes/sims/: sim.py, sim2.py, sim3.py, and sim4.py /
sim4b.py, the model of these fixes on the same grids): false LESS under no real change at most about 5 % in each scenario.

| scenario (the reviewer's grid) | false LESS before | after (sim4 / sim4b) |
|---|---|---|
| H0, sim.py (rates 2-30/min, phi 1-4, sides 5-20 min) | up to 7.2 % | up to 5.3 % (mean 2.4 %) |
| a join burst on the before side only, sim2.py (3-min bursts) | 10.2-51.5 % | up to 4.9 % (a restarted or wrapped after side); 4.8 % with its own join |
| the triggering session chosen for being bad, sim3.py (rate >= 1.0 / 1.3 / 1.6 x usual) | 1.9-10.2 / 12.6-28.1 / 19.8-65.8 % | up to 5.3 % in every cell (sim4b, 4,000 pairs a cell; the H0 level) |
| a burst that lasts into the 4th minute (my stress case, beyond the settle cut) | - | up to 7.7 % (a residual below) |

- **R12STUTTER-1 (M), FIXED 4da1146b** (data f9180629): a session capture's first `SessionOutcome.SETTLE_NANOS` (180 s:
  its world join's chunk streaming, or the reload after an immediate fix's restart) never count, on either side: the
  monitor marks the frame ring there (`FrameRing.markGameplayAt`, one boolean check per frame; the gameplay before the mark
  in the snapshot), and `StutterAnalyzer.Compared` (was Covered) starts at the mark. Red: SessionOutcomeTest
  `aJoinBurstNeverCounts` (a joined session against a restarted one of the same steady play read LESS).
- **R12STUTTER-2 (M), FIXED 4da1146b**: each candidate record carries the capture's running gameplay (`FrameRing.C_GAMEPLAY`,
  STRIDE 11, +32 KiB per session capture, monitorOnRetainedBytes 2,578,576 of 2,621,440); once both rings wrapped, the
  compared play reaches back to the oldest candidate still held (total gameplay minus its stamp) before falling back to
  the frame ring's window. Candidates are gameplay frames only, so menus, AFK and a high frame rate no longer shrink it
  to minutes. The lines say what counts: "A one-click fix needs more play to compare: at least %s and %s hitches after a
  session's first 3 minutes (this one: %s, %s)."; "it had less than 2 minutes of play after its first 3 minutes". Red:
  SessionOutcomeTest `theComparedPlayReachesBackToTheOldestCandidate` (16.8 s compared of ~330 s the candidates held).
- **R12STUTTER-6 (treated as M), FIXED a1f9c064**: the before side is never the session that led to the offer (it was
  chosen for being bad). Try this fix… changes nothing: the fix is chosen (`FixTracker.State.BASELINE`, the running
  session restarts), the next monitor session with the key still at its old value, not excluded, idle or changed, and with
  5:00 of compared play is measured as it is (`READY`, its outcome and start conditions kept), and the player applies the
  change from the block (Apply the fix…, through the preview), with that session as the before side. Trade-off: one more
  session (8 minutes with the settle span) before the change; the offer says why: "First RigTune measures one more
  session as it is (a bad one alone would make any change look good); then you apply the change here." BASELINE/READY
  hold nothing, have nothing to undo, need no journal, block other offers (one fix at a time) and expire after 14 days or 5
  skipped sessions. `RigTuneController.startStutterFix`; StutterScreen's block (Apply the fix… / Dismiss; the advice row's
  own busy line hidden); DevFixCalibration's steps (trigger + baseline, apply, after); StutterFixGameTest's flows (the
  baseline and ready blocks, their screenshots). The red is the reviewer's sim3.py (up to 65.8 %); FixTrackerTest
  `aChosenFixMeasuresOneSessionAsItIsFirst`, `aBaselineSessionMustBeANewFullOneAtTheOldValue`, FixStoreTest
  `baselineAndReadyRoundTrip`, FixTextTest `theBaselineLines`.
- **R12STUTTER-3 (L), FIXED 97f6ed9a** (with ws-s2c's JournalCache, on feat since f173846d): history.json is read only while
  some fix can still change with it (`FixTracker.Record.followsJournal`); StutterFixServiceTest `finishedFixesReadNoHistory`
  (red: 2 parses for a dismissed compared and an expired record).
- **R12STUTTER-4 (L), FIXED aa02e35e**: SettingsWatch keeps the failed session weakly (SettingsWatchTest
  `aFailedSessionReplacedWithoutATickIsReleased`, red).
- **R12STUTTER-5 (L), FIXED de4d34d3**: StutterRings counts the non-reload SETTINGS_CHANGED events from the capture's start
  over the whole capture (an immediate fix's own change is dated before its restarted session); the before side reads
  CHANGED, a baseline or after session is skipped ("a setting changed while it ran"; for an after session a start/end
  difference still names the setting). StutterAnalyzerTest `settingChangesDuringTheCaptureAreCounted`, FixTrackerTest
  `aSessionWithASettingChangeDoesNotCount`.
- **R12STUTTER-7 (L), FIXED 7dbcfaaa**: a key missing at both ends reads as the session's own reason (MODS), not UNREAD
  (FixTrackerTest `aRemovedModIsTheModsNotAnUnreadKey`, red).
- **R12STUTTER-8 (L)**: ws-b's GPU normaliser; FixConditions calls it when ws-b's helper lands (not on feat yet).
- **AC5.14** was run before these fixes (the trigger session as the before side, no settle span); its offer and thresholds
  stand (the evidence side is unchanged), and DevFixCalibration now plays 540 s per step with the baseline step; the run
  isn't repeated (UNVERIFIED under the new flow on real hardware; the game test covers it).
- Residuals: a join burst longer than 3 minutes still leaks into a before side with a join against an after side without
  one (7.7 % in the stress case); regression to the mean across sessions of *different* play (the player explores before
  and builds after) isn't modelled (the settle and baseline rules make each side one ordinary session, not one activity).
