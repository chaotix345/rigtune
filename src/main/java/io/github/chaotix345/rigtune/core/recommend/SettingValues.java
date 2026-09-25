package io.github.chaotix345.rigtune.core.recommend;

import com.google.gson.JsonElement;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

public final class SettingValues {
	static final String REFRESH_RATE = "$refreshRate";
	static final String REFRESH_RATE_CAP = "$refreshRateCap";
	// Setting titles for a mod's keys say which mod they belong to.
	private static final Map<String, String> MOD_NAMES = Map.of(
			SettingKeys.SODIUM_PREFIX, "Sodium",
			SettingKeys.DH_PREFIX, "Distant Horizons",
			SettingKeys.IRIS_PREFIX, "Iris");

	private SettingValues() {
	}

	static String asString(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		return element.getAsString();
	}

	static String resolveTokens(String value, DisplayInfo display) {
		String trimmed = value.trim();
		int hz = display != null && display.refreshRate() > 0 ? display.refreshRate() : 60;
		// vanilla's framerate limit only accepts multiples of 10 (260 = unlimited)
		if (trimmed.equals(REFRESH_RATE)) {
			return Integer.toString(Math.clamp(Math.round(hz / 10.0) * 10, 30, 260));
		}
		if (trimmed.equals(REFRESH_RATE_CAP)) {
			return Integer.toString(refreshRateCap(hz));
		}
		return value;
	}

	// A "$token" this client doesn't know (resolveTokens left it as it is). The entry is skipped rather than applying the
	// token text as a value.
	static boolean unresolvedToken(String value) {
		return value.trim().startsWith("$");
	}

	public static int refreshRateCap(int refreshRate) {
		int hz = refreshRate > 0 ? refreshRate : 60;
		int cap = hz / 10 * 10;
		if (cap == hz && hz >= 100) {
			cap -= 10;
		}
		return Math.clamp(cap, 30, 250);
	}

	static boolean same(String a, String b) {
		if (a == null || b == null) {
			return a == b;
		}
		String na = normalise(a);
		String nb = normalise(b);
		BigDecimal da = number(na);
		BigDecimal db = number(nb);
		if (da != null && db != null) {
			return da.compareTo(db) == 0;
		}
		return na.equals(nb);
	}

	static BigDecimal number(String value) {
		if (value == null) {
			return null;
		}
		try {
			return new BigDecimal(normalise(value));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static String format(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
	}

	// A caption from the key's last segment, e.g. "Render distance", or "Sodium: Chunk builder threads" for a mod's key.
	static String label(String key) {
		String field = key.substring(key.lastIndexOf('.') + 1);
		String words = field.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		return withMod(key, words.isEmpty() ? key : Character.toUpperCase(words.charAt(0)) + words.substring(1));
	}

	private static String withMod(String key, String name) {
		for (Map.Entry<String, String> mod : MOD_NAMES.entrySet()) {
			if (key.startsWith(mod.getKey())) {
				return mod.getValue() + ": " + name;
			}
		}
		return name;
	}

	// "<name>: <current> → <target>", with the rules' settingLabels where they exist and the caption from the key otherwise.
	static String describe(SettingLabel label, String key, String current, String target) {
		String name = label != null && label.name != null && !label.name.isBlank() ? withMod(key, label.name) : label(key);
		return name + ": " + valueLabel(label, current) + " → " + valueLabel(label, target);
	}

	private static String valueLabel(SettingLabel label, String value) {
		if (label == null || label.values == null || value == null) {
			return value;
		}
		String exact = label.values.get(value);
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, String> entry : label.values.entrySet()) {
			if (entry.getValue() != null && same(entry.getKey(), value)) {
				return entry.getValue();
			}
		}
		return value;
	}

	private static String normalise(String value) {
		String s = value.trim();
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			s = s.substring(1, s.length() - 1).trim();
		}
		return s.toLowerCase(Locale.ROOT);
	}
}
