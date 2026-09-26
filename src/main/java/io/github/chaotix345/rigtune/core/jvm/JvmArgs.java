package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The JVM's input arguments (RuntimeMXBean.getInputArguments(), docs/research/v0.4/jvm-gc.md §1.2) as typed entries:
// -XX flags by name (last one wins, as in HotSpot), the heap sizes, and the names of -D properties. No value is kept
// (they can hold paths with the Windows user name), except the heap sizes as numbers. Tokens of a family this class
// reads (-XX:, -Xmx/-Xms/-Xmn/-Xss, -D) that don't parse count as junk; anything else (agents, module options, -Xlog)
// is simply not ours.
public final class JvmArgs {
	private static final Pattern XX = Pattern.compile("-XX:(?:([+-])([A-Za-z][A-Za-z0-9_]*)|([A-Za-z][A-Za-z0-9_]*)=.*)", Pattern.DOTALL);
	private static final Pattern SIZE = Pattern.compile("(\\d{1,19})([kKmMgGtT]?)");
	private static final Pattern SIZED = Pattern.compile("-X(mx|ms|mn|ss)(.*)", Pattern.DOTALL);

	// name is a -XX name; enabled is the sign of a boolean flag, null for a value flag (whose value isn't kept).
	public record XxFlag(String name, @Nullable Boolean enabled) {
		public boolean hasValue() {
			return enabled == null;
		}

		// The name as RigTune shows it: the sign for a boolean, never a value.
		public String display() {
			return enabled == null ? "-XX:" + name : "-XX:" + (enabled ? "+" : "-") + name;
		}

		@Override
		public String toString() {
			return display();
		}
	}

	private final Map<String, XxFlag> xx;
	private final Set<String> propertyKeys;
	private final long maxHeapBytes;
	private final long initialHeapBytes;
	private final long youngGenBytes;
	private final int maxHeapSettings;
	private final int junk;

	private JvmArgs(Map<String, XxFlag> xx, Set<String> propertyKeys, long maxHeapBytes, long initialHeapBytes, long youngGenBytes,
			int maxHeapSettings, int junk) {
		this.xx = Collections.unmodifiableMap(xx);
		this.propertyKeys = Collections.unmodifiableSet(propertyKeys);
		this.maxHeapBytes = maxHeapBytes;
		this.initialHeapBytes = initialHeapBytes;
		this.youngGenBytes = youngGenBytes;
		this.maxHeapSettings = maxHeapSettings;
		this.junk = junk;
	}

	public static JvmArgs parse(@Nullable List<String> arguments) {
		Map<String, XxFlag> xx = new LinkedHashMap<>();
		Set<String> properties = new LinkedHashSet<>();
		long maxHeap = -1;
		long initialHeap = -1;
		long youngGen = -1;
		int maxHeapSettings = 0;
		int junk = 0;
		for (String raw : arguments == null ? List.<String>of() : arguments) {
			String token = raw == null ? "" : raw.strip();
			if (token.isEmpty()) {
				junk++;
				continue;
			}
			if (token.startsWith("-XX:")) {
				Matcher m = XX.matcher(token);
				if (!m.matches()) {
					junk++;
					continue;
				}
				String name = m.group(2) != null ? m.group(2) : m.group(3);
				XxFlag flag = new XxFlag(name, m.group(1) == null ? null : m.group(1).equals("+"));
				if (flag.hasValue() && (name.equals("MaxHeapSize") || name.equals("InitialHeapSize"))) {
					long bytes = size(token.substring(token.indexOf('=') + 1));
					if (bytes < 0) {
						junk++;
						continue;
					}
					if (name.equals("MaxHeapSize")) {
						maxHeap = bytes;
						maxHeapSettings++;
					} else {
						initialHeap = bytes;
					}
				}
				xx.remove(name);
				xx.put(name, flag);
				continue;
			}
			Matcher sized = SIZED.matcher(token);
			if (sized.matches()) {
				long bytes = size(sized.group(2));
				if (bytes < 0) {
					junk++;
					continue;
				}
				switch (sized.group(1)) {
					case "mx" -> {
						maxHeap = bytes;
						maxHeapSettings++;
					}
					case "ms" -> initialHeap = bytes;
					case "mn" -> youngGen = bytes;
					default -> {
						// -Xss: the thread stack size (the version JSON's, on x86); not used.
					}
				}
				continue;
			}
			if (token.startsWith("-D")) {
				int eq = token.indexOf('=');
				String key = eq < 0 ? token.substring(2) : token.substring(2, eq);
				if (key.isBlank()) {
					junk++;
				} else {
					properties.add(key);
				}
			}
		}
		return new JvmArgs(xx, properties, maxHeap, initialHeap, youngGen, maxHeapSettings, junk);
	}

	// HotSpot's size syntax: digits and an optional k/m/g/t (either case). -1 when it isn't one, or overflows.
	public static long size(@Nullable String text) {
		if (text == null) {
			return -1;
		}
		Matcher m = SIZE.matcher(text);
		if (!m.matches()) {
			return -1;
		}
		long value;
		try {
			value = Long.parseLong(m.group(1));
		} catch (NumberFormatException e) {
			return -1;
		}
		int shift = switch (m.group(2).toLowerCase(java.util.Locale.ROOT)) {
			case "k" -> 10;
			case "m" -> 20;
			case "g" -> 30;
			case "t" -> 40;
			default -> 0;
		};
		return value > (Long.MAX_VALUE >> shift) ? -1 : value << shift;
	}

	// The -XX flags by name, in the order of each one's last occurrence.
	public Map<String, XxFlag> xx() {
		return xx;
	}

	public @Nullable XxFlag xx(String name) {
		return xx.get(name);
	}

	// Only the names of the -D properties.
	public Set<String> propertyKeys() {
		return propertyKeys;
	}

	// The effective -Xmx (the last -Xmx or -XX:MaxHeapSize), or -1.
	public long maxHeapBytes() {
		return maxHeapBytes;
	}

	public long initialHeapBytes() {
		return initialHeapBytes;
	}

	public long youngGenBytes() {
		return youngGenBytes;
	}

	// How many times the maximum heap was set (-Xmx and -XX:MaxHeapSize together); 2 or more means a launcher's memory
	// setting and a typed -Xmx are both there (docs/research/v0.4/launcher-steps.md finding 3).
	public int maxHeapSettings() {
		return maxHeapSettings;
	}

	public int junk() {
		return junk;
	}

	@Override
	public String toString() {
		return "JvmArgs[xx=" + xx.values() + ", properties=" + propertyKeys + ", maxHeapBytes=" + maxHeapBytes + ", initialHeapBytes="
				+ initialHeapBytes + ", youngGenBytes=" + youngGenBytes + ", maxHeapSettings=" + maxHeapSettings + ", junk=" + junk + "]";
	}
}
