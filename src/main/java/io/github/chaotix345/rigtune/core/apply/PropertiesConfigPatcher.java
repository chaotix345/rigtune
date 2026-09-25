package io.github.chaotix345.rigtune.core.apply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

// Patches config/iris.properties: java.util.Properties, ISO-8859-1 with unicode escapes for anything outside it --
// this is what Properties.load/store already do by default (docs/research/v0.2/dh-iris.md §5/§8.4), so no explicit
// charset handling is needed here. Like TomlConfigPatcher, never sets a key that isn't already in the file: Iris
// writes every key this mod proposes as soon as it has run once, so a missing key means Iris hasn't saved that
// field yet, and guessing its position would be unsafe. Any call that saves the file (including Iris's own
// setShadersEnabledAndApply) rewrites the whole file and drops hand-added comments; that's inherent to
// Properties.store() and already true of Iris's own saves, not something to preserve here.
public final class PropertiesConfigPatcher {
	private PropertiesConfigPatcher() {
	}

	// The file's current values; empty if the file is missing or unreadable.
	public static Map<String, String> readValues(Path file) {
		Properties properties = load(file);
		if (properties == null) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		properties.stringPropertyNames().forEach(name -> out.put(name, properties.getProperty(name)));
		return out;
	}

	// Returns true when the file changed, false when it already had these values.
	public static boolean patchFile(Path file, Map<String, String> patches) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("No such file: " + file);
		}
		Properties properties = load(file);
		if (properties == null) {
			throw new IOException("Could not parse " + file + " as properties");
		}
		if (!apply(properties, patches)) {
			return false;
		}
		AtomicFiles.writeString(file, store(properties));
		return true;
	}

	// One op per key; a key not already in the file (or a null value) is refused (key -> reason).
	public static SodiumConfigPatcher.Staged stage(Path file, Map<String, String> patches) {
		if (!Files.isRegularFile(file)) {
			return refuseAll(patches, "no such file: " + file.getFileName());
		}
		Properties properties = load(file);
		if (properties == null) {
			return refuseAll(patches, "can't parse " + file.getFileName());
		}
		List<PendingActions.Op> ops = new ArrayList<>();
		Map<String, String> refused = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			if (!properties.containsKey(entry.getKey())) {
				refused.put(entry.getKey(), "no such key in " + file.getFileName());
			} else if (entry.getValue() == null) {
				refused.put(entry.getKey(), "no value");
			} else {
				ops.add(PendingActions.Op.patchProperties(file, Collections.singletonMap(entry.getKey(), entry.getValue())));
			}
		}
		return new SodiumConfigPatcher.Staged(List.copyOf(ops), refused);
	}

	private static SodiumConfigPatcher.Staged refuseAll(Map<String, String> patches, String reason) {
		Map<String, String> refused = new LinkedHashMap<>();
		patches.keySet().forEach(key -> refused.put(key, reason));
		return new SodiumConfigPatcher.Staged(List.of(), refused);
	}

	// null when the file is missing or its content can't be parsed as properties (e.g. a malformed unicode escape).
	private static Properties load(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
			return properties;
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	// Sets only keys already present; throws IllegalArgumentException (not IOException -- this is a permanent
	// failure, not a transient one ApplyExecutor should retry) for the first key that's missing or whose value is
	// null. Returns whether any value actually changed, so a no-op patch doesn't rewrite the file.
	private static boolean apply(Properties properties, Map<String, String> patches) {
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			if (!properties.containsKey(entry.getKey())) {
				throw new IllegalArgumentException("No such key: " + entry.getKey());
			}
			if (entry.getValue() == null) {
				throw new IllegalArgumentException("Cannot set " + entry.getKey() + ": no value");
			}
		}
		boolean changed = false;
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			if (!Objects.equals(properties.getProperty(entry.getKey()), entry.getValue())) {
				properties.setProperty(entry.getKey(), entry.getValue());
				changed = true;
			}
		}
		return changed;
	}

	private static String store(Properties properties) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		properties.store(buffer, null);
		return buffer.toString(StandardCharsets.ISO_8859_1);
	}
}
