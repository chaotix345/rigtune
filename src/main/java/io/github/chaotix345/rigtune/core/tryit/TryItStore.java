package io.github.chaotix345.rigtune.core.tryit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
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

	// The raw read-modify-write (V05StoreShellsTest's file contract). Try it itself writes only through open, change and
	// close, which check the open try inside the same write; this takes the same lock, so they never interleave.
	public synchronized boolean update(UnaryOperator<JsonObject> change) {
		return store.update(change);
	}

	// False when the file is from a newer RigTune, can't be read, or is over the cap (a hand edit): a try couldn't be
	// recorded.
	public synchronized boolean writable() {
		if (!store.writable()) {
			return false;
		}
		try {
			return !Files.isRegularFile(file()) || Files.size(file()) <= MAX_BYTES;
		} catch (IOException e) {
			return false;
		}
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
		return write((open, root) -> open == null ? t.toJson(new JsonObject()) : null, (root, next) -> root.add(CURRENT, next));
	}

	// Changes the open try when it's the one with this id; false otherwise or when nothing could be written.
	public synchronized boolean change(String id, UnaryOperator<TryIt> change) {
		TryIt open = current();
		if (open == null || !open.id().equals(id)) {
			return false;
		}
		return write((now, root) -> now == null || !now.id().equals(id) ? null
				: change.apply(now).toJson(root.get(CURRENT) instanceof JsonObject o ? o : new JsonObject()), (root, next) -> root.add(CURRENT, next));
	}

	// Closes the open try with this id: it leaves `current` and becomes the newest `recent` row, followed by the
	// MAX_RECENT - 1 newest valid older ones (rows a hand edit made unusable stay where they are but don't count). One write.
	public synchronized boolean close(String id, TryIt.Closed closed) {
		TryIt open = current();
		if (open == null || !open.id().equals(id)) {
			return false;
		}
		return write((now, root) -> now == null || !now.id().equals(id) ? null : closed.toJson(), (root, row) -> {
			root.remove(CURRENT);
			JsonArray rows = new JsonArray();
			rows.add(row);
			int valid = 1;
			if (root.get(RECENT) instanceof JsonArray old) {
				for (JsonElement r : old) {
					boolean counts = TryIt.Closed.fromJson(r) != null;
					if (!counts || valid < MAX_RECENT) {
						rows.add(r);
						valid += counts ? 1 : 0;
					}
				}
			}
			root.add(RECENT, rows);
		});
	}

	// One write that decides, inside the store's read-modify-write, from the open try as it is on disk then: next(open,
	// root) answers what to put (null: nothing, and false). The oldest `recent` rows go while the file would be over the
	// cap.
	private boolean write(BiFunction<@Nullable TryIt, JsonObject, @Nullable JsonObject> next, BiConsumer<JsonObject, JsonObject> put) {
		boolean[] done = {false};
		boolean written = store.update(root -> {
			JsonObject value = next.apply(TryIt.fromJson(root.get(CURRENT)), root);
			if (value == null) {
				return root;
			}
			put.accept(root, value);
			done[0] = true;
			return fit(root);
		});
		return written && done[0];
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
