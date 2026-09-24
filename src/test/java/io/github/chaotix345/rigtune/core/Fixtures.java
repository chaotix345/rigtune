package io.github.chaotix345.rigtune.core;

import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class Fixtures {
	private Fixtures() {
	}

	public static Hw userRig() {
		return new Hw();
	}

	public static Hw lowEndLaptop() {
		Hw hw = new Hw();
		hw.cpu = new CpuInfo("Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz", 4, 8, -1);
		hw.gpu = new GpuInfo("Intel", "Intel(R) UHD Graphics 620", "31.0.101.2111", GraphicsBackend.OPENGL, -1);
		hw.ramMb = 8192;
		hw.heapMb = 2048;
		hw.display = new DisplayInfo(1920, 1080, 60, true);
		hw.hasBattery = true;
		hw.onBattery = true;
		return hw;
	}

	public static List<InstalledMod> mods(String... ids) {
		return Arrays.stream(ids).map(Fixtures::mod).toList();
	}

	public static InstalledMod mod(String id) {
		return new InstalledMod(id, id, "1.0.0", Path.of("mods", id + ".jar"), "0000" + id);
	}

	public static final class Hw {
		public CpuInfo cpu = new CpuInfo("AMD Ryzen 7 7800X3D 8-Core Processor", 8, 16, 4201);
		public GpuInfo gpu = new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", "25.9.1", GraphicsBackend.OPENGL, 16384);
		public long ramMb = 32768;
		public long heapMb = 6144;
		public DisplayInfo display = new DisplayInfo(2560, 1440, 180, true);
		public boolean hasBattery;
		public boolean onBattery;
		public String os = "Windows 11";
		public String mcVersion = "26.2";
		public Set<String> flags = Set.of();

		public Hw gpu(String vendor, String renderer) {
			gpu = new GpuInfo(vendor, renderer, "1.0", GraphicsBackend.OPENGL, -1);
			return this;
		}

		public HardwareProfile build() {
			return new HardwareProfile(cpu, gpu, ramMb, heapMb, display, hasBattery, onBattery, os, mcVersion, flags);
		}
	}
}
