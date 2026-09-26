package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.ConditionEvaluator;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import io.github.chaotix345.rigtune.core.rules.Truth;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.5 (StutterAdvisorTest) against fixture rules shaped like research §5.2's seeds (WS-R owns the bundled content;
// StutterSeedScenarioTest covers it once it lands).
class StutterAdvisorTest {
	static RulesDocument fixture() throws IOException {
		try (InputStream in = StutterAdvisorTest.class.getResourceAsStream("/stutter/advice-fixture.json")) {
			return RulesLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	// A calm session: nothing explained, nothing counted.
	static StutterFacts facts(Map<String, Double> claimed, Map<String, Double> tagged, int full, int stalls, int explicit, Double liveSet, Long room,
			Double contention) {
		return new StutterFacts(claimed, tagged, full, stalls, explicit, liveSet, room, contention, 4.0, "g1");
	}

	static List<String> fired(StutterFacts facts, List<InstalledMod> mods, Map<String, String> settings) throws IOException {
		RulesDocument rules = fixture();
		Fixtures.Hw hw = Fixtures.userRig();
		return StutterAdvisor.evaluate(rules, StutterAdvisor.context(rules, hw.build(), mods, new SettingsSnapshot(settings), Goal.BALANCED, facts)).stream()
				.map(StutterAdvisor.Fired::id).toList();
	}

	static List<String> fired(StutterFacts facts) throws IOException {
		return fired(facts, Fixtures.mods("sodium"), Map.of());
	}

	@Test
	void heapAdviceNeedsGcDominanceHeapPressureAndRoom() throws IOException {
		assertEquals(List.of("ram-stutter-gc-heap"), fired(facts(Map.of("gc", 44.0), Map.of(), 1, 0, 0, null, 4096L, null)));
		assertEquals(List.of("ram-stutter-gc-heap"), fired(facts(Map.of("gc", 30.0), Map.of(), 0, 1, 0, null, 2048L, null)), "a stall counts");
		assertEquals(List.of("ram-stutter-gc-heap"), fired(facts(Map.of("gc", 60.0), Map.of(), 0, 0, 0, 80.0, 3000L, null)), "a nearly full heap counts");
		assertEquals(List.of(), fired(facts(Map.of("gc", 29.0), Map.of(), 3, 0, 0, 90.0, 8000L, null)), "under 30 % GC");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 0, 0, 0, 50.0, 8000L, null)), "no heap pressure");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 0, 0, 0, null, 8000L, null)), "live set unknown and no full GC: can't tell");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 2, 0, 0, null, 1024L, null)), "no room to raise the heap");
		assertEquals(List.of(), fired(facts(Map.of("gc", 60.0), Map.of(), 2, 0, 0, null, null, null)), "RAM unknown");
	}

	@Test
	void explicitGcAdvice() throws IOException {
		assertEquals(List.of("stutter-gc-explicit"), fired(facts(Map.of("gc", 12.0), Map.of(), 0, 0, 2, null, null, null)));
		assertEquals(List.of(), fired(facts(Map.of("gc", 12.0), Map.of(), 0, 0, 1, null, null, null)), "one call");
		assertEquals(List.of(), fired(facts(Map.of("gc", 5.0), Map.of(), 0, 0, 5, null, null, null)), "the calls cost little");
	}

	@Test
	void sodiumDeferAdvice() throws IOException {
		StutterFacts building = facts(Map.of("chunkBuild", 30.0), Map.of(), 0, 0, 0, null, null, null);
		String key = "sodium.performance.chunk_build_defer_mode";
		assertEquals(List.of("stutter-sodium-defer"), fired(building, Fixtures.mods("sodium"), Map.of(key, "ZERO_FRAMES")));
		assertEquals(List.of("stutter-sodium-defer"), fired(building, Fixtures.mods("sodium"), Map.of(key, "ONE_FRAME")));
		assertEquals(List.of(), fired(building, Fixtures.mods("sodium"), Map.of(key, "ALWAYS")), "already Deferred");
		assertEquals(List.of(), fired(building, Fixtures.mods("sodium"), Map.of()), "the setting unknown");
		assertEquals(List.of(), fired(building, Fixtures.mods("lithium"), Map.of(key, "ZERO_FRAMES")), "no Sodium");
		assertEquals(List.of(), fired(facts(Map.of("chunkBuild", 20.0), Map.of(), 0, 0, 0, null, null, null), Fixtures.mods("sodium"), Map.of(key, "ZERO_FRAMES")));
	}

	@Test
	void distantHorizonsAdvice() throws IOException {
		StutterFacts dh = facts(Map.of(), Map.of("dh", 45.0), 0, 0, 0, null, null, 35.0);
		assertEquals(List.of("stutter-dh-threads"), fired(dh, Fixtures.mods("sodium", "distanthorizons"), Map.of()));
		assertEquals(List.of(), fired(dh, Fixtures.mods("sodium"), Map.of()), "DH not installed");
		assertEquals(List.of(), fired(facts(Map.of(), Map.of("dh", 45.0), 0, 0, 0, null, null, 20.0), Fixtures.mods("distanthorizons"), Map.of()),
				"little contention");
		assertEquals(List.of(), fired(facts(Map.of(), Map.of("dh", 45.0), 0, 0, 0, null, null, null), Fixtures.mods("distanthorizons"), Map.of()),
				"no samples");
	}

	@Test
	void chunkLoadingInfo() throws IOException {
		assertEquals(List.of("stutter-chunk-loading"), fired(facts(Map.of("chunkLoad", 55.0), Map.of(), 0, 0, 0, null, null, null)));
		assertEquals(List.of(), fired(facts(Map.of("chunkLoad", 35.0), Map.of(), 0, 0, 0, null, null, null)));
	}

	@Test
	void unknownKeysAndFeaturesNeverFire() throws IOException {
		RulesDocument rules = fixture();
		RulesDocument.AdviceRule unknownKey = rules.stutterAdvice.stream().filter(r -> r.id.equals("stutter-unknown-key")).findFirst().orElseThrow();
		StutterFacts all = facts(Map.of("gc", 90.0), Map.of("dh", 90.0), 9, 9, 9, 99.0, 9999L, 99.0);
		assertEquals(Truth.UNKNOWN, ConditionEvaluator.evaluate(unknownKey.when,
				StutterAdvisor.context(rules, Fixtures.userRig().build(), List.of(), null, Goal.BALANCED, all)));
		List<String> ids = fired(all, Fixtures.mods("sodium", "distanthorizons"), Map.of("sodium.performance.chunk_build_defer_mode", "ZERO_FRAMES"));
		assertFalse(ids.contains("stutter-unknown-key"));
		assertFalse(ids.contains("stutter-needs-newer"), "requires a feature this version lacks, even though `always` holds");
		assertTrue(StutterAdvisor.supported(List.of("stutter-doctor")));
		assertFalse(StutterAdvisor.supported(List.of("stutter-doctor", "shader-timing")));
		assertTrue(StutterAdvisor.supported(null));
	}

	@Test
	void firedAdviceCarriesItsText() throws IOException {
		RulesDocument rules = fixture();
		StutterFacts facts = facts(Map.of("gc", 44.0), Map.of(), 1, 0, 0, null, 4096L, null);
		StutterAdvisor.Fired f = StutterAdvisor.evaluate(rules, StutterAdvisor.context(rules, Fixtures.userRig().build(), List.of(), null, Goal.BALANCED, facts))
				.getFirst();
		assertEquals("warning", f.kind());
		assertEquals(Impact.HIGH, f.impact());
		assertEquals("Stutter from memory pressure", f.title());
		assertTrue(f.memory(), "ram- advice gets the launcher's memory steps");
		assertEquals(List.of(), StutterAdvisor.evaluate(null, StutterAdvisor.context(rules, Fixtures.userRig().build(), List.of(), null, Goal.BALANCED, facts)));
	}

	// The main list: a stutter key in an ordinary advice rule is UNKNOWN (no facts), and the stutterAdvice section is never
	// read, so the Recommender's output is the same with and without it.
	@Test
	void theMainListIgnoresStutterAdvice() throws IOException {
		String base = """
				{"schemaVersion": 2, "revision": 5, "advice": [
				  {"id": "plain", "title": "Plain", "text": "x", "when": {"always": true}},
				  {"id": "sneaky", "title": "Sneaky", "text": "x", "when": {"gcFullPausesAtLeast": 0}},
				  {"id": "sneaky-not", "title": "Sneaky not", "text": "x", "when": {"not": {"gcFullPausesAtLeast": 1}}}]""";
		RulesDocument without = RulesLoader.parse(base + "}");
		RulesDocument with = RulesLoader.parse(base + ", \"stutterAdvice\": " + stutterSection() + "}");
		Report a = Recommender.recommend(without, Fixtures.userRig().build(), Fixtures.mods("sodium"), new SettingsSnapshot(new HashMap<>()),
				OnlineData.offline(), Goal.BALANCED);
		Report b = Recommender.recommend(with, Fixtures.userRig().build(), Fixtures.mods("sodium"), new SettingsSnapshot(new HashMap<>()),
				OnlineData.offline(), Goal.BALANCED);
		assertEquals(List.of("advice:plain"), a.recommendations().stream().map(Recommendation::id).toList());
		assertEquals(a.recommendations().stream().map(Recommendation::id).toList(), b.recommendations().stream().map(Recommendation::id).toList());
		assertFalse(Recommender.SUPPORTED_FEATURES.contains(StutterAdvisor.FEATURE));
	}

	private static String stutterSection() throws IOException {
		try (InputStream in = StutterAdvisorTest.class.getResourceAsStream("/stutter/advice-fixture.json")) {
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return json.substring(json.indexOf('['), json.lastIndexOf(']') + 1);
		}
	}
}
