package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 4a-4b, 4e (P0.4): the instance's mod-files policy and the MOD_FILES_NEWS notice. Reached only through
// RealController.v05() (X4); nothing happens in the constructor. The policy is read live from LauncherProbe's answers
// (the detection, the .index/ listing: both in memory once in) and settings.json's opt-in: no I/O, any thread.
public final class ModFilesService {
	private final @Nullable RealController controller;
	private final Supplier<@Nullable LauncherInfo> launcher;
	private final Supplier<@Nullable InstanceEvidence> evidence;
	private final BooleanSupplier optIn;

	public ModFilesService(RealController controller) {
		this(controller, LauncherProbe::answer, LauncherProbe::evidence, () -> controller.settings().modFilesByRigTune);
	}

	// The policy's inputs: the detection's answer and the listing's (null until in), and the opt-in.
	ModFilesService(@Nullable RealController controller, Supplier<@Nullable LauncherInfo> launcher, Supplier<@Nullable InstanceEvidence> evidence,
			BooleanSupplier optIn) {
		this.controller = controller;
		this.launcher = launcher;
		this.evidence = evidence;
		this.optIn = optIn;
	}

	// Render thread, no I/O (screens and RealController.modFiles()); also the rebuild's worker (the report post-step).
	public ModFilesPolicy policy() {
		return ModFilesPolicy.of(launcher.get(), evidence.get(), optIn.getAsBoolean());
	}

	// What the instance would be without the opt-in: whether a launcher keeps its own record (the Settings row, the
	// opted-in header line).
	public ModFilesPolicy withoutOptIn() {
		return ModFilesPolicy.of(launcher.get(), evidence.get(), false);
	}

	public boolean optedIn() {
		return optIn.getAsBoolean();
	}
}
