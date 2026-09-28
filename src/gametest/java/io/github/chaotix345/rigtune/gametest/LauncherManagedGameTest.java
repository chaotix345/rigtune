package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.FirstRunService;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.launcher.LauncherRepairService;
import io.github.chaotix345.rigtune.client.launcher.ModFilesService;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneSettingsScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.LauncherRepair;
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
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.toasts.SystemToast;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
			modFilesNews(context, real);

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

	// AC4b.6 (game): MOD_FILES_NEWS under LAUNCHER for a returning player only (each status forced through WS-F's seam, then
	// put back); its Settings… opens the settings on the Mod files row (review L15, through the real notice source).
	private static void modFilesNews(ClientGameTestContext context, RealController real) {
		FirstRunService firstRun = real.v05().firstRun();
		FirstRun.Status status = firstRun.status();
		try {
			firstRun.forceStatusForTests(FirstRun.Status.NEW);
			check(news(context, real).isEmpty(), "no MOD_FILES_NEWS for a new player");
			firstRun.forceStatusForTests(FirstRun.Status.RETURNING);
			List<Notice> shown = news(context, real);
			check(shown.size() == 1 && shown.getFirst().message().english().equals("RigTune now leaves this instance's mod files to the Modrinth App"),
					"MOD_FILES_NEWS for a returning player: " + shown);
			context.getInput().setCursorPos(1, 1);
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			context.waitForScreen(TitleScreen.class);
			context.runOnClient(mc -> real.noticeAction(ModFilesService.NEWS_KEY, ModFilesService.NEWS_SETTINGS));
			context.waitForScreen(RigTuneSettingsScreen.class);
			context.waitTicks(2);
			String focused = context.computeOnClient(mc -> mc.gui.screen().getCurrentFocusPath() != null
					&& mc.gui.screen().getCurrentFocusPath().leafComponent() instanceof AbstractWidget w ? w.getMessage().getString() : "");
			check(focused.equals("Mod files: Change them in the Modrinth App"), "the news' Settings… focuses the Mod files row: " + focused);
			context.takeScreenshot("launcher-managed-news-settings-854x480-scale2");
		} finally {
			firstRun.forceStatusForTests(status);
		}
		RigTune.LOGGER.info("LauncherManagedGameTest: MOD_FILES_NEWS for RETURNING only (this instance: {}); Settings… focuses the Mod files row", status);
	}

	private static List<Notice> news(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> real.notices()).stream().filter(n -> n.key().equals(ModFilesService.NEWS_KEY)).toList();
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

	// With the real controller and the network off (X1), under the real policy: the Modrinth App's brand through the real
	// probe (WS-L1's redetect), so LAUNCHER. Only the leftover toasts' check forces the policy (the service's test seam),
	// to hold it at PENDING and then give each answer. Seeded as a 0.4 instance would have them: an applied update pair still in effect and an added jar (history.json + mods/), and a staged 0.4 file group
	// (pending.json, its download, its STAGED journal changes). Everything is put back afterwards: pending.json,
	// history.json, awareness.json, the opt-in (then a rebuild), the brand and the policy it gave, the fixture jars, the
	// toasts, the GUI scale, the network switch. The fixture jars' names are new at every run, so the repair notice's key, which Dismiss also keeps in
	// AwarenessService's in-memory session set (no API clears it), can never match anything else.
	private static void heldAndRepair(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		RealController real = v05.realController();
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> real.report() != null, 1200);
		Path config = v05.configDir();
		Path pending = PendingActions.defaultPath(config);
		Path history = Journal.file(config);
		Path awareness = real.awarenessService().file();
		byte[] pendingBefore = read(pending);
		byte[] historyBefore = read(history);
		byte[] awarenessBefore = read(awareness);
		boolean optIn = real.settings().modFilesByRigTune;
		String brand = System.getProperty(BRAND);
		ModFilesPolicy policyBefore = real.modFiles();
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		String run = UUID.randomUUID().toString().substring(0, 8);
		List<Path> made = new ArrayList<>();
		boolean network = GameTestNet.set(context, real, false);
		try {
			System.setProperty(BRAND, "theseus");
			redetect(context, real, ModFilesPolicy.LAUNCHER);
			String repairKey = seedRepairRecords(real, made, run);
			List<Op> held = seedHeldGroup(real, made, "a");
			// The staged ops RigTune counts for "restart to apply N" include the seeded group, as after a start with it.
			context.runOnClient(mc -> real.stagedChanged());
			context.waitTicks(2);
			check(restartCount(context, real) == 2, "the seeded group counts in the restart count");
			openRigTune(context);
			Notice heldNotice = waitForNotice(context, real, LauncherRepairService.HELD_KEY);
			check(heldNotice.priority() == NoticePriority.HELD_MOD_CHANGES && !heldNotice.dismissible(), "the held notice: " + heldNotice);
			check(heldNotice.message().english().equals("1 mod change(s) from an earlier Apply are waiting: Modrinth App manages this instance's mods."),
					heldNotice.message().english());
			for (int[] size : V05TestContext.SIZES) {
				v05.resize(size[0], size[1], size[2]);
				openRigTune(context);
				screenshot(context, "launcher-held-notice-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			}
			v05.resize(1280, 720, 2);

			// AC4g.2: the repair notice, its Copy list (names only) and Dismiss.
			Notice repair = waitForNotice(context, real, repairKey);
			check(repair.priority() == NoticePriority.LAUNCHER_REPAIR && repair.dismissible(), "the repair notice: " + repair);
			String detail = Objects.requireNonNull(repair.detail()).english();
			check(detail.contains("rigtunetestpair-" + run + "-1.0.jar.disabled") && detail.contains("rigtunetestadded-" + run + "-1.0.jar"), detail);
			context.runOnClient(mc -> mc.keyboardHandler.setClipboard(""));
			context.runOnClient(mc -> real.noticeAction(repairKey, LauncherRepairService.COPY));
			String copied = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
			check(copied.equals("rigtunetestpair-" + run + "-1.0.jar.disabled"), "Copy list puts the file names only on the clipboard: '" + copied + "'");
			context.runOnClient(mc -> real.dismissNotice(repairKey));
			check(find(notices(context, real), repairKey) == null, "the repair notice is gone once dismissed");

			// AC4d.2: Cancel them.
			context.runOnClient(mc -> real.noticeAction(LauncherRepairService.HELD_KEY, LauncherRepairService.CANCEL));
			Path download = Path.of(held.get(1).from());
			context.waitFor(mc -> !hasFileOp(pending) && !Files.exists(download), 200);
			String superseded = download.getFileName().toString().replace(PendingActions.PENDING_SUFFIX, PendingActions.SUPERSEDED_SUFFIX);
			check(Files.exists(download.resolveSibling(superseded)), "the download is .rigtune-superseded");
			List<String> statuses = statuses(held);
			check(statuses.equals(List.of(JournalChange.DISCARDED, JournalChange.DISCARDED)), "the journal marks them DISCARDED: " + statuses);
			context.waitFor(mc -> find(real.notices(), LauncherRepairService.HELD_KEY) == null, 200);
			// The controller recounted (on the render thread, after Cancel's worker): an Apply now says nothing waits for a
			// restart.
			context.waitTicks(3);
			check(restartCount(context, real) == 0, "after Cancel them, the restart count no longer counts the cancelled group");

			// AC4d.4: the title screen's leftover toast waits for the policy and says "waiting for your choice"; under RIGTUNE it
			// is today's.
			seedHeldGroup(real, made, "b");
			leftoverToast(context, real, ModFilesPolicy.LAUNCHER, LauncherRepairService.HELD_TOAST, LauncherRepairService.LEFTOVER_TOAST);
			leftoverToast(context, real, ModFilesPolicy.RIGTUNE, LauncherRepairService.LEFTOVER_TOAST, LauncherRepairService.HELD_TOAST);
			LauncherRepairService.overridePolicy(null);

			// AC4d.2: Let RigTune apply them turns the opt-in on.
			openRigTune(context);
			waitForNotice(context, real, LauncherRepairService.HELD_KEY);
			context.runOnClient(mc -> real.noticeAction(LauncherRepairService.HELD_KEY, LauncherRepairService.APPLY));
			context.waitFor(mc -> real.settings().modFilesByRigTune && real.modFiles() == ModFilesPolicy.RIGTUNE, 200);
			check(SettingsSaver.shared().flush(5_000), "settings.json saved");
			check(ClientSettings.load(config).modFilesByRigTune, "settings.json has the opt-in");
			check(find(notices(context, real), LauncherRepairService.HELD_KEY) == null, "no held notice once RigTune may change the mod files");
			RigTune.LOGGER.info("LauncherManagedGameTest: held changes (notice, Cancel them, Let RigTune apply them, the leftover toasts) and the"
					+ " repair notice (text, Copy list, Dismiss) checked");
		} finally {
			// The files first: later classes must never see the seeds, whatever fails below.
			restore(pending, pendingBefore);
			restore(history, historyBefore);
			restore(awareness, awarenessBefore);
			made.forEach(LauncherManagedGameTest::delete);
			LauncherRepairService.overridePolicy(null);
			ClientSettings settings = real.settings();
			settings.modFilesByRigTune = optIn;
			SettingsSaver.shared().save(settings, config);
			SettingsSaver.shared().flush(5_000);
			restoreBrand(brand);
			context.runOnClient(mc -> {
				mc.gui.toastManager().clear();
				mc.gui.setScreen(new TitleScreen());
				real.stagedChanged();
			});
			redetect(context, real, policyBefore);
			GameTestNet.set(context, real, network);
			v05.resize(854, 480, guiScale);
		}
	}

	// An applied update of "rigtunetestpair" still in effect (its old copy disabled, its new one there) and an added
	// "rigtunetestadded", as 0.1.0-0.4's journal records them, file names with this run's tag. Returns the repair notice's
	// key.
	private static String seedRepairRecords(RealController real, List<Path> made, String run) {
		Path mods = real.modsDir();
		String old = "rigtunetestpair-" + run + "-1.0.jar";
		String updated = "rigtunetestpair-" + run + "-2.0.jar";
		String added = "rigtunetestadded-" + run + "-1.0.jar";
		jar(mods.resolve(old + ".disabled"), "rigtunetestpair", made);
		jar(mods.resolve(updated), "rigtunetestpair", made);
		jar(mods.resolve(added), "rigtunetestadded", made);
		ClientJournal.get().record("gametest-l2-repair-" + UUID.randomUUID(), JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "rigtunetestpair", old, JournalChange.APPLIED, UUID.randomUUID().toString(), "g-pair")
						.withResultFile(old + ".disabled"),
				JournalChange.file(JournalChange.ENABLE, "rigtunetestpair", updated, JournalChange.APPLIED, UUID.randomUUID().toString(), "g-pair"),
				JournalChange.file(JournalChange.ENABLE, "rigtunetestadded", added, JournalChange.APPLIED, UUID.randomUUID().toString(), "g-added")));
		return new LauncherRepair.Findings(List.of(new LauncherRepair.Pair("rigtunetestpair", old + ".disabled", updated)), List.of(added), List.of())
				.key(real.launcher().launcher());
	}

	// N in the status an Apply of nothing gives ("Restart Minecraft to finish applying N change(s)"), 0 without that part:
	// the staged changes RealController counts (recounted after pending.json changed: stagedChanged).
	private static int restartCount(ClientGameTestContext context, RealController real) {
		Component status = context.computeOnClient(mc -> real.apply(List.of()));
		return restart(status);
	}

	private static int restart(Component component) {
		if (component.getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.status.restart") && t.getArgs().length == 1
				&& t.getArgs()[0] instanceof Number n) {
			return n.intValue();
		}
		for (Component sibling : component.getSiblings()) {
			int found = restart(sibling);
			if (found > 0) {
				return found;
			}
		}
		return 0;
	}

	// A 0.4-staged update of "rigtunetestheld<tag>": pending.json, its download, and its STAGED journal changes.
	private static List<Op> seedHeldGroup(RealController real, List<Path> made, String tag) {
		Path mods = real.modsDir();
		String modId = "rigtunetestheld" + tag;
		Path old = jar(mods.resolve(modId + "-1.0.jar"), modId, made);
		Path download = jar(mods.resolve(modId + "-2.0.jar" + PendingActions.PENDING_SUFFIX), modId, made);
		made.add(mods.resolve(modId + "-2.0.jar" + PendingActions.SUPERSEDED_SUFFIX));
		List<Op> group = PendingActions.group(Op.disableFile(old), Op.enableFile(download, mods.resolve(modId + "-2.0.jar")).withModId(modId));
		try {
			PendingActions.create(1, mods, real.configDir(), group).save(PendingActions.defaultPath(real.configDir()));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		ClientJournal.get().record("gametest-l2-held-" + tag + "-" + UUID.randomUUID(), JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, modId, old.getFileName().toString(), JournalChange.STAGED, group.get(0).id(), group.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, modId, modId + "-2.0.jar", JournalChange.STAGED, group.get(1).id(), group.get(1).group())));
		return group;
	}

	// The leftover toasts for two staged ops under `policy`: none while the policy is PENDING, then `shown` and never
	// `absent`.
	private static void leftoverToast(ClientGameTestContext context, RealController real, ModFilesPolicy policy, SystemToast.SystemToastId shown,
			SystemToast.SystemToastId absent) {
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		LauncherRepairService.overridePolicy(ModFilesPolicy.PENDING);
		context.runOnClient(mc -> real.v05().launcherRepair().leftoverAtTitle(2));
		context.waitTicks(5);
		check(context.computeOnClient(mc -> mc.gui.toastManager().getToast(SystemToast.class, shown) == null
				&& mc.gui.toastManager().getToast(SystemToast.class, absent) == null), "no leftover toast while the policy isn't known");
		LauncherRepairService.overridePolicy(policy);
		context.waitFor(mc -> mc.gui.toastManager().getToast(SystemToast.class, shown) != null, 200);
		// Past the toast's slide-in, so the screenshot shows all of it.
		context.waitTicks(30);
		check(context.computeOnClient(mc -> mc.gui.toastManager().getToast(SystemToast.class, absent) == null), "under " + policy + " only its own toast");
		context.getInput().setCursorPos(1, 1);
		context.takeScreenshot("launcher-leftover-toast-" + policy.name().toLowerCase(Locale.ROOT));
	}

	private static void openRigTune(ClientGameTestContext context) {
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
	}

	// Asks on the render thread (the notice sources' thread) until the notice is there; the first ask starts the read.
	private static Notice waitForNotice(ClientGameTestContext context, RealController real, String key) {
		context.waitFor(mc -> find(real.notices(), key) != null, 400);
		return Objects.requireNonNull(find(notices(context, real), key));
	}

	private static List<Notice> notices(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> real.notices());
	}

	private static @Nullable Notice find(List<Notice> notices, String key) {
		return notices.stream().filter(n -> n.key().equals(key)).findFirst().orElse(null);
	}

	private static boolean hasFileOp(Path pending) {
		try {
			return Files.isRegularFile(pending) && PendingActions.load(pending).ops().stream()
					.anyMatch(op -> op.type() == PendingActions.Type.ENABLE_FILE || op.type() == PendingActions.Type.DISABLE_FILE);
		} catch (IOException e) {
			return true;
		}
	}

	private static List<String> statuses(List<Op> ops) {
		List<String> out = new ArrayList<>();
		for (JournalEntry entry : ClientJournal.get().entries()) {
			for (JournalChange change : entry.changes()) {
				if (ops.stream().anyMatch(op -> op.id().equals(change.opId()))) {
					out.add(change.status());
				}
			}
		}
		return out;
	}

	private static Path jar(Path file, String modId, List<Path> made) {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		made.add(file);
		return file;
	}

	private static byte @Nullable [] read(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new AssertionError("Could not read " + file, e);
		}
	}

	private static void restore(Path file, byte @Nullable [] bytes) {
		try {
			if (bytes == null) {
				Files.deleteIfExists(file);
			} else {
				Files.write(file, bytes);
			}
		} catch (IOException e) {
			throw new AssertionError("Could not restore " + file, e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			RigTune.LOGGER.warn("LauncherManagedGameTest: could not delete {}", file.getFileName(), e);
		}
	}

	// No toast over the notice line; the cursor in a corner.
	private static void screenshot(ClientGameTestContext context, String name) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		context.waitTicks(2);
		context.takeScreenshot(name);
	}
}
