package io.github.chaotix345.rigtune.core.hardware;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2L (AC2L.1; rw §12.2): the read-only check of Windows' performance-counter switches, with a fake
// registry. Perflib's REG_DWORD 1 -> off, 0 -> on, a REG_SZ -> on and "unusual", absent -> on; a per-service DWORD other
// than 0 -> that service off, another type -> "unusual" only; not Windows -> never read.
class PerfCountersTest {
	private static final String PERFLIB = PerfCounters.PERFLIB_KEY;

	private static PerfCounters.Registry registry(Map<String, Object> values) {
		return (key, name) -> {
			assertEquals(PerfCounters.VALUE, name);
			return values.get(key);
		};
	}

	private static PerfCounters read(Map<String, Object> values) {
		return PerfCounters.detect("Windows 11", () -> registry(values));
	}

	private static String service(String name) {
		return PerfCounters.serviceKey(name);
	}

	@Test
	void perflibOneIsOff() {
		PerfCounters counters = read(Map.of(PERFLIB, 1));
		assertTrue(counters.read());
		assertTrue(counters.off());
		assertEquals(List.of(), counters.unusual());
	}

	@Test
	void perflibZeroIsOn() {
		PerfCounters counters = read(Map.of(PERFLIB, 0));
		assertTrue(counters.read());
		assertFalse(counters.off());
	}

	@Test
	void aRegSzIsOnAndUnusual() {
		PerfCounters counters = read(Map.of(PERFLIB, "1"));
		assertFalse(counters.off(), "only a REG_DWORD counts");
		assertEquals(List.of("Perflib"), counters.unusual());
	}

	@Test
	void absentIsOn() {
		PerfCounters counters = read(Map.of());
		assertTrue(counters.read());
		assertFalse(counters.off());
		assertEquals(List.of(), counters.servicesOff());
		assertEquals(List.of(), counters.unusual());
	}

	@Test
	void notWindowsIsNeverRead() {
		for (String os : List.of("Linux", "Mac OS X", "", "FreeBSD")) {
			PerfCounters counters = PerfCounters.detect(os, () -> {
				throw new AssertionError("the registry is never opened on " + os);
			});
			assertSame(PerfCounters.NOT_READ, counters);
			assertFalse(counters.off());
		}
	}

	@Test
	void theServicesEntries() {
		Map<String, Object> values = new HashMap<>();
		values.put(service("PerfOS"), 0);
		values.put(service("PerfProc"), 1);
		values.put(service("PerfDisk"), 4);
		PerfCounters counters = read(values);
		assertFalse(counters.off(), "a service's own switch isn't Perflib's");
		assertEquals(List.of("PerfProc", "PerfDisk"), counters.servicesOff());
	}

	// This PC's shape (rw §12.2): Perflib 1 as a REG_DWORD, PerfOS "0" as a REG_SZ, PerfProc 0, PerfDisk absent.
	@Test
	void theDevPcsShape() {
		Map<String, Object> values = new HashMap<>();
		values.put(PERFLIB, 1);
		values.put(service("PerfOS"), "0");
		values.put(service("PerfProc"), 0);
		PerfCounters counters = read(values);
		assertTrue(counters.off());
		assertEquals(List.of(), counters.servicesOff());
		assertEquals(List.of("PerfOS"), counters.unusual());
		assertEquals("Windows performance counters: off (Perflib's \"Disable Performance Counters\" isn't 0); unusual value type: PerfOS",
				counters.describe());
	}

	@Test
	void otherTypesAreUnusualAndAReadThatFailsIsAbsent() {
		Map<String, Object> values = new HashMap<>();
		values.put(PERFLIB, 1L);
		values.put(service("PerfOS"), new byte[]{1});
		PerfCounters counters = PerfCounters.detect("Windows 10", () -> (key, name) -> {
			if (key.equals(service("PerfDisk"))) {
				throw new IllegalStateException("access denied");
			}
			return values.get(key);
		});
		assertFalse(counters.off(), "a REG_QWORD isn't the documented REG_DWORD");
		assertEquals(List.of("Perflib", "PerfOS"), counters.unusual());
		assertEquals(List.of(), counters.servicesOff());
	}

	@Test
	void aReaderThatCantBeMadeIsNotRead() {
		PerfCounters counters = PerfCounters.detect("Windows 11", () -> {
			throw new UnsatisfiedLinkError("no JNA");
		});
		assertSame(PerfCounters.NOT_READ, counters);
	}
}
