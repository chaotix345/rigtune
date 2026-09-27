package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicBoolean;

// SCRATCH (the game-test deadlock's regression proof; never merged). Part 1 forces the losing order of the harness race:
// in the tick phase after the test arms it, the render thread sleeps 1 s at the start of its client tick (by then the
// server thread has drained its queue and parked in the harness's phaser), then halts the integrated server the way
// Minecraft.disconnect does (IntegratedServer.halt(false) -> executeBlocking). Without GameTestWorlds' hold
// (-Drigtune.haltRace.hold=false) that deadlocks every time; with it the server runs the halt's task. Part 2 opens and
// leaves real worlds through GameTestWorlds with the game paused first (a paused server ticks in no time).
public class HaltRaceGameTest implements FabricClientGameTest {
	private static final AtomicBoolean ARMED = new AtomicBoolean();
	private static volatile MinecraftServer target;

	@Override
	public void runTest(ClientGameTestContext context) {
		boolean hold = Boolean.parseBoolean(System.getProperty("rigtune.haltRace.hold", "true"));
		int forced = Integer.getInteger("rigtune.haltRace.forced", 5);
		int real = Integer.getInteger("rigtune.haltRace.real", 20);
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (ARMED.compareAndSet(true, false)) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				RigTune.LOGGER.info("HaltRaceGameTest: render thread halting the server now");
				target.halt(false);
				RigTune.LOGGER.info("HaltRaceGameTest: render thread's halt returned");
			}
		});
		for (int cycle = 1; cycle <= forced; cycle++) {
			TestSingleplayerContext world = context.worldBuilder().create();
			world.getConnection().waitForChunksRender();
			MinecraftServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
			target = server;
			if (hold) {
				GameTestWorlds.holdServerUntilHalted(context);
			}
			ARMED.set(true);
			RigTune.LOGGER.info("HaltRaceGameTest: forced cycle {} of {} ({}): the render thread halts the server 1 s into the next tick phase",
					cycle, forced, hold ? "held" : "NOT held");
			context.waitTicks(1);
			context.waitFor(mc -> server.isStopped(), 1200);
			world.close();
			RigTune.LOGGER.info("HaltRaceGameTest: forced cycle {} passed", cycle);
		}
		for (int cycle = 1; cycle <= real; cycle++) {
			try (TestSingleplayerContext world = GameTestWorlds.create(context)) {
				world.getConnection().waitForChunksRender();
				context.runOnClient(mc -> mc.gui.setScreen(new PauseScreen(true)));
				context.waitTicks(5);
			}
			RigTune.LOGGER.info("HaltRaceGameTest: paused-world cycle {} of {} left", cycle, real);
		}
	}
}
