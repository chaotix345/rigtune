package io.github.chaotix345.rigtune.core.stutter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2S L2, AC2S.3: the bundled "chunks loading" seed against facts recorded on the 0.4 RC
// (src/test/resources/stutter/l2-calibration: P5C-F1's teleport re-runs C1r, C3r, C1r2 and AC5.8's still A control, copied
// from docs/v0.4/verification/stutter/). The re-runs have 52 s of gameplay, under the Stutter Doctor's 2-minute enough-data
// gate, so their facts go through the condition (StutterAdvisor.evaluate without the gate); the control had enough data
// and goes through the gated path. It fires on the re-runs and not on the control, also at the control's worst case: the
// A run predates the tag, and at most 3 of its 4 spikes (the world-entry ones) could have carried it.
class ChunksLoadingSeedTest {
	static final String SEED = "stutter-chunks-loading-tag";
	static final RulesDocument RULES = StutterSeedScenarioTest.RULES;

	static JsonObject session(String run) throws IOException {
		try (InputStream in = ChunksLoadingSeedTest.class.getResourceAsStream("/stutter/l2-calibration/" + run + "-stutter.json")) {
			assertNotNull(in, run);
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("sessions").get(0)
					.getAsJsonObject();
		}
	}

	static int spikes(JsonObject session) {
		return session.getAsJsonObject("spikes").entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
	}

	// The facts StutterAnalyzer derives, from what the session recorded: cause shares of the lost time, tag shares of the
	// spikes, spikes per gameplay minute and the GC counts (RAM and CPU contention weren't recorded: unknown).
	static StutterFacts recorded(JsonObject session, Map<String, Double> extraTags) {
		int spikes = spikes(session);
		Map<String, Double> claimed = new HashMap<>();
		for (Map.Entry<String, JsonElement> cause : session.getAsJsonObject("causes").entrySet()) {
			claimed.put(cause.getKey(), 100 * cause.getValue().getAsDouble());
		}
		Map<String, Double> tagged = new HashMap<>();
		for (Map.Entry<String, JsonElement> tag : session.getAsJsonObject("tags").entrySet()) {
			tagged.put(tag.getKey(), 100.0 * tag.getValue().getAsInt() / spikes);
		}
		tagged.putAll(extraTags);
		JsonObject facts = session.getAsJsonObject("facts");
		double perMinute = spikes / (session.get("gameplaySeconds").getAsDouble() / 60);
		return new StutterFacts(claimed, tagged, facts.get("fullGcs").getAsInt(), facts.get("stalls").getAsInt(), facts.get("explicitGcs").getAsInt(),
				facts.get("liveSetPct").getAsDouble(), null, null, perMinute, session.get("collector").getAsString().toLowerCase(Locale.ROOT), true,
				Set.of(Attributor.RENDER));
	}

	static EvalContext context(StutterFacts facts) {
		return StutterAdvisor.context(RULES, Fixtures.userRig().build(), Fixtures.mods("sodium"), new SettingsSnapshot(Map.of(StutterSeedScenarioTest.DEFER,
				"ONE_FRAME")), Goal.BALANCED, facts);
	}

	static List<String> fired(StutterFacts facts) {
		return StutterAdvisor.evaluate(RULES, context(facts)).stream().map(StutterAdvisor.Fired::id).toList();
	}

	@Test
	void theSeedFiresOnTheTeleportReruns() throws IOException {
		for (String run : List.of("C1r", "C3r", "C1r2")) {
			JsonObject session = session(run);
			StutterFacts facts = recorded(session, Map.of());
			assertTrue(facts.taggedShares().get(Attributor.CHUNKS_LOADING) >= 75, run + ": " + facts.taggedShares());
			assertTrue(fired(facts).contains(SEED), run + ": " + fired(facts));
			assertFalse(session.get("enoughData").getAsBoolean(), run + " is a 52 s capture: the product shows no advice for it");
		}
	}

	// This proves the spike-rate threshold, not the tag share: the control's share is unknown (the run predates the tag), so
	// it is also tried at 75 % (its worst case, 3 of 4 spikes) and 100 %; its 0.68 spikes a minute keep the seed off.
	@Test
	void theSpikeRateKeepsTheStillControlOut() throws IOException {
		JsonObject a = session("A");
		assertTrue(a.get("enoughData").getAsBoolean());
		assertEquals(4, spikes(a));
		for (Map<String, Double> chunksLoading : List.of(Map.<String, Double>of(), Map.of(Attributor.CHUNKS_LOADING, 75.0), Map.of(Attributor.CHUNKS_LOADING, 100.0))) {
			StutterFacts facts = recorded(a, chunksLoading);
			List<String> fired = StutterAdvisor.evaluate(RULES, context(facts), true).stream().map(StutterAdvisor.Fired::id).toList();
			assertFalse(fired.contains(SEED), chunksLoading + ": " + fired);
		}
	}

	// The two thresholds: most spikes tagged (60 %) and at least 2 spikes a minute (x10 in the rules).
	@Test
	void theThresholds() {
		assertTrue(fired(tagged(60, 2.0)).contains(SEED));
		assertFalse(fired(tagged(59, 30.0)).contains(SEED));
		assertFalse(fired(tagged(100, 1.99)).contains(SEED));
		assertFalse(fired(new StutterFacts(Map.of("unknown", 100.0), Map.of(), 0, 0, 0, 40.0, 8192L, null, 30.0, "g1", true, Set.of())).contains(SEED),
				"no spike carried the tag");
	}

	static StutterFacts tagged(double share, double perMinute) {
		return new StutterFacts(Map.of("unknown", 100.0), Map.of(Attributor.CHUNKS_LOADING, share), 0, 0, 0, 40.0, 8192L, null, perMinute, "g1", true, Set.of());
	}

	static final String RD_ONLY = "stutter-chunks-loading-tag-rd";

	static List<String> fired(StutterFacts facts, Map<String, String> settings, String... mods) {
		EvalContext ctx = StutterAdvisor.context(RULES, Fixtures.userRig().build(), Fixtures.mods(mods), new SettingsSnapshot(settings), Goal.BALANCED, facts);
		return StutterAdvisor.evaluate(RULES, ctx).stream().map(StutterAdvisor.Fired::id).toList();
	}

	// #44: Sodium's Chunk Updates is suggested only while it isn't already Deferred (ALWAYS); otherwise, and without
	// Sodium, the render-distance text (with Chunky's pre-generation) fires instead. Never both.
	@Test
	void sodiumIsSuggestedOnlyWhileChunkUpdatesArentDeferred() {
		StutterFacts facts = tagged(80, 30.0);
		for (String mode : List.of("ZERO_FRAMES", "ONE_FRAME")) {
			List<String> ids = fired(facts, Map.of(StutterSeedScenarioTest.DEFER, mode), "sodium");
			assertTrue(ids.contains(SEED) && !ids.contains(RD_ONLY), mode + ": " + ids);
		}
		List<String> deferred = fired(facts, Map.of(StutterSeedScenarioTest.DEFER, "ALWAYS"), "sodium");
		assertTrue(deferred.contains(RD_ONLY) && !deferred.contains(SEED), deferred.toString());
		List<String> noSodium = fired(facts, Map.of());
		assertTrue(noSodium.contains(RD_ONLY) && !noSodium.contains(SEED), noSodium.toString());
		RulesDocument.AdviceRule rdOnly = RULES.stutterAdvice.stream().filter(a -> RD_ONLY.equals(a.id)).findFirst().orElseThrow();
		assertFalse(rdOnly.text.contains("Sodium"), rdOnly.text);
		assertTrue(rdOnly.text.contains("render distance") && rdOnly.text.contains("Chunky"), rdOnly.text);
		assertEquals(List.of(StutterAdvisor.FEATURE), rdOnly.requires);
		assertEquals("info", rdOnly.kind);
	}

	// #48: in singleplayer on fast hardware chunk loading is never measured (packet time), so the tag advice says there's
	// no fix to try; when it is measured, the measured advice (which can offer the fix) shows instead of the tag's.
	@Test
	void theTagAdviceSaysWhyThereIsNoFixAndStandsAsideForTheMeasuredOne() {
		for (String id : List.of(SEED, RD_ONLY)) {
			String text = RULES.stutterAdvice.stream().filter(a -> id.equals(a.id)).findFirst().orElseThrow().text;
			assertTrue(text.contains("RigTune can't measure what loading chunks costs here, so it has no fix to try for it"), text);
		}
		StutterFacts measured = new StutterFacts(Map.of(Attributor.CHUNK_LOAD, 45.0, "unknown", 55.0), Map.of(Attributor.CHUNKS_LOADING, 80.0), 0, 0,
				0, 40.0, 8192L, null, 30.0, "g1", true, Set.of());
		for (Map<String, String> settings : List.of(Map.of(StutterSeedScenarioTest.DEFER, "ONE_FRAME"), Map.of(StutterSeedScenarioTest.DEFER, "ALWAYS"))) {
			List<String> ids = fired(measured, settings, "sodium");
			assertTrue(ids.contains("stutter-chunk-loading") && !ids.contains(SEED) && !ids.contains(RD_ONLY), settings + ": " + ids);
		}
	}

	@Test
	void theSeedIsAnInfoForTheStutterDoctorOnly() {
		RulesDocument.AdviceRule seed = RULES.stutterAdvice.stream().filter(a -> SEED.equals(a.id)).findFirst().orElseThrow();
		assertEquals(List.of(StutterAdvisor.FEATURE), seed.requires);
		assertEquals("info", seed.kind);
		assertTrue(RULES.advice.stream().noneMatch(a -> SEED.equals(a.id)));
		String text = seed.text.toLowerCase(Locale.ROOT);
		assertTrue(text.contains("happened while chunks were loading"), seed.text);
		assertTrue(text.contains("render distance") && text.contains("deferred") && text.contains("may reduce"), seed.text);
	}
}
