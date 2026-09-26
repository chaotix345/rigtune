package io.github.chaotix345.rigtune.core.awareness;

import io.github.chaotix345.rigtune.core.hardware.DriverVersionParser;
import io.github.chaotix345.rigtune.core.model.DriverVersion;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

// Hardware change awareness (docs/v0.4/SPEC.md 9, docs/research/v0.4/drivers.md §5): the probe's fingerprint against
// awareness.json's. A different GPU (vendor or renderer) beats a different driver, which beats a different CPU or RAM; a
// GL <-> Vulkan switch alone is a setting, not a hardware change. Anything that can't be told apart (an empty probe
// value, an unknown vendor, driver strings that don't parse across a backend switch) is no change, never a guess.
public final class ChangeDetector {
	// Total RAM differences below this are noise (firmware reservations), not a RAM change.
	static final long RAM_TOLERANCE_MB = 512;
	private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	public enum Kind { NONE, GPU, DRIVER, HARDWARE }

	// from/to: the driver versions for DRIVER (the parsed version when both parse, else the raw strings).
	public record Change(Kind kind, @Nullable String from, @Nullable String to) {
		public static final Change NONE = new Change(Kind.NONE, null, null);

		public boolean changed() {
			return kind != Kind.NONE;
		}
	}

	private ChangeDetector() {
	}

	// before null (no stored fingerprint) is no change: the caller seeds silently.
	public static Change compare(@Nullable Fingerprint before, Fingerprint now) {
		if (before == null) {
			return Change.NONE;
		}
		boolean gpuKnown = !before.gpuRenderer().isBlank() && !now.gpuRenderer().isBlank();
		if (gpuKnown && (vendorChanged(before.gpuVendor(), now.gpuVendor())
				|| !normalisedRenderer(before.gpuRenderer()).equals(normalisedRenderer(now.gpuRenderer())))) {
			return new Change(Kind.GPU, null, null);
		}
		if (gpuKnown) {
			Change driver = driver(before, now);
			if (driver != null) {
				return driver;
			}
		}
		boolean cpuChanged = !before.cpuName().isBlank() && !now.cpuName().isBlank() && !before.cpuName().strip().equals(now.cpuName().strip());
		boolean ramChanged = before.totalRamMb() > 0 && now.totalRamMb() > 0 && Math.abs(before.totalRamMb() - now.totalRamMb()) >= RAM_TOLERANCE_MB;
		return cpuChanged || ramChanged ? new Change(Kind.HARDWARE, null, null) : Change.NONE;
	}

	// Parsed versions when both parse (same family); otherwise the raw strings, but only on the same backend (a backend
	// switch changes the string's whole shape).
	private static @Nullable Change driver(Fingerprint before, Fingerprint now) {
		GpuVendor vendor = vendor(now.gpuVendor());
		DriverVersion a = DriverVersionParser.parse(vendor, backend(before.backend()), before.gpuDriverRaw());
		DriverVersion b = DriverVersionParser.parse(vendor, backend(now.backend()), now.gpuDriverRaw());
		if (a.known() && b.known() && a.family().equals(b.family())) {
			return DriverVersion.compare(a.comparable(), b.comparable()) == 0 ? null : new Change(Kind.DRIVER, a.display(), b.display());
		}
		boolean sameBackend = before.backend().equals(now.backend());
		if (sameBackend && !before.gpuDriverRaw().strip().equals(now.gpuDriverRaw().strip()) && !now.gpuDriverRaw().isBlank()) {
			return new Change(Kind.DRIVER, before.gpuDriverRaw().strip(), now.gpuDriverRaw().strip());
		}
		return null;
	}

	private static boolean vendorChanged(String before, String now) {
		GpuVendor a = vendor(before);
		GpuVendor b = vendor(now);
		return a != GpuVendor.UNKNOWN && b != GpuVendor.UNKNOWN && a != b;
	}

	// The renderer without what drivers append or reword between backends and versions: "(R)", "(radeonsi, navi32, LLVM
	// …)", "/PCIe/SSE2", a leading "Mesa ".
	static String normalisedRenderer(String renderer) {
		String r = PARENTHESES.matcher(renderer.toLowerCase(Locale.ROOT)).replaceAll(" ");
		int slash = r.indexOf('/');
		if (slash >= 0) {
			r = r.substring(0, slash);
		}
		r = SPACES.matcher(r).replaceAll(" ").strip();
		return r.startsWith("mesa ") ? r.substring(5) : r;
	}

	private static GpuVendor vendor(String name) {
		try {
			return GpuVendor.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return GpuVendor.UNKNOWN;
		}
	}

	private static GraphicsBackend backend(String name) {
		try {
			return GraphicsBackend.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return GraphicsBackend.UNKNOWN;
		}
	}

	// awareness.json side: the first run (no usable fingerprint, which includes every upgrade from 0.3 or older and a
	// corrupt file, moved to .bad by the store) seeds silently and is no change; a file this version can't write (a
	// newer RigTune's, unreadable) is no change either, so a notice that could never be acknowledged never shows.
	public static Change check(AwarenessStore store, Fingerprint now) {
		if (!store.writable()) {
			return Change.NONE;
		}
		Fingerprint before = Fingerprint.read(store.read());
		if (before == null) {
			store.update(now::writeTo);
			return Change.NONE;
		}
		return compare(before, now);
	}

	// The notice was shown or dismissed: the current fingerprint becomes the one to compare with.
	public static boolean commit(AwarenessStore store, Fingerprint now) {
		return store.update(now::writeTo);
	}
}
