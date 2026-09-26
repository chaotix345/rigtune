package io.github.chaotix345.p5asm1.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

// Empty on purpose: it only makes this config's plugin run preApply on Minecraft (before any injector looks for targets).
@Mixin(value = Minecraft.class, priority = 1)
abstract class MinecraftAnchorMixin {
}
