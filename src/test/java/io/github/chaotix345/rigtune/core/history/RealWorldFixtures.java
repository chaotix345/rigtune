package io.github.chaotix345.rigtune.core.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The templated real-world fixtures (src/test/resources/realworld/README.md; docs/v0.5/PLAN.md PLAN-11), for WS-L1's
// RealWorldUndoTest and WS-L2's tests: each file with ${INSTANCE} put in, the history as Journal reads it, and the copied
// mods folder as UndoPlanner's state.
public final class RealWorldFixtures {
	public static final String CAPTURE = "2026-09-27";
	public static final String TOKEN = "${INSTANCE}";
	// The legacy-import entry: RigTune 0.1.0's apply, as 0.4.0 imported it.
	public static final String LEGACY_ENTRY = "9bd46b37-812c-430e-b725-f63d72ddca39";
	public static final String DH = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar";
	public static final String DH_OLD = "fabric-26.2.jar";

	private RealWorldFixtures() {
	}

	// A fixture file (relative to the capture's folder) with the token replaced by the instance folder ('/' separators).
	public static String text(String relative, Path instance) {
		String resource = "/realworld/" + CAPTURE + "/" + relative;
		try (InputStream in = RealWorldFixtures.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " isn't on the test classpath");
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return instance == null ? text : text.replace(TOKEN, instance.toAbsolutePath().toString().replace('\\', '/'));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// Writes the capture's config/rigtune/ files into <instance>/config/rigtune/ and returns <instance>/config.
	public static Path config(Path instance) {
		Path rigtune = instance.resolve("config").resolve("rigtune");
		try {
			Files.createDirectories(rigtune);
			for (String name : List.of("history.json", "last-apply.json", "helper.log")) {
				Files.writeString(rigtune.resolve(name), text("config/rigtune/" + name, instance), StandardCharsets.UTF_8);
			}
			Files.createDirectories(instance.resolve("mods"));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return instance.resolve("config");
	}

	// Writes 0.1.0's pending.json from before 0.4.0's helper run into <instance>/config/rigtune/pending.json.
	public static Path seededPending(Path instance) {
		Path pending = instance.resolve("config").resolve("rigtune").resolve("pending.json");
		try {
			Files.createDirectories(pending.getParent());
			Files.createDirectories(instance.resolve("mods"));
			Files.writeString(pending, text("seeded/pending-0.1.0.json", instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return pending;
	}

	// history.json as 0.4.0's helper left it (the legacy-import entry, every change APPLIED).
	public static List<JournalEntry> history(Path instance) {
		return new Journal(config(instance), "0.4.0+mc26.2", "26.2", (m, e) -> {}).entries();
	}

	// The instance's mods/ file names at the copy, in listing order.
	public static List<String> modsListing() {
		return text("mods-listing.txt", null).lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
	}

	// The mod id each jar RigTune's records name declares.
	public static Map<String, String> modIds() {
		JsonObject ids = JsonParser.parseString(text("mod-ids.json", null)).getAsJsonObject();
		Map<String, String> out = new LinkedHashMap<>();
		ids.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		return out;
	}

	// The copied mods folder as UndoPlanner sees it: every listed file, with its mod id (other:<file> for jars no record
	// names).
	static UndoPlannerTest.FakeState folder(UndoPlannerTest.FakeState state) {
		Map<String, String> ids = modIds();
		for (String name : modsListing()) {
			state.jar(name, ids.getOrDefault(name, "other:" + name));
		}
		return state;
	}
}
