package io.github.chaotix345.rigtune.v030.core.benchmark;

// The settings a benchmark changes while it measures. dhRendering and shaders are true only when Distant Horizons is
// rendering / an Iris shader pack is in use; the benchmark only ever turns them off for a cost report.
public record Knobs(int renderDistance, int simulationDistance, boolean dhRendering, boolean shaders) {
	public Knobs withRenderDistance(int rd) {
		return new Knobs(rd, simulationDistance, dhRendering, shaders);
	}

	public Knobs withSimulationDistance(int sd) {
		return new Knobs(renderDistance, sd, dhRendering, shaders);
	}

	public Knobs withDhRendering(boolean on) {
		return new Knobs(renderDistance, simulationDistance, on, shaders);
	}

	public Knobs withShaders(boolean on) {
		return new Knobs(renderDistance, simulationDistance, dhRendering, on);
	}
}
