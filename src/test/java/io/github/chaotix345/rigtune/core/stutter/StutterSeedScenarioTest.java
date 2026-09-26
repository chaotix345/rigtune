package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.5 over the bundled rules (WS-R's five stutterAdvice seeds, rules r14): each seed's fire and no-fire cases through
// StutterAdvisor, the seeds never reaching the main list, and their wording (X4; SPEC 6's GC conclusion).
class StutterSeedScenarioTest {
	static final RulesDocument RULES = RulesLoader.loadBundled();
	static final String DEFER = "sodium.performance.chunk_build_defer_mode";

	static StutterFacts facts(Map<String, Double> claimed, Map<String, Double> tagged, int full, int stalls, int explicit, Double liveSet, Long room,
			Double contention) {
		return new StutterFacts(claimed, tagged, full, stalls, explicit, liveSet, room, contention, 3.0, "g1");
	}

	static StutterFacts calm() {
		return facts(Map.of("unknown", 100.0), Map.of(), 0, 0, 0, 40.0, 8192L, 10.0);
	}

	static List<String> fired(StutterFacts facts, List<InstalledMod> mods, Map<String, String> settings) {
		return StutterAdvisor.evaluate(RULES, StutterAdvisor.context(RULES, Fixtures.userRig().build(), mods, new SettingsSnapshot(settings), Goal.BALANCED,
				facts)).stream().map(StutterAdvisor.Fired::id).toList();
	}

	static List<String> fired(StutterFacts facts) {
		return fired(facts, Fixtures.mods("sodium"), Map.of(DEFER, "ALWAYS"));
	}

	@Test
	void theBundledSeeds() {
		assertEquals(List.of("ram-stutter-gc-heap", "stutter-gc-explicit", "stutter-sodium-defer", "stutter-dh-threads", "stutter-chunk-loading"),
				RULES.stutterAdvice.stream().map(a -> a.id).toList());
		RULES.stutterAdvice.forEach(a -> assertTrue(StutterAdvisor.supported(a.requires), a.id));
		assertEquals(List.of(), fired(calm()), "a calm session fires nothing");
	}

	@Test
	void memoryPressure() {
		assertEquals(List.of("ram-stutter-gc-heap"), fired(facts(Map.of("gc", 45.0), Map.of(), 1, 0, 0, null, 4096L, null)));
		assertEquals(List.of("ram-stutter-gc-heap"), fired(facts(Map.of("gc", 30.0), Map.of(), 0, 2, 0, 50.0, 2048L, null)), "ZGC allocation stalls");
		assertEquals(List.of("ram-stutter-gc-heap"), fired(facts(Map.of("gc", 60.0), Map.of(), 0, 0, 0, 78.0, 6000L, null)), "a nearly full heap");
		assertEquals(List.of(), fired(facts(Map.of("gc", 29.0), Map.of(), 4, 0, 0, 90.0, 8192L, null)), "GC under 30 % of the lost time");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 0, 0, 0, 60.0, 8192L, null)), "the heap isn't under pressure");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 2, 0, 0, null, 1500L, null)), "no room for 2 GB more");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 2, 0, 0, null, null, null)), "RAM unknown");
		StutterAdvisor.Fired f = StutterAdvisor.evaluate(RULES, StutterAdvisor.context(RULES, Fixtures.userRig().build(), Fixtures.mods("sodium"), null,
				Goal.BALANCED, facts(Map.of("gc", 45.0), Map.of(), 1, 0, 0, null, 4096L, null))).getFirst();
		assertEquals("warning", f.kind());
		assertEquals(Impact.HIGH, f.impact());
		assertTrue(f.memory(), "a ram- id: the launcher's memory steps are shown with it");
	}

	@Test
	void explicitCollections() {
		assertEquals(List.of("stutter-gc-explicit"), fired(facts(Map.of("gc", 15.0), Map.of(), 0, 0, 3, 30.0, 8192L, null)));
		assertEquals(List.of(), fired(facts(Map.of("gc", 15.0), Map.of(), 0, 0, 1, 30.0, 8192L, null)), "a single call");
		assertEquals(List.of(), fired(facts(Map.of("gc", 9.0), Map.of(), 0, 0, 6, 30.0, 8192L, null)), "they cost little");
	}

	@Test
	void sodiumChunkUpdates() {
		StutterFacts building = facts(Map.of("chunkBuild", 35.0), Map.of(), 0, 0, 0, 40.0, 8192L, null);
		assertEquals(List.of("stutter-sodium-defer"), fired(building, Fixtures.mods("sodium"), Map.of(DEFER, "ZERO_FRAMES")));
		assertEquals(List.of("stutter-sodium-defer"), fired(building, Fixtures.mods("sodium"), Map.of(DEFER, "ONE_FRAME")));
		assertEquals(List.of(), fired(building, Fixtures.mods("sodium"), Map.of(DEFER, "ALWAYS")), "already Deferred");
		assertEquals(List.of(), fired(building, Fixtures.mods("sodium"), Map.of()), "the setting unreadable");
		assertEquals(List.of(), fired(building, Fixtures.mods("fabric-api"), Map.of(DEFER, "ZERO_FRAMES")), "no Sodium");
		assertEquals(List.of(), fired(facts(Map.of("chunkBuild", 24.0), Map.of(), 0, 0, 0, 40.0, 8192L, null), Fixtures.mods("sodium"),
				Map.of(DEFER, "ZERO_FRAMES")));
	}

	@Test
	void distantHorizons() {
		StutterFacts busy = facts(Map.of("unknown", 90.0), Map.of("dh", 50.0), 0, 0, 0, 40.0, 8192L, 35.0);
		assertEquals(List.of("stutter-dh-threads"), fired(busy, Fixtures.mods("sodium", "distanthorizons"), Map.of(DEFER, "ALWAYS")));
		assertEquals(List.of(), fired(busy, Fixtures.mods("sodium"), Map.of(DEFER, "ALWAYS")), "DH not installed");
		assertEquals(List.of(), fired(facts(Map.of(), Map.of("dh", 39.0), 0, 0, 0, 40.0, 8192L, 60.0), Fixtures.mods("distanthorizons"), Map.of()));
		assertEquals(List.of(), fired(facts(Map.of(), Map.of("dh", 80.0), 0, 0, 0, 40.0, 8192L, 20.0), Fixtures.mods("distanthorizons"), Map.of()),
				"the CPU wasn't saturated");
		assertEquals(List.of(), fired(facts(Map.of(), Map.of("dh", 80.0), 0, 0, 0, 40.0, 8192L, null), Fixtures.mods("distanthorizons"), Map.of()),
				"no sampler data");
	}

	@Test
	void chunkLoading() {
		assertEquals(List.of("stutter-chunk-loading"), fired(facts(Map.of("chunkLoad", 40.0), Map.of(), 0, 0, 0, 40.0, 8192L, null)));
		assertEquals(List.of(), fired(facts(Map.of("chunkLoad", 29.0), Map.of(), 0, 0, 0, 40.0, 8192L, null)));
	}

	@Test
	void severalCanFireTogether() {
		StutterFacts rough = facts(Map.of("gc", 45.0, "chunkLoad", 35.0, "chunkBuild", 26.0), Map.of("dh", 45.0), 1, 0, 2, null, 4096L, 40.0);
		assertEquals(Set.of("ram-stutter-gc-heap", "stutter-gc-explicit", "stutter-sodium-defer", "stutter-dh-threads", "stutter-chunk-loading"),
				Set.copyOf(fired(rough, Fixtures.mods("sodium", "distanthorizons"), Map.of(DEFER, "ZERO_FRAMES"))));
	}

	// The main list never shows them: it doesn't read the section, and the stutter keys are UNKNOWN there (no facts).
	@Test
	void theMainListNeverShowsThem() {
		Set<String> ids = RULES.stutterAdvice.stream().map(a -> "advice:" + a.id).collect(Collectors.toSet());
		for (Fixtures.Hw hw : List.of(Fixtures.userRig(), Fixtures.lowEndLaptop())) {
			List<Recommendation> recs = Recommender.recommend(RULES, hw.build(), Fixtures.mods("sodium", "distanthorizons"),
					new SettingsSnapshot(Map.of(DEFER, "ZERO_FRAMES")), OnlineData.offline(), Goal.BALANCED).recommendations();
			assertTrue(recs.stream().noneMatch(r -> ids.contains(r.id())), recs.toString());
		}
		assertFalse(Recommender.SUPPORTED_FEATURES.contains(StutterAdvisor.FEATURE));
	}

	// X4: likely / may be related, never a proven cause; SPEC 6: GC flags or another collector are never an FPS fix.
	@Test
	void theSeedsWordHonestly() {
		for (RulesDocument.AdviceRule a : RULES.stutterAdvice) {
			String text = (a.title + " " + a.text).toLowerCase(Locale.ROOT);
			for (String banned : List.of("caused", "because of", "bottleneck", "limited by", "disableexplicitgc", "usezgc")) {
				assertFalse(text.contains(banned), a.id + ": " + banned);
			}
			assertTrue(text.contains("likely") || text.contains("may "), a.id);
		}
	}
}
