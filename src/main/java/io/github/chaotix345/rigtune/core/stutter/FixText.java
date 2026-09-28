package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

// docs/v0.5/SPEC.md 5 (C20), sf §2.7: what the Stutter Doctor says about its one-click fixes, built in core so each line
// is tested. X3: numbers are shown next to every verdict, a comparison is "measured comparison, not proof", "more" may be
// unrelated, and nothing says a fix fixed, caused or proves anything (WordingTest's rigtune.stutter.fix. list). labels: how
// History names settings and values (RigTuneController.settingLabels); dates are the player's local day, yyyy-MM-dd.
public final class FixText {
	private FixText() {
	}

	// "Render distance: 12 → 10".
	public static Text change(HistoryModel.Labels labels, String key, String from, String to) {
		return Text.of("rigtune.rec.setting.title", "%s: %s → %s", labels.label(key), labels.value(key, from), labels.value(key, to));
	}

	public static Text offer(Text change) {
		return Text.of("rigtune.stutter.fix.offer", "Try it in one click: %s", change);
	}

	// review-12 R12STUTTER-6: the two steps, and why the first one (one more session as it is).
	public static Text takesEffect(boolean now) {
		return now
				? Text.of("rigtune.stutter.fix.offer.now",
						"First RigTune measures one more session as it is (a bad one alone would make any change look good); then you apply the change here."
								+ " It takes effect at once, you can undo it in History, and RigTune compares your play before and after.")
				: Text.of("rigtune.stutter.fix.offer.restart",
						"First RigTune measures one more session as it is (a bad one alone would make any change look good); then you apply the change here."
								+ " It takes effect after you restart Minecraft, you can undo it in History, and RigTune compares your play before and after.");
	}

	public static Text profileNote(Text profileName) {
		return Text.of("rigtune.stutter.fix.offer.profile", "Your active profile, %s, also sets this; switching profiles later changes it again.",
				profileName);
	}

	public static Text tryButton() {
		return Text.of("rigtune.stutter.fix.try", "Try this fix…");
	}

	public static Text tryNarration(Text change) {
		return Text.of("rigtune.stutter.fix.try.narration", "Try this fix: %s. RigTune measures one more session as it is first.", change);
	}

	// The one line under an advice whose fix isn't offered (yet).
	public static Text notYet(FixOffer.NotYet n) {
		List<String> a = n.args();
		return switch (n.reason()) {
			case LENGTH -> Text.of("rigtune.stutter.fix.not_yet.length",
					"A one-click fix needs more play to compare: at least %s and %s hitches after a session's first 3 minutes (this one: %s, %s).", arg(a, 0),
					arg(a, 1), arg(a, 2), arg(a, 3));
			case EVIDENCE -> Text.of("rigtune.stutter.fix.not_yet.evidence",
					"The measurements don't point at this clearly enough for a one-click fix; the advice above still applies.");
			case BENCHMARK -> Text.of("rigtune.stutter.fix.not_yet.benchmark", "One-click fixes are offered for your own play sessions, not for benchmark runs.");
			case EXCLUDED -> Text.of("rigtune.stutter.fix.not_yet.excluded",
					"This session ran around a benchmark, or Distant Horizons generated terrain in it (or RigTune couldn't tell), so it can't be compared."
							+ " Play a session without either.");
			case CHANGED -> Text.of("rigtune.stutter.fix.not_yet.changed",
					"Settings or the window changed during this session (or RigTune couldn't tell), so it can't be compared. Play a session without changes.");
			case IDLE -> Text.of("rigtune.stutter.fix.not_yet.idle",
					"This session was idle (throttled) longer than it was played, so it can't be compared. Play a session without long breaks.");
			case SERVER -> Text.of("rigtune.stutter.fix.not_yet.server", "This server sends at most %s chunks, so a shorter render distance would change nothing here.",
					arg(a, 0));
			case BUSY -> Text.of("rigtune.stutter.fix.not_yet.busy", "Another fix is still being measured. Wait for its comparison or dismiss it first.");
			case STORE -> Text.of("rigtune.stutter.fix.not_yet.store",
					"RigTune can't keep track of fixes right now (config/rigtune/stutter-fixes.json can't be written).");
		};
	}

	private static String arg(List<String> args, int i) {
		return i < args.size() ? args.get(i) : "?";
	}

	public static Text previewSubtitle(String adviceTitle) {
		return Text.of("rigtune.stutter.fix.preview.subtitle", "Stutter fix for: %s", Text.literal(adviceTitle));
	}

	public static Text previewApply() {
		return Text.of("rigtune.stutter.fix.preview.apply", "Apply fix");
	}

	// The Apply's reason (History and the preview show it).
	public static Text reason(String adviceTitle) {
		return Text.of("rigtune.stutter.fix.reason", "Stutter Doctor: %s", Text.literal(adviceTitle));
	}

	// After Try this fix… (review-12 R12STUTTER-6): the baseline session starts now.
	public static Text baselineStarted() {
		return Text.of("rigtune.stutter.fix.status.baseline", "Measuring your play as it is. Play at least %s with the Stutter Doctor on (a session's"
				+ " first 3 minutes don't count), then leave the world; \"Apply the fix…\" then appears here.", StutterSummary.clock(FixGate.MIN_GAMEPLAY_SECONDS));
	}

	public static Text applyButton() {
		return Text.of("rigtune.stutter.fix.apply", "Apply the fix…");
	}

	public static Text applyNarration(Text change) {
		return Text.of("rigtune.stutter.fix.apply.narration", "Apply the fix: %s. Opens a preview first.", change);
	}

	// After Apply. afterSeconds: how much play the comparison needs.
	public static Text applied(boolean now, double afterSeconds) {
		String play = StutterSummary.clock(afterSeconds);
		return now
				? Text.of("rigtune.stutter.fix.status.applied",
						"Fix applied. Keep the Stutter Doctor on and play at least %s after a session's first 3 minutes; RigTune then compares that play with"
								+ " your play before.", play)
				: Text.of("rigtune.stutter.fix.status.staged",
						"Fix staged: it takes effect after you restart Minecraft. Then play at least %s with the Stutter Doctor on (a session's first 3"
								+ " minutes don't count).", play);
	}

	// Apply while the analysis the offer came from or the tracked fixes aren't there (yet).
	public static Text later() {
		return Text.of("rigtune.stutter.fix.status.later", "The Stutter Doctor isn't ready for this yet. Try again in a moment.");
	}

	public static Text gone() {
		return Text.of("rigtune.stutter.fix.status.gone", "This fix can't be applied now: the setting changed since the analysis. Look again in a moment.");
	}

	public static Text heading() {
		return Text.of("rigtune.stutter.fix.heading", "Your stutter fix");
	}

	// "Render distance: 12 → 10, applied 2026-10-02" ("chosen" before it's applied).
	public static Text applied(HistoryModel.Labels labels, FixTracker.Record r, ZoneId zone) {
		return r.neverApplied()
				? Text.of("rigtune.stutter.fix.change.chosen", "%s, chosen %s", change(labels, r.key(), r.from(), r.to()), day(r.appliedAt(), zone))
				: Text.of("rigtune.stutter.fix.change", "%s, applied %s", change(labels, r.key(), r.from(), r.to()), day(r.appliedAt(), zone));
	}

	// The local day; "?" for an instant outside the zone's range (a hand edit), as TrendText.date does.
	static String day(Instant at, ZoneId zone) {
		try {
			return at.atZone(zone).toLocalDate().toString();
		} catch (DateTimeException e) {
			return "?";
		}
	}

	// Where the fix is: waiting for a restart, measuring (play so far of the play needed), or how it ended. A compared fix's
	// line is its verdict. monitorOn: the Stutter Doctor's monitor is on (measuring needs it).
	public static Text state(HistoryModel.Labels labels, FixTracker.Record r, boolean monitorOn) {
		return switch (r.state()) {
			case BASELINE -> monitorOn
					? Text.of("rigtune.stutter.fix.state.baseline", "Nothing has changed yet: RigTune measures your play as it is first. Play one session of"
							+ " at least %s with the Stutter Doctor on (its first 3 minutes don't count), then leave the world.",
							StutterSummary.clock(FixGate.MIN_GAMEPLAY_SECONDS))
					: Text.of("rigtune.stutter.fix.state.monitor_off", "Turn the Stutter Doctor on and play to compare.");
			case READY -> Text.of("rigtune.stutter.fix.state.ready", "Your play as it is: %s over %s. Apply the change to compare it with your play after.",
					rate(r.before().gameplaySeconds() > 0 ? r.before().hitches() * 60 / r.before().gameplaySeconds() : 0),
					StutterSummary.clock(r.before().gameplaySeconds()));
			case STAGED -> Text.of("rigtune.stutter.fix.state.staged", "Waiting for a restart: the change takes effect when Minecraft starts again.");
			case MEASURING -> monitorOn
					? Text.of("rigtune.stutter.fix.state.measuring", "Measuring: %s of %s played with the Stutter Doctor on (a session's first 3 minutes"
							+ " don't count).",
							StutterSummary.clock(r.after() == null ? 0 : r.after().gameplaySeconds()), StutterSummary.clock(FixTracker.afterTarget(r.before())))
					: Text.of("rigtune.stutter.fix.state.monitor_off", "Turn the Stutter Doctor on and play to compare.");
			case COMPARED -> r.verdict() == null ? Text.of("rigtune.stutter.fix.state.expired", "No comparable play in time, so there's no comparison.")
					: verdict(r.verdict());
			case UNDONE -> Text.of("rigtune.stutter.fix.state.undone", "You undid this change.");
			case NOT_APPLIED -> Text.of("rigtune.stutter.fix.state.not_applied", "The change wasn't applied (it was discarded, or the file couldn't be changed).");
			case REPLACED -> Text.of("rigtune.stutter.fix.state.replaced", "%s was changed again since, so this comparison stopped.",
					labels.label(r.key()));
			case EXPIRED -> r.neverApplied()
					? Text.of("rigtune.stutter.fix.state.never_applied", "Never applied: no session as it is was measured in time, so nothing changed.")
					: Text.of("rigtune.stutter.fix.state.expired", "No comparable play in time, so there's no comparison.");
		};
	}

	// "Your last session didn't count: it had less than 2 minutes of play after its first 3 minutes.", or null when the last
	// session counted.
	public static @Nullable Text skipped(HistoryModel.Labels labels, FixTracker.Record r) {
		FixTracker.Skip skip = r.lastSkip();
		if (skip == null || !r.state().tracking()) {
			return null;
		}
		return Text.of("rigtune.stutter.fix.state.skipped", "Your last session didn't count: %s.", skip(labels, skip));
	}

	static Text skip(HistoryModel.Labels labels, FixTracker.Skip skip) {
		List<String> a = skip.args();
		if (FixTracker.SHORT.equals(skip.reason())) {
			return Text.of("rigtune.stutter.fix.skip.short", "it had less than 2 minutes of play after its first 3 minutes");
		}
		if (FixTracker.EXCLUDED.equals(skip.reason())) {
			return Text.of("rigtune.stutter.fix.skip.excluded", "it ran around a benchmark, or Distant Horizons generated terrain in it (or RigTune couldn't tell)");
		}
		if (FixTracker.CHANGED.equals(skip.reason())) {
			return Text.of("rigtune.stutter.fix.skip.changed", "a setting changed while it ran");
		}
		if (FixTracker.SHORT_BEFORE.equals(skip.reason())) {
			return Text.of("rigtune.stutter.fix.skip.short_before", "it had less than %s of play after its first 3 minutes",
					StutterSummary.clock(FixGate.MIN_GAMEPLAY_SECONDS));
		}
		if (FixTracker.UNREAD.equals(skip.reason())) {
			return Text.of("rigtune.stutter.fix.skip.unread", "RigTune couldn't read %s at its start or end", labels.label(arg(a, 0)));
		}
		if (FixTracker.IDLE.equals(skip.reason())) {
			return Text.of("rigtune.stutter.fix.skip.idle", "it was idle (throttled) longer than it was played");
		}
		FixConditions.Reason reason = FixConditions.Reason.of(skip.reason());
		if (reason == null) {
			return Text.of("rigtune.stutter.fix.skip.other", "it was played under other conditions");
		}
		return switch (reason) {
			case VERSION -> Text.of("rigtune.stutter.fix.skip.version", "the Minecraft version changed");
			case MODS -> Text.of("rigtune.stutter.fix.skip.mods", "the mods changed");
			case MEMORY -> Text.of("rigtune.stutter.fix.skip.memory", "the memory or the garbage collector changed");
			case DISPLAY -> Text.of("rigtune.stutter.fix.skip.display", "the window size or fullscreen changed");
			case GRAPHICS -> Text.of("rigtune.stutter.fix.skip.graphics", "it ran on another graphics API (OpenGL or Vulkan) or GPU");
			case WORLD -> Text.of("rigtune.stutter.fix.skip.world", "it was in another kind of world (singleplayer, LAN, Realm or server)");
			case MEASUREMENT -> Text.of("rigtune.stutter.fix.skip.measurement", "the Stutter Doctor could measure less than before");
			case SETTING -> {
				String key = arg(a, 0);
				yield Text.of("rigtune.stutter.fix.skip.setting", "%s changed (%s → %s)", labels.label(key), labels.value(key, arg(a, 1)),
						labels.value(key, arg(a, 2)));
			}
		};
	}

	public static Text before() {
		return Text.of("rigtune.stutter.fix.before", "Before");
	}

	public static Text after() {
		return Text.of("rigtune.stutter.fix.after", "After");
	}

	// A Before/After bar's value: "4.8 hitches a minute".
	public static Text rate(double hitchesPerMinute) {
		return Text.of("rigtune.stutter.fix.rate", "%s hitches a minute", decimal(hitchesPerMinute));
	}

	// Under the bars: "Time lost to stutter: 310 ms a minute before, 80 ms after."
	public static Text lost(FixComparison.Verdict v) {
		return Text.of("rigtune.stutter.fix.lost", "Time lost to stutter: %s ms a minute before, %s ms after.", whole(v.lostBeforePerMinute()),
				whole(v.lostAfterPerMinute()));
	}

	// Every verdict names both rates; "more" leaves the Undo to the player. "No clear change" says why (STUTTER-5).
	public static Text verdict(FixComparison.Verdict v) {
		String after = decimal(v.afterPerMinute());
		String before = decimal(v.beforePerMinute());
		return switch (v.kind()) {
			case LESS -> Text.of("rigtune.stutter.fix.verdict.less",
					"Less stutter after the change: %s hitches a minute (was %s). Play sessions differ, so this is a measured comparison, not proof.", after,
					before);
			case SAME -> v.fewerHitchesMoreLost()
					? Text.of("rigtune.stutter.fix.verdict.same_more_lost",
							"No clear improvement: %s hitches a minute (was %s), but more time lost to stutter (%s ms a minute, was %s). Keep the change or undo it.",
							after, before, whole(v.lostAfterPerMinute()), whole(v.lostBeforePerMinute()))
					: v.clearButSmall()
					? Text.of("rigtune.stutter.fix.verdict.same_small",
							"A small change: %s hitches a minute (was %s). Measurable, but too small to call better or worse. Keep the change or undo it.", after,
							before)
					: Text.of("rigtune.stutter.fix.verdict.same",
							"No clear change: %s hitches a minute (was %s). The difference is within how much play sessions vary. Keep the change or undo it.",
							after, before);
			case MORE -> Text.of("rigtune.stutter.fix.verdict.more",
					"More stutter after the change: %s hitches a minute (was %s). It may be unrelated, since sessions vary; if it stays worse, undo the change.",
					after, before);
		};
	}

	public static Text undo() {
		return Text.of("rigtune.stutter.fix.undo", "Undo this change…");
	}

	public static Text dismiss() {
		return Text.of("rigtune.stutter.fix.dismiss", "Dismiss");
	}

	public static Text dismissTooltip() {
		return Text.of("rigtune.stutter.fix.dismiss.tooltip", "Hides this comparison. The setting stays as it is.");
	}

	static String decimal(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	static String whole(double value) {
		return String.format(Locale.ROOT, "%,d", Math.round(value));
	}
}
