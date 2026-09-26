package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.hardware.GpuClassifier;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

// awareness.json's "fingerprint" (docs/v0.4/SPEC.md C1, 9): what the last probe saw, as raw strings (a better parser later
// re-reads them without a migration). gpuVendor is the detected GpuVendor's name, which doesn't change with the backend
// (GL_VENDOR says "ATI Technologies Inc." where Vulkan says "AMD"). totalRamMb is -1 when unknown.
public record Fingerprint(String gpuVendor, String gpuRenderer, String gpuDriverRaw, String backend, String cpuName, long totalRamMb) {
	// Longer values in the file are cut (the file is untrusted: players edit it).
	static final int MAX_STRING = 256;

	public Fingerprint {
		gpuVendor = cut(gpuVendor);
		gpuRenderer = cut(gpuRenderer);
		gpuDriverRaw = cut(gpuDriverRaw);
		backend = cut(backend);
		cpuName = cut(cpuName);
	}

	public static Fingerprint of(HardwareProfile hw) {
		GpuInfo gpu = hw.gpu();
		String vendorString = gpu == null ? "" : gpu.vendorString();
		String renderer = gpu == null ? "" : gpu.renderer();
		return new Fingerprint(GpuClassifier.detectVendor(vendorString, renderer).name(), renderer, gpu == null ? "" : gpu.driverVersion(),
				gpu == null || gpu.backend() == null ? "UNKNOWN" : gpu.backend().name(), hw.cpu() == null ? "" : hw.cpu().name(), hw.totalRamMb());
	}

	// The stored fingerprint, or null when there is none (the first run, an upgrade from 0.3 or older) or it isn't usable
	// (a field missing or of the wrong type: treated like a first run, so an edited file never raises a notice).
	public static @Nullable Fingerprint read(JsonObject awareness) {
		if (!(awareness.get(AwarenessStore.FINGERPRINT) instanceof JsonObject f)) {
			return null;
		}
		String vendor = string(f.get(AwarenessStore.FINGERPRINT_GPU_VENDOR));
		String renderer = string(f.get(AwarenessStore.FINGERPRINT_GPU_RENDERER));
		String driver = string(f.get(AwarenessStore.FINGERPRINT_GPU_DRIVER_RAW));
		String backend = string(f.get(AwarenessStore.FINGERPRINT_BACKEND));
		String cpu = string(f.get(AwarenessStore.FINGERPRINT_CPU_NAME));
		if (vendor == null || renderer == null || driver == null || backend == null || cpu == null) {
			return null;
		}
		long ram = f.get(AwarenessStore.FINGERPRINT_TOTAL_RAM_MB) instanceof JsonPrimitive p && p.isNumber() ? safeLong(p) : -1;
		return new Fingerprint(vendor, renderer, driver, backend, cpu, ram);
	}

	// Replaces the fingerprint in awareness.json's root (unknown fields inside it are kept).
	public JsonObject writeTo(JsonObject awareness) {
		JsonObject f = awareness.get(AwarenessStore.FINGERPRINT) instanceof JsonObject existing ? existing : new JsonObject();
		f.addProperty(AwarenessStore.FINGERPRINT_GPU_VENDOR, gpuVendor);
		f.addProperty(AwarenessStore.FINGERPRINT_GPU_RENDERER, gpuRenderer);
		f.addProperty(AwarenessStore.FINGERPRINT_GPU_DRIVER_RAW, gpuDriverRaw);
		f.addProperty(AwarenessStore.FINGERPRINT_BACKEND, backend);
		f.addProperty(AwarenessStore.FINGERPRINT_CPU_NAME, cpuName);
		f.addProperty(AwarenessStore.FINGERPRINT_TOTAL_RAM_MB, totalRamMb);
		awareness.add(AwarenessStore.FINGERPRINT, f);
		return awareness;
	}

	// A short id that is the same for the same fingerprint on every run (String.hashCode is specified).
	public String id() {
		String all = String.join("\n", gpuVendor, gpuRenderer, gpuDriverRaw, backend, cpuName, Long.toString(totalRamMb));
		return String.format(Locale.ROOT, "%08x", all.hashCode());
	}

	private static @Nullable String string(@Nullable JsonElement e) {
		return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
	}

	private static long safeLong(JsonPrimitive p) {
		try {
			double value = p.getAsDouble();
			return Double.isFinite(value) && value > 0 && value < Long.MAX_VALUE ? (long) value : -1;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static String cut(@Nullable String value) {
		String v = value == null ? "" : value;
		return v.length() > MAX_STRING ? v.substring(0, MAX_STRING) : v;
	}
}
