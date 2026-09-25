package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

// v0.3 (WS-C, docs/v0.3/SPEC.md item 5): the RigTune screen's launcher lines. With an unknown launcher both give
// exactly what the screen showed before.
final class LauncherLines {
	private LauncherLines() {
	}

	// The header's CPU line; with a known launcher the memory moves to a line of its own that names it ("Memory 6.0 GB of
	// 32 GB, set in the Modrinth App").
	static List<Component> cpuAndMemory(LauncherInfo launcher, Component cpu, Component threads, Component ram, Component heap, int color) {
		String name = launcher.nameKey();
		if (name == null) {
			return List.of(Component.translatable("rigtune.header.cpu", cpu, threads, ram, heap).withStyle(s -> s.withColor(color)));
		}
		return List.of(
				Component.translatable("rigtune.launcher.header.cpu", cpu, threads).withStyle(s -> s.withColor(color)),
				Component.translatable("rigtune.launcher.header.memory", heap, ram, Component.translatable(name).withStyle(ChatFormatting.WHITE))
						.withStyle(s -> s.withColor(color)));
	}

	// "In <launcher>: <steps>" under a ram-* advice when the launcher is known, else null.
	static @Nullable Component adviceLine(Recommendation recommendation, LauncherInfo launcher) {
		String steps = LauncherAdvice.stepsKey(recommendation, launcher);
		String name = launcher.nameKey();
		if (steps == null || name == null) {
			return null;
		}
		return Component.translatable("rigtune.launcher.advice", Component.translatable(name), Component.translatable(steps));
	}
}
