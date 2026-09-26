package io.github.chaotix345.rigtune.core.store;

import io.github.chaotix345.rigtune.core.LogCapture;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// review-8 JW-1: the new state files' log lines name the file under config/rigtune ("sample.json"), never its absolute
// path, which holds the Windows account name (players paste latest.log into issues).
class JsonStateFileLogTest {
	record Sample(int formatVersion, List<String> items) {
	}

	@TempDir
	Path game;
	private LogCapture log;

	@BeforeEach
	void capture() {
		log = new LogCapture();
	}

	@AfterEach
	void release() {
		log.close();
	}

	private Path file() {
		return game.resolve("config").resolve("rigtune").resolve("sample.json");
	}

	private void write(String text) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	private void assertOnlyTheNameIsLogged() {
		assertFalse(log.lines().isEmpty());
		String home = System.getProperty("user.home");
		for (String line : log.lines()) {
			assertTrue(line.contains("sample.json"), line);
			assertFalse(line.contains(game.toString()), line);
			assertFalse(line.contains(game.toAbsolutePath().toString()), line);
			assertFalse(line.contains(home), line);
		}
	}

	@Test
	void aCorruptFile() throws IOException {
		write("{broken");

		assertEquals(State.MOVED_ASIDE, new JsonStateFile(file(), 1024).load(Sample.class).state());

		assertOnlyTheNameIsLogged();
	}

	@Test
	void aNewerFile() throws IOException {
		write("{\"formatVersion\": 9}");

		assertEquals(State.NEWER, new JsonStateFile(file(), 1024).load(Sample.class).state());

		assertOnlyTheNameIsLogged();
	}

	@Test
	void aFileFarOverTheCap() throws IOException {
		write("{\"items\": [\"" + "x".repeat(5000) + "\"]}");

		assertEquals(State.UNREADABLE, new JsonStateFile(file(), 1024).load(Sample.class).state());

		assertOnlyTheNameIsLogged();
	}

	@Test
	void aReadErrorAndACorruptFileThatCantBeMoved() throws IOException {
		write("{broken");
		JsonStateFile.Io failing = new JsonStateFile.Io() {
			@Override
			public byte[] read(Path file) throws IOException {
				throw new java.nio.file.AccessDeniedException(file.toString());
			}

			@Override
			public void moveAside(Path from, Path to) throws IOException {
				throw new java.nio.file.FileSystemException(from.toString(), to.toString(), "in use");
			}
		};
		JsonStateFile.Io noMove = new JsonStateFile.Io() {
			@Override
			public byte[] read(Path file) throws IOException {
				return Files.readAllBytes(file);
			}

			@Override
			public void moveAside(Path from, Path to) throws IOException {
				failing.moveAside(from, to);
			}
		};

		assertEquals(State.UNREADABLE, new JsonStateFile(file(), 1024, JsonStateFile.GSON, failing).load(Sample.class).state());
		assertEquals(State.UNREADABLE, new JsonStateFile(file(), 1024, JsonStateFile.GSON, noMove).load(Sample.class).state());

		assertOnlyTheNameIsLogged();
	}

	@Test
	void aFailedOrRefusedWrite() throws IOException {
		Files.createDirectories(game.resolve("config"));
		Files.writeString(game.resolve("config").resolve("rigtune"), "a file where the folder should be");

		assertEquals(JsonStateFile.Saved.FAILED, new JsonStateFile(file(), 1024).save(new Sample(1, List.of())));
		assertEquals(JsonStateFile.Saved.TOO_LARGE, new JsonStateFile(file(), 16).save(new Sample(1, List.of("x".repeat(64)))));

		assertOnlyTheNameIsLogged();
	}
}
