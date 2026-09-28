package io.github.chaotix345.rigtune.client.tryit;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.Busy;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.ui.TryItScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.HelperLauncher;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.tryit.Triable;
import io.github.chaotix345.rigtune.core.tryit.TryIt;
import io.github.chaotix345.rigtune.core.tryit.TryItFlow;
import io.github.chaotix345.rigtune.core.tryit.TryItStore;
import io.github.chaotix345.rigtune.core.tryit.TryItText;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

// docs/v0.5/SPEC.md 6 (C09): Measured Try It. Measure -> RealController.apply(List.of(rec), entryId) -> Measure again,
// with the pair's id prefixed "tryit-", then Keep (tryit.json only) or Revert (TryItScreen opens History's Undo this).
// The stage is derived (TryItFlow) from tryit.json, benchmarks.json and history.json, never stored; the derivation and
// every tryit.json write run on one ordered chain on Probes.EXECUTOR (X8). Reached only through RealController.v05() (X4):
// the constructor only stores the controller, and the first derive is the start hook's.
// The chain between the runs is driven from this class's own END_CLIENT_TICK listener, registered when a try's first run
// is queued and otherwise returning at once on a static volatile flag (X4.4, AC6.12); a run starts on a later tick, never
// inside the benchmark's finish or show, and a CURRENT-scene run records where the player stands right before it starts.
// BenchmarkController's outcome hook hands this class its own runs' outcomes (by pair id), so they open no result screen.
// Busy.tryItRunning answers while the chain runs (C8). Everything that touches the game goes through Game (RealGame
// below), so the chain is unit-tested (TryItServiceTest).
// The derivation's contracts (docs/v0.5/design/ws-t.md): TryItFlow.Live.measuring is true from a run's queueing until its
// outcome is handled; the after snapshot and spot are written only once the try's History entry exists.
public final class TryItService {
	// One id per game session: a try records it, so a restart is told apart.
	public static final String SESSION = UUID.randomUUID().toString();
	// A queued run waits this long for its scene, and the chain this long for a run to show itself, before it stops.
	static final int WAIT_TICKS = 20 * 30;
	// However busy the benchmark (or its world) stays, a run that hasn't answered in this long is given up (review L8).
	static final int RUN_CAP_TICKS = 20 * 60 * 20;
	private static final long REFRESH_NANOS = 1_000_000_000L;
	// The cold-start rule: how long the game must have settled before Try It measures.
	public static final int SETTLE_SECONDS = 60;
	private static volatile int settleSeconds = SETTLE_SECONDS;

	// The tick listener's flag: false, it returns after this one read (0 bytes, AC6.12).
	private static volatile boolean active;
	private static volatile @Nullable TryItService ticking;
	private static boolean toastShown;

	// What the service needs from the game; RealGame is the real one.
	interface Game {
		// The game is up (false only before the client exists).
		boolean ready();

		// How long the player has been in the current world and dimension, in client ticks (the local player's age: a join,
		// a dimension change or a respawn starts it again; paused time doesn't count); -1 without a world.
		int playerTicks();

		// Which local player that is (its identity hash; a new one after a join, a dimension change or a respawn).
		int playerId();

		// System.nanoTime().
		long nanos();

		Path configDir();

		Executor executor();

		// On the render thread, as a later task.
		void later(Runnable task);

		@Nullable Text busy();

		// pending: other staged changes the next exit would apply (Triable's PENDING).
		Triable.Context context(boolean busy, boolean pending, @Nullable TryIt open, Journal.@Nullable State journal, boolean storeWritable);

		// pending.json's modification time and size with the mod-files policy (a stat; the render thread may ask), null
		// without the file.
		@Nullable Object pendingStamp();

		// Whether the next exit's helper would apply any of pending.json's ops: not the mod-file groups it holds in a
		// launcher-managed instance (they survive every restart). Reads the file: the chain only.
		boolean pendingRunsAtExit();

		@Nullable String unavailable(Scene scene);

		@Nullable String tryStart(BenchmarkRequest request);

		boolean benchmarkBusy();

		Map<String, String> snapshot();

		TryIt.@Nullable Spot spot();

		void apply(Recommendation rec, String entryId);

		// history.json, read once.
		Journal.Snapshot history();

		List<BenchmarkRecord> runs();

		// benchmarks.json could be read (and isn't from a newer RigTune).
		boolean runsReadable();

		Map<String, ApplyFailures.Failure> failures();

		void acknowledge(String runId);

		HistoryModel.Labels labels();

		void openScreen();

		void overlay(Text text);

		void toast(Text title, Text body);

		String modVersion();

		String mcVersion();

		// The listener, the outcome hook and Busy's hook (once).
		void hook();
	}

	private final @Nullable RealController controller;
	private final Game game;
	private volatile TryItView view = TryItView.EMPTY;
	// The view shows a try this session closed itself (its before stopped, its apply took nothing), until Done.
	private volatile boolean closedHere;
	private volatile @Nullable Recommendation rec;
	private volatile @Nullable BenchmarkRequest next;
	private volatile boolean measuring;
	private volatile boolean afterRun;
	private volatile boolean applyDue;
	private volatile boolean applying;
	private volatile boolean derived;
	private volatile boolean toastWanted;
	private volatile Journal.@Nullable State journalState;
	private volatile boolean storeWritable = true;
	private volatile @Nullable Object historyStamp;
	private volatile long lastStaleCheck;
	// pending.json's answer (review APPLY-4) and the stamp it was read at.
	private volatile @Nullable Object pendingStamp;
	private volatile boolean pendingRunsAtExit;
	private final AtomicBoolean pendingReading = new AtomicBoolean();
	// This launch's first title screen (Game.nanos()), -1 before it: the benchmark world's settle counts from it.
	private volatile long readyNanos = -1;
	// The local player the settle last saw, its ticks then, and when it arrived by the clock (its ticks back from then).
	private int settlePlayer;
	private int settlePlayerTicks;
	private long settleSince;
	private int waitTicks;
	private int runTicks;
	private CompletableFuture<Void> io = CompletableFuture.completedFuture(null);

	public TryItService(RealController controller) {
		this.controller = controller;
		this.game = new RealGame(controller);
	}

	TryItService(Game game) {
		this.controller = null;
		this.game = game;
	}

	// Game tests only: the settle time (0: none), back to SETTLE_SECONDS afterwards.
	public static void settleSeconds(int seconds) {
		settleSeconds = seconds;
	}

	// Other staged changes the next exit would apply (review APPLY-4: not the mod-file groups a launcher-managed instance's
	// helper holds; they survive every restart). A stat here; pending.json is read on the chain (at a derive, and when the
	// stat shows it changed: until that read, its changes count as waiting).
	boolean pendingRuns() {
		Object stamp = game.pendingStamp();
		if (stamp == null) {
			return false;
		}
		if (stamp.equals(pendingStamp)) {
			return pendingRunsAtExit;
		}
		if (pendingReading.compareAndSet(false, true)) {
			io(this::readPending);
		}
		return true;
	}

	// The chain: pending.json read again when its stamp changed.
	private void readPending() {
		try {
			Object stamp = game.pendingStamp();
			if (stamp == null || !stamp.equals(pendingStamp)) {
				pendingRunsAtExit = stamp != null && game.pendingRunsAtExit();
				pendingStamp = stamp;
			}
		} finally {
			pendingReading.set(false);
		}
	}

	// The status line while Start or Measure now in the player's own world waits for it to settle; null otherwise (the
	// benchmark world is never refused: its runs wait instead, see onTick).
	public @Nullable Text settling(Scene scene) {
		int left = scene == Scene.CURRENT ? settleLeft(scene) : 0;
		return left > 0 ? TryItText.settleRefusal(left) : null;
	}

	// Seconds before the scene has settled (0: settled, or nothing to wait for). The player's own world: their time in
	// this world and dimension, by their ticks or by the clock since they arrived (whichever is further: a RigTune screen
	// pauses a singleplayer game, and the world keeps loading behind it). The benchmark world, which each run loads
	// afresh: the time since this launch's first title screen (after a restart, the game itself is cold). Render thread.
	int settleLeft(Scene scene) {
		int need = settleSeconds;
		if (need <= 0) {
			return 0;
		}
		long now = game.nanos();
		if (scene == Scene.CURRENT) {
			int ticks = game.playerTicks();
			if (ticks < 0) {
				return 0;
			}
			int id = game.playerId();
			if (id != settlePlayer || ticks < settlePlayerTicks) {
				settlePlayer = id;
				settleSince = now - ticks * 50_000_000L;
			}
			settlePlayerTicks = ticks;
			return Math.min(seconds(need * 1_000_000_000L - ticks * 50_000_000L), seconds(need * 1_000_000_000L - (now - settleSince)));
		}
		long ready = readyNanos;
		return ready < 0 ? 0 : seconds(need * 1_000_000_000L - (now - ready));
	}

	private static int seconds(long nanos) {
		return nanos <= 0 ? 0 : (int) ((nanos + 999_999_999L) / 1_000_000_000L);
	}

	// The open try's view, in memory. A History change seen by its file's time starts a new derive (at most once a second).
	public TryItView view() {
		refreshIfStale();
		return view;
	}

	public @Nullable Text refusal(Recommendation rec) {
		return refusal(rec, null);
	}

	private @Nullable Text refusal(Recommendation rec, @Nullable Scene scene) {
		if (!game.ready()) {
			return TryItView.UNAVAILABLE;
		}
		Text busy = game.busy();
		Triable.Result result = Triable.check(rec, game.context(busy != null, pendingRuns(), view.tryIt(), journalState, storeWritable), scene);
		if (result.refusal() == null) {
			return null;
		}
		return switch (result.refusal()) {
			case BUSY -> busy;
			case SCENE -> TryItText.sceneRefusal(Objects.requireNonNullElse(game.unavailable(result.scene()), ""));
			default -> TryItText.refusal(result.refusal(), openLabel());
		};
	}

	private @Nullable String openLabel() {
		TryIt open = view.tryIt();
		return open == null ? null : game.labels().label(open.key());
	}

	// TryItScreen's Start (render thread): the try's identity is written, then the before run starts on a later tick.
	public Component start(Recommendation rec, Scene scene) {
		Text refused = refusal(rec, scene);
		if (refused != null) {
			return Texts.component(refused);
		}
		Action.SetSetting set = (Action.SetSetting) rec.action();
		Triable.Result result = Triable.check(rec, game.context(false, pendingRuns(), null, journalState, storeWritable), scene);
		Text unsettled = settling(result.scene());
		if (unsettled != null) {
			return Texts.component(unsettled);
		}
		TryIt t = TryIt.of(ChangeRecorder.newEntryId(), rec.id(), set.key(), set.currentValue(), set.newValue(), result.kind(), result.scene(), now(),
				SESSION, game.modVersion(), game.mcVersion(), game.snapshot(), null);
		this.rec = rec;
		closedHere = false;
		afterRun = false;
		measuring = true;
		waitTicks = 0;
		runTicks = 0;
		view = new TryItView(Stage.MEASURING_BEFORE, t, null, null, null, null, null, true);
		hook();
		// Review M3: the listener runs from here, so a Start whose write never answers ends at the timeout.
		active = true;
		io(() -> {
			if (!TryItStore.shared(game.configDir()).open(t)) {
				RigTune.LOGGER.warn("Try it: could not record the try in {}; not starting it", TryItStore.FILE_NAME);
				game.later(() -> {
					measuring = false;
					this.rec = null;
					view = TryItView.EMPTY.withNote(TryItText.refusal(Triable.Refusal.STORAGE, null));
				});
				return;
			}
			game.later(() -> queue(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, t.scene(), t.pairId())));
		});
		return Component.empty();
	}

	// Measure now / Measure again (READY: the change is applied and journaled): the after snapshot is written, then the
	// run starts on a later tick.
	public void measureNow() {
		TryItView v = view;
		TryIt t = v.tryIt();
		if (t == null || v.stage() != Stage.READY || !game.ready() || game.busy() != null || game.unavailable(t.scene()) != null
				|| settling(t.scene()) != null) {
			return;
		}
		hook();
		queueAfter(t, v);
	}

	// Keep: the try is closed as kept, only while its stage (derived again first, on the chain) still offers Keep: a
	// Revert or an undo meanwhile wins (review M5). Nothing but tryit.json is written. The answer says "Kept" only when
	// History hasn't changed since the view was derived (else the derive decides, silently).
	public Component keep() {
		TryItView v = view;
		TryIt t = v.tryIt();
		if (t == null || !v.actions().contains(TryItView.Action.KEEP)) {
			return Component.empty();
		}
		io(() -> {
			TryItView fresh = deriveNow();
			if (fresh.tryIt() != null && fresh.tryIt().id().equals(t.id()) && fresh.actions().contains(TryItView.Action.KEEP)) {
				close(t, TryIt.Decision.KEPT, fresh.verdict());
				deriveNow();
			}
		});
		return stale() ? Component.empty() : Texts.component(TryItText.kept(t, game.labels()));
	}

	// Done on a try that has ended: it's closed with its stage's decision (at once if this session closed it already).
	public void cancel() {
		TryItView v = view;
		TryIt t = v.tryIt();
		TryIt.Decision decision = v.closing();
		if (t == null || decision == null) {
			if (t == null && v.note() != null) {
				view = TryItView.EMPTY;
			}
			return;
		}
		view = TryItView.EMPTY;
		if (closedHere) {
			closedHere = false;
			return;
		}
		io(() -> {
			close(t, decision, v.verdict());
			deriveNow();
		});
	}

	// TryItScreen, back from History's Undo this: derive again (its footer waits for the new view, review M5).
	public void refresh() {
		io(this::deriveNow);
	}

	// The start hook (V05Services.afterStart), on Probes.EXECUTOR: the derive goes on the ordered chain (review M4). (A
	// development run, RIGTUNE_DEV_TRYIT, starts here.)
	public void derive() {
		io(this::deriveNow);
		if (controller != null && System.getenv(TryItDevRun.ENV) != null) {
			TryItDevRun.startIfAsked(controller);
		}
	}

	// The title-screen hook (V05Services.titleScreen), on the render thread: once per launch, and only for a try that a
	// restart left READY, RETRYING or NOT_APPLIED, whatever the startupToast switch says (it continues the player's action).
	public void titleToast(Minecraft minecraft) {
		if (readyNanos < 0) {
			readyNanos = game.nanos();
		}
		if (toastShown) {
			return;
		}
		toastWanted = true;
		// Review L9: a derive that finished meanwhile has already looked at toastWanted.
		if (derived) {
			toastWanted = false;
			showToast();
		}
	}

	private void showToast() {
		TryItView v = view;
		Text body = v.tryIt() == null || v.sameSession() ? null : TryItText.toastBody(v.stage());
		if (toastShown || body == null) {
			return;
		}
		toastShown = true;
		game.toast(TryItText.toastTitle(), body);
	}

	// The ordered chain: derive from the three files, and remember what the refusals need.
	private TryItView deriveNow() {
		try {
			Path configDir = game.configDir();
			TryItStore store = TryItStore.shared(configDir);
			TryIt t = store.current();
			storeWritable = store.writable();
			historyStamp = stamp(Journal.file(configDir));
			pendingReading.set(true);
			readPending();
			// Review BENCH-1: one read of history.json (a second one that failed would make the entry look missing).
			Journal.Snapshot history = game.history();
			Journal.State state = history.state();
			journalState = state;
			TryItView derivedView;
			if (t == null) {
				// No try open (the usual case, e.g. at the start hook): nothing more to read.
				derivedView = TryItView.EMPTY;
			} else {
				List<JournalEntry> entries = state == Journal.State.OK ? history.entries() : List.of();
				derivedView = TryItFlow.derive(t, game.runs(), game.runsReadable(), new TryItFlow.History(state, entries, game.failures()),
						new TryItFlow.Live(SESSION, measuring, applying || applyDue));
			}
			// Review BENCH-3: a derive queued before Start read tryit.json before the new try was written; it doesn't replace
			// the try this session is starting.
			boolean starting = derivedView.tryIt() == null && running() && view.tryIt() != null;
			if (!(closedHere && derivedView.tryIt() == null) && !starting) {
				closedHere = false;
				TryItView shown = view;
				view = shown.note() != null && derivedView.tryIt() != null && shown.tryIt() != null && shown.tryIt().id().equals(derivedView.tryIt().id())
						? derivedView.withNote(shown.note()) : derivedView;
			}
			acknowledge(t, derivedView);
			derived = true;
			if (toastWanted) {
				toastWanted = false;
				game.later(this::showToast);
			}
			return derivedView;
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Try it: could not work out the open try's stage", e);
			derived = true;
			return view;
		}
	}

	// docs/v0.5/SPEC.md AC6.15: the after run's regression notice isn't raised for numbers the verdict shows.
	private void acknowledge(@Nullable TryIt t, TryItView v) {
		BenchmarkRecord after = v.after();
		if (t == null || v.stage() != Stage.RESULT || after == null || after.id().equals(t.afterRunId())) {
			return;
		}
		game.acknowledge(after.id());
		TryItStore.shared(game.configDir()).change(t.id(), x -> x.withAfterRun(after.id()));
	}

	// Render thread: history.json's time changed (an Undo this, a Discard, another Apply): derive again, at most once a
	// second, only while a try is open and between runs (X8: a stat here, no content read).
	private void refreshIfStale() {
		if (view.tryIt() == null || running()) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastStaleCheck < REFRESH_NANOS) {
			return;
		}
		lastStaleCheck = now;
		if (stale()) {
			historyStamp = null;
			io(this::deriveNow);
		}
	}

	private boolean stale() {
		return !Objects.equals(stamp(Journal.file(game.configDir())), historyStamp);
	}

	// -- the chain (render thread unless said otherwise)

	private void queue(BenchmarkRequest request) {
		measuring = true;
		next = request;
		waitTicks = 0;
		runTicks = 0;
		active = true;
	}

	private void queueAfter(TryIt t, TryItView v) {
		Map<String, String> settings = game.snapshot();
		measuring = true;
		afterRun = true;
		view = new TryItView(Stage.MEASURING_AFTER, t, v.before(), null, null, v.changeStatus(), null, v.sameSession());
		waitTicks = 0;
		runTicks = 0;
		active = true;
		io(() -> {
			TryItStore.shared(game.configDir()).change(t.id(), x -> x.withAfter(settings, SESSION, null));
			game.later(() -> queue(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, t.scene(), t.pairId())));
		});
	}

	// BenchmarkController's outcome hook: only this try's runs (its pair id) are claimed.
	static boolean claim(BenchmarkController.Outcome outcome) {
		TryItService service = ticking;
		return service != null && service.onOutcome(outcome.request(), outcome.cancelled() ? null : outcome.record(), outcome.stepsLeftOut() > 0);
	}

	// record: the stored run, null when it was cancelled or throttled. unsettled: a step's settle timed out on terrain that
	// hadn't loaded (review BENCH-2).
	boolean onOutcome(BenchmarkRequest request, @Nullable BenchmarkRecord record, boolean unsettled) {
		TryItView v = view;
		TryIt t = v.tryIt();
		if (t == null || !measuring || !t.pairId().equals(request.pairId())) {
			return false;
		}
		measuring = false;
		if (!afterRun) {
			if (record != null && BenchmarkRecord.BEFORE.equals(record.phase()) && record.result() != null) {
				if (t.kind() == TryIt.Kind.RESTART && BenchmarkTrend.excluded(record)) {
					// Review BENCH-7: a before run left out of the trend (it created the benchmark world, or DH generated)
					// can't give a verdict after the restart: stop now, before anything changes, and say why.
					boolean fresh = record.context() != null && Boolean.TRUE.equals(record.context().worldFresh());
					closeHere(t, TryIt.Decision.CANCELLED, new TryItView(Stage.STOPPED_BEFORE, t, record, null, null, null, null, true,
							TryItText.excludedBefore(fresh)));
					game.openScreen();
					return true;
				}
				if (unsettled) {
					unsettled(t, record);
				}
				// The before is saved: apply on the next tick, then measure again.
				applyDue = true;
				view = new TryItView(Stage.APPLYING, t, record, null, null, null, null, true);
				active = true;
				game.overlay(TryItText.overlay());
				return true;
			}
			// Stopped (or measured nothing) before anything changed.
			closeHere(t, TryIt.Decision.CANCELLED, new TryItView(Stage.STOPPED_BEFORE, t, record, null, null, null, null, true));
			game.openScreen();
			return true;
		}
		if (unsettled && record != null) {
			unsettled(t, record);
		}
		io(() -> {
			deriveNow();
			game.later(game::openScreen);
		});
		return true;
	}

	// Review BENCH-2: the run's settle timed out on terrain that hadn't loaded: the try lists it (no verdict with it).
	private void unsettled(TryIt t, BenchmarkRecord record) {
		String runId = record.id();
		io(() -> TryItStore.shared(game.configDir()).change(t.id(), x -> x.withUnsettled(runId)));
	}

	// Review M2: whatever the apply does (an exception included, maybe after journaling), the entry decides: derived again.
	private void apply() {
		applyDue = false;
		applying = true;
		TryIt t = view.tryIt();
		Recommendation applied = rec;
		try {
			if (t != null && applied != null) {
				game.apply(applied, t.entryId());
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Try it: the apply failed; History decides what was changed", e);
		}
		io(() -> {
			TryItView after;
			try {
				after = deriveNow();
			} finally {
				applying = false;
			}
			game.later(() -> afterApply(t, after));
		});
	}

	private void afterApply(@Nullable TryIt t, TryItView v) {
		rec = null;
		if (t == null) {
			return;
		}
		if (v.stage() == Stage.APPLYING && t.kind() == TryIt.Kind.NOW && t.to() != null) {
			Map<String, String> settings = game.snapshot();
			if (t.to().equals(settings.get(t.key()))) {
				// Review BENCH-5: nothing journaled, but the option changed (History's write failed and only logged): the
				// after snapshot is the proof, so the try stays open as ENTRY_MISSING (Keep only), with a note.
				io(() -> {
					TryItStore.shared(game.configDir()).change(t.id(), x -> x.withAfter(settings, SESSION, null));
					view = deriveNow().withNote(TryItText.unrecorded());
					game.later(game::openScreen);
				});
				return;
			}
		}
		if (v.stage() == Stage.APPLYING || v.stage() == Stage.NOT_APPLIED) {
			// Nothing was journaled for the key: the apply took nothing (review L7: only these; HISTORY_UNREADABLE keeps
			// the try, and its Revert).
			closeHere(t, TryIt.Decision.FAILED, new TryItView(Stage.NOT_APPLIED, t, v.before(), null, null, v.changeStatus(), v.failure(), true));
			game.openScreen();
			return;
		}
		if (t.kind() == TryIt.Kind.NOW && JournalChange.APPLIED.equals(v.changeStatus()) && v.stage() == Stage.READY) {
			queueAfter(t, v);
			return;
		}
		game.openScreen();
	}

	private void closeHere(TryIt t, TryIt.Decision decision, TryItView shown) {
		rec = null;
		view = shown;
		closedHere = true;
		io(() -> close(t, decision, null));
	}

	private void close(TryIt t, TryIt.Decision decision, TryItVerdict.@Nullable Verdict verdict) {
		String kind = verdict == null ? null : verdict.kind().name().toLowerCase(Locale.ROOT);
		if (!TryItStore.shared(game.configDir()).close(t.id(), TryIt.Closed.of(t, decision, kind, verdict == null ? null : verdict.lowPercent(),
				verdict == null ? null : verdict.avgPercent(), verdict == null ? null : verdict.floorPercent(), now()))) {
			RigTune.LOGGER.warn("Try it: could not record the closed try in {}", TryItStore.FILE_NAME);
		}
	}

	// The chain can't go on (a run that couldn't start, or ended without an outcome; why: the benchmark's refusal or
	// null): before the apply the try stops as cancelled; afterwards it's derived again (READY: Measure again), with the
	// reason as its note (review L8).
	private void lost(@Nullable Text why) {
		measuring = false;
		next = null;
		TryIt t = view.tryIt();
		Text note = TryItText.lost(why);
		if (t != null && !afterRun) {
			closeHere(t, TryIt.Decision.CANCELLED, new TryItView(Stage.STOPPED_BEFORE, t, null, null, null, null, null, true, note));
			game.openScreen();
			return;
		}
		io(() -> {
			TryItView derivedView = deriveNow();
			view = derivedView.withNote(note);
			game.later(game::openScreen);
		});
	}

	private void hook() {
		ticking = this;
		game.hook();
	}

	boolean running() {
		return next != null || measuring || applyDue || applying;
	}

	static boolean chainRunning() {
		TryItService service = ticking;
		return service != null && service.running();
	}

	// Review L13: the listener has nothing to do (TryItGameTest checks it before timing the idle call).
	public static boolean idle() {
		return !active;
	}

	// END_CLIENT_TICK (render thread). Without a try between or inside its runs: one volatile read, nothing allocated
	// (public for TryItGameTest's timing of the idle call).
	public static void tick(Minecraft minecraft) {
		if (!active) {
			return;
		}
		TryItService service = ticking;
		if (service == null) {
			active = false;
			return;
		}
		service.tick();
	}

	void tick() {
		try {
			onTick();
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Try it: the chain failed; stopping it", e);
			lost(null);
		}
	}

	private void onTick() {
		if (applyDue) {
			apply();
			return;
		}
		BenchmarkRequest queued = next;
		if (queued != null) {
			String unavailable = game.unavailable(queued.scene());
			if (unavailable == null && settleLeft(queued.scene()) > 0) {
				// The cold-start rule: a queued run (the benchmark world after a restart, or the player's world after a
				// dimension change) waits for the game to settle instead of refusing; the chain's cap still holds.
				waitTicks = 0;
				if (view.note() == null) {
					view = view.withNote(TryItText.settleWaiting());
				}
				if (++runTicks > RUN_CAP_TICKS) {
					lost(null);
				}
			} else if (unavailable == null) {
				next = null;
				waitTicks = 0;
				if (TryItText.settleWaiting().equals(view.note())) {
					view = view.withNote(null);
				}
				// Review M6: where the player stands right before the run starts (in the player's own world).
				recordSpot(queued);
				String refused = game.tryStart(queued);
				// Review M1: a start that failed inside the benchmark may have handed its outcome over already.
				if (refused != null && measuring) {
					RigTune.LOGGER.warn("Try it: the run couldn't start ({})", refused);
					lost(TryItText.sceneRefusal(refused));
				}
			} else if (++waitTicks > WAIT_TICKS) {
				RigTune.LOGGER.warn("Try it: the run couldn't start in {} s ({})", WAIT_TICKS / 20, unavailable);
				lost(TryItText.sceneRefusal(unavailable));
			}
			return;
		}
		if (measuring) {
			runTicks++;
			if (game.benchmarkBusy() && runTicks <= RUN_CAP_TICKS) {
				waitTicks = 0;
			} else if (++waitTicks > WAIT_TICKS || runTicks > RUN_CAP_TICKS) {
				RigTune.LOGGER.warn("Try it: the run ended without an outcome");
				lost(null);
			}
			return;
		}
		if (!applying) {
			active = false;
		}
	}

	private void recordSpot(BenchmarkRequest request) {
		TryIt t = view.tryIt();
		if (t == null || request.scene() != Scene.CURRENT) {
			return;
		}
		TryIt.Spot spot = game.spot();
		boolean after = afterRun;
		io(() -> TryItStore.shared(game.configDir()).change(t.id(), x -> after ? x.withAfterSpot(spot) : x.withBeforeSpot(spot)));
	}

	private void io(Runnable task) {
		synchronized (this) {
			io = io.thenRunAsync(() -> {
				try {
					task.run();
				} catch (RuntimeException e) {
					RigTune.LOGGER.error("Try it: a step failed", e);
				}
			}, game.executor());
		}
	}

	private static String now() {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
	}

	private static Object stamp(Path file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			return List.of(attributes.lastModifiedTime(), attributes.size());
		} catch (IOException e) {
			return "missing";
		}
	}

	// The game: RealController, Minecraft, the benchmark and the client's stores.
	private static final class RealGame implements Game {
		private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(8000L);
		private static boolean registered;

		private final RealController controller;

		RealGame(RealController controller) {
			this.controller = controller;
		}

		private Minecraft minecraft() {
			return Objects.requireNonNull(controller.minecraft(), "the game");
		}

		@Override
		public boolean ready() {
			return controller.minecraft() != null;
		}

		@Override
		public int playerTicks() {
			Minecraft minecraft = minecraft();
			return minecraft.player == null ? -1 : minecraft.player.tickCount;
		}

		@Override
		public int playerId() {
			Minecraft minecraft = minecraft();
			return minecraft.player == null ? 0 : System.identityHashCode(minecraft.player);
		}

		@Override
		public long nanos() {
			return System.nanoTime();
		}

		@Override
		public Path configDir() {
			return controller.configDir();
		}

		@Override
		public Executor executor() {
			return Probes.EXECUTOR;
		}

		@Override
		public void later(Runnable task) {
			minecraft().execute(task);
		}

		@Override
		public @Nullable Text busy() {
			return Busy.refusal(controller);
		}

		@Override
		public Triable.Context context(boolean busy, boolean pending, @Nullable TryIt open, Journal.@Nullable State journal, boolean storeWritable) {
			Minecraft minecraft = minecraft();
			List<ConfigTargets.Target> targets = ConfigTargets.all(controller.configDir());
			return new Triable.Context(key -> {
				ConfigTargets.Target target = ConfigTargets.forKey(targets, key);
				return target != null && Files.isRegularFile(target.file());
			}, minecraft.level != null, minecraft.level != null && !minecraft.hasSingleplayerServer(), pending, busy,
					scene -> BenchmarkController.unavailable(minecraft, scene) != null, open != null,
					journal == null || journal == Journal.State.OK || journal == Journal.State.MISSING, !BenchmarkStore.history().unreadable(),
					storeWritable);
		}

		@Override
		public @Nullable String unavailable(Scene scene) {
			return BenchmarkController.unavailable(minecraft(), scene);
		}

		@Override
		public @Nullable String tryStart(BenchmarkRequest request) {
			return BenchmarkController.tryStart(minecraft(), request, BenchmarkController.defaultConfig());
		}

		@Override
		public boolean benchmarkBusy() {
			return BenchmarkController.running() || BenchmarkWorld.busy();
		}

		@Override
		public Map<String, String> snapshot() {
			return SettingsBridge.read(minecraft()).values();
		}

		// Where the player stands (docs/v0.5/SPEC.md 6, the coordinator's decision): the block, the dimension and this
		// world's or server's key. Never used to move the player.
		@Override
		public TryIt.@Nullable Spot spot() {
			Minecraft minecraft = minecraft();
			if (minecraft.player == null || minecraft.level == null) {
				return null;
			}
			Vec3 position = minecraft.player.position();
			String server;
			if (minecraft.getSingleplayerServer() != null) {
				Path folder = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
				server = "singleplayer:" + folder;
			} else {
				server = minecraft.getCurrentServer() == null ? null : "server:" + minecraft.getCurrentServer().ip;
			}
			return new TryIt.Spot((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z),
					String.valueOf(minecraft.level.dimension()), server);
		}

		@Override
		public void apply(Recommendation rec, String entryId) {
			controller.apply(List.of(rec), entryId);
		}

		@Override
		public Journal.Snapshot history() {
			return ClientJournal.get().snapshot();
		}

		@Override
		public List<BenchmarkRecord> runs() {
			return BenchmarkStore.history().runs();
		}

		@Override
		public @Nullable Object pendingStamp() {
			Object file = stamp(PendingActions.defaultPath(configDir()));
			return "missing".equals(file) ? null : List.of(file, controller.modFiles());
		}

		@Override
		public boolean pendingRunsAtExit() {
			Path file = PendingActions.defaultPath(configDir());
			try {
				PendingActions plan = PendingActions.load(file);
				int held = HelperLauncher.holds(controller.modFiles()) ? ApplyExecutor.held(plan, file).size() : 0;
				return plan.ops().size() > held;
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("Try it: could not read {}; counting its changes as waiting", file.getFileName(), e);
				return true;
			}
		}

		@Override
		public boolean runsReadable() {
			BenchmarkHistory history = BenchmarkStore.history();
			return !history.unreadable() && !history.newerOnDisk();
		}

		@Override
		public Map<String, ApplyFailures.Failure> failures() {
			Path last = ApplyResult.defaultPath(configDir());
			try {
				ApplyResult result = Files.isRegularFile(last) ? ApplyResult.load(last) : null;
				return ApplyFailures.byOpId(result, List.of(FabricLoader.getInstance().getGameDir().resolve("mods"), configDir()));
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("Try it: could not read {}", last.getFileName(), e);
				return Map.of();
			}
		}

		@Override
		public void acknowledge(String runId) {
			controller.trendService().acknowledge(runId);
		}

		@Override
		public HistoryModel.Labels labels() {
			return controller.settingLabels();
		}

		@Override
		public void openScreen() {
			Minecraft minecraft = minecraft();
			minecraft.gui.setScreen(new TryItScreen(minecraft.gui.screen(), controller, null));
		}

		@Override
		public void overlay(Text text) {
			Minecraft minecraft = minecraft();
			if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Texts.component(text));
			}
		}

		@Override
		public void toast(Text title, Text body) {
			SystemToast.add(minecraft().gui.toastManager(), TOAST_ID, Texts.component(title), Texts.component(body));
		}

		@Override
		public String modVersion() {
			return controller.modVersion();
		}

		@Override
		public String mcVersion() {
			return HardwareProbe.minecraftVersion();
		}

		// When a run is first queued (never at init, X4).
		@Override
		public void hook() {
			if (!registered) {
				registered = true;
				ClientTickEvents.END_CLIENT_TICK.register(TryItService::tick);
				BenchmarkController.setOutcomeHandler(TryItService::claim);
				Busy.tryItRunning = TryItService::chainRunning;
			}
		}
	}
}
