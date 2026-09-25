package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// Patches config/DistantHorizons.toml: replaces only the value token of an existing key in its section, keeping
// whatever quoting the file already used for it (DH quotes every double/float and enum, but writes ints and bools
// bare -- docs/research/v0.2/dh-iris.md §8.1). Never adds, removes or reorders a key or section; a key not already
// present in its section is refused. This reader never throws on malformed content (see TomlDocument); it simply
// doesn't find the key, which the "missing key" refusal already covers.
public final class TomlConfigPatcher {
	// A bare (unquoted) token can't contain whitespace, '#', '"', '[' or ']' without corrupting the line; a quoted
	// token can't contain a '"' since this reader doesn't unescape one.
	private static final Pattern UNSAFE_BARE = Pattern.compile("[\\s#\"\\[\\]]");

	private TomlConfigPatcher() {
	}

	// The file's current values as flat dotted keys; empty if the file is missing or unreadable.
	public static Map<String, String> readValues(Path file) {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try {
			Map<String, String> out = new LinkedHashMap<>();
			TomlDocument.parse(Files.readString(file, StandardCharsets.UTF_8)).forEach((k, v) -> out.put(k, v.raw()));
			return out;
		} catch (IOException | RuntimeException e) {
			return Map.of();
		}
	}

	// Applies every patch to `text`, or throws IllegalArgumentException naming the first key that doesn't fit
	// (missing, or a value that can't be written in this key's quoting style).
	public static String patch(String text, Map<String, String> patches) {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(text);
		List<String> lines = new ArrayList<>(TomlDocument.lines(text));
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			String key = entry.getKey();
			TomlDocument.Value existing = parsed.get(key);
			if (existing == null) {
				throw new IllegalArgumentException("No such key: " + key);
			}
			String problem = unsafe(entry.getValue(), existing.quoted());
			if (problem != null) {
				throw new IllegalArgumentException("Cannot set " + key + " to \"" + entry.getValue() + "\": " + problem);
			}
			lines.set(existing.line(), withValue(lines.get(existing.line()), existing.quoted(), entry.getValue()));
		}
		return String.join(text.contains("\r\n") ? "\r\n" : "\n", lines);
	}

	// Returns true when the file changed, false when it already had these values.
	public static boolean patchFile(Path file, Map<String, String> patches) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("No such file: " + file);
		}
		String text = Files.readString(file, StandardCharsets.UTF_8);
		String patched = patch(text, patches);
		if (patched.equals(text)) {
			return false;
		}
		AtomicFiles.writeString(file, patched);
		return true;
	}

	// One op per key; a value that doesn't fit the file as it is now is refused (key -> reason).
	public static SodiumConfigPatcher.Staged stage(Path file, Map<String, String> patches) {
		String text;
		try {
			if (!Files.isRegularFile(file)) {
				throw new IOException("missing");
			}
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Map<String, String> refused = new LinkedHashMap<>();
			patches.keySet().forEach(key -> refused.put(key, "can't read " + file.getFileName()));
			return new SodiumConfigPatcher.Staged(List.of(), refused);
		}
		List<PendingActions.Op> ops = new ArrayList<>();
		Map<String, String> refused = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			Map<String, String> single = Collections.singletonMap(entry.getKey(), entry.getValue());
			try {
				patch(text, single);
				ops.add(PendingActions.Op.patchToml(file, single));
			} catch (IllegalArgumentException e) {
				refused.put(entry.getKey(), e.getMessage());
			}
		}
		return new SodiumConfigPatcher.Staged(List.copyOf(ops), refused);
	}

	private static String unsafe(String value, boolean quoted) {
		if (value == null) {
			return "no value";
		}
		if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			return "can't contain a line break";
		}
		if (quoted) {
			return value.indexOf('"') >= 0 ? "can't contain a quote" : null;
		}
		return UNSAFE_BARE.matcher(value).find() ? "needs quoting, and the file writes this key unquoted" : null;
	}

	// Rewrites only the value token on `originalLine` (found between '=' and the end of line, trimmed), keeping the
	// key, the surrounding whitespace and the quote style exactly as they were.
	private static String withValue(String originalLine, boolean quoted, String newValue) {
		int eq = originalLine.indexOf('=');
		String before = originalLine.substring(0, eq + 1);
		String after = originalLine.substring(eq + 1);
		int leading = after.length() - after.stripLeading().length();
		int trailing = after.length() - after.stripTrailing().length();
		String rendered = quoted ? "\"" + newValue + "\"" : newValue;
		return before + after.substring(0, leading) + rendered + after.substring(after.length() - trailing);
	}
}
