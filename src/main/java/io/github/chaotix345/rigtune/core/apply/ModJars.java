package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModJars {
	// No real fabric.mod.json comes near this; a bigger (or decompression-bomb) entry isn't read (review 4, security-1).
	public static final int MAX_FABRIC_MOD_JSON_BYTES = 1 << 20;
	// docs/v0.4/SPEC.md 2c, plan review P-L1: the most of a mod's display name History keeps.
	public static final int MAX_NAME_CODE_POINTS = 64;

	private ModJars() {
	}

	public static String modIdOf(Path jar) {
		try {
			return readModId(jar);
		} catch (NoSuchFileException e) {
			// Normal for the 0.1.x history import: jars 0.1.x disabled or replaced are often gone by now.
			RigTune.LOGGER.debug("No mod id for {}: the file is gone", jar);
			return null;
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not read the mod id of {}", jar, e);
			return null;
		}
	}

	// docs/v0.4/SPEC.md 2c: the mod's display name (fabric.mod.json "name"), sanitised; null when the jar is gone, isn't
	// readable or has no name (as modIdOf). History shows it instead of the file name.
	public static String nameOf(Path jar) {
		try {
			return sanitizeName(readField(jar, "name"));
		} catch (NoSuchFileException e) {
			RigTune.LOGGER.debug("No mod name for {}: the file is gone", jar);
			return null;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.debug("Could not read the mod name of {}: {}", jar, e.getMessage());
			return null;
		}
	}

	// docs/v0.4/SPEC.md 2o, H2: the mod's version (fabric.mod.json "version"), which the installed mods' version ranges are
	// matched against; null as nameOf.
	public static String versionOf(Path jar) {
		try {
			return readField(jar, "version");
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.debug("Could not read the version of {}: {}", jar, e.getMessage());
			return null;
		}
	}

	// A downloaded file's text shown in the UI (plan review P-L1): no formatting code (U+00A7 and the code after it), no
	// control, format, separator, private-use or unassigned characters, runs of whitespace (tabs and newlines included) as
	// one space, at most MAX_NAME_CODE_POINTS; null when nothing is left.
	public static String sanitizeName(String raw) {
		if (raw == null) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		int kept = 0;
		for (int i = 0; i < raw.length() && kept < MAX_NAME_CODE_POINTS; ) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == 0x00A7) {
				i += i < raw.length() ? Character.charCount(raw.codePointAt(i)) : 0;
				continue;
			}
			boolean space = Character.isWhitespace(cp) || Character.isSpaceChar(cp);
			if (!space && unsafe(cp)) {
				continue;
			}
			if (space) {
				if (out.isEmpty() || out.charAt(out.length() - 1) == ' ') {
					continue;
				}
				cp = ' ';
			}
			out.appendCodePoint(cp);
			kept++;
		}
		String name = out.toString().strip();
		return name.isEmpty() ? null : name;
	}

	private static boolean unsafe(int cp) {
		return switch (Character.getType(cp)) {
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
					Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
			default -> false;
		};
	}

	// The mod ids of the jars a mod's own updater left in <mods>/update/, directly or in a folder of their own (Distant
	// Horizons: update/<build>/<file>.jar). Unreadable jars don't count.
	public static Set<String> queuedUpdates(Path modsDir) {
		Path update = modsDir.resolve("update");
		if (!Files.isDirectory(update)) {
			return Set.of();
		}
		Set<String> out = new HashSet<>();
		try (Stream<Path> files = Files.walk(update, 2)) {
			for (Path jar : files.filter(f -> f.getFileName().toString().endsWith(".jar") && Files.isRegularFile(f)).toList()) {
				try {
					String id = readModId(jar);
					if (id != null) {
						out.add(id);
					}
				} catch (IOException e) {
					// Not a readable jar: nothing is queued by it.
				}
			}
		} catch (IOException | UncheckedIOException e) {
			RigTune.LOGGER.warn("Could not list {}", update, e);
		}
		return Set.copyOf(out);
	}

	// The bytes of a fabric.mod.json entry; null when it declares or inflates to more than the cap. Bounded whatever
	// size the entry declares: an entry can inflate to far more.
	public static byte[] readFabricModJson(InputStream in, long declaredSize) throws IOException {
		if (declaredSize > MAX_FABRIC_MOD_JSON_BYTES) {
			return null;
		}
		byte[] json = in.readNBytes(MAX_FABRIC_MOD_JSON_BYTES + 1);
		return json.length > MAX_FABRIC_MOD_JSON_BYTES ? null : json;
	}

	// Null when the jar has no fabric.mod.json id, or its fabric.mod.json is over the cap or isn't JSON. Doesn't log:
	// the apply helper runs without a logger on its classpath.
	static String readModId(Path jar) throws IOException {
		return readField(jar, "id");
	}

	// A top-level string field of the jar's fabric.mod.json, or null (as readModId).
	private static String readField(Path jar, String field) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			byte[] json;
			try (InputStream in = zip.getInputStream(entry)) {
				json = readFabricModJson(in, entry.getSize());
			}
			if (json == null) {
				return null;
			}
			try {
				JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
				if (!root.isJsonObject() || !root.getAsJsonObject().has(field)) {
					return null;
				}
				JsonElement value = root.getAsJsonObject().get(field);
				return value.isJsonPrimitive() ? value.getAsString() : null;
			} catch (JsonParseException e) {
				return null;
			}
		} catch (RuntimeException e) {
			throw new IOException("Unreadable fabric.mod.json in " + jar, e);
		}
	}
}
