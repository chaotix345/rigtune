package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.DriverVersion;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads the driver version out of GpuInfo.driverVersion (docs/v0.4/SPEC.md 9, docs/research/v0.4/drivers.md §2.3). Never
// throws; anything it doesn't recognise is UNKNOWN, never a guess.
// OpenGL: the whole GL_VERSION string. AMD Windows "… Core Profile Context YY.M.rev.build" (adrenalin; AMD's older
// five-segment form is UNKNOWN: its mapping isn't verified); "(Core Profile) Mesa x.y.z" for any vendor (mesa); "NVIDIA
// ddd.dd[.dd]" (geforce); Intel Windows "- Build a.b.c.d" (intel-igpu).
// Vulkan (26.3): MC composes "<api version> <driverName> <driverInfo>"; the API version is dropped and only NVIDIA's number,
// AMD Windows "AMD proprietary driver YY.M.rev" (adrenalin; verified against the same PC's GL string, SPEC AC9.8) and a
// Mesa version are read from the rest.
// An unknown backend tries the OpenGL forms, then the Vulkan ones.
public final class DriverVersionParser {
	// Longer strings are not a driver string any GPU reports (VK_MAX_DRIVER_INFO_SIZE is 256 per part).
	static final int MAX_LENGTH = 1024;
	private static final Pattern ADRENALIN = Pattern.compile("\\bContext\\s+(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})\\.\\d{1,9}(?![.\\d])");
	private static final Pattern MESA_GL = Pattern.compile("\\(Core Profile\\)\\s+Mesa\\s+(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");
	private static final Pattern GEFORCE = Pattern.compile("\\bNVIDIA\\s+(\\d{3})\\.(\\d{2,3})(?:\\.(\\d{1,3}))?(?![.\\d])");
	private static final Pattern INTEL_BUILD = Pattern.compile("-\\s*Build\\s+(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?![.\\d])");
	private static final Pattern VULKAN_API = Pattern.compile("^\\s*\\d{1,3}\\.\\d{1,4}\\.\\d{1,5}\\s+");
	private static final Pattern MESA_VK = Pattern.compile("\\bMesa\\s+(?:[A-Za-z]{1,16}\\s+)?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");
	private static final Pattern ADRENALIN_VK = Pattern.compile("\\bAMD proprietary driver\\s+(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})(?![.\\d])");

	private DriverVersionParser() {
	}

	public static DriverVersion parse(@Nullable GpuVendor vendor, @Nullable String raw) {
		return parse(vendor, GraphicsBackend.UNKNOWN, raw);
	}

	public static DriverVersion parse(@Nullable GpuVendor vendor, @Nullable GraphicsBackend backend, @Nullable String raw) {
		GpuVendor v = vendor == null ? GpuVendor.UNKNOWN : vendor;
		if (raw == null || raw.isBlank() || raw.length() > MAX_LENGTH || vendor == null) {
			return DriverVersion.unknown(v, raw);
		}
		try {
			GraphicsBackend b = backend == null ? GraphicsBackend.UNKNOWN : backend;
			DriverVersion found = null;
			if (b != GraphicsBackend.VULKAN) {
				found = openGl(v, raw);
			}
			if (found == null && b != GraphicsBackend.OPENGL) {
				found = vulkan(v, raw);
			}
			return found != null ? found : DriverVersion.unknown(v, raw);
		} catch (RuntimeException e) {
			return DriverVersion.unknown(v, raw);
		}
	}

	private static @Nullable DriverVersion openGl(GpuVendor vendor, String raw) {
		DriverVersion found = switch (vendor) {
			case AMD -> match(ADRENALIN, raw, vendor, DriverVersion.ADRENALIN);
			case NVIDIA -> match(GEFORCE, raw, vendor, DriverVersion.GEFORCE);
			case INTEL -> match(INTEL_BUILD, raw, vendor, DriverVersion.INTEL);
			default -> null;
		};
		return found != null ? found : match(MESA_GL, raw, vendor, DriverVersion.MESA);
	}

	private static @Nullable DriverVersion vulkan(GpuVendor vendor, String raw) {
		Matcher api = VULKAN_API.matcher(raw);
		if (!api.find()) {
			return null;
		}
		String driver = raw.substring(api.end());
		DriverVersion found = switch (vendor) {
			case NVIDIA -> match(GEFORCE, driver, vendor, DriverVersion.GEFORCE, raw);
			case AMD -> match(ADRENALIN_VK, driver, vendor, DriverVersion.ADRENALIN, raw);
			default -> null;
		};
		return found != null ? found : match(MESA_VK, driver, vendor, DriverVersion.MESA, raw);
	}

	private static @Nullable DriverVersion match(Pattern pattern, String text, GpuVendor vendor, String family) {
		return match(pattern, text, vendor, family, text);
	}

	private static @Nullable DriverVersion match(Pattern pattern, String text, GpuVendor vendor, String family, String raw) {
		Matcher m = pattern.matcher(text);
		if (!m.find()) {
			return null;
		}
		int count = 0;
		for (int i = 1; i <= m.groupCount(); i++) {
			if (m.group(i) != null) {
				count++;
			}
		}
		int[] parts = new int[count];
		int at = 0;
		for (int i = 1; i <= m.groupCount(); i++) {
			if (m.group(i) != null) {
				parts[at++] = Integer.parseInt(m.group(i));
			}
		}
		return new DriverVersion(vendor, family, parts, raw);
	}
}
