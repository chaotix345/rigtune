package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.profile.ShareKeys;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// A try's verdict (docs/v0.5/SPEC.md 6, docs/research/v0.5/feature-try-it.md §2.6): the 1 % low and average changes
// between the pair's before and after runs against a noise floor, or no verdict when anything but the tried setting
// differs. A Measure has 2 repeats, whose CV can look like precision by luck, so the floor is never under 2 x MIN_CV
// (5 %), and never under the trend's floor when 3 or more earlier comparable runs exist; BenchmarkMath (0.4's Measure
// pair) is unchanged. Described, never explained (X3): the screen shows the numbers with every verdict.
public final class TryItVerdict {
	// AC6.16's A/A calibration may retune it (docs/v0.5/verification/try-it/).
	public static final double MIN_CV = 0.025;
	private static final double EPSILON = 1e-9;

	public enum Kind {
		// The 1 % lows moved by at least the floor.
		BETTER, WORSE,
		NO_CLEAR_CHANGE,
		// Something besides the tried setting differs (the causes): the numbers are for reference only.
		NOT_COMPARABLE,
		// A run has no result.
		NO_NUMBERS
	}

	// Why the runs can't be compared, in the order they're named: conditions (BenchmarkTrend's, in its order), the loaded
	// mods, History entries between the try's change and the after run (oldest first), managed settings (ShareKeys order).
	public sealed interface Cause {
		record Condition(Difference difference) implements Cause {
		}

		record Mods() implements Cause {
		}

		record Entry(String entryId, String kind) implements Cause {
		}

		record Setting(String key) implements Cause {
		}
	}

	// Lines under the verdict, in this order. NOISY: either run's CV over 5 %. WORLD_CONTENT: a setting whose effect the
	// benchmark world may hide, measured there. DH: Distant Horizons rendered or generated during a run. SESSIONS: the
	// runs were in different game sessions. SCENE: always ("measured in one scene").
	public enum Caveat {
		NOISY, WORLD_CONTENT, DH, SESSIONS, SCENE
	}

	// lowPercent/avgPercent: null only for NO_NUMBERS.
	public record Verdict(Kind kind, @Nullable Double lowPercent, @Nullable Double avgPercent, double floorPercent, List<Cause> causes,
			List<Caveat> caveats) {
		public Verdict {
			causes = List.copyOf(causes);
			caveats = List.copyOf(caveats);
		}
	}

	private TryItVerdict() {
	}

	// runs: benchmarks.json, oldest first (the trend's floor comes from the runs before `before`). entries: history.json,
	// oldest first.
	public static Verdict of(TryIt t, BenchmarkRecord before, BenchmarkRecord after, List<BenchmarkRecord> runs, List<JournalEntry> entries) {
		BenchmarkRecord.Result b = before.result();
		BenchmarkRecord.Result a = after.result();
		double floor = floorPercent(before, after, runs);
		List<Caveat> caveats = caveats(t, before, after);
		if (b == null || a == null) {
			return new Verdict(Kind.NO_NUMBERS, null, null, floor, List.of(), caveats);
		}
		double low = BenchmarkMath.gainPercent(b.onePercentLowFps(), a.onePercentLowFps());
		double avg = BenchmarkMath.gainPercent(b.avgFps(), a.avgFps());
		List<Cause> causes = causes(t, before, after, entries);
		Kind kind = !causes.isEmpty() ? Kind.NOT_COMPARABLE
				: low >= floor - EPSILON ? Kind.BETTER : low <= -floor + EPSILON ? Kind.WORSE : Kind.NO_CLEAR_CHANGE;
		return new Verdict(kind, low, avg, floor, causes, caveats);
	}

	// max(2 x max(cvBefore or 5 %, cvAfter or 5 %, MIN_CV), the trend's floor of the runs before `before` when there are
	// at least BenchmarkTrend.MIN_RUNS comparable ones), in percent.
	static double floorPercent(BenchmarkRecord before, BenchmarkRecord after, List<BenchmarkRecord> runs) {
		Double cvBefore = before.result() == null ? null : before.result().cv();
		Double cvAfter = after.result() == null ? null : after.result().cv();
		double floor = 2 * Math.max(Math.max(cvOrNoisy(cvBefore), cvOrNoisy(cvAfter)), MIN_CV) * 100;
		List<BenchmarkRecord> baseline = BenchmarkTrend.baseline(before, runs);
		if (baseline.size() >= BenchmarkTrend.MIN_RUNS) {
			double[] lows = baseline.stream().mapToDouble(r -> r.result().onePercentLowFps()).toArray();
			floor = Math.max(floor, BenchmarkTrend.noiseFloorPercent(cvBefore, lows));
		}
		return floor;
	}

	private static double cvOrNoisy(@Nullable Double cv) {
		return cv == null ? BenchmarkMath.NOISY_CV : cv;
	}

	private static List<Cause> causes(TryIt t, BenchmarkRecord before, BenchmarkRecord after, List<JournalEntry> entries) {
		List<Cause> out = new ArrayList<>();
		Set<Difference> allowed = Triable.allowed(t.key());
		for (Difference d : BenchmarkTrend.differences(before, after)) {
			if (!allowed.contains(d)) {
				out.add(new Cause.Condition(d));
			}
		}
		String hashBefore = before.context() == null ? null : before.context().modSetHash();
		String hashAfter = after.context() == null ? null : after.context().modSetHash();
		if (hashBefore != null && hashAfter != null && !hashBefore.equals(hashAfter)) {
			out.add(new Cause.Mods());
		}
		for (JournalEntry e : between(t.entryId(), after, entries)) {
			boolean undoOfTheTry = JournalEntry.UNDO.equals(e.kind()) && t.entryId().equals(e.undoOf());
			if (!undoOfTheTry && e.changes().stream().anyMatch(TryItVerdict::couldHaveTakenEffect)) {
				out.add(new Cause.Entry(e.id(), e.kind()));
			}
		}
		if (t.settingsAfter() != null) {
			for (String key : settingKeys(t.settingsBefore(), t.settingsAfter())) {
				if (!key.equals(t.key()) && !Triable.LIFTED.contains(key)
						&& !Objects.equals(t.settingsBefore().get(key), t.settingsAfter().get(key))) {
					out.add(new Cause.Setting(key));
				}
			}
		}
		return out;
	}

	// A change that was discarded or abandoned never took effect; a staged one counts, so the verdict doesn't flip once a
	// restart applies it.
	private static boolean couldHaveTakenEffect(JournalChange c) {
		return !JournalChange.DISCARDED.equals(c.status()) && !JournalChange.ABANDONED.equals(c.status());
	}

	// The entries after the try's own, up to the after run's journalCursor; by time (no later than the after run) when the
	// cursor is missing or no longer before it in the journal. Empty when the try's entry isn't there.
	private static List<JournalEntry> between(String entryId, BenchmarkRecord after, List<JournalEntry> entries) {
		int from = indexOf(entries, entryId);
		if (from < 0) {
			return List.of();
		}
		String cursor = after.context() == null ? null : after.context().journalCursor();
		int to = cursor == null ? -1 : indexOf(entries, cursor);
		if (to >= from) {
			return entries.subList(from + 1, to + 1);
		}
		Instant until = instant(after.createdAt());
		List<JournalEntry> out = new ArrayList<>();
		for (JournalEntry e : entries.subList(from + 1, entries.size())) {
			Instant at = instant(e.at());
			if (until != null && at != null && !at.isAfter(until)) {
				out.add(e);
			}
		}
		return out;
	}

	private static int indexOf(List<JournalEntry> entries, String id) {
		for (int i = 0; i < entries.size(); i++) {
			if (Objects.equals(entries.get(i).id(), id)) {
				return i;
			}
		}
		return -1;
	}

	// Both snapshots' keys, in ShareKeys' table order.
	private static List<String> settingKeys(Map<String, String> before, Map<String, String> after) {
		Set<String> out = new LinkedHashSet<>();
		for (ShareKeys.Key k : ShareKeys.V1) {
			if (before.containsKey(k.key()) || after.containsKey(k.key())) {
				out.add(k.key());
			}
		}
		return List.copyOf(out);
	}

	private static List<Caveat> caveats(TryIt t, BenchmarkRecord before, BenchmarkRecord after) {
		List<Caveat> out = new ArrayList<>();
		if (noisy(before) || noisy(after)) {
			out.add(Caveat.NOISY);
		}
		if (t.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD && Triable.sceneContent(t.key())) {
			out.add(Caveat.WORLD_CONTENT);
		}
		if (distantHorizons(before) || distantHorizons(after)) {
			out.add(Caveat.DH);
		}
		if (t.afterSession() != null ? !t.afterSession().equals(t.session()) : t.kind() == TryIt.Kind.RESTART) {
			out.add(Caveat.SESSIONS);
		}
		out.add(Caveat.SCENE);
		return out;
	}

	private static boolean noisy(BenchmarkRecord run) {
		return run.result() != null && BenchmarkMath.noisy(run.result().cv());
	}

	private static boolean distantHorizons(BenchmarkRecord run) {
		BenchmarkRecord.Context c = run.context();
		return c != null && (c.dhRendering() || Boolean.TRUE.equals(c.dhGenerating()));
	}

	private static @Nullable Instant instant(@Nullable String text) {
		if (text == null) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (DateTimeException e) {
			return null;
		}
	}
}
