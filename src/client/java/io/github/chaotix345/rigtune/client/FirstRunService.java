package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.launcher.ModFilesService;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

// docs/v0.5/SPEC.md 8 (C02): whether the player is new (in memory only; nothing persisted, settings.json untouched).
// Reached only through RealController.v05() (X4); nothing happens in the constructor. UNKNOWN until load() (the start
// hook's step, on Probes.EXECUTOR) has read the disk (FirstRun.isNew); every Apply by any path (the after-apply hook, last)
// makes RETURNING, and a load that finishes after it never turns it back (compareAndSet). A read that fails counts as
// returning, so neither the guide nor the confirmation shows by mistake. Render-thread reads are volatile, no I/O.
// v0.5 WS-L1 (review-11 FEAT-1): loadedStatus() keeps what load() read, the status MOD_FILES_NEWS goes by (an Apply makes
// a new player RETURNING, never a 0.4 upgrader); a NEW read also stores the news' key in awareness.json's dismissals, so
// later launches, which read RETURNING from the records this one leaves, don't show it either.
public final class FirstRunService {
	private final RealController controller;
	private final AtomicReference<FirstRun.Status> status = new AtomicReference<>(FirstRun.Status.UNKNOWN);
	private volatile FirstRun.Status loaded = FirstRun.Status.UNKNOWN;
	private volatile @Nullable String loadedOn;
	private final Consumer<String> keepDismissed;

	public FirstRunService(RealController controller) {
		this(controller, key -> {
			if (controller != null) {
				keepDismissed(controller.configDir(), key);
			}
		});
	}

	// Tests: where a NEW read stores the news' dismissal.
	FirstRunService(RealController controller, Consumer<String> keepDismissed) {
		this.controller = controller;
		this.keepDismissed = keepDismissed;
	}

	public FirstRun.Status status() {
		return status.get();
	}

	// RigTuneController.firstApplyPending(): the Apply button opens the confirmation afterwards.
	public boolean firstApplyPending() {
		return status.get() == FirstRun.Status.NEW;
	}

	// The start hook (V05Services.afterStart), on Probes.EXECUTOR.
	public void load() {
		load(() -> {
			Path configDir = controller.configDir();
			return FirstRun.isNew(ClientJournal.get(), configDir);
		});
	}

	void load(BooleanSupplier isNew) {
		loadedOn = Thread.currentThread().getName();
		FirstRun.Status read;
		try {
			read = isNew.getAsBoolean() ? FirstRun.Status.NEW : FirstRun.Status.RETURNING;
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: couldn't tell whether anything was applied before; the first-Apply guide stays off", e);
			read = FirstRun.Status.RETURNING;
		}
		if (status.compareAndSet(FirstRun.Status.UNKNOWN, read)) {
			loaded = read;
			if (read == FirstRun.Status.NEW) {
				keepDismissed.accept(ModFilesService.NEWS_KEY);
			}
		}
	}

	// What load() read (UNKNOWN before it, and when an Apply came first): MOD_FILES_NEWS shows for RETURNING only.
	public FirstRun.Status loadedStatus() {
		return loaded;
	}

	// awareness.json's dismissals, as the notice's own × stores them (on load()'s worker).
	static void keepDismissed(Path configDir, String key) {
		try {
			AwarenessStore.shared(configDir).dismiss(key);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: couldn't store the dismissal of {}", key, e);
		}
	}

	// The after-apply hook (V05Hooks.afterApply), last: every Apply, by any path, retires NEW.
	public void applied(V05Hooks.ApplyFacts facts) {
		status.set(FirstRun.Status.RETURNING);
	}

	// The thread load() ran on, or null before it ran (AC8.15: FirstApplyGameTest checks it wasn't the render thread).
	public @Nullable String loadedOn() {
		return loadedOn;
	}

	// Test seam (SPEC C6): FirstApplyGameTest restores a new player through it when it isn't the first class in its JVM.
	// The forced status is also what load() read (loadedStatus()); nothing is stored.
	public void forceStatusForTests(FirstRun.Status forced) {
		status.set(forced);
		loaded = forced;
	}
}
