package io.github.chaotix345.rigtune.core.model;

import org.jspecify.annotations.Nullable;

// Text from outside RigTune shown in the game (review-8 SE-2): rule titles and texts from the rules feed, setting labels,
// mod and file names, GPU, CPU and JVM strings. Minecraft's font applies formatting codes found in plain text, so a
// formatting code (U+00A7 and the character after it) is removed, and so are control, format (bidi overrides,
// zero-widths, U+FEFF), private-use, surrogate and unassigned characters; tabs, line breaks and line/paragraph separators
// become a space. Everything else (other scripts, symbols, punctuation) is kept as it is. Display only: what files and the
// share report store is unchanged.
public final class SafeText {
	private static final int SECTION = 0x00A7;

	private SafeText() {
	}

	public static String clean(@Nullable String raw) {
		if (raw == null) {
			return "";
		}
		if (safe(raw)) {
			return raw;
		}
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); ) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == SECTION) {
				i += i < raw.length() ? Character.charCount(raw.codePointAt(i)) : 0;
				continue;
			}
			if (space(cp)) {
				out.append(' ');
			} else if (!dropped(cp)) {
				out.appendCodePoint(cp);
			}
		}
		return out.toString();
	}

	private static boolean safe(String raw) {
		for (int i = 0; i < raw.length(); ) {
			int cp = raw.codePointAt(i);
			if (cp == SECTION || space(cp) || dropped(cp)) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return true;
	}

	private static boolean space(int cp) {
		return cp == '\t' || cp == '\n' || cp == '\r' || cp == 0x0B || cp == '\f' || cp == 0x85
				|| Character.getType(cp) == Character.LINE_SEPARATOR || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR;
	}

	private static boolean dropped(int cp) {
		return switch (Character.getType(cp)) {
			case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true;
			default -> false;
		};
	}
}
