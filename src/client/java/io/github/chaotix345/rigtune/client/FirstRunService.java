package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

// docs/v0.5/SPEC.md 8 (C02): whether the player is new (in memory only; nothing persisted, settings.json untouched).
// Reached only through RealController.v05() (X4); nothing happens in the constructor. UNKNOWN until load() (the start
// hook's step, on Probes.EXECUTOR) has read the disk (FirstRun.isNew); every Apply by any path (the after-apply hook, last)
// makes RETURNING, and a load that finishes after it never turns it back (compareAndSet). A read that fails counts as
// returning, so neither the guide nor the confirmation shows by mistake. Render-thread reads are volatile, no I/O.
public final class FirstRunService {
	private final RealController controller;
	private final AtomicReference<FirstRun.Status> status = new AtomicReference<>(FirstRun.Status.UNKNOWN);
	private volatile @Nullable String loadedOn;

	public FirstRunService(RealController controller) {
		this.controller = controller;
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
		status.compareAndSet(FirstRun.Status.UNKNOWN, read);
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
	public void forceStatusForTests(FirstRun.Status forced) {
		status.set(forced);
	}
}
