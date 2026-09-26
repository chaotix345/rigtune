package io.github.chaotix345.rigtune.core.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class Condition {
	public Boolean always;
	public Integer tierAtLeast;
	public Integer tierAtMost;
	public Integer rawTierAtLeast;
	public Integer rawTierAtMost;
	public List<String> gpuVendor;
	public Boolean gpuIntegrated;
	public Integer gpuTierAtLeast;
	public Integer gpuTierAtMost;
	public Integer cpuTierAtLeast;
	public Integer cpuTierAtMost;
	public Boolean hasBattery;
	public Boolean onBattery;
	public Long heapMbAtLeast;
	public Long heapMbAtMost;
	public Long ramMbAtLeast;
	public Long ramMbAtMost;
	public Long vramMbAtLeast;
	public Long vramMbAtMost;
	public Integer refreshRateAtLeast;
	public List<String> backend;
	public List<String> os;
	public List<String> goal;
	public List<String> mcVersion;
	public List<String> modPresent;
	public List<String> modAbsent;
	public List<String> flags;
	public List<Condition> anyOf;
	public Condition not;

	// Schema v2 (docs/v0.2/SPEC.md item 2). Only rules-v2.json may use these; the updater keeps them out of rules-v1.json.
	public String gpuModelMatches;
	public Long displayPixelsAtLeast;
	public Long displayPixelsAtMost;
	public Map<String, String> modVersion;
	public String mcVersionRange;
	// settings key -> expected value, compared with the SettingsSnapshot after SettingValues normalisation.
	public Map<String, String> settingIs;

	// v0.4, rules-v2 only (docs/v0.4/SPEC.md 9): {"vendor": <gpuVendor>, "atLeast": "526.47", "atMost": "536.22"}. Unknown to
	// 0.2.0/0.3.0, where it poisons the condition (fail closed).
	public Map<String, String> driverVersion;
	// v0.4 Stutter Doctor keys (docs/v0.4/SPEC.md 5), allowed only inside stutterAdvice. They need EvalContext.stutter
	// (StutterFacts) and are UNKNOWN without it, so they can never fire in the main list. Every one is an adapter-vetted
	// type (plan review K-M1), so a bad value poisons only its condition: shares and percentages are whole percent (0-100);
	// spikesPerMinuteAtLeast is spikes per minute x10 (30 = 3 a minute); the share maps (cause or tag -> minimum whole
	// percent) are Map<String, String>, their numbers parsed by the evaluator (UNKNOWN when one isn't a whole number).
	// gcCollector: g1, zgc, shenandoah, parallel, serial.
	public Map<String, String> stutterShareAtLeast;
	public Map<String, String> stutterTaggedShareAtLeast;
	public Integer gcFullPausesAtLeast;
	public Integer gcStallsAtLeast;
	public Integer gcExplicitPausesAtLeast;
	public Integer liveSetPercentAtLeast;
	public Long heapRaiseRoomMbAtLeast;
	public Integer cpuContentionShareAtLeast;
	public Integer spikesPerMinuteAtLeast;
	public List<String> gcCollector;

	// Keys of this object that this client doesn't know, filled in while parsing. Any unknown key anywhere in a
	// condition tree makes the whole top-level condition false (fail closed).
	public transient Set<String> unknownFields;

	// gpuModelMatches compiled once by ConditionEvaluator (benign race: both writers store the same result).
	transient volatile Pattern modelPattern;
	transient volatile boolean modelPatternInvalid;
}
