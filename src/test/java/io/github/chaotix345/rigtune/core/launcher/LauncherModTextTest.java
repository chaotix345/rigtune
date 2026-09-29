package io.github.chaotix345.rigtune.core.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4b: the one source of launcher wording for mod files. AC4b.5 (guideLine), AC4b.4 (Preview's and the
// share report's lines), AC4b.3 (a steps key for every launcher and kind the policy can advise; the Modrinth App, Prism,
// GDLauncher and ATLauncher English pinned to the labels in docs/v0.5/design/ws-l1.md, from each launcher's own source).
class LauncherModTextTest {
	private static final LauncherInfo MODRINTH_APP = LauncherInfo.of(Launcher.MODRINTH_APP);
	private static JsonObject lang;

	@BeforeAll
	static void load() throws IOException {
		try (InputStream in = LauncherModTextTest.class.getResourceAsStream("/assets/rigtune/lang/en_us.json")) {
			assertNotNull(in, "en_us.json on the classpath");
			lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	// The text as the game shows it in English: every key from en_us.json (so the code's English and the file's agree).
	private static String shown(Text text) {
		return text.render(key -> {
			assertTrue(lang.has(key), key + " is in en_us.json");
			return lang.get(key).getAsString();
		});
	}

	@Test
	void theGuideLine() {
		Text launcher = LauncherModText.guideLine(ModFilesPolicy.LAUNCHER, MODRINTH_APP, false);
		assertEquals("This instance's mods are managed by the Modrinth App: RigTune changes settings only.", shown(launcher));
		assertEquals(shown(launcher), launcher.english());
		assertEquals("This instance's mods are managed by your launcher: RigTune changes settings only.",
				shown(LauncherModText.guideLine(ModFilesPolicy.LAUNCHER, LauncherInfo.UNKNOWN, false)));
		assertEquals("This instance's mods are managed by your launcher: RigTune changes settings only.",
				shown(LauncherModText.guideLine(ModFilesPolicy.LAUNCHER, LauncherInfo.of(Launcher.OFFICIAL), false)));
		Text optedIn = LauncherModText.guideLine(ModFilesPolicy.RIGTUNE, MODRINTH_APP, true);
		assertEquals("RigTune changes mod files here; the Modrinth App's own list may go out of date.", shown(optedIn));
		assertEquals(shown(optedIn), optedIn.english());
		assertNull(LauncherModText.guideLine(ModFilesPolicy.RIGTUNE, MODRINTH_APP, false));
		assertNull(LauncherModText.guideLine(ModFilesPolicy.RIGTUNE, null, false));
		assertNull(LauncherModText.guideLine(ModFilesPolicy.PENDING, null, false));
		assertNull(LauncherModText.guideLine(ModFilesPolicy.PENDING, MODRINTH_APP, false));
	}

	// Who is named: a launcher with a name, except the official launcher (a packwiz index there isn't its record).
	@Test
	void whoIsNamed() {
		for (Launcher launcher : Launcher.values()) {
			Text name = LauncherModText.launcherName(LauncherInfo.of(launcher));
			boolean named = launcher != Launcher.OFFICIAL && launcher != Launcher.UNKNOWN;
			assertEquals(named, name != null, launcher.name());
			if (named) {
				assertEquals(lang.get(LauncherInfo.of(launcher).nameKey()).getAsString(), shown(name));
				assertEquals(shown(name), name.english(), launcher + ": the code's English is en_us.json's");
			}
		}
		assertNull(LauncherModText.launcherName(null));
	}

	@Test
	void thePreviewLine() {
		assertEquals("Mod changes to make in the Modrinth App: 3 (listed on the main screen)",
				shown(LauncherModText.previewLine(ModFilesPolicy.LAUNCHER, MODRINTH_APP, 3)));
		assertEquals("Mod changes to make in your launcher: 1 (listed on the main screen)",
				shown(LauncherModText.previewLine(ModFilesPolicy.LAUNCHER, LauncherInfo.UNKNOWN, 1)));
		assertEquals("Mod changes waiting until RigTune knows which launcher manages this instance's mods: 2 (listed on the main screen)",
				shown(LauncherModText.previewLine(ModFilesPolicy.PENDING, null, 2)));
		assertNull(LauncherModText.previewLine(ModFilesPolicy.LAUNCHER, MODRINTH_APP, 0));
		assertNull(LauncherModText.previewLine(ModFilesPolicy.RIGTUNE, MODRINTH_APP, 3));
	}

	@Test
	void theShareReportLine() {
		assertEquals("changed in Modrinth App", LauncherModText.shareLine(ModFilesPolicy.LAUNCHER, MODRINTH_APP, false));
		assertEquals("changed in the launcher", LauncherModText.shareLine(ModFilesPolicy.LAUNCHER, LauncherInfo.UNKNOWN, false));
		assertEquals("waiting for the launcher check", LauncherModText.shareLine(ModFilesPolicy.PENDING, null, false));
		assertEquals("RigTune (opted in)", LauncherModText.shareLine(ModFilesPolicy.RIGTUNE, MODRINTH_APP, true));
		assertNull(LauncherModText.shareLine(ModFilesPolicy.RIGTUNE, MODRINTH_APP, false));
		assertNull(LauncherModText.shareLine(ModFilesPolicy.RIGTUNE, LauncherInfo.UNKNOWN, false));
	}

	// AC4b.3: every launcher the policy can name has steps for every kind; the others have none.
	@Test
	void aStepsKeyForEveryLauncherAndKind() {
		for (Launcher launcher : Launcher.values()) {
			LauncherInfo info = LauncherInfo.of(launcher);
			for (String kind : LauncherInfo.MOD_KINDS) {
				String key = info.modStepsKey(kind);
				if (LauncherModText.launcherName(info) == null) {
					assertNull(key, launcher + " " + kind);
					continue;
				}
				assertNotNull(key, launcher + " " + kind);
				assertTrue(lang.has(key), key + " is in en_us.json");
			}
		}
		assertEquals("rigtune.launcher.mod_steps.modrinth_app.self_update", MODRINTH_APP.modStepsKey("self_update"));
		assertEquals("rigtune.launcher.mod_steps.prism.update", LauncherInfo.of(Launcher.PRISM).modStepsKey("self_update"));
	}

	// AC4b.3: the English of the launchers whose labels come from their own source or locale files (ws-l1.md, "Steps
	// labels"): each step names exactly those labels.
	@Test
	void theStepsUseTheLaunchersOwnLabels() {
		Map<String, String> expected = Map.ofEntries(
				Map.entry("modrinth_app.add", "this instance → Content → Browse content, then install it there."),
				Map.entry("modrinth_app.update", "this instance → Content → select it → Update (or Update all)."),
				Map.entry("modrinth_app.disable", "this instance → Content → select it → Disable."),
				Map.entry("modrinth_app.enable", "this instance → Content → filter State → Disabled → select it → Enable."),
				Map.entry("modrinth_app.self_update",
						"update RigTune in this instance's Content tab, or download it and use Content → Upload files, then delete the old RigTune there."),
				Map.entry("prism.add", "right-click this instance → Edit... → Mods → Download Mods."),
				Map.entry("prism.update", "right-click this instance → Edit... → Mods → select it → Check for Updates."),
				Map.entry("prism.disable", "right-click this instance → Edit... → Mods → select it → Disable."),
				Map.entry("prism.enable", "right-click this instance → Edit... → Mods → select it → Enable."),
				Map.entry("gdlauncher.add", "this instance → Mods → Add Mod."),
				Map.entry("gdlauncher.update", "this instance → Mods → select it → Update (or Update All Mods)."),
				Map.entry("gdlauncher.disable", "this instance → Mods → select it → Disable Mod."),
				Map.entry("gdlauncher.enable", "this instance → Mods → select it → Enable."),
				Map.entry("atlauncher.add", "this instance's Edit Mods button → Browse Mods."),
				Map.entry("atlauncher.update", "this instance's Edit Mods button → select it → Check For Updates."),
				Map.entry("atlauncher.disable", "this instance's Edit Mods button → select it → Disable Selected."),
				Map.entry("atlauncher.enable", "this instance's Edit Mods button → select it → Enable Selected."));
		for (Map.Entry<String, String> e : expected.entrySet()) {
			assertEquals(e.getValue(), lang.get("rigtune.launcher.mod_steps." + e.getKey()).getAsString(), e.getKey());
		}
		Set<String> kinds = Set.copyOf(LauncherInfo.MOD_KINDS);
		assertEquals(Set.of("add", "update", "disable", "enable", "self_update"), kinds);
		assertEquals(List.of("add", "update", "disable", "enable", "self_update"), LauncherInfo.MOD_KINDS);
	}
}
