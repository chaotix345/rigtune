package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.PreviewScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
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
		// Five minutes: the ticked additions are looked up on the live Modrinth API, one after another.
		PreviewScreen screen = waitForPreview(context, 6000);
		ApplyPreview preview = context.computeOnClient(mc -> screen.preview());
		check(preview != null, "the real preview finished");
		List<String> rows = context.computeOnClient(mc -> screen.rowText());
		RigTune.LOGGER.info("PreviewGameTest: real preview ({}): {} now, {} at restart, {} downloads, {} disables, {} not changed:\n{}",
				ticked ? "default ticks" : "every appliable item", preview.now().size(), preview.atRestart().size(), preview.downloads().size(),
				preview.disables().size(), preview.skipped().size(), String.join("\n", rows));
		checkPreviewLayout(context, "real preview");
		context.takeScreenshot("preview-real");
		check(before.equals(snapshot()), "the preview wrote nothing: " + difference(before, snapshot()));
		press(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
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
				if (!(w instanceof AbstractSelectionList<?>)) {
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
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
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
	private static final class CannedController implements RigTuneController {
		private final StubController stub;
		private final boolean pending;
		private final ApplyPreview preview;
		private volatile List<String> asked = List.of();

		CannedController(StubController stub, boolean pending, ApplyPreview preview) {
			this.stub = stub;
			this.pending = pending;
			this.preview = preview;
		}

		@Override
		public @Nullable Report report() {
			return stub.report();
		}

		@Override
		public Goal goal() {
			return stub.goal();
		}

		@Override
		public void setGoal(Goal goal) {
			stub.setGoal(goal);
		}

		@Override
		public Component apply(List<Recommendation> selected) {
			return stub.apply(selected);
		}

		@Override
		public void startBenchmark() {
			stub.startBenchmark();
		}

		@Override
		public void rescan() {
			stub.rescan();
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
