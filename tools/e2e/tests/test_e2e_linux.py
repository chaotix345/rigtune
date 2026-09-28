"""The harness's Linux path (docs/v0.5/SPEC.md 3a, AC3a.1): the process list from /proc, SIGKILL, the helper watcher
thread, chattr +i as the seeded hold, preflight, and the log lines Linux CI under Xvfb adds."""

import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import self_update_e2e  # noqa: E402
from test_self_update_e2e import make_run  # noqa: E402


def fake_proc(root, processes):
    """A /proc-like folder: pid -> argv (None: a cmdline that can't be read)."""
    root = Path(root)
    for pid, argv in processes.items():
        folder = root / str(pid)
        folder.mkdir(parents=True)
        if argv is not None:
            (folder / "cmdline").write_bytes(b"\0".join(a.encode("utf-8") for a in argv) + b"\0")
    return root


class PosixProcessesTest(unittest.TestCase):
    def test_lists_java_and_javaw_with_the_whole_command_line(self):
        proc = fake_proc(tempfile.mkdtemp(), {
            101: ["/opt/jdk/bin/java", "-cp", "a.jar:b.jar", "io.github.chaotix345.rigtune.core.apply.ApplyHelper", "7", "/run/x/pending.json"],
            102: ["/usr/bin/javaw", "KnotClient", "--gameDir", "/run/x/instance"],
            103: ["/usr/bin/bash", "-c", "java -version"],
            104: None,
        })
        (proc / "self").mkdir()
        (proc / "cpuinfo").write_text("x", encoding="utf-8")

        found = sorted(self_update_e2e.posix_java_processes(proc))

        self.assertEqual([(101, "/opt/jdk/bin/java -cp a.jar:b.jar io.github.chaotix345.rigtune.core.apply.ApplyHelper 7 /run/x/pending.json"),
                          (102, "/usr/bin/javaw KnotClient --gameDir /run/x/instance")], found)

    def test_a_process_that_exits_while_listed_is_skipped(self):
        proc = fake_proc(tempfile.mkdtemp(), {201: None})
        self.assertEqual([], self_update_e2e.posix_java_processes(proc))


class KillTest(unittest.TestCase):
    def test_posix_sends_sigkill_and_ignores_a_vanished_process(self):
        sent = []

        def kill(pid, sig):
            sent.append((pid, sig))
            if pid == 2:
                raise ProcessLookupError(pid)

        self_update_e2e.kill_process(1, posix=True, kill=kill)
        self_update_e2e.kill_process(2, posix=True, kill=kill)
        self.assertEqual([(1, 9), (2, 9)], [(p, int(s)) for p, s in sent])

    def test_windows_uses_taskkill_on_the_one_pid(self):
        calls = []
        self_update_e2e.kill_process(5, posix=False, run=lambda cmd, **kw: calls.append(cmd))
        self.assertEqual([["taskkill", "/PID", "5", "/F"]], calls)


class HoldTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.jar = self.tmp / "fabric-26.2.jar"
        self.jar.write_bytes(b"jar")

    def test_posix_makes_the_file_immutable_for_the_block_and_always_undoes_it(self):
        calls = []

        def run(cmd, check=False):
            calls.append((cmd, check))

        with self.assertRaises(RuntimeError):
            with self_update_e2e.held([self.jar], posix=True, run=run):
                calls.append("launch")
                raise RuntimeError("the launch failed")

        self.assertEqual([(["sudo", "-n", "chattr", "+i", str(self.jar)], True), "launch",
                          (["sudo", "-n", "chattr", "-i", str(self.jar)], False)], calls)

    def test_windows_keeps_the_file_open_for_the_block(self):
        with self_update_e2e.held([self.jar], posix=False, run=None) as handles:
            self.assertEqual(1, len(handles))
            self.assertFalse(handles[0].closed)
        self.assertTrue(handles[0].closed)

    def test_nothing_to_hold_runs_nothing(self):
        with self_update_e2e.held([], posix=True, run=lambda *a, **k: self.fail("ran a command")):
            pass


class WatcherTest(unittest.TestCase):
    def test_records_this_runs_helper_command_lines_until_stopped(self):
        run = make_run(Path(tempfile.mkdtemp()))
        mine = "/opt/jdk/bin/java -cp {0}/instance/config/rigtune/helper/0.jar io.github.chaotix345.rigtune.core.apply.ApplyHelper 7".format(run.run_dir)
        processes = [(11, mine), (12, "/opt/jdk/bin/java io.github.chaotix345.rigtune.core.apply.ApplyHelper other-run"),
                     (13, "/opt/jdk/bin/java KnotClient {0}".format(run.run_dir))]
        run.java_processes = lambda: processes
        seen = threading.Event()
        original = run.own

        def own(predicate):
            result = original(predicate)
            seen.set()
            return result
        run.own = own

        run.start_watcher("update", posix=True)
        self.assertTrue(seen.wait(5))
        time.sleep(0.5)
        run.stop_watcher()

        self.assertIsNone(run.watcher)
        self.assertEqual([mine], run.helper_cmdlines())


class PreflightTest(unittest.TestCase):
    def test_posix_needs_proc_not_pwsh(self):
        run = make_run(Path(tempfile.mkdtemp()))
        with self.assertRaises(SystemExit) as raised:
            run.preflight(posix=True, proc=Path(tempfile.mkdtemp()) / "missing")
        self.assertIn("/proc", str(raised.exception))
        proc = fake_proc(tempfile.mkdtemp(), {})
        (proc / "self").mkdir()
        (proc / "self" / "cmdline").write_bytes(b"python\0")
        run.preflight(posix=True, proc=proc)


class XvfbLogLinesTest(unittest.TestCase):
    NARRATOR = "[02:33:27] [Render thread/ERROR]: Error while loading the narrator"
    SOUND = "[02:33:31] [Render thread/ERROR]: Error starting SoundSystem. Turning off sounds & music"
    CURSOR = ["[02:33:34] [Render thread/ERROR]: ########## GL ERROR ##########",
              "[02:33:34] [Render thread/ERROR]: @ Render",
              "[02:33:34] [Render thread/ERROR]: 65547: X11: Standard cursor shape unavailable"]

    def test_the_xvfb_lines_are_harmless(self):
        self.assertEqual([], e2e_checks.rigtune_log_problems("\n".join([self.NARRATOR, self.SOUND] + self.CURSOR)))

    def test_another_gl_error_is_still_a_problem(self):
        other = ["[02:33:35] [Render thread/ERROR]: ########## GL ERROR ##########",
                 "[02:33:35] [Render thread/ERROR]: @ Render",
                 "[02:33:35] [Render thread/ERROR]: 1282: invalid operation"]
        self.assertEqual(other, e2e_checks.rigtune_log_problems("\n".join(self.CURSOR + other)))

    def test_a_cursor_line_without_its_gl_error_header_is_a_problem(self):
        stray = "[02:33:36] [Render thread/ERROR]: 65547: X11: Standard cursor shape unavailable"
        self.assertEqual([stray], e2e_checks.rigtune_log_problems(stray))


if __name__ == "__main__":
    unittest.main()
