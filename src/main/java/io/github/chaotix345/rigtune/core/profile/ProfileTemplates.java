package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.recommend.Recommender.Clamp;
import io.github.chaotix345.rigtune.core.recommend.Recommender.SettingTarget;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.ProfileTemplate;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingRule;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The templates Max FPS, Balanced, Quality, Battery and Recording (docs/v0.4/SPEC.md 4, research profiles.md §4), computed
// on demand in layers: the baseline ("My settings") -> the rules' value entries evaluated with the template's goal and
// forced facts -> the template's own entries (rules-v2 `profileTemplates`; `$recordingFps` resolved only here) -> every
// clamp (the rules' and the template's) last -> only managed keys with a value inside the share table's bounds. The section
// comes from the active rules, else the bundled rules (a v1 document or an old cache lacks it), else the copy below.
// Templates never use server limits (plan review X-M3).
public final class ProfileTemplates {
	public enum TemplateId {
		MAX_FPS("max_fps", Goal.PERFORMANCE), BALANCED("balanced", Goal.BALANCED), QUALITY("quality", Goal.QUALITY),
		BATTERY("battery", Goal.PERFORMANCE), RECORDING("recording", Goal.BALANCED);

		private final String id;
		private final Goal goal;

		TemplateId(String id, Goal goal) {
			this.id = id;
			this.goal = goal;
		}

		public String id() {
			return id;
		}

		public Goal defaultGoal() {
			return goal;
		}

		public static @Nullable TemplateId of(@Nullable String id) {
			for (TemplateId template : values()) {
				if (template.id.equals(id)) {
					return template;
				}
			}
			return null;
		}
	}

	public static final String RECORDING_FPS = "$recordingFps";

	// The fallback when neither the active nor the bundled rules have the section (profiles.md §4.2 plus Battery's DH entry).
	static final String BUILT_IN = """
			{"templates": [
			  {"id": "max_fps", "goal": "performance", "settings": [
			    {"key": "vanilla.maxFps", "value": 260}, {"key": "vanilla.enableVsync", "value": false}]},
			  {"id": "balanced", "goal": "balanced"},
			  {"id": "quality", "goal": "quality"},
			  {"id": "battery", "goal": "performance", "facts": {"onBattery": true, "hasBattery": true}, "settings": [
			    {"key": "iris.enableShaders", "value": false, "when": {"modPresent": ["iris"]}},
			    {"key": "dh.client.advanced.debugging.rendererMode", "value": "DISABLED", "when": {"modPresent": ["distanthorizons"]}},
			    {"key": "vanilla.renderClouds", "value": "false"},
			    {"key": "vanilla.renderDistance", "max": 8}, {"key": "vanilla.simulationDistance", "max": 6},
			    {"key": "vanilla.particles", "min": 1}]},
			  {"id": "recording", "goal": "balanced", "settings": [
			    {"key": "vanilla.maxFps", "value": "$recordingFps"},
			    {"key": "vanilla.enableVsync", "value": true, "when": {"not": {"refreshRateAtLeast": 61}}},
			    {"key": "vanilla.enableVsync", "value": false, "when": {"refreshRateAtLeast": 61}},
			    {"key": "vanilla.inactivityFpsLimit", "value": "minimized"},
			    {"key": "sodium.performance.chunk_build_defer_mode", "value": "ALWAYS", "when": {"modPresent": ["sodium"]}},
			    {"key": "vanilla.prioritizeChunkUpdates", "value": 0, "when": {"modAbsent": ["sodium"]}}]}
			]}
			""";
	private static final RulesDocument.ProfileTemplates BUILT_IN_SECTION = RulesLoader.parse(
			"{\"schemaVersion\": 2, \"profileTemplates\": " + BUILT_IN + "}").profileTemplates;

	// values: key -> value for the managed keys; clamps: every clamp that changed a value.
	public record Result(Map<String, String> values, List<Clamp> clamps) {
		public Result {
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
			clamps = List.copyOf(clamps);
		}
	}

	private ProfileTemplates() {
	}

	public static Result compute(TemplateId id, RulesDocument active, @Nullable RulesDocument bundled, HardwareProfile hardware,
			List<InstalledMod> mods, SettingsSnapshot snapshot, Map<String, String> baseline) {
		ProfileTemplate template = definition(id, active, bundled);
		Goal goal = goal(template.goal, id.defaultGoal());
		EvalContext ctx = Recommender.context(active, withFacts(hardware, template.facts), mods, snapshot, goal);
		Map<String, SettingTarget> targets = new LinkedHashMap<>();
		baseline.forEach((key, value) -> {
			if (ShareKeys.managed(key) && value != null) {
				targets.put(key, target(value));
			}
		});
		List<SettingRule> rules = Recommender.supportedSettings(active.settings);
		List<SettingRule> own = Recommender.supportedSettings(template.settings);
		targets.putAll(Recommender.settingValues(rules, ctx, Map.of()));
		targets.putAll(Recommender.settingValues(own, ctx, Map.of(RECORDING_FPS, Integer.toString(recordingFps(hardware.display() == null ? -1
				: hardware.display().refreshRate())))));
		List<SettingRule> clampRules = new ArrayList<>(rules);
		clampRules.addAll(own);
		List<Clamp> clamps = new ArrayList<>();
		Recommender.applyClamps(targets, clampRules, ctx, snapshot, clamps);
		return new Result(inBounds(targets), clamps);
	}

	// A saved or imported profile's values with the rules' clamps applied (plan review P-L2), evaluated for this PC and goal;
	// each clamp that changed a value is listed (Preview shows them).
	public static Result clamp(Map<String, String> values, RulesDocument rules, HardwareProfile hardware, List<InstalledMod> mods,
			SettingsSnapshot snapshot, Goal goal) {
		EvalContext ctx = Recommender.context(rules, hardware, mods, snapshot, goal);
		Map<String, SettingTarget> targets = new LinkedHashMap<>();
		values.forEach((key, value) -> {
			if (ShareKeys.managed(key) && value != null) {
				targets.put(key, target(value));
			}
		});
		List<Clamp> clamps = new ArrayList<>();
		List<SettingRule> clampRules = Recommender.supportedSettings(rules.settings).stream().filter(r -> targets.containsKey(r.key)).toList();
		Recommender.applyClamps(targets, clampRules, ctx, snapshot, clamps);
		return new Result(inBounds(targets), clamps);
	}

	// Recording's frame cap: 60 on a display of 60 Hz or more (or unknown), else its rate rounded down to 10, at least 30.
	public static int recordingFps(int refreshRate) {
		if (refreshRate <= 0 || refreshRate >= 60) {
			return 60;
		}
		return Math.max(30, refreshRate / 10 * 10);
	}

	// The template's definition: the active rules' entry, else the bundled rules', else the built-in one. An entry this client
	// can't use (an unknown `requires`) counts as absent.
	static ProfileTemplate definition(TemplateId id, RulesDocument active, @Nullable RulesDocument bundled) {
		for (RulesDocument.ProfileTemplates section : new RulesDocument.ProfileTemplates[] {active == null ? null : active.profileTemplates,
				bundled == null ? null : bundled.profileTemplates, BUILT_IN_SECTION}) {
			if (section == null || section.templates == null) {
				continue;
			}
			for (ProfileTemplate template : section.templates) {
				if (template != null && id.id().equals(template.id) && Recommender.supported(template.requires)) {
					return template;
				}
			}
		}
		throw new IllegalStateException("No built-in template " + id.id());
	}

	private static Goal goal(@Nullable String name, Goal fallback) {
		if (name == null) {
			return fallback;
		}
		try {
			return Goal.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	// Only onBattery and hasBattery can be forced.
	private static HardwareProfile withFacts(HardwareProfile hw, @Nullable Map<String, Boolean> facts) {
		if (facts == null || facts.isEmpty()) {
			return hw;
		}
		boolean hasBattery = Boolean.TRUE.equals(facts.getOrDefault("hasBattery", hw.hasBattery()));
		boolean onBattery = Boolean.TRUE.equals(facts.getOrDefault("onBattery", hw.onBattery()));
		return new HardwareProfile(hw.cpu(), hw.gpu(), hw.totalRamMb(), hw.maxHeapMb(), hw.display(), hasBattery, onBattery, hw.osName(),
				hw.mcVersion(), hw.flags());
	}

	private static SettingTarget target(String value) {
		return new SettingTarget(value, "", Impact.LOW, true);
	}

	private static Map<String, String> inBounds(Map<String, SettingTarget> targets) {
		Map<String, String> out = new LinkedHashMap<>();
		for (ShareKeys.Key key : ShareKeys.V1) {
			SettingTarget target = targets.get(key.key());
			if (target != null && key.encode(target.value()) != null) {
				out.put(key.key(), target.value());
			}
		}
		return out;
	}
}
