package io.github.chaotix345.rigtune.core.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// docs/v0.5/SPEC.md 4a, AC4a.2: the bounded listing of <mods>/.index/ (at least one regular *.pw.toml; stop at the first
// match or after 256 entries; regular files only; no symlink followed; never an exception).
class InstanceEvidenceTest {
	@TempDir
	Path dir;

	private Path index() throws IOException {
		return Files.createDirectories(dir.resolve("mods").resolve(".index"));
	}

	private InstanceEvidence.Scan scan() {
		return InstanceEvidence.scan(dir.resolve("mods"));
	}

	@Test
	void aRegularPackwizFileIsTheEvidence() throws IOException {
		Files.writeString(index().resolve("sodium.pw.toml"), "name = \"Sodium\"\n");
		assertTrue(InstanceEvidence.list(dir.resolve("mods")).packwizIndex());
		assertEquals(new InstanceEvidence(true), InstanceEvidence.list(dir.resolve("mods")));
	}

	@Test
	void noModsFolderOrNoIndex() throws IOException {
		assertFalse(InstanceEvidence.list(dir.resolve("mods")).packwizIndex());
		Files.createDirectories(dir.resolve("mods"));
		assertFalse(InstanceEvidence.list(dir.resolve("mods")).packwizIndex());
		assertFalse(InstanceEvidence.list(null).packwizIndex());
	}

	@Test
	void anEmptyIndex() throws IOException {
		index();
		assertEquals(new InstanceEvidence.Scan(false, 0), scan());
	}

	@Test
	void otherFilesAndADirectoryWithThatNameAreNot() throws IOException {
		Path index = index();
		Files.createDirectories(index.resolve("x.pw.toml"));
		Files.writeString(index.resolve("sodium.toml"), "");
		Files.writeString(index.resolve("readme.txt"), "");
		assertFalse(scan().found());
	}

	@Test
	void aSymlinkIsNotFollowed() throws IOException {
		Path index = index();
		Path target = Files.writeString(dir.resolve("real.pw.toml"), "");
		try {
			Files.createSymbolicLink(index.resolve("linked.pw.toml"), target);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "symbolic links aren't available here: " + e);
		}
		assertFalse(scan().found());
	}

	@Test
	void aSymlinkedIndexFolderIsNotFollowed() throws IOException {
		Path real = Files.createDirectories(dir.resolve("elsewhere"));
		Files.writeString(real.resolve("sodium.pw.toml"), "");
		Files.createDirectories(dir.resolve("mods"));
		try {
			Files.createSymbolicLink(dir.resolve("mods").resolve(".index"), real);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "symbolic links aren't available here: " + e);
		}
		assertFalse(scan().found());
	}

	@Test
	void theListingStopsAtItsBound() throws IOException {
		Path index = index();
		for (int i = 0; i < 10_000; i++) {
			Files.createFile(index.resolve("f" + i + ".txt"));
		}
		InstanceEvidence.Scan scan = scan();
		assertFalse(scan.found());
		assertEquals(InstanceEvidence.MAX_ENTRIES, scan.examined());
	}

	@Test
	void theListingStopsAtTheFirstMatch() throws IOException {
		Files.writeString(index().resolve("a.pw.toml"), "");
		InstanceEvidence.Scan scan = scan();
		assertTrue(scan.found());
		assertEquals(1, scan.examined());
	}

	@Test
	void anIndexThatIsAFileOrUnreadableIsNoEvidenceAndNoException() throws IOException {
		Files.createDirectories(dir.resolve("mods"));
		Files.writeString(dir.resolve("mods").resolve(".index"), "not a folder");
		assertFalse(scan().found());

		Path other = Files.createDirectories(dir.resolve("other").resolve(".index"));
		Files.writeString(other.resolve("a.pw.toml"), "");
		assumeTrue(Files.getFileAttributeView(other, PosixFileAttributeView.class) != null, "no POSIX permissions here");
		Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("---------"));
		try {
			assumeTrue(!Files.isReadable(other), "running as a user who reads everything");
			assertFalse(InstanceEvidence.scan(dir.resolve("other")).found());
		} finally {
			Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));
		}
	}

	// The listing runs on the executor it's given, never on the caller's thread.
	@Test
	void theListingRunsOnTheGivenExecutor() throws Exception {
		Files.writeString(index().resolve("sodium.pw.toml"), "");
		Queue<Runnable> queued = new ArrayDeque<>();
		Executor executor = queued::add;
		CompletableFuture<InstanceEvidence> listing = InstanceEvidence.listAsync(dir.resolve("mods"), executor);
		assertFalse(listing.isDone());
		assertEquals(1, queued.size());
		String[] ranOn = new String[1];
		Thread worker = new Thread(() -> {
			ranOn[0] = Thread.currentThread().getName();
			queued.poll().run();
		}, "evidence-worker");
		worker.start();
		worker.join();
		assertEquals("evidence-worker", ranOn[0]);
		assertNotEquals(Thread.currentThread().getName(), ranOn[0]);
		assertTrue(listing.get().packwizIndex());
	}

	@Test
	void anExecutorThatRefusesIsNoEvidence() throws Exception {
		CompletableFuture<InstanceEvidence> listing = InstanceEvidence.listAsync(dir.resolve("mods"), r -> {
			throw new RejectedExecutionException("full");
		});
		assertEquals(InstanceEvidence.NONE, listing.get());
	}
}
