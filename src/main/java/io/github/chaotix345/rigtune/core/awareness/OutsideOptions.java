package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// docs/v0.5/SPEC.md 4h (docs/research/v0.5/launcher-managed-mods.md §2): settings changed outside the game, such as the
// Modrinth App's game-settings sync writing options.txt before a launch. A one-shot start-up comparison, not a monitor: at
// a clean exit the current values of the vanilla keys RigTune itself applied are stored (awareness.json's optionsAtExit);
// at the next start options.txt is compared with them, once. Keys are the journal's ("vanilla.renderClouds"); values are
// the game's own encoding, JSON-unquoted, as SettingsBridge reads them and Options.load reads options.txt. fullscreen is
// never compared (F11 and a launcher's override both touch it). Values are cleaned and capped before they are stored or
// shown (the files are untrusted).
public final class OutsideOptions {
	public static final int MAX_VALUE = 32;
	// The snapshot's exit time (ISO-8601 UTC), stored with the values (review L6).
	public static final String EXIT_AT = "$exitAt";
	static final String FULLSCREEN = SettingKeys.VANILLA_PREFIX + "fullscreen";

	// A key options.txt has with another value than at the last clean exit; rigtune: the value RigTune last applied.
	public record Change(String key, String before, String now, String rigtune) {
	}

	private OutsideOptions() {
	}

	// The vanilla keys RigTune applied, each with the value it last wrote and still has in effect: the latest change of that
	// key whose status is APPLIED (entries are in history order; an undo's own changes are the player's values, and the
	// change an undo reverted is REVERTED, so the Apply before it counts again). Only keys RigTune may change (never
	// fullscreen); a value it couldn't apply again as it is (unsafe or longer than MAX_VALUE) is left out.
	public static Map<String, String> applied(List<JournalEntry> entries) {
		Map<String, @Nullable String> latest = new LinkedHashMap<>();
		for (JournalEntry entry : entries) {
			for (JournalChange change : entry.changes()) {
				String key = change.key();
				if (!change.isSetting() || key == null || !key.startsWith(SettingKeys.VANILLA_PREFIX) || !SettingKeys.changeable(key) || FULLSCREEN.equals(key)
						|| change.reverts() != null || !JournalChange.APPLIED.equals(change.status())) {
					continue;
				}
				latest.remove(key);
				latest.put(key, change.after());
			}
		}
		Map<String, String> out = new LinkedHashMap<>();
		latest.forEach((key, after) -> {
			if (after != null && SettingKeys.safeValue(after) && after.length() <= MAX_VALUE) {
				out.put(key, after);
			}
		});
		return out;
	}

	// The stop snapshot: the watched keys' current values. vanillaNow: the game's options by their bare names
	// (SettingsBridge.readVanilla). At most AwarenessStore.MAX_OPTIONS_AT_EXIT - 1 keys (room for stamped's exit time).
	public static Map<String, String> snapshot(Set<String> watched, Map<String, String> vanillaNow) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String key : watched.stream().sorted().toList()) {
			if (out.size() >= AwarenessStore.MAX_OPTIONS_AT_EXIT - 1) {
				break;
			}
			String value = key.startsWith(SettingKeys.VANILLA_PREFIX) && !FULLSCREEN.equals(key)
					? clean(vanillaNow.get(key.substring(SettingKeys.VANILLA_PREFIX.length()))) : null;
			if (value != null) {
				out.put(key, value);
			}
		}
		return out;
	}

	// The snapshot as stored: the exit time first (review L6), then the values. The stamp is never a setting (compare skips
	// every key RigTune didn't apply).
	public static Map<String, String> stamped(Map<String, String> snapshot, Instant exitAt) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put(EXIT_AT, exitAt.toString());
		for (Map.Entry<String, String> entry : snapshot.entrySet()) {
			if (out.size() >= AwarenessStore.MAX_OPTIONS_AT_EXIT) {
				break;
			}
			out.put(entry.getKey(), entry.getValue());
		}
		return out;
	}

	// True when a launch of another RigTune version was recorded since the snapshot's exit (startup-times.json, which 0.4
	// writes at every launch too): after 0.5 -> 0.4.0 -> 0.5, options changed in game under 0.4 would otherwise look
	// changed outside. This launch's own run is the current version. No (or an unreadable) stamp: can't tell, false.
	public static boolean anotherVersionSince(Map<String, String> snapshot, List<StartupTimesStore.Run> runs, String version) {
		Instant exitAt = instant(snapshot.get(EXIT_AT));
		if (exitAt == null) {
			return false;
		}
		for (StartupTimesStore.Run run : runs) {
			Instant at = instant(run.at());
			if (at != null && at.isAfter(exitAt) && !version.equals(run.rigtuneVersion())) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable Instant instant(@Nullable String value) {
		try {
			return value == null ? null : Instant.parse(value);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	// options.txt as Options.load reads it: "name:value" per line, the value JSON (a string is unquoted).
	public static Map<String, String> parseOptions(List<String> lines) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : lines) {
			int colon = line.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			out.put(line.substring(0, colon), unquote(line.substring(colon + 1)));
		}
		return out;
	}

	// What options.txt has now against the snapshot, for keys RigTune applied only; null snapshot (none stored: a first
	// launch, a crash or a kill) compares nothing. A key already back at RigTune's value is no change to offer (review L7).
	public static List<Change> compare(@Nullable Map<String, String> snapshot, Map<String, String> options, Map<String, String> applied) {
		List<Change> out = new ArrayList<>();
		if (snapshot == null) {
			return out;
		}
		for (Map.Entry<String, String> entry : snapshot.entrySet()) {
			String key = entry.getKey();
			String rigtune = applied.get(key);
			if (rigtune == null || FULLSCREEN.equals(key) || !key.startsWith(SettingKeys.VANILLA_PREFIX)) {
				continue;
			}
			String before = clean(entry.getValue());
			String now = clean(options.get(key.substring(SettingKeys.VANILLA_PREFIX.length())));
			if (before != null && now != null && !SettingValues.same(before, now) && !SettingValues.same(now, rigtune)) {
				out.add(new Change(key, before, now, rigtune));
			}
		}
		return out;
	}

	// A value as stored or shown: control characters dropped, at most MAX_VALUE characters.
	public static @Nullable String clean(@Nullable String value) {
		if (value == null) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		value.codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
		return out.length() > MAX_VALUE ? out.substring(0, MAX_VALUE) : out.toString();
	}

	private static String unquote(String raw) {
		String value = raw.strip();
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			try {
				JsonElement json = JsonParser.parseString(value);
				if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
					return json.getAsString();
				}
			} catch (RuntimeException e) {
				return value;
			}
		}
		return value;
	}
}
