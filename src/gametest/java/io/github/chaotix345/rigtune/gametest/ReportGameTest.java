package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.report.IssueLink;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;

// WS-F (docs/v0.3/SPEC.md item 10, AC10.2): Report a problem copies the full report and shows vanilla's confirm-link
// screen with the issue link, fully readable at every reference size including 640x480 at GUI scale 2; the test then
// cancels, so nothing is ever opened (Cancel only returns to the RigTune screen).
public class ReportGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{640, 480, 2}, {854, 480, 2}, {1280, 720, 2}};
	// Every character URLEncoder emits, plus those of the fixed part of the link.
	private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final String SYMBOLS = ".-*_+%:/?=&";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController controller = RigTuneClient.controller();
		String before = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
		try {
			resize(context, 640, 480, 2);
			context.runOnClient(mc -> {
				mc.gui.toastManager().clear();
				RigTuneClient.open(new TitleScreen());
			});
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(3);
			context.runOnClient(mc -> {
				Button button = findButton(mc.gui.screen(), "rigtune.report.button");
				check(button != null && button.active, "Report a problem is there and active");
				check(mc.font.width(button.getMessage()) <= button.getWidth() - 4, "label fits: " + describe(button));
				Screen screen = mc.gui.screen();
				check(button.getX() >= 0 && button.getRight() <= screen.width && button.getBottom() <= screen.height, "inside the screen: " + describe(button));
			});
			context.takeScreenshot("report-button-640x480-scale2");

			checkWorstCase(context);

			// Press, then read the clipboard and build the expected link in the same client task.
			String[] result = context.computeOnClient(mc -> {
				Button button = findButton(mc.gui.screen(), "rigtune.report.button");
				button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
				String report = controller.shareReport();
				return new String[]{mc.keyboardHandler.getClipboard(), report, IssueLink.uri(controller.reportVersions(), report).toString()};
			});
			String copied = result[0].replace("\r\n", "\n");
			String report = result[1];
			String expected = result[2];
			check(!report.isEmpty() && copied.equals(report), "the clipboard holds the full report (" + copied.length() + "/" + report.length() + ")");
			context.waitForScreen(ConfirmLinkScreen.class);
			context.waitTicks(2);
			String shown = context.computeOnClient(mc -> message(mc.gui.screen()).getMessage().getString());
			RigTune.LOGGER.info("ReportGameTest: {}-character link for a {}-character report: {}", shown.length(), report.length(), shown);
			check(shown.equals(expected), "the confirm screen shows the issue link: " + shown + " vs " + expected);
			check(shown.startsWith(IssueLink.NEW_ISSUE + "?template=" + IssueLink.TEMPLATE + "&title="), shown);
			check(shown.length() <= IssueLink.MAX_URL, "at most " + IssueLink.MAX_URL + " characters: " + shown.length());
			check(!shown.contains("labels=") && !shown.contains("assignees="), shown);
			check(URI.create(shown).getRawQuery().contains("&" + IssueLink.REPORT_FIELD + "="), "carries a report: " + shown);

			for (int[] size : SIZES) {
				resize(context, size[0], size[1], size[2]);
				String name = size[0] + "x" + size[1] + "-scale" + size[2];
				context.runOnClient(mc -> {
					checkFullyShown(mc, "link at " + name);
					checkLayout(mc, "confirm screen at " + name);
				});
				context.takeScreenshot("report-confirm-" + name);
			}

			pressByKey(context, "gui.cancel");
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(2);
			RigTune.LOGGER.info("ReportGameTest: cancelled; back on the RigTune screen, Open in Browser never pressed");
			context.takeScreenshot("report-cancelled");
			context.runOnClient(mc -> mc.gui.screen().onClose());
			context.waitForScreen(TitleScreen.class);
		} finally {
			context.runOnClient(mc -> mc.keyboardHandler.setClipboard(before));
			resize(context, 854, 480, 0);
		}
	}

	// The spike's worst case (F-M1): MAX_URL characters of the widest glyph a link can contain, fully readable at the
	// smallest reference size.
	private static void checkWorstCase(ClientGameTestContext context) {
		URI worst = context.computeOnClient(mc -> {
			char widest = 'A';
			for (char c : LETTERS.toCharArray()) {
				if (mc.font.width(String.valueOf(c)) > mc.font.width(String.valueOf(widest))) {
					widest = c;
				}
			}
			for (char c : SYMBOLS.toCharArray()) {
				check(mc.font.width(String.valueOf(c)) <= mc.font.width(String.valueOf(widest)), "'" + c + "' is no wider than '" + widest + "'");
			}
			String prefix = IssueLink.NEW_ISSUE + "?template=" + IssueLink.TEMPLATE + "&" + IssueLink.REPORT_FIELD + "=";
			RigTune.LOGGER.info("ReportGameTest: widest link glyph '{}' ({} px)", widest, mc.font.width(String.valueOf(widest)));
			return URI.create(prefix + String.valueOf(widest).repeat(IssueLink.MAX_URL - prefix.length()));
		});
		context.runOnClient(mc -> ConfirmLinkScreen.confirmLinkNow(mc.gui.screen(), worst));
		context.waitForScreen(ConfirmLinkScreen.class);
		context.waitTicks(2);
		context.runOnClient(mc -> checkFullyShown(mc, "worst-case " + IssueLink.MAX_URL + "-character link at 640x480@2"));
		context.takeScreenshot("report-confirm-worst-case-640x480-scale2");
		pressByKey(context, "gui.cancel");
		context.waitForScreen(RigTuneScreen.class);
	}

	private static MultiLineTextWidget message(Screen screen) {
		return Screens.getWidgets(screen).stream()
				.filter(MultiLineTextWidget.class::isInstance)
				.map(MultiLineTextWidget.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No message on " + screen));
	}

	// ConfirmScreen wraps its message to width - 50 and shows at most 15 rows (javap, 26.2 and 26.3).
	private static void checkFullyShown(Minecraft mc, String what) {
		Screen screen = mc.gui.screen();
		MultiLineTextWidget text = message(screen);
		int rows = mc.font.split(text.getMessage(), screen.width - 50).size();
		int shown = text.getHeight() / mc.font.lineHeight;
		RigTune.LOGGER.info("ReportGameTest: {}: {} rows, {} shown", what, rows, shown);
		check(rows == shown, what + ": " + rows + " rows but " + shown + " shown");
	}

	private static void checkLayout(Minecraft mc, String name) {
		Screen screen = mc.gui.screen();
		List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w.visible).toList();
		for (AbstractWidget w : widgets) {
			check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
					name + ": " + describe(w) + " outside " + screen.width + "x" + screen.height);
		}
		for (int i = 0; i < widgets.size(); i++) {
			for (int j = i + 1; j < widgets.size(); j++) {
				AbstractWidget a = widgets.get(i);
				AbstractWidget b = widgets.get(j);
				check(!(a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()),
						name + ": " + describe(a) + " overlaps " + describe(b));
			}
		}
	}

	private static String describe(AbstractWidget w) {
		return "'" + w.getMessage().getString() + "' [" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
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
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
		context.waitTicks(2);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
