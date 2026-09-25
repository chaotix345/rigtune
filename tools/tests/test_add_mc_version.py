import sys
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

PROPS_26_3 = """minecraft_dependency=~26.3
fabric_api_version=0.161.0+26.3
modmenu_version=21.0.0
sodium_version=mc26.3-0.9.2-fabric
# Iris 1.11.6+26.3-fabric (Modrinth version id), compileOnly for the benchmark
iris_version=bAdKrpw8
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
        template = PROPS_26_3.replace("26.3", "26.2")
        text, copied = amv.render_properties(values, template)
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


if __name__ == "__main__":
    unittest.main()
