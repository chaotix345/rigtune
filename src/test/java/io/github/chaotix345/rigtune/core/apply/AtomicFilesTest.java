package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Review 2, N3: a replace that is briefly denied (AV scanner, indexer) is retried.
class AtomicFilesTest {
	@TempDir
	Path dir;

	private AtomicFiles.Mover deniedFor(int times, AtomicInteger calls) {
		return (from, to) -> {
			if (calls.incrementAndGet() <= times) {
				throw new AccessDeniedException(to.toString());
			}
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		};
	}

	private List<String> listing() throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void retriesABrieflyDeniedReplace() throws IOException {
		Path target = Files.writeString(dir.resolve("pending.json"), "old");
		AtomicInteger calls = new AtomicInteger();

		AtomicFiles.writeString(target, "new", deniedFor(2, calls), 10, 1);

		assertEquals(3, calls.get());
		assertEquals("new", Files.readString(target));
		assertEquals(List.of("pending.json"), listing());
	}

	@Test
	void givesUpAfterTheLastAttemptAndCleansUp() throws IOException {
		Path target = Files.writeString(dir.resolve("pending.json"), "old");
		AtomicInteger calls = new AtomicInteger();

		assertThrows(AccessDeniedException.class, () -> AtomicFiles.writeString(target, "new", deniedFor(99, calls), 10, 1));

		assertEquals(10, calls.get());
		assertEquals("old", Files.readString(target));
		assertEquals(List.of("pending.json"), listing());
	}

	@Test
	void otherErrorsAreNotRetried() {
		AtomicInteger calls = new AtomicInteger();

		assertThrows(IOException.class, () -> AtomicFiles.writeString(dir.resolve("x.json"), "x", (from, to) -> {
			calls.incrementAndGet();
			throw new IOException("disk full");
		}, 10, 1));

		assertEquals(1, calls.get());
	}
}
