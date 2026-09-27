package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

// docs/v0.5/SPEC.md 5 (C20), the main list's post-step (V05Hooks.afterRecommend, after ServerCap, never inside Recommender
// or settingTargets, so templates never see fixes): a SetSetting that would move an actively fixed key away from the fix's
// target is unticked, with a neutral reason (no claim about what changing it does to stutter, X3), so the main list doesn't
// undo the fix by default (r16 recommends Chunk Updates ONE_FRAME and render distance 12/16 on some tiers). Away: for a
// number, back toward or past the old value (further the same way is left alone); for any other value, anything but the
// target. holds() gives a hold only while the fix's change is in effect, and none once its comparison found more stutter
// (the main list may then recommend going back).
public final class FixHold {
	// The fix moved key from `from` to `to`, applied on appliedOn (yyyy-MM-dd).
	public record Hold(String key, String from, String to, String appliedOn) {
	}

	private FixHold() {
	}

	// The tracked fixes whose change is in effect: staged, measuring, compared (unless the verdict is MORE) or expired, a
	// dismissed one included (dismissing hides the block; the setting stays). Never an undone, not applied or replaced one.
	// appliedOn is the player's local day in zone.
	public static List<Hold> holds(List<FixTracker.Record> records, ZoneId zone) {
		List<Hold> out = new ArrayList<>();
		for (FixTracker.Record r : records) {
			boolean inEffect = switch (r.state()) {
				case STAGED, MEASURING, EXPIRED -> true;
				case COMPARED -> r.verdict() == null || r.verdict().kind() != FixComparison.Kind.MORE;
				case UNDONE, NOT_APPLIED, REPLACED -> false;
			};
			if (inEffect) {
				out.add(new Hold(r.key(), r.from(), r.to(), r.appliedAt().atZone(zone).toLocalDate().toString()));
			}
		}
		return out;
	}

	// The report itself when no recommendation is held.
	public static Report apply(Report report, List<Hold> holds) {
		if (report == null || holds.isEmpty()) {
			return report;
		}
		List<Recommendation> out = new ArrayList<>(report.recommendations().size());
		boolean changed = false;
		for (Recommendation r : report.recommendations()) {
			Recommendation held = hold(r, holds);
			changed |= held != r;
			out.add(held);
		}
		if (!changed) {
			return report;
		}
		return new Report(report.hardware(), report.gpuClass(), report.tier(), report.goal(), List.copyOf(out), report.rulesRevision(),
				report.rulesSource(), report.online(), report.createdAt(), report.tierBasis());
	}

	private static Recommendation hold(Recommendation r, List<Hold> holds) {
		if (!(r.action() instanceof Action.SetSetting set)) {
			return r;
		}
		for (Hold hold : holds) {
			if (hold.key().equals(set.key()) && away(set.newValue(), hold)) {
				Text reason = Text.join(" ", r.reasonText(), Text.of("rigtune.stutter.fix.hold_reason",
						"You set this on %s with the Stutter Doctor's fix; changing it here undoes that fix.", hold.appliedOn()));
				return Recommendation.of(r.id(), r.category(), r.impact(), r.titleText(), reason, r.action(), false);
			}
		}
		return r;
	}

	private static boolean away(String value, Hold hold) {
		if (SettingValues.same(value, hold.to())) {
			return false;
		}
		BigDecimal from = number(hold.from());
		BigDecimal to = number(hold.to());
		BigDecimal next = number(value);
		if (from == null || to == null || next == null || from.compareTo(to) == 0) {
			return true;
		}
		return next.compareTo(to) != to.compareTo(from);
	}

	private static @Nullable BigDecimal number(@Nullable String value) {
		if (value == null) {
			return null;
		}
		try {
			return new BigDecimal(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
