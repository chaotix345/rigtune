package io.github.chaotix345.rigtune.core.tryit;

import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.store.StateStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

// config/rigtune/tryit.json, docs/v0.5/SPEC.md 6 (C09): the open try and the recent closed ones. One writer,
// TryItService's ordered chain on Probes.EXECUTOR (X8). Contracts shell (WS-K): WS-T adds the typed accessors (ti
// §2.4).
// JsonStateFile's rules through StateStore (X7): formatVersion 1, at most MAX_BYTES, corrupt -> .bad and empty, newer
// -> read-only, over 4 x the cap -> left alone, unknown fields at any depth kept. One instance per file per process.
public final class TryItStore {
	public static final String FILE_NAME = "tryit.json";
	public static final long MAX_BYTES = 16 * 1024;

	private static final Map<Path, TryItStore> SHARED = new HashMap<>();

	private final StateStore store;

	private TryItStore(Path file) {
		this.store = new StateStore(file, MAX_BYTES, root -> root);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static synchronized TryItStore shared(Path configDir) {
		return SHARED.computeIfAbsent(file(configDir).toAbsolutePath().normalize(), TryItStore::new);
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
