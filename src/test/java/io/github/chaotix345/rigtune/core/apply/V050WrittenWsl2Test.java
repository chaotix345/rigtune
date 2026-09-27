package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.history.LauncherRepair;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 3b and X11 (src/test/resources/v050-written/README.md): WS-L2's "written by 0.5" set ws-l2, produced
// here by the real code: pending.json as the 0.5 helper leaves it after an exit in a launcher-managed instance (a 0.4
// update group held, its Sodium patch applied: 4d), and awareness.json with the repair notice's key dismissed (4g).
// Paths start with ${INSTANCE} and use '/', ids are fixed UUIDs, times are in the past. By default the committed files
// must equal what the code writes now; RIGTUNE_REGENERATE_FIXTURES=1 writes them. expect.json (hand-written: what 0.4.0's
// own classes must do with the set) must name this pending.json's group.
class V050WrittenWsl2Test {
	private static final String SET = "src/test/resources/v050-written/ws-l2/";
	private static final String TOKEN = "${INSTANCE}";
	private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final List<String> FIXED_IDS = List.of("a1b2c3d4-5e6f-4a0b-8c1d-2e3f4a5b6c01", "a1b2c3d4-5e6f-4a0b-8c1d-2e3f4a5b6c02",
			"a1b2c3d4-5e6f-4a0b-8c1d-2e3f4a5b6c03");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	@TempDir
	Path dir;

	@Test
	void theCommittedSetIsWhatThisVersionWrites() throws IOException {
		Path instance = dir.resolve("instance");
		Path mods = Files.createDirectories(instance.resolve("mods"));
		Path config = Files.createDirectories(instance.resolve("config"));
		Path pending = PendingActions.defaultPath(config);
		Path old = TestJars.modJar(mods.resolve("e2e-held-1.0.0.jar"), "e2e-held");
		Path download = TestJars.modJar(mods.resolve("e2e-held-1.1.0.jar" + PendingActions.PENDING_SUFFIX), "e2e-held");
		Path sodium = Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0}}");
		List<Op> group = PendingActions.group(Op.disableFile(old), Op.enableFile(download, mods.resolve("e2e-held-1.1.0.jar")).withModId("e2e-held"));
		PendingActions.create(4242, mods, config, List.of(group.get(0), group.get(1),
				Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4")))).save(pending);

		ApplyResult held = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending, true);
		assertEquals(1, held.results().size(), held.toString());
		AwarenessStore awareness = AwarenessStore.shared(config);
		String key = new LauncherRepair.Findings(List.of(new LauncherRepair.Pair("e2e-pair", "e2e-pair-1.0.0.jar.disabled", "e2e-pair-1.1.0.jar")),
				List.of(), List.of()).key(Launcher.MODRINTH_APP);
		assertTrue(awareness.dismiss(key));

		Map<String, String> uuids = new LinkedHashMap<>();
		Map<String, String> written = new LinkedHashMap<>();
		written.put("pending.json", normalise(pending, instance, uuids, json -> json.addProperty("createdAt", "2026-09-20T10:00:05Z")));
		written.put("awareness.json", normalise(AwarenessStore.file(config), instance, uuids, json -> {
		}));
		Path committed = RepoFiles.resolve(SET);
		for (Map.Entry<String, String> file : written.entrySet()) {
			Path target = committed.resolve(file.getKey());
			if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
				Files.createDirectories(committed);
				Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
			}
			assertEquals(file.getValue(), Files.readString(target, StandardCharsets.UTF_8).replace("\r\n", "\n"),
					target + " is not what 0.5 writes now; regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		}

		// The set is what the tools expect: the held group, as the helper left it, is the one expect.json has 0.4.0 apply.
		JsonObject plan = JsonParser.parseString(written.get("pending.json")).getAsJsonObject();
		JsonArray ops = plan.getAsJsonArray("ops");
		assertEquals(2, ops.size());
		String groupId = ops.get(0).getAsJsonObject().get("group").getAsString();
		assertTrue(ops.asList().stream().allMatch(op -> op.getAsJsonObject().get("attempts").getAsInt() == 0), ops.toString());
		JsonObject expect = JsonParser.parseString(Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("ws-l2", expect.get("set").getAsString());
		assertTrue(expect.getAsJsonArray("checks").asList().stream().anyMatch(c -> c.getAsJsonObject().has("appliesGroup")
				&& c.getAsJsonObject().get("appliesGroup").getAsString().equals(groupId)), expect.toString());
		assertTrue(written.get("awareness.json").contains(key), written.get("awareness.json"));
		try (var files = Files.list(committed)) {
			assertEquals(Set.of("pending.json", "awareness.json", "expect.json"), Set.copyOf(files.map(p -> p.getFileName().toString()).toList()));
		}
	}

	// The file with the instance folder as ${INSTANCE} (and '/'), every UUID replaced by the next fixed one in order of first
	// appearance, and `fix` applied; pretty-printed with a final newline.
	private static String normalise(Path file, Path instance, Map<String, String> uuids, java.util.function.Consumer<JsonObject> fix)
			throws IOException {
		JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject out = (JsonObject) rewrite(json, instance.toString(), uuids);
		fix.accept(out);
		return GSON.toJson(out) + "\n";
	}

	private static JsonElement rewrite(JsonElement element, String instance, Map<String, String> uuids) {
		if (element.isJsonObject()) {
			JsonObject out = new JsonObject();
			element.getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), rewrite(e.getValue(), instance, uuids)));
			return out;
		}
		if (element.isJsonArray()) {
			JsonArray out = new JsonArray();
			element.getAsJsonArray().forEach(e -> out.add(rewrite(e, instance, uuids)));
			return out;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String value = element.getAsString();
			if (value.startsWith(instance)) {
				value = TOKEN + value.substring(instance.length()).replace('\\', '/');
			}
			Matcher m = UUID.matcher(value);
			StringBuilder replaced = new StringBuilder();
			while (m.find()) {
				String fixed = uuids.computeIfAbsent(m.group(), k -> FIXED_IDS.get(uuids.size()));
				m.appendReplacement(replaced, fixed);
			}
			m.appendTail(replaced);
			return new JsonPrimitive(replaced.toString());
		}
		return element;
	}
}
