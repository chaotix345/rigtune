import copy
import json
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import update_rules as ur

FIXTURES = Path(__file__).resolve().parent / "fixtures"

NVIDIUM_REGEX = "(?i)GTX\\s*16[56]0\\b|RTX\\s*(?:20[6-8]0|30[5-9]0|40[5-9]0|50[5-9]0)\\b"


def load_fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def mod_rule(**fields):
    rule = {
        "slug": "nvidium", "projectId": "SfMw2IZN", "title": "Nvidium", "modIds": ["nvidium"],
        "category": "rendering", "impact": "high", "stability": "beta", "reason": "Mesh shaders.",
        "recommendWhen": {"gpuVendor": ["nvidia"], "gpuTierAtLeast": 4},
        "conflictsWith": ["vulkanmod"],
    }
    rule.update(fields)
    return rule


def setting_rule(**fields):
    rule = {"key": "vanilla.renderDistance", "value": 12, "when": {"tierAtLeast": 4}, "reason": "More chunks."}
    rule.update(fields)
    return rule


def advice_rule(**fields):
    rule = {"id": "tip", "when": {"heapMbAtMost": 2048}, "title": "Tip", "text": "Text.", "kind": "info"}
    rule.update(fields)
    return rule


def content_with(**sections):
    content = {
        "schemaVersion": 2, "gpuTiers": [], "gpuVendorFallback": {}, "cpuTiers": [], "heapTiers": [],
        "mods": [], "obsolete": [], "settings": [], "advice": [], "availability": {}, "upstream": {},
    }
    content.update(sections)
    return content


class CleanRulesTests(unittest.TestCase):
    def test_v1_clean_rule_is_copied_unchanged(self):
        for kind, rule in (
            ("mods", mod_rule(avoidWhen={"not": {"gpuVendor": ["nvidia"]}}, avoidReason="x")),
            ("settings", setting_rule()),
            ("advice", advice_rule()),
            ("obsolete", {"modIds": ["indium"], "title": "Indium", "reason": "Merged.", "replacement": "sodium"}),
        ):
            projected, notes = ur.project_rule(kind, rule)
            self.assertEqual(projected, rule, kind)
            self.assertEqual(notes, [], kind)

    def test_projection_does_not_mutate_the_input(self):
        rule = mod_rule(recommendWhen={"gpuModelMatches": NVIDIUM_REGEX}, v1={"stability": "alpha"})
        before = copy.deepcopy(rule)
        ur.project_rule("mods", rule)
        self.assertEqual(rule, before)


class OverrideTests(unittest.TestCase):
    def test_v1_false_omits_rule_and_notes_it(self):
        projected, notes = ur.project_rule("mods", mod_rule(v1=False))
        self.assertIsNone(projected)
        self.assertEqual(len(notes), 1)
        self.assertIn("omitted", notes[0])

    def test_v1_override_is_shallow_merged_and_never_written(self):
        rule = mod_rule(
            recommendWhen={"gpuVendor": ["nvidia"], "gpuModelMatches": NVIDIUM_REGEX},
            v1={"recommendWhen": {"gpuVendor": ["nvidia"], "gpuTierAtLeast": 4}},
        )
        projected, notes = ur.project_rule("mods", rule)
        self.assertEqual(projected["recommendWhen"], {"gpuVendor": ["nvidia"], "gpuTierAtLeast": 4})
        self.assertNotIn("v1", projected)
        self.assertTrue(any("override" in n for n in notes))
        content = content_with(mods=[rule])
        v2 = ur.v2_content(content)
        self.assertNotIn("v1", v2["mods"][0])
        self.assertEqual(v2["mods"][0]["recommendWhen"]["gpuModelMatches"], NVIDIUM_REGEX)
        v1, _ = ur.v1_projection(content)
        self.assertNotIn("v1", v1["mods"][0])

    def test_null_in_v1_override_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", mod_rule(v1={"recommendWhen": None}))
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", mod_rule(v1={"recommendWhen": {"not": {"gpuVendor": None}}}))

    def test_override_may_only_set_v1_fields(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", mod_rule(v1={"avoidSelected": False}))

    def test_override_conditions_must_be_v1_clean(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", mod_rule(v1={"recommendWhen": {"gpuModelMatches": "rtx"}}))

    def test_v1_true_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", mod_rule(v1=True))


class AutomaticProjectionTests(unittest.TestCase):
    def test_v2_only_recommend_when_becomes_never(self):
        projected, notes = ur.project_rule("mods", mod_rule(recommendWhen={"gpuModelMatches": NVIDIUM_REGEX}))
        self.assertEqual(projected["recommendWhen"], {"always": False})
        self.assertEqual(projected["conflictsWith"], ["vulkanmod"])
        self.assertEqual(projected["modIds"], ["nvidium"])
        self.assertTrue(any("recommendWhen" in n for n in notes))

    def test_v2_only_advice_when_becomes_never(self):
        projected, notes = ur.project_rule("advice", advice_rule(when={"displayPixelsAtLeast": 3686400}))
        self.assertEqual(projected["when"], {"always": False})
        self.assertEqual(len(notes), 1)

    def test_v2_only_avoid_when_dropped_when_never_recommended(self):
        rule = mod_rule(
            recommendWhen={"gpuModelMatches": NVIDIUM_REGEX},
            avoidWhen={"not": {"gpuModelMatches": NVIDIUM_REGEX}},
            avoidReason="Needs Turing.",
        )
        projected, notes = ur.project_rule("mods", rule)
        self.assertEqual(projected["recommendWhen"], {"always": False})
        self.assertNotIn("avoidWhen", projected)
        self.assertNotIn("avoidReason", projected)
        self.assertTrue(any("avoidWhen" in n for n in notes))

    def test_v2_only_avoid_when_with_possible_recommendation_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", mod_rule(avoidWhen={"not": {"gpuModelMatches": NVIDIUM_REGEX}}))
        rule = mod_rule(avoidWhen={"not": {"gpuModelMatches": NVIDIUM_REGEX}})
        del rule["recommendWhen"]
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", rule)

    def test_v1_avoid_when_override_resolves_the_error(self):
        rule = mod_rule(
            avoidWhen={"anyOf": [{"not": {"gpuModelMatches": NVIDIUM_REGEX}}, {"flags": ["shaders-enabled"]}]},
            v1={"avoidWhen": {"anyOf": [{"gpuTierAtMost": 2}, {"flags": ["shaders-enabled"]}]}},
        )
        projected, _ = ur.project_rule("mods", rule)
        self.assertEqual(projected["avoidWhen"], {"anyOf": [{"gpuTierAtMost": 2}, {"flags": ["shaders-enabled"]}]})

    def test_v2_detection_is_recursive(self):
        deep = {"anyOf": [{"tierAtLeast": 2}, {"not": {"anyOf": [{"not": {"mcVersionRange": ">=26.3"}}]}}]}
        self.assertFalse(ur.is_v1_condition(deep))
        projected, _ = ur.project_rule("advice", advice_rule(when=deep))
        self.assertEqual(projected["when"], {"always": False})
        self.assertTrue(ur.is_v1_condition({"anyOf": [{"not": {"tierAtLeast": 2}}]}))


class VocabularyTests(unittest.TestCase):
    def test_v1_vocabularies_are_frozen_at_v010(self):
        self.assertEqual(ur.V1_VOCABULARIES, {
            "gpuVendor": frozenset({"nvidia", "amd", "intel", "apple", "qualcomm", "software", "other", "unknown"}),
            "backend": frozenset({"opengl", "vulkan"}),
            "os": ("windows", "macos", "linux"),
            "goal": frozenset({"performance", "balanced", "quality"}),
            "flags": frozenset({"backend-vulkan", "shaders-enabled"}),
        })

    def test_a_value_only_v2_knows_is_projected_out_of_v1(self):
        extended = dict(ur.V2_VOCABULARIES, gpuVendor=ur.V2_VOCABULARIES["gpuVendor"] | {"newvendor"})
        with mock.patch.dict(ur.V2_VOCABULARIES, extended):
            advice = advice_rule(when={"not": {"gpuVendor": ["newvendor"]}})
            self.assertEqual(ur.condition_problems(advice["when"]), [])
            self.assertFalse(ur.is_v1_condition(advice["when"]))
            projected, _ = ur.project_rule("advice", advice)
            self.assertEqual(projected["when"], {"always": False})


class WarningAdviceTests(unittest.TestCase):
    def test_warning_advice_with_v2_when_needs_explicit_v1(self):
        for kind in ("warning", "critical"):
            rule = advice_rule(kind=kind, when={"displayPixelsAtLeast": 3686400})
            with self.assertRaises(ur.KnowledgeError, msg=kind):
                ur.project_rule("advice", rule)
            with self.assertRaises(ur.KnowledgeError, msg=kind):
                ur.project_rule("advice", dict(rule, v1={"title": "Other"}))
            projected, _ = ur.project_rule("advice", dict(rule, v1=False))
            self.assertIsNone(projected)
            projected, _ = ur.project_rule("advice", dict(rule, v1={"when": {"heapMbAtMost": 2048}}))
            self.assertEqual(projected["when"], {"heapMbAtMost": 2048})

    def test_info_advice_with_v2_when_is_projected_automatically(self):
        projected, _ = ur.project_rule("advice", advice_rule(kind="info", when={"displayPixelsAtLeast": 3686400}))
        self.assertEqual(projected["when"], {"always": False})


class ConflictReferenceTests(unittest.TestCase):
    def test_references_to_an_omitted_rule_use_its_mod_ids(self):
        moonrise = mod_rule(slug="moonrise-opt", modIds=["moonrise"], recommendWhen={"always": False},
                            conflictsWith=["c2me-fabric"], v1=False)
        c2me = mod_rule(slug="c2me-fabric", modIds=["c2me"], recommendWhen={"always": True},
                        conflictsWith=["moonrise-opt", "optifabric"])
        content = content_with(mods=[moonrise, c2me])
        v1, notes = ur.v1_projection(content)
        self.assertEqual([m["slug"] for m in v1["mods"]], ["c2me-fabric"])
        self.assertEqual(v1["mods"][0]["conflictsWith"], ["moonrise", "optifabric"])
        self.assertIn(("mods[c2me-fabric]", "conflictsWith: moonrise-opt (left out of rules-v1.json) -> its mod ids moonrise"), notes)
        self.assertEqual(ur.v2_content(content)["mods"][1]["conflictsWith"], ["moonrise-opt", "optifabric"])

    def test_mod_ids_already_listed_are_not_repeated(self):
        dh = mod_rule(slug="distanthorizons", modIds=["distanthorizons", "dh-core"], recommendWhen={"always": False}, v1=False)
        other = mod_rule(slug="other", modIds=["other"], recommendWhen={"always": True},
                         conflictsWith=["dh-core", "distanthorizons"])
        v1, _ = ur.v1_projection(content_with(mods=[dh, other]))
        self.assertEqual(v1["mods"][0]["conflictsWith"], ["dh-core", "distanthorizons"])

    def test_references_to_kept_rules_are_unchanged(self):
        a = mod_rule(slug="c2me-fabric", modIds=["c2me"], recommendWhen={"always": True}, conflictsWith=["moonrise-opt"])
        b = mod_rule(slug="moonrise-opt", modIds=["moonrise"], recommendWhen={"always": False}, conflictsWith=["c2me-fabric"])
        v1, notes = ur.v1_projection(content_with(mods=[a, b]))
        self.assertEqual(v1["mods"][0]["conflictsWith"], ["moonrise-opt"])
        self.assertEqual(notes, [])


class SettingTests(unittest.TestCase):
    def test_setting_with_v2_when_needs_explicit_v1(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("settings", setting_rule(when={"displayPixelsAtLeast": 3686400}))

    def test_setting_with_non_v1_namespace_needs_explicit_v1(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("settings", setting_rule(key="dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius"))

    def test_setting_with_v1_false_is_omitted(self):
        projected, notes = ur.project_rule("settings", setting_rule(key="iris.maxShadowRenderDistance", v1=False))
        self.assertIsNone(projected)
        self.assertEqual(len(notes), 1)

    def test_setting_with_v1_override_when(self):
        projected, _ = ur.project_rule("settings", setting_rule(when={"displayPixelsAtLeast": 3686400}, v1={"when": {"always": False}}))
        self.assertEqual(projected["when"], {"always": False})

    def test_override_that_keeps_a_v2_when_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("settings", setting_rule(when={"displayPixelsAtLeast": 1}, v1={"reason": "Other."}))


class RuleFieldTests(unittest.TestCase):
    def test_requires_needs_v1_false(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("advice", advice_rule(requires=["future"]))
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("advice", advice_rule(requires=["future"], v1={"title": "T"}))
        projected, _ = ur.project_rule("advice", advice_rule(requires=["future"], v1=False))
        self.assertIsNone(projected)
        projected, notes = ur.project_rule("advice", advice_rule(requires=[], v1={"title": "T"}))
        self.assertNotIn("requires", projected)
        self.assertTrue(any("requires" in n for n in notes))

    def test_avoid_selected_rules(self):
        ldl = mod_rule(slug="lambdynamiclights", modIds=["lambdynlights"], recommendWhen={"always": False},
                       avoidWhen={"tierAtMost": 2}, avoidSelected=False)
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", ldl)
        projected, _ = ur.project_rule("mods", dict(ldl, v1=False))
        self.assertIsNone(projected)
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", dict(ldl, v1={"impact": "low"}))
        projected, _ = ur.project_rule("mods", dict(ldl, avoidSelected=True, v1={"impact": "low"}))
        self.assertNotIn("avoidSelected", projected)
        self.assertEqual(projected["avoidWhen"], {"tierAtMost": 2})


    def test_skip_update_when_is_left_out_of_v1(self):
        dh = mod_rule(slug="distanthorizons", modIds=["distanthorizons"], recommendWhen={"always": False},
                      skipUpdateWhen={"settingIs": {"dh.client.advanced.autoUpdater.enableAutoUpdater": True}})
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("mods", dh)
        projected, notes = ur.project_rule("mods", dict(dh, v1={"impact": "low"}))
        self.assertNotIn("skipUpdateWhen", projected)
        self.assertTrue(any("skipUpdateWhen" in n for n in notes))
        projected, _ = ur.project_rule("mods", dict(dh, v1=False))
        self.assertIsNone(projected)


class SettingIsTests(unittest.TestCase):
    def test_setting_is_is_a_v2_condition(self):
        when = {"not": {"settingIs": {"dh.client.advanced.debugging.rendererMode": "DISABLED"}}}
        self.assertFalse(ur.is_v1_condition(when))
        self.assertEqual(ur.condition_problems(when), [])
        with self.assertRaises(ur.KnowledgeError):
            ur.project_rule("settings", setting_rule(when=when))
        projected, _ = ur.project_rule("settings", setting_rule(when=when, v1={"when": {"tierAtLeast": 4}}))
        self.assertEqual(projected["when"], {"tierAtLeast": 4})

    def test_setting_is_values_must_be_plain(self):
        for value in ("x", [], {"": True}, {"k": [1]}, {"k": {}}, {"k": None}):
            self.assertNotEqual(ur.condition_problems({"settingIs": value}), [], value)
        self.assertEqual(ur.condition_problems({"settingIs": {"a.b": True, "c": 12, "d": "X", "e": 1.5}}), [])


class ValidationTests(unittest.TestCase):
    def knowledge(self, **sections):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge.update(sections)
        return knowledge

    def test_sample_knowledge_is_valid(self):
        ur.validate_knowledge(self.knowledge())

    def test_unknown_rule_field_is_a_knowledge_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(advice=[advice_rule(titel="typo")]))

    def test_unknown_condition_key_is_a_knowledge_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(advice=[advice_rule(when={"not": {"tierAtLest": 2}})]))

    def test_null_in_a_rule_is_a_knowledge_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(advice=[advice_rule(when={"anyOf": [{"gpuVendor": None}]})]))
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(advice=[advice_rule(kind=None)]))

    def test_out_of_vocabulary_value_is_a_knowledge_error(self):
        for when in ({"flags": ["mesh-shaders"]}, {"gpuVendor": ["matrox"]}, {"backend": ["metal"]},
                     {"os": ["freebsd"]}, {"goal": ["extreme"]}):
            with self.assertRaises(ur.KnowledgeError, msg=str(when)):
                ur.validate_knowledge(self.knowledge(advice=[advice_rule(when=when)]))
        ur.validate_knowledge(self.knowledge(advice=[advice_rule(when={"flags": ["sodium-workaround:ANYTHING"], "os": ["win"]})]))

    def test_out_of_range_integer_is_a_knowledge_error(self):
        for when in ({"tierAtLeast": 2 ** 31}, {"refreshRateAtLeast": -(2 ** 31) - 1}, {"ramMbAtLeast": 2 ** 63},
                     {"always": 1}, {"tierAtMost": 3.5}):
            with self.assertRaises(ur.KnowledgeError, msg=str(when)):
                ur.validate_knowledge(self.knowledge(advice=[advice_rule(when=when)]))
        ur.validate_knowledge(self.knowledge(advice=[advice_rule(when={"tierAtLeast": 2 ** 31 - 1, "ramMbAtLeast": 2 ** 63 - 1})]))

    def test_skip_update_when_is_validated(self):
        dh = mod_rule(slug="distanthorizons", modIds=["distanthorizons"], recommendWhen={"always": False}, v1=False)
        ur.validate_knowledge(self.knowledge(mods=[dict(dh, skipUpdateWhen={"settingIs": {"dh.a": True}})]))
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(mods=[dict(dh, skipUpdateWhen={"settingIz": {"dh.a": True}})]))

    def test_overlong_regex_is_a_knowledge_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(advice=[advice_rule(when={"gpuModelMatches": "a" * 201})]))

    def test_unknown_value_token_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(settings=[setting_rule(value="$monitorHz")]))
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(settings=[setting_rule(value="$monitorHz", v1={"value": 60})]))
        ur.validate_knowledge(self.knowledge(settings=[setting_rule(value="$monitorHz", requires=["value-tokens-2"], v1=False)]))
        ur.validate_knowledge(self.knowledge(settings=[setting_rule(key="vanilla.maxFps", value="$refreshRateCap"),
                                                       setting_rule(key="vanilla.maxFps", value="$refreshRate")]))

    def test_setting_labels_shape_is_validated(self):
        for labels in ({"vanilla.renderDistance": {"values": ["Auto"]}}, {"vanilla.renderDistance": {"name": 5}},
                       {"vanilla.renderDistance": {"values": {"0": 1}}}, {"vanilla.renderDistance": {"nmae": "x"}},
                       {"vanilla.renderDistance": "Render distance"}):
            with self.assertRaises(ur.KnowledgeError, msg=str(labels)):
                ur.validate_knowledge(self.knowledge(settingLabels=labels))
        ur.validate_knowledge(self.knowledge(settingLabels={"vanilla.renderDistance": {"name": "Render distance", "values": {"2": "Tiny"}}}))

    def test_unknown_top_level_key_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(settingLabel={}))

    def test_tier_rule_unknown_field_is_an_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(heapTiers=[{"atLeastMb": 0, "tier": 3, "onlyOn": "windows"}]))

    def test_projection_errors_surface_in_validation(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.validate_knowledge(self.knowledge(settings=[setting_rule(key="dh.x")]))

    def test_load_knowledge_validates(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "knowledge.json"
            path.write_text(json.dumps(self.knowledge(advice=[advice_rule(when={"meshShaders": True})])), encoding="utf-8")
            with self.assertRaises(ur.KnowledgeError):
                ur.load_knowledge(path)


class DocumentTests(unittest.TestCase):
    def test_setting_labels_in_v2_not_v1(self):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["settingLabels"] = {"sodium.performance.chunk_builder_threads": {"name": "Chunk builder threads", "values": {"0": "Auto"}}}
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        self.assertEqual(content["schemaVersion"], 2)
        self.assertEqual(ur.v2_content(content)["settingLabels"], knowledge["settingLabels"])
        v1, _ = ur.v1_projection(content)
        self.assertEqual(v1["schemaVersion"], 1)
        self.assertNotIn("settingLabels", v1)

    def test_v1_projection_keeps_tier_rules_and_generated_fields(self):
        knowledge = load_fixture("knowledge_sample.json")
        content = ur.assemble_content(knowledge, knowledge["mods"], {"26.2": ["sodium"]}, {"additive": {"mcVersion": "26.2", "slugs": []}})
        v1, notes = ur.v1_projection(content)
        for key in ("gpuTiers", "gpuVendorFallback", "cpuTiers", "heapTiers", "availability", "upstream", "mods", "settings"):
            self.assertEqual(v1[key], content[key], key)
        self.assertEqual(notes, [])

    def test_both_outputs_share_one_revision(self):
        v2 = {"schemaVersion": 2, "mods": []}
        v1 = {"schemaVersion": 1, "mods": []}
        finals = ur.finalize_documents([(v2, None), (v1, {"schemaVersion": 1, "revision": 4, "mods": []})])
        self.assertEqual(finals[0]["revision"], 5)
        self.assertEqual(finals[1]["revision"], 5)
        self.assertEqual(finals[0]["generatedAt"], finals[1]["generatedAt"])
        self.assertEqual(finals[0]["schemaVersion"], 2)
        self.assertEqual(finals[1]["schemaVersion"], 1)

    def test_revision_bumps_when_only_v2_changes(self):
        old_v2 = {"schemaVersion": 2, "revision": 6, "generatedAt": "x", "mods": []}
        old_v1 = {"schemaVersion": 1, "revision": 6, "generatedAt": "x", "mods": []}
        finals = ur.finalize_documents([({"schemaVersion": 2, "mods": [{"slug": "a"}]}, old_v2), ({"schemaVersion": 1, "mods": []}, old_v1)])
        self.assertEqual([f["revision"] for f in finals], [7, 7])

    def test_unchanged_outputs_keep_their_files(self):
        old_v2 = {"schemaVersion": 2, "revision": 6, "generatedAt": "x", "mods": []}
        old_v1 = {"schemaVersion": 1, "revision": 6, "generatedAt": "x", "mods": []}
        self.assertIsNone(ur.finalize_documents([({"schemaVersion": 2, "mods": []}, old_v2), ({"schemaVersion": 1, "mods": []}, old_v1)]))


class ReviewSectionDTests(unittest.TestCase):
    def render(self, notes):
        return ur.render_review_markdown(["26.2"], "26.2", [], [], [], [], False, projection_notes=notes)

    def test_review_section_d_lists_omissions_and_changes(self):
        md = self.render([("mods[lambdynamiclights]", "omitted (\"v1\": false)"), ("advice[big-screen]", "when uses v2 features: set to never")])
        self.assertIn("## (d) Omitted from rules-v1.json", md)
        self.assertIn("| mods[lambdynamiclights] | omitted (\"v1\": false) |", md)
        self.assertIn("advice[big-screen]", md)
        self.assertIn("changed or omitted in rules-v1.json: 2", md)

    def test_review_section_d_none(self):
        md = self.render([])
        section = md.split("## (d) Omitted from rules-v1.json", 1)[1]
        self.assertIn("None.", section)

    def test_pipeline_notes_reach_review(self):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["advice"].append(advice_rule(id="v2-only", when={"mcVersionRange": ">=26.3"}))
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        _, notes = ur.v1_projection(content)
        self.assertEqual([label for label, _ in notes], ["advice[v2-only]"])


class TierV1Tests(unittest.TestCase):
    NEW = {"pattern": "(?i)RX\\s*9070\\s*GRE\\b", "vendor": "amd", "integrated": False, "tier": 4, "v1": False}
    OLD = {"pattern": "(?i)RX\\s*90[7-9]0\\b", "vendor": "amd", "integrated": False, "tier": 5}
    CPU = {"pattern": "(?i)Ryzen\\s*\\d\\s*\\d{4}X3D", "tier": 5, "v1": False}

    def knowledge(self, **sections):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge.update(sections)
        return knowledge

    def test_v1_false_tier_rows_are_valid(self):
        ur.validate_knowledge(self.knowledge(gpuTiers=[self.NEW, self.OLD], cpuTiers=[self.CPU]))

    def test_other_v1_values_on_tier_rows_are_errors(self):
        for value in (True, {"tier": 3}, None, 0, "false"):
            with self.assertRaises(ur.KnowledgeError, msg=repr(value)):
                ur.validate_knowledge(self.knowledge(gpuTiers=[dict(self.OLD, v1=value)]))
            with self.assertRaises(ur.KnowledgeError, msg=repr(value)):
                ur.validate_knowledge(self.knowledge(cpuTiers=[dict(self.CPU, v1=value)]))

    def test_heap_tiers_take_no_v1(self):
        with self.assertRaises(ur.KnowledgeError) as ctx:
            ur.validate_knowledge(self.knowledge(heapTiers=[{"atLeastMb": 0, "tier": 1, "v1": False}]))
        self.assertIn('"v1" is only allowed on gpuTiers and cpuTiers rows', str(ctx.exception))
        self.assertNotIn("unknown field", str(ctx.exception))

    def test_tier_sections_must_be_arrays(self):
        for kind in ur.TIER_KINDS:
            for value in (None, {}, "rows"):
                with self.assertRaises(ur.KnowledgeError, msg=f"{kind}={value!r}") as ctx:
                    ur.validate_knowledge(self.knowledge(**{kind: value}))
                self.assertIn(f"'{kind}' must be an array", str(ctx.exception))

    def test_v2_keeps_the_row_without_the_key(self):
        v2 = ur.v2_content(content_with(gpuTiers=[self.NEW, self.OLD], cpuTiers=[self.CPU]))
        self.assertEqual(v2["gpuTiers"], [{k: v for k, v in self.NEW.items() if k != "v1"}, self.OLD])
        self.assertEqual(v2["cpuTiers"], [{"pattern": self.CPU["pattern"], "tier": 5}])

    def test_v1_omits_the_row_keeps_the_order_and_notes_it(self):
        v1, notes = ur.v1_projection(content_with(gpuTiers=[self.OLD, self.NEW, dict(self.OLD, tier=3)], cpuTiers=[self.CPU]))
        self.assertEqual(v1["gpuTiers"], [self.OLD, dict(self.OLD, tier=3)])
        self.assertEqual(v1["cpuTiers"], [])
        self.assertEqual([note for _, note in notes], ['omitted ("v1": false)'] * 2)
        self.assertEqual(notes[0][0], "gpuTiers[1] " + self.NEW["pattern"])
        self.assertEqual(notes[1][0], "cpuTiers[0] " + self.CPU["pattern"])

    def test_the_key_never_reaches_either_output(self):
        content = content_with(gpuTiers=[self.NEW, self.OLD], cpuTiers=[self.CPU])
        v1, _ = ur.v1_projection(content)
        for doc in (v1, ur.v2_content(content)):
            for kind in ur.TIER_KINDS:
                self.assertTrue(all("v1" not in row for row in doc[kind]), kind)

    def test_input_is_not_mutated(self):
        content = content_with(gpuTiers=[dict(self.NEW)], cpuTiers=[dict(self.CPU)])
        before = copy.deepcopy(content)
        ur.v1_projection(content)
        ur.v2_content(content)
        self.assertEqual(content, before)

    def test_omitted_rows_reach_review_section_d(self):
        knowledge = self.knowledge(gpuTiers=[self.NEW, self.OLD])
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        _, notes = ur.v1_projection(content)
        md = ur.render_review_markdown(["26.2"], "26.2", [], [], [], [], False, projection_notes=notes)
        self.assertIn("| gpuTiers[0] " + self.NEW["pattern"] + ' | omitted ("v1": false) |', md)


def write_fixture(fixtures_dir, url, body, status=200):
    (fixtures_dir / f"{ur.fixture_key(url)}.json").write_text(json.dumps({"status": status, "body": body}), encoding="utf-8")


class MainOutputTests(unittest.TestCase):
    def test_main_writes_v2_bundled_v2_and_v1_only(self):
        import urllib.parse as up
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["advice"].append(advice_rule(id="v2-only", when={"mcVersionRange": ">=26.3"}))
        knowledge["settingLabels"] = {"vanilla.renderDistance": {"name": "Render distance"}}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixtures = root / "fixtures"
            fixtures.mkdir()
            write_fixture(fixtures, ur.fo_contents_url("26.2"), [])
            write_fixture(fixtures, ur.additive_contents_url("26.2"), [])
            ids = sorted(m["projectId"] for m in knowledge["mods"])
            projects = [{"id": i, "slug": s, "title": s, "status": "approved", "categories": [], "game_versions": [], "loaders": []}
                        for i, s in zip(ids, ["moreculling", "sodium", "lithium"])]
            write_fixture(fixtures, f"{ur.MODRINTH_API}/projects?ids={up.quote(json.dumps(ids), safe='')}", projects)
            knowledge_path = root / "knowledge.json"
            knowledge_path.write_text(json.dumps(knowledge), encoding="utf-8")
            out = root / "out"
            (out / "rules").mkdir(parents=True)
            (out / "rules" / "rules-v1.json").write_text(json.dumps({"schemaVersion": 1, "revision": 4, "mods": []}), encoding="utf-8")

            code = ur.main(["--knowledge", str(knowledge_path), "--out-dir", str(out), "--mc-versions", "26.2",
                            "--offline-fixtures", str(fixtures)])

            self.assertEqual(code, 0)
            v2 = json.loads((out / "rules" / "rules-v2.json").read_text(encoding="utf-8"))
            bundled = (out / "src" / "main" / "resources" / "rigtune" / "rules-v2.json").read_text(encoding="utf-8")
            v1 = json.loads((out / "rules" / "rules-v1.json").read_text(encoding="utf-8"))
            self.assertEqual(bundled, (out / "rules" / "rules-v2.json").read_text(encoding="utf-8"))
            self.assertFalse((out / "src" / "main" / "resources" / "rigtune" / "rules-v1.json").exists())
            self.assertEqual((v2["schemaVersion"], v2["revision"]), (2, 5))
            self.assertEqual((v1["schemaVersion"], v1["revision"]), (1, 5))
            self.assertIn("settingLabels", v2)
            self.assertNotIn("settingLabels", v1)
            self.assertEqual(v1["advice"][-1]["when"], {"always": False})
            review = (out / "rules" / "REVIEW.md").read_text(encoding="utf-8")
            self.assertIn("advice[v2-only]", review)

    def test_main_exits_2_on_a_projection_error(self):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["settings"].append(setting_rule(key="dh.x"))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "knowledge.json"
            path.write_text(json.dumps(knowledge), encoding="utf-8")
            self.assertEqual(ur.main(["--knowledge", str(path), "--out-dir", tmp, "--dry-run"]), 2)


if __name__ == "__main__":
    unittest.main()
