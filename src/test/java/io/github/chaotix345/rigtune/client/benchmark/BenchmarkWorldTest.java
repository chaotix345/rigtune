package io.github.chaotix345.rigtune.client.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkWorldTest {
	@Test
	void recreatedWithoutAMarker() {
		assertTrue(BenchmarkWorld.needsRecreate(null, "26.2"));
	}

	@Test
	void recreatedForAnotherMinecraftVersion() {
		assertTrue(BenchmarkWorld.needsRecreate("26.2", "26.3"));
	}

	@Test
	void reusedForTheSameVersion() {
		assertFalse(BenchmarkWorld.needsRecreate("26.3", "26.3"));
	}
}
