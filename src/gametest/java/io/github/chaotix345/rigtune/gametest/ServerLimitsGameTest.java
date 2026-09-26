package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.notice.ServerLimitNoticeSource;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

// docs/v0.4/SPEC.md 8 (AC8.4; W-L2: TestWorldBuilder.createServer(Properties) is in fabric-client-gametest-api-v1 6.0.2
// for 26.2 and 6.0.7 for 26.3, checked with javap): a dedicated server with view-distance=6 and simulation-distance=5,
// then connect(): the notice reads 6, the RD recommendation is <= 6 (capped where the rules alone would go higher, W-H1),
// BenchmarkController.maxRenderDistance <= 6, server-limits.json has one hashed entry and no "localhost"/"127.0.0.1";
// after disconnect the notice is gone; a singleplayer world shows none. Screenshots at 3 sizes. Runs with the network off
// (X1): nothing here needs it.
public class ServerLimitsGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		if (!(RigTuneClient.controller() instanceof RealController real)) {
			throw new AssertionError("ServerLimitsGameTest needs the real controller");
		}
		Path configDir = FabricLoader.getInstance().getConfigDir();
		int rdBefore = context.computeOnClient(mc -> mc.options.renderDistance().get());
		try {
			setNetwork(context, configDir, real, false);
			context.runOnClient(mc -> mc.options.renderDistance().set(5));
			deleteStore(real);
			singleplayer(context, real);
			dedicatedServer(context, real);
			afterDisconnect(context, real);
			RigTune.LOGGER.info("ServerLimitsGameTest: dedicated server limits, notice, cap and store checked; singleplayer shows none");
		} finally {
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(rdBefore);
				mc.gui.setScreen(new TitleScreen());
			});
			setNetwork(context, configDir, real, true);
			resize(context, 854, 480, 0);
		}
	}

	private static void singleplayer(ClientGameTestContext context, RealController real) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			context.waitFor(mc -> real.serverLimits() != null, 200);
			ServerLimits limits = real.serverLimits();
			check(limits.kind() == ServerLimits.Kind.SINGLEPLAYER, "the player's own world is SINGLEPLAYER: " + limits);
			openRigTune(context, real);
			check(serverNotice(context, real) == null, "no server notice in singleplayer: " + notices(context, real));
			context.takeScreenshot("server-limits-singleplayer");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
		context.waitFor(mc -> real.serverLimits() == null, 200);
	}

	private static void dedicatedServer(ClientGameTestContext context, RealController real) {
		Properties properties = new Properties();
		properties.setProperty("view-distance", "6");
		properties.setProperty("simulation-distance", "5");
		// The test server starts in the game-test run directory (wiped per run), where vanilla's Eula reads eula.txt; the
		// harness writes server.properties there the same way.
		Path eula = Path.of("eula.txt");
		write(eula, "eula=true
");
		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			context.waitFor(mc -> real.serverLimits() != null && real.serverLimits().viewDistance() == 6, 200);
			ServerLimits limits = real.serverLimits();
			check(limits.kind() == ServerLimits.Kind.REMOTE, "a dedicated server is REMOTE: " + limits);
			check(limits.simulationDistance() == 5, "simulation distance 5: " + limits);
			int benchmarkMax = context.computeOnClient(BenchmarkController::maxRenderDistance);
			check(benchmarkMax <= 6, "the benchmark never steps above the server's 6: " + benchmarkMax);

			checkCap(context, real);
			checkStore(context, real);

			openRigTune(context, real);
			for (int[] size : SIZES) {
				resize(context, size[0], size[1], size[2]);
				Notice notice = serverNotice(context, real);
				check(notice != null, "the server notice at " + name(size) + ": " + notices(context, real));
				check(notice.message().english().equals("The server limits view distance to 6 chunks"), notice.message().english());
				check(notice.detail().english().contains("Simulation distance on this server: 5 chunks"), notice.detail().english());
				Notice shown = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice());
				check(shown != null && shown.priority().ordinal() <= notice.priority().ordinal(), "the slot shows it (or a higher priority): " + shown);
				context.takeScreenshot("server-limits-" + name(size));
			}

			// W-H1: above the limit the notice explains what the player sees, and no increase is proposed.
			context.runOnClient(mc -> mc.options.renderDistance().set(10));
			rebuildAndWait(context, real);
			context.waitTicks(3);
			Notice above = serverNotice(context, real);
			check(above != null && above.message().english().equals("The server limits view distance to 6 chunks (you set 10)"), String.valueOf(above));
			check(above.detail().english().startsWith("You set 10; the server sends 6, so 6 is what you see."), above.detail().english());
			Action.SetSetting rd = renderDistance(real.report());
			check(rd == null || Integer.parseInt(rd.newValue()) <= 10, "no render-distance increase while above the limit: " + rd);
			context.takeScreenshot("server-limits-above-" + name(SIZES[2]));
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(5);
				mc.gui.setScreen(null);
			});
		}
	}

	// The report built with the live limit: RD 5 on a server sending 6, so an increase is capped at 6.
	private static void checkCap(ClientGameTestContext context, RealController real) {
		rebuildAndWait(context, real);
		Report report = real.report();
		Action.SetSetting capped = renderDistance(report);
		check(capped == null || Integer.parseInt(capped.newValue()) <= 6, "the RD recommendation is <= 6: " + capped);
		Report uncapped = context.computeOnClient(mc -> Recommender.recommend(real.rules(), real.hardwareProfile(), real.mods(), SettingsBridge.read(mc),
				OnlineData.offline(), real.goal(), real.modVersion(), Set.of()));
		Action.SetSetting wanted = renderDistance(uncapped);
		RigTune.LOGGER.info("ServerLimitsGameTest: RD recommendation {} (the rules alone: {})", capped, wanted);
		if (wanted != null && Integer.parseInt(wanted.newValue()) > 6) {
			check(capped != null && capped.newValue().equals("6"), "the rules' " + wanted.newValue() + " is capped to 6: " + capped);
			Recommendation rec = report.recommendations().stream().filter(r -> r.id().equals("set:vanilla.renderDistance")).findFirst().orElseThrow();
			check(rec.reason().endsWith("The server sends at most 6 chunks."), rec.reason());
		}
	}

	private static void checkStore(ClientGameTestContext context, RealController real) {
		Path file = real.serverLimitsTracker().storeFile();
		context.waitFor(mc -> Files.isRegularFile(file) && servers(file) == 1, 200);
		String text = read(file);
		check(!text.contains("localhost") && !text.contains("127.0.0.1"), "no plaintext address in server-limits.json: " + text);
		JsonObject entry = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("servers").entrySet().iterator().next().getValue().getAsJsonObject();
		check(entry.get("viewDistance").getAsInt() == 6 && entry.get("simulationDistance").getAsInt() == 5
				&& entry.get("kind").getAsString().equals("REMOTE"), "the entry: " + entry);
		RigTune.LOGGER.info("ServerLimitsGameTest: server-limits.json {}", text.replaceAll("\\s+", " "));
	}

	private static void afterDisconnect(ClientGameTestContext context, RealController real) {
		context.waitFor(mc -> real.serverLimits() == null, 200);
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		rebuildAndWait(context, real);
		context.waitTicks(3);
		check(serverNotice(context, real) == null, "the notice is gone after disconnect: " + notices(context, real));
		check(real.report().recommendations().stream().noneMatch(r -> r.reason().contains("The server sends")), "no server reason after disconnect");
		context.takeScreenshot("server-limits-after-disconnect");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	private static void openRigTune(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> RigTuneClient.open(mc.gui.screen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitFor(mc -> real.report() != null, 1200);
		context.waitTicks(3);
	}

	private static void rebuildAndWait(ClientGameTestContext context, RealController real) {
		Report before = context.computeOnClient(mc -> real.report());
		context.runOnClient(mc -> real.rebuild());
		context.waitFor(mc -> real.report() != null && real.report() != before, 1200);
	}

	// On the render thread, where the notice sources run.
	private static @Nullable Notice serverNotice(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> real.notices().stream().filter(n -> n.key().startsWith(ServerLimitNoticeSource.KEY_PREFIX))
				.findFirst().orElse(null));
	}

	private static String notices(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> real.notices().toString());
	}

	private static Action.@Nullable SetSetting renderDistance(Report report) {
		return report.recommendations().stream().map(Recommendation::action)
				.filter(a -> a instanceof Action.SetSetting s && s.key().equals("vanilla.renderDistance"))
				.map(Action.SetSetting.class::cast).findFirst().orElse(null);
	}

	private static int servers(Path file) {
		try {
			return JsonParser.parseString(read(file)).getAsJsonObject().getAsJsonObject("servers").size();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Could not read " + file, e);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.writeString(file, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Could not write " + file, e);
		}
	}

	private static void deleteStore(RealController real) {
		try {
			Files.deleteIfExists(real.serverLimitsTracker().storeFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void setNetwork(ClientGameTestContext context, Path configDir, RealController real, boolean on) {
		context.runOnClient(mc -> {
			ClientSettings settings = ClientSettings.shared(configDir);
			settings.networkEnabled = on;
			settings.save(configDir);
			real.settingsChanged();
		});
		context.waitFor(mc -> real.report() != null, 1200);
	}

	private static String name(int[] size) {
		return size[0] + "x" + size[1] + "-scale" + size[2];
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
}
