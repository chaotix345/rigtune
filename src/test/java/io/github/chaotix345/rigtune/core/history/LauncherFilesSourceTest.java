package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 4g (AC4g.3): the repair notice, like everything else in RigTune, works from RigTune's own records.
// No source names a launcher's database or metadata file, except the instance files launcher detection reads today
// (InstanceFiles) and the packwiz .index/ listing (names only; WS-L1's evidence).
class LauncherFilesSourceTest {
	// A launcher's database or its own record of an instance's content, lower-case.
	static final List<String> LAUNCHER_FILES = List.of("app.db", ".sqlite", "sqlite3", "instance.cfg", "mmc-pack.json", "minecraftinstance.json",
			"instance.json", "launcher_profiles.json", "launcher_settings.json", "launcher_accounts", ".pw.toml", "pack.toml", "modrinthapp",
			"gdlauncher_carbon");

	// Line and block comments (a "//" inside a string literal is rare enough here not to matter).
	static final Pattern COMMENTS = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);

	static final Map<String, Set<String>> ALLOWED = Map.of(
			"src/main/java/io/github/chaotix345/rigtune/core/launcher/InstanceFiles.java", Set.of("instance.cfg", "mmc-pack.json", "minecraftinstance.json",
					"instance.json"),
			"src/main/java/io/github/chaotix345/rigtune/core/launcher/InstanceEvidence.java", Set.of(".pw.toml"),
			"src/client/java/io/github/chaotix345/rigtune/client/probe/LauncherProbe.java", Set.of(".pw.toml"));

	@Test
	void noSourceReadsALaunchersDatabaseOrRecords() throws IOException {
		Path root = RepoFiles.root();
		List<String> found = new ArrayList<>();
		for (String dir : List.of("src/main/java", "src/client/java")) {
			try (Stream<Path> files = Files.walk(root.resolve(dir))) {
				for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
					String relative = root.relativize(file).toString().replace('\\', '/');
					// The code only: a comment may name a launcher's file to say what the code does or doesn't read.
					String source = COMMENTS.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll(" ").toLowerCase(Locale.ROOT);
					for (String name : LAUNCHER_FILES) {
						if (source.contains(name) && !ALLOWED.getOrDefault(relative, Set.of()).contains(name)) {
							found.add(relative + ": " + name);
						}
					}
				}
			}
		}
		assertEquals(List.of(), found);
	}
}
