package io.github.chaotix345.rigtune.core.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Loaded;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Saved;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md C1's common rules for the new files under config/rigtune/.
class JsonStateFileTest {
	record Sample(int formatVersion, List<String> items, String note) {
	}

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("rigtune").resolve("sample.json");
	}

	private JsonStateFile store() {
		return new JsonStateFile(file(), 1024);
	}

	private void write(String text) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	@Test
	void aMissingFileIsEmptyAndWritable() {
		Loaded<Sample> loaded = store().load(Sample.class);
		assertEquals(State.MISSING, loaded.state());
		assertNull(loaded.value());
		assertEquals(new JsonObject(), loaded.root());
		assertTrue(loaded.writable());
		assertFalse(Files.exists(file()));
	}

	@Test
	void saveThenLoadRoundTripsWithFormatVersionFirst() throws IOException {
		assertEquals(Saved.OK, store().save(new Sample(0, List.of("a", "b"), "hi")));
		String text = Files.readString(file());
		assertTrue(text.startsWith("{\n  \"formatVersion\": 1,"), text);
		Loaded<Sample> loaded = store().load(Sample.class);
		assertEquals(State.OK, loaded.state());
		assertEquals(new Sample(1, List.of("a", "b"), "hi"), loaded.value());
		assertEquals(1, loaded.root().get("formatVersion").getAsInt());
	}

	@Test
	void aFileWithoutFormatVersionCountsAsVersionOne() throws IOException {
		write("{\"items\": [\"x\"]}");
		Loaded<Sample> loaded = store().load(Sample.class);
		assertEquals(State.OK, loaded.state());
		assertEquals(List.of("x"), loaded.value().items());
		assertTrue(loaded.writable());
	}

	@Test
	void aNewerFileIsReadOnlyAndNeverOverwritten() throws IOException {
		write("{\"formatVersion\": 2, \"items\": [\"future\"], \"extra\": {\"a\": 1}}");
		byte[] before = Files.readAllBytes(file());
		Loaded<Sample> loaded = store().load(Sample.class);
		assertEquals(State.NEWER, loaded.state());
		assertFalse(loaded.writable());
		assertEquals(List.of("future"), loaded.value().items(), "readable, read-only");
		assertEquals(Saved.READ_ONLY, store().save(new Sample(1, List.of("mine"), null)));
		assertArrayEquals(before, Files.readAllBytes(file()));
		assertFalse(Files.exists(dir.resolve("rigtune").resolve("sample.json.bad")));
	}

	@Test
	void aCorruptFileIsMovedAsideAndTheStoreStartsEmpty() throws IOException {
		write("{not json");
		Loaded<Sample> loaded = store().load(Sample.class);
		assertEquals(State.MOVED_ASIDE, loaded.state());
		assertNull(loaded.value());
		assertTrue(loaded.writable());
		assertFalse(Files.exists(file()));
		assertEquals("{not json", Files.readString(file().resolveSibling("sample.json.bad")));

		write("[1, 2]");
		assertEquals(State.MOVED_ASIDE, store().load(Sample.class).state());
		assertEquals("[1, 2]", Files.readString(file().resolveSibling("sample.json.bad.1")), "an older .bad is never replaced");

		assertEquals(Saved.OK, store().save(new Sample(1, List.of(), null)));
		assertEquals(State.OK, store().load(Sample.class).state());
	}

	@Test
	void unusableFormatVersionsAndShapesCountAsCorrupt() throws IOException {
		for (String text : List.of("{\"formatVersion\": \"x\"}", "{\"formatVersion\": 1.5}", "{\"formatVersion\": 0}",
				"{\"formatVersion\": null}", "{\"formatVersion\": 1, \"items\": \"not a list\"}", "null", "", "\"text\"")) {
			write(text);
			Loaded<Sample> loaded = store().load(Sample.class);
			assertEquals(State.MOVED_ASIDE, loaded.state(), text);
			assertFalse(Files.exists(file()), text);
		}
	}

	@Test
	void invalidUtf8CountsAsCorrupt() throws IOException {
		Files.createDirectories(file().getParent());
		Files.write(file(), new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'});
		assertEquals(State.MOVED_ASIDE, store().load(Sample.class).state());
	}

	@Test
	void aWriteOverTheCapIsRefusedAndLeavesTheFileAlone() throws IOException {
		assertEquals(Saved.OK, store().save(new Sample(1, List.of("small"), null)));
		byte[] before = Files.readAllBytes(file());
		assertEquals(Saved.TOO_LARGE, store().save(new Sample(1, List.of("x".repeat(2000)), null)));
		assertArrayEquals(before, Files.readAllBytes(file()));
		assertEquals(Saved.OK, store().save(new Sample(1, List.of("x".repeat(900)), null)), "just under the cap");
	}

	@Test
	void aFileFarOverTheCapIsMovedAsideUnread() throws IOException {
		write("{\"formatVersion\": 1, \"note\": \"" + "x".repeat(5000) + "\"}");
		assertEquals(State.MOVED_ASIDE, store().load(Sample.class).state());
		assertTrue(Files.exists(file().resolveSibling("sample.json.bad")));
	}

	@Test
	void unknownTopLevelFieldsSurviveASave() throws IOException {
		write("{\"formatVersion\": 1, \"items\": [\"a\"], \"fromTheFuture\": {\"keep\": true}, \"note\": \"old\"}");
		Loaded<Sample> loaded = store().load(Sample.class);
		assertEquals(Saved.OK, store().save(new Sample(1, List.of("a", "b"), null), loaded.root()));
		JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
		assertTrue(root.getAsJsonObject("fromTheFuture").get("keep").getAsBoolean());
		assertEquals(2, root.getAsJsonArray("items").size());
		assertFalse(root.has("note"), "a known field the new value leaves out isn't resurrected");
	}

	@Test
	void preserveUnknownCopiesOnlyKeysTheFreshObjectLacks() {
		JsonObject fresh = JsonParser.parseString("{\"a\": 1, \"b\": 2}").getAsJsonObject();
		JsonObject old = JsonParser.parseString("{\"a\": 9, \"c\": 3, \"formatVersion\": 7}").getAsJsonObject();
		JsonObject merged = JsonStateFile.preserveUnknown(fresh, old, List.of("b"));
		assertEquals(JsonParser.parseString("{\"a\": 1, \"b\": 2, \"c\": 3}"), merged);
		assertEquals(fresh, JsonStateFile.preserveUnknown(fresh, null, List.of()));
	}

	@Test
	void readErrorsNeverThrowAndBlockWrites() throws IOException {
		write("{\"formatVersion\": 1}");
		byte[] before = Files.readAllBytes(file());
		JsonStateFile.Io failingRead = new JsonStateFile.Io() {
			@Override
			public byte[] read(Path file) throws IOException {
				throw new IOException("locked by a virus scanner");
			}

			@Override
			public void moveAside(Path from, Path to) throws IOException {
				throw new AssertionError("nothing is moved");
			}
		};
		JsonStateFile locked = new JsonStateFile(file(), 1024, JsonStateFile.GSON, failingRead);
		Loaded<Sample> loaded = locked.load(Sample.class);
		assertEquals(State.UNREADABLE, loaded.state());
		assertFalse(loaded.writable());
		assertEquals(Saved.READ_ONLY, locked.save(new Sample(1, List.of(), null)));
		assertArrayEquals(before, Files.readAllBytes(file()));
	}

	@Test
	void aCorruptFileThatCantBeMovedIsLeftAloneAndNotOverwritten() throws IOException {
		write("{broken");
		JsonStateFile.Io noMove = new JsonStateFile.Io() {
			@Override
			public byte[] read(Path file) throws IOException {
				return Files.readAllBytes(file);
			}

			@Override
			public void moveAside(Path from, Path to) throws IOException {
				throw new IOException("in use");
			}
		};
		JsonStateFile stuck = new JsonStateFile(file(), 1024, JsonStateFile.GSON, noMove);
		assertEquals(State.UNREADABLE, stuck.load(Sample.class).state());
		assertEquals(Saved.READ_ONLY, stuck.save(new Sample(1, List.of(), null)));
		assertEquals("{broken", Files.readString(file()));
	}

	@Test
	void aFailedWriteReportsFailedInsteadOfThrowing() throws IOException {
		Files.createDirectories(dir.resolve("blocked"));
		Files.writeString(dir.resolve("blocked").resolve("rigtune"), "a file where the folder should be");
		JsonStateFile blocked = new JsonStateFile(dir.resolve("blocked").resolve("rigtune").resolve("sample.json"), 1024);
		assertEquals(Saved.FAILED, blocked.save(new Sample(1, List.of(), null)));
		assertEquals(State.MISSING, blocked.load(Sample.class).state());
	}

	@Test
	void theJsonObjectViewNeedsNoType() throws IOException {
		write("{\"formatVersion\": 1, \"anything\": [1, 2, 3]}");
		Loaded<JsonObject> loaded = store().load(JsonObject.class);
		assertEquals(State.OK, loaded.state());
		assertNotNull(loaded.value());
		assertEquals(3, loaded.value().getAsJsonArray("anything").size());
	}
}
