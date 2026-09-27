package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.apache.commons.lang3.function.FailableConsumer;

// Every singleplayer world a game test leaves goes through here (v0.5: run 36314730108; the same hang as v0.4's run
// 36235561444 and the 26.2 production smoke S1). Fabric's client game-test harness (fabric-client-gametest-api-v1, the
// same code on 26.2 and 26.3) runs a world exit that a test task starts (runOnClient) in the next tick phase instead
// (threading.MinecraftMixin#deferDisconnect). There Minecraft.disconnect calls IntegratedServer.halt(false), which waits
// for a task on the server thread (BlockableEventLoop.executeBlocking) before its busy-wait takes part in the harness's
// phases again. The server thread only runs a queued task while its tick has time left (MinecraftServer.shouldRun and
// haveTime): once its queue is empty and the tick is due (a paused server ticks in no time), it parks in the harness's
// phaser for the next test phase (threading.MinecraftServerMixin#postRunTasks), which never comes, because the render
// thread is still waiting for that task: deadlock. So before such an exit the test queues a task that holds the server
// thread in managedBlock, running every task that arrives (the halt's among them), until the server stops running
// (halt sets that right after its task) or HOLD_LIMIT_NANOS passes.
final class GameTestWorlds {
	private static final long HOLD_LIMIT_NANOS = 30_000_000_000L;

	private GameTestWorlds() {
	}

	// context.worldBuilder().create(), closed without the deadlock.
	static TestSingleplayerContext create(ClientGameTestContext context) {
		TestSingleplayerContext world = context.worldBuilder().create();
		return new TestSingleplayerContext() {
			@Override
			public TestWorldSave getWorldSave() {
				return world.getWorldSave();
			}

			@Override
			public TestServerConnection getConnection() {
				return world.getConnection();
			}

			@Override
			public TestServerContext getServer() {
				return world.getServer();
			}

			@Override
			public void close() {
				holdServerUntilHalted(context);
				world.close();
			}
		};
	}

	// Leaves the singleplayer world with exit, run on the client (BenchmarkWorld::exitNow), without the deadlock.
	static <E extends Throwable> void leave(ClientGameTestContext context, FailableConsumer<Minecraft, E> exit) throws E {
		holdServerUntilHalted(context);
		context.runOnClient(exit);
	}

	// Test thread, in a test phase, right before the task that leaves the world: the server runs the hold in the next tick
	// phase, the one in which the harness runs the deferred disconnect.
	static void holdServerUntilHalted(ClientGameTestContext context) {
		MinecraftServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
		if (server == null) {
			return;
		}
		long deadline = System.nanoTime() + HOLD_LIMIT_NANOS;
		server.execute(() -> server.managedBlock(() -> !server.isRunning() || System.nanoTime() - deadline > 0));
	}
}
