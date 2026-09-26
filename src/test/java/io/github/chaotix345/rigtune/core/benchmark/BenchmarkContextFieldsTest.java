package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md C1/7: the optional Context.modSetHash and journalCursor. benchmarks.json keeps schemaVersion 1.
class BenchmarkContextFieldsTest {
	@TempDir
	Path dir;

	private static BenchmarkRecord run(BenchmarkRecord.Context context) {
		return new BenchmarkRecord("r-1", "2026-09-26T10:00:00Z", "0.4.0", "26.2", "TUNE", "BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 144,
				true, Map.of(), new BenchmarkRecord.Result(300, 200, 6, 3, 0.02), Map.of(), Map.of(), null, false, context);
	}

	@Test
	void theNewFieldsRoundTripThroughBenchmarksJson() throws Exception {
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(false, false, null, 2560, 1440, true, 1).withModSet("abc123", "entry-9");
		Path file = dir.resolve("benchmarks.json");
		BenchmarkHistory.empty().with(run(context)).save(file);
		BenchmarkRecord.Context read = BenchmarkHistory.load(file).runs().getFirst().context();
		assertEquals("abc123", read.modSetHash());
		assertEquals("entry-9", read.journalCursor());
		assertEquals(context, read);
		assertEquals(1, new Gson().fromJson(Files.readString(file), JsonObject.class).get("schemaVersion").getAsInt());
	}

	@Test
	void sameConditionsIgnoresTheModSetAndCursor() {
		BenchmarkRecord.Context a = new BenchmarkRecord.Context(false, true, "pack.zip", 2560, 1440, true, 1);
		BenchmarkRecord.Context b = a.withModSet("h1", "e1");
		assertTrue(a.sameConditions(b));
		assertTrue(b.sameConditions(a.withModSet("h2", "e2")));
		assertFalse(a.sameConditions(new BenchmarkRecord.Context(false, true, "other.zip", 2560, 1440, true, 1)));
		assertFalse(a.sameConditions(new BenchmarkRecord.Context(false, true, "pack.zip", 1920, 1440, true, 1)));
		assertFalse(a.sameConditions(null));
	}

	@Test
	void aContextWithoutThemReadsAsNullAndWritesNothing() {
		String json = "{\"dhRendering\":true,\"shaders\":false,\"width\":1920,\"height\":1080,\"fullscreen\":false,\"protocol\":1}";
		BenchmarkRecord.Context read = new Gson().fromJson(json, BenchmarkRecord.Context.class);
		assertNull(read.modSetHash());
		assertNull(read.journalCursor());
		assertEquals(new BenchmarkRecord.Context(true, false, null, 1920, 1080, false, 1), read);
		assertFalse(new Gson().toJson(read).contains("modSetHash"));
	}
}
