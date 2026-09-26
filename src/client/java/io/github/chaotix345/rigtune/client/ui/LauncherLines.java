package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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

	// v0.4 (docs/v0.4/SPEC.md 6): under a jvm-* advice, "Found in your Java arguments: <flags>." and the launcher's
	// Java-arguments steps (either can be missing: no matching flag names, an unknown launcher); under a ram-* advice the
	// memory steps, plus the typed -Xmx note where that -Xmx wins over the memory slider. Null when there's nothing to add.
	static @Nullable Component adviceLine(Recommendation recommendation, LauncherInfo launcher, JvmReport jvm) {
		if (!LauncherAdvice.isJvmAdvice(recommendation)) {
			Component memory = adviceLine(recommendation, launcher);
			String name = launcher.nameKey();
			if (memory == null || name == null || !LauncherAdvice.typedXmxWins(recommendation, launcher, jvm)) {
				return memory;
			}
			return memory.copy().append(" ").append(Component.translatable("rigtune.launcher.xmx_in_java_args", Component.translatable(name)));
		}
		List<String> flags = jvm.flagsFor(recommendation.id());
		MutableComponent line = flags.isEmpty() ? null : Component.translatable("rigtune.jvm.found_flags", Component.literal(String.join(", ", flags)));
		String steps = LauncherAdvice.jvmStepsKey(recommendation, launcher);
		String name = launcher.nameKey();
		if (steps != null && name != null) {
			MutableComponent where = Component.translatable("rigtune.launcher.advice", Component.translatable(name), Component.translatable(steps));
			line = line == null ? where : line.append(" ").append(where);
		}
		return line;
	}
}
