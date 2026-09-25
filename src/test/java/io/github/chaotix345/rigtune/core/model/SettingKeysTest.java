package io.github.chaotix345.rigtune.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingKeysTest {
	@Test
	void vanillaAndSodiumBehaviourIsUnchanged() {
		assertTrue(SettingKeys.changeable("vanilla.renderDistance"));
		assertFalse(SettingKeys.changeable("vanilla.notAKey"));
		assertTrue(SettingKeys.changeable("sodium.performance.chunk_builder_threads"));
		assertFalse(SettingKeys.changeable("sodium."));
	}

	@Test
	void dhAndIrisAcceptDottedIdentifierKeys() {
		assertTrue(SettingKeys.changeable("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius"));
		assertTrue(SettingKeys.changeable("dh.common.multiThreading.numberOfThreads"));
		assertTrue(SettingKeys.changeable("iris.maxShadowRenderDistance"));
		assertFalse(SettingKeys.changeable("dh."));
		assertFalse(SettingKeys.changeable("iris."));
	}

	@Test
	void dhAndIrisRejectPathTraversalSlashesAndControlCharacters() {
		assertFalse(SettingKeys.changeable("dh.../../etc/passwd"));
		assertFalse(SettingKeys.changeable("dh.client..quality"));
		assertFalse(SettingKeys.changeable("dh..client"));
		assertFalse(SettingKeys.changeable("dh.client.advanced."));
		assertFalse(SettingKeys.changeable("dh.client/advanced"));
		assertFalse(SettingKeys.changeable("dh.client\\advanced"));
		assertFalse(SettingKeys.changeable("dh.client advanced"));
		assertFalse(SettingKeys.changeable("dh.client.advanced\u0000"));
		assertFalse(SettingKeys.changeable("iris.max#Shadow"));
		assertFalse(SettingKeys.changeable("iris.\"maxShadow\""));
	}

	@Test
	void sodiumAcceptsItsRealOptionKeys() {
		for (String key : new String[] {"sodium.performance.chunk_builder_threads", "sodium.performance.chunk_build_defer_mode",
				"sodium.performance.use_no_error_g_l_context", "sodium.quality.weather_quality", "sodium.quality.hidden_fluid_culling",
				"sodium.advanced.cpu_render_ahead_limit", "sodium.notifications.has_shown_donation_prompt"}) {
			assertTrue(SettingKeys.changeable(key), key);
		}
	}

	// Review 3, security-2: the sodium. suffix gets the same safe-key rules as dh./iris.
	@Test
	void sodiumRejectsPathTraversalSlashesEmptySegmentsAndControlCharacters() {
		for (String key : new String[] {"sodium.../../etc/passwd", "sodium.performance..threads", "sodium..performance",
				"sodium.performance.", "sodium.performance/threads", "sodium.performance\\threads", "sodium.performance threads",
				"sodium.performance.threads\u0000", "sodium.performance.\nthreads", "sodium.\"performance\"", "sodium.a:b", "sodium.."}) {
			assertFalse(SettingKeys.changeable(key), key);
		}
	}

	@Test
	void unrelatedNamespacesAndNullStayRejected() {
		assertFalse(SettingKeys.changeable("distanthorizons.foo"));
		assertFalse(SettingKeys.changeable(null));
	}
}
