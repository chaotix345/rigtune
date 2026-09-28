package io.github.chaotix345.rigtune.client.mixin;

import io.github.chaotix345.rigtune.client.probe.PreloadTimer;
import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// docs/v0.5/SPEC.md 2L (RW-16), X4.4: times vanilla's crash-report setup, which Main.main runs before any mod's init
// (`CrashReport.preload()V`, the same on 26.2 and 26.3: javap). Two clock reads, once per launch. Optional (require = 0):
// if it doesn't apply, the launch-time advice leaves the number out.
@Mixin(CrashReport.class)
abstract class CrashReportMixin {
	@Inject(method = "preload", at = @At("HEAD"), require = 0)
	private static void rigtune$preloadStart(CallbackInfo ci) {
		PreloadTimer.start();
	}

	@Inject(method = "preload", at = @At("RETURN"), require = 0)
	private static void rigtune$preloadEnd(CallbackInfo ci) {
		PreloadTimer.end();
	}
}
