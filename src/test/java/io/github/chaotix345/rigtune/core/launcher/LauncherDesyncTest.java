package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 4j, AC4j.1: a database-free model of the Modrinth App's content rows (docs/research/v0.5/
// launcher-managed-mods.md §1: rows keyed by the canonical path, entries only from the app's own installs, a rename of an
// unmanaged row followed, missing rows never pruned, and Update refused when another row sits at the update's file name,
// content_mutation.rs:266-278) and of GDLauncher's file-name is_already_installed check (§3.3), driven by the file ops
// RigTune plans for a report. RigTune 0.4's behaviour (RIGTUNE) reproduces the incident's refusal; under LAUNCHER RigTune
// plans no op, and neither launcher is desynced: the regression proof of the root cause.
class LauncherDesyncTest {
	private static final Path MODS = Path.of("mods");

	// A file op RigTune stages (PendingActions' DISABLE_FILE / ENABLE_FILE, by file name).
	record FileOp(boolean enable, String file) {
	}

	// RealController.apply's partition for mod files (after the apply guard): a disable renames the jar; an update disables
	// the old jar and adds the new one under Modrinth's file name; an addition adds one.
	static List<FileOp> planned(Report report, ModFilesPolicy policy) {
		List<Recommendation> selected = report.recommendations().stream().filter(Recommendation::appliable).toList();
		List<FileOp> ops = new ArrayList<>();
		for (Recommendation r : LauncherModAdvice.guard(selected, policy)) {
			switch (r.action()) {
				case Action.DisableMod disable -> ops.add(new FileOp(false, disable.file().getFileName().toString()));
				case Action.UpdateMod update -> {
					ops.add(new FileOp(false, update.currentFile().getFileName().toString()));
					ops.add(new FileOp(true, update.update().file().filename()));
				}
				case Action.AddMod add -> ops.add(new FileOp(true, add.slug() + ".jar"));
				default -> {
				}
			}
		}
		return ops;
	}

	// The mods folder's file names after the helper ran the ops (RigTune's .disabled convention).
	static Set<String> after(Set<String> folder, List<FileOp> ops) {
		Set<String> out = new LinkedHashSet<>(folder);
		for (FileOp op : ops) {
			if (op.enable()) {
				out.add(op.file());
			} else if (out.remove(op.file())) {
				out.add(op.file() + ".disabled");
			}
		}
		return out;
	}

	// The Modrinth App 0.21.5's content rows for one instance (lm §1.1-1.5), unmanaged rows only (every row RigTune can
	// meet in an instance the app didn't install since 0.21; the store-managed ones only vanish, §1.3).
	static final class ModrinthApp {
		static final String COLLISION = "The updated filename belongs to another content item";

		record Row(boolean enabled, boolean missing, @Nullable String updateTo) {
		}

		final Map<String, Row> rows = new LinkedHashMap<>();

		static String canonical(String file) {
			return file.endsWith(".disabled") ? file.substring(0, file.length() - ".disabled".length()) : file;
		}

		// An install through the app: a row with its entry, and the version its update check points at.
		void install(String file, @Nullable String updateTo) {
			rows.put(file, new Row(true, false, updateTo));
		}

		// sync_instance_content_files: a file it has no row for gets an entry-less one; a rename of an unmanaged row is
		// followed; a row whose file is gone goes missing and stays (never pruned).
		void sync(Set<String> folder) {
			Set<String> seen = new LinkedHashSet<>();
			for (String file : folder) {
				String path = canonical(file);
				seen.add(path);
				Row row = rows.get(path);
				rows.put(path, new Row(!file.endsWith(".disabled"), false, row == null ? null : row.updateTo()));
			}
			rows.replaceAll((path, row) -> seen.contains(path) ? row : new Row(row.enabled(), true, row.updateTo()));
		}

		// Update on the row at path: refused when the update's file name differs and any row, missing or not, sits there.
		@Nullable String update(String path) {
			Row row = rows.get(path);
			if (row == null || row.updateTo() == null) {
				return "nothing to update";
			}
			if (!row.updateTo().equals(path) && rows.containsKey(row.updateTo())) {
				return COLLISION;
			}
			rows.remove(path);
			rows.put(row.updateTo(), new Row(row.enabled(), false, null));
			return null;
		}

		// Update all: every listed (not missing) row with an update, in order, stopping at the first refusal.
		@Nullable String updateAll() {
			for (String path : List.copyOf(rows.keySet())) {
				Row row = rows.get(path);
				if (row != null && !row.missing() && row.updateTo() != null) {
					String refused = update(path);
					if (refused != null) {
						return refused;
					}
				}
			}
			return null;
		}
	}

	// GDLauncher Carbon's mod-file cache (lm §3.3): one row per base file name, reconciled from the folder (.disabled is its
	// own convention, gone files deleted); its Update of a row installs the new file name only if no row has it
	// (is_already_installed, installer/mod.rs:290-316, :836-850).
	static final class GdLauncher {
		static final String ALREADY_INSTALLED = "resource is already installed";
		final Set<String> rows = new LinkedHashSet<>();

		void sync(Set<String> folder) {
			rows.clear();
			folder.forEach(file -> rows.add(ModrinthApp.canonical(file)));
		}

		@Nullable String update(String path, String to) {
			if (!rows.contains(path)) {
				return "nothing to update";
			}
			if (rows.contains(to)) {
				return ALREADY_INSTALLED;
			}
			rows.remove(path);
			rows.add(to);
			return null;
		}
	}

	private static Report report(Recommendation... recs) {
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.BALANCED, List.of(recs), 16, "bundled", true, Instant.parse("2026-09-25T09:00:00Z"));
	}

	// The incident's Mod Menu: the app installed 20.0.2 (before 0.21, so an unmanaged row) and its update check points at
	// 20.0.3, the very file name RigTune downloads; RigTune also adds Lithium and turns Indium off.
	private static Report incident() {
		return report(
				new Recommendation("update:modmenu", Category.UPDATE_MOD, Impact.LOW, "Update Mod Menu", "20.0.3 is available.",
						new Action.UpdateMod("modmenu", MODS.resolve("modmenu-20.0.2.jar"), new UpdateInfo("modmenu", "mOgUt4GM", "20.0.2", "v",
								"20.0.3", new ModFile("https://cdn.modrinth.com/modmenu-20.0.3.jar", "modmenu-20.0.3.jar", "0", 1))), true),
				new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Install Lithium", "r", new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true),
				new Recommendation("disable:indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium", "r", new Action.DisableMod("indium", MODS.resolve("indium.jar")), true));
	}

	private static final Set<String> FOLDER = Set.of("modmenu-20.0.2.jar", "indium.jar", "sodium.jar");

	private static ModrinthApp appWithItsOwnInstalls() {
		ModrinthApp app = new ModrinthApp();
		app.install("modmenu-20.0.2.jar", "modmenu-20.0.3.jar");
		app.install("indium.jar", null);
		app.install("sodium.jar", null);
		app.sync(FOLDER);
		return app;
	}

	@Test
	void rigTune04ReproducesTheIncidentsRefusal() {
		Report report = incident();
		List<FileOp> ops = planned(report, ModFilesPolicy.RIGTUNE);
		assertEquals(List.of(new FileOp(false, "modmenu-20.0.2.jar"), new FileOp(true, "modmenu-20.0.3.jar"), new FileOp(true, "lithium.jar"),
				new FileOp(false, "indium.jar")), ops);
		Set<String> folder = after(FOLDER, ops);

		ModrinthApp app = appWithItsOwnInstalls();
		app.sync(folder);
		assertEquals(ModrinthApp.COLLISION, app.update("modmenu-20.0.2.jar"));
		assertEquals(ModrinthApp.COLLISION, app.updateAll(), "Update all stops at the first refusal");

		GdLauncher gd = new GdLauncher();
		gd.sync(folder);
		assertEquals(GdLauncher.ALREADY_INSTALLED, gd.update("modmenu-20.0.2.jar", "modmenu-20.0.3.jar"));
	}

	// Undoing 0.4's update (the renames back) doesn't repair the app (lm §1.7): RigTune's new row still sits at the name.
	@Test
	void rigTune04sUndoDoesNotRepairIt() {
		Set<String> folder = after(FOLDER, planned(incident(), ModFilesPolicy.RIGTUNE));
		folder.remove("modmenu-20.0.2.jar.disabled");
		folder.add("modmenu-20.0.2.jar");
		folder.remove("modmenu-20.0.3.jar");
		folder.add("modmenu-20.0.3.jar.disabled");
		ModrinthApp app = appWithItsOwnInstalls();
		app.sync(after(FOLDER, planned(incident(), ModFilesPolicy.RIGTUNE)));
		app.sync(folder);
		assertEquals(ModrinthApp.COLLISION, app.update("modmenu-20.0.2.jar"));
	}

	@Test
	void underLauncherNothingIsPlannedAndNeitherLauncherIsDesynced() {
		for (ModFilesPolicy policy : List.of(ModFilesPolicy.LAUNCHER, ModFilesPolicy.PENDING)) {
			Report advised = LauncherModAdvice.apply(incident(), policy, LauncherInfo.of(Launcher.MODRINTH_APP));
			assertEquals(List.of(), planned(advised, policy), policy.name());
			assertEquals(List.of(), planned(incident(), policy), policy + ": the apply guard alone plans nothing either");
			Set<String> folder = after(FOLDER, planned(advised, policy));
			assertEquals(FOLDER, folder);

			ModrinthApp app = appWithItsOwnInstalls();
			app.sync(folder);
			assertNull(app.updateAll(), policy + ": the app's own Update all works");
			assertEquals(Set.of("modmenu-20.0.3.jar", "indium.jar", "sodium.jar"), app.rows.keySet());

			GdLauncher gd = new GdLauncher();
			gd.sync(folder);
			assertNull(gd.update("modmenu-20.0.2.jar", "modmenu-20.0.3.jar"), policy.name());
		}
	}
}
