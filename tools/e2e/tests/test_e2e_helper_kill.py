"""The helper-kill scenario (docs/v0.5/SPEC.md 3f, AC3f.5): a staged two-op group, op 2 blocked, the helper killed during
its retries; the next exit's helper finishes the group; the next start loads it and History shows it applied."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_matrix  # noqa: E402
import self_update_e2e as su  # noqa: E402

REPO = Path(__file__).resolve().parents[3]


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class Instance:
    """The instance with the group staged (kill_group), mods/ as the test sets it, and the helper's files."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.mods = self.root / "mods"
        self.config = self.root / "config" / "rigtune"
        self.mods.mkdir(parents=True)
        self.config.mkdir(parents=True)
        pending, history = su.kill_group(self.root, "0.5.0+mc26.2", "26.2")
        self.write("pending.json", pending)
        self.write("history.json", history)

    def write(self, name, data):
        (self.config / name).write_text(json.dumps(data), encoding="utf-8")

    def mods_as(self, *names):
        for p in self.mods.iterdir():
            p.unlink()
        for name in names:
            (self.mods / name).write_bytes(b"jar")

    def record(self, *groups):
        self.write("unfinished-groups.json", {"groups": [{"group": g, "renames": []} for g in groups]})

    def applied(self, *statuses):
        results = [{"op": {"id": i}, "status": s} for i, s in zip(su.KILL_OPS, statuses)]
        self.write("last-apply.json", {"finishedAt": "2026-09-27T10:05:00Z", "results": results})

    def statuses(self, *statuses):
        history = json.loads((self.config / "history.json").read_text(encoding="utf-8"))
        for change, status in zip(history["entries"][0]["changes"], statuses):
            change["status"] = status
        self.write("history.json", history)


class KillGroupTest(unittest.TestCase):
    def test_one_apply_entry_and_one_group_disable_before_enable(self):
        root = Path("/inst")
        pending, history = su.kill_group(root, "0.5.0+mc26.3", "26.3")
        ops = pending["ops"]
        self.assertEqual(["DISABLE_FILE", "ENABLE_FILE"], [op["type"] for op in ops])
        self.assertEqual({su.KILL_GROUP}, {op["group"] for op in ops})
        self.assertEqual(list(su.KILL_OPS), [op["id"] for op in ops])
        self.assertEqual(str(root / "mods" / (su.KILL_NEW + ".rigtune-pending")), ops[1]["from"])
        self.assertEqual({su.KILL_ID}, {op["modId"] for op in ops})
        entry = history["entries"][0]
        self.assertEqual(("apply", "0.5.0+mc26.3", "26.3"), (entry["kind"], entry["rigtuneVersion"], entry["mcVersion"]))
        self.assertEqual(list(su.KILL_OPS), [c["opId"] for c in entry["changes"]])
        self.assertEqual(list(su.KILL_CHANGES), [c["id"] for c in entry["changes"]])
        self.assertEqual({"STAGED"}, {c["status"] for c in entry["changes"]})

    def test_the_scenario_runs_the_new_jar_with_the_undo_driver(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        args = su.parse_args(["--name", "k", "--scenario", "helper-kill", "--new-jar", "b.jar", "--work", str(tmp),
                              "--java-home", "jdk", "--lock", "none"])
        run = su.Run(args)
        self.assertEqual(list(su.KILL_PHASES), list(run.checks))
        self.assertEqual("e2eUndoDriverJar", run.driver_jar_task())
        self.assertEqual([":26.2:e2eClient", "-Pe2e.driver=undo"], run.driver_args(":26.2:e2eClient"))

    def test_both_release_nodes_get_the_row_and_the_push_tier_doesn_t(self):
        release = [r for r in e2e_matrix.rows(REPO, "release") if r["id"] == "helper-kill"]
        self.assertEqual(["26.2", "26.3"], [r["mc"] for r in release])
        self.assertEqual({("", "--scenario helper-kill")}, {(r["old"], r["args"]) for r in release})
        self.assertFalse([r for r in e2e_matrix.rows(REPO, "push") if r["id"] == "helper-kill"])


class AfterKillTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)
        self.killed = {"helpers": [4711], "recordHadGroup": True, "goneAfterKill": True, "lastApplyBefore": False, "lastApplyAfter": False}

    def check(self, kill=None):
        return e2e_checks.after_helper_kill(self.i.root, self.killed if kill is None else kill, su.KILL_GROUP, su.KILL_OPS, su.KILL_NEW)

    def test_killed_in_its_retries_with_op_2_undone(self):
        self.i.mods_as(su.KILL_OLD, su.KILL_NEW + ".rigtune-pending")
        self.i.record(su.KILL_GROUP)
        self.assertEqual([], failing(self.check()))
        # Half-applied (killed between op 1 and its rollback) is a state the next run must finish, not a failure here.
        self.i.mods_as(su.KILL_OLD + ".disabled", su.KILL_NEW + ".rigtune-pending")
        self.assertEqual([], failing(self.check()))

    def test_a_helper_that_finished_or_was_never_seen_fails(self):
        self.i.mods_as(su.KILL_OLD, su.KILL_NEW + ".rigtune-pending")
        self.i.record(su.KILL_GROUP)
        name = "the helper was killed during its retries (it wrote no results)"
        self.assertEqual([name], failing(self.check({"helpers": [], "lastApplyAfter": True})))
        self.assertEqual([name], failing(self.check(dict(self.killed, lastApplyAfter=True))))
        self.assertEqual([name], failing(self.check(dict(self.killed, goneAfterKill=False))))

    def test_the_record_and_pending_ops_must_survive_and_op_2_not_happen(self):
        self.i.mods_as(su.KILL_OLD, su.KILL_NEW)
        self.i.record("another-group")
        self.assertEqual(["the blocked op didn't happen", "unfinished-groups.json and pending.json still hold the group"], failing(self.check()))


class AfterKillSecondTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)
        self.driver = {"ok": True, "loadedMods": ["e2e-kill", "rigtune"]}

    def check(self):
        return e2e_checks.after_kill_second(self.i.root, self.driver, su.KILL_GROUP, su.KILL_OPS, su.KILL_OLD, su.KILL_NEW)

    def test_the_next_helper_finished_the_whole_group(self):
        self.i.mods_as(su.KILL_OLD + ".disabled", su.KILL_NEW)
        (self.i.config / "pending.json").unlink()
        self.i.record()
        self.i.applied("SKIPPED_ALREADY_DONE", "OK")
        self.assertEqual([], failing(self.check()))

    def test_half_a_group_or_a_left_record_fails(self):
        self.i.mods_as(su.KILL_OLD + ".disabled", su.KILL_NEW + ".rigtune-pending")
        self.i.record(su.KILL_GROUP)
        self.i.applied("OK", "FAILED")
        self.driver = {"ok": False, "error": "no report"}
        self.assertEqual(["mods/ holds the group's result", "the game started on what the killed helper left",
                          "the next helper applied the whole group",
                          "unfinished-groups.json no longer holds the group, pending.json is done"], failing(self.check()))


class AfterKillCheckTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)

    def test_history_shows_the_group_applied_and_the_mod_loads(self):
        self.i.statuses("APPLIED", "APPLIED")
        checks = e2e_checks.after_kill_check(self.i.root, {"ok": True, "modVersions": {"e2e-kill": "1.1.0"}}, su.KILL_CHANGES, su.KILL_ID, "1.1.0")
        self.assertEqual([], failing(checks))

    def test_a_half_applied_group_or_a_missing_mod_fails(self):
        self.i.statuses("APPLIED", "FAILED")
        (self.i.config / "history.json.bad").write_text("{}", encoding="utf-8")
        checks = e2e_checks.after_kill_check(self.i.root, {"ok": True, "modVersions": {"e2e-kill": "1.0.0"}}, su.KILL_CHANGES, su.KILL_ID, "1.1.0")
        self.assertEqual(["History shows the group applied as a whole", "no .bad file, no crash report", "the next start loads the updated mod"],
                         failing(checks))


class KillInBackoffTest(unittest.TestCase):
    """kill_helper_in_backoff, with the process list and the kill stubbed."""

    def setUp(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        args = su.parse_args(["--name", "k", "--scenario", "helper-kill", "--new-jar", "b.jar", "--work", str(tmp),
                              "--java-home", "jdk", "--lock", "none"])
        self.run = su.Run(args)
        self.run.run_dir.mkdir(parents=True)
        self.run.rigtune_dir.mkdir(parents=True)
        self.run.mods.mkdir(parents=True)
        self.run.log = lambda message: None
        self.alive = [4711]
        self.killed = []

        def own(predicate):
            return [(pid, "java ApplyHelper " + self.run.run_dir.name) for pid in self.alive]
        self.run.own = own

    def kill(self, pid, **_):
        self.killed.append(pid)
        self.alive.remove(pid)

    def test_kills_the_helper_once_it_recorded_the_group(self):
        (self.run.rigtune_dir / "unfinished-groups.json").write_text(json.dumps({"groups": [{"group": su.KILL_GROUP}]}), encoding="utf-8")
        with mock.patch.object(su, "kill_process", self.kill), mock.patch.object(su, "KILL_AFTER", 0), mock.patch.object(su.time, "sleep"):
            seen = self.run.kill_helper_in_backoff()
        self.assertEqual([4711], self.killed)
        self.assertEqual({"helpers": [4711], "recordHadGroup": True, "goneAfterKill": True, "lastApplyBefore": False, "lastApplyAfter": False},
                         {k: seen[k] for k in ("helpers", "recordHadGroup", "goneAfterKill", "lastApplyBefore", "lastApplyAfter")})

    def test_a_helper_that_already_finished_is_reported_not_killed(self):
        self.alive = []
        (self.run.rigtune_dir / "last-apply.json").write_text("{}", encoding="utf-8")
        with mock.patch.object(su, "kill_process", self.kill), mock.patch.object(su.time, "sleep"):
            seen = self.run.kill_helper_in_backoff()
        self.assertEqual([], self.killed)
        self.assertEqual({"helpers": [], "lastApplyAfter": True}, seen)


if __name__ == "__main__":
    unittest.main()
