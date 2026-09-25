package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreMarkerTest {
	@TempDir
	Path configDir;

	private static final class FakeTarget implements RestoreMarker.Target {
		boolean loaded = true;
		boolean ready = true;
		boolean fail;
		final List<Boolean> sets = new ArrayList<>();

		@Override
		public boolean loaded() {
			return loaded;
		}

		@Override
		public boolean ready() {
			return ready;
		}

		@Override
		public void set(boolean value) throws Exception {
			if (fail) {
				throw new IllegalStateException("refused");
			}
			sets.add(value);
		}
	}

	private final FakeTarget dh = new FakeTarget();
	private final FakeTarget iris = new FakeTarget();

	private Path file() {
		return RestoreMarker.defaultPath(configDir);
	}

	@Test
	void defaultPathIsInTheRigTuneFolder() {
		assertEquals(configDir.resolve("rigtune").resolve("benchmark-restore.json"), file());
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		RestoreMarker marker = new RestoreMarker(true, false, "2026-09-25T10:00:00Z");
		marker.save(file());
		assertEquals(Optional.of(marker), RestoreMarker.load(file()));
	}

	@Test
	void missingFileIsEmpty() {
		assertEquals(Optional.empty(), RestoreMarker.load(file()));
		assertTrue(RestoreMarker.restorePending(file(), dh, iris));
	}

	@Test
	void corruptFileIsDeletedAndEmpty() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{not json");
		assertEquals(Optional.empty(), RestoreMarker.load(file()));
		assertFalse(Files.exists(file()));
	}

	@Test
	void restoresBothWhenReadyAndDeletesMarker() throws IOException {
		new RestoreMarker(true, true, "t").save(file());
		assertTrue(RestoreMarker.restorePending(file(), dh, iris));
		assertEquals(List.of(true), dh.sets);
		assertEquals(List.of(true), iris.sets);
		assertFalse(Files.exists(file()));
	}

	@Test
	void waitsWhileDhNotReady() throws IOException {
		new RestoreMarker(true, true, "t").save(file());
		dh.ready = false;
		assertFalse(RestoreMarker.restorePending(file(), dh, iris));
		assertEquals(List.of(true), iris.sets);
		assertEquals(Optional.of(new RestoreMarker(true, null, "t")), RestoreMarker.load(file()));
		dh.ready = true;
		assertTrue(RestoreMarker.restorePending(file(), dh, iris));
		assertEquals(List.of(true), dh.sets);
		assertEquals(List.of(true), iris.sets, "Iris is not set twice");
		assertFalse(Files.exists(file()));
	}

	@Test
	void unloadedModDropsItsField() throws IOException {
		new RestoreMarker(true, true, "t").save(file());
		iris.loaded = false;
		assertTrue(RestoreMarker.restorePending(file(), dh, iris));
		assertTrue(iris.sets.isEmpty());
		assertFalse(Files.exists(file()));
	}

	@Test
	void setFailureKeepsMarker() throws IOException {
		new RestoreMarker(true, null, "t").save(file());
		dh.fail = true;
		assertFalse(RestoreMarker.restorePending(file(), dh, iris));
		assertEquals(Optional.of(new RestoreMarker(true, null, "t")), RestoreMarker.load(file()));
	}

	@Test
	void nullFieldsAreIgnored() throws IOException {
		new RestoreMarker(null, false, "t").save(file());
		assertTrue(RestoreMarker.restorePending(file(), dh, iris));
		assertTrue(dh.sets.isEmpty());
		assertEquals(List.of(false), iris.sets);
	}
}
