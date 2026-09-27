package io.github.chaotix345.rigtune.core.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore;
import io.github.chaotix345.rigtune.core.stutter.FixStore;
import io.github.chaotix345.rigtune.core.tryit.TryItStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md C1 and X7 (PLAN contracts item 9): the three new state files' shells, one contract for all of them.
// Each test gets its own config dir, so each store is a fresh shared instance.
class V05StoreShellsTest {
	private record Shell(String name, long maxBytes, Function<Path, Path> file, Function<Path, JsonObject> read,
			BiPredicate<Path, UnaryOperator<JsonObject>> update, Predicate<Path> writable) {
	}

	private static final List<Shell> SHELLS = List.of(
			new Shell(FixStore.FILE_NAME, FixStore.MAX_BYTES, FixStore::file, d -> FixStore.shared(d).read(), (d, c) -> FixStore.shared(d).update(c),
					d -> FixStore.shared(d).writable()),
			new Shell(TryItStore.FILE_NAME, TryItStore.MAX_BYTES, TryItStore::file, d -> TryItStore.shared(d).read(), (d, c) -> TryItStore.shared(d).update(c),
					d -> TryItStore.shared(d).writable()),
			new Shell(ServerProfileStore.FILE_NAME, ServerProfileStore.MAX_BYTES, ServerProfileStore::file, d -> ServerProfileStore.shared(d).read(),
					(d, c) -> ServerProfileStore.shared(d).update(c), d -> ServerProfileStore.shared(d).writable()));

	@TempDir
	Path dir;

	private Path configDir(Shell shell) throws IOException {
		return Files.createDirectories(dir.resolve(shell.name()));
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	@Test
	void namesAndCaps() {
		assertEquals(List.of("stutter-fixes.json", "tryit.json", "server-profiles.json"), SHELLS.stream().map(Shell::name).toList());
		assertEquals(List.of(32L * 1024, 16L * 1024, 16L * 1024), SHELLS.stream().map(Shell::maxBytes).toList());
		assertEquals(Path.of("c", "rigtune", "tryit.json"), TryItStore.file(Path.of("c")));
	}

	@Test
	void aMissingFileLoadsEmptyAndWritableAndTheFirstWriteHasFormatVersion1() throws IOException {
		for (Shell shell : SHELLS) {
			Path config = configDir(shell);
			assertEquals(new JsonObject(), shell.read().apply(config), shell.name());
			assertTrue(shell.writable().test(config), shell.name());
			assertFalse(Files.exists(shell.file().apply(config)), shell.name() + ": reading writes nothing");
			assertTrue(shell.update().test(config, root -> {
				root.addProperty("x", 1);
				return root;
			}), shell.name());
			JsonObject written = JsonParser.parseString(Files.readString(shell.file().apply(config))).getAsJsonObject();
			assertEquals(1, written.get("formatVersion").getAsInt(), shell.name());
			assertEquals(1, written.get("x").getAsInt(), shell.name());
		}
	}

	@Test
	void aCorruptFileIsMovedToBadAndTheStoreStartsEmpty() throws IOException {
		for (Shell shell : SHELLS) {
			Path config = configDir(shell);
			Path file = shell.file().apply(config);
			write(file, "{not json");
			assertEquals(new JsonObject(), shell.read().apply(config), shell.name());
			assertEquals("{not json", Files.readString(file.resolveSibling(shell.name() + ".bad")), shell.name());
			assertTrue(shell.writable().test(config), shell.name());
		}
	}

	@Test
	void aNewerFileIsReadOnly() throws IOException {
		for (Shell shell : SHELLS) {
			Path config = configDir(shell);
			Path file = shell.file().apply(config);
			String newer = "{\"formatVersion\": 2, \"future\": [1, 2]}";
			write(file, newer);
			assertFalse(shell.writable().test(config), shell.name());
			assertFalse(shell.update().test(config, root -> root), shell.name());
			assertEquals(newer, Files.readString(file), shell.name());
			assertTrue(shell.read().apply(config).has("future"), shell.name() + ": still readable");
		}
	}

	@Test
	void aFileOverFourTimesTheCapIsLeftAlone() throws IOException {
		for (Shell shell : SHELLS) {
			Path config = configDir(shell);
			Path file = shell.file().apply(config);
			byte[] big = ("{\"formatVersion\": 1, \"pad\": \"" + "x".repeat((int) (4 * shell.maxBytes())) + "\"}").getBytes(StandardCharsets.UTF_8);
			Files.createDirectories(file.getParent());
			Files.write(file, big);
			assertFalse(shell.writable().test(config), shell.name());
			assertFalse(shell.update().test(config, root -> root), shell.name());
			assertArrayEquals(big, Files.readAllBytes(file), shell.name());
			assertFalse(Files.exists(file.resolveSibling(shell.name() + ".bad")), shell.name());
		}
	}

	@Test
	void unknownFieldsSurviveAtAnyDepth() throws IOException {
		for (Shell shell : SHELLS) {
			Path config = configDir(shell);
			Path file = shell.file().apply(config);
			write(file, "{\"formatVersion\": 1, \"future\": {\"deep\": {\"x\": [1, {\"y\": true}]}}, \"list\": [{\"z\": \"w\"}]}");
			assertTrue(shell.update().test(config, root -> {
				root.addProperty("mine", "v");
				return root;
			}), shell.name());
			JsonObject after = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			assertEquals(JsonParser.parseString("{\"deep\": {\"x\": [1, {\"y\": true}]}}"), after.get("future"), shell.name());
			assertEquals(JsonParser.parseString("[{\"z\": \"w\"}]"), after.get("list"), shell.name());
			assertEquals("v", after.get("mine").getAsString(), shell.name());
		}
	}
}
