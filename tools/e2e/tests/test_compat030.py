"""The released-jar compatibility harness's runner (compat030.py; AC3.3)."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import compat030  # noqa: E402


def jar(cache, group, artifact, version, digest="0123abcd"):
    folder = Path(cache) / group / artifact / version / digest
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "{}-{}-sources.jar".format(artifact, version)).write_bytes(b"src")
    path = folder / "{}-{}.jar".format(artifact, version)
    path.write_bytes(b"jar")
    return path


class FindJarTest(unittest.TestCase):
    def setUp(self):
        self.cache = Path(tempfile.mkdtemp())

    def test_an_exact_version(self):
        want = jar(self.cache, "com.google.code.gson", "gson", "2.14.0")
        jar(self.cache, "com.google.code.gson", "gson", "2.10.1")
        self.assertEqual(want, compat030.find_jar(self.cache, "com.google.code.gson", "gson", "2.14.0"))

    def test_the_newest_version(self):
        jar(self.cache, "org.slf4j", "slf4j-api", "2.0.9")
        want = jar(self.cache, "org.slf4j", "slf4j-api", "2.0.17")
        self.assertEqual(want, compat030.find_jar(self.cache, "org.slf4j", "slf4j-api"))

    def test_missing(self):
        with self.assertRaises(SystemExit):
            compat030.find_jar(self.cache, "net.fabricmc", "fabric-loader", "0.19.5")

    def test_classpath_is_the_old_jar_then_its_runtime(self):
        gson = jar(self.cache, "com.google.code.gson", "gson", "2.14.0")
        loader = jar(self.cache, "net.fabricmc", "fabric-loader", "0.19.5")
        slf4j = jar(self.cache, "org.slf4j", "slf4j-api", "2.0.17")
        old = Path("rigtune-0.3.0+mc26.2.jar")
        self.assertEqual([old, gson, loader, slf4j], compat030.classpath(old, self.cache, "0.19.5"))


class OutputTest(unittest.TestCase):
    def test_parses_check_lines_and_ignores_the_rest(self):
        out = "SLF4J(W): No SLF4J providers were found.\nPASS Journal: ok | state OK\nFAIL RulesLoader: x | counts {a=1}\n"
        self.assertEqual([(True, "Journal: ok", "state OK"), (False, "RulesLoader: x", "counts {a=1}")], compat030.parse(out))

    def test_loader_version_from_gradle_properties(self):
        props = Path(tempfile.mkdtemp()) / "gradle.properties"
        props.write_text("org.gradle.parallel=true\nloader_version=0.19.5\n", encoding="utf-8")
        self.assertEqual("0.19.5", compat030.loader_version(props))

    def test_every_expected_check_must_be_reported(self):
        lines = [(True, name, "") for name in compat030.EXPECTED[:-1]]
        self.assertEqual([compat030.EXPECTED[-1]], compat030.missing(lines))
        self.assertEqual([], compat030.missing(lines + [(True, compat030.EXPECTED[-1], "")]))


if __name__ == "__main__":
    unittest.main()
