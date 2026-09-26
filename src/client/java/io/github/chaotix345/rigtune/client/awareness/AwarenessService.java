package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.notice.NoticeCenter;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.awareness.ChangeDetector;
import io.github.chaotix345.rigtune.core.awareness.Fingerprint;
import io.github.chaotix345.rigtune.core.awareness.WhatsNew;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

// Change awareness (docs/v0.4/SPEC.md 9, plan review W-L3, X-M1): the hardware fingerprint, the what's-new baseline and
// the notice dismissals, all in awareness.json through the one process-wide AwarenessStore. It is the notice slot's
// Dismissals store (C3).
// Hardware: after every probe the fingerprint is compared with the stored one (the first run seeds silently). A change
// stays a notice for the session; the fingerprint is committed once the notice was shown (the notice line's current
// notice, top or cycled to, or listed on NoticeScreen) or dismissed, so a notice hidden behind others is never lost.
// What's new: evaluated when RigTuneScreen asks for notices, once per report (WhatsNew); dismissing moves the baseline on.
public final class AwarenessService implements NoticeCenter.Dismissals {
	public static final String HARDWARE_KEY_PREFIX = "hardware-changed:";
	public static final String WHATS_NEW_KEY_PREFIX = "whats-new:";
	public static final String RESCAN = "rescan";
	public static final String REBENCHMARK = "rebenchmark";
	// Names the what's-new detail lists before "…".
	private static final int MAX_NAMES = 8;

	private record Pending(ChangeDetector.Change change, Fingerprint now, String key) {
	}

	private record Fresh(int revision, Set<String> potential, List<Recommendation> fresh, String key) {
	}

	private final RealController controller;
	private final Path configDir;
	private final AwarenessStore store;
	// Dismissals of this session, so × still works while awareness.json is from a newer RigTune or unreadable.
	private final NoticeCenter.Dismissals session = NoticeCenter.inMemory();
	private volatile @Nullable Pending hardware;
	private volatile boolean hardwareCommitted;
	private volatile @Nullable Fresh whatsNew;
	// Render thread only: the report the what's-new check last ran for.
	private @Nullable Report checkedReport;

	public AwarenessService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
		this.store = AwarenessStore.shared(configDir);
	}

	// Once, from RigTuneClient: the W-L3 "shown" signal. A per-screen tick listener (it goes with the screen) compares one
	// key; nothing is allocated per tick.
	public void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof RigTuneScreen rigtune) {
				ScreenEvents.afterTick(screen).register(s -> shown(rigtune.shownNotice()));
			} else if (screen instanceof NoticeScreen notices) {
				notices.shown().forEach(this::shown);
			}
		});
	}

	// After every hardware probe (RealController.rescan, off the render thread).
	public void afterProbe(@Nullable HardwareProfile hw) {
		if (hw == null) {
			return;
		}
		try {
			Fingerprint now = Fingerprint.of(hw);
			ChangeDetector.Change change = ChangeDetector.check(store, now);
			hardware = change.changed() ? new Pending(change, now, HARDWARE_KEY_PREFIX + now.id()) : null;
			hardwareCommitted = false;
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not compare the hardware with {}", AwarenessStore.FILE_NAME, e);
		}
	}

	public @Nullable Notice hardwareNotice() {
		Pending p = hardware;
		if (p == null) {
			return null;
		}
		Text message = switch (p.change().kind()) {
			case GPU -> Text.of("rigtune.awareness.gpu_changed", "Your GPU changed since last time");
			case DRIVER -> Text.of("rigtune.awareness.driver_changed", "Your GPU driver changed since last time (%s → %s)",
					Text.literal(p.change().from()), Text.literal(p.change().to()));
			case HARDWARE -> Text.of("rigtune.awareness.hardware_changed", "Your hardware changed since last time");
			case NONE -> null;
		};
		if (message == null) {
			return null;
		}
		return new Notice(p.key(), NoticePriority.HARDWARE_CHANGED, message,
				Text.of("rigtune.awareness.hardware_changed.detail", "Re-scan to refresh the recommendations, or re-benchmark to measure again."),
				List.of(new NoticeAction(RESCAN, Text.of("rigtune.awareness.rescan", "Re-scan")),
						new NoticeAction(REBENCHMARK, Text.of("rigtune.awareness.rebenchmark", "Re-benchmark"))), true);
	}

	public void hardwareAction(String actionId) {
		Minecraft minecraft = controller.minecraft();
		if (minecraft == null) {
			return;
		}
		if (RESCAN.equals(actionId)) {
			controller.rescan();
		} else if (REBENCHMARK.equals(actionId)) {
			minecraft.gui.setScreen(new BenchmarkMenuScreen(minecraft.gui.screen(), controller));
		}
	}

	// Render thread, on screen init/rebuild.
	public @Nullable Notice whatsNewNotice() {
		Report report = controller.report();
		RulesDocument rules = controller.rules();
		if (report == null || rules == null) {
			return null;
		}
		if (report != checkedReport) {
			checkedReport = report;
			WhatsNew.Result result = WhatsNew.check(store, report, rules);
			whatsNew = result.kind() == WhatsNew.Kind.NEW
					? new Fresh(rules.revision, WhatsNew.potentialIds(rules), result.fresh(), WHATS_NEW_KEY_PREFIX + rules.revision) : null;
		}
		Fresh w = whatsNew;
		if (w == null) {
			return null;
		}
		List<Text> names = new ArrayList<>();
		for (Recommendation r : w.fresh()) {
			names.add(r.action() instanceof Action.AddMod add && add.title() != null ? Text.literal(add.title()) : r.titleText());
		}
		int count = names.size();
		Text message = count == 1
				? Text.of("rigtune.awareness.whats_new.one", "1 new recommendation for you since you last looked: %s", names.getFirst())
				: Text.of("rigtune.awareness.whats_new.many", "%s new recommendations for you since you last looked: %s, +%s more", count,
						names.getFirst(), count - 1);
		List<Text> listed = new ArrayList<>(names.subList(0, Math.min(MAX_NAMES, count)));
		if (count > MAX_NAMES) {
			listed.add(Text.literal("…"));
		}
		return new Notice(w.key(), NoticePriority.WHATS_NEW, message,
				Text.of("rigtune.awareness.whats_new.detail", "New in rules revision %s: %s", w.revision(), Text.join(", ", listed)), List.of(), true);
	}

	// W-L3: the hardware notice was the notice line's current notice or listed on NoticeScreen.
	void shown(@Nullable Notice notice) {
		Pending p = hardware;
		if (notice == null || p == null || hardwareCommitted || !notice.key().equals(p.key())) {
			return;
		}
		hardwareCommitted = true;
		CompletableFuture.runAsync(() -> ChangeDetector.commit(store, p.now()), Probes.EXECUTOR).exceptionally(e -> {
			RigTune.LOGGER.warn("Could not update {}", AwarenessStore.FILE_NAME, e);
			return null;
		});
	}

	// For the game tests: awareness.json.
	public Path file() {
		return AwarenessStore.file(configDir);
	}

	@Override
	public Set<String> dismissed() {
		Set<String> keys = new HashSet<>(store.dismissed());
		keys.addAll(session.dismissed());
		return keys;
	}

	@Override
	public void dismiss(String key) {
		session.dismiss(key);
		store.dismiss(key);
		Pending p = hardware;
		if (p != null && key.equals(p.key())) {
			hardwareCommitted = true;
			ChangeDetector.commit(store, p.now());
		}
		Fresh w = whatsNew;
		if (w != null && key.equals(w.key())) {
			WhatsNew.acknowledge(store, w.revision(), w.potential());
			whatsNew = null;
		}
	}
}
