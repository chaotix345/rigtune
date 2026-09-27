package io.github.chaotix345.rigtune.v040.core.recommend;

import java.util.List;
import java.util.Set;

// NOT a verbatim copy (the v030 stub's precedent, docs/v0.4/plan-review.md K-L1): only the two members of v0.4.0's
// Recommender that decide a rule's `requires` (Recommender.java:52 and :233-235 at tag v0.4.0), for the v0.5 4i reach test
// (docs/v0.5/SPEC.md AC4i.1). v0.4.0's Recommender.context (:125-141) builds the mod-version map from the scanned mods and
// advice() (:448-462) evaluates each supported rule's `when`; OldClientWarningTest builds the context the same way. The
// rest of the 0.4.0 Recommender isn't pinned.
public final class Recommender {
	public static final Set<String> SUPPORTED_FEATURES = Set.of("jvm-flags");

	private Recommender() {
	}

	public static boolean supported(List<String> requires) {
		return requires == null || requires.stream().allMatch(feature -> feature != null && SUPPORTED_FEATURES.contains(feature));
	}
}
