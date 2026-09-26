package io.github.chaotix345.rigtune.core.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.AtomicFiles;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// The common rules for the files 0.4 adds under config/rigtune/ (docs/v0.4/SPEC.md "Shared contracts" C1): profiles.json,
// stutter.json, server-limits.json, awareness.json and startup-times.json. Each feature's store wraps one of these.
// - `formatVersion` is written first and is always FORMAT_VERSION; a file without it counts as version 1.
// - A file from a newer RigTune (higher formatVersion) is read-only: it can still be read, but it is never overwritten.
// - A corrupt file (not UTF-8, not a JSON object, an unusable formatVersion, a shape the type can't take, or far over the
//   cap) is moved to <name>.bad (then .bad.1, .bad.2...; never deleted) and the store starts empty.
// - Writes are atomic (AtomicFiles) and refused over maxBytes; the caller prunes first.
// - Nothing here throws to the caller: a file that can't be read, or a corrupt one that can't be moved aside, is
//   UNREADABLE (left alone, never overwritten this time; the next load tries again), and a failed write returns FAILED.
// - Unknown top-level fields survive a rewrite when the caller passes the root it loaded (save(value, previous)).
public final class JsonStateFile {
	public static final int FORMAT_VERSION = 1;
	public static final String FORMAT_VERSION_KEY = "formatVersion";
	// A file bigger than this many times the write cap isn't even read (it can't be one we wrote).
	static final int READ_CAP_FACTOR = 4;
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public enum State {
		// No file: empty, writable.
		MISSING,
		OK,
		// It was corrupt and is now <name>.bad: empty, writable.
		MOVED_ASIDE,
		// From a newer RigTune: readable (value may be null if the shape changed), never written.
		NEWER,
		// It exists but couldn't be read or moved aside: empty, not written.
		UNREADABLE
	}

	public enum Saved { OK, TOO_LARGE, READ_ONLY, FAILED }

	// value: the file as T for OK and NEWER (null when a newer file doesn't fit T), else null. root: the file's JSON object
	// (empty unless OK or NEWER), for save(value, root).
	public record Loaded<T>(State state, @Nullable T value, JsonObject root) {
		public boolean writable() {
			return state != State.NEWER && state != State.UNREADABLE;
		}
	}

	interface Io {
		byte[] read(Path file) throws IOException;

		void moveAside(Path from, Path to) throws IOException;
	}

	private static final Io FILES = new Io() {
		@Override
		public byte[] read(Path file) throws IOException {
			return Files.readAllBytes(file);
		}

		@Override
		public void moveAside(Path from, Path to) throws IOException {
			Files.move(from, to);
		}
	};

	private final Path file;
	private final long maxBytes;
	private final Gson gson;
	private final Io io;

	public JsonStateFile(Path file, long maxBytes) {
		this(file, maxBytes, GSON);
	}

	public JsonStateFile(Path file, long maxBytes, Gson gson) {
		this(file, maxBytes, gson, FILES);
	}

	JsonStateFile(Path file, long maxBytes, Gson gson, Io io) {
		this.file = file;
		this.maxBytes = maxBytes;
		this.gson = gson;
		this.io = io;
	}

	public Path file() {
		return file;
	}

	public long maxBytes() {
		return maxBytes;
	}

	public synchronized <T> Loaded<T> load(Class<T> type) {
		if (!Files.isRegularFile(file)) {
			return new Loaded<>(State.MISSING, null, new JsonObject());
		}
		JsonObject root;
		try {
			if (Files.size(file) > maxBytes * READ_CAP_FACTOR) {
				return movedAside("larger than " + maxBytes * READ_CAP_FACTOR + " bytes", null);
			}
			root = parse(io.read(file));
		} catch (CorruptException e) {
			return movedAside(e.getMessage(), e.getCause());
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read {}; leaving it alone for now", file, e);
			return new Loaded<>(State.UNREADABLE, null, new JsonObject());
		}
		int version = formatVersion(root);
		if (version > FORMAT_VERSION) {
			RigTune.LOGGER.info("{} is from a newer RigTune (formatVersion {}); reading it only, never writing it", file, version);
			return new Loaded<>(State.NEWER, fitOrNull(root, type), root);
		}
		if (version < 1) {
			return movedAside("unusable formatVersion " + root.get(FORMAT_VERSION_KEY), null);
		}
		try {
			T value = gson.fromJson(root, type);
			if (value == null) {
				return movedAside("empty", null);
			}
			return new Loaded<>(State.OK, value, root);
		} catch (RuntimeException e) {
			return movedAside("a shape this version can't read", e);
		}
	}

	public Saved save(Object value) {
		return save(value, null);
	}

	// previous: the root this store loaded, whose top-level fields this version doesn't write are kept.
	public synchronized Saved save(Object value, @Nullable JsonObject previous) {
		JsonObject out = new JsonObject();
		out.addProperty(FORMAT_VERSION_KEY, FORMAT_VERSION);
		String text;
		try {
			JsonElement tree = value instanceof JsonElement element ? element : gson.toJsonTree(value);
			if (!tree.isJsonObject()) {
				RigTune.LOGGER.warn("Not writing {}: not a JSON object", file);
				return Saved.FAILED;
			}
			JsonObject merged = preserveUnknown(tree.getAsJsonObject(), previous, knownKeys(value.getClass()));
			for (Map.Entry<String, JsonElement> entry : merged.entrySet()) {
				if (!FORMAT_VERSION_KEY.equals(entry.getKey())) {
					out.add(entry.getKey(), entry.getValue());
				}
			}
			text = gson.toJson(out);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not serialise {}", file, e);
			return Saved.FAILED;
		}
		if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			RigTune.LOGGER.warn("Not writing {}: larger than its {} byte cap", file, maxBytes);
			return Saved.TOO_LARGE;
		}
		if (!load(JsonObject.class).writable()) {
			return Saved.READ_ONLY;
		}
		try {
			AtomicFiles.writeString(file, text);
			return Saved.OK;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not write {}", file, e);
			return Saved.FAILED;
		}
	}

	// fresh plus every top-level key of previous that fresh lacks, except formatVersion and the known keys (fields this
	// version knows but chose to leave out stay out). Also usable per element for nested objects.
	public static JsonObject preserveUnknown(JsonObject fresh, @Nullable JsonObject previous, Collection<String> knownKeys) {
		JsonObject out = fresh.deepCopy();
		if (previous != null) {
			for (Map.Entry<String, JsonElement> entry : previous.entrySet()) {
				String key = entry.getKey();
				if (!out.has(key) && !FORMAT_VERSION_KEY.equals(key) && !knownKeys.contains(key)) {
					out.add(key, entry.getValue().deepCopy());
				}
			}
		}
		return out;
	}

	// The field names this version writes for a value of this type (a record's components, else its instance fields).
	private static Set<String> knownKeys(Class<?> type) {
		Set<String> keys = new HashSet<>();
		if (type.isRecord()) {
			for (RecordComponent component : type.getRecordComponents()) {
				keys.add(component.getName());
			}
		} else if (!JsonElement.class.isAssignableFrom(type)) {
			for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Field field : c.getDeclaredFields()) {
					if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
						keys.add(field.getName());
					}
				}
			}
		}
		return keys;
	}

	private <T> @Nullable T fitOrNull(JsonObject root, Class<T> type) {
		try {
			return gson.fromJson(root, type);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private <T> Loaded<T> movedAside(String why, @Nullable Throwable cause) {
		Path bad = free(file, ".bad");
		try {
			io.moveAside(file, bad);
			RigTune.LOGGER.warn("Corrupt {} ({}); kept it as {} and started empty", file, why, bad.getFileName(), cause);
			return new Loaded<>(State.MOVED_ASIDE, null, new JsonObject());
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Corrupt {} ({}) couldn't be moved aside; leaving it alone for now", file, why, e);
			return new Loaded<>(State.UNREADABLE, null, new JsonObject());
		}
	}

	// <file><suffix>, or <file><suffix>.1, .2... when taken.
	private static Path free(Path file, String suffix) {
		Path candidate = file.resolveSibling(file.getFileName() + suffix);
		for (int i = 1; Files.exists(candidate); i++) {
			candidate = file.resolveSibling(file.getFileName() + suffix + "." + i);
		}
		return candidate;
	}

	private static JsonObject parse(byte[] bytes) throws CorruptException {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new CorruptException("not UTF-8", e);
		}
		JsonElement tree;
		try {
			tree = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			throw new CorruptException("not JSON", e);
		}
		if (tree == null || !tree.isJsonObject()) {
			throw new CorruptException("not a JSON object", null);
		}
		return tree.getAsJsonObject();
	}

	// Missing: 1. Not a whole number: 0 (unusable).
	private static int formatVersion(JsonObject root) {
		if (!root.has(FORMAT_VERSION_KEY)) {
			return FORMAT_VERSION;
		}
		JsonElement value = root.get(FORMAT_VERSION_KEY);
		if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			return 0;
		}
		try {
			BigDecimal number = new BigDecimal(primitive.getAsString());
			if (number.stripTrailingZeros().scale() > 0 || number.compareTo(BigDecimal.ONE) < 0) {
				return 0;
			}
			return number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : number.intValue();
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static final class CorruptException extends Exception {
		CorruptException(String message, @Nullable Throwable cause) {
			super(message, cause);
		}
	}
}
