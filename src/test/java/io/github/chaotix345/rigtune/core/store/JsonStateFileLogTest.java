package io.github.chaotix345.rigtune.core.store;

import io.github.chaotix345.rigtune.core.store.JsonStateFile.State;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
	final List<String> logged = new CopyOnWriteArrayList<>();
	private AbstractAppender appender;

	@BeforeEach
	void capture() {
		appender = new AbstractAppender("rigtune-test-capture", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				StringWriter out = new StringWriter();
				out.append(event.getMessage().getFormattedMessage());
				if (event.getThrown() != null) {
					event.getThrown().printStackTrace(new PrintWriter(out));
				}
				logged.add(out.toString());
			}
		};
		appender.start();
		((Logger) LogManager.getLogger("RigTune")).addAppender(appender);
	}

	@AfterEach
	void release() {
		((Logger) LogManager.getLogger("RigTune")).removeAppender(appender);
		appender.stop();
	}

	private Path file() {
		return game.resolve("config").resolve("rigtune").resolve("sample.json");
	}

	private void write(String text) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	private void assertOnlyTheNameIsLogged() {
		assertFalse(logged.isEmpty());
		String home = System.getProperty("user.home");
		for (String line : logged) {
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
