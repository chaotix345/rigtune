package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.OptionalInt;

// The read-only shader advice on the result screen (docs/v0.3/SPEC.md 8a). It shows exactly when a Tune measured the
// shader cost, the 1% low with shaders is below the target, the 1% low without them reaches it, and the gap is at least
// MIN_GAP_PERCENT (twice BenchmarkMath.NOISY_CV: the quick protocol has one sample per side). The gap is the number
// the advice shows: the share of the shaders-off 1% low the pack takes. Nothing is changed: the player picks a lighter
// profile in the pack's own settings. Iris has no API for profiles, and they're per pack.
public final class ShaderAdvice {
	public static final double MIN_GAP_PERCENT = 10;

	private ShaderAdvice() {
	}

	/** What the shader pack costs, in % of the 1% lows without it, when the advice applies; empty otherwise. */
	public static OptionalInt costPercent(SessionResult.@Nullable Cost shaders, double targetFps) {
		if (shaders == null || shaders.baselineLow() <= 0) {
			return OptionalInt.empty();
		}
		boolean below = shaders.baselineLow() < targetFps;
		boolean reaches = shaders.offLow() >= targetFps;
		double cost = 100 * (shaders.offLow() - shaders.baselineLow()) / shaders.offLow();
		if (!below || !reaches || cost < MIN_GAP_PERCENT - 1e-9) {
			return OptionalInt.empty();
		}
		return OptionalInt.of((int) Math.round(cost));
	}
}
