package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.PreviewScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RowFocus;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.DownloadPlanner;
import io.github.chaotix345.rigtune.core.modrinth.DryRunPlanner;
import io.github.chaotix345.rigtune.core.modrinth.HttpModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.modrinth.RangeReader;
import io.github.chaotix345.rigtune.core.modrinth.StagedProjects;
import io.github.chaotix345.rigtune.core.modrinth.VersionPins;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.preview.DownloadInputs;
import io.github.chaotix345.rigtune.core.preview.PreviewPlanner;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// docs/v0.3/SPEC.md item 13 (AC13.2): the Preview button sits right after Apply and the footer still fits at every
// reference size, including 640x480 at GUI scale 2 (review X-M2); a preview of every kind of change renders inside
// the screen at those sizes; the real controller's preview of the report's ticked items writes nothing. And AC13.1
// through the real RealController.apply: what the preview says Apply writes to options.txt is exactly what Apply then
// writes, and the files a staged Sodium key and disable would touch are exactly the ones pending.json names.
public class PreviewGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{640, 480, 2}, {854, 480, 2}, {1280, 720, 2}};
	private static final int GAP = 4;
	private static final int MIN_BUTTON = 91;

	private final Path gameDir = FabricLoader.getInstance().getGameDir();
	private final Path configDir = FabricLoader.getInstance().getConfigDir();
	private final Path modsDir = InstanceDirs.modsDir(gameDir);

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController real = RigTuneClient.controller();
		Path history = Journal.file(configDir);
		byte[] historyBefore = read(history);
		try {
			canned(context, false);
			canned(context, true);
			realPreviewWritesNothing(context, real);
			downloadChecks(context, real);
			vanillaAsPreviewed(context, real);
			stagedAsPreviewed(context, real);
		} finally {
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			restore(history, historyBefore);
			resize(context, 854, 480, 0);
		}
	}

	// The stub's report (seven items ticked by default) through a controller whose preview is canned.
	private void canned(ClientGameTestContext context, boolean pending) {
		CannedController canned = new CannedController(new StubController(RigTuneClient::hardware), pending, cannedPreview());
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), canned)));
		context.waitForScreen(RigTuneScreen.class);
		String variant = pending ? "-pending" : "";
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			String name = size[0] + "x" + size[1] + "-scale" + size[2] + variant;
			checkFooter(context, name);
			if (!pending || size == SIZES[0]) {
				context.takeScreenshot("preview-footer-" + name);
			}
			if (pending) {
				continue;
			}
			List<String> ticked = context.computeOnClient(mc -> {
				RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
				return screen.controller().report().recommendations().stream()
						.filter(r -> r.appliable() && screen.selectedIds().contains(r.id())).map(Recommendation::id).toList();
			});
			press(context, "rigtune.preview.button");
			PreviewScreen preview = waitForPreview(context, 100);
			check(canned.asked.equals(ticked), "the preview gets exactly Apply's selection: " + canned.asked + " vs " + ticked);
			checkPreviewLayout(context, "preview " + name);
			context.takeScreenshot("preview-" + name);
			if (size == SIZES[2]) {
				List<String> rows = context.computeOnClient(mc -> preview.rowText());
				RigTune.LOGGER.info("PreviewGameTest: canned preview rows:\n{}", String.join("\n", rows));
				check(rows.contains("options.txt") && rows.contains("config/DistantHorizons.toml"), "files shown relative to the game folder: " + rows);
				check(rows.stream().anyMatch(r -> r.startsWith("mods/fabric-api-0.140.2+26.2.jar (needed by Add Lithium)")), "a dependency: " + rows);
				check(rows.stream().anyMatch(r -> r.startsWith("A file from Modrinth for Add FerriteCore")), "an unresolved addition: " + rows);
				checkSettingRows(context, rows);
			}
			press(context, "gui.done");
			context.waitForScreen(RigTuneScreen.class);
		}
	}

	// docs/v0.4/SPEC.md 2b (AC2b.2): each setting row reads as History and the main list name it, not the file's raw key and
	// value; a vanilla row matches the main list's own title for that key.
	private static void checkSettingRows(ClientGameTestContext context, List<String> rows) {
		List<String> expected = List.of("Render Distance: 16 → 12", "Simulation Distance: 12 → 8", "Sodium: Use Entity Culling: Off → On",
				"Distant Horizons: LOD Chunk Render Distance Radius: 256 → 128", "Distant Horizons: LOD Dropoff Distance: High → Medium",
				"Iris: Max Shadow Distance: not set → 16");
		for (String row : expected) {
			check(rows.contains(row), "a labelled setting row '" + row + "': " + rows);
		}
		check(rows.stream().noneMatch(r -> r.startsWith("renderDistance:") || r.startsWith("performance.use_entity_culling:")), "no raw keys: " + rows);
		press(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
		List<String> mainList = context.computeOnClient(mc -> {
			RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
			return List.of(screen.titleOf("set-vanilla.renderDistance"), screen.titleOf("set-vanilla.simulationDistance"));
		});
		check(mainList.equals(expected.subList(0, 2)), "the main list names them the same: " + mainList);
		press(context, "rigtune.preview.button");
		waitForPreview(context, 100);
	}

	private static void checkFooter(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			Button apply = findButton(screen, "rigtune.screen.apply.count");
			Button preview = findButton(screen, "rigtune.preview.button");
			check(apply != null && preview != null, name + ": Apply and Preview are there");
			check(apply.active && preview.active, name + ": both active with items ticked");
			check(preview.getY() == apply.getY() && preview.getX() == apply.getRight() + GAP, name + ": Preview is right after Apply: " + describe(apply)
					+ " " + describe(preview));
			List<Button> buttons = Screens.getWidgets(screen).stream().filter(w -> w instanceof Button && w.getY() >= apply.getY())
					.map(Button.class::cast).toList();
			RigTune.LOGGER.info("PreviewGameTest: footer at {}: {}", name, buttons.stream().map(PreviewGameTest::describe).toList());
			for (Button b : buttons) {
				check(b.getWidth() >= MIN_BUTTON, name + ": at least " + MIN_BUTTON + " px: " + describe(b));
			}
		});
		checkLayout(context, "footer " + name);
	}

	// Every widget inside the screen and apart, and every row's text inside the list's column.
	private static void checkPreviewLayout(ClientGameTestContext context, String name) {
		checkLayout(context, name);
		context.runOnClient(mc -> {
			PreviewScreen screen = (PreviewScreen) mc.gui.screen();
			PreviewScreen.PreviewList list = screen.list();
			check(list != null && !list.children().isEmpty(), name + ": rows shown");
			for (PreviewScreen.PreviewList.Row row : list.children()) {
				check(row.textFits(), name + ": a row's text runs past the column");
			}
		});
	}

	private void realPreviewWritesNothing(ClientGameTestContext context, RigTuneController real) {
		TreeMap<String, String> before = snapshot();
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		resize(context, 854, 480, 2);
		boolean ticked = context.computeOnClient(mc -> {
			Button preview = findButton(mc.gui.screen(), "rigtune.preview.button");
			check(preview != null, "Preview on the real screen");
			return preview.active;
		});
		if (ticked) {
			press(context, "rigtune.preview.button");
		} else {
			// Nothing ticked by default on this machine: preview every item Apply could take.
			context.runOnClient(mc -> {
				Report report = real.report();
				mc.gui.setScreen(new PreviewScreen(mc.gui.screen(), real, report.recommendations().stream().filter(Recommendation::appliable).toList()));
			});
		}
		// The ticked additions are looked up one after another, on the local fake Modrinth (build.gradle's ModrinthFixture;
		// a minute is ample; it was five against the live API).
		PreviewScreen screen = waitForPreview(context, 1200);
		ApplyPreview preview = context.computeOnClient(mc -> screen.preview());
		check(preview != null, "the real preview finished");
		List<String> rows = context.computeOnClient(mc -> screen.rowText());
		RigTune.LOGGER.info("PreviewGameTest: real preview ({}): {} now, {} at restart, {} downloads, {} disables, {} not changed:\n{}",
				ticked ? "default ticks" : "every appliable item", preview.now().size(), preview.atRestart().size(), preview.downloads().size(),
				preview.disables().size(), preview.skipped().size(), String.join("\n", rows));
		checkPreviewLayout(context, "real preview");
		context.takeScreenshot("preview-real");
		check(before.equals(snapshot()), "the preview wrote nothing: " + difference(before, snapshot()));
		// v0.5 L5: the candidates the fake Modrinth serves are text files, not jars, so their reads fail and the disclosure
		// line stands in for the check whenever a file is listed.
		boolean listed = preview.downloads().stream().anyMatch(d -> d.fileName() != null);
		check(hasNote(context, rows) == (listed && !preview.downloadsChecked()), "the disclosure line with unchecked downloads only: " + rows);
		press(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
	}

	// docs/v0.5/SPEC.md 2H L5 (AC2H.1b): with Modrinth on, the preview reads each download's fabric.mod.json from the fake
	// Modrinth through Range requests (FakeModrinth's `ranged`) and runs Apply's fabric.mod.json checks: Mod Menu's update,
	// which a test pin rules out, is under "Not changed" with Apply's own line, Fabric API is listed, and the disclosure
	// line isn't shown. With Modrinth off nothing is read, and the disclosure line shows with the listed update. A temporary
	// mods folder, so nothing of the game's is touched.
	private void downloadChecks(ClientGameTestContext context, RigTuneController real) {
		TreeMap<String, String> before = snapshot();
		boolean network = GameTestNet.set(context, real, true);
		Path temp = null;
		try {
			temp = Files.createTempDirectory("rigtune-preview-l5");
			Path mods = Files.createDirectories(temp.resolve("mods"));
			String mc = real.report().hardware().mcVersion();
			HttpModrinthClient modrinth = new HttpModrinthClient("gametest");
			ModrinthVersion modMenu = modrinth.latestVersion("mOgUt4GM", "fabric", mc).orElseThrow(() -> new AssertionError("the fake Modrinth serves Mod Menu"));
			Path oldModMenu = Files.writeString(mods.resolve("modmenu-old.jar"), "an older Mod Menu");
			Recommendation update = new Recommendation("l5:update-modmenu", Category.UPDATE_MOD, Impact.LOW, "Update Mod Menu", "",
					new Action.UpdateMod("modmenu", oldModMenu, new UpdateInfo("modmenu", modMenu.projectId(), "old", modMenu.id(), modMenu.versionNumber(),
							modMenu.primaryFile())), true);
			Recommendation fabricApi = new Recommendation("l5:add-fabric-api", Category.ADD_MOD, Impact.LOW, "Install Fabric API", "",
					new Action.AddMod("fabric-api", "P7dR8mSH", "Fabric API"), true);
			List<Recommendation> recs = List.of(update, fabricApi);
			VersionPins pins = new VersionPins(List.of(new VersionPins.Pin("l5-test", "L5 test mod", "l5-test", "modmenu", "Mod Menu",
					VersionPins.Kind.DEPENDS, () -> "99.x", v -> false)));
			List<String> reads = Collections.synchronizedList(new ArrayList<>());

			ApplyPreview checked = l5Preview(temp, mods, modrinth, mc, modMenu, pins, reads, recs);
			RigTune.LOGGER.info("PreviewGameTest: L5 preview with Modrinth on: {} (read {})", checked, reads);
			check(reads.size() == 2 && reads.getFirst().equals(modMenu.primaryFile().filename()), "both downloads read, Mod Menu's first: " + reads);
			check(checked.downloads().size() == 1 && checked.downloads().getFirst().recommendationId().equals(fabricApi.id()), "Fabric API listed: " + checked);
			check(checked.skipped().size() == 1 && checked.skipped().getFirst().recommendationId().equals(update.id())
					&& checked.skipped().getFirst().reason() == ApplyPreview.Reason.DOWNLOAD_FAILED, "Mod Menu's update refused: " + checked);
			String refusal = checked.skipped().getFirst().detail();
			check(refusal.startsWith("L5 test mod, which is installed, needs Mod Menu 99.x, not "), "the pin's line: " + refusal);
			check(refusal.equals(applysRefusal(mods, modrinth, mc, modMenu, pins, update)), "Apply's own line: " + refusal);
			check(checked.downloadsChecked(), "every listed download was checked");
			List<String> rows = showPreview(context, checked, recs, "preview-l5-checked");
			check(rows.stream().anyMatch(r -> r.equals("Update Mod Menu: " + refusal)), "the refusal under Not changed: " + rows);
			check(!hasNote(context, rows), "no disclosure line once every download was checked: " + rows);

			GameTestNet.set(context, real, false);
			reads.clear();
			ApplyPreview off = l5Preview(temp, mods, modrinth, mc, modMenu, pins, reads, recs);
			RigTune.LOGGER.info("PreviewGameTest: L5 preview with Modrinth off: {}", off);
			check(reads.isEmpty(), "nothing read with Modrinth off: " + reads);
			check(off.downloads().stream().map(ApplyPreview.Download::fileName).toList().equals(Arrays.asList(modMenu.primaryFile().filename(), null)),
					"the update listed, the addition unresolved: " + off);
			check(!off.downloadsChecked() && off.skipped().isEmpty(), "unchecked, nothing refused: " + off);
			List<String> offRows = showPreview(context, off, recs, "preview-l5-modrinth-off");
			check(hasNote(context, offRows), "the disclosure line with the listed update: " + offRows);
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			GameTestNet.set(context, real, network);
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			deleteTree(temp);
		}
		check(before.equals(snapshot()), "the L5 previews wrote nothing in the game folder: " + difference(before, snapshot()));
	}

	// The preview's own inputs, as RealController.preview builds them, over a temporary mods folder and a test pin; Modrinth
	// is on or off as RigTune's settings say.
	private ApplyPreview l5Preview(Path temp, Path mods, ModrinthClient modrinth, String mc, ModrinthVersion modMenu, VersionPins pins, List<String> reads,
			List<Recommendation> recs) {
		boolean lookups = ClientSettings.shared(configDir).modrinthAllowed();
		RangeReader reader = new RangeReader("gametest", () -> ClientSettings.shared(configDir).modrinthAllowed());
		DryRunPlanner.Checks checks = new DryRunPlanner.Checks(pins, Set.of(), file -> {
			reads.add(file.filename());
			return reader.read(file);
		}, reader::close);
		DownloadInputs inputs = new DownloadInputs(modrinth, lookups, "fabric", mc, Map.of(), Map.of(modMenu.id(), modMenu), Set.of(), Set.of(), Map.of(),
				(a, b) -> false, StagedProjects.NONE, true).withJarChecks(checks);
		return new PreviewPlanner(temp.resolve("options.txt"), Map.of(), Map.of(), List.of(), mods, inputs).preview(recs);
	}

	// What Apply's own planner says of Mod Menu's update once it has downloaded the whole jar (RealController.download's
	// DownloadPlanner, the same pin): its refusal's cause, as the preview shows it.
	private static String applysRefusal(Path mods, HttpModrinthClient modrinth, String mc, ModrinthVersion modMenu, VersionPins pins, Recommendation update) {
		DownloadPlanner.Result result = new DownloadPlanner(new DependencyResolver(modrinth, "fabric", mc, Map.of()), mods, file -> {
			Path pending = SafeFileNames.resolveJar(mods, file.filename(), PendingActions.PENDING_SUFFIX);
			modrinth.download(file, pending);
			return pending;
		}, (a, b) -> false, Map.of(modMenu.id(), modMenu), pins).plan(List.of(update), Set.of(), Set.of(), Map.of());
		check(result.errors().size() == 1, "Apply refuses the update: " + result.errors());
		String error = result.errors().getFirst();
		return error.substring(error.indexOf(": ") + 2);
	}

	private List<String> showPreview(ClientGameTestContext context, ApplyPreview preview, List<Recommendation> recs, String screenshot) {
		CannedController canned = new CannedController(new StubController(RigTuneClient::hardware), false, preview);
		context.runOnClient(mc -> mc.gui.setScreen(new PreviewScreen(new TitleScreen(), canned, recs)));
		resize(context, 854, 480, 2);
		PreviewScreen screen = waitForPreview(context, 100);
		checkPreviewLayout(context, screenshot);
		context.takeScreenshot(screenshot);
		List<String> rows = context.computeOnClient(mc -> screen.rowText());
		RigTune.LOGGER.info("PreviewGameTest: {} rows:\n{}", screenshot, String.join("\n", rows));
		return rows;
	}

	// A row reading the disclosure (a wrapped row's lines are joined with spaces).
	private static boolean hasNote(ClientGameTestContext context, List<String> rows) {
		String note = context.computeOnClient(mc -> Component.translatable("rigtune.preview.note.downloads").getString());
		return rows.stream().anyMatch(r -> r.replace(" ", "").equals(note.replace(" ", "")));
	}

	private static void deleteTree(@Nullable Path root) {
		if (root == null) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		} catch (IOException e) {
			RigTune.LOGGER.warn("PreviewGameTest: could not delete {}", root, e);
		}
	}

	// options.txt, mods/ and config/ (RigTune's own caches aside; its apply files included), by SHA-256.
	private TreeMap<String, String> snapshot() {
		TreeMap<String, String> out = new TreeMap<>();
		try {
			hash(gameDir.resolve("options.txt"), out);
			for (Path root : List.of(modsDir, configDir)) {
				if (!Files.isDirectory(root)) {
					continue;
				}
				try (Stream<Path> files = Files.walk(root)) {
					for (Path file : files.filter(Files::isRegularFile).toList()) {
						String name = gameDir.relativize(file).toString().replace('\\', '/');
						if (!name.startsWith("config/rigtune/") || name.endsWith("/pending.json") || name.endsWith("/history.json")
								|| name.endsWith("/last-apply.json")) {
							hash(file, out);
						}
					}
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		out.keySet().stream().filter(name -> name.endsWith(PendingActions.PENDING_SUFFIX)).findAny()
				.ifPresent(name -> check(false, "a staged download is lying around: " + name));
		return out;
	}

	private void hash(Path file, Map<String, String> out) throws IOException {
		if (Files.isRegularFile(file)) {
			try {
				out.put(gameDir.relativize(file).toString().replace('\\', '/'), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static String difference(Map<String, String> a, Map<String, String> b) {
		List<String> out = new ArrayList<>();
		a.forEach((k, v) -> {
			if (!v.equals(b.get(k))) {
				out.add(k);
			}
		});
		b.keySet().stream().filter(k -> !a.containsKey(k)).forEach(out::add);
		return out.toString();
	}

	// The vanilla half of AC13.1: preview a render-distance change, apply it through the real controller, and compare
	// options.txt before and after with what the preview said.
	private void vanillaAsPreviewed(ClientGameTestContext context, RigTuneController real) {
		Path options = gameDir.resolve("options.txt");
		int renderDistance = context.computeOnClient(mc -> {
			mc.options.save();
			return mc.options.renderDistance().get();
		});
		String next = Integer.toString(renderDistance > 2 ? renderDistance - 1 : renderDistance + 1);
		Recommendation change = new Recommendation("preview-test:renderDistance", Category.SETTING, Impact.LOW, "Render distance", "",
				new Action.SetSetting("vanilla.renderDistance", Integer.toString(renderDistance), next), true);
		Map<String, String> before = optionsTxt(options);
		try {
			ApplyPreview preview = context.computeOnClient(mc -> real.preview(List.of(change)));
			check(preview.now().equals(List.of(new ApplyPreview.Setting(change.id(), options, "renderDistance", Integer.toString(renderDistance), next))),
					"the preview: " + preview);
			check(preview.filesAtRestart().isEmpty() && preview.skipped().isEmpty(), "nothing else: " + preview);
			check(before.equals(optionsTxt(options)), "the preview didn't write options.txt");
			Recommendation outOfRange = new Recommendation("preview-test:renderDistance-99", Category.SETTING, Impact.LOW, "Render distance 99", "",
					new Action.SetSetting("vanilla.renderDistance", Integer.toString(renderDistance), "99"), true);
			ApplyPreview refused = context.computeOnClient(mc -> real.preview(List.of(outOfRange)));
			check(refused.now().isEmpty() && refused.skipped().size() == 1 && refused.skipped().getFirst().reason() == ApplyPreview.Reason.REFUSED
					&& refused.skipped().getFirst().detail().contains("99"), "a value the game refuses isn't listed as written: " + refused);

			Component status = context.computeOnClient(mc -> real.apply(List.of(change)));
			Map<String, String> after = optionsTxt(options);
			Map<String, String> changed = new LinkedHashMap<>();
			after.forEach((key, value) -> {
				if (!value.equals(before.get(key))) {
					changed.put(key, value);
				}
			});
			RigTune.LOGGER.info("PreviewGameTest: applied {} -> {} ({}); options.txt changed: {}", renderDistance, next, status.getString(), changed);
			check(changed.equals(Map.of("renderDistance", next)), "Apply wrote exactly what the preview said: " + changed);
		} finally {
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(renderDistance);
				mc.options.save();
			});
		}
	}

	// AC13.1 through the real RealController.apply for staged changes: a disable of a throwaway jar and, when Sodium is
	// loaded, a Sodium key flipped. pending.json then names exactly the preview's files (less the .disabled name the
	// helper picks at restart), and everything staged is discarded again.
	private void stagedAsPreviewed(ClientGameTestContext context, RigTuneController real) {
		Path pendingFile = PendingActions.defaultPath(configDir);
		Path probe = modsDir.resolve("rigtune-preview-probe.jar");
		context.runOnClient(mc -> real.discardPending());
		check(!Files.exists(pendingFile), "nothing staged to start with");
		List<Recommendation> changes = new ArrayList<>();
		try {
			Files.createDirectories(modsDir);
			modJar(probe, "rigtunepreviewprobe");
			changes.add(new Recommendation("preview-test:disable", Category.REMOVE_MOD, Impact.LOW, "Disable the probe", "",
					new Action.DisableMod("rigtunepreviewprobe", probe), true));
			Path sodium = configDir.resolve("sodium-options.json");
			Map.Entry<String, String> flag = FabricLoader.getInstance().isModLoaded("sodium") && Files.isRegularFile(sodium)
					? SettingsBridge.readSodium(sodium).entrySet().stream().filter(e -> e.getValue().equals("true") || e.getValue().equals("false"))
							.findFirst().orElse(null)
					: null;
			if (flag != null) {
				changes.add(new Recommendation("preview-test:sodium", Category.SETTING, Impact.LOW, "A Sodium switch", "",
						new Action.SetSetting(flag.getKey(), flag.getValue(), Boolean.toString(!Boolean.parseBoolean(flag.getValue()))), true));
			} else {
				RigTune.LOGGER.info("PreviewGameTest: Sodium isn't loaded or has no options file yet: the staged check covers the disable only");
			}
			byte[] sodiumBefore = read(sodium);
			ApplyPreview preview = context.computeOnClient(mc -> real.preview(changes));
			check(preview.skipped().isEmpty() && preview.now().isEmpty(), "everything is staged: " + preview);
			Set<Path> expected = new TreeSet<>();
			preview.filesAtRestart().forEach(p -> expected.add(p.toAbsolutePath().normalize()));
			preview.disables().forEach(d -> expected.remove(d.disabledAs().toAbsolutePath().normalize()));
			check(!Files.exists(pendingFile), "the preview staged nothing");

			Component status = context.computeOnClient(mc -> real.apply(changes));
			Set<Path> named = new TreeSet<>();
			for (PendingActions.Op op : PendingActions.load(pendingFile).ops()) {
				named.add(Path.of(op.type() == PendingActions.Type.ENABLE_FILE ? op.to() : op.path()).toAbsolutePath().normalize());
			}
			RigTune.LOGGER.info("PreviewGameTest: staged {} ({}); pending.json names {}", changes.stream().map(Recommendation::id).toList(), status.getString(),
					named.stream().map(p -> gameDir.relativize(p).toString()).toList());
			check(named.equals(expected), "pending.json names exactly the preview's files: " + named + " vs " + expected);
			check(Files.exists(probe) && Arrays.equals(sodiumBefore, read(sodium)), "staged only: nothing changes before the restart");
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			context.runOnClient(mc -> real.discardPending());
			try {
				Files.deleteIfExists(probe);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		check(!Files.exists(pendingFile), "discarded again");
	}

	private static void modJar(Path jar, String modId) throws IOException {
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static Map<String, String> optionsTxt(Path file) {
		Map<String, String> out = new LinkedHashMap<>();
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					out.put(line.substring(0, colon), line.substring(colon + 1));
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		return out;
	}

	// Every kind of row, with long DH keys and file names.
	private ApplyPreview cannedPreview() {
		Path options = gameDir.resolve("options.txt");
		Path dh = configDir.resolve("DistantHorizons.toml");
		return new ApplyPreview(
				List.of(new ApplyPreview.Setting("set-vanilla.renderDistance", options, "renderDistance", "16", "12"),
						new ApplyPreview.Setting("set-vanilla.simulationDistance", options, "simulationDistance", "12", "8")),
				List.of(new ApplyPreview.Setting("set-sodium.performance.use_entity_culling", configDir.resolve("sodium-options.json"),
								"performance.use_entity_culling", "false", "true"),
						new ApplyPreview.Setting("set-dh", dh, "client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
						new ApplyPreview.Setting("set-dh-2", dh, "client.advanced.graphics.quality.horizontalQuality", "HIGH", "MEDIUM"),
						new ApplyPreview.Setting("set-iris", configDir.resolve("iris.properties"), "maxShadowRenderDistance", null, "16")),
				List.of(new ApplyPreview.Download("update-sodium", "Update Sodium 0.9.1 → 0.9.2", "sodium-fabric-0.9.2+mc26.2.jar",
								modsDir.resolve("sodium-fabric-0.9.2+mc26.2.jar"), false),
						new ApplyPreview.Download("add-lithium", "Add Lithium", "lithium-fabric-0.21.0+mc26.2.jar", modsDir.resolve("lithium-fabric-0.21.0+mc26.2.jar"),
								false),
						new ApplyPreview.Download("add-lithium", "Add Lithium", "fabric-api-0.140.2+26.2.jar", modsDir.resolve("fabric-api-0.140.2+26.2.jar"), true),
						new ApplyPreview.Download("add-ferritecore", "Add FerriteCore", null, null, false)),
				List.of(new ApplyPreview.Disable("obsolete-indium", "Disable Indium", modsDir.resolve("indium-1.0.36+mc26.2.jar"),
								modsDir.resolve("indium-1.0.36+mc26.2.jar.disabled")),
						new ApplyPreview.Disable("update-sodium", "Update Sodium 0.9.1 → 0.9.2", modsDir.resolve("sodium-fabric-0.9.1+mc26.2.jar"),
								modsDir.resolve("sodium-fabric-0.9.1+mc26.2.jar.disabled.1"))),
				List.of(new ApplyPreview.Skipped("set-shadows", "Entity shadows: on → off", ApplyPreview.Reason.UNCHANGED, null),
						new ApplyPreview.Skipped("add-entityculling", "Add Entity Culling (alpha build)", ApplyPreview.Reason.DOWNLOAD_FAILED,
								"Modrinth marks Entity Culling and Sodium as incompatible, and both would be installed"),
						new ApplyPreview.Skipped("set-dh-3", "Distant Horizons threads: 8 → 4", ApplyPreview.Reason.REFUSED,
								"Cannot set common.multiThreading.numberOfThreads to \"four\": it expects a whole number")),
				false);
	}

	private static PreviewScreen waitForPreview(ClientGameTestContext context, int ticks) {
		context.waitForScreen(PreviewScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen screen && !screen.loading(), ticks);
		context.waitTicks(3);
		return context.computeOnClient(mc -> (PreviewScreen) mc.gui.screen());
	}

	private static void checkLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w.visible).toList();
			for (AbstractWidget w : widgets) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
						name + ": " + describe(w) + " outside " + screen.width + "x" + screen.height);
				// A RowFocus draws no label: its message is what the narrator reads (review-8 UV-2 to UV-4).
				if (!(w instanceof AbstractSelectionList<?>) && !(w instanceof RowFocus)) {
					check(mc.font.width(w.getMessage()) <= w.getWidth() - 4, name + ": label doesn't fit " + describe(w));
				}
			}
			for (int i = 0; i < widgets.size(); i++) {
				for (int j = i + 1; j < widgets.size(); j++) {
					AbstractWidget a = widgets.get(i);
					AbstractWidget b = widgets.get(j);
					boolean overlap = a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
					check(!overlap, name + ": " + describe(a) + " overlaps " + describe(b));
				}
			}
		});
	}

	private static String describe(AbstractWidget w) {
		return "'" + w.getMessage().getString() + "' [" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
	}

	private static @Nullable Button findButton(Screen screen, String key) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast)
				.findFirst()
				.orElse(null);
	}

	private static void press(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = findButton(mc.gui.screen(), key);
			check(button != null, "No button " + key + " on " + mc.gui.screen());
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		});
		context.waitTicks(2);
	}

	// The cursor goes to a corner so no tooltip or hover highlight covers the screenshots.
	private static void resize(ClientGameTestContext context, int width, int height, int guiScale) {
		context.getInput().resizeWindow(width, height);
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
	}

	private static byte @Nullable [] read(Path file) {
		try {
			return Files.exists(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new AssertionError(e);
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
			throw new AssertionError(e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// The stub's report and a canned preview; remembers which items it was asked to preview.
	private static final class CannedController extends ForwardingController {
		private final boolean pending;
		private final ApplyPreview preview;
		private volatile List<String> asked = List.of();

		CannedController(StubController stub, boolean pending, ApplyPreview preview) {
			super(stub);
			this.pending = pending;
			this.preview = preview;
		}

		@Override
		public boolean hasPendingChanges() {
			return pending;
		}

		@Override
		public ApplyPreview preview(List<Recommendation> selected) {
			asked = selected.stream().map(Recommendation::id).toList();
			return preview;
		}

		// The real controller's labels (the game's captions, the rules' settingLabels), as History uses them (SPEC 2b).
		@Override
		public HistoryModel.Labels settingLabels() {
			return RigTuneClient.controller().settingLabels();
		}
	}
}
