package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelperLauncherTest {
	@Test
	void buildsJavaCommandForHelper() {
		Path java = Path.of("jre", "bin", "javaw.exe");
		Path ours = Path.of("mods", "rigtune.jar");
		Path gson = Path.of("libs", "gson.jar");
		Path pending = Path.of("config", "rigtune", "pending.json");

		List<String> command = HelperLauncher.buildCommand(java, List.of(ours, gson, ours), 1234, pending);

		assertEquals(List.of(java.toString(), "-cp", ours + File.pathSeparator + gson,
				"io.github.chaotix345.rigtune.core.apply.ApplyHelper", "1234", pending.toString()), command);
	}

	@Test
	void locatesCodeSourcesAndJava() {
		assertTrue(Files.exists(HelperLauncher.codeSourceOf(Gson.class)));
		assertTrue(Files.exists(HelperLauncher.codeSourceOf(ApplyHelper.class)));
		assertTrue(Files.isRegularFile(HelperLauncher.currentJava()));
	}

	@Test
	void helperRunsInChildJvmAfterGameExits(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Files.writeString(mods.resolve("new.jar" + PendingActions.PENDING_SUFFIX), "new");
		Files.writeString(mods.resolve("old.jar"), "old");
		Files.writeString(mods.resolve("old.jar.disabled"), "older");
		Path sodium = config.resolve("sodium-options.json");
		Files.writeString(sodium, "{\"performance\":{\"chunk_builder_threads\":0}}");

		Process finished = new ProcessBuilder(HelperLauncher.currentJava().toString(), "-version")
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
		assertTrue(finished.waitFor(60, TimeUnit.SECONDS));
		long deadPid = finished.pid();

		Path pending = PendingActions.defaultPath(config);
		PendingActions.create(deadPid, mods, config, List.of(
				Op.disableFile(mods.resolve("old.jar")),
				Op.enableFile(mods.resolve("new.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("new.jar")),
				Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "3")))).save(pending);

		List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.filter(s -> !s.isBlank())
				.map(Path::of)
				.toList();
		Path log = HelperLauncher.helperLog(config);
		Files.createDirectories(log.getParent());
		Process helper = new ProcessBuilder(HelperLauncher.buildCommand(HelperLauncher.currentJava(), classpath, deadPid, pending))
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();

		boolean exited = helper.waitFor(90, TimeUnit.SECONDS);
		if (!exited) {
			helper.destroyForcibly();
		}
		String output = Files.readString(log);
		assertTrue(exited, output);
		assertEquals(0, helper.exitValue(), output);
		assertTrue(output.contains("not found; proceeding") || output.contains("Waiting for game process"), output);
		assertEquals("new", Files.readString(mods.resolve("new.jar")));
		assertEquals("old", Files.readString(mods.resolve("old.jar.disabled.1")));
		assertEquals("older", Files.readString(mods.resolve("old.jar.disabled")));
		assertFalse(Files.exists(mods.resolve("old.jar")));
		assertTrue(Files.readString(sodium).contains("\"chunk_builder_threads\": 3"));
		assertFalse(Files.exists(pending));
		assertTrue(ApplyResult.load(ApplyResult.defaultPath(config)).allSucceeded());
	}
}
