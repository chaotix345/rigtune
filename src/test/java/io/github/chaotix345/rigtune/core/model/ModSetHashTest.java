package io.github.chaotix345.rigtune.core.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

// docs/v0.4/SPEC.md 7 and 13: one definition of the mod-set hash for benchmarks.json and startup-times.json.
class ModSetHashTest {
	@Test
	void itIsTheSha256OfTheSortedIdVersionLines() throws Exception {
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest("iris\u00001.11.6\nsodium\u00000.9.2\n".getBytes(StandardCharsets.UTF_8)));
		assertEquals(expected, ModSetHash.of(Map.of("sodium", "0.9.2", "iris", "1.11.6")));
	}

	@Test
	void theInsertionOrderDoesNotMatter() {
		Map<String, String> a = new LinkedHashMap<>();
		a.put("sodium", "0.9.2");
		a.put("iris", "1.11.6");
		Map<String, String> b = new LinkedHashMap<>();
		b.put("iris", "1.11.6");
		b.put("sodium", "0.9.2");
		assertEquals(ModSetHash.of(a), ModSetHash.of(b));
	}

	@Test
	void aVersionChangeOrAnExtraModChangesIt() {
		String base = ModSetHash.of(Map.of("sodium", "0.9.2"));
		assertNotEquals(base, ModSetHash.of(Map.of("sodium", "0.9.3")));
		assertNotEquals(base, ModSetHash.of(Map.of("sodium", "0.9.2", "iris", "1.11.6")));
	}

	@Test
	void theSeparatorsKeepIdsAndVersionsApart() {
		assertNotEquals(ModSetHash.of(Map.of("ab", "c")), ModSetHash.of(Map.of("a", "bc")));
	}

	@Test
	void nullVersionsAndAnEmptySetAreFine() {
		Map<String, String> withNull = new LinkedHashMap<>();
		withNull.put("sodium", null);
		assertEquals(ModSetHash.of(Map.of("sodium", "")), ModSetHash.of(withNull));
		assertEquals(64, ModSetHash.of(Map.of()).length());
	}
}
