package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
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
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import net.minecraft.locale.Language;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 4b: under each mod-file row the launcher took over, "In <launcher>: <steps>" for the row's kind, at the
// text the screen shows (RigTune's en_us.json); nothing under PENDING, nothing without a named launcher.
class LauncherLinesModStepsTest {
	private Language previous;

	@BeforeEach
	void english() throws IOException {
		previous = Language.getInstance();
		Language.inject(TextsTest.rigtuneEnglish(previous));
	}

	@AfterEach
	void restore() {
		Language.inject(previous);
	}

	private static final Recommendation ADD = new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Install Lithium", "r",
			new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true);
	private static final Recommendation UPDATE = new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium", "r",
			new Action.UpdateMod("sodium", Path.of("mods", "sodium.jar"), new UpdateInfo("sodium", "p", "1", "v", "2", null)), true);
	private static final Recommendation SELF = new Recommendation("update:rigtune", Category.UPDATE_MOD, Impact.LOW, "Update RigTune", "r",
			new Action.UpdateMod("rigtune", Path.of("mods", "rigtune.jar"), new UpdateInfo("rigtune", "p", "1", "v", "2", null)), true);
	private static final Recommendation DISABLE = new Recommendation("disable:indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium", "r",
			new Action.DisableMod("indium", Path.of("mods", "indium.jar")), true);

	private static List<Recommendation> advised(ModFilesPolicy policy, LauncherInfo launcher) {
		Report report = new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.QUALITY, List.of(ADD, UPDATE, SELF, DISABLE), 9, "cache", true, Instant.parse("2026-09-25T10:00:00Z"));
		return LauncherModAdvice.apply(report, policy, launcher).recommendations();
	}

	private static String line(Recommendation r, LauncherInfo launcher) {
		var line = LauncherLines.adviceLine(r, launcher, JvmReport.UNAVAILABLE);
		return line == null ? null : line.getString();
	}

	@Test
	void theModrinthAppsSteps() {
		LauncherInfo app = LauncherInfo.of(Launcher.MODRINTH_APP);
		List<Recommendation> rows = advised(ModFilesPolicy.LAUNCHER, app);
		assertEquals("In the Modrinth App: this instance → Content → Browse content, then install it there.", line(rows.get(0), app));
		assertEquals("In the Modrinth App: this instance → Content → select it → Update (or Update all).", line(rows.get(1), app));
		assertEquals("In the Modrinth App: update RigTune in this instance's Content tab, or download it and use Content → Upload files, then delete"
				+ " the old RigTune there.", line(rows.get(2), app));
		assertEquals("In the Modrinth App: this instance → Content → select it → Disable.", line(rows.get(3), app));
	}

	@Test
	void prismWithAPackwizIndex() {
		LauncherInfo prism = LauncherInfo.of(Launcher.PRISM);
		List<Recommendation> rows = advised(ModFilesPolicy.LAUNCHER, prism);
		assertEquals("In Prism Launcher: right-click this instance → Edit... → Mods → select it → Check for Updates.", line(rows.get(2), prism),
				"RigTune's own update is an ordinary update there");
	}

	// 4c: an Undo item the launcher keeps gets the launcher's own steps to change it back there.
	@Test
	void undoStepsForASkippedItem() {
		LauncherInfo app = LauncherInfo.of(Launcher.MODRINTH_APP);
		Text reason = Text.of("rigtune.undo.reason.launcher_managed", "This instance's mods are managed by %s: change it there",
				LauncherModText.nameOrYours(app));
		UndoPlan.Item added = UndoPlan.Item.of(Text.of("rigtune.undo.item.enable", "Enable %s", "lithium.jar"), UndoPlan.Action.SKIP, reason, false,
				List.of("c1"), List.of());
		UndoPlan.Item turnedOff = UndoPlan.Item.of(Text.of("rigtune.undo.item.disable", "Disable %s", "indium.jar"), UndoPlan.Action.SKIP, reason, false,
				List.of("c2"), List.of());
		assertEquals("In the Modrinth App: this instance → Content → select it → Disable.", LauncherLines.undoStepsLine(added).getString());
		assertEquals("In the Modrinth App: this instance → Content → filter Disabled → select it → Enable.",
				LauncherLines.undoStepsLine(turnedOff).getString());
		Text yours = Text.of("rigtune.undo.reason.launcher_managed", "This instance's mods are managed by %s: change it there",
				LauncherModText.nameOrYours(LauncherInfo.UNKNOWN));
		assertNull(LauncherLines.undoStepsLine(UndoPlan.Item.of(Text.of("rigtune.undo.item.enable", "Enable %s", "lithium.jar"), UndoPlan.Action.SKIP,
				yours, false, List.of("c1"), List.of())), "no steps where the reason names no launcher");
		UndoPlan.Item gone = UndoPlan.Item.of(Text.of("rigtune.undo.item.enable", "Enable %s", "x.jar"), UndoPlan.Action.SKIP,
				Text.of("rigtune.undo.reason.file_gone", "%s is no longer in the mods folder", "x.jar"), false, List.of("c3"), List.of());
		assertNull(LauncherLines.undoStepsLine(gone));
	}

	@Test
	void nothingUnderPendingOrWithoutANamedLauncherOrForARowItDidntTouch() {
		for (Recommendation r : advised(ModFilesPolicy.PENDING, LauncherInfo.UNKNOWN)) {
			assertNull(line(r, LauncherInfo.UNKNOWN), r.id());
		}
		for (LauncherInfo unnamed : List.of(LauncherInfo.UNKNOWN, LauncherInfo.of(Launcher.OFFICIAL))) {
			for (Recommendation r : advised(ModFilesPolicy.LAUNCHER, unnamed)) {
				assertNull(line(r, unnamed), unnamed + " " + r.id());
			}
		}
		assertNull(line(UPDATE, LauncherInfo.of(Launcher.MODRINTH_APP)), "an update RigTune still does itself");
	}
}
