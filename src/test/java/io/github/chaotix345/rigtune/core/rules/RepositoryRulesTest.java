package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The generated rules files in the repository (SPEC item 2, "CI and tests").
class RepositoryRulesTest {
	private static String read(String relative) throws IOException {
		return Files.readString(RepoFiles.resolve(relative), StandardCharsets.UTF_8);
	}

	private static RulesDocument v1() throws IOException {
		return RulesLoader.parse(read("rules/rules-v1.json"));
	}

	private static RulesDocument v2() throws IOException {
		return RulesLoader.parse(read("rules/rules-v2.json"));
	}

	@Test
	void repoRulesV1AndV2ParseWithOneRevision() throws IOException {
		RulesDocument v1 = v1();
		RulesDocument v2 = v2();
		assertEquals(1, v1.schemaVersion);
		assertEquals(2, v2.schemaVersion);
		assertEquals(v2.revision, v1.revision);
		assertEquals(v2.generatedAt, v1.generatedAt);
	}

	@Test
	void everySettingKeyIsAllowlisted() throws IOException {
		for (RulesDocument.SettingRule rule : v2().settings) {
			assertTrue(SettingKeys.changeable(rule.key), rule.key);
		}
		// rules-v1.json is applied by 0.1.x, so its keys must pass 0.1.0's own allowlist.
		for (RulesDocument.SettingRule rule : v1().settings) {
			assertTrue(io.github.chaotix345.rigtune.v010.core.model.SettingKeys.changeable(rule.key), rule.key);
		}
	}

	@Test
	void everyConditionIsUnderstood() throws IOException {
		for (RulesDocument doc : List.of(v1(), v2())) {
			for (Condition condition : conditions(doc)) {
				assertTrue(!ConditionEvaluator.poisoned(condition), "unknown condition key in revision " + doc.revision);
			}
		}
	}

	@Test
	void bundledV2EqualsRepoV2() throws IOException {
		try (InputStream in = RulesLoader.class.getResourceAsStream(RulesLoader.BUNDLED_RESOURCE)) {
			assertNotNull(in, RulesLoader.BUNDLED_RESOURCE);
			String bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(read("rules/rules-v2.json").replace("\r\n", "\n"), bundled.replace("\r\n", "\n"));
		}
		assertEquals("/rigtune/rules-v2.json", RulesLoader.BUNDLED_RESOURCE);
	}

	@Test
	void v1IsNotBundled() {
		assertNull(RulesLoader.class.getResource("/rigtune/rules-v1.json"));
	}

	@Test
	void v1HasNoV2Features() throws IOException {
		RulesDocument v1 = v1();
		assertTrue(v1.settingLabels.isEmpty());
		v1.mods.forEach(r -> {
			assertNull(r.requires, r.slug);
			assertNull(r.avoidSelected, r.slug);
			assertNull(r.skipUpdateWhen, r.slug);
		});
		v1.obsolete.forEach(r -> assertNull(r.requires, r.title));
		v1.settings.forEach(r -> assertNull(r.requires, r.key));
		v1.advice.forEach(r -> assertNull(r.requires, r.id));
		for (Condition condition : conditions(v1)) {
			assertTrue(v2FieldsAbsent(condition));
		}
	}

	private static boolean v2FieldsAbsent(Condition c) {
		if (c.gpuModelMatches != null || c.displayPixelsAtLeast != null || c.displayPixelsAtMost != null
				|| c.modVersion != null || c.mcVersionRange != null || c.settingIs != null) {
			return false;
		}
		return (c.not == null || v2FieldsAbsent(c.not)) && (c.anyOf == null || c.anyOf.stream().allMatch(RepositoryRulesTest::v2FieldsAbsent));
	}

	private static List<Condition> conditions(RulesDocument doc) {
		List<Condition> out = new ArrayList<>();
		doc.mods.forEach(r -> {
			out.add(r.recommendWhen);
			out.add(r.avoidWhen);
			out.add(r.skipUpdateWhen);
		});
		doc.settings.forEach(r -> out.add(r.when));
		doc.advice.forEach(r -> out.add(r.when));
		out.removeIf(c -> c == null);
		return out;
	}
}
