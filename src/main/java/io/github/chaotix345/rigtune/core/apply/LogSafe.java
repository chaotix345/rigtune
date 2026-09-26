package io.github.chaotix345.rigtune.core.apply;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What RigTune's log lines say about files and about text from files or the network. Players paste latest.log and
// helper.log into issues and chats, so a line names a file without its folders (a Windows path holds the account name;
// review-8 JW-1), and untrusted text can't start a new line or hide characters (review-8 SE-4). JDK only (helper-safe).
public final class LogSafe {
	static final int MAX_TEXT = 200;

	private LogSafe() {
	}

	// A file under config/rigtune by its path there ("awareness.json", "helper/unfinished-groups.json"), any other file
	// by its name.
	public static String name(Path file) {
		if (file == null) {
			return "null";
		}
		Path abs = file.toAbsolutePath().normalize();
		for (int i = abs.getNameCount() - 2; i >= 1; i--) {
			if (abs.getName(i).toString().equals("rigtune") && abs.getName(i - 1).toString().equals("config")) {
				return text(abs.subpath(i + 1, abs.getNameCount()).toString().replace(File.separatorChar, '/'));
			}
		}
		Path name = abs.getFileName();
		return text(name == null ? "" : name.toString());
	}

	// "Type: message" for a log line instead of the exception itself (whose message and stack trace print full paths),
	// plus "(caused by Type: message)" for its root cause, with the folders of `files` and the home folder cut out of the
	// messages (ignoring letter case on Windows).
	public static String error(Throwable e, Path... files) {
		if (e == null) {
			return "null";
		}
		Throwable root = e;
		for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 20; depth++) {
			root = root.getCause();
		}
		String out = describe(e, files);
		return text(root == e ? out : out + " (caused by " + describe(root, files) + ")");
	}

	private static String describe(Throwable e, Path... files) {
		String message = e.getMessage();
		if (message != null) {
			for (Path file : files) {
				Path dir = file == null ? null : file.toAbsolutePath().normalize().getParent();
				if (dir != null) {
					message = replace(replace(message, dir + File.separator, ""), dir.toString(), ".");
				}
			}
			String home = System.getProperty("user.home");
			if (home != null && home.length() > 1) {
				message = replace(message, home, "~");
			}
		}
		return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	private static String replace(String text, String target, String replacement) {
		int flags = File.separatorChar == '\\' ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
		return Pattern.compile(Pattern.quote(target), flags).matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
	}

	// Text from a file or the network, for one log line: control, format (bidi overrides, zero-width) and line-separator
	// characters escaped (backslash, u, 4 hex digits), at most MAX_TEXT characters.
	public static String text(Object value) {
		String raw = String.valueOf(value);
		StringBuilder out = new StringBuilder();
		int kept = 0;
		for (int i = 0; i < raw.length(); ) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (kept == MAX_TEXT) {
				out.append('…');
				break;
			}
			if (hidden(cp)) {
				out.append(escape(cp));
			} else {
				out.appendCodePoint(cp);
			}
			kept++;
		}
		return out.toString();
	}

	// A hidden character as it is written in logs and messages: backslash, u, 4 hex digits (U and 8 beyond U+FFFF).
	static String escape(int cp) {
		return cp <= 0xFFFF ? String.format("\\u%04x", cp) : String.format("\\U%08x", cp);
	}

	static boolean hidden(int cp) {
		return switch (Character.getType(cp)) {
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
			default -> false;
		};
	}
}
