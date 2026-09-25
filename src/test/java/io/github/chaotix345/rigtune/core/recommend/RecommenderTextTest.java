package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.L10nFixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.report.ModrinthOffAdvice;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 9, G-M1: the Recommender's own titles and reasons are Texts next to the unchanged Strings.
class RecommenderTextTest {
	private static Map<String, Recommendation> byId(Report report) {
		return report.recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r, (a, b) -> a, LinkedHashMap::new));
	}

	// The key a Text shows (the first part's key for joined sentences), or "literal".
	private static String key(Text text) {
		return switch (text) {
			case Text.Translatable t -> t.key();
			case Text.Literal l -> "literal";
			case Text.Joined j -> j.parts().isEmpty() ? "blank" : key(j.parts().getFirst());
		};
	}

	@Test
	void theStringsAreTheTextsEnglish() {
		for (Report report : L10nFixtures.reports()) {
			for (Recommendation r : report.recommendations()) {
				assertEquals(r.title(), r.titleText().english(), r.id());
				assertEquals(r.reason(), r.reasonText().english(), r.id());
			}
		}
	}

	@Test
	void everyCoreBuiltTitleAndReasonHasItsKey() {
		Map<String, Recommendation> recs = byId(L10nFixtures.report());
		Map<String, List<String>> expected = new LinkedHashMap<>();
		expected.put("disable:indium", List.of("rigtune.rec.disable.title", "literal"));
		expected.put("disable:oldlib", List.of("rigtune.rec.disable.title", "rigtune.rec.obsolete.reason"));
		expected.put("disable:outside", List.of("rigtune.rec.disable.title", "rigtune.rec.obsolete.reason"));
		expected.put("disable:nested", List.of("rigtune.rec.disable.title", "rigtune.rec.obsolete.reason"));
		expected.put("disable:avoidme", List.of("rigtune.rec.disable.title", "rigtune.rec.unsuitable.reason"));
		expected.put("disable:avoidtoo", List.of("rigtune.rec.disable.title", "literal"));
		expected.put("conflict:clash+sodium", List.of("rigtune.rec.conflict.title", "rigtune.rec.conflict.reason"));
		expected.put("add:net", List.of("rigtune.rec.install.title", "literal"));
		expected.put("add:pair-a", List.of("rigtune.rec.install.title", "rigtune.rec.install.keeps_out"));
		expected.put("update:lithium", List.of("rigtune.rec.update.title", "rigtune.rec.update.reason"));
		expected.put("update:far", List.of("rigtune.rec.update.title", "rigtune.rec.update.reason"));
		expected.put("advice:update-queued:queuedmod", List.of("rigtune.rec.update_queued.title", "rigtune.rec.update_queued.reason"));
		expected.put("advice:updates-itself:distanthorizons", List.of("rigtune.rec.updates_itself.title", "rigtune.rec.updates_itself.reason"));
		expected.put("set:vanilla.renderDistance", List.of("rigtune.rec.setting.title", "literal"));
		expected.put("set:vanilla.simulationDistance", List.of("rigtune.rec.setting.title", "literal"));
		expected.put("advice:tip", List.of("literal", "literal"));
		expected.put("advice:untitled", List.of("literal", "literal"));
		expected.put("advice:update-rigtune", List.of("rigtune.rec.update_rigtune.title", "rigtune.rec.update_rigtune.reason"));
		assertEquals(expected.keySet().stream().sorted().toList(), recs.keySet().stream().sorted().toList());
		expected.forEach((id, keys) -> {
			Recommendation r = recs.get(id);
			assertEquals(keys, List.of(key(r.titleText()), key(r.reasonText())), id);
		});
	}

	@Test
	void theNotesAndConflictsReadAsBefore() {
		Map<String, Recommendation> recs = byId(L10nFixtures.report());
		assertEquals("Faster net. RigTune doesn't also offer Noise or Surface, which conflict with it. " + Recommender.AVAILABILITY_UNKNOWN_NOTE,
				recs.get("add:net").reason());
		assertEquals("RigTune doesn't also offer Pair B, which conflicts with it. " + Recommender.ALPHA_NOTE + " " + Recommender.AVAILABILITY_UNKNOWN_NOTE,
				recs.get("add:pair-a").reason());
		assertEquals("Clash conflicts with sodium", recs.get("conflict:clash+sodium").title());
		assertEquals("Outside is obsolete on this Minecraft version. " + Recommender.OUTSIDE_MODS_FOLDER + " remove it in your launcher.",
				recs.get("disable:outside").reason());
		assertEquals("Nested is obsolete on this Minecraft version. It is bundled inside another mod, so it has to be removed together with that mod.",
				recs.get("disable:nested").reason());
		assertEquals("Version 2.1 is available (you have 2.0). " + Recommender.OUTSIDE_MODS_FOLDER + " update it in your launcher.",
				recs.get("update:far").reason());
		assertEquals("These recommendations are written for RigTune 9.9.9 or newer and you have 0.3.0. Update RigTune so every suggestion is understood correctly.",
				recs.get("advice:update-rigtune").reason());
		Text.Translatable names = assertInstanceOf(Text.Translatable.class,
				((Text.Translatable) ((Text.Joined) recs.get("add:net").reasonText()).parts().get(1)).args().getFirst());
		assertEquals("rigtune.rec.list.or", names.key());
	}

	@Test
	void modrinthOffNotesAreTexts() {
		for (boolean networkOff : new boolean[]{false, true}) {
			Map<String, Recommendation> recs = byId(ModrinthOffAdvice.apply(L10nFixtures.report(), networkOff));
			String prefix = networkOff ? "rigtune.rec.network_off." : "rigtune.rec.modrinth_off.";
			Text.Joined add = assertInstanceOf(Text.Joined.class, recs.get("add:net").reasonText());
			assertEquals(prefix + "install", key(add.parts().getLast()));
			Text.Joined update = assertInstanceOf(Text.Joined.class, recs.get("update:lithium").reasonText());
			assertEquals(prefix + "update", key(update.parts().getLast()));
			assertEquals("rigtune.rec.update.title", key(recs.get("update:lithium").titleText()));
		}
		Recommendation blank = Recommendation.of("add:x", Category.ADD_MOD, Impact.LOW, Text.literal("Install X"), Text.literal(""),
				new Action.AddMod("x", "X", "X"), true);
		Recommendation off = ModrinthOffAdvice.apply(new Report(null, null, null, null, List.of(blank), 1, "t", false, null)).recommendations().getFirst();
		assertEquals(ModrinthOffAdvice.ADD_NOTE, off.reason());
		assertEquals("rigtune.rec.modrinth_off.install", key(off.reasonText()));
	}

	// G-M1: the Strings are byte for byte what 0.2 built, also for rule text with spaces at its edges or no text at all.
	@Test
	void ruleTextWithSpacesOrNoTextReadsAsBefore() {
		String rules = """
				{"schemaVersion":2,"revision":1,"gpuVendorFallback":{"amd":5},"cpuTiers":[{"pattern":"(?i)ryzen","tier":5}],
				 "heapTiers":[{"atLeastMb":0,"tier":5}],"availability":{"26.2":["plain","noted","blank"]},
				 "mods":[
				  {"slug":"plain","projectId":"P1","title":"Plain","modIds":["plain"],"reason":" Plain. ","recommendWhen":{"always":true}},
				  {"slug":"noted","projectId":"P2","title":"Noted","modIds":["noted"],"reason":"  Noted.  ","stability":"alpha","recommendWhen":{"always":true}},
				  {"slug":"blank","projectId":"P3","title":"Blank","modIds":["blank"],"reason":"   ","stability":"alpha","recommendWhen":{"always":true}}
				 ],
				 "obsolete":[{"modIds":["gone"],"reason":""},{"modIds":["spaced"],"reason":" Old. "}]}
				""";
		List<InstalledMod> mods = List.of(
				new InstalledMod("gone", "Gone", "1", null, "aaaa"),
				new InstalledMod("spaced", "Spaced", "1", null, null));
		Report report = Recommender.recommend(RulesLoader.parse(rules), Fixtures.userRig().build(),
				mods, new SettingsSnapshot(Map.of()), OnlineData.offline(),
				Goal.BALANCED);
		Map<String, Recommendation> recs = byId(report);
		assertEquals(" Plain. ", recs.get("add:plain").reason());
		assertEquals(("  Noted.  " + " " + Recommender.ALPHA_NOTE).trim(), recs.get("add:noted").reason());
		assertEquals(("   " + " " + Recommender.ALPHA_NOTE).trim(), recs.get("add:blank").reason());
		assertEquals("" + " " + Recommender.OUTSIDE_MODS_FOLDER + " remove it in your launcher.", recs.get("disable:gone").reason());
		assertEquals(" Old. " + " It is bundled inside another mod, so it has to be removed together with that mod.", recs.get("disable:spaced").reason());
		Recommendation spaced = new Recommendation("add:x", Category.ADD_MOD, Impact.LOW, "Install X", "Fast. ",
				new Action.AddMod("x", "X", "X"), true);
		Report one = new Report(null, null, null, null, List.of(spaced), 1, "t", false, null);
		assertEquals("Fast. " + " " + ModrinthOffAdvice.ADD_NOTE, ModrinthOffAdvice.apply(one).recommendations().getFirst().reason());
		for (Report r : List.of(report, ModrinthOffAdvice.apply(one))) {
			for (Recommendation rec : r.recommendations()) {
				assertEquals(rec.reason(), rec.reasonText().english(), rec.id());
			}
		}
	}

	@Test
	void theOldConstructorKeepsWorkingWithLiterals() {
		Recommendation r = new Recommendation("add:x", Category.ADD_MOD, Impact.LOW, "Install X", "E2E test mod",
				new Action.None(), false);
		assertEquals(Text.literal("Install X"), r.titleText());
		assertEquals(Text.literal("E2E test mod"), r.reasonText());
		Recommendation nulls = new Recommendation("a", Category.ADVICE, Impact.LOW, null, null, new Action.None(), false);
		assertNotNull(nulls.titleText());
		assertTrue(nulls.reasonText().isBlank());
	}
}
