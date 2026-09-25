package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.benchmark.KnobGuard;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.ModToggles;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Sets the benchmark knobs on the render thread. Render and simulation distance change in memory only (the controller
// writes options.txt once, with the original values); Distant Horizons and Iris go through ModToggles and the restore
// marker.
final class ClientKnobs implements KnobGuard.Applier {
	static final String RENDER_DISTANCE = "vanilla.renderDistance";
	static final String SIMULATION_DISTANCE = "vanilla.simulationDistance";

	private final Minecraft minecraft;
	private final ModToggles toggles;

	ClientKnobs(Minecraft minecraft, Knobs original, Path markerFile) {
		this.minecraft = minecraft;
		this.toggles = new ModToggles(original, markerFile, new ModToggles.Mods() {
			@Override
			public void setDhRendering(boolean on) {
				OptionalMods.setDhRendering(on);
			}

			@Override
			public void restoreDhRendering() {
				OptionalMods.restoreDhRendering();
			}

			@Override
			public void setShaders(boolean on) {
				OptionalMods.setShaders(on);
			}
		}, () -> Instant.now().toString());
	}

	@Override
	public void apply(Knobs from, Knobs to) throws Exception {
		List<String> failures = new ArrayList<>();
		Map<String, String> vanilla = new LinkedHashMap<>();
		if (from.renderDistance() != to.renderDistance()) {
			vanilla.put(RENDER_DISTANCE, Integer.toString(to.renderDistance()));
		}
		if (from.simulationDistance() != to.simulationDistance()) {
			vanilla.put(SIMULATION_DISTANCE, Integer.toString(to.simulationDistance()));
		}
		if (!vanilla.isEmpty()) {
			SettingsBridge.applyVanilla(minecraft.options, vanilla, false).values().stream()
					.filter(r -> !r.ok())
					.forEach(r -> failures.add(r.key() + ": " + r.message()));
		}
		toggles.apply(from, to, failures);
		if (!failures.isEmpty()) {
			throw new IllegalStateException(String.join("; ", failures));
		}
	}
}
