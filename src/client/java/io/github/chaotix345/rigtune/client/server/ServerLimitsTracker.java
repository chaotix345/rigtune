package io.github.chaotix345.rigtune.client.server;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.mixin.ClientPacketListenerAccessor;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.server.ServerLimitsStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

// Server-aware advice (docs/v0.4/SPEC.md 8, plan review W-H1): the limits the connected server sent, fed by
// ClientPacketListenerMixin (login and the two live updates) and cleared on DISCONNECT. The report rebuilds on JOIN, on
// DISCONNECT and when a connected server changes its limits. Remote servers, LAN guests and Realms are remembered in
// server-limits.json (not in readable form) only so the notice can say the limit changed since last time; the
// Recommender only ever sees the live limits. The player's own world (including an Open-to-LAN host) is SINGLEPLAYER.
public final class ServerLimitsTracker {
	private static volatile @Nullable ServerLimitsTracker active;

	// wasViewDistance: the view distance this server sent before (last time, or before a live change), when it differed.
	public record Live(ServerLimits limits, @Nullable String address, @Nullable Integer wasViewDistance) {
	}

	private final RealController controller;
	private final Path configDir;
	private volatile @Nullable Live live;
	private volatile @Nullable ServerLimitsStore store;
	// Bumped on every disconnect, so a store lookup that finishes after it changes nothing.
	private volatile int connection;

	public ServerLimitsTracker(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	// Once, from RigTuneClient.
	public void register() {
		active = this;
		ClientPlayConnectionEvents.JOIN.register((listener, sender, minecraft) -> update(listener, true));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> disconnected());
	}

	// ClientPacketListenerMixin, on the render thread.
	public static void onLimits(ClientPacketListener listener) {
		ServerLimitsTracker tracker = active;
		if (tracker != null) {
			tracker.update(listener, false);
		}
	}

	// Null when not connected (or not known yet).
	public @Nullable ServerLimits live() {
		Live current = live;
		return current == null ? null : current.limits();
	}

	public @Nullable Live liveState() {
		return live;
	}

	// For the game tests.
	public Path storeFile() {
		return ServerLimitsStore.file(configDir);
	}

	private void update(ClientPacketListener listener, boolean joined) {
		try {
			ClientPacketListenerAccessor sent = (ClientPacketListenerAccessor) listener;
			ServerData data = listener.getServerData();
			ServerLimits.Kind kind = kind(Minecraft.getInstance(), data);
			int view = sent.rigtune$serverChunkRadius();
			int simulation = sent.rigtune$serverSimulationDistance();
			Live before = live;
			boolean same = before != null && before.limits().kind() == kind && before.limits().viewDistance() == view
					&& before.limits().simulationDistance() == simulation;
			if (same && !joined) {
				return;
			}
			String address = address(kind, data);
			Integer was = before == null || !Objects.equals(before.address(), address) ? null
					: before.limits().viewDistance() != view ? Integer.valueOf(before.limits().viewDistance()) : before.wasViewDistance();
			Live next = new Live(new ServerLimits(view, simulation, kind, System.currentTimeMillis()), address, was);
			live = next;
			if (!same && kind != ServerLimits.Kind.SINGLEPLAYER && address != null && view > 0) {
				remember(next);
			}
			if (joined || before != null) {
				rebuild();
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the server's view distance", e);
		}
	}

	// Off the render thread: the file write, then "(was N)" from what this server sent last time.
	private void remember(Live next) {
		int at = connection;
		CompletableFuture.runAsync(() -> {
			ServerLimitsStore.Entry previous = store().remember(next.address(), next.limits());
			if (previous != null && previous.viewDistance() != next.limits().viewDistance() && next.wasViewDistance() == null
					&& at == connection && live == next) {
				live = new Live(next.limits(), next.address(), previous.viewDistance());
				rebuild();
			}
		}, Probes.EXECUTOR).exceptionally(e -> {
			RigTune.LOGGER.warn("Could not update {}", ServerLimitsStore.FILE_NAME, e);
			return null;
		});
	}

	private void disconnected() {
		connection++;
		Live before = live;
		live = null;
		if (before != null) {
			rebuild();
		}
	}

	private void rebuild() {
		if (controller.minecraft() != null) {
			controller.rebuild();
		}
	}

	private synchronized ServerLimitsStore store() {
		if (store == null) {
			store = new ServerLimitsStore(configDir);
		}
		return store;
	}

	static ServerLimits.Kind kind(Minecraft minecraft, @Nullable ServerData data) {
		if (minecraft.hasSingleplayerServer()) {
			return ServerLimits.Kind.SINGLEPLAYER;
		}
		if (data != null && data.isRealm()) {
			return ServerLimits.Kind.REALM;
		}
		return data != null && data.isLan() ? ServerLimits.Kind.LAN_GUEST : ServerLimits.Kind.REMOTE;
	}

	static @Nullable String address(ServerLimits.Kind kind, @Nullable ServerData data) {
		if (data == null || kind == ServerLimits.Kind.SINGLEPLAYER) {
			return null;
		}
		if (kind == ServerLimits.Kind.REALM) {
			return data.name == null ? null : ServerLimitsStore.realm(data.name);
		}
		if (data.ip == null || data.ip.isBlank()) {
			return null;
		}
		ServerAddress parsed = ServerAddress.parseString(data.ip);
		return ServerLimitsStore.address(parsed.getHost(), parsed.getPort());
	}
}
