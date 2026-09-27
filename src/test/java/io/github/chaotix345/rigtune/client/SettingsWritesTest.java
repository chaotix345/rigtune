package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md X8 and 2R L6 (AC2R.2): settings.json is written only through SettingsSaver (its own thread, flushed on
// quit), never raw from the render thread or Probes.EXECUTOR. No ClientSettings.save(...) call outside SettingsSaver in
// src/client: a ClientSettings variable, field or parameter's save, or ClientSettings.shared(...).save(...).
class SettingsWritesTest {
	private static final Pattern DECLARED = Pattern.compile("\\bClientSettings\\s+(\\w+)\\s*[=;,)]");
	private static final Pattern SHARED_SAVE = Pattern.compile("ClientSettings\\.(?:shared|load)\\([^)]*\\)\\s*\\.save\\(");

	@Test
	void noRawSettingsSaveOutsideSettingsSaver() throws IOException {
		Path client = RepoFiles.resolve("src/client/java");
		List<String> raw = new ArrayList<>();
		try (Stream<Path> files = Files.walk(client)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String name = file.getFileName().toString();
				if (name.equals("SettingsSaver.java") || name.equals("ClientSettings.java")) {
					continue;
				}
				String source = Files.readString(file);
				Set<String> names = new LinkedHashSet<>(List.of("settings"));
				Matcher declared = DECLARED.matcher(source);
				while (declared.find()) {
					names.add(declared.group(1));
				}
				List<String> lines = source.lines().toList();
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					boolean save = SHARED_SAVE.matcher(line).find();
					for (String variable : names) {
						save |= Pattern.compile("\\b" + Pattern.quote(variable) + "(?:\\(\\))?\\.save\\(").matcher(line).find()
								&& !line.contains("SettingsSaver");
					}
					if (save) {
						raw.add(client.relativize(file) + ":" + (i + 1) + ": " + line.strip());
					}
				}
			}
		}
		assertEquals(List.of(), raw, "settings.json saved outside SettingsSaver");
	}

	// The check itself: it finds the raw save shapes 0.4 had (StartupNotices, BenchmarkMenuScreen) and lets SettingsSaver's
	// calls through.
	@Test
	void theCheckSeesARawSave() {
		String raw = "ClientSettings settings = ClientSettings.shared(configDir());\n\t\t\t\t\t\tsettings.save(configDir());";
		assertTrue(Pattern.compile("\\bsettings(?:\\(\\))?\\.save\\(").matcher(raw).find());
		assertTrue(SHARED_SAVE.matcher("ClientSettings.shared(dir).save(dir);").find());
		Matcher declared = DECLARED.matcher("private final ClientSettings clientSettings;");
		assertTrue(declared.find());
		assertEquals("clientSettings", declared.group(1));
	}
}
