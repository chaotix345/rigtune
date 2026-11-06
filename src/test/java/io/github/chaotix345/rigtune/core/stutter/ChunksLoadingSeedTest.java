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

	static final String SODIUM = "stutter-chunks-loading-tag-sodium";
	static final Set<String> NO_PHASE_TIMING = Set.of(Attributor.RENDER, Attributor.CHUNK_LOAD, Attributor.CHUNK_BUILD, Attributor.TICK);

	static List<String> fired(StutterFacts facts, Map<String, String> settings, String... mods) {
		EvalContext ctx = StutterAdvisor.context(RULES, Fixtures.userRig().build(), Fixtures.mods(mods), new SettingsSnapshot(settings), Goal.BALANCED, facts);
		return StutterAdvisor.evaluate(RULES, ctx).stream().map(StutterAdvisor.Fired::id).toList();
	}

	static StutterFacts tagged(double share, double perMinute, Map<String, Double> causes, Set<String> unmeasured) {
		return new StutterFacts(causes, Map.of(Attributor.CHUNKS_LOADING, share), 0, 0, 0, 40.0, 8192L, null, perMinute, "g1", true, unmeasured);
	}

	static String text(String id) {
		return RULES.stutterAdvice.stream().filter(a -> id.equals(a.id)).findFirst().orElseThrow().text;
	}

	// #44: the tag advice no longer names Sodium; a second entry suggests Deferred only while Chunk Updates is known to be
	// Immediate or Soon, so it never fires when it's already Deferred (ALWAYS) or Sodium isn't there.
	@Test
	void sodiumIsSuggestedOnlyWhileChunkUpdatesArentDeferred() {
		StutterFacts facts = tagged(80, 30.0);
		for (String mode : List.of("ZERO_FRAMES", "ONE_FRAME")) {
			List<String> ids = fired(facts, Map.of(StutterSeedScenarioTest.DEFER, mode), "sodium");
			assertTrue(ids.contains(SEED) && ids.contains(SODIUM), mode + ": " + ids);
		}
		List<String> deferred = fired(facts, Map.of(StutterSeedScenarioTest.DEFER, "ALWAYS"), "sodium");
		assertTrue(deferred.contains(SEED) && !deferred.contains(SODIUM), deferred.toString());
		List<String> noSodium = fired(facts, Map.of());
		assertTrue(noSodium.contains(SEED) && !noSodium.contains(SODIUM), noSodium.toString());
		assertFalse(text(SEED).contains("Sodium"), text(SEED));
		assertTrue(text(SEED).contains("render distance") && text(SEED).contains("Chunky"), text(SEED));
		assertTrue(text(SODIUM).contains("Deferred") && text(SODIUM).contains("may reduce them"), text(SODIUM));
		RulesDocument.AdviceRule sodium = RULES.stutterAdvice.stream().filter(a -> SODIUM.equals(a.id)).findFirst().orElseThrow();
		assertEquals(List.of(StutterAdvisor.FEATURE), sodium.requires);
		assertEquals("info", sodium.kind);
	}

	// PR #60 review: a session without phase timing (chunkLoad unmeasured: UNKNOWN) or with Sodium's setting missing
	// from the snapshot still gets the tag advice, as under r21; the Sodium entry needs the setting known.
	@Test
	void unmeasuredPhasesOrAMissingSodiumSettingStillGetTheAdvice() {
		StutterFacts noPhases = tagged(80, 30.0, Map.of(Attributor.UNKNOWN, 100.0), NO_PHASE_TIMING);
		for (Map<String, String> settings : List.of(Map.of(StutterSeedScenarioTest.DEFER, "ONE_FRAME"), Map.of(StutterSeedScenarioTest.DEFER, "ALWAYS"),
				Map.<String, String>of())) {
			List<String> ids = fired(noPhases, settings, "sodium");
			assertTrue(ids.contains(SEED), settings + ": " + ids);
			assertEquals(settings.containsValue("ONE_FRAME"), ids.contains(SODIUM), settings + ": " + ids);
		}
		assertTrue(fired(noPhases, Map.of()).contains(SEED), "no Sodium, no phase timing");
		List<String> missing = fired(tagged(80, 30.0), Map.of(), "sodium");
		assertTrue(missing.contains(SEED) && !missing.contains(SODIUM), "Sodium without its setting in the snapshot: " + missing);
	}

	// #48: in singleplayer on fast hardware chunk loading is never measured (packet time), so no fix is offered; the tag
	// advice says when one is. Its sentence holds next to the measured advice too, which may offer the fix.
	@Test
	void theTagAdviceSaysWhenAFixIsOffered() {
		assertTrue(text(SEED).contains("RigTune offers a fix to try only when it could measure what loading chunks cost (\"Stutter while loading chunks\")"),
				text(SEED));
		StutterFacts measured = tagged(80, 30.0, Map.of(Attributor.CHUNK_LOAD, 45.0, Attributor.UNKNOWN, 55.0), Set.of());
		List<String> ids = fired(measured, Map.of(StutterSeedScenarioTest.DEFER, "ALWAYS"), "sodium");
		assertTrue(ids.contains("stutter-chunk-loading") && ids.contains(SEED), ids.toString());
	}

	@Test
	void theSeedIsAnInfoForTheStutterDoctorOnly() {
		RulesDocument.AdviceRule seed = RULES.stutterAdvice.stream().filter(a -> SEED.equals(a.id)).findFirst().orElseThrow();
		assertEquals(List.of(StutterAdvisor.FEATURE), seed.requires);
		assertEquals("info", seed.kind);
		assertTrue(RULES.advice.stream().noneMatch(a -> SEED.equals(a.id)));
		String text = seed.text.toLowerCase(Locale.ROOT);
		assertTrue(text.contains("happened while chunks were loading"), seed.text);
		assertTrue(text.contains("render distance") && text.contains("may reduce"), seed.text);
	}
}
