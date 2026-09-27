package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// Launch-time regression alerts (docs/v0.5/SPEC.md 9, C18; research la §1-2): is the latest launch slower than usual?
// Pure, over startup-times.json's runs (oldest first). Comparable = the same MC version only: a RigTune or mod-set change
// is an explanation, not a grouping, and no Java version is stored, so none is claimed. The baseline is the newest
// MAX_RUNS comparable runs before the latest (Tools' "median of the last 10" window), from MIN_RUNS of them; the floor is
// BenchmarkTrend's (2 x max(5 %, 1.4826 x MAD / median), so at least 10 %), reusing its median and MAD. Launch time is
// noisier than a benchmark's 1 % lows (±7.5 % on one quiet PC, la §1.3), hence 5 runs rather than 3. MIN_RUNS and the
// floor are estimates, checked against real launches on the dev PC (AC9.8, docs/v0.5/verification/startup/).
public final class StartupTrend {
	public static final int MIN_RUNS = 5;
	public static final int MAX_RUNS = StartupTimesStore.MEDIAN_OF;
	private static final double EPSILON = 1e-9;

	public enum Kind {
		// No launch recorded.
		NO_RUN,
		// Fewer than MIN_RUNS comparable runs before the latest: nothing is claimed.
		TOO_FEW,
		// Within the floor of the median.
		IN_LINE,
		// Faster than the median by more than the floor (computed, never shown in v0.5).
		IMPROVEMENT,
		// Slower than the median by more than the floor.
		SLOWER
	}

	// What changed since the newest comparable run before the latest ("may be related to"): the first that matches only.
	public enum Cause {
		NONE, MOD_COUNT, MOD_SET, RIGTUNE_VERSION
	}

	// latest: the run assessed (null for NO_RUN). baselineRuns: the comparable runs before it (at most MAX_RUNS). medianMs,
	// deltaPercent (+ = slower) and floorPercent: from MIN_RUNS on. previous: the newest comparable run before the latest,
	// which the cause compares with (null for TOO_FEW).
	public record Assessment(Kind kind, @Nullable Run latest, int baselineRuns, @Nullable Double medianMs, @Nullable Double deltaPercent,
			@Nullable Double floorPercent, Cause cause, @Nullable Run previous) {
		public boolean slower() {
			return kind == Kind.SLOWER;
		}
	}

	private StartupTrend() {
	}

	// The comparable runs before the last one, the newest MAX_RUNS, oldest first.
	public static List<Run> baseline(List<Run> runs) {
		if (runs.isEmpty()) {
			return List.of();
		}
		Run latest = runs.getLast();
		List<Run> out = new ArrayList<>();
		for (Run r : runs.subList(0, runs.size() - 1)) {
			if (latest.mcVersion() != null && latest.mcVersion().equals(r.mcVersion())) {
				out.add(r);
			}
		}
		return List.copyOf(out.subList(Math.max(0, out.size() - MAX_RUNS), out.size()));
	}

	public static Assessment assess(List<Run> runs) {
		if (runs.isEmpty()) {
			return new Assessment(Kind.NO_RUN, null, 0, null, null, null, Cause.NONE, null);
		}
		Run latest = runs.getLast();
		List<Run> baseline = baseline(runs);
		if (baseline.size() < MIN_RUNS) {
			return new Assessment(Kind.TOO_FEW, latest, baseline.size(), null, null, null, Cause.NONE, null);
		}
		double[] ms = baseline.stream().mapToDouble(Run::ms).toArray();
		double median = BenchmarkTrend.median(ms);
		double floor = BenchmarkTrend.noiseFloorPercent(null, ms);
		double delta = (latest.ms() - median) * 100 / median;
		Kind kind = delta > floor + EPSILON ? Kind.SLOWER : delta < -floor - EPSILON ? Kind.IMPROVEMENT : Kind.IN_LINE;
		Run previous = baseline.getLast();
		return new Assessment(kind, latest, baseline.size(), median, delta, floor, cause(previous, latest), previous);
	}

	// Mod count, then the mod-set hash, then RigTune's version; a value either run didn't record claims nothing.
	private static Cause cause(Run previous, Run latest) {
		if (previous.mods() > 0 && latest.mods() > 0 && previous.mods() != latest.mods()) {
			return Cause.MOD_COUNT;
		}
		if (differ(previous.modSetHash(), latest.modSetHash())) {
			return Cause.MOD_SET;
		}
		if (differ(previous.rigtuneVersion(), latest.rigtuneVersion())) {
			return Cause.RIGTUNE_VERSION;
		}
		return Cause.NONE;
	}

	private static boolean differ(@Nullable String a, @Nullable String b) {
		return a != null && b != null && !a.isBlank() && !b.isBlank() && !Objects.equals(a, b);
	}

	// The notice's message and Tools' first regression row: "Launch time 45% higher than usual (21.3 s vs your usual ~14.7 s)".
	public static Text regression(Assessment a) {
		return Text.of("rigtune.startup.regression", "Launch time %s%% higher than usual (%s s vs your usual ~%s s)",
				a.deltaPercent() == null ? "?" : Long.toString(Math.round(a.deltaPercent())), a.latest() == null ? "?" : seconds(a.latest().ms()),
				a.medianMs() == null ? "?" : seconds(a.medianMs()));
	}

	// The one cause line (la §1.4): never two guesses.
	public static Text cause(Assessment a) {
		Run previous = a.previous();
		Run latest = a.latest();
		return switch (a.cause()) {
			case MOD_COUNT -> Text.of("rigtune.startup.regression.mod_count_changed", "May be related to your mod set changing (%s → %s mods) since your last launch",
					previous == null ? "?" : previous.mods(), latest == null ? "?" : latest.mods());
			case MOD_SET -> Text.of("rigtune.startup.regression.mod_set_changed", "May be related to your mod set changing since your last launch");
			case RIGTUNE_VERSION -> Text.of("rigtune.startup.regression.rigtune_changed", "RigTune %s → %s since your last launch",
					previous == null ? "?" : previous.rigtuneVersion(), latest == null ? "?" : latest.rigtuneVersion());
			case NONE -> Text.of("rigtune.startup.regression.no_change", "No change recorded since your last launch; possibly another program running, a cold "
					+ "disk cache, or a driver/OS update");
		};
	}

	private static String seconds(double ms) {
		return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
	}
}
