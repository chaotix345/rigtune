package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.JvmProbe;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.client.ui.JvmScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.jvm.JvmCollector;
import io.github.chaotix345.rigtune.core.jvm.JvmFacts;
import io.github.chaotix345.rigtune.core.jvm.JvmFinding;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.jvm.JvmSnapshot;
import io.github.chaotix345.rigtune.core.jvm.VmOptions;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// docs/v0.4/SPEC.md 6 (AC6.4; AC6.6's detection part when run locally with -PgametestJvmArgs). With the network off (X1):
// (1) the running JVM through the real probe: in CI plain G1 chosen by Java, no argument notes, no jvm- advice; run with
// "-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x" the notes come from the running JVM. (2) An injected snapshot
// (the probe's test seam) with those arguments and brand theseus through the real controller: the facts reach the
// profile, the screen lists the notes, and the jvm-* advice (the bundled rules' once they carry it; a canned report
// otherwise) shows "Found in your Java arguments" with the Modrinth App's Java-arguments steps. Screenshots at 3 sizes.
public class JvmGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
	private static final String MODRINTH_STEPS = "In the Modrinth App: this instance → Instance settings (gear) → Sync overrides → turn on Custom Java arguments";
	private static final JvmFinding AIKAR_NOTE = new JvmFinding(JvmFinding.Kind.SERVER_SET, "-Dusing.aikars.flags");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		RigTuneController real = RigTuneClient.controller();
		Path configDir = FabricLoader.getInstance().getConfigDir();
		String startBrand = System.getProperty(LauncherSignals.BRAND);
		boolean networkWas = context.computeOnClient(mc -> ClientSettings.shared(configDir).networkEnabled);
		setNetwork(context, real, configDir, false);
		Throwable failure = null;
		try {
			runningJvm(context, real);
			injected(context, real);
		} catch (RuntimeException | Error e) {
			failure = e;
			throw e;
		} finally {
			// Later game-test classes get the running JVM, the start-up launcher and the network setting back.
			try {
				JvmProbe.inject(null);
				if (startBrand == null) {
					System.clearProperty(LauncherSignals.BRAND);
				} else {
					System.setProperty(LauncherSignals.BRAND, startBrand);
				}
				context.runOnClient(mc -> LauncherProbe.reset());
				setNetwork(context, real, configDir, networkWas);
				context.waitFor(mc -> real.report() != null && real.jvmReport().javaVersion() != null
						&& (runningHasAikar() || !real.jvmReport().findings().contains(AIKAR_NOTE)), 1200);
			} catch (RuntimeException | AssertionError e) {
				if (failure == null) {
					throw e;
				}
				failure.addSuppressed(e);
			}
		}
		resize(context, 854, 480, 2);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		RigTune.LOGGER.info("JvmGameTest: passed");
	}

	// settingsChanged() rescans, so the report (and the probe's facts in it) follow.
	private static void setNetwork(ClientGameTestContext context, RigTuneController real, Path configDir, boolean on) {
		context.runOnClient(mc -> {
			ClientSettings settings = ClientSettings.shared(configDir);
			settings.networkEnabled = on;
			settings.save(configDir);
			real.settingsChanged();
		});
		context.waitFor(mc -> ClientSettings.load(configDir).networkEnabled == on && real.report() != null, 1200);
		RigTune.LOGGER.info("JvmGameTest: network {}", on ? "on" : "off");
	}

	private static boolean runningHasAikar() {
		return ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(a -> a.startsWith("-Dusing.aikars.flags"));
	}

	// (1) The running JVM, read by the real probe.
	private static void runningJvm(ClientGameTestContext context, RigTuneController real) {
		context.waitFor(mc -> real.report() != null && real.jvmReport().javaVersion() != null, 1200);
		JvmReport running = real.jvmReport();
		RigTune.LOGGER.info("JvmGameTest: running JVM: Java {} ({}), {} typed={}, heap {} MB, notes {}, facts {}", running.javaVersion(), running.vendor(),
				running.collectorName(), running.collectorTyped(), running.maxHeapMb(), running.findings(), running.facts());
		check(running.available(), "the HotSpot check ran: " + running);
		check(real.report().hardware().flags().contains(JvmFacts.PROBED), "jvm-probed reached the profile: " + real.report().hardware().flags());
		boolean zGenerational = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+ZGenerational");
		if (zGenerational) {
			// AC6.6 (local run with -PgametestJvmArgs): the notes come from the running JVM.
			check(running.collector() == JvmCollector.ZGC && running.collectorTyped(), "typed ZGC: " + running);
			check(running.findings().contains(new JvmFinding(JvmFinding.Kind.IGNORED, "-XX:+ZGenerational")), "ZGenerational ignored: " + running.findings());
			check(!runningHasAikar() || running.findings().contains(AIKAR_NOTE), "the Aikar marker: " + running.findings());
			RigTune.LOGGER.info("JvmGameTest: AC6.6 detection from the running JVM passed");
		} else if (System.getenv("CI") != null) {
			check(running.collector() == JvmCollector.G1 && !running.collectorTyped(), "CI: G1 chosen by Java: " + running);
			check(running.findings().isEmpty(), "CI: no argument notes: " + running.findings());
			List<String> jvmAdvice = real.report().recommendations().stream().filter(LauncherAdvice::isJvmAdvice).map(Recommendation::id).toList();
			check(jvmAdvice.isEmpty(), "CI: Java's defaults fire no jvm- advice: " + jvmAdvice);
		}
		openJvm(context, real);
		atEverySize(context, "jvm-running");
		context.runOnClient(mc -> {
			JvmScreen screen = (JvmScreen) mc.gui.screen();
			List<String> rows = screen.rowText();
			RigTune.LOGGER.info("JvmGameTest: running rows {}", rows);
			check(rows.stream().anyMatch(r -> r.startsWith("Java " + running.javaVersion())), "the Java line: " + rows);
			check(rows.stream().anyMatch(r -> r.startsWith("Garbage collector: ")), "the collector line: " + rows);
			check(!running.findings().isEmpty() || rows.contains("Nothing to note about your Java arguments."), "no notes: " + rows);
		});
		String shared = context.computeOnClient(mc -> real.shareReport());
		check(shared.contains("\n- Java: " + running.javaVersion()), "the share report's Java line: " + shared);
	}

	// (2) An injected snapshot and the Modrinth App's brand, through the real controller.
	private static void injected(ClientGameTestContext context, RigTuneController real) {
		JvmProbe.inject(snapshot());
		System.setProperty(LauncherSignals.BRAND, "theseus");
		context.runOnClient(mc -> {
			LauncherProbe.reset();
			real.rescan();
		});
		context.waitFor(mc -> real.report() != null && real.launcher().launcher() == Launcher.MODRINTH_APP && real.jvmReport().collector() == JvmCollector.ZGC
				&& real.report().hardware().flags().contains("jvm-gc-zgc"), 1200);
		JvmReport jvm = real.jvmReport();
		check(jvm.findings().equals(List.of(new JvmFinding(JvmFinding.Kind.IGNORED, "-XX:+ZGenerational"), AIKAR_NOTE)), "the injected notes: " + jvm.findings());
		Set<String> flags = real.report().hardware().flags();
		check(flags.containsAll(Set.of(JvmFacts.PROBED, "jvm-gc-zgc", JvmFacts.GC_TYPED, JvmFacts.IGNORED_FLAGS, JvmFacts.SERVER_FLAGS)), "facts: " + flags);

		// The bundled rules' jvm-* advice once the rules carry it; a canned report until then.
		List<Recommendation> bundled = real.report().recommendations().stream().filter(LauncherAdvice::isJvmAdvice).toList();
		RigTune.LOGGER.info("JvmGameTest: the bundled rules fire {}", bundled.stream().map(Recommendation::id).toList());
		boolean fromRules = bundled.stream().anyMatch(r -> r.id().equals("advice:jvm-ignored-flags"))
				&& bundled.stream().anyMatch(r -> r.id().equals("advice:jvm-server-flags"));
		RigTuneController shown = fromRules ? real : new CannedController(real, cannedReport(real.report()));

		openMain(context, shown);
		resize(context, 854, 480, 2);
		context.takeScreenshot("jvm-main-list-854x480-scale2");
		context.runOnClient(mc -> {
			List<String> lines = ((RigTuneScreen) mc.gui.screen()).launcherLines().stream().map(Component::getString).toList();
			RigTune.LOGGER.info("JvmGameTest: main list lines {}", lines);
			check(lines.stream().anyMatch(l -> l.startsWith("Found in your Java arguments: -XX:+ZGenerational.") && l.contains(MODRINTH_STEPS)),
					"the found-flags line and the Modrinth App steps under jvm-ignored-flags: " + lines);
			check(lines.stream().anyMatch(l -> l.startsWith("Found in your Java arguments: -Dusing.aikars.flags.")), "under jvm-server-flags: " + lines);
		});

		openJvm(context, shown);
		atEverySize(context, "jvm-findings");
		context.runOnClient(mc -> {
			JvmScreen screen = (JvmScreen) mc.gui.screen();
			List<String> rows = screen.rowText();
			RigTune.LOGGER.info("JvmGameTest: findings rows {}", rows);
			check(rows.contains("Garbage collector: ZGC, set in your Java arguments"), "typed ZGC: " + rows);
			check(rows.stream().anyMatch(r -> r.startsWith("-XX:+ZGenerational: this Java doesn't have this option")), "the ZGenerational note: " + rows);
			check(rows.stream().anyMatch(r -> r.startsWith("-Dusing.aikars.flags: part of Paper's server flags")), "the Aikar note: " + rows);
			check(rows.stream().anyMatch(r -> r.startsWith("Found in your Java arguments: -XX:+ZGenerational.") && r.contains(MODRINTH_STEPS)),
					"the advice's found-flags line and steps: " + rows);
			for (String row : rows) {
				check(!row.contains("=") && !row.contains("/"), "no argument value or path on screen: " + row);
			}
		});
		String shared = context.computeOnClient(mc -> real.shareReport());
		check(shared.contains("ZGC, 2 argument notes\n") && !shared.contains("ZGenerational") && !shared.contains("aikars"),
				"the share report counts the notes and names none: " + shared);
	}

	// -XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x on a HotSpot 25 that no longer knows ZGenerational.
	private static JvmSnapshot snapshot() {
		VmOptions vm = name -> switch (name) {
			case "UseZGC" -> VmOptions.Lookup.found("true", VmOptions.Origin.VM_CREATION);
			case "ZGenerational" -> VmOptions.Lookup.MISSING;
			default -> VmOptions.Lookup.found("false", VmOptions.Origin.DEFAULT);
		};
		return new JvmSnapshot(List.of("-Xmx4G", "-XX:+UseZGC", "-XX:+ZGenerational", "-Dusing.aikars.flags=x"), vm,
				List.of("ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses"), 4L << 30, 256L << 20, "25.0.3", "Azul Systems, Inc.");
	}

	// The real report plus two jvm-* advice entries (until the bundled rules carry them).
	private static Report cannedReport(Report base) {
		List<Recommendation> recs = new ArrayList<>(base.recommendations());
		recs.add(new Recommendation("advice:jvm-ignored-flags", Category.ADVICE, Impact.LOW, "Remove Java arguments Java ignores",
				"Java ignores some of your Java arguments; ZGenerational was removed in Java 24, and a future Java won't start with it.", new Action.None(), false));
		recs.add(new Recommendation("advice:jvm-server-flags", Category.ADVICE, Impact.LOW, "Paper's server flags keep all of Minecraft's memory reserved",
				"In RigTune's tests they gave the same FPS and 1% lows as Java's defaults.", new Action.None(), false));
		return new Report(base.hardware(), base.gpuClass(), base.tier(), base.goal(), recs, base.rulesRevision(), base.rulesSource(), base.online(),
				base.createdAt(), base.tierBasis());
	}

	private static void openMain(ClientGameTestContext context, RigTuneController controller) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), controller)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
	}

	private static void openJvm(ClientGameTestContext context, RigTuneController controller) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new RigTuneScreen(new TitleScreen(), controller), controller)));
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> ((ToolsScreen) mc.gui.screen()).openJvm());
		context.waitForScreen(JvmScreen.class);
		context.waitTicks(3);
	}

	private static void atEverySize(ClientGameTestContext context, String name) {
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkLayout(context, name + " " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot(name + "-" + size[0] + "x" + size[1] + "-scale" + size[2]);
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

	// Every widget inside the screen, and on the JVM screen every row's text inside the list (X7).
	private static void checkLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			for (AbstractWidget w : Screens.getWidgets(screen)) {
				if (w.visible) {
					check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
							name + ": " + w.getMessage().getString() + " outside " + screen.width + "x" + screen.height);
				}
			}
			if (screen instanceof JvmScreen jvm) {
				check(jvm.rowsFit(), name + ": a row's text runs past the list");
			}
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// A fixed report with the real controller's launcher and JVM report.
	private static final class CannedController implements RigTuneController {
		private final RigTuneController real;
		private final Report report;
		private Goal goal = Goal.BALANCED;

		CannedController(RigTuneController real, Report report) {
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

		@Override
		public JvmReport jvmReport() {
			return real.jvmReport();
		}
	}
}
