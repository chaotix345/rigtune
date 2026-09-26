package io.github.chaotix345.rigtune.v030.core.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SafeFileNames {
	public static final int MAX_LENGTH = 255;
	private static final String FORBIDDEN = "/\\:<>\"|?*";
	private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "COM¹", "COM²", "COM³",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT¹", "LPT²", "LPT³");
	private static final Pattern SHORT_NAME = Pattern.compile("~\\d");

	private SafeFileNames() {
	}

	public static boolean isSafeJarName(String name) {
		return problem(name) == null;
	}

	public static String requireJarName(String name) throws IOException {
		String problem = problem(name);
		if (problem != null) {
			throw new IOException("Unsafe file name " + quote(name) + ": " + problem);
		}
		return name;
	}

	// Resolves a bare .jar name (plus an optional fixed suffix) inside dir and checks it can't escape.
	public static Path resolveJar(Path dir, String name, String suffix) throws IOException {
		requireJarName(name);
		Path target = dir.resolve(name + suffix);
		if (!isDirectChild(dir, target)) {
			throw new IOException("Unsafe file name " + quote(name) + ": resolves outside " + dir);
		}
		return target;
	}

	public static Path resolveJar(Path dir, String name) throws IOException {
		return resolveJar(dir, name, "");
	}

	public static boolean isDirectChild(Path dir, Path file) {
		if (dir == null || file == null) {
			return false;
		}
		Path parent = file.toAbsolutePath().normalize().getParent();
		return parent != null && canonical(parent).equals(canonical(dir));
	}

	public static boolean isInside(Path dir, Path file) {
		if (dir == null || file == null) {
			return false;
		}
		Path d = canonical(dir);
		Path f = file.toAbsolutePath().normalize();
		if (f.getParent() == null) {
			return false;
		}
		f = canonical(f.getParent()).resolve(f.getFileName());
		return !f.equals(d) && f.startsWith(d);
	}

	// Fabric reports mod paths under the real path of the mods folder, so folders are compared by real path (symlinks,
	// letter case and short names resolved) as far as they exist. The file itself is left alone.
	static Path canonical(Path path) {
		Path abs = path.toAbsolutePath().normalize();
		Path existing = abs;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		if (existing == null) {
			return abs;
		}
		try {
			return existing.toRealPath().resolve(existing.relativize(abs));
		} catch (IOException e) {
			return abs;
		}
	}

	static String problem(String name) {
		if (name == null || name.isEmpty()) {
			return "empty";
		}
		if (name.length() > MAX_LENGTH) {
			return "longer than " + MAX_LENGTH + " characters";
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 0x20 || c == 0x7f) {
				return "contains a control character";
			}
			if (FORBIDDEN.indexOf(c) >= 0) {
				return "contains '" + c + "'";
			}
		}
		if (name.startsWith(".")) {
			return "starts with a dot";
		}
		char last = name.charAt(name.length() - 1);
		if (last == '.' || last == ' ') {
			return "ends with a dot or space";
		}
		if (!name.endsWith(".jar") || name.length() == ".jar".length()) {
			return "not a .jar file";
		}
		int dot = name.indexOf('.');
		String stem = (dot < 0 ? name : name.substring(0, dot)).stripTrailing().toUpperCase(Locale.ROOT);
		if (RESERVED.contains(stem)) {
			return "reserved device name";
		}
		// On NTFS "SODIUM~1.jar" can be the short alias of an existing long-named jar, so Files.exists would find it.
		if (SHORT_NAME.matcher(stem).find()) {
			return "looks like an 8.3 short name";
		}
		try {
			Path path = Path.of(name);
			if (path.getFileName() == null || !path.getFileName().toString().equals(name) || path.getNameCount() != 1) {
				return "not a bare file name";
			}
		} catch (InvalidPathException e) {
			return "not a valid path";
		}
		return null;
	}

	private static String quote(String name) {
		if (name == null) {
			return "null";
		}
		StringBuilder out = new StringBuilder("\"");
		name.codePoints().limit(80).forEach(cp -> {
			if (cp < 0x20 || cp == 0x7f) {
				out.append(String.format("\\u%04x", cp));
			} else {
				out.appendCodePoint(cp);
			}
		});
		return out.append(name.length() > 80 ? "…\"" : "\"").toString();
	}
}
