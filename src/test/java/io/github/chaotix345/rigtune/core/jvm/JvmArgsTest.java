package io.github.chaotix345.rigtune.core.jvm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC6.1 (docs/v0.4/SPEC.md 6): units and signs, last-wins duplicates, -D markers, junk tokens, an empty list.
class JvmArgsTest {
	private static final long MIB = 1024L * 1024L;

	@Test
	void sizesInEveryUnit() {
		assertEquals(6144 * MIB, JvmArgs.size("6144M"));
		assertEquals(6144 * MIB, JvmArgs.size("6144m"));
		assertEquals(4L * 1024 * MIB, JvmArgs.size("4G"));
		assertEquals(4L * 1024 * MIB, JvmArgs.size("4g"));
		assertEquals(512L * 1024, JvmArgs.size("512k"));
		assertEquals(512L * 1024, JvmArgs.size("512K"));
		assertEquals(1024L * 1024 * MIB, JvmArgs.size("1t"));
		assertEquals(1073741824L, JvmArgs.size("1073741824"));
		for (String bad : new String[] {"", "M", "4GB", "4.5G", "-4G", "4 G", "99999999999999999999", "9999999999999T", "0x10"}) {
			assertEquals(-1, JvmArgs.size(bad), bad);
		}
		assertEquals(-1, JvmArgs.size(null));
	}

	@Test
	void heapSizesAndSigns() {
		JvmArgs args = JvmArgs.parse(List.of("-Xmx6144M", "-Xms512m", "-Xmn1G", "-Xss1M", "-XX:+UseZGC", "-XX:-UseNUMA",
				"-XX:G1HeapRegionSize=32M"));
		assertEquals(6144 * MIB, args.maxHeapBytes());
		assertEquals(512 * MIB, args.initialHeapBytes());
		assertEquals(1024 * MIB, args.youngGenBytes());
		assertEquals(1, args.maxHeapSettings());
		assertEquals(Boolean.TRUE, args.xx("UseZGC").enabled());
		assertEquals("-XX:+UseZGC", args.xx("UseZGC").display());
		assertEquals(Boolean.FALSE, args.xx("UseNUMA").enabled());
		assertEquals("-XX:-UseNUMA", args.xx("UseNUMA").display());
		JvmArgs.XxFlag region = args.xx("G1HeapRegionSize");
		assertNull(region.enabled());
		assertTrue(region.hasValue());
		assertEquals("-XX:G1HeapRegionSize", region.display(), "the value is never kept");
		assertEquals("-XX:G1HeapRegionSize", region.toString());
		assertEquals(0, args.junk());
	}

	@Test
	void duplicatesResolveLastWins() {
		JvmArgs args = JvmArgs.parse(List.of("-Xmx2G", "-XX:+UseG1GC", "-Xmx6144M", "-XX:-UseG1GC", "-XX:MaxGCPauseMillis=50",
				"-XX:MaxGCPauseMillis=200"));
		assertEquals(6144 * MIB, args.maxHeapBytes());
		assertEquals(2, args.maxHeapSettings());
		assertEquals(Boolean.FALSE, args.xx("UseG1GC").enabled());
		assertTrue(args.xx("MaxGCPauseMillis").hasValue());
		assertEquals(List.of("UseG1GC", "MaxGCPauseMillis"), List.copyOf(args.xx().keySet()));
	}

	@Test
	void maxHeapSizeCountsAsAnXmx() {
		JvmArgs args = JvmArgs.parse(List.of("-Xmx4G", "-XX:MaxHeapSize=2G"));
		assertEquals(2048 * MIB, args.maxHeapBytes());
		assertEquals(2, args.maxHeapSettings());
		JvmArgs initial = JvmArgs.parse(List.of("-XX:InitialHeapSize=1G", "-Xms256m"));
		assertEquals(256 * MIB, initial.initialHeapBytes());
	}

	@Test
	void propertiesKeepOnlyTheirNames() {
		JvmArgs args = JvmArgs.parse(List.of("-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true",
				"-Djava.library.path=C:/Users/Someone/AppData/natives", "-Dflag", "-D", "-D=x"));
		assertEquals(Set.of("using.aikars.flags", "aikars.new.flags", "java.library.path", "flag"), args.propertyKeys());
		assertEquals(2, args.junk(), "-D and -D=x have no name");
		assertFalse(args.toString().contains("Someone"), "no value is kept: " + args);
		assertFalse(args.toString().contains("mcflags"), "no value is kept: " + args);
		JvmArgs paths = JvmArgs.parse(List.of("-XX:ErrorFile=C:/Users/Someone/hs_err.log", "-XX:HeapDumpPath=C:/Users/Someone/dump"));
		assertFalse(paths.toString().contains("Someone"), "no -XX value is kept: " + paths);
		assertEquals("-XX:ErrorFile", paths.xx("ErrorFile").display());
	}

	@Test
	void junkAndOtherTokens() {
		JvmArgs args = JvmArgs.parse(Arrays.asList(null, "", "   ", "-XX:", "-XX:+", "-XX:9Bad", "-XX:NoSign", "-XX:+Bad-Name",
				"-XX:+ParallelGCThreads=8", "-Xmx", "-Xmx4GB", "-Xmxlarge", "garbage",
				"-javaagent:C:/Users/Someone/agent.jar", "--add-opens", "java.base/java.net=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
				"--sun-misc-unsafe-memory-access=allow", "-Xlog:gc", "-Xshare:auto", "-ea", "-cp"));
		assertTrue(args.xx().isEmpty(), "nothing typed as a -XX flag: " + args.xx());
		assertEquals(-1, args.maxHeapBytes());
		assertEquals(0, args.maxHeapSettings());
		assertEquals(12, args.junk(), "malformed -XX/-X/-D tokens and blanks; other tokens (agents, module options) are simply not ours");
		assertTrue(args.propertyKeys().isEmpty());
	}

	@Test
	void emptyAndNull() {
		for (JvmArgs args : List.of(JvmArgs.parse(List.of()), JvmArgs.parse(null))) {
			assertTrue(args.xx().isEmpty());
			assertEquals(-1, args.maxHeapBytes());
			assertEquals(-1, args.initialHeapBytes());
			assertEquals(-1, args.youngGenBytes());
			assertEquals(0, args.maxHeapSettings());
			assertEquals(0, args.junk());
			assertTrue(args.propertyKeys().isEmpty());
		}
	}
}
