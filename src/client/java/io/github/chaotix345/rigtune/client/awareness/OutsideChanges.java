package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.V05Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

// docs/v0.5/SPEC.md 4h: settings changed outside the game, a one-shot start-up comparison. snapshotAtStop: a clean
// CLIENT_STOPPING (V05Services.registerEvents registers it to run before RigTune's own exit work). compareAtStart: the
// start hook, on Probes.EXECUTOR. afterApply: the Apply status's Modrinth App sync line (V05Hooks.afterApply, second).
// Contracts stubs (WS-K): no-ops, until WS-W fills them in.
public final class OutsideChanges {
	private OutsideChanges() {
	}

	public static void snapshotAtStop(RealController controller, Minecraft minecraft) {
	}

	public static void compareAtStart(RealController controller) {
	}

	public static void afterApply(RealController controller, V05Hooks.ApplyFacts facts, List<Component> parts) {
	}
}
