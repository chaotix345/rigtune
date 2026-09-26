package io.github.chaotix345.rigtune.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

// The mod-set fingerprint shared by benchmarks.json (Context.modSetHash) and startup-times.json (docs/v0.4/SPEC.md 7, 13):
// SHA-256 (lower-case hex) over "id\0version\n" for each mod, sorted by id. Callers pass the loaded non-builtin mods.
public final class ModSetHash {
	// RigTune's own mod id (RigTune.MOD_ID, which core can't import): left out of ofLoadedMods.
	public static final String RIGTUNE_ID = "rigtune";

	private ModSetHash() {
	}

	// The loaded non-builtin mods' hash, RigTune itself left out (review-8 BF-1): a RigTune update is recorded as its own
	// version, never as a mod-set change. benchmarks.json and startup-times.json both use this.
	public static String ofLoadedMods(Map<String, String> idToVersion) {
		Map<String, String> others = new TreeMap<>(idToVersion);
		others.remove(RIGTUNE_ID);
		return of(others);
	}

	public static String of(Map<String, String> idToVersion) {
		StringBuilder lines = new StringBuilder();
		new TreeMap<>(idToVersion).forEach((id, version) ->
				lines.append(id).append('\0').append(version == null ? "" : version).append('\n'));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(lines.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}
}
