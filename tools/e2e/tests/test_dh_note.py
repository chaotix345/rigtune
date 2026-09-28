"""The DH server-note run's pieces (tools/e2e/dhnote, docs/v0.5/SPEC.md AC3f.4): the server's port and properties, the
operator's offline UUID, the phases."""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dhnote"))

import dh_server_note as note  # noqa: E402


class DhNoteTest(unittest.TestCase):
    def test_the_offline_uuid_is_java_s(self):
        # Minecraft's offline-mode UUID of "Notch", as servers log it.
        self.assertEqual("b50ad385-829d-3141-a216-7e7d7539ba7f", note.offline_uuid("Notch"))

    def test_the_port_is_never_25565(self):
        class Socket:
            ports = iter([25565, 50123])

            def __init__(self, *args):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def bind(self, address):
                self.port = next(Socket.ports)

            def getsockname(self):
                return ("127.0.0.1", self.port)

        with mock.patch.object(note.socket, "socket", Socket):
            self.assertEqual(50123, note.free_port())

    def test_the_server_binds_loopback_on_the_port_with_the_phase_s_view_distance(self):
        text = note.server_properties(50123, 6)
        self.assertIn("server-ip=127.0.0.1\n", text)
        self.assertIn("server-port=50123\n", text)
        self.assertIn("view-distance=6\n", text)
        self.assertIn("online-mode=false\n", text)
        self.assertNotIn("25565", text)

    def test_phases(self):
        self.assertEqual([("explore", "90"), ("dhserver", "240")], note.parse_phases("explore:90,dhserver:240"))
        self.assertEqual({"explore": 16, "limited": 6, "dhserver": 6}, note.VIEW)
        with self.assertRaises(SystemExit):
            note.parse_phases("nearby:30")

    def test_each_join_must_see_its_phase_s_view_distance(self):
        phases = note.parse_phases("explore:90,limited:240,dhserver:240")
        joins = [{"phase": "explore", "viewDistance": 16}, {"phase": "limited", "viewDistance": 6}, {"phase": "dhserver", "viewDistance": 6}]
        self.assertEqual([], note.view_problems(phases, joins))
        self.assertEqual(["limited: view distance 16 (expected 6)", "dhserver: view distance None (expected 6)"],
                         note.view_problems(phases, joins[:1] + [{"phase": "limited", "viewDistance": 16}]))


class NoteRunTest(unittest.TestCase):
    """Review round 3: M2 (copies only), M1 (the last phase), L11 (the lock), L8 (kill_own)."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.src = self.tmp / "player" / "mods"
        self.src.mkdir(parents=True)
        for name in ("dh.jar", "fabric-api.jar", "rigtune.jar"):
            (self.src / name).write_bytes(name.encode())
        self.work = Path(tempfile.mkdtemp(prefix="dh"))
        self.addCleanup(shutil.rmtree, self.work)

    def run_for(self, *extra):
        return note.NoteRun(note.parse_args(["--rigtune-jar", str(self.src / "rigtune.jar"), "--dh-jar", str(self.src / "dh.jar"),
                                             "--fabric-api", str(self.src / "fabric-api.jar"), "--work", str(self.work),
                                             "--java-home", "jdk"] + list(extra)))

    def test_the_server_loads_only_copies_under_the_run_s_folder(self):
        run = self.run_for("--lock", "none")
        run.prepare()
        for name in ("explore", "dhserver"):
            mods = run.mods_for(name)
            self.assertTrue(mods and all(run.run_dir in m.parents and m.is_file() for m in mods), mods)
        self.assertEqual(["fabric-api.jar", "dh.jar"], [m.name for m in run.mods_for("dhserver")])
        self.assertEqual(["fabric-api.jar"], [m.name for m in run.mods_for("limited")])

    def test_kill_own_touches_only_command_lines_naming_the_run(self):
        run = self.run_for("--lock", "none")
        run.run_dir.mkdir(parents=True)
        killed = []
        run.kill_own(processes=[(1, "java -cp x net.fabricmc.installer.ServerLauncher -Dfabric.addMods=" + str(run.run_dir / "server-mods" / "dh.jar")),
                                (2, "java -jar fabric-server-launcher.jar nogui"), (3, "java KnotClient -Drigtune.e2e.out=" + str(run.run_dir / "out"))],
                     kill=killed.append)
        self.assertEqual([1, 3], killed)

    def test_the_last_phase_s_client_exit_is_fine_an_earlier_one_isn_t(self):
        run = self.run_for("--lock", "none")
        run.run_dir.mkdir(parents=True)

        class Proc:
            def __init__(self, polls):
                self.polls = list(polls)

            def poll(self):
                return self.polls.pop(0) if len(self.polls) > 1 else self.polls[0]
        with mock.patch.object(note.time, "sleep", lambda s: None):
            run.wait_phase(Proc([None, None, 0]), Proc([0]), "dhserver", "1", last=True)
            with self.assertRaises(SystemExit):
                run.wait_phase(Proc([None, None, 0]), Proc([0]), "limited", "1", last=False)

    def test_it_takes_and_releases_the_game_test_lock_and_stops_when_it_is_held(self):
        lock = self.tmp / "lock"
        run = self.run_for("--lock", str(lock))
        run.run_dir.mkdir(parents=True)
        run.take_lock()
        self.assertIn("run: {}".format(run.run_dir), (lock / "owner.txt").read_text(encoding="utf-8"))
        with self.assertRaises(SystemExit):
            other = self.run_for("--lock", str(lock))
            other.run_dir.mkdir(parents=True)
            other.take_lock()
        run.release_lock()
        self.assertFalse(lock.exists())


if __name__ == "__main__":
    unittest.main()
