package io.github.chaotix345.rigtune.client.mixin;

import io.github.chaotix345.rigtune.client.benchmark.FrameTimes;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
	@Inject(method = "logFrameDuration", at = @At("HEAD"))
	private void rigtune$recordFrame(long frameDuration, CallbackInfo ci) {
		FrameTimes.onFrame(frameDuration);
	}
}
