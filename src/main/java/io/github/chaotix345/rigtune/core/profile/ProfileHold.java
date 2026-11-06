package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// #45, the main list's post-step after FixHold (V05Hooks.afterRecommend): while a profile the player switched to stands,
// a SetSetting that would move a key the switch set away from the profile's value is unticked, with a reason naming the
// profile, so the next Apply doesn't quietly undo it (Max FPS's uncapped frame rate against the refresh-rate cap).
// "My settings" holds nothing (ProfileService.hold), so switching back to it brings the usual ticks back.
public final class ProfileHold {
	// The switch set key to value. staged: it takes effect at the next restart, so the files still read the old value;
	// any other hold stands only while the key's current value is the profile's (changed since, nothing is held).
	public record Hold(String key, String value, boolean staged) {
	}

	public record Active(Text profile, List<Hold> holds) {
		public Active {
			holds = List.copyOf(holds);
		}
	}

	private ProfileHold() {
	}

	// The setting changes of the switch's journal entry that are applied or staged.
	public static List<Hold> holds(@Nullable JournalEntry entry) {
		if (entry == null) {
			return List.of();
		}
		List<Hold> out = new ArrayList<>();
		for (JournalChange c : entry.changes()) {
			boolean staged = JournalChange.STAGED.equals(c.status());
			if (c.isSetting() && c.key() != null && c.after() != null && (staged || JournalChange.APPLIED.equals(c.status()))) {
				out.add(new Hold(c.key(), c.after(), staged));
			}
		}
		return out;
	}

	// The report itself when nothing is held.
	public static Report apply(Report report, @Nullable Active active) {
		if (report == null || active == null || active.holds().isEmpty()) {
			return report;
		}
		List<Recommendation> out = new ArrayList<>(report.recommendations().size());
		boolean changed = false;
		for (Recommendation r : report.recommendations()) {
			Recommendation held = hold(r, active);
			changed |= held != r;
			out.add(held);
		}
		if (!changed) {
			return report;
		}
		return new Report(report.hardware(), report.gpuClass(), report.tier(), report.goal(), List.copyOf(out), report.rulesRevision(),
				report.rulesSource(), report.online(), report.createdAt(), report.tierBasis());
	}

	private static Recommendation hold(Recommendation r, Active active) {
		if (!(r.action() instanceof Action.SetSetting set)) {
			return r;
		}
		for (Hold hold : active.holds()) {
			if (hold.key().equals(set.key()) && (hold.staged() || SettingValues.same(set.currentValue(), hold.value()))
					&& !SettingValues.same(set.newValue(), hold.value())) {
				Text reason = Text.join(" ", r.reasonText(), Text.of("rigtune.profile.hold_reason",
						"Your %s profile sets this on purpose, so it starts unticked; switch to My settings for RigTune's usual suggestions.",
						active.profile()));
				return Recommendation.of(r.id(), r.category(), r.impact(), r.titleText(), reason, r.action(), false);
			}
		}
		return r;
	}
}
