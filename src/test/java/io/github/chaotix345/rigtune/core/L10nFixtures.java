package io.github.chaotix345.rigtune.core;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.report.ModrinthOffAdvice;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// docs/v0.3/SPEC.md item 9 (AC9.3): reports that reach every piece of text the Recommender and ModrinthOffAdvice build
// themselves, next to rule-provided text.
public final class L10nFixtures {
	public static final String AUTO_UPDATER = "dh.client.advanced.autoUpdater.enableAutoUpdater";
	public static final String RULES = """
			{"schemaVersion":2,"revision":1,"minModVersion":"9.9.9",
			 "gpuVendorFallback":{"amd":5,"nvidia":5},
			 "cpuTiers":[{"pattern":"(?i)ryzen","tier":5}],
			 "heapTiers":[{"atLeastMb":0,"tier":5}],
			 "mods":[
			  {"slug":"net","projectId":"P1","title":"Net","modIds":["net"],"reason":"Faster net.","recommendWhen":{"always":true},"conflictsWith":["noise_mod"]},
			  {"slug":"noise","projectId":"P2","title":"Noise","modIds":["noise_mod"],"reason":"Faster noise.","recommendWhen":{"always":true}},
			  {"slug":"surface","projectId":"P3","title":"Surface","modIds":["surface"],"reason":"Faster surface.","recommendWhen":{"always":true},"conflictsWith":["net"]},
			  {"slug":"pair-a","projectId":"P4","title":"Pair A","modIds":["pair_a"],"reason":"","stability":"alpha","recommendWhen":{"always":true},"conflictsWith":["pair-b"]},
			  {"slug":"pair-b","projectId":"P5","title":"Pair B","modIds":["pair_b"],"reason":"B.","recommendWhen":{"always":true}},
			  {"slug":"avoidme","projectId":"P6","title":"Avoid Me","modIds":["avoidme"],"reason":"r","recommendWhen":{"always":false},"avoidWhen":{"always":true}},
			  {"slug":"avoidtoo","projectId":"P7","title":"Avoid Too","modIds":["avoidtoo"],"reason":"r","recommendWhen":{"always":false},"avoidWhen":{"always":true},"avoidReason":"Needs a faster GPU."},
			  {"slug":"clash","projectId":"P8","title":"Clash","modIds":["clash"],"reason":"r","recommendWhen":{"always":false},"conflictsWith":["sodium"]},
			  {"slug":"distanthorizons","projectId":"uCdwusMi","title":"Distant Horizons","modIds":["distanthorizons"],"reason":"r","recommendWhen":{"always":false},
			   "skipUpdateWhen":{"settingIs":{"%s":true}}}
			 ],
			 "obsolete":[{"modIds":["indium"],"title":"Indium","reason":"Built into Sodium."},{"modIds":["oldlib","outside","nested"]}],
			 "settings":[
			  {"key":"vanilla.renderDistance","value":20,"reason":"More view.","when":{"always":true}},
			  {"key":"vanilla.simulationDistance","max":5,"reason":"Less ticking.","when":{"always":true}}
			 ],
			 "advice":[{"id":"tip","title":"Tip","text":"Read this.","when":{"always":true}},{"id":"untitled","text":"No title.","when":{"always":true}}]}
			""".formatted(AUTO_UPDATER);

	private L10nFixtures() {
	}

	public static RulesDocument rules() {
		return RulesLoader.parse(RULES);
	}

	// Every Recommender branch with text of its own: disables (obsolete with and without rule text, unsuitable, outside the
	// mods folder, bundled), a conflict, additions keeping out one and two conflicts (alpha, availability unknown),
	// updates (in and outside the mods folder, queued, updating itself), settings, advice and "Update RigTune".
	public static Report report() {
		List<InstalledMod> mods = new ArrayList<>(Fixtures.mods("indium", "oldlib", "avoidme", "avoidtoo", "clash", "sodium", "queuedmod"));
		mods.add(new InstalledMod("outside", "Outside", "1", null, "aaaa"));
		mods.add(new InstalledMod("nested", "Nested", "1", null, null));
		mods.add(new InstalledMod("lithium", "Lithium", "0.25.2", Path.of("mods", "lithium.jar"), "bbbb"));
		mods.add(new InstalledMod("far", "Far", "2.0", null, "cccc"));
		mods.add(new InstalledMod("distanthorizons", "Distant Horizons", "3.3.0", Path.of("mods", "dh.jar"), "dddd"));
		ModFile file = new ModFile("https://cdn", "new.jar", "abc", 10);
		Map<String, UpdateInfo> updates = Map.of(
				"lithium", new UpdateInfo("lithium", "gvQqBUqZ", "0.25.2", "v2", "0.25.3", file),
				"far", new UpdateInfo("far", "FAR", null, "v3", "2.1", file),
				"queuedmod", new UpdateInfo("queuedmod", "Q", "1.0.0", "v4", "1.1", file),
				"distanthorizons", new UpdateInfo("distanthorizons", "uCdwusMi", "3.3.0", "v5", "3.3.2", file));
		SettingsSnapshot settings = new SettingsSnapshot(Map.of("vanilla.renderDistance", "12", "vanilla.simulationDistance", "12", AUTO_UPDATER, "true"));
		return Recommender.recommend(rules(), Fixtures.userRig().build(), mods, settings, new OnlineData(true, Map.of(), updates), Goal.BALANCED,
				"0.3.0", Set.of("queuedmod"));
	}

	// The report, and the same with Modrinth off and with the network off.
	public static List<Report> reports() {
		Report report = report();
		return List.of(report, ModrinthOffAdvice.apply(report, false), ModrinthOffAdvice.apply(report, true));
	}

	// The rule-provided text of RULES: what may reach the UI as a literal (docs/v0.3/SPEC.md item 9: rule text stays data).
	public static Set<String> ruleText() {
		return Set.of("Faster net.", "Faster noise.", "Faster surface.", "B.", "Built into Sodium.", "Needs a faster GPU.", "More view.",
				"Less ticking.", "More view. Less ticking.", "Tip", "Read this.", "untitled", "No title.");
	}
}
