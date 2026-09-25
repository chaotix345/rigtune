import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import fixtures  # noqa: E402

BS = chr(92)
NL = chr(10)
QUOTE = chr(34)


class TemplateTest(unittest.TestCase):
    def test_replaces_the_instance_root_in_every_spelling(self):
        root = BS.join(["C:", "Users", "Admin", "run", "instance"])
        pending = json.dumps({"modsDir": root + BS + "mods", "ops": [{"to": root + BS + "mods" + BS + "a.jar"}]})
        log = "Applying 2 operation(s) from " + root + BS + "config" + BS + "rigtune" + BS + "pending.json" + NL
        forward = "path " + root.replace(BS, "/") + "/mods" + NL
        lower = "c:" + BS + "users" + BS + "admin" + BS + "run" + BS + "instance" + BS + "mods"

        self.assertEqual({"modsDir": "${INSTANCE}" + BS + "mods", "ops": [{"to": "${INSTANCE}" + BS + "mods" + BS + "a.jar"}]},
                         json.loads(fixtures.template(pending, root)))
        self.assertEqual("Applying 2 operation(s) from ${INSTANCE}" + BS + "config" + BS + "rigtune" + BS + "pending.json" + NL,
                         fixtures.template(log, root))
        self.assertEqual("path ${INSTANCE}/mods" + NL, fixtures.template(forward, root))
        self.assertEqual("${INSTANCE}" + BS + "mods", fixtures.template(lower, root))
        self.assertNotIn("Admin", fixtures.template(pending + log + forward, root))

    def test_other_paths_are_left_alone(self):
        self.assertEqual("C:" + BS + "Other", fixtures.template("C:" + BS + "Other", "C:" + BS + "Users"))

    def test_capture_writes_templated_copies(self):
        base = Path(tempfile.mkdtemp())
        instance = base / "instance"
        (instance / "config").mkdir(parents=True)
        source = instance / "config" / "last-apply.json"
        source.write_text(json.dumps({"path": str(instance / "mods" / "x.jar")}))
        log = instance / "config" / "helper.log"
        log.write_text("from " + str(instance / "config" / "pending.json") + " done" + NL)
        dest = base / "captured"

        written = fixtures.capture({"last-apply.json": source, "helper.log": log, "missing.json": instance / "nope.json"},
                                   instance, dest)

        self.assertEqual(["last-apply.json", "helper.log"], [p.name for p in written])
        captured = json.loads((dest / "last-apply.json").read_text())
        self.assertEqual("${INSTANCE}/mods/x.jar", captured["path"])
        self.assertEqual("from ${INSTANCE}/config/pending.json done" + NL, (dest / "helper.log").read_text())

    def test_json_rewrites_only_string_values_that_start_with_the_root(self):
        root = BS.join(["C:", "Users", "Admin", "run", "instance"])
        tricky = "a" + BS + QUOTE + "b" + QUOTE + NL
        original = {"modsDir": root + BS + "mods", "note": "see " + root, "quote": tricky, "gamePid": 9204,
                    "ops": [{"to": root.upper() + BS + "mods" + BS + "x+y.jar", "patches": None}]}

        text = fixtures.template_json(json.dumps(original, indent=2) + NL, root)

        self.assertEqual({"modsDir": "${INSTANCE}/mods", "note": "see " + root, "quote": tricky, "gamePid": 9204,
                          "ops": [{"to": "${INSTANCE}/mods/x+y.jar", "patches": None}]}, json.loads(text))
        self.assertTrue(text.startswith("{" + NL + "  " + QUOTE + "modsDir" + QUOTE), text)
        self.assertTrue(text.endswith("}" + NL))

    def test_portable_paths_use_forward_slashes_after_the_token(self):
        log = "from ${INSTANCE}" + BS + "config" + BS + "pending.json done"

        self.assertEqual("from ${INSTANCE}/config/pending.json done", fixtures.portable_paths(log))


class SeedTemplateTest(unittest.TestCase):
    """H-M2: the user's real 0.1.0 files, templated. Unlike template_json, paths inside messages are templated too."""

    ROOT = BS.join(["C:", "Users", "Admin", "AppData", "Roaming", "ModrinthApp", "profiles", "Fabric 26.2"])

    def real_last_apply(self):
        mods = self.ROOT + BS + "mods" + BS
        return json.dumps({"finishedAt": "2026-09-24T23:09:01.530708800Z", "results": [{
            "op": {"type": "DISABLE_FILE", "path": mods + "fabric-26.2.jar", "id": "0419", "group": "7b80", "attempts": 0},
            "status": "FAILED",
            "message": "Gave up after 10 attempt(s): java.nio.file.FileSystemException: " + mods + "fabric-26.2.jar -> "
                       + mods + "fabric-26.2.jar.disabled: The process cannot access the file"}]}, indent=2) + NL

    def test_every_spelling_of_the_root_becomes_the_token_with_forward_slashes(self):
        text = fixtures.template_seed_json(self.real_last_apply(), self.ROOT)
        result = json.loads(text)["results"][0]

        self.assertEqual("${INSTANCE}/mods/fabric-26.2.jar", result["op"]["path"])
        self.assertEqual("Gave up after 10 attempt(s): java.nio.file.FileSystemException: ${INSTANCE}/mods/fabric-26.2.jar -> "
                         "${INSTANCE}/mods/fabric-26.2.jar.disabled: The process cannot access the file", result["message"])
        self.assertEqual(0, result["op"]["attempts"])
        self.assertNotIn("Admin", text)
        self.assertTrue(text.endswith("}" + NL))

    def test_instantiate_puts_the_instance_back_with_native_separators(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        text = fixtures.instantiate_json(fixtures.template_seed_json(self.real_last_apply(), self.ROOT), instance)
        result = json.loads(text)["results"][0]

        self.assertEqual(str(instance / "mods" / "fabric-26.2.jar"), result["op"]["path"])
        self.assertIn(str(instance / "mods" / "fabric-26.2.jar.disabled") + ":", result["message"])
        self.assertNotIn(fixtures.TOKEN, text)

    def test_instantiate_a_whole_path_value_with_spaces(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        text = json.dumps({"path": "${INSTANCE}/mods/update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar"})
        self.assertEqual(str(instance / "mods" / "update" / "DistantHorizons-3.3.2 - 26.2 neo" / "fabric-26.2.jar"),
                         json.loads(fixtures.instantiate_json(text, instance))["path"])

    def test_instantiate_leaves_other_values_alone(self):
        text = json.dumps({"gamePid": 12228, "note": "no path", "ops": [{"attempts": 1, "path": None}]})
        self.assertEqual(json.loads(text), json.loads(fixtures.instantiate_json(text, Path("x"))))


if __name__ == "__main__":
    unittest.main()
