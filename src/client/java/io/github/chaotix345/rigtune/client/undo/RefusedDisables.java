package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.V05Hooks;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// docs/v0.5/SPEC.md 2V (ws-g2's refused Disable): a "Disable X" DisableGuard refuses (another mod needs X, or another change
// of X is staged) shows the same way in Preview and Apply: Preview lists it under "Not changed" with DisableGuard's reason,
// and the Apply status counts it as not applied (V05Hooks.afterApply, first in its list; DisableGuard's toast names it).
public final class RefusedDisables {
	private RefusedDisables() {
	}

	public static void afterApply(V05Hooks.ApplyFacts facts, List<Component> parts) {
		int refused = (int) facts.selected().stream().filter(r -> r.action() instanceof Action.DisableMod && !facts.disablesAllowed().contains(r.id())).count();
		if (refused > 0) {
			parts.add(Component.translatable("rigtune.status.disables_refused", refused));
		}
	}

	// For PreviewPlanner.withDisableRefusals: DisableGuard's refusals for these file names in this instance's mods folder, as
	// RealController.apply asks it (one Apply's disables together); none when it can't tell, as Apply then disables them.
	public static Function<List<String>, Map<String, Text>> previewRefusals(Path pendingFile, Path modsDir) {
		return files -> {
			try {
				return DisableGuard.refusals(pendingFile, ModsFolder.current(modsDir), files);
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Could not check what depends on {}", files, e);
				return Map.of();
			}
		};
	}
}
