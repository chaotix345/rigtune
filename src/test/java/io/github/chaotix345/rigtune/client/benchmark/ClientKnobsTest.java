package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRun;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkSession;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.KnobGuard;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.ModToggles;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.benchmark.Timing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md AC8.1: every benchmark render/simulation distance change, and every restore, is followed by a
// broadcast to the server, and none of them writes options.txt.
class ClientKnobsTest {
	private static final Knobs ORIGINAL = new Knobs(8, 10, true, false);

	@TempDir
	Path dir;

	// Counts what ClientKnobs asks of the game's options, in order.
	private static final class CountingVanilla implements ClientKnobs.Vanilla {
		final List<String> calls = new ArrayList<>();
		final List<Boolean> saves = new ArrayList<>();
		int broadcasts;
		RuntimeException broadcastFailure;

		@Override
		public Map<String, SettingsBridge.Result> apply(Map<String, String> values, boolean save) {
			saves.add(save);
			calls.add("apply " + values);
			Map<String, SettingsBridge.Result> out = new LinkedHashMap<>();
			values.forEach((k, v) -> out.put(k, new SettingsBridge.Result(k, true, "?", v, "")));
			return out;
		}

		@Override
		public void broadcast() {
			calls.add("broadcast");
			broadcasts++;
			if (broadcastFailure != null) {
				throw broadcastFailure;
			}
		}
	}

	private static final class FakeMods implements ModToggles.Mods {
		final List<String> calls = new ArrayList<>();

		@Override
		public void setDhRendering(boolean on) {
			calls.add("dh " + on);
		}

		@Override
		public void restoreDhRendering() {
			calls.add("dh restore");
		}

		@Override
		public void setShaders(boolean on) {
			calls.add("shaders " + on);
		}
	}

	private final CountingVanilla vanilla = new CountingVanilla();
	private final FakeMods mods = new FakeMods();

	private ClientKnobs knobs() {
		return new ClientKnobs(vanilla, new ModToggles(ORIGINAL, dir.resolve("benchmark-restore.json"), mods, () -> "now"));
	}

	@Test
	void aRenderDistanceChangeIsAppliedInMemoryThenBroadcast() throws Exception {
		knobs().apply(ORIGINAL, ORIGINAL.withRenderDistance(12));
		assertEquals(List.of("apply {vanilla.renderDistance=12}", "broadcast"), vanilla.calls);
		assertEquals(List.of(false), vanilla.saves);
	}

	@Test
	void aSimulationDistanceChangeIsBroadcastToo() throws Exception {
		knobs().apply(ORIGINAL, ORIGINAL.withSimulationDistance(6));
		assertEquals(List.of("apply {vanilla.simulationDistance=6}", "broadcast"), vanilla.calls);
	}

	@Test
	void bothChangeInOneApplyAndOneBroadcast() throws Exception {
		knobs().apply(ORIGINAL, ORIGINAL.withRenderDistance(12).withSimulationDistance(6));
		assertEquals(List.of("apply {vanilla.renderDistance=12, vanilla.simulationDistance=6}", "broadcast"), vanilla.calls);
	}

	@Test
	void aModOnlyChangeTouchesNoVanillaOption() throws Exception {
		knobs().apply(ORIGINAL, ORIGINAL.withDhRendering(false));
		assertTrue(vanilla.calls.isEmpty());
		assertEquals(List.of("dh false"), mods.calls);
	}

	@Test
	void aFailedBroadcastStillSetsTheModsThenThrows() {
		vanilla.broadcastFailure = new IllegalStateException("no connection");
		ClientKnobs knobs = knobs();
		Exception e = assertThrows(IllegalStateException.class, () -> knobs.apply(ORIGINAL, ORIGINAL.withRenderDistance(12).withDhRendering(false)));
		assertTrue(e.getMessage().contains("no connection"), e.getMessage());
		assertEquals(List.of("dh false"), mods.calls);
	}

	private BenchmarkRun run() {
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, new BenchmarkSession.TuneLimits(4, 32, 100, false, 5), Timing.DEFAULT, 0);
		return new BenchmarkRun(session, new KnobGuard(ORIGINAL, knobs()), () -> 0L);
	}

	private static FrameStats fps(double low) {
		return new FrameStats(1000, low * 2, low, 1000 / low, 2000 / low);
	}

	// Steps: RD 8 (the original, nothing to apply), then 10: the run is left at RD 10 before it ends.
	private BenchmarkRun runAtTen() {
		BenchmarkRun run = run();
		assertEquals(8, run.advance().orElseThrow().knobs().renderDistance());
		run.record(fps(500));
		Step second = run.advance().orElseThrow();
		assertEquals(10, second.knobs().renderDistance());
		assertEquals(List.of("apply {vanilla.renderDistance=10}", "broadcast"), vanilla.calls);
		vanilla.calls.clear();
		return run;
	}

	@Test
	void cancellingRestoresAndBroadcasts() {
		BenchmarkRun run = runAtTen();
		run.cancel();
		assertTrue(run.restoreOk());
		assertEquals(List.of("apply {vanilla.renderDistance=8}", "broadcast"), vanilla.calls);
	}

	@Test
	void aFailedRunRestoresAndBroadcasts() {
		BenchmarkRun run = runAtTen();
		run.fail(new RuntimeException("boom"));
		assertEquals(List.of("apply {vanilla.renderDistance=8}", "broadcast"), vanilla.calls);
	}

	@Test
	void aFinishedRunRestoresAndBroadcasts() {
		BenchmarkRun run = run();
		Optional<Step> step;
		while ((step = run.advance()).isPresent()) {
			run.record(fps(1000.0 / step.get().knobs().renderDistance() * 8));
		}
		assertTrue(run.finished());
		assertFalse(vanilla.calls.isEmpty());
		assertEquals("apply {vanilla.renderDistance=8}", vanilla.calls.get(vanilla.calls.size() - 2));
		assertEquals("broadcast", vanilla.calls.getLast());
		assertTrue(vanilla.saves.stream().noneMatch(s -> s), "options.txt is never written for a test value: " + vanilla.saves);
		for (int i = 0; i < vanilla.calls.size(); i++) {
			if (vanilla.calls.get(i).startsWith("apply")) {
				assertEquals("broadcast", vanilla.calls.get(i + 1), "every apply is followed by a broadcast: " + vanilla.calls);
			}
		}
	}
}
