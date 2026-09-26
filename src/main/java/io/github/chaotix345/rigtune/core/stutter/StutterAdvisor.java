package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.ConditionEvaluator;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Evaluates the rules-v2 `stutterAdvice` section against a session's StutterFacts (docs/v0.4/SPEC.md 5). This is the
// only place that knows the feature "stutter-doctor": the main Recommender doesn't, so it skips these entries, and the
// stutter keys are UNKNOWN there anyway (no facts). An entry whose `requires` names a feature this version lacks is
// skipped. Advice only informs; nothing is applied from here.
public final class StutterAdvisor {
	public static final String FEATURE = "stutter-doctor";
	public static final Set<String> SUPPORTED_FEATURES = Set.of(FEATURE);
	public static final String MEMORY_PREFIX = "ram-";

	// kind: info, warning or critical (as in the main list's advice).
	public record Fired(String id, String kind, Impact impact, String title, String text) {
		public boolean memory() {
			return id.startsWith(MEMORY_PREFIX);
		}
	}

	private StutterAdvisor() {
	}

	public static boolean supported(@Nullable List<String> requires) {
		return requires == null || requires.stream().allMatch(feature -> feature != null && SUPPORTED_FEATURES.contains(feature));
	}

	// ctx must carry the session's facts (EvalContext.withStutter); without them nothing fires.
	public static List<Fired> evaluate(@Nullable RulesDocument rules, EvalContext ctx) {
		List<Fired> out = new ArrayList<>();
		if (rules == null || rules.stutterAdvice == null) {
			return out;
		}
		for (RulesDocument.AdviceRule rule : rules.stutterAdvice) {
			if (rule == null || rule.id == null || !supported(rule.requires) || !ConditionEvaluator.matches(rule.when, ctx)) {
				continue;
			}
			String kind = rule.kind == null ? "info" : rule.kind.toLowerCase(Locale.ROOT);
			Impact impact = switch (kind) {
				case "critical" -> Impact.HIGH;
				case "warning" -> RulesDocument.impactOf(rule.impact, Impact.MEDIUM);
				default -> RulesDocument.impactOf(rule.impact, Impact.LOW);
			};
			out.add(new Fired(rule.id, kind, impact, rule.title != null ? rule.title : rule.id, rule.text == null ? "" : rule.text));
		}
		return out;
	}

	// The evaluation context the main list builds for this machine (Recommender.context) plus the session's facts, so
	// stutter advice can also use the tier, heap, mod and setting keys.
	public static EvalContext context(RulesDocument rules, HardwareProfile hardware, @Nullable List<InstalledMod> mods, @Nullable SettingsSnapshot settings,
			Goal goal, StutterFacts facts) {
		return Recommender.context(rules, hardware, mods, settings, goal).withStutter(facts);
	}
}
