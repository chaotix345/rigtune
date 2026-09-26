package io.github.chaotix345.rigtune.core.jvm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.EXPLICIT_GC_DISABLED;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.IGNORED;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.NO_GC;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.OVERRIDDEN;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.SERVER_SET;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.STOP_THE_WORLD_GC;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.XMX_DUPLICATE;
import static io.github.chaotix345.rigtune.core.jvm.JvmFinding.Kind.YOUNG_GEN_FIXED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC6.2 (docs/v0.4/SPEC.md 6, amendment "Launcher steps"): real command lines (docs/research/v0.4/jvm-gc.md §1.1, the
// launchers' sources, the user's own crash reports) through a HotSpot 25 stand-in.
class JvmFlagClassifierTest {
	private static final long MIB = 1024L * 1024L;
	private static final String HEAP_DUMP = "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump";

	// The 26.2 version JSON's Windows arguments (jvm-gc.md §1.1), with a user name in the paths.
	private static List<String> versionJson(String brand, String version) {
		return List.of(HEAP_DUMP, "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
				"-Djava.library.path=C:/Users/Charlie Someone/AppData/Roaming/ModrinthApp/meta/natives/26.2",
				"-Djna.tmpdir=C:/Users/Charlie Someone/AppData/Roaming/ModrinthApp/meta/natives/26.2",
				"-Dorg.lwjgl.system.SharedLibraryExtractPath=C:/Users/Charlie Someone/AppData/Roaming/ModrinthApp/meta/natives/26.2",
				"-Dio.netty.native.workdir=C:/Users/Charlie Someone/AppData/Roaming/ModrinthApp/meta/natives/26.2",
				"-Dminecraft.launcher.brand=" + brand, "-Dminecraft.launcher.version=" + version);
	}

	private static List<String> concat(List<String> first, String... rest) {
		List<String> all = new ArrayList<>(first);
		all.addAll(List.of(rest));
		return all;
	}

	// modrinth/code 22ccdc1 args.rs:112-205: the version JSON's arguments, -Xmx from the slider, log4j, the agent, IPC,
	// then the instance's Java arguments. The user's real instance: -Xmx6144M and no Java arguments.
	private static List<String> modrinth(String... javaArguments) {
		List<String> all = concat(versionJson("theseus", "0.21.5"), "-Xmx6144M",
				"-Dlog4j.configurationFile=C:/Users/Charlie Someone/AppData/Roaming/ModrinthApp/meta/log_configs/client-1.21.2.xml",
				"-javaagent:C:/Users/Charlie Someone/AppData/Roaming/ModrinthApp/meta/libraries/com/modrinth/theseus/agent/0.21.5/agent.jar",
				"-Dmodrinth.internal.ipc.host=127.0.0.1", "-Dmodrinth.internal.ipc.port=53917", "-Dmodrinth.internal.quickPlay.singleplayer=x");
		all.addAll(List.of(javaArguments));
		return all;
	}

	private static final List<String> MOJANG_G1_SET = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20",
			"-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M");

	// PaperMC's Aikar's flags page (jvm-gc.md §2.1): -Xms = -Xmx plus 19 flags.
	private static final List<String> AIKAR = List.of("-Xms4G", "-Xmx4G", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
			"-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30",
			"-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5",
			"-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
			"-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
			"-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true");

	private static JvmReport classify(List<String> args) {
		FakeVm vm = FakeVm.of(args);
		return classify(args, vm);
	}

	private static JvmReport classify(List<String> args, FakeVm vm) {
		JvmArgs parsed = JvmArgs.parse(args);
		long heap = parsed.maxHeapBytes() > 0 ? parsed.maxHeapBytes() : 8192 * MIB;
		return JvmFlagClassifier.classify(new JvmSnapshot(args, vm, vm.beans(), heap, 512 * MIB, "25.0.3", "Azul Systems, Inc."));
	}

	private static Set<JvmFinding.Kind> kinds(JvmReport report) {
		Set<JvmFinding.Kind> kinds = new java.util.TreeSet<>();
		report.findings().forEach(f -> kinds.add(f.kind()));
		return kinds;
	}

	private static void assertNoFindings(String name, JvmReport report, JvmCollector collector, boolean typed) {
		assertTrue(report.available(), name);
		assertEquals(List.of(), report.findings(), name);
		assertEquals(collector, report.collector(), name);
		assertEquals(typed, report.collectorTyped(), name);
		Set<String> facts = typed ? Set.of(JvmFacts.PROBED, collector.fact(), JvmFacts.GC_TYPED) : Set.of(JvmFacts.PROBED, collector.fact());
		assertEquals(facts, report.facts(), name);
		assertClean(report);
	}

	// No finding, fact or name contains a path, a value or a user name.
	private static void assertClean(JvmReport report) {
		Stream.concat(report.findings().stream().map(JvmFinding::flag), report.facts().stream()).forEach(text -> {
			assertFalse(text.contains("="), text);
			assertFalse(text.contains("/") || text.contains("\\") || text.contains(":/"), text);
			assertFalse(text.contains("Charlie") || text.contains("mcflags"), text);
		});
		assertFalse(report.toString().contains("Charlie"), report.toString());
		assertFalse(report.toString().contains("mcflags"), report.toString());
	}

	@Test
	void theUsersModrinthInstanceHasNoFindings() {
		JvmReport report = classify(modrinth());
		assertNoFindings("Modrinth App, -Xmx6144M", report, JvmCollector.G1, false);
		assertEquals(6144, report.maxHeapMb());
		assertEquals(512, report.initialHeapMb());
		assertEquals("25.0.3", report.javaVersion());
		assertEquals("Azul Systems, Inc.", report.vendor());
	}

	@Test
	void theOfficialLauncherDefaultsHaveNoFindings() {
		// Launcher 3.22.20's logged line (jvm-gc.md §1.1): before 26.1, -Xmx2G and the G1 set.
		List<String> g1 = concat(versionJson("minecraft-launcher", "3.22.20"), "-Xss1M");
		g1.add("-Xmx2G");
		g1.addAll(MOJANG_G1_SET);
		assertNoFindings("official launcher, pre-26.1 G1 default", classify(g1), JvmCollector.G1, true);
		// From 26.1 (Mojang articles 41950300066573 and 39083573916941): ZGC and 4 GB; the article's fix replaces -XX:+UseZGC.
		List<String> zgc = concat(versionJson("minecraft-launcher", "3.24.1"), "-Xmx4G", "-XX:+UseZGC");
		JvmReport report = classify(zgc);
		assertNoFindings("official launcher, 26.1+ ZGC 4 GB default", report, JvmCollector.ZGC, true);
		assertEquals(4096, report.maxHeapMb());
	}

	@Test
	void atLauncherAndPrismDefaultsHaveNoFindings() {
		// ATLauncher 1dac5d8 MCLauncher.java:280-367 and Constants.java:200.
		List<String> atl = new ArrayList<>(List.of("-Xmx4096M", "-XX:MetaspaceSize=256M", "-Duser.language=en", "-Duser.country=US",
				"-Dlog4j.configurationFile=C:/Users/Charlie Someone/AppData/Roaming/ATLauncher/configs/log4j2-xml.xml"));
		atl.addAll(MOJANG_G1_SET);
		atl.addAll(versionJson("ATLauncher", "3.4.41.3"));
		assertNoFindings("ATLauncher defaults", classify(atl), JvmCollector.G1, true);
		// PrismLauncher ea87ffc MinecraftInstance.cpp:572-630: language, (empty) Java Arguments, HeapDumpPath, memory, add-opens.
		List<String> prism = List.of("-Duser.language=en", HEAP_DUMP, "-Xms512m", "-Xmx4096m", "--add-opens", "java.base/java.net=ALL-UNNAMED",
				"-Djava.library.path=C:/Users/Charlie Someone/AppData/Roaming/PrismLauncher/instances/x/natives",
				"-Dorg.prismlauncher.instance.name=My pack");
		assertNoFindings("Prism defaults", classify(prism), JvmCollector.G1, false);
	}

	@Test
	void aikarsSetIsTheServerSet() {
		JvmReport report = classify(modrinth(AIKAR.toArray(String[]::new)));
		assertEquals(Set.of(SERVER_SET, XMX_DUPLICATE), kinds(report), report.findings().toString());
		List<String> server = report.flagsFor("advice:jvm-server-flags");
		assertTrue(server.containsAll(List.of("-Dusing.aikars.flags", "-Daikars.new.flags", "-XX:G1NewSizePercent", "-XX:SurvivorRatio")), server.toString());
		assertTrue(server.containsAll(List.of("-Xms", "-XX:+AlwaysPreTouch", "-XX:+DisableExplicitGC")), "what keeps memory reserved, too: " + server);
		assertTrue(report.facts().containsAll(Set.of(JvmFacts.PROBED, JvmFacts.SERVER_FLAGS, "jvm-gc-g1", JvmFacts.GC_TYPED, JvmFacts.XMX_DUPLICATE)));
		assertFalse(report.facts().contains(JvmFacts.EXPLICIT_GC_DISABLED), "DisableExplicitGC is part of the server set's note");
		assertClean(report);
	}

	@Test
	void theServerSetWithoutMarkersNeedsFourDistinctiveFlags() {
		List<String> four = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
				"-XX:SurvivorRatio=32", "-XX:MaxTenuringThreshold=1");
		assertEquals(Set.of(SERVER_SET), kinds(classify(four)));
		List<String> three = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=30", "-XX:SurvivorRatio=32", "-XX:MaxTenuringThreshold=1");
		assertEquals(Set.of(), kinds(classify(three)));
		JvmReport marker = classify(List.of("-Dusing.aikars.flags=https://mcflags.emc.gs"));
		assertEquals(List.of(new JvmFinding(SERVER_SET, "-Dusing.aikars.flags")), marker.findings());
	}

	@Test
	void zGenerationalIsIgnored() {
		JvmReport report = classify(modrinth("-XX:+UseZGC", "-XX:+ZGenerational"));
		assertEquals(List.of(new JvmFinding(IGNORED, "-XX:+ZGenerational")), report.findings());
		assertEquals(JvmCollector.ZGC, report.collector());
		assertTrue(report.collectorTyped());
		assertEquals(Set.of(JvmFacts.PROBED, "jvm-gc-zgc", JvmFacts.GC_TYPED, JvmFacts.IGNORED_FLAGS), report.facts());
		assertEquals(List.of("-XX:+ZGenerational"), report.flagsFor("advice:jvm-ignored-flags"));
		assertEquals(List.of("-XX:+UseZGC"), report.flagsFor("advice:jvm-zgc-small-heap"));
		assertEquals(List.of("-XX:+UseZGC"), report.flagsFor("jvm-zgc-small-pc"));
		assertEquals(List.of(), report.flagsFor("advice:jvm-server-flags"));
		assertEquals(List.of(), report.flagsFor("advice:ram-low"));
		JvmReport negative = classify(List.of("-XX:-ZGenerational"));
		assertEquals(List.of(new JvmFinding(IGNORED, "-XX:-ZGenerational")), negative.findings());
	}

	@Test
	void numaOnWindowsIsOverridden() {
		JvmReport report = classify(List.of("-XX:+UseNUMA", "-XX:+UseLargePages"));
		assertEquals(List.of(new JvmFinding(OVERRIDDEN, "-XX:+UseNUMA"), new JvmFinding(OVERRIDDEN, "-XX:+UseLargePages")), report.findings());
		assertTrue(report.facts().contains(JvmFacts.IGNORED_FLAGS));
		assertEquals(List.of("-XX:+UseNUMA", "-XX:+UseLargePages"), report.flagsFor("advice:jvm-ignored-flags"));
	}

	@Test
	void aFixedYoungGenerationUnderG1() {
		JvmReport xmn = classify(List.of("-Xmx4G", "-Xmn1G"));
		assertEquals(List.of(new JvmFinding(YOUNG_GEN_FIXED, "-Xmn")), xmn.findings());
		assertTrue(xmn.facts().contains(JvmFacts.YOUNG_GEN_FIXED));
		JvmReport newSize = classify(List.of("-XX:NewSize=512m", "-XX:MaxNewSize=1g"));
		assertEquals(List.of(new JvmFinding(YOUNG_GEN_FIXED, "-XX:NewSize"), new JvmFinding(YOUNG_GEN_FIXED, "-XX:MaxNewSize")), newSize.findings());
		assertEquals(List.of(), classify(List.of("-XX:+UseZGC", "-Xmn1G")).findings(), "only under G1");
	}

	@Test
	void parallelFromJavaToolOptionsIsStopTheWorld() {
		List<String> args = concat(List.of("-XX:+UseParallelGC"), modrinth().toArray(String[]::new));
		JvmReport report = classify(args, FakeVm.withToolOptions(List.of("-XX:+UseParallelGC"), modrinth()));
		assertEquals(List.of(new JvmFinding(STOP_THE_WORLD_GC, "-XX:+UseParallelGC")), report.findings());
		assertEquals(JvmCollector.PARALLEL, report.collector());
		assertEquals(Set.of(JvmFacts.PROBED, "jvm-gc-parallel", JvmFacts.GC_TYPED), report.facts());
		assertEquals(List.of("-XX:+UseParallelGC"), report.flagsFor("advice:jvm-stop-the-world-gc"));
		JvmReport serial = classify(List.of("-XX:+UseSerialGC"));
		assertEquals(List.of(new JvmFinding(STOP_THE_WORLD_GC, "-XX:+UseSerialGC")), serial.findings());
	}

	@Test
	void ergonomicCollectorsAreNotFindings() {
		assertNoFindings("ergonomic G1", classify(List.of()), JvmCollector.G1, false);
		// A tiny machine where HotSpot picks Serial by itself: not typed, so no stop-the-world note.
		VmOptions ergonomicSerial = name -> switch (name) {
			case "UseSerialGC" -> VmOptions.Lookup.found("true", VmOptions.Origin.ERGONOMIC);
			case "UseG1GC", "UseZGC", "UseShenandoahGC", "UseParallelGC" -> VmOptions.Lookup.found("false", VmOptions.Origin.DEFAULT);
			case "MaxHeapSize" -> VmOptions.Lookup.found("268435456", VmOptions.Origin.ERGONOMIC);
			default -> VmOptions.Lookup.MISSING;
		};
		JvmReport serial = JvmFlagClassifier.classify(new JvmSnapshot(List.of(), ergonomicSerial, List.of("Copy", "MarkSweepCompact"), 256 * MIB, -1,
				"25", "x"));
		assertEquals(List.of(), serial.findings());
		assertEquals(JvmCollector.SERIAL, serial.collector());
		assertFalse(serial.collectorTyped());
		assertEquals(-1, serial.initialHeapMb());
	}

	@Test
	void epsilonNeverCollects() {
		JvmReport report = classify(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"));
		assertEquals(List.of(new JvmFinding(NO_GC, "-XX:+UseEpsilonGC")), report.findings());
		assertEquals(Set.of(JvmFacts.PROBED, "jvm-gc-epsilon", JvmFacts.GC_TYPED), report.facts());
	}

	@Test
	void disableExplicitGcAlone() {
		JvmReport report = classify(List.of("-XX:+DisableExplicitGC"));
		assertEquals(List.of(new JvmFinding(EXPLICIT_GC_DISABLED, "-XX:+DisableExplicitGC")), report.findings());
		assertTrue(report.facts().contains(JvmFacts.EXPLICIT_GC_DISABLED));
		assertEquals(List.of(), classify(List.of("-XX:-DisableExplicitGC")).findings());
	}

	@Test
	void twoXmxAreADuplicate() {
		JvmReport report = classify(modrinth("-Xmx8G"));
		assertEquals(List.of(new JvmFinding(XMX_DUPLICATE, "-Xmx")), report.findings());
		assertTrue(report.xmxDuplicate());
		assertEquals(8192, report.maxHeapMb());
		assertEquals(List.of("-Xmx"), report.flagsFor("advice:jvm-xmx-duplicate"));
		assertFalse(classify(modrinth()).xmxDuplicate());
	}

	@Test
	void launcherInjectedFlagsAreNeverFindings() {
		// Even a JVM that didn't know them: HeapDumpPath (version JSON) and MetaspaceSize (ATLauncher) are the launcher's.
		VmOptions nothingKnown = name -> name.equals("UseG1GC") || name.equals("MaxHeapSize") ? VmOptions.Lookup.found("true", VmOptions.Origin.ERGONOMIC)
				: VmOptions.Lookup.MISSING;
		JvmReport report = JvmFlagClassifier.classify(new JvmSnapshot(List.of(HEAP_DUMP, "-XX:MetaspaceSize=256M"), nothingKnown,
				List.of("G1 Young Generation"), 4096 * MIB, -1, "25", "x"));
		assertEquals(List.of(), report.findings());
	}

	@Test
	void noHotSpotBeanMeansNoFactsAndNoException() {
		JvmReport openJ9 = assertDoesNotThrow(() -> JvmFlagClassifier.classify(new JvmSnapshot(modrinth("-XX:+ZGenerational"), null,
				List.of("scavenge", "global"), 6144 * MIB, -1, "21.0.4", "Eclipse OpenJ9")));
		assertFalse(openJ9.available());
		assertEquals(Set.of(), openJ9.facts());
		assertEquals(List.of(), openJ9.findings());
		assertEquals("21.0.4", openJ9.javaVersion());
		assertEquals(JvmCollector.OTHER, openJ9.collector());
		assertNull(openJ9.collectorName());
		assertEquals(6144, openJ9.maxHeapMb());
		// No GC beans read: not probed either (J-M1).
		FakeVm vm = FakeVm.of(List.of());
		JvmReport noBeans = JvmFlagClassifier.classify(new JvmSnapshot(List.of(), vm, List.of(), 4096 * MIB, -1, "25", "x"));
		assertFalse(noBeans.available());
		assertEquals(Set.of(), noBeans.facts());
	}

	@Test
	void aFailingLookupConcludesNothingAboutThatFlag() {
		List<String> args = List.of("-XX:+ZGenerational", "-XX:+UseNUMA");
		JvmReport report = classify(args, FakeVm.failing(args, Set.of("ZGenerational", "UseNUMA")));
		assertEquals(List.of(), report.findings());
		assertTrue(report.available());
	}

	// Review M1: a diagnostic bean that can't find MaxHeapSize (every HotSpot has it) isn't a working one: no facts.
	@Test
	void aBeanThatAnswersNothingIsNotAProbe() {
		List<String> args = modrinth("-XX:+UseZGC", "-XX:+ZGenerational");
		for (VmOptions broken : List.<VmOptions>of(name -> VmOptions.Lookup.MISSING, name -> VmOptions.Lookup.FAILED, name -> {
			throw new IllegalStateException("stub");
		})) {
			JvmReport report = JvmFlagClassifier.classify(new JvmSnapshot(args, broken, List.of("ZGC Minor Cycles"), 6144 * MIB, -1, "21", "x"));
			assertFalse(report.available());
			assertEquals(Set.of(), report.facts());
			assertEquals(List.of(), report.findings());
			assertEquals(JvmCollector.ZGC, report.collector(), "still shown from the bean names");
		}
	}

	@Test
	void argumentFileOptionsAreNeverFindings() {
		VmOptions vm = name -> switch (name) {
			case "UseG1GC" -> VmOptions.Lookup.found("true", VmOptions.Origin.ERGONOMIC);
			case "MaxHeapSize" -> VmOptions.Lookup.found("4294967296", VmOptions.Origin.ERGONOMIC);
			default -> VmOptions.Lookup.MISSING;
		};
		JvmReport report = JvmFlagClassifier.classify(new JvmSnapshot(List.of("-XX:Flags=.hotspotrc", "-XX:VMOptionsFile=C:/x/opts.txt"), vm,
				List.of("G1 Young Generation"), 4096 * MIB, -1, "25", "x"));
		assertEquals(List.of(), report.findings());
	}

	@Test
	void unknownCollectorIsOther() {
		VmOptions none = name -> name.equals("MaxHeapSize") ? VmOptions.Lookup.found("1", VmOptions.Origin.ERGONOMIC) : VmOptions.Lookup.MISSING;
		JvmReport report = JvmFlagClassifier.classify(new JvmSnapshot(List.of(), none, List.of("Mystery Collector"), 4096 * MIB, -1, "27", "x"));
		assertEquals(JvmCollector.OTHER, report.collector());
		assertEquals(Set.of(JvmFacts.PROBED, "jvm-gc-other"), report.facts());
	}

	@Test
	void collectorFromBeansWhenTheOptionsCantTell() {
		VmOptions failing = name -> name.equals("MaxHeapSize") ? VmOptions.Lookup.found("1", VmOptions.Origin.ERGONOMIC) : VmOptions.Lookup.FAILED;
		JvmReport report = JvmFlagClassifier.classify(new JvmSnapshot(List.of("-XX:+UseZGC"), failing,
				List.of("ZGC Minor Cycles", "ZGC Minor Pauses"), 4096 * MIB, -1, "25", "x"));
		assertEquals(JvmCollector.ZGC, report.collector());
		assertTrue(report.collectorTyped(), "typed on the command line");
		assertTrue(report.available());
	}

	@Test
	void everyRuleFlagIsReachable() {
		Set<String> seen = new java.util.HashSet<>();
		for (List<String> args : List.of(List.<String>of(), List.of("-XX:+UseZGC", "-XX:+ZGenerational"), List.of("-XX:+UseShenandoahGC"),
				List.of("-XX:+UseParallelGC"), List.of("-XX:+UseSerialGC"), List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"),
				List.of("-Xmn1G", "-XX:+DisableExplicitGC", "-Xmx2G", "-Xmx3G"), AIKAR)) {
			seen.addAll(classify(args).facts());
		}
		VmOptions none = name -> name.equals("MaxHeapSize") ? VmOptions.Lookup.found("1", VmOptions.Origin.ERGONOMIC) : VmOptions.Lookup.MISSING;
		seen.addAll(JvmFlagClassifier.classify(new JvmSnapshot(List.of(), none, List.of("?"), 1, -1, "27", "x")).facts());
		Set<String> expected = new java.util.HashSet<>(JvmFacts.RULE_FLAGS);
		expected.add(JvmFacts.PROBED);
		assertEquals(expected, seen);
	}
}
