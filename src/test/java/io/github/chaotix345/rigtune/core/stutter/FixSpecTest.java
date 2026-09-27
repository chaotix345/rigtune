package io.github.chaotix345.rigtune.core.stutter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 5: the client's own check of the rules' stutterFixes entries (the updater validates them too, WS-R). A
// bad entry drops only itself; no entry can name a key outside the allowlist (AC5.15).
class FixSpecTest {
	static final String SODIUM = "{\"adviceId\": \"stutter-sodium-defer\", \"requires\": [\"stutter-fix\"],"
			+ " \"evidence\": {\"stutterShareAtLeast\": {\"chunkBuild\": 40}, \"causeSpikesAtLeast\": {\"chunkBuild\": 5}},"
			+ " \"set\": {\"key\": \"sodium.performance.chunk_build_defer_mode\", \"value\": \"ALWAYS\"}}";
	static final String CHUNKS = "{\"adviceId\": \"stutter-chunk-loading\", \"requires\": [\"stutter-fix\"],"
			+ " \"evidence\": {\"stutterShareAtLeast\": {\"chunkLoad\": 40}, \"causeSpikesAtLeast\": {\"chunkLoad\": 5}},"
			+ " \"set\": {\"key\": \"vanilla.renderDistance\", \"step\": -2, \"min\": 6}}";
	static final String DH = "{\"adviceId\": \"stutter-dh-threads\", \"requires\": [\"stutter-fix\"],"
			+ " \"evidence\": {\"stutterTaggedShareAtLeast\": {\"dh\": 60}, \"cpuContentionShareAtLeast\": 50, \"spikesPerMinuteAtLeast\": 20},"
			+ " \"set\": {\"key\": \"dh.common.multiThreading.numberOfThreads\", \"step\": -2, \"min\": 1}}";

	private static String bundled() throws IOException {
		try (InputStream in = RulesLoader.class.getResourceAsStream(RulesLoader.BUNDLED_RESOURCE)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// The bundled rules (their stutterAdvice ids) with this stutterFixes section.
	static RulesDocument rules(String... entries) throws IOException {
		JsonObject root = JsonParser.parseString(bundled()).getAsJsonObject();
		root.add("stutterFixes", JsonParser.parseString("[" + String.join(",", entries) + "]"));
		return RulesLoader.parse(root.toString());
	}

	static List<FixSpec> specs(String... entries) throws IOException {
		return FixSpec.of(rules(entries));
	}

	// One entry with its set object replaced.
	private static String withSet(String set) {
		return "{\"adviceId\": \"stutter-chunk-loading\", \"requires\": [\"stutter-fix\"], \"evidence\": {\"always\": true}, \"set\": " + set + "}";
	}

	private static void refused(String entry) throws IOException {
		assertEquals(List.of(), specs(entry), entry);
		// ... and only that entry: a good one next to it stays.
		assertEquals(List.of("stutter-sodium-defer"), specs(entry, SODIUM).stream().map(FixSpec::adviceId).toList(), entry);
	}

	@Test
	void theSeedsParse() throws IOException {
		List<FixSpec> specs = specs(SODIUM, CHUNKS, DH);
		assertEquals(List.of("stutter-sodium-defer", "stutter-chunk-loading", "stutter-dh-threads"), specs.stream().map(FixSpec::adviceId).toList());
		FixSpec sodium = specs.getFirst();
		assertEquals("sodium.performance.chunk_build_defer_mode", sodium.key());
		assertEquals("ALWAYS", sodium.value());
		assertNotNull(sodium.evidence().causeSpikesAtLeast);
		assertFalse(sodium.now());
		FixSpec chunks = specs.get(1);
		assertEquals("vanilla.renderDistance", chunks.key());
		assertNull(chunks.value());
		assertEquals(-2, chunks.step());
		assertEquals(6, chunks.min());
		assertTrue(chunks.now());
		assertEquals(1, specs.get(2).min());
		assertFalse(specs.get(2).now());
	}

	@Test
	void noSectionNoFixes() throws IOException {
		assertEquals(List.of(), FixSpec.of(null));
		assertEquals(List.of(), FixSpec.of(RulesLoader.parse(bundled())));
	}

	@Test
	void requiresMustNameStutterFixAndNothingUnknown() throws IOException {
		refused(SODIUM.replace("[\"stutter-fix\"]", "[\"stutter-fix\", \"stutter-fix-v2\"]"));
		refused(SODIUM.replace("[\"stutter-fix\"]", "[\"stutter-doctor\"]"));
		refused(SODIUM.replace("[\"stutter-fix\"]", "[]"));
		refused(SODIUM.replace("\"requires\": [\"stutter-fix\"],", ""));
	}

	@Test
	void theAdviceIdMustBeAnExistingStutterAdviceOnce() throws IOException {
		refused(SODIUM.replace("stutter-sodium-defer", "stutter-made-up"));
		refused(SODIUM.replace("\"adviceId\": \"stutter-sodium-defer\",", ""));
		// A main-list advice id isn't a stutterAdvice id.
		refused(SODIUM.replace("stutter-sodium-defer", "ram-low"));
		// The first entry for an advice wins.
		List<FixSpec> twice = specs(SODIUM, SODIUM.replace("\"ALWAYS\"", "\"ONE_FRAME\""));
		assertEquals(1, twice.size());
		assertEquals("ALWAYS", twice.getFirst().value());
	}

	@Test
	void evidenceIsRequired() throws IOException {
		refused(CHUNKS.replace("\"evidence\": {\"stutterShareAtLeast\": {\"chunkLoad\": 40}, \"causeSpikesAtLeast\": {\"chunkLoad\": 5}},", ""));
	}

	// AC5.15: the allowlist, whatever the key.
	@Test
	void aKeyOutsideTheAllowlistIsRefused() throws IOException {
		refused(withSet("{\"key\": \"vanilla.maxFps\", \"value\": \"60\"}"));
		refused(withSet("{\"key\": \"sodium.performance.chunk_builder_threads\", \"step\": -2, \"min\": 1}"));
		refused(withSet("{\"key\": \"vanilla.graphicsPreset\", \"value\": \"fast\"}"));
		refused(withSet("{\"key\": \"mods/sodium.jar\", \"value\": \"disabled\"}"));
		refused(withSet("{\"value\": \"ALWAYS\"}"));
		refused("{\"adviceId\": \"stutter-chunk-loading\", \"requires\": [\"stutter-fix\"], \"evidence\": {\"always\": true}}");
	}

	@Test
	void valueXorStep() throws IOException {
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"value\": \"10\", \"step\": -2, \"min\": 6}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\"}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"value\": null, \"step\": null}"));
	}

	@Test
	void aValueMustDecodeInItsTableEntry() throws IOException {
		refused(withSet("{\"key\": \"sodium.performance.chunk_build_defer_mode\", \"value\": \"NEVER\"}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"value\": \"40\"}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"value\": {\"n\": 10}}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"value\": [10]}"));
		// The table's own spelling is kept, whatever the rule wrote.
		FixSpec lower = specs(withSet("{\"key\": \"sodium.performance.chunk_build_defer_mode\", \"value\": \"always\"}")).getFirst();
		assertEquals("ALWAYS", lower.value());
		FixSpec number = specs(withSet("{\"key\": \"vanilla.renderDistance\", \"value\": 10}")).getFirst();
		assertEquals("10", number.value());
	}

	@Test
	void aStepIsAWholeNumberUpToEightWithItsBoundOnAnIntKey() throws IOException {
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": 0, \"min\": 6}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": -9, \"min\": 6}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": 9, \"max\": 16}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": -2}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": 2, \"min\": 6}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": -1.5, \"min\": 6}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": \"-2\", \"min\": 6}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": -2, \"min\": \"6\"}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": -2, \"min\": 6.5}"));
		refused(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": -2, \"min\": 6, \"max\": {\"x\": 1}}"));
		refused(withSet("{\"key\": \"sodium.performance.chunk_build_defer_mode\", \"step\": 1, \"max\": 2}"));
		assertEquals(8, specs(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": 8, \"max\": 16}")).getFirst().step());
	}

	@Test
	void targets() throws IOException {
		List<FixSpec> specs = specs(SODIUM, CHUNKS, DH);
		FixSpec sodium = specs.get(0);
		FixSpec rd = specs.get(1);
		FixSpec dh = specs.get(2);
		assertEquals("ALWAYS", sodium.target("ZERO_FRAMES"));
		assertEquals("ALWAYS", sodium.target("ONE_FRAME"));
		assertNull(sodium.target("ALWAYS"));
		assertNull(sodium.target("always"));
		assertEquals("10", rd.target("12"));
		assertEquals("6", rd.target("7"));
		assertNull(rd.target("6"));
		// Already below the rule's floor: the step never raises it.
		assertNull(rd.target("4"));
		// Out of the table's range: clamped into it.
		assertEquals("32", rd.target("40"));
		assertEquals("10", rd.target("12.0"));
		assertNull(rd.target("far"));
		assertNull(rd.target(null));
		assertEquals("2", dh.target("4"));
		assertEquals("1", dh.target("2"));
		assertNull(dh.target("1"));
		FixSpec up = specs(withSet("{\"key\": \"vanilla.renderDistance\", \"step\": 4, \"max\": 16}")).getFirst();
		assertEquals("16", up.target("14"));
		assertNull(up.target("16"));
	}

	@Test
	void theConstantsStayTheContracts() {
		assertEquals("stutter-fix", FixSpec.FEATURE);
		assertEquals(3, FixSpec.KEYS.size());
		assertEquals(java.util.Set.of(FixSpec.FEATURE), FixOffers.SUPPORTED_FEATURES);
	}
}
