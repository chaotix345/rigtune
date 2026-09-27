package io.github.chaotix345.rigtune.core.stutter;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.profile.ShareKeys;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.Condition;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// docs/v0.5/SPEC.md 5 (C20), sf §2.2: one valid entry of the rules' stutterFixes section (RulesDocument.StutterFix). The
// updater validates the section (WS-R); the client checks it again, since remote rules are untrusted: an entry that fails
// any check is dropped alone (logged by its index), never the section. value: the fix's target in the key's ShareKeys
// spelling; else step (a whole number, 1 <= |step| <= MAX_STEP, INT keys only) toward its bound (min when negative, max
// when positive). The constants are the contracts' (WS-K), mirrored by tools/update_rules.py STUTTER_FIX_* and tied by
// SchemaConsistencyTest.
public record FixSpec(String adviceId, Condition evidence, String key, @Nullable String value, int step, @Nullable Integer min, @Nullable Integer max) {
	// The only feature a stutterFixes entry's `requires` may name; known to FixOffers, never to the main list or StutterAdvisor.
	public static final String FEATURE = "stutter-fix";
	// The allowlist: no fix can name another key (AC5.15).
	public static final Set<String> KEYS = Set.of("vanilla.renderDistance", "sodium.performance.chunk_build_defer_mode",
			"dh.common.multiThreading.numberOfThreads");
	// RulesDocument.StutterFix's and FixSet's fields.
	public static final Set<String> FIELDS = Set.of("requires", "adviceId", "evidence", "set");
	public static final Set<String> SET_FIELDS = Set.of("key", "value", "step", "min", "max");
	public static final int MAX_STEP = 8;

	// The valid entries, in the section's order; for an advice id only its first valid entry.
	public static List<FixSpec> of(@Nullable RulesDocument rules) {
		if (rules == null || rules.stutterFixes == null) {
			return List.of();
		}
		Set<String> adviceIds = new HashSet<>();
		if (rules.stutterAdvice != null) {
			rules.stutterAdvice.forEach(a -> adviceIds.add(a.id));
		}
		List<FixSpec> out = new ArrayList<>();
		Set<String> taken = new HashSet<>();
		for (int i = 0; i < rules.stutterFixes.size(); i++) {
			RulesDocument.StutterFix entry = rules.stutterFixes.get(i);
			if (!supported(entry.requires)) {
				continue;
			}
			List<String> problems = new ArrayList<>();
			FixSpec spec = parse(entry, adviceIds, problems);
			if (spec != null && !taken.add(spec.adviceId)) {
				problems.add("a second entry for the same advice");
				spec = null;
			}
			if (spec == null) {
				RigTune.LOGGER.warn("Ignoring stutterFixes entry {}: {}", i, String.join("; ", problems));
			} else {
				out.add(spec);
			}
		}
		return List.copyOf(out);
	}

	// requires must name stutter-fix and nothing this client doesn't support (a future fix type skips the entry).
	private static boolean supported(@Nullable List<String> requires) {
		return requires != null && requires.contains(FEATURE) && requires.stream().allMatch(f -> f != null && FixOffers.SUPPORTED_FEATURES.contains(f));
	}

	private static @Nullable FixSpec parse(RulesDocument.StutterFix entry, Set<String> adviceIds, List<String> problems) {
		if (entry.adviceId == null || !adviceIds.contains(entry.adviceId)) {
			problems.add("adviceId isn't a stutterAdvice id");
		}
		if (entry.evidence == null) {
			problems.add("no evidence");
		}
		RulesDocument.FixSet set = entry.set;
		ShareKeys.Key table = set == null ? null : ShareKeys.byKey(set.key);
		if (set == null || set.key == null || !KEYS.contains(set.key) || table == null) {
			problems.add("set.key isn't one of the allowed keys");
			return null;
		}
		boolean hasValue = present(set.value);
		boolean hasStep = present(set.step);
		String value = null;
		int step = 0;
		Integer min = present(set.min) ? whole(set.min) : null;
		Integer max = present(set.max) ? whole(set.max) : null;
		if (hasValue == hasStep) {
			problems.add("set needs a value or a step, not both");
		} else if (hasValue) {
			Integer wire = set.value.isJsonPrimitive() && SettingKeys.safeValue(set.value.getAsString()) ? table.encode(set.value.getAsString()) : null;
			if (wire == null) {
				problems.add("set.value isn't a value of the key");
			} else {
				value = table.decode(wire, 60);
			}
		} else {
			Integer n = whole(set.step);
			if (n == null || n == 0 || Math.abs(n) > MAX_STEP || table.kind() != ShareKeys.Kind.INT) {
				problems.add("set.step isn't a whole number from 1 to " + MAX_STEP + " on a whole-number key");
			} else if (present(set.min) && min == null || present(set.max) && max == null || n < 0 && min == null || n > 0 && max == null) {
				problems.add("set.step needs a whole-number bound in its direction");
			} else {
				step = n;
			}
		}
		if (!problems.isEmpty()) {
			return null;
		}
		return new FixSpec(entry.adviceId, entry.evidence, set.key, value, step, min, max);
	}

	private static boolean present(@Nullable JsonElement e) {
		return e != null && !e.isJsonNull();
	}

	// A JSON number that is a whole int; null for anything else (a string, a fraction, an object).
	private static @Nullable Integer whole(@Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
			return null;
		}
		return wholeNumber(p.getAsString());
	}

	private static @Nullable Integer wholeNumber(String text) {
		try {
			BigDecimal n = new BigDecimal(text.trim());
			return n.stripTrailingZeros().scale() > 0 ? null : n.intValueExact();
		} catch (NumberFormatException | ArithmeticException e) {
			return null;
		}
	}

	// Vanilla keys change now; a mod's config at the next restart (a staged patch op).
	public boolean now() {
		return key.startsWith(SettingKeys.VANILLA_PREFIX);
	}

	// The value the fix sets from `current`, or null when there's nothing to do: already the value; a step that can't move
	// in its direction (at or past its bound, or current isn't a whole number); target = clamp(current + step, bound, the
	// table's range).
	public @Nullable String target(@Nullable String current) {
		if (current == null || !SettingKeys.safeValue(current)) {
			return null;
		}
		if (value != null) {
			return SettingValues.same(current, value) ? null : value;
		}
		ShareKeys.Key table = ShareKeys.byKey(key);
		Integer now = wholeNumber(current);
		if (table == null || now == null) {
			return null;
		}
		long t = (long) now + step;
		t = step < 0 ? Math.max(t, min) : Math.min(t, max);
		t = Math.clamp(t, table.min(), table.max());
		if (step < 0 ? t >= now : t <= now) {
			return null;
		}
		return Long.toString(t);
	}
}
