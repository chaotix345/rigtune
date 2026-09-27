package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

// The benchmark result screen's lines about how the run was measured (docs/v0.5/SPEC.md 2B), pure so their truth tables
// are unit-tested: the distances that couldn't be measured (RW-5), the steps the stutter check left out (RW-15), Distant
// Horizons generating terrain (RW-6), what the noise went with (RW-7) and Distant Horizons rendering off (RW-9). Each
// names what was measured; none claims a cause.
public final class ResultNotes {
	private ResultNotes() {
	}

	// RW-5: the distances still incomplete after the run (the terrain hadn't loaded, even on the second try when there
	// was one): not measured, neither a pass nor a fail. Null when every distance was measured.
	public static @Nullable Text unmeasured(List<PlannerResult.Measurement> rows) {
		List<Integer> rds = rows.stream().filter(m -> !m.complete()).map(PlannerResult.Measurement::rd).sorted().toList();
		if (rds.isEmpty()) {
			return null;
		}
		String list = rds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		return rds.size() == 1
				? Text.of("rigtune.benchmark.not_measured.distance",
						"? Render distance %s couldn't be measured: its terrain hadn't loaded in time, so it counts as neither a pass nor a fail.", list)
				: Text.of("rigtune.benchmark.not_measured.distances",
						"? Render distances %s couldn't be measured: their terrain hadn't loaded in time, so they count as neither a pass nor a fail.", list);
	}

	// RW-9: the player plays with Distant Horizons rendering, the run measured without it (the rules cap vanilla render
	// distance while it renders, so the tune doesn't carry over).
	public static @Nullable Text dhOff(boolean dhInstalled, boolean dhRendering) {
		return dhInstalled && !dhRendering ? Text.of("rigtune.benchmark.dh_off", "Measured with Distant Horizons rendering off") : null;
	}

	// RW-6: the run's context says Distant Horizons generated terrain while it ran.
	public static @Nullable Text dhGenerating(BenchmarkRecord.@Nullable Context context) {
		return context != null && Boolean.TRUE.equals(context.dhGenerating())
				? Text.of("rigtune.benchmark.dh_generating", "Distant Horizons was generating terrain during this run, so these numbers may be low.")
				: null;
	}

	// RW-7: the noise warning (cv above BenchmarkMath.NOISY_CV), naming Distant Horizons building terrain or new terrain
	// being generated when those tags are on at least half of the benchmark capture's spikes (Distant Horizons first);
	// otherwise the generic line. Null when the run wasn't noisy.
	public static @Nullable Text noisy(@Nullable Double cv, int spikes, int dhTagged, int chunksLoadingTagged) {
		if (!BenchmarkMath.noisy(cv)) {
			return null;
		}
		String spread = String.format(Locale.ROOT, "%.0f%%", cv * 100);
		if (spikes > 0 && 2 * dhTagged >= spikes) {
			return Text.of("rigtune.benchmark.noisy.dh", "Results were noisy (%s spread) while Distant Horizons was building terrain; a later run may be steadier.",
					spread);
		}
		if (spikes > 0 && 2 * chunksLoadingTagged >= spikes) {
			return Text.of("rigtune.benchmark.noisy.terrain",
					"Results were noisy (%s spread) while new terrain was still being generated; a later run may be steadier.", spread);
		}
		return Text.of("rigtune.benchmark.noisy", "Results were noisy (%s spread): close background apps and retry.", spread);
	}

	// docs/v0.5/SPEC.md 2A (L3): what a table row narrates: its distance, average FPS, 1 % low, P99 and pass / fail / not
	// measured, then whether it is the suggested one.
	public static Text row(PlannerResult.Measurement row, boolean suggested) {
		Text verdict = row.passed() ? Text.of("rigtune.benchmark.row.pass", "meets the target")
				: row.complete() ? Text.of("rigtune.benchmark.row.fail", "misses the target")
				: Text.of("rigtune.benchmark.row.not_measured", "not measured (its terrain hadn't loaded in time)");
		Text text = Text.of("rigtune.benchmark.row", "Render distance %s: average %s FPS, 1%% low %s FPS, P99 %s ms, %s", row.rd(),
				Math.round(row.stats().avgFps()), Math.round(row.stats().onePercentLowFps()), String.format(Locale.ROOT, "%.1f", row.stats().p99FrameMs()),
				verdict);
		return suggested ? Text.join(". ", text, Text.of("rigtune.benchmark.row.suggested", "Suggested")) : text;
	}

	// The table's last column.
	public static String mark(PlannerResult.Measurement row) {
		return row.passed() ? "✔" : row.complete() ? "✘" : "?";
	}

	// RW-15: how many steps the benchmark's stutter capture left out (their settle timed out); null for none.
	public static @Nullable Text stutterStepsLeftOut(int steps) {
		if (steps <= 0) {
			return null;
		}
		return steps == 1 ? Text.of("rigtune.benchmark.stutter_left_out.one", "The stutter check left out 1 step whose terrain hadn't loaded.")
				: Text.of("rigtune.benchmark.stutter_left_out", "The stutter check left out %s steps whose terrain hadn't loaded.", steps);
	}
}
