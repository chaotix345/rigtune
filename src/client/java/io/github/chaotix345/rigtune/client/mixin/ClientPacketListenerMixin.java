package io.github.chaotix345.rigtune.client.mixin;

import io.github.chaotix345.rigtune.client.server.ServerLimitsTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Server-aware advice (docs/v0.4/SPEC.md 8): after the client stored what the server sent (the login packet and the two
// live updates), ServerLimitsTracker reads it through ClientPacketListenerAccessor. The handlers run on the render thread
// (each starts with PacketUtils.ensureRunningOnSameThread), so TAIL is reached there only.
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("TAIL"), require = 1)
	private void rigtune$afterLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
		ServerLimitsTracker.onLimits((ClientPacketListener) (Object) this);
	}

	@Inject(method = "handleSetChunkCacheRadius", at = @At("TAIL"), require = 1)
	private void rigtune$afterChunkCacheRadius(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
		ServerLimitsTracker.onLimits((ClientPacketListener) (Object) this);
	}

	@Inject(method = "handleSetSimulationDistance", at = @At("TAIL"), require = 1)
	private void rigtune$afterSimulationDistance(ClientboundSetSimulationDistancePacket packet, CallbackInfo ci) {
		ServerLimitsTracker.onLimits((ClientPacketListener) (Object) this);
	}
}
