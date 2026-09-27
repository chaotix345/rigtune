package io.github.chaotix345.rigtune.client.server;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 7 (C16): per-server profile offers. Reached only through RealController.v05() (X4); nothing happens
// in the constructor. Contracts skeleton (WS-K): nothing remembered, nothing offered, until WS-P2 fills it in. JOIN and
// DISCONNECT arrive through V05Services.registerEvents (registered after ServerLimitsTracker's), on the render thread.
public final class ServerProfileService {
	private final RealController controller;

	public ServerProfileService(RealController controller) {
		this.controller = controller;
	}

	public ServerProfilesView view() {
		return ServerProfilesView.EMPTY;
	}

	public Component remember(@Nullable String profileId) {
		return Component.translatable("rigtune.status.nothing");
	}

	public Component forget(String key) {
		return Component.translatable("rigtune.status.nothing");
	}

	public Component forgetAll() {
		return Component.translatable("rigtune.status.nothing");
	}

	public void onJoin(ClientPacketListener listener, Minecraft minecraft) {
	}

	public void onDisconnect() {
	}
}
