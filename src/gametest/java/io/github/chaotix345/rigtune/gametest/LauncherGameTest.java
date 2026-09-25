package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

// WS-C (docs/v0.3/SPEC.md item 5, AC5.3 with C-L1): the header's memory line and the launcher line under the ram-*
// advice, screenshotted at the three reference sizes without a launcher brand (as the test run starts the game; the
// start-up values are logged so the CI latest.log shows it) and with minecraft.launcher.brand=theseus, which goes
// through the real probe (the property is set, the probe's once-per-session detection is reset, and the real controller
// rescans).
public class LauncherGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{854, 480, 2}, {1280, 720, 3}, {1280, 720, 2}};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		RigTuneController real = RigTuneClient.controller();
		context.waitFor(mc -> real.report() != null, 1200);
		String startBrand = System.getProperty(LauncherSignals.BRAND);
		RigTune.LOGGER.info("LauncherGameTest: at start {}={}; {} set: {}; {} set: {}; INST_ID set: {}; INST_NAME set: {}; detected: {}",
				LauncherSignals.BRAND, startBrand, LauncherSignals.PRISM_INSTANCE, System.getProperty(LauncherSignals.PRISM_INSTANCE) != null,
				LauncherSignals.MULTIMC_INSTANCE, System.getProperty(LauncherSignals.MULTIMC_INSTANCE) != null,
				System.getenv(LauncherSignals.INST_ID) != null, System.getenv(LauncherSignals.INST_NAME) != null, real.launcher());
		// In CI this confirms the production run passes no brand (C-L1); a developer's own environment may carry one.
		boolean noLauncher = !real.launcher().known();
		check(noLauncher || System.getenv("CI") == null, "the CI run has no launcher signal: " + real.launcher() + " (brand " + startBrand + ")");

		Report report = ramAdviceReport();
		long ramAdvice = report.recommendations().stream().filter(LauncherAdvice::isRamAdvice).count();
		check(ramAdvice >= 2, "the fixture report has ram-* advice: " + report.recommendations().stream().map(Recommendation::id).toList());
		RigTuneController stub = new RamAdviceController(real, report);

		if (noLauncher) {
			open(context, stub);
			atEverySize(context, "launcher-none");
			context.runOnClient(mc -> {
				RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
				check(screen.launcherLines().isEmpty(), "no launcher line without a launcher: " + screen.launcherLines());
				check(headerHas(screen, "rigtune.header.cpu") && !headerHas(screen, "rigtune.launcher.header.memory"), "the header as before");
			});
			check(!context.computeOnClient(mc -> real.shareReport()).contains("Launcher:"), "no launcher in the share report");
		} else {
			RigTune.LOGGER.warn("LauncherGameTest: this run already has a launcher signal ({}); the no-launcher screenshots are skipped", real.launcher());
		}

		Throwable failure = null;
		try {
			System.setProperty(LauncherSignals.BRAND, "theseus");
			redetect(context, real);
			context.waitFor(mc -> real.launcher().launcher() == Launcher.MODRINTH_APP && real.report() != null, 1200);
			RigTune.LOGGER.info("LauncherGameTest: with {}=theseus detected {}", LauncherSignals.BRAND, real.launcher());

			open(context, stub);
			atEverySize(context, "launcher-modrinth");
			context.runOnClient(mc -> {
				RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
				List<Component> lines = screen.launcherLines();
				check(lines.size() == ramAdvice, "one launcher line per ram-* advice: " + lines);
				for (Component line : lines) {
					check(line.getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.launcher.advice")
							&& key(t.getArgs()[0]).equals("rigtune.launcher.name.modrinth_app")
							&& key(t.getArgs()[1]).equals("rigtune.launcher.steps.modrinth_app"), "Modrinth App steps: " + line);
				}
				check(headerHas(screen, "rigtune.launcher.header.memory") && !headerHas(screen, "rigtune.header.cpu"), "memory line names the launcher");
				RigTune.LOGGER.info("LauncherGameTest: header {}; line {}", screen.headerLines().stream().map(Component::getString).toList(),
						lines.getFirst().getString());
			});
			String shared = context.computeOnClient(mc -> real.shareReport());
			check(shared.contains("\n- Launcher: Modrinth App\n"), "the share report names the launcher: " + shared);
		} catch (RuntimeException | Error e) {
			failure = e;
			throw e;
		} finally {
			// Later game-test classes get the start-up launcher back; a failure here doesn't hide the one above.
			try {
				if (startBrand == null) {
					System.clearProperty(LauncherSignals.BRAND);
				} else {
					System.setProperty(LauncherSignals.BRAND, startBrand);
				}
				redetect(context, real);
				context.waitFor(mc -> real.launcher().known() != noLauncher && real.report() != null, 1200);
				context.waitTicks(40);
				context.waitFor(mc -> real.report() != null, 1200);
			} catch (RuntimeException | AssertionError e) {
				if (failure == null) {
					throw e;
				}
				failure.addSuppressed(e);
			}
		}

		resize(context, 854, 480, 0);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	// The bundled rules' recommendations for 16 GB of RAM, a 2 GB heap and Distant Horizons (ram-low and
	// ram-distant-horizons), so the screenshots don't depend on the CI machine's memory.
	private static Report ramAdviceReport() {
		HardwareProfile hw = new HardwareProfile(new CpuInfo("AMD Ryzen 7 7800X3D 8-Core Processor", 8, 16, 5050),
				new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", "25.9.1", GraphicsBackend.OPENGL, 16368),
				16_384, 2048, new DisplayInfo(2560, 1440, 165, true), false, false, "Windows 11", "26.2", Set.of());
		List<InstalledMod> mods = List.of(mod("sodium"), mod("distanthorizons"));
		return Recommender.recommend(RulesLoader.loadBundled(), hw, mods, new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED);
	}

	private static InstalledMod mod(String id) {
		return new InstalledMod(id, id, "1.0.0", Path.of("mods", id + ".jar"), null);
	}

	private static void redetect(ClientGameTestContext context, RigTuneController real) {
		context.runOnClient(mc -> {
			LauncherProbe.reset();
			real.rescan();
		});
	}

	private static String key(Object arg) {
		return arg instanceof Component c && c.getContents() instanceof TranslatableContents t ? t.getKey() : String.valueOf(arg);
	}

	private static boolean headerHas(RigTuneScreen screen, String key) {
		return screen.headerLines().stream().anyMatch(line -> line.getContents() instanceof TranslatableContents t && t.getKey().equals(key));
	}

	private static void open(ClientGameTestContext context, RigTuneController controller) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), controller)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
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

	private static void checkLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			for (AbstractWidget w : Screens.getWidgets(screen)) {
				if (w.visible) {
					check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
							name + ": " + w.getMessage().getString() + " outside " + screen.width + "x" + screen.height);
				}
				// The extra header line still leaves room for a ram-* advice with its launcher line.
				if (w instanceof AbstractSelectionList<?> list) {
					check(list.getHeight() >= 60, name + ": the list is only " + list.getHeight() + " high");
				}
			}
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// The fixture report with the real controller's launcher.
	private static final class RamAdviceController implements RigTuneController {
		private final RigTuneController real;
		private final Report report;
		private Goal goal = Goal.BALANCED;

		RamAdviceController(RigTuneController real, Report report) {
			this.real = real;
			this.report = report;
		}

		@Override
		public @Nullable Report report() {
			return report;
		}

		@Override
		public Goal goal() {
			return goal;
		}

		@Override
		public void setGoal(Goal goal) {
			this.goal = goal;
		}

		@Override
		public Component apply(List<Recommendation> selected) {
			return Component.translatable("rigtune.status.nothing");
		}

		@Override
		public void startBenchmark() {
		}

		@Override
		public void rescan() {
		}

		@Override
		public LauncherInfo launcher() {
			return real.launcher();
		}
	}
}
