import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import fixtures  # noqa: E402

BS = chr(92)


class TemplateTest(unittest.TestCase):
    def test_replaces_the_instance_root_in_every_spelling(self):
        root = BS.join(["C:", "Users", "Admin", "run", "instance"])
        pending = json.dumps({"modsDir": root + BS + "mods", "ops": [{"to": root + BS + "mods" + BS + "a.jar"}]})
        log = "Applying 2 operation(s) from " + root + BS + "config" + BS + "rigtune" + BS + "pending.json\n"
        forward = "path " + root.replace(BS, "/") + "/mods\n"
        lower = "c:" + BS + "users" + BS + "admin" + BS + "run" + BS + "instance" + BS + "mods"

        self.assertEqual({"modsDir": "${INSTANCE}" + BS + "mods", "ops": [{"to": "${INSTANCE}" + BS + "mods" + BS + "a.jar"}]},
                         json.loads(fixtures.template(pending, root)))
        self.assertEqual("Applying 2 operation(s) from ${INSTANCE}" + BS + "config" + BS + "rigtune" + BS + "pending.json\n",
                         fixtures.template(log, root))
        self.assertEqual("path ${INSTANCE}/mods\n", fixtures.template(forward, root))
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
        dest = base / "captured"

        written = fixtures.capture({"last-apply.json": source, "missing.json": instance / "nope.json"}, instance, dest)

        self.assertEqual(["last-apply.json"], [p.name for p in written])
        captured = json.loads((dest / "last-apply.json").read_text())
        self.assertTrue(captured["path"].startswith("${INSTANCE}"), captured)


if __name__ == "__main__":
    unittest.main()
