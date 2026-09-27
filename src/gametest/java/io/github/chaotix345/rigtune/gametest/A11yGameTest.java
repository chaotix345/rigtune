package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.ui.BenchmarkHistoryScreen;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.JvmScreen;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.PreviewScreen;
import io.github.chaotix345.rigtune.client.ui.ProfilesScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates.TemplateId;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.GcKind;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;

// docs/v0.4/SPEC.md 11 (AC11.1-AC11.3). Per list screen (RigTune, History, Preview, Undo, JVM, Profiles, Stutter): Tab
// from nothing focused reaches the list, then each press lands on the next row's own child, none skipped, and the press
// after the last row leaves the list; the narration collected for each focused row (ScreenNarrationCollector over the
// list's updateWidgetNarration) contains the row's text, and the fixture's identifying strings are all narrated. The arrow
// keys walk the Undo list; Enter and Space select a History entry (the focus stays on its row) and Enter a profile. With
// High Contrast Block Outline on, RigTune's own colours change in the screenshot (AC11.2). The network is off (X1).
public class A11yGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
	// Palette: the label grey and its high-contrast value (RGB).
	private static final int LABEL = 0xA8A8A8;
	private static final int LABEL_HIGH_CONTRAST = 0xE6E6E6;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		RigTuneController real = RigTuneClient.controller();
		context.waitFor(mc -> real.report() != null, 1200);
		Path configDir = FabricLoader.getInstance().getConfigDir();
		boolean outline = context.computeOnClient(mc -> mc.options.highContrastBlockOutline().get());
		boolean network = GameTestNet.set(context, real, false);
		try {
			StubController stub = new StubController(RigTuneClient::hardware);
			A11yController controller = new A11yController(stub, real, configDir);
			resize(context, 854, 480, 2);
			rigTune(context, controller);
			history(context, controller);
			preview(context, controller);
			undo(context, controller);
			jvm(context, controller);
			profiles(context, controller);
			stutter(context, controller);
			standaloneText(context, controller);
			highContrast(context, controller);
			// v0.5 (docs/v0.5/SPEC.md C6, X6; PLAN contracts item 16): one walk per owner, each with the contracts' context
			// record; canned views through CannedViews, cleared by the walk.
			V05TestContext v05 = new V05TestContext(context, stub, real, configDir);
			walkStutterFix(v05);
			walkTryIt(v05);
			walkServerProfiles(v05);
			walkFirstApply(v05);
			walkHowItWorks(v05);
			walkToolsStartup(v05);
			walkBenchmarkScreens(v05);
			walkBatteryOfferRow(v05);
			walkModFilesRowAndNews(v05);
			walkLauncherNotices(v05);
			highContrastRunningGame(v05);
		} finally {
			context.runOnClient(mc -> {
				mc.options.highContrastBlockOutline().set(outline);
				mc.setLastInputType(InputType.MOUSE);
			});
			GameTestNet.set(context, real, network);
			resize(context, 854, 480, 0);
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		}
		context.waitForScreen(TitleScreen.class);
		RigTune.LOGGER.info("A11yGameTest: every list row is reached by Tab and narrated; high contrast recolours RigTune's own colours");
	}

	// --- the screens

	private static void rigTune(ClientGameTestContext context, A11yController controller) {
		openRigTune(context, controller);
		String rows = walk(context, "rigtune", List.of("Only 2 GB of RAM allocated", "Update your GPU driver", "Disable Indium", "Add Lithium"));
		RigTune.LOGGER.info("A11yGameTest: the RigTune rows narrate:\n{}", rows);
		// A focused category heading (it has no checkbox) at the 3 standard sizes: the frame is its only focus mark.
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			focusRow(context, 0);
			context.takeScreenshot("a11y-rigtune-focus-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, 854, 480, 2);
	}

	private static void history(ClientGameTestContext context, A11yController controller) {
		openHistory(context, controller);
		// After keyboard use a (re)built screen focuses its first button as before, not the first row (review M3).
		check(context.computeOnClient(A11yGameTest::rowIndex) == -1 && context.computeOnClient(mc -> mc.gui.screen().getFocused() != null),
				"history: the initial focus is a button, not a row");
		walk(context, "history", List.of("Apply", "Render distance", "Lithium", "RigTune 0.3.0"));

		// Enter on the older entry (row 3) selects it, and after the rebuild the focus is on its row again.
		focusRow(context, 3);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen h && "e1".equals(h.selected()) && rows(mc) == 3, 40);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> focusedRectangle(mc).equals(((HistoryScreen) mc.gui.screen()).entryRow("e1"))),
				"history: the focus is back on the selected entry's row");
		String selected = context.computeOnClient(A11yGameTest::narration);
		check(selected.contains("Selected"), "history: the open entry says it's selected: " + selected);
		context.takeScreenshot("a11y-history-enter-854x480-scale2");
		// Up to the newer entry, and Space selects it.
		context.getInput().pressKey(InputConstants.KEY_UP);
		context.waitTicks(1);
		check(context.computeOnClient(A11yGameTest::rowIndex) == 0, "history: Up moved to the newer entry");
		context.getInput().pressKey(InputConstants.KEY_SPACE);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen h && "e2".equals(h.selected()) && rows(mc) == 4, 40);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> focusedRectangle(mc).equals(((HistoryScreen) mc.gui.screen()).entryRow("e2"))),
				"history: Space selected the newer entry and kept the focus on it");
	}

	private static void preview(ClientGameTestContext context, A11yController controller) {
		List<Recommendation> selected = controller.stub.report().recommendations().stream().filter(Recommendation::appliable).toList();
		context.runOnClient(mc -> mc.gui.setScreen(new PreviewScreen(new TitleScreen(), controller, selected)));
		context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen p && !p.loading() && p.preview() != null && rows(mc) >= 4, 200);
		context.waitTicks(2);
		walk(context, "preview", List.of("options.txt", "sodium-options.json"));
		focusRow(context, 1);
		context.takeScreenshot("a11y-preview-focus-854x480-scale2");
	}

	private static void undo(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new UndoScreen(new TitleScreen(), controller, false)));
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen u && u.plan() != null && rows(mc) == 6, 200);
		context.waitTicks(2);
		walk(context, "undo", List.of("Render distance: 12 → 16", "Lithium", "Simulation distance: 8 → 12", "Changed again by a later apply"));
		// The arrow keys walk the rows too: Down to the last row, then Up once.
		focusRow(context, 0);
		int rows = context.computeOnClient(A11yGameTest::rows);
		for (int i = 1; i < rows; i++) {
			context.getInput().pressKey(InputConstants.KEY_DOWN);
			context.waitTicks(1);
			int at = context.computeOnClient(A11yGameTest::rowIndex);
			check(at == i, "undo: Down " + i + " focused row " + at);
		}
		context.getInput().pressKey(InputConstants.KEY_UP);
		context.waitTicks(1);
		check(context.computeOnClient(A11yGameTest::rowIndex) == rows - 2, "undo: Up moved back one row");
		context.takeScreenshot("a11y-undo-arrows-854x480-scale2");
		int[] over = context.computeOnClient(mc -> {
			ScreenRectangle first = ((AbstractWidget) ((ContainerEventHandler) list(mc).children().get(0)).children().get(0)).getRectangle();
			int scale = (int) mc.getWindow().getGuiScale();
			return new int[]{(first.left() + 20) * scale, (first.top() + first.height() / 2) * scale};
		});
		context.getInput().setCursorPos(over[0], over[1]);
		context.waitTicks(3);
		String[] hovered = context.computeOnClient(mc -> new String[]{narration(mc), leafText(mc)});
		check(hovered[0].contains(hovered[1]), "undo: with the cursor on another row the focused row still narrates (" + hovered[1] + "): " + hovered[0]);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(1);
	}

	private static void jvm(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new JvmScreen(new TitleScreen(), controller)));
		context.waitFor(mc -> mc.gui.screen() instanceof JvmScreen && rows(mc) >= 4, 400);
		context.waitTicks(2);
		walk(context, "jvm", List.of());
	}

	private static void profiles(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new ProfilesScreen(new TitleScreen(), controller)));
		context.waitFor(mc -> mc.gui.screen() instanceof ProfilesScreen && rows(mc) == 3, 200);
		context.waitTicks(2);
		walk(context, "profiles", List.of("Battery", "Max FPS", "My settings"));
		focusRow(context, 2);
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
		check("p-a11y".equals(context.computeOnClient(mc -> ((ProfilesScreen) mc.gui.screen()).selected())), "profiles: Enter selected the profile");
		check(context.computeOnClient(A11yGameTest::rowIndex) == 2, "profiles: the focus stayed on its row");
		String selected = context.computeOnClient(A11yGameTest::narration);
		check(selected.contains("My settings") && selected.contains("Selected"), "profiles: the chosen profile says it's selected: " + selected);
		context.takeScreenshot("a11y-profiles-enter-854x480-scale2");
	}

	private static void stutter(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new StutterScreen(new TitleScreen(), controller)));
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen && rows(mc) >= 10, 200);
		context.waitTicks(2);
		walk(context, "stutter", List.of());
		focusRow(context, 3);
		context.takeScreenshot("a11y-stutter-focus-854x480-scale2");
		// A live session brings a new report every few seconds and the screen rebuilds: the focus stays on row 3 (review M1).
		StutterView before = context.computeOnClient(mc -> ((StutterScreen) mc.gui.screen()).shownView());
		controller.refreshStutter();
		context.waitFor(mc -> ((StutterScreen) mc.gui.screen()).shownView() != before, 40);
		context.waitTicks(2);
		check(context.computeOnClient(A11yGameTest::rowIndex) == 3, "stutter: a refresh kept the focused row");
	}

	// review-8 UV-2 to UV-4: text that isn't a list row is a Tab stop the narrator reads: the notice line (with its detail;
	// inline and behind the narrow screens' "..." button) and NoticeScreen's rows, every line of Benchmark history, and the
	// Tools startup line with the notes under it.
	private static void standaloneText(ClientGameTestContext context, A11yController controller) {
		String message = "The server limits view distance to 6 chunks";
		controller.notices = List.of(new Notice("a11y-notice", NoticePriority.SERVER_LIMIT, Text.literal(message),
				Text.literal("You set 12; the server sends 6, so 6 is what you see."), List.of(new NoticeAction("open", Text.literal("Open"))), true));
		try {
			for (int[] size : new int[][]{{854, 480, 2}, {640, 480, 2}}) {
				resize(context, size[0], size[1], size[2]);
				openRigTune(context, controller);
				String said = tabUntilNarrates(context, "notice line " + size[0] + "x" + size[1], message);
				check(said.contains("the server sends 6"), "the notice's detail is narrated with it: " + said);
				context.takeScreenshot("a11y-notice-focus-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			}
			context.runOnClient(mc -> mc.gui.setScreen(new NoticeScreen(new TitleScreen(), controller)));
			context.waitForScreen(NoticeScreen.class);
			tabUntilNarrates(context, "notice screen", message);
		} finally {
			controller.notices = List.of();
			resize(context, 854, 480, 2);
		}

		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkHistoryScreen(new TitleScreen(), controller)));
		context.waitForScreen(BenchmarkHistoryScreen.class);
		context.waitTicks(2);
		List<String> lines = context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).shownLines().stream()
				.map(line -> Texts.component(line.text()).getString()).toList());
		String history = tabAll(context);
		check(!lines.isEmpty(), "benchmark history shows lines");
		for (String line : lines) {
			check(history.contains(line), "benchmark history: \"" + line + "\" is narrated: " + history);
		}
		context.takeScreenshot("a11y-benchmark-history-focus-854x480-scale2");

		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), controller)));
		context.waitForScreen(ToolsScreen.class);
		context.waitTicks(2);
		Component startup = context.computeOnClient(mc -> ((ToolsScreen) mc.gui.screen()).startupLine());
		check(startup != null, "the Tools screen shows the startup line (FootprintGameTest recorded this launch)");
		String tools = tabAll(context);
		check(tools.contains(startup.getString()), "tools: the startup line is narrated: " + tools);
		String advice = Component.translatable("rigtune.startup.advice").getString();
		check(tools.contains(advice.substring(0, Math.min(40, advice.length()))), "tools: the startup advice is narrated: " + tools);
		RigTune.LOGGER.info("A11yGameTest: the notice line, NoticeScreen, Benchmark history's {} lines and the Tools startup line are narrated", lines.size());
	}

	// Tab (at most 40 presses, from nothing focused) until the focused widget narrates `text`; returns what it narrates.
	private static String tabUntilNarrates(ClientGameTestContext context, String name, String text) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.screen().clearFocus());
		for (int i = 0; i < 40; i++) {
			tab(context);
			String said = context.computeOnClient(A11yGameTest::focusedNarration);
			if (said.contains(text)) {
				return said;
			}
		}
		throw new AssertionError(name + ": Tab never reached a stop that narrates \"" + text + "\"");
	}

	// Tab once round every stop (from nothing focused, until the first comes back), joining what each narrates.
	private static String tabAll(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.screen().clearFocus());
		StringBuilder out = new StringBuilder();
		Object first = null;
		for (int i = 0; i < 60; i++) {
			tab(context);
			Object leaf = context.computeOnClient(mc -> {
				ComponentPath path = mc.gui.screen().getCurrentFocusPath();
				return path == null ? null : path.leafComponent();
			});
			if (leaf == null || leaf == first) {
				break;
			}
			if (first == null) {
				first = leaf;
			}
			out.append(context.computeOnClient(A11yGameTest::focusedNarration)).append('\n');
		}
		return out.toString();
	}

	// What the focused widget narrates (ScreenNarrationCollector over its own narration), or "".
	private static String focusedNarration(Minecraft mc) {
		ComponentPath path = mc.gui.screen().getCurrentFocusPath();
		if (path == null || !(path.leafComponent() instanceof AbstractWidget widget)) {
			return "";
		}
		ScreenNarrationCollector collector = new ScreenNarrationCollector();
		//? if >=26.3 {
		/*collector.update(widget::updateNarration, net.minecraft.client.gui.narration.NarrationTrigger.KEYBOARD);
		*///?} else
		collector.update(widget::updateNarration);
		return collector.collectNarrationText(false);
	}

	// AC11.2: the stub's RigTune screen with High Contrast Block Outline off, then on (the option that reloads no resource
	// pack; Palette reads High Contrast as well). Off draws RigTune's label grey and never its high-contrast value; on, the
	// label pixels are recoloured (vanilla's button sprites keep some pixels of that grey in both).
	private static void highContrast(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.options.highContrastBlockOutline().set(false));
		openRigTune(context, controller);
		int[] off = count(context.takeScreenshot("a11y-hc-off-854x480-scale2"), LABEL, LABEL_HIGH_CONTRAST);
		context.runOnClient(mc -> mc.options.highContrastBlockOutline().set(true));
		openRigTune(context, controller);
		int[] on = count(context.takeScreenshot("a11y-hc-on-854x480-scale2"), LABEL, LABEL_HIGH_CONTRAST);
		RigTune.LOGGER.info("A11yGameTest: label grey / high-contrast pixels: off {} / {}, on {} / {}", off[0], off[1], on[0], on[1]);
		check(off[0] >= 200 && off[1] == 0, "high contrast off: the label grey is drawn, its high-contrast value isn't: " + off[0] + " / " + off[1]);
		check(on[1] >= 200 && off[0] - on[0] >= on[1] * 9 / 10, "high contrast on: the label pixels are recoloured: off " + off[0] + ", on " + on[0]
				+ " / " + on[1]);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			context.takeScreenshot("a11y-hc-rigtune-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, 854, 480, 2);
		openHistory(context, controller);
		focusRow(context, 1);
		context.takeScreenshot("a11y-hc-history-focus-854x480-scale2");
		context.runOnClient(mc -> mc.options.highContrastBlockOutline().set(false));
	}

	private static void openRigTune(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), controller)));
		context.waitFor(mc -> mc.gui.screen() instanceof RigTuneScreen && rows(mc) >= 10, 200);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(3);
	}

	private static void openHistory(ClientGameTestContext context, A11yController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new HistoryScreen(new TitleScreen(), controller)));
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen h && h.view() != null && !h.loading() && rows(mc) == 4, 200);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(2);
	}

	// --- focus and narration

	// Tab from nothing focused until the list's first row has the focus, then one press per row: each lands on the next
	// row's child. Returns every row's narration; each contains its row's text, and together they name every expected string.
	private static String walk(ClientGameTestContext context, String name, List<String> expected) {
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(1);
		context.runOnClient(mc -> mc.gui.screen().clearFocus());
		int rows = context.computeOnClient(A11yGameTest::rows);
		check(rows >= 2, name + ": rows listed: " + rows);
		int presses = 0;
		while (context.computeOnClient(A11yGameTest::rowIndex) != 0) {
			check(presses++ < 40, name + ": Tab never reached the first row (at " + context.computeOnClient(A11yGameTest::rowIndex) + ")");
			tab(context);
		}
		StringBuilder narrated = new StringBuilder();
		for (int row = 0; row < rows; row++) {
			String[] seen = context.computeOnClient(mc -> new String[]{Integer.toString(rowIndex(mc)), narration(mc), leafText(mc)});
			check(Integer.parseInt(seen[0]) == row, name + ": Tab " + (presses + row) + " focused row " + seen[0] + ", not row " + row);
			check(!seen[2].isEmpty() && seen[1].contains(seen[2]), name + ": row " + row + " narrates its text (" + seen[2] + "): " + seen[1]);
			narrated.append(seen[1]).append('\n');
			tab(context);
		}
		check(context.computeOnClient(A11yGameTest::rowIndex) == -1, name + ": the Tab after the last row leaves the list");
		String all = narrated.toString();
		for (String text : expected) {
			check(all.contains(text), name + ": \"" + text + "\" is narrated: " + all);
		}
		RigTune.LOGGER.info("A11yGameTest: {}: Tab reached all {} rows, the first after {} presses", name, rows, presses);
		return all;
	}

	private static void tab(ClientGameTestContext context) {
		context.getInput().pressKey(InputConstants.KEY_TAB);
		context.waitTicks(1);
	}

	// Focuses row i as Tab would (from the top), for the screenshots and the key presses.
	private static void focusRow(ClientGameTestContext context, int i) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.screen().clearFocus());
		int presses = 0;
		while (context.computeOnClient(A11yGameTest::rowIndex) != i) {
			check(presses++ < 80, "row " + i + " was never focused");
			tab(context);
		}
		context.waitTicks(2);
	}

	private static ContainerObjectSelectionList<?> list(Minecraft mc) {
		return (ContainerObjectSelectionList<?>) Screens.getWidgets(mc.gui.screen()).stream()
				.filter(w -> w instanceof ContainerObjectSelectionList<?>).findFirst()
				.orElseThrow(() -> new AssertionError("no list on " + mc.gui.screen()));
	}

	private static int rows(Minecraft mc) {
		return list(mc).children().size();
	}

	// The row whose own child has the focus, or -1 (the focus is elsewhere or nowhere).
	private static int rowIndex(Minecraft mc) {
		ComponentPath path = mc.gui.screen().getCurrentFocusPath();
		if (path == null) {
			return -1;
		}
		GuiEventListener leaf = path.leafComponent();
		List<?> entries = list(mc).children();
		for (int i = 0; i < entries.size(); i++) {
			if (((ContainerEventHandler) entries.get(i)).children().contains(leaf)) {
				return i;
			}
		}
		return -1;
	}

	private static String leafText(Minecraft mc) {
		ComponentPath path = mc.gui.screen().getCurrentFocusPath();
		return path != null && path.leafComponent() instanceof AbstractWidget w ? w.getMessage().getString() : "";
	}

	private static ScreenRectangle focusedRectangle(Minecraft mc) {
		ComponentPath path = mc.gui.screen().getCurrentFocusPath();
		check(path != null, "something has the focus");
		return path.leafComponent().getRectangle();
	}

	// What vanilla's narrator says for the list now: the focused row (the cursor is off the list).
	private static String narration(Minecraft mc) {
		ContainerObjectSelectionList<?> list = list(mc);
		ScreenNarrationCollector collector = new ScreenNarrationCollector();
		//? if >=26.3 {
		/*collector.update(list::updateWidgetNarration, net.minecraft.client.gui.narration.NarrationTrigger.KEYBOARD);
		*///?} else
		collector.update(list::updateWidgetNarration);
		return collector.collectNarrationText(false);
	}

	// --- v0.5 walks (docs/v0.5/SPEC.md C6): one method per owner; an owner edits only its own method's body and adds its
	// own private helpers right below it. Each walk leaves the screen, the size and CannedViews as it found them.

	// ---- WS-S2 (C20, AC5.11): StutterScreen's fix rows, the ButtonRow and the "Your stutter fix" block.

	private static void walkStutterFix(V05TestContext v05) {
	}

	// ---- WS-T (C09, AC6.13): TryItScreen and the plain Preview's footer.

	private static void walkTryIt(V05TestContext v05) {
	}

	// ---- WS-P2 (C16, AC7.11): ServerProfilesScreen with a canned view.

	private static void walkServerProfiles(V05TestContext v05) {
	}

	// ---- WS-F (C02, AC8.12): FirstApplyScreen.

	private static void walkFirstApply(V05TestContext v05) {
	}

	// ---- WS-F (C02, AC8.12): HowItWorksScreen.

	private static void walkHowItWorks(V05TestContext v05) {
	}

	// ---- WS-W, then WS-W2 (2L, C18 AC9.5): ToolsScreen's startup lines.

	private static void walkToolsStartup(V05TestContext v05) {
	}

	// ---- WS-B (L3, AC2A.1-AC2A.2): BenchmarkResultScreen and BenchmarkHistoryScreen.

	// A seeded Tune result (5 distances: 3 pass, 1 fails, 1 couldn't be measured) over seeded comparable runs, then Benchmark
	// history over them: Tab reaches every status line, each table row in order and the chart's textual equivalent, each
	// narrates its text; every value of the painted table is in the screen's text (AC2A.2: nothing lost against the WS-K
	// merge's screenshots); X12's layout at the 3 sizes (and 854x480@3, where the table scrolls); high contrast.
	private static void walkBenchmarkScreens(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		Path file = io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore.file();
		byte[] saved = benchRead(file);
		net.minecraft.client.gui.screens.Screen before = context.computeOnClient(mc -> mc.gui.screen());
		boolean outline = context.computeOnClient(mc -> mc.options.highContrastBlockOutline().get());
		try {
			io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Current now = context.computeOnClient(
					io.github.chaotix345.rigtune.client.benchmark.BenchmarkConditions::current);
			io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord latest = benchSeed(file, now);
			io.github.chaotix345.rigtune.client.benchmark.BenchmarkController.Outcome outcome = benchOutcome(now, latest);
			int rd = now.renderDistance();
			List<String> cells = List.of(rd + " | 704 | 440 | ✔", (rd + 2) + " | 650 | 400 | ✔", (rd + 3) + " | 620 | 390 | ✔", (rd + 4) + " | 600 | 380 | ✘",
					(rd + 6) + " | 500 | 300 | ?");
			for (int[] size : V05TestContext.SIZES) {
				v05.resize(size[0], size[1], size[2]);
				String where = size[0] + "x" + size[1] + "@" + size[2];
				benchOpenResult(context, outcome);
				benchLayout(context, "bench result " + where);
				List<String> text = context.computeOnClient(mc -> ((io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen) mc.gui.screen()).textContent());
				for (String row : cells) {
					check(text.contains(row), "bench result " + where + ": the table row \"" + row + "\" is shown: " + text);
				}
				for (String header : List.of("rigtune.benchmark.col.rd", "rigtune.benchmark.col.avg", "rigtune.benchmark.col.low", "rigtune.benchmark.col.ok")) {
					check(text.contains(Component.translatable(header).getString()), "bench result " + where + ": the header " + header + ": " + text);
				}
				String all = tabAll(context);
				check(all.contains("Render distance " + (rd + 3) + ": average 620 FPS, 1% low 390 FPS, P99 3.6 ms, meets the target") && all.contains("Suggested"),
						"bench result " + where + ": the suggested row narrates its numbers: " + all);
				check(all.contains("Render distance " + (rd + 6) + ": average 500 FPS, 1% low 300 FPS") && all.contains("not measured"),
						"bench result " + where + ": the unmeasured row says so: " + all);
				check(all.contains("comparable runs from") && all.contains("Your usual: 543 FPS"), "bench result " + where + ": the chart's summary: " + all);
				check(all.contains("Result: avg 704 FPS"), "bench result " + where + ": the status lines are stops: " + all);
				focusRow(context, 2);
				context.takeScreenshot("a11y-bench-result-row-focus-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			}
			v05.resize(854, 480, 2);
			benchOpenResult(context, outcome);
			walk(context, "bench result", List.of("meets the target", "misses the target", "not measured", "Suggested"));
			// 854x480@3: the table scrolls; the last row is reached and shown.
			v05.resize(V05TestContext.SCROLLING[0], V05TestContext.SCROLLING[1], V05TestContext.SCROLLING[2]);
			benchOpenResult(context, outcome);
			benchLayout(context, "bench result 854x480@3");
			focusRow(context, 4);
			context.takeScreenshot("a11y-bench-result-scrolled-854x480-scale3");
			v05.resize(854, 480, 2);
			context.runOnClient(mc -> mc.options.highContrastBlockOutline().set(true));
			benchOpenResult(context, outcome);
			focusRow(context, 2);
			context.takeScreenshot("a11y-hc-bench-result-854x480-scale2");
			context.runOnClient(mc -> mc.options.highContrastBlockOutline().set(outline));

			for (int[] size : V05TestContext.SIZES) {
				v05.resize(size[0], size[1], size[2]);
				String where = size[0] + "x" + size[1] + "@" + size[2];
				context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkHistoryScreen(new TitleScreen(), v05.real())));
				context.waitForScreen(BenchmarkHistoryScreen.class);
				context.waitTicks(2);
				benchLayout(context, "bench history " + where);
				Component chart = context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).chartSummary());
				check(chart != null && chart.getString().contains("5 comparable runs from") && chart.getString().contains("540, 545, 538, 550, 440"),
						"bench history " + where + ": the chart's textual equivalent: " + chart);
				String all = tabAll(context);
				check(all.contains(chart.getString()), "bench history " + where + ": the chart's stop narrates it: " + all);
				check(all.contains("1% lows 19% below your usual 543 FPS"), "bench history " + where + ": the regression line: " + all);
				context.takeScreenshot("a11y-bench-history-" + size[0] + "x" + size[1] + "-scale" + size[2]);
				tabUntilNarrates(context, "bench history chart " + where, "comparable runs from");
				context.takeScreenshot("a11y-bench-history-chart-focus-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			}
			v05.resize(854, 480, 2);
			context.runOnClient(mc -> mc.options.highContrastBlockOutline().set(true));
			context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkHistoryScreen(new TitleScreen(), v05.real())));
			context.waitForScreen(BenchmarkHistoryScreen.class);
			tabUntilNarrates(context, "bench history chart (high contrast)", "comparable runs from");
			context.takeScreenshot("a11y-hc-bench-history-854x480-scale2");
			RigTune.LOGGER.info("A11yGameTest: the benchmark result's status lines, 5 table rows and chart summary, and Benchmark history's chart summary "
					+ "are Tab stops that narrate their text");
		} finally {
			benchRestore(file, saved);
			context.runOnClient(mc -> {
				mc.options.highContrastBlockOutline().set(outline);
				mc.gui.setScreen(before);
			});
			v05.resize(854, 480, 2);
		}
	}

	private static void benchOpenResult(ClientGameTestContext context, io.github.chaotix345.rigtune.client.benchmark.BenchmarkController.Outcome outcome) {
		context.runOnClient(mc -> mc.gui.setScreen(new io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen(new TitleScreen(), outcome)));
		context.waitForScreen(io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen.class);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(2);
	}

	// Under the game's current conditions: 4 comparable runs (1 % lows 540, 545, 538, 550: usual 542.5) and the latest at
	// 440, 19 % below it (the result screen's run). Returns the latest.
	private static io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord benchSeed(Path file,
			io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Current now) {
		io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory history = io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory.empty();
		double[] lows = {540, 545, 538, 550, 440};
		io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord last = null;
		for (int i = 0; i < lows.length; i++) {
			java.util.Map<String, io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.KnobResult> knobs = new java.util.LinkedHashMap<>();
			knobs.put(io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.RENDER_DISTANCE,
					new io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.KnobResult(now.renderDistance(), now.renderDistance(), null, null, null));
			knobs.put(io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.SIMULATION_DISTANCE,
					new io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.KnobResult(now.simulationDistance(), now.simulationDistance(), null, null, null));
			io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.Context c = new io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.Context(
					now.dhRendering(), now.shaders(), now.shaderPack(), now.width(), now.height(), now.fullscreen(),
					io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.Context.PROTOCOL).withModSet(now.modSetHash(), null);
			last = new io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord(i == 4 ? "a11y-bench-latest" : "a11y-bench-" + i,
					"2026-09-2" + i + "T10:00:00Z", "0.5.0", now.mcVersion(), "TUNE", "CURRENT", io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.SINGLE,
					null, 60, true, knobs, new io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord.Result(lows[i] * 1.6, lows[i], 1000 / lows[i], 2, 0.02),
					java.util.Map.of(), java.util.Map.of(), null, false, c);
			history = history.with(last);
		}
		try {
			history.save(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return last;
	}

	private static io.github.chaotix345.rigtune.client.benchmark.BenchmarkController.Outcome benchOutcome(
			io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Current now, io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord record) {
		int rd = now.renderDistance();
		io.github.chaotix345.rigtune.core.benchmark.Knobs original = new io.github.chaotix345.rigtune.core.benchmark.Knobs(rd, now.simulationDistance(), false, false);
		List<io.github.chaotix345.rigtune.core.benchmark.PlannerResult.Measurement> steps = List.of(
				benchStep(rd, 704, 440, 3.1, true, true), benchStep(rd + 2, 650, 400, 3.4, true, true), benchStep(rd + 3, 620, 390, 3.6, true, true),
				benchStep(rd + 4, 600, 380, 3.8, false, true), benchStep(rd + 6, 500, 300, 4.4, false, false));
		io.github.chaotix345.rigtune.core.benchmark.SessionResult session = new io.github.chaotix345.rigtune.core.benchmark.SessionResult(
				io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Mode.TUNE, original, original.withRenderDistance(rd + 3), 60,
				new io.github.chaotix345.rigtune.core.benchmark.PlannerResult(rd + 3, true, rd + 3, steps, "test"), List.of(),
				new io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath.Aggregate(704, 440, 3.1, 2, 0.02), null, null, java.util.Map.of(), false);
		return new io.github.chaotix345.rigtune.client.benchmark.BenchmarkController.Outcome(new io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest(
				io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Mode.TUNE, io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene.CURRENT,
				null), session, false, record, null, true, false, List.of());
	}

	private static io.github.chaotix345.rigtune.core.benchmark.PlannerResult.Measurement benchStep(int rd, double avg, double low, double p99, boolean passed,
			boolean complete) {
		return new io.github.chaotix345.rigtune.core.benchmark.PlannerResult.Measurement(rd,
				new io.github.chaotix345.rigtune.core.benchmark.FrameStats(900, avg, low, p99, p99 * 3), passed, complete);
	}

	// X12: every visible widget inside the screen, none overlapping.
	private static void benchLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			net.minecraft.client.gui.screens.Screen screen = mc.gui.screen();
			List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w.visible).toList();
			for (AbstractWidget w : widgets) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
						name + ": " + w.getMessage().getString() + " outside " + screen.width + "x" + screen.height + " at " + w.getRectangle());
			}
			for (int i = 0; i < widgets.size(); i++) {
				for (int j = i + 1; j < widgets.size(); j++) {
					AbstractWidget a = widgets.get(i);
					AbstractWidget b = widgets.get(j);
					check(!(a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()),
							name + ": " + a.getMessage().getString() + " " + a.getRectangle() + " overlaps " + b.getMessage().getString() + " " + b.getRectangle());
				}
			}
		});
	}

	private static byte @Nullable [] benchRead(Path file) {
		try {
			return java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void benchRestore(Path file, byte @Nullable [] bytes) {
		try {
			if (bytes == null) {
				java.nio.file.Files.deleteIfExists(file);
			} else {
				java.nio.file.Files.write(file, bytes);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- WS-P (PF-2): RigTuneSettingsScreen's battery-offer row.

	private static void walkBatteryOfferRow(V05TestContext v05) {
	}

	// ---- WS-L1 (4e, 4b): RigTuneSettingsScreen's mod-files row and MOD_FILES_NEWS on NoticeScreen.

	private static void walkModFilesRowAndNews(V05TestContext v05) {
	}

	// ---- WS-L2 (4d, 4g): NoticeScreen with the held-changes and repair notices.

	private static void walkLauncherNotices(V05TestContext v05) {
	}

	// ---- WS-E (3f): high contrast in a running game.

	private static void highContrastRunningGame(V05TestContext v05) {
	}

	// --- helpers

	// How many pixels of each RGB colour the screenshot has.
	private static int[] count(Path png, int... rgbs) {
		try {
			BufferedImage image = ImageIO.read(png.toFile());
			int[] out = new int[rgbs.length];
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int rgb = image.getRGB(x, y) & 0xFFFFFF;
					for (int i = 0; i < rgbs.length; i++) {
						if (rgb == rgbs[i]) {
							out[i]++;
						}
					}
				}
			}
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void resize(ClientGameTestContext context, int width, int height, int guiScale) {
		context.getInput().resizeWindow(width, height);
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// --- fixtures

	// A monitor session with GC and world-save spikes (the same capture as StutterWrittenFixtureTest's), for bar rows.
	private static StutterReport stutterReport() {
		long ms = 1_000_000L;
		long s = 1_000 * ms;
		FrameRing ring = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		StutterRings rings = new StutterRings(0);
		long t0 = 100 * s;
		long now = t0;
		int frame = 0;
		while (now < t0 + 150 * s) {
			long d = frame % 2000 == 999 ? 85 * ms : frame % 3001 == 1500 ? 240 * ms : 8 * ms;
			now += d;
			ring.frame(now, d, now < t0 + 10 * s, 250_000, 900_000, 6 * ms, frame % 2000 == 999 ? 12 : 0);
			if (frame % 2000 == 999) {
				long startMs = (now - d + 10 * ms) / ms - 18;
				rings.gc(now, startMs, startMs + 60, GcKind.classify("G1 Young Generation", "end of minor GC", "G1 Evacuation Pause"), 0);
			}
			frame++;
		}
		rings.event(StutterRings.SAVE_BEGIN, t0 + 60 * s, 0);
		rings.event(StutterRings.SAVE_END, t0 + 61 * s, 0);
		return StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), rings.snapshot(), t0, now, Instant.parse("2026-09-24T19:30:00Z"),
				StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, true, false)).report();
	}

	// The stub's report plus a canned history, undo plan, preview, profiles and stutter session; the real JVM report, read once.
	// The walks' canned world: it forwards to the stub (ForwardingController), answers the few real-backed methods from the
	// game's controller, and the v0.5 views from CannedViews when an owner's walk set them.
	private static final class A11yController extends ForwardingController {
		private final StubController stub;
		private final RigTuneController real;
		private final Path configDir;
		private final HistoryModel.View history;
		private final UndoPlan plan;
		private final List<ProfileView> profiles;
		private final JvmReport jvm;
		private volatile StutterView stutter;
		volatile List<Notice> notices = List.of();

		A11yController(StubController stub, RigTuneController real, Path configDir) {
			super(stub);
			this.stub = stub;
			this.real = real;
			this.configDir = configDir;
			this.jvm = real.jvmReport();
			this.history = new HistoryModel.View(Journal.State.OK, List.of(
					new HistoryModel.Entry("e2", JournalEntry.APPLY, "2026-09-25T10:05:31Z", "0.4.0", "26.2", null, null, true, List.of(
							new HistoryModel.Change(HistoryModel.Row.SETTING, List.of("c1"), JournalChange.APPLIED, "Render distance", "16", "12", null, null,
									null, null),
							new HistoryModel.Change(HistoryModel.Row.ADDED, List.of("c2"), JournalChange.STAGED, null, null, null, "lithium-fabric-0.21.0.jar",
									null, "lithium", null, "Lithium"))),
					new HistoryModel.Entry("e1", JournalEntry.APPLY, "2026-09-24T09:00:00Z", "0.3.0", "26.2", null, null, true, List.of(
							new HistoryModel.Change(HistoryModel.Row.SETTING, List.of("c0"), JournalChange.APPLIED, "Simulation distance", "12", "8", null, null,
									null, null)))));
			this.plan = new UndoPlan(false, "e2", List.of(
					new UndoPlan.Item("Render distance: 12 → 16", UndoPlan.Action.REVERT, null, false),
					new UndoPlan.Item("Lithium: taken off the restart queue", UndoPlan.Action.DISCARD_STAGED, null, true),
					new UndoPlan.Item("Simulation distance: 8 → 12", UndoPlan.Action.SKIP, "Changed again by a later apply", false)));
			this.profiles = List.of(
					new ProfileView(ProfileStore.TEMPLATE_PREFIX + TemplateId.BATTERY.id(), TemplateId.BATTERY.displayName(), ProfileView.TEMPLATE, false),
					new ProfileView(ProfileStore.TEMPLATE_PREFIX + TemplateId.MAX_FPS.id(), TemplateId.MAX_FPS.displayName(), ProfileView.TEMPLATE, true),
					new ProfileView("p-a11y", Text.literal("My settings"), ProfileStore.SOURCE_SAVED, false));
			this.stutter = new StutterView(false, false, false, false, false, stutterReport(), List.of());
		}

		@Override
		public void startBenchmark() {
		}

		@Override
		public void rescan() {
		}

		@Override
		public HistoryModel.@Nullable View history() {
			return history;
		}

		@Override
		public @Nullable UndoPlan undoPlan(boolean all) {
			return plan;
		}

		@Override
		public @Nullable UndoPlan undoPlanFor(String entryId) {
			return plan;
		}

		@Override
		public ApplyPreview preview(List<Recommendation> selected) {
			Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
			return new ApplyPreview(List.of(new ApplyPreview.Setting("set-vanilla.renderDistance", options, "renderDistance", "16", "12")),
					List.of(new ApplyPreview.Setting("set-sodium.performance.use_entity_culling", configDir.resolve("sodium-options.json"),
							"performance.use_entity_culling", "false", "true")),
					List.of(), List.of(), List.of(), true);
		}

		@Override
		public HistoryModel.Labels settingLabels() {
			return real.settingLabels();
		}

		@Override
		public List<ProfileView> profiles() {
			return profiles;
		}

		@Override
		public StutterView stutter() {
			StutterView canned = CannedViews.stutter();
			return canned != null ? canned : stutter;
		}

		@Override
		public TryItView tryIt() {
			TryItView canned = CannedViews.tryIt();
			return canned != null ? canned : super.tryIt();
		}

		@Override
		public ServerProfilesView serverProfiles() {
			ServerProfilesView canned = CannedViews.serverProfiles();
			return canned != null ? canned : super.serverProfiles();
		}

		@Override
		public ModFilesPolicy modFiles() {
			ModFilesPolicy canned = CannedViews.modFiles();
			return canned != null ? canned : super.modFiles();
		}

		@Override
		public boolean firstApplyPending() {
			Boolean canned = CannedViews.firstApplyPending();
			return canned != null ? canned : super.firstApplyPending();
		}

		@Override
		public JvmReport jvmReport() {
			return jvm;
		}

		@Override
		public List<Notice> notices() {
			return notices;
		}

		@Override
		public BenchmarkTrend.View benchmarkTrend(@Nullable String contextKey) {
			return real.benchmarkTrend(contextKey);
		}

		@Override
		public StartupTimes.View startupTimes() {
			return real.startupTimes();
		}

		// A live session's refresh: the same capture, a new report.
		void refreshStutter() {
			stutter = new StutterView(false, false, false, false, false, stutterReport(), List.of());
		}
	}
}
