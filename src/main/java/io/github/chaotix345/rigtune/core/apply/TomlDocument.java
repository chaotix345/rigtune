package io.github.chaotix345.rigtune.core.apply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A minimal reader for Distant Horizons' config/DistantHorizons.toml: sections and `key = value` lines only, no
// arrays, inline tables or multi-line strings (DH doesn't write any; docs/research/v0.2/dh-iris.md §2/§8). Section
// headers already carry their full dotted path (`[client.advanced.graphics.quality]`), so a header is never
// implicitly nested under an earlier one; indentation is purely cosmetic.
//
// This reader only ever reports a value it is certain it understands: a bare token that is exactly a boolean or a
// whole number, or a double-quoted string with no embedded quote or backslash (DH's own writer never produces
// anything else -- see the patcher's `unsafe()` check for why preserving that distinction on write matters). A
// value in any other shape (a single-quoted TOML literal string, an inline comment after the value, an object,
// anything with a backslash) is treated as though the key doesn't exist: refused by a patch, absent from
// readValues. Likewise a line that looks like a section header but doesn't parse as one locks out every key until
// the next real header, and a key that appears more than once excludes itself entirely -- both would otherwise let
// a malformed line quietly misattribute a key to the wrong section.
public final class TomlDocument {
	private static final Pattern SECTION = Pattern.compile("\\[([^\\[\\]]+)]");
	private static final Pattern BARE_INTEGER = Pattern.compile("-?\\d+");

	// The value token found for one dotted path: its unquoted text, whether the source token was quoted, and the
	// [start, end) character offsets of the *original* token (quotes included) within the source text, so a
	// patcher can splice in a replacement without touching anything else in the file.
	public record Value(String raw, boolean quoted, int start, int end) {
	}

	private record Line(String content, int start) {
	}

	private TomlDocument() {
	}

	// Splits on \r\n or \n. A trailing newline produces one trailing empty element, so re-joining with the same
	// separator reproduces the original text exactly.
	public static List<String> lines(String text) {
		return Arrays.asList(text.split("\r\n|\n", -1));
	}

	// Dotted path -> value, in file order.
	public static Map<String, Value> parse(String text) {
		Map<String, Value> out = new LinkedHashMap<>();
		Set<String> duplicate = new HashSet<>();
		String section = "";
		boolean sectionUnknown = false;
		for (Line line : scanLines(text)) {
			String withoutComment = stripComment(line.content());
			String trimmed = withoutComment.strip();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.startsWith("[")) {
				Matcher sectionMatch = SECTION.matcher(trimmed);
				sectionUnknown = !sectionMatch.matches();
				if (!sectionUnknown) {
					section = sectionMatch.group(1).strip();
				}
				continue;
			}
			if (sectionUnknown) {
				continue;
			}
			int eq = withoutComment.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = withoutComment.substring(0, eq).strip();
			if (key.isEmpty()) {
				continue;
			}
			String after = withoutComment.substring(eq + 1);
			String token = after.strip();
			if (token.isEmpty()) {
				continue;
			}
			int leading = after.length() - after.stripLeading().length();
			int tokenStart = line.start() + eq + 1 + leading;
			Value value = recognize(token, tokenStart, tokenStart + token.length());
			if (value == null) {
				continue;
			}
			String path = section.isEmpty() ? key : section + "." + key;
			if (hasUnderscorePrefixedSegment(path)) {
				continue;
			}
			if (out.containsKey(path)) {
				duplicate.add(path);
			} else {
				out.put(path, value);
			}
		}
		duplicate.forEach(out::remove);
		return out;
	}

	// A recognised value token: a double-quoted string with no embedded quote or backslash, a bare whole number, or
	// a bare "true"/"false". Anything else (a single-quoted string, an object, a stray word, a number DH never
	// writes bare) is null -- unrecognised, so the caller treats the key as absent.
	private static Value recognize(String token, int start, int end) {
		if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
			String inner = token.substring(1, token.length() - 1);
			return inner.indexOf('"') < 0 && inner.indexOf('\\') < 0 ? new Value(inner, true, start, end) : null;
		}
		if (BARE_INTEGER.matcher(token).matches() || token.equals("true") || token.equals("false")) {
			return new Value(token, false, start, end);
		}
		return null;
	}

	private static boolean hasUnderscorePrefixedSegment(String path) {
		for (String segment : path.split("\\.")) {
			if (segment.startsWith("_")) {
				return true;
			}
		}
		return false;
	}

	// Everything from a '#' outside a double-quoted string to the end of the line. DH never writes a trailing
	// comment after a value, so this only matters for a comment-only line and for a section header followed by one.
	private static String stripComment(String line) {
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
			} else if (c == '#' && !inQuotes) {
				return line.substring(0, i);
			}
		}
		return line;
	}

	// Each line's content (terminator excluded) and the offset where it starts in `text`, so a matched value's
	// token span can be expressed in `text`'s own coordinates.
	private static List<Line> scanLines(String text) {
		List<Line> out = new ArrayList<>();
		int start = 0;
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\n') {
				out.add(new Line(text.substring(start, i), start));
				i++;
				start = i;
			} else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
				out.add(new Line(text.substring(start, i), start));
				i += 2;
				start = i;
			} else {
				i++;
			}
		}
		out.add(new Line(text.substring(start), start));
		return out;
	}
}
