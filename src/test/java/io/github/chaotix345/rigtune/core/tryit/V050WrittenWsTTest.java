package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "written by 0.5" set src/test/resources/v050-written/ws-t/ (docs/v0.5/SPEC.md 3b, X11, AC6.11; the set's README),
// written by 0.5's own classes: a closed try (Cutout Leaves on -> off measured here, then reverted: its tryit- pair, its
// apply entry REVERTED and the undo entry) and an open restart try of Sodium's defer mode (its before run in the benchmark
// world, the apply entry STAGED under the try's entry id, the PATCH_JSON op in pending.json, tryit.json's current). Only
// ordinary apply/undo entries and an existing op type; benchmarks.json keeps schemaVersion 1 and no new field. Its entries
// change keys no other set's history changes, so composed with them each set's Undo this still reverts its own. By default
// the test compares with the committed files; RIGTUNE_REGENERATE_FIXTURES=1 writes them instead. The pinned 0.2.0
// BenchmarkHistory and 0.3.0 Journal read them.
class V050WrittenWsTTest {
	private static final String SET = "src/test/resources/v050-written/ws-t/";
	private static final List<String> FILES = List.of("benchmarks.json", "history.json", "pending.json", "tryit.json");
	private static final String SESSION = "5c1e8a90-2f7b-4f4e-9d0a-1b6c2d3e4f50";
	static final String CLOSED_ENTRY = "7a3e9c10-6b2d-4c8f-a1e4-0d5f6a7b8c91";
	static final String UNDO_ENTRY = "7a3e9c10-6b2d-4c8f-a1e4-0d5f6a7b8c92";
	static final String OPEN_ENTRY = "3f0c6a8e-4b1d-4e7a-9c2f-5a6b7c8d9e01";
	static final String OP = "3f0c6a8e-4b1d-4e7a-9c2f-5a6b7c8d9e02";
	static final String GROUP = "3f0c6a8e-4b1d-4e7a-9c2f-5a6b7c8d9e03";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";

	@TempDir
	Path dir;

	static final TryIt CLOSED = new TryIt("t-1b2c3d4e-5f60-4718-8a9b-0c1d2e3f4051", "tryit-1b2c3d4e-5f60-4718-8a9b-0c1d2e3f4052", CLOSED_ENTRY,
			"setting:vanilla.cutoutLeaves", "vanilla.cutoutLeaves", "true", "false", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT, "2026-09-24T10:00:00Z",
			SESSION, "0.5.0+mc26.2", "26.2", settings("true", "ALWAYS"), new TryIt.Spot(-118, 72, 2044, "ResourceKey[minecraft:dimension / minecraft:overworld]",
			"singleplayer:New World"), settings("false", "ALWAYS"), SESSION, new TryIt.Spot(-118, 72, 2044,
			"ResourceKey[minecraft:dimension / minecraft:overworld]", "singleplayer:New World"), "2026-09-24T10:09:40Z-2b3c");
	static final TryIt OPEN = new TryIt("t-9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a", "tryit-9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1b", OPEN_ENTRY,
			"setting:" + DEFER, DEFER, "ALWAYS", "ONE_FRAME", TryIt.Kind.RESTART, BenchmarkRequest.Scene.BENCHMARK_WORLD, "2026-09-25T10:00:00Z", SESSION,
			"0.5.0+mc26.2", "26.2", settings("true", "ALWAYS"), null, null, null, null, null);

	private static Map<String, String> settings(String cutoutLeaves, String defer) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("vanilla.renderDistance", "12");
		out.put("vanilla.cutoutLeaves", cutoutLeaves);
		out.put("vanilla.simulationDistance", "8");
		out.put("vanilla.particles", "0");
		out.put(DEFER, defer);
		return out;
	}

	private static BenchmarkRecord run(String id, String at, String scene, String phase, String pairId, int rd, double low, double avg) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, rd, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		BenchmarkRecord.World world = "BENCHMARK_WORLD".equals(scene) ? new BenchmarkRecord.World("rigtune-benchmark", 8675309L) : null;
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(false, false, null, 2560, 1440, false, BenchmarkRecord.Context.PROTOCOL,
				"6c652939928ad17df74ccf1ed644093099c9bef2f396af02a52986a103d1713b", null).withStagedAtStart(List.of())
				.withWorldFresh("BENCHMARK_WORLD".equals(scene) ? false : null);
		return new BenchmarkRecord(id, at, "0.5.0+mc26.2", "26.2", "MEASURE", scene, phase, pairId, 144, true, knobs,
				new BenchmarkRecord.Result(avg, low, 1000 / low, 2, 0.021), Map.of(), Map.of(), world, false, context);
	}

	// The four files in configDir/rigtune/, in the order 0.5 writes them.
	static void write(Path configDir) throws IOException {
		Path rigtune = Files.createDirectories(configDir.resolve("rigtune"));
		TryItStore store = TryItStore.shared(configDir);
		Journal journal = new Journal(configDir, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		// The closed try: before, the apply, after, then Revert (History's Undo this) and Done.
		assertTrue(store.open(CLOSED));
		JournalChange applied = new JournalChange("7a3e9c10-6b2d-4c8f-a1e4-0d5f6a7b8c93", JournalChange.SETTING, "vanilla.cutoutLeaves", "true", "false", null,
				null, null, null, JournalChange.REVERTED, null, null, null);
		JournalChange undo = new JournalChange("7a3e9c10-6b2d-4c8f-a1e4-0d5f6a7b8c94", JournalChange.SETTING, "vanilla.cutoutLeaves", "false", "true", null,
				null, null, null, JournalChange.APPLIED, null, null, applied.id());
		assertTrue(store.close(CLOSED.id(), TryIt.Closed.of(CLOSED, TryIt.Decision.REVERTED, "no_clear_change", 1.2, 4.8, 5.0, "2026-09-24T10:12:00Z")));
		// The open try: its before run, the staged apply.
		assertTrue(store.open(OPEN));
		PendingActions.Op op = new PendingActions.Op(PendingActions.Type.PATCH_JSON, null, null, "${INSTANCE}/config/sodium-options.json",
				Map.of("performance.chunk_build_defer_mode", "ONE_FRAME"), OP, GROUP, null, 0);
		new PendingActions("2026-09-25T10:03:10Z", 4242, "${INSTANCE}/mods", "${INSTANCE}/config", List.of(op)).save(PendingActions.defaultPath(configDir));
		JournalChange staged = new JournalChange("3f0c6a8e-4b1d-4e7a-9c2f-5a6b7c8d9e04", JournalChange.SETTING, DEFER, "ALWAYS", "ONE_FRAME", null, null, null,
				null, JournalChange.STAGED, OP, GROUP, null);
		assertTrue(journal.update(entries -> List.of(
				new JournalEntry(CLOSED_ENTRY, "2026-09-24T10:03:05Z", JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null, List.of(applied)),
				new JournalEntry(UNDO_ENTRY, "2026-09-24T10:11:30Z", JournalEntry.UNDO, "0.5.0+mc26.2", "26.2", CLOSED_ENTRY, List.of(undo)),
				new JournalEntry(OPEN_ENTRY, "2026-09-25T10:03:10Z", JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null, List.of(staged)))));
		BenchmarkHistory.empty()
				.with(run("2026-09-24T10:02:58Z-1a2b", "2026-09-24T10:02:58Z", "CURRENT", BenchmarkRecord.BEFORE, CLOSED.pairId(), 12, 84.2, 142.0))
				.with(run("2026-09-24T10:09:40Z-2b3c", "2026-09-24T10:09:40Z", "CURRENT", BenchmarkRecord.AFTER, CLOSED.pairId(), 12, 85.2, 148.8))
				.with(run("2026-09-25T10:03:02Z-3c4d", "2026-09-25T10:03:02Z", "BENCHMARK_WORLD", BenchmarkRecord.BEFORE, OPEN.pairId(), 12, 96.4, 171.9))
				.save(rigtune.resolve("benchmarks.json"));
	}

	@Test
	void theCommittedSetIsWhatThe05CodeWrites() throws IOException {
		write(dir);
		Path committed = RepoFiles.resolve(SET);
		for (String name : FILES) {
			Path written = dir.resolve("rigtune").resolve(name);
			if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
				Files.createDirectories(committed);
				Files.copy(written, committed.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
			assertEquals(Files.readString(committed.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n"),
					Files.readString(written, StandardCharsets.UTF_8).replace("\r\n", "\n"), name + ": regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		}
	}

	// 0.5 reads its own set back: the open try waits for its restart (in another session), the closed one is in `recent`,
	// and the Benchmark menu's "Measure after" never takes the open try's before.
	@Test
	void the05CodeReadsTheSetBack() throws IOException {
		Path config = copyOfTheSet();
		TryItStore store = TryItStore.shared(config);
		assertEquals(OPEN, store.current());
		assertEquals(TryIt.Decision.REVERTED, store.recent().getFirst().decision());
		Journal journal = new Journal(config, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		BenchmarkHistory history = BenchmarkHistory.load(config.resolve("rigtune").resolve("benchmarks.json"));
		TryItView view = TryItFlow.derive(store.current(), history.runs(), new TryItFlow.History(journal.state(), journal.entries(), Map.of()),
				new TryItFlow.Live("a later session", false, false));
		assertEquals(Stage.AWAITING_RESTART, view.stage());
		assertNotNull(view.before());
		assertTrue(history.openBefore("BENCHMARK_WORLD", "26.2").isPresent(), "an open before (0.4.0's menu would offer it: harmless)");
		assertFalse(history.openBefore("BENCHMARK_WORLD", "26.2", id -> !id.startsWith(TryIt.PAIR_PREFIX)).isPresent(), "0.5's menu skips it");
		assertTrue(history.runs().stream().anyMatch(r -> BenchmarkRecord.AFTER.equals(r.phase()) && CLOSED.pairId().equals(r.pairId())));
		List<PendingActions.Op> ops = PendingActions.load(PendingActions.defaultPath(config)).ops();
		assertEquals(List.of(OP), ops.stream().map(PendingActions.Op::id).toList());
	}

	// AC6.11: the pinned 0.2.0 BenchmarkHistory loads the runs (no .bad, the pairs intact) and the pinned 0.3.0 Journal and
	// HistoryModel read the entries as ordinary apply and undo entries, Undo this offered on the open try's.
	@Test
	void olderReadersReadTheSet() throws IOException {
		Path config = copyOfTheSet();
		Path runs = config.resolve("rigtune").resolve("benchmarks.json");
		var v020 = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(runs);
		assertFalse(v020.unreadable());
		assertEquals(3, v020.runs().size());
		assertTrue(v020.before(OPEN.pairId()).isPresent());
		assertFalse(Files.exists(runs.resolveSibling("benchmarks.json.bad")));
		var journal = new io.github.chaotix345.rigtune.v030.core.history.Journal(config, "0.3.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, journal.state());
		var view = io.github.chaotix345.rigtune.v030.core.history.HistoryModel.build(journal.state(), journal.entries(), Map.of(),
				io.github.chaotix345.rigtune.v030.core.history.HistoryModel.Labels.RAW);
		assertEquals(List.of(OPEN_ENTRY, UNDO_ENTRY, CLOSED_ENTRY), view.entries().stream().map(e -> e.id()).toList());
		assertTrue(view.entries().getFirst().undoable(), "Undo this is offered on the open try's entry");
		HistoryModel.View now = HistoryModel.build(Journal.State.OK, new Journal(config, "0.5.0+mc26.2", "26.2", (m, e) -> {
		}).entries(), Map.of(), HistoryModel.Labels.RAW);
		assertEquals(3, now.entries().size());
	}

	private Path copyOfTheSet() throws IOException {
		Path config = dir.resolve("reread");
		Files.createDirectories(config.resolve("rigtune"));
		for (String name : FILES) {
			Files.copy(RepoFiles.resolve(SET + name), config.resolve("rigtune").resolve(name));
		}
		return config;
	}
}
