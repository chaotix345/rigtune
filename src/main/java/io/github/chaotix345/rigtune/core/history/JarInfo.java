package io.github.chaotix345.rigtune.core.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

// What a mod jar's fabric.mod.json says, for the undo planner's dependency check (review M9). provides: the jar's
// `provides` plus the ids (and provides) of the jars nested in it; depends: the mod ids in its `depends`.
public record JarInfo(String id, Set<String> provides, Set<String> depends) {
	private static final int MAX_NESTING = 3;

	public JarInfo {
		provides = provides == null ? Set.of() : Set.copyOf(provides);
		depends = depends == null ? Set.of() : Set.copyOf(depends);
	}

	// Null when the file isn't a readable Fabric mod jar.
	public static JarInfo read(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			JsonObject root;
			try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (!parsed.isJsonObject()) {
					return null;
				}
				root = parsed.getAsJsonObject();
			}
			String id = string(root.get("id"));
			if (id == null) {
				return null;
			}
			Set<String> provides = new HashSet<>(strings(root.get("provides")));
			for (String nested : nestedJars(root)) {
				ZipEntry nestedEntry = zip.getEntry(nested);
				if (nestedEntry != null) {
					try (InputStream in = zip.getInputStream(nestedEntry)) {
						nestedIds(in.readAllBytes(), 1, provides);
					}
				}
			}
			return new JarInfo(id, provides, keys(root.get("depends")));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void nestedIds(byte[] jar, int depth, Set<String> out) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
				if (e.getName().equals("fabric.mod.json") || e.getName().endsWith(".jar") && depth < MAX_NESTING) {
					entries.put(e.getName(), zip.readAllBytes());
				}
			}
		}
		byte[] json = entries.get("fabric.mod.json");
		if (json == null) {
			return;
		}
		JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
		if (!parsed.isJsonObject()) {
			return;
		}
		JsonObject root = parsed.getAsJsonObject();
		String id = string(root.get("id"));
		if (id != null) {
			out.add(id);
		}
		out.addAll(strings(root.get("provides")));
		for (String nested : nestedJars(root)) {
			byte[] inner = entries.get(nested);
			if (inner != null) {
				nestedIds(inner, depth + 1, out);
			}
		}
	}

	private static Set<String> nestedJars(JsonObject root) {
		Set<String> out = new HashSet<>();
		if (root.get("jars") instanceof JsonArray jars) {
			for (JsonElement jar : jars) {
				if (jar.isJsonObject()) {
					String file = string(jar.getAsJsonObject().get("file"));
					if (file != null) {
						out.add(file);
					}
				}
			}
		}
		return out;
	}

	private static String string(JsonElement element) {
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static Set<String> strings(JsonElement element) {
		Set<String> out = new HashSet<>();
		if (element instanceof JsonArray array) {
			for (JsonElement e : array) {
				String s = string(e);
				if (s != null) {
					out.add(s);
				}
			}
		}
		return out;
	}

	private static Set<String> keys(JsonElement element) {
		return element instanceof JsonObject object ? new HashSet<>(object.keySet()) : Set.of();
	}
}
