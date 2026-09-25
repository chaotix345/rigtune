import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import gametest_matrix as gm


class GametestMatrixTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "versions").mkdir()

    def add_node(self, mc, sodium=True):
        node = self.root / "versions" / mc
        node.mkdir()
        props = f"minecraft_dependency=~{mc}\n" + (f"sodium_version=mc{mc}-0.9.2-fabric\n" if sodium else "")
        (node / "gradle.properties").write_text(props, encoding="utf-8", newline="\n")

    def test_current_nodes_give_three_legs(self):
        self.add_node("26.2")
        self.add_node("26.3")
        self.assertEqual(gm.legs(self.root), [
            {"mc": "26.2", "backend": "OpenGL"},
            {"mc": "26.3", "backend": "OpenGL"},
            {"mc": "26.3", "backend": "Vulkan"},
        ])

    def test_extra_node_gets_legs_without_other_changes(self):
        self.add_node("26.2")
        self.add_node("26.3")
        self.add_node("26.4-snapshot-1")
        self.add_node("27.1")
        self.assertEqual(gm.legs(self.root)[3:], [
            {"mc": "26.4-snapshot-1", "backend": "OpenGL"},
            {"mc": "26.4-snapshot-1", "backend": "Vulkan"},
            {"mc": "27.1", "backend": "OpenGL"},
            {"mc": "27.1", "backend": "Vulkan"},
        ])

    def test_hotfix_and_prerelease_of_26_3_get_vulkan(self):
        self.add_node("26.3.1")
        self.add_node("26.3-rc-1")
        backends = {(leg["mc"], leg["backend"]) for leg in gm.legs(self.root)}
        self.assertIn(("26.3.1", "Vulkan"), backends)
        self.assertIn(("26.3-rc-1", "Vulkan"), backends)

    def test_older_nodes_get_opengl_only(self):
        self.add_node("26.1.2")
        self.add_node("26.2")
        self.assertEqual(gm.legs(self.root), [
            {"mc": "26.1.2", "backend": "OpenGL"},
            {"mc": "26.2", "backend": "OpenGL"},
        ])

    def test_node_without_sodium_gets_its_legs(self):
        self.add_node("26.3")
        self.add_node("26.4", sodium=False)
        self.assertEqual(gm.legs(self.root)[2:], [
            {"mc": "26.4", "backend": "OpenGL"},
            {"mc": "26.4", "backend": "Vulkan"},
        ])

    def test_numeric_sort(self):
        self.add_node("26.10")
        self.add_node("26.3")
        self.add_node("26.2")
        self.assertEqual(gm.nodes(self.root), ["26.2", "26.3", "26.10"])

    def test_prerelease_stages_sort_before_the_release(self):
        for mc in ["26.4.1", "26.4", "26.4-rc-1", "26.4-pre-1", "26.4-snapshot-10", "26.4-snapshot-2"]:
            self.add_node(mc)
        self.assertEqual(gm.nodes(self.root),
                         ["26.4-snapshot-2", "26.4-snapshot-10", "26.4-pre-1", "26.4-rc-1", "26.4", "26.4.1"])

    def test_hidden_dirs_and_files_are_ignored(self):
        self.add_node("26.2")
        (self.root / "versions" / ".gradle").mkdir()
        (self.root / "versions" / "README.txt").write_text("x", encoding="utf-8")
        self.assertEqual(gm.nodes(self.root), ["26.2"])

    def test_no_nodes_is_an_error(self):
        with self.assertRaises(SystemExit):
            gm.nodes(self.root)

    def test_unparseable_node_is_an_error(self):
        self.add_node("latest")
        with self.assertRaises(SystemExit):
            gm.nodes(self.root)

    def test_shell_metacharacters_are_refused(self):
        for mc in ["26.4-$(id)", "26.4-x;ls", "26.4-a b", "26.4-`id`", "26.4-a'b"]:
            with self.subTest(mc=mc):
                node = self.root / "versions" / mc
                node.mkdir()
                with self.assertRaises(SystemExit):
                    gm.nodes(self.root)
                node.rmdir()

    def test_cli_prints_one_line_of_matrix_json(self):
        self.add_node("26.2")
        self.add_node("26.3")
        out = io.StringIO()
        with redirect_stdout(out):
            gm.main(["--root", str(self.root)])
        text = out.getvalue()
        self.assertEqual(text.count("\n"), 1)
        self.assertEqual(json.loads(text), {"include": gm.legs(self.root)})

    def test_repository_nodes_all_listed(self):
        repo = Path(__file__).resolve().parent.parent.parent
        on_disk = sorted(p.name for p in (repo / "versions").iterdir() if p.is_dir() and not p.name.startswith("."))
        self.assertEqual(sorted(gm.nodes(repo)), on_disk)
        self.assertIn({"mc": "26.2", "backend": "OpenGL"}, gm.legs(repo))


if __name__ == "__main__":
    unittest.main()
