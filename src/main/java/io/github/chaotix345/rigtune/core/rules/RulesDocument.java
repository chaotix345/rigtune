package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonElement;
import io.github.chaotix345.rigtune.core.model.Impact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RulesDocument {
	public int schemaVersion;
	public int revision;
	public String generatedAt;
	public String minModVersion;
	public List<GpuTierRule> gpuTiers = new ArrayList<>();
	public Map<String, Integer> gpuVendorFallback = new LinkedHashMap<>();
	public List<CpuTierRule> cpuTiers = new ArrayList<>();
	public List<HeapTierRule> heapTiers = new ArrayList<>();
	public List<ModRule> mods = new ArrayList<>();
	public List<ObsoleteRule> obsolete = new ArrayList<>();
	public List<SettingRule> settings = new ArrayList<>();
	public List<AdviceRule> advice = new ArrayList<>();
	public Map<String, List<String>> availability = new LinkedHashMap<>();
	public Map<String, UpstreamPack> upstream = new LinkedHashMap<>();

	private transient String source;

	public String source() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	void fillDefaults() {
		if (gpuTiers == null) gpuTiers = new ArrayList<>();
		if (gpuVendorFallback == null) gpuVendorFallback = new LinkedHashMap<>();
		if (cpuTiers == null) cpuTiers = new ArrayList<>();
		if (heapTiers == null) heapTiers = new ArrayList<>();
		if (mods == null) mods = new ArrayList<>();
		if (obsolete == null) obsolete = new ArrayList<>();
		if (settings == null) settings = new ArrayList<>();
		if (advice == null) advice = new ArrayList<>();
		if (availability == null) availability = new LinkedHashMap<>();
		if (upstream == null) upstream = new LinkedHashMap<>();
		gpuTiers.removeIf(r -> r == null);
		cpuTiers.removeIf(r -> r == null);
		heapTiers.removeIf(r -> r == null);
		mods.removeIf(r -> r == null || r.slug == null);
		obsolete.removeIf(r -> r == null);
		settings.removeIf(r -> r == null || r.key == null);
		advice.removeIf(r -> r == null || r.id == null);
		for (ModRule mod : mods) {
			if (mod.modIds == null) mod.modIds = new ArrayList<>();
			if (mod.conflictsWith == null) mod.conflictsWith = new ArrayList<>();
		}
		for (ObsoleteRule rule : obsolete) {
			if (rule.modIds == null) rule.modIds = new ArrayList<>();
		}
	}

	public static Impact impactOf(String value, Impact fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Impact.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	public abstract static class PatternRule {
		public String pattern;
		private transient Pattern compiled;
		private transient boolean invalid;

		public Pattern compiled() {
			if (compiled == null && !invalid) {
				try {
					compiled = Pattern.compile(pattern);
				} catch (PatternSyntaxException | NullPointerException e) {
					invalid = true;
				}
			}
			return compiled;
		}
	}

	public static final class GpuTierRule extends PatternRule {
		public String vendor;
		public Boolean integrated;
		public int tier;
	}

	public static final class CpuTierRule extends PatternRule {
		public int tier;
	}

	public static final class HeapTierRule {
		public long atLeastMb;
		public int tier;
	}

	public static final class ModRule {
		public String slug;
		public String projectId;
		public String title;
		public List<String> modIds = new ArrayList<>();
		public String category;
		public String impact;
		public String stability;
		public String reason;
		public Condition recommendWhen;
		public Condition avoidWhen;
		public String avoidReason;
		public List<String> conflictsWith = new ArrayList<>();
		public Boolean defaultSelected;
		public Map<String, Boolean> upstream;

		public String displayTitle() {
			return title != null ? title : slug;
		}

		public boolean alpha() {
			return "alpha".equalsIgnoreCase(stability);
		}
	}

	public static final class ObsoleteRule {
		public List<String> modIds = new ArrayList<>();
		public String title;
		public String reason;
		public String replacement;
	}

	public static final class SettingRule {
		public String key;
		public JsonElement value;
		public Double min;
		public Double max;
		public Condition when;
		public String reason;
		public String impact;
		public Boolean defaultSelected;

		public boolean isValueEntry() {
			return value != null && !value.isJsonNull();
		}

		public boolean isClampEntry() {
			return !isValueEntry() && (min != null || max != null);
		}
	}

	public static final class AdviceRule {
		public String id;
		public Condition when;
		public String impact;
		public String title;
		public String text;
		public String kind;
	}

	public static final class UpstreamPack {
		public String mcVersion;
		public List<String> slugs = new ArrayList<>();
	}
}
