package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.MarkerRestore;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.ShaderAdvice;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

	// name -> sha256 of the bytes / of the lines without '#' comments (Properties.store writes a date comment).
	private static Map<String, String> irisFiles() {
		Path game = FabricLoader.getInstance().getGameDir();
		List<Path> files = new ArrayList<>();
		files.add(FabricLoader.getInstance().getConfigDir().resolve("iris.properties"));
		try (Stream<Path> packs = Files.list(game.resolve("shaderpacks"))) {
			packs.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().forEach(files::add);
		} catch (IOException e) {
			RigTune.LOGGER.warn("Smoke: could not list shaderpacks", e);
		}
		Map<String, String> out = new TreeMap<>();
		for (Path file : files) {
			try {
				byte[] bytes = Files.readAllBytes(file);
				String values = new String(bytes, StandardCharsets.ISO_8859_1).lines().filter(l -> !l.startsWith("#")).collect(Collectors.joining("\n"));
				out.put(game.relativize(file).toString().replace('\\', '/'), sha256(bytes) + "/values " + sha256(values.getBytes(StandardCharsets.ISO_8859_1)));
			} catch (IOException e) {
				out.put(file.getFileName().toString(), "unreadable: " + e);
			}
		}
		return out;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
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
		// v0.3 Phase 5 (AC8.7): Iris' files right before the run (after Iris' own startup save) and right after it.
		evidence.add("Iris files before " + irisFiles());
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
			// v0.3 Phase 5 (AC8.7): the result screen, where the shader advice shows.
			context.waitTicks(10);
			context.takeScreenshot("smoke-benchmark-result");
			evidence.add("Iris files after " + irisFiles());
			boolean dhAfter = context.computeOnClient(mc -> OptionalMods.dhRendering());
			boolean shadersAfter = context.computeOnClient(mc -> OptionalMods.shadersInUse());
			boolean markerGone = !Files.exists(MarkerRestore.file());
			String overrideAfter = context.computeOnClient(mc -> OptionalMods.dhRenderingOverride());
			evidence.add("outcome cancelled " + (outcome == null || outcome.cancelled()) + ", steps "
					+ (session == null ? "?" : session.measurements().stream().map(m -> m.step().kind()).toList()));
			evidence.add("DH off seen during the run " + sawDhOff[0] + ", restore marker seen " + sawMarker[0]);
			evidence.add("DH cost " + (session == null ? null : session.dhCost()) + ", shader cost " + (session == null ? null : session.shaderCost()));
			evidence.add("target " + (session == null ? null : session.targetFps()) + " FPS, shader advice "
					+ (session == null ? null : ShaderAdvice.costPercent(session.shaderCost(), session.targetFps())));
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
