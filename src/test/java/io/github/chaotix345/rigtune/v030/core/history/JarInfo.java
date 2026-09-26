package io.github.chaotix345.rigtune.v030.core.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.v030.core.apply.ModJars;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
	private static final long MAX_NESTED_BYTES = 32L << 20;
	private static final int MAX_NESTED_JARS = 512;

	// How much of a jar's nesting is read: maxBytes in all, counting every byte buffered from a nested jar (an entry
	// that doesn't fit in what is left is skipped, and what it inflated to still counts), and the first maxJars nested
	// jars (re-check of review 4: a crafted jar can't make the game buffer more).
	private static final class Budget {
		long bytesLeft;
		int jarsLeft;

		Budget(long maxBytes, int maxJars) {
			this.bytesLeft = maxBytes;
			this.jarsLeft = maxJars;
		}

		// A nested jar, whose declared size must fit in the bytes left.
		boolean take(long size) {
			if (jarsLeft <= 0 || size > bytesLeft) {
				return false;
			}
			jarsLeft--;
			return true;
		}

		// Null when the entry is over max or over the bytes left.
		byte[] read(InputStream in, long max) throws IOException {
			long limit = Math.min(max, bytesLeft);
			byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
			bytesLeft -= Math.min(bytes.length, bytesLeft);
			return bytes.length > limit ? null : bytes;
		}
	}

	public JarInfo {
		provides = provides == null ? Set.of() : Set.copyOf(provides);
		depends = depends == null ? Set.of() : Set.copyOf(depends);
	}

	// Null when the file isn't a readable Fabric mod jar.
	public static JarInfo read(Path jar) {
		return read(jar, MAX_NESTED_BYTES, MAX_NESTED_JARS);
	}

	static JarInfo read(Path jar, long maxNestedBytes, int maxNestedJars) {
		Budget budget = new Budget(maxNestedBytes, maxNestedJars);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			byte[] json;
			try (InputStream in = zip.getInputStream(entry)) {
				json = ModJars.readFabricModJson(in, entry.getSize());
			}
			if (json == null) {
				return null;
			}
			JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject root = parsed.getAsJsonObject();
			String id = string(root.get("id"));
			if (id == null) {
				return null;
			}
			Set<String> provides = new HashSet<>(strings(root.get("provides")));
			for (String nested : nestedJars(root)) {
				ZipEntry nestedEntry = zip.getEntry(nested);
				if (nestedEntry != null && budget.take(nestedEntry.getSize() < 0 ? 0 : nestedEntry.getSize())) {
					try (InputStream in = zip.getInputStream(nestedEntry)) {
						byte[] bytes = budget.read(in, Long.MAX_VALUE);
						if (bytes != null) {
							nestedIds(bytes, 1, provides, budget);
						}
					}
				}
			}
			return new JarInfo(id, provides, keys(root.get("depends")));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void nestedIds(byte[] jar, int depth, Set<String> out, Budget budget) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
				boolean json = e.getName().equals("fabric.mod.json");
				if (json || e.getName().endsWith(".jar") && depth < MAX_NESTING) {
					byte[] bytes = budget.read(zip, json ? ModJars.MAX_FABRIC_MOD_JSON_BYTES : Long.MAX_VALUE);
					if (bytes != null) {
						entries.put(e.getName(), bytes);
					}
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
			// Its bytes were counted when it was buffered.
			if (inner != null && budget.take(0)) {
				nestedIds(inner, depth + 1, out, budget);
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
