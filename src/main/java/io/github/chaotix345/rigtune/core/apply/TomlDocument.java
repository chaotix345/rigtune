package io.github.chaotix345.rigtune.core.apply;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A minimal reader for Distant Horizons' config/DistantHorizons.toml: sections and `key = value` lines only, no
// arrays, inline tables or multi-line strings (DH doesn't write any; docs/research/v0.2/dh-iris.md §2/§8). Section
// headers already carry their full dotted path (`[client.advanced.graphics.quality]`), so a header is never
// implicitly nested under an earlier one; indentation is purely cosmetic. Comments are always whole lines.
public final class TomlDocument {
	private static final Pattern SECTION = Pattern.compile("\\[([^\\[\\]]+)]");

	// The value token found for one dotted path: its unquoted text, whether the source token was quoted, and the
	// 0-based line it came from (so a patcher can rewrite just that line).
	public record Value(String raw, boolean quoted, int line) {
	}

	private TomlDocument() {
	}

	// Splits on \r\n or \n. A trailing newline produces one trailing empty element, so re-joining with the same
	// separator reproduces the original text exactly.
	public static List<String> lines(String text) {
		return Arrays.asList(text.split("\r\n|\n", -1));
	}

	// Dotted path -> value, in file order. A repeated key keeps its last occurrence.
	public static Map<String, Value> parse(String text) {
		Map<String, Value> out = new LinkedHashMap<>();
		List<String> lines = lines(text);
		String section = "";
		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			Matcher sectionMatch = SECTION.matcher(trimmed);
			if (sectionMatch.matches()) {
				section = sectionMatch.group(1).strip();
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = trimmed.substring(0, eq).strip();
			if (key.isEmpty()) {
				continue;
			}
			String token = trimmed.substring(eq + 1).strip();
			boolean quoted = token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"");
			String raw = quoted ? token.substring(1, token.length() - 1) : token;
			out.put(section.isEmpty() ? key : section + "." + key, new Value(raw, quoted, i));
		}
		return out;
	}
}
