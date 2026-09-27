package io.github.chaotix345.rigtune.v040.core.rules;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md "Compatibility promise" (0.4.x), AC5.2: 0.4.0's own parser (the pinned copies next to this test,
// parsing the way 0.4.0's RulesLoader.parse does) reads the bundled rules-v2.json with the current parser's advice,
// settings and stutterAdvice counts, and a stutterFixes section in any JSON shape changes nothing
// (feature-stutter-fixes.md §1.4). WS-R extends it for the release revision R.
class LegacyParserTest {
	private static RulesDocument parse040(String json) {
		RulesDocument doc = new GsonBuilder().registerTypeAdapterFactory(new ConditionAdapterFactory()).create().fromJson(json, RulesDocument.class);
		doc.fillDefaults();
		return doc;
	}

	// docs/v0.5/SPEC.md "Compatibility promise" (0.4.x), AC2S.4, AC5.2: what the release revision R adds over r16 for 0.4.0.
	// (The same list as LegacyRulesParseTest's.)
	static final List<String> ADVICE_ADDED_SINCE_R16 = List.of();
	static final List<String> STUTTER_ADVICE_ADDED_SINCE_R16 = List.of("stutter-chunks-loading-tag");

	private static String bundled() throws IOException {
		try (InputStream in = RulesLoader.class.getResourceAsStream(RulesLoader.BUNDLED_RESOURCE)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String r16() throws IOException {
		try (InputStream in = LegacyParserTest.class.getResourceAsStream("/rules/r16/rules-v2.json")) {
			assertNotNull(in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static List<String> plus(List<String> base, List<String> added) {
		List<String> out = new java.util.ArrayList<>(base);
		out.addAll(added);
		return out;
	}

	private static void assertSameCounts(io.github.chaotix345.rigtune.core.rules.RulesDocument current, RulesDocument old, String what) {
		assertEquals(current.revision, old.revision, what);
		assertEquals(current.advice.size(), old.advice.size(), what);
		assertEquals(current.settings.size(), old.settings.size(), what);
		assertEquals(current.mods.size(), old.mods.size(), what);
		assertNotNull(old.stutterAdvice, what);
		assertEquals(current.stutterAdvice.size(), old.stutterAdvice.size(), what);
		assertEquals(current.profileTemplates.templates.size(), old.profileTemplates.templates.size(), what);
		assertTrue(old.advice.stream().allMatch(a -> a.when == null || a.when.unknownFields == null || a.when.unknownFields.isEmpty()), what);
		assertTrue(old.stutterAdvice.stream().allMatch(a -> a.when == null || a.when.unknownFields == null || a.when.unknownFields.isEmpty()), what);
	}

	@Test
	void theReleasedParserReadsTheBundledRulesWithTheSameCounts() throws IOException {
		String json = bundled();
		assertSameCounts(RulesLoader.parse(json), parse040(json), "bundled rules-v2.json");
	}

	// R against r16 through 0.4.0's parser: r16's settings, advice and stutterAdvice in r16's order plus only the listed
	// additions, no section dropped, and every condition readable (an unknown key would poison it on 0.4.0).
	@Test
	void theReleasedParserReadsRWithR16sCountsPlusTheAdditions() throws IOException {
		RulesDocument r = parse040(bundled());
		RulesDocument r16 = parse040(r16());
		assertEquals(r16.settings.stream().map(s -> s.key).toList(), r.settings.stream().map(s -> s.key).toList());
		assertEquals(plus(r16.advice.stream().map(a -> a.id).toList(), ADVICE_ADDED_SINCE_R16), r.advice.stream().map(a -> a.id).toList());
		assertNotNull(r.stutterAdvice);
		assertEquals(plus(r16.stutterAdvice.stream().map(a -> a.id).toList(), STUTTER_ADVICE_ADDED_SINCE_R16),
				r.stutterAdvice.stream().map(a -> a.id).toList());
		assertNotNull(r.profileTemplates);
		assertEquals(r16.profileTemplates.templates.size(), r.profileTemplates.templates.size());
		assertTrue(r.stutterAdvice.stream().allMatch(a -> a.when == null || a.when.unknownFields == null || a.when.unknownFields.isEmpty()));
		assertTrue(r.advice.stream().allMatch(a -> a.when == null || a.when.unknownFields == null || a.when.unknownFields.isEmpty()));
	}

	@Test
	void aStutterFixesSectionInAnyShapeChangesNothing() throws IOException {
		String json = bundled();
		io.github.chaotix345.rigtune.core.rules.RulesDocument current = RulesLoader.parse(json);
		for (String section : List.of(
				"[{\"adviceId\": \"stutter-sodium-defer\", \"requires\": [\"stutter-fix\"], \"evidence\": {\"causeSpikesAtLeast\": {\"chunkBuild\": 5}},"
						+ " \"set\": {\"key\": \"sodium.performance.chunk_build_defer_mode\", \"value\": \"ALWAYS\"}}]",
				"\"a string\"", "null", "42", "{\"odd\": true}")) {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			root.add("stutterFixes", JsonParser.parseString(section));
			assertSameCounts(current, parse040(root.toString()), section);
		}
	}
}
