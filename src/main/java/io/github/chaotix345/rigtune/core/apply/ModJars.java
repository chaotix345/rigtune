package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModJars {
	// No real fabric.mod.json comes near this; a bigger (or decompression-bomb) entry isn't read (review 4, security-1).
	static final int MAX_FABRIC_MOD_JSON_BYTES = 1 << 20;

	private ModJars() {
	}

	public static String modIdOf(Path jar) {
		try {
			return readModId(jar);
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not read the mod id of {}", jar, e);
			return null;
		}
	}

	// Null when the jar has no fabric.mod.json id, or its fabric.mod.json is over the cap or isn't JSON. Doesn't log:
	// the apply helper runs without a logger on its classpath.
	static String readModId(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null || entry.getSize() > MAX_FABRIC_MOD_JSON_BYTES) {
				return null;
			}
			// Bounded whatever size the entry declares: an entry can inflate to far more.
			byte[] json;
			try (InputStream in = zip.getInputStream(entry)) {
				json = in.readNBytes(MAX_FABRIC_MOD_JSON_BYTES + 1);
			}
			if (json.length > MAX_FABRIC_MOD_JSON_BYTES) {
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
