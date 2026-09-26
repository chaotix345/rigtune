package io.github.chaotix345.rigtune.core.report;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

// Text from outside RigTune (hardware names, mod titles, rule titles) in Markdown for a Discord message or an issue:
// the share report and the Stutter Doctor's summary.
public final class MarkdownSafe {
	private static final int FIELD_LIMIT = 120;
	private static final String PATH = "(path)";
	// A drive, home or root start, then folders that may contain spaces (each ends at a separator), then a last
	// segment without spaces. Finally any word with a backslash (relative Windows paths, UNC paths).
	private static final List<Pattern> PATHS = List.of(
			Pattern.compile("(?i)(?<!\\w)[a-z]:[\\\\/](?:[^\\\\/\\r\\n]*[\\\\/])*[^\\s\\\\/]*"),
			Pattern.compile("~[\\\\/](?:[^\\\\/\\r\\n]*[\\\\/])*[^\\s\\\\/]*"),
			Pattern.compile("(?<![\\w:/.])/[^\\s/][^/\\r\\n]*/(?:[^/\\r\\n]*/)*[^\\s/]*"),
			Pattern.compile("\\S*\\\\\\S*"));
	private static final String MARKDOWN = "\\*_~`|[]<";

	private MarkdownSafe() {
	}

	// No paths, bounded, and inert as Markdown, so a mod called "*@everyone*" can't format the message or ping anyone.
	public static String field(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return "?";
		}
		return escape(clip(scrub(value.strip().replaceAll("\\s+", " ")), FIELD_LIMIT));
	}

	// Replaces anything that looks like a file path, which is where user names would show up.
	static String scrub(String text) {
		String out = text;
		for (Pattern pattern : PATHS) {
			out = pattern.matcher(out).replaceAll(PATH);
		}
		return out;
	}

	// At most max characters, ending in "…", never splitting a surrogate pair.
	static String clip(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		int end = max - 1;
		if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end) + "…";
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (MARKDOWN.indexOf(c) >= 0) {
				out.append('\\');
			}
			out.append(c);
			if (c == '@') {
				out.append('\u200B');
			}
		}
		return out.toString();
	}
}
