package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Which launcher started the game (docs/v0.3/SPEC.md item 5 with C-M1): the Prism/MultiMC properties and environment,
// then the brand literals verified from the launchers' sources, then the instance files in the game dir and its parent,
// and last the official launcher's brand (CurseForge starts the game through the official launcher and sends it too).
// Every signal is optional; anything unexpected, including any Throwable, means Unknown.
public final class LauncherDetector {
	public static final String MODRINTH_BRAND = "theseus";
	public static final String ATLAUNCHER_BRAND = "ATLauncher";
	public static final String OFFICIAL_BRAND = "minecraft-launcher";

	private LauncherDetector() {
	}

	public static LauncherInfo detect(@Nullable LauncherSignals signals) {
		try {
			return signals == null ? LauncherInfo.UNKNOWN : detectOrThrow(signals);
		} catch (Throwable t) {
			return LauncherInfo.UNKNOWN;
		}
	}

	private static LauncherInfo detectOrThrow(LauncherSignals signals) {
		Map<String, String> properties = signals.properties() == null ? Map.of() : signals.properties();
		Map<String, String> env = signals.env() == null ? Map.of() : signals.env();
		if (present(properties, LauncherSignals.PRISM_INSTANCE) || present(properties, LauncherSignals.MULTIMC_INSTANCE)
				|| present(env, LauncherSignals.INST_ID) || present(env, LauncherSignals.INST_NAME)) {
			return LauncherInfo.of(Launcher.PRISM);
		}
		String brand = properties.get(LauncherSignals.BRAND);
		if (MODRINTH_BRAND.equals(brand)) {
			return LauncherInfo.of(Launcher.MODRINTH_APP);
		}
		if (ATLAUNCHER_BRAND.equals(brand)) {
			return LauncherInfo.of(Launcher.ATLAUNCHER);
		}
		for (Path dir : dirs(signals.gameDir())) {
			if (InstanceFiles.prismInstance(dir)) {
				return LauncherInfo.of(Launcher.PRISM);
			}
			Optional<InstanceFiles.CurseForge> curseForge = InstanceFiles.curseForge(dir);
			if (curseForge.isPresent()) {
				return new LauncherInfo(Launcher.CURSEFORGE, curseForge.get().memoryOverride());
			}
		}
		if (OFFICIAL_BRAND.equals(brand)) {
			return LauncherInfo.of(Launcher.OFFICIAL);
		}
		return LauncherInfo.UNKNOWN;
	}

	private static boolean present(Map<String, String> values, String name) {
		String value = values.get(name);
		return value != null && !value.isBlank();
	}

	// The game dir and its parent only (C-M1): CurseForge's file is in the game dir, Prism's one level up.
	static List<Path> dirs(@Nullable Path gameDir) {
		if (gameDir == null) {
			return List.of();
		}
		Path dir = gameDir.toAbsolutePath().normalize();
		Path parent = dir.getParent();
		return parent == null ? List.of(dir) : List.of(dir, parent);
	}
}
