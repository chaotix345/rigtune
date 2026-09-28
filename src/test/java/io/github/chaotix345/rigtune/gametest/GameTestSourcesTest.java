package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v0.5 SPEC 1e (ws-ci): rules for the client game tests' sources, checked on the files themselves.
class GameTestSourcesTest {
	private static final Path GAMETEST = RepoFiles.resolve("src/gametest/java/io/github/chaotix345/rigtune/gametest");

	// AC1e.2: RigTune's network switch changes only through GameTestNet, which rescans and waits for the matching report;
	// writing the field alone left the report on screen online or offline by timing (BenchmarkHistoryGameTest, WS-X).
	@Test
	void onlyGameTestNetWritesTheNetworkSwitch() throws IOException {
		Pattern write = Pattern.compile("\\.networkEnabled\\s*=(?!=)");
		List<String> writers;
		try (Stream<Path> files = Files.list(GAMETEST)) {
			writers = files.filter(f -> f.toString().endsWith(".java") && !f.getFileName().toString().equals("GameTestNet.java"))
					.filter(f -> write.matcher(read(f)).find())
					.map(f -> f.getFileName().toString())
					.toList();
		}
		assertEquals(List.of(), writers);
		assertTrue(write.matcher(read(GAMETEST.resolve("GameTestNet.java"))).find(), "GameTestNet itself writes it");
	}

	// The tick hooks' 0-allocation keys are the sum over every timed block (FootprintBudgets.allocatedBytes), never the
	// fewest-allocating block (coordinator: the 0-allocation checks stay strict).
	@Test
	void footprintTickAllocationIsTheSumOverEveryBlock() throws IOException {
		String footprint = Files.readString(GAMETEST.resolve("FootprintGameTest.java"), StandardCharsets.UTF_8);
		assertTrue(footprint.contains("FootprintBudgets.allocatedBytes(blockBytes)"), "timeTick sums the blocks' bytes");
		assertFalse(Pattern.compile("Math\\.min\\(\\s*bytes").matcher(footprint).find(), "no fewest-allocating block");
	}

	// Singleplayer worlds are opened and left through GameTestWorlds, which holds the integrated server while the render
	// thread halts it, so the halt can't deadlock the harness's tick phases (run 36314730108).
	@Test
	void singleplayerWorldsAreLeftThroughGameTestWorlds() throws IOException {
		List<String> offenders;
		try (Stream<Path> files = Files.list(GAMETEST)) {
			offenders = files.filter(f -> f.toString().endsWith(".java") && !f.getFileName().toString().equals("GameTestWorlds.java"))
					.filter(f -> {
						String source = read(f);
						return source.contains("worldBuilder().create()") || source.contains("runOnClient(BenchmarkWorld::exitNow)");
					})
					.map(f -> f.getFileName().toString())
					.toList();
		}
		assertEquals(List.of(), offenders);
	}

	// AC1e.1: UiGameTest's settings checks (waitForSaved) wait for the save on SettingsSaver, not by polling settings.json.
	@Test
	void uiGameTestWaitsOnSettingsSaverNotAPoll() {
		String ui = read(GAMETEST.resolve("UiGameTest.java"));
		int start = ui.indexOf("private static void waitForSaved(");
		assertTrue(start >= 0, "UiGameTest.waitForSaved");
		String method = ui.substring(start, ui.indexOf("\n\t}\n", start));
		assertTrue(method.contains("SettingsSaver.shared().flush("), "waitForSaved flushes SettingsSaver: " + method);
		assertFalse(method.contains("waitFor("), "waitForSaved doesn't poll: " + method);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
