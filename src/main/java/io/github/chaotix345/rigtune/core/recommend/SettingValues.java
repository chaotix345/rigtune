package io.github.chaotix345.rigtune.core.recommend;

import com.google.gson.JsonElement;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;

import java.math.BigDecimal;
import java.util.Locale;

final class SettingValues {
	static final String REFRESH_RATE = "$refreshRate";
	static final String REFRESH_RATE_CAP = "$refreshRateCap";

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
		if (trimmed.equals(REFRESH_RATE)) {
			return Integer.toString(hz);
		}
		if (trimmed.equals(REFRESH_RATE_CAP)) {
			return Integer.toString(Math.max(30, hz - 3));
		}
		return value;
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

	static String label(String key) {
		String field = key.substring(key.lastIndexOf('.') + 1);
		String words = field.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		String text = key.startsWith("sodium.") ? "Sodium " + words : words;
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static String normalise(String value) {
		String s = value.trim();
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			s = s.substring(1, s.length() - 1).trim();
		}
		return s.toLowerCase(Locale.ROOT);
	}
}
