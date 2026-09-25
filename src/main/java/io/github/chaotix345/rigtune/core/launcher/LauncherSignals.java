package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// The inputs of launcher detection. The client fills the maps with exactly the names listed here and nothing else.
public record LauncherSignals(@Nullable Map<String, String> properties, @Nullable Map<String, String> env, @Nullable Path gameDir) {
	public static final String PRISM_INSTANCE = "org.prismlauncher.instance.name";
	public static final String MULTIMC_INSTANCE = "multimc.instance.title";
	public static final String BRAND = "minecraft.launcher.brand";
	public static final String INST_ID = "INST_ID";
	public static final String INST_NAME = "INST_NAME";
	public static final List<String> PROPERTIES = List.of(PRISM_INSTANCE, MULTIMC_INSTANCE, BRAND);
	public static final List<String> ENV = List.of(INST_ID, INST_NAME);
}
