# C20 — Stutter Doctor one-click fixes: design research

Summary (10 lines):
1. The critic (§9.1) is right: no confidence gating exists (`StutterAdvisor.java:29-42`, `RulesDocument.java:213-222`); it is new, costed work.
2. Gate = a client floor rules can't lower (monitor session, ≥ 8 hitches, ≥ 300 s) + a per-fix fail-closed `evidence` condition.
3. Rules: a new lenient rules-v2 section `stutterFixes` (`requires: ["stutter-fix"]`, one allowlisted `set`) + one evidence-only key `causeSpikesAtLeast`.
4. Verified with 0.4.0's and 0.3.0's own parser classes (git show v0.4.0/v0.3.0, Gson 2.14.0): both ignore the new section in any shape.
5. Fixes: Sodium Chunk Updates → Deferred (staged), render distance −2 (now); DH threads −2 strictly gated, cut first; heap and System.gc() stay advice.
6. A fix is an ordinary `RealController.apply(List.of(rec), entryId)`: journal, Undo this, no new pending.json op; confirmed in PreviewScreen's `Confirm`.
7. Tracking in a new `config/rigtune/stutter-fixes.json` (StateStore); the comparison block lives in StutterScreen; a `FixHold` stops main-list ping-pong.
8. Comparison: hitches and lost ms per minute only (never cause shares), quasi-Poisson binomial test → "less" / "no clear change" / "more", numbers always shown.
9. Effort ~5.75 agent-days (MVP after cuts ~3.5; the brainstorm's 2.5 assumed the gate existed). Settings only, so the launcher-managed P0 doesn't change it.
10. Riskiest: inducing a fixable advice with strong evidence on the one real PC (AC5.8 never saw a chunk cause claim time) and calibrating thresholds.

---

## 1. The brainstorm's open questions and the critic's corrections

### 1.1 Critic §9.1 confirmed: there is no confidence gating

- `StutterAdvisor.Fired(String id, String kind, Impact impact, String title, String text)`
  (`core/stutter/StutterAdvisor.java:29`). `evaluate` fires an entry when `requires` is supported and `when` is TRUE
  (`:62-73`), and nothing at all without enough data (`:52-54`: at least 3 spikes and 2 minutes, `StutterAnalyzer.java:24-25,118`).
  That is the only gate.
- `RulesDocument.AdviceRule` has `requires, id, when, impact, title, text, kind` only (`core/rules/RulesDocument.java:213-222`).
- `Attributor.Confidence` HIGH/MEDIUM/LOW (`core/stutter/Attributor.java:53-68`) is per spike and per cause. It is set from the
  claimed share (`share()`, `:269-274`: HIGH at half the lost time, MEDIUM at a fifth) for GC; chunk loading is HIGH/MEDIUM by
  the same half; chunk building is always MEDIUM (`:200`); ticks MEDIUM. It lives only in the `notes` strings of the 10 worst
  spikes of a saved report. It is not an advice-level signal.
- The five live `stutterAdvice` entries (`rules/rules-v2.json:2498-2596`, r16) are `warning` (`ram-stutter-gc-heap`) or `info`
  (the other four). A `kind == "critical"` gate would never fire today, and promoting an entry to `critical` just to get a
  button would change what 0.4.0 shows (`StutterAdvisor.java:67-71` maps critical to Impact.HIGH). Rejected.

So the gate is new state and new schema, costed below (§8), not "no new subsystem".

### 1.2 A second correction: "Apply buttons on stutter advice" was a v0.4 deferral, not a design non-goal

`docs/research/v0.5/v04-leftovers.md` L20 (lines ~415-418) cites "docs/DESIGN.md:25" for it. DESIGN.md's Non-goals (line 25)
don't mention it. The statement is `docs/v0.4/SPEC.md:288` ("Deferred / not in v0.4": "\"Apply\" buttons on stutter advice").
C20 lifts that deferral; the v0.5 SPEC should say so, and DESIGN.md's "Stutter Doctor" section should gain the fix paragraph.
`StutterAdvisor`'s own comment "Advice only informs; nothing is applied from here" (`StutterAdvisor.java:22`) stays true: fixes
are evaluated by a separate class.

### 1.3 The evidence gate, concretely

Three layers, all of which must pass for the "Try this fix…" button to appear:

1. **The advice fired** (unchanged 0.4 path, including its enough-data rule).
2. **The client floor** (`core/stutter/FixGate`, constants in code; rules can only add to them):
   - the report is a monitor session (`StutterReport.MONITOR`), never a benchmark capture (§1.8);
   - at least `MIN_HITCHES = 8` hitches (spikes less than 100 ms apart count once, `SpikeDetector.hitches`, the unit the
     header already shows). Why 8: below it even a perfect fix (8 → 0 at equal play time) can't be told from chance in the
     comparison: one-sided binomial p = 0.5^8 = 0.0039; at 5 it is 0.031, at 4 it is 0.0625 (computed, §2.4.5);
   - at least `MIN_GAMEPLAY_SECONDS = 300` of gameplay (0.4's verdict needs 120 s; a comparison needs rates that mean something);
   - the offer's setting precondition (§2.3.3): the key is in this instance's settings, its mod is loaded, it is changeable,
     the effective current value (staged ops included, `EffectiveSettings`) differs from the target, and for render distance the
     server doesn't already send fewer chunks than the target;
   - no other fix is being measured (one at a time, or the comparison is confounded), and `stutter-fixes.json` is writable.
3. **The rules' `evidence` condition** on the `stutterFixes` entry, evaluated TRUE (UNKNOWN never offers) against the same
   `EvalContext` the advice used (`StutterAdvisor.context`, `:79-82`), i.e. every v2 key, every 0.4 stutter key, plus one new key:

   `causeSpikesAtLeast: {cause: n}` = at least n spikes in which that cause claimed at least half of the spike's lost time.
   This is the missing "confidence": an aggregate share can come from one huge spike, a count of dominated spikes can't. It is
   computed in `StutterAnalyzer.analyze` from the in-memory attributions (`Attribution.claims()` vs `spike().lost()`), UNKNOWN when
   the cause is in `facts.unmeasured()` (same rule as the share maps, `ConditionEvaluator.java:349-398`). It isn't diluted by
   audit SD-1 (a spike whose GC evidence rotated out simply doesn't count: undercounting only, the conservative direction).

Rejected alternatives: (a) `kind == critical` (§1.1); (b) a `fix` object inside each `stutterAdvice` entry. (b) is parser-safe
for 0.4.0 (verified, §1.4), but `SchemaConsistencyTest.ruleFieldsMatch` ties `AdviceRule`'s Java fields to the updater's main-list
advice fields (`src/test/.../core/rules/SchemaConsistencyTest.java:65,73`), so the field would leak into main-list advice or need a
carve-out; and on 0.5 `LenientSection` (`core/rules/LenientSection.java:30-40`) drops a whole section on any parse error, so one
malformed `fix` would drop all Stutter Doctor advice. A separate section keeps advice text and actions apart and fails alone.

### 1.4 How 0.4.0's parser treats the new data (verified in the code, not assumed)

A scratch harness compiled `git show v0.4.0:` copies of `RulesDocument`, `LenientSection`, `ConditionAdapterFactory`,
`Condition`, `BudgetedChars` and `Impact` with Gson 2.14.0 (the version both 26.2 and 26.3 ship:
`fabric-loom/26.x/mojang_minecraft_info.json`) and parsed today's `rules/rules-v2.json` the way 0.4.0's
`RulesLoader.parse` does (`new GsonBuilder().registerTypeAdapterFactory(new ConditionAdapterFactory()).create().fromJson(json,
RulesDocument.class)`). Output, identical for every case (5 stutterAdvice, 34 advice, 56 settings, no unknown condition keys on
`stutter-sodium-defer`'s `when`):

| case | 0.4.0 | 0.3.0 (same harness on `v0.3.0:` sources) |
|---|---|---|
| baseline r16 | stutterAdvice=5 advice=34 settings=56 | advice=34 settings=56 |
| `fix` object added inside a stutterAdvice entry | unchanged, entry kept | unchanged |
| new top-level `stutterFixes` array (with the new condition key inside) | unchanged | unchanged |
| `stutterFixes` as a string / as null | unchanged | unchanged |
| `fix` as a number inside the entry | unchanged | unchanged |

Gson's reflective adapter skips unknown names whatever their JSON shape. So a new top-level section costs 0.2.0-0.4.0 nothing,
and 0.1.x never sees it (`rules-v1.json` leaves V2-only sections out, `tools/update_rules.py:144`). The harness is in the scratch
directory (`f-stutterfix/v040`, `f-stutterfix/v030`); §5 turns it into a CI test.

### 1.5 Which live rules map to a setting change

| rule (r16) | fires when | fix? | the change | takes effect |
|---|---|---|---|---|
| `ram-stutter-gc-heap` | gc ≥ 30 %, full GC/stall/live set ≥ 75 %, heap room ≥ 2 GB | **No, advice** | the heap is the launcher's (DESIGN Non-goals: "Changing the launcher's RAM allocation…") | - |
| `stutter-gc-explicit` | ≥ 2 System.gc(), gc ≥ 10 % | **No, advice** | "a mod, or Java freeing direct memory": no setting, and RigTune can't tell which mod | - |
| `stutter-sodium-defer` | chunkBuild ≥ 25 %, Sodium, Chunk Updates ZERO_FRAMES/ONE_FRAME | **Yes** | `sodium.performance.chunk_build_defer_mode` → `ALWAYS` ("Deferred", Sodium's own default and the r16 main-list value for tier ≤ 3) | at the next restart (PATCH_JSON, staged) |
| `stutter-chunk-loading` | chunkLoad ≥ 30 % | **Yes** | `vanilla.renderDistance` −2, never below 6 (the advice says "2 to 4 chunks shorter") | now (vanilla) |
| `stutter-dh-threads` | DH tag ≥ 40 % of spikes, CPU contended ≥ 30 % | **Yes, cut first** | `dh.common.multiThreading.numberOfThreads` −2, never below 1 | at the next restart (PATCH_TOML) |

The DH rule is correlational (tags never claim milliseconds, `Attributor.java:13-19`), so its evidence gate is the strictest and it
is the first thing to cut (§9). The two non-fix rules could still get a "compare with the next session" follow-up for a change
the player makes in the launcher; that is out of scope here (listed as an open question).

### 1.6 The before/after comparison

**What is compared.** Outcome measures only: hitches per minute of gameplay and lost milliseconds per minute. Never the cause
shares, because the attribution rule itself changes with the Sodium fix: with Chunk Updates set to wait, any render excess over
half a spike's lost time is claimed as chunk building without other evidence (`Attributor.java:196`,
`StutterService.java:366`); after the fix it needs a measured backlog. The chunk-building share would drop by construction. The
1 % low is shown nowhere in the comparison: audit SD-2 found it covers only the frame ring's tail (`StutterAnalyzer.java:116,124`).

**"Same conditions" for two play sessions** (`core/stutter/FixConditions`, captured when the fix is applied and again at the start
and end of every candidate session):
- Minecraft version; the mod-set hash (`BenchmarkConditions.modSetHash()`, RigTune left out, the benchmark's hash);
- the max heap (MB) and the collector family (a changed heap would be exactly the other kind of fix);
- window width/height and fullscreen (as `BenchmarkRecord.Context.sameConditions`, `core/benchmark/BenchmarkRecord.java:68-72`);
- every `ShareKeys.MANAGED` key present (render/simulation distance, Sodium, Iris `enableShaders`, DH `rendererMode`, the thread
  counts, …) plus `iris.shaderPack`, all except the fixed key; the fixed key must equal the fix's target;
- the kind of world (singleplayer incl. Open to LAN host / LAN guest / Realm / remote: `ServerLimits.Kind`, `core/model/ServerLimits.java:6`);
- the measurement: phase timing and the GC listener available in both (else a cause couldn't be measured the same way).

The world itself (which save, where the player went) can't be held equal; the wording says so every time. A session whose
conditions differ doesn't count and the block says why (one reason line); it doesn't end the tracking.

**Minimum length.** Each counted session ≥ 120 s of gameplay; sessions accumulate until the after side has
`clamp(before.gameplay, 300 s, 1200 s)` of gameplay (more play on the after side gives the test power: halving 20 → 10 hitches at
equal time reaches p = 0.049, 12 → 6 only p = 0.119). Up to 5 skipped sessions or 14 days, then "expired".

**Noise test** (`core/stutter/FixComparison`, pure). Hitches cluster (a teleport gives a dozen in 10 s), so a plain Poisson test
would overclaim. Quasi-Poisson: bin each session's hitches by 60 s of wall time, dispersion φ = variance/mean of the bin counts
(≥ 1, pooled over both sides), effective counts h/φ; one-sided exact binomial test of the after count given the total, with
p0 = after gameplay / total gameplay. Verdict:
- **less**: after rate ≤ 2/3 of before, p ≤ 0.05, and lost ms per minute not higher;
- **more**: after rate ≥ 3/2 of before, p ≤ 0.05 the other way;
- **no clear change**: otherwise.

Worked on the verified AC5.8 data (`docs/v0.4/verification/stutter/table.md`), computed for this doc:
- A (4 hitches / 352 s) vs A3 (1 / 383 s), two still controls: ratio 0.23, p = 0.16 → no clear change (correct; a naive ratio
  would call a 77 % "improvement").
- B (28 / 140 s, forced GCs) then A: ratio 0.06, p < 0.0001 → less.
- C4 (14 hitches in 131 s, all in one post-teleport burst, bins [0, 0, 14], φ = 14) vs 5 hitches: p = 0.5 → no clear change;
  without the dispersion guard p = 0.032 would have claimed "less" from one burst.

**Honest wording when the next session is noisier or worse.** Every verdict line shows both numbers. "More stutter" says it may be
unrelated because sessions vary, and puts an "Undo this change…" button next to it; it is never hidden. "No clear change" says the
difference is within how much sessions vary and leaves keep/undo to the player. "Less stutter" says it is a measured comparison,
not proof. No verdict uses "fixed", "caused" or "because of" (`WordingTest.java:31-33` already forbids the last two under
`rigtune.stutter.`).

### 1.7 Interactions

**Profiles.** All three fix keys are profile-managed (`ShareKeys.java:115,135,144`). A fix is not a profile switch: the active
marker stays (it only voids when the switch's own entry has nothing left, `core/profile/ActiveProfile.java:18-30`), the saved
profile isn't edited, and the offer row says "Your active profile, %s, also sets this; switching profiles later changes it
again." A later switch that changes the fixed key moves the record to "replaced" (the key no longer equals the target at a
session start). Templates never see fixes (as with server limits).

**The main list (a real ping-pong).** r16's settings rules recommend `chunk_build_defer_mode = ONE_FRAME` for tier ≥ 4 on the
quality goal (ticked by default) and render distance 12/16 for tiers 4/5 (unticked). After a fix, the main list would offer to
undo it. `core/stutter/FixHold` (pure, mirroring `ServerCap`, `core/recommend/ServerCap.java`, applied at the same place,
`RealController.java:335`) unticks a SetSetting recommendation that moves an actively fixed key away from the fix's target and
appends "The Stutter Doctor's fix set this on %s; changing it back may bring the stutter back." Active = the fix entry still has an
APPLIED or STAGED change (the `ActiveProfile` test). Never inside `Recommender` or `settingTargets`.

**The benchmark's own capture.**
- Benchmark captures (`source: benchmark`) never get an offer: their sweeps change render distance on purpose and run in
  RigTune's world, not the player's settings.
- A benchmark run ends a running session (`StutterService.java:229-256`); that session counts for a comparison only on its own
  merits (≥ 120 s, conditions unchanged). A kept benchmark result that changes render distance makes later sessions not count
  (setting changed); if it changes the fixed key itself, the record becomes "replaced".
- A render-distance fix marks the last benchmark "Needs a rerun" (the stale marker compares RD), which is correct.
- C09 (Measured Try It) owns benchmark before/after pairs (`BenchmarkRecord.pairId`); C20 doesn't touch them. Both share only
  `UndoScreen(parent, controller, entryId)` for "undo this change".

**Undo, Discard, the journal.** Undo this / last / all revert it like any Apply. The record follows the journal entry: REVERTED →
"undone", DISCARDED or ABANDONED → "not applied", entry folded away by the 50-entry cap (`Journal.cap`) → tracking stops, a
computed verdict is kept.

### 1.8 "RigTune never fights the launcher" (new P0)

Every fix is a setting (`vanilla.`, `sodium.`, `dh.`), which the P0 keeps one-click in both modes. It is structural, not a check:
the client's `FixSpec.KEYS` allowlist and the updater refuse any `set.key` outside three settings keys, and there is no mod-file
fix type. A future fix that touches a jar would need its own `requires` feature and, in a launcher-managed instance, would become
advice with the launcher's steps (the P0's rule). Nothing in this design reads the launcher mode.

### 1.9 The audit's SD-1..SD-6 (another agent fixes them; no collision)

- SD-1 (GC/sampler rings shorter than the spike window): the gate uses dominated-spike counts (undercount only) and the
  comparison uses hitches and lost time, which come from the frame and candidate rings covering the whole capture. If the SD-1 fix
  adds GC/DH to `unmeasured` when a ring wrapped, evidence becomes UNKNOWN → no offer: the right outcome.
- SD-2 (1 % low over the ring's tail): not used by the comparison.
- SD-3 (no summary after an unsaved session) and R10-1/L1 (`end()` loses a session if the copy throws): the fix hook is one
  statement in `end()`'s save runnable right after `analyze(copy, machine)` (`StutterService.java:200-217`), in its own try/catch,
  independent of `worthSaving`. Whoever lands second rebases one line. Flagged as a hotspot (§7).
- SD-4 (Clear race): Clear doesn't touch `stutter-fixes.json`; fix records carry their own before-numbers.
- SD-5 (NPE on `"causes": {"unknown": null}`): the fix code reads no saved report fields; its own file is read through
  `StateStore`'s type-checked accessors.
- SD-6 (Shenandoah live set): feeds only `ram-stutter-gc-heap`, which is not a fix.

### 1.10 The brainstorm's three open questions, answered

1. No threshold exists; a new floor (`FixGate`: 8 hitches, 300 s, monitor source) plus a per-fix rules `evidence` condition with
   the new `causeSpikesAtLeast` key. Nothing reuses `kind`.
2. Worse or noisier sessions are reported as they are, with numbers and an Undo button (§1.6).
3. Yes: the comparison lives in `StutterScreen` (a block at the top of its list); the confirm step reuses `PreviewScreen`'s
   `Confirm` overload (`client/ui/PreviewScreen.java:89-103`), as profile switches and imports do. No new screen.

---

## 2. Design

### 2.1 Data flow

```
analysis (Probes.EXECUTOR, existing: StutterService.analyze)
  StutterAnalyzer.analyze -> report + facts (+ causeSpikes, + SessionOutcome)
  StutterAdvisor.evaluate -> fired advice                      (unchanged)
  FixOffers.evaluate(rules.stutterFixes, ctx, report, facts, fired, effective settings, loaded mods, limits, active record)
      -> per advice id: Offer(key, from, to, now|restart) or NotYet(reason)
  -> Analysis(report, advice, offers) -> StutterView(..., fixes, tracked)

StutterScreen (render thread): advice row + [Try this fix…]
  -> PreviewScreen(loader: c -> c.previewStutterFix(offer), Confirm("Apply fix"))
  -> RigTuneController.applyStutterFix(offer) -> StutterFixService.apply (render thread, like ProfileService.switchTo)
       re-check precondition (effective value == offer.from, not downloading, no benchmark, store writable, none active)
       entryId = ChangeRecorder.newEntryId(); RealController.apply(List.of(rec), entryId)   (journal; vanilla now, config staged)
       FixStore.add(record{entryId, key, from, to, before outcome, conditions})           (io chain, StateStore)
       immediate: StutterService.restartSession()                                          (after side starts clean)

each monitor session start (render thread, only while a record is measuring): FixConditions.capture -> kept with the Capture
each monitor session end (existing end() runnable, Probes.EXECUTOR): StutterFixService.onSessionEnded(analysis, start conditions)
  -> FixTracker.advance(record, journal statuses, session) -> FixStore.update -> view model refreshed
main list rebuild (existing worker): FixHold.apply(ServerCap.apply(...), active holds)
```

### 2.2 Rules: the `stutterFixes` section (rules-v2 only, 0.5+)

```json
"stutterFixes": [
  { "adviceId": "stutter-sodium-defer", "requires": ["stutter-fix"],
    "evidence": { "stutterShareAtLeast": { "chunkBuild": 40 }, "causeSpikesAtLeast": { "chunkBuild": 5 } },
    "set": { "key": "sodium.performance.chunk_build_defer_mode", "value": "ALWAYS" } },
  { "adviceId": "stutter-chunk-loading", "requires": ["stutter-fix"],
    "evidence": { "stutterShareAtLeast": { "chunkLoad": 40 }, "causeSpikesAtLeast": { "chunkLoad": 5 } },
    "set": { "key": "vanilla.renderDistance", "step": -2, "min": 6 } },
  { "adviceId": "stutter-dh-threads", "requires": ["stutter-fix"],
    "evidence": { "stutterTaggedShareAtLeast": { "dh": 60 }, "cpuContentionShareAtLeast": 50, "spikesPerMinuteAtLeast": 20 },
    "set": { "key": "dh.common.multiThreading.numberOfThreads", "step": -2, "min": 1 } }
]
```

The thresholds are **UNVERIFIED starting values**: no AC5.8 run fired either chunk advice (C1-C4 left chunk hitches "not
explained"; the dev PC's Sodium never backed up). They must be checked against the real run (§5.3) before release; the DH
census run (6 of 14 spikes, 43 %) fires the advice but not this fix, which is the intended strictness.

| field | rules |
|---|---|
| `adviceId` | required; the id of a `stutterAdvice` entry in the same document; unique across the section |
| `requires` | required, must contain `stutter-fix` (known only by `FixOffers.SUPPORTED_FEATURES`, never by the main list or `StutterAdvisor`); an unknown feature → the client skips the entry (future fix types) |
| `evidence` | required, a Condition; v2 keys + the 0.4 stutter keys + `causeSpikesAtLeast`; TRUE offers, FALSE/UNKNOWN don't |
| `set.key` | required; one of `FixSpec.KEYS` = `vanilla.renderDistance`, `sodium.performance.chunk_build_defer_mode`, `dh.common.multiThreading.numberOfThreads` |
| `set.value` | xor `step`; must decode in the key's `ShareKeys` table entry (ENUM value list / INT range) |
| `set.step` | xor `value`; non-zero integer, \|step\| ≤ 8, INT keys only; needs `min` when negative, `max` when positive; target = clamp(current + step, bound, ShareKeys range) |

`causeSpikesAtLeast` (Condition field, `Map<String, String>` like the share maps, plan review K-M1): cause → whole count ≥ 0;
causes as `stutterShareAtLeast`'s vocabulary. The updater allows it **only** inside `stutterFixes[].evidence`: in `stutterAdvice`
it would poison the whole `when` on 0.4.0 and the advice would vanish there. No `v1` (the section never reaches rules-v1.json).

Java model: `RulesDocument` gains `@JsonAdapter(LenientSection.class) public List<StutterFix> stutterFixes;` with
`StutterFix { List<String> requires; String adviceId; Condition evidence; FixSet set; }` and
`FixSet { String key; JsonElement value; JsonElement step; JsonElement min; JsonElement max; }` (numbers as `JsonElement`, checked
in `FixSpec`, so a wrong type drops one entry, not the section); `fillDefaults` drops nulls. A section Gson can't read at all
becomes null (no fixes; advice unaffected, since it is a different field).

Updater (`tools/update_rules.py`): `KNOWLEDGE_TOP_LEVEL` and `V2_ONLY_SECTIONS` gain `stutterFixes` (`:139-144`); new
`stutter_fix_problems(section, advice_ids)`: shape, unknown fields, nulls, `adviceId` exists and is unique, `requires` has
`stutter-fix`, `evidence` present and valid with `V2_CONDITION_KEYS | STUTTER_CONDITION_KEYS | {"causeSpikesAtLeast"}`, no jvm-
flags, `set` per the table (`STUTTER_FIX_KEYS`, the ShareKeys ranges it already mirrors as `managedProfileKeys`); and the
existing `condition_problems` refuses `causeSpikesAtLeast` everywhere else. `SchemaConsistencyTest` gains a check that the
updater's `STUTTER_FIX_FIELDS`, `STUTTER_FIX_SET_FIELDS`, `STUTTER_FIX_KEYS` and `STUTTER_FIX_FEATURE` equal the Java ones.
Revision 17 carries the seeds.

### 2.3 Classes

#### 2.3.1 New, core (pure, no Minecraft types, JUnit)

| class | job |
|---|---|
| `core/stutter/FixSpec` | record of one valid `stutterFixes` entry; `static List<FixSpec> of(RulesDocument)` validates (feature, adviceId, `KEYS`, value/step against `ShareKeys`), drops invalid entries with a logged reason; `target(String current)` resolves the value (the table's own spelling, as `ProfileSwitch.build` does, `core/profile/ProfileSwitch.java:41-43`) |
| `core/stutter/FixGate` | the client floor constants and `check(report, outcome, active, storeWritable)` → OK or a `Reason` |
| `core/stutter/FixOffers` | `evaluate(...)` (see §2.1) → `Map<String, FixOffer>`; `FixOffer` is sealed: `Offer(adviceId, key, from, to, boolean now)` / `NotYet(adviceId, Reason, args)`; no offer when the setting precondition fails (silent: the advice's own text already covers it) |
| `core/stutter/SessionOutcome` | record: gameplaySeconds, hitches, lostMs, bins, binMean, binVariance; `of(Result)`; `plus(other)` for accumulated after sessions |
| `core/stutter/FixComparison` | `compare(before, after)` → `Verdict(kind LESS/SAME/MORE, beforePerMin, afterPerMin, lostBefore, lostAfter, pLess, pMore)`; exact binomial CDF in log space (O(n), n capped at 100 000) |
| `core/stutter/FixConditions` | record (§1.6) + `differences(other, fixKey)` → reason ids in a fixed order |
| `core/stutter/FixStore` | `config/rigtune/stutter-fixes.json` on `StateStore` (§2.5); `records()`, `add`, `update(entryId, change)`, `dismiss(entryId)` |
| `core/stutter/FixTracker` | the pure state machine: `advance(record, JournalEntry of entryId or null, @Nullable SessionEnd)` → record |
| `core/stutter/FixHold` | the main-list post-step (§1.7) |

#### 2.3.2 Changed, core

- `StutterFacts` (`core/stutter/StutterFacts.java:23-38`): + `Map<String, Integer> causeSpikes`; the 12- and 10-argument
  constructors stay (tests use them; in-memory only, never persisted).
- `StutterAnalyzer` (`:51-142`): counts dominated spikes per cause in the existing attribution loop (`:74-86`), builds
  `SessionOutcome` (bins from spike end times since `in.startNanos()`); `Result` gains `outcome` (old constructor kept).
- `Condition` + `causeSpikesAtLeast`; `ConditionEvaluator.hasStutterKey` / `stutter()` (`:338-366`) evaluate it (UNKNOWN when
  unmeasured, unknown cause, or not a whole number; UNKNOWN in the main list, which has no facts).
- `RulesDocument` + `stutterFixes` (+ `fillDefaults`).
- `StutterView` (`core/stutter/StutterView.java:11-21`) + `Map<String, FixOffer> fixes`, `@Nullable TrackedFix tracked` (a display record: change label,
  date, state, progress, verdict, skip reason); the 7-argument constructor stays.
- `HistoryModel.Entry` + `fix` label (old constructor kept) and `withFixes(view, labels)` (cut 2nd, §9).

#### 2.3.3 Client

- New `client/stutter/StutterFixService` (owned by `StutterService`, like the other 0.4 services: no work in its constructor):
  `apply(Offer)` mirrors `ProfileService.switchTo` (`client/profile/ProfileService.java:330-360`): re-reads the effective value
  (`SettingsBridge.read` + pending ops through `EffectiveSettings`) and refuses if it isn't `offer.from`; refuses while
  downloading, while a benchmark runs (same `BooleanSupplier` seam as `ProfileService.overrideBenchmarkCheck`), when a record is
  staged or measuring, or when the store isn't writable; builds `Recommendation.of("stutterfix:" + adviceId, Category.SETTING,
  Impact.MEDIUM, SettingValues.describe(label, key, from, to), Text.of("rigtune.stutter.fix.reason", …), new
  Action.SetSetting(key, from, to), true)`; `controller.apply(List.of(rec), entryId)`; reads the journal entry back (as
  `ProfileService` does) to know STAGED vs APPLIED and whether anything was recorded; queues `FixStore.add` on `StutterService`'s
  ordered io chain; for an immediate change calls `StutterService.restartSession()`. Also: `onSessionStart(capture)`,
  `onSessionEnded(analysis, capture)`, `view()` (cached view model, refreshed on the io chain), `dismiss(entryId)`, `holds()`.
- `StutterService` (`client/stutter/StutterService.java`): `Analysis` + offers; `analyze()` (`:363-376`) calls `FixOffers`; the
  `Machine` snapshot (`:59-61,353-361`) gains the pending ops' effective settings, the loaded mod ids and the live server limits;
  `end()` (`:193-223`) calls `fixes.onSessionEnded` (one guarded statement); `tick()` (`:158-167`) copies the live server limits
  into the running capture (a reference store per tick, not per frame) and calls `fixes.onSessionStart` once per new session;
  new `restartSession()` = the existing `end(session, minecraft, false)` + `StutterCapture.startSession()` (the pattern of
  `clear()`, `:134-145`, but saving).
- `StutterMonitor.Capture` (`client/stutter/StutterMonitor.java:30-38`): + `volatile @Nullable ServerLimits limits` (the world kind
  and view distance seen during the session; `ServerLimitsTracker` clears its live state on disconnect, before `end()` runs).
- `RealController`: `previewStutterFix`, `applyStutterFix`, `dismissStutterFix` (one line each, as the 0.4 delegations,
  `:928-952`); `FixHold.apply` wraps the `ServerCap.apply` call (`:335`); History labels merge profile and fix labels.
- `RigTuneController`: the three methods as defaults (`ApplyPreview.EMPTY`, a "nothing" status, no-op) for `StubController`.
- `StutterScreen`, `HistoryScreen`: §2.6.

### 2.4 Details worth pinning down

1. **Target resolution.** `value`: decoded/re-encoded through the `ShareKeys` entry. `step`: the effective current value must
   parse as an integer (else no offer); target = clamp(current + step, min/max, table range); equal to current → no offer. For
   render distance on a LAN guest/Realm/remote server with live view distance L < current: no offer when target ≥ L
   (`NotYet(SERVER, L)`: a shorter distance wouldn't change what loads).
2. **Before numbers** are frozen at the click from the shown analysis (live or just ended): the same numbers the gate saw. A
   saved report from an earlier run (loaded from `stutter.json`) has no facts (`StutterService.adviceFor`, `:341-351`, keeps only
   ids), so it never offers a fix; that also avoids offering against settings that may have changed since.
3. **The after side** starts only once the change is in effect: immediately for vanilla keys (the session is restarted); for staged
   keys, the first session whose start snapshot has key == target *and* whose journal change is APPLIED (after the helper ran).
   Sessions in the same game run as a staged fix aren't "skipped", they simply don't start the after side.
4. **The restarted session** after an immediate render-distance change begins with the unload/reload of chunks. It should get the
   same 10 s exclusion as a level change (the `AFTER_CLIENT_LEVEL_CHANGE` window, ws-s decision 7). UNVERIFIED which event is
   the least invasive way to trigger it; check `StutterHooks`' level-change handler when implementing.
5. **Binomial CDF.** log pmf(0) = n·log1p(−p0), log pmf(k+1) = log pmf(k) + log(n−k) − log(k+1) + log(p0) − log1p(−p0), summed
   with log-sum-exp; φ = max(1, pooled variance/mean) over bins with k ≥ 3 per side (else φ = 1 for that side). The numbers in §1.3
   and §1.6 were computed with an exact `math.comb` reference and are the unit test's expected values.

### 2.5 State file: `config/rigtune/stutter-fixes.json`

On `StateStore` (`core/store/StateStore.java`): one instance per process; `update` re-reads under the lock; unknown fields survive
at any depth; values type-checked; `JsonStateFile` rules: `formatVersion: 1`, atomic write, cap **32 KiB**, corrupt → `.bad`
(never replaced) and empty, newer `formatVersion` → read-only (then no offers: `NotYet(STORE)`), I/O error or over 4 × the cap →
left alone; nothing throws; a read error never blocks startup. All writes go through `StutterService`'s ordered io chain on
`Probes.EXECUTOR` (so a dismiss can't be overtaken by an older session-end write), the render thread never reads the file.

```json
{ "formatVersion": 1,
  "fixes": [ {
    "entryId": "3f0c…", "adviceId": "stutter-sodium-defer",
    "key": "sodium.performance.chunk_build_defer_mode", "from": "ZERO_FRAMES", "to": "ALWAYS",
    "appliedAt": "2026-10-02T09:14:00Z", "rulesRevision": 17, "now": false,
    "state": "measuring",
    "before": { "startedAt": "2026-10-02T08:59:12Z", "gameplaySeconds": 612.4, "hitches": 41, "lostMs": 3120.5, "bins": 11, "binMean": 3.7, "binVariance": 6.1 },
    "conditions": { "mc": "26.2", "modSetHash": "…", "heapMaxMb": 4096, "collector": "g1", "width": 1920, "height": 1080,
      "fullscreen": true, "world": "SINGLEPLAYER", "phaseTiming": true, "gcMeasured": true,
      "settings": { "vanilla.renderDistance": "12", "iris.shaderPack": null, "…": "…" } },
    "after": { "sessions": 2, "gameplaySeconds": 640.0, "hitches": 9, "lostMs": 540.0, "bins": 12, "binMean": 0.8, "binVariance": 0.9 },
    "skipped": 1, "lastSkip": "setting:vanilla.renderDistance",
    "verdict": "less", "dismissed": false } ] }
```

- `state`: `staged` → `measuring` → `compared`; or `undone`, `not_applied`, `replaced`, `expired`. Only one record may be
  `staged`/`measuring`.
- At most 10 records; over that (or over the cap) the oldest finished one goes first, never the active one. About 1.5-2 KB a
  record (≤ 30 managed values).
- Local only: in no report or Copy summary except the numbers line the player copies (cut 3rd); the shader pack name is the only
  string that isn't RigTune's own, as in `benchmarks.json`.

### 2.6 UI

**StutterScreen** (`client/ui/StutterScreen.java`), no new screen:
- In `advice()` (`:323-344`), after an advice's text: with an `Offer`, a TextRow "Try it in one click: Sodium: Chunk Updates:
  Immediate → Deferred", a TextRow "Takes effect after you restart Minecraft. …" (or "now"), an optional profile note, and a new
  `ButtonRow` with **Try this fix…** (width min(row, 160)). With a `NotYet`, one COLOR_LABEL TextRow with the reason.
- At the top of `populate()` (`:166-187`), when `view.tracked()` isn't null or dismissed: heading "Your stutter fix", the change and
  date, the state line; when compared, two `BarRow`s (Before / After, bars scaled to the larger hitch rate, values "4.8 hitches a
  minute, 310 ms lost a minute") and the verdict line (COLOR_GOOD / COLOR_LABEL / COLOR_BAD, the existing constants, so `PaletteTest`
  needs no new literal); a `ButtonRow` with **Undo this change…** (opens `new UndoScreen(this, controller, entryId)`, as
  `HistoryScreen.java:147`) and **Dismiss**.
- `same()` (`:140-143`) also compares `fixes` and `tracked` so a state change rebuilds the list; `shownText()` includes the new rows
  for the game tests.
- `ButtonRow extends Row`: `children()`/`narratables()` return its buttons (the checkbox pattern of the main list,
  `RigTuneScreen.java:866-874`), so each button is its own Tab stop and `RowList` draws the focus frame and narrates it on 26.2
  (`RowList.java:32-45`). The Try button sets `Button.Builder.createNarration` (present on 26.2 and 26.3, §4) to "Try this fix: %s.
  Opens a preview first." so the narration names the change, not just "Try this fix…".
- **Fit at 640×480, GUI scale 2** (320×240 scaled): the column is min(420, 320 − 32) = 288 px and text wraps at 276
  (`init()`, `:79-107`); the list spans y 32-186 (154 px, ~15 text lines) and scrolls. A ButtonRow is 22 px; two buttons of 136 px
  fit "Undo this change…" (~100 px) and "Dismiss". The game test checks the three standard sizes (1280×720, 640×480 @2,
  854×480 @2) as `StutterGameTest.checkLayout` does, and that no button extends past the list.

**PreviewScreen** (unchanged class): `new PreviewScreen(this, controller, c -> c.previewStutterFix(offer), new
PreviewScreen.Confirm(Component.translatable("rigtune.stutter.fix.preview.subtitle", SafeLiteral.of(adviceTitle)),
Component.translatable("rigtune.stutter.fix.preview.apply"), () -> { setScreen(stutterScreen); apply(offer); }, null))`. The preview
already sorts the one change into "Changed now" or "Changed at the next restart" (`ApplyPreview.now/atRestart`,
`core/preview/ApplyPreview.java:16`). Cancel/Escape writes nothing.

**HistoryScreen** (`:318`): `entry.fix() != null` → "Stutter fix: %s" (cut 2nd).

**Main list**: `FixHold`'s reason line on the recommendation; nothing else on RigTuneScreen, no notice (cut: §9).

### 2.7 Wording: draft `en_us.json` keys (all `%s`, no `%d`, per LangCheckTest)

```json
"rigtune.stutter.fix.offer": "Try it in one click: %s",
"rigtune.stutter.fix.offer.now": "Takes effect now. You can undo it in History, and RigTune compares your next play with this session.",
"rigtune.stutter.fix.offer.restart": "Takes effect after you restart Minecraft. You can undo it in History, and RigTune compares your next play with this session.",
"rigtune.stutter.fix.offer.profile": "Your active profile, %s, also sets this; switching profiles later changes it again.",
"rigtune.stutter.fix.try": "Try this fix…",
"rigtune.stutter.fix.try.narration": "Try this fix: %s. Opens a preview first.",
"rigtune.stutter.fix.not_yet.length": "A one-click fix needs a longer session: at least %s of play and %s hitches (this one: %s, %s).",
"rigtune.stutter.fix.not_yet.evidence": "The measurements don't point at this clearly enough for a one-click fix; the advice above still applies.",
"rigtune.stutter.fix.not_yet.benchmark": "One-click fixes are offered for your own play sessions, not for benchmark runs.",
"rigtune.stutter.fix.not_yet.server": "This server sends at most %s chunks, so a shorter render distance would change nothing here.",
"rigtune.stutter.fix.not_yet.busy": "Another fix is still being measured. Wait for its comparison or dismiss it first.",
"rigtune.stutter.fix.not_yet.store": "RigTune can't keep track of fixes right now (config/rigtune/stutter-fixes.json can't be written).",
"rigtune.stutter.fix.preview.subtitle": "Stutter fix for: %s",
"rigtune.stutter.fix.preview.apply": "Apply fix",
"rigtune.stutter.fix.reason": "Stutter Doctor: %s",
"rigtune.stutter.fix.status.applied": "Fix applied. Keep the Stutter Doctor on and play at least %s; RigTune then compares that play with this session.",
"rigtune.stutter.fix.status.staged": "Fix staged: it takes effect after you restart Minecraft. Then play at least %s with the Stutter Doctor on.",
"rigtune.stutter.fix.status.gone": "This fix can't be applied now: the setting changed since the analysis. Look again in a moment.",
"rigtune.stutter.fix.status.untracked": "Fix applied, but RigTune can't track the comparison (stutter-fixes.json can't be written).",
"rigtune.stutter.fix.heading": "Your stutter fix",
"rigtune.stutter.fix.change": "%s, applied %s",
"rigtune.stutter.fix.state.staged": "Waiting for a restart: the change takes effect when Minecraft starts again.",
"rigtune.stutter.fix.state.measuring": "Measuring: %s of %s played with the Stutter Doctor on.",
"rigtune.stutter.fix.state.monitor_off": "Turn the Stutter Doctor on and play to compare.",
"rigtune.stutter.fix.state.skipped": "Your last session didn't count: %s.",
"rigtune.stutter.fix.skip.short": "it was shorter than 2 minutes",
"rigtune.stutter.fix.skip.setting": "%s changed (%s → %s)",
"rigtune.stutter.fix.skip.mods": "the mods changed",
"rigtune.stutter.fix.skip.display": "the window size or fullscreen changed",
"rigtune.stutter.fix.skip.memory": "the memory or the garbage collector changed",
"rigtune.stutter.fix.skip.world": "it was in another kind of world (singleplayer, LAN, Realm or server)",
"rigtune.stutter.fix.skip.measurement": "the Stutter Doctor could measure less than before",
"rigtune.stutter.fix.skip.version": "the Minecraft version changed",
"rigtune.stutter.fix.before": "Before",
"rigtune.stutter.fix.after": "After",
"rigtune.stutter.fix.rate": "%s hitches a minute, %s ms lost a minute",
"rigtune.stutter.fix.verdict.less": "Less stutter after the change: %s hitches a minute (was %s). Play sessions differ, so this is a measured comparison, not proof.",
"rigtune.stutter.fix.verdict.same": "No clear change: %s hitches a minute (was %s). The difference is within how much play sessions vary. Keep the change or undo it.",
"rigtune.stutter.fix.verdict.more": "More stutter after the change: %s hitches a minute (was %s). It may be unrelated, since sessions vary; if it stays worse, undo the change.",
"rigtune.stutter.fix.state.undone": "You undid this change.",
"rigtune.stutter.fix.state.not_applied": "The change wasn't applied (it was discarded, or the file couldn't be changed).",
"rigtune.stutter.fix.state.replaced": "%s was changed again since, so this comparison stopped.",
"rigtune.stutter.fix.state.expired": "No comparable play in time (%s sessions didn't count), so there's no comparison.",
"rigtune.stutter.fix.undo": "Undo this change…",
"rigtune.stutter.fix.dismiss": "Dismiss",
"rigtune.stutter.fix.dismiss.tooltip": "Hides this comparison. The setting stays as it is.",
"rigtune.stutter.fix.history_kind": "Stutter fix: %s",
"rigtune.stutter.fix.hold_reason": "The Stutter Doctor's fix set this on %s; changing it back may bring the stutter back."
```

The verdict and skip keys are chosen by id, so LangCheckTest needs them registered as a key family (like
`rigtune.stutter.cause.*`) or written out in a switch; `WordingTest` passes (no "caused"/"because of"/"limited by"); add a
stutter-fix list ("fixed the", "proves", "guarantee") to `WordingTest` so a later edit can't turn a comparison into a claim.
Durations use `StutterSummary.clock` ("5:00"), dates yyyy-MM-dd as elsewhere.

### 2.8 Threading and footprint

- Nothing per frame: the hook (`StutterMonitor.onFrame`) is untouched; `FrameHookBudgetTest` and the retained-bytes accounting
  (`StutterMonitor.retainedBytes()`, 2,506,752 bytes) are unchanged. No new thread (the io chain on `Probes.EXECUTOR`).
- Offers: computed inside the existing analysis job (worker), a few map lookups and one condition evaluation per fix.
- Dominated-spike counts and bins: one extra pass over the spike list already in the analysis (O(spikes)).
- Render thread: per tick, one reference copy of the live limits into the capture; per session start while a fix is measuring,
  one `SettingsBridge.read` (vanilla options in memory, config files cached by mtime, `SettingsBridge.java:54-80`) plus the
  window size: the same work `machine()` already does at every session end and 5-s live refresh. Apply runs on the render thread
  like any Apply.
- Startup: nothing at `onInitializeClient`; `StutterFixService` is created with `StutterService` and does no work in its
  constructor; `stutter-fixes.json` is first read on the io chain when StutterScreen opens or a session ends. The render-thread
  init ceilings (400 ms wall / 150 ms CPU) are untouched; re-run `FootprintGameTest` for the class-bytes budget (a dozen small new
  classes, loaded only after the first analysis): UNVERIFIED headroom until that run.
- The monitor stays opt-in: a fix never turns it on; the tracked block says "Turn the Stutter Doctor on and play to compare".

### 2.9 Error handling

- Rules: a bad entry is dropped with a log line (`FixSpec.of`); a section Gson can't read → null → no fixes; advice unaffected.
- Evaluation: a throwing `FixOffers` is caught in `analyze`, logged, offers empty (advice still shown).
- Apply: every refusal is a status line (busy, gone, store); `RealController.apply`'s own result Component is shown as is (failed
  settings, "some failed"); if the journal entry has no change, no record is written; a failed record write after a successful
  apply shows `status.untracked` (the change stays, undoable in History).
- Session end: `onSessionEnded` in its own try/catch after `analyze` (a failure never blocks the session save).
- Store: newer/corrupt/unreadable handled by `StateStore`/`JsonStateFile`; the view model falls back to "no tracked fix".
- Journal unreadable (`Journal.State` not OK/MISSING): the record isn't advanced (like `ActiveProfile.inEffect`, which "decides
  nothing").

---

## 3. Compatibility

- **0.1.x-0.4.x files**: none changes shape. `stutter.json` keeps 0.4's `StutterReport` exactly (no new session field, since 0.4.0
  would drop it on its next rewrite); `history.json` gets ordinary `apply` entries; `pending.json` gets only `PATCH_JSON`/
  `PATCH_TOML` ops that 0.2+ helpers already run (no new `PendingActions.Type`, the Gson-enum trap C22 hit); `settings.json`,
  `profiles.json`, `benchmarks.json` untouched. New data only in the new `stutter-fixes.json`.
- **Downgrade to 0.4.0**: 0.4.0 ignores `stutter-fixes.json` and the `stutterFixes` section (verified, §1.4); a staged fix is a
  plain PATCH op that 0.4.0's helper applies; History shows an "Apply" entry with the setting row; Undo this works. Back on 0.5 the
  record follows the journal (REVERTED → undone; a change made meanwhile → replaced). The released-jar harness should get a 0.4.0
  variant (`tools/e2e/compat040.py` or a pinned `v040` parser copy) that feeds r17 and the `v050-written` fixtures to 0.4.0's code.
- **Rules**: `rules-v1.json` unchanged apart from `revision`/`generatedAt` (the section is V2-only; `RulesV1DifferentialTest` and
  `check_rules_v1.py` keep passing). rules-v2: a lenient new section (older parsers ignore it), every entry `requires` a feature
  only 0.5 knows, a fail-closed `evidence` condition, and the one new condition key allowed only there. No existing
  `stutterAdvice` entry changes, so 0.4.0 shows exactly the advice it shows today. `minModVersion` unchanged.
- **Launcher-managed instances**: identical (settings only, §1.8).

## 4. 26.2 vs 26.3

- UI APIs used are identical (javap on `minecraft-clientonly-deobf-26.2/26.3.jar` in Loom's cache): `Button$Builder` (`bounds`,
  `tooltip`, `createNarration(Button$CreateNarration)`, `build`), `Button$CreateNarration.createNarrationMessage(Supplier)`,
  `ContainerObjectSelectionList$Entry` (`narratables()`, `mouseClicked(MouseButtonEvent, boolean)`, focus paths), `AbstractWidget`
  (`setTooltip`, `setMessage`, `active`). No `//?` block is needed in the new code; the one existing difference (26.2's list
  narration) is handled by `RowList` (`:32-45`), and real Buttons don't compare mouse-button constants (the 26.3 SDL3 issue,
  DESIGN "Accessibility (0.4)").
- Settings: both nodes run Sodium `0.9.2` (`versions/26.x/gradle.properties`), so `chunk_build_defer_mode` and its values are the
  same; vanilla `renderDistance` goes through `VanillaChanges` on both (profiles already use it); DH isn't in CI on either.
- Stutter capture: phase timers run on all three CI legs (ws-s: "timers seen 11111"). The local 26.3 client crash noted in 0.4
  means the real run is 26.2 only, as in AC5.8.

## 5. Test plan

### 5.1 Unit tests (JUnit, both nodes)

- `FixSpecTest`: valid seeds parse; unknown `requires` feature skipped; key outside `KEYS` refused; `value` outside the ShareKeys
  enum/range refused; `step` without its bound refused; `value` and `step` both refused; wrong JSON types drop one entry only;
  `target()` clamps (RD 7 → 6 at min 6; RD 6 → no offer).
- `RulesStutterFixesTest`: a malformed `stutterFixes` section → null, `stutterAdvice` still 5; bundled r17 parses.
- `LegacyParserTest` (or `tools/e2e` job): 0.4.0's and 0.3.0's parser classes read r17 with the same advice/settings/stutterAdvice
  as r16 (turns the §1.4 harness into CI; a pinned `v040` copy like the existing `v030/core/rules/`).
- `StutterConditionTest` (existing, extended): `causeSpikesAtLeast` TRUE/FALSE, UNKNOWN for an unmeasured cause, an unknown
  cause, a non-whole number; UNKNOWN in the main list; `not {causeSpikesAtLeast…}` never TRUE when unmeasured.
- `StutterAnalyzerTest` (extended): dominated-spike counts (a spike where GC claimed 40 % doesn't count, 60 % does); bins and
  hitch counts for a synthetic capture; `SessionOutcome.plus`.
- `FixGateTest` / `FixOffersTest`: each floor alone blocks (7 hitches; 299 s; benchmark source; another record measuring; store
  read-only); evidence FALSE and UNKNOWN block; advice not fired blocks; setting missing, mod not loaded, value already the target,
  staged op already setting the target (`EffectiveSettings`) block; server view distance ≤ target blocks the RD fix only on
  LAN/Realm/remote; all pass → exactly one `Offer` with the right from/to/now.
- `FixComparisonTest`: binomial CDF against exact values; the §1.6 cases (A vs A3 → same, p 0.162; B → A → less; C4 burst with
  φ 14 → same, and → less with the guard off, to prove the guard matters); perfect fix 8 → 0 → less; 10 → 20 → more (p 0.049);
  lost-time veto (fewer hitches but more lost ms → same); zero bins / one bin → φ 1.
- `FixConditionsTest`: each field alone gives its reason id; the fixed key is excluded; the order of reasons is stable.
- `FixTrackerTest`: STAGED → APPLIED starts measuring only with key == target at a session start; REVERTED → undone; DISCARDED /
  ABANDONED → not_applied; entry missing → tracking stops, verdict kept; 5 skips / 14 days → expired; accumulation to the target
  gameplay; a key change → replaced.
- `FixStoreTest`: `JsonStateFile` rules (formatVersion, cap, corrupt → `.bad`, newer → read-only and no offers, unknown fields at
  every depth survive an update, hand-edited junk types read as absent); 10-record cap drops the oldest finished, never the active.
- `FixHoldTest`: unticks and annotates a main-list SetSetting that reverses an active fix; leaves other keys and same-direction
  changes alone; nothing after undo.
- Existing guards that must stay green: `LangCheckTest` (keys, families, `%s`), `WordingTest` (+ the new list), `PaletteTest`,
  `SchemaConsistencyTest` (+ the new sets), `RulesV1DifferentialTest`, `StutterWrittenFixtureTest`, `FrameRingAllocationTest`,
  `StutterMonitorTest` (0 bytes per frame).
- Python (`tools/tests/test_update_rules.py`): the section validates; each refusal (unknown field, null, missing/duplicate/unknown
  `adviceId`, missing `stutter-fix`, missing `evidence`, jvm- flag in evidence, `causeSpikesAtLeast` in `stutterAdvice` or the main
  list, key outside the allowlist, value/step shape); the section is absent from rules-v1.json.

### 5.2 Client game tests (all three Linux CI legs: 26.2 GL, 26.3 GL, 26.3 Vulkan under Xvfb; local fixtures only)

New `StutterFixGameTest` (registered in `src/gametest/resources/fabric.mod.json` after `StutterGameTest`, before
`FootprintGameTest`). Main case with the network off (RigTune's Modrinth and remote rules switched off in `settings.json`, so the
bundled r17 is used). CI's software rendering can't be relied on to produce chunk-dominated stutter, so the before and after
analyses are injected through a game-test probe in `StutterHooks` (the pattern of `lastBenchmark()`/`sessionsEnded()`) that runs
the real `FixOffers`, apply path and `FixTracker` on synthetic `Result`s; everything else is real.
1. **RD fix, immediate** (singleplayer world, monitor on): inject a chunk-loading-dominated analysis (e.g. chunkLoad 60 %, 10
   dominated spikes, 20 hitches, 400 s) → StutterScreen shows the offer rows and "Try this fix…" → screenshots at 1280×720,
   640×480 @2, 854×480 @2 with the layout check → press it → PreviewScreen shows one "now" row "Render Distance: 12 → 10" →
   Apply → `options.renderDistance == 10`, one `apply` journal entry with that change APPLIED, one `measuring` record, a session
   ended and a new one started.
2. **Comparison**: inject a matching after session (30 % of the hitch rate) → block shows Before/After bars and the "less" line;
   inject a mismatching one first (another setting changed) → "didn't count" line with the reason.
3. **Undo**: press "Undo this change…" → UndoScreen for that entry → confirm → render distance 12 → record `undone`, block says so.
4. **Staged Sodium path** (Sodium is on every leg): write `chunk_build_defer_mode: ZERO_FRAMES` into `sodium-options.json` (the
   bridge reads the file; Sodium's in-memory value doesn't matter to the offer), inject a chunk-building-dominated analysis → offer
   says "after a restart" → Apply → `pending.json` has one `PATCH_JSON` op, journal change STAGED, record `staged` → Discard
   pending → DISCARDED → record `not_applied`. Restore the file.
5. **Negatives**: benchmark-source report, 7 hitches, 4:59 of play, phase timing off (evidence UNKNOWN) → no button and the
   right one-line reason.
6. **Main list hold**: after step 1, the RigTune screen's render-distance recommendation (if the stub tier proposes 12) is unticked
   with the hold reason.
7. **Accessibility**: Tab reaches "Try this fix…", "Undo this change…" and "Dismiss" in order; the focused button's narration
   contains the change; `highContrastBlockOutline` on → screenshot (as ws-x does).
8. Downgrade/compat fixtures: write `src/test/resources/v050-written/stutter-fixes/` (a record in each state) for the released-jar
   harness and `e2eDowngrade`.

### 5.3 One real run on the dev PC (26.2, Ryzen 7 7800X3D / RX 7800 XT, the AC5.8 instance)

1. Instance: the RC jar, Fabric API, Sodium 0.9.2 with Chunk Updates = Immediate (ZERO_FRAMES), RD 12, monitor on.
2. Driver (the P5A `driver` pattern): a fresh world, teleport into new terrain every 20 s for 6 minutes, then open StutterScreen,
   log the report, `causeSpikes`, the offer, screenshot. Expect `stutter-sodium-defer` to fire (with ZERO_FRAMES, AC5.8's
   "render:low" post-teleport spikes become chunk-building claims). UNVERIFIED that it reaches 8 hitches, 40 % and 5 dominated
   spikes on this fast PC; if it doesn't, record the numbers, which is exactly the calibration data §2.2 needs.
3. Try this fix → Preview → Apply → quit (helper patches `sodium-options.json`) → relaunch → same driver script, 6 minutes →
   leave the world → the block shows a verdict; record both outcomes, φ and p.
4. Undo this → restart → Chunk Updates back to Immediate; the record says undone.
5. Render-distance fix: try the same on a local vanilla 26.2 server (the AC8.5 setup) where chunk packets may claim time; AC5.8 never
   saw a chunk-loading claim, so this may not fire: UNVERIFIED, and if it doesn't, the RD path is covered by CI only (say so).

## 6. Draft acceptance criteria

- **AC20.1** `rules/rules-v2.json` r17 has a `stutterFixes` section documented in RULES_SCHEMA.md; the updater validates it (every
  refusal in §5.1 has a Python test) and leaves it out of `rules-v1.json`, whose content is unchanged apart from `revision` and
  `generatedAt`.
- **AC20.2** 0.4.0's and 0.3.0's parser classes read r17 with the same `advice`, `settings` and (0.4.0) `stutterAdvice` as r16, in
  a CI test.
- **AC20.3** "Try this fix…" appears only when the advice fired, the client floor holds (monitor session, ≥ 8 hitches, ≥ 300 s),
  the rules' evidence is TRUE, the setting precondition holds and no other fix is being measured; each failing condition has a unit
  test, and the four player-visible ones show their one-line reason.
- **AC20.4** Pressing it opens PreviewScreen listing exactly one setting change in the right section; Cancel writes nothing; Apply
  makes one `apply` journal entry (vanilla applied now, Sodium/DH staged as one PATCH op) and one tracked record.
- **AC20.5** History's Undo this and the block's "Undo this change…" revert it; Discard pending cancels a staged one; the record
  then reads undone / not applied.
- **AC20.6** The comparison uses only hitches and lost time per minute; the verdict tests in §5.1 pass with the listed p-values;
  every verdict line shows both rates; "more" is shown with an Undo button.
- **AC20.7** A session counts only under the same conditions (§1.6); each differing condition has a test and a reason line; the
  fixed key changing elsewhere makes the record "replaced".
- **AC20.8** `stutter-fixes.json` follows the JsonStateFile/StateStore rules, ≤ 32 KiB, ≤ 10 records, unknown fields survive,
  a newer file disables offers without throwing.
- **AC20.9** A main-list recommendation that would reverse an active fix is unticked with the hold reason; not after the fix is undone.
- **AC20.10** StutterScreen with an offer and with a tracked block fits at 1280×720, 640×480 @2 and 854×480 @2 on all three CI
  legs (layout check + screenshots); every new control is a Tab stop with a narration naming the change; high contrast maps
  every new colour; LangCheckTest, WordingTest and PaletteTest pass.
- **AC20.11** `FrameHookBudgetTest` and `FootprintGameTest` pass against the unchanged `tools/footprint-budgets.json`; no new thread;
  no work at client init.
- **AC20.12** With RigTune's network switches off, the bundled rules offer the fixes and the whole flow works (game test main case).
- **AC20.13** After a downgrade to 0.4.0 with a staged and an applied fix, 0.4.0 starts, its helper applies the staged op, History
  shows both as Apply entries and Undo this works; back on 0.5 the records follow the journal.
- **AC20.14** On the dev PC (26.2), the Sodium fix is offered from real stutter, applied, restarted, compared and undone, with the
  numbers recorded in `docs/v0.5/verification/` (or the thresholds are recalibrated from the recorded numbers and the run repeated).
- **AC20.15** No fix entry can name a key outside the allowlist (updater and client both refuse it), so no fix touches a mod file
  in either launcher mode.

## 7. File ownership

Hotspots in **bold**.

| file | change |
|---|---|
| `src/main/java/.../core/stutter/FixSpec.java`, `FixGate.java`, `FixOffers.java`, `SessionOutcome.java`, `FixComparison.java`, `FixConditions.java`, `FixStore.java`, `FixTracker.java`, `FixHold.java` | new |
| `core/stutter/StutterFacts.java`, `StutterAnalyzer.java`, `StutterView.java` | changed (optional fields, old constructors kept) |
| `core/rules/RulesDocument.java`, `Condition.java`, `ConditionEvaluator.java` | changed (shared with the L2 "chunks loading" leftover: same stutter-key block) |
| `core/history/HistoryModel.java` | changed (fix label; cut 2nd; C02/C09 may touch it too) |
| `src/client/java/.../client/stutter/StutterFixService.java` | new |
| `client/stutter/StutterService.java` | changed: **hotspot with the SD-3/SD-4/R10-1 fixes** (`end()`, `loadSaved()`, `clear()`) |
| `client/stutter/StutterMonitor.java` (Capture), `StutterHooks.java` (game-test probe) | changed |
| **`client/RealController.java`** | 3 delegations, `FixHold` at `:335`, history label merge |
| **`client/ui/RigTuneController.java`** | 3 defaults |
| **`client/ui/StutterScreen.java`** | offer rows, tracked block, ButtonRow |
| `client/ui/HistoryScreen.java` | one branch at `:318` (cut 2nd) |
| **`src/main/resources/assets/rigtune/lang/en_us.json`** | ~50 `rigtune.stutter.fix.*` keys |
| **`rules/source/knowledge.json`**, `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/rules-v1.json` (revision only), `rules/REVIEW.md` | seeds, regenerated r17 |
| **`tools/update_rules.py`**, `tools/tests/test_update_rules.py` | section validator, new key (next to the L2 leftover's vocabulary edit) |
| `src/test/java/...` | the tests in §5.1; `SchemaConsistencyTest`, `WordingTest`, `StutterConditionTest`, `StutterAnalyzerTest` extended; pinned `v040/core/rules/` copy |
| `src/gametest/java/.../StutterFixGameTest.java`, **`src/gametest/resources/fabric.mod.json`** | new class + one entrypoint line |
| `src/test/resources/v050-written/stutter-fixes/` | fixtures |
| `docs/RULES_SCHEMA.md`, `docs/DESIGN.md`, `README.md`, `CHANGELOG.md`, the v0.5 SPEC | docs |

Not touched: `RigTuneScreen`, `ToolsScreen`, `Recommender`, `UndoPlanner`, `PreviewScreen` (used through its public `Confirm`),
`knowledge.json`'s existing entries, `build.yml`, `build.gradle` (the new game-test class needs no workflow edit; the matrix
comes from `tools/gametest_matrix.py`), `NoticePriority`, `PendingActions`, the helper.

## 8. Effort and risk

| part | agent-days |
|---|---|
| rules section, Java model, `FixSpec`, updater + Python tests, `SchemaConsistencyTest`, seeds, r17, legacy-parser test | 1.0 |
| core: `causeSpikes`, `SessionOutcome`, `FixGate`/`FixOffers`, `FixComparison`, `FixConditions`, `FixStore`, `FixTracker`, `FixHold` + unit tests | 1.5 |
| client: `StutterFixService`, `StutterService`/`Capture`/`StutterHooks` hooks, controller delegations, hold wiring | 1.0 |
| UI: StutterScreen rows and block, ButtonRow, Preview wiring, History label, ~50 strings, a11y | 0.75 |
| `StutterFixGameTest` on three legs, fixtures, footprint re-run | 0.75 |
| real run on the dev PC (+ one threshold recalibration round) | 0.5 |
| docs | 0.25 |
| **total** | **~5.75** (MVP after cuts 1-4 of §9: ~3.5) |

The brainstorm's 2.5 days assumed the gate existed. **Riskiest part:** real-world evidence. On the one real PC, AC5.8 never saw
chunk loading or building claim time with default settings, so whether a fixable advice fires with *strong* evidence in real
play is UNVERIFIED; the Sodium ZERO_FRAMES setup is the plausible way in, and the thresholds in §2.2 are guesses until that run.
Second: the comparison's power. With typical hitch counts, many honest comparisons will end "no clear change"; that is correct,
but it must be worded so it doesn't read as failure. Third: merge friction in `StutterService` with the SD/R10-1 fixes.

## 9. What to cut first

1. **The DH threads fix** (seed only two entries): correlational evidence, staged, DH isn't in CI, and the dev PC's DH census
   fired the advice but not the fix gate.
2. **The History "Stutter fix:" label** (`HistoryModel`/`HistoryScreen`): History still shows the setting row.
3. **The Copy-summary line** for a tracked fix.
4. **Multi-session accumulation**: compare against one after session of at least 5 minutes (weaker test power, same honesty rules).
5. **The main-list hold** reduced to a note on the offer row ("RigTune's main list may suggest changing it back; leave that
   unticked"): cheaper, but it leaves the ping-pong to the player.
6. Last resort: ship only the Sodium fix (the clearest mechanism and the one the real PC can likely induce); the RD fix is then
   CI-only work saved.

Never cut: the evidence gate and client floor, the ordinary Apply/journal/Undo path, the outcome-only comparison with the
dispersion guard, and the honest wording (numbers always shown, "more" never hidden).

## Open questions for the coordinator / spec agent

- Should the two advice-only rules (heap, System.gc()) get a "compare my next session" follow-up after the player changes the
  launcher setting or removes a mod? Cheap on top of this design (the record without an `entryId`), but it is new scope.
- Should a finished comparison raise a notice on the RigTune screen? Left out to avoid a `NoticePriority` edit next to C16's slot.
- C02 (First-time Apply trust flow) may add a confirmation on the first Apply; a stutter fix already goes through PreviewScreen,
  so C02 should treat that as the confirmation rather than stacking a second one.
- The L2 leftover (the "chunks loading" tag as a rules condition) edits the same updater vocabulary and `ConditionEvaluator` block;
  schedule it before or after C20, not in parallel.
