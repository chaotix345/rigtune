package io.github.chaotix345.rigtune.core.jvm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// AC6.3 (docs/v0.4/SPEC.md 6 with the "Launcher steps" amendment): real command lines through the classifier, its facts
// into the profile, the rules through the Recommender: each jvm-* advice fires for its case with its found flags and the
// launcher's Java-arguments steps (none for an unknown launcher), and Java's and the launchers' defaults fire nothing.
// Until the bundled rules carry the jvm-* advice (WS-R), they run with the fixture advice of
// src/test/resources/jvm/jvm-advice-fixture.json appended (SPEC 6's conditions, without requires).
class JvmScenarioTest {
	private static final long MIB = 1024L * 1024L;
	private static final List<String> MOJANG_G1_SET = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20",
			"-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M");
	private static final List<String> AIKAR = List.of("-Xms4G", "-Xmx4G", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
			"-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30",
			"-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5",
			"-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
			"-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
			"-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true");

	static RulesDocument rules() throws IOException {
		JsonObject bundled = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/rigtune/rules-v2.json"))).getAsJsonObject();
		JsonArray advice = bundled.getAsJsonArray("advice");
		boolean hasJvm = advice.asList().stream().anyMatch(a -> a.getAsJsonObject().get("id").getAsString().startsWith(JvmFacts.PREFIX));
		if (!hasJvm) {
			JsonObject fixture = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/test/resources/jvm/jvm-advice-fixture.json"))).getAsJsonObject();
			for (JsonElement entry : fixture.getAsJsonArray("advice")) {
				advice.add(entry);
			}
		}
		return RulesLoader.parse(bundled.toString());
	}

	private record Case(String name, long ramMb, List<String> args, List<String> toolOptions, Set<String> expected) {
	}

	private static Case of(String name, long ramMb, List<String> args, String... expected) {
		return new Case(name, ramMb, args, List.of(), Set.of(expected));
	}

	private static List<String> modrinth(String... javaArguments) {
		List<String> all = new ArrayList<>(List.of("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
				"--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-Djava.library.path=C:/Users/x/natives",
				"-Dminecraft.launcher.brand=theseus", "-Dminecraft.launcher.version=0.21.5", "-Xmx6144M",
				"-javaagent:C:/Users/x/agent.jar", "-Dmodrinth.internal.ipc.host=127.0.0.1"));
		all.addAll(List.of(javaArguments));
		return all;
	}

	private static List<String> plus(List<String> first, String... more) {
		List<String> all = new ArrayList<>(first);
		all.addAll(List.of(more));
		return all;
	}

	private static List<Case> cases() {
		List<Case> cases = new ArrayList<>();
		cases.add(of("the user's Modrinth instance", 32768, modrinth()));
		cases.add(of("official launcher before 26.1 (G1 set, 2 GB)", 16384, plus(List.of("-Xss1M", "-Xmx2G"), MOJANG_G1_SET.toArray(String[]::new))));
		cases.add(of("official launcher 26.1+ (ZGC, 4 GB) on 16 GB", 16384, List.of("-Xmx4G", "-XX:+UseZGC")));
		cases.add(of("official launcher 26.1+ (ZGC, 4 GB) on 8 GB", 8192, List.of("-Xmx4G", "-XX:+UseZGC"), "jvm-zgc-small-pc"));
		cases.add(of("official launcher 26.1+ back at 2 GB on 16 GB", 16384, List.of("-Xmx2G", "-XX:+UseZGC"), "jvm-zgc-small-heap"));
		cases.add(of("official launcher 26.1+ at 2 GB on a 4 GB PC (Mojang's own advice)", 4096, List.of("-Xmx2G", "-XX:+UseZGC")));
		cases.add(of("ATLauncher defaults", 16384, plus(List.of("-Xmx4096M", "-XX:MetaspaceSize=256M", "-Duser.language=en"), MOJANG_G1_SET.toArray(String[]::new))));
		cases.add(of("Prism defaults", 16384, List.of("-Duser.language=en", "-Xms512m", "-Xmx4096m", "--add-opens", "java.base/java.net=ALL-UNNAMED")));
		cases.add(of("ZGC + ZGenerational in Modrinth", 32768, modrinth("-XX:+UseZGC", "-XX:+ZGenerational"), "jvm-ignored-flags"));
		cases.add(of("NUMA on Windows", 32768, modrinth("-XX:+UseNUMA"), "jvm-ignored-flags"));
		cases.add(of("Aikar's set in Modrinth on 16 GB", 16384, modrinth(AIKAR.toArray(String[]::new)), "jvm-server-flags", "jvm-xmx-duplicate"));
		cases.add(of("Aikar's set in Modrinth on 32 GB", 32768, modrinth(AIKAR.toArray(String[]::new)), "jvm-xmx-duplicate"));
		cases.add(of("-Xmn under G1", 16384, modrinth("-Xmn1G"), "jvm-young-gen-fixed"));
		cases.add(new Case("Parallel from JAVA_TOOL_OPTIONS", 16384, plus(List.of("-XX:+UseParallelGC"), modrinth().toArray(String[]::new)),
				List.of("-XX:+UseParallelGC"), Set.of("jvm-stop-the-world-gc")));
		cases.add(of("Serial typed", 16384, modrinth("-XX:+UseSerialGC"), "jvm-stop-the-world-gc"));
		cases.add(of("Epsilon", 16384, modrinth("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"), "jvm-no-gc"));
		cases.add(of("DisableExplicitGC alone", 16384, modrinth("-XX:+DisableExplicitGC"), "jvm-explicit-gc-disabled"));
		cases.add(of("-Xmx typed over the Modrinth slider", 16384, modrinth("-Xmx8G"), "jvm-xmx-duplicate"));
		return cases;
	}

	private static JvmReport classify(Case c) {
		FakeVm vm = c.toolOptions().isEmpty() ? FakeVm.of(c.args()) : FakeVm.withToolOptions(c.toolOptions(), c.args().subList(c.toolOptions().size(), c.args().size()));
		JvmArgs parsed = JvmArgs.parse(c.args());
		long heap = parsed.maxHeapBytes() > 0 ? parsed.maxHeapBytes() : c.ramMb() / 4 * MIB;
		return JvmFlagClassifier.classify(new JvmSnapshot(c.args(), vm, vm.beans(), heap, -1, "25.0.3", "Azul Systems, Inc."));
	}

	private static List<Recommendation> recommend(RulesDocument rules, long ramMb, JvmReport jvm) {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.ramMb = ramMb;
		hw.heapMb = jvm.maxHeapMb();
		hw.flags = jvm.facts();
		return Recommender.recommend(rules, hw.build(), Fixtures.mods("sodium"), new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED)
				.recommendations();
	}

	@Test
	void eachJvmAdviceFiresForItsCaseWithItsLines() throws IOException {
		RulesDocument rules = rules();
		for (Case c : cases()) {
			JvmReport jvm = classify(c);
			List<Recommendation> recs = recommend(rules, c.ramMb(), jvm);
			Set<String> fired = recs.stream().filter(LauncherAdvice::isJvmAdvice).map(r -> r.id().substring("advice:".length()))
					.collect(Collectors.toCollection(TreeSet::new));
			assertEquals(new TreeSet<>(c.expected()), fired, c.name() + ": facts " + jvm.facts() + ", notes " + jvm.findings());
			for (Recommendation r : recs) {
				if (!LauncherAdvice.isJvmAdvice(r)) {
					continue;
				}
				assertFalse(jvm.flagsFor(r.id()).isEmpty(), c.name() + ": " + r.id() + " names the flags it's about");
				for (Launcher launcher : Launcher.values()) {
					String key = LauncherAdvice.jvmStepsKey(r, LauncherInfo.of(launcher));
					if (launcher == Launcher.UNKNOWN) {
						assertNull(key, c.name());
					} else {
						assertNotNull(key, c.name() + " / " + launcher);
					}
				}
			}
		}
	}

	@Test
	void withoutTheHotSpotBeanNothingFires() throws IOException {
		RulesDocument rules = rules();
		List<String> args = modrinth(AIKAR.toArray(String[]::new));
		JvmReport openJ9 = JvmFlagClassifier.classify(new JvmSnapshot(args, null, List.of("scavenge"), 4096 * MIB, -1, "21", "Eclipse OpenJ9"));
		for (long ram : new long[] {4096, 8192, 16384, 32768}) {
			assertEquals(List.of(), recommend(rules, ram, openJ9).stream().filter(LauncherAdvice::isJvmAdvice).map(Recommendation::id).toList());
		}
	}

	@Test
	void everyFixtureAdviceIsCovered() throws IOException {
		Set<String> covered = new TreeSet<>();
		cases().forEach(c -> covered.addAll(c.expected()));
		Set<String> ids = rules().advice.stream().map(a -> a.id).filter(id -> id.startsWith(JvmFacts.PREFIX)).collect(Collectors.toCollection(TreeSet::new));
		assertEquals(ids, covered);
	}
}
