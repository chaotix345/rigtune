package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4b, AC4b.1-AC4b.2 (unit): under LAUNCHER or PENDING every AddMod, UpdateMod and DisableMod (and RigTune's
// own update) keeps its id, title, category and impact, becomes Action.None and is unticked, with the launcher's note (or
// PENDING's neutral one); everything else is untouched; under RIGTUNE the report is the same object. Composed with
// ModrinthOffAdvice as RealController.rebuild() runs them (LauncherModAdvice first), for Modrinth on, off and network off.
class LauncherModAdviceTest {
	private static final LauncherInfo MODRINTH_APP = LauncherInfo.of(Launcher.MODRINTH_APP);
	private static final Recommendation ADD = new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Install Lithium",
			"Optimises game logic.", new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true);
	private static final Recommendation UPDATE = new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium",
			"Version 0.9.2 is available (you have 0.9.1).",
			new Action.UpdateMod("sodium", Path.of("mods", "sodium.jar"), new UpdateInfo("sodium", "AANobbMI", "0.9.1", "v", "0.9.2", null)), true);
	private static final Recommendation DISABLE = new Recommendation("disable:indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium",
			"Merged into Sodium.", new Action.DisableMod("indium", Path.of("mods", "indium.jar")), true);
	private static final Recommendation SELF_UPDATE = new Recommendation("update:rigtune", Category.UPDATE_MOD, Impact.LOW, "Update RigTune",
			"Version 0.5.1 is available (you have 0.5.0).",
			new Action.UpdateMod("rigtune", Path.of("mods", "rigtune.jar"), new UpdateInfo("rigtune", "rt", "0.5.0", "v2", "0.5.1", null)), true);
	private static final Recommendation OUTSIDE = new Recommendation("update:nested", Category.UPDATE_MOD, Impact.LOW, "Update Nested",
			"Version 2 is available (you have 1).",
			new Action.UpdateMod("nested", Path.of("elsewhere", "nested.jar"), new UpdateInfo("nested", "n", "1", "v", "2", null)), true);
	private static final Recommendation SETTING = new Recommendation("set:vanilla.renderDistance", Category.SETTING, Impact.HIGH,
			"Render distance", "Tier 4.", new Action.SetSetting("vanilla.renderDistance", "16", "12"), true);
	private static final Recommendation ADVICE = new Recommendation("advice:ram", Category.ADVICE, Impact.MEDIUM, "More RAM",
			"Raise it.", new Action.None(), false);
	private static final List<Recommendation> ALL = List.of(ADD, UPDATE, DISABLE, SELF_UPDATE, OUTSIDE, SETTING, ADVICE);
	// The note each mod-file row gets, by id (the steps kind LauncherLines reads back: kindOf).
	private static final Map<String, String> KIND = Map.of("add:lithium", "add", "update:sodium", "update", "disable:indium", "disable",
			"update:rigtune", "self_update", "update:nested", "update");

	private enum Modrinth { ON, OFF, NETWORK_OFF }

	private static Report report(List<Recommendation> recs) {
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.QUALITY, recs, 9, "cache", true, Instant.parse("2026-09-25T10:00:00Z"));
	}

	// As RealController.rebuild(): the post-step on the worker, then ModrinthOffAdvice last when Modrinth is off.
	private static Report rebuilt(ModFilesPolicy policy, LauncherInfo launcher, Modrinth modrinth) {
		Report advised = LauncherModAdvice.apply(report(ALL), policy, launcher);
		return modrinth == Modrinth.ON ? advised : ModrinthOffAdvice.apply(advised, modrinth == Modrinth.NETWORK_OFF);
	}

	private static List<String> keys(Text text) {
		List<String> out = new ArrayList<>();
		collect(text, out);
		return out;
	}

	private static void collect(Text text, List<String> out) {
		switch (text) {
			case Text.Translatable t -> {
				out.add(t.key());
				t.args().stream().filter(Text.class::isInstance).forEach(a -> collect((Text) a, out));
			}
			case Text.Joined j -> j.parts().forEach(p -> collect(p, out));
			case Text.Literal ignored -> {
			}
		}
	}

	@Test
	void underRigTuneTheReportIsTheSameObject() {
		Report in = report(ALL);
		for (LauncherInfo launcher : List.of(LauncherInfo.UNKNOWN, MODRINTH_APP, LauncherInfo.of(Launcher.PRISM))) {
			assertSame(in, LauncherModAdvice.apply(in, ModFilesPolicy.RIGTUNE, launcher));
		}
		for (Recommendation r : ALL) {
			assertNull(LauncherModAdvice.kindOf(r), r.id());
		}
	}

	@Test
	void everyCell() {
		for (ModFilesPolicy policy : ModFilesPolicy.values()) {
			for (Modrinth modrinth : Modrinth.values()) {
				Report out = rebuilt(policy, policy == ModFilesPolicy.PENDING ? LauncherInfo.UNKNOWN : MODRINTH_APP, modrinth);
				String cell = policy + " Modrinth " + modrinth;
				assertEquals(ALL.stream().map(Recommendation::id).toList(), out.recommendations().stream().map(Recommendation::id).toList(), cell);
				for (Recommendation r : out.recommendations()) {
					Recommendation in = ALL.stream().filter(o -> o.id().equals(r.id())).findFirst().orElseThrow();
					assertEquals(in.category(), r.category(), cell + " " + r.id());
					assertEquals(in.impact(), r.impact(), cell + " " + r.id());
					assertEquals(in.title(), r.title(), cell + " " + r.id());
					String kind = KIND.get(r.id());
					boolean modFile = kind != null;
					if (!modFile) {
						assertSame(in, r, cell + ": " + r.id() + " untouched");
						continue;
					}
					List<String> noteKeys = keys(r.reasonText());
					if (policy == ModFilesPolicy.RIGTUNE) {
						boolean download = !(in.action() instanceof Action.DisableMod);
						if (modrinth == Modrinth.ON || !download) {
							assertSame(in, r, cell + ": " + r.id() + " stays one-click");
						} else {
							assertInstanceOf(Action.None.class, r.action(), cell + " " + r.id());
							assertTrue(noteKeys.stream().anyMatch(k -> k.startsWith(modrinth == Modrinth.OFF ? "rigtune.rec.modrinth_off." : "rigtune.rec.network_off.")),
									cell + " " + r.id() + ": " + noteKeys);
						}
						assertNull(LauncherModAdvice.kindOf(r), cell + " " + r.id());
						continue;
					}
					assertInstanceOf(Action.None.class, r.action(), cell + " " + r.id());
					assertFalse(r.appliable(), cell + " " + r.id());
					assertFalse(r.selectedByDefault(), cell + " " + r.id());
					assertTrue(r.reason().startsWith(in.reason() + " "), cell + " " + r.id() + ": the reason stays first: " + r.reason());
					assertTrue(noteKeys.stream().noneMatch(k -> k.startsWith("rigtune.rec.modrinth_off.") || k.startsWith("rigtune.rec.network_off.")),
							cell + " " + r.id() + ": ModrinthOffAdvice finds nothing left: " + noteKeys);
					if (policy == ModFilesPolicy.PENDING) {
						assertTrue(noteKeys.contains("rigtune.launcher.mod_files.note.pending"), cell + " " + r.id() + ": " + noteKeys);
						assertTrue(r.reason().endsWith(LauncherModAdvice.PENDING_NOTE), r.reason());
						assertNull(LauncherModAdvice.kindOf(r), "PENDING gives no steps");
					} else {
						assertTrue(noteKeys.contains("rigtune.launcher.mod_files.note." + (kind.equals("self_update") ? "update" : kind)), cell + " " + r.id() + ": " + noteKeys);
						assertTrue(noteKeys.contains("rigtune.launcher.name.modrinth_app"), cell + " " + r.id() + ": names the launcher: " + noteKeys);
						assertEquals(kind, LauncherModAdvice.kindOf(r), cell + " " + r.id());
					}
				}
			}
		}
	}

	@Test
	void theNotesInEnglish() {
		Report out = LauncherModAdvice.apply(report(List.of(ADD, UPDATE, DISABLE)), ModFilesPolicy.LAUNCHER, MODRINTH_APP);
		assertEquals("Optimises game logic. This instance's mods are managed by the Modrinth App: install it there.", out.recommendations().get(0).reason());
		assertEquals("Version 0.9.2 is available (you have 0.9.1). This instance's mods are managed by the Modrinth App: update it there.",
				out.recommendations().get(1).reason());
		assertEquals("Merged into Sodium. This instance's mods are managed by the Modrinth App: turn it off there.", out.recommendations().get(2).reason());
		Report pending = LauncherModAdvice.apply(report(List.of(ADD)), ModFilesPolicy.PENDING, LauncherInfo.UNKNOWN);
		assertEquals("Optimises game logic. Checking which launcher manages this instance's mods; mod changes wait until then.",
				pending.recommendations().getFirst().reason());
	}

	// A packwiz index under the official launcher or an unknown one: the launcher isn't named (its record isn't the
	// official launcher's), and there are no steps to give.
	@Test
	void anUnnamedLauncherIsYourLauncher() {
		for (LauncherInfo launcher : List.of(LauncherInfo.UNKNOWN, LauncherInfo.of(Launcher.OFFICIAL))) {
			Recommendation r = LauncherModAdvice.apply(report(List.of(DISABLE)), ModFilesPolicy.LAUNCHER, launcher).recommendations().getFirst();
			assertEquals("Merged into Sodium. This instance's mods are managed by your launcher: turn it off there.", r.reason(), launcher.toString());
			assertEquals("disable", LauncherModAdvice.kindOf(r));
		}
	}

	@Test
	void aBlankReasonGetsJustTheNote() {
		Recommendation noReason = new Recommendation("add:x", Category.ADD_MOD, Impact.LOW, "Install X", null, new Action.AddMod("x", "id", "X"), true);
		assertEquals("This instance's mods are managed by the Modrinth App: install it there.",
				LauncherModAdvice.apply(report(List.of(noReason)), ModFilesPolicy.LAUNCHER, MODRINTH_APP).recommendations().getFirst().reason());
	}

	// AC4b.2 (unit): the apply guard drops every mod-file change the policy forbids and keeps the rest; under RIGTUNE it
	// changes nothing.
	@Test
	void theGuardDropsModFileChangesUnderLauncherAndPending() {
		assertSame(ALL, LauncherModAdvice.guard(ALL, ModFilesPolicy.RIGTUNE));
		for (ModFilesPolicy policy : List.of(ModFilesPolicy.LAUNCHER, ModFilesPolicy.PENDING)) {
			assertEquals(List.of(SETTING, ADVICE), LauncherModAdvice.guard(ALL, policy), policy.name());
			assertEquals(List.of(SETTING), LauncherModAdvice.guard(List.of(ADD, SETTING, DISABLE), policy), policy.name());
		}
	}
}
