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
