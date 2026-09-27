package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

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
	static final String FULLSCREEN = SettingKeys.VANILLA_PREFIX + "fullscreen";

	// A key options.txt has with another value than at the last clean exit; rigtune: the value RigTune last applied.
	public record Change(String key, String before, String now, String rigtune) {
	}

	private OutsideOptions() {
	}

	// The vanilla keys RigTune applied, each with the value it last wrote: the journal's latest applied setting change of
	// that key (entries are in history order). An undo's change stops the key being watched (the value is the player's
	// own again). Only keys RigTune may change (never fullscreen); a value it couldn't apply again as it is (unsafe or
	// longer than MAX_VALUE) is left out.
	public static Map<String, String> applied(List<JournalEntry> entries) {
		Map<String, String> out = new LinkedHashMap<>();
		for (JournalEntry entry : entries) {
			for (JournalChange change : entry.changes()) {
				String key = change.key();
				if (!change.isSetting() || key == null || !key.startsWith(SettingKeys.VANILLA_PREFIX) || !SettingKeys.changeable(key) || FULLSCREEN.equals(key)) {
					continue;
				}
				if (change.reverts() != null) {
					out.remove(key);
				} else if (JournalChange.APPLIED.equals(change.status())) {
					String after = change.after();
					if (after != null && SettingKeys.safeValue(after) && after.length() <= MAX_VALUE) {
						out.remove(key);
						out.put(key, after);
					} else {
						out.remove(key);
					}
				}
			}
		}
		return out;
	}

	// The stop snapshot: the watched keys' current values. vanillaNow: the game's options by their bare names
	// (SettingsBridge.readVanilla). At most AwarenessStore.MAX_OPTIONS_AT_EXIT keys.
	public static Map<String, String> snapshot(Set<String> watched, Map<String, String> vanillaNow) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String key : watched.stream().sorted().toList()) {
			if (out.size() >= AwarenessStore.MAX_OPTIONS_AT_EXIT) {
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
	// launch, a crash or a kill) compares nothing.
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
			if (before != null && now != null && !SettingValues.same(before, now)) {
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
