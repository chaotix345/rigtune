package io.github.chaotix345.rigtune.core.profile;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

// Profile names are data from other players (share codes) or typed by this one (docs/v0.4/SPEC.md 4, AC4.3, P-L1): NFC;
// control, format (bidi overrides, zero-widths, U+FEFF), private-use, surrogate, unassigned and line/paragraph separator
// code points dropped; the formatting code sign `§` and the characters config syntaxes give a meaning (`=`, `#`, `"`,
// `\`) dropped; whitespace runs collapsed to one space; at most MAX_CODE_POINTS. Shown only as a literal and never used in
// a path (profile ids are generated).
public final class ProfileNames {
	public static final int MAX_CODE_POINTS = 32;
	// The share-code body keeps a name's UTF-8 length in one byte, capped here (docs/research/v0.4/profiles.md §5.2).
	public static final int MAX_UTF8_BYTES = 64;
	private static final String DROPPED = "§=#\"\\";

	private ProfileNames() {
	}

	// The sanitised name, or null when nothing is left (the caller shows its own default name).
	public static @Nullable String sanitise(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String nfc = Normalizer.normalize(raw.length() > 4096 ? raw.substring(0, 4096) : raw, Normalizer.Form.NFC);
		StringBuilder out = new StringBuilder();
		int kept = 0;
		boolean space = false;
		for (int i = 0; i < nfc.length() && kept < MAX_CODE_POINTS; ) {
			int cp = nfc.codePointAt(i);
			i += Character.charCount(cp);
			if (dropped(cp)) {
				continue;
			}
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				space = !out.isEmpty();
				continue;
			}
			if (space) {
				out.append(' ');
				kept++;
				space = false;
				if (kept >= MAX_CODE_POINTS) {
					break;
				}
			}
			out.appendCodePoint(cp);
			kept++;
		}
		String name = out.toString().strip();
		return name.isEmpty() ? null : name;
	}

	// The longest prefix (whole code points) whose UTF-8 form fits MAX_UTF8_BYTES.
	public static String fitUtf8(String name) {
		if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES) {
			return name;
		}
		StringBuilder out = new StringBuilder();
		int bytes = 0;
		for (int i = 0; i < name.length(); ) {
			int cp = name.codePointAt(i);
			i += Character.charCount(cp);
			int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
			if (bytes + size > MAX_UTF8_BYTES) {
				break;
			}
			out.appendCodePoint(cp);
			bytes += size;
		}
		return out.toString().strip();
	}

	static boolean dropped(int cp) {
		if (DROPPED.indexOf(cp) >= 0) {
			return true;
		}
		return switch (Character.getType(cp)) {
			case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
					Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
			default -> false;
		};
	}
}
