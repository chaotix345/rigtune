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

    def test_a_trailing_newline_is_refused(self):
        # $ also matches before one trailing newline; the id must match in full (review 5, security-1).
        with self.assertRaises(SystemExit):
            gm._core("26.4\n")

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

    def add_classes(self, *names):
        mod = self.root / gm.GAMETEST_MOD
        mod.parent.mkdir(parents=True)
        entries = ["io.github.chaotix345.rigtune.gametest." + name for name in names]
        mod.write_text(json.dumps({"entrypoints": {"fabric-client-gametest": entries}}), encoding="utf-8")

    # SPEC 1g: the dormant split. With one part (the default) the matrix is the legs, unchanged.
    def test_one_part_is_the_legs_unchanged(self):
        self.add_node("26.2")
        self.add_node("26.3")
        self.assertEqual(1, gm.PARTS)
        self.assertEqual(gm.legs(self.root), gm.matrix(self.root))
        self.assertEqual(gm.legs(self.root), gm.matrix(self.root, 1))

    # AC1g.4: two parts run every class exactly once per leg, the first class in part 1, in fabric.mod.json's order.
    def test_two_parts_run_every_class_once_per_leg(self):
        self.add_node("26.2")
        self.add_node("26.3")
        self.add_classes("FirstApplyGameTest", "RigTuneClientGameTest", "BenchmarkGameTest", "UiGameTest", "A11yGameTest")
        entries = gm.matrix(self.root, 2)
        self.assertEqual(6, len(entries))
        for mc, backend in (("26.2", "OpenGL"), ("26.3", "OpenGL"), ("26.3", "Vulkan")):
            parts = [e for e in entries if e["mc"] == mc and e["backend"] == backend]
            self.assertEqual(["1/2", "2/2"], [e["part"] for e in parts])
            self.assertEqual(["-part1", "-part2"], [e["suffix"] for e in parts])
            self.assertEqual(["FirstApplyGameTest,RigTuneClientGameTest", "BenchmarkGameTest,UiGameTest,A11yGameTest"],
                             [e["classes"] for e in parts])

    def test_parts_must_fit_the_classes(self):
        self.add_node("26.2")
        self.add_classes("A", "B")
        self.assertEqual([["A"], ["B"]], gm.split(["A", "B"], 2))
        for parts in (0, 3):
            with self.assertRaises(SystemExit):
                gm.matrix(self.root, parts)

    def test_cli_takes_the_parts(self):
        self.add_node("26.2")
        self.add_classes("A", "B", "C")
        out = io.StringIO()
        with redirect_stdout(out):
            gm.main(["--root", str(self.root), "--parts", "3"])
        self.assertEqual(["A", "B", "C"], [e["classes"] for e in json.loads(out.getvalue())["include"]])

    def test_repository_classes_split_in_two(self):
        repo = Path(__file__).resolve().parent.parent.parent
        classes = gm.game_test_classes(repo)
        halves = gm.split(classes, 2)
        self.assertEqual(classes, halves[0] + halves[1])

    # FootprintGameTest runs second to last, just before A11yGameTest, with the split off or on.
    def test_footprint_stays_second_to_last_in_its_part(self):
        repo = Path(__file__).resolve().parent.parent.parent
        classes = gm.game_test_classes(repo)
        for parts in (1, 2):
            with self.subTest(parts=parts):
                part = [p for p in gm.split(classes, parts) if "FootprintGameTest" in p][0]
                self.assertEqual(["FootprintGameTest", "A11yGameTest"], part[-2:])

    def test_repository_nodes_all_listed(self):
        repo = Path(__file__).resolve().parent.parent.parent
        on_disk = sorted(p.name for p in (repo / "versions").iterdir() if p.is_dir() and not p.name.startswith("."))
        self.assertEqual(sorted(gm.nodes(repo)), on_disk)
        self.assertIn({"mc": "26.2", "backend": "OpenGL"}, gm.legs(repo))


if __name__ == "__main__":
    unittest.main()
