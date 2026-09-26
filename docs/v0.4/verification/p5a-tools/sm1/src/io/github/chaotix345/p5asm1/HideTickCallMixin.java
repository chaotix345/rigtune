package io.github.chaotix345.p5asm1;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Test-only (RigTune v0.4 AC5.8 / plan review S-M1): replaces runTick's call to Minecraft.tick() before RigTune's
// mixins apply (priority 500 < 1000), so RigTune's phase-timer injectors that target that INVOKE find no target. The game
// still ticks: the handler calls tick() itself.
@Mixin(value = Minecraft.class, priority = 500)
abstract class HideTickCallMixin {
	@Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
	private void p5asm1$tick(Minecraft self) {
		self.tick();
	}
}
