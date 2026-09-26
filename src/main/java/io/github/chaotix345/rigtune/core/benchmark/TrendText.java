package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Assessment;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

// The words of the benchmark trend (docs/v0.4/SPEC.md 7, X4): the result and history screens, the two notices, the tier
// tooltip's last-benchmark line (and, in English, the share report). A drop is described, never explained: changes
// between the runs are listed as possibly related, a comparison under different conditions says "cause unknown", and a
// benchmark never names a limiting component. Dates are the player's local day (yyyy-MM-dd); numbers are rounded.
public final class TrendText {
	// How a line is shown (the client picks the colour).
	public enum Tone { NORMAL, GOOD, WARNING, BAD }

	public record Line(Text text, Tone tone) {
	}

	private TrendText() {
	}

	// The lines under a run's result: the trend of the view's newest run. describe: History's own text for a change row.
	// maxChanges: how many change rows to list before "…and N more".
	public static List<Line> assessment(BenchmarkTrend.View view, ZoneId zone, Function<HistoryModel.Change, Text> describe, int maxChanges) {
		Assessment a = view.assessment();
		if (a == null) {
			return List.of();
		}
		return switch (a.kind()) {
			case NO_RESULT -> List.of();
			case TOO_FEW -> List.of(new Line(Text.of("rigtune.benchmark.trend.too_few", "Not enough comparable runs for a trend yet (%s of 3)",
					a.baselineRuns()), Tone.NORMAL));
			case IN_LINE -> List.of(new Line(Text.of("rigtune.benchmark.trend.in_line", "1%% lows in line with your usual %s FPS (%s comparable runs)",
					fps(a.median()), a.baselineRuns()), Tone.NORMAL));
			case IMPROVEMENT -> List.of(new Line(Text.of("rigtune.benchmark.trend.improvement",
					"1%% lows %s%% above your usual %s FPS (%s comparable runs)", percent(a.deltaPercent()), fps(a.median()), a.baselineRuns()), Tone.GOOD));
			case REGRESSION -> {
				List<Line> out = new ArrayList<>();
				out.add(new Line(regression(a, since(view, a, zone)), Tone.BAD));
				if (view.changes() != null) {
					out.addAll(changes(view.changes(), describe, maxChanges));
				}
				yield out;
			}
			case DIFFERENT_CONDITIONS -> List.of(new Line(Text.of("rigtune.benchmark.trend.different",
					"Performance changed under different conditions (%s); cause unknown.", differences(a.differences())), Tone.WARNING));
		};
	}

	// The regression line (also the notice's message): "1% lows 18% below your usual 543 FPS since 2026-09-24".
	public static Text regression(Assessment a, String since) {
		return Text.of("rigtune.benchmark.trend.regression", "1%% lows %s%% below your usual %s FPS since %s", percent(a.deltaPercent()),
				fps(a.median()), since);
	}

	// The baseline run's day: the newest comparable run before the latest.
	public static String since(BenchmarkTrend.View view, Assessment a, ZoneId zone) {
		for (BenchmarkRecord r : view.points()) {
			if (r.id().equals(a.baselineRunId())) {
				return date(r.createdAt(), zone);
			}
		}
		return "?";
	}

	// "Changes since then (may be related):" and History's rows (oldest first), a RigTune version change, and the mod set
	// changing outside RigTune; or "No change recorded; …".
	public static List<Line> changes(ChangeWindow window, Function<HistoryModel.Change, Text> describe, int maxChanges) {
		List<Line> out = new ArrayList<>();
		if (window.nothingRecorded()) {
			out.add(new Line(Text.of("rigtune.benchmark.trend.no_change", "No change recorded; possibly a driver, OS or other change"), Tone.WARNING));
			return out;
		}
		out.add(new Line(Text.of("rigtune.benchmark.trend.changes", "Changes since then (may be related):"), Tone.WARNING));
		List<Text> items = new ArrayList<>();
		if (window.rigtuneChanged()) {
			items.add(Text.of("rigtune.benchmark.trend.rigtune", "RigTune %s → %s", window.rigtuneFrom(), window.rigtuneTo()));
		}
		for (ChangeWindow.Item item : window.items()) {
			items.add(describe.apply(item.change()));
		}
		int shown = items.size() > maxChanges ? Math.max(0, maxChanges - 1) : items.size();
		for (Text item : items.subList(0, shown)) {
			out.add(new Line(Text.join("", Text.literal("· "), item), Tone.NORMAL));
		}
		if (shown < items.size()) {
			out.add(new Line(Text.of("rigtune.benchmark.trend.changes.more", "· …and %s more (see History)", items.size() - shown), Tone.NORMAL));
		}
		if (window.outsideChange()) {
			out.add(new Line(Text.of("rigtune.benchmark.trend.outside", "Something outside RigTune changed too (the mod set differs)"), Tone.WARNING));
		}
		return out;
	}

	// The notice's detail: the change lines in one paragraph.
	public static @Nullable Text changesDetail(@Nullable ChangeWindow window, Function<HistoryModel.Change, Text> describe) {
		if (window == null) {
			return null;
		}
		return Text.join("\n", changes(window, describe, 6).stream().map(Line::text).toList());
	}

	public static Text difference(Difference d) {
		return switch (d) {
			case MC_VERSION -> Text.of("rigtune.benchmark.trend.key.mc_version", "Minecraft version");
			case SCENE -> Text.of("rigtune.benchmark.trend.key.scene", "scene");
			case RENDER_DISTANCE -> Text.of("rigtune.benchmark.trend.key.render_distance", "render distance");
			case SIMULATION_DISTANCE -> Text.of("rigtune.benchmark.trend.key.simulation_distance", "simulation distance");
			case RESOLUTION -> Text.of("rigtune.benchmark.trend.key.resolution", "resolution");
			case FULLSCREEN -> Text.of("rigtune.benchmark.trend.key.fullscreen", "fullscreen");
			case SHADERS -> Text.of("rigtune.benchmark.trend.key.shaders", "shaders");
			case SHADER_PACK -> Text.of("rigtune.benchmark.trend.key.shader_pack", "shader pack");
			case DISTANT_HORIZONS -> Text.of("rigtune.benchmark.trend.key.distant_horizons", "Distant Horizons");
			case PROTOCOL -> Text.of("rigtune.benchmark.trend.key.protocol", "benchmark version");
			case NOT_RECORDED -> Text.of("rigtune.benchmark.trend.key.not_recorded", "conditions not recorded");
			case MOD_SET -> Text.of("rigtune.benchmark.trend.key.mod_set", "mod set");
		};
	}

	public static Text differences(List<Difference> differences) {
		return Text.join(", ", differences.stream().map(TrendText::difference).toList());
	}

	// The context selector's label for a group of comparable runs.
	public static Text context(BenchmarkRecord run) {
		List<Text> parts = new ArrayList<>();
		parts.add(scene(run.scene()));
		parts.add(Text.of("rigtune.benchmark.trend.context.version", "Minecraft %s", run.mcVersion()));
		parts.add(distances(run));
		BenchmarkRecord.Context c = run.context();
		if (c == null) {
			parts.add(Text.of("rigtune.benchmark.trend.context.not_recorded", "conditions not recorded"));
			return Text.join(" · ", parts);
		}
		conditions(c, parts);
		if (c.protocol() != BenchmarkRecord.Context.PROTOCOL) {
			parts.add(Text.of("rigtune.benchmark.trend.context.protocol", "benchmark version %s", c.protocol()));
		}
		return Text.join(" · ", parts);
	}

	private static Text scene(String scene) {
		return "BENCHMARK_WORLD".equals(scene) ? Text.of("rigtune.benchmark.scene.benchmark_world", "Benchmark world")
				: "CURRENT".equals(scene) ? Text.of("rigtune.benchmark.scene.current", "Current world") : Text.literal(String.valueOf(scene));
	}

	private static Text distances(BenchmarkRecord run) {
		return Text.of("rigtune.benchmark.trend.context.rd_sd", "RD %s · SD %s", orUnknown(BenchmarkTrend.knob(run, BenchmarkRecord.RENDER_DISTANCE)),
				orUnknown(BenchmarkTrend.knob(run, BenchmarkRecord.SIMULATION_DISTANCE)));
	}

	private static void conditions(BenchmarkRecord.Context c, List<Text> parts) {
		parts.add(Text.of("rigtune.benchmark.trend.context.resolution", "%s×%s", c.width(), c.height()));
		if (c.fullscreen()) {
			parts.add(Text.of("rigtune.benchmark.trend.context.fullscreen", "fullscreen"));
		}
		if (!c.shaders()) {
			parts.add(Text.of("rigtune.benchmark.trend.context.shaders_off", "shaders off"));
		} else if (c.shaderPack() != null) {
			parts.add(Text.of("rigtune.benchmark.trend.context.shaders", "shaders: %s", c.shaderPack()));
		} else {
			parts.add(Text.of("rigtune.benchmark.trend.context.shaders_on", "shaders on"));
		}
		if (c.dhRendering()) {
			parts.add(Text.of("rigtune.benchmark.trend.context.dh", "Distant Horizons on"));
		}
	}

	// "Last benchmark: 1% low 543 FPS · RD 12 · SD 8 · 2560×1440 · shaders off · 2026-09-24".
	public static @Nullable Text last(@Nullable BenchmarkRecord run, ZoneId zone) {
		if (run == null || run.result() == null) {
			return null;
		}
		return Text.of("rigtune.benchmark.trend.last", "Last benchmark: 1%% low %s FPS · %s · %s", fps(run.result().onePercentLowFps()),
				conditions(run), date(run.createdAt(), zone));
	}

	// "RD 12 · SD 8 · 2560×1440 · shaders off" (the context's parts only when the run recorded one).
	public static Text conditions(BenchmarkRecord run) {
		List<Text> parts = new ArrayList<>();
		parts.add(distances(run));
		if (run.context() != null) {
			conditions(run.context(), parts);
		}
		return Text.join(" · ", parts);
	}

	// "Needs a rerun (changed since: resolution, mod set)"; null when nothing changed.
	public static @Nullable Text rerun(List<Difference> stale) {
		return stale.isEmpty() ? null : Text.of("rigtune.benchmark.trend.last.rerun", "Needs a rerun (changed since: %s)", differences(stale));
	}

	// The stale notice's message.
	public static Text staleNotice(List<Difference> stale) {
		return Text.of("rigtune.benchmark.trend.notice.stale", "Your last benchmark needs a rerun: %s changed", differences(stale));
	}

	// Benchmark history without a run.
	public static Text empty() {
		return Text.of("rigtune.benchmark.trend.empty", "No benchmark runs yet. Run one from Tools → Benchmark…");
	}

	// "N comparable runs; M with different conditions not shown".
	public static Text note(int comparable, int other) {
		return comparable == 1 ? Text.of("rigtune.benchmark.trend.note.one", "1 comparable run; %s with different conditions not shown", other)
				: Text.of("rigtune.benchmark.trend.note", "%s comparable runs; %s with different conditions not shown", comparable, other);
	}

	// createdAt is UTC (Instant.toString); shown as the player's local day.
	public static String date(@Nullable String createdAt, ZoneId zone) {
		if (createdAt == null) {
			return "?";
		}
		try {
			return Instant.parse(createdAt).atZone(zone).toLocalDate().toString();
		} catch (DateTimeException e) {
			return createdAt.length() >= 10 ? createdAt.substring(0, 10) : "?";
		}
	}

	private static String orUnknown(@Nullable Integer value) {
		return value == null ? "?" : Integer.toString(value);
	}

	static String fps(@Nullable Double value) {
		return value == null ? "?" : Long.toString(Math.round(value));
	}

	static String percent(@Nullable Double value) {
		return value == null ? "?" : String.format(Locale.ROOT, "%d", Math.round(Math.abs(value)));
	}
}
