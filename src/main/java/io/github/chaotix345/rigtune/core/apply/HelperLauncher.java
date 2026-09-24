package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.stream.Collectors;

public final class HelperLauncher {
	private HelperLauncher() {
	}

	public static List<String> buildCommand(Path javaExecutable, List<Path> classpath, long gamePid, Path pendingJson) {
		String cp = classpath.stream().map(Path::toString).distinct().collect(Collectors.joining(File.pathSeparator));
		return List.of(javaExecutable.toString(), "-cp", cp, ApplyHelper.class.getName(), Long.toString(gamePid), pendingJson.toString());
	}

	public static Process launch(Path configDir, Path pendingJson) throws IOException {
		List<Path> classpath = List.of(codeSourceOf(ApplyHelper.class), codeSourceOf(Gson.class));
		List<String> command = buildCommand(currentJava(), classpath, ProcessHandle.current().pid(), pendingJson);
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
