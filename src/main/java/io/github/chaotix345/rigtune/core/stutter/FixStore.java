package io.github.chaotix345.rigtune.core.stutter;

import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.store.StateStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

// config/rigtune/stutter-fixes.json, docs/v0.5/SPEC.md 5 (C20): the tracked stutter fixes. Written only on
// StutterService's ordered io chain (X8); the render thread never reads it. Contracts shell (WS-K): WS-S2 adds
// records(), add, update, dismiss (sf §2.5).
// JsonStateFile's rules through StateStore (X7): formatVersion 1, at most MAX_BYTES, corrupt -> .bad and empty, newer
// -> read-only, over 4 x the cap -> left alone, unknown fields at any depth kept. One instance per file per process.
public final class FixStore {
	public static final String FILE_NAME = "stutter-fixes.json";
	public static final long MAX_BYTES = 32 * 1024;

	private static final Map<Path, FixStore> SHARED = new HashMap<>();

	private final StateStore store;

	private FixStore(Path file) {
		this.store = new StateStore(file, MAX_BYTES, root -> root);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static synchronized FixStore shared(Path configDir) {
		return SHARED.computeIfAbsent(file(configDir).toAbsolutePath().normalize(), FixStore::new);
	}

	public Path file() {
		return store.file();
	}

	// A copy of the content; empty when there's no usable file. A newer file is returned as it is (read-only).
	public JsonObject read() {
		return store.read();
	}

	public boolean update(UnaryOperator<JsonObject> change) {
		return store.update(change);
	}

	// False when the file is from a newer RigTune or can't be read: update() then writes nothing.
	public boolean writable() {
		return store.writable();
	}
}
