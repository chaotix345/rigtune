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

    def test_expect_history_values(self):
        base = ["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", "w", "--java-home", "jdk"]
        self.assertEqual("legacy-import", self_update_e2e.parse_args(base + ["--expect-history"]).expect_history)
        self.assertEqual("own-update", self_update_e2e.parse_args(base + ["--expect-history", "own-update"]).expect_history)
        self.assertIsNone(self_update_e2e.parse_args(base).expect_history)


def make_run(tmp, *extra):
    args = self_update_e2e.parse_args(["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", str(tmp),
                                       "--java-home", "jdk", "--lock", str(Path(tmp) / "lock")] + list(extra))
    run = self_update_e2e.Run(args)
    run.run_dir.mkdir(parents=True)
    return run


class LockTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.run = make_run(self.tmp)
        self.lock = self.tmp / "lock"

    def test_owner_txt_follows_the_plan_protocol(self):
        text = self_update_e2e.owner_text("ws-h", Path("C:/Dev/Worktrees/rigtune-e2e3"), Path("C:/tmp/run-1"),
                                          "2026-09-26T10:00:00+00:00")
        lines = text.splitlines()
        self.assertEqual("agent: ws-h", lines[0])
        self.assertEqual("worktree: C:/Dev/Worktrees/rigtune-e2e3", lines[1])
        self.assertEqual("started: 2026-09-26T10:00:00+00:00", lines[2])
        self.assertEqual("run: " + str(Path("C:/tmp/run-1")), lines[3])

    def test_take_writes_owner_txt_and_release_removes_the_lock(self):
        self.run.take_lock()
        owner = (self.lock / "owner.txt").read_text(encoding="utf-8")
        self.assertIn("agent: ws-h", owner)
        self.assertTrue(self_update_e2e.owns_lock(owner, self.run.run_dir))
        self.run.release_lock()
        self.assertFalse(self.lock.exists())

    def test_agent_can_be_named(self):
        run = make_run(self.tmp, "--agent", "p5-verify")
        run.take_lock()
        self.assertIn("agent: p5-verify", (self.lock / "owner.txt").read_text(encoding="utf-8"))
        run.release_lock()

    def test_busy_lock_raises_with_the_owner(self):
        self.lock.mkdir()
        (self.lock / "owner.txt").write_text("agent: ws-e\n", encoding="utf-8")
        with self.assertRaises(self_update_e2e.LockBusy) as busy:
            self.run.take_lock()
        self.assertIn("ws-e", str(busy.exception))
        self.assertTrue((self.lock / "owner.txt").is_file())

    def test_release_leaves_another_runs_lock(self):
        self.lock.mkdir()
        other = self_update_e2e.owner_text("ws-e", Path("C:/x"), self.tmp / "other-run", "now")
        (self.lock / "owner.txt").write_text(other, encoding="utf-8")
        self.run.release_lock()
        self.assertEqual(other, (self.lock / "owner.txt").read_text(encoding="utf-8"))

    def test_release_never_deletes_other_files(self):
        self.run.take_lock()
        (self.lock / "note.txt").write_text("someone else's", encoding="utf-8")
        self.run.release_lock()
        self.assertTrue((self.lock / "note.txt").is_file())

    def test_owns_lock_matches_the_whole_run_line(self):
        text = self_update_e2e.owner_text("ws-h", Path("C:/r"), Path("C:/tmp/run-10"), "now")
        self.assertTrue(self_update_e2e.owns_lock(text, Path("C:/tmp/run-10")))
        self.assertFalse(self_update_e2e.owns_lock(text, Path("C:/tmp/run-1")))


class UndoScenarioTest(unittest.TestCase):
    def test_the_undo_scenario_ends_with_the_per_entry_phases(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo")
        self.assertEqual(["mod-apply", "mod-undo", "mod-check", "entry-apply", "entry-undo", "entry-check"], list(run.checks))

    def test_entry_id_is_added_to_a_phase_after_the_earlier_launch(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo")
        (run.run_dir / "jvm-entry-undo.txt").write_text("-Drigtune.e2e.phase=entry-undo\n", encoding="utf-8")
        run.add_jvm_args("entry-undo", ["-Drigtune.e2e.entryId=a1", "-Drigtune.e2e.entryMod=e2e-first"])
        self.assertEqual(["-Drigtune.e2e.phase=entry-undo", "-Drigtune.e2e.entryId=a1", "-Drigtune.e2e.entryMod=e2e-first"],
                         (run.run_dir / "jvm-entry-undo.txt").read_text(encoding="utf-8").splitlines())


class HelperLogTest(unittest.TestCase):
    def test_only_text_after_the_offset_counts(self):
        log = Path(tempfile.mkdtemp()) / "helper.log"
        self.assertEqual("", self_update_e2e.helper_log_tail(log, 0))
        log.write_bytes(b"[a] All operations done\n")
        offset = log.stat().st_size
        self.assertEqual("", self_update_e2e.helper_log_tail(log, offset))
        with open(log, "a", encoding="utf-8", newline="") as out:
            out.write("[b] Nothing to apply\n")
        self.assertEqual("[b] Nothing to apply\n", self_update_e2e.helper_log_tail(log, offset))


if __name__ == "__main__":
    unittest.main()
