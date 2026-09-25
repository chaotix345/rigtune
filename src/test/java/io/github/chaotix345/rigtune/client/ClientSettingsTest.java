package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.model.Goal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSettingsTest {
	private static void assertDefaults(ClientSettings settings) {
		assertTrue(settings.networkEnabled);
		assertTrue(settings.remoteRules);
		assertTrue(settings.modrinth);
		assertTrue(settings.startupToast);
		assertEquals("CURRENT", settings.benchmarkScene);
		assertFalse(settings.privacyNoticeShown);
	}

	private static void write(Path file, String json) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, json);
	}

	@Test
	void missingFileGivesDefaults(@TempDir Path configDir) {
		assertDefaults(ClientSettings.load(configDir));
	}

	@Test
	void partialFileKeepsTheOtherDefaults(@TempDir Path configDir) throws IOException {
		write(ClientSettings.file(configDir), "{\"networkEnabled\": false}");

		ClientSettings settings = ClientSettings.load(configDir);
		assertFalse(settings.networkEnabled);
		assertTrue(settings.remoteRules);
		assertTrue(settings.modrinth);
		assertTrue(settings.startupToast);
		assertEquals("CURRENT", settings.benchmarkScene);
		assertFalse(settings.privacyNoticeShown);
	}

	@Test
	void corruptFileGivesDefaults(@TempDir Path configDir) throws IOException {
		write(ClientSettings.file(configDir), "{not json");

		assertDefaults(ClientSettings.load(configDir));
	}

	@Test
	void roundTrip(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);
		settings.networkEnabled = false;
		settings.modrinth = false;
		settings.startupToast = false;
		settings.benchmarkScene = "BENCHMARK_WORLD";
		settings.privacyNoticeShown = true;
		settings.save(configDir);

		ClientSettings reloaded = ClientSettings.load(configDir);
		assertFalse(reloaded.networkEnabled);
		assertTrue(reloaded.remoteRules);
		assertFalse(reloaded.modrinth);
		assertFalse(reloaded.startupToast);
		assertEquals(BenchmarkRequest.Scene.BENCHMARK_WORLD, reloaded.benchmarkSceneOrDefault());
		assertTrue(reloaded.privacyNoticeShown);
	}

	@Test
	void unknownSceneFallsBackToCurrent(@TempDir Path configDir) throws IOException {
		write(ClientSettings.file(configDir), "{\"benchmarkScene\": \"MOON_BASE\"}");
		assertEquals(BenchmarkRequest.Scene.CURRENT, ClientSettings.load(configDir).benchmarkSceneOrDefault());

		write(ClientSettings.file(configDir), "{\"benchmarkScene\": null}");
		assertEquals(BenchmarkRequest.Scene.CURRENT, ClientSettings.load(configDir).benchmarkSceneOrDefault());
	}

	@Test
	void theMasterSwitchOverridesTheFineOnes() {
		ClientSettings settings = new ClientSettings();
		assertTrue(settings.remoteRulesAllowed());
		assertTrue(settings.modrinthAllowed());

		settings.networkEnabled = false;
		assertFalse(settings.remoteRulesAllowed());
		assertFalse(settings.modrinthAllowed());

		settings.networkEnabled = true;
		settings.remoteRules = false;
		assertFalse(settings.remoteRulesAllowed());
		assertTrue(settings.modrinthAllowed());

		settings.remoteRules = true;
		settings.modrinth = false;
		assertTrue(settings.remoteRulesAllowed());
		assertFalse(settings.modrinthAllowed());
	}

	// 0.1.0 wrote only config/rigtune/rigtune.json (goal, lastShownApply). 0.2 still reads it, and the switches live
	// elsewhere, so they come up with their defaults.
	@Test
	void aV010RigtuneJsonStillLoadsAndLeavesTheSettingsAtTheirDefaults(@TempDir Path configDir) throws IOException {
		write(ClientState.file(configDir), "{\n  \"goal\": \"PERFORMANCE\",\n  \"lastShownApply\": \"2026-09-24T10:00:00Z\"\n}");

		ClientState state = ClientState.load(configDir);
		assertEquals(Goal.PERFORMANCE, state.goalOrDefault());
		assertEquals("2026-09-24T10:00:00Z", state.lastShownApply);
		assertDefaults(ClientSettings.load(configDir));
		assertFalse(Files.exists(ClientSettings.file(configDir)));
	}
}
