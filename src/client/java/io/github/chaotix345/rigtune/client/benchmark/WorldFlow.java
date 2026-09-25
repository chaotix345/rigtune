package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld.State;

// The benchmark world's state machine without Minecraft, so every rule is unit-tested (WorldFlowTest). BenchmarkWorld
// turns the game into an Observation each tick and carries out the returned Action. The rules that keep the player's own
// worlds safe: only the benchmark save is ever set up or left; a server connection or anything else ends the flow
// without touching it.
final class WorldFlow {
	static final int TIMEOUT_TICKS = 20 * 90;
	static final int FAILED_OPEN_GRACE_TICKS = 20;
	// After giving up, a benchmark world that still finishes loading within this time is left again.
	static final int LATE_LOAD_TICKS = 20 * 60;

	enum Action { NONE, SET_UP, READY, LEAVE, FINISH_EXIT }

	// worldLoaded: a client level exists. multiplayer: that level isn't from the integrated server. serverRunning: an
	// integrated server exists. benchmarkSave: the loaded world is singleplayer, ready, and its save is rigtune-benchmark
	// with the level name RigTune gave it. backOnMenu: no world, no server, and the screen the flow was opened from is
	// showing again (WorldOpenFlows gave up without a callback). atCamera: the player is at the camera spot, no screen open.
	record Observation(boolean worldLoaded, boolean multiplayer, boolean serverRunning, boolean singleplayerReady,
			boolean benchmarkSave, boolean backOnMenu, boolean atCamera) {
	}

	record Step(State state, Action action) {
	}

	private WorldFlow() {
	}

	static Step next(State state, int ticksInState, Observation o) {
		Step stay = new Step(state, Action.NONE);
		return switch (state) {
			case OPENING -> {
				if (o.multiplayer()) {
					yield new Step(State.FAILED, Action.NONE);
				}
				if (o.worldLoaded() && o.singleplayerReady()) {
					yield o.benchmarkSave() ? new Step(State.SETTING_UP, Action.SET_UP) : new Step(State.FAILED, Action.NONE);
				}
				if (ticksInState > TIMEOUT_TICKS || o.backOnMenu() && ticksInState > FAILED_OPEN_GRACE_TICKS) {
					yield new Step(State.FAILED, Action.NONE);
				}
				yield stay;
			}
			case SETTING_UP -> {
				if (!o.benchmarkSave()) {
					yield new Step(State.FAILED, Action.NONE);
				}
				if (o.atCamera()) {
					yield new Step(State.READY, Action.READY);
				}
				yield ticksInState > TIMEOUT_TICKS ? new Step(state, Action.LEAVE) : stay;
			}
			case READY -> o.benchmarkSave() ? stay : new Step(State.IDLE, Action.NONE);
			case LEAVING -> !o.worldLoaded() && !o.serverRunning() ? new Step(State.IDLE, Action.FINISH_EXIT) : stay;
			case FAILED -> o.benchmarkSave() && ticksInState <= LATE_LOAD_TICKS ? new Step(state, Action.LEAVE) : stay;
			case IDLE, AWAITING_EXIT -> stay;
		};
	}
}
