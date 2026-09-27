package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.client.V05Hooks;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 2V (ws-g2, AC2V.3, the Apply half): a "Disable X" DisableGuard refuses wasn't counted in Apply's status
// line; now it says how many weren't disabled (the toast names them and why).
class RefusedDisablesTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();

	private static Recommendation disable(String mod, Path file) {
		return new Recommendation("disable:" + mod, Category.REMOVE_MOD, Impact.LOW, "Disable " + mod, "", new Action.DisableMod(mod, file), true);
	}

	private static List<Component> afterApply(List<Recommendation> selected, Set<String> allowed) {
		List<Component> parts = new ArrayList<>(List.of(Component.literal("Applied 1 setting(s).")));
		RefusedDisables.afterApply(new V05Hooks.ApplyFacts("e1", selected, allowed, 1, 0, 1, false, 0), parts, () -> MODS);
		return parts;
	}

	@Test
	void aRefusedDisableIsCountedAsNotApplied() {
		List<Component> parts = afterApply(List.of(disable("lib", MODS.resolve("lib.jar")), disable("x", MODS.resolve("x.jar"))), Set.of("disable:x"));

		assertEquals(2, parts.size());
		TranslatableContents refused = (TranslatableContents) parts.get(1).getContents();
		assertEquals("rigtune.status.disables_refused", refused.getKey());
		assertEquals(List.of(1), List.of(refused.getArgs()));
	}

	// Allowed ones, and one outside the mods folder (never DisableGuard's: Apply leaves it out, Preview says so), add nothing.
	@Test
	void nothingRefusedAddsNothing() {
		assertEquals(1, afterApply(List.of(disable("x", MODS.resolve("x.jar"))), Set.of("disable:x")).size());
		assertEquals(1, afterApply(List.of(), Set.of()).size());
		assertEquals(1, afterApply(List.of(disable("y", Path.of("elsewhere", "y.jar").toAbsolutePath())), Set.of()).size());
	}
}
