package io.github.chaotix345.rigtune.client.server;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.profile.ProfileService;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers.Connection;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers.Offer;
import io.github.chaotix345.rigtune.core.profile.ServerProfilePrompt;
import io.github.chaotix345.rigtune.core.profile.ServerProfilePrompt.Reason;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

// docs/v0.5/SPEC.md 7 (C16), sp §2.1: per-server profile offers. Reached only through RealController.v05() (X4); the
// constructor does no work. JOIN and DISCONNECT arrive through V05Services.registerEvents (after ServerLimitsTracker's),
// on the render thread: JOIN reads the connection's kind and address and submits one Probes.EXECUTOR task (the store
// lookup, its lastSeen write, the decision). RigTune never switches by itself: an offer is a toast (once per server per
// game session, shown once the world is visible, so a slow join doesn't use up its 8 s behind the loading screen) and the
// SERVER_PROFILE notice, re-decided whenever a screen asks for it (nothing is read while no offer is pending; while one is,
// one read of each file). Switch is ProfileService.switchProfile (one journal entry, the shared busy check, the clamps);
// Don't offer here and the Servers screen's Stop/Forget remove the entry. No address is logged, stored or shown.
public final class ServerProfileService {
	// 8 s, so the toast stays readable a while after the world shows (checked for real in AC7.16).
	public static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(8000L);
	// Whether a benchmark runs (BenchmarkController.running); ServerProfilesGameTest stands one in.
	private static volatile BooleanSupplier benchmarkRunning = BenchmarkController::running;

	private final RealController controller;
	private final ServerProfileOffers offers = new ServerProfileOffers();
	// The offer whose toast waits for the world to show (set and read on the render thread; the tick reads it).
	private volatile @Nullable WaitingToast waiting;
	private boolean tickRegistered;
	// The last toast's text, for ServerProfilesGameTest (a toast may be gone by the time a test looks).
	private volatile @Nullable Component lastToast;

	private record WaitingToast(Offer offer, Text name) {
	}

	public ServerProfileService(RealController controller) {
		this.controller = controller;
	}

	// Render thread.
	public void onJoin(ClientPacketListener listener, Minecraft minecraft) {
		ServerData data = listener.getServerData();
		ServerLimits.Kind kind = ServerLimitsTracker.kind(minecraft, data);
		Connection connection = offers.joined(kind, ServerLimitsTracker.address(kind, data), System.currentTimeMillis());
		if (connection == null || !connection.offerable()) {
			return;
		}
		CompletableFuture.runAsync(() -> lookup(connection), Probes.EXECUTOR).exceptionally(e -> {
			RigTune.LOGGER.warn("Could not look up the profile set for this server", e);
			return null;
		});
	}

	public void onDisconnect() {
		offers.disconnected();
		waiting = null;
	}

	// Probes.EXECUTOR. An offer held for a benchmark or for battery power is kept (no toast): its notice shows once that
	// ends, while the player is still on this server.
	private void lookup(Connection connection) {
		ServerProfileStore.Entry entry = store().joined(connection.address(), Instant.now());
		if (entry == null || offers.current() != connection) {
			return;
		}
		ProfileService.Names names = controller.profileService().names();
		Text name = names.name(entry.profile());
		Reason reason = decide(connection, entry.profile(), name, names);
		RigTune.LOGGER.info("RigTune: this server has a profile set ({}): {}", entry.profile(), reason);
		if (!ServerProfileOffers.kept(reason)) {
			return;
		}
		Offer offer = offers.offer(connection, entry.key(), entry.profile());
		Minecraft minecraft = controller.minecraft();
		if (offer != null && reason == Reason.OFFER && minecraft != null) {
			minecraft.execute(() -> waitForTheWorld(offer, name));
		}
	}

	// Render thread: the toast waits for the first tick with the world shown and no screen over it. Its own
	// END_CLIENT_TICK listener, registered on the first offer (X4.4), reads one field unless a toast waits.
	private void waitForTheWorld(Offer offer, Text name) {
		waiting = new WaitingToast(offer, name);
		if (!tickRegistered) {
			tickRegistered = true;
			ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		}
	}

	private void tick(Minecraft minecraft) {
		WaitingToast toast = waiting;
		if (toast == null || minecraft.level == null || minecraft.player == null || minecraft.gui.screen() != null) {
			return;
		}
		waiting = null;
		if (offers.toast(toast.offer())) {
			// "Profile for this server" / "Max FPS is set for this server. Press F8 to switch."
			show(minecraft, ServerProfilePrompt.toastBody(toast.name(), key()));
		}
	}

	// ServerProfileNoticeSource (render thread, on screen init). While the offer is held it shows nothing and stays; once
	// its profile is active or gone, or its server was forgotten or set to another profile meanwhile, it's retired.
	public @Nullable Notice notice() {
		Offer offer = offers.pending();
		if (offer == null) {
			return null;
		}
		ServerProfileStore.Entry entry = store().entry(offer.key());
		ProfileService.Names names = controller.profileService().names();
		Text name = names.name(offer.profile());
		boolean stillSet = entry != null && entry.profile().equals(offer.profile());
		return offers.settle(offer, decide(offer.connection(), offer.profile(), name, names), stillSet)
				? ServerProfilePrompt.notice(offer.connection().joinedAtMillis(), name) : null;
	}

	// The notice's Switch and Don't offer here; the result is the toast.
	public void act(String actionId) {
		Offer offer = offers.pending();
		Minecraft minecraft = controller.minecraft();
		if (offer == null || minecraft == null) {
			return;
		}
		if (ServerProfilePrompt.ACTION_SWITCH.equals(actionId)) {
			// A refusal (a benchmark, downloads, not ready) keeps the offer; it goes once the profile is active.
			Component result = controller.profileService().switchProfile(offer.profile());
			ProfileService.Names names = controller.profileService().names();
			offers.settle(offer, decide(offer.connection(), offer.profile(), names.name(offer.profile()), names), true);
			showComponent(minecraft, result);
		} else if (ServerProfilePrompt.ACTION_FORGET.equals(actionId)) {
			offers.retire(offer);
			show(minecraft, ServerProfilesView.forgot(store().forgetKey(offer.key())));
		}
	}

	// Whether this server's toast was shown this game session (ServerProfilesGameTest; key: the entry's).
	public boolean toasted(String key) {
		return offers.toasted(key);
	}

	// The last toast's text (ServerProfilesGameTest).
	public @Nullable Component lastToast() {
		return lastToast;
	}

	// ServerProfilesScreen's model (render thread): one read of server-profiles.json and one of profiles.json.
	public ServerProfilesView view() {
		return view(offers.current(), store().snapshot(), controller.profileService().names(), onBattery(), ZoneId.systemDefault());
	}

	static ServerProfilesView view(@Nullable Connection connection, ServerProfileStore.Snapshot servers, ProfileService.Names names, boolean onBattery,
			ZoneId zone) {
		ServerProfilesView.State state = connection == null ? ServerProfilesView.State.NOT_CONNECTED
				: connection.kind() == ServerLimits.Kind.SINGLEPLAYER ? ServerProfilesView.State.OWN_WORLD
				: connection.address() == null ? ServerProfilesView.State.UNRECOGNISED : ServerProfilesView.State.SERVER;
		String key = state == ServerProfilesView.State.SERVER ? servers.keyOf(connection.address()) : null;
		return ServerProfilesView.of(state, connection == null ? null : connection.kind(), key, servers.entries(), names::name, names.active(),
				onBattery, servers.writable(), zone);
	}

	// "Offer %s here": from the next join on. A pending offer for this server goes (the new choice counts from then).
	public Component remember(@Nullable String profileId) {
		if (profileId == null) {
			return Component.translatable("rigtune.profile.server.remember.none");
		}
		Connection connection = offers.current();
		Text name = controller.profileService().nameOf(profileId);
		if (connection == null || !connection.offerable() || name == null) {
			return Component.translatable("rigtune.profile.status.unavailable");
		}
		ServerProfileStore.Result result = store().remember(connection.address(), connection.kind(), profileId, Instant.now());
		if (result == ServerProfileStore.Result.OK) {
			offers.forgot(store().keyOf(connection.address()));
		}
		return Texts.component(ServerProfilesView.remembered(result, name));
	}

	// "Stop offering here" (this server's key) and Forget (a row's).
	public Component forget(String key) {
		offers.forgot(key);
		return Texts.component(ServerProfilesView.forgot(store().forgetKey(key)));
	}

	public Component forgetAll() {
		offers.forgot(null);
		return Texts.component(ServerProfilesView.forgotAll(store().forgetAll()));
	}

	// RealController.deleteProfile, after Profiles deleted it: its servers are forgotten ("My settings" can't be deleted,
	// so it still resolves and keeps its servers).
	public void forgetProfile(String profileId) {
		if (controller.profileService().nameOf(profileId) == null) {
			store().forgetProfile(profileId);
		}
	}

	// For ServerProfilesGameTest only: null puts the real check back.
	public static void overrideBenchmarkCheck(@Nullable BooleanSupplier running) {
		benchmarkRunning = running == null ? BenchmarkController::running : running;
	}

	private Reason decide(Connection connection, String profile, @Nullable Text name, ProfileService.Names names) {
		return ServerProfilePrompt.decide(connection.kind(), profile, name != null, names.active(), benchmarkRunning.getAsBoolean(), onBattery());
	}

	private void show(Minecraft minecraft, Text body) {
		showComponent(minecraft, Texts.component(body));
	}

	private void showComponent(Minecraft minecraft, Component body) {
		lastToast = body;
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID, Texts.component(ServerProfilePrompt.toastTitle()), body);
	}

	// RigTune's key's name, or null when none is bound (the toast then says to open RigTune).
	private static @Nullable Text key() {
		KeyMapping open = RigTuneClient.openKey();
		return open == null || open.isUnbound() ? null : Text.literal(open.getTranslatedKeyMessage().getString());
	}

	private boolean onBattery() {
		HardwareProfile hardware = controller.hardwareProfile();
		return hardware != null && hardware.onBattery();
	}

	private ServerProfileStore store() {
		return ServerProfileStore.shared(controller.configDir());
	}
}
