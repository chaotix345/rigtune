package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class HelperLauncher {
	// docs/v0.5/SPEC.md 4d: groups with a mod-file op wait in pending.json for the player's choice (ApplyExecutor.held).
	// Only the 0.5 helper reads it, and the helper always runs from a copy of the current jar (launch); 0.4.0 and older
	// apply such groups at their next exit (their behaviour after a downgrade).
	public static final String HOLD_FILE_OPS_PROPERTY = "rigtune.helper.holdFileOps";

	private HelperLauncher() {
	}

	// Whether the helper holds mod-file changes under this policy: in an instance whose launcher keeps its own list of
	// mods, and while that isn't known yet (PENDING, or no answer at all).
	public static boolean holds(ModFilesPolicy policy) {
		return policy != ModFilesPolicy.RIGTUNE;
	}

	public static List<String> buildCommand(Path javaExecutable, List<Path> classpath, long gamePid, Path pendingJson) {
		return buildCommand(javaExecutable, classpath, gamePid, pendingJson, null);
	}

	// modsFolder: the game's resolved -Dfabric.modsFolder (null when unset), handed on so the helper sees the same folder.
	public static List<String> buildCommand(Path javaExecutable, List<Path> classpath, long gamePid, Path pendingJson, Path modsFolder) {
		return buildCommand(javaExecutable, classpath, gamePid, pendingJson, modsFolder, false);
	}

	public static List<String> buildCommand(Path javaExecutable, List<Path> classpath, long gamePid, Path pendingJson, Path modsFolder,
			boolean holdFileOps) {
		String cp = classpath.stream().map(Path::toString).distinct().collect(Collectors.joining(File.pathSeparator));
		List<String> command = new ArrayList<>();
		command.add(javaExecutable.toString());
		if (modsFolder != null) {
			command.add("-D" + InstanceDirs.MODS_FOLDER_PROPERTY + "=" + modsFolder);
		}
		if (holdFileOps) {
			command.add("-D" + HOLD_FILE_OPS_PROPERTY + "=true");
		}
		command.addAll(List.of("-cp", cp, ApplyHelper.class.getName(), Long.toString(gamePid), pendingJson.toString()));
		return List.copyOf(command);
	}

	public static Process launch(Path configDir, Path pendingJson) throws IOException {
		return launch(configDir, pendingJson, ModFilesPolicy.RIGTUNE);
	}

	// policy: who changes this instance's mod files (docs/v0.5/SPEC.md 4a); decides the hold (holds).
	public static Process launch(Path configDir, Path pendingJson, ModFilesPolicy policy) throws IOException {
		Path modsFolder = System.getProperty(InstanceDirs.MODS_FOLDER_PROPERTY) == null ? null
				: InstanceDirs.modsDir(configDir.toAbsolutePath().getParent());
		return launch(configDir, pendingJson, List.of(codeSourceOf(ApplyHelper.class), codeSourceOf(Gson.class)), ProcessHandle.current().pid(),
				modsFolder, holds(policy));
	}

	static Process launch(Path configDir, Path pendingJson, List<Path> sources, long gamePid) throws IOException {
		return launch(configDir, pendingJson, sources, gamePid, null, false);
	}

	// The helper runs from copies in config/rigtune/helper/, never from mods/: a JVM keeps its classpath jars open,
	// and on Windows an open jar can't be renamed, so running from mods/ would block RigTune's own update.
	static Process launch(Path configDir, Path pendingJson, List<Path> sources, long gamePid, Path modsFolder, boolean holdFileOps) throws IOException {
		List<Path> classpath = helperClasspath(helperDir(configDir), sources);
		List<String> command = buildCommand(currentJava(), classpath, gamePid, pendingJson, modsFolder, holdFileOps);
		Path log = helperLog(configDir);
		Files.createDirectories(log.getParent());
		return new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
				.start();
	}

	public static Path helperLog(Path configDir) {
		return configDir.resolve("rigtune").resolve("helper.log");
	}

	public static Path helperDir(Path configDir) {
		return configDir.resolve("rigtune").resolve("helper");
	}

	// True when RigTune runs from a jar, which launch() copies, so the helper can rename RigTune's own jar.
	public static boolean selfUpdateSupported() {
		try {
			return Files.isRegularFile(codeSourceOf(ApplyHelper.class));
		} catch (RuntimeException e) {
			return false;
		}
	}

	// Copies each jar into helperDir (reusing an identical copy) and keeps directories as they are: class
	// directories only occur in development, where nothing runs from mods/. Other files in helperDir are removed, except
	// a record of unfinished groups (UnfinishedGroups) a 0.4.0 dev build left here, which the helper about to start still
	// reads and moves to config/rigtune/ (review-8 CR-1).
	static List<Path> helperClasspath(Path helperDir, List<Path> sources) throws IOException {
		Files.createDirectories(helperDir);
		List<Path> distinct = sources.stream().distinct().toList();
		List<Path> out = new ArrayList<>();
		for (int i = 0; i < distinct.size(); i++) {
			Path source = distinct.get(i);
			if (!Files.isRegularFile(source)) {
				out.add(source);
				continue;
			}
			Path target = helperDir.resolve(i + "-" + source.getFileName());
			if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
				target = copy(source, target, helperDir, i);
			}
			out.add(target);
		}
		try (Stream<Path> files = Files.list(helperDir)) {
			for (Path file : files.toList()) {
				if (!out.contains(file) && !file.getFileName().toString().equals(UnfinishedGroups.FILE_NAME)) {
					try {
						Files.deleteIfExists(file);
					} catch (IOException ignored) {
					}
				}
			}
		}
		return List.copyOf(out);
	}

	private static Path copy(Path source, Path target, Path helperDir, int index) throws IOException {
		Path tmp = Files.createTempFile(helperDir, "copy-", ".tmp");
		try {
			Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				return target;
			} catch (IOException e) {
				// An older helper may still be running from `target`.
				Path fresh = helperDir.resolve(index + "-" + System.nanoTime() + "-" + source.getFileName());
				Files.move(tmp, fresh);
				return fresh;
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	public static Path currentJava() {
		return ProcessHandle.current().info().command()
				.map(Path::of)
				.orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
						File.separatorChar == '\\' ? "java.exe" : "java"));
	}

	public static Path codeSourceOf(Class<?> type) {
		CodeSource source = type.getProtectionDomain().getCodeSource();
		if (source == null || source.getLocation() == null) {
			throw new IllegalStateException("No code source for " + type.getName());
		}
		try {
			String url = source.getLocation().toURI().toString();
			if (url.startsWith("jar:")) {
				int bang = url.indexOf("!/");
				url = url.substring(4, bang < 0 ? url.length() : bang);
			}
			return Path.of(new URI(url));
		} catch (URISyntaxException e) {
			throw new IllegalStateException("Bad code source for " + type.getName(), e);
		}
	}
}
