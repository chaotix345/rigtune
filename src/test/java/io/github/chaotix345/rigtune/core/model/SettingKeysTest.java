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
	void unrelatedNamespacesAndNullStayRejected() {
		assertFalse(SettingKeys.changeable("distanthorizons.foo"));
		assertFalse(SettingKeys.changeable(null));
	}
}
