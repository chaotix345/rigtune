package io.github.chaotix345.rigtune.core.model;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

// A GPU driver version read from GpuInfo.driverVersion (docs/v0.4/SPEC.md 9, docs/research/v0.4/drivers.md §2):
// comparable is compared left to right (missing trailing parts count as 0); raw is the string as the game reported it.
// family UNKNOWN_FAMILY (empty comparable) when the string wasn't recognised: it then never takes part in a rule.
public record DriverVersion(GpuVendor vendor, String family, int[] comparable, String raw) {
	public static final String ADRENALIN = "adrenalin";
	public static final String GEFORCE = "geforce";
	public static final String INTEL = "intel-igpu";
	public static final String MESA = "mesa";
	public static final String UNKNOWN_FAMILY = "unknown";
	// "a.b.c" in rules: at most this many parts of at most 9 digits each.
	static final int MAX_PARTS = 8;

	public DriverVersion {
		vendor = vendor == null ? GpuVendor.UNKNOWN : vendor;
		family = family == null ? UNKNOWN_FAMILY : family;
		comparable = comparable == null ? new int[0] : comparable.clone();
		raw = raw == null ? "" : raw;
	}

	public static DriverVersion unknown(@Nullable GpuVendor vendor, @Nullable String raw) {
		return new DriverVersion(vendor, UNKNOWN_FAMILY, new int[0], raw);
	}

	public boolean known() {
		return comparable.length > 0 && !UNKNOWN_FAMILY.equals(family);
	}

	@Override
	public int[] comparable() {
		return comparable.clone();
	}

	// The parsed version ("560.94"), or the raw string when it wasn't recognised.
	public String display() {
		if (!known()) {
			return raw;
		}
		StringBuilder out = new StringBuilder();
		for (int part : comparable) {
			if (!out.isEmpty()) {
				out.append('.');
			}
			out.append(part);
		}
		return out.toString();
	}

	public int compareTo(int[] other) {
		return compare(comparable, other);
	}

	// Left to right; a missing trailing part is 0.
	public static int compare(int[] a, int[] b) {
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int order = Integer.compare(i < a.length ? a[i] : 0, i < b.length ? b[i] : 0);
			if (order != 0) {
				return order;
			}
		}
		return 0;
	}

	// A rule's dotted numeric string ("526.47"): null unless it is 1 to MAX_PARTS non-negative whole numbers of at most 9
	// digits separated by single dots (surrounding whitespace ignored).
	public static int @Nullable [] dotted(@Nullable String text) {
		if (text == null) {
			return null;
		}
		String value = text.strip();
		if (value.isEmpty() || value.length() > MAX_PARTS * 10) {
			return null;
		}
		String[] parts = value.split("\\.", -1);
		if (parts.length > MAX_PARTS) {
			return null;
		}
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty() || part.length() > 9) {
				return null;
			}
			for (int c = 0; c < part.length(); c++) {
				if (part.charAt(c) < '0' || part.charAt(c) > '9') {
					return null;
				}
			}
			out[i] = Integer.parseInt(part);
		}
		return out;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof DriverVersion other && vendor == other.vendor && family.equals(other.family)
				&& Arrays.equals(comparable, other.comparable) && raw.equals(other.raw);
	}

	@Override
	public int hashCode() {
		return Objects.hash(vendor, family, Arrays.hashCode(comparable), raw);
	}

	@Override
	public String toString() {
		return "DriverVersion[" + vendor + " " + family + " " + Arrays.toString(comparable) + " '" + raw + "']";
	}
}
