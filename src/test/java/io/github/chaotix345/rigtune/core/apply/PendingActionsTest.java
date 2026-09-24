package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingActionsTest {
	@Test
	void roundTripsThroughJson(@TempDir Path dir) throws IOException {
		Path mods = dir.resolve("mods");
		Path config = dir.resolve("config");
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("performance.chunk_builder_threads", "4");
		patches.put("quality.weather_quality", "FAST");
		PendingActions plan = PendingActions.create(4242, mods, config, List.of(
				Op.disableFile(mods.resolve("old.jar")),
				Op.enableFile(mods.resolve("new.jar.rigtune-pending"), mods.resolve("new.jar")),
				Op.patchJson(config.resolve("sodium-options.json"), patches)));
		Path file = PendingActions.defaultPath(config);

		plan.save(file);
		PendingActions loaded = PendingActions.load(file);

		assertEquals(plan, loaded);
		assertEquals(Type.ENABLE_FILE, loaded.ops().get(1).type());
		assertEquals(List.copyOf(patches.keySet()), List.copyOf(loaded.ops().get(2).patches().keySet()));
	}

	@Test
	void opsAreTaggedByTypeAndOmitUnusedFields(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("pending.json");
		PendingActions.create(1, dir, dir, List.of(Op.disableFile(dir.resolve("a.jar")))).save(file);

		JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		JsonObject op = root.getAsJsonArray("ops").get(0).getAsJsonObject();
		assertEquals("DISABLE_FILE", op.get("type").getAsString());
		assertEquals(dir.resolve("a.jar").toString(), op.get("path").getAsString());
		assertFalse(op.has("from"));
		assertFalse(op.has("patches"));
		assertEquals(1, root.get("gamePid").getAsLong());
	}

	@Test
	void opsCarryIdsGroupsAndModIdsThroughJson(@TempDir Path dir) throws IOException {
		Path mods = dir.resolve("mods");
		List<Op> update = PendingActions.group(Op.disableFile(mods.resolve("old.jar")),
				Op.enableFile(mods.resolve("new.jar.rigtune-pending"), mods.resolve("new.jar")).withModId("sodium"));
		Op alone = Op.disableFile(mods.resolve("x.jar"));

		assertNotNull(update.get(0).id());
		assertNotEquals(update.get(0).id(), update.get(1).id());
		assertNotNull(update.get(0).group());
		assertEquals(update.get(0).group(), update.get(1).group());
		assertNotEquals(update.get(0).group(), PendingActions.group(alone).getFirst().group());
		assertNull(alone.group());
		assertEquals("sodium", update.get(1).modId());

		Path file = dir.resolve("pending.json");
		PendingActions.create(1, mods, dir, update).save(file);
		assertEquals(update, PendingActions.load(file).ops());
		JsonObject enable = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("ops").get(1).getAsJsonObject();
		assertEquals("sodium", enable.get("modId").getAsString());
		assertEquals(update.get(1).group(), enable.get("group").getAsString());
	}

	@Test
	void sameChangeIgnoresIdentityButSameOpDoesNot() {
		Path jar = Path.of("mods", "a.jar");
		Op a = Op.disableFile(jar);
		Op b = Op.disableFile(jar);

		assertTrue(a.sameChange(b));
		assertTrue(a.sameChange(b.inGroup("g").withModId("m")));
		assertFalse(a.sameOp(b));
		assertTrue(a.sameOp(a.inGroup("g")));
		assertFalse(a.sameChange(Op.disableFile(Path.of("mods", "b.jar"))));
		Op legacy = new Op(Type.DISABLE_FILE, null, null, jar.toString(), null);
		assertTrue(legacy.sameOp(new Op(Type.DISABLE_FILE, null, null, jar.toString(), null)));
		assertFalse(legacy.sameOp(a));
	}

	@Test
	void loadsHandWrittenPlanAndRejectsGarbage(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("pending.json");
		Files.writeString(file, """
				{"createdAt":"2026-09-24T00:00:00Z","gamePid":7,"modsDir":"m","configDir":"c",
				 "ops":[{"type":"ENABLE_FILE","from":"m/a.jar.rigtune-pending","to":"m/a.jar"}]}""");
		assertEquals(new Op(Type.ENABLE_FILE, "m/a.jar.rigtune-pending", "m/a.jar", null, null), PendingActions.load(file).ops().getFirst());

		Files.writeString(file, "{\"createdAt\":\"x\"}");
		assertEquals(List.of(), PendingActions.load(file).ops());

		Files.writeString(file, "not json {");
		assertThrows(IOException.class, () -> PendingActions.load(file));
	}
}
