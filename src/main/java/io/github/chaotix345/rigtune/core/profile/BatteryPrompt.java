package io.github.chaotix345.rigtune.core.profile;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Predicate;

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

	// v0.5 PF-1: the profile the plug-in offer targets once Battery is switched to: the active one, else "My settings" (the
	// baseline, saved before the first switch), so a player who takes the offer with no profile active is offered it back
	// on AC. Null only without a baseline (profiles.json not writable).
	public static @Nullable String previousFor(@Nullable String active, @Nullable String baseline) {
		return active != null ? active : baseline;
	}

	// v0.5 PF-3: whether a pending offer still applies: its target still resolves (a deleted profile's plug-in offer is
	// retired, never "Switch back to ?") and isn't the active profile already.
	public static boolean stillOffered(Decision decision, @Nullable String active, Predicate<String> resolves) {
		String target = decision.target();
		return target != null && !target.equals(active) && resolves.test(target);
	}

	// v0.5 PF-2: only the unplug offer has "Don't offer again". On the plug-in offer it would also stop the unplug offer;
	// that one keeps only its × (a dismissal of that one offer).
	public static boolean offersSnooze(Offer offer) {
		return offer == Offer.BATTERY;
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
