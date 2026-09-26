package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkConditions;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.notice.BenchmarkStaleNoticeSource;
import io.github.chaotix345.rigtune.client.notice.RegressionNoticeSource;
import io.github.chaotix345.rigtune.client.ui.BenchmarkHistoryScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkTrendLines;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.ChangeWindow;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.notice.Notice;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// docs/v0.4/SPEC.md 7 (AC7.5), with the network off (X1): a seeded benchmarks.json (a run under other conditions, 4
// comparable runs and a regressed latest run, all under the game's current conditions) and history.json (a mod update
// between the last comparable run and the latest). The regression notice on RigTune's screen; Benchmark history with the
// regression line, "Changes since then (may be related)" naming the update, the chart and the "not shown" note; the
// context selector; Got it acknowledges the run (awareness.json). Then the latest run's resolution is changed on disk:
// "Performance changed under different conditions (resolution); cause unknown.", the "needs a rerun" marker (Benchmark
// history, the tier tooltip's line, the share report) and the stale notice, dismissed per run id. Screenshots at
// 1280x720@2, 640x480@2 and 854x480@2. Every seeded file is put back as it was.
public class BenchmarkHistoryGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
	private static final String LATEST = "bh-latest";
	private static final String REGRESSION_KEY = RegressionNoticeSource.KEY_PREFIX + LATEST;
	private static final String STALE_KEY = BenchmarkStaleNoticeSource.KEY_PREFIX + LATEST;

	private final Path configDir = FabricLoader.getInstance().getConfigDir();
	private final Path historyFile = Journal.file(configDir);
	private final Path awarenessFile = AwarenessStore.file(configDir);
	private final Journal journal = new Journal(configDir, null, null, (message, error) -> {
		throw new AssertionError(message, error);
	});

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController controller = RigTuneClient.controller();
		Path benchmarksFile = BenchmarkStore.file();
		byte[] benchmarks = read(benchmarksFile);
		byte[] history = read(historyFile);
		byte[] awareness = read(awarenessFile);
		boolean network = context.computeOnClient(mc -> ClientSettings.shared(configDir).networkEnabled);
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		try {
			context.runOnClient(mc -> ClientSettings.shared(configDir).networkEnabled = false);
			resize(context, 854, 480, 2);
			run(context, controller, benchmarksFile);
		} finally {
			// The files first: later classes must never see the seeded runs, whatever fails below.
			restore(benchmarksFile, benchmarks);
			restore(historyFile, history);
			restore(awarenessFile, awareness);
			context.runOnClient(mc -> {
				ClientSettings.shared(configDir).networkEnabled = network;
				mc.gui.setScreen(new TitleScreen());
			});
			resize(context, 854, 480, guiScale);
		}
		RigTune.LOGGER.info("BenchmarkHistoryGameTest: passed");
	}

	private void run(ClientGameTestContext context, RigTuneController controller, Path benchmarksFile) {
		BenchmarkTrend.Current now = context.computeOnClient(BenchmarkConditions::current);
		check(now.modSetHash() != null && now.modSetHash().matches("[0-9a-f]{64}"), "the mod-set hash: " + now.modSetHash());
		JournalEntry before = new JournalEntry("bh-apply-0", "2026-09-19T08:00:00Z", JournalEntry.APPLY, "0.4.0", now.mcVersion(), null,
				List.of(JournalChange.setting("vanilla.entityShadows", "true", "false", JournalChange.APPLIED, null)));
		JournalEntry update = new JournalEntry("bh-update", "2026-09-23T12:00:00Z", JournalEntry.APPLY, "0.4.0", now.mcVersion(), null, List.of(
				JournalChange.file(JournalChange.DISABLE, "fakemod", "fake-mod-1.0.jar", JournalChange.APPLIED, "bh-op-1", "bh-group"),
				JournalChange.file(JournalChange.ENABLE, "fakemod", "fake-mod-1.1.jar", JournalChange.APPLIED, "bh-op-2", "bh-group")));
		try {
			Files.deleteIfExists(historyFile);
			check(journal.update(entries -> List.of(before, update)), "seeded history.json");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		seed(benchmarksFile, now, now.width());

		// The regression notice on RigTune's screen (not dismissible: Details… and Got it acknowledge it).
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		Notice regression = notice(context, controller, REGRESSION_KEY);
		check(regression != null && !regression.dismissible() && regression.actions().size() == 2, "the regression notice: " + regression);
		check(regression.message().english().startsWith("1% lows 19% below your usual 543 FPS since "), "its message: " + regression.message().english());
		check(regression.detail() != null && regression.detail().english().contains("may be related")
				&& regression.detail().english().contains("fake-mod-1.1.jar"), "its detail names the update: " + regression.detail());
		check(notice(context, controller, STALE_KEY) == null, "no stale notice under the seeded conditions");
		context.takeScreenshot("bench-history-notice-854x480-scale2");
		// Seeded again at each size, so the screenshots show the seeded case without a "needs a rerun" marker.
		resize(context, 1280, 720, 2);
		reseed(context, benchmarksFile);
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("bench-history-notice-1280x720-scale2");
		resize(context, 854, 480, 2);
		reseed(context, benchmarksFile);

		// Tools -> Benchmark history.
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(mc.gui.screen(), controller)));
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> ((ToolsScreen) mc.gui.screen()).openBenchmarkHistory());
		context.waitForScreen(BenchmarkHistoryScreen.class);
		context.waitTicks(3);
		BenchmarkTrend.View view = context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).view());
		check(view.assessment() != null && view.assessment().kind() == BenchmarkTrend.Kind.REGRESSION && LATEST.equals(view.assessment().latestRunId()),
				"the latest run regressed: " + view.assessment());
		check(view.comparableRuns() == 5 && view.otherRuns() == 1 && view.points().size() == 5, "5 comparable runs, 1 not shown: " + view);
		ChangeWindow changes = view.changes();
		check(changes != null && changes.byCursor() && changes.items().size() == 1
				&& changes.items().getFirst().change().row() == HistoryModel.Row.UPDATED && "fakemod".equals(changes.items().getFirst().change().modId()),
				"the window has the update, walked by cursor: " + changes);
		List<String> lines = lines(context);
		check(has(lines, "1% lows 19% below your usual 543 FPS since "), "the regression line: " + lines);
		check(has(lines, "Changes since then (may be related):") && lines.stream().anyMatch(l -> l.startsWith("· ") && l.contains("fake-mod-1.1.jar")),
				"the changes name the update: " + lines);
		check(has(lines, "5 comparable runs; 1 with different conditions not shown"), "the not-shown note: " + lines);
		check(has(lines, "Last benchmark: 1% low 440 FPS · ") && !has(lines, "Needs a rerun"), "the last benchmark, current: " + lines);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			reseed(context, benchmarksFile);
			context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkHistoryScreen(new ToolsScreen(null, controller), controller)));
			context.waitForScreen(BenchmarkHistoryScreen.class);
			context.waitTicks(3);
			checkLayout(context, "history " + size[0] + "x" + size[1]);
			List<String> shown = lines(context);
			check(shown.size() == 5 && has(shown, "Changes since then (may be related):") && !has(shown, "Needs a rerun"),
					"lines at " + size[0] + "x" + size[1] + ": " + shown);
			check(context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).chartDrawn()), "the chart is drawn at " + size[0] + "x" + size[1]);
			context.takeScreenshot("bench-history-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}

		// The result screen of a Tune with many lines at 640x480@2 (review M3): the changes are counted in one line, and
		// the table keeps room for its header and 3 rows.
		resize(context, 640, 480, 2);
		BenchmarkTrend.Current small = reseed(context, benchmarksFile);
		BenchmarkRecord latest = context.computeOnClient(mc -> BenchmarkStore.history().runs().getLast());
		check(LATEST.equals(latest.id()), "the seeded latest run: " + latest.id());
		BenchmarkController.Outcome outcome = tuneOutcome(small, latest);
		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkResultScreen(new TitleScreen(), outcome)));
		context.waitForScreen(BenchmarkResultScreen.class);
		context.waitTicks(3);
		int top = context.computeOnClient(mc -> ((BenchmarkResultScreen) mc.gui.screen()).contentTop());
		int bottom = context.computeOnClient(mc -> ((BenchmarkResultScreen) mc.gui.screen()).contentBottom());
		check(top + 12 * 4 + 2 <= bottom, "room for the table at 640x480@2: " + top + " to " + bottom);
		context.takeScreenshot("bench-history-result-640x480-scale2");
		// review-8 P5B-F4: the status lines wrap to the width at every size; a line folds back to one clipped row (its text
		// a tooltip) only where the table would otherwise lose its header and 3 rows.
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			context.waitTicks(3);
			String where = size[0] + "x" + size[1] + "@" + size[2];
			int[] fit = context.computeOnClient(mc -> {
				BenchmarkResultScreen result = (BenchmarkResultScreen) mc.gui.screen();
				int widest = result.statusRows().stream().mapToInt(mc.font::width).max().orElse(0);
				return new int[]{widest, result.width, result.clippedStatusRows(), result.statusRows().size(), result.contentTop(), result.contentBottom()};
			});
			check(fit[0] <= fit[1] - 16, where + ": every status row fits the width (" + fit[0] + " of " + (fit[1] - 16) + ")");
			check(fit[4] + 12 * 4 + 2 <= fit[5], where + ": room for the table: " + fit[4] + " to " + fit[5]);
			check(size[0] == 640 || fit[2] == 0, where + ": no status line clipped: " + fit[2] + " of " + fit[3]);
			RigTune.LOGGER.info("BenchmarkHistoryGameTest: result screen at {}: {} status rows, {} clipped", where, fit[3], fit[2]);
			context.takeScreenshot("bench-result-wrapped-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, 854, 480, 2);
		reseed(context, benchmarksFile);
		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkHistoryScreen(new ToolsScreen(null, controller), controller)));
		context.waitForScreen(BenchmarkHistoryScreen.class);
		context.waitTicks(3);

		// The context selector: the other conditions' single run, then back.
		String selected = view.contextKey();
		cycleSelector(context);
		BenchmarkTrend.View other = context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).view());
		check(!selected.equals(other.contextKey()) && other.comparableRuns() == 1 && other.otherRuns() == 5, "the other context: " + other);
		check(has(lines(context), "1 comparable run; 5 with different conditions not shown"), "its note: " + lines(context));
		context.takeScreenshot("bench-history-other-context");
		cycleSelector(context);
		check(selected.equals(context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).view().contextKey())), "back to the latest's");

		// Details… on RigTune's screen: opens Benchmark history and acknowledges the run in awareness.json (Got it takes the
		// same path without opening it); the notice is gone.
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		context.runOnClient(mc -> controller.noticeAction(REGRESSION_KEY, RegressionNoticeSource.DETAILS));
		context.waitForScreen(BenchmarkHistoryScreen.class);
		check(notice(context, controller, REGRESSION_KEY) == null, "acknowledged, the regression notice is gone");
		check(AwarenessStore.shared(configDir).acknowledgedRegressions().contains(LATEST), "awareness.json acknowledgedRegressions has the run");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);

		// The latest run's resolution changed on disk: not comparable any more, and it needs a rerun.
		seed(benchmarksFile, now, now.width() * 2);
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		Notice stale = notice(context, controller, STALE_KEY);
		check(stale != null && stale.dismissible() && "Your last benchmark needs a rerun: resolution changed".equals(stale.message().english()),
				"the stale notice: " + stale);
		Component tooltip = context.computeOnClient(mc -> BenchmarkTrendLines.lastBenchmark(controller));
		String tooltipText = tooltip == null ? "" : tooltip.getString();
		check(tooltipText.contains("Last benchmark: 1% low 440 FPS") && tooltipText.contains("Needs a rerun (changed since: resolution)"),
				"the tier tooltip's line: " + tooltipText);
		String report = context.computeOnClient(mc -> controller.shareReport());
		check(report.contains("- Needs a rerun (changed since: resolution)\n") && report.contains("- conditions: RD "), "the share report: " + report);
		context.takeScreenshot("bench-history-stale-notice");
		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkHistoryScreen(mc.gui.screen(), controller)));
		context.waitForScreen(BenchmarkHistoryScreen.class);
		context.waitTicks(3);
		List<String> rerun = lines(context);
		check(has(rerun, "Performance changed under different conditions (resolution); cause unknown."), "not comparable: " + rerun);
		check(has(rerun, "Needs a rerun (changed since: resolution)"), "the marker on Benchmark history: " + rerun);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			context.takeScreenshot("bench-history-rerun-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, 854, 480, 2);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);

		// Dismissed per run id (awareness.json dismissed).
		context.runOnClient(mc -> controller.dismissNotice(STALE_KEY));
		check(notice(context, controller, STALE_KEY) == null, "dismissed, the stale notice is gone");
		check(AwarenessStore.shared(configDir).dismissed().contains(STALE_KEY), "awareness.json dismissed has the key");
	}

	// Seeds again under the conditions of the current window size.
	private static BenchmarkTrend.Current reseed(ClientGameTestContext context, Path file) {
		BenchmarkTrend.Current now = context.computeOnClient(BenchmarkConditions::current);
		seed(file, now, now.width());
		return now;
	}

	// A Tune that fills the result screen: noisy, a Distant Horizons cost, an incomplete step and the deadline, as the
	// seeded (regressed) latest run.
	private static BenchmarkController.Outcome tuneOutcome(BenchmarkTrend.Current now, BenchmarkRecord record) {
		int rd = now.renderDistance();
		Knobs original = new Knobs(rd, now.simulationDistance(), false, false);
		List<PlannerResult.Measurement> steps = List.of(new PlannerResult.Measurement(rd, new FrameStats(900, 704, 440, 3.1, 9), true, true),
				new PlannerResult.Measurement(rd + 2, new FrameStats(900, 650, 400, 3.4, 10), true, true),
				new PlannerResult.Measurement(rd + 4, new FrameStats(900, 600, 380, 3.6, 11), false, false));
		SessionResult session = new SessionResult(BenchmarkRequest.Mode.TUNE, original, original, 60, new PlannerResult(rd, true, rd, steps, "test"),
				List.of(), new BenchmarkMath.Aggregate(704, 440, 3.1, 2, 0.08), new SessionResult.Cost(440, 704, 470, 760), null, Map.of(), true);
		return new BenchmarkController.Outcome(new BenchmarkRequest(BenchmarkRequest.Mode.TUNE, BenchmarkRequest.Scene.CURRENT, null), session, false,
				record, null, true, false, List.of());
	}

	// Under the current conditions except the latest run's width: a run with another render distance (not shown), 4
	// comparable runs (1 % lows 540, 545, 538, 550: median 542.5) and the latest at 440 (19 % below it).
	private static void seed(Path file, BenchmarkTrend.Current now, int latestWidth) {
		List<BenchmarkRecord> runs = new ArrayList<>();
		runs.add(run("bh-other", "2026-09-18T10:00:00Z", 300, now, now.renderDistance() + 2, now.width(), null));
		double[] lows = {540, 545, 538, 550};
		for (int i = 0; i < lows.length; i++) {
			runs.add(run("bh-" + i, "2026-09-" + (19 + i) + "T10:00:00Z", lows[i], now, now.renderDistance(), now.width(), "bh-apply-0"));
		}
		runs.add(run(LATEST, "2026-09-24T10:00:00Z", 440, now, now.renderDistance(), latestWidth, "bh-update"));
		BenchmarkHistory history = BenchmarkHistory.empty();
		for (BenchmarkRecord r : runs) {
			history = history.with(r);
		}
		try {
			history.save(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static BenchmarkRecord run(String id, String at, double low, BenchmarkTrend.Current now, int rd, int width, @Nullable String cursor) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, rd, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(now.simulationDistance(), now.simulationDistance(), null, null, null));
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(now.dhRendering(), now.shaders(), now.shaderPack(), width, now.height(),
				now.fullscreen(), BenchmarkRecord.Context.PROTOCOL).withModSet(now.modSetHash(), cursor);
		return new BenchmarkRecord(id, at, "0.4.0", now.mcVersion(), "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 144, true, knobs,
				new BenchmarkRecord.Result(low * 1.6, low, 1000 / low, 2, 0.02), Map.of(), Map.of(), new BenchmarkRecord.World("rigtune-benchmark", 8675309L),
				false, context);
	}

	private static @Nullable Notice notice(ClientGameTestContext context, RigTuneController controller, String key) {
		return context.computeOnClient(mc -> controller.notices().stream().filter(n -> n.key().equals(key)).findFirst().orElse(null));
	}

	// The lines Benchmark history shows, as the game renders them (en_us in the harness).
	private static List<String> lines(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((BenchmarkHistoryScreen) mc.gui.screen()).shownLines().stream()
				.map(l -> Texts.component(l.text()).getString()).toList());
	}

	private static boolean has(List<String> lines, String prefix) {
		return lines.stream().anyMatch(l -> l.startsWith(prefix));
	}

	private static void cycleSelector(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			CycleButton<?> selector = Screens.getWidgets(mc.gui.screen()).stream().filter(w -> w instanceof CycleButton<?>).map(w -> (CycleButton<?>) w)
					.findFirst().orElseThrow(() -> new AssertionError("no context selector"));
			check(selector.active, "the selector is active with two contexts");
			selector.onPress(new MouseButtonEvent(selector.getX() + 1, selector.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		});
		context.waitTicks(3);
	}

	// Every visible widget inside the screen, none overlapping (the selector's long label scrolls, as vanilla's do).
	private static void checkLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w.visible).toList();
			for (AbstractWidget w : widgets) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
						name + ": " + w.getMessage().getString() + " outside " + screen.width + "x" + screen.height);
			}
			for (int i = 0; i < widgets.size(); i++) {
				for (int j = i + 1; j < widgets.size(); j++) {
					AbstractWidget a = widgets.get(i);
					AbstractWidget b = widgets.get(j);
					check(!(a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()),
							name + ": " + a.getMessage().getString() + " overlaps " + b.getMessage().getString());
				}
			}
		});
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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
