package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
	void valueSpanCoversExactlyTheOriginalTokenIncludingQuotes() {
		String text = "[a]\n\tb = \"x\"\n\tc = 2\n";
		TomlDocument.Value quoted = TomlDocument.parse(text).get("a.b");
		TomlDocument.Value bare = TomlDocument.parse(text).get("a.c");

		assertEquals("\"x\"", text.substring(quoted.start(), quoted.end()));
		assertEquals("2", text.substring(bare.start(), bare.end()));
	}

	// Review finding (Important 2): stripComment is quote-aware, so a trailing "# comment" after a value is cut
	// before the value is classified -- the value itself is recognised normally (DH never actually writes a
	// trailing comment, but if it did, this is the correct reading: the quotes and the value are not conflated
	// with the comment text). Because a patch only splices the value token's own span, the comment past the end of
	// that span is untouched by a patch either way.
	@Test
	void aValueFollowedByAnInlineCommentIsRecognisedWithTheCommentStripped() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tb = \"HIGH\" # trailing comment\n");
		assertEquals("HIGH", parsed.get("a.b").raw());
		assertTrue(parsed.get("a.b").quoted());
	}

	@Test
	void aSingleQuotedValueIsUnrecognisedAndTheKeyIsExcluded() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tb = 'x'\n");
		assertNull(parsed.get("a.b"));
	}

	@Test
	void aBareValueThatIsNeitherABooleanNorAnIntegerIsUnrecognisedAndExcluded() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tb = HIGH\n\tc = 1.5\n\td = {}\n\te = 1,2\n");
		assertNull(parsed.get("a.b"));
		assertNull(parsed.get("a.c"));
		assertNull(parsed.get("a.d"));
		assertNull(parsed.get("a.e"));
	}

	@Test
	void aQuotedValueContainingABackslashOrAnEmbeddedQuoteIsUnrecognisedAndExcluded() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tb = \"C:\\x\"\n\tc = \"a\\\"b\"\n");
		assertNull(parsed.get("a.b"));
		assertNull(parsed.get("a.c"));
	}

	// Review finding (Important 3): a comment on the same line as a section header must not stop the header from
	// being recognised (otherwise its keys are silently attributed to the previous section).
	@Test
	void aSectionHeaderWithATrailingCommentIsStillRecognised() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tx = 1\n[b] # a comment\n\tx = 2\n");
		assertEquals("1", parsed.get("a.x").raw());
		assertEquals("2", parsed.get("b.x").raw());
	}

	// A line that looks like a header but doesn't parse as one (unbalanced brackets, an array-of-tables DH never
	// writes) must not silently keep attributing keys to whatever section came before it -- every key until the
	// next real header is excluded instead.
	@Test
	void aMalformedHeaderLocksOutKeysUntilTheNextRealHeader() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tx = 1\n[b c\n\ty = 2\n[d]\n\tz = 3\n");
		assertEquals("1", parsed.get("a.x").raw());
		assertNull(parsed.get("b.y"));
		assertNull(parsed.get("y"));
		assertEquals("3", parsed.get("d.z").raw());
	}

	@Test
	void aKeyRepeatedInTheFileIsExcludedRatherThanPickingEitherOccurrence() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("[a]\n\tx = 1\n\tx = 2\n");
		assertNull(parsed.get("a.x"));
	}

	// Review finding (Minor 11): DH's own schema-version field (and anything else prefixed with "_") is never
	// exposed as a settable key.
	@Test
	void underscorePrefixedKeysAreExcluded() {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse("_version = 4\n[a]\n\t_internal = 1\n\tx = 2\n");
		assertNull(parsed.get("_version"));
		assertNull(parsed.get("a._internal"));
		assertEquals("2", parsed.get("a.x").raw());
	}
}
