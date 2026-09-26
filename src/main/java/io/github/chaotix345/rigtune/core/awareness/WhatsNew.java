package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// "What's new for you" (docs/v0.4/SPEC.md 9, plan review W-L3). The baseline in awareness.json is a rules revision and
// the recommendation ids that revision COULD produce (every rule's id, whether or not it fired here), so a goal change or
// a newly installed mod never counts as new rules. When the shown report's revision is newer than the baseline's: new =
// the report's appliable recommendations and warning/critical advice whose ids the current rules can produce and the
// baseline's couldn't. No baseline (an upgrade from 0.3 or older) is seeded silently; a revision with nothing new moves
// the baseline on silently; new ones stay until the notice is dismissed (acknowledge). Bundled and remote revisions are
// the same to it.
public final class WhatsNew {
	// About 40 bytes each, so the baseline stays well inside awareness.json's 64 KiB cap; a longer list isn't stored.
	static final int MAX_IDS = 1000;

	public enum Kind { NOTHING, SEEDED, ADVANCED, NEW }

	public record Result(Kind kind, List<Recommendation> fresh) {
		static final Result NOTHING = new Result(Kind.NOTHING, List.of());

		public Result {
			fresh = List.copyOf(fresh);
		}
	}

	public record Baseline(int revision, Set<String> ids) {
		public Baseline {
			ids = Set.copyOf(ids);
		}

		// null unless both fields are there and usable.
		public static @Nullable Baseline read(JsonObject awareness) {
			if (!(awareness.get(AwarenessStore.LAST_SEEN_RULES_REVISION) instanceof JsonPrimitive r) || !r.isNumber()
					|| !(awareness.get(AwarenessStore.LAST_SEEN_RECOMMENDATION_IDS) instanceof JsonArray list)) {
				return null;
			}
			double revision = r.getAsDouble();
			if (revision != Math.rint(revision) || Math.abs(revision) > Integer.MAX_VALUE) {
				return null;
			}
			Set<String> ids = new LinkedHashSet<>();
			for (JsonElement id : list) {
				if (id instanceof JsonPrimitive p && p.isString()) {
					ids.add(p.getAsString());
				}
			}
			return new Baseline((int) revision, ids);
		}

		public JsonObject writeTo(JsonObject awareness) {
			JsonArray list = new JsonArray();
			ids.stream().sorted().forEach(list::add);
			awareness.addProperty(AwarenessStore.LAST_SEEN_RULES_REVISION, revision);
			awareness.add(AwarenessStore.LAST_SEEN_RECOMMENDATION_IDS, list);
			return awareness;
		}
	}

	private WhatsNew() {
	}

	// Every recommendation id the Recommender can make from these rules (Recommender's own id shapes): add:<slug> and, for
	// a rule with avoidWhen, disable:<modId> per mod id; conflict:<a>+<b> (ordered slugs) per conflictsWith pair;
	// disable:<modId> per obsolete mod id; set:<key> per setting entry; advice:<id> per advice entry. Rules whose
	// `requires` this client lacks are included: a future client may produce them, and counting them keeps noise down.
	public static Set<String> potentialIds(RulesDocument rules) {
		Set<String> ids = new LinkedHashSet<>();
		Map<String, String> slugByModId = new LinkedHashMap<>();
		Set<String> slugs = new LinkedHashSet<>();
		for (RulesDocument.ModRule mod : rules.mods) {
			if (mod != null && mod.slug != null) {
				slugs.add(mod.slug);
				mod.modIds.forEach(id -> slugByModId.putIfAbsent(id, mod.slug));
			}
		}
		for (RulesDocument.ModRule mod : rules.mods) {
			if (mod == null || mod.slug == null) {
				continue;
			}
			ids.add("add:" + mod.slug);
			if (mod.avoidWhen != null) {
				mod.modIds.forEach(id -> ids.add("disable:" + id));
			}
			for (String ref : mod.conflictsWith) {
				String other = ref == null ? null : slugs.contains(ref) ? ref : slugByModId.getOrDefault(ref, ref);
				if (other != null && !other.equals(mod.slug)) {
					ids.add("conflict:" + (mod.slug.compareTo(other) <= 0 ? mod.slug + "+" + other : other + "+" + mod.slug));
				}
			}
		}
		for (RulesDocument.ObsoleteRule rule : rules.obsolete) {
			if (rule != null) {
				rule.modIds.forEach(id -> ids.add("disable:" + id));
			}
		}
		for (RulesDocument.SettingRule rule : rules.settings) {
			if (rule != null && rule.key != null) {
				ids.add("set:" + rule.key);
			}
		}
		for (RulesDocument.AdviceRule rule : rules.advice) {
			if (rule != null && rule.id != null) {
				ids.add("advice:" + rule.id);
			}
		}
		return ids;
	}

	// Pure: what the report shows that is new against the baseline.
	public static Result compare(@Nullable Baseline baseline, int revision, List<Recommendation> recommendations, Set<String> potentialNow) {
		if (baseline == null) {
			return new Result(Kind.SEEDED, List.of());
		}
		// An older revision (an older RigTune's bundled rules, a cache that went away) isn't news, and the baseline stays.
		if (baseline.revision() >= revision) {
			return Result.NOTHING;
		}
		List<Recommendation> fresh = new ArrayList<>();
		for (Recommendation r : recommendations) {
			if ((r.appliable() || r.category() == Category.WARNING) && potentialNow.contains(r.id()) && !baseline.ids().contains(r.id())) {
				fresh.add(r);
			}
		}
		return fresh.isEmpty() ? new Result(Kind.ADVANCED, List.of()) : new Result(Kind.NEW, fresh);
	}

	// When RigTuneScreen shows a report: seeds or moves the baseline on silently where there's nothing to show. A report
	// built from other rules than these (a reload in between), or a file this version can't write, shows nothing.
	public static Result check(AwarenessStore store, Report report, RulesDocument rules) {
		if (report == null || rules == null || report.rulesRevision() != rules.revision || !store.writable()) {
			return Result.NOTHING;
		}
		Set<String> potential = potentialIds(rules);
		Result result = compare(Baseline.read(store.read()), rules.revision, report.recommendations(), potential);
		if (result.kind() == Kind.SEEDED || result.kind() == Kind.ADVANCED) {
			acknowledge(store, rules.revision, potential);
		}
		return result;
	}

	// The player dismissed the notice (or there was nothing to show): this revision's ids become the baseline.
	public static boolean acknowledge(AwarenessStore store, int revision, Set<String> potentialIds) {
		if (potentialIds.size() > MAX_IDS) {
			return false;
		}
		Baseline baseline = new Baseline(revision, potentialIds);
		return store.update(baseline::writeTo);
	}
}
