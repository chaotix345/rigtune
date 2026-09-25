import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import add_mc_version as amv

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "add_mc_version"

SETTINGS = """plugins {
\tid 'dev.kikugie.stonecutter' version '0.9.8'
}

stonecutter {
\tkotlinController = false
\tcentralScript = 'build.gradle'

\tcreate(getRootProject()) {
\t\tversions '26.2', '26.3'
\t\tvcsVersion = '26.2'
\t}
}

rootProject.name = 'rigtune'
"""

PROPS_26_2 = """minecraft_dependency=~26.2
fabric_api_version=0.161.0+26.2
modmenu_version=20.0.2
sodium_version=mc26.2-0.9.2-fabric
# Iris 1.11.4+26.2-fabric (Modrinth version id), compileOnly for the benchmark
iris_version=gxZWWnKH
"""

PROPS_26_3 = """minecraft_dependency=~26.3
fabric_api_version=0.161.0+26.3
modmenu_version=21.0.0
sodium_version=mc26.3-0.9.2-fabric
# Iris 1.11.6+26.3-fabric (Modrinth version id), compileOnly for the benchmark
iris_version=bAdKrpw8
"""

BUILD_GRADLE_GUARDED = """dependencies {
\tif (project.hasProperty("sodium_version")) {
\t\tlocalRuntime "maven.modrinth:sodium:${project.sodium_version}"
\t}
}
"""

BUILD_GRADLE_UNGUARDED = """dependencies {
\tlocalRuntime "maven.modrinth:sodium:${project.sodium_version}"
}
"""


class McIdTest(unittest.TestCase):
    def test_parse_mc_id_release(self):
        self.assertEqual(amv.parse_mc_id("26.3"), amv.McId("26.3", "release", 0))
        self.assertEqual(amv.parse_mc_id("26.3.1"), amv.McId("26.3.1", "release", 0))

    def test_parse_mc_id_prereleases(self):
        self.assertEqual(amv.parse_mc_id("26.4-snapshot-1"), amv.McId("26.4", "snapshot", 1))
        self.assertEqual(amv.parse_mc_id("26.4-pre-2"), amv.McId("26.4", "pre", 2))
        self.assertEqual(amv.parse_mc_id("26.3.1-rc-1"), amv.McId("26.3.1", "rc", 1))

    def test_parse_mc_id_refuses_other_forms(self):
        for bad in ("25w14a", "1.21-pre1", "26", "26.4-snapshot", "26.4 ", "latest", "26.4-SNAPSHOT-1"):
            with self.subTest(bad=bad), self.assertRaises(amv.Refusal):
                amv.parse_mc_id(bad)

    def test_version_key_orders_prereleases_before_release(self):
        ordered = ["26.2", "26.2.1", "26.3-rc-1", "26.3", "26.4-snapshot-1", "26.4-snapshot-2", "26.4-pre-1",
                   "26.4-rc-1", "26.4", "26.4.1", "26.10"]
        self.assertEqual(sorted(reversed(ordered), key=amv.version_key), ordered)

    def test_minecraft_dependency(self):
        self.assertEqual(amv.minecraft_dependency("26.4"), "~26.4")
        self.assertEqual(amv.minecraft_dependency("26.3.1"), "~26.3.1")
        self.assertEqual(amv.minecraft_dependency("26.4-snapshot-1"), "~26.4-")
        self.assertEqual(amv.minecraft_dependency("26.3.1-rc-1"), "~26.3.1-")


class SettingsTest(unittest.TestCase):
    def test_read_settings_versions(self):
        self.assertEqual(amv.read_settings_versions(SETTINGS), ["26.2", "26.3"])

    def test_insert_settings_version_appends(self):
        out = amv.insert_settings_version(SETTINGS, "26.4-snapshot-1")
        self.assertEqual(out, SETTINGS.replace("versions '26.2', '26.3'", "versions '26.2', '26.3', '26.4-snapshot-1'"))

    def test_insert_settings_version_in_order(self):
        out = amv.insert_settings_version(SETTINGS, "26.2.1")
        self.assertIn("\t\tversions '26.2', '26.2.1', '26.3'\n", out)
        self.assertEqual(out.count("\n"), SETTINGS.count("\n"))

    def test_insert_settings_version_keeps_double_quotes(self):
        text = 'stonecutter {\n\tversions "26.2"\n}\n'
        self.assertEqual(amv.insert_settings_version(text, "26.3"), 'stonecutter {\n\tversions "26.2", "26.3"\n}\n')

    def test_insert_settings_version_refuses_duplicate(self):
        with self.assertRaises(amv.Refusal):
            amv.insert_settings_version(SETTINGS, "26.3")

    def test_insert_settings_version_refuses_without_versions_call(self):
        with self.assertRaises(amv.Refusal):
            amv.insert_settings_version("rootProject.name = 'rigtune'\n", "26.4")
        with self.assertRaises(amv.Refusal):
            amv.insert_settings_version("versions '26.2'\nversions '26.3'\n", "26.4")


class PropertiesTest(unittest.TestCase):
    def test_render_properties_full(self):
        values = {
            "minecraft_dependency": "~26.3",
            "fabric_api_version": "0.161.0+26.3",
            "modmenu_version": "21.0.0",
            "sodium_version": "mc26.3-0.9.2-fabric",
            "iris_comment": "# Iris 1.11.6+26.3-fabric (Modrinth version id), compileOnly for the benchmark",
            "iris_version": "bAdKrpw8",
        }
        text, copied = amv.render_properties(values, PROPS_26_2)
        self.assertEqual(text, PROPS_26_3)
        self.assertEqual(copied, [])

    def test_render_properties_without_sodium(self):
        values = {
            "minecraft_dependency": "~26.4-",
            "fabric_api_version": "0.161.1+26.4",
            "modmenu_version": "22.0.0-alpha.1",
            "sodium_version": None,
            "iris_comment": "# Iris x",
            "iris_version": "bAdKrpw8",
        }
        text, _ = amv.render_properties(values, PROPS_26_3)
        self.assertNotIn("sodium_version", text)
        self.assertEqual(text.splitlines()[-2:], ["# Iris x", "iris_version=bAdKrpw8"])

    def test_render_properties_copies_unknown_keys(self):
        template = PROPS_26_3 + "# DH API for this version\ndh_api_version=abc\n"
        values = {
            "minecraft_dependency": "~26.4",
            "fabric_api_version": "1",
            "modmenu_version": "2",
            "sodium_version": None,
            "iris_comment": "# Iris y",
            "iris_version": "z",
        }
        text, copied = amv.render_properties(values, template)
        self.assertTrue(text.endswith("# DH API for this version\ndh_api_version=abc\n"))
        self.assertEqual(copied, ["dh_api_version"])


def fixture(name):
    return (FIXTURES / name).read_bytes()


def manifest_url(mc):
    for entry in json.loads(fixture("manifest.json"))["versions"]:
        if entry["id"] == mc:
            return entry["url"]
    raise KeyError(mc)


def default_responses():
    responses = {
        amv.MANIFEST_URL: (200, fixture("manifest.json")),
        amv.FABRIC_API_METADATA_URL: (200, fixture("fabric-api-maven-metadata.xml")),
        amv.MODMENU_METADATA_URL: (200, fixture("modmenu-maven-metadata.xml")),
        amv.FABRIC_FEED_URL: (200, fixture("fabric-feed.xml")),
        "https://fabricmc.net/2026/09/15/263.html": (200, fixture("fabric-post-263.html")),
        amv.fabric_loader_url("26.5"): (400, b"[]"),
    }
    for mc in ("26.3", "26.4-snapshot-1", "26.5"):
        responses[manifest_url(mc)] = (200, fixture(f"version-{mc}.json"))
    for mc in ("26.3", "26.4-snapshot-1"):
        responses[amv.fabric_loader_url(mc)] = (200, fixture(f"fabric-loader-{mc}.json"))
        for project in ("fabric-api", "modmenu", "sodium", "iris"):
            responses[amv.modrinth_versions_url(project, mc)] = (200, fixture(f"modrinth-{project}-{mc}.json"))
    return responses


class FakeWorld:
    """A scripted network with a fake clock: every request takes 50 ms, sleeping advances the clock."""

    def __init__(self, overrides=None):
        self.responses = default_responses()
        self.responses.update(overrides or {})
        self.now = 0.0
        self.sleeps = []
        self.requests = []

    def opener(self, request):
        url = request.full_url
        self.requests.append((url, {k.lower(): v for k, v in request.header_items()}, self.now))
        self.now += 0.05
        if url not in self.responses:
            raise AssertionError(f"unscripted request to {url}")
        status, body = self.responses[url]
        return status, body, {}

    def sleeper(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def clock(self):
        return self.now

    def http(self):
        return amv.Http(opener=self.opener, sleeper=self.sleeper, clock=self.clock)


class RepoCase(unittest.TestCase):
    def make_repo(self, versions=("26.2", "26.3"), build_gradle=BUILD_GRADLE_GUARDED, release_yml=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        listed = ", ".join(f"'{v}'" for v in versions)
        (root / "settings.gradle").write_text(SETTINGS.replace("'26.2', '26.3'", listed), encoding="utf-8", newline="\n")
        (root / "gradle.properties").write_text("loader_version=0.19.5\n", encoding="utf-8", newline="\n")
        (root / "build.gradle").write_text(build_gradle, encoding="utf-8", newline="\n")
        props = {"26.2": PROPS_26_2, "26.3": PROPS_26_3}
        for v in versions:
            (root / "versions" / v).mkdir(parents=True)
            (root / "versions" / v / "gradle.properties").write_text(props[v], encoding="utf-8", newline="\n")
        if release_yml is not None:
            (root / ".github" / "workflows").mkdir(parents=True)
            (root / ".github" / "workflows" / "release.yml").write_text(release_yml, encoding="utf-8", newline="\n")
        return root

    def snapshot(self, root):
        return {str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}

    def run_tool(self, root, argv, world=None):
        world = world or FakeWorld()
        out, err = io.StringIO(), io.StringIO()
        code = amv.main(argv, root=root, http=world.http(), out=out, err=err)
        return code, out.getvalue(), err.getvalue(), world


class AddVersionTest(RepoCase):
    def test_release_adds_node_and_writes_properties(self):
        root = self.make_repo(versions=("26.2",))
        code, out, err, _ = self.run_tool(root, ["26.3"])
        self.assertEqual(code, 0, err)
        self.assertIn("\t\tversions '26.2', '26.3'\n", (root / "settings.gradle").read_text(encoding="utf-8"))
        self.assertEqual((root / "versions" / "26.3" / "gradle.properties").read_bytes(), PROPS_26_3.encode("utf-8"))
        self.assertNotIn(b"\r", (root / "settings.gradle").read_bytes())
        self.assertEqual(err, "")

    def test_added_node_gets_ci_game_test_legs(self):
        import gametest_matrix
        root = self.make_repo()
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"])
        self.assertEqual(code, 0, err)
        self.assertIn({"mc": "26.4-snapshot-1", "backend": "Vulkan"}, gametest_matrix.legs(root))
        self.assertEqual(gametest_matrix.nodes(root), amv.read_settings_versions((root / "settings.gradle").read_text(encoding="utf-8")))

    def test_snapshot_refused_without_prerelease_ok(self):
        root = self.make_repo()
        before = self.snapshot(root)
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1"])
        self.assertEqual(code, 1)
        self.assertIn("--prerelease-ok", err)
        self.assertEqual(self.snapshot(root), before)

    def test_snapshot_without_sodium_has_no_sodium_version(self):
        root = self.make_repo()
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"])
        self.assertEqual(code, 0, err)
        props = (root / "versions" / "26.4-snapshot-1" / "gradle.properties").read_text(encoding="utf-8")
        self.assertEqual(props, (
            "minecraft_dependency=~26.4-\n"
            "fabric_api_version=0.161.1+26.4\n"
            "modmenu_version=22.0.0-alpha.1\n"
            "# Iris 1.11.6+26.3-fabric (Modrinth version id), compileOnly for the benchmark; "
            "no Iris build for 26.4-snapshot-1 yet, so 26.3's is kept\n"
            "iris_version=bAdKrpw8\n"))
        self.assertIn("versions '26.2', '26.3', '26.4-snapshot-1'", (root / "settings.gradle").read_text(encoding="utf-8"))
        self.assertIn("No Sodium build for 26.4-snapshot-1", out)

    def test_existing_node_refused(self):
        root = self.make_repo()
        before = self.snapshot(root)
        world = FakeWorld()
        code, out, err, _ = self.run_tool(root, ["26.3"], world)
        self.assertEqual(code, 1)
        self.assertIn("26.3 is already a node", err)
        self.assertEqual(self.snapshot(root), before)
        self.assertEqual(world.requests, [])

    def test_existing_versions_dir_refused(self):
        root = self.make_repo(versions=("26.2",))
        (root / "versions" / "26.3").mkdir()
        code, out, err, _ = self.run_tool(root, ["26.3"])
        self.assertEqual(code, 1)
        self.assertIn("versions/26.3", err)

    def test_dry_run_writes_nothing(self):
        root = self.make_repo()
        before = self.snapshot(root)
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.snapshot(root), before)
        self.assertFalse((root / "versions" / "26.4-snapshot-1").exists())
        self.assertIn("+\t\tversions '26.2', '26.3', '26.4-snapshot-1'", out)
        self.assertIn("fabric_api_version=0.161.1+26.4", out)
        self.assertIn("Dry run: nothing was written", out)

    def test_java_not_25_refused(self):
        root = self.make_repo()
        before = self.snapshot(root)
        code, out, err, _ = self.run_tool(root, ["26.5"])
        self.assertEqual(code, 1)
        self.assertIn("Java 26", err)
        self.assertEqual(self.snapshot(root), before)

    def test_unknown_version_refused(self):
        root = self.make_repo()
        code, out, err, _ = self.run_tool(root, ["26.9"])
        self.assertEqual(code, 1)
        self.assertIn("26.9 isn't in the Mojang version manifest", err)

    def test_malformed_version_refused_before_any_request(self):
        root = self.make_repo()
        world = FakeWorld()
        code, out, err, _ = self.run_tool(root, ["26.4-SNAPSHOT-1"], world)
        self.assertEqual(code, 1)
        self.assertEqual(world.requests, [])

    def test_no_fabric_loader_refused(self):
        root = self.make_repo()
        world = FakeWorld({amv.fabric_loader_url("26.4-snapshot-1"): (400, b"[]")})
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"], world)
        self.assertEqual(code, 1)
        self.assertIn("Fabric Loader", err)

    def test_no_fabric_api_refused(self):
        root = self.make_repo()
        world = FakeWorld({amv.modrinth_versions_url("fabric-api", "26.4-snapshot-1"): (200, b"[]")})
        before = self.snapshot(root)
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"], world)
        self.assertEqual(code, 1)
        self.assertIn("no Fabric API build", err)
        self.assertEqual(self.snapshot(root), before)

    def test_fabric_api_must_be_on_the_fabric_maven(self):
        root = self.make_repo()
        meta = fixture("fabric-api-maven-metadata.xml").replace(b"<version>0.161.1+26.4</version>", b"")
        world = FakeWorld({amv.FABRIC_API_METADATA_URL: (200, meta)})
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"], world)
        self.assertEqual(code, 1)
        self.assertIn("maven.fabricmc.net", err)

    def test_no_modmenu_refused(self):
        root = self.make_repo()
        world = FakeWorld({amv.modrinth_versions_url("modmenu", "26.4-snapshot-1"): (200, b"[]")})
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"], world)
        self.assertEqual(code, 1)
        self.assertIn("no Mod Menu build", err)

    def test_prefers_release_over_newer_alpha(self):
        root = self.make_repo(versions=("26.2",))
        code, out, err, _ = self.run_tool(root, ["26.3", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertIn("sodium_version=mc26.3-0.9.2-fabric", out)
        self.assertNotIn("0.9.3-alpha.1", out)

    def test_every_request_sends_user_agent(self):
        root = self.make_repo()
        code, out, err, world = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertTrue(world.requests)
        for url, headers, _ in world.requests:
            self.assertEqual(headers.get("user-agent"), amv.USER_AGENT, url)

    def test_modrinth_calls_are_one_second_apart(self):
        root = self.make_repo()
        code, out, err, world = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        times = [t for url, _, t in world.requests if url.startswith("https://api.modrinth.com/")]
        self.assertEqual(len(times), 4)
        for earlier, later in zip(times, times[1:]):
            self.assertGreaterEqual(later - earlier, 1.0 - 1e-9)

    def test_retries_a_server_error(self):
        root = self.make_repo()

        class Flaky(FakeWorld):
            failed = False

            def opener(self, request):
                if request.full_url == amv.MANIFEST_URL and not self.failed:
                    self.failed = True
                    self.requests.append((request.full_url, {}, self.now))
                    return 503, b"", {"retry-after": "2"}
                return super().opener(request)

        world = Flaky()
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"], world)
        self.assertEqual(code, 0, err)
        self.assertIn(2.0, world.sleeps)

    def test_network_failure_is_reported(self):
        root = self.make_repo()
        world = FakeWorld({amv.MANIFEST_URL: (404, b"")})
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok"], world)
        self.assertEqual(code, 1)
        self.assertIn("HTTP 404", err)

    def test_checklist_printed(self):
        root = self.make_repo()
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertIn("./gradlew :26.4-snapshot-1:build", out)
        self.assertIn("python tools/mc_apidiff.py 26.3 26.4-snapshot-1", out)
        self.assertIn('./gradlew "Reset active project"', out)
        self.assertIn("pre-release node", out)

    def test_blog_post_lines_are_shown(self):
        root = self.make_repo(versions=("26.2",))
        code, out, err, _ = self.run_tool(root, ["26.3", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertIn("Developers should use Loom 1.17 and Gradle 9.6.0", out)

    def test_missing_blog_post_is_not_fatal(self):
        root = self.make_repo()
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertIn('No "Fabric for Minecraft 26.4" post', out)

    def test_warns_when_build_gradle_needs_sodium_guard(self):
        root = self.make_repo(build_gradle=BUILD_GRADLE_UNGUARDED)
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertIn("build.gradle puts Sodium on localRuntime unconditionally", out)

    def test_warns_about_hard_coded_release_steps(self):
        root = self.make_repo(release_yml="      - run: ./gradlew :26.2:modrinth\n      - run: ./gradlew :26.3:modrinth\n")
        code, out, err, _ = self.run_tool(root, ["26.4-snapshot-1", "--prerelease-ok", "--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertIn("release.yml", out)

    def test_hotfix_node_suggests_narrowing_the_old_range(self):
        root = self.make_repo()
        world = FakeWorld()
        manifest = json.loads(fixture("manifest.json"))
        manifest["versions"].insert(0, {"id": "26.3.1", "type": "release", "url": "https://example.invalid/26.3.1.json"})
        world.responses[amv.MANIFEST_URL] = (200, json.dumps(manifest).encode("utf-8"))
        world.responses["https://example.invalid/26.3.1.json"] = (200, fixture("version-26.3.json"))
        world.responses[amv.fabric_loader_url("26.3.1")] = (200, fixture("fabric-loader-26.3.json"))
        for project in ("fabric-api", "modmenu", "sodium", "iris"):
            world.responses[amv.modrinth_versions_url(project, "26.3.1")] = (200, fixture(f"modrinth-{project}-26.3.json"))
        code, out, err, _ = self.run_tool(root, ["26.3.1", "--dry-run"], world)
        self.assertEqual(code, 0, err)
        self.assertIn("versions '26.2', '26.3', '26.3.1'", out)
        self.assertIn("minecraft_dependency=~26.3.1", out)
        self.assertIn(">=26.3 <26.3.1-", out)


class RankTest(unittest.TestCase):
    def test_rank_modrinth_prefers_release_then_newest(self):
        versions = [
            {"version_type": "alpha", "date_published": "2026-09-20T00:00:00Z", "id": "a"},
            {"version_type": "release", "date_published": "2026-09-10T00:00:00Z", "id": "r-old"},
            {"version_type": "release", "date_published": "2026-09-15T00:00:00Z", "id": "r-new"},
            {"version_type": "beta", "date_published": "2026-09-19T00:00:00Z", "id": "b"},
        ]
        self.assertEqual([v["id"] for v in amv.rank_modrinth(versions)], ["r-new", "r-old", "b", "a"])
        self.assertEqual(amv.rank_modrinth([]), [])


if __name__ == "__main__":
    unittest.main()
