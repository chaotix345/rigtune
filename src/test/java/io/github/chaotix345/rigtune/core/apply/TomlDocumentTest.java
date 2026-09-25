package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlDocumentTest {
	@Test
	void sectionHeadersCarryTheirFullDottedPath() {
		String text = """
				_version = 4

				[common.multiThreading]
					numberOfThreads = 8

				[client.advanced.graphics.quality]
					verticalQuality = "HIGH"
				""";
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(text);

		assertEquals("4", parsed.get("_version").raw());
		assertFalse(parsed.get("_version").quoted());
		assertEquals("8", parsed.get("common.multiThreading.numberOfThreads").raw());
		assertFalse(parsed.get("common.multiThreading.numberOfThreads").quoted());
		assertEquals("HIGH", parsed.get("client.advanced.graphics.quality.verticalQuality").raw());
		assertTrue(parsed.get("client.advanced.graphics.quality.verticalQuality").quoted());
	}

	@Test
	void quotedFloatsAndBareIntsAreDistinguished() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("""
				[a]
					overdrawPrevention = "-1.0"
					lodBiomeBlending = 3
				""");

		assertTrue(parsed.get("a.overdrawPrevention").quoted());
		assertEquals("-1.0", parsed.get("a.overdrawPrevention").raw());
		assertFalse(parsed.get("a.lodBiomeBlending").quoted());
		assertEquals("3", parsed.get("a.lodBiomeBlending").raw());
	}

	@Test
	void commentsAndBlankLinesAreSkipped() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("""
				[a]
					#
					# A comment describing b.
					b = 1
				""");

		assertEquals(1, parsed.size());
		assertEquals("1", parsed.get("a.b").raw());
	}

	@Test
	void crlfLineEndingsParseTheSameAsLf() {
		String crlf = "[a]\r\n\tb = \"x\"\r\n\tc = 2\r\n";
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(crlf);

		assertEquals("x", parsed.get("a.b").raw());
		assertEquals("2", parsed.get("a.c").raw());
	}

	@Test
	void linesRoundTripsExactlyIncludingATrailingNewline() {
		String text = "a = 1\nb = 2\n";
		assertEquals(text, String.join("\n", TomlDocument.lines(text)));
		String noTrailingNewline = "a = 1\nb = 2";
		assertEquals(noTrailingNewline, String.join("\n", TomlDocument.lines(noTrailingNewline)));
	}

	@Test
	void recordsTheSourceLineOfEachValue() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tb = 1\n\tc = 2\n");
		assertEquals(1, parsed.get("a.b").line());
		assertEquals(2, parsed.get("a.c").line());
	}
}
