package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md AC8.4, thresholds from docs/research/v0.3/benchmark.md §2.6.
class SceneVarietyTest {
	private static final int SEA = 63;

	private static List<SceneVariety.Sample> grid(BiFunction<Integer, Integer, Integer> floor, BiFunction<Integer, Integer, String> biome) {
		List<SceneVariety.Sample> out = new ArrayList<>();
		for (int[] o : SceneVariety.offsets()) {
			out.add(new SceneVariety.Sample(o[0], o[1], floor.apply(o[0], o[1]), biome.apply(o[0], o[1])));
		}
		return out;
	}

	// Hills from 70 to about 130, well above the sea.
	private static int hills(int dx, int dz) {
		return 100 + (int) Math.round(30 * Math.sin(dx / 40.0) * Math.cos(dz / 50.0));
	}

	// Three biomes in bands.
	private static String bands(int dx, int dz) {
		return dx < -64 ? "minecraft:plains" : dx < 64 ? "minecraft:forest" : "minecraft:birch_forest";
	}

	static List<SceneVariety.Sample> recorded() throws IOException {
		List<SceneVariety.Sample> out = new ArrayList<>();
		try (InputStream in = SceneVarietyTest.class.getResourceAsStream("/benchmark/scene-grid.csv")) {
			assertNotNull(in, "fixture");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (line.isBlank() || line.startsWith("#") || line.startsWith("dx,")) {
					continue;
				}
				String[] f = line.strip().split(",");
				out.add(new SceneVariety.Sample(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3]));
			}
		}
		return out;
	}

	@Test
	void theGridIsEverySixteenBlocksWithin192() {
		List<int[]> offsets = SceneVariety.offsets();
		assertEquals(441, offsets.size());
		assertEquals(197, offsets.stream().filter(o -> o[0] * o[0] + o[1] * o[1] <= 128 * 128).count());
		assertTrue(offsets.stream().allMatch(o -> o[0] % 16 == 0 && o[1] % 16 == 0));
	}

	@Test
	void allOceanIsRejectedAsOcean() {
		SceneVariety.Report report = SceneVariety.check(grid((dx, dz) -> 40 + Math.abs(dx) / 16, (dx, dz) -> dx < 0 ? "minecraft:ocean" : "minecraft:deep_ocean"), SEA);
		assertEquals("ocean", report.rejection());
		assertFalse(report.accepted());
		assertEquals(1.0, report.water());
	}

	@Test
	void justOverHalfUnderTheSeaIsOcean() {
		// dz < 16 is under water: more than half of the samples within 128.
		SceneVariety.Report report = SceneVariety.check(grid((dx, dz) -> dz < 16 ? 50 : hills(dx, dz), SceneVarietyTest::bands), SEA);
		assertEquals("ocean", report.rejection());
	}

	@Test
	void flatLandIsRejectedAsFlat() {
		SceneVariety.Report report = SceneVariety.check(grid((dx, dz) -> 70 + (dx + dz) / 64, SceneVarietyTest::bands), SEA);
		assertEquals("flat", report.rejection());
		assertTrue(report.heightSd() < 8);
	}

	@Test
	void aSmallRangeIsFlatEvenWithSomeSpread() {
		// Two plateaus 20 blocks apart: sd 10, range 20.
		SceneVariety.Report report = SceneVariety.check(grid((dx, dz) -> dx < 0 ? 70 : 90, SceneVarietyTest::bands), SEA);
		assertTrue(report.heightSd() >= 8, "sd " + report.heightSd());
		assertEquals(20, report.heightRange());
		assertEquals("flat", report.rejection());
	}

	@Test
	void oneBiomeIsRejectedAsMonotone() {
		SceneVariety.Report report = SceneVariety.check(grid(SceneVarietyTest::hills, (dx, dz) -> "minecraft:forest"), SEA);
		assertEquals("monotone", report.rejection());
		assertEquals(1, report.biomes());
	}

	@Test
	void aSecondBiomeUnderFivePercentStillIsMonotone() {
		// 20 of 441 samples (4.5%) are plains.
		int[] count = {0};
		SceneVariety.Report report = SceneVariety.check(grid(SceneVarietyTest::hills, (dx, dz) -> count[0]++ < 20 ? "minecraft:plains" : "minecraft:forest"), SEA);
		assertEquals("monotone", report.rejection());
	}

	@Test
	void theRecordedBenchmarkSceneIsAccepted() throws IOException {
		List<SceneVariety.Sample> samples = recorded();
		assertEquals(441, samples.size());
		SceneVariety.Report report = SceneVariety.check(samples, SEA);
		assertNull(report.rejection(), report.fingerprint());
		assertTrue(report.accepted());
		// The research's numbers for the camera (docs/research/v0.3/benchmark.md §2.6).
		assertEquals(0.0, report.water());
		assertEquals(18.5, report.heightSd(), 0.05);
		assertEquals(57, report.heightRange());
		assertEquals(4, report.biomes());
		assertEquals(0.147, report.farWater(), 0.0005);
		assertEquals(117, report.centerFloor());
		assertEquals("minecraft:forest", report.centerBiome());
		assertEquals("floor 117 minecraft:forest; within 128: water 0.0%, height sd 18.5, range 57; within 192: 4 biomes >= 5%, water 14.7%",
				report.fingerprint());
	}

	@Test
	void anEmptyGridIsRejected() {
		assertEquals("empty", SceneVariety.check(List.of(), SEA).rejection());
	}
}
