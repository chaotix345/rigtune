package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.realmsclient.dto.RealmsServer;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.notice.ServerLimitNoticeSource;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.server.ServerLimitsTracker;
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
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.realms.RealmsConnect;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

// docs/v0.5/SPEC.md 3d (AC3d.1, AC3d.2): a LAN guest of a dedicated server (online-mode=false, view-distance=6,
// simulation-distance=5, a free port) that vanilla's own LanServerPinger announces, joined from JoinMultiplayerScreen's
// LAN list through the Join button as a player does (NetworkServerEntry.join is what makes the ServerData a LAN one).
// No publishServer (its signature differs between 26.2 and 26.3, SPEC-17): the Open-to-LAN host's SINGLEPLAYER
// classification stays with ServerLimitsTracker's unit tests. Then a restart on another port with view-distance=4 and a
// rejoin: still one entry, "(was 6)". Then the Realms block: vanilla's RealmsConnect with a RealmsServer named
// "RigTune Realm test" against the same offline server (a REALM-typed ServerData, no Realms API call). Every class used
// here has the same signatures on 26.2 and 26.3 (javap, docs/v0.5/design/ws-e.md). RigTune's network is off (X1).
public class LanGuestGameTest implements FabricClientGameTest {
	private static final String MOTD = "RigTune LAN test";
	private static final String REALM = "RigTune Realm test";
	private static final int JOIN_TICKS = 1200;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, JOIN_TICKS);
		RealController real = V05TestContext.of(context).realController();
		int rdBefore = context.computeOnClient(mc -> mc.options.renderDistance().get());
		try {
			GameTestNet.set(context, real, false);
			context.runOnClient(mc -> mc.options.renderDistance().set(5));
			deleteStore(real);
			int first = freePort();
			String host;
			try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties(6, first))) {
				host = joinFromLanList(context, first);
				firstJoin(context, real, host, first);
				disconnect(context);
			}
			int second = freePort();
			while (second == first) {
				second = freePort();
			}
			try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties(4, second))) {
				context.runOnClient(mc -> mc.options.renderDistance().set(2));
				check(joinFromLanList(context, second).equals(host), "the same host after the restart");
				rejoin(context, real, host);
				disconnect(context);
				realm(context, real, host, second);
				disconnect(context);
			}
			RigTune.LOGGER.info("LanGuestGameTest: LAN guest (joined from the LAN list, restart, was 6) and the Realms block checked");
		} finally {
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(rdBefore);
				mc.gui.setScreen(new TitleScreen());
			});
			GameTestNet.set(context, real, true);
		}
	}

	private static Properties properties(int view, int port) {
		Properties properties = new Properties();
		properties.setProperty("online-mode", "false");
		properties.setProperty("view-distance", Integer.toString(view));
		properties.setProperty("simulation-distance", "5");
		// A free port, never 25565 (a server the player runs may hold it).
		properties.setProperty("server-port", Integer.toString(port));
		// The test server starts in the game-test run directory, where vanilla's Eula reads eula.txt.
		write(Path.of("eula.txt"), "eula=true\n");
		return properties;
	}

	// Vanilla's pinger announces the server as publishServer would (motd, port); the multiplayer screen's LAN detector
	// lists it; the player selects the entry and presses Join Server. Returns the host the guest connected to.
	private static String joinFromLanList(ClientGameTestContext context, int port) {
		LanServerPinger pinger;
		try {
			pinger = new LanServerPinger(MOTD, Integer.toString(port));
		} catch (IOException e) {
			throw new AssertionError("LanServerPinger could not open its socket", e);
		}
		pinger.start();
		try {
			context.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
			context.waitForScreen(JoinMultiplayerScreen.class);
			context.waitFor(mc -> lanEntry(mc) != null, 600);
			context.runOnClient(mc -> list(mc).setSelected(lanEntry(mc)));
			screenshot(context, "lan-guest-list-" + port);
			context.clickScreenButton("selectServer.select");
			waitForWorld(context);
		} finally {
			pinger.interrupt();
		}
		ServerData data = context.computeOnClient(Minecraft::getCurrentServer);
		check(data != null && data.isLan(), "getCurrentServer().isLan(): " + (data == null ? null : data.ip));
		ServerAddress address = ServerAddress.parseString(data.ip);
		check(address.getPort() == port, "the LAN entry is this server's (" + port + "): " + data.ip);
		RigTune.LOGGER.info("LanGuestGameTest: joined {} ('{}') from the LAN list", data.ip, data.name);
		return address.getHost();
	}

	private static void firstJoin(ClientGameTestContext context, RealController real, String host, int port) {
		ServerLimitsTracker.Live live = waitForLimits(context, real, 6);
		check(live.limits().kind() == ServerLimits.Kind.LAN_GUEST && live.limits().simulationDistance() == 5, "LAN_GUEST, 6/5: " + live);
		check(("lan:" + host).equals(live.address()), "remembered by host only: " + live.address());
		checkCap(context, real, 6);
		Map.Entry<String, JsonObject> entry = onlyEntry(context, real);
		check(entry.getKey().equals(key(salt(real), "lan:" + host)), "the key is HMAC(salt, lan:" + host + "): " + entry.getKey());
		check(entry.getValue().get("kind").getAsString().equals("LAN_GUEST") && entry.getValue().get("viewDistance").getAsInt() == 6, "the entry: " + entry);
		checkNoPlaintext(real, host, port);
		Notice notice = openWithNotice(context, real);
		check(notice.message().english().equals("The server limits view distance to 6 chunks"), notice.message().english());
		screenshot(context, "lan-guest-notice");
		context.runOnClient(mc -> mc.gui.setScreen(null));
	}

	// The same host on a new port sends 4: still one entry (the host is the key), and the notice says it was 6.
	private static void rejoin(ClientGameTestContext context, RealController real, String host) {
		waitForLimits(context, real, 4);
		context.waitFor(mc -> {
			ServerLimitsTracker.Live live = real.serverLimitsTracker().liveState();
			return live != null && Integer.valueOf(6).equals(live.wasViewDistance());
		}, 200);
		check(real.serverLimits().kind() == ServerLimits.Kind.LAN_GUEST, "still LAN_GUEST: " + real.serverLimits());
		checkCap(context, real, 4);
		Map.Entry<String, JsonObject> entry = onlyEntry(context, real);
		check(entry.getKey().equals(key(salt(real), "lan:" + host)) && entry.getValue().get("viewDistance").getAsInt() == 4, "the one entry, now 4: " + entry);
		Notice notice = openWithNotice(context, real);
		check(notice.message().english().equals("The server limits view distance to 4 chunks"), notice.message().english());
		check(notice.detail() != null && notice.detail().english().contains("This server's limit changed since last time (was 6)."), String.valueOf(notice.detail()));
		screenshot(context, "lan-guest-was-6");
		context.runOnClient(mc -> mc.gui.setScreen(null));
	}

	// AC3d.2: a Realms-typed connection (RealmsServer.toServerData) to the same offline server, ticked as vanilla's
	// connect task screen does until the world loads.
	private static void realm(ClientGameTestContext context, RealController real, String host, int port) {
		RealmsConnect[] connect = new RealmsConnect[1];
		context.runOnClient(mc -> {
			RealmsServer server = new RealmsServer();
			server.name = REALM;
			connect[0] = new RealmsConnect(new TitleScreen());
			connect[0].connect(server, ServerAddress.parseString(host + ":" + port));
		});
		for (int tick = 0; tick < JOIN_TICKS && !context.computeOnClient(LanGuestGameTest::inWorld); tick++) {
			context.runOnClient(mc -> connect[0].tick());
			context.waitTick();
		}
		waitForWorld(context);
		ServerData data = context.computeOnClient(Minecraft::getCurrentServer);
		check(data != null && data.isRealm() && REALM.equals(data.name), "getCurrentServer().isRealm(): " + (data == null ? null : data.name));
		ServerLimitsTracker.Live live = waitForLimits(context, real, 4);
		check(live.limits().kind() == ServerLimits.Kind.REALM, "REALM: " + live);
		check(("realm:" + REALM).equals(live.address()), "remembered by world name: " + live.address());
		context.waitFor(mc -> servers(real.serverLimitsTracker().storeFile()) == 2, 200);
		JsonObject realm = servers(real).getAsJsonObject(key(salt(real), "realm:" + REALM));
		check(realm != null && realm.get("kind").getAsString().equals("REALM") && realm.get("viewDistance").getAsInt() == 4, "the Realm's entry: " + realm);
		check(!read(real.serverLimitsTracker().storeFile()).contains(REALM), "no plaintext world name in server-limits.json");
		Notice notice = openWithNotice(context, real);
		check(notice.message().english().startsWith("The server limits view distance to 4 chunks"), notice.message().english());
		screenshot(context, "realm-notice");
		context.runOnClient(mc -> mc.gui.setScreen(null));
	}

	private static ServerLimitsTracker.Live waitForLimits(ClientGameTestContext context, RealController real, int view) {
		context.waitFor(mc -> real.serverLimits() != null && real.serverLimits().viewDistance() == view, 200);
		return real.serverLimitsTracker().liveState();
	}

	private static void waitForWorld(ClientGameTestContext context) {
		context.waitFor(LanGuestGameTest::inWorld, JOIN_TICKS);
		context.waitTicks(20);
	}

	private static boolean inWorld(Minecraft mc) {
		return mc.level != null && mc.player != null && mc.gui.screen() == null;
	}

	// As the fabric API's own connection close does: the guest leaves, the saving screen goes, back to the title.
	private static void disconnect(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (mc.level != null) {
				mc.level.disconnect(Component.literal("Disconnecting"));
			}
			mc.disconnectWithSavingScreen();
		});
		context.waitFor(mc -> mc.level == null, JOIN_TICKS);
		context.waitTicks(2);
		context.setScreen(TitleScreen::new);
	}

	private static @Nullable ServerSelectionList list(Minecraft mc) {
		return mc.gui.screen() instanceof JoinMultiplayerScreen screen ? screen.children().stream()
				.filter(ServerSelectionList.class::isInstance).map(ServerSelectionList.class::cast).findFirst().orElse(null) : null;
	}

	private static ServerSelectionList.@Nullable NetworkServerEntry lanEntry(Minecraft mc) {
		ServerSelectionList list = list(mc);
		return list == null ? null : list.children().stream().filter(ServerSelectionList.NetworkServerEntry.class::isInstance)
				.map(ServerSelectionList.NetworkServerEntry.class::cast).findFirst().orElse(null);
	}

	// The report with the live limit: an increase the rules alone would propose above the server's view is capped to it,
	// with the server's reason.
	private static void checkCap(ClientGameTestContext context, RealController real, int view) {
		Report before = context.computeOnClient(mc -> real.report());
		context.runOnClient(mc -> real.rebuild());
		context.waitFor(mc -> real.report() != null && real.report() != before, JOIN_TICKS);
		Action.SetSetting capped = renderDistance(real.report());
		check(capped == null || Integer.parseInt(capped.newValue()) <= view, "the RD recommendation is <= " + view + ": " + capped);
		Report uncapped = context.computeOnClient(mc -> Recommender.recommend(real.rules(), real.hardwareProfile(), real.mods(), SettingsBridge.read(mc),
				OnlineData.offline(), real.goal(), real.modVersion(), Set.of()));
		Action.SetSetting wanted = renderDistance(uncapped);
		RigTune.LOGGER.info("LanGuestGameTest: RD recommendation {} with the server at {} (the rules alone: {})", capped, view, wanted);
		if (wanted != null && Integer.parseInt(wanted.newValue()) > view) {
			check(capped != null && capped.newValue().equals(Integer.toString(view)), "capped to " + view + ": " + capped);
			Recommendation rec = real.report().recommendations().stream().filter(r -> r.id().equals("set:vanilla.renderDistance")).findFirst().orElseThrow();
			check(rec.reason().endsWith("The server sends at most " + view + " chunks."), rec.reason());
		}
	}

	private static Action.@Nullable SetSetting renderDistance(Report report) {
		return report.recommendations().stream().map(Recommendation::action)
				.filter(a -> a instanceof Action.SetSetting s && s.key().equals("vanilla.renderDistance"))
				.map(Action.SetSetting.class::cast).findFirst().orElse(null);
	}

	private static Notice openWithNotice(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> RigTuneClient.open(mc.gui.screen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitFor(mc -> real.report() != null, JOIN_TICKS);
		context.waitTicks(3);
		Notice notice = context.computeOnClient(mc -> real.notices().stream().filter(n -> n.key().startsWith(ServerLimitNoticeSource.KEY_PREFIX))
				.findFirst().orElse(null));
		check(notice != null, "the server notice: " + context.computeOnClient(mc -> real.notices().toString()));
		return notice;
	}

	private static Map.Entry<String, JsonObject> onlyEntry(ClientGameTestContext context, RealController real) {
		Path file = real.serverLimitsTracker().storeFile();
		context.waitFor(mc -> servers(file) == 1, 200);
		Map.Entry<String, JsonElement> entry = servers(real).entrySet().iterator().next();
		return Map.entry(entry.getKey(), entry.getValue().getAsJsonObject());
	}

	// Only the hashed key and the limits: no host, no port, no "lan:" address.
	private static void checkNoPlaintext(RealController real, String host, int port) {
		String text = read(real.serverLimitsTracker().storeFile());
		check(!text.contains(host) && !text.contains("lan:") && !text.contains("localhost"), "no plaintext host in server-limits.json: " + text);
		for (Map.Entry<String, JsonElement> entry : servers(real).entrySet()) {
			check(entry.getValue().getAsJsonObject().keySet().equals(Set.of("viewDistance", "simulationDistance", "kind", "lastSeen")),
					"an entry holds the limits only: " + entry.getValue());
			check(!entry.getValue().toString().contains(Integer.toString(port)), "no port in an entry: " + entry.getValue());
		}
		RigTune.LOGGER.info("LanGuestGameTest: server-limits.json {}", text.replaceAll("\\s+", " "));
	}

	private static JsonObject servers(RealController real) {
		return JsonParser.parseString(read(real.serverLimitsTracker().storeFile())).getAsJsonObject().getAsJsonObject("servers");
	}

	private static String salt(RealController real) {
		return JsonParser.parseString(read(real.serverLimitsTracker().storeFile())).getAsJsonObject().get("salt").getAsString();
	}

	private static int servers(Path file) {
		try {
			return JsonParser.parseString(read(file)).getAsJsonObject().getAsJsonObject("servers").size();
		} catch (RuntimeException | AssertionError e) {
			return -1;
		}
	}

	// ServerLimitsStore's key, computed here from the file's salt.
	private static String key(String saltHex, String address) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(HexFormat.of().parseHex(saltHex), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new AssertionError("No free port for the test server", e);
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

	// No toast over the notice line; the cursor in a corner.
	private static void screenshot(ClientGameTestContext context, String name) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		context.waitTicks(2);
		context.takeScreenshot(name);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
