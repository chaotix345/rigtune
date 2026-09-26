package io.github.chaotix345.rigtune.client.mixin;

import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The Stutter Doctor's phase timers (docs/v0.4/SPEC.md 5; research §2.4): where a frame's time went, for attribution.
// packets = PacketProcessor.processQueuedPackets + runAllTasks (chunk data is handled here), ticks = every Minecraft.tick
// in the frame, render = renderFrame up to the frame limiter (closed by StutterMonitor.onFrame), limiter = the limiter's
// wait (only while the frame rate is capped). The targets are identical on 26.2 and 26.3 (javap), but every injector
// is optional (plan review S-M1: require = 0, expect = 0): a missing target only turns phase attribution off, and the
// report then says "phase timing unavailable". Each handler is one volatile read while nothing captures.
@Mixin(Minecraft.class)
abstract class MinecraftFrameMixin {
	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"), require = 0,
			expect = 0)
	private void rigtune$packetsStart(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.packetsStart();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER),
			require = 0, expect = 0)
	private void rigtune$packetsEnd(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.packetsEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"), require = 0, expect = 0)
	private void rigtune$tickStart(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.tickStart();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = At.Shift.AFTER), require = 0,
			expect = 0)
	private void rigtune$tickEnd(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.tickEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"), require = 0, expect = 0)
	private void rigtune$renderStart(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.renderStart();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), require = 0,
			expect = 0)
	private void rigtune$limiterStart(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.limiterStart();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V",
			shift = At.Shift.AFTER), require = 0, expect = 0)
	private void rigtune$limiterEnd(boolean advanceGameTime, CallbackInfo ci) {
		StutterMonitor.limiterEnd();
	}
}
