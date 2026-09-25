package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public class BenchmarkGameTest implements FabricClientGameTest {
	private static final int WORLD_TIMEOUT_TICKS = 20 * 120;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		benchmarkWorldSpike(context);
	}

	private static void benchmarkWorldSpike(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> BenchmarkWorld.open(mc, mc.gui.screen())), "benchmark world opens");
		awaitReady(context);
		context.waitTicks(100);
		context.takeScreenshot("bw-created");
		Path marker = context.computeOnClient(BenchmarkWorld::markerPath);
		check(Files.isRegularFile(marker), "marker written: " + marker);
		FileTime created = modified(marker);
		exit(context);

		check(context.computeOnClient(mc -> BenchmarkWorld.open(mc, mc.gui.screen())), "benchmark world reopens");
		awaitReady(context);
		context.waitTicks(40);
		context.takeScreenshot("bw-reused");
		check(modified(marker).equals(created), "world reused, not recreated");
		exit(context);
	}

	private static void awaitReady(ClientGameTestContext context) {
		context.waitFor(mc -> BenchmarkWorld.state() == BenchmarkWorld.State.READY || BenchmarkWorld.state() == BenchmarkWorld.State.FAILED,
				WORLD_TIMEOUT_TICKS);
		check(context.computeOnClient(mc -> BenchmarkWorld.state()) == BenchmarkWorld.State.READY, "benchmark world ready");
		Object camera = context.computeOnClient(mc -> BenchmarkWorld.cameraPosition());
		RigTune.LOGGER.info("Benchmark world spike: camera at {}", camera);
	}

	// Production leaves from the client tick; under the harness BenchmarkWorld waits for the test thread to do it.
	private static void exit(ClientGameTestContext context) {
		context.runOnClient(mc -> BenchmarkWorld.leave(mc, null));
		check(context.computeOnClient(mc -> BenchmarkWorld.awaitingExit()), "exit deferred to the test thread");
		context.runOnClient(BenchmarkWorld::exitNow);
		context.waitFor(mc -> mc.level == null && BenchmarkWorld.state() == BenchmarkWorld.State.IDLE, WORLD_TIMEOUT_TICKS);
		context.waitForScreen(TitleScreen.class);
	}

	private static FileTime modified(Path file) {
		try {
			return Files.getLastModifiedTime(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
