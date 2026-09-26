package io.github.chaotix345.rigtune.v030.core.recommend;

import java.util.List;
import java.util.Set;

// NOT a verbatim copy (docs/v0.4/plan-review.md K-L1, AC6.3): only the two members of v0.3.0's Recommender that decide a
// rule's `requires` (Recommender.java:50 and :123-125 at tag v0.3.0; the same in v0.2.0 at :49 and :115-117), so tests
// can show that 0.2.0/0.3.0 skip every rule requiring a feature. supported() is public here (package-private there) so
// tests in other packages can call it. The rest of the 0.3.0 Recommender isn't pinned.
public final class Recommender {
	public static final Set<String> SUPPORTED_FEATURES = Set.of();

	private Recommender() {
	}

	public static boolean supported(List<String> requires) {
		return requires == null || requires.stream().allMatch(feature -> feature != null && SUPPORTED_FEATURES.contains(feature));
	}
}
