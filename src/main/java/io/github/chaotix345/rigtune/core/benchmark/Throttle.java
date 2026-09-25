package io.github.chaotix345.rigtune.core.benchmark;

// Whether a step was measured while the game was throttled (Phase 5 finding 4), sampled every tick of its sweeps. The
// benchmark sets the frame limit to uncapped, so any lower limit means something throttled the game (a minimised
// window, Dynamic FPS); an inactive window counts too while a mod that slows unfocused windows is loaded. Such a run
// measures the throttle, not the machine.
public final class Throttle {
	private boolean inactive;
	private int lowestLimit = Integer.MAX_VALUE;

	public void reset() {
		inactive = false;
		lowestLimit = Integer.MAX_VALUE;
	}

	public void sample(boolean windowActive, int frameLimit) {
		inactive |= !windowActive;
		lowestLimit = Math.min(lowestLimit, frameLimit);
	}

	public boolean throttled(int uncappedLimit, boolean slowsUnfocusedWindows) {
		return lowestLimit < uncappedLimit || inactive && slowsUnfocusedWindows;
	}
}
