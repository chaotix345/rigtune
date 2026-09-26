"""Checks on the GENERATED rules files (docs/v0.4/SPEC.md AC2k.1, AC4.10, AC5.6, AC6.5, 9): what 0.1.x and 0.2+ download
must carry the v0.4 content as reviewed, and nothing v2-only may reach rules-v1.json. Reads the repository's files."""

import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import update_rules as ur

REPO = Path(__file__).resolve().parent.parent.parent
CAP_REASON = ("Avoids rendering frames your monitor can't show; with FreeSync or G-Sync it also keeps the frame rate inside the "
              "variable-refresh range.")
VSYNC_REASON = "Optional: turning VSync off lowers input lag but can cause tearing; leave it on if you see tearing."
VRR_WHEN = {"refreshRateAtLeast": 30, "not": {"onBattery": True}}
# AC6.5: every number in a jvm-* advice text traces to docs/research/v0.4/jvm-gc.md §4 (or is a threshold or fact SPEC 6
# sets), per rule, so a number can't wander into a claim it doesn't support.
JVM_TEXT_NUMBERS = {
    "jvm-server-flags": {
        "1": "the \"1% low\" metric",
        "4096": "Aikar's set committed all 4096 MB at -Xms4G -Xmx4G, §4.2",
        "4": "the -Xmx4G heap of every §4.2 run",
        "1.3": "G1 default committed 1316 MB at -Xmx4G, §4.2",
        "16": "the 16 GB RAM threshold (SPEC 6)",
    },
    "jvm-zgc-small-heap": {
        "2": "ZGC at -Xmx2G, §4.3",
        "1": "the \"1% low\" metric: 564 at 2 GB vs 627 at 4 GB, §4.3",
        "4": "ZGC at -Xmx4G, §4.3; 4 GB is also what ram-low recommends",
    },
    "jvm-zgc-small-pc": {
        "3.9": "ZGC committed 3852 MB at -Xmx4G, §4.2 (3.9 GB in §0)",
        "4": "the -Xmx4G heap, §4.2",
        "1.3": "G1 default committed 1316 MB at -Xmx4G, §4.2",
        "8": "the 8 GB RAM threshold (SPEC 6)",
        "26.1": "Mojang's launcher defaults to ZGC from 26.1 (launcher-steps.md finding 5)",
    },
}
# J-M2: no -Xms advice; only jvm-server-flags names it, as part of Aikar's set it describes.
XMS_ALLOWED = {"jvm-server-flags"}
NUMBER_RE = re.compile(r"(?<![\w.+-])\d+(?:\.\d+)?")


def load(*parts):
    return json.loads((REPO.joinpath(*parts)).read_text(encoding="utf-8"))


class GeneratedRulesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v2 = load("rules", "rules-v2.json")
        cls.v1 = load("rules", "rules-v1.json")
        cls.bundled = load("src", "main", "resources", "rigtune", "rules-v2.json")
        cls.knowledge = load("rules", "source", "knowledge.json")
        cls.raw = {name: (REPO / "rules" / name).read_text(encoding="utf-8") for name in ("rules-v2.json", "rules-v1.json")}

    def entries(self, doc, key, value):
        return [s for s in doc["settings"] if s["key"] == key and s.get("value") == value and s.get("when") == VRR_WHEN]

    # AC2k.1
    def test_vsync_off_is_unticked_with_honest_reasons_in_both_files(self):
        for name, doc in (("rules-v2.json", self.v2), ("rules-v1.json", self.v1)):
            vsync = self.entries(doc, "vanilla.enableVsync", False)
            self.assertEqual(len(vsync), 1, name)
            self.assertIs(vsync[0].get("defaultSelected"), False, name)
            self.assertEqual(vsync[0]["reason"], VSYNC_REASON, name)
            cap = self.entries(doc, "vanilla.maxFps", "$refreshRateCap")
            self.assertEqual(len(cap), 1, name)
            self.assertEqual(cap[0]["reason"], CAP_REASON, name)
            self.assertNotIn("defaultSelected", cap[0], name)
            self.assertNotIn("keeps FreeSync", self.raw[name], name)

    def test_one_revision_and_the_bundled_copy(self):
        self.assertEqual(self.v2, self.bundled)
        self.assertEqual(self.v1["revision"], self.v2["revision"])
        self.assertEqual(self.v1["generatedAt"], self.v2["generatedAt"])
        self.assertEqual(self.v1["schemaVersion"], 1)
        self.assertEqual(self.v2["schemaVersion"], 2)

    def test_nothing_v2_only_reaches_v1(self):
        for section in ("profileTemplates", "stutterAdvice", "settingLabels"):
            self.assertIn(section, self.v2)
            self.assertNotIn(section, self.v1)
        self.assertFalse([a["id"] for a in self.v1["advice"] if a["id"].startswith(("jvm-", "driver-", "stutter-", "ram-stutter"))])
        for fragment in ("driverVersion", '"jvm-', "requires", "stutter-doctor", "$recordingFps"):
            self.assertNotIn(fragment, self.raw["rules-v1.json"], fragment)

    def test_every_new_advice_is_v1_false_in_the_source(self):
        new = [a for a in self.knowledge["advice"] if a["id"].startswith(("jvm-", "driver-"))]
        self.assertEqual(len(new), 11)
        for rule in new:
            self.assertIs(rule.get("v1"), False, rule["id"])

    # AC4.10 + SPEC 4's template definitions.
    def test_profile_templates(self):
        templates = {t["id"]: t for t in self.v2["profileTemplates"]["templates"]}
        self.assertEqual(list(templates), list(ur.PROFILE_TEMPLATE_IDS))
        self.assertEqual({t: templates[t]["goal"] for t in templates},
                         {"max_fps": "performance", "balanced": "balanced", "quality": "quality", "battery": "performance", "recording": "balanced"})
        settings = {t: {(s["key"], json.dumps(s.get("value", {k: s[k] for k in ("min", "max") if k in s}))): s for s in templates[t].get("settings", [])}
                    for t in templates}
        self.assertIn(("vanilla.maxFps", "260"), settings["max_fps"])
        self.assertIn(("vanilla.enableVsync", "false"), settings["max_fps"])
        self.assertEqual(templates["battery"]["facts"], {"onBattery": True, "hasBattery": True})
        battery = settings["battery"]
        self.assertEqual(battery[("dh.client.advanced.debugging.rendererMode", '"DISABLED"')]["when"], {"modPresent": ["distanthorizons"]})
        self.assertEqual(battery[("iris.enableShaders", "false")]["when"], {"modPresent": ["iris"]})
        for key in (("vanilla.renderClouds", '"false"'), ("vanilla.renderDistance", '{"max": 8}'),
                    ("vanilla.simulationDistance", '{"max": 6}'), ("vanilla.particles", '{"min": 1}')):
            self.assertIn(key, battery)
        recording = settings["recording"]
        for key in (("vanilla.maxFps", '"$recordingFps"'), ("vanilla.inactivityFpsLimit", '"minimized"'),
                    ("sodium.performance.chunk_build_defer_mode", '"ALWAYS"'), ("vanilla.prioritizeChunkUpdates", "0")):
            self.assertIn(key, recording)
        self.assertEqual(recording[("vanilla.enableVsync", "true")]["when"], {"not": {"refreshRateAtLeast": 61}})
        self.assertEqual(recording[("vanilla.enableVsync", "false")]["when"], {"refreshRateAtLeast": 61})
        self.assertNotIn("settings", templates["balanced"])
        self.assertNotIn("settings", templates["quality"])

    # AC5.6 + SPEC 5's advice rules.
    def test_stutter_advice(self):
        rules = self.v2["stutterAdvice"]
        self.assertEqual([r["id"] for r in rules],
                         ["ram-stutter-gc-heap", "stutter-gc-explicit", "stutter-sodium-defer", "stutter-dh-threads", "stutter-chunk-loading"])
        for rule in rules:
            self.assertEqual(rule["requires"], ["stutter-doctor"], rule["id"])
            if "heapRaiseRoomMbAtLeast" in json.dumps(rule["when"]):
                self.assertTrue(rule["id"].startswith("ram-"), rule["id"])
            for banned in ("DisableExplicitGC", "ZGC", "Shenandoah", "caused", "because of"):
                self.assertNotIn(banned, rule["text"] + rule["title"], rule["id"])
        self.assertIn("no frame-rate gain over Java's defaults", rules[0]["text"], "SPEC 6's measured conclusion")

    # SPEC 6 (amended) and AC6.5.
    def test_jvm_advice(self):
        rules = {a["id"]: a for a in self.v2["advice"] if a["id"].startswith("jvm-")}
        self.assertEqual(set(rules), {"jvm-ignored-flags", "jvm-young-gen-fixed", "jvm-stop-the-world-gc", "jvm-no-gc", "jvm-server-flags",
                                      "jvm-explicit-gc-disabled", "jvm-zgc-small-heap", "jvm-zgc-small-pc", "jvm-xmx-duplicate"})
        kinds = {"jvm-young-gen-fixed": "warning", "jvm-stop-the-world-gc": "warning", "jvm-no-gc": "critical"}
        for rule_id, rule in rules.items():
            self.assertEqual(rule["requires"], ["jvm-flags"], rule_id)
            self.assertEqual(rule["kind"], kinds.get(rule_id, "info"), rule_id)
            for number in NUMBER_RE.findall(rule["text"]):
                self.assertIn(number, JVM_TEXT_NUMBERS.get(rule_id, {}), f"{rule_id}: {number}")
            if rule_id not in XMS_ALLOWED:
                self.assertNotIn("-Xms", rule["text"], f"{rule_id}: no -Xms advice (J-M2)")
            self.assertNotIn("AlwaysPreTouch", rule["text"], rule_id)
        self.assertEqual(rules["jvm-server-flags"]["when"]["ramMbAtMost"], 16384)
        self.assertEqual(rules["jvm-zgc-small-heap"]["when"]["heapMbAtMost"], 3072)
        # Coordinator decision (self-review, option C): small-heap only above 8 GB, so an 8 GB PC is never sent from one ZGC
        # rule to the other (KnowledgeV2ScenarioTest.zgcAdviceNeverLoops).
        self.assertEqual(rules["jvm-zgc-small-heap"]["when"], {"flags": ["jvm-gc-zgc"], "heapMbAtMost": 3072, "ramMbAtLeast": 8193})
        self.assertIn("test PC, ZGC with 2 GB gave slightly lower 1% lows than with 4 GB", rules["jvm-zgc-small-heap"]["text"])
        self.assertEqual(rules["jvm-zgc-small-pc"]["when"], {"flags": ["jvm-gc-zgc"], "ramMbAtMost": 8192, "heapMbAtLeast": 4096})
        for rule_id in ("jvm-zgc-small-heap", "jvm-zgc-small-pc"):
            for claim in ("FPS", "frame rate", "should"):
                self.assertNotIn(claim, rules[rule_id]["text"], rule_id)
        self.assertIn("official Minecraft Launcher's default since 26.1", rules["jvm-zgc-small-pc"]["text"])

    # SPEC 9: the two verified seeds only.
    def test_driver_seeds(self):
        rules = {a["id"]: a for a in self.v2["advice"] if "driverVersion" in json.dumps(a.get("when", {}))}
        self.assertEqual(set(rules), {"driver-nvidia-threaded-optimization", "driver-intel-gen7-old"})
        nvidia = rules["driver-nvidia-threaded-optimization"]
        self.assertEqual(nvidia["when"], {"os": ["windows"], "gpuVendor": ["nvidia"],
                                          "driverVersion": {"vendor": "nvidia", "atLeast": "526.47", "atMost": "536.22"}})
        self.assertIn("Sodium issue #1486", nvidia["text"])
        intel = rules["driver-intel-gen7-old"]
        self.assertEqual(intel["when"]["driverVersion"], {"vendor": "intel", "atMost": "10.18.10.5160"})
        self.assertIn("Sodium issue #899", intel["text"])
        for rule in rules.values():
            self.assertEqual(rule["kind"], "warning")
            self.assertNotIn("requires", rule)

    # Phase 5 P5B-F6: the rule fires on the estimated (effective) tier, which memory alone can lower on a fast CPU, so its
    # reason names no component as the one that's short.
    def test_lambdynamiclights_reason_follows_the_estimated_tier(self):
        for name, doc in (("rules-v2.json", self.v2), ("knowledge.json", self.knowledge)):
            mod = next(m for m in doc["mods"] if m["slug"] == "lambdynamiclights")
            self.assertEqual(mod["avoidWhen"], {"tierAtMost": 2}, name)
            self.assertNotIn("entry-level", mod["avoidReason"], name)
            self.assertNotIn("short of", mod["avoidReason"], name)
            self.assertIn("estimated tier", mod["avoidReason"], name)


if __name__ == "__main__":
    unittest.main()
