package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.stutter.FixSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 5 and C2 (PLAN contracts item 11): the rules model for C20's stutterFixes section and the
// causeSpikesAtLeast condition key. No content: the bundled rules have no section until the rules workstream's R.
class StutterFixesModelTest {
	private static final String GOOD = "{\"adviceId\": \"stutter-sodium-defer\", \"requires\": [\"stutter-fix\"],"
			+ " \"evidence\": {\"stutterShareAtLeast\": {\"chunkBuild\": 40}, \"causeSpikesAtLeast\": {\"chunkBuild\": 5}},"
			+ " \"set\": {\"key\": \"sodium.performance.chunk_build_defer_mode\", \"value\": \"ALWAYS\"}}";

	private static String bundled() throws IOException {
		try (InputStream in = RulesLoader.class.getResourceAsStream(RulesLoader.BUNDLED_RESOURCE)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static RulesDocument withSection(String section) throws IOException {
		JsonObject root = JsonParser.parseString(bundled()).getAsJsonObject();
		root.add("stutterFixes", JsonParser.parseString(section));
		return RulesLoader.parse(root.toString());
	}

	private static Set<String> fields(Class<?> type) {
		return Arrays.stream(type.getFields()).filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
				.map(Field::getName).collect(Collectors.toSet());
	}

	@Test
	void absentIsNull() throws IOException {
		JsonObject root = JsonParser.parseString(bundled()).getAsJsonObject();
		root.remove("stutterFixes");
		assertNull(RulesLoader.parse(root.toString()).stutterFixes);
	}

	@Test
	void anEntryReadsWithItsFields() throws IOException {
		RulesDocument doc = withSection("[" + GOOD + ", {\"adviceId\": \"stutter-chunk-loading\", \"requires\": [\"stutter-fix\"],"
				+ " \"evidence\": {\"always\": true}, \"set\": {\"key\": \"vanilla.renderDistance\", \"step\": -2, \"min\": 6}}]");
		assertEquals(2, doc.stutterFixes.size());
		RulesDocument.StutterFix first = doc.stutterFixes.getFirst();
		assertEquals("stutter-sodium-defer", first.adviceId);
		assertEquals(List.of(FixSpec.FEATURE), first.requires);
		assertTrue(first.evidence.unknownFields == null || first.evidence.unknownFields.isEmpty(), "causeSpikesAtLeast is known");
		assertEquals("sodium.performance.chunk_build_defer_mode", first.set.key);
		assertEquals("ALWAYS", first.set.value.getAsString());
		assertEquals(-2, doc.stutterFixes.get(1).set.step.getAsInt());
		assertEquals(6, doc.stutterFixes.get(1).set.min.getAsInt());
	}

	@Test
	void aMalformedEntryDropsOnlyItself() throws IOException {
		RulesDocument doc = withSection("[{\"adviceId\": {\"not\": \"a string\"}}, " + GOOD + ", null, {\"requires\": \"not a list\"},"
				+ " {\"evidence\": 7}, {\"set\": {\"step\": {\"deep\": []}, \"key\": \"k\"}}]");
		assertEquals(2, doc.stutterFixes.size(), "the unreadable entries and the null are gone");
		assertEquals("stutter-sodium-defer", doc.stutterFixes.getFirst().adviceId);
		assertTrue(doc.stutterFixes.get(1).set.step.isJsonObject(), "a number of the wrong shape stays a JsonElement, for FixSpec to refuse");
	}

	@Test
	void aSectionThatIsNotAnArrayIsNullAndCostsNoAdvice() throws IOException {
		RulesDocument plain = RulesLoader.parse(bundled());
		for (String section : List.of("\"a string\"", "42", "{\"odd\": true}", "null")) {
			RulesDocument doc = withSection(section);
			assertNull(doc.stutterFixes, section);
			assertEquals(plain.advice.size(), doc.advice.size(), section);
			assertNotNull(doc.stutterAdvice, section);
			assertEquals(plain.stutterAdvice.size(), doc.stutterAdvice.size(), section);
		}
	}

	@Test
	void causeSpikesAtLeastIsAStutterKeyAndEvaluatedAgainstTheFacts() {
		assertTrue(ConditionEvaluator.hasStutterKey(RulesLoader.condition("{\"causeSpikesAtLeast\": {\"gc\": 1}}")));
		// WS-S2 filled the contracts stub (it answered UNKNOWN): facts without dominated spikes count 0 for a measured cause.
		assertEquals(FALSE, StutterConditionTest.eval("{\"causeSpikesAtLeast\": {\"gc\": 1}}"), "evaluated (StutterConditionTest.causeSpikes)");
		assertEquals(UNKNOWN, StutterConditionTest.eval("{\"causeSpikesAtLeast\": {\"gc\": 1}}", null), "no facts (the main list)");
		assertEquals(UNKNOWN, StutterConditionTest.eval("{\"not\": {\"causeSpikesAtLeast\": {\"shaderCompile\": 1}}}"),
				"a not over an unknown cause is never TRUE");
		assertEquals(FALSE, StutterConditionTest.eval("{\"causeSpikesAtLeast\": {\"gc\": 1}, \"stutterShareAtLeast\": {\"gc\": 99}}"),
				"a FALSE elsewhere still decides");
	}

	@Test
	void fixSpecNamesTheModelsFields() {
		assertEquals(FixSpec.FIELDS, fields(RulesDocument.StutterFix.class));
		assertEquals(FixSpec.SET_FIELDS, fields(RulesDocument.FixSet.class));
		assertEquals("stutter-fix", FixSpec.FEATURE);
		assertEquals(Set.of("vanilla.renderDistance", "sodium.performance.chunk_build_defer_mode", "dh.common.multiThreading.numberOfThreads"),
				FixSpec.KEYS);
		assertFalse(Recommender.SUPPORTED_FEATURES.contains(FixSpec.FEATURE), "the main list never knows the fix feature");
	}
}
