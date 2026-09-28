"""release_verify.py (docs/v0.5/SPEC.md 3c, AC3c.2): each node's GitHub asset against its Modrinth version: the metadata
sha512, the bytes the CDN serves, and the version's number, game versions, loaders and type."""

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import release_verify  # noqa: E402

JAR = b"the 26.2 jar"


def version(mc="26.2", data=JAR, **overrides):
    v = {"version_number": "0.5.0+mc" + mc, "game_versions": [mc], "loaders": ["fabric"], "version_type": "release",
         "files": [{"filename": "rigtune-0.5.0+mc{}.jar".format(mc), "url": "https://cdn.modrinth.com/data/P/versions/V/f.jar",
                    "hashes": {"sha1": hashlib.sha1(data).hexdigest(), "sha512": hashlib.sha512(data).hexdigest()}}]}
    v.update(overrides)
    return v


def failures(results):
    return [message for ok, message in results if not ok]


class ExpectedTest(unittest.TestCase):
    def test_one_asset_per_node_with_its_version_number_and_type(self):
        self.assertEqual([("26.2", "rigtune-0.5.0+mc26.2.jar", "0.5.0+mc26.2", "release"),
                          ("26.4-snapshot-1", "rigtune-0.5.0+mc26.4-snapshot-1.jar", "0.5.0+mc26.4-snapshot-1", "alpha")],
                         release_verify.expected("v0.5.0", ["26.2", "26.4-snapshot-1"]))

    def test_the_type_rule_is_build_gradle_s(self):
        self.assertEqual(["release", "release", "alpha", "alpha"],
                         [release_verify.version_type(mc) for mc in ("26.3", "26.3.1", "26.4-rc-1", "26.4-snapshot-1")])


class CheckNodeTest(unittest.TestCase):
    def check(self, versions, served=JAR):
        return release_verify.check_node("26.2", "rigtune-0.5.0+mc26.2.jar", "0.5.0+mc26.2", "release", JAR, versions,
                                         fetch=lambda url: served)

    def test_the_same_bytes_and_metadata_pass(self):
        results = self.check([version(mc="26.3", data=b"other"), version()])
        self.assertEqual([], failures(results))
        self.assertTrue(any("CDN" in m for ok, m in results if ok))

    def test_no_file_with_the_asset_s_sha1_fails(self):
        self.assertTrue(failures(self.check([version(data=b"other")])))

    def test_a_metadata_sha512_mismatch_fails(self):
        bad = version()
        bad["files"][0]["hashes"]["sha512"] = "0" * 128
        self.assertEqual(1, len(failures(self.check([bad]))))

    def test_other_bytes_on_the_cdn_fail(self):
        self.assertEqual(1, len(failures(self.check([version()], served=b"tampered"))))

    def test_wrong_metadata_fails_each_field(self):
        for field, value in (("game_versions", ["26.2", "26.3"]), ("loaders", ["quilt"]), ("version_type", "alpha"),
                             ("version_number", "0.5.0")):
            with self.subTest(field=field):
                self.assertEqual(1, len(failures(self.check([version(**{field: value})]))), field)


class NodesTest(unittest.TestCase):
    def test_nodes_come_from_versions(self):
        root = Path(tempfile.mkdtemp())
        for mc in ("26.3", "26.2"):
            (root / "versions" / mc).mkdir(parents=True)
        self.assertEqual(["26.2", "26.3"], release_verify.nodes(root))


if __name__ == "__main__":
    unittest.main()
