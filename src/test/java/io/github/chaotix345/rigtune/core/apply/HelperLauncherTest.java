package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
	void copiesJarsOutOfTheModsFolderAndKeepsDirectories(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path ours = Files.writeString(mods.resolve("rigtune-1.0.jar"), "ours");
		Path gson = Files.writeString(Files.createDirectories(dir.resolve("libs")).resolve("gson.jar"), "gson");
		Path classes = Files.createDirectories(dir.resolve("classes"));
		Path helperDir = HelperLauncher.helperDir(dir.resolve("config"));
		Files.createDirectories(helperDir);
		Files.writeString(helperDir.resolve("stale.jar"), "old");

		List<Path> classpath = HelperLauncher.helperClasspath(helperDir, List.of(ours, gson, classes, ours));

		assertEquals(List.of(helperDir.resolve("0-rigtune-1.0.jar"), helperDir.resolve("1-gson.jar"), classes), classpath);
		assertEquals("ours", Files.readString(classpath.get(0)));
		assertEquals("gson", Files.readString(classpath.get(1)));
		assertTrue(classpath.stream().noneMatch(p -> p.startsWith(mods)));
		assertFalse(Files.exists(helperDir.resolve("stale.jar")));

		FileTime copied = Files.getLastModifiedTime(classpath.get(0));
		assertEquals(classpath, HelperLauncher.helperClasspath(helperDir, List.of(ours, gson, classes)));
		assertEquals(copied, Files.getLastModifiedTime(classpath.get(0)));

		Files.writeString(ours, "ours, updated");
		assertEquals("ours, updated", Files.readString(HelperLauncher.helperClasspath(helperDir, List.of(ours, gson)).get(0)));
	}

	@Test
	void copyInUseByARunningHelperGetsAFreshName(@TempDir Path dir) throws Exception {
		Path ours = Files.writeString(dir.resolve("rigtune.jar"), "v1");
		Path helperDir = HelperLauncher.helperDir(dir.resolve("config"));
		Path first = HelperLauncher.helperClasspath(helperDir, List.of(ours)).getFirst();
		Files.writeString(ours, "v2");

		try (FileChannel inUse = FileChannel.open(first, StandardOpenOption.READ)) {
			Path second = HelperLauncher.helperClasspath(helperDir, List.of(ours)).getFirst();
			assertEquals("v2", Files.readString(second));
			assertTrue(inUse.isOpen());
		}
	}

	private static Path jarOf(Path classesDir, Path jar) throws IOException {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out);
				Stream<Path> files = Files.walk(classesDir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				zip.putNextEntry(new ZipEntry(classesDir.relativize(file).toString().replace(File.separatorChar, '/')));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
		return jar;
	}

	private record SelfUpdate(Path mods, Path config, Path pending, Path oldJar, List<PendingActions.Op> ops) {
		List<String> enabledRigTuneJars() throws IOException {
			try (Stream<Path> files = Files.list(mods)) {
				return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("rigtune") && n.endsWith(".jar")).sorted().toList();
			}
		}
	}

	// mods/rigtune-1.0.jar is a real RigTune jar (built from the main classes), and the plan updates it to 2.0.
	private static SelfUpdate selfUpdate(Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Path oldJar = jarOf(HelperLauncher.codeSourceOf(ApplyHelper.class), mods.resolve("rigtune-1.0.jar"));
		Path newPending = Files.copy(oldJar, mods.resolve("rigtune-2.0.jar" + PendingActions.PENDING_SUFFIX));
		Path pending = PendingActions.defaultPath(config);
		List<PendingActions.Op> ops = PendingActions.group(Op.disableFile(oldJar),
				Op.enableFile(newPending, mods.resolve("rigtune-2.0.jar")).withModId("rigtune"));
		PendingActions.create(1, mods, config, ops).save(pending);
		return new SelfUpdate(mods, config, pending, oldJar, ops);
	}

	private static String awaitHelper(Process helper, Path log) throws Exception {
		boolean exited = helper.waitFor(120, TimeUnit.SECONDS);
		if (!exited) {
			helper.destroyForcibly();
		}
		String output = Files.readString(log);
		assertTrue(exited, output);
		return output;
	}

	@Test
	void selfUpdateSucceedsBecauseTheHelperRunsFromCopies(@TempDir Path dir) throws Exception {
		SelfUpdate update = selfUpdate(dir);
		Path gson = HelperLauncher.codeSourceOf(Gson.class);

		Process helper = HelperLauncher.launch(update.config(), update.pending(), List.of(update.oldJar(), gson), ApplyLockTest.deadPid());

		String output = awaitHelper(helper, HelperLauncher.helperLog(update.config()));
		assertEquals(0, helper.exitValue(), output);
		assertEquals(List.of("rigtune-2.0.jar"), update.enabledRigTuneJars(), output);
		assertTrue(Files.exists(update.mods().resolve("rigtune-1.0.jar.disabled")), output);
		assertFalse(Files.exists(update.pending()), output);
		try (Stream<Path> copies = Files.list(HelperLauncher.helperDir(update.config()))) {
			assertEquals(List.of("0-rigtune-1.0.jar", "1-" + gson.getFileName()), copies.map(p -> p.getFileName().toString()).sorted().toList());
		}
	}

	// Without the copies, Windows keeps mods/rigtune-1.0.jar open in the helper JVM, so the disable fails. The group
	// then skips the enable, so there is still exactly one RigTune jar, and the update stays pending.
	@Test
	void helperRunningFromTheModsJarStillNeverLeavesTwoRigTuneJars(@TempDir Path dir) throws Exception {
		SelfUpdate update = selfUpdate(dir);
		Path log = dir.resolve("helper.log");
		Process helper = new ProcessBuilder(HelperLauncher.buildCommand(HelperLauncher.currentJava(),
				List.of(update.oldJar(), HelperLauncher.codeSourceOf(Gson.class)), ApplyLockTest.deadPid(), update.pending()))
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();

		String output = awaitHelper(helper, log);
		assertEquals(1, update.enabledRigTuneJars().size(), output);
		if (System.getProperty("os.name").startsWith("Windows")) {
			assertEquals(1, helper.exitValue(), output);
			assertEquals(List.of("rigtune-1.0.jar"), update.enabledRigTuneJars());
			assertTrue(Files.exists(update.mods().resolve("rigtune-2.0.jar" + PendingActions.PENDING_SUFFIX)));
			assertEquals(update.ops(), PendingActions.load(update.pending()).ops());
		}
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
