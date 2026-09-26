package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * docs/v0.4/SPEC.md "Compatibility promise" (b), AC2k.2, AC4.10, AC5.6: 0.2.0 and 0.3.0 read the regenerated bundled
 * rules-v2.json as before. Their rules parser is pinned once (v030/core/rules, byte-identical in v0.2.0 and v0.3.0): it
 * ignores profileTemplates and stutterAdvice and gets the same counts as with both sections removed.
 */
class LegacyRulesParseTest {
	private static final String VSYNC = "vanilla.enableVsync";

	static String bundledJson() throws IOException {
		try (InputStream in = RulesLoader.class.getResourceAsStream(RulesLoader.BUNDLED_RESOURCE)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static io.github.chaotix345.rigtune.v030.core.rules.RulesDocument legacy(String json) {
		return io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(json);
	}

	@Test
	void theBundledRulesCarryBothSections() {
		RulesDocument rules = RulesLoader.loadBundled();
		assertEquals(List.of("max_fps", "balanced", "quality", "battery", "recording"),
				rules.profileTemplates.templates.stream().map(t -> t.id).toList());
		assertEquals(List.of("ram-stutter-gc-heap", "stutter-gc-explicit", "stutter-sodium-defer", "stutter-dh-threads", "stutter-chunk-loading"),
				rules.stutterAdvice.stream().map(a -> a.id).toList());
		rules.stutterAdvice.forEach(a -> assertEquals(List.of("stutter-doctor"), a.requires, a.id));
	}

	@Test
	void legacyParserIgnoresTheNewSections() throws IOException {
		String json = bundledJson();
		JsonObject stripped = JsonParser.parseString(json).getAsJsonObject();
		assertNotNull(stripped.remove("profileTemplates"));
		assertNotNull(stripped.remove("stutterAdvice"));

		var withSections = legacy(json);
		var without = legacy(stripped.toString());
		RulesDocument current = RulesLoader.parse(json);
		for (var doc : List.of(withSections, without)) {
			assertEquals(current.revision, doc.revision);
			assertEquals(current.mods.size(), doc.mods.size());
			assertEquals(current.obsolete.size(), doc.obsolete.size());
			assertEquals(current.settings.size(), doc.settings.size());
			assertEquals(current.advice.size(), doc.advice.size());
			assertEquals(current.gpuTiers.size(), doc.gpuTiers.size());
			assertEquals(current.cpuTiers.size(), doc.cpuTiers.size());
			assertEquals(current.heapTiers.size(), doc.heapTiers.size());
			assertEquals(current.settingLabels.keySet(), doc.settingLabels.keySet());
			assertEquals(current.availability, doc.availability);
		}
		assertEquals(current.advice.stream().map(a -> a.id).toList(), withSections.advice.stream().map(a -> a.id).toList());
		assertEquals(current.mods.stream().map(m -> m.slug).toList(), withSections.mods.stream().map(m -> m.slug).toList());
		assertEquals(current.settings.stream().map(s -> s.key).toList(), withSections.settings.stream().map(s -> s.key).toList());
		Set<String> legacyFields = Arrays.stream(io.github.chaotix345.rigtune.v030.core.rules.RulesDocument.class.getFields())
				.map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
		assertFalse(legacyFields.contains("profileTemplates") || legacyFields.contains("stutterAdvice"), legacyFields.toString());
	}

	// AC2k.2: the VSync-off entry reaches 0.2.0/0.3.0 unticked (and 0.4 too).
	@Test
	void legacyParserReadsTheVsyncEntryUnticked() throws IOException {
		var legacyEntry = legacy(bundledJson()).settings.stream()
				.filter(s -> s.key.equals(VSYNC) && s.value != null && !s.value.getAsBoolean() && s.when != null && s.when.refreshRateAtLeast != null)
				.toList();
		assertEquals(1, legacyEntry.size());
		assertEquals(Boolean.FALSE, legacyEntry.getFirst().defaultSelected);
		assertEquals("Optional: turning VSync off lowers input lag but can cause tearing; leave it on if you see tearing.",
				legacyEntry.getFirst().reason);

		var currentEntry = RulesLoader.loadBundled().settings.stream()
				.filter(s -> s.key.equals(VSYNC) && s.isValueEntry() && !s.value.getAsBoolean() && s.when.refreshRateAtLeast != null).toList();
		assertEquals(1, currentEntry.size());
		assertEquals(Boolean.FALSE, currentEntry.getFirst().defaultSelected);
	}
}
