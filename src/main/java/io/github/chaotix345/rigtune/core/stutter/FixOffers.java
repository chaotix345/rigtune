package io.github.chaotix345.rigtune.core.stutter;

import java.util.Set;

// docs/v0.5/SPEC.md 5 (C20): which one-click fixes the Stutter Doctor offers under the advice that fired.
public final class FixOffers {
	// The features a stutterFixes entry may require; never known to the main list (Recommender) or StutterAdvisor.
	public static final Set<String> SUPPORTED_FEATURES = Set.of(FixSpec.FEATURE);

	private FixOffers() {
	}
}
