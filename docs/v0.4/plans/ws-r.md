# WS-R plan: rules (SPEC 2k, 2l, C2 tools side, content for 4, 5, 6, 9)

Branch `feat/rules-v04`, worktree `rigtune-rules4`. Goal: one early green merge with the tools side, 2k and all content;
corrections later in a second small merge. Binding: SPEC top + X1-X8, C2, 2k, 2l, 4, 5, 6, 9, amendments R-L1, J-M1, J-M2,
K-M1, "Launcher steps"; ws-k.md C2; coordinator note from WS-J (jvm- rule vocabulary = `JvmFacts.RULE_FLAGS`).

## Task 1: updater (tools/update_rules.py) + Python tests
- [ ] `profileTemplates`: object `{templates: [...]}`; template fields id/goal/facts/settings/requires; ids in
      {max_fps, balanced, quality, battery, recording}, unique; goal in the goal vocabulary; facts ⊆ {onBattery, hasBattery},
      booleans; settings as SettingRule (value xor min/max, V2 conditions), keys in the managed keyset (profiles.md §5.2),
      tokens `$refreshRate`, `$refreshRateCap`, `$recordingFps` (template layer only); no `v1`.
- [ ] `stutterAdvice`: array of AdviceRule; ids unique; `requires` contains `stutter-doctor`; conditions may use V2 +
      STUTTER keys (K-M1 types: share maps cause/tag -> whole percent 0..100, percents 0..100, gcCollector vocabulary); no `v1`.
- [ ] Stutter keys outside `stutterAdvice` refused with a specific message.
- [ ] `driverVersion`: map {vendor (required, gpuVendor vocabulary), atLeast?, atMost?}, at least one bound, dotted numeric
      strings, atLeast <= atMost; unknown map key refused.
- [ ] `jvm-` flags: `JVM_FLAGS` mirrors WS-J's `JvmFacts.RULE_FLAGS`; a rule using one needs `requires` containing `jvm-flags`;
      `jvm-probed` and any other `jvm-*` refused.
- [ ] R-L1: a key/value 0.2.0/0.3.0 don't know (`LEGACY_V2_CONDITION_KEYS`/`LEGACY_V2_VOCABULARIES`) inside a clamp, an
      `avoidWhen` or a `skipUpdateWhen` is refused unless the rule has a non-empty `requires`.
- [ ] Both sections go to rules-v2.json and never to rules-v1.json; check_rules_v1.py rejects them (and `driverVersion`) in v1.
- [ ] Tests: tools/tests/test_rules_v04.py (each rule above, pass and fail).

## Task 2: Java side
- [ ] `Recommender.SUPPORTED_FEATURES = Set.of("jvm-flags")`.
- [ ] SchemaConsistencyTest: legacy keys/vocabularies vs the pinned v030 copies; template fields vs `ProfileTemplate`;
      `jvm-flags` in `SUPPORTED_FEATURES`.
- [ ] LegacyRulesParseTest (pinned v030 = 0.2.0 = 0.3.0 parser): bundled rules parse with the same counts as without the new
      sections; VSync entry `defaultSelected: false`.
- [ ] LegacyConditionFailClosedTest: every rule using `driverVersion` or a stutter key is UNKNOWN on the pinned evaluator;
      every `jvm-*` rule is skipped by the pinned `SUPPORTED_FEATURES`.

## Task 3: content (knowledge.json) + regeneration
- [ ] 2k: VSync-off `defaultSelected: false`, both reasons reworded (SPEC 2k text).
- [ ] `profileTemplates`: SPEC 4's five templates (Battery with DH rendering off; Recording with `$recordingFps`).
- [ ] `stutterAdvice`: ram-stutter-gc-heap, stutter-gc-explicit, stutter-sodium-defer, stutter-dh-threads,
      stutter-chunk-loading (info); GC wording from SPEC 6; never DisableExplicitGC.
- [ ] `jvm-*` advice (SPEC 6 as amended; every number from jvm-gc.md §4), `requires: ["jvm-flags"]`, `"v1": false`.
- [ ] Driver seeds (SPEC 9), `"v1": false`.
- [ ] Regenerate once (one revision), `./gradlew build`.
- [ ] tools/tests/test_generated_rules.py (AC2k.1 + sections present in v2, absent in v1, no new advice in v1).

## Task 4: scenario tests
- [ ] KnowledgeV2ScenarioTest: VSync unticked; nothing new fires today (jvm/driver/stutter); the Intel Gen7 regex.
- [ ] RulesV1DifferentialTest: the pinned v0.1.0 recommender shows VSync-off unticked with rules-v1.json.
- [ ] RecommenderScenarioTest: 2l (i) Iris Xe laptop on battery, (ii) i5-4590 + GTX 960, (iii) unrecognised CPU/GPU.
- [ ] README "What has been verified".

## Task 5: docs, review, finish
- [ ] docs/RULES_SCHEMA.md (profileTemplates, stutterAdvice, driverVersion, jvm- flags + `jvm-flags`, R-L1), tools/README.md.
- [ ] Code-reviewer subagent; fix high/medium.
- [ ] Merge origin/feat/v0.4.0, build, push, CI green (rules-consistency, rules-v1-compat), docs/v0.4/design/ws-r.md.
