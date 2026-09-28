"""The DH server-note run's pieces (tools/e2e/dhnote, docs/v0.5/SPEC.md AC3f.4): the server's port and properties, the
operator's offline UUID, the phases."""

import sys
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


if __name__ == "__main__":
    unittest.main()
