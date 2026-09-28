package io.github.chaotix345.rigtune.core.stutter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.profile.ShareKeys;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

// config/rigtune/stutter-fixes.json, docs/v0.5/SPEC.md 5 (C20): the tracked stutter fixes (FixTracker.Record, sf §2.5),
// in the order they were applied: {"formatVersion": 1, "fixes": [{...}, ...]}. Written only on StutterService's ordered
// io chain (X8); the render thread never reads it.
// JsonStateFile's rules through StateStore (X7): formatVersion 1, at most MAX_BYTES, corrupt -> .bad and empty, newer
// -> read-only, over 4 x the cap -> left alone. One instance per file per process. At most MAX_RECORDS readable records:
// the oldest one that isn't active goes first, also to fit the byte cap; the active one is never dropped. A record is
// written into its own JSON object, so fields this version doesn't know survive at every depth. Players edit these files:
// every value is type-checked, one of the wrong type reads as absent (its default), and a record without what it needs
// (a key outside FixSpec.KEYS, a from or to that isn't a value of the key, an appliedAt more than a day ahead) is skipped
// and left in the file: pruning never drops it (it may be a newer version's), so a file full of them can't be written.
public final class FixStore {
	public static final String FILE_NAME = "stutter-fixes.json";
	public static final long MAX_BYTES = 32 * 1024;
	public static final int MAX_RECORDS = 10;
	static final String FIXES = "fixes";
	// How far ahead of the clock an appliedAt may be (a clock corrected meanwhile), not more.
	static final Duration FUTURE = Duration.ofDays(1);
	// The earliest appliedAt read (review-11 STUTTER-1): 0.5 can't have applied a fix before it, and a far-past instant
	// (Instant.MIN) has no local date.
	static final Instant PAST = Instant.parse("2000-01-01T00:00:00Z");

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

	// The readable records, oldest first (a newer file's too).
	public List<FixTracker.Record> records() {
		Instant now = Instant.now();
		List<FixTracker.Record> out = new ArrayList<>();
		if (store.read().get(FIXES) instanceof JsonArray fixes) {
			for (JsonElement e : fixes) {
				FixTracker.Record r = decode(e, now);
				if (r != null) {
					out.add(r);
				}
			}
		}
		return out;
	}

	// The staged or measuring record that isn't dismissed, or null.
	public FixTracker.@Nullable Record active() {
		return records().stream().filter(FixTracker.Record::active).findFirst().orElse(null);
	}

	// Adds the record last (replacing one with the same entry id), then prunes. False when nothing was written.
	public boolean add(FixTracker.Record record) {
		Instant now = Instant.now();
		return store.update(root -> {
			JsonArray fixes = root.get(FIXES) instanceof JsonArray a ? a : new JsonArray();
			for (int i = fixes.size() - 1; i >= 0; i--) {
				FixTracker.Record r = decode(fixes.get(i), now);
				if (r != null && r.entryId().equals(record.entryId())) {
					fixes.remove(i);
				}
			}
			JsonObject o = new JsonObject();
			encode(record, o);
			fixes.add(o);
			root.add(FIXES, fixes);
			prune(root, fixes, now);
			return root;
		});
	}

	// Changes the record with this entry id in place. False when there's none, or nothing was written.
	public boolean update(String entryId, UnaryOperator<FixTracker.Record> change) {
		if (records().stream().noneMatch(r -> r.entryId().equals(entryId))) {
			return false;
		}
		Instant now = Instant.now();
		boolean[] found = {false};
		boolean written = store.update(root -> {
			if (!(root.get(FIXES) instanceof JsonArray fixes)) {
				return root;
			}
			for (JsonElement e : fixes) {
				FixTracker.Record r = decode(e, now);
				if (r != null && r.entryId().equals(entryId)) {
					FixTracker.Record next = change.apply(r);
					if (next != null) {
						encode(next, (JsonObject) e);
					}
					found[0] = true;
					break;
				}
			}
			prune(root, fixes, now);
			return root;
		});
		return found[0] && written;
	}

	// Hides the record's block; a staged or measuring one stops tracking (FixTracker leaves it alone).
	public boolean dismiss(String entryId) {
		return update(entryId, FixTracker.Record::dismiss);
	}

	private static void prune(JsonObject root, JsonArray fixes, Instant now) {
		boolean dropped = true;
		while (dropped && (readable(fixes, now) > MAX_RECORDS || bytes(root) > MAX_BYTES)) {
			dropped = dropOldest(fixes, now);
		}
	}

	private static int readable(JsonArray fixes, Instant now) {
		int n = 0;
		for (JsonElement e : fixes) {
			if (decode(e, now) != null) {
				n++;
			}
		}
		return n;
	}

	// Drops the oldest readable record that isn't active.
	private static boolean dropOldest(JsonArray fixes, Instant now) {
		for (int i = 0; i < fixes.size(); i++) {
			FixTracker.Record r = decode(fixes.get(i), now);
			if (r != null && !r.active()) {
				fixes.remove(i);
				return true;
			}
		}
		return false;
	}

	// The size JsonStateFile.save would write.
	private static long bytes(JsonObject root) {
		JsonObject out = new JsonObject();
		out.addProperty(JsonStateFile.FORMAT_VERSION_KEY, JsonStateFile.FORMAT_VERSION);
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (!JsonStateFile.FORMAT_VERSION_KEY.equals(e.getKey())) {
				out.add(e.getKey(), e.getValue());
			}
		}
		return JsonStateFile.GSON.toJson(out).getBytes(StandardCharsets.UTF_8).length;
	}

	static void encode(FixTracker.Record r, JsonObject o) {
		o.addProperty("entryId", r.entryId());
		o.addProperty("adviceId", r.adviceId());
		o.addProperty("key", r.key());
		o.addProperty("from", r.from());
		o.addProperty("to", r.to());
		o.addProperty("appliedAt", r.appliedAt().toString());
		o.addProperty("rulesRevision", r.rulesRevision());
		o.addProperty("now", r.now());
		o.addProperty("state", r.state().id());
		encode(r.before(), child(o, "before"));
		encode(r.conditions(), child(o, "conditions"));
		if (r.after() == null) {
			o.remove("after");
		} else {
			encode(r.after(), child(o, "after"));
		}
		o.addProperty("skipped", r.skipped());
		if (r.lastSkip() == null) {
			o.remove("lastSkip");
		} else {
			JsonObject skip = child(o, "lastSkip");
			skip.addProperty("reason", r.lastSkip().reason());
			JsonArray args = new JsonArray();
			r.lastSkip().args().forEach(args::add);
			skip.add("args", args);
		}
		if (r.verdict() == null) {
			for (String name : List.of("verdict", "phi", "pLess", "pMore")) {
				o.remove(name);
			}
		} else {
			o.addProperty("verdict", r.verdict().kind().id());
			o.addProperty("phi", r.verdict().phi());
			o.addProperty("pLess", r.verdict().pLess());
			o.addProperty("pMore", r.verdict().pMore());
		}
		o.addProperty("dismissed", r.dismissed());
	}

	private static void encode(SessionOutcome s, JsonObject o) {
		o.addProperty("sessions", s.sessions());
		o.addProperty("gameplaySeconds", s.gameplaySeconds());
		o.addProperty("hitches", s.hitches());
		o.addProperty("lostMs", s.lostMs());
		o.addProperty("bins", s.bins());
		o.addProperty("binMean", s.binMean());
		o.addProperty("binVariance", s.binVariance());
	}

	private static void encode(FixConditions c, JsonObject o) {
		put(o, "mc", c.mc());
		put(o, "modSetHash", c.modSetHash());
		o.addProperty("heapMaxMb", c.heapMaxMb());
		put(o, "collector", c.collector());
		o.addProperty("width", c.width());
		o.addProperty("height", c.height());
		o.addProperty("fullscreen", c.fullscreen());
		put(o, "world", c.world());
		o.addProperty("phaseTiming", c.phaseTiming());
		o.addProperty("gcMeasured", c.gcMeasured());
		JsonObject settings = child(o, "settings");
		c.settings().forEach(settings::addProperty);
	}

	private static void put(JsonObject o, String name, @Nullable String value) {
		if (value == null) {
			o.remove(name);
		} else {
			o.addProperty(name, value);
		}
	}

	// The object under name, made (replacing anything else there) when it isn't one.
	private static JsonObject child(JsonObject o, String name) {
		if (o.get(name) instanceof JsonObject existing) {
			return existing;
		}
		JsonObject made = new JsonObject();
		o.add(name, made);
		return made;
	}

	static FixTracker.@Nullable Record decode(@Nullable JsonElement e, Instant now) {
		if (!(e instanceof JsonObject o)) {
			return null;
		}
		String entryId = string(o, "entryId");
		String adviceId = string(o, "adviceId");
		String key = string(o, "key");
		String from = string(o, "from");
		String to = string(o, "to");
		Instant appliedAt = instant(string(o, "appliedAt"));
		FixTracker.State state = FixTracker.State.of(string(o, "state"));
		SessionOutcome before = outcome(o.get("before"));
		FixConditions conditions = conditions(o.get("conditions"));
		if (entryId == null || adviceId == null || key == null || !FixSpec.KEYS.contains(key) || !valueOf(key, from) || !valueOf(key, to)
				|| appliedAt == null || appliedAt.isAfter(now.plus(FUTURE)) || appliedAt.isBefore(PAST) || state == null || before == null
				|| conditions == null) {
			return null;
		}
		SessionOutcome after = outcome(o.get("after"));
		FixComparison.Kind kind = FixComparison.Kind.of(string(o, "verdict"));
		FixComparison.Verdict verdict = kind == null || after == null ? null
				: FixComparison.Verdict.of(kind, before, after, number(o, "phi", 1), number(o, "pLess", 1), number(o, "pMore", 1));
		return new FixTracker.Record(entryId, adviceId, key, from, to, appliedAt, whole(o, "rulesRevision", 0), bool(o, "now",
				key.startsWith(SettingKeys.VANILLA_PREFIX)), state, before, conditions, after, whole(o, "skipped", 0), skip(o.get("lastSkip")), verdict,
				bool(o, "dismissed", false));
	}

	private static boolean valueOf(String key, @Nullable String value) {
		ShareKeys.Key table = ShareKeys.byKey(key);
		return value != null && table != null && table.encode(value) != null;
	}

	private static @Nullable SessionOutcome outcome(@Nullable JsonElement e) {
		if (!(e instanceof JsonObject o)) {
			return null;
		}
		return new SessionOutcome(whole(o, "sessions", 1), number(o, "gameplaySeconds", 0), whole(o, "hitches", 0), number(o, "lostMs", 0),
				whole(o, "bins", 0), number(o, "binMean", 0), number(o, "binVariance", 0));
	}

	private static @Nullable FixConditions conditions(@Nullable JsonElement e) {
		if (!(e instanceof JsonObject o)) {
			return null;
		}
		Map<String, String> settings = new LinkedHashMap<>();
		if (o.get("settings") instanceof JsonObject s) {
			for (Map.Entry<String, JsonElement> entry : s.entrySet()) {
				if (entry.getValue() instanceof JsonPrimitive p) {
					settings.put(entry.getKey(), p.getAsString());
				}
			}
		}
		return new FixConditions(string(o, "mc"), string(o, "modSetHash"), (long) number(o, "heapMaxMb", 0), string(o, "collector"), whole(o, "width", 0),
				whole(o, "height", 0), bool(o, "fullscreen", false), string(o, "world"), bool(o, "phaseTiming", false), bool(o, "gcMeasured", false),
				settings);
	}

	private static FixTracker.@Nullable Skip skip(@Nullable JsonElement e) {
		if (!(e instanceof JsonObject o) || string(o, "reason") == null) {
			return null;
		}
		List<String> args = new ArrayList<>();
		if (o.get("args") instanceof JsonArray a) {
			for (JsonElement arg : a) {
				if (arg instanceof JsonPrimitive p) {
					args.add(p.getAsString());
				}
			}
		}
		return new FixTracker.Skip(string(o, "reason"), args);
	}

	private static @Nullable String string(JsonObject o, String name) {
		return o.get(name) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
	}

	private static double number(JsonObject o, String name, double otherwise) {
		if (o.get(name) instanceof JsonPrimitive p && p.isNumber()) {
			double d = p.getAsDouble();
			return Double.isFinite(d) ? d : otherwise;
		}
		return otherwise;
	}

	private static int whole(JsonObject o, String name, int otherwise) {
		double d = number(o, name, Double.NaN);
		return Double.isNaN(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE ? otherwise : (int) d;
	}

	private static boolean bool(JsonObject o, String name, boolean otherwise) {
		return o.get(name) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : otherwise;
	}

	private static @Nullable Instant instant(@Nullable String text) {
		if (text == null) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
