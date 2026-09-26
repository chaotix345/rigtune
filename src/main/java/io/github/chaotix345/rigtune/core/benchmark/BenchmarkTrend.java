package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Benchmark history and regression alerts (docs/v0.4/SPEC.md 7, plan review B-H1/B-L1): which runs are comparable, the
// trend maths (median, MAD, noise floor, Regression) and the "needs a rerun" marker. Pure; the client's TrendService
// feeds it benchmarks.json and the current conditions.
//
// Comparable = same MC version, scene, render and simulation distance (the knob values) and conditions
// (Context.sameConditions: resolution, fullscreen, shaders + pack, Distant Horizons, protocol); runs without a context
// (0.2.x) only with each other. The mod-set hash never splits runs (B-H1): its effect is what a comparison looks for.
//
// Noise floor (research bench-history-a11y.md A2): 2 x max(latest.cv or 5 %, 1.4826 x MAD / median) of the comparable
// runs' 1 % lows, from at least 3 of them. Same-session 1 %-low CVs were 1.7-12.3 % (median 4.2 %), so the 5 % default
// stays; retune once real cross-session history exists.
public final class BenchmarkTrend {
	public static final int MIN_RUNS = 3;
	// The chart shows, and "your usual" is the median of, at most this many comparable runs.
	public static final int MAX_RUNS = 10;
	public static final double MAD_SCALE = 1.4826;
	// B-L1: for the "needs a rerun" marker only, a pixel count within 10 % counts as the same resolution.
	public static final double RESOLUTION_TOLERANCE = 0.10;
	private static final double EPSILON = 1e-9;

	// Declaration order is the order they are named in.
	public enum Difference {
		MC_VERSION, SCENE, RENDER_DISTANCE, SIMULATION_DISTANCE, RESOLUTION, FULLSCREEN, SHADERS, SHADER_PACK, DISTANT_HORIZONS, PROTOCOL,
		// One run has a context and the other doesn't (0.2.x wrote none).
		NOT_RECORDED,
		// Only for the marker: the loaded mods differ (both hashes known).
		MOD_SET
	}

	public enum Kind {
		// The run has no result.
		NO_RESULT,
		// Fewer than MIN_RUNS comparable runs before it: no delta is claimed.
		TOO_FEW,
		// Within the noise floor of the median.
		IN_LINE,
		// Above the median by at least the floor (never an alert).
		IMPROVEMENT,
		// Below the median by at least the floor.
		REGRESSION,
		// Not comparable with the previous run of the scene, whose 1 % lows differ past the noise: no delta is claimed.
		DIFFERENT_CONDITIONS
	}

	public record Regression(double median, double latestLow, double deltaPercent, String baselineRunId) {
	}

	// latestRunId: the run assessed. baselineRuns: the comparable runs before it (at most MAX_RUNS). median, latestLow,
	// deltaPercent and floorPercent: set for IN_LINE, IMPROVEMENT and REGRESSION. baselineRunId: the newest comparable run
	// before it (the "since" of an alert), or for DIFFERENT_CONDITIONS the previous run it differs from. differences: for
	// DIFFERENT_CONDITIONS, what differs.
	public record Assessment(Kind kind, String latestRunId, int baselineRuns, @Nullable Double median, @Nullable Double latestLow,
			@Nullable Double deltaPercent, @Nullable Double floorPercent, @Nullable String baselineRunId, List<Difference> differences) {
		public Assessment {
			differences = List.copyOf(differences);
		}

		public @Nullable Regression regression() {
			return kind == Kind.REGRESSION ? new Regression(median, latestLow, deltaPercent, baselineRunId) : null;
		}
	}

	// What the game is set to now, for the marker. modSetHash: null when unknown.
	public record Current(String mcVersion, int renderDistance, int simulationDistance, int width, int height, boolean fullscreen, boolean shaders,
			@Nullable String shaderPack, boolean dhRendering, @Nullable String modSetHash) {
	}

	// What the Benchmark history screen, the result screen, the notices, the tooltip and the share report show.
	// contextKey: the context shown; contextKeys: every context with a result, newest first, and examples: the newest run
	// of each (for its label). comparableRuns / otherRuns: the "N comparable runs; M with different conditions not shown"
	// note. points: the shown context's newest runs (at most MAX_RUNS), oldest first. assessment: of the newest of them.
	// changes: what History recorded between a regression's baseline and that run (the client adds it).
	// last: the newest run with a result, whatever its context; stale: what no longer matches it (empty when unknown).
	public record View(@Nullable String contextKey, List<String> contextKeys, int comparableRuns, int otherRuns, List<BenchmarkRecord> examples,
			List<BenchmarkRecord> points, @Nullable Assessment assessment, @Nullable ChangeWindow changes, @Nullable BenchmarkRecord last,
			List<Difference> stale) {
		public static final View EMPTY = new View(null, List.of(), 0, 0, List.of(), List.of(), null, null, null, List.of());

		public View {
			contextKeys = contextKeys == null ? List.of() : List.copyOf(contextKeys);
			examples = examples == null ? List.of() : List.copyOf(examples);
			points = points == null ? List.of() : List.copyOf(points);
			stale = stale == null ? List.of() : List.copyOf(stale);
		}

		public View withChanges(@Nullable ChangeWindow newChanges) {
			return new View(contextKey, contextKeys, comparableRuns, otherRuns, examples, points, assessment, newChanges, last, stale);
		}

		public @Nullable BenchmarkRecord example(@Nullable String key) {
			int i = contextKeys.indexOf(key);
			return i < 0 ? null : examples.get(i);
		}

		// The newest run of the context shown.
		public @Nullable BenchmarkRecord latest() {
			return points.isEmpty() ? null : points.getLast();
		}

		// The chart's median line: "your usual", when there is one.
		public @Nullable Double median() {
			return assessment == null ? null : assessment.median();
		}
	}

	private BenchmarkTrend() {
	}

	public static @Nullable Integer knob(BenchmarkRecord run, String name) {
		BenchmarkRecord.KnobResult knob = run.knobs().get(name);
		return knob == null ? null : knob.value();
	}

	// What makes the two runs not comparable (never MOD_SET); empty = comparable.
	public static List<Difference> differences(BenchmarkRecord a, BenchmarkRecord b) {
		Set<Difference> out = EnumSet.noneOf(Difference.class);
		if (!Objects.equals(a.mcVersion(), b.mcVersion())) {
			out.add(Difference.MC_VERSION);
		}
		if (!Objects.equals(a.scene(), b.scene())) {
			out.add(Difference.SCENE);
		}
		if (!Objects.equals(knob(a, BenchmarkRecord.RENDER_DISTANCE), knob(b, BenchmarkRecord.RENDER_DISTANCE))) {
			out.add(Difference.RENDER_DISTANCE);
		}
		if (!Objects.equals(knob(a, BenchmarkRecord.SIMULATION_DISTANCE), knob(b, BenchmarkRecord.SIMULATION_DISTANCE))) {
			out.add(Difference.SIMULATION_DISTANCE);
		}
		BenchmarkRecord.Context ca = a.context();
		BenchmarkRecord.Context cb = b.context();
		if (ca == null || cb == null) {
			if (ca != cb) {
				out.add(Difference.NOT_RECORDED);
			}
			return List.copyOf(out);
		}
		if (ca.width() != cb.width() || ca.height() != cb.height()) {
			out.add(Difference.RESOLUTION);
		}
		if (ca.fullscreen() != cb.fullscreen()) {
			out.add(Difference.FULLSCREEN);
		}
		if (ca.shaders() != cb.shaders()) {
			out.add(Difference.SHADERS);
		} else if (ca.shaders() && !Objects.equals(ca.shaderPack(), cb.shaderPack())) {
			out.add(Difference.SHADER_PACK);
		}
		if (ca.dhRendering() != cb.dhRendering()) {
			out.add(Difference.DISTANT_HORIZONS);
		}
		if (ca.protocol() != cb.protocol()) {
			out.add(Difference.PROTOCOL);
		}
		return List.copyOf(out);
	}

	public static boolean comparable(BenchmarkRecord a, BenchmarkRecord b) {
		return differences(a, b).isEmpty();
	}

	// One string per comparable group: two runs are comparable exactly when their keys are equal (the free-text pack name
	// comes last, so the key can't be ambiguous).
	public static String contextKey(BenchmarkRecord run) {
		StringBuilder key = new StringBuilder().append(run.mcVersion()).append('|').append(run.scene()).append("|rd")
				.append(knob(run, BenchmarkRecord.RENDER_DISTANCE)).append("|sd").append(knob(run, BenchmarkRecord.SIMULATION_DISTANCE));
		BenchmarkRecord.Context c = run.context();
		if (c == null) {
			return key.append("|none").toString();
		}
		return key.append('|').append(c.width()).append('x').append(c.height()).append(c.fullscreen() ? "|fs" : "|win")
				.append(c.dhRendering() ? "|dh" : "|nodh").append("|p").append(c.protocol())
				.append(c.shaders() ? "|shaders:" + c.shaderPack() : "|noshaders").toString();
	}

	public static double median(double... values) {
		if (values.length == 0) {
			throw new IllegalArgumentException("no values");
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int mid = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
	}

	// Median absolute deviation (unscaled).
	public static double mad(double... values) {
		double median = median(values);
		double[] deviations = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			deviations[i] = Math.abs(values[i] - median);
		}
		return median(deviations);
	}

	// Percent. A latest run without a CV counts as BenchmarkMath.NOISY_CV (5 %).
	public static double noiseFloorPercent(@Nullable Double latestCv, double... baselineLows) {
		double cv = latestCv == null ? BenchmarkMath.NOISY_CV : latestCv;
		double median = median(baselineLows);
		double spread = median > 0 ? MAD_SCALE * mad(baselineLows) / median : 0;
		return 2 * Math.max(cv, spread) * 100;
	}

	// The comparable runs with a result before `latest` in `runs` (oldest first; all of them when latest isn't there),
	// the newest MAX_RUNS, oldest first.
	public static List<BenchmarkRecord> baseline(BenchmarkRecord latest, List<BenchmarkRecord> runs) {
		List<BenchmarkRecord> out = new ArrayList<>();
		for (BenchmarkRecord r : before(latest, runs)) {
			if (r.result() != null && comparable(r, latest)) {
				out.add(r);
			}
		}
		return List.copyOf(out.subList(Math.max(0, out.size() - MAX_RUNS), out.size()));
	}

	private static List<BenchmarkRecord> before(BenchmarkRecord latest, List<BenchmarkRecord> runs) {
		for (int i = runs.size() - 1; i >= 0; i--) {
			if (Objects.equals(runs.get(i).id(), latest.id())) {
				return runs.subList(0, i);
			}
		}
		return runs;
	}

	public static Assessment assess(BenchmarkRecord latest, List<BenchmarkRecord> runs) {
		BenchmarkRecord.Result result = latest.result();
		if (result == null) {
			return new Assessment(Kind.NO_RESULT, latest.id(), 0, null, null, null, null, null, List.of());
		}
		List<BenchmarkRecord> baseline = baseline(latest, runs);
		if (baseline.size() < MIN_RUNS) {
			BenchmarkRecord previous = previousOfScene(latest, runs);
			if (previous != null && !comparable(previous, latest)) {
				BenchmarkMath.Gain gain = BenchmarkRecords.gain(previous, latest);
				if (gain != null && gain.significant()) {
					return new Assessment(Kind.DIFFERENT_CONDITIONS, latest.id(), baseline.size(), null, null, null, null, previous.id(),
							differences(previous, latest));
				}
			}
			return new Assessment(Kind.TOO_FEW, latest.id(), baseline.size(), null, null, null, null,
					baseline.isEmpty() ? null : baseline.getLast().id(), List.of());
		}
		double[] lows = baseline.stream().mapToDouble(r -> r.result().onePercentLowFps()).toArray();
		double median = median(lows);
		double floor = noiseFloorPercent(result.cv(), lows);
		double delta = BenchmarkMath.gainPercent(median, result.onePercentLowFps());
		Kind kind = delta <= -floor + EPSILON ? Kind.REGRESSION : delta >= floor - EPSILON ? Kind.IMPROVEMENT : Kind.IN_LINE;
		return new Assessment(kind, latest.id(), baseline.size(), median, result.onePercentLowFps(), delta, floor, baseline.getLast().id(), List.of());
	}

	private static @Nullable BenchmarkRecord previousOfScene(BenchmarkRecord latest, List<BenchmarkRecord> runs) {
		List<BenchmarkRecord> before = before(latest, runs);
		for (int i = before.size() - 1; i >= 0; i--) {
			BenchmarkRecord r = before.get(i);
			if (r.result() != null && Objects.equals(r.scene(), latest.scene())) {
				return r;
			}
		}
		return null;
	}

	// What no longer matches the last run (the "needs a rerun" marker): MC version, render and simulation distance, then,
	// when the run recorded its context, resolution (B-L1: within 10 % of its pixel count is the same), fullscreen,
	// shaders or the pack, Distant Horizons, and the mod set when both hashes are known.
	public static List<Difference> stale(BenchmarkRecord last, Current now) {
		Set<Difference> out = EnumSet.noneOf(Difference.class);
		if (!Objects.equals(last.mcVersion(), now.mcVersion())) {
			out.add(Difference.MC_VERSION);
		}
		if (knobChanged(last.knobs().get(BenchmarkRecord.RENDER_DISTANCE), now.renderDistance())) {
			out.add(Difference.RENDER_DISTANCE);
		}
		if (knobChanged(last.knobs().get(BenchmarkRecord.SIMULATION_DISTANCE), now.simulationDistance())) {
			out.add(Difference.SIMULATION_DISTANCE);
		}
		BenchmarkRecord.Context c = last.context();
		if (c == null) {
			return List.copyOf(out);
		}
		double pixels = (double) c.width() * c.height();
		double nowPixels = (double) now.width() * now.height();
		if (pixels <= 0 ? nowPixels > 0 : Math.abs(nowPixels - pixels) / pixels >= RESOLUTION_TOLERANCE - EPSILON) {
			out.add(Difference.RESOLUTION);
		}
		if (c.fullscreen() != now.fullscreen()) {
			out.add(Difference.FULLSCREEN);
		}
		if (c.shaders() != now.shaders()) {
			out.add(Difference.SHADERS);
		} else if (c.shaders() && !Objects.equals(c.shaderPack(), now.shaderPack())) {
			out.add(Difference.SHADER_PACK);
		}
		if (c.dhRendering() != now.dhRendering()) {
			out.add(Difference.DISTANT_HORIZONS);
		}
		if (c.modSetHash() != null && now.modSetHash() != null && !c.modSetHash().equals(now.modSetHash())) {
			out.add(Difference.MOD_SET);
		}
		return List.copyOf(out);
	}

	// A Tune whose suggestion the player didn't take (Keep: the game is still at the original value) changed nothing
	// (review M2); the last-benchmark line names the distance it measured.
	private static boolean knobChanged(BenchmarkRecord.@Nullable KnobResult knob, int now) {
		return knob != null && knob.value() != now && !(knob.original() != knob.value() && knob.original() == now);
	}

	// The trend of one context (contextKey; null or unknown = the newest run's) over `runs` (benchmarks.json, oldest
	// first). now: the current conditions for the marker, or null when unknown.
	public static View view(List<BenchmarkRecord> runs, @Nullable String contextKey, @Nullable Current now) {
		Map<String, BenchmarkRecord> newestByKey = new LinkedHashMap<>();
		Map<String, List<BenchmarkRecord>> byKey = new LinkedHashMap<>();
		int withResult = 0;
		// A run without a result, or without an id (a hand edit), takes no part.
		runs = runs.stream().filter(r -> r.id() != null).toList();
		for (BenchmarkRecord r : runs.reversed()) {
			if (r.result() == null) {
				continue;
			}
			withResult++;
			String key = contextKey(r);
			newestByKey.putIfAbsent(key, r);
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
		}
		if (newestByKey.isEmpty()) {
			return View.EMPTY;
		}
		List<String> keys = List.copyOf(newestByKey.keySet());
		String shown = contextKey != null && newestByKey.containsKey(contextKey) ? contextKey : keys.getFirst();
		List<BenchmarkRecord> group = byKey.get(shown).reversed();
		List<BenchmarkRecord> points = group.subList(Math.max(0, group.size() - MAX_RUNS), group.size());
		BenchmarkRecord last = newestByKey.get(keys.getFirst());
		return new View(shown, keys, group.size(), withResult - group.size(), List.copyOf(newestByKey.values()), points,
				assess(points.getLast(), runs), null, last, now == null ? List.of() : stale(last, now));
	}
}
