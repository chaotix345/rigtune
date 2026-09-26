package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.compat.ModMenuIntegration;
import io.github.chaotix345.rigtune.client.ui.BenchmarkHistoryScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.JvmScreen;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.ProfilesScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneSettingsScreen;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.report.ModrinthOffAdvice;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.client.gui.navigation.ScreenRectangle;
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
	// v0.4 (docs/v0.4/SPEC.md X7): the three standard sizes for the Tools hub and the notice line.
	private static final int[][] V04_SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
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
		checkTools(context);

		StubController stub = new StubController(RigTuneClient::hardware);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), new PendingStub(stub))));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		atEverySize(context, "ui-stub-pending");
		check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.screen.discard") != null), "Discard pending shown with pending changes");
		// v0.4 (review X-M2): with Discard pending and a notice, the footer (nine buttons) and the notice line leave room
		// for at least 2 recommendation rows at 640x480 GUI scale 2.
		stub.setNotices(List.of(new Notice("test-server", NoticePriority.SERVER_LIMIT, Text.literal("The server limits view distance to 6 chunks"),
				Text.literal("You set 12."), List.of(), false)));
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), new PendingStub(stub))));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		for (int[] size : V04_SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkLayout(context, "ui-stub-pending-notice " + size[0] + "x" + size[1] + "@" + size[2]);
			int listHeight = context.computeOnClient(mc -> Screens.getWidgets(mc.gui.screen()).stream()
					.filter(w -> w instanceof AbstractSelectionList<?>).mapToInt(AbstractWidget::getHeight).findFirst().orElse(0));
			check(listHeight >= 2 * 24, "at least 2 recommendation rows at " + size[0] + "x" + size[1] + "@" + size[2] + ": list " + listHeight);
			checkShownNotice(context, "test-server", 0);
			context.takeScreenshot("ui-stub-pending-notice-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		checkNoticeLine(context);
		checkTierAndNarration(context);

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
		// v0.3 (review X-M2): Undo last and Undo all are in the History screen.
		pressByKey(context, "rigtune.history.open");
		context.waitForScreen(HistoryScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen history && !history.loading(), 400);
		context.waitTicks(2);
		context.takeScreenshot("ui-history-from-button");
		checkUndoButton(context, "rigtune.screen.undo_last", false, "ui-undo-last-from-button");
		checkUndoButton(context, "rigtune.screen.undo_all", true, "ui-undo-all-from-button");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);

		// v0.4 (review X-M2): Benchmark… is the Tools hub's first entry.
		pressByKey(context, "rigtune.tools.open");
		context.waitForScreen(ToolsScreen.class);
		pressByKey(context, "rigtune.screen.benchmark_menu");
		context.waitForScreen(BenchmarkMenuScreen.class);
		context.waitTicks(2);
		context.takeScreenshot("ui-benchmark-menu-from-button");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);
	}

	// v0.4 (docs/v0.4/SPEC.md C3, X3, X7; review X-M2): one Tools… button right after History…, in place of Benchmark…,
	// the footer fitting at 640x480 GUI scale 2, and the hub opening Benchmark and its four new screens (skeletons until
	// their workstreams fill them), at the 3 sizes.
	private static void checkTools(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> {
			List<String> keys = Screens.getWidgets(mc.gui.screen()).stream().filter(w -> w instanceof Button)
					.map(w -> w.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : "").toList();
			return keys.indexOf("rigtune.tools.open") == keys.indexOf("rigtune.history.open") + 1 && !keys.contains("rigtune.screen.benchmark_menu");
		}), "Tools… follows History… and replaces Benchmark…");
		resize(context, 640, 480, 2);
		checkLayout(context, "ui-main 640x480@2");
		context.takeScreenshot("ui-tools-footer-640x480-scale2");
		resize(context, 854, 480, 2);

		pressByKey(context, "rigtune.tools.open");
		context.waitForScreen(ToolsScreen.class);
		for (int[] size : V04_SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkLayout(context, "ui-tools " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot("ui-tools-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		pressByKey(context, "rigtune.screen.benchmark_menu");
		context.waitForScreen(BenchmarkMenuScreen.class);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(ToolsScreen.class);
		checkToolsEntry(context, "rigtune.tools.profiles", ProfilesScreen.class, "ui-tools-profiles");
		checkToolsEntry(context, "rigtune.tools.stutter", StutterScreen.class, "ui-tools-stutter");
		checkToolsEntry(context, "rigtune.tools.jvm", JvmScreen.class, "ui-tools-jvm");
		checkToolsEntry(context, "rigtune.tools.benchmark_history", BenchmarkHistoryScreen.class, "ui-tools-benchmark-history");
		pressByKey(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
		resize(context, 854, 480, 2);
	}

	private static void checkToolsEntry(ClientGameTestContext context, String key, Class<? extends Screen> screen, String screenshot) {
		pressByKey(context, key);
		context.waitForScreen(screen);
		context.waitTicks(2);
		checkLayout(context, screenshot);
		context.takeScreenshot(screenshot);
		pressByKey(context, "gui.done");
		context.waitForScreen(ToolsScreen.class);
	}

	// v0.4 (docs/v0.4/SPEC.md C3): the one notice line over canned notices: the highest priority shows with at most 2
	// actions, a dismiss button when dismissible and "+N more", which cycles; dismissing hides it. At the 3 sizes.
	private static void checkNoticeLine(ClientGameTestContext context) {
		StubController stub = new StubController(RigTuneClient::hardware);
		stub.setNotices(List.of(
				new Notice("test-whats-new", NoticePriority.WHATS_NEW, Text.literal("3 new recommendations for you since you last looked"), null,
						List.of(), true),
				new Notice("test-battery", NoticePriority.BATTERY_OFFER, Text.literal("You are on battery power now"),
						Text.literal("Switching changes settings; undo it in History."), List.of(new NoticeAction("switch", Text.literal("Switch to Battery")),
						new NoticeAction("snooze", Text.literal("Don't offer again")), new NoticeAction("third", Text.literal("Never shown"))), true),
				new Notice("test-server", NoticePriority.SERVER_LIMIT, Text.literal("The server limits view distance to 6 chunks"), null, List.of(),
						false)));
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), stub)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		for (int[] size : V04_SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkShownNotice(context, "test-battery", 2);
			boolean inline = context.computeOnClient(mc -> mc.gui.screen().width >= 400);
			if (inline) {
				check(context.computeOnClient(mc -> findLiteralButton(mc.gui.screen(), "Switch to Battery") != null
						&& findLiteralButton(mc.gui.screen(), "Don't offer again") != null && findLiteralButton(mc.gui.screen(), "Never shown") == null
						&& findButton(mc.gui.screen(), "rigtune.notice.dismiss") != null && findButton(mc.gui.screen(), "rigtune.notice.more") != null
						&& findButton(mc.gui.screen(), "rigtune.notice.open") == null), "inline: two actions, dismiss and +N more");
			} else {
				check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.notice.open") != null
						&& findLiteralButton(mc.gui.screen(), "Switch to Battery") == null && findButton(mc.gui.screen(), "rigtune.notice.dismiss") == null),
						"narrow: the message and one … button");
			}
			checkLayout(context, "ui-notice " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot("ui-notice-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, 640, 480, 2);
		checkNoticeScreen(context, stub);
		resize(context, 854, 480, 2);
		context.runOnClient(mc -> press(findLiteralButton(mc.gui.screen(), "Switch to Battery")));
		context.waitTicks(2);
		check(stub.noticeActions().equals(List.of("test-battery:snooze", "test-battery:switch")), "actions reached the controller: " + stub.noticeActions());

		pressByKey(context, "rigtune.notice.more");
		checkShownNotice(context, "test-server", 2);
		check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.notice.dismiss") == null), "no dismiss on a notice that can't be dismissed");
		context.takeScreenshot("ui-notice-cycled");
		pressByKey(context, "rigtune.notice.more");
		checkShownNotice(context, "test-whats-new", 2);
		pressByKey(context, "rigtune.notice.more");
		checkShownNotice(context, "test-battery", 2);

		pressByKey(context, "rigtune.notice.dismiss");
		checkShownNotice(context, "test-server", 1);
		resize(context, 854, 480, 2);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	// At 640x480@2 the … button opens NoticeScreen with every notice, its actions and dismiss buttons.
	private static void checkNoticeScreen(ClientGameTestContext context, StubController stub) {
		pressByKey(context, "rigtune.notice.open");
		context.waitForScreen(NoticeScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> ((NoticeScreen) mc.gui.screen()).shown().stream().map(Notice::key).toList())
				.equals(List.of("test-battery", "test-server", "test-whats-new")), "NoticeScreen lists every notice by priority");
		checkLayout(context, "ui-notice-screen 640x480@2");
		context.takeScreenshot("ui-notice-screen-640x480-scale2");
		context.runOnClient(mc -> press(findLiteralButton(mc.gui.screen(), "Don't offer again")));
		context.waitTicks(2);
		check(stub.noticeActions().equals(List.of("test-battery:snooze")), "action from NoticeScreen: " + stub.noticeActions());
		pressByKey(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
	}

	private static void checkShownNotice(ClientGameTestContext context, String key, int others) {
		String shown = context.computeOnClient(mc -> mc.gui.screen() instanceof RigTuneScreen screen && screen.shownNotice() != null
				? screen.shownNotice().key() + "+" + screen.otherNotices() : "none");
		check(shown.equals(key + "+" + others), "notice line shows " + key + " with " + others + " more: " + shown);
	}

	private static @Nullable Button findLiteralButton(Screen screen, String label) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getString().equals(label))
				.map(Button.class::cast)
				.findFirst()
				.orElse(null);
	}

	private static void press(@Nullable Button button) {
		check(button != null && button.active, "button there and active");
		button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
	}

	// The History screen's button opens WS-B's confirmation screen for the right scope; nothing is undone here
	// (Cancel/close only).
	private static void checkUndoButton(ClientGameTestContext context, String key, boolean all, String screenshot) {
		pressByKey(context, key);
		context.waitForScreen(UndoScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen undo && undo.plan() != null, 400);
		check(context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).plan().all()) == all, key + " opens the undo screen with all=" + all);
		context.waitTicks(2);
		context.takeScreenshot(screenshot);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(HistoryScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen history && !history.loading(), 400);
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

	// docs/v0.4/SPEC.md 2j (AC2j.3) and 2m (AC2m.1): the stub's report (GPU 5 table match, CPU 4 fallback estimate from 16
	// threads, memory 5): the header says "Estimated tier 4/5 · lowest estimated component: CPU" at the 3 standard sizes
	// and the badge's one tooltip gives each component's basis (hovered for a screenshot); with a recommendation's
	// checkbox focused, the list's narration names that recommendation.
	private static void checkTierAndNarration(ClientGameTestContext context) {
		StubController stub = new StubController(RigTuneClient::hardware);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), stub)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		List<String> tooltip = List.of("GPU tier 5 (table match)", "CPU tier 4 (fallback estimate from 16 threads)", "Memory tier 5 (6.0 GB heap)");
		for (int[] size : V04_SIZES) {
			resize(context, size[0], size[1], size[2]);
			String name = size[0] + "x" + size[1] + "-scale" + size[2];
			checkLayout(context, "ui-tier " + name);
			ScreenRectangle badge = context.computeOnClient(mc -> {
				RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
				String header = String.join("\n", screen.headerLines().stream().map(Component::getString).toList());
				check(screen.tierBadgeArea() != null, name + ": the tier badge is drawn");
				check("Estimated tier 4/5 · lowest estimated component: CPU".equals(screen.tierBadgeText()), name + ": badge " + screen.tierBadgeText());
				check(screen.tierTooltip().stream().map(Component::getString).toList().equals(tooltip), name + ": tooltip " + screen.tierTooltip());
				check(!header.contains("limited by"), name + ": no bottleneck wording: " + header);
				return screen.tierBadgeArea();
			});
			RigTune.LOGGER.info("UiGameTest: tier badge at {} for {}", badge, name);
			context.takeScreenshot("ui-tier-" + name);
			int scale = context.computeOnClient(mc -> (int) mc.getWindow().getGuiScale());
			context.getInput().setCursorPos((badge.left() + 2) * scale, (badge.top() + 4) * scale);
			context.waitTicks(3);
			context.takeScreenshot("ui-tier-tooltip-" + name);
			context.getInput().setCursorPos(1, 1);
		}
		String narration = context.computeOnClient(mc -> {
			RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
			ContainerObjectSelectionList<?> list = screen.focusRecommendation("set-vanilla.renderDistance");
			check(list != null, "the render distance row is there");
			ScreenNarrationCollector collector = new ScreenNarrationCollector();
			//? if >=26.3 {
			/*collector.update(list::updateWidgetNarration, net.minecraft.client.gui.narration.NarrationTrigger.KEYBOARD);
			*///?} else
			collector.update(list::updateWidgetNarration);
			return collector.collectNarrationText(false);
		});
		RigTune.LOGGER.info("UiGameTest: the focused row narrates: {}", narration);
		String title = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).titleOf("set-vanilla.renderDistance"));
		check(narration.contains(title) && narration.contains("High impact"), "the narration names the recommendation (" + title + "): " + narration);
		context.takeScreenshot("ui-narration-focused");
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

		@Override
		public List<Notice> notices() {
			return stub.notices();
		}

		@Override
		public void noticeAction(String key, String actionId) {
			stub.noticeAction(key, actionId);
		}

		@Override
		public void dismissNotice(String key) {
			stub.dismissNotice(key);
		}
	}
}
