package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md AC8.8 and the compatibility promise at its top: 0.3.0 adds an optional `context` to new
// benchmarks.json runs and keeps schemaVersion 1. The 0.2.0 side is 0.2.0's own code, pinned in the test tree under
// io.github.chaotix345.rigtune.v020.core.benchmark: BenchmarkHistory, BenchmarkRecord and the BenchmarkMath and
// FrameStats they need, each `git show v0.2.0:src/main/java/io/github/chaotix345/rigtune/core/benchmark/<name>.java`
// with only the package line changed (RigTune.LOGGER and AtomicFiles stay the current ones). Never edit them.
// src/test/resources/v020/benchmarks.json was written by that pinned BenchmarkHistory.save.
class BenchmarkCompatibilityTest {
	private static final Gson GSON = new Gson();
	private static final String PAIR = "5d0c1e8e-2f5b-4c62-9d7a-1b2e3f4a5b6c";

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("benchmarks.json");
	}

	private static String fixture() throws IOException {
		try (InputStream in = BenchmarkCompatibilityTest.class.getResourceAsStream("/v020/benchmarks.json")) {
			assertNotNull(in, "fixture");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
	}

	private static BenchmarkRecord withContext() {
		return new BenchmarkRecord("2026-10-01T12:00:00Z-1a2b", "2026-10-01T12:00:00Z", "0.3.0+mc26.2", "26.2", "TUNE", "BENCHMARK_WORLD",
				BenchmarkRecord.SINGLE, null, 170, true,
				Map.of(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(16, 12, 800.0, 250.0, 5.0)),
				new BenchmarkRecord.Result(790.0, 248.0, 5.1, 2, 0.02), Map.of(), Map.of(), new BenchmarkRecord.World("rigtune-benchmark", 8675309L),
				false, new BenchmarkRecord.Context(true, true, "ComplementaryReimagined_r5.9.3.zip", 2560, 1440, false, BenchmarkRecord.Context.PROTOCOL));
	}

	@Test
	void aFileWrittenBy020LoadsUnchanged() throws IOException {
		Files.writeString(file(), fixture());
		BenchmarkHistory now = BenchmarkHistory.load(file());
		var old = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(file());
		assertEquals(3, now.runs().size());
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")));
		for (int i = 0; i < 3; i++) {
			assertNull(now.runs().get(i).context());
			assertEquals(GSON.toJsonTree(old.runs().get(i)), GSON.toJsonTree(now.runs().get(i)), "run " + i);
		}
		assertTrue(now.before(PAIR).isPresent());
		// Saved again by 0.3.0, the runs 0.2.0 wrote come out byte for byte the same.
		now.save(file());
		assertEquals(fixture(), Files.readString(file(), StandardCharsets.UTF_8));
	}

	@Test
	void the020ReaderLoadsAFileWrittenBy030() throws IOException {
		Files.writeString(file(), fixture());
		BenchmarkHistory.load(file()).with(withContext()).save(file());
		String text = Files.readString(file(), StandardCharsets.UTF_8);
		assertTrue(text.contains("\"context\": {"), text);
		assertTrue(text.contains("\"schemaVersion\": 1"), text);

		var old = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(file());
		assertEquals(4, old.runs().size());
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")), "0.2.0 must not take the file for corrupt");
		JsonObject expected = GSON.toJsonTree(withContext()).getAsJsonObject();
		expected.remove("context");
		assertEquals(expected, GSON.toJsonTree(old.runs().getLast()));
		assertTrue(old.before(PAIR).isPresent());
		assertEquals(1, old.chart("BENCHMARK_WORLD", "26.2", 10).size());
		assertEquals(2, old.chart("BENCHMARK_WORLD", "26.3", 10).size());
	}

	@Test
	void a020RewriteDropsOnlyTheContext() throws IOException {
		// After a downgrade, 0.2.0 rewrites the file when it adds a run: `context` is lost, nothing else.
		BenchmarkHistory.empty().with(withContext()).save(file());
		var old = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(file());
		old.with(old.runs().getFirst()).save(file());
		BenchmarkHistory now = BenchmarkHistory.load(file());
		assertEquals(2, now.runs().size());
		assertNull(now.runs().getFirst().context());
		BenchmarkRecord first = now.runs().getFirst();
		BenchmarkRecord expected = withContext();
		assertEquals(new BenchmarkRecord(expected.id(), expected.createdAt(), expected.rigtuneVersion(), expected.mcVersion(), expected.mode(),
				expected.scene(), expected.phase(), expected.pairId(), expected.targetFps(), expected.targetMet(), expected.knobs(), expected.result(),
				expected.costs(), expected.notMeasured(), expected.world(), expected.deadlineHit()), first);
	}

	@Test
	void theContextRoundTrips() throws IOException {
		BenchmarkHistory.empty().with(withContext()).save(file());
		assertEquals(withContext(), BenchmarkHistory.load(file()).runs().getFirst());
	}
}
