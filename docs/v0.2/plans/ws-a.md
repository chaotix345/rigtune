# WS-A: rules schema v2 — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:test-driven-development for every task, superpowers:verification-before-completion before reporting. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 0.2 clients read `rules-v2.json` (new condition fields, `requires`, `settingLabels`, `avoidSelected`) with fail-closed, three-valued evaluation, while `rules-v1.json` stays a safe per-field projection for 0.1.x.

**Architecture:** Parsing records unknown condition keys (Gson TypeAdapterFactory). `ConditionEvaluator` evaluates to TRUE/FALSE/UNKNOWN; only TRUE fires. Loading moves into a testable core class (`RulesSources`); RealController only wires it and gates the remote fetch. The Python updater writes rules-v2.json (+ bundled copy) and the v1 projection from one knowledge.json; `check_rules_v1.py` and a pinned-v0.1.0 differential JUnit test guard the projection.

**Tech Stack:** Java 25, Gson, Fabric Loader 0.19.5 (`Version`, `VersionPredicate`, `SemanticVersion`), JUnit 5, JDK HttpServer; Python 3.11 stdlib; GitHub Actions.

**Spec:** docs/v0.2/SPEC.md item 2 + "Amendments from the plan review" (item 2); docs/v0.2/plan-review.md H1, H2, H3, M1, L1, M14; docs/research/v0.2/triage.md §5.

## Global Constraints
- Worktree `C:/Dev/Worktrees/rigtune-rules`, branch `feat/rules-v2`; rebase onto `origin/feat/v0.2.0` at the end; never commit to `main`/`feat/v0.2.0`.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for 26.2 AND 26.3. Stonecutter active version stays 26.2; never commit while switched.
- `core/` has no Minecraft imports (Fabric Loader's version API is allowed; the helper never reaches core/rules).
- rules-v1.json must stay safe for 0.1.x; 0.1.0's parser must accept it. 0.2 never writes `rules-cache.json`.
- No request at all when `ClientSettings.shared(configDir).remoteRulesAllowed()` is false.
- Always rerun `./gradlew build` after regenerating rules. Regenerating needs network (Modrinth, GitHub).
- Only WS-A edits RULES_SCHEMA.md. Don't edit SPEC.md, PLAN.md, PROGRESS.md.
- No Python string literals with Windows backslashes. Scratch: `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/097c9765-76fe-415d-a5ae-7debe28cb5de/scratchpad/ws-a/`.
- Commit messages end with the two trailer lines (Co-Authored-By, Claude-Session) from PLAN.md.
- Baseline (before WS-A): 238 JUnit tests per MC version, 61 Python tests.

## Decisions (argued from the spec; recorded again in docs/v0.2/design/ws-a.md)
1. **Unknown keys poison the whole tree; values use Kleene logic.** SPEC item 2 says an unknown key anywhere makes the whole top-level condition false (AC2.2, PLAN task 1 tests), and H1 adds three-valued evaluation. Both are kept: any unknown key (or explicit JSON `null` value) anywhere → the top-level result is UNKNOWN. Undecidable *values* (no GPU info, bad regex, exhausted budget, unknown display/RAM/VRAM/refresh, unparseable version or predicate, a value outside a field's vocabulary) are UNKNOWN at their node and combine by Kleene rules: `not U = U`; `anyOf` = TRUE if any branch TRUE, else U if any U, else FALSE; AND = FALSE if any FALSE, else U if any U, else TRUE. Only a top-level TRUE fires. (Pure Kleene would let a TRUE `anyOf` sibling override an unknown *key*, which AC2.2 forbids and which is unsound for a future key that isn't a plain AND-ed predicate.)
2. **Additions need a definitely-FALSE `avoidWhen`.** In `additions()` an UNKNOWN `avoidWhen` blocks the add (fail closed = no recommendation). `avoided()` still fires only on TRUE.
3. **Vocabularies:** `gpuVendor` = lowercase GpuVendor names (nvidia, amd, intel, apple, qualcomm, software, other, unknown); `backend` = opengl, vulkan; `os` = an entry that is a non-empty prefix of windows, macos or linux; `goal` = performance, balanced, quality; `flags` = backend-vulkan, shaders-enabled, `sodium-workaround:<NAME>`. The same sets are 0.1.0's vocabularies (0.2 adds none).
4. **Per-field projection (H2)**, in the updater:
   - `"v1": false` → the rule is omitted from rules-v1.json.
   - `"v1": {…}` → shallow-merged over the rule for v1 only. A `null` anywhere in it is an error, it may only set v1 fields, and its conditions must be v1-clean.
   - ModRule `recommendWhen` that isn't v1-clean → `{"always": false}`. Advice `when` that isn't v1-clean → `{"always": false}`.
   - ModRule `avoidWhen` that isn't v1-clean → dropped (with `avoidReason`), but ONLY if the v1 `recommendWhen` is `{"always": false}`; otherwise the updater errors. Dropping it would otherwise let 0.1.x recommend adding the mod where 0.2 avoids it (H2 only considered the disable effect, not the add-blocking effect).
   - A setting entry with a non-v1-clean `when` or a key outside `vanilla.`/`sodium.` needs an explicit `v1` (error otherwise).
   - Rule fields outside the per-type v1 whitelist need an explicit `v1`. With an override, a field is dropped only if dropping it is exactly as safe: `requires` only when empty; `avoidSelected` only when true or when the v1 rule has no `avoidWhen`. Otherwise it's an error (use `"v1": false`).
   - A field unknown to v2 as well (a typo), an unknown condition key, a `null` in a condition, or a value outside the vocabularies is a knowledge error.
   - "v1-clean condition" = keys in the v0.1.0 set (recursively) and enumerated values inside the v0.1.0 vocabularies.
   - Tier rules are copied as they are; unknown fields in them are errors (L1).
   - Every omission and field change is listed in REVIEW.md section (d).
5. **Differential test "more conservative"** = for every matrix point, the set of ticked, actionable recommendations under the new rules-v1.json (id + target value) is a subset of the baseline's. The baseline is `src/test/resources/v010/rules-v1-baseline.json` (= rules-v1.json at tag v0.1.0). Availability is neutralised (online data marks every slug available). If a maintainer deliberately adds a ticked v1 recommendation, they review the diff and replace the baseline (documented in tools/README.md).
6. **One revision for both outputs:** the new revision = max(old v1, old v2 revision) + 1, bumped when either output's content changes; both files get the same `generatedAt`.
7. **Remote v2 is cached only if its schemaVersion is 2**, so the v2 cache always holds a v2 document. Remote v1 is in memory only.
8. `RealController.settingsChanged()` reloads the rules (a new generation; a stale load can't overwrite a newer one) and rescans.

## File structure
- Create `src/main/java/io/github/chaotix345/rigtune/core/rules/ConditionAdapterFactory.java`: records unknown/null keys into `Condition.unknownFields`.
- Create `core/rules/Truth.java`: TRUE/FALSE/UNKNOWN with `and`, `or`, `not`.
- Modify `core/rules/ConditionEvaluator.java`: `evaluate` (three-valued) + `matches`; the new fields; vocabularies.
- Modify `core/rules/EvalContext.java`: `modVersions` component + 5-arg constructor.
- Modify `core/rules/Condition.java`: transient compiled-regex cache for `gpuModelMatches`.
- Modify `core/rules/RulesDocument.java`: `ModRule.avoidSelected`.
- Modify `core/rules/RulesLoader.java`: Gson with the factory; schemaVersion 1–2; bundled `rules-v2.json`; tie-breaks.
- Modify `core/rules/RemoteRulesFetcher.java`: no default URI; cache only schemaVersion 2.
- Create `core/rules/RulesSources.java`: candidates, remote v2 → v1 fallback, base URL, gating.
- Modify `core/hardware/GpuClassifier.java`: extract `public static String subject(GpuInfo)` (one method, used by both).
- Modify `core/recommend/Recommender.java`: `requires`, `avoidSelected`, labels, mod versions, UNKNOWN avoid blocks adds.
- Modify `core/recommend/SettingValues.java`: `describe(label, key, current, target)` for titles.
- Modify `client/RealController.java`: `loadRules()` via RulesSources + gating; `settingsChanged()`.
- Modify `tools/update_rules.py`; create `tools/check_rules_v1.py`; tests in `tools/tests/test_update_rules.py` + new `tools/tests/test_projection.py`, `tools/tests/test_check_rules_v1.py`.
- Generated: `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/rules-v1.json`, `rules/REVIEW.md`. Delete `src/main/resources/rigtune/rules-v1.json`.
- Modify `.github/workflows/build.yml` (rules-consistency, rules-v1-compat) and `.github/workflows/update-rules.yml` (git add the new outputs).
- Tests (Java): `core/rules/ConditionParsingTest`, `ConditionEvaluatorTest` (extended), `ThreeValuedEvaluationTest`, `NewConditionFieldsTest`, `RulesLoaderTest` (extended), `RulesSourcesTest`, `RepositoryRulesTest`, `RulesV1DifferentialTest`; `core/recommend/RecommenderV2Test`; test helper `core/RepoFiles`.
- Pinned v0.1.0 copy: `src/test/java/io/github/chaotix345/rigtune/v010/**` (package rename only) + `src/test/resources/v010/rules-v1-baseline.json`.
- Docs: `docs/RULES_SCHEMA.md`, `tools/README.md`, one line in README.md's layout block, `docs/v0.2/design/ws-a.md`.

---

### Task 1: Parse conditions with unknown-key tracking; accept schemaVersion 1 and 2

**Files:** create `core/rules/ConditionAdapterFactory.java`; modify `core/rules/RulesLoader.java`; test `src/test/java/io/github/chaotix345/rigtune/core/rules/ConditionParsingTest.java`, `RulesLoaderTest.java`.

**Produces:** `RulesLoader.parse(String)` accepts schemaVersion 1 and 2 (`RulesLoader.MIN_SCHEMA_VERSION = 1`, `SCHEMA_VERSION = 2`); package-private `RulesLoader.condition(String json)` for tests; every parsed `Condition` has a non-null `unknownFields` (empty when clean).

- [ ] Step 1: failing tests in ConditionParsingTest:
  - `unknownTopLevelKeyIsRecorded`: `{"tierAtLeast":3,"meshShaders":true}` → unknownFields == {"meshShaders"}, tierAtLeast == 3.
  - `unknownKeyInsideNotIsRecordedOnTheNestedNode`: `{"not":{"futureKey":1}}` → root unknownFields empty, `not.unknownFields` == {"futureKey"}.
  - `unknownKeyInsideAnyOfIsRecorded`: `{"anyOf":[{"always":true},{"x":1}]}` → anyOf[1].unknownFields == {"x"}.
  - `explicitNullValueCountsAsUnknown`: `{"gpuModelMatches":null}` → unknownFields == {"gpuModelMatches"}.
  - `v2FieldsParse`: gpuModelMatches, displayPixelsAtLeast/AtMost, modVersion map, mcVersionRange all set; unknownFields empty.
  - `ruleConditionsInADocumentAreTracked`: a full v2 doc with an unknown key in a mod's `avoidWhen.anyOf[0].not` → recorded.
  - RulesLoaderTest: `acceptsSchemaVersions1And2` and `rejectsOtherSchemaVersions` (0, 3, missing); replace the old `{"schemaVersion":2}` rejection line.
- [ ] Step 2: `./gradlew :26.2:test --tests '*ConditionParsingTest' --tests '*RulesLoaderTest'` → FAIL (no factory; schema 2 rejected).
- [ ] Step 3: implement:
```java
final class ConditionAdapterFactory implements TypeAdapterFactory {
	static final Set<String> KNOWN_KEYS = Arrays.stream(Condition.class.getFields())
			.filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
			.map(Field::getName).collect(Collectors.toUnmodifiableSet());

	@Override
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
		if (type.getRawType() != Condition.class) return null;
		TypeAdapter<Condition> delegate = gson.getDelegateAdapter(this, TypeToken.get(Condition.class));
		TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
		// read: JsonElement tree → unknown = keys not in KNOWN_KEYS or with a JSON null value → delegate.fromJsonTree(tree)
		//       → c.unknownFields = Set.copyOf(unknown); JSON null → null condition; non-object → JsonParseException
	}
}
```
  RulesLoader: `GSON = new GsonBuilder().registerTypeAdapterFactory(new ConditionAdapterFactory()).create()`; accept `MIN_SCHEMA_VERSION <= schemaVersion <= SCHEMA_VERSION`.
- [ ] Step 4: tests pass; `./gradlew :26.2:test` all green (ConditionEvaluatorTest still uses a plain Gson; switched in Task 2).
- [ ] Step 5: commit `feat(rules): track unknown condition keys; accept schemaVersion 2`.

### Task 2: Three-valued evaluation, vocabularies, the v2 condition fields, mod versions in EvalContext

**Files:** create `core/rules/Truth.java`; modify `ConditionEvaluator.java`, `EvalContext.java`, `Condition.java` (transient pattern cache), `core/hardware/GpuClassifier.java` (`subject`); tests `ConditionEvaluatorTest` (switch to `RulesLoader.condition`), new `ThreeValuedEvaluationTest`, `NewConditionFieldsTest`.

**Produces:**
```java
public enum Truth { TRUE, FALSE, UNKNOWN; Truth and(Truth o); Truth or(Truth o); Truth not(); }
public static Truth ConditionEvaluator.evaluate(@Nullable Condition c, EvalContext ctx); // null → TRUE; poisoned tree → UNKNOWN
public static boolean ConditionEvaluator.matches(@Nullable Condition c, EvalContext ctx); // evaluate(...) == TRUE
public record EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds, Map<String, String> modVersions) // + 5-arg ctor (modVersions = Map.of())
public static String GpuClassifier.subject(GpuInfo gpu); // renderer, or the vendor string when the renderer is blank; "" when neither
```
Field semantics (per node, ANDed): tiers/battery/heap/mcVersion/modPresent/modAbsent as v1 (heap ≤ 0 → UNKNOWN); `gpuVendor` (vendor UNKNOWN → UNKNOWN unless listed); `gpuIntegrated` (no GPU info → UNKNOWN); ram/vram/refresh unknown → UNKNOWN; `backend` (null/UNKNOWN backend → UNKNOWN); `os` (blank OS → UNKNOWN); `flags` (per entry: present TRUE, known-but-absent FALSE, out-of-vocabulary UNKNOWN; ANDed); list fields: a known entry that matches → TRUE, else out-of-vocabulary entries → UNKNOWN, else FALSE; `gpuModelMatches` (blank subject, >200 chars, invalid, budget exhausted → UNKNOWN; `BudgetedChars.find` with DEFAULT_BUDGET); `displayPixelsAtLeast/AtMost` (display null or w/h ≤ 0 → UNKNOWN; compares `(long) w * h`); `modVersion` (per entry: blank/unparseable predicate → UNKNOWN; mod not loaded → FALSE; version missing, unparseable or not a `SemanticVersion` → UNKNOWN; else `VersionPredicate.test`); `mcVersionRange` (same parsing rules on `hardware.mcVersion()`).

- [ ] Step 1: failing tests.
  - ThreeValuedEvaluationTest: `unknownKeyAtTopLevelIsUnknown`, `unknownKeyInsideNotIsUnknown` (`not {futureKey}` must not be TRUE), `unknownKeyInsideAnyOfPoisonsEvenWithATrueSibling`, `deeplyNestedUnknownKeyPoisons`, `notOfUnknownValueIsUnknown` (`not {gpuModelMatches:"rtx"}` with no GPU info), `unknownFlagInsideNotIsUnknown` (`not {flags:["mesh-shaders"]}`), `anyOfIsTrueIfABranchIsTrueDespiteUnknownValues`, `anyOfWithFalseAndUnknownIsUnknown`, `emptyAnyOfIsFalse`, `andIsFalseWhenAnyPartIsFalseEvenIfAnotherIsUnknown` (`{tierAtLeast:5, ramMbAtLeast:1}` with RAM unknown → FALSE; under `not` → TRUE), `unknownRamVramRefreshAreUnknown`, `noGpuInfoMakesVendorAndIntegratedUnknown`, `outOfVocabularyValuesAreUnknown` (gpuVendor "matrox", backend "metal", os "freebsd", goal "extreme"), `knownMatchingEntryWinsOverOutOfVocabularyEntry` (`gpuVendor:["amd","matrox"]` on AMD → TRUE), `sodiumWorkaroundFlagsAreInVocabulary`, `matchesIsTrueOnlyForTrue`.
  - NewConditionFieldsTest: `nvidiumRegexMatchesMeshShaderCards` and `nvidiumRegexRejectsOlderAndOtherCards` (the exact triage.md §5 regex and both string lists, via a GpuInfo renderer, also with "/PCIe/SSE2" suffixes), `gpuModelMatchesUsesVendorStringWhenRendererBlank`, `gpuModelMatchesInvalidOverlongOrBudgetExhaustedIsUnknown` (`"(?i)[unclosed"`, 201 chars, `((a+)+)+b` vs 40 a's), `displayPixelsBounds` (2560×1440 = 3686400 inclusive at both bounds), `displayPixelsUnknownSizeIsUnknown` (-1 width, 0 height, null display), `modVersionMatchesInstalledVersion` (`sodium: ">=0.6.0 <0.8.0"` with 0.7.1 TRUE, 0.9.2 FALSE, `"~0.9"` with 0.9.2 TRUE), `modVersionNotLoadedIsFalse`, `modVersionUnparseablePredicateOrVersionIsUnknown` (predicate `">=>="`, version "abc", missing version), `mcVersionRange` (`">=26.3"` FALSE on 26.2, `"~26.2"` TRUE, `">=26.2 <26.4"` TRUE, `"not a range"` → UNKNOWN; mcVersion "unknown" → UNKNOWN).
  - ConditionEvaluatorTest: parse with `RulesLoader.condition(json)`; existing assertions stay.
- [ ] Step 2: run → FAIL (no Truth/evaluate/new fields).
- [ ] Step 3: implement Truth, evaluator, EvalContext secondary ctor, GpuClassifier.subject (classify uses it).
- [ ] Step 4: `./gradlew :26.2:test` green.
- [ ] Step 5: commit `feat(rules): three-valued condition evaluation and the v2 condition fields`.

### Task 3: Recommender: `requires`, `avoidSelected` (H3), labels, mod versions (M14), UNKNOWN avoid blocks adds

**Files:** modify `core/rules/RulesDocument.java` (`public Boolean avoidSelected;` on ModRule), `core/recommend/Recommender.java`, `core/recommend/SettingValues.java`; test `core/recommend/RecommenderV2Test.java`.

**Produces:** `Recommender.SUPPORTED_FEATURES = Set.of()` (public); `static boolean supported(List<String> requires)`; titles `"<label name or SettingValues.label(key)>: <value label> → <value label>"`.

- [ ] Step 1: failing tests (schemaVersion 2 docs built like RecommenderTest's `rules(...)`):
  - `ruleRequiringAnUnknownFeatureIsSkippedForEveryKind` (mod add, avoided disable, conflict, obsolete disable, setting value, setting clamp, advice — each with `"requires":["future"]` → absent; the same rules without `requires` → present).
  - `emptyRequiresIsAllowed`.
  - `avoidSelectedFalseGivesAnUntickedDisable`; `avoidSelectedDefaultsToTicked`.
  - `poisonedAvoidWhenDisablesNothing` (unknown key in `avoidWhen.not`).
  - `unknownAvoidWhenBlocksTheAddition` (avoidWhen `not {gpuModelMatches}` with no GPU info; recommendWhen always → no `add:`).
  - `poisonedSettingEntryIsSkipped` (value entry and clamp entry), `poisonedAdviceIsNotShown`, `poisonedRecommendWhenDoesNotAdd`.
  - `labelsAreUsedInSettingTitles` (`settingLabels: {"sodium.performance.chunk_builder_threads": {"name":"Chunk builder threads","values":{"0":"Auto"}}}` → title `Chunk builder threads: Auto → 4`; the Action keeps raw "0"/"4").
  - `missingLabelFallsBackToTheCaption`.
  - `modVersionConditionSeesInstalledVersions` (an advice with `modVersion {"sodium":">=0.9"}`; installed sodium "0.9.2" → shown; "0.8.0" → not).
- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement: Session filters `mods/obsolete/settings/advice` by `supported(requires)` once and uses the filtered lists everywhere (incl. `bySlug`, `refKey`); `disable(..., boolean selected)`; `additions()` skips when `avoidWhen != null && evaluate(avoidWhen) != Truth.FALSE`; `recommend(...)` builds `modVersions` from installed mods (first non-null version per id); `SettingValues.describe(SettingLabel label, String key, String current, String target)`.
- [ ] Step 4: full `./gradlew :26.2:test` green (scenario tests on the bundled v1 rules must not change).
- [ ] Step 5: commit `feat(recommend): requires, avoidSelected, setting labels, mod versions`.

### Task 4: Updater: v2 output, per-field v1 projection, whitelists, errors, one revision, REVIEW (d)

**Files:** modify `tools/update_rules.py`, `tools/tests/test_update_rules.py`; create `tools/tests/test_projection.py`.

**Produces (Python, module `update_rules`):**
```python
V1_CONDITION_KEYS, V2_CONDITION_KEYS, V1_VOCABULARIES, V2_VOCABULARIES, FLAG_PREFIXES
V1_RULE_FIELDS: dict[kind, set]   # kinds: mods, obsolete, settings, advice, gpuTiers, cpuTiers, heapTiers
V2_ONLY_RULE_FIELDS: dict[kind, set]  # mods: requires, avoidSelected; obsolete/settings/advice: requires
V1_SETTING_PREFIXES = ("vanilla.", "sodium.")
validate_knowledge(knowledge) -> None            # raises KnowledgeError
condition_problems(cond, keys, vocab, path) -> list[str]
is_v1_condition(cond) -> bool
rule_label(kind, rule) -> str                    # "mods[nvidium]", "settings[3] vanilla.renderDistance", "advice[ram-low]", "obsolete[Indium]"
project_rule(kind, rule) -> tuple[dict | None, list[str]]   # raises ProjectionError(UpdateRulesError)
assemble_content(knowledge, mods, availability, upstream) -> dict   # schemaVersion 2, keeps rule "v1" keys, adds settingLabels when present
v2_content(content) -> dict                      # rule "v1" keys removed
v1_projection(content) -> tuple[dict, list[tuple[str, str]]]   # schemaVersion 1, no settingLabels; (label, note) list
finalize_documents(pairs: list[tuple[content, old_doc]]) -> list[dict] | None   # one revision = max(old revisions)+1
finalize_document(content, old_doc) -> dict | None   # kept: finalize_documents([(content, old_doc)])
render_review_markdown(..., projection_notes=None)    # adds "## (d) Omitted from rules-v1.json"
```
main(): reads old rules-v2.json and rules-v1.json, writes `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/rules-v1.json` (all only when changed), always writes REVIEW.md. Never writes a bundled v1.

- [ ] Step 1: failing tests in test_projection.py:
  - `test_v1_clean_rule_is_copied_unchanged` (each kind).
  - `test_v1_false_omits_rule_and_notes_it`.
  - `test_v1_override_is_shallow_merged_and_never_written` (v1 key absent from both outputs; v2 keeps the original fields).
  - `test_null_in_v1_override_is_an_error` (top level and nested inside a condition).
  - `test_override_may_only_set_v1_fields`, `test_override_conditions_must_be_v1_clean`.
  - `test_v2_only_recommend_when_becomes_never` (conflictsWith/modIds kept).
  - `test_v2_only_advice_when_becomes_never`.
  - `test_v2_only_avoid_when_dropped_when_never_recommended` (avoidReason dropped too).
  - `test_v2_only_avoid_when_with_possible_recommendation_is_an_error` (including a missing recommendWhen).
  - `test_v2_detection_is_recursive` (gpuModelMatches inside `not` inside `anyOf`).
  - `test_out_of_vocabulary_value_is_not_v1_clean` (projected like a v2 condition for v1; an error in validation only if outside the v2 vocabulary too — they're equal now, so: validation error).
  - `test_setting_with_v2_when_needs_explicit_v1`, `test_setting_with_non_v1_namespace_needs_explicit_v1`, `test_setting_with_v1_false_is_omitted`, `test_setting_with_v1_override_when`.
  - `test_requires_needs_v1_false` (no v1 → error; override → error; v1:false → omitted; empty requires + override → dropped).
  - `test_avoid_selected_rules` (false + no v1 → error; false + v1:false → omitted; false + override keeping avoidWhen → error; true + override → dropped).
  - `test_unknown_rule_field_is_a_knowledge_error`, `test_unknown_condition_key_is_a_knowledge_error`, `test_null_in_condition_is_a_knowledge_error`, `test_tier_rule_unknown_field_is_an_error`.
  - `test_setting_labels_in_v2_not_v1`.
  - `test_both_outputs_share_one_revision` (bump when only v2 changes; unchanged → None; revision = max+1; same generatedAt).
  - `test_review_section_d_lists_omissions_and_changes` and `test_review_section_d_none`.
  - `test_main_writes_v2_bundled_v2_and_v1_only` (offline fixtures dir built in the test; asserts no bundled rules-v1.json written).
  - test_update_rules.py: update `assemble_content` expectations (schemaVersion 2) and the end-to-end pipeline test.
- [ ] Step 2: `python -m unittest discover -s tools/tests -v` → FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: all Python tests pass.
- [ ] Step 5: commit `feat(tools): rules v2 output and the per-field v1 projection`.

### Task 5: `check_rules_v1.py` + CI jobs

**Files:** create `tools/check_rules_v1.py`, `tools/tests/test_check_rules_v1.py`; modify `.github/workflows/build.yml`, `.github/workflows/update-rules.yml`.

**Produces:** `check(repo_root: Path) -> list[str]` (problems) and `main(argv) -> int` (0 ok, 1 problems). Checks rules-v1.json: schemaVersion 1; top-level and per-rule fields within the v1 whitelists (so no requires/settingLabels/v1/avoidSelected); condition keys v1 (recursive); enumerated values within the v0.1.0 vocabularies; no nulls; setting keys `vanilla.`/`sodium.`; and equality with the projection rebuilt offline from knowledge.json + rules-v2.json's generated parts (mods[].upstream, availability, upstream, revision, generatedAt), plus rules-v2.json == v2_content of the same rebuild.

- [ ] Step 1: failing tests: `test_consistent_pair_passes`, `test_v2_condition_key_in_v1_fails`, `test_requires_in_v1_fails`, `test_setting_labels_in_v1_fails`, `test_unknown_flag_in_v1_fails`, `test_dh_key_in_v1_fails`, `test_hand_edited_v1_fails_the_projection_check`, `test_stale_v2_fails` (knowledge edited without regenerating), `test_wrong_schema_version_fails`.
- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement (imports `update_rules` from the same folder).
- [ ] Step 4: pass. CI: `rules-consistency` → `cmp rules/rules-v2.json src/main/resources/rigtune/rules-v2.json` and fail if `src/main/resources/rigtune/rules-v1.json` exists; new `rules-v1-compat` job (setup-python 3.11, `python tools/check_rules_v1.py`). update-rules.yml `git add`s rules/rules-v1.json, rules/rules-v2.json, src/main/resources/rigtune/rules-v2.json, rules/REVIEW.md.
- [ ] Step 5: commit `feat(tools): check_rules_v1.py and the rules-v1-compat CI job`.

### Task 6: Generate rules-v2.json; bundle v2 only; repository rules tests

**Files:** generated `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/rules-v1.json`, `rules/REVIEW.md`; delete `src/main/resources/rigtune/rules-v1.json`; modify `RulesLoader.BUNDLED_RESOURCE = "/rigtune/rules-v2.json"`; tests: create `src/test/java/io/github/chaotix345/rigtune/core/RepoFiles.java` and `core/rules/RepositoryRulesTest.java`; update `RulesLoaderTest.bundledRulesAreComplete` (schemaVersion 2) and remove the vacuous `bundledCopiesMatchRepositoryRules` (its relative path never resolved from build/junit-run).

**Produces:** `RepoFiles.root()` (walks up from the working directory to the folder that has `rules/source/knowledge.json` and `settings.gradle`; fails the test if not found).

- [ ] Step 1: failing RepositoryRulesTest: `repoRulesV1AndV2Parse` (schemaVersion 1 and 2, same revision), `everySettingKeyIsAllowlisted` (both files, `SettingKeys.changeable`), `bundledV2EqualsRepoV2` (LF-normalised), `noBundledV1` (`getResource("/rigtune/rules-v1.json") == null`), `v1HasNoV2Fields` (every parsed condition has empty unknownFields and no v2 fields set; no requires/avoidSelected; settingLabels empty).
- [ ] Step 2: run → FAIL (files missing).
- [ ] Step 3: `python tools/update_rules.py` (network; knowledge unchanged); `git rm src/main/resources/rigtune/rules-v1.json`; switch BUNDLED_RESOURCE; `python tools/check_rules_v1.py`.
- [ ] Step 4: `./gradlew build` (both versions) green; Python tests green.
- [ ] Step 5: commit `feat(rules): generate rules-v2.json; bundle v2 only`.

### Task 7: Loading: bundled v2, v2 cache, legacy v1 cache, remote v2 → v1, tie-breaks, gating, settingsChanged (M14)

**Files:** create `core/rules/RulesSources.java`; modify `RulesLoader.pickNewest`, `RemoteRulesFetcher.java`, `client/RealController.java`; tests `core/rules/RulesSourcesTest.java`, `RulesLoaderTest.java`, `RemoteRulesFetcherTest.java`.

**Produces:**
```java
public final class RulesSources {
	public static final String BASE_URL_PROPERTY = "rigtune.rules.baseUrl";
	public static final URI DEFAULT_BASE_URL = URI.create("https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/");
	public static final String V2_FILE = "rules-v2.json", V1_FILE = "rules-v1.json";
	public static final String V2_CACHE = "rules-v2-cache.json", LEGACY_CACHE = "rules-cache.json";
	public interface Listener { void loaded(RulesDocument rules, boolean remote); }
	public RulesSources(Path configDir, URI baseUrl, String modVersion);
	public static URI baseUrl(@Nullable String override); // http(s) only, trailing slash added; else default
	public List<RulesLoader.Candidate> local();            // bundled v2, config/rigtune/rules-v2-cache.json, config/rigtune/rules-cache.json
	public Optional<RulesLoader.Candidate> remote();       // remote v2 (cached if schemaVersion 2), else remote v1 (not cached)
	public void load(boolean remoteAllowed, Listener listener); // publishes the local best, then (if allowed) a better remote pick
}
```
`RulesLoader.pickNewest`: highest revision; tie → higher schemaVersion; tie → source rank remote > cache > bundled. `RemoteRulesFetcher(URI, modVersion, cacheFile)` only (no default URI); caches only when `doc.schemaVersion == RulesLoader.SCHEMA_VERSION`.

- [ ] Step 1: failing tests:
  - RulesLoaderTest: `pickNewestPrefersV2OnARevisionTie` (v1 remote rev 5 vs v2 bundled rev 5 → v2), `pickNewestPrefersRemoteThenCacheThenBundledOnFullTies` (order-independent: shuffled lists), `pickNewestStillTakesTheHighestRevision` (v1 rev 6 beats v2 rev 5).
  - RulesSourcesTest (JDK HttpServer on 127.0.0.1:0 with a request counter): `remoteV2IsUsedAndCached`, `remoteV2NotFoundFallsBackToRemoteV1WhichIsNotCached`, `remoteV2InvalidFallsBackToRemoteV1`, `bothRemotesFailingKeepsTheLocalPick`, `noRequestWhenRemoteNotAllowed` (counter == 0; listener called once, remote == false), `legacyV1CacheIsReadButNeverWritten` (content and mtime unchanged after a remote v2 success), `v2CacheIsACandidate`, `remoteV2WithSchemaVersion1IsNotCached`, `listenerIsNotCalledAgainWhenRemoteIsNotNewer`, `baseUrlOverride` (with/without trailing slash, `ftp:` and garbage → default, null → default).
  - RemoteRulesFetcherTest: adjust to the one constructor; `/good` now serves schemaVersion 2 for the caching assertion; add `schemaVersion1IsNotCached`.
- [ ] Step 2: run → FAIL.
- [ ] Step 3: implement; RealController:
```java
private final AtomicInteger rulesGeneration = new AtomicInteger();

private void loadRules() {
	int gen = rulesGeneration.incrementAndGet();
	RulesSources sources = new RulesSources(configDir, RulesSources.baseUrl(System.getProperty(RulesSources.BASE_URL_PROPERTY)), modVersion);
	sources.load(ClientSettings.shared(configDir).remoteRulesAllowed(), (doc, remote) -> {
		if (gen != rulesGeneration.get()) {
			return;
		}
		rules = doc;
		rebuild();
		if (remote) {
			fetchOnline();
		}
	});
}

@Override
public void settingsChanged() {
	CompletableFuture.runAsync(this::loadRules, Probes.EXECUTOR);
	rescan();
}
```
  (the `rulesCache` field goes; the 0.1.0 cache is read by RulesSources.)
- [ ] Step 4: `./gradlew build` both versions green (RealController compiles for 26.3 too).
- [ ] Step 5: commit `feat(rules): load v2 with remote v1 fallback, cache migration and gating`.

### Task 8: Pinned v0.1.0 differential test (H2) + 0.1.0 parser accepts rules-v1.json (AC2.4)

**Files:** create `src/test/java/io/github/chaotix345/rigtune/v010/**` (from `git show v0.1.0:<path>` with `io.github.chaotix345.rigtune` → `io.github.chaotix345.rigtune.v010` in package/import lines only): `RigTune`, `core/hardware/{CpuClassifier,GpuClassifier,TierCalculator}`, `core/model/{Action,Category,CpuInfo,DisplayInfo,Goal,GpuClass,GpuInfo,GpuVendor,GraphicsBackend,HardwareProfile,Impact,InstalledMod,ModFile,OnlineData,Recommendation,Report,SettingKeys,SettingsSnapshot,TierResult,UpdateInfo}`, `core/recommend/{Recommender,SettingValues}`, `core/rules/{BudgetedChars,Condition,ConditionEvaluator,EvalContext,RulesDocument,RulesLoader}` (grow the list only if javac asks); `src/test/resources/v010/rules-v1-baseline.json` (= `git show v0.1.0:rules/rules-v1.json`), `src/test/resources/v010/README.md` (what this is; never edit the sources); test `core/rules/RulesV1DifferentialTest.java`.

**Produces:** `RulesV1DifferentialTest.tickedActions(v010 RulesDocument, matrix point) -> Set<String>` (`id` + `=` + new value for SetSetting).

- [ ] Step 1: failing tests:
  - `v010ParserAcceptsRepoRulesV1` (pinned `RulesLoader.parse` on RepoFiles rules/rules-v1.json).
  - `v010ParserRejectsRulesV2` (documents why v2 lives in its own file).
  - `repoRulesV1AddsNoTickedRecommendationOverTheBaseline`: matrix = 12 hardware profiles (user rig AMD; low-end Intel laptop on battery; RTX 2060; GTX 1080; RTX 4090 4K; GTX 1660 laptop; no GPU info with unknown RAM/VRAM/refresh; Apple M1 macOS; AMD iGPU Linux; llvmpipe; Intel Arc; RTX 3070 with shaders-enabled + backend-vulkan + a sodium workaround flag) × 12 mod sets (none; fabric-api; sodium; sodium+iris; sodium+lithium+ferritecore; sodium+nvidium; sodium+iris+nvidium; optifabric; indium+sodium; distanthorizons+sodium+iris; lambdynlights+sodium; every mod id either document names) × 3 goals × 3 settings snapshots (every setting key both docs name = "0"; = "64"; realistic vanilla defaults). Online data marks every slug available. Failure message lists each new ticked action with its matrix point.
  - `theDifferentialCatchesANewTickedRecommendation` (baseline + an extra always-true `vanilla.renderDistance` value 2 entry → non-empty diff) and `removalsAreAllowed` (baseline minus its mods → empty diff).
- [ ] Step 2: run → FAIL (classes missing).
- [ ] Step 3: copy the pinned sources with a script (sed on `package`/`import` lines), add the test.
- [ ] Step 4: `./gradlew build` both versions green.
- [ ] Step 5: commit `test(rules): pinned v0.1.0 differential test for rules-v1.json`.

### Task 9: Documentation

**Files:** `docs/RULES_SCHEMA.md` (rewrite: files and URLs; loading and tie-breaks; v2 top level (`settingLabels`), `requires`, `avoidSelected`, the new condition fields; three-valued evaluation + fail-closed poisoning + vocabularies; the v1 projection rules and errors; maintainer rules M1 (never add a v2-only/future field to an existing restrictive rule; add a new rule instead) and L1 (tier-rule schema changes need a new schemaVersion)); `tools/README.md` (both outputs, `v1` overrides and `"v1": false`, the automatic projections and the errors, REVIEW (d), check_rules_v1.py, the differential test and how to accept a deliberate v1 change by replacing the baseline); README.md layout line.

- [ ] Step 1: write the docs; check every rule stated matches the code (grep the constants).
- [ ] Step 2: commit `docs(rules): schema v2, projection and fail-closed evaluation`.

### Finish (protocol steps 4–6)
- [ ] Self-review: dispatch a code-reviewer subagent on `git diff origin/feat/v0.2.0...HEAD`; fix HIGH/MEDIUM; commit.
- [ ] `git fetch && git rebase origin/feat/v0.2.0`; resolve hotspot conflicts keeping both sides; `./gradlew build` (both versions) + `python -m unittest discover -s tools/tests` + `python tools/check_rules_v1.py`; push `feat/rules-v2`; `gh run watch <id> --exit-status`.
- [ ] Write `docs/v0.2/design/ws-a.md` (design + deviations: decisions 1–8 above); commit; push; CI green again.
- [ ] Report ≤15 lines.

## Self-review against the spec
- SPEC item 2 files/URLs → Tasks 4, 6, 7. Client loading (candidates, v1 fallback, no v1 cache, tie-breaks, schemaVersion 1–2, network off) → Tasks 1, 7. v2 document (`settingLabels`, `requires`, new fields, fail closed) → Tasks 1–3. v1 projection (same revision, override, omission, `v1` never written, labels omitted, REVIEW (d)) → Task 4. CI (`rules-consistency`, `rules-v1-compat`) → Task 5. Java tests list → Tasks 1–3, 6, 7. Python tests → Tasks 4, 5.
- Amendments: H1 → Task 2 (+ vocabularies in Task 9, check in Task 5). H2 → Tasks 4, 5, 8. H3 → Task 3 (+ projection of `avoidSelected` in Task 4). M14 → Tasks 2, 3, 7. M1/L1 → Task 9 (+ tier-rule field errors in Task 4).
- AC2.1 → Tasks 6, 7. AC2.2 → Tasks 1–3. AC2.3 → the `gpuModelMatches`/`displayPixelsAtLeast` evaluation is tested in Task 2 with the triage regex; the Nvidium/RenderScale *content* is WS-H's (Wave B), so AC2.3 stays open for WS-H. AC2.4 → Tasks 5, 8. AC2.5 → Task 9.
