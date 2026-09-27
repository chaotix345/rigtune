package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.client.V05Hooks;
import net.minecraft.network.chat.Component;

import java.util.List;

// docs/v0.5/SPEC.md 2V (ws-g2's refused Disable): the Apply status says how many Disable items were refused
// (V05Hooks.afterApply, first in its list). Contracts stub (WS-K): adds nothing, until WS-H fills it in.
public final class RefusedDisables {
	private RefusedDisables() {
	}

	public static void afterApply(V05Hooks.ApplyFacts facts, List<Component> parts) {
	}
}
