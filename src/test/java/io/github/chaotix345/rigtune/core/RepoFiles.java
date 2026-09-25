package io.github.chaotix345.rigtune.core;

import java.nio.file.Files;
import java.nio.file.Path;

// The repository checkout, found by walking up from the test's working directory (versions/<mc>/build/junit-run).
public final class RepoFiles {
	private RepoFiles() {
	}

	public static Path root() {
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			if (Files.isRegularFile(dir.resolve("rules/source/knowledge.json")) && Files.isRegularFile(dir.resolve("settings.gradle"))) {
				return dir;
			}
		}
		throw new IllegalStateException("No repository root above " + Path.of("").toAbsolutePath());
	}

	public static Path resolve(String relative) {
		return root().resolve(relative);
	}
}
