package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.model.SettingKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesLoaderTest {
	private static RulesDocument doc(int revision) {
		return RulesLoader.parse("{\"schemaVersion\":1,\"revision\":" + revision + "}");
	}

	@Test
	void parsesMinimalDocumentWithEmptyLists() {
		RulesDocument doc = doc(3);
		assertEquals(3, doc.revision);
		assertTrue(doc.mods.isEmpty());
		assertTrue(doc.settings.isEmpty());
		assertTrue(doc.availability.isEmpty());
	}

	@Test
	void acceptsSchemaVersions1And2() {
		assertEquals(1, RulesLoader.parse("{\"schemaVersion\":1,\"revision\":9}").schemaVersion);
		assertEquals(2, RulesLoader.parse("{\"schemaVersion\":2,\"revision\":9}").schemaVersion);
	}

	@Test
	void rejectsOtherSchemaVersions() {
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse("{\"schemaVersion\":0,\"revision\":9}"));
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse("{\"schemaVersion\":3,\"revision\":9}"));
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse("{\"revision\":9}"));
	}

	@Test
	void rejectsInvalidDocuments() {
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse("{\"revision\":9}"));
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse("not json at all {"));
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse(""));
		assertThrows(IllegalArgumentException.class, () -> RulesLoader.parse("{\"schemaVersion\":1,\"mods\":\"oops\"}"));
	}

	@Test
	void skipsInvalidRegexes() {
		RulesDocument doc = RulesLoader.parse("""
				{"schemaVersion":1,"revision":1,
				 "gpuTiers":[{"pattern":"(?i)[unclosed","tier":3},{"pattern":"(?i)rtx","tier":4}],
				 "cpuTiers":[{"pattern":"*bad","tier":1},{"pattern":"(?i)ryzen","tier":4}]}
				""");
		assertEquals(1, doc.gpuTiers.size());
		assertEquals("(?i)rtx", doc.gpuTiers.get(0).pattern);
		assertEquals(1, doc.cpuTiers.size());
	}

	@Test
	void ignoresAnOversizedCache(@TempDir Path dir) throws IOException {
		Path cache = dir.resolve("rules-cache.json");
		Files.writeString(cache, "{\"schemaVersion\":1,\"revision\":9}" + " ".repeat((int) RulesLoader.MAX_RULES_BYTES));
		assertFalse(RulesLoader.loadCache(cache).isPresent());
		Files.writeString(cache, "{\"schemaVersion\":1,\"revision\":9}");
		assertEquals(9, RulesLoader.loadCache(cache).orElseThrow().revision);
	}

	@Test
	void pickNewestTakesHighestRevisionAndRecordsSource() {
		RulesDocument bundled = doc(1);
		RulesDocument cache = doc(3);
		RulesDocument remote = doc(2);
		Optional<RulesDocument> picked = RulesLoader.pickNewest(List.of(
				new RulesLoader.Candidate(RulesLoader.SOURCE_BUNDLED, bundled),
				new RulesLoader.Candidate(RulesLoader.SOURCE_CACHE, cache),
				new RulesLoader.Candidate(RulesLoader.SOURCE_REMOTE, remote)));
		assertSame(cache, picked.orElseThrow());
		assertEquals("cache", cache.source());
	}

	private static RulesDocument doc(int schemaVersion, int revision) {
		return RulesLoader.parse("{\"schemaVersion\":" + schemaVersion + ",\"revision\":" + revision + "}");
	}

	private static RulesLoader.Candidate candidate(String source, RulesDocument doc) {
		return new RulesLoader.Candidate(source, doc);
	}

	@Test
	void pickNewestPrefersRemoteThenCacheThenBundledOnFullTiesAndSkipsMissing() {
		RulesDocument bundled = doc(2);
		RulesDocument cache = doc(2);
		RulesDocument remote = doc(2);
		for (List<RulesLoader.Candidate> order : List.of(
				List.of(candidate(RulesLoader.SOURCE_BUNDLED, bundled), candidate(RulesLoader.SOURCE_CACHE, cache), candidate(RulesLoader.SOURCE_REMOTE, remote)),
				List.of(candidate(RulesLoader.SOURCE_REMOTE, remote), candidate(RulesLoader.SOURCE_BUNDLED, bundled), candidate(RulesLoader.SOURCE_CACHE, cache)))) {
			assertSame(remote, RulesLoader.pickNewest(order).orElseThrow());
		}
		RulesDocument picked = RulesLoader.pickNewest(List.of(candidate(RulesLoader.SOURCE_CACHE, cache),
				candidate(RulesLoader.SOURCE_BUNDLED, bundled), candidate(RulesLoader.SOURCE_REMOTE, null))).orElseThrow();
		assertSame(cache, picked);
		assertEquals("cache", picked.source());
		assertFalse(RulesLoader.pickNewest(List.of()).isPresent());
	}

	@Test
	void pickNewestToleratesANullSource() {
		RulesDocument unnamed = doc(2, 5);
		RulesDocument bundled = doc(2, 5);
		assertSame(bundled, RulesLoader.pickNewest(List.of(candidate(null, unnamed), candidate(RulesLoader.SOURCE_BUNDLED, bundled))).orElseThrow());
		assertSame(unnamed, RulesLoader.pickNewest(List.of(candidate(null, unnamed))).orElseThrow());
	}

	@Test
	void newerComparesRevisionThenSchemaVersionOnly() {
		assertTrue(RulesLoader.newer(doc(1, 6), doc(2, 5)));
		assertTrue(RulesLoader.newer(doc(2, 5), doc(1, 5)));
		assertFalse(RulesLoader.newer(doc(2, 5), doc(2, 5)));
		assertFalse(RulesLoader.newer(doc(1, 5), doc(2, 5)));
		assertTrue(RulesLoader.newer(doc(1, 1), null));
	}

	@Test
	void pickNewestPrefersV2OnARevisionTie() {
		RulesDocument remoteV1 = doc(1, 5);
		RulesDocument bundledV2 = doc(2, 5);
		RulesDocument picked = RulesLoader.pickNewest(List.of(candidate(RulesLoader.SOURCE_REMOTE, remoteV1),
				candidate(RulesLoader.SOURCE_BUNDLED, bundledV2))).orElseThrow();
		assertSame(bundledV2, picked);
		assertEquals("bundled", picked.source());
	}

	@Test
	void pickNewestStillTakesTheHighestRevision() {
		RulesDocument remoteV1 = doc(1, 6);
		RulesDocument picked = RulesLoader.pickNewest(List.of(candidate(RulesLoader.SOURCE_BUNDLED, doc(2, 5)),
				candidate(RulesLoader.SOURCE_REMOTE, remoteV1))).orElseThrow();
		assertSame(remoteV1, picked);
	}

	@Test
	void remoteWinsWhenNewer() {
		RulesDocument remote = doc(5);
		RulesDocument picked = RulesLoader.pickNewest(List.of(
				new RulesLoader.Candidate(RulesLoader.SOURCE_BUNDLED, doc(1)),
				new RulesLoader.Candidate(RulesLoader.SOURCE_REMOTE, remote))).orElseThrow();
		assertSame(remote, picked);
		assertEquals("remote", picked.source());
	}

	@Test
	void cacheRoundTrip(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("config/rigtune/rules-cache.json");
		assertFalse(RulesLoader.loadCache(file).isPresent());
		RulesLoader.saveCache(file, "{\"schemaVersion\":1,\"revision\":7}");
		RulesDocument cached = RulesLoader.loadCache(file).orElseThrow();
		assertEquals(7, cached.revision);
		assertEquals("cache", cached.source());
		Files.writeString(file, "{\"schemaVersion\":99}");
		assertFalse(RulesLoader.loadCache(file).isPresent());
	}

	@Test
	void bundledRulesAreComplete() {
		RulesDocument doc = RulesLoader.loadBundled();
		assertEquals(2, doc.schemaVersion);
		assertTrue(doc.revision >= 1);
		assertEquals("bundled", doc.source());
		assertNotNull(doc.generatedAt);
		assertFalse(doc.gpuTiers.isEmpty());
		assertFalse(doc.cpuTiers.isEmpty());
		assertFalse(doc.heapTiers.isEmpty());
		assertFalse(doc.obsolete.isEmpty());
		assertFalse(doc.advice.isEmpty());
		for (RulesDocument.ModRule mod : doc.mods) {
			assertNotNull(mod.projectId, mod.slug);
			assertFalse(mod.modIds.isEmpty(), mod.slug);
			assertNotNull(mod.reason, mod.slug);
			assertNotNull(mod.title, mod.slug);
			assertNotNull(RulesDocument.impactOf(mod.impact, null), mod.slug);
		}
		for (RulesDocument.SettingRule setting : doc.settings) {
			assertTrue(SettingKeys.changeable(setting.key), setting.key);
			assertTrue(setting.isValueEntry() ^ setting.isClampEntry(), setting.key);
			assertNotNull(setting.reason, setting.key);
		}
		for (RulesDocument.AdviceRule advice : doc.advice) {
			assertTrue(List.of("info", "warning", "critical").contains(advice.kind), advice.id);
			assertNotNull(advice.title, advice.id);
			assertNotNull(advice.text, advice.id);
		}
	}
}
