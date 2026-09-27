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
| task | commit | evidence |
|---|---|---|
| R1 `--revision`, r16 baseline, first fold at r17 | 894817cb | `RevisionPinTests` (9); `LegacyRulesParseTest`/`LegacyParserTest` relative counts; the fold: Moonrise has a Fabric 26.3 build (availability 26.3 + `moonrise-opt`), Fabulously Optimized 26.3 added controlify, debugify, skyboxify, yacl, zoomify (debugify's rule is now upstream in FO), zoomify triaged into `reviewIgnore` (a zoom key, not performance) |
| R2 L2 | 350f6216 | red: 3 Python + 6 Java failures (run log `r2-red.log`); green: `ChunksLoadingTagTests` (7), `ChunksLoadingSeedV1Tests`, `SchemaConsistencyTest.stutterVocabulariesMatchTheAttributor`, `ChunksLoadingSeedTest` (4) |
| R3 L4 | 74bc986d | red: `test_no_entry_level_wording`, `test_l4_changed_only_reasons`; green after the rewording; `RulesV1DifferentialTest` (15) and `check_rules_v1.py` untouched |
| R4 C20 rules side | e5d9ea47 | red: 28 Python validator tests + `test_the_c20_seeds` + 4 Java failures; green: `StutterFixesTests` (28), `test_the_c20_seeds`, `SchemaConsistencyTest.stutterFixValuesMatchShareKeys`, `StutterFixSeedsTest` (3) |
| R5 4i | 65ba0fd6 | the deciding test `OldClientWarningTest.theConditionReachesExactlyTheReleasedOldClients` passed before the rule existed (so it ships); red then green: the bundled-rule tests, `RulesV1DifferentialTest.rulesV1GainsOnlyTheOldClientWarning`, `test_the_old_client_warning`, the relative-count tests |
| R6 2T tool | 2d266d35 | `UpstreamDiffTests` (6); `python tools/rules_upstream_diff.py src/test/resources/rules/r16/rules-v2.json rules/rules-v2.json` lists exactly R1's fold |
| merge of feat/v0.5.0 (WS-L1 m1, WS-P2 A, WS-S2's early core) | 6ebc52ea, 76c3f2a4 | WS-S2's `FixSpecTest.noSectionNoFixes` assumed no bundled section (true until R): it now strips the section; new `StutterFixSeedsTest.theClientAcceptsEverySeed` (the client's `FixSpec.of` accepts all three seeds with the same targets); `MAX_FIX_STEP` tied to `FixSpec.MAX_STEP` |

Every regeneration ran `python tools/update_rules.py --revision 17` live (Modrinth/GitHub), then `check_rules_v1.py`, the
Python suite from the repository root (413 tests, 1 skipped) and `:26.2:test` over `core.*` and `v0*` in a build slot
(1569-1901 tests, 0 failures after each task's fix). rules-v1.json's content changed only for L4 (the four vanilla reasons)
and 4i (the warning), plus `revision`/`generatedAt`.

CI (every job, every leg green): 36321718784 (c526ca81: R1-R4 + feat/v0.5.0 with WS-S's early merge), 36324314978
(2d266d35: + R5, R6). Looked at (run 36324314978, `gametest-screenshots-26.2-OpenGL`): `0051_ui-main-1280x720-scale2`
(header "Rules r17 (bundled…)"; Warnings 1, the llvmpipe one: no old-client warning on 0.5.0-dev), `0135_stutter-1280x720-
scale2`, `0138_stutter-saved` (unchanged layout; no session there has enough data for advice).

## Deviations
- **`--revision`** is a new updater option (the plan fixed one R but no mechanism). It refuses an R below the files'
  revision even when nothing changed, so a stale R after main moves fails loudly.
- **The L2 seed has two conditions**, the tag share (>= 60 %) and the spike rate (>= 2 a minute): the tag share alone
  can't separate the A control's worst case from C1r (both 75 %). The re-runs are 52 s captures, under the enough-data
  gate, so their facts are checked through `StutterAdvisor.evaluate(rules, ctx)`; the A control (enough data) through the
  gated path.
- **`chunksLoading` is also accepted in a fix's `evidence`** (AC2S.2's "refuses it anywhere else" read as "outside the
  Stutter Doctor's sections": `stutterFixes` is 0.5-only, and every stutter key is allowed in a fix's evidence per SPEC 5).
- **The validator refuses `stutter-doctor` and `jvm-flags` next to `stutter-fix`** (0.5 would skip such a fix silently),
  and an INT key's `value` must be a JSON integer (a digit string is refused; `causeSpikesAtLeast` counts take digit
  strings like the share maps).
- **4i's text** ends "RigTune 0.5 does this for you; update RigTune itself in the launcher too." (lm §6's text plus the
  last clause: an old client's own "Update RigTune" item is a mod-file change too).
- **L4's DH radius reasons** keep the guide's audience as "lower-end PCs" (was "entry-level PCs") and say "on this PC's
  estimated tier" for RigTune's cap.
- **Other owners' tests adjusted** because the bundled rules changed: `StutterFixesModelTest.absentIsNull` (WS-K's) and
  `FixSpecTest.noSectionNoFixes` (WS-S2's) now strip the bundled section, `KnowledgeV2ScenarioTest` (stutterAdvice count
  6), `LegacyConditionFailClosedTest` (the count read from the file), `StutterSeedScenarioTest.theBundledSeeds` (the new
  id).
- **v040 pins added** (ConditionEvaluator, EvalContext, Truth, client/probe/ModScanner with one added import of the
  current `Probes`, a `Recommender` stub, the `V040Parser` helper); `v040/package-info.java` lists them.
- **tools/README.md** edited (the updater's own doc: `--revision`, the stutterFixes errors, the release-time weekly PR).

## Residuals, UNVERIFIED
- **C20's thresholds** are sf §2.2's UNVERIFIED starting values until WS-S2's AC5.14 run; the recalibration comes back
  as a follow-up regeneration at R (`--revision <R>`).
- **The A control predates the chunksLoading tag**: its share is unknown, so the test also checks 75 % and 100 %; no new
  still-control capture was recorded.
- **4i on real old clients** is shown by the pinned copies, not by running 0.2.0-0.4.0; a client whose RigTune version
  isn't a semantic version would evaluate the condition UNKNOWN (no warning; the safe direction).
- **AC2S.4, AC5.2's compat040 half** closes when WS-E's compat040 lands (Cross-workstream ACs); the pinned 0.4.0 parser
  half is here.
- **2T**: the first Monday bot PR (2026-09-28 03:00 UTC) is handled per "2T procedure" after this report; AC2T.2 closes at
  the last regeneration before the release PR.

## Docs (for the docs workstream)
- CHANGELOG [0.5.0], rules: "Rules revision 17 (the release revision): the Stutter Doctor suggests a shorter render
  distance or Sodium's Deferred chunk updates when most hitches happen while chunks are loading; setting reasons name
  the PC's estimated tier instead of 'entry-level' hardware; the rules for the Stutter Doctor's one-click fixes
  (`stutterFixes`); a warning for RigTune 0.1-0.4 players about launchers that manage their mods; Moonrise now counts as
  available for 26.3."
- README "Known limits": drop "the chunks loading tag isn't usable as a rules condition yet" (L2 is done).
- README "Known issues" (4i, AC4i.3): lm §6's text; add that RigTune 0.1-0.4 now show a warning with the same steps
  (from rules revision 17 on).
- DESIGN.md rules pipeline: one revision per release (`--revision`); the `stutterFixes` section is rules-v2 only and
  validated by the updater (docs/RULES_SCHEMA.md "stutterFixes"); `tools/rules_upstream_diff.py` for the weekly PR while a
  release is prepared.

## Footprint deltas
Against ws-k.md's per-leg baseline (run 36310249248), run 36324314978 (2d266d35):

| leg | renderThreadInitCpuMs | initCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference | v05RenderThreadResolve |
|---|---|---|---|---|---|---|
| 26.2 OpenGL | 103.3 (+21.1) | 56.0 (+14.7) | 36.1 (-0.3) | 210.4 (+74.9) | 1.569 (+0.088) | null |
| 26.3 OpenGL | 63.3 (-18.9) | 35.1 (-8.6) | 19.0 (-8.0) | 123.4 (-29.8) | 1.338 (-0.408) | null |
| 26.3 Vulkan | 115.0 (-5.0) | 56.2 (-0.6) | 40.0 (+0.1) | 203.4 (+2.7) | 1.567 (+0.032) | null |

WS-R changes no main or client code; the bundled rules grew by about 3 % (parsed off the render thread). The deltas
change sign between legs and stay inside the runner-to-runner spread ws-k.md records (renderThreadInitCpuMs 63.5-112.9,
workerCpuMs5s 132.8-214.1 on near-identical code); every value keeps its budget (150 / 141 / 300 / 2.05).

## AC table
| AC | status | evidence |
|---|---|---|
| AC2S.2 (updater accepts chunksLoading in stutterAdvice, refuses it elsewhere; tag vocabulary = Attributor.TAGS) | verified | `test_rules_v05.ChunksLoadingTagTests`; `SchemaConsistencyTest.stutterVocabulariesMatchTheAttributor`; CI 36324314978 python + java |
| AC2S.3 (the seed fires on C1r/C3r facts, not on the A control; v1 identical with and without it) | verified | `ChunksLoadingSeedTest` (recorded sessions in src/test/resources/stutter/l2-calibration); `ChunksLoadingSeedV1Tests` |
| AC2S.4 (0.4.0 parses R: stutterAdvice r16 + 1, advice r16 + 1, no section dropped) | pinned-parser half verified; compat040 half closes with WS-E | `v040.LegacyParserTest.theReleasedParserReadsRWithR16sCountsPlusTheAdditions` |
| AC2R.1 (only `reason` changes for L4; no "entry-level" in reason/text/title; ids unchanged; v1 checks untouched) | verified | `test_l4_changed_only_reasons` (7 in v2, 4 in v1, vs r16), `test_no_entry_level_wording`; `RulesV1DifferentialTest` and `check_rules_v1.py` green unchanged |
| AC5.1 (stutterFixes documented; a Python test per refusal; absent from rules-v1.json, whose content doesn't change for C20) | verified | RULES_SCHEMA.md "stutterFixes"; `StutterFixesTests` (28); `test_the_c20_seeds`; `test_the_section_never_reaches_rules_v1` |
| AC5.2 (0.4.0's and 0.3.0's parsers read R with r16's counts plus the 2S and 4i additions) | pinned halves verified; compat040 half closes with WS-E | `v040.LegacyParserTest`, `LegacyRulesParseTest.legacyParserReadsRWithR16sCountsPlusTheAdditions`, `LegacyParserTest.aStutterFixesSectionInAnyShapeChangesNothing` |
| AC5.15 (no fix can name a key outside the allowlist) | updater half verified; `FixSpec` half is WS-S2's | `StutterFixesTests.test_a_key_outside_the_allowlist`; `SchemaConsistencyTest.stutterFixKeysMatchTheUpdater` / `stutterFixValuesMatchShareKeys` |
| AC4i.1 (TRUE on 0.2.0/0.3.0/0.4.0 through their pinned code, FALSE on 0.5.0 and its pre-releases; ships only then) | verified; the warning ships | `OldClientWarningTest` (3), `v040.client.probe.ModScannerPinTest` |
| AC4i.2 (rules-v1 gains only this advice, nothing appliable or ticked; check_rules_v1 passes; minModVersion unchanged) | verified | `RulesV1DifferentialTest.rulesV1GainsOnlyTheOldClientWarning`; `check_rules_v1.py`; `test_the_old_client_warning` |
| AC4i.3 (README/body-0.5 known-issue text) | the docs workstream's | "Docs" above |
| AC2T.1 (PROGRESS lists each bot PR and canary run) | in progress | the r17 fold (R1) recorded here; the 2026-09-28 bot PR pending; PROGRESS line handed to the coordinator |
| AC2T.2 (R > main's revision; the branch carries the last bot PR's upstream changes) | tool verified; closes at the last regeneration before the release PR | `rules_upstream_diff.py` + `UpstreamDiffTests`; R = 17 > main's 16 today |
