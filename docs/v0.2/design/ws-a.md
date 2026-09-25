# WS-A: rules schema v2

SPEC item 2 (P0) and the plan-review fixes H1, H2, H3, M1/L1 and M14. The user-facing contract is
docs/RULES_SCHEMA.md; the maintainer workflow is tools/README.md. This note records the design and
where it deviates from, or interprets, the SPEC and PLAN.

## Files
- `core/rules/ConditionAdapterFactory.java` (new): Gson factory that records a Condition's unknown
  keys in `Condition.unknownFields` on the node that has them (nested `not`/`anyOf` conditions go
  through it too). Also counted as unknown: a key with an explicit JSON `null`, a number that isn't
  an integer in its Java field's range, and a non-boolean for a boolean field. Reading through the
  JSON tree would otherwise let Gson truncate 3.5 to 3 and wrap 2^32+1 to 1 (review I1). A condition
  that is itself `null` becomes a poisoned condition instead of "always" (review M4).
- `core/rules/Truth.java` (new) + `ConditionEvaluator.evaluate()`: TRUE/FALSE/UNKNOWN; `matches()` is
  `evaluate() == TRUE`. The vocabularies are public constants there (`GPU_VENDORS`, `BACKENDS`,
  `OS_FAMILIES`, `GOALS`, `FLAGS`, `SODIUM_WORKAROUND_FLAG`), mirrored in tools/update_rules.py.
- `core/rules/EvalContext.java`: `modVersions` component, plus the old 5-argument constructor
  (empty versions) so other code keeps compiling.
- `core/rules/RulesSources.java` (new): the candidate list and remote fallback; unit-tested with a
  JDK HttpServer. RealController only wires it.
- `core/rules/RulesLoader.java`: schemaVersion 1–2, bundled `/rigtune/rules-v2.json`, tie-breaks.
- `core/rules/RemoteRulesFetcher.java`: one constructor (URI, version, cache); caches only v2 documents.
- `core/rules/RulesDocument.java`: `ModRule.avoidSelected`.
- `core/hardware/GpuClassifier.java`: `subject(GpuInfo)` extracted, so `gpuModelMatches` and
  `gpuTiers` see the same string.
- `core/recommend/Recommender.java`, `SettingValues.java`: `requires`, `avoidSelected`, labels,
  mod versions, UNKNOWN `avoidWhen` blocks additions.
- `client/RealController.java`: `reloadRules()`/`loadRules(gen)` via RulesSources on a dedicated
  single-thread executor with a generation counter; `settingsChanged()` (the RigTuneController
  contract the settings screen calls; SPEC M14's "reloadRules()" is the private method behind it).
- `tools/update_rules.py`, new `tools/check_rules_v1.py`, tests; `.github/workflows/build.yml`
  (`rules-consistency`, new `rules-v1-compat`); `.github/workflows/update-rules.yml` (commits the new
  outputs; not in the PLAN's ownership list, but the weekly job would otherwise never commit
  rules-v2.json).
- Generated `rules/rules-v2.json` + bundled copy, `rules/rules-v1.json` (revision 5; the only content
  change from live data is debugify's new 26.3 build), `rules/REVIEW.md`. The bundled
  `rules-v1.json` is deleted.
- Tests: `src/test/java/io/github/chaotix345/rigtune/v010/**` (pinned v0.1.0 copy),
  `src/test/resources/v010/`, `RulesV1DifferentialTest`, `RepositoryRulesTest`, `RulesSourcesTest`,
  `ThreeValuedEvaluationTest`, `NewConditionFieldsTest`, `ConditionParsingTest`, `RecommenderV2Test`,
  `SchemaConsistencyTest` (runs Python: skipped locally without it, fails in CI without it), and the
  `RepoFiles` helper.
- README.md: one line in the "How the recommendations stay current" block.

## Evaluation (H1)
- **Unknown keys poison the whole tree; values use Kleene logic.** The SPEC's fail-closed paragraph
  (and AC2.2, "an unknown condition field anywhere makes the rule not fire") poisons the whole
  top-level condition for an unknown key; H1's amendment adds three-valued logic in which
  `anyOf` is TRUE if any branch is TRUE. Taken literally, H1 would let a TRUE sibling override an
  unknown key inside `anyOf`, which contradicts AC2.2 and is unsound for a future key that isn't a
  plain ANDed predicate (e.g. a modifier). So: any unknown key or `null` value anywhere → the
  top-level result is UNKNOWN; undecidable *values* are UNKNOWN where they occur and combine by
  Kleene rules. Both readings agree that only TRUE fires.
- **Additions need a definitely-FALSE `avoidWhen`.** `additions()` used to require
  `!matches(avoidWhen)`. With three values, an UNKNOWN `avoidWhen` now blocks the add too (fail
  closed = no recommendation). `avoided()` still disables only on TRUE.
- **Version predicates.** Fabric Loader's `VersionPredicate.parse` accepts almost anything: a term
  that isn't a semantic version becomes an exact string match (`">=>="` → `= ">="`, `"a || b"` →
  three terms), which would quietly evaluate FALSE and flip under `not`. A predicate with any
  non-semantic reference version is therefore treated as unparseable (UNKNOWN), and so is an
  installed or MC version that doesn't parse as a `SemanticVersion` (e.g. `mc26.2-0.9.2-fabric`).
  A blank predicate is UNKNOWN too (Fabric would read it as `*`). Checked against Fabric Loader
  0.19.5 with a scratch program.
- `heapMb*` became "UNKNOWN when ≤ 0" like RAM (the JVM always reports a heap, so no behaviour change).
- `gpuModelMatches` compiles its regex on every evaluation (no cache on Condition): a handful of
  conditions per report, and it avoids unsafe publication across the Probes threads.

## v1 projection (H2, H3)
- Per field, as H2 says: `v1: false` omits, a `v1` object is shallow-merged (only v1 fields, v1
  conditions, no nulls anywhere), a v2-only `recommendWhen`/advice `when` becomes
  `{"always": false}`.
- **Deviation: a v2-only `avoidWhen` is dropped only when the v1 `recommendWhen` is
  `{"always": false}`**; otherwise the updater errors. H2 only considered the disable effect of
  dropping it, but `avoidWhen` also blocks additions in `additions()`, so dropping it silently would
  let 0.1.x offer the mod where 0.2 avoids it. (Nvidium: WS-H needs a v1 `avoidWhen` override next
  to its v1 `recommendWhen`, e.g. the old `anyOf[not nvidia, gpuTierAtMost 2, shaders]`.)
- Setting entries with a v2 `when` or a non-`vanilla.`/`sodium.` key need an explicit `v1` (error
  otherwise), as H2 says. **WS-H: every `dh.`/`iris.` setting entry needs `"v1": false`.**
- **Deviation (review M9): a `warning`/`critical` advice with a v2-only `when` needs an explicit `v1`**
  (error otherwise); only `info` advice is turned into `{"always": false}` automatically. H2 would
  project every advice automatically, but a warning 0.1.x shows today shouldn't vanish without a
  decision (plan review M1's concern, for warnings).
- Vocabularies: `V1_VOCABULARIES` (frozen at 0.1.0) and `V2_VOCABULARIES` are separate, so a value
  a later 0.2.x adds makes a condition v2-only (review M3). `SchemaConsistencyTest` compares the
  updater's condition keys, rule fields and vocabularies with the Java classes and the v0.1.0 copy.
- `requires`/`avoidSelected` need an explicit `v1`; with an override they're left out only when that
  is exactly as safe (empty `requires`; `avoidSelected: true`, or no v1 `avoidWhen`). LambDynamicLights
  (`avoidSelected: false`) needs `"v1": false`, as H3 says.
- Knowledge is validated before any request: unknown fields (typos, also at the top level), unknown
  condition keys, nulls anywhere in a rule, values outside the vocabularies, integers outside their
  field's 32/64-bit range, regexes over 200 characters, malformed `settingLabels`, unknown fields on
  tier rules (L1). The updater exits 2 and lists every problem.
- One revision for both files: `max(old v1, old v2) + 1`, bumped when either changes; same
  `generatedAt`.
- REVIEW.md gets section (d) with every omission and field change.

## Differential test (H2)
`RulesV1DifferentialTest` runs the pinned v0.1.0 Recommender (tag v0.1.0, package renamed to
`io.github.chaotix345.rigtune.v010`, otherwise byte-identical) over the baseline
`src/test/resources/v010/rules-v1-baseline.json` (= rules-v1.json at v0.1.0) and the repository's
`rules/rules-v1.json`. Matrix: 12 hardware profiles (incl. no GPU info, llvmpipe, Apple, Intel Arc,
laptops on battery, shaders + Vulkan flags; MC 26.2 and 26.3) × 12 mod sets (incl. every tracked mod
id) × 3 settings snapshots × 3 goals. "More conservative" = the set of ticked, appliable actions
(add, disable, set with its target value) is a subset of the baseline's at every matrix point.
Availability is neutralised (every slug available) so live availability changes don't fail it. A
deliberate new ticked action for 0.1.x means replacing the baseline after review
(src/test/resources/v010/README.md). Self-tests prove it catches an injected action and reaches
add, disable and set actions.

## Loading (SPEC "Client loading", M14)
- Candidates: bundled v2, `rules-v2-cache.json`, 0.1.0's `rules-cache.json` (read-only), remote v2,
  then remote v1 when remote v2 fails. `pickNewest`: revision, then schemaVersion, then
  remote > cache > bundled (order-independent).
- The remote step is skipped entirely when `ClientSettings.shared(configDir).remoteRulesAllowed()`
  is false (test: zero requests).
- `-Drigtune.rules.baseUrl` must be an http(s) URL with a host; a trailing slash is added; anything
  else logs a warning and uses the default.
- `settingsChanged()` schedules a new load and rescans. The generation is bumped when a load is
  scheduled; the check and the assignment of the rules share a lock, so an older load can't overwrite
  a newer one's rules. `RulesSources.load` takes a `BooleanSupplier` asked before each request
  ("still the current load and remote rules still allowed"), so switching the network off stops the
  v1 fallback request of a load already in flight (review M1). Rules loads run on their own single
  thread, not on the two-thread pool the report builds use (a load can wait up to a minute on the
  network).
- After any rules publish (local too, not only a newer remote one) RealController calls
  `fetchOnline()`, which is a no-op until the scan is done. This fixes the offline race WS-G found
  (design/ws-g.md): a scan that finished before the local rules were set never fetched the Modrinth
  data until Rescan.
- Conflict references (`conflictsWith` slugs) resolve through every ModRule, including ones skipped
  by `requires`; only firing is filtered (review M2).

## Self-review
A code-reviewer subagent reviewed the branch: no critical findings; one important (I1, fixed) and
nine minor ones. Fixed: I1, M1, M2, M3, M4, M5, M6 (docs), M7 (v1 keys checked against 0.1.0's
allowlist), M8 (this file committed), M9. Its scratch Gson check stays in the session scratchpad
(outside the repo).

## Not done here / for others
- AC2.3 content (Nvidium's `gpuModelMatches` gate, RenderScale via `displayPixelsAtLeast`) is WS-H's
  knowledge work; WS-A tests the fields with the triage regex and strings.
- M1's "enforce by diffing against the previously published rules-v2.json" is documented as a
  maintainer rule in RULES_SCHEMA.md, not enforced by the updater.
- L1's optional "poison rules with unknown rule-level keys": not done; `requires` is the escape hatch.
