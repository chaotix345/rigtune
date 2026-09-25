package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.ModRule;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Which ModRules' mods conflict, by slug. A conflictsWith entry names another rule by its slug or one of its mod ids
// (resolved through every rule), and one side declaring it is enough (review 4, rules-accuracy-2).
public final class ModConflicts {
	private record Pair(String a, String b) {
	}

	private final Set<Pair> pairs = new HashSet<>();

	private ModConflicts() {
	}

	public static ModConflicts of(RulesDocument rules) {
		ModConflicts out = new ModConflicts();
		for (ModRule rule : rules.mods) {
			for (String ref : rule.conflictsWith) {
				String other = slugOf(rules.mods, ref);
				if (rule.slug != null && other != null && !other.equals(rule.slug)) {
					out.pairs.add(new Pair(rule.slug, other));
					out.pairs.add(new Pair(other, rule.slug));
				}
			}
		}
		return out;
	}

	public boolean between(String slugA, String slugB) {
		return pairs.contains(new Pair(slugA, slugB));
	}

	private static String slugOf(List<ModRule> mods, String ref) {
		if (ref == null) {
			return null;
		}
		if (mods.stream().anyMatch(m -> ref.equals(m.slug))) {
			return ref;
		}
		return mods.stream().filter(m -> m.modIds.contains(ref)).map(m -> m.slug).findFirst().orElse(ref);
	}
}
