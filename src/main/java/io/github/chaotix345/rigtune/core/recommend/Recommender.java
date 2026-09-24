package io.github.chaotix345.rigtune.core.recommend;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
		OnlineData data = online == null ? OnlineData.offline() : online;
		GpuClass gpuClass = GpuClassifier.from(rules).classify(hardware.gpu());
		int cpuTier = CpuClassifier.from(rules).classify(hardware.cpu());
		int memTier = TierCalculator.heapTier(rules.heapTiers, hardware.maxHeapMb());
		TierResult tier = TierCalculator.calculate(gpuClass.tier(), cpuTier, memTier, goal);

		List<InstalledMod> installed = mods == null ? List.of() : mods.stream().filter(m -> m.modId() != null).toList();
		Set<String> loaded = installed.stream().map(InstalledMod::modId).collect(Collectors.toUnmodifiableSet());
		EvalContext ctx = new EvalContext(hardware, gpuClass, tier, goal, loaded);

		Session session = new Session(rules, ctx, installed, settings == null ? new SettingsSnapshot(Map.of()) : settings, data);
		session.obsolete();
		session.avoided();
		session.conflicts();
		session.additions();
		session.updates();
		session.settings();
		session.advice();
		session.rigtuneVersion(modVersion);

		List<Recommendation> sorted = new ArrayList<>(session.recs.values());
		sorted.sort(ORDER);
		String source = rules.source() == null ? "unknown" : rules.source();
		return new Report(hardware, gpuClass, tier, goal, List.copyOf(sorted), rules.revision, source, data.online(), Instant.now());
	}

	static int compareVersions(String a, String b) {
		String[] pa = a.split("[^0-9]+");
		String[] pb = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			long va = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
			long vb = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
			if (va != vb) {
				return Long.compare(va, vb);
			}
		}
		return 0;
	}

	private enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

	private record Resolved(String value, String reason, Impact impact, boolean selected) {
	}

	private static final class Session {
		final RulesDocument rules;
		final EvalContext ctx;
		final List<InstalledMod> installed;
		final SettingsSnapshot snapshot;
		final OnlineData online;
		final Map<String, ModRule> bySlug = new LinkedHashMap<>();
		final Map<String, Recommendation> recs = new LinkedHashMap<>();

		Session(RulesDocument rules, EvalContext ctx, List<InstalledMod> installed, SettingsSnapshot snapshot, OnlineData online) {
			this.rules = rules;
			this.ctx = ctx;
			this.installed = installed;
			this.snapshot = snapshot;
			this.online = online;
			for (ModRule mod : rules.mods) {
				bySlug.putIfAbsent(mod.slug, mod);
			}
		}

		void obsolete() {
			for (ObsoleteRule rule : rules.obsolete) {
				for (InstalledMod mod : installed) {
					if (!rule.modIds.contains(mod.modId())) {
						continue;
					}
					String title = "Disable " + (rule.title != null ? rule.title : name(mod));
					String reason = rule.reason != null ? rule.reason : name(mod) + " is obsolete on this Minecraft version.";
					disable(mod, Impact.HIGH, title, reason);
				}
			}
		}

		void avoided() {
			for (ModRule rule : rules.mods) {
				if (rule.avoidWhen == null || !isInstalled(rule) || !matches(rule.avoidWhen)) {
					continue;
				}
				for (InstalledMod mod : installed) {
					if (rule.modIds.contains(mod.modId())) {
						String reason = rule.avoidReason != null ? rule.avoidReason : rule.displayTitle() + " doesn't suit this hardware.";
						disable(mod, RulesDocument.impactOf(rule.impact, Impact.MEDIUM), "Disable " + rule.displayTitle(), reason);
					}
				}
			}
		}

		void conflicts() {
			for (ModRule rule : rules.mods) {
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
					put(new Recommendation(id, Category.WARNING, Impact.HIGH, a + " conflicts with " + b,
							a + " and " + b + " change the same parts of the game and shouldn't be installed together. Keep one and disable the other.",
							new Action.None(), false));
				}
			}
		}

		void additions() {
			String mc = ctx.hardware().mcVersion();
			for (ModRule rule : rules.mods) {
				if (isInstalled(rule) || !matches(rule.recommendWhen)
						|| (rule.avoidWhen != null && matches(rule.avoidWhen))
						|| rule.conflictsWith.stream().anyMatch(this::refInstalled)) {
					continue;
				}
				Availability availability = availability(rule.slug, mc);
				if (availability == Availability.UNAVAILABLE) {
					continue;
				}
				String reason = rule.reason == null ? "" : rule.reason;
				if (rule.alpha()) {
					reason = (reason + " " + ALPHA_NOTE).trim();
				}
				if (availability == Availability.UNKNOWN) {
					reason = (reason + " " + AVAILABILITY_UNKNOWN_NOTE).trim();
				}
				boolean selected = !rule.alpha() && (rule.defaultSelected == null || rule.defaultSelected);
				String title = rule.displayTitle();
				put(new Recommendation("add:" + rule.slug, Category.ADD_MOD, RulesDocument.impactOf(rule.impact, Impact.MEDIUM),
						"Install " + title, reason, new Action.AddMod(rule.slug, rule.projectId, title), selected));
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
				if (mod == null || update == null || mod.file() == null || recs.containsKey("disable:" + modId)) {
					continue;
				}
				String current = update.currentVersion() != null ? update.currentVersion() : mod.version();
				put(new Recommendation("update:" + modId, Category.UPDATE_MOD, Impact.LOW, "Update " + name(mod),
						"Version " + update.newVersionNumber() + " is available (you have " + current + ").",
						new Action.UpdateMod(modId, mod.file(), update), true));
			}
		}

		void settings() {
			Map<String, Resolved> resolved = new LinkedHashMap<>();
			for (SettingRule rule : rules.settings) {
				if (!rule.isValueEntry() || !matches(rule.when)) {
					continue;
				}
				String value = SettingValues.asString(rule.value);
				if (value == null) {
					continue;
				}
				value = SettingValues.resolveTokens(value, ctx.hardware().display());
				resolved.put(rule.key, new Resolved(value, text(rule.reason), RulesDocument.impactOf(rule.impact, Impact.LOW), selected(rule.defaultSelected)));
			}
			for (SettingRule rule : rules.settings) {
				if (!rule.isClampEntry() || !matches(rule.when)) {
					continue;
				}
				Resolved current = resolved.get(rule.key);
				BigDecimal number = SettingValues.number(current != null ? current.value() : snapshot.get(rule.key));
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
				String reason = current == null ? text(rule.reason) : (current.reason() + " " + text(rule.reason)).trim();
				Impact impact = current != null ? current.impact() : RulesDocument.impactOf(rule.impact, Impact.LOW);
				boolean selected = current != null ? current.selected() : selected(rule.defaultSelected);
				resolved.put(rule.key, new Resolved(SettingValues.format(clamped), reason, impact, selected));
			}
			for (Map.Entry<String, Resolved> entry : resolved.entrySet()) {
				String key = entry.getKey();
				Resolved target = entry.getValue();
				if (!snapshot.has(key) || !SettingKeys.changeable(key) || !SettingKeys.safeValue(target.value())) {
					continue;
				}
				String current = snapshot.get(key);
				if (SettingValues.same(current, target.value())) {
					continue;
				}
				put(new Recommendation("set:" + key, Category.SETTING, target.impact(),
						SettingValues.label(key) + ": " + current + " → " + target.value(), target.reason(),
						new Action.SetSetting(key, current, target.value()), target.selected()));
			}
		}

		void advice() {
			for (AdviceRule rule : rules.advice) {
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
				put(new Recommendation("advice:" + rule.id, category, impact, rule.title != null ? rule.title : rule.id,
						text(rule.text), new Action.None(), false));
			}
		}

		void rigtuneVersion(String modVersion) {
			if (modVersion == null || rules.minModVersion == null || compareVersions(modVersion, rules.minModVersion) >= 0) {
				return;
			}
			put(new Recommendation("advice:update-rigtune", Category.ADVICE, Impact.MEDIUM, "Update RigTune",
					"These recommendations are written for RigTune " + rules.minModVersion + " or newer and you have " + modVersion
							+ ". Update RigTune so every suggestion is understood correctly.",
					new Action.None(), false));
		}

		private void disable(InstalledMod mod, Impact impact, String title, String reason) {
			String id = "disable:" + mod.modId();
			if (recs.containsKey(id)) {
				return;
			}
			if (mod.file() == null) {
				put(new Recommendation(id, Category.REMOVE_MOD, impact, title,
						reason + " It is bundled inside another mod, so it has to be removed together with that mod.", new Action.None(), false));
				return;
			}
			put(new Recommendation(id, Category.REMOVE_MOD, impact, title, reason, new Action.DisableMod(mod.modId(), mod.file()), true));
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
