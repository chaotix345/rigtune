package io.github.chaotix345.rigtune.core.apply;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

// A helper (ApplyExecutor) for tests outside this package whose renames of some files fail every time, as a jar another
// program keeps open does; no real waiting.
public final class TestExecutors {
	private TestExecutors() {
	}

	public static ApplyExecutor failingMovesOf(Predicate<Path> fails) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			if (fails.test(from)) {
				throw new FileSystemException(from.toString(), null, "The process cannot access the file because it is being used by another process");
			}
			Files.move(from, to);
		}, millis -> true);
	}
}
