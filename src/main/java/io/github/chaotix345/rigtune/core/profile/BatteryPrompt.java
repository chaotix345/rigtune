package io.github.chaotix345.rigtune.core.profile;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

// When to OFFER a profile on a power change (docs/v0.4/SPEC.md 4, AC4.9). RigTune never switches by itself: this only
// decides whether the notice and the toast appear. An AC -> battery edge offers Battery when offers are on, not snoozed
// ("Don't offer again"), none was shown in the last COOLDOWN, no benchmark runs and Battery isn't already active. A
// battery -> AC edge offers the profile that was active before Battery, while Battery is still active.
public final class BatteryPrompt {
	public static final Duration COOLDOWN = Duration.ofMinutes(10);
	public static final int DEBOUNCE_POLLS = 2;
	public static final String BATTERY = ProfileStore.TEMPLATE_PREFIX + ProfileTemplates.TemplateId.BATTERY.id();

	public enum Offer { NONE, BATTERY, PREVIOUS }

	// target: the profile id to switch to ("template:battery" or the previous profile's id), null for NONE.
	public record Decision(Offer offer, @Nullable String target) {
		static final Decision NONE = new Decision(Offer.NONE, null);
	}

	private BatteryPrompt() {
	}

	public static Decision onEdge(boolean nowOnBattery, ProfileStore.Battery state, @Nullable String active, boolean benchmarkRunning, Instant now) {
		if (!state.prompt() || state.snoozed() || benchmarkRunning) {
			return Decision.NONE;
		}
		if (nowOnBattery) {
			if (BATTERY.equals(active) || recent(state.lastPromptAt(), now)) {
				return Decision.NONE;
			}
			return new Decision(Offer.BATTERY, BATTERY);
		}
		if (!BATTERY.equals(active) || state.previousProfile() == null || BATTERY.equals(state.previousProfile())) {
			return Decision.NONE;
		}
		return new Decision(Offer.PREVIOUS, state.previousProfile());
	}

	private static boolean recent(@Nullable String lastPromptAt, Instant now) {
		if (lastPromptAt == null) {
			return false;
		}
		try {
			Instant last = Instant.parse(lastPromptAt);
			return last.isAfter(now.minus(COOLDOWN)) && !last.isAfter(now.plus(Duration.ofMinutes(1)));
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	// A power state counts once DEBOUNCE_POLLS polls in a row agree, so a wiggled plug doesn't flap. The first poll only sets
	// the baseline (the state at startup is not an edge).
	public static final class Debouncer {
		private @Nullable Boolean confirmed;
		private boolean candidate;
		private int streak;

		public Debouncer(@Nullable Boolean initial) {
			this.confirmed = initial;
		}

		// The newly confirmed state when this poll completes an edge, else null.
		public synchronized @Nullable Boolean poll(boolean onBattery) {
			if (confirmed == null) {
				confirmed = onBattery;
				return null;
			}
			if (onBattery == confirmed) {
				streak = 0;
				return null;
			}
			if (streak == 0 || candidate != onBattery) {
				candidate = onBattery;
				streak = 1;
			} else {
				streak++;
			}
			if (streak >= DEBOUNCE_POLLS) {
				confirmed = onBattery;
				streak = 0;
				return onBattery;
			}
			return null;
		}

		public synchronized @Nullable Boolean confirmed() {
			return confirmed;
		}
	}
}
