package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardwareProbeTest {
	private static final HardwareProbe.Card RADEON = new HardwareProbe.Card("AMD Radeon RX 7800 XT", "Advanced Micro Devices, Inc.", 16368);
	private static final HardwareProbe.Card IGPU = new HardwareProbe.Card("AMD Radeon(TM) Graphics", "Advanced Micro Devices, Inc.", 512);
	private static final HardwareProbe.Card NVIDIA = new HardwareProbe.Card("NVIDIA GeForce RTX 4070", "NVIDIA", 12282);

	@Test
	void matchesExactName() {
		assertEquals(16368, HardwareProbe.matchVram("AMD Radeon RX 7800 XT", List.of(IGPU, RADEON)));
	}

	@Test
	void matchesOpenGlRendererSuffixes() {
		assertEquals(12282, HardwareProbe.matchVram("NVIDIA GeForce RTX 4070/PCIe/SSE2", List.of(RADEON, NVIDIA)));
	}

	@Test
	void prefersModelNumberTokens() {
		assertEquals(16368, HardwareProbe.matchVram("Radeon RX 7800 XT (RADV NAVI32)", List.of(IGPU, RADEON)));
	}

	@Test
	void singleCardIsUsedWhenNothingMatches() {
		assertEquals(512, HardwareProbe.matchVram("Unknown renderer", List.of(IGPU)));
		assertEquals(-1, HardwareProbe.matchVram("Unknown renderer", List.of(IGPU, NVIDIA)));
		assertEquals(-1, HardwareProbe.matchVram("anything", List.of()));
	}

	@Test
	void ignoresPlaceholderBattery() {
		assertEquals(false, HardwareProbe.realBattery("unknown", "unknown", 1, 1));
		assertEquals(true, HardwareProbe.realBattery("DELL 7FJ9265", "LION", 54000, 56000));
		assertEquals(false, HardwareProbe.realBattery("DELL 7FJ9265", "LION", 0, 0));
	}

	@Test
	void backendNames() {
		assertEquals(GraphicsBackend.VULKAN, HardwareProbe.backend("Vulkan"));
		assertEquals(GraphicsBackend.OPENGL, HardwareProbe.backend("OpenGL"));
		assertEquals(GraphicsBackend.UNKNOWN, HardwareProbe.backend(null));
	}
}
