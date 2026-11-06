package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UiFixesChecks {
	private static final int[][] SIZES = {{1280, 720, 3}, {854, 480, 2}, {1280, 720, 2}};

	private final List<String> failures = new ArrayList<>();

	static void run(ClientGameTestContext context) {
		new UiFixesChecks().runAll(context);
	}

	private void runAll(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.hardware() != null && RigTuneClient.controller().report() != null, 1200);
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		tieHeader(context);
		sodiumButton(context);
		pairCaveat(context);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		resize(context, 854, 480, guiScale);
		if (!failures.isEmpty()) {
			throw new AssertionError("UiFixesChecks: " + String.join("; ", failures));
		}
	}

	private void tieHeader(ClientGameTestContext context) {
		StubController stub = new StubController(RigTuneClient::hardware);
		stub.tieTiers();
		Component status = (Component.literal("Applied 1 setting(s). If the Modrinth App syncs game settings, it copies these to your other instances when"
				+ " the game closes; turn that off in the app's settings to keep them for this instance only."));
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), stub)));
		context.waitForScreen(RigTuneScreen.class);
		context.runOnClient(mc -> stub.setStatus(status));
		context.waitTicks(3);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			String name = size[0] + "x" + size[1] + "-scale" + size[2];
			context.takeScreenshot("fix35-tie-header-" + name);
			int[] where = context.computeOnClient(mc -> {
				RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
				try {
					java.lang.reflect.Field field = RigTuneScreen.class.getDeclaredField("statusY");
					field.setAccessible(true);
					return new int[]{(int) field.get(screen), (int) mc.getWindow().getGuiScale()};
				} catch (ReflectiveOperationException e) {
					throw new AssertionError(e);
				}
			});
			context.getInput().setCursorPos(40 * where[1], (where[0] + 4) * where[1]);
			context.waitTicks(3);
			context.takeScreenshot("fix38-status-tooltip-" + name);
			context.getInput().setCursorPos(1, 1);
			List<String> header = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).headerLines().stream().map(Component::getString).toList());
			int column = Math.min(size[0] / size[2] - 32, 480);
			boolean readable = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).headerLines().stream()
					.filter(l -> l.getString().contains("Rules r"))
					.allMatch(l -> mc.font.width(l) <= column));
			RigTune.LOGGER.info("UiFixesChecks: header at {}: {}", name, header);
			check(header.stream().anyMatch(l -> l.contains("GPU, CPU, memory")), name + ": the badge lists the tie: " + header);
			check(readable && header.stream().anyMatch(l -> l.contains("Rules r") && (l.contains("Online") || l.contains("Offline"))),
					name + ": the display, rules and online status fit the column: " + header);
		}
	}

	private void sodiumButton(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		resize(context, 854, 480, 2);
		context.clickScreenButton("menu.options");
		context.clickScreenButton("options.video");
		context.waitTicks(5);
		boolean sodium = context.computeOnClient(mc -> mc.gui.screen() != null && mc.gui.screen().getClass().getName().equals(RigTuneClient.SODIUM_VIDEO_SCREEN));
		if (!sodium) {
			RigTune.LOGGER.info("UiFixesChecks: Sodium isn't loaded; its Video Settings isn't checked");
			return;
		}
		for (int[] size : new int[][]{{1280, 720, 3}, {1280, 720, 2}, {1920, 1080, 2}}) {
			resize(context, size[0], size[1], size[2]);
			String name = size[0] + "x" + size[1] + "-scale" + size[2];
			context.takeScreenshot("fix39-sodium-video-" + name);
			List<String> overlaps = context.computeOnClient(mc -> {
				Screen screen = mc.gui.screen();
				AbstractWidget button = Screens.getWidgets(screen).stream()
						.filter(w -> w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.button")).findFirst().orElse(null);
				if (button == null) {
					return List.of("no RigTune button");
				}
				ScreenRectangle own = button.getRectangle();
				List<String> out = new ArrayList<>();
				for (GuiEventListener child : screen.children()) {
					ScreenRectangle r = child.getRectangle();
					if (child != button && r.width() > 0 && r.height() > 0 && r.intersects(own)) {
						out.add(child.getClass().getSimpleName() + " " + r);
					}
				}
				RigTune.LOGGER.info("UiFixesChecks: RigTune button at {} in Sodium's Video Settings at {}", own, mc.getWindow().getGuiScaledWidth() + "x"
						+ mc.getWindow().getGuiScaledHeight());
				return out;
			});
			check(overlaps.isEmpty(), name + ": the RigTune button covers " + overlaps);
		}
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	private void pairCaveat(ClientGameTestContext context) {
		BenchmarkController.Outcome outcome = measurePair();
		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkResultScreen(new TitleScreen(), outcome)));
		context.waitForScreen(BenchmarkResultScreen.class);
		for (int[] size : new int[][]{{1280, 720, 3}, {854, 480, 2}, {640, 480, 2}}) {
			resize(context, size[0], size[1], size[2]);
			String name = size[0] + "x" + size[1] + "-scale" + size[2];
			context.takeScreenshot("fix43-pair-caveat-" + name);
			String rows = context.computeOnClient(mc -> String.join("\n", ((BenchmarkResultScreen) mc.gui.screen()).statusRows().stream()
					.map(UiFixesChecks::plain).toList()));
			RigTune.LOGGER.info("UiFixesChecks: result rows at {}:\n{}", name, rows);
			check(rows.replace("\n", " ").contains("during both runs, so they can't be compared. Let it finish generating first, then Measure before again."),
					name + ": the whole caveat is shown: " + rows);
		}
	}

	private static BenchmarkController.Outcome measurePair() {
		Knobs knobs = new Knobs(16, 12, true, false);
		SessionResult session = new SessionResult(BenchmarkRequest.Mode.MEASURE, knobs, knobs, 170, new PlannerResult(16, true, 16, List.of(), "test"),
				List.of(), new BenchmarkMath.Aggregate(400, 161, 7.9, 3, 0.09), null, null, Map.of(), false);
		BenchmarkRecord before = record("fix43-before", BenchmarkRecord.BEFORE, 12, 422, 181);
		BenchmarkRecord after = record("fix43-after", BenchmarkRecord.AFTER, 16, 400, 161);
		return new BenchmarkController.Outcome(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, BenchmarkRequest.Scene.CURRENT, "fix43"), session, false,
				after, before, true, false, List.of());
	}

	private static BenchmarkRecord record(String id, String phase, int rd, double avg, double low) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, rd, null, null, null));
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(true, false, null, 2560, 1440, false, BenchmarkRecord.Context.PROTOCOL).withDhGenerating(true);
		return new BenchmarkRecord(id, "2026-11-01T10:00:00Z", "0.5.1", "26.2", "MEASURE", "CURRENT", phase, "fix43", 170, false, knobs,
				new BenchmarkRecord.Result(avg, low, 1000 / low, 3, 0.09), Map.of(), Map.of(), null, false, context);
	}

	private static String plain(FormattedCharSequence sequence) {
		StringBuilder out = new StringBuilder();
		sequence.accept((index, style, codePoint) -> {
			out.appendCodePoint(codePoint);
			return true;
		});
		return out.toString();
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

	private void check(boolean condition, String message) {
		if (!condition) {
			RigTune.LOGGER.error("UiFixesChecks: check failed: {}", message);
			failures.add(message);
		}
	}
}
