package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.awareness.OutsideChanges;
import io.github.chaotix345.rigtune.client.launcher.LauncherRepairService;
import io.github.chaotix345.rigtune.client.launcher.ModFilesService;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.server.ServerProfileService;
import io.github.chaotix345.rigtune.client.stutter.StutterFixService;
import io.github.chaotix345.rigtune.client.tryit.TryItService;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

// docs/v0.5/SPEC.md X4 and C4 (PLAN contracts items 2 and 13d-f): the v0.5 feature services behind one lazy holder, made
// on the first RealController.v05() call (never in its constructor) and each service on its first use, so none of them is
// constructed or loaded on the render thread during preLaunch, onInitializeClient or the CLIENT_STARTED handler. Every
// resolution tells FootprintStats, which flags one made on the render thread inside those windows (FootprintGameTest);
// a worker resolving it meanwhile is allowed. Each service's constructor only stores the controller.
// The statics are the hooks the startup code calls: registerEvents (onInitializeClient: registrations only), afterStart
// (the end of RealController.start: one Probes.EXECUTOR task) and titleScreen (RigTuneClient.showNotices, once). This
// class is frozen after the contracts commit: a new entry goes through the coordinator.
public final class V05Services {
	// Before Fabric's default phase, so the exit snapshot sees the session as it was, before RigTune's own exit work
	// (a benchmark cancelled, the helper started).
	private static final Identifier BEFORE_EXIT = Identifier.fromNamespaceAndPath(RigTune.MOD_ID, "v05-before-exit");

	private final RealController controller;
	private final String createdOn;
	private @Nullable ModFilesService modFiles;
	private @Nullable LauncherRepairService launcherRepair;
	private @Nullable FirstRunService firstRun;
	private @Nullable TryItService tryIt;
	private @Nullable ServerProfileService serverProfiles;
	private @Nullable StutterFixService stutterFixes;

	V05Services(RealController controller) {
		this.controller = controller;
		this.createdOn = Thread.currentThread().getName();
		FootprintStats.lazyResolved("the v0.5 services");
	}

	// The thread that made the holder (for the footprint logs).
	public String createdOn() {
		return createdOn;
	}

	// P0.4 (WS-L1).
	public synchronized ModFilesService modFiles() {
		FootprintStats.lazyResolved("ModFilesService");
		if (modFiles == null) {
			modFiles = new ModFilesService(controller);
		}
		return modFiles;
	}

	// P0.4 (WS-L2).
	public synchronized LauncherRepairService launcherRepair() {
		FootprintStats.lazyResolved("LauncherRepairService");
		if (launcherRepair == null) {
			launcherRepair = new LauncherRepairService(controller);
		}
		return launcherRepair;
	}

	// C02 (WS-F).
	public synchronized FirstRunService firstRun() {
		FootprintStats.lazyResolved("FirstRunService");
		if (firstRun == null) {
			firstRun = new FirstRunService(controller);
		}
		return firstRun;
	}

	// C09 (WS-T).
	public synchronized TryItService tryIt() {
		FootprintStats.lazyResolved("TryItService");
		if (tryIt == null) {
			tryIt = new TryItService(controller);
		}
		return tryIt;
	}

	// C16 (WS-P2).
	public synchronized ServerProfileService serverProfiles() {
		FootprintStats.lazyResolved("ServerProfileService");
		if (serverProfiles == null) {
			serverProfiles = new ServerProfileService(controller);
		}
		return serverProfiles;
	}

	// C20 (WS-S2).
	public synchronized StutterFixService stutterFixes() {
		FootprintStats.lazyResolved("StutterFixService");
		if (stutterFixes == null) {
			stutterFixes = new StutterFixService(controller);
		}
		return stutterFixes;
	}

	// One line in onInitializeClient, after the v0.4 registrations (JOIN after ServerLimitsTracker's). One lambda each; a
	// lambda resolves its service when its event first fires (X4.2).
	public static void registerEvents(RealController controller) {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, minecraft) -> controller.v05().serverProfiles().onJoin(listener, minecraft));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> controller.v05().serverProfiles().onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.addPhaseOrdering(BEFORE_EXIT, Event.DEFAULT_PHASE);
		ClientLifecycleEvents.CLIENT_STOPPING.register(BEFORE_EXIT, minecraft -> OutsideChanges.snapshotAtStop(controller, minecraft));
	}

	// The end of RealController.start (inside the CLIENT_STARTED handler): one short task on Probes.EXECUTOR, which
	// resolves the holder there. Each step runs even if an earlier one threw.
	public static void afterStart(RealController controller) {
		CompletableFuture.runAsync(() -> {
			V05Services services = controller.v05();
			step("FirstRunService.load", () -> services.firstRun().load());
			step("TryItService.derive", () -> services.tryIt().derive());
			step("OutsideChanges.compareAtStart", () -> OutsideChanges.compareAtStart(controller));
		}, Probes.EXECUTOR);
	}

	// Once per launch, from RigTuneClient.showNotices at the first title screen (render thread).
	public static void titleScreen(Minecraft minecraft, RigTuneController controller) {
		if (controller instanceof RealController real) {
			step("TryItService.titleToast", () -> real.v05().tryIt().titleToast(minecraft));
		}
	}

	private static void step(String what, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("RigTune: {} failed", what, e);
		}
	}
}
