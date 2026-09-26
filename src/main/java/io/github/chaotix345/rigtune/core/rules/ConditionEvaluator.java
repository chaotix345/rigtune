package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.hardware.GpuClassifier;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.stutter.Attributor;
import io.github.chaotix345.rigtune.core.stutter.StutterFacts;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.TRUE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;

// Evaluates rule conditions to TRUE, FALSE or UNKNOWN (docs/RULES_SCHEMA.md "Evaluation"). Only TRUE makes a rule fire.
// An unknown or null key anywhere in a condition tree makes the whole condition UNKNOWN; undecidable values (no GPU
// info, a bad regex, unknown RAM, a value outside a field's vocabulary, ...) are UNKNOWN where they occur and combine
// with Kleene logic, so a `not` can never turn "can't tell" into "yes".
public final class ConditionEvaluator {
	public static final Set<String> GPU_VENDORS = Arrays.stream(GpuVendor.values())
			.map(v -> v.name().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
	public static final Set<String> BACKENDS = Set.of("opengl", "vulkan");
	public static final List<String> OS_FAMILIES = List.of("windows", "macos", "linux");
	public static final Set<String> GOALS = Arrays.stream(Goal.values())
			.map(g -> g.name().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
	public static final String BACKEND_VULKAN_FLAG = "backend-vulkan";
	public static final Set<String> FLAGS = Set.of(BACKEND_VULKAN_FLAG, "shaders-enabled");
	public static final String SODIUM_WORKAROUND_FLAG = "sodium-workaround:";
	// The gcCollector vocabulary (docs/v0.4/SPEC.md 5): GcKind's families.
	public static final Set<String> GC_COLLECTORS = Set.of("g1", "zgc", "shenandoah", "parallel", "serial");

	private ConditionEvaluator() {
	}

	public static boolean matches(Condition c, EvalContext ctx) {
		return evaluate(c, ctx) == TRUE;
	}

	// A missing condition is TRUE, as in v1.
	public static Truth evaluate(Condition c, EvalContext ctx) {
		if (c == null) {
			return TRUE;
		}
		return poisoned(c) ? UNKNOWN : node(c, ctx);
	}

	static boolean poisoned(Condition c) {
		if (c.unknownFields != null && !c.unknownFields.isEmpty()) {
			return true;
		}
		if (c.not != null && poisoned(c.not)) {
			return true;
		}
		return c.anyOf != null && c.anyOf.stream().anyMatch(sub -> sub == null || poisoned(sub));
	}

	private static Truth node(Condition c, EvalContext ctx) {
		HardwareProfile hw = ctx.hardware();
		TierResult tier = ctx.tier();
		GpuInfo gpu = hw.gpu();
		Truth t = c.always == null ? TRUE : Truth.of(c.always);
		t = and(t, () -> range(tier.effectiveTier(), c.tierAtLeast, c.tierAtMost));
		t = and(t, () -> range(tier.rawTier(), c.rawTierAtLeast, c.rawTierAtMost));
		t = and(t, () -> range(ctx.gpu().tier(), c.gpuTierAtLeast, c.gpuTierAtMost));
		t = and(t, () -> range(tier.cpuTier(), c.cpuTierAtLeast, c.cpuTierAtMost));
		t = and(t, () -> c.gpuVendor == null ? TRUE : gpuVendor(c.gpuVendor, ctx.gpu().vendor()));
		t = and(t, () -> c.gpuIntegrated == null ? TRUE
				: ctx.gpu().vendor() == GpuVendor.UNKNOWN ? UNKNOWN : Truth.of(c.gpuIntegrated == ctx.gpu().integrated()));
		t = and(t, () -> c.hasBattery == null ? TRUE : Truth.of(c.hasBattery == hw.hasBattery()));
		t = and(t, () -> c.onBattery == null ? TRUE : Truth.of(c.onBattery == hw.onBattery()));
		t = and(t, () -> knownRange(hw.maxHeapMb(), c.heapMbAtLeast, c.heapMbAtMost));
		t = and(t, () -> knownRange(hw.totalRamMb(), c.ramMbAtLeast, c.ramMbAtMost));
		t = and(t, () -> knownRange(gpu == null ? -1 : gpu.vramMb(), c.vramMbAtLeast, c.vramMbAtMost));
		t = and(t, () -> c.refreshRateAtLeast == null ? TRUE : refreshRate(hw.display(), c.refreshRateAtLeast));
		t = and(t, () -> c.displayPixelsAtLeast == null && c.displayPixelsAtMost == null ? TRUE
				: displayPixels(hw.display(), c.displayPixelsAtLeast, c.displayPixelsAtMost));
		t = and(t, () -> c.backend == null ? TRUE : backend(c.backend, gpu));
		t = and(t, () -> c.os == null ? TRUE : os(c.os, hw.osName()));
		t = and(t, () -> c.goal == null ? TRUE : goal(c.goal, ctx.goal()));
		t = and(t, () -> c.mcVersion == null ? TRUE : hw.mcVersion() == null ? UNKNOWN : Truth.of(c.mcVersion.contains(hw.mcVersion())));
		t = and(t, () -> c.modPresent == null ? TRUE : allEntries(c.modPresent, id -> ctx.loadedModIds().contains(id)));
		t = and(t, () -> c.modAbsent == null ? TRUE : allEntries(c.modAbsent, id -> !ctx.loadedModIds().contains(id)));
		t = and(t, () -> c.flags == null ? TRUE : flags(c.flags, hw.flags(), gpu));
		t = and(t, () -> c.gpuModelMatches == null ? TRUE : gpuModelMatches(c, gpu));
		t = and(t, () -> c.modVersion == null ? TRUE : modVersions(c.modVersion, ctx));
		t = and(t, () -> c.mcVersionRange == null ? TRUE : mcVersionRange(c.mcVersionRange, hw.mcVersion()));
		t = and(t, () -> c.settingIs == null ? TRUE : settingIs(c.settingIs, ctx.settings()));
		t = and(t, () -> c.driverVersion == null ? TRUE : driverVersion(c.driverVersion, ctx));
		t = and(t, () -> hasStutterKey(c) ? stutter(c, ctx.stutter()) : TRUE);
		t = and(t, () -> c.anyOf == null ? TRUE : anyOf(c.anyOf, ctx));
		return and(t, () -> c.not == null ? TRUE : node(c.not, ctx).not());
	}

	private static Truth and(Truth sofar, Supplier<Truth> next) {
		return sofar == FALSE ? FALSE : sofar.and(next.get());
	}

	private static Truth anyOf(List<Condition> branches, EvalContext ctx) {
		Truth any = FALSE;
		for (Condition branch : branches) {
			any = any.or(node(branch, ctx));
			if (any == TRUE) {
				return TRUE;
			}
		}
		return any;
	}

	public static String osFamily(String osName) {
		String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT).trim();
		if (name.startsWith("windows")) {
			return "windows";
		}
		if (name.startsWith("mac") || name.startsWith("darwin") || name.contains("os x")) {
			return "macos";
		}
		if (name.startsWith("linux")) {
			return "linux";
		}
		return name;
	}

	// `os` entries match by prefix, so "win" is a known entry.
	public static boolean knownOsEntry(String entry) {
		String lower = entry.toLowerCase(Locale.ROOT);
		return !lower.isEmpty() && OS_FAMILIES.stream().anyMatch(family -> family.startsWith(lower));
	}

	public static boolean knownFlag(String flag) {
		return FLAGS.contains(flag) || flag.startsWith(SODIUM_WORKAROUND_FLAG) && flag.length() > SODIUM_WORKAROUND_FLAG.length();
	}

	// An OR over the listed values: TRUE if a known value matches; otherwise UNKNOWN if the subject is unknown or a value
	// is outside the vocabulary (a newer client might match it); otherwise FALSE. Values are compared in lower case.
	private static Truth anyEntry(List<String> wanted, Predicate<String> known, Predicate<String> matches, boolean subjectKnown) {
		boolean undecided = !subjectKnown;
		for (String entry : wanted) {
			String value = entry == null ? null : entry.toLowerCase(Locale.ROOT);
			if (value == null || !known.test(value)) {
				undecided = true;
			} else if (subjectKnown && matches.test(value)) {
				return TRUE;
			}
		}
		return undecided ? UNKNOWN : FALSE;
	}

	private static Truth allEntries(List<String> wanted, Predicate<String> holds) {
		Truth t = TRUE;
		for (String entry : wanted) {
			t = t.and(entry == null ? UNKNOWN : Truth.of(holds.test(entry)));
		}
		return t;
	}

	private static Truth gpuVendor(List<String> wanted, GpuVendor vendor) {
		String name = vendor.name().toLowerCase(Locale.ROOT);
		if (vendor == GpuVendor.UNKNOWN && wanted.stream().anyMatch(name::equalsIgnoreCase)) {
			return TRUE;
		}
		return anyEntry(wanted, GPU_VENDORS::contains, name::equals, vendor != GpuVendor.UNKNOWN);
	}

	private static Truth backend(List<String> wanted, GpuInfo gpu) {
		GraphicsBackend backend = gpu == null ? null : gpu.backend();
		boolean known = backend != null && backend != GraphicsBackend.UNKNOWN;
		String name = known ? backend.name().toLowerCase(Locale.ROOT) : "";
		return anyEntry(wanted, BACKENDS::contains, name::equals, known);
	}

	private static Truth os(List<String> wanted, String osName) {
		String family = osFamily(osName);
		return anyEntry(wanted, ConditionEvaluator::knownOsEntry, family::startsWith, osName != null && !osName.isBlank());
	}

	private static Truth goal(List<String> wanted, Goal goal) {
		String name = goal == null ? "" : goal.name().toLowerCase(Locale.ROOT);
		return anyEntry(wanted, GOALS::contains, name::equals, goal != null);
	}

	// Every listed flag must be present. A missing flag this client can detect is FALSE; one it can't is UNKNOWN, and so
	// is backend-vulkan while the backend itself is unknown (the flag is derived from it).
	private static Truth flags(List<String> wanted, Set<String> present, GpuInfo gpu) {
		Set<String> flags = present == null ? Set.of() : present;
		boolean backendKnown = gpu != null && gpu.backend() != null && gpu.backend() != GraphicsBackend.UNKNOWN;
		Truth t = TRUE;
		for (String flag : wanted) {
			if (flag == null || !flags.contains(flag)) {
				boolean decidable = flag != null && knownFlag(flag) && (backendKnown || !flag.equals(BACKEND_VULKAN_FLAG));
				t = t.and(decidable ? FALSE : UNKNOWN);
			}
		}
		return t;
	}

	private static Truth range(long value, Number atLeast, Number atMost) {
		return Truth.of((atLeast == null || value >= atLeast.longValue()) && (atMost == null || value <= atMost.longValue()));
	}

	private static Truth knownRange(long value, Number atLeast, Number atMost) {
		if (atLeast == null && atMost == null) {
			return TRUE;
		}
		return value > 0 ? range(value, atLeast, atMost) : UNKNOWN;
	}

	private static Truth refreshRate(DisplayInfo display, int atLeast) {
		int hz = display == null ? -1 : display.refreshRate();
		return hz > 0 ? Truth.of(hz >= atLeast) : UNKNOWN;
	}

	private static Truth displayPixels(DisplayInfo display, Long atLeast, Long atMost) {
		if (display == null || display.width() <= 0 || display.height() <= 0) {
			return UNKNOWN;
		}
		return range((long) display.width() * display.height(), atLeast, atMost);
	}

	// Found in the same subject string the gpuTiers patterns see, with the same length limit and read budget.
	private static Truth gpuModelMatches(Condition c, GpuInfo gpu) {
		String subject = GpuClassifier.subject(gpu);
		Pattern pattern = modelPattern(c);
		if (subject.isBlank() || pattern == null) {
			return UNKNOWN;
		}
		Boolean found = BudgetedChars.find(pattern, subject, BudgetedChars.DEFAULT_BUDGET);
		return found == null ? UNKNOWN : Truth.of(found);
	}

	private static Pattern modelPattern(Condition c) {
		if (c.modelPattern == null && !c.modelPatternInvalid) {
			try {
				if (c.gpuModelMatches.length() > RulesDocument.PatternRule.MAX_PATTERN_LENGTH) {
					throw new PatternSyntaxException("longer than " + RulesDocument.PatternRule.MAX_PATTERN_LENGTH + " characters", c.gpuModelMatches, -1);
				}
				c.modelPattern = Pattern.compile(c.gpuModelMatches);
			} catch (PatternSyntaxException e) {
				c.modelPatternInvalid = true;
			}
		}
		return c.modelPattern;
	}

	private static Truth modVersions(Map<String, String> wanted, EvalContext ctx) {
		Truth t = TRUE;
		for (Map.Entry<String, String> entry : wanted.entrySet()) {
			t = and(t, () -> modVersion(entry.getKey(), entry.getValue(), ctx));
		}
		return t;
	}

	private static Truth modVersion(String modId, String predicateText, EvalContext ctx) {
		VersionPredicate predicate = predicate(predicateText);
		if (modId == null || predicate == null) {
			return UNKNOWN;
		}
		if (!ctx.loadedModIds().contains(modId)) {
			return FALSE;
		}
		SemanticVersion version = semantic(ctx.modVersions() == null ? null : ctx.modVersions().get(modId));
		return version == null ? UNKNOWN : Truth.of(predicate.test(version));
	}

	// Every listed key must be in the current settings and equal the expected value; a key that isn't there is UNKNOWN
	// (the mod's config may be missing, unreadable or older).
	private static Truth settingIs(Map<String, String> wanted, SettingsSnapshot settings) {
		Truth t = TRUE;
		for (Map.Entry<String, String> entry : wanted.entrySet()) {
			t = and(t, () -> entry.getKey() == null || entry.getValue() == null || settings == null || !settings.has(entry.getKey()) ? UNKNOWN
					: Truth.of(SettingValues.same(settings.get(entry.getKey()), entry.getValue())));
		}
		return t;
	}

	// v0.4 contract stub (docs/v0.4/SPEC.md 9): filled by WS-R. UNKNOWN until then, so no rule using it can fire.
	private static Truth driverVersion(Map<String, String> wanted, EvalContext ctx) {
		return UNKNOWN;
	}

	public static boolean hasStutterKey(Condition c) {
		return c.stutterShareAtLeast != null || c.stutterTaggedShareAtLeast != null || c.gcFullPausesAtLeast != null
				|| c.gcStallsAtLeast != null || c.gcExplicitPausesAtLeast != null || c.liveSetPercentAtLeast != null
				|| c.heapRaiseRoomMbAtLeast != null || c.cpuContentionShareAtLeast != null || c.spikesPerMinuteAtLeast != null
				|| c.gcCollector != null;
	}

	// v0.4 (docs/v0.4/SPEC.md 5): the stutter keys against the Stutter Doctor's session facts. Without facts (the main
	// list) every one is UNKNOWN, so they can never fire there. Thresholds are whole numbers (plan review K-M1): shares
	// and percentages in whole percent, spikesPerMinuteAtLeast x10. A share-map value that isn't a whole number >= 0, a
	// cause or tag this version doesn't know, and an unknown fact (null) are UNKNOWN.
	private static Truth stutter(Condition c, @Nullable StutterFacts facts) {
		if (facts == null) {
			return UNKNOWN;
		}
		Truth t = c.stutterShareAtLeast == null ? TRUE : shares(c.stutterShareAtLeast, facts.claimedShares(), Attributor.CAUSES);
		t = and(t, () -> c.stutterTaggedShareAtLeast == null ? TRUE : shares(c.stutterTaggedShareAtLeast, facts.taggedShares(), Attributor.TAGS));
		t = and(t, () -> c.gcFullPausesAtLeast == null ? TRUE : Truth.of(facts.gcFullPauses() >= c.gcFullPausesAtLeast));
		t = and(t, () -> c.gcStallsAtLeast == null ? TRUE : Truth.of(facts.gcStalls() >= c.gcStallsAtLeast));
		t = and(t, () -> c.gcExplicitPausesAtLeast == null ? TRUE : Truth.of(facts.gcExplicitPauses() >= c.gcExplicitPausesAtLeast));
		t = and(t, () -> c.liveSetPercentAtLeast == null ? TRUE : atLeast(facts.liveSetPercent(), c.liveSetPercentAtLeast));
		t = and(t, () -> c.heapRaiseRoomMbAtLeast == null ? TRUE : atLeast(facts.heapRaiseRoomMb() == null ? null : facts.heapRaiseRoomMb().doubleValue(),
				c.heapRaiseRoomMbAtLeast));
		t = and(t, () -> c.cpuContentionShareAtLeast == null ? TRUE : atLeast(facts.cpuContentionShare(), c.cpuContentionShareAtLeast));
		t = and(t, () -> c.spikesPerMinuteAtLeast == null ? TRUE : Truth.of(facts.spikesPerMinute() * 10 >= c.spikesPerMinuteAtLeast));
		String collector = facts.gcCollector() == null ? "" : facts.gcCollector().toLowerCase(Locale.ROOT);
		return and(t, () -> c.gcCollector == null ? TRUE : anyEntry(c.gcCollector, GC_COLLECTORS::contains, collector::equals, !collector.isEmpty()));
	}

	private static Truth atLeast(@Nullable Double value, Number threshold) {
		return value == null ? UNKNOWN : Truth.of(value >= threshold.doubleValue());
	}

	// Every entry must hold: the measured share (percent; absent = 0) at least the entry's whole-percent threshold.
	private static Truth shares(Map<String, String> wanted, Map<String, Double> measured, List<String> vocabulary) {
		Truth t = TRUE;
		for (Map.Entry<String, String> entry : wanted.entrySet()) {
			Integer threshold = wholeNumber(entry.getValue());
			if (entry.getKey() == null || !vocabulary.contains(entry.getKey()) || threshold == null) {
				t = t.and(UNKNOWN);
				continue;
			}
			t = t.and(Truth.of(measured.getOrDefault(entry.getKey(), 0.0) >= threshold));
		}
		return t;
	}

	private static @Nullable Integer wholeNumber(@Nullable String text) {
		if (text == null) {
			return null;
		}
		try {
			BigDecimal value = new BigDecimal(text.trim());
			return value.signum() < 0 || value.stripTrailingZeros().scale() > 0 ? null : value.intValueExact();
		} catch (NumberFormatException | ArithmeticException e) {
			return null;
		}
	}

	private static Truth mcVersionRange(String predicateText, String mcVersion) {
		VersionPredicate predicate = predicate(predicateText);
		SemanticVersion version = semantic(mcVersion);
		return predicate == null || version == null ? UNKNOWN : Truth.of(predicate.test(version));
	}

	// Fabric Loader's predicate syntax, as in fabric.mod.json (">=0.6.0 <0.8.0", "~0.9", "*"). Fabric turns any term it
	// can't read as a semantic version into an exact string match (">=>=" becomes "= >="), which would quietly be FALSE,
	// so such a predicate counts as unparseable.
	private static VersionPredicate predicate(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			VersionPredicate predicate = VersionPredicate.parse(text);
			for (VersionPredicate.PredicateTerm term : predicate.getTerms()) {
				if (!(term.getReferenceVersion() instanceof SemanticVersion)) {
					return null;
				}
			}
			return predicate;
		} catch (Exception e) {
			return null;
		}
	}

	// Only semantic versions can be ordered; anything else (e.g. "mc26.2-0.9.2-fabric") is undecidable.
	private static SemanticVersion semantic(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Version.parse(text) instanceof SemanticVersion semantic ? semantic : null;
		} catch (Exception e) {
			return null;
		}
	}
}
