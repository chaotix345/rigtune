package io.github.chaotix345.rigtune.v010.core.rules;

import io.github.chaotix345.rigtune.v010.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.v010.core.model.GpuInfo;
import io.github.chaotix345.rigtune.v010.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.v010.core.model.TierResult;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConditionEvaluator {
	private ConditionEvaluator() {
	}

	public static boolean matches(Condition c, EvalContext ctx) {
		if (c == null) {
			return true;
		}
		if (c.always != null && !c.always) {
			return false;
		}
		HardwareProfile hw = ctx.hardware();
		TierResult tier = ctx.tier();
		if (!inRange(tier.effectiveTier(), c.tierAtLeast, c.tierAtMost)
				|| !inRange(tier.rawTier(), c.rawTierAtLeast, c.rawTierAtMost)
				|| !inRange(ctx.gpu().tier(), c.gpuTierAtLeast, c.gpuTierAtMost)
				|| !inRange(tier.cpuTier(), c.cpuTierAtLeast, c.cpuTierAtMost)) {
			return false;
		}
		if (c.gpuVendor != null && !containsIgnoreCase(c.gpuVendor, ctx.gpu().vendor().name())) {
			return false;
		}
		if (c.gpuIntegrated != null && c.gpuIntegrated != ctx.gpu().integrated()) {
			return false;
		}
		if (c.hasBattery != null && c.hasBattery != hw.hasBattery()) {
			return false;
		}
		if (c.onBattery != null && c.onBattery != hw.onBattery()) {
			return false;
		}
		if (!inRange(hw.maxHeapMb(), c.heapMbAtLeast, c.heapMbAtMost)) {
			return false;
		}
		if (!knownInRange(hw.totalRamMb(), c.ramMbAtLeast, c.ramMbAtMost)) {
			return false;
		}
		GpuInfo gpu = hw.gpu();
		if (!knownInRange(gpu == null ? -1 : gpu.vramMb(), c.vramMbAtLeast, c.vramMbAtMost)) {
			return false;
		}
		if (c.refreshRateAtLeast != null) {
			DisplayInfo display = hw.display();
			int hz = display == null ? -1 : display.refreshRate();
			if (hz <= 0 || hz < c.refreshRateAtLeast) {
				return false;
			}
		}
		if (c.backend != null && (gpu == null || gpu.backend() == null || !containsIgnoreCase(c.backend, gpu.backend().name()))) {
			return false;
		}
		if (c.os != null && !osMatches(c.os, hw.osName())) {
			return false;
		}
		if (c.goal != null && !containsIgnoreCase(c.goal, ctx.goal().name())) {
			return false;
		}
		if (c.mcVersion != null && !c.mcVersion.contains(hw.mcVersion())) {
			return false;
		}
		Set<String> loaded = ctx.loadedModIds();
		if (c.modPresent != null && !loaded.containsAll(c.modPresent)) {
			return false;
		}
		if (c.modAbsent != null && c.modAbsent.stream().anyMatch(loaded::contains)) {
			return false;
		}
		if (c.flags != null && (hw.flags() == null || !hw.flags().containsAll(c.flags))) {
			return false;
		}
		if (c.anyOf != null && c.anyOf.stream().noneMatch(sub -> matches(sub, ctx))) {
			return false;
		}
		return c.not == null || !matches(c.not, ctx);
	}

	public static String osFamily(String osName) {
		String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT).trim();
		if (name.startsWith("windows")) {
			return "windows";
		}
		if (name.startsWith("mac") || name.startsWith("darwin") || name.contains("os x")) {
			return "macos";
		}
		if (name.startsWith("linux")) {
			return "linux";
		}
		return name;
	}

	private static boolean osMatches(List<String> wanted, String osName) {
		String family = osFamily(osName);
		return wanted.stream().anyMatch(w -> family.startsWith(w.toLowerCase(Locale.ROOT)));
	}

	private static boolean containsIgnoreCase(List<String> list, String value) {
		return list.stream().anyMatch(v -> v.equalsIgnoreCase(value));
	}

	private static boolean inRange(long value, Number atLeast, Number atMost) {
		return (atLeast == null || value >= atLeast.longValue()) && (atMost == null || value <= atMost.longValue());
	}

	private static boolean knownInRange(long value, Long atLeast, Long atMost) {
		if (atLeast == null && atMost == null) {
			return true;
		}
		return value > 0 && inRange(value, atLeast, atMost);
	}
}
