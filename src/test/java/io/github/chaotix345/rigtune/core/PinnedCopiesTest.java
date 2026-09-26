package io.github.chaotix345.rigtune.core;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/design/ws-k.md: one smoke test per group of pinned copies (src/test/java/.../v030/, v010/core/apply/), each
// reading a file written by the current code with every 0.4 optional field set, so the copies are exercised. The
// feature workstreams' compatibility tests build on these.
class PinnedCopiesTest {
	@TempDir
	Path dir;

	@Test
	void rules030ParsesTheBundledRulesAndIgnoresTheNewSections() throws Exception {
		RulesDocument current = RulesLoader.loadBundled();
		io.github.chaotix345.rigtune.v030.core.rules.RulesDocument old = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.loadBundled();
		assertEquals(current.revision, old.revision);
		assertEquals(current.mods.size(), old.mods.size());
		assertEquals(current.settings.size(), old.settings.size());
		assertEquals(current.advice.size(), old.advice.size());

		String withSections = """
				{"schemaVersion": 2, "revision": 1,
				 "advice": [{"id": "driver", "when": {"driverVersion": {"vendor": "nvidia", "atMost": "536.22"}}, "title": "t", "text": "x"}],
				 "profileTemplates": {"templates": [{"id": "battery", "goal": "performance", "facts": {"onBattery": true}}]},
				 "stutterAdvice": [{"id": "s", "requires": ["stutter-doctor"], "when": {"gcStallsAtLeast": 1}, "title": "t", "text": "x"}]}
				""";
		io.github.chaotix345.rigtune.v030.core.rules.RulesDocument parsed = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(withSections);
		assertEquals(1, parsed.advice.size());
		assertTrue(parsed.advice.getFirst().when.unknownFields.contains("driverVersion"), "0.3.0 fails closed on driverVersion");
		assertFalse(io.github.chaotix345.rigtune.v030.core.recommend.Recommender.supported(List.of("jvm-flags")));
		assertFalse(io.github.chaotix345.rigtune.v030.core.recommend.Recommender.supported(List.of("stutter-doctor")));
		assertTrue(io.github.chaotix345.rigtune.v030.core.recommend.Recommender.supported(null));
	}

	@Test
	void history030ReadsA04HistoryJson() throws Exception {
		JournalChange file = JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.9.2.jar", JournalChange.STAGED, "op-1", "g-1").withModName("Sodium");
		JournalChange setting = JournalChange.setting("vanilla.renderDistance", "12", "8", JournalChange.APPLIED, null);
		Journal journal = new Journal(dir, "0.4.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertTrue(journal.update(entries -> List.of(new JournalEntry("entry-1", "2026-09-26T10:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null,
				List.of(file, setting)))));

		io.github.chaotix345.rigtune.v030.core.history.Journal old = new io.github.chaotix345.rigtune.v030.core.history.Journal(dir, "0.3.0", "26.2",
				(message, error) -> {
					throw new AssertionError(message, error);
				});
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertEquals(List.of("entry-1"), old.entries().stream().map(io.github.chaotix345.rigtune.v030.core.history.JournalEntry::id).toList());
		assertEquals(List.of(file.id(), setting.id()), old.entries().getFirst().changes().stream()
				.map(io.github.chaotix345.rigtune.v030.core.history.JournalChange::id).toList());
		assertEquals("sodium-0.9.2.jar", old.entries().getFirst().changes().getFirst().file());
	}

	@Test
	void pendingActions010And030ParseA04PendingJson() throws Exception {
		Path mods = dir.resolve("mods");
		Op enable = Op.enableFile(mods.resolve("lithium.jar.rigtune-pending"), mods.resolve("lithium.jar")).withModId("lithium").withProjectId("gvQqBUqZ");
		Op patch = Op.patchJson(dir.resolve("sodium-options.json"), Map.of("quality.weather_quality", "FAST"));
		Path file = dir.resolve("pending.json");
		PendingActions.create(42, mods, dir, List.of(enable, patch)).save(file);
		assertTrue(Files.readString(file).contains("projectId"));

		var old030 = io.github.chaotix345.rigtune.v030.core.apply.PendingActions.load(file);
		assertEquals(2, old030.ops().size());
		assertEquals(enable.id(), old030.ops().getFirst().id());
		assertEquals(enable.from(), old030.ops().getFirst().from());
		assertEquals("lithium", old030.ops().getFirst().modId());
		assertEquals(Map.of("quality.weather_quality", "FAST"), old030.ops().get(1).patches());

		var old010 = io.github.chaotix345.rigtune.v010.core.apply.PendingActions.load(file);
		assertEquals(2, old010.ops().size());
		assertEquals(enable.id(), old010.ops().getFirst().id());
		assertEquals(io.github.chaotix345.rigtune.v010.core.apply.PendingActions.Type.ENABLE_FILE, old010.ops().getFirst().type());
		assertEquals(enable.to(), old010.ops().getFirst().to());
		assertEquals(42, old010.gamePid());
	}

	@Test
	void benchmarks030LoadA04BenchmarksJson() throws Exception {
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(true, false, null, 2560, 1440, true, 1).withModSet("hash", "entry-1");
		BenchmarkRecord run = new BenchmarkRecord("run-1", "2026-09-26T10:00:00Z", "0.4.0", "26.2", "MEASURE", "BENCHMARK_WORLD",
				BenchmarkRecord.SINGLE, null, 144, true, Map.of(), new BenchmarkRecord.Result(300, 200, 6, 3, 0.02), Map.of(), Map.of(), null,
				false, context);
		Path file = dir.resolve("benchmarks.json");
		BenchmarkHistory.empty().with(run).save(file);

		var old = io.github.chaotix345.rigtune.v030.core.benchmark.BenchmarkHistory.load(file);
		assertFalse(old.unreadable());
		assertEquals(1, old.runs().size());
		var oldRun = old.runs().getFirst();
		assertEquals("run-1", oldRun.id());
		assertEquals(200, oldRun.result().onePercentLowFps());
		assertEquals(2560, oldRun.context().width());
		assertTrue(oldRun.context().dhRendering());
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")));
		assertNull(oldRun.pairId());
	}
}
