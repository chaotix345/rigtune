package io.github.chaotix345.rigtune.core.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// docs/v0.5/SPEC.md 4a, AC4a.2: the bounded listing of <mods>/.index/ (at least one regular *.pw.toml; stop at the first
// match or after 256 entries; regular files only; no symlink followed; never an exception). That it runs on the executor
// it is given is LauncherProbeTest's (the real path, LauncherProbe.startListing).
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

	// An .index that is a file is no evidence; an .index folder that can't be listed fails closed (review-11 APPLY-7, a
	// WS-L2 edit): it counts as packwiz metadata, so the policy never becomes RIGTUNE on an unreadable listing.
	@Test
	void anIndexThatIsAFileIsNoEvidenceAndAnUnreadableOneFailsClosed() throws IOException {
		Files.createDirectories(dir.resolve("mods"));
		Files.writeString(dir.resolve("mods").resolve(".index"), "not a folder");
		assertFalse(scan().found());

		Path other = Files.createDirectories(dir.resolve("other").resolve(".index"));
		Files.writeString(other.resolve("a.pw.toml"), "");
		assumeTrue(Files.getFileAttributeView(other, PosixFileAttributeView.class) != null, "no POSIX permissions here");
		Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("---------"));
		try {
			assumeTrue(!Files.isReadable(other), "running as a user who reads everything");
			assertTrue(InstanceEvidence.scan(dir.resolve("other")).found());
		} finally {
			Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));
		}
	}
}
