package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModJars {
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

	// Null when the jar has no fabric.mod.json id. Doesn't log: the apply helper runs without a logger on its classpath.
	static String readModId(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(reader);
				if (!root.isJsonObject() || !root.getAsJsonObject().has("id")) {
					return null;
				}
				JsonElement id = root.getAsJsonObject().get("id");
				return id.isJsonPrimitive() ? id.getAsString() : null;
			}
		} catch (RuntimeException e) {
			throw new IOException("Unreadable fabric.mod.json in " + jar, e);
		}
	}
}
