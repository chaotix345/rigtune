"""v0.5 rules work (docs/v0.5/SPEC.md 2S L2, 2R L4, 5 C20's rules side, 4i, 2T; docs/v0.5/design/ws-r.md)."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import update_rules as ur

FIXTURES = Path(__file__).resolve().parent / "fixtures"
REPO = Path(__file__).resolve().parent.parent.parent


def sample_knowledge(**sections):
    knowledge = json.loads((FIXTURES / "knowledge_sample.json").read_text(encoding="utf-8"))
    knowledge.update(sections)
    return knowledge


def repo_json(*parts):
    return json.loads(REPO.joinpath(*parts).read_text(encoding="utf-8"))


def repo_content(knowledge):
    """knowledge's full content with the repository's generated data (as check_rules_v1 rebuilds it offline)."""
    v2 = repo_json("rules", "rules-v2.json")
    upstream = {m["slug"]: m["upstream"] for m in v2["mods"]}
    mods = [dict(m, upstream=upstream[m["slug"]]) for m in knowledge["mods"]]
    return ur.assemble_content(knowledge, mods, v2["availability"], v2["upstream"])


def stutter_advice(**fields):
    rule = {"id": "s", "requires": ["stutter-doctor"], "kind": "info", "impact": "low", "title": "T", "text": "t",
            "when": {"stutterTaggedShareAtLeast": {"chunksLoading": 60}}}
    rule.update(fields)
    return rule


class Base(unittest.TestCase):
    def assert_valid(self, knowledge):
        ur.validate_knowledge(knowledge)

    def assert_invalid(self, fragment, knowledge):
        with self.assertRaises(ur.KnowledgeError) as e:
            ur.validate_knowledge(knowledge)
        self.assertIn(fragment, str(e.exception))


def v2_doc(revision, mods=()):
    return {"schemaVersion": 2, "revision": revision, "generatedAt": "2026-09-01T00:00:00Z", "mods": list(mods)}


def v1_doc(revision, mods=()):
    return {"schemaVersion": 1, "revision": revision, "generatedAt": "2026-09-01T00:00:00Z", "mods": list(mods)}


# One release revision R for every regeneration of a release (docs/v0.5/SPEC.md "Compatibility promise"): --revision pins
# it; without the option the updater bumps as before (the weekly bot).
class RevisionPinTests(unittest.TestCase):
    def changed(self, old_revision, revision):
        return ur.finalize_documents([({"schemaVersion": 2, "mods": [{"slug": "new"}]}, v2_doc(old_revision)),
                                      ({"schemaVersion": 1, "mods": [{"slug": "new"}]}, v1_doc(old_revision))], revision=revision)

    def test_a_pinned_revision_is_written_to_both_files(self):
        finals = self.changed(16, 17)
        self.assertEqual([f["revision"] for f in finals], [17, 17])
        self.assertEqual(finals[0]["generatedAt"], finals[1]["generatedAt"])

    def test_regenerating_inside_the_same_revision_keeps_it(self):
        self.assertEqual([f["revision"] for f in self.changed(17, 17)], [17, 17])

    def test_a_pinned_revision_may_skip_ahead(self):
        self.assertEqual([f["revision"] for f in self.changed(16, 18)], [18, 18])

    def test_a_revision_never_goes_down(self):
        with self.assertRaises(ur.UpdateRulesError) as e:
            self.changed(17, 16)
        self.assertIn("below", str(e.exception))
        with self.assertRaises(ur.UpdateRulesError):
            ur.finalize_documents([({"schemaVersion": 2, "mods": []}, None)], revision=0)

    def test_the_highest_old_revision_counts(self):
        with self.assertRaises(ur.UpdateRulesError):
            ur.finalize_documents([({"schemaVersion": 2, "mods": [{"slug": "new"}]}, v2_doc(17)),
                                   ({"schemaVersion": 1, "mods": [{"slug": "new"}]}, v1_doc(16))], revision=16)

    def test_unchanged_content_writes_nothing_even_when_pinned(self):
        old = v2_doc(16, [{"slug": "a"}])
        self.assertIsNone(ur.finalize_documents([(ur.strip_meta(old), old)], revision=17))

    def test_without_the_option_the_bot_bumps_as_before(self):
        self.assertEqual([f["revision"] for f in self.changed(16, None)], [17, 17])

    def test_a_pinned_revision_stays_under_the_client_limit(self):
        with self.assertRaises(ur.UpdateRulesError):
            self.changed(16, ur.MAX_SAFE_REVISION)

    def test_main_writes_the_pinned_revision(self):
        knowledge = json.loads((FIXTURES / "knowledge_sample.json").read_text(encoding="utf-8"))
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})

        def fake_pipeline(knowledge, client, override, old_doc, nodes=None):
            return content, "# review\n", {}, ["26.2"], {"fabulouslyOptimized": None, "additive": None}, set(), set()

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ur, "run_pipeline", fake_pipeline):
            rules = Path(tmp) / "rules"
            rules.mkdir()
            (rules / "rules-v2.json").write_text(json.dumps(v2_doc(16)), encoding="utf-8")
            (rules / "rules-v1.json").write_text(json.dumps(v1_doc(16)), encoding="utf-8")
            argv = ["--knowledge", str(FIXTURES / "knowledge_sample.json"), "--out-dir", tmp, "--mc-versions", "26.2"]
            self.assertEqual(ur.main(argv + ["--revision", "18"]), 0)
            written = [json.loads((rules / name).read_text(encoding="utf-8"))["revision"] for name in ("rules-v2.json", "rules-v1.json")]
            bundled = json.loads((Path(tmp) / "src" / "main" / "resources" / "rigtune" / "rules-v2.json").read_text(encoding="utf-8"))
            self.assertEqual(written + [bundled["revision"]], [18, 18, 18])
            self.assertEqual(ur.main(argv + ["--revision", "17"]), 1, "below the files' revision")
            self.assertEqual(json.loads((rules / "rules-v2.json").read_text(encoding="utf-8"))["revision"], 18)


# L2 (AC2S.2): the "chunks loading" tag, which 0.4.0 already evaluates (Attributor.TAGS), is rules vocabulary inside the
# Stutter Doctor's sections and refused everywhere else.
class ChunksLoadingTagTests(Base):
    TAG = {"stutterTaggedShareAtLeast": {"chunksLoading": 60}}

    def test_accepted_in_stutter_advice(self):
        self.assert_valid(sample_knowledge(stutterAdvice=[stutter_advice()]))
        self.assert_valid(sample_knowledge(stutterAdvice=[stutter_advice(when={"stutterTaggedShareAtLeast": {"chunksLoading": "60"},
                                                                               "spikesPerMinuteAtLeast": 20})]))

    def test_its_share_is_a_whole_percentage(self):
        self.assert_invalid("chunksLoading must be a whole percentage",
                            sample_knowledge(stutterAdvice=[stutter_advice(when={"stutterTaggedShareAtLeast": {"chunksLoading": 101}})]))

    def test_refused_in_main_list_advice(self):
        knowledge = sample_knowledge()
        knowledge["advice"].append({"id": "x", "when": dict(self.TAG), "title": "T", "text": "t", "kind": "info"})
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_refused_in_a_setting(self):
        knowledge = sample_knowledge()
        knowledge["settings"].append({"key": "vanilla.renderDistance", "max": 8, "when": dict(self.TAG), "requires": ["x"], "v1": False})
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_refused_in_a_mod_rule(self):
        knowledge = sample_knowledge()
        knowledge["mods"][0]["recommendWhen"] = dict(self.TAG)
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_refused_in_a_template(self):
        knowledge = sample_knowledge(profileTemplates={"templates": [
            {"id": "battery", "goal": "performance", "settings": [{"key": "vanilla.renderDistance", "max": 8, "when": dict(self.TAG)}]}]})
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_an_unknown_tag_is_still_refused(self):
        self.assert_invalid("chunkLoading: not one of",
                            sample_knowledge(stutterAdvice=[stutter_advice(when={"stutterTaggedShareAtLeast": {"chunkLoading": 60}})]))


# AC2S.3 (python): the seed is v2-only, so rules-v1.json is the same with and without it.
class ChunksLoadingSeedV1Tests(unittest.TestCase):
    SEED = "stutter-chunks-loading-tag"

    def test_rules_v1_is_identical_with_and_without_the_l2_seed(self):
        knowledge = repo_json("rules", "source", "knowledge.json")
        self.assertIn(self.SEED, [a["id"] for a in knowledge["stutterAdvice"]])
        without = dict(knowledge, stutterAdvice=[a for a in knowledge["stutterAdvice"] if a["id"] != self.SEED])
        with_seed, _ = ur.v1_projection(repo_content(knowledge))
        without_seed, _ = ur.v1_projection(repo_content(without))
        self.assertEqual(with_seed, without_seed)
        self.assertEqual(ur.strip_meta(repo_json("rules", "rules-v1.json")), with_seed)


def fix(**fields):
    entry = {"adviceId": "stutter-sodium-defer", "requires": ["stutter-fix"],
             "evidence": {"stutterShareAtLeast": {"chunkBuild": 40}, "causeSpikesAtLeast": {"chunkBuild": 5}},
             "set": {"key": "sodium.performance.chunk_build_defer_mode", "value": "ALWAYS"}}
    entry.update(fields)
    return entry


STUTTER_ADVICE_IDS = ("stutter-sodium-defer", "stutter-chunk-loading", "stutter-dh-threads")


def with_fixes(*fixes, **sections):
    advice = [stutter_advice(id=i, when={"stutterShareAtLeast": {"chunkBuild": 25}}) for i in STUTTER_ADVICE_IDS]
    return sample_knowledge(stutterAdvice=sections.pop("stutterAdvice", advice), stutterFixes=list(fixes), **sections)


def without(mapping, key):
    return {k: v for k, v in mapping.items() if k != key}


# C20's rules side (AC5.1, AC5.15): the stutterFixes section's validator. Every refusal has its own test.
class StutterFixesTests(Base):
    def test_the_three_seeds_are_valid(self):
        self.assert_valid(with_fixes(
            fix(),
            fix(adviceId="stutter-chunk-loading", evidence={"stutterShareAtLeast": {"chunkLoad": 40}, "causeSpikesAtLeast": {"chunkLoad": "5"}},
                set={"key": "vanilla.renderDistance", "step": -2, "min": 6}),
            fix(adviceId="stutter-dh-threads", evidence={"stutterTaggedShareAtLeast": {"dh": 60}, "cpuContentionShareAtLeast": 50,
                                                          "spikesPerMinuteAtLeast": 20},
                set={"key": "dh.common.multiThreading.numberOfThreads", "step": -2, "min": 1})))

    def test_a_future_feature_next_to_stutter_fix_is_allowed(self):
        self.assert_valid(with_fixes(fix(requires=["stutter-fix", "stutter-fix-2"])))

    def test_not_an_array(self):
        self.assert_invalid("stutterFixes must be an array", dict(with_fixes(), stutterFixes={"adviceId": "x"}))

    def test_an_entry_that_isnt_an_object(self):
        self.assert_invalid("stutterFixes[0] must be an object", with_fixes("stutter-sodium-defer"))

    def test_unknown_field(self):
        self.assert_invalid("unknown field(s) v1", with_fixes(fix(v1=False)))
        self.assert_invalid("unknown field(s) text", with_fixes(fix(text="t")))

    def test_unknown_field_in_set(self):
        self.assert_invalid("set: unknown field(s) when", with_fixes(fix(set={"key": "vanilla.renderDistance", "step": -2, "min": 6, "when": {}})))

    def test_null(self):
        self.assert_invalid("null isn't allowed", with_fixes(fix(evidence={"stutterShareAtLeast": {"chunkBuild": None}})))
        self.assert_invalid("null isn't allowed", with_fixes(fix(set={"key": "sodium.performance.chunk_build_defer_mode", "value": None})))

    def test_missing_advice_id(self):
        self.assert_invalid("needs an adviceId", with_fixes(without(fix(), "adviceId")))

    def test_duplicate_advice_id(self):
        self.assert_invalid("duplicate adviceId", with_fixes(fix(), fix()))

    def test_unknown_advice_id(self):
        self.assert_invalid("isn't a stutterAdvice id", with_fixes(fix(adviceId="ram-stutter-gc-heap")))
        self.assert_invalid("isn't a stutterAdvice id", with_fixes(fix(adviceId="vsync-cap"), stutterAdvice=[]))

    def test_needs_the_stutter_fix_feature(self):
        self.assert_invalid('needs "requires": ["stutter-fix"]', with_fixes(without(fix(), "requires")))
        self.assert_invalid('needs "requires": ["stutter-fix"]', with_fixes(fix(requires=["stutter-doctor"])))

    def test_features_that_would_make_0_5_skip_it(self):
        for feature in ("stutter-doctor", "jvm-flags"):
            self.assert_invalid(f"{feature} would make RigTune 0.5 skip this fix", with_fixes(fix(requires=["stutter-fix", feature])))

    def test_needs_evidence(self):
        self.assert_invalid("needs an evidence condition", with_fixes(without(fix(), "evidence")))

    def test_evidence_is_a_condition(self):
        self.assert_invalid("evidence.tierAtLeest: unknown condition key", with_fixes(fix(evidence={"tierAtLeest": 3})))
        self.assert_invalid("evidence must be an object", with_fixes(fix(evidence=[{"always": True}])))

    def test_no_jvm_flag_in_evidence(self):
        self.assert_invalid("doesn't evaluate jvm- flags", with_fixes(fix(evidence={"flags": ["jvm-gc-g1"], "stutterShareAtLeast": {"gc": 30}})))

    def test_cause_spikes_only_in_fix_evidence(self):
        cause = {"causeSpikesAtLeast": {"chunkBuild": 5}}
        self.assert_invalid("allowed only inside stutterFixes[].evidence", sample_knowledge(stutterAdvice=[stutter_advice(when=dict(cause))]))
        self.assert_invalid("allowed only inside stutterFixes[].evidence",
                            sample_knowledge(stutterAdvice=[stutter_advice(when={"not": {"anyOf": [dict(cause)]}})]))
        knowledge = sample_knowledge()
        knowledge["advice"].append({"id": "x", "when": dict(cause), "title": "T", "text": "t", "kind": "info"})
        self.assert_invalid("allowed only inside stutterFixes[].evidence", knowledge)
        knowledge = sample_knowledge()
        knowledge["settings"].append({"key": "vanilla.renderDistance", "value": 8, "when": dict(cause), "v1": False})
        self.assert_invalid("allowed only inside stutterFixes[].evidence", knowledge)
        self.assert_invalid("allowed only inside stutterFixes[].evidence", sample_knowledge(profileTemplates={"templates": [
            {"id": "battery", "goal": "performance", "settings": [{"key": "vanilla.renderDistance", "max": 8, "when": dict(cause)}]}]}))

    def test_cause_spikes_values(self):
        for value, fragment in (([5], "must map causes to whole spike counts"), ({}, "must map causes to whole spike counts"),
                                ({"chunksLoading": 5}, "chunksLoading: not one of"), ({"chunkBuild": -1}, "whole number of spikes"),
                                ({"chunkBuild": 2.5}, "whole number of spikes"), ({"chunkBuild": "5.0"}, "whole number of spikes"),
                                ({"chunkBuild": True}, "whole number of spikes"), ({"chunkBuild": 2 ** 31}, "whole number of spikes")):
            self.assert_invalid(fragment, with_fixes(fix(evidence={"causeSpikesAtLeast": value})))

    def test_needs_a_set(self):
        self.assert_invalid("needs a set", with_fixes(without(fix(), "set")))
        self.assert_invalid("set must be an object", with_fixes(fix(set=["vanilla.renderDistance", -2])))

    def test_a_key_outside_the_allowlist(self):
        for key in ("vanilla.simulationDistance", "sodium.performance.chunk_builder_threads", "mods/sodium.jar", 5):
            self.assert_invalid("set.key must be one of", with_fixes(fix(set={"key": key, "value": 8})))
        self.assert_invalid("set.key must be one of", with_fixes(fix(set={"value": 8})))

    def test_value_and_step_together_or_neither(self):
        self.assert_invalid("needs exactly one of value or step", with_fixes(fix(set={"key": "vanilla.renderDistance", "value": 8, "step": -2, "min": 6})))
        self.assert_invalid("needs exactly one of value or step", with_fixes(fix(set={"key": "vanilla.renderDistance", "min": 6})))

    def test_an_enum_value_outside_the_list(self):
        for value in ("DEFERRED", "always", 0, True):
            self.assert_invalid("set.value must be one of ALWAYS, ONE_FRAME, ZERO_FRAMES",
                                with_fixes(fix(set={"key": "sodium.performance.chunk_build_defer_mode", "value": value})))

    def test_an_int_value_out_of_range_or_of_the_wrong_type(self):
        for key, value in (("vanilla.renderDistance", 1), ("vanilla.renderDistance", 33), ("vanilla.renderDistance", "8"),
                           ("vanilla.renderDistance", 8.5), ("vanilla.renderDistance", True), ("dh.common.multiThreading.numberOfThreads", 0)):
            self.assert_invalid("set.value must be a whole number from", with_fixes(fix(set={"key": key, "value": value})))

    def test_bounds_only_go_with_a_step(self):
        self.assert_invalid("set.min only goes with a step", with_fixes(fix(set={"key": "vanilla.renderDistance", "value": 8, "min": 6})))
        self.assert_invalid("set.max only goes with a step", with_fixes(fix(set={"key": "vanilla.renderDistance", "value": 8, "max": 12})))

    def test_step_shape(self):
        for step in (0, 9, -9, 1.5, "-2", True):
            self.assert_invalid("set.step must be a whole number from -8 to 8, not 0",
                                with_fixes(fix(set={"key": "vanilla.renderDistance", "step": step, "min": 6, "max": 12})))

    def test_a_step_on_an_enum_key(self):
        self.assert_invalid("set.step only works on a number setting",
                            with_fixes(fix(set={"key": "sodium.performance.chunk_build_defer_mode", "step": 1, "max": 2})))

    def test_a_negative_step_needs_min_and_a_positive_one_max(self):
        self.assert_invalid("a negative step needs a min", with_fixes(fix(set={"key": "vanilla.renderDistance", "step": -2, "max": 12})))
        self.assert_invalid("a positive step needs a max", with_fixes(fix(set={"key": "vanilla.renderDistance", "step": 2, "min": 6})))

    def test_bounds_shape(self):
        for bounds, fragment in (({"min": 1}, "set.min must be a whole number from 2 to 32"),
                                 ({"min": "6"}, "set.min must be a whole number from 2 to 32"),
                                 ({"min": 6, "max": 40}, "set.max must be a whole number from 2 to 32"),
                                 ({"min": 12, "max": 6}, "min is above max")):
            self.assert_invalid(fragment, with_fixes(fix(set={"key": "vanilla.renderDistance", "step": -2, **bounds})))

    def test_the_section_never_reaches_rules_v1(self):
        knowledge = with_fixes(fix())
        self.assert_valid(knowledge)
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        self.assertEqual(ur.v2_content(content)["stutterFixes"], [fix()])
        with_section, _ = ur.v1_projection(content)
        without_section, _ = ur.v1_projection(ur.assemble_content(without(knowledge, "stutterFixes"), knowledge["mods"], {}, {}))
        self.assertNotIn("stutterFixes", with_section)
        self.assertEqual(with_section, without_section)


def generated(**changes):
    doc = {"schemaVersion": 2, "revision": 17, "generatedAt": "2026-09-28T03:00:00Z",
           "mods": [{"slug": "sodium", "reason": "r", "upstream": {"fabulouslyOptimized": True, "additive": True}},
                    {"slug": "moonrise-opt", "upstream": {"fabulouslyOptimized": False, "additive": False}}],
           "availability": {"26.3": ["sodium"], "26.2": ["moonrise-opt", "sodium"]},
           "upstream": {"fabulouslyOptimized": {"mcVersion": "26.3", "slugs": ["sodium"]}, "additive": {"mcVersion": "26.3", "slugs": ["sodium"]}}}
    doc.update(changes)
    return doc


# 2T (AC2T.2): the upstream data a weekly bot PR found is carried by the integration branch's generated files.
class UpstreamDiffTests(unittest.TestCase):
    def setUp(self):
        import rules_upstream_diff
        self.diff = rules_upstream_diff.differences

    def test_equal_generated_data_passes(self):
        self.assertEqual(self.diff(generated(), generated()), [])

    def test_revision_generated_at_and_knowledge_fields_are_ignored(self):
        mods = [{"slug": "sodium", "reason": "reworded", "upstream": {"fabulouslyOptimized": True, "additive": True}},
                {"slug": "moonrise-opt", "upstream": {"fabulouslyOptimized": False, "additive": False}},
                {"slug": "new-rule", "upstream": {"fabulouslyOptimized": True, "additive": False}}]
        ours = generated(revision=18, generatedAt="2026-09-29T00:00:00Z", mods=mods, advice=[{"id": "x"}],
                         availability={"26.3": ["new-rule", "sodium"], "26.2": ["moonrise-opt", "sodium"]})
        self.assertEqual(self.diff(generated(), ours), [])

    def test_availability_changes_are_reported(self):
        bot = generated(availability={"26.3": ["moonrise-opt", "sodium"], "26.2": ["moonrise-opt", "sodium"]})
        self.assertEqual(self.diff(bot, generated()), ["availability 26.3: moonrise-opt only in the first file"])
        self.assertEqual(self.diff(generated(availability={"26.2": ["moonrise-opt", "sodium"]}), generated()),
                         ["availability 26.3: only in the second file"])

    def test_upstream_changes_are_reported(self):
        bot = generated(upstream={"fabulouslyOptimized": {"mcVersion": "26.3", "slugs": ["sodium", "zoomify"]},
                                  "additive": {"mcVersion": "26.3", "slugs": ["sodium"]}})
        self.assertEqual(self.diff(bot, generated()), ["upstream fabulouslyOptimized: zoomify only in the first file"])
        bot = generated(upstream={"fabulouslyOptimized": {"mcVersion": "26.4", "slugs": ["sodium"]},
                                  "additive": {"mcVersion": "26.3", "slugs": ["sodium"]}})
        self.assertEqual(self.diff(bot, generated()), ["upstream fabulouslyOptimized: mcVersion 26.4 vs 26.3"])

    def test_a_mods_upstream_flags_are_reported(self):
        bot = generated(mods=[{"slug": "sodium", "upstream": {"fabulouslyOptimized": True, "additive": True}},
                              {"slug": "moonrise-opt", "upstream": {"fabulouslyOptimized": True, "additive": False}}])
        self.assertEqual(self.diff(bot, generated()), ["mods[moonrise-opt].upstream: {'additive': False, 'fabulouslyOptimized': True} vs "
                                                       "{'additive': False, 'fabulouslyOptimized': False}"])

    def test_main_exit_codes(self):
        import rules_upstream_diff
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.json", Path(tmp) / "b.json"
            a.write_text(json.dumps(generated()), encoding="utf-8")
            b.write_text(json.dumps(generated()), encoding="utf-8")
            self.assertEqual(rules_upstream_diff.main([str(a), str(b)]), 0)
            b.write_text(json.dumps(generated(availability={"26.2": []})), encoding="utf-8")
            self.assertEqual(rules_upstream_diff.main([str(a), str(b)]), 1)
            self.assertEqual(rules_upstream_diff.main([str(a), str(Path(tmp) / "missing.json")]), 2)


# The generated files as the release revision R carries them (v0.5 content; docs/v0.5/design/ws-r.md).
class GeneratedV05Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v2 = repo_json("rules", "rules-v2.json")
        cls.v1 = repo_json("rules", "rules-v1.json")
        cls.r16 = repo_json("src", "test", "resources", "rules", "r16", "rules-v2.json")

    def stutter(self, advice_id):
        return next(a for a in self.v2["stutterAdvice"] if a["id"] == advice_id)

    # L4 (AC2R.1): no rule names a hardware type for a combined-tier condition any more; ids such as shaders-entry-level stay.
    def test_no_entry_level_wording(self):
        def texts(node, path=""):
            if isinstance(node, dict):
                for key, value in node.items():
                    if key in ("reason", "avoidReason", "text", "title") and isinstance(value, str):
                        yield f"{path}.{key}", value
                    else:
                        yield from texts(value, f"{path}.{key}")
            elif isinstance(node, list):
                for i, value in enumerate(node):
                    yield from texts(value, f"{path}[{i}]")

        for name, doc in (("knowledge.json", repo_json("rules", "source", "knowledge.json")), ("rules-v2.json", self.v2),
                          ("rules-v1.json", self.v1)):
            found = [where for where, text in texts(doc) if "entry-level" in text.lower()]
            self.assertEqual(found, [], name)
        self.assertIn("shaders-entry-level", [a["id"] for a in self.v2["advice"]])

    # AC2R.1: against r16, the settings are the same entries in the same order and only L4's seven reasons differ, in both
    # files (so keys, values, bounds, conditions and ticks are untouched).
    def test_l4_changed_only_reasons(self):
        r16_v1 = repo_json("src", "test", "resources", "rules", "r16", "rules-v1.json")
        for name, old, new in (("rules-v2.json", self.r16, self.v2), ("rules-v1.json", r16_v1, self.v1)):
            self.assertEqual(len(old["settings"]), len(new["settings"]), name)
            changed = []
            for i, (before, after) in enumerate(zip(old["settings"], new["settings"])):
                self.assertEqual({k: v for k, v in before.items() if k != "reason"}, {k: v for k, v in after.items() if k != "reason"}, f"{name} settings[{i}]")
                if before.get("reason") != after.get("reason"):
                    changed.append(i)
                    self.assertIn("entry-level", before["reason"], f"{name} settings[{i}]")
                    self.assertIn("estimated tier", after["reason"], f"{name} settings[{i}]")
            expected = [i for i, before in enumerate(old["settings"]) if "entry-level" in before.get("reason", "")]
            self.assertEqual(changed, expected, name)
            self.assertEqual(len(expected), 7 if name == "rules-v2.json" else 4, name)

    # C20 (SPEC 5, sf §2.2): the three seeds with their UNVERIFIED starting thresholds (AC5.14 calibrates them), DH last so
    # it can be cut first; the section is v2-only and rules-v1.json is the same with and without it (AC5.1).
    def test_the_c20_seeds(self):
        self.assertEqual(self.v2["stutterFixes"], [
            {"adviceId": "stutter-sodium-defer", "requires": ["stutter-fix"],
             "evidence": {"stutterShareAtLeast": {"chunkBuild": 40}, "causeSpikesAtLeast": {"chunkBuild": 5}},
             "set": {"key": "sodium.performance.chunk_build_defer_mode", "value": "ALWAYS"}},
            {"adviceId": "stutter-chunk-loading", "requires": ["stutter-fix"],
             "evidence": {"stutterShareAtLeast": {"chunkLoad": 40}, "causeSpikesAtLeast": {"chunkLoad": 5}},
             "set": {"key": "vanilla.renderDistance", "step": -2, "min": 6}},
            {"adviceId": "stutter-dh-threads", "requires": ["stutter-fix"],
             "evidence": {"stutterTaggedShareAtLeast": {"dh": 60}, "cpuContentionShareAtLeast": 50, "spikesPerMinuteAtLeast": 20},
             "set": {"key": "dh.common.multiThreading.numberOfThreads", "step": -2, "min": 1}},
        ])
        self.assertNotIn("stutterFixes", self.v1)
        knowledge = repo_json("rules", "source", "knowledge.json")
        with_fixes_v1, _ = ur.v1_projection(repo_content(knowledge))
        without_v1, _ = ur.v1_projection(repo_content(without(knowledge, "stutterFixes")))
        self.assertEqual(with_fixes_v1, without_v1)
        self.assertEqual(ur.strip_meta(self.v1), with_fixes_v1)

    # 4i (AC4i.1, AC4i.2): one warning, only for clients below 0.5.0 (0.2.0-0.4.0 through modVersion, 0.1.x through the v1
    # override), after the last r16 advice; minModVersion unchanged; no other advice added since r16.
    def test_the_old_client_warning(self):
        rule = next(a for a in self.v2["advice"] if a["id"] == "old-client-launcher-mods")
        self.assertEqual(rule["when"], {"modVersion": {"rigtune": "<0.5.0-"}})
        self.assertEqual((rule["kind"], sorted(rule)), ("warning", ["id", "impact", "kind", "text", "title", "when"]))
        v1_rule = next(a for a in self.v1["advice"] if a["id"] == "old-client-launcher-mods")
        self.assertEqual(v1_rule, dict(rule, when={"always": True}))
        for text in ("Modrinth App", "CurseForge", "ATLauncher", "GDLauncher", "Prism", "Install, Update and Disable",
                     "Content → Disabled", "RigTune 0.5"):
            self.assertIn(text, rule["text"])
        r16_v1 = repo_json("src", "test", "resources", "rules", "r16", "rules-v1.json")
        for new, old in ((self.v2, self.r16), (self.v1, r16_v1)):
            self.assertEqual([a["id"] for a in new["advice"]], [a["id"] for a in old["advice"]] + ["old-client-launcher-mods"])
            self.assertEqual(new.get("minModVersion"), old.get("minModVersion"))

    # L2 (SPEC 2S): one info entry on the tag, calibrated on P5C-F1's re-runs and AC5.8's A control (ChunksLoadingSeedTest).
    def test_the_l2_seed(self):
        seed = self.stutter("stutter-chunks-loading-tag")
        self.assertEqual(seed["requires"], ["stutter-doctor"])
        self.assertEqual((seed["kind"], seed["impact"]), ("info", "low"))
        self.assertEqual(seed["when"], {"stutterTaggedShareAtLeast": {"chunksLoading": 60}, "spikesPerMinuteAtLeast": 20})
        self.assertIn("happened while chunks were loading", seed["text"])
        self.assertIn("may reduce them", seed["text"])
        self.assertNotIn("v1", seed)
        self.assertNotIn("stutterAdvice", self.v1)


if __name__ == "__main__":
    unittest.main()
