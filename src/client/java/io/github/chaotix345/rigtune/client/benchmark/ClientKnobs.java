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
// marker. After a render or simulation distance change, the new values are broadcast to the server (docs/v0.3/SPEC.md
// 3f): the server sends chunks out to the render distance the client last told it, and only Options.save() tells it
// otherwise, so without this every step above the starting distance measured the starting chunks. The broadcast is
// the packet vanilla sends on every options save; it writes no file, does nothing without a player, and sends nothing
// when only the simulation distance changed (it isn't part of the client information, and an unchanged one isn't sent).
final class ClientKnobs implements KnobGuard.Applier {
	static final String RENDER_DISTANCE = "vanilla.renderDistance";
	static final String SIMULATION_DISTANCE = "vanilla.simulationDistance";

	// The game's video options, so the unit test can count what happens to them.
	interface Vanilla {
		Map<String, SettingsBridge.Result> apply(Map<String, String> values, boolean save);

		void broadcast();
	}

	private final Vanilla vanilla;
	private final ModToggles toggles;

	ClientKnobs(Minecraft minecraft, Knobs original, Path markerFile) {
		this(new Vanilla() {
			@Override
			public Map<String, SettingsBridge.Result> apply(Map<String, String> values, boolean save) {
				return SettingsBridge.applyVanilla(minecraft.options, values, save);
			}

			@Override
			public void broadcast() {
				minecraft.options.broadcastOptions();
			}
		}, new ModToggles(original, markerFile, new ModToggles.Mods() {
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
		}, () -> Instant.now().toString()));
	}

	ClientKnobs(Vanilla vanilla, ModToggles toggles) {
		this.vanilla = vanilla;
		this.toggles = toggles;
	}

	@Override
	public void apply(Knobs from, Knobs to) throws Exception {
		List<String> failures = new ArrayList<>();
		Map<String, String> values = new LinkedHashMap<>();
		if (from.renderDistance() != to.renderDistance()) {
			values.put(RENDER_DISTANCE, Integer.toString(to.renderDistance()));
		}
		if (from.simulationDistance() != to.simulationDistance()) {
			values.put(SIMULATION_DISTANCE, Integer.toString(to.simulationDistance()));
		}
		if (!values.isEmpty()) {
			vanilla.apply(values, false).values().stream()
					.filter(r -> !r.ok())
					.forEach(r -> failures.add(r.key() + ": " + r.message()));
			try {
				vanilla.broadcast();
			} catch (RuntimeException e) {
				failures.add("broadcast to the server: " + e);
			}
		}
		toggles.apply(from, to, failures);
		if (!failures.isEmpty()) {
			throw new IllegalStateException(String.join("; ", failures));
		}
	}
}
