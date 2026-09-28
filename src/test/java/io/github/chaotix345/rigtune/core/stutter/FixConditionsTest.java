package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// AC5.8 (FixConditionsTest): a session counts only under the fix's conditions; each differing condition alone gives its
// reason id, the fixed key is left out, and the order is stable.
class FixConditionsTest {
	private static final String RD = "vanilla.renderDistance";

	static Map<String, String> settings() {
		Map<String, String> s = new LinkedHashMap<>();
		s.put(RD, "10");
		s.put("vanilla.simulationDistance", "8");
		s.put("sodium.performance.chunk_build_defer_mode", "ZERO_FRAMES");
		s.put("iris.enableShaders", "false");
		return s;
	}

	static FixConditions base() {
		return new FixConditions("26.2", "abc123", 4096, "g1", 1920, 1080, true, "SINGLEPLAYER", true, true, settings());
	}

	private static FixConditions graphics(@org.jspecify.annotations.Nullable String backend, @org.jspecify.annotations.Nullable String gpu) {
		FixConditions b = base();
		return new FixConditions(b.mc(), b.modSetHash(), b.heapMaxMb(), b.collector(), b.width(), b.height(), b.fullscreen(), b.world(), b.phaseTiming(),
				b.gcMeasured(), b.settings(), backend, gpu);
	}

	// review-11 COMPAT-2 (ws-b's rule for the benchmark, BenchmarkTrend's BACKEND/GPU): sessions on another graphics backend
	// (vanilla's Graphics API option, or 26.3 falling back to Vulkan) or another GPU aren't the same conditions. Both must be
	// known and differ; the GPU is compared on the same or an unknown backend only (one device reads differently under
	// OpenGL and Vulkan), case and outer spaces aside. Unknown on either side claims nothing.
	@Test
	void anotherGraphicsBackendOrGpuDiffers() {
		FixConditions gl = graphics("OPENGL", "AMD Radeon RX 7800 XT");
		List<FixConditions.Difference> vulkan = gl.differences(graphics("VULKAN", "AMD Radeon RX 7800 XT (RADV NAVI32)"), RD);
		assertEquals(List.of(new FixConditions.Difference(FixConditions.Reason.GRAPHICS, List.of())), vulkan);
		assertEquals(List.of(new FixConditions.Difference(FixConditions.Reason.GRAPHICS, List.of())),
				gl.differences(graphics("OPENGL", "Intel(R) UHD Graphics 620"), RD), "the iGPU on the same backend");
		assertEquals(List.of(), gl.differences(graphics("OPENGL", "  amd radeon rx 7800 xt "), RD));
		assertEquals(List.of(), gl.differences(graphics(null, null), RD), "unknown claims nothing");
		assertEquals(List.of(), graphics(null, null).differences(gl, RD));
		assertEquals(List.of(new FixConditions.Difference(FixConditions.Reason.GRAPHICS, List.of())), gl.differences(graphics(null, "Intel(R) UHD Graphics 620"),
				RD), "another GPU on an unknown backend");
		assertEquals(List.of(), gl.differences(graphics("OPENGL", null), RD));
		assertEquals(gl, gl.withMeasurement(true, true), "kept with the measurement flags");
		// review-12 R12FEAT-1 (ws-b's GpuName): a Mesa/LLVM update is the same GPU, not another.
		assertEquals(List.of(), graphics("OPENGL", "AMD Radeon RX 9070 XT (radeonsi, gfx1201, LLVM 20.1.8, DRM 3.64)")
				.differences(graphics("OPENGL", "AMD Radeon RX 9070 XT (radeonsi, gfx1201, LLVM 21.1.7, DRM 3.64)"), RD));
	}

	private static FixConditions with(String what, Object value) {
		FixConditions b = base();
		return new FixConditions(what.equals("mc") ? (String) value : b.mc(), what.equals("mods") ? (String) value : b.modSetHash(),
				what.equals("heap") ? (long) value : b.heapMaxMb(), what.equals("collector") ? (String) value : b.collector(),
				what.equals("width") ? (int) value : b.width(), what.equals("height") ? (int) value : b.height(),
				what.equals("fullscreen") ? (boolean) value : b.fullscreen(), what.equals("world") ? (String) value : b.world(),
				what.equals("phases") ? (boolean) value : b.phaseTiming(), what.equals("gc") ? (boolean) value : b.gcMeasured(), b.settings());
	}

	private static FixConditions withSettings(Map<String, String> settings) {
		FixConditions b = base();
		return new FixConditions(b.mc(), b.modSetHash(), b.heapMaxMb(), b.collector(), b.width(), b.height(), b.fullscreen(), b.world(), b.phaseTiming(),
				b.gcMeasured(), settings);
	}

	private static List<String> reasons(FixConditions other) {
		return base().differences(other, RD).stream().map(d -> d.reason().id()).toList();
	}

	@Test
	void theSameConditionsDifferInNothing() {
		assertEquals(List.of(), base().differences(base(), RD));
	}

	@Test
	void eachConditionAloneGivesItsReason() {
		assertEquals(List.of("version"), reasons(with("mc", "26.3")));
		assertEquals(List.of("mods"), reasons(with("mods", "def456")));
		assertEquals(List.of("mods"), reasons(with("mods", null)));
		assertEquals(List.of("memory"), reasons(with("heap", 8192L)));
		assertEquals(List.of("memory"), reasons(with("collector", "zgc")));
		assertEquals(List.of("display"), reasons(with("width", 1280)));
		assertEquals(List.of("display"), reasons(with("height", 720)));
		assertEquals(List.of("display"), reasons(with("fullscreen", false)));
		assertEquals(List.of("world"), reasons(with("world", "REMOTE")));
		assertEquals(List.of("measurement"), reasons(with("phases", false)));
		assertEquals(List.of("measurement"), reasons(with("gc", false)));
		Map<String, String> s = settings();
		s.put("vanilla.simulationDistance", "12");
		List<FixConditions.Difference> d = base().differences(withSettings(s), RD);
		assertEquals(List.of(new FixConditions.Difference(FixConditions.Reason.SETTING, List.of("vanilla.simulationDistance", "8", "12"))), d);
	}

	// The fixed key must differ (it's the change being measured); the tracker checks it equals the target instead.
	@Test
	void theFixedKeyIsLeftOut() {
		Map<String, String> s = settings();
		s.put(RD, "12");
		assertEquals(List.of(), base().differences(withSettings(s), RD));
		assertEquals(List.of("setting"), base().differences(withSettings(s), "vanilla.simulationDistance").stream().map(x -> x.reason().id()).toList());
	}

	@Test
	void valuesCompareAsSettingsDo() {
		Map<String, String> s = settings();
		s.put("vanilla.simulationDistance", "8.0");
		s.put("sodium.performance.chunk_build_defer_mode", "zero_frames");
		assertEquals(List.of(), base().differences(withSettings(s), RD));
	}

	@Test
	void aSettingOnOneSideOnlyDiffers() {
		Map<String, String> s = settings();
		s.remove("iris.enableShaders");
		s.put(FixConditions.SHADER_PACK, "BSL.zip");
		assertEquals(List.of(new FixConditions.Difference(FixConditions.Reason.SETTING, List.of("iris.enableShaders", "false", "")),
				new FixConditions.Difference(FixConditions.Reason.SETTING, List.of(FixConditions.SHADER_PACK, "", "BSL.zip"))),
				base().differences(withSettings(s), RD));
	}

	// All at once: the fixed order, settings in the share-key table's order, others after.
	@Test
	void theOrderIsStable() {
		Map<String, String> s = new HashMap<>();
		s.put(FixConditions.SHADER_PACK, "BSL.zip");
		s.put("iris.enableShaders", "true");
		s.put("vanilla.simulationDistance", "12");
		s.put("sodium.performance.chunk_build_defer_mode", "ALWAYS");
		FixConditions all = new FixConditions("26.3", "zzz", 2048, "zgc", 800, 600, false, "REALM", false, false, s);
		List<FixConditions.Difference> d = base().differences(all, RD);
		assertEquals(List.of("version", "mods", "memory", "display", "world", "measurement", "setting", "setting", "setting", "setting"),
				d.stream().map(x -> x.reason().id()).toList());
		assertEquals(List.of("vanilla.simulationDistance", "sodium.performance.chunk_build_defer_mode", "iris.enableShaders", FixConditions.SHADER_PACK),
				d.subList(6, 10).stream().map(x -> x.args().getFirst()).toList());
	}

	@Test
	void reasonIdsRoundTrip() {
		for (FixConditions.Reason reason : FixConditions.Reason.values()) {
			assertEquals(reason, FixConditions.Reason.of(reason.id()));
		}
		assertEquals(null, FixConditions.Reason.of("weather"));
	}

	@Test
	void nullSettingValuesAreLeftOut() {
		Map<String, String> s = new HashMap<>();
		s.put(RD, null);
		s.put(null, "x");
		s.put("vanilla.simulationDistance", "8");
		FixConditions c = withSettings(s);
		assertEquals(Map.of("vanilla.simulationDistance", "8"), c.settings());
	}
}
