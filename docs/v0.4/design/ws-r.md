# WS-R: rules (SPEC 2k, 2l, C2 tools side; content for 4, 5, 6, 9)

Branch `feat/rules-v04`. Rules revision 13 → **14** (one regeneration for both files). Plan: docs/v0.4/plans/ws-r.md.

## What landed
- **Updater** (tools/update_rules.py): `profileTemplates` and `stutterAdvice` validated and written to rules-v2.json only
  (`V2_ONLY_SECTIONS`, stripped by `v1_projection`; check_rules_v1.py already rejects unknown top-level fields);
  `driverVersion` validated (`vendor` required and lower-case, `atLeast`/`atMost` dotted numbers < 2^31, at least one,
  `atLeast` ≤ `atMost`, no other field); stutter keys refused outside `stutterAdvice` with their own message, K-M1 value
  checks inside it (share maps: known cause/tag → whole percent 0-100 as int or digit string; percents 0-100; counts ≥ 0;
  `gcCollector` vocabulary); `jvm-` facts = `JVM_FLAGS` (WS-J's `JvmFacts.RULE_FLAGS`, coordinator note), a rule testing
  one needs `requires` naming `jvm-flags`, `jvm-probed` and any other `jvm-*` refused (J-M1); R-L1 (`restrictive_problems`):
  a key or value outside `LEGACY_V2_CONDITION_KEYS`/`LEGACY_V2_VOCABULARIES` (what 0.2.0/0.3.0 know) in a clamp, an
  `avoidWhen` or a `skipUpdateWhen` is refused unless the rule has a non-empty `requires`.
- **Content** (knowledge.json): 2k (VSync-off `defaultSelected: false`, both reasons as SPEC 2k); `profileTemplates` (SPEC 4:
  Max FPS, Balanced, Quality, Battery incl. DH `rendererMode` DISABLED and Iris shaders off, Recording with `$recordingFps`);
  `stutterAdvice` (ram-stutter-gc-heap, stutter-gc-explicit, stutter-sodium-defer, stutter-dh-threads, stutter-chunk-loading);
  9 `jvm-*` advice (SPEC 6 as amended + `jvm-xmx-duplicate`), each `requires: ["jvm-flags"]`, `"v1": false`; the two driver
  seeds (SPEC 9), `"v1": false`, no `requires`.
- **Java**: `Recommender.SUPPORTED_FEATURES = {"jvm-flags"}`. No evaluator code (driverVersion: WS-W, stutter keys: WS-S,
  jvm- facts: WS-J).
- **Tests**: tools/tests/test_rules_v04.py (27), tools/tests/test_generated_rules.py (8, AC2k.1 on the generated files);
  LegacyRulesParseTest (3), LegacyConditionFailClosedTest (4), SchemaConsistencyTest (+2: legacy key sets/vocabularies vs
  the pinned v030 copy; template fields; features), KnowledgeV2ScenarioTest (+4: 2k, nothing new fires today, jvm wiring,
  the Intel Gen7 regex), RecommenderScenarioTest (+3: 2l i-iii), RulesV1DifferentialTest (+1: the pinned v0.1.0
  recommender offers VSync off unticked), RecommenderV2Test/RulesContractsTest updated (see Deviations 1).
- **Docs**: RULES_SCHEMA.md (both sections, driverVersion, jvm- facts and the `jvm-` id prefix, the features table, R-L1
  in "Rules for maintainers", v1 projection), tools/README.md, README "What has been verified".

Released-jar harness (tools/e2e/compat030.py, the released rigtune-0.3.0+mc26.2.jar, run locally on the merged branch):
"RulesLoader: rules-v2.json parses with unchanged counts: revision 14, counts {mods=28, settings=56, advice=34, obsolete=6,
gpuTiers=43, cpuTiers=8, heapTiers=5}; new sections present: [profileTemplates, stutterAdvice]", RESULT PASS.

rules-v1.json vs feat/v0.4.0: only the VSync pair (cap reason; VSync `defaultSelected: false` + reason), `revision`,
`generatedAt`. The live updater was dry-run into a scratch dir first: no upstream/availability drift at revision 13.

## Decisions and deviations
1. **RulesContractsTest / RecommenderV2Test** (WS-K's and v0.2's tests) asserted "no features" and "the bundled rules have
   no sections"; both are now "jvm-flags only" and "sections null when absent from a document" (WS-K's comment said WS-R
   changes it).
2. **RAM thresholds.** "≥ 8 GB" (`jvm-zgc-small-heap`, Launcher-steps amendment) is `ramMbAtLeast: 7680`: PCs report a
   little less than the installed RAM (hardware-reserved memory, iGPU carve-outs), like the 12000/16000 the `ram-*` rules
   use for 12/16 GB. "≤ 8 GB" (`jvm-zgc-small-pc`) is `ramMbAtMost: 8192` and "≤ 16 GB" (`jvm-server-flags`) 16384, as
   `ram-too-much` does.
3. **Conditions made explicit** beyond the fact name: `jvm-young-gen-fixed` also needs `jvm-gc-g1` (SPEC "under G1");
   `jvm-stop-the-world-gc` needs `jvm-gc-typed` plus serial or parallel (research §5.2: typed, not ergonomic);
   `jvm-explicit-gc-disabled` excludes `jvm-server-flags` (research: not twice for Aikar's set). With J-M1 a `not` over a
   jvm- fact stays UNKNOWN until the check ran.
4. **For the coordinator:** `jvm-zgc-small-pc` (SPEC 6) fires on an 8 GB PC running Mojang's 26.1+ default (ZGC, 4 GB),
   which launcher-steps.md found; it's info-level with the measured footprint (3852 of 4096 MB vs ~1.3 GB for G1), so kept
   as specified.
5. **Stutter vocabulary** (causes gc, chunkLoad, chunkBuild, tick, render, unknown; tags worldSave, dh, cpuContention,
   afterTeleport, movingFast) is the `StutterFacts` contract as landed; WS-S must use these names (or extend both the
   updater's `STUTTER_MAP_KEYS` and the evaluator). `stutter-chunk-loading` (chunkLoad ≥ 30 %) is SPEC 5's "info entry for
   chunk-loading-dominant sessions". Only `ram-stutter-gc-heap` mentions the collector, with SPEC 6's conclusion.
6. **Templates carry reasons** on every entry (for Preview); the updater's managed keyset (`MANAGED_PROFILE_KEYS`) mirrors
   profiles.md §5.2 plus the two local-only thread counts. WS-P: once `ShareKeys` exists, a SchemaConsistencyTest check
   `MANAGED_PROFILE_KEYS == ShareKeys keys + the two thread counts` belongs next to it (and the template ids vs
   `ProfileTemplates`). WS-J: once `JvmFacts.RULE_FLAGS` exists, add `JVM_FLAGS == RULE_FLAGS` there.
7. **Driver seeds**: titles differ from `intel-igpu-driver` (both can show on an HD 4000); impact high (crash/freeze). The
   Intel regex `(?i)\bHD\s*Graphics\s*P?(?:2500|4000)\b` covers the Ivy Bridge names (HD 2500, HD 4000, P4000) and nothing
   of Sandy Bridge, Haswell or later (KnowledgeV2ScenarioTest strings).
8. **Handoff**: `KnowledgeV2ScenarioTest.theV04ContentFiresNothingUntilItsEvaluatorsLand` asserts that no jvm-, driver- or
   stutter id fires in the main list today; `LegacyConditionFailClosedTest` covers only the 0.2.0/0.3.0 side. WS-W, WS-S and
   WS-J replace their part of the first with their seeds' scenario tests (AC9.4, AC5.5, AC6.3) and add the current code's
   TRUE/FALSE to AC9.3.

## AC6.5 checklist: numbers in `jvm-*` texts (tools/tests/test_generated_rules.py enforces the set)
| advice | number | source (docs/research/v0.4/jvm-gc.md) |
|---|---|---|
| jvm-server-flags | "same frame rates and 1% lows" | §4.2: Aikar 1926 / 622 vs G1 1931 / 610, within run-to-run spread |
| jvm-server-flags | 4096 MB of a 4 GB allocation | §4.2: Aikar committed 4096 (4096-4096) MB at -Xms4G -Xmx4G |
| jvm-server-flags, jvm-zgc-small-pc | about 1.3 GB (G1) | §4.2: G1 default committed 1316 (1298-1384) MB at -Xmx4G |
| jvm-zgc-small-pc | 3852 of 4096 MB | §4.2: ZGC committed 3852 (3852-3854) MB at -Xmx4G |
| jvm-zgc-small-heap | 2 GB vs 4 GB: "slightly lower 1% lows" | §4.3: 564 vs 627 (n = 3 / 5) |
| jvm-server-flags / zgc-small-pc | 16 GB / 8 GB | SPEC 6 thresholds, not measurements |

## AC status
- 2k: AC2k.1 verified (test_generated_rules.py), AC2k.2 verified (RulesV1DifferentialTest.vsyncOffIsUntickedFor010,
  KnowledgeV2ScenarioTest.vsyncOffIsOptionalAndHonest, LegacyRulesParseTest.legacyParserReadsTheVsyncEntryUnticked).
- 2l: AC2l.1 verified (RecommenderScenarioTest i-iii; README section). TierBasis on `Report` is WS-A's; the tests read the
  basis from `GpuClass.matchedPattern` and the cpuTiers rows.
- C2 tools side, AC4.10, AC5.6 (rules parts), AC9.3 (legacy side + SchemaConsistencyTest + check_rules_v1 Python test),
  AC6.3 (legacy skip part): verified by the tests above.

## UNVERIFIED
- Menu paths in advice text: Sodium "video settings (Performance page)" for Chunk Updates and Distant Horizons'
  "NO. of threads" label (from stutter.md §5 and the existing settingLabels, not re-read in game).
- "Minecraft itself doesn't call System.gc() in normal play" rests on jvm-gc.md §2.1's 26.2 bytecode search; 26.3 not
  re-checked.
- Recording's reasons (VSync above 60 Hz adds lag; OBS capture) inherit profiles.md §4.3's UNVERIFIED notes.
