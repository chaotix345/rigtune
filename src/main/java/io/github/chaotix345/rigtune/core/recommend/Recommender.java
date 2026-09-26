package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.hardware.CpuClassifier;
import io.github.chaotix345.rigtune.core.hardware.GpuClassifier;
import io.github.chaotix345.rigtune.core.hardware.TierCalculator;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.TierBasis;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.rules.Condition;
import io.github.chaotix345.rigtune.core.rules.ConditionEvaluator;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.AdviceRule;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.ModRule;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.ObsoleteRule;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingRule;
import io.github.chaotix345.rigtune.core.rules.Truth;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class Recommender {
	public static final String AVAILABILITY_UNKNOWN_NOTE = "(availability not confirmed)";
	public static final String ALPHA_NOTE = "(alpha build)";
	// Client features a rule's `requires` may name; a rule needing any other is skipped. 0.2.0 and 0.3.0 know none. 0.4 adds
	// "jvm-flags": the jvm-* advice testing the jvm- facts (docs/v0.4/SPEC.md 6). "stutter-doctor" is only StutterAdvisor's.
	public static final Set<String> SUPPORTED_FEATURES = Set.of("jvm-flags");
	static final String OUTSIDE_MODS_FOLDER = "It isn't in this instance's mods folder, so";
	// The Recommender's own text as translation keys with their English (docs/v0.3/SPEC.md item 9); rule text stays literal.
	private static final Text ALPHA = Text.of("rigtune.rec.alpha", ALPHA_NOTE);
	private static final Text AVAILABILITY_UNKNOWN = Text.of("rigtune.rec.availability_unknown", AVAILABILITY_UNKNOWN_NOTE);
	private static final Text OUTSIDE_REMOVE = Text.of("rigtune.rec.outside_mods_folder.remove", OUTSIDE_MODS_FOLDER + " remove it in your launcher.");
	private static final Text OUTSIDE_UPDATE = Text.of("rigtune.rec.outside_mods_folder.update", OUTSIDE_MODS_FOLDER + " update it in your launcher.");
	private static final Text BUNDLED = Text.of("rigtune.rec.bundled",
			"It is bundled inside another mod, so it has to be removed together with that mod.");

	private static final Comparator<Recommendation> ORDER = Comparator
			.comparing(Recommendation::category)
			.thenComparing(Recommendation::impact)
			.thenComparing(Recommendation::title, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Recommendation::id);

	private Recommender() {
	}

	public static Report recommend(RulesDocument rules, HardwareProfile hardware, List<InstalledMod> mods,
			SettingsSnapshot settings, OnlineData online, Goal goal) {
		return recommend(rules, hardware, mods, settings, online, goal, null);
	}

	public static Report recommend(RulesDocument rules, HardwareProfile hardware, List<InstalledMod> mods,
			SettingsSnapshot settings, OnlineData online, Goal goal, String modVersion) {
		return recommend(rules, hardware, mods, settings, online, goal, modVersion, Set.of());
	}

	// queuedUpdates: the mod ids with an update of their own waiting in mods/update/ (ModJars.queuedUpdates).
	public static Report recommend(RulesDocument rules, HardwareProfile hardware, List<InstalledMod> mods,
			SettingsSnapshot settings, OnlineData online, Goal goal, String modVersion, Set<String> queuedUpdates) {
		OnlineData data = online == null ? OnlineData.offline() : online;
		List<InstalledMod> installed = installed(mods);
		SettingsSnapshot snapshot = settings == null ? new SettingsSnapshot(Map.of()) : settings;
		EvalContext ctx = context(rules, hardware, installed, snapshot, goal);
		GpuClass gpuClass = ctx.gpu();
		TierResult tier = ctx.tier();

		Session session = new Session(rules, ctx, installed, snapshot, data, queuedUpdates == null ? Set.of() : queuedUpdates);
		section("obsolete", session::obsolete);
		section("avoided", session::avoided);
		section("conflicts", session::conflicts);
		section("additions", session::additions);
		section("updates", session::updates);
		section("settings", session::settings);
		section("advice", session::advice);
		section("rigtune version", () -> session.rigtuneVersion(modVersion));

		List<Recommendation> sorted = new ArrayList<>(session.recs.values());
		sorted.sort(ORDER);
		String source = rules.source() == null ? "unknown" : rules.source();
		return new Report(hardware, gpuClass, tier, goal, List.copyOf(sorted), rules.revision, source, data.online(), Instant.now(),
				tierBasis(rules, hardware, gpuClass, tier));
	}

	// docs/v0.4/SPEC.md 2j: what each component's tier rests on, for the tier badge's tooltip (the same classifiers as
	// context(); the CPU's matched row or formula inputs from classifyDetailed).
	private static TierBasis tierBasis(RulesDocument rules, HardwareProfile hardware, GpuClass gpu, TierResult tier) {
		TierBasis.Basis gpuBasis = gpu.matchedPattern() != null ? TierBasis.Basis.TABLE_MATCH : TierBasis.Basis.FALLBACK_ESTIMATE;
		return new TierBasis(new TierBasis.Gpu(gpu.tier(), gpuBasis, gpu.matchedPattern(), gpu.vendor(), gpu.integrated()),
				CpuClassifier.from(rules).classifyDetailed(hardware.cpu()), new TierBasis.Memory(tier.memTier(), TierBasis.Basis.TABLE_MATCH, hardware.maxHeapMb()));
	}

	private static void section(String name, Runnable body) {
		try {
			body.run();
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Skipping the {} recommendations after an error", name, e);
		}
	}

	// The context recommend() evaluates the rules in: the tiers for this hardware and goal, the loaded mods and the settings.
	public static EvalContext context(RulesDocument rules, HardwareProfile hardware, List<InstalledMod> mods, SettingsSnapshot settings,
			Goal goal) {
		GpuClass gpuClass = GpuClassifier.from(rules).classify(hardware.gpu());
		int cpuTier = CpuClassifier.from(rules).classify(hardware.cpu());
		int memTier = TierCalculator.heapTier(rules.heapTiers, hardware.maxHeapMb());
		TierResult tier = TierCalculator.calculate(gpuClass.tier(), cpuTier, memTier, goal);
		List<InstalledMod> installed = installed(mods);
		Set<String> loaded = installed.stream().map(InstalledMod::modId).collect(Collectors.toUnmodifiableSet());
		Map<String, String> versions = new HashMap<>();
		for (InstalledMod mod : installed) {
			if (mod.version() != null) {
				versions.putIfAbsent(mod.modId(), mod.version());
			}
		}
		SettingsSnapshot snapshot = settings == null ? new SettingsSnapshot(Map.of()) : settings;
		return new EvalContext(hardware, gpuClass, tier, goal, loaded, Map.copyOf(versions), snapshot);
	}

	private static List<InstalledMod> installed(List<InstalledMod> mods) {
		return mods == null ? List.of() : mods.stream().filter(m -> m.modId() != null).toList();
	}

	// A setting's target from the rules: the value, the reason, the impact and whether it starts ticked.
	public record SettingTarget(String value, String reason, Impact impact, boolean selected) {
	}

	// A clamp entry that changed a key's value (from: the value before, to: the clamped value; reason: the rule's).
	public record Clamp(String key, String from, String to, String reason) {
	}

	// Every setting target the rules this client supports give in ctx (plan review X-M3): the value entries, then the clamp
	// entries over them (or over the snapshot's value for a key without one). recommend() turns these into its `set:`
	// recommendations for the keys the snapshot has and whose value differs; nothing here is filtered by the snapshot.
	public static Map<String, SettingTarget> settingTargets(RulesDocument rules, EvalContext ctx, SettingsSnapshot snapshot) {
		List<SettingRule> supported = supportedSettings(rules.settings);
		Map<String, SettingTarget> targets = settingValues(supported, ctx, Map.of());
		applyClamps(targets, supported, ctx, snapshot, null);
		return targets;
	}

	// The entries a client with SUPPORTED_FEATURES reads.
	public static List<SettingRule> supportedSettings(List<SettingRule> rules) {
		return rules == null ? List.of() : rules.stream().filter(r -> supported(r.requires)).toList();
	}

	// The value entries of rules whose `when` matches in ctx, the last match per key winning, in first-match order. tokens:
	// extra "$token" values the caller resolves (the Profiles template layer's $recordingFps); a token nobody knows skips
	// the entry.
	public static Map<String, SettingTarget> settingValues(List<SettingRule> rules, EvalContext ctx, Map<String, String> tokens) {
		Map<String, SettingTarget> resolved = new LinkedHashMap<>();
		for (SettingRule rule : rules) {
			if (!rule.isValueEntry() || !ConditionEvaluator.matches(rule.when, ctx)) {
				continue;
			}
			String value = SettingValues.asString(rule.value);
			if (value == null) {
				continue;
			}
			String token = tokens.get(value.trim());
			if (token != null) {
				value = token;
			}
			value = SettingValues.resolveTokens(value, ctx.hardware().display());
			if (SettingValues.unresolvedToken(value)) {
				continue;
			}
			resolved.put(rule.key, new SettingTarget(value, Session.text(rule.reason), RulesDocument.impactOf(rule.impact, Impact.LOW),
					Session.selected(rule.defaultSelected)));
		}
		return resolved;
	}

	// The clamp entries of rules whose `when` matches in ctx, in order, over targets (or the snapshot's value for a key
	// without a target); a clamped key's target gets the clamped value with the clamp's reason appended. applied (if not
	// null) receives each clamp that changed a value.
	public static void applyClamps(Map<String, SettingTarget> targets, List<SettingRule> rules, EvalContext ctx, SettingsSnapshot snapshot,
			List<Clamp> applied) {
		for (SettingRule rule : rules) {
			if (!rule.isClampEntry() || !ConditionEvaluator.matches(rule.when, ctx)) {
				continue;
			}
			SettingTarget current = targets.get(rule.key);
			String before = current != null ? current.value() : snapshot.get(rule.key);
			BigDecimal number = SettingValues.number(before);
			if (number == null) {
				continue;
			}
			BigDecimal clamped = number;
			if (rule.min != null && clamped.compareTo(BigDecimal.valueOf(rule.min)) < 0) {
				clamped = BigDecimal.valueOf(rule.min);
			}
			if (rule.max != null && clamped.compareTo(BigDecimal.valueOf(rule.max)) > 0) {
				clamped = BigDecimal.valueOf(rule.max);
			}
			if (clamped.compareTo(number) == 0) {
				continue;
			}
			String reason = current == null ? Session.text(rule.reason) : (current.reason() + " " + Session.text(rule.reason)).trim();
			Impact impact = current != null ? current.impact() : RulesDocument.impactOf(rule.impact, Impact.LOW);
			boolean selected = current != null ? current.selected() : Session.selected(rule.defaultSelected);
			String value = SettingValues.format(clamped);
			targets.put(rule.key, new SettingTarget(value, reason, impact, selected));
			if (applied != null) {
				applied.add(new Clamp(rule.key, before, value, Session.text(rule.reason)));
			}
		}
	}

	public static boolean supported(List<String> requires) {
		return requires == null || requires.stream().allMatch(feature -> feature != null && SUPPORTED_FEATURES.contains(feature));
	}

	static int compareVersions(String a, String b) {
		String[] pa = a.split("[^0-9]+");
		String[] pb = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			BigInteger va = i < pa.length && !pa[i].isEmpty() ? new BigInteger(pa[i]) : BigInteger.ZERO;
			BigInteger vb = i < pb.length && !pb[i].isEmpty() ? new BigInteger(pb[i]) : BigInteger.ZERO;
			int order = va.compareTo(vb);
			if (order != 0) {
				return order;
			}
		}
		return 0;
	}

	private enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

	private static final class Session {
		final RulesDocument rules;
		// The rules this client understands: a rule whose `requires` names an unknown feature doesn't fire. Mod identity
		// (conflictsWith references by slug) still resolves through every ModRule.
		final List<ModRule> mods;
		final List<ObsoleteRule> obsolete;
		final List<AdviceRule> advice;
		final EvalContext ctx;
		final List<InstalledMod> installed;
		final SettingsSnapshot snapshot;
		final OnlineData online;
		final Set<String> queuedUpdates;
		final Map<String, ModRule> bySlug = new LinkedHashMap<>();
		final Map<String, Recommendation> recs = new LinkedHashMap<>();

		Session(RulesDocument rules, EvalContext ctx, List<InstalledMod> installed, SettingsSnapshot snapshot, OnlineData online,
				Set<String> queuedUpdates) {
			this.rules = rules;
			this.ctx = ctx;
			this.installed = installed;
			this.snapshot = snapshot;
			this.online = online;
			this.queuedUpdates = queuedUpdates;
			this.mods = rules.mods.stream().filter(r -> supported(r.requires)).toList();
			this.obsolete = rules.obsolete.stream().filter(r -> supported(r.requires)).toList();
			this.advice = rules.advice.stream().filter(r -> supported(r.requires)).toList();
			for (ModRule mod : rules.mods) {
				bySlug.putIfAbsent(mod.slug, mod);
			}
		}

		void obsolete() {
			for (ObsoleteRule rule : obsolete) {
				for (InstalledMod mod : installed) {
					if (!rule.modIds.contains(mod.modId())) {
						continue;
					}
					Text title = disableTitle(rule.title != null ? rule.title : name(mod));
					Text reason = rule.reason != null ? Text.literal(rule.reason)
							: Text.of("rigtune.rec.obsolete.reason", "%s is obsolete on this Minecraft version.", name(mod));
					disable(mod, Impact.HIGH, title, reason, true);
				}
			}
		}

		void avoided() {
			for (ModRule rule : mods) {
				if (rule.avoidWhen == null || !isInstalled(rule) || !matches(rule.avoidWhen)) {
					continue;
				}
				for (InstalledMod mod : installed) {
					if (rule.modIds.contains(mod.modId())) {
						Text reason = rule.avoidReason != null ? Text.literal(rule.avoidReason)
								: Text.of("rigtune.rec.unsuitable.reason", "%s doesn't suit this hardware.", rule.displayTitle());
						disable(mod, RulesDocument.impactOf(rule.impact, Impact.MEDIUM), disableTitle(rule.displayTitle()), reason,
								rule.avoidSelected == null || rule.avoidSelected);
					}
				}
			}
		}

		void conflicts() {
			for (ModRule rule : mods) {
				if (!isInstalled(rule)) {
					continue;
				}
				for (String ref : rule.conflictsWith) {
					String other = refKey(ref);
					if (other.equals(rule.slug) || !refInstalled(ref)) {
						continue;
					}
					boolean ordered = rule.slug.compareTo(other) <= 0;
					String id = "conflict:" + (ordered ? rule.slug + "+" + other : other + "+" + rule.slug);
					String a = rule.displayTitle();
					String b = refTitle(ref);
					put(Recommendation.of(id, Category.WARNING, Impact.HIGH, Text.of("rigtune.rec.conflict.title", "%s conflicts with %s", a, b),
							Text.of("rigtune.rec.conflict.reason",
									"%s and %s change the same parts of the game and shouldn't be installed together. Keep one and disable the other.", a, b),
							new Action.None(), false));
				}
			}
		}

		void additions() {
			String mc = ctx.hardware().mcVersion();
			Map<ModRule, Availability> offered = new LinkedHashMap<>();
			for (ModRule rule : mods) {
				// Fail closed: an avoidWhen this client can't decide blocks the addition too.
				if (isInstalled(rule) || !matches(rule.recommendWhen)
						|| (rule.avoidWhen != null && ConditionEvaluator.evaluate(rule.avoidWhen, ctx) != Truth.FALSE)
						|| rule.conflictsWith.stream().anyMatch(this::refInstalled)) {
					continue;
				}
				Availability availability = availability(rule.slug, mc);
				if (availability != Availability.UNAVAILABLE) {
					offered.put(rule, availability);
				}
			}
			// Two mods that conflict are never offered together (review 4, rules-accuracy-2): the one earlier in the
			// rules stays and names the ones it keeps out.
			ModConflicts conflicts = ModConflicts.of(rules);
			Map<ModRule, List<String>> keptOut = new LinkedHashMap<>();
			for (ModRule rule : offered.keySet()) {
				ModRule kept = keptOut.keySet().stream().filter(k -> conflicts.between(k.slug, rule.slug)).findFirst().orElse(null);
				if (kept != null) {
					keptOut.get(kept).add(rule.displayTitle());
				} else {
					keptOut.put(rule, new ArrayList<>());
				}
			}
			for (Map.Entry<ModRule, List<String>> entry : keptOut.entrySet()) {
				ModRule rule = entry.getKey();
				Availability availability = offered.get(rule);
				List<Text> notes = new ArrayList<>();
				if (!entry.getValue().isEmpty()) {
					List<String> titles = entry.getValue();
					Object names = titles.size() == 1 ? titles.getFirst()
							: Text.of("rigtune.rec.list.or", "%s or %s", String.join(", ", titles.subList(0, titles.size() - 1)), titles.getLast());
					notes.add(titles.size() == 1
							? Text.of("rigtune.rec.install.keeps_out", "RigTune doesn't also offer %s, which conflicts with it.", names)
							: Text.of("rigtune.rec.install.keeps_out.plural", "RigTune doesn't also offer %s, which conflict with it.", names));
				}
				if (rule.alpha()) {
					notes.add(ALPHA);
				}
				if (availability == Availability.UNKNOWN) {
					notes.add(AVAILABILITY_UNKNOWN);
				}
				boolean selected = !rule.alpha() && (rule.defaultSelected == null || rule.defaultSelected);
				String title = rule.displayTitle();
				put(Recommendation.of("add:" + rule.slug, Category.ADD_MOD, RulesDocument.impactOf(rule.impact, Impact.MEDIUM),
						Text.of("rigtune.rec.install.title", "Install %s", title), withNotes(rule.reason, notes),
						new Action.AddMod(rule.slug, rule.projectId, title), selected));
			}
		}

		void updates() {
			Map<String, UpdateInfo> updates = online.updatesByModId();
			if (updates == null) {
				return;
			}
			for (Map.Entry<String, UpdateInfo> entry : updates.entrySet()) {
				String modId = entry.getKey();
				UpdateInfo update = entry.getValue();
				InstalledMod mod = installed.stream().filter(m -> m.modId().equals(modId)).findFirst().orElse(null);
				if (mod == null || update == null || (mod.file() == null && mod.sha1() == null) || recs.containsKey("disable:" + modId)) {
					continue;
				}
				// Its own updater already has the next build waiting, whatever the rules say (review 4, rules-accuracy-1):
				// RigTune updating it too races that updater for the jar at exit.
				if (queuedUpdates.contains(modId)) {
					put(Recommendation.of("advice:update-queued:" + modId, Category.ADVICE, Impact.LOW,
							Text.of("rigtune.rec.update_queued.title", "%s has an update of its own waiting", name(mod)),
							Text.of("rigtune.rec.update_queued.reason", "It's in mods/update, so RigTune leaves it alone."), new Action.None(), false));
					continue;
				}
				ModRule selfUpdating = mods.stream()
						.filter(r -> r.skipUpdateWhen != null && r.modIds.contains(modId) && matches(r.skipUpdateWhen)).findFirst().orElse(null);
				if (selfUpdating != null) {
					put(Recommendation.of("advice:updates-itself:" + modId, Category.ADVICE, Impact.LOW,
							Text.of("rigtune.rec.updates_itself.title", "%s updates itself", selfUpdating.displayTitle()),
							Text.of("rigtune.rec.updates_itself.reason", "Its own auto-updater is on, so RigTune leaves its updates to it."),
							new Action.None(), false));
					continue;
				}
				String current = update.currentVersion() != null ? update.currentVersion() : mod.version();
				Text reason = Text.of("rigtune.rec.update.reason", "Version %s is available (you have %s).", update.newVersionNumber(), current);
				Text title = Text.of("rigtune.rec.update.title", "Update %s", name(mod));
				if (mod.file() == null) {
					put(Recommendation.of("update:" + modId, Category.UPDATE_MOD, Impact.LOW, title, Text.sentences(reason, OUTSIDE_UPDATE),
							new Action.None(), false));
					continue;
				}
				put(Recommendation.of("update:" + modId, Category.UPDATE_MOD, Impact.LOW, title, reason,
						new Action.UpdateMod(modId, mod.file(), update), true));
			}
		}

		void settings() {
			for (Map.Entry<String, SettingTarget> entry : settingTargets(rules, ctx, snapshot).entrySet()) {
				String key = entry.getKey();
				SettingTarget target = entry.getValue();
				if (!snapshot.has(key) || !SettingKeys.changeable(key) || !SettingKeys.safeValue(target.value())) {
					continue;
				}
				String current = snapshot.get(key);
				if (SettingValues.same(current, target.value())) {
					continue;
				}
				put(Recommendation.of("set:" + key, Category.SETTING, target.impact(),
						SettingValues.describe(rules.settingLabels.get(key), key, current, target.value()), Text.literal(target.reason()),
						new Action.SetSetting(key, current, target.value()), target.selected()));
			}
		}

		void advice() {
			for (AdviceRule rule : advice) {
				if (!matches(rule.when)) {
					continue;
				}
				String kind = rule.kind == null ? "info" : rule.kind.toLowerCase(Locale.ROOT);
				Category category = kind.equals("info") ? Category.ADVICE : Category.WARNING;
				Impact impact = switch (kind) {
					case "critical" -> Impact.HIGH;
					case "warning" -> RulesDocument.impactOf(rule.impact, Impact.MEDIUM);
					default -> RulesDocument.impactOf(rule.impact, Impact.LOW);
				};
				put(Recommendation.of("advice:" + rule.id, category, impact, Text.literal(rule.title != null ? rule.title : rule.id),
						Text.literal(text(rule.text)), new Action.None(), false));
			}
		}

		void rigtuneVersion(String modVersion) {
			if (modVersion == null || rules.minModVersion == null || compareVersions(modVersion, rules.minModVersion) >= 0) {
				return;
			}
			put(Recommendation.of("advice:update-rigtune", Category.ADVICE, Impact.MEDIUM, Text.of("rigtune.rec.update_rigtune.title", "Update RigTune"),
					Text.of("rigtune.rec.update_rigtune.reason",
							"These recommendations are written for RigTune %s or newer and you have %s. Update RigTune so every suggestion is understood correctly.",
							rules.minModVersion, modVersion),
					new Action.None(), false));
		}

		private void disable(InstalledMod mod, Impact impact, Text title, Text reason, boolean selected) {
			String id = "disable:" + mod.modId();
			if (recs.containsKey(id)) {
				return;
			}
			if (mod.file() == null) {
				// reason + " " + note, as before (a blank reason keeps its space).
				Text how = new Text.Joined(" ", List.of(reason, mod.sha1() != null ? OUTSIDE_REMOVE : BUNDLED));
				put(Recommendation.of(id, Category.REMOVE_MOD, impact, title, how, new Action.None(), false));
				return;
			}
			put(Recommendation.of(id, Category.REMOVE_MOD, impact, title, reason, new Action.DisableMod(mod.modId(), mod.file()), selected));
		}

		// The rule's reason, then RigTune's notes: the rule's text as it is without notes, else what the old
		// `(reason + " " + note).trim()` made (the reason's leading whitespace dropped, and the reason when that's all it is).
		private static Text withNotes(String reason, List<Text> notes) {
			String text = reason == null ? "" : reason;
			if (notes.isEmpty()) {
				return Text.literal(text);
			}
			List<Text> parts = new ArrayList<>();
			int start = 0;
			while (start < text.length() && text.charAt(start) <= ' ') {
				start++;
			}
			if (start < text.length()) {
				parts.add(Text.literal(text.substring(start)));
			}
			parts.addAll(notes);
			return new Text.Joined(" ", parts);
		}

		private static Text disableTitle(String name) {
			return Text.of("rigtune.rec.disable.title", "Disable %s", name);
		}

		private void put(Recommendation recommendation) {
			recs.putIfAbsent(recommendation.id(), recommendation);
		}

		private Availability availability(String slug, String mcVersion) {
			if (online.online() && online.availableBySlug() != null) {
				Boolean available = online.availableBySlug().get(slug);
				if (available != null) {
					return available ? Availability.AVAILABLE : Availability.UNAVAILABLE;
				}
			}
			List<String> offline = rules.availability.get(mcVersion);
			if (offline != null) {
				return offline.contains(slug) ? Availability.AVAILABLE : Availability.UNAVAILABLE;
			}
			return Availability.UNKNOWN;
		}

		private boolean matches(Condition condition) {
			return ConditionEvaluator.matches(condition, ctx);
		}

		private boolean isInstalled(ModRule rule) {
			return rule.modIds.stream().anyMatch(ctx.loadedModIds()::contains);
		}

		private boolean refInstalled(String ref) {
			if (ctx.loadedModIds().contains(ref)) {
				return true;
			}
			ModRule rule = bySlug.get(ref);
			return rule != null && isInstalled(rule);
		}

		private String refKey(String ref) {
			if (bySlug.containsKey(ref)) {
				return ref;
			}
			return rules.mods.stream().filter(m -> m.modIds.contains(ref)).map(m -> m.slug).findFirst().orElse(ref);
		}

		private String refTitle(String ref) {
			ModRule rule = bySlug.get(refKey(ref));
			if (rule != null) {
				return rule.displayTitle();
			}
			return installed.stream().filter(m -> m.modId().equals(ref)).map(Session::name).findFirst().orElse(ref);
		}

		private static String name(InstalledMod mod) {
			return mod.name() != null && !mod.name().isBlank() ? mod.name() : mod.modId();
		}

		private static String text(String value) {
			return Objects.requireNonNullElse(value, "");
		}

		private static boolean selected(Boolean defaultSelected) {
			return defaultSelected == null || defaultSelected;
		}
	}
}
