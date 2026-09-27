package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

// docs/v0.5/SPEC.md C8: the one "busy" check before a settings change a player asks for (a profile switch, C16's offer, a
// stutter fix, a Try it). In order: a benchmark is running; downloads of an earlier Apply are still running; a Try it
// chain is between or inside its runs; the rules or the hardware scan aren't ready yet. Callers add their own refusals
// after it. The benchmark text is ProfileService's own from 0.4.
public final class Busy {
	// C09 (WS-T) points this at its own state; the default: no Try it is running.
	public static volatile BooleanSupplier tryItRunning = () -> false;
	private static volatile BooleanSupplier benchmarkRunning = BenchmarkController::running;

	private Busy() {
	}

	public static @Nullable Text refusal(RealController controller) {
		return refusal(benchmarkRunning.getAsBoolean(), controller.downloading(), tryItRunning.getAsBoolean(),
				controller.rules() != null && controller.hardwareProfile() != null);
	}

	static @Nullable Text refusal(boolean benchmark, boolean downloading, boolean tryIt, boolean ready) {
		if (benchmark) {
			return Text.of("rigtune.profile.status.benchmark", "Profiles can't switch while a benchmark is running.");
		}
		if (downloading) {
			return Text.of("rigtune.status.busy", "Still downloading the previous changes…");
		}
		if (tryIt) {
			return Text.of("rigtune.tryit.refused.running", "A Try it (measured) is still in progress. Finish or cancel it first.");
		}
		if (!ready) {
			return Text.of("rigtune.profile.code.error.not_ready", "RigTune is still scanning this PC. Try again in a moment.");
		}
		return null;
	}

	// For game tests that stand a running benchmark in (ProfileService.overrideBenchmarkCheck): null puts the real check back.
	public static void overrideBenchmarkCheck(@Nullable BooleanSupplier running) {
		benchmarkRunning = running == null ? BenchmarkController::running : running;
	}
}
