# WS-D (rules) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development for every task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Refresh the hardware tier tables for new GPUs without changing 0.1.x's classification, add the spark hint, stop the `vulkan-backend` advice from firing on 26.4, drive the updater's MC targets from the Stonecutter list, and ship it all as rules revision 11 in one regeneration run.

**Architecture:** All recommendation changes are data in `rules/source/knowledge.json`. `tools/update_rules.py` learns one source-only key on tier rows (`"v1": false`: omitted from rules-v1.json, stripped from both outputs) and takes its targets from `settings.gradle`. `tools/check_rules_v1.py` re-projects through the same functions, so it agrees without its own logic. Java tests read the bundled/generated rules, so they turn green only after the single regeneration (Task 6).

**Tech Stack:** Python 3.11 stdlib (updater, unittest), Java 25 + JUnit 5 (core tests), Gson, Fabric Loader `VersionPredicate` (mcVersionRange).

**Spec:** docs/v0.3/SPEC.md items 7, 12, item 1 (change C, vulkan-backend), 3d, and the amendments D-H1, D-M1, D-M2, D-L1, V-L1 (they override the item text). Research: docs/research/v0.3/hardware-tiers.md, misc.md §C, mc-versions.md §4.2 C / §5.4.

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-rules3` on `feat/rules-v03`; merge (never rebase) `origin/feat/v0.3.0`; no force push.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; committed Stonecutter version stays 26.2.
- Don't edit build.gradle, stonecutter.gradle, settings.gradle, gradle.properties, .github/workflows/* (update-rules.yml only if change C truly needs it: it doesn't, the updater reads settings.gradle from the checkout).
- rules-v1.json never less conservative for 0.1.x (RulesV1DifferentialTest, check_rules_v1.py); rules-v2.json safe for 0.2.0.
- **D-H1: every new tier row carries `"v1": false`.** D-L1: the spark advice is `"v1": false`, `impact: low`, short, with the upload note verified against spark's docs.
- One regeneration run for both files (revision 10 → 11); bundled copy identical; `./gradlew build` after it.
- Python files LF, no Windows-backslash string literals; text written with `newline='\n'`.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`.
- Don't create tools/MC_VERSIONS.md (WS-V) or touch DESIGN.md.

## File map
| file | change |
|---|---|
| tools/update_rules.py | `SOURCE_ONLY_TIER_FIELDS`; tier `v1` validation, stripping, omission + REVIEW (d) notes; `stonecutter_nodes()`; `resolve_target_versions(client, override, nodes)` |
| tools/check_rules_v1.py | nothing new expected (re-projection via `ur.v1_projection`/`ur.v2_content`); tests prove it |
| tools/tests/test_projection.py | `TierV1Tests` |
| tools/tests/test_check_rules_v1.py | tier `v1: false` pair passes; leaked row / leaked key fails |
| tools/tests/test_update_rules.py | `TargetVersionTests` (fake tag list incl. 26.4.1), `StonecutterNodesTests`, main reads settings.gradle |
| src/test/.../core/rules/SchemaConsistencyTest.java | source-only tier keys aren't Java fields (v1 or v2) |
| rules/source/knowledge.json | 4 GPU rows (v1 false), vulkan-backend range + v1 override, spark advice (v1 false) |
| src/test/.../core/hardware/GpuClassifierTest.java, CpuClassifierTest.java | research §4 cases incl. negatives |
| src/test/.../core/rules/RulesV1DifferentialTest.java | 4 matrix entries; pinned v0.1.0 classifier test; self-test that the new rows would be caught; vulkan-backend v1 `when` |
| src/test/.../core/recommend/KnowledgeV2ScenarioTest.java | vulkan-backend on 26.2/26.3/26.4-alpha.1/26.4; spark shown/not shown |
| rules/rules-v2.json, rules/rules-v1.json, src/main/resources/rigtune/rules-v2.json, rules/REVIEW.md | regenerated once (rev 11) |
| docs/RULES_SCHEMA.md, tools/README.md | tier `v1: false`, `ram-` convention, mcVersionRange note, change C |
| docs/v0.3/design/ws-d.md | design notes, rules-v1.json diff explained, bot-PR triage, UNVERIFIED |

---

### Task 1: Tier-row `"v1": false` in the updater (AC7.3)

**Files:** Modify `tools/update_rules.py` (constants ~l.103, `validate_knowledge`, `v2_content`, `v1_projection`); Test `tools/tests/test_projection.py`, `tools/tests/test_check_rules_v1.py`; Modify `SchemaConsistencyTest.java`.

**Interfaces:** Produces `ur.SOURCE_ONLY_TIER_FIELDS = {"gpuTiers": frozenset({"v1"}), "cpuTiers": frozenset({"v1"})}`; `v1_projection` notes `(f"{kind}[{i}] {pattern}", 'omitted ("v1": false)')` per omitted row.

- [ ] Step 1: failing tests in test_projection.py:
```python
class TierV1Tests(unittest.TestCase):
    NEW = {"pattern": "(?i)RX\\s*9070\\s*GRE\\b", "vendor": "amd", "integrated": False, "tier": 4, "v1": False}
    OLD = {"pattern": "(?i)RX\\s*90[7-9]0\\b", "vendor": "amd", "integrated": False, "tier": 5}
    CPU = {"pattern": "(?i)Ryzen\\s*\\d\\s*\\d{4}X3D", "tier": 5, "v1": False}

    def knowledge(self, **sections):
        k = load_fixture("knowledge_sample.json"); k.update(sections); return k

    def test_v1_false_tier_rows_are_valid(self):
        ur.validate_knowledge(self.knowledge(gpuTiers=[self.NEW, self.OLD], cpuTiers=[self.CPU]))

    def test_other_v1_values_on_tier_rows_are_errors(self):
        for value in (True, {"tier": 3}, None, 0):
            with self.assertRaises(ur.KnowledgeError, msg=repr(value)):
                ur.validate_knowledge(self.knowledge(gpuTiers=[dict(self.OLD, v1=value)]))

    def test_heap_tiers_take_no_v1(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(heapTiers=[{"atLeastMb": 0, "tier": 1, "v1": False}]))

    def test_v2_keeps_the_row_without_the_key(self):
        v2 = ur.v2_content(content_with(gpuTiers=[self.NEW, self.OLD], cpuTiers=[self.CPU]))
        self.assertEqual(v2["gpuTiers"], [{k: v for k, v in self.NEW.items() if k != "v1"}, self.OLD])
        self.assertEqual(v2["cpuTiers"], [{"pattern": self.CPU["pattern"], "tier": 5}])

    def test_v1_omits_the_row_keeps_order_and_notes_it(self):
        v1, notes = ur.v1_projection(content_with(gpuTiers=[self.NEW, self.OLD], cpuTiers=[self.CPU]))
        self.assertEqual(v1["gpuTiers"], [self.OLD])
        self.assertEqual(v1["cpuTiers"], [])
        self.assertEqual([n for _, n in notes], ['omitted ("v1": false)'] * 2)
        self.assertIn("gpuTiers[0]", notes[0][0]); self.assertIn("9070", notes[0][0])

    def test_input_is_not_mutated(self):
        content = content_with(gpuTiers=[dict(self.NEW)])
        ur.v1_projection(content); ur.v2_content(content)
        self.assertIn("v1", content["gpuTiers"][0])
```
test_check_rules_v1.py: setUp variant with a `v1: false` gpu row → `check.check()` is `[]`; editing rules-v1.json to re-add the row → "projection" problem; adding `"v1": false` to a v1 tier row → "v1" field problem.
SchemaConsistencyTest: dump `sourceOnlyTierFields`; assert no Java v1 or v2 tier class has a field named in it.
- [ ] Step 2: `python -m unittest discover -s tools/tests` → the new tests fail (unknown field `v1` on a tier rule).
- [ ] Step 3: implement: `SOURCE_ONLY_TIER_FIELDS`; `validate_knowledge` allows those keys and requires `is False`; `v2_content` strips them from `TIER_KINDS`; `v1_projection` skips rows with `v1 is False` (label = `f"{kind}[{i}] {rule.get('pattern')}"`) and strips the key otherwise.
- [ ] Step 4: Python tests green; `python tools/check_rules_v1.py` still passes on the repo files.
- [ ] Step 5: commit `feat(rules): tier rows may carry a source-only "v1": false`.

### Task 2: Change C, MC targets from the Stonecutter list (AC1.4)

**Files:** Modify `tools/update_rules.py` (`resolve_target_versions`, `run_pipeline`, `main`); Test `tools/tests/test_update_rules.py`.

**Interfaces:** `stonecutter_nodes(path: Path) -> list[str]` (raises `UpdateRulesError` if no `versions` list); `hotfix_tags(tags, node) -> list[str]` (release tags matching `^<node>\.\d+$`); `resolve_target_versions(client, override, nodes=None) -> list[str]` sorted newest first; `run_pipeline(knowledge, client, mc_versions_override, old_doc, nodes=None)`.

- [ ] Step 1: failing tests:
```python
class TargetVersionTests(unittest.TestCase):
    TAGS = [{"version": v, "version_type": t} for v, t in (
        ("26.4.1", "release"), ("26.4", "release"), ("26.4-snapshot-1", "snapshot"), ("26.3.2", "release"),
        ("26.3.1", "release"), ("26.3.1-rc-1", "snapshot"), ("26.3", "release"), ("26.20", "release"),
        ("26.2", "release"), ("26.1.2", "release"), ("26.1", "release"))]

    def client(self):
        opener = ScriptedOpener({f"{ur.MODRINTH_API}/tag/game_version": [(200, json_body(self.TAGS), {})]})
        return ur.Client(opener=opener, sleeper=RecordingSleeper())

    def test_targets_are_the_nodes_plus_their_hotfixes(self):
        self.assertEqual(ur.resolve_target_versions(self.client(), None, ["26.2", "26.3"]),
                         ["26.3.2", "26.3.1", "26.3", "26.2"])

    def test_a_newer_release_and_its_hotfix_are_not_targets(self):
        targets = ur.resolve_target_versions(self.client(), None, ["26.2", "26.3"])
        self.assertNotIn("26.4.1", targets); self.assertNotIn("26.4", targets); self.assertIn("26.2", targets)

    def test_override_wins_without_a_request(self): ...
    def test_a_node_missing_from_modrinth_is_still_a_target(self): ...  # e.g. nodes ["26.3", "26.5"]
```
plus `StonecutterNodesTests` (`versions '26.2', '26.3'`, double quotes, parentheses, missing list → error) and a test that `main` without `--mc-versions` reads `<repo>/settings.gradle` (patched `stonecutter_nodes`) and fails with exit 1 on a missing list. The old `test_resolve_target_versions_auto_detects_top_3_releases` is replaced.
- [ ] Step 2: run → fail.
- [ ] Step 3: implement; `main` reads `repo_root / "settings.gradle"` only when no override.
- [ ] Step 4: Python tests green.
- [ ] Step 5: commit `feat(rules): MC targets from the Stonecutter list plus hotfix tags (change C)`.

### Task 3: New GPU rows (AC7.1, D-H1)

**Files:** Modify `rules/source/knowledge.json` gpuTiers; Test `GpuClassifierTest.java`, `CpuClassifierTest.java`.

Rows (inserted before the rows research §3 names; all `"v1": false`):
```json
{ "pattern": "(?i)RTX\\s*5050\\b(?!\\s*Ti)", "vendor": "nvidia", "integrated": false, "tier": 3, "v1": false },   // after the 30x0/40[67]0/5060 tier-4 row
{ "pattern": "(?i)RX\\s*9070\\s*GRE\\b", "vendor": "amd", "integrated": false, "tier": 4, "v1": false },            // before RX ... 90[7-9]0 tier 5
{ "pattern": "(?i)Arc(?:\\s*\\(TM\\))?\\s*Pro\\s*B50\\b", "vendor": "intel", "integrated": false, "tier": 3, "v1": false },     // before the Arc catch-all
{ "pattern": "(?i)Arc(?:\\s*\\(TM\\))?\\s*Pro\\s*B[67]\\d\\b", "vendor": "intel", "integrated": false, "tier": 4, "v1": false }
```
- [ ] Step 1: add research §4 cases to `GpuClassifierTest.CASES` (RTX 5090/5070 Ti/5060 Ti/5050, 5090 Laptop, 5070 Ti Laptop, RX 9070 XT (+ Mesa string), RX 9070 GRE → 4, RX 9060 XT/9060 → 4, Arc B580 → 4, Arc Pro B60 → 4 discrete, Arc Pro B50 → 3 discrete, Arc Pro B70 → 4, 8060S → 4 iGPU, 890M → 3 iGPU, Apple M5/M5 Pro/M5 Max/M5 Ultra, Adreno X1-85) and a negatives test: `RTX 5050 Ti` is not matched by the 5050 row (fallback, `matchedPattern == null`), `Intel(R) Graphics` → fallback tier 2 integrated, `RTX 5050 Laptop GPU` → the laptop catch-all (tier 2). CpuClassifierTest: 9950X3D/9900X3D/9800X3D → 5, Core Ultra 9 285K → 5, Core Ultra 7 258V (8 threads, 4800 MHz) → formula 3 with no rule match.
- [ ] Step 2: `./gradlew :26.2:test --tests '*ClassifierTest'` → GRE/Arc Pro/5050 cases fail (bundled rules not yet regenerated).
- [ ] Step 3: edit knowledge.json; `python -c` load_knowledge → valid.
- [ ] Step 4: green only after Task 6's regeneration (noted in the commit).
- [ ] Step 5: commit `feat(rules): RX 9070 GRE, Arc Pro B50/B6x/B7x and RTX 5050 tier rows (v2 only)`.

### Task 4: RulesV1DifferentialTest matrix + pinned classifier (AC7.2)

**Files:** Modify `RulesV1DifferentialTest.java`.
- [ ] Step 1: matrix entries (heap 8192/6144, tier-5 CPU): `rtx-5070` (i7-14700K + `NVIDIA GeForce RTX 5070/PCIe/SSE2`), `rx-9070-gre` (7800X3D + `AMD Radeon RX 9070 GRE`), `arc-pro-b60` (Core Ultra 9 285K + `Intel(R) Arc(TM) Pro B60 Graphics`), `ryzen-9800x3d` (9800X3D + `AMD Radeon RX 9070 XT`).
- [ ] Step 2: `newHardwareKeepsItsV010Classification`: the pinned v0.1.0 `GpuClassifier`/`CpuClassifier` over the baseline and over `rules/rules-v1.json` give each new string the same tier and integrated flag (and CPU tier).
- [ ] Step 3: `theNewTierRowsWouldBeCaughtInV1`: baseline with `gpuTiers` replaced by rules-v2.json's → differences include `rx-9070-gre` and `arc-pro-b60` (proves the entries have teeth).
- [ ] Step 4: `vulkanBackendAdviceKeepsItsV010Condition`: rules/rules-v1.json's `vulkan-backend` `when` is exactly `{"backend":["vulkan"]}` (D-M2).
- [ ] Step 5: commit with Task 5 (green after Task 6).

### Task 5: vulkan-backend range (AC1.7) and spark advice (AC12.1)

**Files:** Modify `rules/source/knowledge.json` advice; Test `KnowledgeV2ScenarioTest.java`.
```json
{ "id": "vulkan-backend", "when": { "backend": ["vulkan"], "mcVersionRange": "<26.4-" }, "v1": { "when": { "backend": ["vulkan"] } }, ... }
{ "id": "spark-profiler", "when": { "modPresent": ["spark"] }, "kind": "info", "impact": "low", "v1": false,
  "title": "Find the cause of lag with spark", "text": "<short, verified against spark.lucko.me/docs>" }
```
- [ ] Step 1: verify spark's upload contents and commands in its docs (browse); record sources in the design doc.
- [ ] Step 2: tests: `vulkanBackendAdviceOnlyBefore264` (Vulkan rig on 26.2, 26.3 → shown; 26.4-alpha.1, 26.4 → not; OpenGL on 26.3 → not); `sparkAdviceOnlyWithSpark` (shown with `spark` loaded, low impact, info, not shown without).
- [ ] Step 3: edit knowledge.json; validate.
- [ ] Step 4: commit `feat(rules): vulkan-backend before 26.4 only; spark profiler hint (v2 only)` (green after Task 6).

### Task 6: One regeneration run (AC7.4)
- [ ] `python tools/update_rules.py` (live; no `--mc-versions`, so change C runs for real) → revision 11, both files + bundled copy + REVIEW.md. If a later fix is needed, restore the rules files to revision 10 before re-running so the result stays revision 11.
- [ ] `python tools/check_rules_v1.py`; `cmp rules/rules-v2.json src/main/resources/rigtune/rules-v2.json`; no bundled rules-v1.json.
- [ ] `./gradlew build` (both versions) and `python -m unittest discover -s tools/tests` green.
- [ ] commit `chore(rules): regenerate (revision 11)`; push; watch CI.

### Task 7: Docs
- [ ] RULES_SCHEMA.md: tier `v1: false` (GpuTierRule/CpuTierRule, "The v1 projection", maintainers' rules), the `ram-` advice-id convention, an mcVersionRange usage note (`<26.4-` excludes 26.4's snapshots and pre-releases; normalized snapshot ids like `26.4-alpha.1`).
- [ ] tools/README.md: targets from settings.gradle + hotfix tags; tier `v1: false`; REVIEW (d) lists omitted tier rows.
- [ ] commit `docs(rules): tier v1, ram- convention, mcVersionRange, change C`.

### Task 8: Review, design doc, finish
- [ ] Dispatch code-reviewer on `git diff feat/v0.3.0...HEAD` (hand-back goes to the coordinator); keep working.
- [ ] docs/v0.3/design/ws-d.md: decisions, the rules-v1.json diff vs feat/v0.3.0 line by line, bot-PR triage (none seen), research corrections, UNVERIFIED.
- [ ] Merge origin/feat/v0.3.0 when WS-0 lands; `./gradlew build`; Python tests; push; CI green on every job incl. rules-consistency and rules-v1-compat.
