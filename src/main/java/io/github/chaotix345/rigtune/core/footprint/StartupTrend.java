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
// floor are estimates, checked against real launches (AC9.8, docs/v0.5/verification/startup/).
// RW-19 (SPEC amendment): vanilla's crash-report setup (2L's preloadMs) is 0-2 s warm and 5-9 s cold on a PC with Windows'
// performance counters off, which widened the floor past a real +34-39 % slowdown; when the latest run and at least
// MIN_RUNS runs of the window recorded it, the comparison leaves it out. Review H1: a slow streak is one regression: while
// the previous comparable launch was SLOWER too, the streak's first launch gives the key and the cause.
public final class StartupTrend {
	public static final int MIN_RUNS = 5;
	public static final int MAX_RUNS = StartupTimesStore.MEDIAN_OF;
	// The notice's key and awareness.json's acknowledgement (acknowledgedStartupRegressions): one per slow streak, by the
	// time of its first launch.
	public static final String KEY_PREFIX = "startup.regression.";
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

	// What changed since the newest comparable run before the slow streak's first launch ("may be related to"): the first
	// that matches only.
	public enum Cause {
		NONE, MOD_COUNT, MOD_SET, RIGTUNE_VERSION
	}

	// latest: the run assessed (null for NO_RUN). baselineRuns: the comparable runs compared with (at most MAX_RUNS).
	// medianMs: their median as compared (without the crash-report setup when preloadSubtracted); rawMedianMs: their median
	// launch to title. deltaPercent (+ = slower) and floorPercent: from MIN_RUNS on. first: the slow streak's first launch
	// (the latest when it is the only one; null unless SLOWER), streak: how many comparable launches in a row, ending with
	// the latest, were SLOWER. cause and previous: first's cause, against the newest comparable run before it.
	public record Assessment(Kind kind, @Nullable Run latest, int baselineRuns, @Nullable Double medianMs, @Nullable Double rawMedianMs,
			boolean preloadSubtracted, @Nullable Double deltaPercent, @Nullable Double floorPercent, Cause cause, @Nullable Run previous,
			@Nullable Run first, int streak) {
		public boolean slower() {
			return kind == Kind.SLOWER;
		}

		// The latest launch as compared.
		public @Nullable Double latestMs() {
			return latest == null ? null : (double) value(latest, preloadSubtracted);
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
		Assessment latest = assessOne(runs);
		if (!latest.slower()) {
			return latest;
		}
		Assessment first = latest;
		int streak = 1;
		List<Run> upTo = runs;
		for (int i = previousComparable(upTo); i >= 0; i = previousComparable(upTo)) {
			upTo = upTo.subList(0, i + 1);
			Assessment before = assessOne(upTo);
			if (!before.slower()) {
				break;
			}
			first = before;
			streak++;
		}
		return new Assessment(latest.kind(), latest.latest(), latest.baselineRuns(), latest.medianMs(), latest.rawMedianMs(), latest.preloadSubtracted(),
				latest.deltaPercent(), latest.floorPercent(), first.cause(), first.previous(), first.latest(), streak);
	}

	// The latest run against its own baseline, without looking at a streak.
	private static Assessment assessOne(List<Run> runs) {
		if (runs.isEmpty()) {
			return new Assessment(Kind.NO_RUN, null, 0, null, null, false, null, null, Cause.NONE, null, null, 0);
		}
		Run latest = runs.getLast();
		List<Run> window = baseline(runs);
		if (window.size() < MIN_RUNS) {
			return new Assessment(Kind.TOO_FEW, latest, window.size(), null, null, false, null, null, Cause.NONE, null, null, 0);
		}
		List<Run> withPreload = window.stream().filter(StartupTrend::hasPreload).toList();
		boolean subtract = hasPreload(latest) && withPreload.size() >= MIN_RUNS;
		List<Run> used = subtract ? withPreload : window;
		double median = BenchmarkTrend.median(values(used, subtract));
		double floor = BenchmarkTrend.noiseFloorPercent(null, values(used, subtract));
		double delta = (value(latest, subtract) - median) * 100 / median;
		Kind kind = delta > floor + EPSILON ? Kind.SLOWER : delta < -floor - EPSILON ? Kind.IMPROVEMENT : Kind.IN_LINE;
		Run previous = window.getLast();
		boolean slower = kind == Kind.SLOWER;
		return new Assessment(kind, latest, used.size(), median, BenchmarkTrend.median(values(used, false)), subtract, delta, floor, cause(previous, latest),
				previous, slower ? latest : null, slower ? 1 : 0);
	}

	// A preload time the player didn't break by editing the file: at least 0 and less than the launch.
	private static boolean hasPreload(Run r) {
		return r.preloadMs() != null && r.preloadMs() >= 0 && r.preloadMs() < r.ms();
	}

	private static long value(Run r, boolean subtract) {
		return subtract && hasPreload(r) ? r.ms() - r.preloadMs() : r.ms();
	}

	private static double[] values(List<Run> runs, boolean subtract) {
		return runs.stream().mapToDouble(r -> value(r, subtract)).toArray();
	}

	// The index of the newest run before the last one with its MC version, or -1.
	private static int previousComparable(List<Run> runs) {
		String mc = runs.getLast().mcVersion();
		for (int i = runs.size() - 2; mc != null && i >= 0; i--) {
			if (mc.equals(runs.get(i).mcVersion())) {
				return i;
			}
		}
		return -1;
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

	// A slow streak's first launch, else the latest.
	public static @Nullable String key(Assessment a) {
		Run run = a.first() != null ? a.first() : a.latest();
		return run == null ? null : KEY_PREFIX + run.at();
	}

	// The notice's message and Tools' first regression row: "Launch time 45% higher than usual (21.3 s vs your usual ~14.7 s)",
	// or, compared without the crash-report setup (RW-19), those numbers so named (after them: the notice line clips its end).
	public static Text regression(Assessment a) {
		String percent = a.deltaPercent() == null ? "?" : Long.toString(Math.round(a.deltaPercent()));
		String latest = a.latestMs() == null ? "?" : seconds(a.latestMs());
		String usual = a.medianMs() == null ? "?" : seconds(a.medianMs());
		return a.preloadSubtracted()
				? Text.of("rigtune.startup.regression.without_preload", "Launch time %s%% higher than usual (%s s vs your usual ~%s s, not counting "
						+ "Minecraft's crash-report setup)", percent, latest, usual)
				: Text.of("rigtune.startup.regression", "Launch time %s%% higher than usual (%s s vs your usual ~%s s)", percent, latest, usual);
	}

	// The one cause line (la §1.4): never two guesses. For a streak, the change before its first launch.
	public static Text cause(Assessment a) {
		Run previous = a.previous();
		Run first = a.first() != null ? a.first() : a.latest();
		Object from = previous == null ? "?" : previous.mods();
		Object to = first == null ? "?" : first.mods();
		Object oldVersion = previous == null ? "?" : previous.rigtuneVersion();
		Object newVersion = first == null ? "?" : first.rigtuneVersion();
		if (a.streak() > 1) {
			return switch (a.cause()) {
				case MOD_COUNT -> Text.of("rigtune.startup.regression.streak.mod_count_changed", "Slower for your last %s launches; may be related to your mod "
						+ "set changing (%s → %s mods) before the first of them", a.streak(), from, to);
				case MOD_SET -> Text.of("rigtune.startup.regression.streak.mod_set_changed", "Slower for your last %s launches; may be related to your mod set "
						+ "changing before the first of them", a.streak());
				case RIGTUNE_VERSION -> Text.of("rigtune.startup.regression.streak.rigtune_changed", "Slower for your last %s launches; RigTune %s → %s before the "
						+ "first of them", a.streak(), oldVersion, newVersion);
				case NONE -> Text.of("rigtune.startup.regression.streak.no_change", "Slower for your last %s launches; no change recorded before the first of "
						+ "them; possibly another program running, a cold disk cache, or a driver/OS update", a.streak());
			};
		}
		return switch (a.cause()) {
			case MOD_COUNT -> Text.of("rigtune.startup.regression.mod_count_changed", "May be related to your mod set changing (%s → %s mods) since your last launch",
					from, to);
			case MOD_SET -> Text.of("rigtune.startup.regression.mod_set_changed", "May be related to your mod set changing since your last launch");
			case RIGTUNE_VERSION -> Text.of("rigtune.startup.regression.rigtune_changed", "RigTune %s → %s since your last launch", oldVersion, newVersion);
			case NONE -> Text.of("rigtune.startup.regression.no_change", "No change recorded since your last launch; possibly another program running, a cold "
					+ "disk cache, or a driver/OS update");
		};
	}

	// For the launch's log line (English, not shown in the game).
	public static String describe(Assessment a) {
		return switch (a.kind()) {
			case NO_RUN -> "no launch recorded";
			case TOO_FEW -> "too few comparable launches (" + a.baselineRuns() + " of " + MIN_RUNS + ")";
			default -> String.format(Locale.ROOT, "%s: %+.1f %% vs the median %.1f s of %d comparable launches%s (floor %.1f %%)%s%s", a.kind(),
					a.deltaPercent(), a.medianMs() / 1000.0, a.baselineRuns(), a.preloadSubtracted() ? ", crash-report setup left out" : "", a.floorPercent(),
					a.streak() > 1 && a.first() != null ? "; slower " + a.streak() + " launches in a row, from " + a.first().at() : "",
					a.cause() == Cause.NONE ? "" : "; cause: " + a.cause());
		};
	}

	private static String seconds(double ms) {
		return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
	}
}
