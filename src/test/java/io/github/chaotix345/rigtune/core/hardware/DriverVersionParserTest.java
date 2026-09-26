package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.DriverVersion;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 9 (AC9.1): every vector of docs/research/v0.4/drivers.md §2.3, the 26.3 Vulkan composed strings, and
// garbage that must come out UNKNOWN, never as an exception.
class DriverVersionParserTest {
	private static void parses(GpuVendor vendor, String raw, String family, int... comparable) {
		parses(vendor, null, raw, family, comparable);
	}

	private static void parses(GpuVendor vendor, GraphicsBackend backend, String raw, String family, int... comparable) {
		DriverVersion v = backend == null ? DriverVersionParser.parse(vendor, raw) : DriverVersionParser.parse(vendor, backend, raw);
		assertTrue(v.known(), raw + " parses");
		assertEquals(family, v.family(), raw);
		assertArrayEquals(comparable, v.comparable(), raw);
		assertEquals(vendor, v.vendor());
		assertEquals(raw, v.raw());
	}

	private static void unknown(GpuVendor vendor, String raw) {
		DriverVersion v = DriverVersionParser.parse(vendor, raw);
		assertFalse(v.known(), raw + " is UNKNOWN");
		assertEquals(DriverVersion.UNKNOWN_FAMILY, v.family());
		assertEquals(0, v.comparable().length);
	}

	@Test
	void researchVectors() {
		parses(GpuVendor.AMD, "3.3.0 Core Profile Context 26.8.1.260810", DriverVersion.ADRENALIN, 26, 8, 1);
		parses(GpuVendor.AMD, "4.6.14761 Core Profile Context 24.12.1.241205", DriverVersion.ADRENALIN, 24, 12, 1);
		parses(GpuVendor.AMD, "4.6 (Core Profile) Mesa 24.2.3", DriverVersion.MESA, 24, 2, 3);
		parses(GpuVendor.NVIDIA, "4.6.0 NVIDIA 560.94", DriverVersion.GEFORCE, 560, 94);
		parses(GpuVendor.NVIDIA, "3.3.0 NVIDIA 340.102", DriverVersion.GEFORCE, 340, 102);
		parses(GpuVendor.INTEL, "4.6.0 - Build 31.0.101.5595", DriverVersion.INTEL, 31, 0, 101, 5595);
		parses(GpuVendor.INTEL, "4.5.0 - Build 25.20.100.6471", DriverVersion.INTEL, 25, 20, 100, 6471);
	}

	@Test
	void mesaForAnyVendorAndLinuxNvidiaThreeParts() {
		parses(GpuVendor.INTEL, "4.6 (Core Profile) Mesa 24.0.5-0ubuntu0.1", DriverVersion.MESA, 24, 0, 5);
		parses(GpuVendor.NVIDIA, "4.3 (Core Profile) Mesa 23.3.6", DriverVersion.MESA, 23, 3, 6);
		parses(GpuVendor.SOFTWARE, "4.5 (Core Profile) Mesa 24.2.8-1ubuntu1~24.04.1", DriverVersion.MESA, 24, 2, 8);
		parses(GpuVendor.NVIDIA, "4.6.0 NVIDIA 550.54.14", DriverVersion.GEFORCE, 550, 54, 14);
		parses(GpuVendor.INTEL, "4.0.0 - Build 10.18.10.4358", DriverVersion.INTEL, 10, 18, 10, 4358);
	}

	@Test
	void vulkanComposedStringParsesOnlyTheDriverPart() {
		parses(GpuVendor.NVIDIA, "1.3.296 NVIDIA 560.94", DriverVersion.GEFORCE, 560, 94);
		parses(GpuVendor.AMD, "1.3.290 Mesa RADV 24.2.3", DriverVersion.MESA, 24, 2, 3);
		parses(GpuVendor.NVIDIA, GraphicsBackend.VULKAN, "1.3.296 NVIDIA 560.94", DriverVersion.GEFORCE, 560, 94);
		parses(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.3.290 Mesa RADV 24.2.3", DriverVersion.MESA, 24, 2, 3);
		parses(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.3.289 radv Mesa 24.0.9-0ubuntu0.1", DriverVersion.MESA, 24, 0, 9);
		parses(GpuVendor.INTEL, GraphicsBackend.VULKAN, "1.3.289 Intel open-source Mesa driver Mesa 24.2.3", DriverVersion.MESA, 24, 2, 3);
		// The API version is never the driver version.
		assertFalse(DriverVersionParser.parse(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.3.296 AMD proprietary driver 24.12.1 (AMD proprietary shader compiler)").known());
		assertFalse(DriverVersionParser.parse(GpuVendor.INTEL, GraphicsBackend.VULKAN, "1.3.296 Intel Corporation 101.5595").known());
		assertFalse(DriverVersionParser.parse(GpuVendor.NVIDIA, GraphicsBackend.VULKAN, "1.3.296").known());
		// The GL sub-parsers don't read a Vulkan string, and the GL backend doesn't read the Vulkan form.
		assertFalse(DriverVersionParser.parse(GpuVendor.AMD, GraphicsBackend.VULKAN, "3.3.0 Core Profile Context 26.8.1.260810").known());
		assertFalse(DriverVersionParser.parse(GpuVendor.AMD, GraphicsBackend.OPENGL, "1.3.290 Mesa RADV 24.2.3").known());
	}

	@Test
	void vendorGatesTheVendorFamilies() {
		unknown(GpuVendor.INTEL, "4.6.0 NVIDIA 560.94");
		unknown(GpuVendor.NVIDIA, "3.3.0 Core Profile Context 26.8.1.260810");
		unknown(GpuVendor.AMD, "4.6.0 - Build 31.0.101.5595");
		unknown(GpuVendor.UNKNOWN, "4.6.0 NVIDIA 560.94");
		unknown(GpuVendor.OTHER, "3.3.0 Core Profile Context 26.8.1.260810");
		parses(GpuVendor.UNKNOWN, "4.6 (Core Profile) Mesa 24.2.3", DriverVersion.MESA, 24, 2, 3);
	}

	@Test
	void legacyAmdFiveSegmentsIsUnknown() {
		unknown(GpuVendor.AMD, "4.6.14761 Core Profile Context 22.20.27.09.230330");
		unknown(GpuVendor.AMD, "22.20.27.09.230330");
	}

	@Test
	void garbageIsUnknown() {
		for (String raw : new String[]{"", " ", "unknown", "1.0", "25.9.1", "NVIDIA", "NVIDIA 56", "3.3.0 Core Profile Context",
				"3.3.0 Core Profile Context 26.8", "4.6 (Core Profile) Mesa 24", "4.6.0 - Build 31.0.101", "4.6.0 - Build",
				"4.6.0 NVIDIA 5600.94", "OpenGL ES 3.2 v1.r38p1", "Metal 3", "4.6.0 - Build 99999999999999999999.0.101.5595",
				"\u0000\u0001", "Context 26.8.1.260810"}) {
			for (GpuVendor vendor : GpuVendor.values()) {
				DriverVersion v = DriverVersionParser.parse(vendor, raw);
				if (raw.equals("Context 26.8.1.260810") && vendor == GpuVendor.AMD) {
					continue;
				}
				assertFalse(v.known(), vendor + " " + raw);
			}
		}
		assertFalse(DriverVersionParser.parse(GpuVendor.NVIDIA, null).known());
		assertFalse(DriverVersionParser.parse(null, "4.6.0 NVIDIA 560.94").known());
		assertTrue(DriverVersionParser.parse(GpuVendor.NVIDIA, null, "4.6.0 NVIDIA 560.94").known(), "a null backend counts as unknown");
		assertEquals("", DriverVersionParser.parse(GpuVendor.NVIDIA, null).raw());
	}

	@Test
	void overlongInputIsUnknownQuickly() {
		String huge = "4.6.0 NVIDIA 560.94 " + "9.".repeat(200_000);
		long start = System.nanoTime();
		assertFalse(DriverVersionParser.parse(GpuVendor.NVIDIA, huge).known());
		assertTrue(System.nanoTime() - start < 1_000_000_000L);
	}

	@Test
	void randomInputNeverThrows() {
		Random random = new Random(9);
		String alphabet = "0123456789. -()NVIDIAMesaContextBuildCoreProfile‮\u0000";
		for (int i = 0; i < 20_000; i++) {
			StringBuilder s = new StringBuilder();
			int length = random.nextInt(60);
			for (int j = 0; j < length; j++) {
				s.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			for (GraphicsBackend backend : GraphicsBackend.values()) {
				DriverVersionParser.parse(GpuVendor.values()[random.nextInt(GpuVendor.values().length)], backend, s.toString());
			}
		}
	}

	@Test
	void comparesLeftToRightWithMissingPartsAsZero() {
		assertEquals(0, DriverVersion.compare(new int[]{526, 47}, new int[]{526, 47, 0}));
		assertTrue(DriverVersion.compare(new int[]{531, 18}, new int[]{526, 47}) > 0);
		assertTrue(DriverVersion.compare(new int[]{10, 18, 10, 4358}, new int[]{10, 18, 10, 5160}) < 0);
		assertTrue(DriverVersion.compare(new int[]{536, 23}, new int[]{536, 22}) > 0);
		assertEquals("560.94", DriverVersionParser.parse(GpuVendor.NVIDIA, "4.6.0 NVIDIA 560.94").display());
		assertEquals("4.6.0 NVIDIA", DriverVersionParser.parse(GpuVendor.NVIDIA, "4.6.0 NVIDIA").display(), "unknown shows the raw string");
		assertEquals(DriverVersionParser.parse(GpuVendor.NVIDIA, "4.6.0 NVIDIA 560.94"), DriverVersionParser.parse(GpuVendor.NVIDIA, "4.6.0 NVIDIA 560.94"));
	}

	@Test
	void dottedNumbers() {
		assertArrayEquals(new int[]{526, 47}, DriverVersion.dotted("526.47"));
		assertArrayEquals(new int[]{10, 18, 10, 5160}, DriverVersion.dotted("10.18.10.5160"));
		assertArrayEquals(new int[]{7}, DriverVersion.dotted(" 7 "));
		for (String bad : new String[]{null, "", ".", "1.", ".1", "1..2", "a.b", "-1", "1.2.3.4.5.6.7.8.9", "99999999999", "1,2", "1 .2"}) {
			assertEquals(null, DriverVersion.dotted(bad), String.valueOf(bad));
		}
	}
}
