package io.github.chaotix345.rigtune.client.tryit;

import io.github.chaotix345.rigtune.client.Busy;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.tryit.Triable;
import io.github.chaotix345.rigtune.core.tryit.TryIt;
import io.github.chaotix345.rigtune.core.tryit.TryItStore;
import io.github.chaotix345.rigtune.core.tryit.TryItText;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md AC6.12 (unit part, X4.4): Try it's own END_CLIENT_TICK listener returns at once while no try is
// between or inside its runs: one volatile read, nothing allocated (a million calls allocate less than the measuring
// noise; one object per call would be megabytes), and it never needs the game or the lazy holder then. The TryItGameTest
// times it strictly in a running game. Nothing hooks Busy before a try's first run is queued.
// The chain against a fake game (FakeGame: the files are real, in a temporary config folder; the executor and the render
// thread are queues that drain() runs): the code review's M1-M6, and the cold-start rule (the coordinator's amendment).
class TryItServiceTest {
	private static final long NOISE_BYTES = 64 * 1024;
	private static final String KEY = "vanilla.cutoutLeaves";
	private static final TryIt.Spot HERE = new TryIt.Spot(100, 64, -200, "minecraft:overworld", "singleplayer:World");
	private static final TryIt.Spot THERE = new TryIt.Spot(180, 70, -200, "minecraft:overworld", "singleplayer:World");

	@TempDir
	Path dir;

	private static long allocated() {
		return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
	}

	@AfterEach
	void idleAgain() {
		for (int i = 0; i < 2 * TryItService.RUN_CAP_TICKS && !TryItService.idle(); i++) {
			TryItService.tick(null);
		}
	}

	@Test
	void theIdleTickAllocatesNothingAndNeedsNoGame() {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported());
		assertTrue(TryItService.idle());
		for (int i = 0; i < 200_000; i++) {
			TryItService.tick(null);
		}
		long before = allocated();
		for (int i = 0; i < 1_000_000; i++) {
			TryItService.tick(null);
		}
		long bytes = allocated() - before;
		assertTrue(bytes < NOISE_BYTES, "a million idle ticks allocated " + bytes + " bytes");
	}

	@Test
	void theServiceStartsWithNoTryAndHooksNothing() {
		TryItService service = new TryItService((RealController) null);
		assertEquals(TryItView.EMPTY, service.view());
		assertFalse(Busy.tryItRunning.getAsBoolean());
		assertTrue(TryItService.SESSION.length() > 8);
	}

	// Review M1: a start that failed inside the benchmark hands its (cancelled) outcome over before tryStart answers: the
	// try is closed once and one screen opens.
	@Test
	void aStartThatFailedAfterHandingItsOutcomeOverIsHandledOnce() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		game.onStart = request -> {
			service.onOutcome(request, null);
			return "rigtune.benchmark.refused.running";
		};
		TryIt t = start(game, Scene.BENCHMARK_WORLD);
		TryItService.tick(null);
		game.drain();
		assertEquals(1, game.screens);
		assertEquals(Stage.STOPPED_BEFORE, service.view().stage());
		assertNull(store().current());
		assertEquals(List.of(t.id()), store().recent().stream().map(TryIt.Closed::id).toList());
		assertEquals(TryIt.Decision.CANCELLED, store().recent().getFirst().decision());
		assertFalse(service.running());
	}

	// Review M2: an apply that throws after journaling its change: History decides (the try goes on to its after run),
	// never "stopped before anything changed", and the chain doesn't stay applying.
	@Test
	void anApplyThatThrowsAfterJournalingIsDerived() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		game.applyThrows = true;
		TryIt t = start(game, Scene.BENCHMARK_WORLD);
		runBefore(game, t);
		TryItService.tick(null);
		game.drain();
		assertNotNull(store().current(), "the try stays open");
		assertEquals(t.id(), store().current().id());
		assertEquals(Stage.MEASURING_AFTER, service.view().stage());
		assertEquals(0, game.screens);
		runAfter(game, t);
		assertEquals(Stage.RESULT, service.view().stage());
		assertFalse(service.running(), "nothing keeps Busy");
	}

	// Review M3: the listener runs from Start on, so a Start whose tryit.json write never answers ends at the timeout and
	// Busy is released.
	@Test
	void aStartWhoseWriteNeverAnswersEndsAtTheTimeout() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		assertTrue(TryItService.idle());
		game.executor = task -> {
		};
		assertEquals("", service.start(rec(), Scene.BENCHMARK_WORLD).getString());
		assertTrue(service.running());
		for (int i = 0; i <= TryItService.WAIT_TICKS + 1; i++) {
			TryItService.tick(null);
		}
		assertFalse(service.running());
		assertEquals(Stage.STOPPED_BEFORE, service.view().stage());
		assertEquals(1, game.screens);
	}

	// Review M4: the start hook's derive runs on the ordered chain, never on the calling thread.
	@Test
	void theStartHooksDeriveRunsOnTheChain() {
		FakeGame game = new FakeGame(dir);
		game.service.derive();
		assertEquals(0, game.journalReads, "nothing read before the chain runs it");
		game.drain();
		assertEquals(1, game.journalReads);
	}

	// Review M5: Revert -> Undo this -> Keep on the view from before the undo: the chain derives first, so the try isn't
	// recorded as kept.
	@Test
	void keepAfterAnUndoIsNotRecordedAsKept() throws IOException {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		TryIt t = start(game, Scene.BENCHMARK_WORLD);
		runBefore(game, t);
		applyAndQueueAfter(game);
		runAfter(game, t);
		assertEquals(Stage.RESULT, service.view().stage());
		game.journal.update(entries -> entries.stream().map(e -> e.id().equals(t.entryId())
				? new JournalEntry(e.id(), e.at(), e.kind(), e.rigtuneVersion(), e.mcVersion(), e.undoOf(),
				e.changes().stream().map(c -> c.withStatus(JournalChange.REVERTED)).toList()) : e).toList());
		service.keep();
		game.drain();
		assertTrue(store().recent().stream().noneMatch(c -> c.decision() == TryIt.Decision.KEPT), "not kept: " + store().recent());
		assertEquals(Stage.REVERTED, service.view().stage());
	}

	// Review M6: each run records where the player stands as it starts (not when it was queued): moving while the before
	// run waits to start is no "you moved".
	@Test
	void theSpotIsWhereEachRunStarts() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		TryIt t = start(game, Scene.CURRENT);
		game.spot = THERE;
		runBefore(game, t);
		applyAndQueueAfter(game);
		runAfter(game, t);
		assertEquals(Stage.RESULT, service.view().stage());
		assertEquals(THERE, store().current().beforeSpot());
		assertEquals(THERE, store().current().afterSpot());
		assertTrue(causes(service).stream().noneMatch(c -> c instanceof TryItVerdict.Cause.Moved), "causes: " + causes(service));
	}

	// Review M6: moving while the change is applied -> NOT_COMPARABLE "you moved".
	@Test
	void movingBetweenTheRunsIsNotComparable() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		TryIt t = start(game, Scene.CURRENT);
		runBefore(game, t);
		game.spot = THERE;
		applyAndQueueAfter(game);
		runAfter(game, t);
		assertEquals(Stage.RESULT, service.view().stage());
		assertEquals(HERE, store().current().beforeSpot());
		assertEquals(THERE, store().current().afterSpot());
		assertEquals(TryItVerdict.Kind.NOT_COMPARABLE, service.view().verdict().kind());
		assertTrue(causes(service).stream().anyMatch(c -> c instanceof TryItVerdict.Cause.Moved), "causes: " + causes(service));
	}

	// The cold-start rule: in the player's own world, Start waits until they've been there a minute (their time in this
	// world and dimension); the status line says how long is left.
	@Test
	void startInThePlayersWorldRefusesUntilItHasSettled() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		game.playerTicks = 20 * 10 + 5;
		Text settling = service.settling(Scene.CURRENT);
		assertNotNull(settling);
		assertEquals("Try It measures better once the world has settled. Play for about a minute first (50 s left).", settling.english());
		assertFalse(service.start(rec(), Scene.CURRENT).getString().isEmpty(), "Start answers with the status line");
		game.drain();
		assertNull(store().current(), "no try opened");
		assertTrue(game.started.isEmpty());
		assertFalse(service.running());
		game.playerTicks = 20 * TryItService.SETTLE_SECONDS;
		assertNull(service.settling(Scene.CURRENT));
		start(game, Scene.CURRENT);
	}

	// The count goes on by the clock while a RigTune screen pauses a singleplayer game (no ticks); a new local player (a
	// dimension change, a respawn) starts it again.
	@Test
	void theCountGoesOnWhileAScreenPausesTheGameAndRestartsWithANewPlayer() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		game.playerTicks = 20 * 10 + 5;
		assertEquals(50, service.settleLeft(Scene.CURRENT));
		game.nanos += 30_000_000_000L;
		assertEquals(20, service.settleLeft(Scene.CURRENT), "paused: no ticks, 30 s by the clock");
		game.playerTicks = 20 * 55;
		assertEquals(5, service.settleLeft(Scene.CURRENT), "the ticks are further");
		game.playerId = 2;
		game.playerTicks = 0;
		assertEquals(TryItService.SETTLE_SECONDS, service.settleLeft(Scene.CURRENT), "a new dimension");
		game.nanos += 60_000_000_000L;
		assertEquals(0, service.settleLeft(Scene.CURRENT));
	}

	// The benchmark world (every RESTART try) isn't refused: its runs wait until a minute after this launch's first title
	// screen (a restart's world load), with a note, then start.
	@Test
	void benchmarkWorldRunsWaitForTheGameToSettleInsteadOfRefusing() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		game.nanos = 1_000_000_000L;
		service.titleToast(null);
		game.nanos += 10_000_000_000L;
		assertNull(service.settling(Scene.BENCHMARK_WORLD), "never refused");
		start(game, Scene.BENCHMARK_WORLD);
		for (int i = 0; i < 40; i++) {
			TryItService.tick(null);
		}
		assertTrue(game.started.isEmpty(), "the before run waits");
		assertEquals(Stage.MEASURING_BEFORE, service.view().stage());
		assertEquals(TryItText.settleWaiting(), service.view().note());
		game.nanos += 50_000_000_000L;
		TryItService.tick(null);
		assertEquals(1, game.started.size(), "the before run starts once the game has settled");
		assertNull(service.view().note());
	}

	// A settled game, the seam at 0 (game tests) and the benchmark world never refuse.
	@Test
	void onlyAnUnsettledOwnWorldRefuses() {
		FakeGame game = new FakeGame(dir);
		TryItService service = game.service;
		assertNull(service.settling(Scene.CURRENT), "two minutes in the world");
		game.playerTicks = 0;
		assertNotNull(service.settling(Scene.CURRENT));
		assertNull(service.settling(Scene.BENCHMARK_WORLD));
		TryItService.settleSeconds(0);
		try {
			assertNull(service.settling(Scene.CURRENT));
		} finally {
			TryItService.settleSeconds(TryItService.SETTLE_SECONDS);
		}
	}

	private TryItStore store() {
		return TryItStore.shared(dir);
	}

	private static List<TryItVerdict.Cause> causes(TryItService service) {
		TryItVerdict.Verdict verdict = service.view().verdict();
		assertNotNull(verdict);
		return verdict.causes();
	}

	// Start, and the before run queued.
	private TryIt start(FakeGame game, Scene scene) {
		assertEquals("", game.service.start(rec(), scene).getString());
		game.drain();
		TryIt t = store().current();
		assertNotNull(t);
		return t;
	}

	// The before run starts on a tick and is saved; its outcome is handed over.
	private static void runBefore(FakeGame game, TryIt t) {
		TryItService.tick(null);
		game.drain();
		BenchmarkRecord before = run("before", BenchmarkRecord.BEFORE, t);
		game.runs.add(before);
		assertTrue(game.service.onOutcome(game.started.getLast(), before));
		game.drain();
	}

	// The apply on the next tick, then the after run queued.
	private static void applyAndQueueAfter(FakeGame game) {
		TryItService.tick(null);
		game.drain();
		assertEquals(Stage.MEASURING_AFTER, game.service.view().stage());
	}

	private static void runAfter(FakeGame game, TryIt t) {
		TryItService.tick(null);
		game.drain();
		BenchmarkRecord after = run("after", BenchmarkRecord.AFTER, t);
		game.runs.add(after);
		assertTrue(game.service.onOutcome(game.started.getLast(), after));
		game.drain();
	}

	private static Recommendation rec() {
		return new Recommendation("setting:" + KEY, Category.SETTING, Impact.MEDIUM, "t", "r", new Action.SetSetting(KEY, "true", "false"), true);
	}

	private static BenchmarkRecord run(String id, String phase, TryIt t) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(12, 12, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(false, false, null, 2560, 1440, false, BenchmarkRecord.Context.PROTOCOL,
				"hash-a", null);
		return new BenchmarkRecord(id, Instant.now().toString(), "0.5.0+mc26.2", "26.2", "MEASURE", t.scene().name(), phase, t.pairId(), 144, true,
				knobs, new BenchmarkRecord.Result(800, 500, 2, 2, 0.01), Map.of(), Map.of(), null, false, context);
	}

	private static final class FakeGame implements TryItService.Game {
		final Path configDir;
		final Journal journal;
		final TryItService service;
		final Queue<Runnable> io = new ArrayDeque<>();
		final Queue<Runnable> render = new ArrayDeque<>();
		final List<BenchmarkRequest> started = new ArrayList<>();
		final List<BenchmarkRecord> runs = new ArrayList<>();
		Executor executor = io::add;
		Function<BenchmarkRequest, @Nullable String> onStart = request -> null;
		boolean applyThrows;
		TryIt.Spot spot = HERE;
		int playerTicks = 20 * 120;
		int playerId = 1;
		long nanos;
		int screens;
		int journalReads;

		FakeGame(Path configDir) {
			this.configDir = configDir;
			this.journal = new Journal(configDir, "0.5.0+mc26.2", "26.2", (message, error) -> {
				throw new AssertionError(message, error);
			});
			this.service = new TryItService(this);
		}

		// Runs the chain's and the render thread's tasks until both are empty.
		void drain() {
			while (!io.isEmpty() || !render.isEmpty()) {
				while (!io.isEmpty()) {
					io.poll().run();
				}
				while (!render.isEmpty()) {
					render.poll().run();
				}
			}
		}

		@Override
		public boolean ready() {
			return true;
		}

		@Override
		public int playerTicks() {
			return playerTicks;
		}

		@Override
		public int playerId() {
			return playerId;
		}

		@Override
		public long nanos() {
			return nanos;
		}

		@Override
		public Path configDir() {
			return configDir;
		}

		@Override
		public Executor executor() {
			return task -> executor.execute(task);
		}

		@Override
		public void later(Runnable task) {
			render.add(task);
		}

		@Override
		public @Nullable Text busy() {
			return null;
		}

		@Override
		public Triable.Context context(boolean busy, @Nullable TryIt open, Journal.@Nullable State journal, boolean storeWritable) {
			return new Triable.Context(key -> true, true, false, false, busy, scene -> false, open != null, true, true, storeWritable);
		}

		@Override
		public @Nullable String unavailable(Scene scene) {
			return null;
		}

		@Override
		public @Nullable String tryStart(BenchmarkRequest request) {
			started.add(request);
			return onStart.apply(request);
		}

		@Override
		public boolean benchmarkBusy() {
			return false;
		}

		@Override
		public Map<String, String> snapshot() {
			return Map.of("vanilla.renderDistance", "12");
		}

		@Override
		public TryIt.@Nullable Spot spot() {
			return spot;
		}

		@Override
		public void apply(Recommendation rec, String entryId) {
			Action.SetSetting set = (Action.SetSetting) rec.action();
			journal.record(entryId, JournalEntry.APPLY, List.of(JournalChange.setting(set.key(), set.currentValue(), set.newValue(), JournalChange.APPLIED,
					null)));
			if (applyThrows) {
				throw new IllegalStateException("the apply failed after journaling");
			}
		}

		@Override
		public Journal journal() {
			journalReads++;
			return journal;
		}

		@Override
		public List<BenchmarkRecord> runs() {
			return List.copyOf(runs);
		}

		@Override
		public Map<String, ApplyFailures.Failure> failures() {
			return Map.of();
		}

		@Override
		public void acknowledge(String runId) {
		}

		@Override
		public HistoryModel.Labels labels() {
			return HistoryModel.Labels.RAW;
		}

		@Override
		public void openScreen() {
			screens++;
		}

		@Override
		public void overlay(Text text) {
		}

		@Override
		public void toast(Text title, Text body) {
		}

		@Override
		public String modVersion() {
			return "0.5.0+mc26.2";
		}

		@Override
		public String mcVersion() {
			return "26.2";
		}

		@Override
		public void hook() {
		}
	}
}
