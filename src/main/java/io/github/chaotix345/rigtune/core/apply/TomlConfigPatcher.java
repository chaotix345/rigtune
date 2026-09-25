package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Contract stub (v0.2 item 7); the DH/Iris workstream implements it. Keys are the path inside the file, without the
// "dh."/"iris." namespace prefix.
public final class TomlConfigPatcher {
	private TomlConfigPatcher() {
	}

	// Returns true when the file changed, false when it already had these values.
	public static boolean patchFile(Path file, Map<String, String> patches) throws IOException {
		throw new IOException("Toml config patching is not implemented yet");
	}

	// The file's current values as flat keys (the same keys patches use); empty if the file is missing or unreadable.
	// Used for journal before-values and the settings snapshot.
	public static Map<String, String> readValues(Path file) {
		return Map.of();
	}

	// One op per key; a value that doesn't fit the file as it is now is refused (key -> reason).
	public static SodiumConfigPatcher.Staged stage(Path file, Map<String, String> patches) {
		return new SodiumConfigPatcher.Staged(List.of(), Map.copyOf(patches.keySet().stream()
				.collect(java.util.stream.Collectors.toMap(k -> k, k -> "not supported yet"))));
	}
}
