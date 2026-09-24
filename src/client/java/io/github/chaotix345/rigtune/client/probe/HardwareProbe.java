package io.github.chaotix345.rigtune.client.probe;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public final class HardwareProbe {
	private static final long MIB = 1024L * 1024L;
	private static volatile CompletableFuture<SlowPart> slow;

	public record Card(String name, String vendor, long vramMb) {
	}

	public record FastPart(String gpuName, String gpuVendor, String driver, GraphicsBackend backend, DisplayInfo display, Set<String> flags) {
	}

	public record SlowPart(CpuInfo cpu, long totalRamMb, boolean hasBattery, boolean onBattery, List<Card> cards) {
	}

	private HardwareProbe() {
	}

	public static CompletableFuture<HardwareProfile> probe(Minecraft minecraft) {
		FastPart fast = probeFast(minecraft);
		return slowPart().thenApply(s -> combine(fast, s));
	}

	public static synchronized CompletableFuture<SlowPart> slowPart() {
		if (slow == null) {
			slow = CompletableFuture.supplyAsync(HardwareProbe::probeSlow, Probes.EXECUTOR);
		}
		return slow;
	}

	public static HardwareProfile combine(FastPart fast, SlowPart slow) {
		long vram = matchVram(fast.gpuName(), slow.cards());
		GpuInfo gpu = new GpuInfo(fast.gpuVendor(), fast.gpuName(), fast.driver(), fast.backend(), vram);
		String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		String os = System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "");
		return new HardwareProfile(slow.cpu(), gpu, slow.totalRamMb(), Runtime.getRuntime().maxMemory() / MIB, fast.display(),
				slow.hasBattery(), slow.onBattery(), os.trim(), mcVersion, fast.flags());
	}

	public static FastPart probeFast(Minecraft minecraft) {
		String name = "unknown";
		String vendor = "unknown";
		String driver = "unknown";
		GraphicsBackend backend = GraphicsBackend.UNKNOWN;
		try {
			GpuDevice device = RenderSystem.tryGetDevice();
			if (device != null) {
				DeviceInfo info = device.getDeviceInfo();
				name = info.name();
				vendor = info.vendorName();
				driver = info.driverInfo();
				backend = backend(info.backendName());
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read GPU device info", e);
		}
		DisplayInfo display = new DisplayInfo(-1, -1, -1, false);
		try {
			Window window = minecraft.getWindow();
			int width = window.getWidth();
			int height = window.getHeight();
			int refresh = window.getRefreshRate();
			Monitor monitor = window.findBestMonitor();
			if (monitor != null) {
				VideoMode mode = monitor.currentMode();
				width = mode.getWidth();
				height = mode.getHeight();
				refresh = refresh > 0 ? refresh : mode.getRefreshRate();
			}
			display = new DisplayInfo(width, height, refresh > 0 ? refresh : -1, window.isFullscreen());
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read display info", e);
		}
		Set<String> flags = new TreeSet<>();
		if (backend == GraphicsBackend.VULKAN) {
			flags.add("backend-vulkan");
		}
		if (irisShadersInUse()) {
			flags.add("shaders-enabled");
		}
		for (String workaround : sodiumWorkarounds()) {
			flags.add("sodium-workaround:" + workaround);
		}
		return new FastPart(name, vendor, driver, backend, display, Set.copyOf(flags));
	}

	static GraphicsBackend backend(String name) {
		if (name == null) {
			return GraphicsBackend.UNKNOWN;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.contains("vulkan")) {
			return GraphicsBackend.VULKAN;
		}
		if (lower.contains("opengl") || lower.equals("gl")) {
			return GraphicsBackend.OPENGL;
		}
		return GraphicsBackend.UNKNOWN;
	}

	static SlowPart probeSlow() {
		String cpuName = "unknown";
		int physical = -1;
		int logical = Runtime.getRuntime().availableProcessors();
		long maxMhz = -1;
		long ramMb = -1;
		boolean hasBattery = false;
		boolean onBattery = false;
		List<Card> cards = new ArrayList<>();
		HardwareAbstractionLayer hal = null;
		try {
			hal = new SystemInfo().getHardware();
		} catch (Throwable t) {
			RigTune.LOGGER.warn("OSHI unavailable", t);
		}
		if (hal != null) {
			try {
				CentralProcessor cpu = hal.getProcessor();
				cpuName = cpu.getProcessorIdentifier().getName().trim();
				physical = cpu.getPhysicalProcessorCount();
				logical = cpu.getLogicalProcessorCount();
				long hz = cpu.getMaxFreq();
				if (hz <= 0) {
					hz = cpu.getProcessorIdentifier().getVendorFreq();
				}
				maxMhz = hz > 0 ? hz / 1_000_000L : -1;
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not read CPU info", t);
			}
			try {
				ramMb = hal.getMemory().getTotal() / MIB;
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not read memory info", t);
			}
			try {
				for (PowerSource source : hal.getPowerSources()) {
					if (realBattery(source.getDeviceName(), source.getChemistry(), source.getMaxCapacity(), source.getDesignCapacity())) {
						hasBattery = true;
						onBattery |= !source.isPowerOnLine() && source.isDischarging();
					}
				}
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not read battery info", t);
			}
			try {
				for (GraphicsCard card : hal.getGraphicsCards()) {
					long vram = card.getVRam();
					cards.add(new Card(card.getName(), card.getVendor(), vram > 0 ? vram / MIB : -1));
				}
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not read graphics cards", t);
			}
		}
		return new SlowPart(new CpuInfo(cpuName, physical, logical, maxMhz), ramMb, hasBattery, onBattery, List.copyOf(cards));
	}

	// On desktops Windows OSHI reports a placeholder "System Battery" with unknown device/chemistry and a capacity of 1.
	static boolean realBattery(String deviceName, String chemistry, int maxCapacity, int designCapacity) {
		boolean unknown = (deviceName == null || deviceName.equalsIgnoreCase("unknown")) && (chemistry == null || chemistry.equalsIgnoreCase("unknown"));
		return !unknown && Math.max(maxCapacity, designCapacity) > 1;
	}

	static long matchVram(String deviceName, List<Card> cards) {
		if (cards.isEmpty()) {
			return -1;
		}
		String target = normalise(deviceName);
		Card best = null;
		int bestScore = 0;
		for (Card card : cards) {
			String candidate = normalise(card.name());
			int score;
			if (!target.isEmpty() && (candidate.contains(target) || target.contains(candidate)) && !candidate.isEmpty()) {
				score = 1000;
			} else {
				score = 0;
				for (String token : candidate.split(" ")) {
					if (token.length() > 1 && (" " + target + " ").contains(" " + token + " ")) {
						score += token.chars().anyMatch(Character::isDigit) ? 10 : 1;
					}
				}
			}
			if (score > bestScore) {
				bestScore = score;
				best = card;
			}
		}
		if (best == null && cards.size() == 1) {
			best = cards.getFirst();
		}
		return best == null ? -1 : best.vramMb();
	}

	static String normalise(String name) {
		if (name == null) {
			return "";
		}
		String s = name.toLowerCase(Locale.ROOT)
				.replace("(tm)", " ").replace("(r)", " ").replace("/pcie/sse2", " ")
				.replaceAll("[^a-z0-9]+", " ");
		return s.trim().replaceAll("\\s+", " ");
	}

	static boolean irisShadersInUse() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return false;
		}
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = api.getMethod("getInstance").invoke(null);
			return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(instance));
		} catch (Throwable t) {
			RigTune.LOGGER.debug("Iris API unavailable", t);
			return false;
		}
	}

	static List<String> sodiumWorkarounds() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		try {
			Class<?> workarounds = Class.forName("net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds");
			Class<?> reference = Class.forName("net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds$Reference");
			var check = workarounds.getMethod("isWorkaroundEnabled", reference);
			for (Object constant : reference.getEnumConstants()) {
				if (Boolean.TRUE.equals(check.invoke(null, constant))) {
					out.add(((Enum<?>) constant).name());
				}
			}
		} catch (Throwable t) {
			RigTune.LOGGER.debug("Sodium workaround API unavailable", t);
		}
		return out;
	}
}
