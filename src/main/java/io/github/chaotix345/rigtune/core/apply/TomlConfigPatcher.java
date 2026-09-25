package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// Patches config/DistantHorizons.toml: replaces only the value token of an existing key in its section, keeping
// whatever quoting the file already used for it (DH quotes every double/float and enum, but writes ints and bools
// bare -- docs/research/v0.2/dh-iris.md §8.1). Never adds, removes or reorders a key or section; a key not already
// present in its section is refused. Everything outside the touched value spans -- indentation, comments, blank
// lines, each line's own line ending -- is left byte-for-byte untouched, since a patch splices the new token
// directly into the original text rather than rebuilding lines. TomlDocument never reports a value it isn't sure
// it understands, so "the key doesn't fit" and "the key is missing" both surface the same way here: refused.
public final class TomlConfigPatcher {
	private static final Pattern BARE_INTEGER = Pattern.compile("-?\\d+");

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
	// (missing, or a value that can't be written in this key's quoting style) -- nothing in `text` changes in that
	// case, since every edit is validated before any of them are spliced in.
	public static String patch(String text, Map<String, String> patches) {
		Map<String, TomlDocument.Value> parsed = TomlDocument.parse(text);
		List<Edit> edits = new ArrayList<>();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			String key = entry.getKey();
			TomlDocument.Value existing = parsed.get(key);
			if (existing == null) {
				throw new IllegalArgumentException("No such key: " + key);
			}
			String problem = unsafe(existing.raw(), existing.quoted(), entry.getValue());
			if (problem != null) {
				throw new IllegalArgumentException("Cannot set " + key + " to \"" + entry.getValue() + "\": " + problem);
			}
			String rendered = existing.quoted() ? "\"" + entry.getValue() + "\"" : entry.getValue();
			edits.add(new Edit(existing.start(), existing.end(), rendered));
		}
		// Apply from the end of the text backwards, so an earlier edit's offsets are never shifted by a later one.
		edits.sort(Comparator.comparingInt(Edit::start).reversed());
		StringBuilder out = new StringBuilder(text);
		for (Edit edit : edits) {
			out.replace(edit.start(), edit.end(), edit.replacement());
		}
		return out.toString();
	}

	private record Edit(int start, int end, String replacement) {
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

	// A quoted value can't add a quote or a backslash (this reader doesn't decode escapes, so a backslash could
	// produce an invalid or a silently different escape sequence). A bare value must stay the same kind the file
	// already used for it -- DH's own writer only ever puts a plain boolean or a whole number bare (§8.1), so
	// writing anything else there (an enum string, a decimal, stray text) wouldn't just look different, it would
	// change what DH reads the field as, or fail to parse at all. TomlDocument guarantees `existingRaw` is always
	// one of those two kinds when `quoted` is false, so the final branch here is a defensive fallback, not a case
	// this codebase can currently reach.
	private static String unsafe(String existingRaw, boolean quoted, String value) {
		if (value == null) {
			return "no value";
		}
		if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			return "can't contain a line break";
		}
		if (quoted) {
			if (value.indexOf('"') >= 0) {
				return "can't contain a quote";
			}
			return value.indexOf('\\') >= 0 ? "can't contain a backslash" : null;
		}
		if (isBareBoolean(existingRaw)) {
			return isBareBoolean(value) ? null : "expects true or false, like the file's existing value";
		}
		if (isBareInteger(existingRaw)) {
			return isBareInteger(value) ? null : "expects a whole number, like the file's existing value";
		}
		return "the file's existing bare value isn't a recognised boolean or integer, so a new value can't be written safely";
	}

	private static boolean isBareBoolean(String s) {
		return "true".equals(s) || "false".equals(s);
	}

	private static boolean isBareInteger(String s) {
		return BARE_INTEGER.matcher(s).matches();
	}
}
