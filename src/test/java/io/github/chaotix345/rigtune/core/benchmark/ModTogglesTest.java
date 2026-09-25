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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModTogglesTest {
	@TempDir
	Path configDir;

	private static final Knobs BOTH_ON = new Knobs(12, 12, true, true);

	private final List<String> calls = new ArrayList<>();
	private boolean markerSeenOnFirstCall;
	private boolean dhFails;
	private boolean dhRestoreFails;

	private Path marker() {
		return RestoreMarker.defaultPath(configDir);
	}

	private final ModToggles.Mods mods = new ModToggles.Mods() {
		private void call(String what) {
			if (calls.isEmpty()) {
				markerSeenOnFirstCall = Files.exists(marker());
			}
			calls.add(what);
		}

		@Override
		public void setDhRendering(boolean on) {
			if (dhFails) {
				throw new IllegalStateException("DH refused");
			}
			call("dh " + on);
		}

		@Override
		public void restoreDhRendering() {
			if (dhRestoreFails) {
				throw new IllegalStateException("DH restore refused");
			}
			call("dh restore");
		}

		@Override
		public void setShaders(boolean on) {
			call("shaders " + on);
		}
	};

	private ModToggles toggles(Knobs original) {
		return new ModToggles(original, marker(), mods, () -> "2026-09-25T10:00:00Z");
	}

	@Test
	void markerWrittenBeforeTheFirstChange() throws IOException {
		List<String> failures = new ArrayList<>();
		toggles(BOTH_ON).apply(BOTH_ON, BOTH_ON.withDhRendering(false), failures);
		assertTrue(markerSeenOnFirstCall);
		assertEquals(List.of("dh false"), calls);
		assertTrue(failures.isEmpty());
	}

	@Test
	void markerHoldsOnlyFeaturesThatWereOn() throws IOException {
		Knobs dhOnly = new Knobs(12, 12, true, false);
		toggles(dhOnly).apply(dhOnly, dhOnly.withDhRendering(false), new ArrayList<>());
		assertEquals(Optional.of(new RestoreMarker(true, null, "2026-09-25T10:00:00Z")), RestoreMarker.load(marker()));
	}

	@Test
	void backToOriginalRestoresAndDeletesTheMarker() throws IOException {
		ModToggles t = toggles(BOTH_ON);
		List<String> failures = new ArrayList<>();
		t.apply(BOTH_ON, BOTH_ON.withDhRendering(false), failures);
		t.apply(BOTH_ON.withDhRendering(false), BOTH_ON.withShaders(false), failures);
		assertTrue(Files.exists(marker()), "still mid-run");
		t.apply(BOTH_ON.withShaders(false), BOTH_ON, failures);
		assertEquals(List.of("dh false", "dh restore", "shaders false", "shaders true"), calls);
		assertFalse(Files.exists(marker()));
		assertTrue(failures.isEmpty());
	}

	@Test
	void markerKeptWhenARestoreFails() throws IOException {
		ModToggles t = toggles(BOTH_ON);
		List<String> failures = new ArrayList<>();
		t.apply(BOTH_ON, BOTH_ON.withDhRendering(false), failures);
		dhRestoreFails = true;
		t.apply(BOTH_ON.withDhRendering(false), BOTH_ON, failures);
		assertEquals(1, failures.size());
		assertTrue(Files.exists(marker()), "the next start retries");
	}

	@Test
	void oneFailureDoesNotStopTheOtherToggle() throws IOException {
		dhFails = true;
		List<String> failures = new ArrayList<>();
		toggles(BOTH_ON).apply(BOTH_ON, new Knobs(12, 12, false, false), failures);
		assertEquals(List.of("shaders false"), calls);
		assertEquals(1, failures.size());
		assertTrue(failures.getFirst().contains("DH refused"));
	}

	@Test
	void markerWriteFailureTouchesNothing() throws IOException {
		Files.createDirectories(configDir);
		Files.writeString(configDir.resolve("rigtune"), "a file where the folder should be");
		assertThrows(IOException.class, () -> toggles(BOTH_ON).apply(BOTH_ON, BOTH_ON.withDhRendering(false), new ArrayList<>()));
		assertTrue(calls.isEmpty());
	}

	@Test
	void renderAndSimulationDistanceAreNotItsBusiness() throws IOException {
		toggles(BOTH_ON).apply(BOTH_ON, BOTH_ON.withRenderDistance(8).withSimulationDistance(6), new ArrayList<>());
		assertTrue(calls.isEmpty());
		assertFalse(Files.exists(marker()));
	}
}
