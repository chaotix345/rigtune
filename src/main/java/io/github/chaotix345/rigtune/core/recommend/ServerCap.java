package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// Server-aware advice (docs/v0.4/SPEC.md 8 as amended by plan review W-H1 and X-M3): a pure step on the main list's
// finished recommendations, never inside the Recommender's value/clamp logic (so profile templates never see server
// limits). Only while connected to a server that isn't the player's own world (SINGLEPLAYER includes an Open-to-LAN host),
// and only from the live limits (stored ones never): the server limit L only lowers a proposed render-distance increase.
// Target T, current C: T > C becomes min(T, max(C, L)), with the reason when that caps it; capped down to C, nothing is
// left to change and the recommendation goes (the notice explains). T <= C (the rules' own decrease) is untouched: no
// decrease is ever made because of the server. Simulation distance isn't changed (the server decides it).
public final class ServerCap {
	public static final String RENDER_DISTANCE = "vanilla.renderDistance";

	private ServerCap() {
	}

	public static Report apply(Report report, @Nullable ServerLimits live, @Nullable RulesDocument rules) {
		if (report == null || live == null || live.kind() == ServerLimits.Kind.SINGLEPLAYER || live.viewDistance() <= 0) {
			return report;
		}
		RulesDocument.SettingLabel label = rules == null || rules.settingLabels == null ? null : rules.settingLabels.get(RENDER_DISTANCE);
		List<Recommendation> out = new ArrayList<>(report.recommendations().size());
		boolean changed = false;
		for (Recommendation r : report.recommendations()) {
			Recommendation capped = cap(r, live.viewDistance(), label);
			changed |= capped != r;
			if (capped != null) {
				out.add(capped);
			}
		}
		if (!changed) {
			return report;
		}
		return new Report(report.hardware(), report.gpuClass(), report.tier(), report.goal(), List.copyOf(out), report.rulesRevision(),
				report.rulesSource(), report.online(), report.createdAt(), report.tierBasis());
	}

	// r itself when the limit doesn't touch it, a capped copy, or null when nothing is left to change.
	static @Nullable Recommendation cap(Recommendation r, int limit, RulesDocument.@Nullable SettingLabel label) {
		if (!(r.action() instanceof Action.SetSetting set) || !RENDER_DISTANCE.equals(set.key())) {
			return r;
		}
		BigDecimal current = SettingValues.number(set.currentValue());
		BigDecimal target = SettingValues.number(set.newValue());
		if (current == null || target == null || target.compareTo(current) <= 0) {
			return r;
		}
		BigDecimal capped = target.min(current.max(BigDecimal.valueOf(limit)));
		if (capped.compareTo(target) == 0) {
			return r;
		}
		if (capped.compareTo(current) == 0) {
			return null;
		}
		String value = SettingValues.format(capped);
		Text reason = Text.join(" ", r.reasonText(), Text.of("rigtune.server.cap_reason", "The server sends at most %s chunks.", limit));
		return Recommendation.of(r.id(), r.category(), r.impact(), SettingValues.describe(label, set.key(), set.currentValue(), value), reason,
				new Action.SetSetting(set.key(), set.currentValue(), value), r.selectedByDefault());
	}
}
