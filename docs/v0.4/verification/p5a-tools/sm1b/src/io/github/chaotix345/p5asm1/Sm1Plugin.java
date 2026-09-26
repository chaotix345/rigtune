package io.github.chaotix345.p5asm1;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

// Test-only (RigTune v0.4 AC5.8 / plan review S-M1): before the mixins on Minecraft apply, rewrite runTick's call to
// Minecraft.tick() into a static call to Sm1Hook.tick(Minecraft), so RigTune's phase-timer injectors that target
// INVOKE Minecraft.tick() find no target (they are require = 0). The game still ticks through the hook.
public final class Sm1Plugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		int replaced = 0;
		for (MethodNode m : targetClass.methods) {
			if (!m.name.equals("runTick")) {
				continue;
			}
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals("net/minecraft/client/Minecraft")
						&& call.name.equals("tick") && call.desc.equals("()V")) {
					m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "io/github/chaotix345/p5asm1/Sm1Hook", "tick",
							"(Lnet/minecraft/client/Minecraft;)V", false));
					replaced++;
				}
			}
		}
		System.out.println("[P5A-SM1] rewrote " + replaced + " call(s) to Minecraft.tick() in runTick before the mixins apply");
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
