package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// src/test/resources/realworld/README.md: file names only. No file of the real-world fixtures holds a machine path, a user
// or instance name, or a path that doesn't start with the ${INSTANCE} token.
class RealWorldFixturesTest {
	// Matched ignoring case (review L11); "profiles/" as a path, since a mod's own name may hold the word (InventoryProfilesNext).
	private static final List<String> FORBIDDEN = List.of("users", "admin", "appdata", "modrinthapp", "profiles/", "profiles\\", "fabric 26.2", "home/",
			":\\", ":/", "chaotix345", "charlie");
	// A JSON value that holds a path (pending.json's and last-apply.json's op paths, the plan's folders).
	private static final Pattern PATH_VALUE = Pattern.compile("\"(path|from|to|modsDir|configDir)\"\\s*:\\s*\"([^\"]*)\"");

	private static List<Path> files() throws IOException {
		try (Stream<Path> walk = Files.walk(RepoFiles.resolve("src/test/resources/realworld"), 4)) {
			return walk.filter(Files::isRegularFile).toList();
		}
	}

	@Test
	void noMachinePathOrName() throws IOException {
		List<Path> files = files();
		assertTrue(files.size() >= 7, files.toString());
		for (Path file : files) {
			if (file.getFileName().toString().equals("README.md")) {
				continue;
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			String lower = text.toLowerCase(Locale.ROOT);
			for (String word : FORBIDDEN) {
				assertFalse(lower.contains(word), file + " holds \"" + word + "\"");
			}
			Matcher m = PATH_VALUE.matcher(text);
			while (m.find()) {
				assertTrue(m.group(2).startsWith(RealWorldFixtures.TOKEN + "/"), file + ": " + m.group());
			}
		}
	}

	@Test
	void theCaptureLoads() {
		assertEquals(52, RealWorldFixtures.modsListing().size());
		assertEquals("distanthorizons", RealWorldFixtures.modIds().get(RealWorldFixtures.DH));
		assertTrue(RealWorldFixtures.modsListing().containsAll(RealWorldFixtures.modIds().keySet()));
	}
}
