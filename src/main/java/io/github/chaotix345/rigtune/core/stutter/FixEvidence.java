package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.rules.Truth;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// docs/v0.5/SPEC.md 5 (C20): the rules' causeSpikesAtLeast key, a fix's evidence that an aggregate share can't give (one
// huge spike can carry a share; it can't carry a count). A spike counts for a cause when that cause claimed at least half of
// the spike's lost time; "unknown" counts the spikes whose unexplained part was at least half. The vocabulary is
// stutterShareAtLeast's (Attributor.CAUSES). Under SD-1's ring limits a cause whose evidence rotated out simply doesn't
// count: an undercount, the conservative direction. A spike tagged settingsChanged (RW-11: right after a settings change
// or a resource reload) never counts: it follows the change, not the cause a fix would address.
public final class FixEvidence {
	private FixEvidence() {
	}

	// cause -> the spikes it dominated; causes that dominated none are absent.
	public static Map<String, Integer> dominatedSpikes(List<Attributor.Attribution> attributions) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Attributor.Attribution a : attributions) {
			long lost = a.spike().lost();
			if (lost <= 0 || a.tags().contains(Attributor.SETTINGS_CHANGED)) {
				continue;
			}
			a.claims().forEach((cause, ns) -> {
				if (ns != null && ns * 2 >= lost) {
					out.merge(cause, 1, Integer::sum);
				}
			});
			if (a.unexplained() * 2 >= lost) {
				out.merge(Attributor.UNKNOWN, 1, Integer::sum);
			}
		}
		return out;
	}

	// Every entry (cause -> whole number) must hold: at least that many dominated spikes. A cause this version doesn't know
	// or the capture couldn't measure, and a value that isn't a whole number >= 0, are UNKNOWN (fail closed, also under
	// `not`).
	public static Truth causeSpikesAtLeast(Map<String, String> wanted, Map<String, Integer> counts, Set<String> unmeasured) {
		Truth t = Truth.TRUE;
		for (Map.Entry<String, String> entry : wanted.entrySet()) {
			Integer threshold = wholeNumber(entry.getValue());
			String cause = entry.getKey();
			if (cause == null || !Attributor.CAUSES.contains(cause) || threshold == null || unmeasured.contains(cause)) {
				t = t.and(Truth.UNKNOWN);
				continue;
			}
			t = t.and(Truth.of(counts.getOrDefault(cause, 0) >= threshold));
		}
		return t;
	}

	private static @Nullable Integer wholeNumber(@Nullable String text) {
		if (text == null) {
			return null;
		}
		try {
			BigDecimal value = new BigDecimal(text.trim());
			return value.signum() < 0 || value.stripTrailingZeros().scale() > 0 ? null : value.intValueExact();
		} catch (NumberFormatException | ArithmeticException e) {
			return null;
		}
	}
}
