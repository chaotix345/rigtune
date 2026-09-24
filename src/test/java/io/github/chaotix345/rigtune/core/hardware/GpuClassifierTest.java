package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuClassifierTest {
	private record Case(String vendor, String renderer, GpuVendor expectedVendor, boolean integrated, int minTier, int maxTier) {
	}

	private static final List<Case> CASES = List.of(
			new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 4090/PCIe/SSE2", GpuVendor.NVIDIA, false, 5, 5),
			new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 3060 Laptop GPU/PCIe/SSE2", GpuVendor.NVIDIA, false, 3, 3),
			new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 4060 Laptop GPU/PCIe/SSE2", GpuVendor.NVIDIA, false, 3, 3),
			new Case("NVIDIA Corporation", "NVIDIA GeForce RTX 3070/PCIe/SSE2", GpuVendor.NVIDIA, false, 4, 4),
			new Case("NVIDIA Corporation", "NVIDIA GeForce GTX 1050 Ti/PCIe/SSE2", GpuVendor.NVIDIA, false, 2, 2),
			new Case("NVIDIA Corporation", "NVIDIA GeForce GTX 1660 SUPER/PCIe/SSE2", GpuVendor.NVIDIA, false, 3, 3),
			new Case("Microsoft Corporation", "D3D12 (NVIDIA GeForce RTX 4070)", GpuVendor.NVIDIA, false, 4, 4),
			new Case("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", GpuVendor.AMD, false, 5, 5),
			new Case("AMD", "AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 17.0.6, DRM 3.57, 6.8.0)", GpuVendor.AMD, false, 5, 5),
			new Case("ATI Technologies Inc.", "AMD Radeon RX 7900 XTX", GpuVendor.AMD, false, 5, 5),
			new Case("ATI Technologies Inc.", "AMD Radeon RX 6600", GpuVendor.AMD, false, 4, 4),
			new Case("ATI Technologies Inc.", "Radeon RX 580 Series", GpuVendor.AMD, false, 2, 2),
			new Case("ATI Technologies Inc.", "AMD Radeon(TM) Graphics", GpuVendor.AMD, true, 1, 2),
			new Case("ATI Technologies Inc.", "AMD Radeon(TM) Vega 8 Graphics", GpuVendor.AMD, true, 1, 2),
			new Case("ATI Technologies Inc.", "AMD Radeon 780M Graphics", GpuVendor.AMD, true, 3, 3),
			new Case("Intel", "Intel(R) UHD Graphics 620", GpuVendor.INTEL, true, 2, 2),
			new Case("Intel", "Mesa Intel(R) UHD Graphics 620 (KBL GT2)", GpuVendor.INTEL, true, 2, 2),
			new Case("Intel", "Intel(R) HD Graphics 4000", GpuVendor.INTEL, true, 1, 1),
			new Case("Intel", "Intel(R) Iris(R) Xe Graphics", GpuVendor.INTEL, true, 3, 3),
			new Case("Intel", "Intel(R) Arc(TM) A770 Graphics", GpuVendor.INTEL, false, 4, 4),
			new Case("Intel", "Intel(R) Arc(TM) Graphics", GpuVendor.INTEL, true, 3, 3),
			new Case("Apple", "Apple M2", GpuVendor.APPLE, true, 3, 3),
			new Case("Apple", "Apple M3 Max", GpuVendor.APPLE, true, 4, 4),
			new Case("Qualcomm", "Qualcomm(R) Adreno(TM) X1-85 GPU", GpuVendor.QUALCOMM, true, 2, 2),
			new Case("Mesa", "llvmpipe (LLVM 15.0.7, 256 bits)", GpuVendor.SOFTWARE, false, 0, 0),
			new Case("Microsoft Corporation", "Microsoft Basic Render Driver", GpuVendor.SOFTWARE, false, 0, 0),
			new Case("Microsoft Corporation", "GDI Generic", GpuVendor.SOFTWARE, false, 0, 0));

	@Test
	void classifiesRealRendererStringsWithBundledRules() {
		GpuClassifier classifier = GpuClassifier.from(RulesLoader.loadBundled());
		assertAll(CASES.stream().map(c -> (Executable) () -> {
			GpuClass result = classifier.classify(new GpuInfo(c.vendor(), c.renderer(), "1", GraphicsBackend.OPENGL, -1));
			String label = c.renderer() + " -> " + result;
			assertEquals(c.expectedVendor(), result.vendor(), label);
			assertEquals(c.integrated(), result.integrated(), label);
			assertTrue(result.tier() >= c.minTier() && result.tier() <= c.maxTier(), label);
		}));
	}

	@Test
	void recordsMatchedPattern() {
		GpuClass result = GpuClassifier.from(RulesLoader.loadBundled())
				.classify(new GpuInfo("NVIDIA Corporation", "NVIDIA GeForce RTX 4090/PCIe/SSE2", "1", GraphicsBackend.OPENGL, 24576));
		assertNotNull(result.matchedPattern());
	}

	@Test
	void fallsBackToVendorTierAndHeuristicWithoutRules() {
		GpuClassifier classifier = new GpuClassifier(List.of(), Map.of("intel", 2, "amd", 3));
		GpuClass intel = classifier.classify(new GpuInfo("Intel", "Intel(R) UHD Graphics 620", "1", GraphicsBackend.OPENGL, -1));
		assertEquals(new GpuClass(GpuVendor.INTEL, true, 2, null), intel);

		GpuClass arc = classifier.classify(new GpuInfo("Intel", "Intel(R) Arc(TM) B580 Graphics", "1", GraphicsBackend.OPENGL, -1));
		assertFalse(arc.integrated());

		GpuClass amdIgpu = classifier.classify(new GpuInfo("ATI Technologies Inc.", "AMD Radeon Graphics", "1", GraphicsBackend.OPENGL, -1));
		assertTrue(amdIgpu.integrated());
		assertEquals(3, amdIgpu.tier());

		GpuClass amdDiscrete = classifier.classify(new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 9999 XT", "1", GraphicsBackend.OPENGL, -1));
		assertFalse(amdDiscrete.integrated());

		GpuClass vega = classifier.classify(new GpuInfo("ATI Technologies Inc.", "AMD Radeon(TM) Vega 11 Graphics", "1", GraphicsBackend.OPENGL, -1));
		assertTrue(vega.integrated());

		GpuClass apple = classifier.classify(new GpuInfo("Apple", "Apple M1", "1", GraphicsBackend.OPENGL, -1));
		assertTrue(apple.integrated());
		assertEquals(GpuClassifier.DEFAULT_TIER, apple.tier());

		GpuClass software = classifier.classify(new GpuInfo("Mesa", "llvmpipe (LLVM 17.0.6, 256 bits)", "1", GraphicsBackend.OPENGL, -1));
		assertEquals(GpuVendor.SOFTWARE, software.vendor());
		assertEquals(0, software.tier());
		assertNull(software.matchedPattern());
	}

	@Test
	void ruleForAnotherVendorIsIgnored() {
		RulesDocument.GpuTierRule rule = new RulesDocument.GpuTierRule();
		rule.pattern = "(?i)radeon";
		rule.vendor = "nvidia";
		rule.tier = 5;
		GpuClass result = new GpuClassifier(List.of(rule), Map.of("amd", 3))
				.classify(new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 580", "1", GraphicsBackend.OPENGL, -1));
		assertEquals(GpuVendor.AMD, result.vendor());
		assertEquals(3, result.tier());
	}

	@Test
	void detectsVendors() {
		assertEquals(GpuVendor.NVIDIA, GpuClassifier.detectVendor("NVIDIA Corporation", ""));
		assertEquals(GpuVendor.AMD, GpuClassifier.detectVendor("Advanced Micro Devices, Inc.", ""));
		assertEquals(GpuVendor.AMD, GpuClassifier.detectVendor("ATI Technologies Inc.", "Unknown"));
		assertEquals(GpuVendor.OTHER, GpuClassifier.detectVendor("Imagination Technologies", "PowerVR"));
		assertEquals(GpuVendor.UNKNOWN, GpuClassifier.detectVendor(null, null));
		assertEquals(GpuVendor.SOFTWARE, GpuClassifier.detectVendor("Google Inc.", "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device))"));
	}
}
