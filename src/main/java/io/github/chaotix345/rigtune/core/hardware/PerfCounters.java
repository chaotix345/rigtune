package io.github.chaotix345.rigtune.core.hardware;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 2L (RW-16 part a; docs/research/v0.5/real-world-2026-09-27.md §12): whether Windows' registry-based
// performance counters are switched off, read (never written) from HKEY_LOCAL_MACHINE. Microsoft's "Disable Performance
// Counters" entry under Perflib is a REG_DWORD, 0 by default; anything but 0 turns off every registry-based counter on the
// PC, and vanilla's crash-report setup (OSHI) then waits seconds for queries that can only fail. The per-service entries
// (PerfOS, PerfProc, PerfDisk: the ones OSHI checks) are REG_DWORDs too; another type is reported as "unusual" only, since
// how Windows reads it isn't documented. Only on Windows; nothing here guesses who set it.
public record PerfCounters(boolean read, boolean off, List<String> servicesOff, List<String> unusual) {
	public static final String PERFLIB_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Perflib";
	public static final String VALUE = "Disable Performance Counters";
	static final List<String> SERVICES = List.of("PerfOS", "PerfProc", "PerfDisk");
	// Not Windows, or the registry couldn't be opened: nothing known, nothing shown.
	public static final PerfCounters NOT_READ = new PerfCounters(false, false, List.of(), List.of());

	// One HKEY_LOCAL_MACHINE value: an Integer for a REG_DWORD, a String for a REG_SZ, another object for another type,
	// null when the key or the value doesn't exist. Reads only.
	public interface Registry {
		@Nullable Object value(String key, String name);
	}

	public PerfCounters {
		servicesOff = List.copyOf(servicesOff);
		unusual = List.copyOf(unusual);
	}

	public static String serviceKey(String service) {
		return "SYSTEM\\CurrentControlSet\\Services\\" + service + "\\Performance";
	}

	// osName: System.getProperty("os.name"). The registry is made (and read) only on Windows; a reader that can't be made
	// (no JNA) is NOT_READ, and a value that can't be read counts as absent.
	public static PerfCounters detect(String osName, Supplier<Registry> registry) {
		if (osName == null || !osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
			return NOT_READ;
		}
		Registry reader;
		try {
			reader = registry.get();
		} catch (RuntimeException | LinkageError e) {
			return NOT_READ;
		}
		List<String> unusual = new ArrayList<>();
		Object perflib = value(reader, PERFLIB_KEY);
		boolean off = perflib instanceof Integer dword && dword != 0;
		if (perflib != null && !(perflib instanceof Integer)) {
			unusual.add("Perflib");
		}
		List<String> servicesOff = new ArrayList<>();
		for (String service : SERVICES) {
			Object entry = value(reader, serviceKey(service));
			if (entry instanceof Integer dword) {
				if (dword != 0) {
					servicesOff.add(service);
				}
			} else if (entry != null) {
				unusual.add(service);
			}
		}
		return new PerfCounters(true, off, servicesOff, unusual);
	}

	private static @Nullable Object value(Registry reader, String key) {
		try {
			return reader.value(key, VALUE);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	// For the log: what was found, in the registry's own names (no path, no user name).
	public String describe() {
		if (!read) {
			return "Windows performance counters: not checked";
		}
		StringBuilder out = new StringBuilder("Windows performance counters: ")
				.append(off ? "off (Perflib's \"" + VALUE + "\" isn't 0)" : "on");
		if (!servicesOff.isEmpty()) {
			out.append("; switched off for ").append(String.join(", ", servicesOff));
		}
		if (!unusual.isEmpty()) {
			out.append("; unusual value type: ").append(String.join(", ", unusual));
		}
		return out.toString();
	}
}
