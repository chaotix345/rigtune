package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.GpuTierRule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class GpuClassifier {
	public static final int DEFAULT_TIER = 2;

	private static final Pattern SOFTWARE = Pattern.compile("(?i)llvmpipe|softpipe|lavapipe|swiftshader|microsoft basic render|gdi generic");
	private static final Pattern NVIDIA = Pattern.compile("(?i)nvidia|geforce|quadro|nouveau");
	private static final Pattern AMD = Pattern.compile("(?i)\\bamd\\b|advanced micro devices|radeon|\\bati\\b");
	private static final Pattern INTEL = Pattern.compile("(?i)intel");
	private static final Pattern APPLE = Pattern.compile("(?i)\\bapple\\b");
	private static final Pattern QUALCOMM = Pattern.compile("(?i)qualcomm|adreno");

	private static final Pattern INTEL_ARC = Pattern.compile("(?i)\\barc\\b");
	private static final Pattern AMD_GENERIC_IGPU = Pattern.compile("(?i)radeon\\s*(\\(tm\\))?\\s*graphics|vega\\s*\\d+\\s*graphics");
	private static final Pattern APPLE_SILICON = Pattern.compile("(?i)apple\\s*m\\d");

	private final List<GpuTierRule> rules;
	private final Map<String, Integer> vendorFallback;

	public GpuClassifier(List<GpuTierRule> rules, Map<String, Integer> vendorFallback) {
		this.rules = rules == null ? List.of() : rules;
		this.vendorFallback = vendorFallback == null ? Map.of() : vendorFallback;
	}

	public static GpuClassifier from(RulesDocument rules) {
		return new GpuClassifier(rules.gpuTiers, rules.gpuVendorFallback);
	}

	// The string the gpuTiers patterns (and the gpuModelMatches condition) are matched against.
	public static String subject(GpuInfo gpu) {
		String renderer = gpu == null || gpu.renderer() == null ? "" : gpu.renderer();
		String vendorString = gpu == null || gpu.vendorString() == null ? "" : gpu.vendorString();
		return renderer.isBlank() ? vendorString : renderer;
	}

	public GpuClass classify(GpuInfo gpu) {
		String renderer = gpu == null || gpu.renderer() == null ? "" : gpu.renderer();
		String vendorString = gpu == null || gpu.vendorString() == null ? "" : gpu.vendorString();
		String subject = subject(gpu);
		GpuVendor vendor = detectVendor(vendorString, renderer);

		for (GpuTierRule rule : rules) {
			if (!vendorCompatible(rule.vendor, vendor) || !rule.find(subject)) {
				continue;
			}
			GpuVendor resolved = vendor == GpuVendor.UNKNOWN || vendor == GpuVendor.OTHER ? parseVendor(rule.vendor, vendor) : vendor;
			boolean integrated = rule.integrated != null ? rule.integrated : integratedHeuristic(resolved, subject);
			int tier = resolved == GpuVendor.SOFTWARE ? 0 : clampTier(rule.tier);
			return new GpuClass(resolved, integrated, tier, rule.pattern);
		}

		if (vendor == GpuVendor.SOFTWARE) {
			return new GpuClass(vendor, false, 0, null);
		}
		int tier = clampTier(vendorFallback.getOrDefault(vendor.name().toLowerCase(Locale.ROOT), DEFAULT_TIER));
		return new GpuClass(vendor, integratedHeuristic(vendor, subject), tier, null);
	}

	public static GpuVendor detectVendor(String vendorString, String renderer) {
		String r = renderer == null ? "" : renderer;
		String v = vendorString == null ? "" : vendorString;
		if (SOFTWARE.matcher(r).find() || SOFTWARE.matcher(v).find()) {
			return GpuVendor.SOFTWARE;
		}
		GpuVendor fromRenderer = vendorIn(r);
		if (fromRenderer != GpuVendor.UNKNOWN) {
			return fromRenderer;
		}
		GpuVendor fromVendor = vendorIn(v);
		if (fromVendor != GpuVendor.UNKNOWN) {
			return fromVendor;
		}
		return r.isBlank() && v.isBlank() ? GpuVendor.UNKNOWN : GpuVendor.OTHER;
	}

	private static GpuVendor vendorIn(String s) {
		if (s.isBlank()) {
			return GpuVendor.UNKNOWN;
		}
		if (NVIDIA.matcher(s).find()) {
			return GpuVendor.NVIDIA;
		}
		if (AMD.matcher(s).find()) {
			return GpuVendor.AMD;
		}
		if (INTEL.matcher(s).find()) {
			return GpuVendor.INTEL;
		}
		if (APPLE.matcher(s).find()) {
			return GpuVendor.APPLE;
		}
		if (QUALCOMM.matcher(s).find()) {
			return GpuVendor.QUALCOMM;
		}
		return GpuVendor.UNKNOWN;
	}

	private static boolean integratedHeuristic(GpuVendor vendor, String renderer) {
		return switch (vendor) {
			case INTEL -> !INTEL_ARC.matcher(renderer).find();
			case AMD -> AMD_GENERIC_IGPU.matcher(renderer).find();
			case APPLE -> APPLE_SILICON.matcher(renderer).find();
			case QUALCOMM -> true;
			default -> false;
		};
	}

	private static boolean vendorCompatible(String ruleVendor, GpuVendor detected) {
		if (ruleVendor == null || detected == GpuVendor.UNKNOWN || detected == GpuVendor.OTHER) {
			return true;
		}
		return ruleVendor.equalsIgnoreCase(detected.name());
	}

	private static GpuVendor parseVendor(String value, GpuVendor fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return GpuVendor.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static int clampTier(int tier) {
		return Math.max(0, Math.min(5, tier));
	}
}
