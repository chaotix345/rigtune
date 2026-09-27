# WS-R: rules (v0.5)

SPEC items: 2S L2 (AC2S.2, AC2S.3, AC2S.4), 2R L4 (AC2R.1), C20's rules side (AC5.1, AC5.2, AC5.15's python half), 4i
(AC4i.1, AC4i.2), 2T (AC2T.1, AC2T.2 with the coordinator). Branch `feat/v05-rules` from `feat/v0.5.0` @ a7613410 (ws-ci +
WS-K). One rules owner: `rules/source/knowledge.json`, `tools/update_rules.py`, `tools/check_rules_v1.py` and every
regeneration. **R** = main's revision at release time + 1 (main is at r16, so r17 unless main moves; no test hard-codes 17).

## Decisions taken before the code (from reading the code)
- **One revision R across several regenerations.** `update_rules.py` today writes `max(old revisions) + 1` whenever the
  content changes, so L2, L4, C20, 4i and each 2T fold would each bump it (r17, r18, ...). New option `--revision N`:
  when the content changed, both files get exactly N; N below the files' current revision is refused (a revision never goes
  down); without the option nothing changes (the Monday bot keeps its behaviour). Every WS-R regeneration runs with
  `--revision <R>`. If a safety-relevant bot PR is merged into main (2T), R moves up and the next regeneration passes the
  new R.
- **r16 pinned as a test baseline** (`src/test/resources/rules/r16/rules-v2.json`, `rules-v1.json`, byte copies of
  main's files): the "same counts as r16 plus the additions" tests (AC2S.4 prep, AC5.2, LegacyRulesParseTest) and L4's
  "only `reason` changed" check (AC2R.1) compare R against it, so they hold at any R.
- **L2 seed condition** (calibration, P5C-F1's re-runs and AC5.8's A control, docs/v0.4/verification/stutter/):
  `{"stutterTaggedShareAtLeast": {"chunksLoading": 60}, "spikesPerMinuteAtLeast": 20}`.
  | run | spikes | chunksLoading | gameplay | spikes/min (x10) | seed |
  |---|---|---|---|---|---|
  | C1r | 12 | 9 (75 %) | 51.9 s | 138.7 | fires |
  | C3r | 14 | 11 (78.6 %) | 52.6 s | 159.7 | fires |
  | C1r2 | 8 | 7 (87.5 %) | 51.8 s | 92.7 | fires |
  | A (control) | 4 | 0 recorded (the run predates the tag: at most 3 of 4, 75 %, could have carried it) | 352.1 s | 6.8 | doesn't fire, even at 75 % |
  A tag share alone can't separate the control's worst case (3 of 4 world-entry spikes, 75 %) from C1r (75 %); the spike
  rate does (6.8 vs >= 92), so the seed needs both. 60 % keeps "most of the hitches" true with a 15-point margin below
  C1r. Caveat recorded in the test: the C runs have 52 s of gameplay, under the Stutter Doctor's 2-minute enough-data
  gate, so the calibration checks the seed's condition on their facts (`StutterAdvisor.evaluate(rules, ctx)`), and the
  gated path on the A control (enough data: 4 spikes, 5:52).
- **Where `chunksLoading` is allowed:** in `stutterTaggedShareAtLeast`, which the updater accepts only in the Stutter
  Doctor's sections (`stutterAdvice`, and from C20 `stutterFixes[].evidence`, which 0.5 alone reads). Refused in the main
  list, templates and every other place (the stutter keys' existing rule). See Deviations.
- **4i's reach test decides** whether the warning ships (no code-deciding real run: the pinned copies are the evidence).
  0.4.0's path is pinned like v030's: `git show v0.4.0:` copies of `ConditionEvaluator`, `EvalContext`, `Truth` and
  `client/probe/ModScanner` (package renamed), and a `Recommender` stub holding only `SUPPORTED_FEATURES` and `supported()`
  (the plan review K-L1 precedent of the v030 stub): 0.4.0's `Recommender.context` (`:125-141`) turns the scanned mods into
  the version map, `advice()` (`:448-462`) evaluates `when`; the test builds the context the same way. 0.2.0/0.3.0 use the
  existing v030 copies (core/rules byte-identical in both; their `Recommender.recommend` builds the map the same way,
  v0.2.0 `:82-89`, v0.3.0 `:90-97`). `ModScanner.skip` is identical in 0.2.0-0.4.0 (`git diff v0.2.0 v0.4.0` only adds
  `loadedIds()`), and no version filters `rigtune` out of the scanned list before `Recommender.recommend`
  (RealController v0.2.0 `:210/:260`, v0.3.0 `:221/:289`, v0.4.0 `:266/:335`). Released version strings:
  `0.2.0+mc26.2`, `0.3.0+mc26.2`/`+mc26.3`, `0.4.0+mc26.2`/`+mc26.3`; the branch builds `0.5.0-dev`.

## TDD task plan
Each task: the red test first, then the change, then (for knowledge edits) `python tools/update_rules.py --revision <R>`,
`python tools/check_rules_v1.py`, the Python suite and `./gradlew :26.2:test --tests <the rules tests>` in a build slot,
then a commit. Pushes batched, never during a streak.

| # | task | files | tests (red first) | ACs |
|---|---|---|---|---|
| R1 | `--revision` pin; r16 baseline resources; the relative-count parse tests; first live regeneration (2T fold of upstream at R) | `tools/update_rules.py`, `tools/tests/test_rules_v05.py` (new), `src/test/resources/rules/r16/*` (new), `LegacyRulesParseTest`, `v040/core/rules/LegacyParserTest`, generated rules | `RevisionPinTests` (pinned N written when changed; N = old allowed; N < old refused; unchanged = no write; bot path unchanged); `LegacyRulesParseTest.legacyParserReadsRWithR16sCountsPlusTheAdditions`, `LegacyParserTest.theReleasedParserReadsRWithR16sCountsPlusTheAdditions` (expected additions list, grows per task) | AC2T.1 (first fold), AC5.2/AC2S.4 prep |
| R2 | L2: `chunksLoading` in the tag vocabulary; the `stutter-chunks-loading-tag` seed | `update_rules.py`, knowledge.json, `SchemaConsistencyTest`, new `core/rules/ChunksLoadingSeedTest`, `StutterSeedScenarioTest.theBundledSeeds`, `test_generated_rules.py`, RULES_SCHEMA.md | Python `ChunksLoadingTagTests` (accepted in stutterAdvice; refused in main-list advice/settings/mods/templates); `SchemaConsistencyTest.stutterVocabulariesMatchTheAttributor` (tags = `Attributor.TAGS`, causes = `Attributor.CAUSES`); `ChunksLoadingSeedTest` (C1r, C3r, C1r2 facts: fires; A control as recorded and at its 75 % worst case: doesn't; the seed never in the main list; honest wording); Python `test_v1_identical_with_and_without_the_l2_seed` | AC2S.2, AC2S.3 |
| R3 | L4: the 7 `reason` strings | knowledge.json, `test_generated_rules.py` | `test_no_entry_level_wording` (no `reason`/`text`/`title` in knowledge, v1, v2 contains "entry-level"; ids unchanged); `test_l4_changed_only_reasons` (vs r16: same settings in the same order, only those 7 entries' `reason` differ; v1 likewise); `check_rules_v1.py`, `RulesV1DifferentialTest` untouched | AC2R.1 |
| R4 | C20 rules side: the `stutterFixes` validator, `causeSpikesAtLeast` only in `stutterFixes[].evidence`, the three seeds (DH last) | `update_rules.py`, knowledge.json, `SchemaConsistencyTest`, new `core/rules/StutterFixSeedsTest`, `test_generated_rules.py`, RULES_SCHEMA.md, tools/README.md | Python `StutterFixesTests`: one test per refusal (unknown field in the entry or `set`; a null; missing, duplicate and unknown `adviceId`; no `stutter-fix`; no `evidence`; a `jvm-` flag in `evidence`; `causeSpikesAtLeast` in `stutterAdvice`, main-list advice, a setting's `when`, a template; a key outside the allowlist; value and step together or neither; an enum value outside the list; an int value out of range or of the wrong type; step 0, non-integer, over 8, on an enum key; a negative step without `min`, a positive one without `max`; `min`/`max` with a value, out of range, not integers, min above max; `causeSpikesAtLeast` not a map, empty, an unknown cause, negative or not whole; the section not an array, an entry not an object); the seeds valid; the section absent from rules-v1.json and v1 identical with and without it. `SchemaConsistencyTest.stutterFixValuesMatchShareKeys`. `StutterFixSeedsTest` (bundled R: three entries in sf §2.2's order, DH last, each `adviceId` an existing stutterAdvice id, keys in `FixSpec.KEYS`, `requires` = [stutter-fix]) | AC5.1, AC5.15 (python), AC5.2 |
| R5 | 4i: the reach test, then the warning (only if it passes) | new pins `v040/core/rules/{ConditionEvaluator, EvalContext, Truth}`, `v040/core/recommend/Recommender` (stub), `v040/client/probe/ModScanner`, `v040/package-info.java`, new `core/rules/OldClientWarningTest`, knowledge.json, `RulesV1DifferentialTest`, `test_generated_rules.py` | `OldClientWarningTest`: the condition TRUE on 0.2.0/0.3.0 (v030 copies), 0.4.0 (v040 copies; `ModScanner.skip` keeps `rigtune`), FALSE on 0.5.0, `0.5.0-dev`, `-alpha.1`, `-beta.1`, `-rc.1`, 0.5.1, 1.0.0 (current code), and the whole main list for a 0.5 client identical with and without the rule; `RulesV1DifferentialTest.rulesV1GainsOnlyTheOldClientWarning` (the only advice rules-v1 gains over the baseline; no appliable or newly ticked change); `minModVersion` unchanged (python) | AC4i.1, AC4i.2 |
| R6 | 2T: the upstream diff tool; each Monday bot PR folded | new `tools/rules_upstream_diff.py`, `tools/tests/test_rules_v05.py`, generated rules, knowledge.json (`reviewIgnore` or rules from REVIEW.md (a)) | `UpstreamDiffTests` (equal generated data passes; a changed availability, `upstream` list or a mod's `upstream` flag is reported; `revision`, `generatedAt` and knowledge-derived fields ignored) | AC2T.1 (record), AC2T.2 (tool; closed by the last regeneration before the release PR) |

## 2T procedure (each Monday while WS-R runs; first 2026-09-28 03:00 UTC)
1. `gh pr list --search "Rules update" --state open`; read the bot PR's REVIEW.md and its generated files.
2. On `feat/v05-rules` (later a follow-up branch from `feat/v0.5.0`): `python tools/update_rules.py --revision <R>`;
   triage REVIEW.md (a)-(c) into knowledge.json (`reviewIgnore` with a reason, or a rule); regenerate; check with
   `python tools/rules_upstream_diff.py <bot rules-v2.json> rules/rules-v2.json` that every upstream change is carried.
3. Ask the coordinator to close the bot PR with a comment linking `feat/v0.5.0` (never merged into main during v0.5
   unless safety-relevant; then main is merged into `feat/v0.5.0` first and R moves up). Record it below and hand the
   PROGRESS line to the coordinator (AC2T.1).

## Progress
(filled as tasks land)

## Deviations
(filled as tasks land)

## Residuals, UNVERIFIED
(filled as tasks land)

## Docs (for the docs workstream)
(filled as tasks land)

## Footprint deltas
WS-R changes no client or main code (rules data, the updater, tests), so none is expected; the CI legs' footprint JSON is
compared with ws-k.md's baseline at the end.

## AC table
(filled at the end)
