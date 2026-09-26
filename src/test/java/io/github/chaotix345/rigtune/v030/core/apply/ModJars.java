package io.github.chaotix345.rigtune.v030.core.apply;

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
				if (!root.isJsonObject() || !root.getAsJsonObject().has("id")) {
					return null;
				}
				JsonElement id = root.getAsJsonObject().get("id");
				return id.isJsonPrimitive() ? id.getAsString() : null;
			} catch (JsonParseException e) {
				return null;
			}
		} catch (RuntimeException e) {
			throw new IOException("Unreadable fabric.mod.json in " + jar, e);
		}
	}
}
