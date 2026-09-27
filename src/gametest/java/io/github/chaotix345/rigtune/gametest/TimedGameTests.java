package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

// Every game-test class runs through here (build.gradle's processGametestResources routes each fabric-client-gametest
// entrypoint to this adapter): the class is made by Fabric's default adapter, and its wall time goes into the log as
// "Game-test class <Name> runTest returned in <ms> ms" (or "runTest threw"), per leg and part, for tools/ci_streak.py
// (docs/v0.5/SPEC.md 1g). Returning isn't passing: Fabric's own checks after runTest (back on the title screen, no server
// left) can still fail the class.
public final class TimedGameTests implements LanguageAdapter {
	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
		T created = LanguageAdapter.getDefault().create(mod, value, type);
		if (type != FabricClientGameTest.class) {
			return created;
		}
		FabricClientGameTest test = (FabricClientGameTest) created;
		String name = value.substring(value.lastIndexOf('.') + 1);
		return type.cast((FabricClientGameTest) context -> {
			long start = System.nanoTime();
			String outcome = "threw";
			try {
				test.runTest(context);
				outcome = "returned";
			} finally {
				RigTune.LOGGER.info("Game-test class {} runTest {} in {} ms", name, outcome, (System.nanoTime() - start) / 1_000_000);
			}
		});
	}
}
