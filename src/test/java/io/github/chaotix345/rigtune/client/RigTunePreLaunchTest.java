package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.history.V010Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md 3e, AC3.5, review B-M1: failed ops in last-apply.json are logged once per helper run (finishedAt).
class RigTunePreLaunchTest {
	@TempDir
	Path game;

	private Path mods() {
		return game.resolve("mods");
	}

	private Path config() {
		return game.resolve("config");
	}

	private void install(String resource) throws IOException {
		V010Fixtures.install(resource, ApplyResult.defaultPath(config()), mods(), config());
	}

	private List<String> warnOnce() {
		List<String> lines = new ArrayList<>();
		RigTunePreLaunch.warnOnce(config(), mods(), ClientState.load(config()), lines::add);
		return lines;
	}

	@Test
	void theRealV010FailureIsLoggedOncePerRun() throws IOException {
		install("real-instance/last-apply.json");

		List<String> first = warnOnce();

		assertEquals(2, first.size(), first.toString());
		assertTrue(first.get(0).contains("DISABLE_FILE fabric-26.2.jar") && first.get(0).contains("attempt 1 of 3")
				&& first.get(0).contains("2026-09-24T23:09:01.530708800Z"), first.get(0));
		assertTrue(first.get(1).contains("ENABLE_FILE distanthorizons") && first.get(1).contains("attempt 1 of 3"), first.get(1));
		assertEquals("2026-09-24T23:09:01.530708800Z", ClientState.load(config()).lastWarnedApply);
		assertTrue(warnOnce().isEmpty(), "the same run isn't logged again");

		install("last-apply.json");
		List<String> next = warnOnce();
		assertEquals(1, next.size(), next.toString());
		assertTrue(next.getFirst().contains("ferritecore"), next.getFirst());
	}

	@Test
	void nothingIsLoggedOrWrittenWithoutFailures() throws IOException {
		assertTrue(warnOnce().isEmpty());
		install("captured/last-apply.json");
		assertTrue(warnOnce().isEmpty());
		assertNull(ClientState.load(config()).lastWarnedApply);
		assertTrue(!Files.exists(ClientState.file(config())));
	}

	@Test
	void anUnreadableResultIsNotAProblem() throws IOException {
		Files.createDirectories(ApplyResult.defaultPath(config()).getParent());
		Files.writeString(ApplyResult.defaultPath(config()), "{not json");
		assertTrue(warnOnce().isEmpty());
	}
}
