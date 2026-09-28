package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "written by 0.5" set ws-b (docs/v0.5/SPEC.md 3b and X11, src/test/resources/v050-written/README.md): benchmarks.json
// as 0.5 writes it, with the optional context fields RW-8 (worldFresh), RW-6 (dhGenerating), BH-2 (stagedAtStart) and
// review-11 COMPAT-2 (backend, gpu).
// The file comes from this test; RIGTUNE_REGENERATE_FIXTURES=1 rewrites it, otherwise the committed file must be exactly
// what the code writes now. The pinned 0.2.0/0.3.0 readers load it without a .bad (compat040 does the same with 0.4.0's
// own classes through expect.json), and a 0.3.0 rewrite drops only the fields it doesn't know.
class BenchmarkWrittenV050Test {
	private static final String SET = "src/test/resources/v050-written/ws-b/";
	private static final Gson GSON = new Gson();
	private static final String GPU_GL = "AMD Radeon RX 7800 XT";
	private static final String GPU_VK = "AMD Radeon RX 7800 XT";
	private static final String HASH = "9c2e4f6a8b0d1c3e5f7a9b1d3c5e7f9a0b2c4d6e8f0a1b3c5d7e9f1a2b4c6d8e";

	@TempDir
	Path dir;

	private static Map<String, BenchmarkRecord.KnobResult> knobs(int rd, int originalRd) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, originalRd, rd == originalRd ? null : 702.5,
				rd == originalRd ? null : 331.0, rd == originalRd ? null : 3.4));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		return knobs;
	}

	// A Measure run in the benchmark world it created (worldFresh), the next one reusing it with a change still staged, a
	// Tune there while Distant Horizons generated terrain (dhGenerating, DH rendering off), and a Tune in the player's own
	// world (no worldFresh). Timestamps in the past; ids unique across the sets.
	static List<BenchmarkRecord> v050Written() {
		BenchmarkRecord.Context plain = new BenchmarkRecord.Context(false, false, null, 2560, 1440, true, BenchmarkRecord.Context.PROTOCOL);
		BenchmarkRecord.World world = new BenchmarkRecord.World("rigtune-benchmark", 8675309L);
		BenchmarkRecord fresh = new BenchmarkRecord("2026-09-24T18:02:13Z-3b7e", "2026-09-24T18:02:13Z", "0.5.0+mc26.2", "26.2", "MEASURE",
				"BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 170, true, knobs(12, 12), new BenchmarkRecord.Result(866.0, 402.5, 4.9, 2, 0.061),
				Map.of(), Map.of(), world, false, plain.withModSet(HASH, null).withWorldFresh(true).withStagedAtStart(List.of())
						.withGraphics("OPENGL", GPU_GL));
		BenchmarkRecord staged = new BenchmarkRecord("2026-09-25T19:40:07Z-a41c", "2026-09-25T19:40:07Z", "0.5.0+mc26.2", "26.2", "MEASURE",
				"BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 170, true, knobs(12, 12), new BenchmarkRecord.Result(912.5, 540.25, 2.6, 2, 0.022),
				Map.of(), Map.of(), world, false, plain.withModSet(HASH, "7e3a1c5b-9d2f-4b68-8a14-c6e0f2d4b9a1").withWorldFresh(false)
						.withStagedAtStart(List.of("0b9d7f15-3e6c-4a82-b1d4-5f7e9a3c2b60")).withGraphics("OPENGL", GPU_GL));
		BenchmarkRecord generating = new BenchmarkRecord("2026-09-26T09:15:44Z-6f02", "2026-09-26T09:15:44Z", "0.5.0+mc26.2", "26.2", "TUNE",
				"BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 170, true, knobs(24, 32), new BenchmarkRecord.Result(757.5, 318.5, 2.36, 2, 0.0707),
				Map.of(), Map.of(), world, false, plain.withModSet(HASH, "7e3a1c5b-9d2f-4b68-8a14-c6e0f2d4b9a1").withWorldFresh(false)
						.withDhGenerating(true).withStagedAtStart(List.of()).withGraphics("VULKAN", GPU_VK));
		BenchmarkRecord current = new BenchmarkRecord("2026-09-26T21:03:29Z-c8d5", "2026-09-26T21:03:29Z", "0.5.0+mc26.2", "26.2", "TUNE", "CURRENT",
				BenchmarkRecord.SINGLE, null, 170, true, knobs(16, 12), new BenchmarkRecord.Result(701.0, 330.75, 3.3, 2, 0.031), Map.of(), Map.of(),
				null, false, new BenchmarkRecord.Context(true, false, null, 2560, 1440, true, BenchmarkRecord.Context.PROTOCOL)
						.withModSet(HASH, "7e3a1c5b-9d2f-4b68-8a14-c6e0f2d4b9a1").withStagedAtStart(List.of()));
		return List.of(fresh, staged, generating, current);
	}

	private Path written() throws IOException {
		BenchmarkHistory history = BenchmarkHistory.empty();
		for (BenchmarkRecord run : v050Written()) {
			history = history.with(run);
		}
		Path file = dir.resolve("benchmarks.json");
		history.save(file);
		return file;
	}

	@Test
	void theV050WrittenSetIsWhatThisVersionWrites() throws IOException {
		Path file = written();
		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(file, committed.resolve("benchmarks.json"), StandardCopyOption.REPLACE_EXISTING);
		}
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"worldFresh\": ") && text.contains("\"dhGenerating\": ") && text.contains("\"stagedAtStart\": ")
				&& text.contains("\"backend\": ") && text.contains("\"gpu\": "), text);
		assertEquals(text, Files.readString(committed.resolve("benchmarks.json"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		assertEquals(v050Written(), BenchmarkHistory.load(committed.resolve("benchmarks.json")).runs());
		// What the fields mean: the fresh and the generating runs stay out of the trend.
		assertEquals(List.of(true, false, true, false), v050Written().stream().map(BenchmarkTrend::excluded).toList());
	}

	private static JsonObject withoutNewFields(BenchmarkRecord run, String... also) {
		JsonObject json = GSON.toJsonTree(run).getAsJsonObject();
		JsonObject context = json.getAsJsonObject("context");
		for (String field : List.of("worldFresh", "dhGenerating", "stagedAtStart", "backend", "gpu")) {
			context.remove(field);
		}
		for (String field : also) {
			context.remove(field);
		}
		return json;
	}

	@Test
	void the020And030ReadersLoadIt() throws IOException {
		Path file = written();
		List<BenchmarkRecord> expected = v050Written();

		var v030 = io.github.chaotix345.rigtune.v030.core.benchmark.BenchmarkHistory.load(file);
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")), "0.3.0 must not take the file for corrupt");
		assertFalse(v030.unreadable());
		assertEquals(expected.size(), v030.runs().size());
		for (int i = 0; i < expected.size(); i++) {
			assertEquals(withoutNewFields(expected.get(i), "modSetHash", "journalCursor"), GSON.toJsonTree(v030.runs().get(i)), "0.3.0 run " + i);
		}

		var v020 = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(file);
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")), "0.2.0 must not take the file for corrupt");
		assertEquals(expected.size(), v020.runs().size());
		for (int i = 0; i < expected.size(); i++) {
			JsonObject want = GSON.toJsonTree(expected.get(i)).getAsJsonObject();
			want.remove("context");
			assertEquals(want, GSON.toJsonTree(v020.runs().get(i)), "0.2.0 run " + i);
		}
	}

	// After a downgrade to 0.3.0 and a new run there, the 0.4 and 0.5 context fields are gone and 0.5 reads the rest back:
	// comparisons fall back to 0.4's rules (a formerly excluded run counts again).
	@Test
	void a030RewriteDropsOnlyTheNewFields() throws IOException {
		Path file = written();
		var v030 = io.github.chaotix345.rigtune.v030.core.benchmark.BenchmarkHistory.load(file);
		v030.with(v030.runs().getFirst()).save(file);
		List<BenchmarkRecord> now = BenchmarkHistory.load(file).runs();
		List<BenchmarkRecord> expected = v050Written();
		assertEquals(expected.size() + 1, now.size());
		for (int i = 0; i < expected.size(); i++) {
			assertEquals(withoutNewFields(expected.get(i), "modSetHash", "journalCursor"), GSON.toJsonTree(now.get(i)), "run " + i);
			assertFalse(BenchmarkTrend.excluded(now.get(i)));
		}
	}
}
