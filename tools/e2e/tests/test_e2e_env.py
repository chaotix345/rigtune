import datetime
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_env  # noqa: E402


def make_jar(path, mod_id, version, depends=None):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": mod_id, "version": version,
                                                        "depends": depends or {}}))
        archive.writestr("a/B.class", b"\xca\xfe")
    return path


class HostsFileTest(unittest.TestCase):
    def test_redirects_every_host_to_loopback_and_keeps_local_names(self):
        lines = e2e_env.hosts_file_text("DESKTOP-X").splitlines()
        for host in ("api.modrinth.com", "cdn.modrinth.com", "raw.githubusercontent.com", "localhost", "DESKTOP-X"):
            self.assertIn("127.0.0.1 " + host, lines)
        self.assertTrue(all(line.startswith("#") or line.startswith("127.0.0.1 ") for line in lines))

    def test_can_leave_out_local_names(self):
        text = e2e_env.hosts_file_text("DESKTOP-X", include_local=False)
        self.assertNotIn("localhost", text)
        self.assertNotIn("DESKTOP-X", text)
        self.assertIn("127.0.0.1 cdn.modrinth.com", text)


class TlsTest(unittest.TestCase):
    def test_keytool_certificate_covers_every_redirected_host(self):
        commands = e2e_env.keytool_commands("keytool", Path("tls"))
        self.assertEqual(["-genkeypair", "-exportcert", "-importcert"], [c[1] for c in commands])
        san = commands[0][commands[0].index("-ext") + 1]
        for host in e2e_env.REDIRECTED_HOSTS:
            self.assertIn("dns:" + host, san)
        for command in commands:
            self.assertIn("PKCS12", command)
        self.assertTrue(commands[2][commands[2].index("-keystore") + 1].endswith("truststore.p12"))

    def test_jvm_args_are_properties_only(self):
        tls = e2e_env.Tls(Path("tls/server.p12"), Path("tls/truststore.p12"), "pw")
        args = e2e_env.jvm_args(Path("hosts.txt"), tls)
        self.assertEqual(["-Djdk.net.hosts.file", "-Djavax.net.ssl.trustStore", "-Djavax.net.ssl.trustStorePassword",
                          "-Djavax.net.ssl.trustStoreType"], [a.split("=", 1)[0] for a in args])
        self.assertTrue(Path(args[0].split("=", 1)[1]).is_absolute())
        self.assertIn("-Djavax.net.ssl.trustStorePassword=pw", args)


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.old = make_jar(self.dir / "rigtune-0.1.0.jar", "rigtune", "0.1.0")
        self.new = make_jar(self.dir / "rigtune-0.2.0+mc26.2.jar", "rigtune", "0.2.0+mc26.2")
        self.rules = self.dir / "rules-v1.json"
        self.rules.write_text("{}")

    def test_mod_json_reads_the_jar(self):
        self.assertEqual("0.2.0+mc26.2", e2e_env.mod_json(self.new)["version"])

    def test_one_project_with_the_installed_version_and_a_later_update(self):
        now = datetime.datetime(2026, 9, 25, 10, 0, tzinfo=datetime.timezone.utc)
        catalog = e2e_env.catalog(self.old, self.new, "26.2", [self.rules], now=now)

        self.assertEqual({"api": "api.modrinth.com", "cdn": "cdn.modrinth.com"}, catalog["hosts"])
        self.assertEqual("https://cdn.modrinth.com", catalog["cdnBase"])
        (project,) = catalog["projects"]
        self.assertEqual("rigtune", project["slug"])
        old, new = project["versions"]
        self.assertEqual(("0.1.0", "0.2.0+mc26.2"), (old["version_number"], new["version_number"]))
        self.assertLess(old["date_published"], new["date_published"])
        self.assertEqual("2026-09-25T10:00:00Z", new["date_published"])
        for version in (old, new):
            self.assertEqual(["26.2"], version["game_versions"])
            self.assertEqual(["fabric"], version["loaders"])
            self.assertNotIn("\\", version["file"])
            self.assertTrue(Path(version["file"]).is_file())
        (static,) = catalog["static"]
        self.assertEqual(("raw.githubusercontent.com", "/chaotix345/rigtune/main/rules/rules-v1.json"),
                         (static["host"], static["path"]))
        json.dumps(catalog)

    def test_without_an_old_jar_rigtune_has_only_the_installed_version_and_extra_projects_are_listed(self):
        added = e2e_env.test_mod_jar(self.dir / "e2e-added-1.0.0.jar", "e2e-added")

        catalog = e2e_env.catalog(None, self.new, "26.2", [], extra_projects=[("E2EAddMd", "e2e-added", added)])

        rigtune, extra = catalog["projects"]
        self.assertEqual(["0.2.0+mc26.2"], [v["version_number"] for v in rigtune["versions"]])
        self.assertEqual(("E2EAddMd", "e2e-added"), (extra["id"], extra["slug"]))
        (version,) = extra["versions"]
        self.assertEqual(("1.0.0", [], ["26.2"], ["fabric"]),
                         (version["version_number"], version["dependencies"], version["game_versions"], version["loaders"]))
        self.assertTrue(Path(version["file"]).is_file())

    def test_test_mod_jar_is_a_minimal_fabric_mod(self):
        jar = e2e_env.test_mod_jar(self.dir / "e2e-x-1.0.0.jar", "e2e-x", "1.2.3")
        mod = e2e_env.mod_json(jar)
        self.assertEqual((1, "e2e-x", "1.2.3", {"fabricloader": ">=0.19.5"}),
                         (mod["schemaVersion"], mod["id"], mod["version"], mod["depends"]))
        with zipfile.ZipFile(jar) as archive:
            self.assertEqual(["fabric.mod.json"], archive.namelist())


if __name__ == "__main__":
    unittest.main()
