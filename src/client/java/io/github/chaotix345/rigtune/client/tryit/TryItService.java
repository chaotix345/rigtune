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
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
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

// docs/v0.5/SPEC.md 6 (C09): Measured Try It. Measure -> RealController.apply(List.of(rec), entryId) -> Measure again,
// with the pair's id prefixed "tryit-", then Keep (tryit.json only) or Revert (TryItScreen opens History's Undo this).
// The stage is derived (TryItFlow) from tryit.json, benchmarks.json and history.json, never stored; tryit.json is
// written only here, through one ordered chain on Probes.EXECUTOR (X8). Reached only through RealController.v05() (X4):
// the constructor only stores the controller, and the first derive is the start hook's, on a worker.
// The chain between the runs is driven from this class's own END_CLIENT_TICK listener, registered when a try's first run
// is queued and otherwise returning at once on a static volatile flag (X4.4, AC6.12); a run starts on a later tick,
// never inside the benchmark's finish or show. BenchmarkController's outcome hook hands this class its own runs'
// outcomes (by pair id), so they open no result screen. Busy.tryItRunning answers while the chain runs (C8).
// The derivation's contracts (docs/v0.5/design/ws-t.md): TryItFlow.Live.measuring is true from a run's queueing until its
// outcome is handled; the after snapshot and spot are written only once the try's History entry exists.
public final class TryItService {
	// One id per game session: a try records it, so a restart is told apart.
	public static final String SESSION = UUID.randomUUID().toString();
	// A queued run waits this long for its scene, and a started one this long to show itself, before the try stops.
	private static final int WAIT_TICKS = 20 * 30;
	private static final long REFRESH_NANOS = 1_000_000_000L;
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(8000L);

	// The tick listener's flag: false, it returns after this one read (0 bytes, AC6.12).
	private static volatile boolean active;
	private static boolean registered;
	private static volatile @Nullable TryItService ticking;
	private static boolean toastShown;

	private final RealController controller;
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
	private int waitTicks;
	private CompletableFuture<Void> io = CompletableFuture.completedFuture(null);

	public TryItService(RealController controller) {
		this.controller = controller;
	}

	// The open try's view, in memory. A History change seen by its file's time starts a new derive (at most once a second).
	public TryItView view() {
		refreshIfStale();
		return view;
	}

	public @Nullable Text refusal(Recommendation rec) {
		return refusal(rec, null);
	}

	private @Nullable Text refusal(Recommendation rec, BenchmarkRequest.@Nullable Scene scene) {
		Minecraft minecraft = controller.minecraft();
		if (minecraft == null) {
			return TryItView.UNAVAILABLE;
		}
		Text busy = Busy.refusal(controller);
		Triable.Result result = Triable.check(rec, context(minecraft, busy != null), scene);
		if (result.refusal() == null) {
			return null;
		}
		return switch (result.refusal()) {
			case BUSY -> busy;
			case SCENE -> TryItText.sceneRefusal(Objects.requireNonNullElse(BenchmarkController.unavailable(minecraft, result.scene()), ""));
			default -> TryItText.refusal(result.refusal(), openLabel());
		};
	}

	private Triable.Context context(Minecraft minecraft, boolean busy) {
		List<ConfigTargets.Target> targets = ConfigTargets.all(controller.configDir());
		Journal.State journal = journalState;
		return new Triable.Context(key -> {
			ConfigTargets.Target target = ConfigTargets.forKey(targets, key);
			return target != null && Files.isRegularFile(target.file());
		}, minecraft.level != null, minecraft.level != null && !minecraft.hasSingleplayerServer(), controller.hasPendingChanges(), busy,
				scene -> BenchmarkController.unavailable(minecraft, scene) != null, view.tryIt() != null,
				journal == null || journal == Journal.State.OK || journal == Journal.State.MISSING, !BenchmarkStore.history().unreadable(), storeWritable);
	}

	private @Nullable String openLabel() {
		TryIt open = view.tryIt();
		return open == null ? null : controller.settingLabels().label(open.key());
	}

	// TryItScreen's Start (render thread): the try's identity is written, then the before run starts on a later tick.
	public Component start(Recommendation rec, BenchmarkRequest.Scene scene) {
		Text refused = refusal(rec, scene);
		if (refused != null) {
			return Texts.component(refused);
		}
		Minecraft minecraft = controller.minecraft();
		Action.SetSetting set = (Action.SetSetting) rec.action();
		Triable.Result result = Triable.check(rec, context(minecraft, false), scene);
		TryIt t = TryIt.of(ChangeRecorder.newEntryId(), rec.id(), set.key(), set.currentValue(), set.newValue(), result.kind(), result.scene(), now(),
				SESSION, controller.modVersion(), HardwareProbe.minecraftVersion(), snapshot(minecraft),
				result.scene() == BenchmarkRequest.Scene.CURRENT ? spot(minecraft) : null);
		this.rec = rec;
		closedHere = false;
		afterRun = false;
		measuring = true;
		view = new TryItView(Stage.MEASURING_BEFORE, t, null, null, null, null, null, true);
		hook();
		io(() -> {
			if (!TryItStore.shared(controller.configDir()).open(t)) {
				RigTune.LOGGER.warn("Try it: could not record the try in {}; not starting it", TryItStore.FILE_NAME);
				minecraft.execute(() -> {
					measuring = false;
					this.rec = null;
					view = TryItView.EMPTY;
				});
				deriveNow();
				return;
			}
			minecraft.execute(() -> queue(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, t.scene(), t.pairId())));
		});
		return Component.empty();
	}

	// Measure now / Measure again (READY: the change is applied and journaled): the after snapshot is written, then the
	// run starts on a later tick.
	public void measureNow() {
		TryItView v = view;
		TryIt t = v.tryIt();
		Minecraft minecraft = controller.minecraft();
		if (t == null || v.stage() != Stage.READY || minecraft == null || Busy.refusal(controller) != null
				|| BenchmarkController.unavailable(minecraft, t.scene()) != null) {
			return;
		}
		hook();
		queueAfter(minecraft, t, v);
	}

	// Keep: the try is closed as kept; nothing but tryit.json is written.
	public Component keep() {
		TryItView v = view;
		TryIt t = v.tryIt();
		if (t == null || !v.actions().contains(TryItView.Action.KEEP)) {
			return Component.translatable("rigtune.status.nothing");
		}
		view = TryItView.EMPTY;
		closedHere = false;
		io(() -> {
			close(t, TryIt.Decision.KEPT, v.verdict());
			deriveNow();
		});
		return Texts.component(TryItText.kept(t, controller.settingLabels()));
	}

	// Done on a try that has ended: it's closed with its stage's decision (at once if this session closed it already).
	public void cancel() {
		TryItView v = view;
		TryIt t = v.tryIt();
		TryIt.Decision decision = v.closing();
		if (t == null || decision == null) {
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

	// The start hook (V05Services.afterStart), on Probes.EXECUTOR.
	public void derive() {
		deriveNow();
	}

	// The title-screen hook (V05Services.titleScreen), on the render thread: once per launch, and only for a try that a
	// restart left READY, RETRYING or NOT_APPLIED, whatever the startupToast switch says (it continues the player's action).
	public void titleToast(Minecraft minecraft) {
		if (toastShown) {
			return;
		}
		if (!derived) {
			toastWanted = true;
			return;
		}
		showToast(minecraft);
	}

	private void showToast(Minecraft minecraft) {
		TryItView v = view;
		Text body = v.tryIt() == null || v.sameSession() ? null : TryItText.toastBody(v.stage());
		if (toastShown || body == null) {
			return;
		}
		toastShown = true;
		SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Texts.component(TryItText.toastTitle()), Texts.component(body));
	}

	// Executor: derive from the three files, and remember what the refusals need.
	private TryItView deriveNow() {
		try {
			Path configDir = controller.configDir();
			TryItStore store = TryItStore.shared(configDir);
			TryIt t = store.current();
			storeWritable = store.writable();
			Journal journal = ClientJournal.get();
			historyStamp = stamp(Journal.file(configDir));
			Journal.State state = journal.state();
			journalState = state;
			List<JournalEntry> entries = state == Journal.State.OK ? journal.entries() : List.of();
			TryItView derivedView = TryItFlow.derive(t, BenchmarkStore.history().runs(), new TryItFlow.History(state, entries, failures(configDir)),
					new TryItFlow.Live(SESSION, measuring, applying || applyDue));
			if (!(closedHere && derivedView.tryIt() == null)) {
				closedHere = false;
				view = derivedView;
			}
			acknowledge(t, derivedView);
			derived = true;
			Minecraft minecraft = controller.minecraft();
			if (toastWanted && minecraft != null) {
				toastWanted = false;
				minecraft.execute(() -> showToast(minecraft));
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
		controller.trendService().acknowledge(after.id());
		TryItStore.shared(controller.configDir()).change(t.id(), x -> x.withAfterRun(after.id()));
	}

	private static Map<String, ApplyFailures.Failure> failures(Path configDir) {
		Path last = ApplyResult.defaultPath(configDir);
		try {
			ApplyResult result = Files.isRegularFile(last) ? ApplyResult.load(last) : null;
			return ApplyFailures.byOpId(result, List.of(FabricLoader.getInstance().getGameDir().resolve("mods"), configDir));
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Try it: could not read {}", last.getFileName(), e);
			return Map.of();
		}
	}

	// Render thread: history.json's time changed (an Undo this, a Discard, another Apply): derive again, at most once a
	// second, only while a try is open and between runs (X8: a stat here, no content read).
	private void refreshIfStale() {
		if (controller == null || view.tryIt() == null || running()) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastStaleCheck < REFRESH_NANOS) {
			return;
		}
		lastStaleCheck = now;
		if (!Objects.equals(stamp(Journal.file(controller.configDir())), historyStamp)) {
			historyStamp = null;
			io(this::deriveNow);
		}
	}

	// -- the chain (render thread unless said otherwise)

	private void queue(BenchmarkRequest request) {
		measuring = true;
		next = request;
		waitTicks = 0;
		active = true;
	}

	private void queueAfter(Minecraft minecraft, TryIt t, TryItView v) {
		Map<String, String> settings = snapshot(minecraft);
		TryIt.Spot spot = t.scene() == BenchmarkRequest.Scene.CURRENT ? spot(minecraft) : null;
		measuring = true;
		afterRun = true;
		view = new TryItView(Stage.MEASURING_AFTER, t, v.before(), null, null, v.changeStatus(), null, v.sameSession());
		active = true;
		io(() -> {
			TryItStore.shared(controller.configDir()).change(t.id(), x -> x.withAfter(settings, SESSION, spot));
			minecraft.execute(() -> queue(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, t.scene(), t.pairId())));
		});
	}

	// BenchmarkController's outcome hook: only this try's runs (its pair id) are claimed.
	private static boolean claim(BenchmarkController.Outcome outcome) {
		TryItService service = ticking;
		return service != null && service.onOutcome(outcome);
	}

	private boolean onOutcome(BenchmarkController.Outcome outcome) {
		TryItView v = view;
		TryIt t = v.tryIt();
		if (t == null || !t.pairId().equals(outcome.request().pairId())) {
			return false;
		}
		Minecraft minecraft = controller.minecraft();
		measuring = false;
		BenchmarkRecord record = outcome.record();
		if (!afterRun) {
			if (record != null && BenchmarkRecord.BEFORE.equals(record.phase()) && record.result() != null) {
				// The before is saved: apply on the next tick, then measure again.
				applyDue = true;
				view = new TryItView(Stage.APPLYING, t, record, null, null, null, null, true);
				active = true;
				if (minecraft.player != null) {
					minecraft.player.sendOverlayMessage(Texts.component(TryItText.overlay()));
				}
				return true;
			}
			// Stopped (or measured nothing) before anything changed.
			closeHere(t, TryIt.Decision.CANCELLED, new TryItView(Stage.STOPPED_BEFORE, t, record, null, null, null, null, true));
			openScreen(minecraft);
			return true;
		}
		io(() -> {
			deriveNow();
			minecraft.execute(() -> openScreen(minecraft));
		});
		return true;
	}

	private void apply(Minecraft minecraft) {
		applyDue = false;
		applying = true;
		TryIt t = view.tryIt();
		Recommendation applied = rec;
		if (t != null && applied != null) {
			controller.apply(List.of(applied), t.entryId());
		}
		io(() -> {
			TryItView after = deriveNow();
			applying = false;
			minecraft.execute(() -> afterApply(minecraft, t, after));
		});
	}

	private void afterApply(Minecraft minecraft, @Nullable TryIt t, TryItView v) {
		rec = null;
		if (t == null) {
			return;
		}
		if (v.changeStatus() == null) {
			// Nothing was journaled for the key: the apply took nothing.
			closeHere(t, TryIt.Decision.FAILED, new TryItView(Stage.NOT_APPLIED, t, v.before(), null, null, null, null, true));
			openScreen(minecraft);
			return;
		}
		if (t.kind() == TryIt.Kind.NOW && JournalChange.APPLIED.equals(v.changeStatus()) && v.stage() == Stage.READY) {
			queueAfter(minecraft, t, v);
			return;
		}
		openScreen(minecraft);
	}

	private void closeHere(TryIt t, TryIt.Decision decision, TryItView shown) {
		rec = null;
		view = shown;
		closedHere = true;
		io(() -> close(t, decision, null));
	}

	private void close(TryIt t, TryIt.Decision decision, TryItVerdict.@Nullable Verdict verdict) {
		String kind = verdict == null ? null : verdict.kind().name().toLowerCase(Locale.ROOT);
		if (!TryItStore.shared(controller.configDir()).close(t.id(), TryIt.Closed.of(t, decision, kind, verdict == null ? null : verdict.lowPercent(),
				verdict == null ? null : verdict.avgPercent(), verdict == null ? null : verdict.floorPercent(), now()))) {
			RigTune.LOGGER.warn("Try it: could not record the closed try in {}", TryItStore.FILE_NAME);
		}
	}

	private void openScreen(Minecraft minecraft) {
		minecraft.gui.setScreen(new TryItScreen(minecraft.gui.screen(), controller, null));
	}

	// The chain can't go on (a run that couldn't start, or ended without an outcome): before the apply the try stops as
	// cancelled; afterwards it's derived again (READY: Measure again).
	private void lost(Minecraft minecraft) {
		measuring = false;
		next = null;
		TryIt t = view.tryIt();
		if (t != null && !afterRun) {
			closeHere(t, TryIt.Decision.CANCELLED, new TryItView(Stage.STOPPED_BEFORE, t, null, null, null, null, null, true));
			openScreen(minecraft);
			return;
		}
		io(() -> {
			deriveNow();
			minecraft.execute(() -> openScreen(minecraft));
		});
	}

	// The listener, the outcome hook and Busy's hook, once, when a run is first queued (never at init, X4).
	private void hook() {
		ticking = this;
		if (!registered) {
			registered = true;
			ClientTickEvents.END_CLIENT_TICK.register(TryItService::tick);
			BenchmarkController.setOutcomeHandler(TryItService::claim);
			Busy.tryItRunning = TryItService::chainRunning;
		}
	}

	private boolean running() {
		return next != null || measuring || applyDue || applying;
	}

	private static boolean chainRunning() {
		TryItService service = ticking;
		return service != null && service.running();
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
		try {
			service.onTick(minecraft);
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Try it: the chain failed; stopping it", e);
			service.lost(minecraft);
		}
	}

	private void onTick(Minecraft minecraft) {
		if (applyDue) {
			apply(minecraft);
			return;
		}
		BenchmarkRequest queued = next;
		if (queued != null) {
			String unavailable = BenchmarkController.unavailable(minecraft, queued.scene());
			if (unavailable == null) {
				next = null;
				waitTicks = 0;
				String refused = BenchmarkController.tryStart(minecraft, queued, BenchmarkController.defaultConfig());
				if (refused != null) {
					RigTune.LOGGER.warn("Try it: the run couldn't start ({})", refused);
					lost(minecraft);
				}
			} else if (++waitTicks > WAIT_TICKS) {
				RigTune.LOGGER.warn("Try it: the run couldn't start in {} s ({})", WAIT_TICKS / 20, unavailable);
				lost(minecraft);
			}
			return;
		}
		if (measuring) {
			if (BenchmarkController.running() || BenchmarkWorld.busy()) {
				waitTicks = 0;
			} else if (++waitTicks > WAIT_TICKS) {
				RigTune.LOGGER.warn("Try it: the run ended without an outcome");
				lost(minecraft);
			}
			return;
		}
		if (!applying) {
			active = false;
		}
	}

	private void io(Runnable task) {
		synchronized (this) {
			io = io.thenRunAsync(() -> {
				try {
					task.run();
				} catch (RuntimeException e) {
					RigTune.LOGGER.error("Try it: a step failed", e);
				}
			}, Probes.EXECUTOR);
		}
	}

	private static Map<String, String> snapshot(Minecraft minecraft) {
		return SettingsBridge.read(minecraft).values();
	}

	// Where the player stands (docs/v0.5/SPEC.md 6, the coordinator's decision): the block, the dimension and this world's
	// or server's key. Never used to move the player.
	private static TryIt.@Nullable Spot spot(Minecraft minecraft) {
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
}
