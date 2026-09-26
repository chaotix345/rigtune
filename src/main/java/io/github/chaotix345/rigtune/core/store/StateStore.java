package io.github.chaotix345.rigtune.core.store;

import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.RigTune;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

// A state file with several writers on different threads (docs/v0.4/plan-review.md X-M1): every change is a
// read-modify-write that re-reads the file under this object's lock, so two writers can't lose each other's update. Use
// one instance per file per process (AwarenessStore.shared, ProfileStore.shared). The JSON root is kept as a JsonObject,
// so fields this version doesn't know survive every rewrite. JsonStateFile's rules apply (formatVersion, cap, .bad,
// newer = read-only). The content is untrusted (players edit these files): accessors type-check every value (instanceof
// JsonPrimitive / JsonArray / JsonObject) instead of calling getAs* blindly, and a change that throws writes nothing.
public final class StateStore {
	private final JsonStateFile file;
	private final UnaryOperator<JsonObject> defaults;

	// defaults: fills in the fields that must always be there (on a copy; returns it).
	public StateStore(Path file, long maxBytes, UnaryOperator<JsonObject> defaults) {
		this.file = new JsonStateFile(file, maxBytes);
		this.defaults = defaults;
	}

	public Path file() {
		return file.file();
	}

	// The current content (a copy) with the defaults filled in; the defaults alone when there's no usable file. A newer
	// file is returned as it is (read-only).
	public synchronized JsonObject read() {
		JsonStateFile.Loaded<JsonObject> loaded = file.load(JsonObject.class);
		JsonObject root = loaded.value() == null ? new JsonObject() : loaded.value().deepCopy();
		root.remove(JsonStateFile.FORMAT_VERSION_KEY);
		return loaded.state() == JsonStateFile.State.NEWER ? root : defaults.apply(root);
	}

	// Applies change to the content as it is on disk now and writes the result. False when nothing was written: the
	// file is from a newer RigTune or unreadable, the result is over the cap, or the write failed.
	public synchronized boolean update(UnaryOperator<JsonObject> change) {
		JsonStateFile.Loaded<JsonObject> loaded = file.load(JsonObject.class);
		if (!loaded.writable()) {
			return false;
		}
		JsonObject root = loaded.value() == null ? new JsonObject() : loaded.value().deepCopy();
		root.remove(JsonStateFile.FORMAT_VERSION_KEY);
		JsonObject next;
		try {
			next = change.apply(defaults.apply(root));
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Not writing {}: the change failed on its content", file.file(), e);
			return false;
		}
		return file.save(next == null ? root : next) == JsonStateFile.Saved.OK;
	}

	public synchronized boolean writable() {
		return file.load(JsonObject.class).writable();
	}
}
