package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.profile.ProfileSwitch;
import io.github.chaotix345.rigtune.core.rules.ConditionEvaluator;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.Truth;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// docs/v0.5/SPEC.md 5 (C20): which one-click fixes the Stutter Doctor offers under the advice that fired, behind the
// three-layer evidence gate: (1) the advice fired (the unchanged 0.4 path); (2) the client floor (FixGate) and the setting
// precondition; (3) the rules' `evidence` evaluates TRUE against the advice's own context (FALSE and UNKNOWN never offer).
// Pure, on the analysis worker.
public final class FixOffers {
	// The features a stutterFixes entry may require; never known to the main list (Recommender) or StutterAdvisor.
	public static final Set<String> SUPPORTED_FEATURES = Set.of(FixSpec.FEATURE);

	private FixOffers() {
	}

	// Per fired advice with a valid fix, in the rules' order: an Offer from the effective value to the target, or a NotYet
	// with its reason (FixGate's, then SERVER, then EVIDENCE). Nothing at all (the advice's own text covers it) when the
	// advice didn't fire, the key isn't in this instance, isn't changeable or its mod isn't loaded, or there's no target
	// (the effective value, staged ops included, is already there).
	// ctx: the advice's context, with the session's facts (StutterAdvisor.context). effective: the settings as the next
	// restart leaves them (EffectiveSettings). live: the connected server's limits (null when none). busy: another fix is
	// staged or being measured.
	public static Map<String, FixOffer> evaluate(List<FixSpec> specs, Collection<String> fired, StutterReport report, EvalContext ctx,
			SettingsSnapshot effective, Set<String> loadedMods, @Nullable ServerLimits live, boolean busy, boolean storeWritable) {
		Map<String, FixOffer> out = new LinkedHashMap<>();
		for (FixSpec spec : specs) {
			if (!fired.contains(spec.adviceId()) || !ProfileSwitch.takesPart(spec.key(), effective, loadedMods)) {
				continue;
			}
			String from = effective.get(spec.key());
			String to = spec.target(from);
			if (to == null) {
				continue;
			}
			out.put(spec.adviceId(), offer(spec, from, to, report, ctx, live, busy, storeWritable));
		}
		return out;
	}

	private static FixOffer offer(FixSpec spec, String from, String to, StutterReport report, EvalContext ctx, @Nullable ServerLimits live, boolean busy,
			boolean storeWritable) {
		FixOffer.Reason reason = FixGate.check(report, busy, storeWritable);
		if (reason == FixOffer.Reason.LENGTH) {
			return new FixOffer.NotYet(spec.adviceId(), reason, List.of(StutterSummary.clock(FixGate.MIN_GAMEPLAY_SECONDS),
					Integer.toString(FixGate.MIN_HITCHES), StutterSummary.clock(report.gameplaySeconds()), Integer.toString(report.hitches())));
		}
		if (reason != null) {
			return new FixOffer.NotYet(spec.adviceId(), reason, List.of());
		}
		Integer limit = serverLimit(spec, to, live);
		if (limit != null) {
			return new FixOffer.NotYet(spec.adviceId(), FixOffer.Reason.SERVER, List.of(Integer.toString(limit)));
		}
		if (ConditionEvaluator.evaluate(spec.evidence(), ctx) != Truth.TRUE) {
			return new FixOffer.NotYet(spec.adviceId(), FixOffer.Reason.EVIDENCE, List.of());
		}
		return new FixOffer.Offer(spec.adviceId(), spec.key(), from, to, spec.now());
	}

	// The server's view distance when a LAN guest, Realm or remote server already sends no more than the render-distance
	// fix's target (a shorter distance would change nothing there); else null. Singleplayer, an Open-to-LAN host included,
	// never blocks.
	private static @Nullable Integer serverLimit(FixSpec spec, String to, @Nullable ServerLimits live) {
		if (!spec.key().equals("vanilla.renderDistance") || live == null || live.kind() == ServerLimits.Kind.SINGLEPLAYER || live.viewDistance() <= 0) {
			return null;
		}
		try {
			return live.viewDistance() <= Integer.parseInt(to) ? live.viewDistance() : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
