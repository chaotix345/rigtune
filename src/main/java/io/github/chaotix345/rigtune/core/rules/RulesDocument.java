package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonElement;
import io.github.chaotix345.rigtune.RigTune;
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
	// v2: human-readable names for settings keys and their values.
	public Map<String, SettingLabel> settingLabels = new LinkedHashMap<>();
	// v0.4, rules-v2 only (docs/v0.4/SPEC.md C2), null when absent (older files, rules-v1.json): the Profiles templates
	// (item 4) and the Stutter Doctor's advice (item 5, entries `requires: ["stutter-doctor"]`). 0.2.0/0.3.0 ignore both.
	public ProfileTemplates profileTemplates;
	public List<AdviceRule> stutterAdvice;

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
		if (settingLabels == null) settingLabels = new LinkedHashMap<>();
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
		if (stutterAdvice != null) {
			stutterAdvice.removeIf(r -> r == null || r.id == null);
		}
		if (profileTemplates != null) {
			if (profileTemplates.templates == null) profileTemplates.templates = new ArrayList<>();
			profileTemplates.templates.removeIf(t -> t == null || t.id == null);
			for (ProfileTemplate template : profileTemplates.templates) {
				if (template.settings != null) template.settings.removeIf(r -> r == null || r.key == null);
			}
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
		public static final int MAX_PATTERN_LENGTH = 200;

		public String pattern;
		private transient Pattern compiled;
		private transient boolean invalid;

		// Null for a missing, invalid or overlong pattern.
		public Pattern compiled() {
			if (compiled == null && !invalid) {
				try {
					if (pattern.length() > MAX_PATTERN_LENGTH) {
						throw new PatternSyntaxException("longer than " + MAX_PATTERN_LENGTH + " characters", pattern, -1);
					}
					compiled = Pattern.compile(pattern);
				} catch (PatternSyntaxException | NullPointerException e) {
					invalid = true;
				}
			}
			return compiled;
		}

		// A match that runs out of its read budget (catastrophic backtracking) counts as no match.
		public boolean find(String subject) {
			Pattern p = compiled();
			if (p == null || subject == null) {
				return false;
			}
			Boolean found = BudgetedChars.find(p, subject, BudgetedChars.DEFAULT_BUDGET);
			if (found == null) {
				RigTune.LOGGER.warn("Rules regex {} gave up on \"{}\"; treating it as no match", pattern, subject);
				return false;
			}
			return found;
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
		// v2: client features this rule needs; a client that lacks one skips the rule.
		public List<String> requires;
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
		// v2: whether the "disable" suggestion from avoidWhen starts ticked (default true).
		public Boolean avoidSelected;
		// v2: when TRUE for an installed mod, its update is left to the mod itself (its own auto-updater is on).
		public Condition skipUpdateWhen;
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
		// v2: client features this rule needs; a client that lacks one skips the rule.
		public List<String> requires;
		public List<String> modIds = new ArrayList<>();
		public String title;
		public String reason;
		public String replacement;
	}

	public static final class SettingRule {
		// v2: client features this rule needs; a client that lacks one skips the rule.
		public List<String> requires;
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
		// v2: client features this rule needs; a client that lacks one skips the rule.
		public List<String> requires;
		public String id;
		public Condition when;
		public String impact;
		public String title;
		public String text;
		public String kind;
	}

	// v0.4 (docs/v0.4/SPEC.md 4, docs/research/v0.4/profiles.md §4.2): {"templates": [...]}.
	public static final class ProfileTemplates {
		public List<ProfileTemplate> templates = new ArrayList<>();
	}

	// id: a template id (max_fps, balanced, quality, battery, recording). goal: a Goal name in lower case. facts: hardware
	// facts forced before evaluation (only onBattery/hasBattery). settings: SettingRule entries (value or clamp) layered
	// over the rules' own. requires: client features this template needs; a client that lacks one skips it.
	public static final class ProfileTemplate {
		public List<String> requires;
		public String id;
		public String goal;
		public Map<String, Boolean> facts;
		public List<SettingRule> settings;
	}

	public static final class SettingLabel {
		public String name;
		public Map<String, String> values = new LinkedHashMap<>();
	}

	public static final class UpstreamPack {
		public String mcVersion;
		public List<String> slugs = new ArrayList<>();
	}
}
