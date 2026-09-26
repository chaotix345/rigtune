"""v0.4 rules sections and condition keys (docs/v0.4/SPEC.md C2, 4, 5, 6, 9; plan review R-L1, J-M1, K-M1)."""

import copy
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_rules_v1 as check
import update_rules as ur

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def sample_knowledge(**sections):
    knowledge = json.loads((FIXTURES / "knowledge_sample.json").read_text(encoding="utf-8"))
    knowledge.update(sections)
    return knowledge


def templates(*entries):
    return {"templates": list(entries)}


def template(**fields):
    t = {"id": "battery", "goal": "performance", "facts": {"onBattery": True, "hasBattery": True},
         "settings": [{"key": "vanilla.renderDistance", "max": 8}]}
    t.update(fields)
    return t


def stutter(**fields):
    rule = {"id": "ram-stutter-gc-heap", "requires": ["stutter-doctor"], "kind": "warning", "impact": "high",
            "when": {"stutterShareAtLeast": {"gc": 30}, "anyOf": [{"gcFullPausesAtLeast": 1}, {"liveSetPercentAtLeast": 75}],
                     "heapRaiseRoomMbAtLeast": 2048},
            "title": "T", "text": "t"}
    rule.update(fields)
    return rule


def advice(**fields):
    rule = {"id": "tip", "when": {"heapMbAtMost": 2048}, "title": "Tip", "text": "Text.", "kind": "info"}
    rule.update(fields)
    return rule


def driver_advice(**fields):
    return advice(id="driver-nvidia-threaded-optimization", kind="warning", v1=False,
                  when={"os": ["windows"], "gpuVendor": ["nvidia"],
                        "driverVersion": {"vendor": "nvidia", "atLeast": "526.47", "atMost": "536.22"}}, **fields)


def jvm_advice(flags, **fields):
    return advice(id="jvm-x", when={"flags": flags}, requires=["jvm-flags"], v1=False, **fields)


class Base(unittest.TestCase):
    def assert_valid(self, **sections):
        ur.validate_knowledge(sample_knowledge(**sections))

    def assert_invalid(self, fragment, **sections):
        with self.assertRaises(ur.KnowledgeError) as e:
            ur.validate_knowledge(sample_knowledge(**sections))
        self.assertIn(fragment, str(e.exception))


class ProfileTemplatesTests(Base):
    def test_the_five_templates_are_valid(self):
        section = templates(
            template(id="max_fps", goal="performance", facts={}, settings=[{"key": "vanilla.maxFps", "value": 260},
                                                                          {"key": "vanilla.enableVsync", "value": False}]),
            {"id": "balanced", "goal": "balanced"},
            {"id": "quality", "goal": "quality"},
            template(settings=[{"key": "iris.enableShaders", "value": False, "when": {"modPresent": ["iris"]}},
                               {"key": "dh.client.advanced.debugging.rendererMode", "value": "DISABLED",
                                "when": {"modPresent": ["distanthorizons"]}},
                               {"key": "vanilla.particles", "min": 1, "reason": "r"}]),
            template(id="recording", goal="balanced", facts={}, settings=[
                {"key": "vanilla.maxFps", "value": "$recordingFps"},
                {"key": "vanilla.enableVsync", "value": True, "when": {"not": {"refreshRateAtLeast": 61}}},
                {"key": "sodium.performance.chunk_build_defer_mode", "value": "ALWAYS", "when": {"modPresent": ["sodium"]}}]))
        self.assert_valid(profileTemplates=section)

    def test_section_shape(self):
        self.assert_invalid("profileTemplates must be an object", profileTemplates=[])
        self.assert_invalid("templates must be an array", profileTemplates={"templates": {}})
        self.assert_invalid("unknown field(s) extra", profileTemplates={"templates": [], "extra": 1})

    def test_ids_goal_and_facts(self):
        self.assert_invalid("id must be one of", profileTemplates=templates(template(id="turbo")))
        self.assert_invalid("duplicate id", profileTemplates=templates(template(), template()))
        self.assert_invalid("goal must be one of", profileTemplates=templates(template(goal="battery")))
        self.assert_invalid("goal must be one of", profileTemplates=templates(template(goal="Performance")))
        self.assert_invalid("facts may only set", profileTemplates=templates(template(facts={"onBattery": "yes"})))
        self.assert_invalid("facts may only set", profileTemplates=templates(template(facts={"refreshRate": 60})))

    def test_template_fields(self):
        self.assert_invalid("unknown field(s) v1", profileTemplates=templates(template(v1=False)))
        self.assert_invalid("null isn't allowed", profileTemplates=templates(template(goal=None)))
        self.assert_invalid("requires must be an array", profileTemplates=templates(template(requires="x")))

    def test_setting_entries(self):
        for entry, fragment in (
            ({"key": "vanilla.graphicsPreset", "value": "fast"}, "not a key profiles manage"),
            ({"key": "iris.shaderPack", "value": "x.zip"}, "not a key profiles manage"),
            ({"key": "vanilla.renderDistance", "value": 8, "max": 8}, "needs either value or min/max"),
            ({"key": "vanilla.renderDistance", "reason": "r"}, "needs either value or min/max"),
            ({"key": "vanilla.renderDistance", "max": "8"}, "max must be a number"),
            ({"key": "vanilla.maxFps", "value": "$monitorHz"}, "isn't a known token"),
            ({"key": "vanilla.maxFps", "value": 60, "when": {"stutterShareAtLeast": {"gc": 30}}}, "allowed only inside stutterAdvice"),
            ({"key": "vanilla.maxFps", "value": 60, "when": {"flags": ["jvm-gc-zgc"]}}, "needs \"requires\": [\"jvm-flags\"]"),
            ({"key": "vanilla.maxFps", "value": 60, "v1": False}, "unknown field(s) v1"),
        ):
            self.assert_invalid(fragment, profileTemplates=templates(template(settings=[entry])))
        self.assert_valid(profileTemplates=templates(template(settings=[{"key": "vanilla.maxFps", "value": "$monitorHz", "requires": ["t"]}])))
        self.assert_valid(profileTemplates=templates(template(requires=["t"], settings=[{"key": "vanilla.maxFps", "value": "$monitorHz"}])))

    def test_recording_fps_token_is_only_for_templates(self):
        self.assert_invalid("isn't a known token", settings=[{"key": "vanilla.maxFps", "value": "$recordingFps", "reason": "r"}])

    def test_in_v2_never_in_v1(self):
        knowledge = sample_knowledge(profileTemplates=templates(template()))
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        self.assertEqual(ur.v2_content(content)["profileTemplates"], knowledge["profileTemplates"])
        v1, notes = ur.v1_projection(content)
        self.assertNotIn("profileTemplates", v1)
        self.assertEqual(notes, [])


class StutterAdviceTests(Base):
    def test_valid_entry(self):
        self.assert_valid(stutterAdvice=[stutter(), stutter(id="stutter-gc-explicit", kind="info", impact="medium",
                                                            when={"gcExplicitPausesAtLeast": 2, "gcCollector": ["g1", "zgc"],
                                                                  "stutterTaggedShareAtLeast": {"dh": "40"}, "spikesPerMinuteAtLeast": 30,
                                                                  "modPresent": ["sodium"]})])

    def test_needs_requires_stutter_doctor(self):
        rule = stutter()
        del rule["requires"]
        self.assert_invalid('needs "requires": ["stutter-doctor"]', stutterAdvice=[rule])
        self.assert_invalid('needs "requires": ["stutter-doctor"]', stutterAdvice=[stutter(requires=["jvm-flags"])])

    def test_stutter_keys_only_inside_stutter_advice(self):
        for key, value in (("gcStallsAtLeast", 1), ("stutterShareAtLeast", {"gc": 30}), ("gcCollector", ["g1"])):
            self.assert_invalid("allowed only inside stutterAdvice", advice=[advice(when={key: value})])
            self.assert_invalid("allowed only inside stutterAdvice", settings=[{"key": "vanilla.maxFps", "value": 60, "when": {"not": {key: value}},
                                                                                "reason": "r"}])

    def test_value_types(self):
        for when, fragment in (
            ({"stutterShareAtLeast": {"gcc": 30}}, "not one of"),
            ({"stutterShareAtLeast": {"dh": 30}}, "not one of"),
            ({"stutterTaggedShareAtLeast": {"gc": 30}}, "not one of"),
            ({"stutterShareAtLeast": {"gc": 101}}, "whole percentage"),
            ({"stutterShareAtLeast": {"gc": "3.5"}}, "whole percentage"),
            ({"stutterShareAtLeast": {"gc": True}}, "whole percentage"),
            ({"stutterShareAtLeast": {}}, "must map causes"),
            ({"stutterShareAtLeast": [30]}, "must map causes"),
            ({"liveSetPercentAtLeast": 120}, "whole percentage"),
            ({"gcStallsAtLeast": -1}, "can't be negative"),
            ({"gcStallsAtLeast": 1.5}, "must be an integer"),
            ({"gcCollector": ["cms"]}, "outside the known values"),
            ({"heapRaiseRoomMbAtLeast": "2048"}, "must be an integer"),
        ):
            self.assert_invalid(fragment, stutterAdvice=[stutter(when=when)])

    def test_rule_fields(self):
        self.assert_invalid("unknown field(s) v1", stutterAdvice=[stutter(v1=False)])
        self.assert_invalid("duplicate id", stutterAdvice=[stutter(), stutter()])
        self.assert_invalid("kind must be one of", stutterAdvice=[stutter(kind="tip")])
        self.assert_invalid("impact must be one of", stutterAdvice=[stutter(impact="huge")])
        self.assert_invalid("needs a text", stutterAdvice=[stutter(text=" ")])
        self.assert_invalid("stutterAdvice must be an array", stutterAdvice={})

    def test_in_v2_never_in_v1(self):
        knowledge = sample_knowledge(stutterAdvice=[stutter()])
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        self.assertEqual(ur.v2_content(content)["stutterAdvice"], [stutter()])
        v1, _ = ur.v1_projection(content)
        self.assertNotIn("stutterAdvice", v1)


class DriverVersionTests(Base):
    def test_valid_seed(self):
        self.assert_valid(advice=[driver_advice()])
        self.assert_valid(advice=[advice(kind="warning", v1=False, when={"driverVersion": {"vendor": "intel", "atMost": "10.18.10.5160"}})])

    def test_shape(self):
        for value, fragment in (
            ({"atMost": "536.22"}, "vendor must be"),
            ({"vendor": "NVIDIA", "atMost": "536.22"}, "vendor must be"),
            ({"vendor": "matrox", "atMost": "536.22"}, "vendor must be"),
            ({"vendor": "nvidia"}, "needs atLeast or atMost"),
            ({"vendor": "nvidia", "atMost": 536.22}, "dotted version string"),
            ({"vendor": "nvidia", "atMost": "536.22a"}, "dotted version string"),
            ({"vendor": "nvidia", "atMost": ""}, "dotted version string"),
            ({"vendor": "nvidia", "atMost": "1.99999999999"}, "dotted version string"),
            ({"vendor": "nvidia", "atLeast": "536.23", "atMost": "536.22"}, "atLeast is above atMost"),
            ({"vendor": "nvidia", "atLeast": "10.1", "atMost": "10"}, "atLeast is above atMost"),
            ({"vendor": "nvidia", "atMost": "536.22", "family": "geforce"}, "unknown field"),
            ("nvidia", "must be an object"),
        ):
            self.assert_invalid(fragment, advice=[advice(v1=False, when={"driverVersion": value})])
        self.assert_valid(advice=[advice(v1=False, when={"driverVersion": {"vendor": "nvidia", "atLeast": "10", "atMost": "10.0.0"}})])

    def test_v2_only(self):
        self.assertIn("driverVersion", ur.V2_CONDITION_KEYS)
        self.assertNotIn("driverVersion", ur.V1_CONDITION_KEYS)
        self.assertNotIn("driverVersion", ur.LEGACY_V2_CONDITION_KEYS)
        self.assert_invalid("would silently disappear for 0.1.x", advice=[{k: v for k, v in driver_advice().items() if k != "v1"}])

    def test_info_advice_is_never_shown_to_v1(self):
        knowledge = sample_knowledge(advice=[advice(when={"driverVersion": {"vendor": "amd", "atMost": "24.9.1"}})])
        ur.validate_knowledge(knowledge)
        v1, _ = ur.v1_projection(ur.assemble_content(knowledge, knowledge["mods"], {}, {}))
        self.assertEqual(v1["advice"][0]["when"], {"always": False})


class JvmFlagTests(Base):
    def test_every_rule_flag_is_accepted_with_requires(self):
        for flag in sorted(ur.JVM_FLAGS):
            self.assert_valid(advice=[jvm_advice([flag])])

    def test_needs_requires_jvm_flags(self):
        self.assert_invalid('needs "requires": ["jvm-flags"]', advice=[advice(v1=False, when={"flags": ["jvm-gc-zgc"]})])
        self.assert_invalid('needs "requires": ["jvm-flags"]', advice=[advice(v1=False, requires=["stutter-doctor"],
                                                                              when={"not": {"anyOf": [{"flags": ["jvm-server-flags"]}]}})])

    def test_jvm_probed_and_unknown_jvm_flags_are_refused(self):
        self.assert_invalid("set by RigTune itself", advice=[jvm_advice(["jvm-probed"])])
        self.assert_invalid("set by RigTune itself", advice=[dict(jvm_advice(["jvm-gc-g1"]), when={"not": {"flags": ["jvm-probed"]}})])
        self.assert_invalid("outside the known values", advice=[jvm_advice(["jvm-gc-zgcc"])])
        self.assert_invalid("outside the known values", advice=[jvm_advice(["jvm-xms-large"])])

    def test_jvm_flags_are_never_v1_or_legacy(self):
        self.assertFalse(ur.is_v1_condition({"flags": ["jvm-gc-zgc"]}))
        self.assertFalse(ur.is_legacy_v2_condition({"flags": ["jvm-gc-zgc"]}))
        self.assertTrue(ur.is_legacy_v2_condition({"flags": ["shaders-enabled", "sodium-workaround:X"]}))
        self.assert_invalid('add "v1": false', advice=[{k: v for k, v in jvm_advice(["jvm-gc-zgc"]).items() if k != "v1"}])


class RestrictiveRulesTests(Base):
    """Plan review R-L1: a key or value newer than 0.2.0/0.3.0 in a clamp, avoidWhen or skipUpdateWhen needs `requires`."""

    def clamp(self, when, **fields):
        return dict({"key": "vanilla.renderDistance", "max": 8, "when": when, "reason": "r", "v1": False}, **fields)

    def mod(self, **fields):
        rule = {"slug": "x", "projectId": "p", "title": "X", "modIds": ["x"], "category": "rendering", "impact": "low",
                "stability": "stable", "reason": "r", "recommendWhen": {"always": False}, "conflictsWith": [], "v1": False}
        rule.update(fields)
        return rule

    def test_clamp(self):
        driver = {"driverVersion": {"vendor": "nvidia", "atMost": "536.22"}}
        self.assert_invalid("0.2.0/0.3.0 don't know", settings=[self.clamp(driver)])
        self.assert_invalid("0.2.0/0.3.0 don't know", settings=[self.clamp({"not": driver})])
        self.assert_valid(settings=[self.clamp(driver, requires=["driver-clamps"])])
        self.assert_valid(settings=[{"key": "vanilla.renderDistance", "value": 8, "when": driver, "reason": "r", "v1": False}])
        self.assert_valid(settings=[self.clamp({"settingIs": {"dh.a": "X"}})])

    def test_avoid_when_and_skip_update_when(self):
        driver = {"driverVersion": {"vendor": "amd", "atLeast": "24.8.1"}}
        self.assert_invalid("avoidWhen uses a condition 0.2.0/0.3.0 don't know", mods=[self.mod(avoidWhen=driver, avoidReason="r")])
        self.assert_invalid("skipUpdateWhen uses a condition 0.2.0/0.3.0 don't know", mods=[self.mod(skipUpdateWhen=driver)])
        self.assert_invalid("avoidWhen uses a condition 0.2.0/0.3.0 don't know",
                            mods=[self.mod(avoidWhen={"flags": ["jvm-gc-zgc"]}, avoidReason="r", requires=[])])
        self.assert_valid(mods=[self.mod(skipUpdateWhen=driver, requires=["driver-updates"])])
        self.assert_valid(mods=[self.mod(skipUpdateWhen={"settingIs": {"dh.a": True}})])


class CheckRulesV1Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        knowledge = sample_knowledge(profileTemplates=templates(template()), stutterAdvice=[stutter()], advice=[driver_advice()])
        path = self.tmp / "rules" / "source" / "knowledge.json"
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(knowledge), encoding="utf-8")
        mods = ur.mods_with_upstream(knowledge["mods"], set(), set())
        content = ur.assemble_content(knowledge, mods, {}, ur.top_level_upstream(None, set(), None, set()))
        v1, _ = ur.v1_projection(content)
        final_v2, final_v1 = ur.finalize_documents([(ur.v2_content(content), None), (v1, None)])
        self.v2, self.v1 = final_v2, final_v1
        ur.write_json(self.tmp / "rules" / "rules-v2.json", final_v2)
        ur.write_json(self.tmp / "rules" / "rules-v1.json", final_v1)

    def edit_v1(self, change):
        doc = copy.deepcopy(self.v1)
        change(doc)
        ur.write_json(self.tmp / "rules" / "rules-v1.json", doc)

    def test_generated_pair_passes_and_sections_stay_in_v2(self):
        self.assertEqual(check.check(self.tmp), [])
        self.assertIn("profileTemplates", self.v2)
        self.assertIn("stutterAdvice", self.v2)
        self.assertNotIn("profileTemplates", self.v1)
        self.assertNotIn("stutterAdvice", self.v1)
        self.assertEqual(self.v1["advice"], [], "the driver warning is v1: false")

    def test_new_sections_in_v1_fail(self):
        for section in ("profileTemplates", "stutterAdvice"):
            self.edit_v1(lambda d: d.update({section: self.v2[section]}))
            self.assertTrue(any("top-level field(s) 0.1.x doesn't know" in p and section in p for p in check.check(self.tmp)), section)

    def test_new_condition_keys_in_v1_fail(self):
        for when, fragment in (({"driverVersion": {"vendor": "nvidia", "atMost": "536.22"}}, "driverVersion"),
                               ({"flags": ["jvm-gc-zgc"]}, "jvm-gc-zgc"),
                               ({"gcStallsAtLeast": 1}, "gcStallsAtLeast")):
            self.edit_v1(lambda d: d["advice"].append(advice(when=when)))
            self.assertTrue(any(fragment in p for p in check.check(self.tmp)), fragment)


class LegacyKeySetTests(unittest.TestCase):
    def test_legacy_sets(self):
        self.assertEqual(ur.V2_CONDITION_KEYS - ur.LEGACY_V2_CONDITION_KEYS, {"driverVersion"})
        self.assertTrue(ur.V1_CONDITION_KEYS < ur.LEGACY_V2_CONDITION_KEYS)
        self.assertEqual(ur.LEGACY_V2_VOCABULARIES["flags"], ur.V2_VOCABULARIES["flags"])
        self.assertNotIn("jvmFlags", ur.LEGACY_V2_VOCABULARIES)
        self.assertFalse(ur.JVM_FLAGS & ur.V2_VOCABULARIES["flags"])
        self.assertNotIn(ur.JVM_PROBED_FLAG, ur.JVM_FLAGS)
        self.assertTrue(all(f.startswith(ur.JVM_FLAG_PREFIX) for f in ur.JVM_FLAGS))


if __name__ == "__main__":
    unittest.main()
