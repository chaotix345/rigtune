package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.modrinth.DryRunPlanner;
import io.github.chaotix345.rigtune.core.modrinth.RangeReader;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

// docs/v0.5/SPEC.md 2H L5: what one preview needs to read its downloads' fabric.mod.json in memory and run Apply's checks
// on them: the loaded mods' ranges (FabricPins, as Apply's planner gets them), the ids loaded other than as a top-level jar,
// and a RangeReader (closed when the preview's plan is done). Made on the preview's thread, never at startup.
public final class PreviewJarChecks {
	private PreviewJarChecks() {
	}

	// allowed: whether Modrinth may be asked now (RigTune's settings), checked before each request.
	public static DryRunPlanner.Checks of(String modVersion, BooleanSupplier allowed) {
		Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
		RangeReader reader = new RangeReader(modVersion, allowed);
		return new DryRunPlanner.Checks(FabricPins.of(mods), nestedOrProvided(mods), reader::read, reader::close);
	}

	// Every id a loaded mod has other than as its own top-level jar: nested mods, and what any mod provides.
	static Set<String> nestedOrProvided(Collection<ModContainer> mods) {
		Set<String> out = new HashSet<>();
		for (ModContainer mod : mods) {
			if (mod.getContainingMod().isPresent()) {
				out.add(mod.getMetadata().getId());
			}
			out.addAll(mod.getMetadata().getProvides());
		}
		return out;
	}
}
