package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.GcClock;
import io.github.chaotix345.rigtune.core.stutter.GcKind;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// docs/v0.4/SPEC.md 5, AC5.7 (network off, X1): enable the monitor, create a singleplayer world, wait 100 ticks,
// System.gc(), 40 ticks, a full save (save-all's code), open StutterScreen: a GC event with cause System.gc(), calibrated and overlapping a
// recorded frame; a save window with begin and end; the screen renders (screenshots at the 3 standard sizes, X7).
// Disabling removes the GC listener and stops the sampler; leaving the world writes the session to stutter.json and
// releases the buffers. No spike counts (the harness's tick sync makes frame timing unrepresentative).
public class StutterGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		Path configDir = FabricLoader.getInstance().getConfigDir();
		RigTuneController controller = RigTuneClient.controller();
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> controller.report() != null, 1200);
		ClientSettings settings = ClientSettings.shared(configDir);
		boolean networkBefore = settings.networkEnabled;
		try {
			context.runOnClient(mc -> {
				settings.networkEnabled = false;
				settings.save(configDir);
				controller.settingsChanged();
			});
			hubAndEmptyScreen(context, controller);
			inAWorld(context, controller, configDir);
		} finally {
			context.runOnClient(mc -> {
				controller.setStutterMonitor(false);
				settings.networkEnabled = networkBefore;
				settings.save(configDir);
				controller.settingsChanged();
			});
			resize(context, 854, 480, 0);
		}
		RigTune.LOGGER.info("StutterGameTest: passed");
	}

	// Tools -> Stutter Doctor, with the monitor off and no world: the screen says so and offers Start.
	private static void hubAndEmptyScreen(ClientGameTestContext context, RigTuneController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new RigTuneScreen(new TitleScreen(), controller), controller)));
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> ((ToolsScreen) mc.gui.screen()).openStutter());
		context.waitForScreen(StutterScreen.class);
		context.waitTicks(5);
		StutterView view = context.computeOnClient(mc -> ((StutterScreen) mc.gui.screen()).shownView());
		check(!view.recording() && !view.monitorOn(), "monitor off, not recording: " + view);
		check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.stutter.start") != null), "a Start button");
		checkLayout(context, "stutter-off 854x480");
		context.takeScreenshot("stutter-off");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	private static void inAWorld(ClientGameTestContext context, RigTuneController controller, Path configDir) {
		context.runOnClient(mc -> controller.setStutterMonitor(true));
		check(ClientSettings.shared(configDir).stutterMonitor, "the monitor setting is on");
		int before = new StutterStore(configDir).sessions().size();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(100);
			check(StutterMonitor.session() != null, "a session capture started with the world");
			check(StutterHooks.gcListenerActive(), "the GC listener is registered while capturing");
			check(StutterHooks.samplerRunning() && samplerThread(), "the sampler thread runs while capturing");

			long gcCalled = context.computeOnClient(mc -> {
				long t = System.nanoTime();
				System.gc();
				return t;
			});
			context.waitTicks(40);
			// What save-all runs (the command exists only on dedicated servers): MinecraftServer.saveEverything ->
			// saveAllChunks, which Fabric's BEFORE_SAVE/AFTER_SAVE wrap.
			singleplayer.getServer().runOnServer(server -> server.saveEverything(false, true, true));
			context.waitTicks(40);
			checkCapture(gcCalled);

			context.runOnClient(mc -> mc.gui.setScreen(new StutterScreen(null, controller)));
			context.waitForScreen(StutterScreen.class);
			context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().report() != null, 400);
			context.waitTicks(3);
			StutterView view = context.computeOnClient(mc -> ((StutterScreen) mc.gui.screen()).shownView());
			StutterReport report = view.report();
			check(view.recording() && view.live(), "the live session's analysis: " + view);
			check(report.facts().explicitGcs() >= 1, "the explicit GC counted: " + report.facts());
			check(report.facts().gcOffsetMs() != null, "GC timing calibrated: " + report.facts());
			RigTune.LOGGER.info("StutterGameTest: live report: {} frames, {} spikes, phase timing {}, GC offset {} ms, collector {}", report.frames(),
					report.spikes().total(), report.phaseTiming() ? "ok" : "unavailable", report.facts().gcOffsetMs(), report.collector());
			for (int[] size : SIZES) {
				resize(context, size[0], size[1], size[2]);
				checkLayout(context, "stutter " + size[0] + "x" + size[1] + "@" + size[2]);
				context.takeScreenshot("stutter-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			}
			resize(context, 854, 480, 0);

			// Stop: the listener goes, the sampler stops, the buffers are released, the session is saved.
			context.runOnClient(mc -> controller.setStutterMonitor(false));
			context.waitTicks(5);
			check(StutterMonitor.session() == null && !StutterMonitor.active(), "no capture after Stop");
			check(!StutterHooks.gcListenerActive(), "the GC listener was removed");
			check(!StutterHooks.samplerRunning() && !samplerThread(), "no thread named RigTune stutter sampler");
			check(StutterMonitor.retainedBytes() == 0, "the buffers were released");
			waitForSessions(context, configDir, before + 1);

			// On again in the same world, then leave: leaving saves that session too.
			context.runOnClient(mc -> {
				mc.gui.setScreen(null);
				controller.setStutterMonitor(true);
			});
			context.waitTicks(40);
			check(StutterMonitor.session() != null, "a new session in the same world");
		}
		context.waitForScreen(TitleScreen.class);
		context.waitTicks(5);
		check(StutterMonitor.session() == null && StutterMonitor.retainedBytes() == 0, "leaving the world ended the session and released the buffers");
		check(!samplerThread(), "no sampler thread after leaving");
		waitForSessions(context, configDir, before + 2);
		List<StutterReport> sessions = new StutterStore(configDir).sessions();
		check(StutterReport.MONITOR.equals(sessions.getLast().source()), "the last saved session is the monitor's");

		// With no world the screen shows the saved summary.
		context.runOnClient(mc -> mc.gui.setScreen(new StutterScreen(new TitleScreen(), controller)));
		context.waitForScreen(StutterScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof StutterScreen s && s.shownView().report() != null, 200);
		check(context.computeOnClient(mc -> !((StutterScreen) mc.gui.screen()).shownView().live()), "the saved summary, not a live one");
		context.takeScreenshot("stutter-saved");
		context.runOnClient(mc -> {
			controller.setStutterMonitor(false);
			mc.gui.setScreen(new TitleScreen());
		});
		context.waitForScreen(TitleScreen.class);
	}

	// AC5.7's capture checks, on the live rings.
	private static void checkCapture(long gcCalled) {
		StutterRings rings = StutterMonitor.rings();
		StutterMonitor.Capture session = StutterMonitor.session();
		check(rings != null && session != null, "capturing");
		StutterRings.Snapshot s = rings.snapshot();
		FrameRing.Snapshot frames = session.snapshot();
		GcClock.Calibration clock = s.clock();
		check(clock.calibrated(), "the GC clock is calibrated");
		boolean explicit = false;
		boolean overlaps = false;
		long[] gc = s.gc();
		for (int i = 0; i + StutterRings.GC_STRIDE <= gc.length; i += StutterRings.GC_STRIDE) {
			int flags = (int) gc[i + StutterRings.G_FLAGS];
			if ((flags & GcKind.EXPLICIT) == 0 || !GcKind.pause(flags) || gc[i + StutterRings.G_RECEIVED] < gcCalled) {
				continue;
			}
			explicit = true;
			long start = clock.pauseStart(gc[i + StutterRings.G_START_MS]);
			long end = clock.pauseEnd(gc[i + StutterRings.G_END_MS]);
			long[] ends = frames.ends();
			for (int f = 1; f < ends.length && !overlaps; f++) {
				overlaps = start < (ends[f] & ~1L) && end > (ends[f - 1] & ~1L);
			}
			RigTune.LOGGER.info("StutterGameTest: System.gc() pause mapped to [{} .. {}] ns after the call (offset {} ms), overlaps a frame: {}",
					start - gcCalled, end - gcCalled, String.format(Locale.ROOT, "%.2f", clock.offsetMs()), overlaps);
		}
		check(explicit, "a GC event with cause System.gc()");
		check(overlaps, "the System.gc() pause overlaps a recorded frame interval");
		boolean begin = false;
		boolean ended = false;
		long[] events = s.events();
		for (int i = 0; i + StutterRings.EVENT_STRIDE <= events.length; i += StutterRings.EVENT_STRIDE) {
			if (events[i] == StutterRings.SAVE_BEGIN) {
				begin = true;
			} else if (events[i] == StutterRings.SAVE_END && begin) {
				ended = true;
			}
		}
		check(begin && ended, "a save window with a begin and an end");
		RigTune.LOGGER.info("StutterGameTest: capture checks passed ({} frames, {} GC records, phase timers {})", frames.frames(),
				gc.length / StutterRings.GC_STRIDE, StutterMonitor.phaseTiming() ? "complete" : "incomplete");
	}

	private static void waitForSessions(ClientGameTestContext context, Path configDir, int count) {
		context.waitFor(mc -> new StutterStore(configDir).sessions().size() >= Math.min(count, StutterStore.MAX_SESSIONS), 400);
	}

	private static boolean samplerThread() {
		return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && "RigTune stutter sampler".equals(t.getName()));
	}

	private static @Nullable Button findButton(Screen screen, String key) {
		for (var child : screen.children()) {
			if (child instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
				return b;
			}
		}
		return null;
	}

	// Every button inside the screen, none overlapping, each label readable, the list above the buttons.
	private static void checkLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			List<AbstractWidget> buttons = new ArrayList<>();
			for (var child : screen.children()) {
				if (child instanceof Button b && b.visible) {
					buttons.add(b);
				}
			}
			check(buttons.size() == 5, name + ": the 5 buttons (Start or Stop, Pause, Clear, Copy summary, Done)");
			for (AbstractWidget w : buttons) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getX() + w.getWidth() <= screen.width && w.getY() + w.getHeight() <= screen.height,
						name + ": " + w.getMessage().getString() + " inside the screen");
				check(mc.font.width(w.getMessage()) <= w.getWidth() - 4, name + ": " + w.getMessage().getString() + " fits its button");
				for (AbstractWidget o : buttons) {
					if (o != w) {
						check(w.getX() >= o.getX() + o.getWidth() || o.getX() >= w.getX() + w.getWidth() || w.getY() >= o.getY() + o.getHeight()
								|| o.getY() >= w.getY() + w.getHeight(), name + ": " + w.getMessage().getString() + " overlaps " + o.getMessage().getString());
					}
				}
			}
			StutterScreen stutter = (StutterScreen) screen;
			int top = buttons.stream().mapToInt(AbstractWidget::getY).min().orElse(screen.height);
			check(stutter.list() != null && stutter.list().getY() + stutter.list().getHeight() <= top, name + ": the list ends above the buttons");
		});
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

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("StutterGameTest: " + what);
		}
	}
}
