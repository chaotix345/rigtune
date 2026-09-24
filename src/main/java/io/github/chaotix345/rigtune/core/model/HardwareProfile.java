package io.github.chaotix345.rigtune.core.model;

import java.util.Set;

/**
 * Everything RigTune knows about the machine. flags carries extra facts such as
 * "shaders-enabled" or "sodium-workaround:AMD_GAME_OPTIMIZATION_BROKEN".
 */
public record HardwareProfile(
		CpuInfo cpu,
		GpuInfo gpu,
		long totalRamMb,
		long maxHeapMb,
		DisplayInfo display,
		boolean hasBattery,
		boolean onBattery,
		String osName,
		String mcVersion,
		Set<String> flags) {
}
