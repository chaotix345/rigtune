import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import self_update_e2e  # noqa: E402


class FilteredLogTest(unittest.TestCase):
    def test_keeps_rigtune_driver_problems_and_the_mod_list(self):
        log = Path(tempfile.mkdtemp()) / "latest.log"
        log.write_text("\n".join([
            "[10:00:00] [main/INFO] (FabricLoader) Loading 4 mods:",
            "\t- fabric-api 0.161.0+26.2",
            "\t- rigtune 0.1.0",
            "[10:00:01] [main/INFO] (Minecraft) Setting user: Player123",
            "[10:00:02] [RigTune E2E/INFO] [E2E] t+1.0s title screen",
            "[10:00:03] [Render thread/WARN] (Minecraft) Something odd",
            "[10:00:04] [main/INFO] (RigTune) Hardware: ...",
            "[10:00:05] [main/INFO] (Minecraft) Sound engine started",
        ]), encoding="utf-8")

        kept = self_update_e2e.filtered_log(log).splitlines()

        self.assertEqual(["Loading 4 mods", "fabric-api", "rigtune 0.1.0", "[E2E]", "Something odd", "Hardware"],
                         [next(w for w in ("Loading 4 mods", "fabric-api", "rigtune 0.1.0", "[E2E]", "Something odd", "Hardware")
                               if w in line) for line in kept])


class ArgsTest(unittest.TestCase):
    def test_defaults(self):
        args = self_update_e2e.parse_args(["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", "w",
                                           "--java-home", "jdk"])
        self.assertEqual(("26.2", 443, self_update_e2e.DEFAULT_LOCK, None), (args.mc, args.port, args.lock, args.driver_api_jar))
        self.assertFalse(args.expect_history)


if __name__ == "__main__":
    unittest.main()
