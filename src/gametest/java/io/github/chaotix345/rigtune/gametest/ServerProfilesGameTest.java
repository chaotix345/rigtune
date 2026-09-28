package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.profile.ProfileService;
import io.github.chaotix345.rigtune.client.server.ServerProfileService;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.ProfilesScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RowFocus;
import io.github.chaotix345.rigtune.client.ui.ServerProfilesScreen;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.profile.ServerProfilePrompt;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.server.ServerLimitsStore;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

// docs/v0.5/SPEC.md 7 (C16; AC7.1, AC7.5-AC7.11, AC7.15, AC7.18's gap), sp §5, with RigTune's network off (X1): the own
// world gets no offer and the Servers screen says why; on a dedicated server on a free port (never 25565), connection 1
// sets Max FPS for it from Profiles → Servers… (the file holds one hashed entry, no host or port); connection 2 offers it
// (the notice before SERVER_LIMIT, one toast), holds it while a benchmark runs, refuses a Switch then and changes
// nothing, then the Switch is one apply entry "Profile: Max FPS" of settings only, and after Undo this the offer doesn't
// come back; connection 3 offers again with no second toast, × hides it without storing its key, Don't offer here
// forgets the server, and Evening is set; connection 4 offers Evening, whose rename the offer and the screen follow and
// whose deletion forgets the server; the screen stops offering and forgets all; after the disconnect it says to join a
// server. Screenshots at X12's sizes (1280x720@3 for the
// scroll size); server-profiles.json, profiles.json, the history, pending.json, awareness.json and the options are put back.
public class ServerProfilesGameTest implements FabricClientGameTest {
	private static final String MAX_FPS = "template:max_fps";
	private static final String QUALITY = "template:quality";
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}, {1280, 720, 3}};

	private final Path configDir = FabricLoader.getInstance().getConfigDir();
	private final Path serverProfilesFile = ServerProfileStore.file(configDir);
	private final Path profilesFile = ProfileStore.file(configDir);
	private final Path historyFile = Journal.file(configDir);
	private final Path pendingFile = PendingActions.defaultPath(configDir);
	private final Path awarenessFile = AwarenessStore.file(configDir);
	private final Path serverLimitsFile = ServerLimitsStore.file(configDir);
	private final Path lastApplyFile = ApplyResult.defaultPath(configDir);
	private final Journal journal = new Journal(configDir, null, null, (message, error) -> {
		throw new AssertionError(message, error);
	});
	private String port = "";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RealController real = V05TestContext.of(context).realController();
		Map<Path, byte[]> saved = backup(serverProfilesFile, profilesFile, historyFile, pendingFile, awarenessFile, serverLimitsFile, lastApplyFile);
		Map<String, String> original = context.computeOnClient(mc -> vanilla(mc.options));
		boolean network = GameTestNet.set(context, real, false);
		try {
			context.runOnClient(mc -> real.discardPending());
			delete(serverProfilesFile);
			ownWorld(context, real);
			port = Integer.toString(freePort());
			Properties properties = new Properties();
			// A free port, never 25565: a local run must not meet a server the player runs.
			properties.setProperty("server-port", port);
			// The harness starts the server in the (wiped) game-test run directory, where vanilla reads eula.txt.
			write(Path.of("eula.txt"), "eula=true\n");
			try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
				setMaxFpsForThisServer(context, real, server);
				offerSwitchAndUndo(context, real, server);
				offerAgainDismissAndForget(context, real, server);
				renameDeleteAndTheScreen(context, real, server);
			}
			afterDisconnect(context, real);
			RigTune.LOGGER.info("ServerProfilesGameTest: own world, set, offer, held and refused during a benchmark, switch + Undo, no second toast, "
					+ "×, Don't offer here, rename, delete, Stop offering here and Forget all checked");
		} finally {
			ServerProfileService.overrideBenchmarkCheck(null);
			ProfileService.overrideBenchmarkCheck(null);
			GameTestNet.set(context, real, network);
			context.runOnClient(mc -> {
				real.discardPending();
				SettingsBridge.applyVanilla(mc.options, original);
				mc.gui.toastManager().clear();
				mc.gui.setScreen(new TitleScreen());
			});
			restore(saved);
			resize(context, 854, 480, 0);
		}
		context.waitForScreen(TitleScreen.class);
	}

	// The own world: no offer, no toast, and the screen says profiles are offered on servers.
	private void ownWorld(ClientGameTestContext context, RealController real) {
		try (TestSingleplayerContext world = GameTestWorlds.create(context)) {
			world.getConnection().waitForChunksRender();
			context.waitFor(mc -> real.serverProfiles().state() == ServerProfilesView.State.OWN_WORLD, 200);
			context.waitTicks(10);
			check(offer(context, real) == null, "no offer in the own world");
			check(toast(context) == null, "no toast in the own world");
			openServers(context, real, null);
			ServerProfilesView view = context.computeOnClient(mc -> screen(mc).view());
			check(view.here().english().equals("Profiles are offered on servers, not in your own worlds."), view.here().english());
			check(!context.computeOnClient(mc -> screen(mc).offerButton().active), "Offer is inactive in the own world");
			screenshot(context, "server-profiles-own-world");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
		context.waitFor(mc -> real.serverProfiles().state() == ServerProfilesView.State.NOT_CONNECTED, 200);
	}

	// Connection 1: nothing set, so no offer; Quality is switched to, and Max FPS set for this server from Profiles.
	private void setMaxFpsForThisServer(ClientGameTestContext context, RealController real, TestDedicatedServerContext server) {
		try (TestDedicatedServerConnection connection = server.connect()) {
			inTheWorld(context, real);
			context.waitTicks(20);
			check(offer(context, real) == null, "nothing set for this server: no offer");
			check(toast(context) == null, "nothing set for this server: no toast");
			check(switched(context.computeOnClient(mc -> real.switchProfile(QUALITY))), "Quality switched to");
			openServers(context, real, MAX_FPS);
			ServerProfilesView before = context.computeOnClient(mc -> screen(mc).view());
			check(before.here().english().equals("This server: no profile set."), before.here().english());
			Button offer = context.computeOnClient(mc -> screen(mc).offerButton());
			check(offer.active && offer.getMessage().getString().equals("Offer Max FPS here"), "Offer takes the profile selected in Profiles: "
					+ offer.getMessage().getString());
			check(!context.computeOnClient(mc -> screen(mc).stopButton().active), "nothing to stop yet");
			screenshot(context, "server-profiles-none-854x480-scale2");
			context.runOnClient(mc -> screen(mc).remember());
			context.waitTicks(2);
			String status = context.computeOnClient(mc -> screen(mc).status().getString());
			check(status.equals("RigTune will offer Max FPS when you join this server."), status);
			ServerProfilesView after = context.computeOnClient(mc -> screen(mc).view());
			check(after.here().english().equals("This server: RigTune offers Max FPS when you join."), after.here().english());
			check(after.rows().size() == 1 && after.rows().getFirst().current() && MAX_FPS.equals(after.currentProfile()), "one row, this server: " + after);
			check(after.rows().getFirst().text().english().startsWith("Server · Max FPS · last joined "), after.rows().getFirst().text().english());
			checkFile(1);
			JsonObject entry = servers().entrySet().iterator().next().getValue().getAsJsonObject();
			check(entry.get("profile").getAsString().equals(MAX_FPS) && entry.get("kind").getAsString().equals("REMOTE") && entry.has("setAt")
					&& entry.has("lastSeen"), "the entry: " + entry);
			check(offer(context, real) == null, "setting it offers nothing now: the next join does");
			checkScreen(context, "server-profiles-set");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
		context.waitFor(mc -> real.serverProfiles().state() == ServerProfilesView.State.NOT_CONNECTED, 200);
		check(offer(context, real) == null, "no offer after the disconnect");
	}

	// Connection 2: the offer (the notice before SERVER_LIMIT, one toast), held and refused during a benchmark, the
	// Switch, Undo this.
	private void offerSwitchAndUndo(ClientGameTestContext context, RealController real, TestDedicatedServerContext server) {
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		try (TestDedicatedServerConnection connection = server.connect()) {
			inTheWorld(context, real);
			waitForOffer(context, real);
			Notice offer = offer(context, real);
			check(offer.message().english().equals("You set Max FPS for this server. Switch to it?"), offer.message().english());
			check(offer.key().startsWith(ServerProfilePrompt.KEY_PREFIX) && offer.dismissible(), "key and ×: " + offer.key());
			check(offer.actions().stream().map(NoticeAction::id).toList().equals(List.of("switch", "forget")), "actions: " + offer.actions());
			// The toast waits for the world to show (no screen over it), then shows once for this server this session.
			String key = servers().keySet().iterator().next();
			context.waitFor(mc -> real.v05().serverProfiles().toasted(key), 200);
			String body = context.computeOnClient(mc -> real.v05().serverProfiles().lastToast().getString());
			check(body.startsWith("Max FPS is set for this server. ") && body.endsWith(" to switch."), "the toast's text: " + body);
			// Best effort: the live toast, fully slid in (it stays 8 s).
			context.waitTicks(20);
			context.getInput().setCursorPos(1, 1);
			RigTune.LOGGER.info("ServerProfilesGameTest: toast on screen for the screenshot: {}", toast(context) != null);
			context.takeScreenshot("server-profiles-toast");
			List<Notice> notices = context.computeOnClient(mc -> real.notices());
			int limit = indexOf(notices, NoticePriority.SERVER_LIMIT);
			check(notices.getFirst().key().equals(offer.key()) && limit > 0, "the offer first, SERVER_LIMIT after it: " + notices);
			context.runOnClient(mc -> mc.gui.toastManager().clear());
			context.runOnClient(mc -> RigTuneClient.open(null));
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(3);
			for (int[] size : SIZES) {
				resize(context, size[0], size[1], size[2]);
				Notice shown = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice());
				int others = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).otherNotices());
				check(shown != null && shown.key().equals(offer.key()) && others >= 1, "RigTuneScreen shows the offer with +" + others + " more: " + shown);
				screenshot(context, "server-profiles-offer-" + name(size));
			}
			resize(context, 640, 480, 2);
			context.runOnClient(mc -> mc.gui.setScreen(new NoticeScreen(null, real)));
			context.waitForScreen(NoticeScreen.class);
			screenshot(context, "server-profiles-notice-screen-640x480-scale2");
			resize(context, 854, 480, 2);
			context.runOnClient(mc -> mc.gui.setScreen(null));

			// AC7.8: while a benchmark runs the offer is held, and a Switch is refused with Profiles' message.
			ServerProfileService.overrideBenchmarkCheck(() -> true);
			check(offer(context, real) == null, "no offer while a benchmark runs");
			ServerProfileService.overrideBenchmarkCheck(null);
			check(offer(context, real) != null, "the held offer shows again once the benchmark is over");
			int entries = journal.entries().size();
			Map<String, String> beforeRefusal = context.computeOnClient(mc -> vanilla(mc.options));
			ProfileService.overrideBenchmarkCheck(() -> true);
			context.runOnClient(mc -> real.noticeAction(offer.key(), ServerProfilePrompt.ACTION_SWITCH));
			ProfileService.overrideBenchmarkCheck(null);
			check(journal.entries().size() == entries, "the refused Switch journals nothing");
			check(context.computeOnClient(mc -> vanilla(mc.options)).equals(beforeRefusal), "the refused Switch changes no option");
			String refusal = context.computeOnClient(mc -> real.v05().serverProfiles().lastToast().getString());
			check(refusal.equals("Profiles can't switch while a benchmark is running."), "the refusal is the toast: " + refusal);
			check(offer(context, real) != null, "the offer stays after a refusal");

			// AC7.7: the Switch is one apply entry "Profile: Max FPS" with Max FPS's vanilla values; settings only (AC7.18).
			context.runOnClient(mc -> real.noticeAction(offer.key(), ServerProfilePrompt.ACTION_SWITCH));
			context.waitTicks(2);
			List<JournalEntry> after = journal.entries();
			check(after.size() == entries + 1, "one journal entry for the Switch");
			JournalEntry switched = after.getLast();
			check(JournalEntry.APPLY.equals(switched.kind()), "an ordinary apply entry");
			check(switched.changes().stream().allMatch(c -> JournalChange.SETTING.equals(c.type())), "settings only: " + switched.changes());
			HistoryModel.View history = context.computeOnClient(mc -> real.history());
			check("Max FPS".equals(history.entries().getFirst().profile()) && history.entries().getFirst().id().equals(switched.id()),
					"History labels it Profile: Max FPS");
			Map<String, String> now = context.computeOnClient(mc -> vanilla(mc.options));
			check("260".equals(now.get("vanilla.maxFps")) && "false".equals(now.get("vanilla.enableVsync")), "Max FPS's values: " + now);
			check(offer(context, real) == null, "the offer is gone once Max FPS is active");
			settingsOnlyPreviews(context, real);

			UndoPlan plan = context.computeOnClient(mc -> real.undoPlanFor(switched.id()));
			check(plan.problem() == null && !plan.items().isEmpty(), "Undo this plans: " + plan);
			context.runOnClient(mc -> real.undo(plan));
			context.waitTicks(3);
			check(!"260".equals(context.computeOnClient(mc -> vanilla(mc.options)).get("vanilla.maxFps")), "Undo this restores the frame cap");
			check(context.computeOnClient(mc -> real.profiles()).stream().noneMatch(p -> MAX_FPS.equals(p.id()) && p.active()),
					"the undone switch leaves Max FPS not active");
			check(offer(context, real) == null, "the offer doesn't come back in the same connection");
		}
		context.waitFor(mc -> real.serverProfiles().state() == ServerProfilesView.State.NOT_CONNECTED, 200);
	}

	// AC7.18's gap: a template's and a saved profile's real resolution (what the offer's Switch applies) has no download
	// and no disable in Preview.
	private void settingsOnlyPreviews(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> real.saveCurrentProfile("Evening"));
		String evening = eveningId();
		for (String id : List.of(QUALITY, evening)) {
			ApplyPreview preview = context.computeOnClient(mc -> real.previewProfile(id));
			check(preview.downloads().isEmpty() && preview.disables().isEmpty(), id + ": settings only: " + preview);
		}
		context.runOnClient(mc -> real.deleteProfile(evening));
	}

	// Connection 3: the offer again with no second toast; × hides it for this connection only; Don't offer here forgets
	// the server; then Evening is set here from the screen.
	private void offerAgainDismissAndForget(ClientGameTestContext context, RealController real, TestDedicatedServerContext server) {
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		try (TestDedicatedServerConnection connection = server.connect()) {
			inTheWorld(context, real);
			waitForOffer(context, real);
			Notice offer = offer(context, real);
			context.waitTicks(20);
			check(toast(context) == null, "no second toast for this server in this game session");

			context.runOnClient(mc -> real.dismissNotice(offer.key()));
			check(offer(context, real) == null, "× hides it");
			context.runOnClient(mc -> RigTuneClient.open(null));
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(2);
			Notice shown = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice());
			check(shown == null || !shown.key().equals(offer.key()), "reopening RigTune keeps it hidden: " + shown);
			byte[] awareness = bytes(awarenessFile);
			check(awareness == null || !new String(awareness, StandardCharsets.UTF_8).contains(offer.key()), "awareness.json doesn't store the per-join key");

			// Don't offer here (what NoticeCenter passes on for the notice's second action).
			context.runOnClient(mc -> real.v05().serverProfiles().act(ServerProfilePrompt.ACTION_FORGET));
			checkFile(0);

			context.runOnClient(mc -> real.saveCurrentProfile("Evening"));
			String evening = eveningId();
			openServers(context, real, evening);
			context.runOnClient(mc -> screen(mc).remember());
			context.waitTicks(2);
			checkFile(1);
			check(evening.equals(servers().entrySet().iterator().next().getValue().getAsJsonObject().get("profile").getAsString()), "Evening set here");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
		context.waitFor(mc -> real.serverProfiles().state() == ServerProfilesView.State.NOT_CONNECTED, 200);
	}

	// Connection 4 (AC7.9): Evening is offered; renamed, the offer and the screen say its new name; deleted, its server is
	// forgotten and the offer goes. Then the screen: Max FPS set, Stop offering here, set again, Forget all… (confirmed).
	private void renameDeleteAndTheScreen(ClientGameTestContext context, RealController real, TestDedicatedServerContext server) {
		try (TestDedicatedServerConnection connection = server.connect()) {
			inTheWorld(context, real);
			waitForOffer(context, real);
			check(offer(context, real).message().english().equals("You set Evening for this server. Switch to it?"), offer(context, real).message().english());
			String evening = eveningId();
			context.runOnClient(mc -> real.renameProfile(evening, "Night"));
			Notice renamed = offer(context, real);
			check(renamed != null && renamed.message().english().equals("You set Night for this server. Switch to it?"), "the offer says the new name: " + renamed);
			ServerProfilesView view = context.computeOnClient(mc -> real.serverProfiles());
			check(view.here().english().equals("This server: RigTune offers Night when you join."), "a rename keeps the mapping: " + view.here().english());
			context.runOnClient(mc -> real.deleteProfile(evening));
			checkFile(0);
			check(offer(context, real) == null, "a deleted profile's offer goes");

			openServers(context, real, MAX_FPS);
			context.runOnClient(mc -> screen(mc).remember());
			context.waitTicks(2);
			checkFile(1);
			checkScreen(context, "server-profiles-this-server");
			context.runOnClient(mc -> screen(mc).stop());
			context.waitTicks(2);
			checkFile(0);
			check(context.computeOnClient(mc -> screen(mc).status().getString()).equals("RigTune won't offer a profile on this server any more."),
					"Stop offering here's status");
			context.runOnClient(mc -> screen(mc).remember());
			context.waitTicks(2);
			checkFile(1);
			context.runOnClient(mc -> screen(mc).confirmForgetAll());
			context.waitForScreen(ConfirmScreen.class);
			screenshot(context, "server-profiles-forget-all-confirm");
			context.runOnClient(mc -> press(mc, CommonComponents.GUI_YES.getString()));
			context.waitForScreen(ServerProfilesScreen.class);
			context.waitTicks(2);
			checkFile(0);
			check(context.computeOnClient(mc -> screen(mc).view().rows().isEmpty()), "no rows after Forget all");
			screenshot(context, "server-profiles-empty-854x480-scale2");
			context.runOnClient(mc -> mc.gui.setScreen(null));
		}
		context.waitFor(mc -> real.serverProfiles().state() == ServerProfilesView.State.NOT_CONNECTED, 200);
	}

	private void afterDisconnect(ClientGameTestContext context, RealController real) {
		check(offer(context, real) == null, "no offer after the disconnect");
		openServers(context, real, null);
		ServerProfilesView view = context.computeOnClient(mc -> screen(mc).view());
		check(view.state() == ServerProfilesView.State.NOT_CONNECTED && view.here().english().equals("Join a server to set a profile for it."),
				view.here().english());
		check(!context.computeOnClient(mc -> screen(mc).offerButton().active), "Offer is inactive while not connected");
		screenshot(context, "server-profiles-not-connected");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	// --- the screen

	// Profiles (with `profile` selected) → Servers….
	private static void openServers(ClientGameTestContext context, RealController real, @Nullable String profile) {
		context.runOnClient(mc -> mc.gui.setScreen(new ProfilesScreen(null, real)));
		context.waitForScreen(ProfilesScreen.class);
		context.runOnClient(mc -> {
			ProfilesScreen screen = (ProfilesScreen) mc.gui.screen();
			check(screen.actions().stream().anyMatch(b -> b.getMessage().getString().equals("Servers…")), "Profiles' row 3 has Servers…");
			screen.select(profile);
			screen.openServers();
		});
		context.waitForScreen(ServerProfilesScreen.class);
		context.waitTicks(2);
	}

	private static ServerProfilesScreen screen(Minecraft mc) {
		return (ServerProfilesScreen) mc.gui.screen();
	}

	// AC7.11 on the real screen: nothing drawn or narrated holds the host, the port or "localhost"; at every size the
	// buttons are inside the screen, apart and their labels fit, the text lines sit above the list; a screenshot each.
	private void checkScreen(ClientGameTestContext context, String name) {
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			String at = name + "-" + name(size);
			context.runOnClient(mc -> {
				ServerProfilesScreen screen = screen(mc);
				List<String> texts = new ArrayList<>();
				Screens.getWidgets(screen).forEach(w -> texts.add(w.getMessage().getString()));
				for (ServerProfilesScreen.ServerRow row : screen.list().children()) {
					for (GuiEventListener child : row.children()) {
						if (child instanceof AbstractWidget w) {
							texts.add(w.getMessage().getString());
						}
					}
				}
				texts.add(Texts.component(screen.view().here()).getString());
				for (String text : texts) {
					for (String plain : List.of("localhost", "127.0.0.1", port)) {
						check(!text.contains(plain), at + ": \"" + plain + "\" in " + text);
					}
				}
				List<Button> buttons = screen.actions();
				for (Button b : buttons) {
					check(b.getX() >= 0 && b.getY() >= 30 && b.getRight() <= screen.width && b.getBottom() <= screen.height, at + ": outside: "
							+ b.getMessage().getString());
					check(mc.font.width(b.getMessage()) <= b.getWidth() - 4, at + ": label doesn't fit: " + b.getMessage().getString());
					for (Button o : buttons) {
						check(o == b || !(o.getX() < b.getRight() && b.getX() < o.getRight() && o.getY() < b.getBottom() && b.getY() < o.getBottom()),
								at + ": overlap " + b.getMessage().getString() + " / " + o.getMessage().getString());
					}
				}
				for (AbstractWidget w : Screens.getWidgets(screen)) {
					if (w instanceof RowFocus) {
						// Below the title (y 8): the status (or subtitle) stop starts at 19 (review-11 FEAT-4).
						check(w.getX() >= 0 && w.getY() >= 18 && w.getRight() <= screen.width && w.getBottom() <= screen.list().getY(), at + ": text line: "
								+ w.getMessage().getString());
					}
				}
				check(screen.list().getY() >= 80 && screen.list().getBottom() <= buttons.getLast().getY(), at + ": the list between the lines and the buttons");
			});
			screenshot(context, at);
		}
		resize(context, 854, 480, 2);
	}

	private static void press(Minecraft mc, String label) {
		Button button = (Button) Screens.getWidgets(mc.gui.screen()).stream().filter(w -> w instanceof Button && w.getMessage().getString().equals(label))
				.findFirst().orElseThrow(() -> new AssertionError("no " + label + " button"));
		button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
	}

	// --- notices and toasts

	private static @Nullable Notice offer(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> offer(real));
	}

	// On the render thread, where the notice sources run.
	private static @Nullable Notice offer(RealController real) {
		return real.notices().stream().filter(n -> n.priority() == NoticePriority.SERVER_PROFILE).findFirst().orElse(null);
	}

	// Joined and in the world. Not waitForChunksRender: a profile switch raises the render distance above what the server
	// sends, and the chunks beyond it never come.
	private static void inTheWorld(ClientGameTestContext context, RealController real) {
		context.waitFor(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null
				&& real.serverProfiles().state() == ServerProfilesView.State.SERVER, 600);
		context.waitTicks(5);
	}

	// The join lookup runs on Probes.EXECUTOR: up to 20 s for its offer; on a timeout, what there was instead.
	private void waitForOffer(ClientGameTestContext context, RealController real) {
		for (int i = 0; i < 40; i++) {
			if (offer(context, real) != null) {
				return;
			}
			context.waitTicks(10);
		}
		String state = context.computeOnClient(mc -> real.serverProfiles() + " | notices " + real.notices() + " | active "
				+ real.profileService().activeProfileId());
		throw new AssertionError("Check failed: no offer on joining a server set to Max FPS: " + state + " | " + read(serverProfilesFile));
	}

	private static @Nullable SystemToast toast(ClientGameTestContext context) {
		return context.computeOnClient(ServerProfilesGameTest::toast);
	}

	private static @Nullable SystemToast toast(Minecraft mc) {
		return mc.gui.toastManager().getToast(SystemToast.class, ServerProfileService.TOAST_ID);
	}

	private static int indexOf(List<Notice> notices, NoticePriority priority) {
		for (int i = 0; i < notices.size(); i++) {
			if (notices.get(i).priority() == priority) {
				return i;
			}
		}
		return -1;
	}

	private static boolean switched(Component result) {
		return result.getContents() instanceof TranslatableContents t
				&& (t.getKey().startsWith("rigtune.profile.status.switched") || t.getKey().equals("rigtune.profile.status.already"));
	}

	// --- files

	private String eveningId() {
		return ProfileStore.shared(configDir).profiles().stream().filter(p -> "Evening".equals(p.name())).findFirst()
				.orElseThrow(() -> new AssertionError("Evening saved")).id();
	}

	// AC7.1: formatVersion 1, a 32-hex salt, `count` entries keyed by 64 hex; no host, port or "localhost" anywhere.
	private void checkFile(int count) {
		String text = read(serverProfilesFile);
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		check(root.get("formatVersion").getAsInt() == 1 && root.get("salt").getAsString().matches("[0-9a-f]{32}"), "formatVersion and salt: " + text);
		JsonObject servers = root.getAsJsonObject("servers");
		check(servers.size() == count, count + " entries: " + text);
		check(servers.keySet().stream().allMatch(k -> k.matches("[0-9a-f]{64}")), "hashed keys: " + text);
		String withoutHex = text.replaceAll("[0-9a-f]{32,64}", "#");
		for (String plain : List.of("localhost", "127.0.0.1", port)) {
			check(!withoutHex.contains(plain), "\"" + plain + "\" in server-profiles.json: " + text);
		}
	}

	private JsonObject servers() {
		return JsonParser.parseString(read(serverProfilesFile)).getAsJsonObject().getAsJsonObject("servers");
	}

	private static Map<String, String> vanilla(Options options) {
		Map<String, String> out = new LinkedHashMap<>();
		SettingsBridge.readVanilla(options).forEach((k, v) -> out.put("vanilla." + k, v));
		return out;
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
			throw new UncheckedIOException(e);
		}
	}

	private static byte @Nullable [] bytes(Path file) {
		try {
			return Files.exists(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.writeString(file, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Map<Path, byte[]> backup(Path... files) {
		Map<Path, byte[]> out = new LinkedHashMap<>();
		for (Path file : files) {
			out.put(file, bytes(file));
		}
		return out;
	}

	private static void restore(Map<Path, byte[]> saved) {
		saved.forEach((file, bytes) -> {
			try {
				if (bytes == null) {
					Files.deleteIfExists(file);
				} else {
					Files.write(file, bytes);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	// --- screenshots and sizes

	// No toast over the screen; the cursor in a corner.
	private static void screenshot(ClientGameTestContext context, String name) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		context.waitTicks(2);
		context.takeScreenshot(name);
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
