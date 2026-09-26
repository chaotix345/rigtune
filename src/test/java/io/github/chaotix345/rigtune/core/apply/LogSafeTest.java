package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// review-8 JW-1 and SE-4: what RigTune's log lines may say about a file and about text from files or the network.
class LogSafeTest {
	private static final Path GAME = Path.of(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft").toAbsolutePath();

	@Test
	void aRigTuneFileIsNamedUnderConfigRigtune() {
		assertEquals("awareness.json", LogSafe.name(GAME.resolve("config").resolve("rigtune").resolve("awareness.json")));
		assertEquals("helper/unfinished-groups.json", LogSafe.name(GAME.resolve("config").resolve("rigtune").resolve("helper").resolve("unfinished-groups.json")));
	}

	@Test
	void anyOtherFileByItsFileName() {
		assertEquals("sodium-0.7.1.jar", LogSafe.name(GAME.resolve("mods").resolve("sodium-0.7.1.jar")));
		assertEquals("sodium-options.json", LogSafe.name(GAME.resolve("config").resolve("sodium-options.json")));
		assertEquals("null", LogSafe.name(null));
	}

	@Test
	void anErrorLosesTheFoldersOfItsFilesAndTheHomeFolder() {
		Path file = GAME.resolve("config").resolve("rigtune").resolve("awareness.json");
		String error = LogSafe.error(new AccessDeniedException(file + ".tmp", GAME.resolve("elsewhere").toString(), "denied"), file);

		assertTrue(error.startsWith("AccessDeniedException: "), error);
		assertTrue(error.contains("awareness.json.tmp"), error);
		assertFalse(error.contains(System.getProperty("user.home")), error);
		assertFalse(error.contains(GAME.toString()), error);
	}

	// Review of fix-8a: an HttpClient failure's reason is often only in its cause.
	@Test
	void anErrorNamesItsRootCauseToo() {
		Path file = GAME.resolve("mods").resolve("x.jar");
		IOException e = new IOException(new java.net.ConnectException("Connection refused: " + file));

		String error = LogSafe.error(e, GAME.resolve("mods"));

		assertTrue(error.startsWith("IOException: "), error);
		assertTrue(error.endsWith("(caused by ConnectException: Connection refused: mods\\x.jar)") || error.endsWith("(caused by ConnectException: Connection refused: mods/x.jar)"), error);
		assertFalse(error.contains(System.getProperty("user.home")), error);
	}

	@Test
	void onWindowsTheHomeFolderIsCutWhateverItsLetterCase() {
		assumeTrue(java.io.File.separatorChar == '\\');
		String home = System.getProperty("user.home");

		assertEquals("IOException: ~\\x", LogSafe.error(new IOException(home.toUpperCase(java.util.Locale.ROOT) + "\\x")));
	}

	@Test
	void untrustedTextCantForgeALogLine() {
		assertEquals("x.jar\\u000a[main/INFO] fake", LogSafe.text("x.jar\n[main/INFO] fake"));
		assertEquals("\\u001b[31mred", LogSafe.text("\u001b[31mred"));
		assertEquals("gpj.\\u202eexe.jar", LogSafe.text("gpj.\u202eexe.jar"));
		assertEquals("a\\u200bb\\u2028c\\u0085d", LogSafe.text("a\u200bb\u2028c\u0085d"));
		assertEquals("Sodium Extra 0.7", LogSafe.text("Sodium Extra 0.7"));
		assertEquals("null", LogSafe.text(null));
		assertEquals(LogSafe.MAX_TEXT + 1, LogSafe.text("y".repeat(1000)).length());
	}
}
