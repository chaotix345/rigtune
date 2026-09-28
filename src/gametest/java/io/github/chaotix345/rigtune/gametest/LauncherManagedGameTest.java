package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneSettingsScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// docs/v0.5/SPEC.md 4j (AC4j.2 and the P0.4 game-test checks): RigTune in an instance whose launcher keeps its own
// record of the mods. Contracts skeleton (WS-K): registered at its C6 place in fabric.mod.json, returns at once under
// rigtune.smoke; one skeleton method per owner, each called once from runTest with the contracts' context record. An
// owner edits only its own method's body (and its own private helpers below it); each block puts back what it changed
// (the launcher brand property, fixture files, the network switch through GameTestNet).
public class LauncherManagedGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		V05TestContext v05 = V05TestContext.of(context);
		policyAndAdvice(v05);
		heldAndRepair(v05);
	}

	// ---- WS-L1 (AC4j.2, AC4a.3, AC4b.2, AC4b.6, AC4e.2): the policy, the launcher's steps, the refused Apply, the opt-in,
	// MOD_FILES_NEWS.

	private static void policyAndAdvice(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		RealController real = v05.realController();
		String brand = System.getProperty(BRAND);
		Path index = real.modsDir().resolve(".index");
		boolean indexWasThere = Files.exists(index);
		int[] window = context.computeOnClient(mc -> new int[]{mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});
		Set<String> pendingBefore = pendingIds(v05.configDir());
		boolean network = GameTestNet.set(context, real, false);
		Throwable failure = null;
		try {
			// The Modrinth App's brand, through the real probe: every mod-file row becomes the app's steps (X1: network off).
			System.setProperty(BRAND, "theseus");
			redetect(context, real, ModFilesPolicy.LAUNCHER);
			check(real.launcher().launcher() == Launcher.MODRINTH_APP, "the brand is the Modrinth App: " + real.launcher());
			launcherAdvice(context, real);
			refusedApply(context, real, v05.configDir());
			undoSkipsAppliedModFiles(context, real);
			check(context.computeOnClient(mc -> real.notices()).stream().noneMatch(n -> n.key().equals("launcher.mod_files_news")),
					"no MOD_FILES_NEWS before FirstRunService says RETURNING (WS-F)");

			// AC4e.2: the opt-in brings 0.4's behaviour back, with its line in the header's one warning slot (network on, so the
			// offline line doesn't take the slot).
			GameTestNet.set(context, real, true);
			optIn(context, real, v05.configDir());

			// A packwiz index with no launcher brand: the evidence alone makes it LAUNCHER (SPEC-14).
			GameTestNet.set(context, real, false);
			restoreBrand(brand);
			Files.createDirectories(index);
			Files.writeString(index.resolve("sodium.pw.toml"), "name = \"Sodium\"\nfilename = \"sodium.jar\"\n");
			redetect(context, real, ModFilesPolicy.LAUNCHER);
			RigTune.LOGGER.info("LauncherManagedGameTest: mods/.index/sodium.pw.toml with launcher {} gives {}", real.launcher(), real.modFiles());
			Report indexed = context.computeOnClient(mc -> real.report());
			check(indexed.recommendations().stream().noneMatch(LauncherManagedGameTest::modFileAction), "no mod-file change appliable with .index/");
		} catch (IOException e) {
			failure = e;
			throw new UncheckedIOException(e);
		} catch (RuntimeException | Error e) {
			failure = e;
			throw e;
		} finally {
			try {
				restoreBrand(brand);
				deleteIndexFixture(index, indexWasThere);
				// Review L12: through SettingsSaver (X8), the window as it was, and no pending op of this block's left behind.
				context.runOnClient(mc -> {
					ClientSettings settings = real.settings();
					settings.modFilesByRigTune = false;
					SettingsSaver.shared().save(settings, v05.configDir());
				});
				check(SettingsSaver.shared().flush(10_000), "settings.json saved");
				dropCreatedPendingOps(context, real, v05.configDir(), pendingBefore);
				resize(context, window);
				context.runOnClient(mc -> LauncherProbe.reset());
				GameTestNet.set(context, real, network);
				context.waitFor(mc -> real.report() != null && real.modFiles() != ModFilesPolicy.PENDING, 1200);
				context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
				context.waitForScreen(TitleScreen.class);
			} catch (RuntimeException | AssertionError e) {
				if (failure == null) {
					throw e;
				}
				failure.addSuppressed(e);
			}
		}
		RigTune.LOGGER.info("LauncherManagedGameTest: policyAndAdvice done");
	}

	private static final String BRAND = "minecraft.launcher.brand";
	private static final String FIXTURE_ENTRY = "ws-l1-launcher-managed";
	private static final String FIXTURE_JAR = "ws-l1-launcher-managed.jar";

	// The real probe again (reset + rescan), then the report built with the final policy (game tests wait past PENDING) and
	// the launcher recorded: when the detection outlives the probe's cap, its late answer rescans once (AC4a.3).
	private static void redetect(ClientGameTestContext context, RealController real, ModFilesPolicy expected) {
		Report[] old = new Report[1];
		context.runOnClient(mc -> {
			old[0] = real.report();
			LauncherProbe.reset();
			real.rescan();
		});
		context.waitFor(mc -> real.report() != null && real.report() != old[0] && real.modFiles() != ModFilesPolicy.PENDING
				&& real.launcher() != LauncherProbe.NOT_YET, 1200);
		check(real.modFiles() == expected, "policy " + real.modFiles() + ", expected " + expected);
	}

	private static void restoreBrand(@Nullable String brand) {
		if (brand == null) {
			System.clearProperty(BRAND);
		} else {
			System.setProperty(BRAND, brand);
		}
	}

	private static void deleteIndexFixture(Path index, boolean indexWasThere) {
		try {
			Files.deleteIfExists(index.resolve("sodium.pw.toml"));
			if (!indexWasThere) {
				Files.deleteIfExists(index);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean modFileAction(Recommendation r) {
		return r.action() instanceof Action.AddMod || r.action() instanceof Action.UpdateMod || r.action() instanceof Action.DisableMod;
	}

	// AC4j.2: nothing that changes a mod file is appliable, and every row the app now does carries its steps line.
	private static void launcherAdvice(ClientGameTestContext context, RealController real) {
		Report report = context.computeOnClient(mc -> real.report());
		check(report.recommendations().stream().noneMatch(LauncherManagedGameTest::modFileAction), "no Add/Update/Disable appliable: " + report.recommendations());
		List<Recommendation> advised = report.recommendations().stream().filter(LauncherModAdvice::advised).toList();
		RigTune.LOGGER.info("LauncherManagedGameTest: {} mod-file rows are the Modrinth App's: {}", advised.size(), advised.stream().map(Recommendation::id).toList());
		// Review M5: the offline catalog's add rows are there in every run, so the checks below can fail; each advised row
		// carries the app's note, never PENDING's.
		check(!advised.isEmpty(), "the offline catalog's add rows became the app's advice");
		check(advised.stream().allMatch(r -> !r.appliable() && !r.selectedByDefault()), "advised rows are unticked advice");
		for (Recommendation r : advised) {
			List<String> notes = keys(r.reasonText()).filter(k -> k.startsWith("rigtune.launcher.mod_files.note.")).toList();
			check(notes.size() == 1 && NOTES.contains(notes.getFirst()), r.id() + "'s launcher note: " + notes);
		}
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		List<Component> lines = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).launcherLines());
		long steps = lines.stream().filter(line -> line.getContents() instanceof TranslatableContents t && t.getArgs().length == 2
				&& t.getArgs()[1] instanceof Component s && s.getContents() instanceof TranslatableContents k
				&& k.getKey().startsWith("rigtune.launcher.mod_steps.modrinth_app.")).count();
		check(steps == advised.stream().filter(r -> LauncherModAdvice.kindOf(r) != null).count(), "one Modrinth App steps line per advised row: " + lines);
		for (int[] size : V05TestContext.SIZES) {
			resize(context, size);
			context.takeScreenshot("launcher-managed-rows-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, new int[]{854, 480, 2});
	}

	private static final Set<String> NOTES = Set.of("rigtune.launcher.mod_files.note.add", "rigtune.launcher.mod_files.note.update",
			"rigtune.launcher.mod_files.note.disable");

	// Every translation key in a Text, its arguments' too.
	private static Stream<String> keys(@Nullable Text text) {
		return switch (text) {
			case null -> Stream.empty();
			case Text.Translatable t -> Stream.concat(Stream.of(t.key()), t.args().stream().filter(a -> a instanceof Text).flatMap(a -> keys((Text) a)));
			case Text.Joined j -> j.parts().stream().flatMap(LauncherManagedGameTest::keys);
			case Text.Literal l -> Stream.empty();
		};
	}

	// AC4b.2: Apply given a crafted add, update and disable under LAUNCHER stages nothing and leaves mods/ byte-identical.
	private static void refusedApply(ClientGameTestContext context, RealController real, Path configDir) {
		Path mods = real.modsDir();
		Map<String, String> before = hashes(mods);
		long fileOpsBefore = fileOps(configDir);
		Path jar = before.keySet().stream().filter(name -> name.endsWith(".jar")).findFirst().map(mods::resolve).orElse(mods.resolve("none.jar"));
		List<Recommendation> crafted = List.of(
				new Recommendation("add:ws-l1-crafted", Category.ADD_MOD, Impact.LOW, "Install", "r", new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true),
				new Recommendation("update:ws-l1-crafted", Category.UPDATE_MOD, Impact.LOW, "Update", "r", new Action.UpdateMod("ws-l1-crafted", jar,
						new UpdateInfo("ws-l1-crafted", "p", "1", "v", "2", new ModFile("https://cdn.modrinth.com/x.jar", "ws-l1-crafted-2.jar", "0", 1))), true),
				new Recommendation("disable:ws-l1-crafted", Category.REMOVE_MOD, Impact.LOW, "Disable", "r", new Action.DisableMod("ws-l1-crafted", jar), true));
		String status = context.computeOnClient(mc -> real.apply(crafted).getString());
		context.waitTicks(20);
		check(status.equals(Component.translatable("rigtune.status.nothing").getString()), "the crafted Apply did nothing: " + status);
		check(!real.downloading(), "no download started");
		check(fileOps(configDir) == fileOpsBefore, "no file op staged in pending.json");
		check(hashes(mods).equals(before), "mods/ unchanged");
	}

	// AC4j.2: an applied mod-file change in history.json (a fixture entry, removed afterwards) is skipped by Undo all with
	// the launcher's reason, and the Undo screen adds the app's steps.
	private static void undoSkipsAppliedModFiles(ClientGameTestContext context, RealController real) throws IOException {
		JournalEntry fixture = new JournalEntry(FIXTURE_ENTRY, "2026-09-26T10:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null,
				List.of(JournalChange.file(JournalChange.ENABLE, "ws-l1-fixture", FIXTURE_JAR, JournalChange.APPLIED, "op-ws-l1", "g-ws-l1")));
		ClientJournal.get().update(entries -> {
			List<JournalEntry> out = new ArrayList<>(entries);
			out.add(fixture);
			return out;
		});
		try {
			// On the render thread: from the test thread, UndoService's wait for the render thread would never end (the harness runs
			// one of the two at a time).
			UndoPlan plan = context.computeOnClient(mc -> real.undoPlan(true));
			check(plan != null, "an Undo all plan");
			UndoPlan.Item item = plan.items().stream().filter(i -> i.description().contains(FIXTURE_JAR)).findFirst()
					.orElseThrow(() -> new AssertionError("the fixture change is in the plan: " + plan));
			check(item.action() == UndoPlan.Action.SKIP, "the applied file change is skipped: " + item);
			check("This instance's mods are managed by the Modrinth App: change it there".equals(item.reason()), "the launcher reason: " + item.reason());
			context.runOnClient(mc -> mc.gui.setScreen(new UndoScreen(new TitleScreen(), real, true)));
			context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen undo && undo.plan() != null, 400);
			context.waitTicks(3);
			context.takeScreenshot("launcher-managed-undo-854x480-scale2");
		} finally {
			ClientJournal.get().update(entries -> entries.stream().filter(e -> !FIXTURE_ENTRY.equals(e.id())).toList());
		}
	}

	// AC4e.1/AC4e.2: the Settings row turns the opt-in on (saved through SettingsSaver): the report is RigTune's again, and
	// the header's one warning line says so at every size; then off again.
	private static void optIn(ClientGameTestContext context, RealController real, Path configDir) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneSettingsScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneSettingsScreen.class);
		context.waitTicks(2);
		String row = context.computeOnClient(mc -> modFilesSwitch(mc.gui.screen()).getMessage().getString());
		check(row.equals("Mod files: Change them in the Modrinth App"), "the row's default: " + row);
		context.takeScreenshot("launcher-managed-settings-854x480-scale2");

		Report[] old = {context.computeOnClient(mc -> real.report())};
		pressModFiles(context);
		check(SettingsSaver.shared().flush(10_000) && ClientSettings.load(configDir).modFilesByRigTune, "the opt-in saved to settings.json");
		context.waitFor(mc -> real.report() != null && real.report() != old[0] && real.modFiles() == ModFilesPolicy.RIGTUNE, 1200);
		check(real.report().recommendations().stream().noneMatch(LauncherModAdvice::advised), "opted in: no row is the launcher's");

		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneScreen.class);
		String line = "RigTune changes mod files here; the Modrinth App's own list may go out of date.";
		for (int[] size : V05TestContext.SIZES) {
			resize(context, size);
			List<String> header = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).headerLines().stream().map(Component::getString).toList());
			check(header.contains(line), "the opted-in line: " + header);
			check(header.stream().filter(h -> h.startsWith("Offline") || h.startsWith("Modrinth off") || h.equals(line)).count() == 1, "one warning line: " + header);
			context.takeScreenshot("launcher-managed-opted-in-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, new int[]{854, 480, 2});

		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneSettingsScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneSettingsScreen.class);
		context.waitTicks(2);
		old[0] = context.computeOnClient(mc -> real.report());
		pressModFiles(context);
		check(SettingsSaver.shared().flush(10_000) && !ClientSettings.load(configDir).modFilesByRigTune, "the opt-in off again");
		context.waitFor(mc -> real.report() != null && real.report() != old[0] && real.modFiles() == ModFilesPolicy.LAUNCHER, 1200);
	}

	private static CycleButton<?> modFilesSwitch(Screen screen) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof ContainerObjectSelectionList<?>)
				.flatMap(w -> ((ContainerObjectSelectionList<?>) w).children().stream().flatMap(row -> row.children().stream()))
				.filter(w -> w instanceof CycleButton<?> && ((AbstractWidget) w).getMessage().getContents() instanceof TranslatableContents t
						&& t.getArgs().length > 0 && t.getArgs()[0] instanceof Component name && name.getContents() instanceof TranslatableContents n
						&& n.getKey().equals("rigtune.settings.mod_files"))
				.map(w -> (CycleButton<?>) w)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no Mod files row on " + screen));
	}

	private static void pressModFiles(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			CycleButton<?> button = modFilesSwitch(mc.gui.screen());
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		});
		context.waitTicks(2);
	}

	private static void resize(ClientGameTestContext context, int[] size) {
		context.getInput().resizeWindow(size[0], size[1]);
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(size[2]);
			mc.resizeGui();
		});
		context.waitTicks(3);
	}

	// Each file in the folder (not its subfolders) by name, with its SHA-256.
	private static Map<String, String> hashes(Path dir) {
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				try (InputStream in = Files.newInputStream(file)) {
					out.put(file.getFileName().toString(), HexFormat.of().formatHex(digest.digest(in.readAllBytes())));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		return out;
	}

	private static Set<String> pendingIds(Path configDir) {
		Path pending = PendingActions.defaultPath(configDir);
		if (!Files.exists(pending)) {
			return Set.of();
		}
		try {
			return PendingActions.load(pending).ops().stream().map(PendingActions.Op::id).filter(Objects::nonNull).collect(Collectors.toSet());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// Review L12: whatever this block staged (nothing should be: the crafted Apply is refused) is dropped again; with nothing
	// staged before, through the Discard path.
	private static void dropCreatedPendingOps(ClientGameTestContext context, RealController real, Path configDir, Set<String> before) {
		Set<String> created = new HashSet<>(pendingIds(configDir));
		created.removeAll(before);
		if (created.isEmpty()) {
			return;
		}
		RigTune.LOGGER.warn("LauncherManagedGameTest: dropping {} pending op(s) it staged", created.size());
		if (before.isEmpty()) {
			context.runOnClient(mc -> real.discardPending());
			return;
		}
		Path pending = PendingActions.defaultPath(configDir);
		try {
			PendingActions.load(pending).remove(created).plan().save(pending);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static long fileOps(Path configDir) {
		Path pending = PendingActions.defaultPath(configDir);
		if (!Files.exists(pending)) {
			return 0;
		}
		try {
			return PendingActions.load(pending).ops().stream()
					.filter(op -> op.type() == PendingActions.Type.ENABLE_FILE || op.type() == PendingActions.Type.DISABLE_FILE).count();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// ---- WS-L2 (AC4d.2, AC4d.4, AC4g.2): held pending file groups and the repair notice.

	private static void heldAndRepair(V05TestContext v05) {
	}
}
