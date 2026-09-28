"""e2e_matrix.py (docs/v0.5/SPEC.md 3a, AC3a.2): the E2E scenarios per tier from one table, old jars from RELEASED,
nodes from versions/*/."""

import contextlib
import io
import json
import re
import shlex
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_matrix  # noqa: E402
import self_update_e2e  # noqa: E402

REPO = Path(__file__).resolve().parents[3]


def repo_with(*nodes, sodium=True):
    root = Path(tempfile.mkdtemp())
    for mc in nodes:
        (root / "versions" / mc).mkdir(parents=True)
        sodium_line = "sodium_version=mc{}-1-fabric\n".format(mc) if sodium else ""
        (root / "versions" / mc / "gradle.properties").write_text("fabric_api_version=1\n" + sodium_line, encoding="utf-8")
    return root


class MatrixTest(unittest.TestCase):
    def test_the_release_tier_uses_every_released_jar(self):
        rows = e2e_matrix.rows(REPO, "release")
        self.assertEqual(set(self_update_e2e.RELEASED), {r["old"] for r in rows if r["old"]})

    def test_the_push_tier_is_the_newest_release_to_new_on_each_node(self):
        rows = e2e_matrix.rows(REPO, "push")
        self.assertEqual([("upgrade-from-0.4.0", "26.2", "0.4.0+mc26.2"), ("upgrade-from-0.4.0", "26.3", "0.4.0+mc26.3")],
                         [(r["id"], r["mc"], r["old"]) for r in rows])

    def test_the_release_tier_contains_the_push_tier(self):
        release = e2e_matrix.rows(REPO, "release")
        for row in e2e_matrix.rows(REPO, "push"):
            self.assertIn(row, release)

    def test_the_release_scenarios_per_node(self):
        rows = e2e_matrix.rows(REPO, "release")
        by_node = {mc: sorted(r["id"] for r in rows if r["mc"] == mc) for mc in ("26.2", "26.3")}
        self.assertEqual(sorted(["upgrade-from-0.4.0", "upgrade-from-0.3.0", "upgrade-from-0.2.0", "upgrade-from-0.1.0",
                                 "seeded-v010-dh", "seeded-v010-dh-app-reinstalled", "seeded-v010-dh-app-reinstalled-disabled",
                                 "undo-profiles", "undo-settings", "helper-kill", "brand-theseus", "downgrade-to-0.4.0", "downgrade-to-0.3.0"]),
                         by_node["26.2"])
        self.assertEqual(sorted(["upgrade-from-0.4.0", "upgrade-from-0.3.0", "upgrade-from-0.2.0", "handover-from-0.4.0-dh", "undo-profiles",
                                 "undo-settings", "helper-kill", "downgrade-to-0.4.0", "downgrade-to-0.3.0"]), by_node["26.3"])

    def test_old_jars_tags_and_digests_come_from_released(self):
        for row in e2e_matrix.rows(REPO, "release"):
            if not row["old"]:
                self.assertEqual(("", "", ""), (row["tag"], row["asset"], row["sha256"]), row)
                continue
            self.assertEqual(self_update_e2e.RELEASED[row["old"]], (row["asset"], row["sha256"]), row)
            self.assertEqual("v" + row["old"].split("+")[0], row["tag"], row)

    def test_0_1_0_is_the_26_2_node_s_and_gets_the_legacy_checks(self):
        rows = [r for r in e2e_matrix.rows(REPO, "release") if r["id"] == "upgrade-from-0.1.0"]
        self.assertEqual(["26.2"], [r["mc"] for r in rows])
        self.assertEqual(["--legacy-disable", "--expect-history", "auto"], shlex.split(rows[0]["args"]))

    def test_nodes_follow_the_versions_folder(self):
        rows = e2e_matrix.rows(repo_with("26.2", "26.3", "26.9"), "release")
        self.assertEqual(["helper-kill", "undo-profiles", "undo-settings"], sorted(r["id"] for r in rows if r["mc"] == "26.9"))
        self.assertEqual([], [r for r in e2e_matrix.rows(repo_with("26.2", "26.3", "26.9"), "push") if r["mc"] == "26.9"])
        self.assertEqual({"26.2"}, {r["mc"] for r in e2e_matrix.rows(repo_with("26.2"), "release")})

    def test_a_node_without_sodium_gets_the_undo_scenario_without_the_profile_part(self):
        # review-11 CI-4: add_mc_version.py leaves sodium_version out when Sodium has no build yet; the profile part needs
        # Sodium (a staged config target), so such a node runs the undo scenario without it, never crashing the tier.
        root = repo_with("26.9", sodium=False)
        self.assertEqual([("helper-kill", "--scenario helper-kill"), ("undo", "--scenario undo")],
                         sorted((r["id"], r["args"]) for r in e2e_matrix.rows(root, "release")))

    def test_ids_are_unique_per_node_and_shell_safe(self):
        rows = e2e_matrix.rows(REPO, "release")
        keys = [(r["id"], r["mc"]) for r in rows]
        self.assertEqual(len(keys), len(set(keys)))
        for row in rows:
            self.assertRegex(row["id"], r"^[a-z0-9][a-z0-9.-]*$")

    def test_every_row_s_arguments_are_the_harness_s(self):
        for row in e2e_matrix.rows(REPO, "release"):
            argv = ["--name", row["id"], "--mc", row["mc"], "--new-jar", "new.jar", "--work", "w", "--java-home", "jdk",
                    "--lock", "none"] + (["--old-jar", row["asset"]] if row["old"] else []) + shlex.split(row["args"])
            args = self_update_e2e.parse_args(argv)
            self.assertEqual(row["mc"], args.mc)

    def test_prints_compact_json_for_the_workflow(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            e2e_matrix.main(["--tier", "push", "--root", str(REPO)])
        text = out.getvalue().strip()
        self.assertNotIn(" ", re.sub(r'"[^"]*"', "", text))
        self.assertEqual(e2e_matrix.rows(REPO, "push"), json.loads(text)["include"])

    def test_an_unknown_tier_is_refused(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            e2e_matrix.main(["--tier", "nightly"])

    def test_nodes_alone_for_the_per_node_legs(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            e2e_matrix.main(["--nodes", "--root", str(REPO)])
        self.assertEqual(e2e_matrix.gametest_matrix.nodes(REPO), json.loads(out.getvalue()))
        self.assertIn("26.2", json.loads(out.getvalue()))
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            e2e_matrix.main(["--nodes", "--tier", "push"])


if __name__ == "__main__":
    unittest.main()
