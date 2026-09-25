package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Is the benchmark world's scene still worth measuring? (docs/v0.3/SPEC.md 8c, thresholds calibrated in
// docs/research/v0.3/benchmark.md §2.6.) The input is the terrain noise, not the generated chunks, so the answer doesn't
// depend on render distance or on trees: the terrain floor and biome every 16 blocks within 192 blocks of the camera.
// A scene is rejected as "ocean" when more than half of it within 128 blocks is below sea level, "flat" when the floor
// within 128 blocks varies too little (standard deviation below 8 or range below 24 blocks), and "monotone" when fewer
// than two biomes each cover 5% of it within 192 blocks. A new Minecraft version's worldgen change is caught here.
public final class SceneVariety {
	public static final int GRID = 16;
	public static final int NEAR = 128;
	public static final int FAR = 192;
	public static final double MAX_WATER = 0.5;
	public static final double MIN_HEIGHT_SD = 8;
	public static final int MIN_HEIGHT_RANGE = 24;
	public static final int MIN_BIOMES = 2;
	public static final double MIN_BIOME_SHARE = 0.05;

	// dx, dz: blocks from the camera column; floor: the terrain height there; biome: its id.
	public record Sample(int dx, int dz, int floor, String biome) {
	}

	// water and heightSd/heightRange: within NEAR; biomes (with at least MIN_BIOME_SHARE) and farWater: within FAR.
	public record Report(@Nullable String rejection, double water, double heightSd, int heightRange, int biomes, double farWater,
			int centerFloor, String centerBiome) {
		public boolean accepted() {
			return rejection == null;
		}

		/** One log line to compare between Minecraft versions. */
		public String fingerprint() {
			return String.format(Locale.ROOT, "floor %d %s; within %d: water %.1f%%, height sd %.1f, range %d; within %d: %d biomes >= %d%%, water %.1f%%",
					centerFloor, centerBiome, NEAR, water * 100, heightSd, heightRange, FAR, biomes, Math.round(MIN_BIOME_SHARE * 100), farWater * 100);
		}
	}

	private SceneVariety() {
	}

	/** Where to sample, as {dx, dz}: every GRID blocks within FAR of the camera (441 points). */
	public static List<int[]> offsets() {
		List<int[]> out = new ArrayList<>();
		for (int dz = -FAR; dz <= FAR; dz += GRID) {
			for (int dx = -FAR; dx <= FAR; dx += GRID) {
				if (dx * dx + dz * dz <= FAR * FAR) {
					out.add(new int[]{dx, dz});
				}
			}
		}
		return out;
	}

	public static Report check(List<Sample> samples, int seaLevel) {
		List<Sample> near = new ArrayList<>();
		List<Sample> far = new ArrayList<>();
		Sample center = null;
		for (Sample s : samples) {
			long d2 = (long) s.dx() * s.dx() + (long) s.dz() * s.dz();
			if (d2 <= (long) FAR * FAR) {
				far.add(s);
			}
			if (d2 <= (long) NEAR * NEAR) {
				near.add(s);
			}
			if (s.dx() == 0 && s.dz() == 0) {
				center = s;
			}
		}
		int centerFloor = center == null ? 0 : center.floor();
		String centerBiome = center == null ? "?" : center.biome();
		if (near.isEmpty()) {
			return new Report("empty", 0, 0, 0, 0, 0, centerFloor, centerBiome);
		}
		double water = share(near, seaLevel);
		double mean = near.stream().mapToInt(Sample::floor).average().orElse(0);
		double variance = near.stream().mapToDouble(s -> (s.floor() - mean) * (s.floor() - mean)).sum() / near.size();
		double sd = Math.sqrt(variance);
		int range = near.stream().mapToInt(Sample::floor).max().orElse(0) - near.stream().mapToInt(Sample::floor).min().orElse(0);
		Map<String, Integer> counts = new HashMap<>();
		far.forEach(s -> counts.merge(s.biome(), 1, Integer::sum));
		int biomes = (int) counts.values().stream().filter(n -> n >= MIN_BIOME_SHARE * far.size()).count();
		String rejection = water > MAX_WATER ? "ocean"
				: sd < MIN_HEIGHT_SD || range < MIN_HEIGHT_RANGE ? "flat"
				: biomes < MIN_BIOMES ? "monotone"
				: null;
		return new Report(rejection, water, sd, range, biomes, share(far, seaLevel), centerFloor, centerBiome);
	}

	// Below sea level: the terrain floor is under the water surface.
	private static double share(List<Sample> samples, int seaLevel) {
		return samples.isEmpty() ? 0 : (double) samples.stream().filter(s -> s.floor() < seaLevel).count() / samples.size();
	}
}
