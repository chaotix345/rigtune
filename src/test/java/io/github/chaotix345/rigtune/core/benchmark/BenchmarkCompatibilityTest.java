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
import java.util.LinkedHashMap;
import java.util.List;
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
// docs/v0.4/SPEC.md AC7.4: 0.4.0 adds the optional context.modSetHash and context.journalCursor (schemaVersion stays 1);
// the 0.3.0 side is v030/core/benchmark, `git show v0.3.0:` copies (docs/v0.4/design/ws-k.md). The "written by 0.4"
// fixture src/test/resources/v040-written/ws-b/benchmarks.json (WS-H's downgrade run and released-jar harness) is what
// v040Written() writes: the test writes it to build/v040-written/ws-b/ and checks the committed copy is the same.
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
		// The fixture is what 0.2.0's own save writes for these runs.
		old.save(dir.resolve("written-by-020.json"));
		assertEquals(fixture(), Files.readString(dir.resolve("written-by-020.json"), StandardCharsets.UTF_8));
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

	// v0.4 (AC7.4) ---------------------------------------------------------------------------------------------------

	private static final String V040_FIXTURE = "/v040-written/ws-b/benchmarks.json";
	private static final String HASH = "5e1f2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0";

	private static BenchmarkRecord measure040(String id, String at, double avg, double low, double cv, BenchmarkRecord.Context context) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(12, 12, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		return new BenchmarkRecord(id, at, "0.4.0+mc26.2", "26.2", "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 144, true, knobs,
				new BenchmarkRecord.Result(avg, low, 2.5, 2, cv), Map.of(), Map.of(), new BenchmarkRecord.World("rigtune-benchmark", 8675309L), false,
				context);
	}

	// A 0.3.0-shaped run (a context without the new fields), three comparable 0.4 Measure runs with the new fields (the
	// first before anything was journaled: no cursor) and a 0.4 Tune run; timestamps in the past.
	static List<BenchmarkRecord> v040Written() {
		BenchmarkRecord.Context plain = new BenchmarkRecord.Context(false, false, null, 2560, 1440, false, BenchmarkRecord.Context.PROTOCOL);
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(12, 12, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		BenchmarkRecord old = new BenchmarkRecord("2026-09-18T09:12:40Z-4c1d", "2026-09-18T09:12:40Z", "0.3.0+mc26.2", "26.2", "MEASURE",
				"BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 144, true, knobs, new BenchmarkRecord.Result(812.4, 541.0, 1.8, 2, 0.021), Map.of(),
				Map.of(), new BenchmarkRecord.World("rigtune-benchmark", 8675309L), false, plain);
		Map<String, BenchmarkRecord.KnobResult> tuneKnobs = new LinkedHashMap<>();
		tuneKnobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(16, 12, 640.5, 402.25, 3.1));
		tuneKnobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		BenchmarkRecord tune = new BenchmarkRecord("2026-09-23T20:41:05Z-77e0", "2026-09-23T20:41:05Z", "0.4.0+mc26.2", "26.2", "TUNE", "CURRENT",
				BenchmarkRecord.SINGLE, null, 144, true, tuneKnobs, new BenchmarkRecord.Result(636.0, 398.5, 3.2, 2, 0.034),
				Map.of(BenchmarkRecord.DISTANT_HORIZONS, new BenchmarkRecord.Cost(636.0, 398.5, 702.0, 431.0)), Map.of(), null, false,
				new BenchmarkRecord.Context(true, false, null, 1920, 1080, true, BenchmarkRecord.Context.PROTOCOL)
						.withModSet(HASH, "2b8e6f0a-3c47-4d19-9a52-7e1c0d4b6f83"));
		String cursor = "5f0c2a9e-1d6b-4f38-8e27-c4a1b9d07e52";
		return List.of(old,
				measure040("2026-09-20T10:03:11Z-9a21", "2026-09-20T10:03:11Z", 820.7, 546.5, 0.018, plain.withModSet(HASH, null)),
				measure040("2026-09-21T18:30:52Z-0b3f", "2026-09-21T18:30:52Z", 809.1, 538.25, 0.024, plain.withModSet(HASH, cursor)),
				measure040("2026-09-22T19:05:27Z-e6d4", "2026-09-22T19:05:27Z", 815.3, 543.75, 0.02, plain.withModSet(HASH, cursor)),
				tune);
	}

	private static String resource(String name) throws IOException {
		try (InputStream in = BenchmarkCompatibilityTest.class.getResourceAsStream(name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
	}

	@Test
	void theV040WrittenFixtureIsWhatThisVersionWrites() throws IOException {
		BenchmarkHistory history = BenchmarkHistory.empty();
		for (BenchmarkRecord run : v040Written()) {
			history = history.with(run);
		}
		Path written = Path.of("build", "v040-written", "ws-b", "benchmarks.json").toAbsolutePath();
		Files.createDirectories(written.getParent());
		history.save(written);
		String text = Files.readString(written, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"modSetHash\": ") && text.contains("\"journalCursor\": "), text);
		assertEquals(text, resource(V040_FIXTURE), "copy " + written + " to src/test/resources" + V040_FIXTURE);
		assertEquals(v040Written(), BenchmarkHistory.load(written).runs());
	}

	@Test
	void the020And030ReadersLoadTheV040File() throws IOException {
		Files.writeString(file(), resource(V040_FIXTURE));
		List<BenchmarkRecord> expected = v040Written();

		var v030 = io.github.chaotix345.rigtune.v030.core.benchmark.BenchmarkHistory.load(file());
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")), "0.3.0 must not take the file for corrupt");
		assertFalse(v030.unreadable());
		assertEquals(expected.size(), v030.runs().size());
		for (int i = 0; i < expected.size(); i++) {
			JsonObject want = GSON.toJsonTree(expected.get(i)).getAsJsonObject();
			want.getAsJsonObject("context").remove("modSetHash");
			want.getAsJsonObject("context").remove("journalCursor");
			assertEquals(want, GSON.toJsonTree(v030.runs().get(i)), "0.3.0 run " + i);
		}
		assertEquals(4, v030.chart("BENCHMARK_WORLD", "26.2", 10).size());

		var v020 = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(file());
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")), "0.2.0 must not take the file for corrupt");
		assertEquals(expected.size(), v020.runs().size());
		for (int i = 0; i < expected.size(); i++) {
			JsonObject want = GSON.toJsonTree(expected.get(i)).getAsJsonObject();
			want.remove("context");
			assertEquals(want, GSON.toJsonTree(v020.runs().get(i)), "0.2.0 run " + i);
		}
	}

	@Test
	void a030RewriteDropsOnlyTheNewFields() throws IOException {
		// After a downgrade, 0.3.0 rewrites the file when it adds a run: the two new fields are lost, nothing else, and 0.4
		// still reads every run (the change window then falls back to timestamps).
		Files.writeString(file(), resource(V040_FIXTURE));
		var v030 = io.github.chaotix345.rigtune.v030.core.benchmark.BenchmarkHistory.load(file());
		v030.with(v030.runs().getFirst()).save(file());
		BenchmarkHistory history = BenchmarkHistory.load(file());
		List<BenchmarkRecord> now = history.runs();
		List<BenchmarkRecord> expected = v040Written();
		assertEquals(expected.size() + 1, now.size());
		for (int i = 0; i < expected.size(); i++) {
			BenchmarkRecord want = expected.get(i);
			BenchmarkRecord.Context c = want.context();
			BenchmarkRecord.Context stripped = new BenchmarkRecord.Context(c.dhRendering(), c.shaders(), c.shaderPack(), c.width(), c.height(),
					c.fullscreen(), c.protocol());
			assertEquals(new BenchmarkRecord(want.id(), want.createdAt(), want.rigtuneVersion(), want.mcVersion(), want.mode(), want.scene(), want.phase(),
					want.pairId(), want.targetFps(), want.targetMet(), want.knobs(), want.result(), want.costs(), want.notMeasured(), want.world(),
					want.deadlineHit(), stripped), now.get(i), "run " + i);
		}
		// Comparability doesn't depend on the dropped fields (plan review B-H1): the old run and the three Measure runs.
		assertEquals(5, history.comparable(now.get(3), 10).size());
	}
}
