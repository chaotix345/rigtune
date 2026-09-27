package io.github.chaotix345.rigtune.core.tryit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

// config/rigtune/tryit.json, docs/v0.5/SPEC.md 6 (C09): the open try (`current`, a TryIt) and the recent closed ones
// (`recent`, newest first, at most MAX_RECENT and fewer when they don't fit the cap). Nothing else is stored here: the
// stage is derived (TryItFlow), the pair lives in benchmarks.json and the change in history.json. One writer,
// TryItService's ordered chain on Probes.EXECUTOR (X8).
// JsonStateFile's rules through StateStore (X7): formatVersion 1, at most MAX_BYTES, corrupt -> .bad and empty, newer
// -> read-only, over 4 x the cap -> left alone, unknown fields at any depth kept (inside `current` and each `recent` row
// too). One instance per file per process.
public final class TryItStore {
	public static final String FILE_NAME = "tryit.json";
	public static final long MAX_BYTES = 16 * 1024;
	public static final int MAX_RECENT = 10;
	static final String CURRENT = "current";
	static final String RECENT = "recent";

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

	// The open try, or null (none, or one a hand edit made unusable).
	public @Nullable TryIt current() {
		return TryIt.fromJson(read().get(CURRENT));
	}

	// The closed tries, newest first; rows a hand edit made unusable are left out.
	public List<TryIt.Closed> recent() {
		List<TryIt.Closed> out = new ArrayList<>();
		if (read().get(RECENT) instanceof JsonArray rows) {
			for (JsonElement row : rows) {
				TryIt.Closed closed = TryIt.Closed.fromJson(row);
				if (closed != null) {
					out.add(closed);
				}
			}
		}
		return out;
	}

	// Starts a try; false while another one is open or when nothing could be written.
	public synchronized boolean open(TryIt t) {
		if (current() != null) {
			return false;
		}
		return store.update(root -> {
			root.add(CURRENT, t.toJson(new JsonObject()));
			return fit(root);
		});
	}

	// Changes the open try when it's the one with this id; false otherwise or when nothing could be written.
	public synchronized boolean change(String id, UnaryOperator<TryIt> change) {
		TryIt open = current();
		if (open == null || !open.id().equals(id)) {
			return false;
		}
		TryIt next = change.apply(open);
		return store.update(root -> {
			root.add(CURRENT, next.toJson(root.get(CURRENT) instanceof JsonObject o ? o : new JsonObject()));
			return fit(root);
		});
	}

	// Closes the open try with this id: it leaves `current` and becomes the newest `recent` row. One write.
	public synchronized boolean close(String id, TryIt.Closed closed) {
		TryIt open = current();
		if (open == null || !open.id().equals(id)) {
			return false;
		}
		return store.update(root -> {
			root.remove(CURRENT);
			JsonArray rows = new JsonArray();
			rows.add(closed.toJson());
			if (root.get(RECENT) instanceof JsonArray old) {
				for (JsonElement row : old) {
					if (rows.size() < MAX_RECENT) {
						rows.add(row);
					}
				}
			}
			root.add(RECENT, rows);
			return fit(root);
		});
	}

	// Drops the oldest `recent` rows while the file would be over the cap (the newest one stays).
	private static JsonObject fit(JsonObject root) {
		while (root.get(RECENT) instanceof JsonArray rows && rows.size() > 1 && bytes(root) > MAX_BYTES) {
			rows.remove(rows.size() - 1);
		}
		return root;
	}

	// The size JsonStateFile would write.
	private static long bytes(JsonObject root) {
		JsonObject out = new JsonObject();
		out.addProperty(JsonStateFile.FORMAT_VERSION_KEY, JsonStateFile.FORMAT_VERSION);
		root.entrySet().forEach(e -> {
			if (!JsonStateFile.FORMAT_VERSION_KEY.equals(e.getKey())) {
				out.add(e.getKey(), e.getValue());
			}
		});
		return JsonStateFile.GSON.toJson(out).getBytes(StandardCharsets.UTF_8).length;
	}
}
