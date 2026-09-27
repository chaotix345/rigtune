package io.github.chaotix345.rigtune.client.footprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 9 (C18): StartupTimes' view carries the trend's assessment (computed off the render thread with the
// summary; startup-times.json unchanged), and a Got it is kept in awareness.json's acknowledgedStartupRegressions (AC9.3):
// the next launch's StartupTimes reads it back; a 0.4.0 awareness.json without the array acknowledges nothing and keeps
// its fields when the array is added (AC9.4).
class StartupTimesAckTest {
	@TempDir
	Path config;

	// Six launches of 10 s, then one of 15 s with more mods: SLOWER, the mod count changed.
	private String seedSlower() {
		StartupTimesStore store = new StartupTimesStore(config);
		for (int i = 0; i < 6; i++) {
			store.record(new Run("2026-09-2" + i + "T10:00:00Z", 10_000, "26.2", "0.5.0", 80, "h"));
		}
		store.record(new Run("2026-09-27T10:00:00Z", 15_000, "26.2", "0.5.0", 92, "h2"));
		return "startup.regression.2026-09-27T10:00:00Z";
	}

	@Test
	void theViewCarriesTheAssessmentOnceComputed() throws IOException {
		String key = seedSlower();
		String before = Files.readString(StartupTimesStore.file(config), StandardCharsets.UTF_8);
		StartupTimes times = new StartupTimes(null, config);
		assertNull(times.computed(), "nothing read before a view was computed");
		StartupTimes.View view = times.view();
		assertSame(view, times.computed());
		assertEquals(7, view.runs());
		assertEquals(15_000L, view.lastMs());
		assertEquals(StartupTrend.Kind.SLOWER, view.assessment().kind());
		assertEquals(StartupTrend.Cause.MOD_COUNT, view.assessment().cause());
		assertEquals(key, StartupTrend.key(view.assessment()));
		assertEquals(before, Files.readString(StartupTimesStore.file(config), StandardCharsets.UTF_8), "startup-times.json is only read");
		assertNull(new StartupTimes.View(15_125L, 14_517L, 12, true).assessment(), "0.4's constructor: no assessment");
	}

	@Test
	void anAcknowledgementIsKeptForTheNextLaunch() {
		String key = seedSlower();
		StartupTimes times = new StartupTimes(null, config);
		times.view();
		assertFalse(times.acknowledged(key));
		times.acknowledge(key);
		assertTrue(times.acknowledged(key));
		assertTrue(AwarenessStore.shared(config).acknowledgedStartupRegressions().contains(key));

		StartupTimes next = new StartupTimes(null, config);
		assertFalse(next.acknowledged(key), "read with the view, off the render thread");
		next.view();
		assertTrue(next.acknowledged(key));
		assertFalse(next.acknowledged("startup.regression.2026-09-28T10:00:00Z"), "a later launch has its own key");
	}

	@Test
	void aFileFrom040HasNoAcknowledgementsAndKeepsItsFieldsWhenOneIsAdded() throws IOException {
		String key = seedSlower();
		Path file = AwarenessStore.file(config);
		Files.createDirectories(file.getParent());
		Files.writeString(file, """
				{
				  "formatVersion": 1,
				  "fingerprint": {"gpuVendor": "NVIDIA", "gpuRenderer": "RTX 4070", "gpuDriverRaw": "581.57", "backend": "OpenGL", "cpuName": "Ryzen 7", "totalRamMb": 32768},
				  "lastSeenRulesRevision": 16,
				  "dismissed": ["battery-offer"],
				  "acknowledgedRegressions": ["run-1"]
				}
				""", StandardCharsets.UTF_8);
		StartupTimes times = new StartupTimes(null, config);
		times.view();
		assertFalse(times.acknowledged(key));
		times.acknowledge(key);
		JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("[\"" + key + "\"]", root.get("acknowledgedStartupRegressions").toString());
		assertEquals("[\"battery-offer\"]", root.get("dismissed").toString());
		assertEquals("[\"run-1\"]", root.get("acknowledgedRegressions").toString());
		assertEquals(16, root.get("lastSeenRulesRevision").getAsInt());
		assertEquals("581.57", root.getAsJsonObject("fingerprint").get("gpuDriverRaw").getAsString());
	}
}
