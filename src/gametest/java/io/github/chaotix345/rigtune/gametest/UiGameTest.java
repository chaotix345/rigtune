package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.compat.ModMenuIntegration;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneSettingsScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.report.ModrinthOffAdvice;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

// WS-E (docs/v0.2/SPEC.md items 8 and 10): the RigTune screen's buttons, the settings screen, the network switches'
// header and report effects, Mod Menu, and Copy report. Every screen is checked for fit at the three reference sizes.
public class UiGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{854, 480, 2}, {1280, 720, 3}, {1280, 720, 2}};
	private static final Pattern DRIVE_PATH = Pattern.compile("(?<![A-Za-z])[A-Za-z]:[\\\\/]");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		Path configDir = FabricLoader.getInstance().getConfigDir();
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		context.waitFor(mc -> ClientSettings.load(configDir).privacyNoticeShown, 200);

		RigTuneController real = RigTuneClient.controller();
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> RigTuneClient.open(mc.gui.screen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		atEverySize(context, "ui-main");

		checkCopyReport(context, real);
		checkSubScreens(context);

		StubController stub = new StubController(RigTuneClient::hardware);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), new PendingStub(stub))));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		atEverySize(context, "ui-stub-pending");
		check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.screen.discard") != null), "Discard pending shown with pending changes");

		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		checkSettings(context, configDir, real);
		checkModMenu(context);

		resize(context, 854, 480, 0);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	private static void checkCopyReport(ClientGameTestContext context, RigTuneController controller) {
		String before = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
		try {
			// Press, read the clipboard and build the report again in one client task, so a report rebuilt in
			// between can't make them differ.
			String[] result = context.computeOnClient(mc -> {
				Button button = findButton(mc.gui.screen(), "rigtune.screen.copy_report");
				check(button != null && button.active, "Copy report is there and active");
				button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
				return new String[]{mc.keyboardHandler.getClipboard(), controller.shareReport()};
			});
			String raw = result[0];
			String copied = raw.replace("\r\n", "\n");
			String expected = result[1];
			RigTune.LOGGER.info("UiGameTest: copied report, {} characters (CRLF from the clipboard: {}):\n{}", copied.length(), raw.contains("\r\n"), copied);
			check(copied.equals(expected), "clipboard holds the share report" + firstDifference(copied, expected));
			check(copied.startsWith("**RigTune "), "report header: " + copied);
			check(copied.contains("· Minecraft 26."), "MC version: " + copied);
			check(copied.length() <= 2000, "at most 2000 characters: " + copied.length());
			String gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
			String home = System.getProperty("user.home");
			check(!copied.contains(gameDir) && !copied.contains(home) && !DRIVE_PATH.matcher(copied).find(), "no paths: " + copied);
			context.waitTicks(2);
			context.takeScreenshot("ui-main-copied");
		} finally {
			context.runOnClient(mc -> mc.keyboardHandler.setClipboard(before));
		}
	}

	private static String firstDifference(String actual, String expected) {
		int i = 0;
		while (i < actual.length() && i < expected.length() && actual.charAt(i) == expected.charAt(i)) {
			i++;
		}
		return String.format(": lengths %d/%d, first difference at %d: clipboard %s vs report %s", actual.length(), expected.length(), i,
				codes(actual, i), codes(expected, i));
	}

	private static String codes(String text, int from) {
		StringBuilder out = new StringBuilder("[");
		for (int i = from; i < Math.min(text.length(), from + 6); i++) {
			out.append(String.format("U+%04X ", (int) text.charAt(i)));
		}
		return out.append("] after \"").append(text, Math.max(0, from - 20), Math.min(from, text.length())).append('"').toString();
	}

	private static void checkSubScreens(ClientGameTestContext context) {
		checkUndoButton(context, "rigtune.screen.undo_last", false, "ui-undo-last-from-button");
		checkUndoButton(context, "rigtune.screen.undo_all", true, "ui-undo-all-from-button");

		pressByKey(context, "rigtune.screen.benchmark_menu");
		context.waitForScreen(BenchmarkMenuScreen.class);
		context.waitTicks(2);
		context.takeScreenshot("ui-benchmark-menu-from-button");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);
	}

	// The button opens WS-B's confirmation screen for the right scope; nothing is undone here (Cancel/close only).
	private static void checkUndoButton(ClientGameTestContext context, String key, boolean all, String screenshot) {
		pressByKey(context, key);
		context.waitForScreen(UndoScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen undo && undo.plan() != null, 400);
		check(context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).plan().all()) == all, key + " opens the undo screen with all=" + all);
		context.waitTicks(2);
		context.takeScreenshot(screenshot);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);
	}

	private static void checkSettings(ClientGameTestContext context, Path configDir, RigTuneController controller) {
		pressByKey(context, "rigtune.screen.settings");
		context.waitForScreen(RigTuneSettingsScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.settings.open_rigtune") == null),
				"no Open RigTune button when opened from RigTune");
		atEverySize(context, "ui-settings");

		Goal goalBefore = controller.goal();
		try {
			// Network off: saved, the finer switches grey out, and the report and header follow.
			cycle(context, "rigtune.settings.network");
			waitForSaved(context, configDir, s -> !s.networkEnabled, "networkEnabled=false saved to settings.json");
			check(context.computeOnClient(mc -> !findCycle(mc.gui.screen(), "rigtune.settings.remote_rules").active
					&& !findCycle(mc.gui.screen(), "rigtune.settings.modrinth").active), "finer switches inactive while the network is off");
			context.takeScreenshot("ui-settings-network-off");
			pressByKey(context, "gui.done");
			context.waitForScreen(RigTuneScreen.class);
			waitForSettledReport(context, controller);
			checkHeader(context, "rigtune.screen.header.network_off");
			checkModrinthOff(context, controller, ModrinthOffAdvice.NETWORK_ADD_NOTE);
			context.takeScreenshot("ui-main-network-off");

			// Network on, Modrinth off.
			pressByKey(context, "rigtune.screen.settings");
			context.waitForScreen(RigTuneSettingsScreen.class);
			cycle(context, "rigtune.settings.network");
			cycle(context, "rigtune.settings.modrinth");
			waitForSaved(context, configDir, s -> s.networkEnabled && !s.modrinth, "network on, Modrinth off saved");
			pressByKey(context, "gui.done");
			context.waitForScreen(RigTuneScreen.class);
			waitForSettledReport(context, controller);
			checkHeader(context, "rigtune.screen.header.modrinth_off");
			checkModrinthOff(context, controller, ModrinthOffAdvice.ADD_NOTE);
			context.takeScreenshot("ui-main-modrinth-off");

			// The other settings save too.
			pressByKey(context, "rigtune.screen.settings");
			context.waitForScreen(RigTuneSettingsScreen.class);
			cycle(context, "rigtune.settings.modrinth");
			cycle(context, "rigtune.settings.startup_toast");
			cycle(context, "rigtune.settings.scene");
			cycle(context, "rigtune.settings.goal");
			waitForSaved(context, configDir, s -> s.modrinth && !s.startupToast && "BENCHMARK_WORLD".equals(s.benchmarkScene),
					"Modrinth on, startup toast off and the benchmark world saved");
			check(controller.goal() != goalBefore, "goal changed through the controller");
		} finally {
			restoreDefaults(context, configDir, controller, goalBefore);
		}

		pressByKey(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
		waitForSettledReport(context, controller);
		check(!context.computeOnClient(mc -> headerHas(mc.gui.screen(), "rigtune.screen.header.network_off")
				|| headerHas(mc.gui.screen(), "rigtune.screen.header.modrinth_off")), "no network note with everything on");
	}

	// Later game-test classes expect the defaults, whether or not a check above failed.
	private static void restoreDefaults(ClientGameTestContext context, Path configDir, RigTuneController controller, Goal goal) {
		context.runOnClient(mc -> {
			ClientSettings settings = ClientSettings.shared(configDir);
			settings.networkEnabled = true;
			settings.remoteRules = true;
			settings.modrinth = true;
			settings.startupToast = true;
			settings.benchmarkScene = "CURRENT";
			settings.save(configDir);
			controller.setGoal(goal);
			controller.settingsChanged();
		});
		ClientSettings saved = ClientSettings.load(configDir);
		check(saved.networkEnabled && saved.remoteRules && saved.modrinth && saved.startupToast && "CURRENT".equals(saved.benchmarkScene), "defaults restored");
	}

	private static void waitForSaved(ClientGameTestContext context, Path configDir, Predicate<ClientSettings> saved, String what) {
		context.waitFor(mc -> saved.test(ClientSettings.load(configDir)), 100);
		RigTune.LOGGER.info("UiGameTest: {}", what);
	}

	private static void checkModMenu(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			Screen screen = new ModMenuIntegration().getModConfigScreenFactory().create(new TitleScreen());
			check(screen instanceof RigTuneSettingsScreen, "Mod Menu opens the settings: " + screen);
			mc.gui.setScreen(screen);
		});
		context.waitForScreen(RigTuneSettingsScreen.class);
		context.waitTicks(2);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkLayout(context, "modmenu-settings " + size[0] + "x" + size[1] + "@" + size[2]);
		}
		context.takeScreenshot("ui-modmenu-settings");
		pressByKey(context, "rigtune.settings.open_rigtune");
		context.waitForScreen(RigTuneScreen.class);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(TitleScreen.class);
	}

	// A report built after the switch changed: the rescan and the online fetch both finish, and the result stays put.
	private static void waitForSettledReport(ClientGameTestContext context, RigTuneController controller) {
		context.waitFor(mc -> controller.report() != null, 1200);
		context.waitTicks(40);
		context.waitFor(mc -> controller.report() != null, 1200);
		context.waitTicks(3);
	}

	private static void checkHeader(ClientGameTestContext context, String key) {
		check(context.computeOnClient(mc -> headerHas(mc.gui.screen(), key)), "header shows " + key);
	}

	private static boolean headerHas(Screen screen, String key) {
		return screen instanceof RigTuneScreen rigtune && rigtune.headerLines().stream()
				.anyMatch(line -> line.getContents() instanceof TranslatableContents t && t.getKey().equals(key));
	}

	private static void checkModrinthOff(ClientGameTestContext context, RigTuneController controller, String note) {
		Report report = context.computeOnClient(mc -> controller.report());
		check(!report.online(), "report is offline");
		List<Recommendation> adds = report.recommendations().stream().filter(r -> r.category() == Category.ADD_MOD).toList();
		if (adds.isEmpty()) {
			RigTune.LOGGER.info("UiGameTest: no install suggestions for this instance; the advice text isn't checked");
		}
		check(adds.stream().allMatch(r -> !r.appliable() && r.reason().endsWith(note)), "installs became advice: " + adds);
		check(report.recommendations().stream().noneMatch(r -> r.action() instanceof Action.AddMod || r.action() instanceof Action.UpdateMod),
				"nothing left to download: " + report.recommendations());
	}

	private static void atEverySize(ClientGameTestContext context, String name) {
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkLayout(context, name + " " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot(name + "-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
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

	// Every visible widget inside the screen, no two overlapping, and every button label readable without scrolling.
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

	private static void cycle(ClientGameTestContext context, String nameKey) {
		context.runOnClient(mc -> {
			CycleButton<?> button = findCycle(mc.gui.screen(), nameKey);
			check(button.active, nameKey + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
		context.waitTicks(1);
	}

	private static CycleButton<?> findCycle(Screen screen, String nameKey) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof CycleButton<?> && w.getMessage().getContents() instanceof TranslatableContents t
						&& t.getArgs().length > 0 && t.getArgs()[0] instanceof Component name
						&& name.getContents() instanceof TranslatableContents n && n.getKey().equals(nameKey))
				.map(w -> (CycleButton<?>) w)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No switch " + nameKey));
	}

	private static @Nullable Button findButton(Screen screen, String key) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast)
				.findFirst()
				.orElse(null);
	}

	private static void pressByKey(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = findButton(mc.gui.screen(), key);
			check(button != null, "No button " + key + " on " + mc.gui.screen());
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
		context.waitTicks(2);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// The stub's report with changes waiting for a restart, so the footer has all eight buttons.
	private static final class PendingStub implements RigTuneController {
		private final StubController stub;

		PendingStub(StubController stub) {
			this.stub = stub;
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
			return true;
		}
	}
}
