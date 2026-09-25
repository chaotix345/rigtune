package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.benchmark.KnobGuard;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.RestoreMarker;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Sets the benchmark knobs on the render thread. Render and simulation distance change in memory only (the controller
// writes options.txt once, with the original values). Before the first Distant Horizons or Iris change the original
// values go into the restore marker; the marker is deleted once both are back.
final class ClientKnobs implements KnobGuard.Applier {
	static final String RENDER_DISTANCE = "vanilla.renderDistance";
	static final String SIMULATION_DISTANCE = "vanilla.simulationDistance";

	private final Minecraft minecraft;
	private final Knobs original;
	private final Path markerFile;
	private boolean markerWritten;

	ClientKnobs(Minecraft minecraft, Knobs original, Path markerFile) {
		this.minecraft = minecraft;
		this.original = original;
		this.markerFile = markerFile;
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
		if (from.dhRendering() != to.dhRendering() || from.shaders() != to.shaders()) {
			boolean backToOriginal = to.dhRendering() == original.dhRendering() && to.shaders() == original.shaders();
			if (!backToOriginal && !markerWritten) {
				new RestoreMarker(original.dhRendering() ? Boolean.TRUE : null, original.shaders() ? Boolean.TRUE : null,
						Instant.now().toString()).save(markerFile);
				markerWritten = true;
			}
			if (from.dhRendering() != to.dhRendering()) {
				try {
					if (to.dhRendering() == original.dhRendering()) {
						OptionalMods.restoreDhRendering();
					} else {
						OptionalMods.setDhRendering(to.dhRendering());
					}
				} catch (RuntimeException | LinkageError e) {
					failures.add("Distant Horizons rendering: " + e);
				}
			}
			if (from.shaders() != to.shaders()) {
				try {
					OptionalMods.setShaders(to.shaders());
				} catch (RuntimeException | LinkageError e) {
					failures.add("Iris shaders: " + e);
				}
			}
			if (backToOriginal && failures.isEmpty()) {
				RestoreMarker.delete(markerFile);
				markerWritten = false;
			}
		}
		if (!failures.isEmpty()) {
			throw new IllegalStateException(String.join("; ", failures));
		}
	}
}
