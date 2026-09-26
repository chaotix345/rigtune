package io.github.chaotix345.p5a.mixin;

import io.github.chaotix345.p5a.P5aFrames;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
abstract class FrameMixin {
	@Inject(method = "logFrameDuration", at = @At("HEAD"))
	private void p5a$frame(long nanos, CallbackInfo ci) {
		P5aFrames.onFrame(nanos);
	}
}
