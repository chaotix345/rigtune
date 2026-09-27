package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

// The benchmark result screen's lines about how the run was measured (docs/v0.5/SPEC.md 2B), pure so their truth tables
// are unit-tested: the distances that couldn't be measured (RW-5) and the steps the stutter check left out (RW-15).
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
