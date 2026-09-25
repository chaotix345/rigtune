package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.MarkerRestore;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// AC6.4 in a production smoke run with the player's mods (-PsmokeBenchmark): a short Tune in the smoke world, then
// Distant Horizons' renderingEnabled and Iris' shaders must be back to their original values and
// benchmark-restore.json gone. The evidence goes to run/rigtune-benchmark-smoke.txt first, because the harness
// deadlocks leaving a world while Distant Horizons is loaded (docs/smoke/README.md): the client then has to be killed.
final class BenchmarkSmoke {
	private static final BenchmarkController.Config SHORT = new BenchmarkController.Config(3, 2.0, 1.0, 20.0);
	private static final int TIMEOUT_TICKS = 20 * 360;

	private BenchmarkSmoke() {
	}

	private static String rendererMode() {
		Path toml = FabricLoader.getInstance().getConfigDir().resolve("DistantHorizons.toml");
		try {
			return Files.readAllLines(toml).stream().map(String::strip).filter(l -> l.startsWith("rendererMode")).findFirst().orElse("(no rendererMode)");
		} catch (IOException e) {
			return "(unreadable: " + e + ")";
		}
	}

	static void run(ClientGameTestContext context) {
		boolean dhBefore = context.computeOnClient(mc -> OptionalMods.dhRendering());
		boolean shadersBefore = context.computeOnClient(mc -> OptionalMods.shadersInUse());
		boolean dhLoaded = OptionalMods.dhLoaded();
		String overrideBefore = context.computeOnClient(mc -> OptionalMods.dhRenderingOverride());
		List<String> evidence = new ArrayList<>();
		evidence.add("at " + Instant.now());
		evidence.add("Distant Horizons loaded " + dhLoaded + ", rendering before " + dhBefore + ", API override before " + overrideBefore);
		evidence.add("Iris loaded " + OptionalMods.irisLoaded() + ", shaders in use before " + shadersBefore);
		boolean passed;
		try {
			boolean started = context.computeOnClient(mc -> BenchmarkController.start(mc, new BenchmarkRequest(BenchmarkRequest.Mode.TUNE,
					BenchmarkRequest.Scene.CURRENT, null), SHORT));
			evidence.add("benchmark started " + started);
			boolean[] sawDhOff = {false};
			boolean[] sawMarker = {false};
			context.waitFor(mc -> {
				if (dhBefore && !OptionalMods.dhRendering()) {
					sawDhOff[0] = true;
				}
				sawMarker[0] |= Files.exists(MarkerRestore.file());
				return !BenchmarkController.running();
			}, TIMEOUT_TICKS);
			BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
			SessionResult session = outcome == null ? null : outcome.session();
			boolean dhAfter = context.computeOnClient(mc -> OptionalMods.dhRendering());
			boolean shadersAfter = context.computeOnClient(mc -> OptionalMods.shadersInUse());
			boolean markerGone = !Files.exists(MarkerRestore.file());
			String overrideAfter = context.computeOnClient(mc -> OptionalMods.dhRenderingOverride());
			evidence.add("outcome cancelled " + (outcome == null || outcome.cancelled()) + ", steps "
					+ (session == null ? "?" : session.measurements().stream().map(m -> m.step().kind()).toList()));
			evidence.add("DH off seen during the run " + sawDhOff[0] + ", restore marker seen " + sawMarker[0]);
			evidence.add("DH cost " + (session == null ? null : session.dhCost()) + ", shader cost " + (session == null ? null : session.shaderCost()));
			evidence.add("Distant Horizons rendering after " + dhAfter + ", API override after " + overrideAfter + ", shaders in use after " + shadersAfter);
			evidence.add("benchmark-restore.json gone " + markerGone);
			// DH saves renderingEnabled as rendererMode in its TOML; give it time to write before reading the file.
			context.waitTicks(100);
			evidence.add("DistantHorizons.toml " + rendererMode());
			boolean dhMeasured = session != null && session.measurements().stream().anyMatch(m -> m.step().kind() == Step.Kind.DH_OFF);
			passed = started && outcome != null && !outcome.cancelled() && dhAfter == dhBefore && shadersAfter == shadersBefore && markerGone
					&& overrideAfter.equals(overrideBefore)
					&& (!dhBefore || dhMeasured && sawDhOff[0]);
		} catch (RuntimeException | AssertionError e) {
			evidence.add("error " + e);
			passed = false;
		}
		evidence.add("AC6.4 " + (passed ? "PASSED" : "FAILED"));
		Path out = FabricLoader.getInstance().getGameDir().resolve("rigtune-benchmark-smoke.txt");
		try {
			Files.write(out, evidence);
		} catch (IOException e) {
			RigTune.LOGGER.warn("Smoke: could not write {}", out, e);
		}
		RigTune.LOGGER.info("Smoke benchmark: {}", String.join(" | ", evidence));
		if (dhLoaded) {
			RigTune.LOGGER.warn("Smoke benchmark: with Distant Horizons loaded the harness deadlocks when it leaves the world;"
					+ " the evidence is in {}, so kill the client if it hangs", out);
		}
		if (!passed) {
			throw new AssertionError("Smoke benchmark failed: " + evidence);
		}
	}
}
