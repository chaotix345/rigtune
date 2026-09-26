package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC8.3: the accessor-backed server limit caps tuneMaxRenderDistance, and no reflection on Options
// remains in BenchmarkController.
class BenchmarkControllerServerLimitTest {
	private static final BenchmarkRequest TUNE_WORLD = new BenchmarkRequest(BenchmarkRequest.Mode.TUNE, BenchmarkRequest.Scene.BENCHMARK_WORLD, null);
	private static final BenchmarkRequest TUNE_HERE = new BenchmarkRequest(BenchmarkRequest.Mode.TUNE, BenchmarkRequest.Scene.CURRENT, null);
	private static final BenchmarkRequest MEASURE = new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, BenchmarkRequest.Scene.CURRENT, null);

	@Test
	void theServerRadiusCapsTheChunkLimitAndTheTunesSteps() {
		assertEquals(6, BenchmarkController.maxRenderDistance(32, false, 6));
		assertEquals(32, BenchmarkController.maxRenderDistance(32, true, 6), "the player's own world: its options' limit only");
		assertEquals(32, BenchmarkController.maxRenderDistance(32, false, 0), "nothing sent yet");
		assertEquals(12, BenchmarkController.maxRenderDistance(12, false, 16));
		int limit = BenchmarkController.maxRenderDistance(32, false, 6);
		int maxRd = BenchmarkController.tuneMaxRenderDistance(BenchmarkRequest.Scene.BENCHMARK_WORLD, 5, BenchmarkController.MAX_RD, limit);
		assertTrue(maxRd <= 6, "Tune never steps above the server's view distance: " + maxRd);
		assertEquals(10, BenchmarkController.tuneMaxRenderDistance(BenchmarkRequest.Scene.CURRENT, 2, BenchmarkController.MAX_RD,
				BenchmarkController.maxRenderDistance(32, false, 10)));
	}

	@Test
	void theResultNamesTheServerOnlyWhenItCappedTheSteps() {
		int capped = BenchmarkController.tuneMaxRenderDistance(BenchmarkRequest.Scene.BENCHMARK_WORLD, 5, BenchmarkController.MAX_RD, 6);
		assertEquals(6, BenchmarkController.serverLimit(TUNE_WORLD, 5, BenchmarkController.MAX_RD, 32, capped, 6));
		int uncapped = BenchmarkController.tuneMaxRenderDistance(BenchmarkRequest.Scene.CURRENT, 4, BenchmarkController.MAX_RD, 32);
		assertEquals(0, BenchmarkController.serverLimit(TUNE_HERE, 4, BenchmarkController.MAX_RD, 32, uncapped, 32),
				"a server limit above the steps didn't cap anything");
		assertEquals(0, BenchmarkController.serverLimit(MEASURE, 5, BenchmarkController.MAX_RD, 32, 5, 6), "Measure has no steps");
		assertEquals(0, BenchmarkController.serverLimit(TUNE_WORLD, 5, BenchmarkController.MAX_RD, 32, 32, 0));
	}

	@Test
	void noReflectionOnOptionsRemains() throws IOException {
		String bytes;
		try (InputStream in = BenchmarkController.class.getResourceAsStream("BenchmarkController.class")) {
			assertNotNull(in);
			bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
		assertFalse(bytes.contains("serverRenderDistance"), "no field name lookup");
		assertFalse(bytes.contains("java/lang/reflect/Field"), "no Field reflection");
		assertFalse(bytes.contains("getDeclaredField"), "no getDeclaredField");
		assertTrue(bytes.contains("ClientPacketListenerAccessor"), "reads the accessor");
	}
}
