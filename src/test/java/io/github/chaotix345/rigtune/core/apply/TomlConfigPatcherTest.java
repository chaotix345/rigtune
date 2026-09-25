package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlConfigPatcherTest {
	// The test working directory is a build scratch dir (build.gradle's `test.workingDir`), not the project root, so
	// the fixture is loaded from the test classpath rather than a relative file path.
	private static String fixture() throws IOException {
		try (InputStream in = TomlConfigPatcherTest.class.getResourceAsStream("/dh/DistantHorizons.toml")) {
			if (in == null) {
				throw new IOException("Missing test resource dh/DistantHorizons.toml");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String fixtureUnchecked() {
		try {
			return fixture();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	void readValuesFlattensTheFixtureByDottedPath(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		Map<String, String> values = TomlConfigPatcher.readValues(file);

		assertEquals("8", values.get("common.multiThreading.numberOfThreads"));
		assertEquals("1.0", values.get("common.multiThreading.threadRunTimeRatio"));
		assertEquals("HIGH", values.get("client.advanced.graphics.quality.verticalQuality"));
		assertEquals("256", values.get("client.advanced.graphics.quality.lodChunkRenderDistanceRadius"));
		assertEquals("-1.0", values.get("client.advanced.graphics.culling.overdrawPrevention"));
	}

	@Test
	void readValuesIsEmptyForAMissingFile(@TempDir Path dir) {
		assertTrue(TomlConfigPatcher.readValues(dir.resolve("absent.toml")).isEmpty());
	}

	@Test
	void readValuesExcludesTheUnderscorePrefixedSchemaVersionKey(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		assertFalse(TomlConfigPatcher.readValues(file).containsKey("_version"));
	}

	@Test
	void quotedFloatStaysQuotedAndBareIntStaysBare() {
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of(
				"common.multiThreading.threadRunTimeRatio", "0.5",
				"client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "128"));

		Map<String, TomlDocument.Value> reparsed = TomlDocument.parse(patched);
		assertTrue(reparsed.get("common.multiThreading.threadRunTimeRatio").quoted());
		assertEquals("0.5", reparsed.get("common.multiThreading.threadRunTimeRatio").raw());
		assertFalse(reparsed.get("client.advanced.graphics.quality.lodChunkRenderDistanceRadius").quoted());
		assertEquals("128", reparsed.get("client.advanced.graphics.quality.lodChunkRenderDistanceRadius").raw());
	}

	@Test
	void enumStringStaysQuoted() {
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.verticalQuality", "MEDIUM"));
		Map<String, TomlDocument.Value> reparsed = TomlDocument.parse(patched);
		assertTrue(reparsed.get("client.advanced.graphics.quality.verticalQuality").quoted());
		assertEquals("MEDIUM", reparsed.get("client.advanced.graphics.quality.verticalQuality").raw());
	}

	@Test
	void sectionScopingKeepsSameNamedKeysInDifferentSectionsSeparate() {
		// "numberOfThreads" only exists under common.multiThreading in the fixture; patching it must not touch
		// any other key, and a lookup under the wrong section must be refused as missing.
		String patched = TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("common.multiThreading.numberOfThreads", "4"));
		Map<String, TomlDocument.Value> reparsed = TomlDocument.parse(patched);
		assertEquals("4", reparsed.get("common.multiThreading.numberOfThreads").raw());
		assertEquals("DISABLED", reparsed.get("client.advanced.debugging.rendererMode").raw());

		assertThrows(IllegalArgumentException.class,
				() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.numberOfThreads", "4")));
	}

	@Test
	void missingKeyIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.quality.noSuchKey", "1")));
		assertTrue(e.getMessage().contains("noSuchKey"), e.getMessage());
	}

	@Test
	void theSchemaVersionKeyIsRefusedLikeAnyOtherMissingKey() {
		assertThrows(IllegalArgumentException.class, () -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("_version", "5")));
	}

	// A bare value must stay the same kind (boolean or whole number) the file already used for that key, so a
	// remote-rules typo can't silently reset a field to something DH can't parse, or parses as something else
	// entirely (docs/v0.2/plan-review.md self-review: Critical 1).
	@Test
	void aBareIntegerKeyRefusesAWordOrADecimal() {
		for (String bad : new String[]{"HIGH", "1.5", "{}", "1,2", "'x'", ""}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("common.multiThreading.numberOfThreads", bad)), bad);
			assertTrue(e.getMessage().contains("whole number"), e.getMessage());
		}
	}

	@Test
	void aBareBooleanKeyRefusesAnythingButTrueOrFalse() {
		for (String bad : new String[]{"1", "0", "yes", "TRUE", ""}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.graphics.culling.disableShadowPassFrustumCulling", bad)),
					bad);
			assertTrue(e.getMessage().contains("true or false"), e.getMessage());
		}
	}

	@Test
	void aQuotedKeyRefusesABackslashOrAnEmbeddedQuote() {
		IllegalArgumentException backslash = assertThrows(IllegalArgumentException.class,
				() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.debugging.rendererMode", "C:\\x")));
		assertTrue(backslash.getMessage().contains("backslash"), backslash.getMessage());

		IllegalArgumentException quote = assertThrows(IllegalArgumentException.class,
				() -> TomlConfigPatcher.patch(fixtureUnchecked(), Map.of("client.advanced.debugging.rendererMode", "a\"b")));
		assertTrue(quote.getMessage().contains("quote"), quote.getMessage());
	}

	@Test
	void everythingElseInTheFileIsUntouched() {
		String original = fixtureUnchecked();
		String patched = TomlConfigPatcher.patch(original, Map.of("client.advanced.graphics.quality.verticalQuality", "LOW"));

		TomlDocument.Value changed = TomlDocument.parse(original).get("client.advanced.graphics.quality.verticalQuality");
		// Everything before and after the touched span is byte-for-byte identical to the original, whatever the
		// replacement's own length turns out to be.
		assertEquals(original.substring(0, changed.start()), patched.substring(0, changed.start()));
		assertEquals(original.substring(changed.end()), patched.substring(patched.length() - (original.length() - changed.end())));
	}

	// Review finding (Important 4): a batch with one missing key must leave the file exactly as it was -- nothing
	// from the valid keys in the same batch gets written either.
	@Test
	void patchFileLeavesTheFileByteIdenticalWhenOneKeyInABatchIsMissing(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		String before = Files.readString(file, StandardCharsets.UTF_8);
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("common.multiThreading.numberOfThreads", "4");
		patches.put("client.advanced.graphics.quality.noSuchKey", "1");

		assertThrows(IllegalArgumentException.class, () -> TomlConfigPatcher.patchFile(file, patches));
		assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void crlfFileRoundTripsAsCrlf() {
		String crlf = "[a]\r\n\tb = \"x\"\r\n";
		String patched = TomlConfigPatcher.patch(crlf, Map.of("a.b", "y"));
		assertTrue(patched.contains("\r\n"));
		assertEquals("y", TomlDocument.parse(patched).get("a.b").raw());
	}

	@Test
	void mixedLineEndingsAreEachPreservedIndividually() {
		String mixed = "[a]\r\n\tb = 1\n\tc = 2\r\n";
		String patched = TomlConfigPatcher.patch(mixed, Map.of("a.b", "9"));
		assertEquals("[a]\r\n\tb = 9\n\tc = 2\r\n", patched);
	}

	@Test
	void patchFileWritesAtomicallyAndReportsNoOp(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());

		assertTrue(TomlConfigPatcher.patchFile(file, Map.of("common.multiThreading.numberOfThreads", "2")));
		assertEquals("2", TomlConfigPatcher.readValues(file).get("common.multiThreading.numberOfThreads"));

		assertFalse(TomlConfigPatcher.patchFile(file, Map.of("common.multiThreading.numberOfThreads", "2")));
		try (var files = Files.list(dir)) {
			assertEquals(1, files.count(), "no leftover temp file");
		}
	}

	@Test
	void patchFileThrowsForAMissingFile(@TempDir Path dir) {
		assertThrows(IOException.class, () -> TomlConfigPatcher.patchFile(dir.resolve("absent.toml"), Map.of("a.b", "1")));
	}

	@Test
	void stagingRefusesAMissingKeyAndStagesTheRest(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("common.multiThreading.numberOfThreads", "4");
		patches.put("client.advanced.graphics.quality.noSuchKey", "1");

		SodiumConfigPatcher.Staged staged = TomlConfigPatcher.stage(file, patches);

		assertEquals(1, staged.ops().size());
		assertEquals(List.of("client.advanced.graphics.quality.noSuchKey"), List.copyOf(staged.refused().keySet()));
	}

	@Test
	void stagingRefusesABareValueOfTheWrongKind(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("DistantHorizons.toml"), fixture());
		SodiumConfigPatcher.Staged staged = TomlConfigPatcher.stage(file, Map.of("common.multiThreading.numberOfThreads", "HIGH"));

		assertTrue(staged.ops().isEmpty());
		assertTrue(staged.refused().get("common.multiThreading.numberOfThreads").contains("whole number"));
	}

	@Test
	void stagingRefusesEverythingForAMissingFile(@TempDir Path dir) {
		Map<String, String> patches = Map.of("a.b", "1");
		SodiumConfigPatcher.Staged staged = TomlConfigPatcher.stage(dir.resolve("absent.toml"), patches);
		assertTrue(staged.ops().isEmpty());
		assertEquals(patches.keySet(), staged.refused().keySet());
	}
}
