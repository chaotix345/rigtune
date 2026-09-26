package io.github.chaotix345.rigtune.v030.core.benchmark;

// One measurement: set these knobs, then measure with this protocol.
public record Step(Kind kind, Knobs knobs, Protocol protocol) {
	public enum Kind {
		RENDER_DISTANCE, SIMULATION_DISTANCE, REPEAT, BASELINE, DH_OFF, SHADERS_OFF
	}
}
