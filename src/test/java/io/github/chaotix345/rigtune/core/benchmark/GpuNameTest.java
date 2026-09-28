package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// review-12 R12FEAT-1: the GPU key for "the same GPU" leaves out the build versions Mesa puts in its renderer string (LLVM,
// DRM, sometimes the kernel), so a driver update isn't "another GPU". Strings from src/test/resources/drivers/real-strings.tsv
// (rows 11, 13, 15, 18, 22, R5) and docs/research/v0.3/hardware-tiers.md:41.
class GpuNameTest {
	private static final String RX9070_LLVM21 = "AMD Radeon RX 9070 XT (radeonsi, gfx1201, LLVM 21.1.7, DRM 3.64)";
	private static final String RX9070_LLVM20 = "AMD Radeon RX 9070 XT (radeonsi, gfx1201, LLVM 20.1.8, DRM 3.64, 6.16.0-rc6-1-cachyos-rc)";
	private static final String RX9060 = "AMD Radeon RX 9060 XT (radeonsi, gfx1200, LLVM 21.1.8, DRM 3.64)";
	private static final String LLVMPIPE_15 = "llvmpipe (LLVM 15.0.6, 256 bits)";
	private static final String LLVMPIPE_20 = "llvmpipe (LLVM 20.1.2, 256 bits)";
	private static final String ARC = "Intel(R) Arc(TM) B580 Graphics";
	private static final String RTX3060 = "NVIDIA GeForce RTX 3060";

	@Test
	void theSameCardAcrossMesaVersionsIsTheSameGpu() {
		assertTrue(GpuName.same(RX9070_LLVM21, RX9070_LLVM20));
		assertTrue(GpuName.same(LLVMPIPE_15, LLVMPIPE_20));
		assertTrue(GpuName.same(" NVIDIA  GeForce RTX 3060 ", "nvidia geforce rtx 3060"), "case and spaces aside");
	}

	@Test
	void anotherCardIsAnotherGpu() {
		assertFalse(GpuName.same(ARC, RTX3060));
		assertFalse(GpuName.same(RX9070_LLVM21, RX9060));
		assertFalse(GpuName.same("Intel(R) UHD Graphics 620", "NVIDIA GeForce RTX 3050 Laptop GPU/PCIe/SSE2"));
	}

	// Only groups with a build version go: a chip or a trademark in parentheses stays part of the name.
	@Test
	void cleanKeepsTheModelAndDropsVersionGroups() {
		assertEquals("AMD Radeon RX 9070 XT", GpuName.clean(RX9070_LLVM20));
		assertEquals("llvmpipe", GpuName.clean(LLVMPIPE_15));
		assertEquals(ARC, GpuName.clean(ARC));
		assertEquals("AMD Radeon Graphics (RADV GFX1201)", GpuName.clean("AMD Radeon Graphics (RADV GFX1201)"));
		assertEquals("D3D12 (Qualcomm(R) Adreno(TM) X1-85 GPU)", GpuName.clean("D3D12 (Qualcomm(R) Adreno(TM) X1-85 GPU)"));
		assertNull(GpuName.clean("   "));
		assertNull(GpuName.clean(null));
	}
}
