package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.profile.ProfileService;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.PreviewScreen;
import io.github.chaotix345.rigtune.client.ui.ProfileImportScreen;
import io.github.chaotix345.rigtune.client.ui.ProfilesScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.profile.ShareCode;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

// docs/v0.4/SPEC.md AC4.11 (with plan review P-H1), all with the network off (X1): switching to Battery changes the vanilla
// values now, stages the Sodium change in pending.json and adds one journal entry that History shows as "Profile: Battery";
// Undo this restores them; Battery -> Max FPS -> restart -> Undo last x2 (and Undo all from the same start) puts every key
// back; two switches of a staged key before a restart leave two ops that end on the second; importing a code opens Preview
// with exactly the decoded keys and writes nothing until Apply; a malformed code shows its error; a switch during a
// benchmark is refused. Screenshots at the 3 standard sizes.
public class ProfilesGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
	private static final String CULLING = "sodium.performance.use_block_face_culling";
	private static final String CULLING_IN_FILE = "performance.use_block_face_culling";
	private static final String BATTERY = "template:battery";
	private static final String MAX_FPS = "template:max_fps";
	// Values Battery and Max FPS change (the harness's own values are restored afterwards).
	private static final Map<String, String> SEED = seed();

	private final Path configDir = FabricLoader.getInstance().getConfigDir();
	private final Path gameDir = FabricLoader.getInstance().getGameDir();
	private final Path pendingFile = PendingActions.defaultPath(configDir);
	private final Path sodiumFile = configDir.resolve("sodium-options.json");
	private final Path historyFile = Journal.file(configDir);
	private final Path profilesFile = ProfileStore.file(configDir);
	private final Path lastApplyFile = ApplyResult.defaultPath(configDir);
	private final Journal journal = new Journal(configDir, null, null, (message, error) -> {
		throw new AssertionError(message, error);
	});

	private static Map<String, String> seed() {
		Map<String, String> seed = new LinkedHashMap<>();
		seed.put("vanilla.renderDistance", "12");
		seed.put("vanilla.simulationDistance", "10");
		seed.put("vanilla.maxFps", "120");
		seed.put("vanilla.enableVsync", "false");
		seed.put("vanilla.inactivityFpsLimit", "minimized");
		seed.put("vanilla.renderClouds", "true");
		seed.put("vanilla.particles", "0");
		return seed;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController controller = RigTuneClient.controller();
		check(controller instanceof RealController, "the real controller");
		ClientSettings settings = ClientSettings.shared(configDir);
		boolean network = settings.networkEnabled;
		Map<Path, byte[]> saved = backup(historyFile, profilesFile, pendingFile, sodiumFile, lastApplyFile);
		Map<String, String> original = context.computeOnClient(mc -> vanilla(mc.options));
		try {
			settings.networkEnabled = false;
			context.runOnClient(mc -> controller.discardPending());
			batterySwitchAndUndoThis(context, controller);
			twoSwitchesThenUndo(context, controller, false);
			twoSwitchesThenUndo(context, controller, true);
			aToBToABeforeARestart(context, controller);
			importAndMalformed(context, controller);
			refusedDuringABenchmark(context, controller);
		} finally {
			ProfileService.overrideBenchmarkCheck(null);
			settings.networkEnabled = network;
			context.runOnClient(mc -> {
				controller.discardPending();
				SettingsBridge.applyVanilla(mc.options, original);
				mc.gui.setScreen(new TitleScreen());
			});
			restore(saved);
			screenshotAt(context, 854, 480, 0, null);
		}
		RigTune.LOGGER.info("ProfilesGameTest: AC4.11 passed");
	}

	// Battery: vanilla values now, the Sodium change staged, one entry labelled "Profile: Battery"; Undo this restores them.
	private void batterySwitchAndUndoThis(ClientGameTestContext context, RigTuneController controller) {
		reset(context);
		openProfiles(context, controller);
		List<ProfileView> rows = context.computeOnClient(mc -> ((ProfilesScreen) mc.gui.screen()).rows());
		check(rows.size() >= 6 && rows.stream().filter(r -> r.id().startsWith(ProfileStore.TEMPLATE_PREFIX)).count() == 5, "5 templates + My settings: " + rows);
		check(rows.stream().anyMatch(r -> ProfileStore.SOURCE_BASELINE.equals(r.source())), "My settings auto-saved: " + rows);
		for (int[] size : SIZES) {
			screenshotAt(context, size[0], size[1], size[2], "profiles-" + size[0] + "x" + size[1] + "-scale" + size[2]);
			checkLayout(context);
		}
		int entriesBefore = journal.entries().size();
		context.runOnClient(mc -> {
			ProfilesScreen screen = (ProfilesScreen) mc.gui.screen();
			screen.select(BATTERY);
			screen.switchSelected();
		});
		context.waitTicks(3);
		Component status = context.computeOnClient(mc -> ((ProfilesScreen) mc.gui.screen()).status());
		check(key(status).equals("rigtune.profile.status.switched_restart"), "the toast says what applies after a restart: " + text(status));
		context.takeScreenshot("profiles-switched-battery");
		Map<String, String> now = context.computeOnClient(mc -> vanilla(mc.options));
		check("60".equals(now.get("vanilla.maxFps")) && "true".equals(now.get("vanilla.enableVsync")), "Battery's frame cap applied now: " + now);
		check(Integer.parseInt(now.get("vanilla.renderDistance")) <= 8 && "false".equals(now.get("vanilla.renderClouds")), "Battery's distances applied: " + now);
		check("false".equals(sodium()), "the Sodium file is untouched until the restart");
		check(stagedValues().contains("true"), "the Sodium change is staged in pending.json: " + stagedValues());
		List<JournalEntry> entries = journal.entries();
		check(entries.size() == entriesBefore + 1, "one journal entry for the switch");
		JournalEntry entry = entries.getLast();
		check(JournalEntry.APPLY.equals(entry.kind()), "the switch is an ordinary apply entry");
		check(entry.changes().stream().anyMatch(c -> CULLING.equals(c.key()) && JournalChange.STAGED.equals(c.status())), "the staged change is journaled: " + entry);
		check("Battery".equals(ProfileStore.shared(configDir).labels().get(entry.id())), "profiles.json labels the entry");
		HistoryModel.View view = context.computeOnClient(mc -> controller.history());
		check(view != null && view.entries().getFirst().id().equals(entry.id()) && "Battery".equals(view.entries().getFirst().profile()),
				"History labels it Profile: Battery");
		context.runOnClient(mc -> mc.gui.setScreen(new HistoryScreen(new TitleScreen(), controller)));
		context.waitForScreen(HistoryScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen history && !history.loading(), 400);
		for (int[] size : SIZES) {
			screenshotAt(context, size[0], size[1], size[2], "profiles-history-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}

		UndoPlan plan = context.computeOnClient(mc -> controller.undoPlanFor(entry.id()));
		check(plan.problem() == null && !plan.items().isEmpty(), "Undo this plans: " + plan);
		context.runOnClient(mc -> controller.undo(plan));
		context.waitTicks(2);
		checkSeed(context, "Undo this on Battery");
		check(!stagedValues().contains("true"), "the staged Sodium change is dropped: " + stagedValues());
	}

	// P-H1: Battery -> Max FPS -> restart -> Undo last twice (or Undo all) puts every staged and vanilla key back.
	private void twoSwitchesThenUndo(ClientGameTestContext context, RigTuneController controller, boolean all) {
		reset(context);
		clearJournal();
		Component battery = context.computeOnClient(mc -> controller.switchProfile(BATTERY));
		Component maxFps = context.computeOnClient(mc -> controller.switchProfile(MAX_FPS));
		check(key(battery).startsWith("rigtune.profile.status.switched") && key(maxFps).startsWith("rigtune.profile.status.switched"),
				"both switches ran: " + text(battery) + " / " + text(maxFps));
		check("260".equals(context.computeOnClient(mc -> vanilla(mc.options)).get("vanilla.maxFps")), "Max FPS is unlimited");
		runHelper();
		check("true".equals(sodium()), "the helper applied the staged Sodium change at the restart");
		if (all) {
			UndoPlan plan = context.computeOnClient(mc -> controller.undoPlan(true));
			check(plan.problem() == null, "Undo all plans: " + plan);
			context.runOnClient(mc -> controller.undo(plan));
		} else {
			for (int i = 0; i < 2; i++) {
				UndoPlan plan = context.computeOnClient(mc -> controller.undoPlan(false));
				check(plan.problem() == null, "Undo last plans: " + plan);
				context.runOnClient(mc -> controller.undo(plan));
				context.waitTicks(1);
			}
		}
		context.waitTicks(2);
		checkSeed(context, all ? "Undo all after Battery -> Max FPS" : "Undo last x2 after Battery -> Max FPS");
		runHelper();
		check("false".equals(sodium()), "the staged Sodium key is back at its pre-Battery value after the next restart");
	}

	// Battery (stages the Sodium key) then My settings (back to the file's value) before a restart: two ops, both entries
	// journaled, and the key ends at My settings' value (audit M1, plan review P-H1).
	private void aToBToABeforeARestart(ClientGameTestContext context, RigTuneController controller) {
		reset(context);
		clearJournal();
		String mine = ProfileStore.shared(configDir).baseline().id();
		context.runOnClient(mc -> controller.switchProfile(BATTERY));
		context.runOnClient(mc -> controller.switchProfile(mine));
		check(stagedValues().equals(List.of("true", "false")), "two ops for the key, in order: " + stagedValues());
		List<JournalEntry> entries = journal.entries();
		check(entries.size() == 2 && entries.stream().allMatch(e -> e.changes().stream().anyMatch(c -> CULLING.equals(c.key())
				&& JournalChange.STAGED.equals(c.status()))), "both switches journaled the staged key: " + entries);
		checkSeed(context, "Battery -> My settings");
		runHelper();
		check("false".equals(sodium()), "the helper ends on My settings' value");
	}

	private void importAndMalformed(ClientGameTestContext context, RigTuneController controller) {
		reset(context);
		openProfiles(context, controller);
		Map<String, String> values = new LinkedHashMap<>();
		values.put("vanilla.renderDistance", "9");
		values.put("vanilla.maxFps", "90");
		values.put("vanilla.entityShadows", "false");
		values.put(CULLING, "true");
		context.runOnClient(mc -> SettingsBridge.applyVanilla(mc.options, Map.of("vanilla.entityShadows", "true")));
		String code = ShareCode.encode("Friend's‮ ​profile\n=#", values, -1);
		String hashes = hashes();
		context.runOnClient(mc -> ((ProfilesScreen) mc.gui.screen()).openImport());
		context.waitForScreen(ProfileImportScreen.class);
		context.runOnClient(mc -> {
			ProfileImportScreen screen = (ProfileImportScreen) mc.gui.screen();
			screen.setCode(code);
			screen.importCode();
		});
		context.waitForScreen(PreviewScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen preview && !preview.loading() && preview.preview() != null, 400);
		ApplyPreview preview = context.computeOnClient(mc -> ((PreviewScreen) mc.gui.screen()).preview());
		List<String> keys = new ArrayList<>();
		preview.now().forEach(s -> keys.add("vanilla." + s.key()));
		preview.atRestart().forEach(s -> keys.add("sodium." + s.key()));
		check(Set.copyOf(keys).equals(values.keySet()) && keys.size() == values.size(), "Preview lists exactly the decoded keys: " + keys);
		check(hashes.equals(hashes()), "options.txt and config/ unchanged by decoding and Preview");
		check(context.computeOnClient(mc -> ((PreviewScreen) mc.gui.screen()).applyButton().active), "Apply is offered");
		for (int[] size : SIZES) {
			screenshotAt(context, size[0], size[1], size[2], "profiles-import-preview-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		context.runOnClient(mc -> press(((PreviewScreen) mc.gui.screen()).applyButton()));
		context.waitForScreen(ProfilesScreen.class);
		// Render distance may be limited for this PC's memory (plan review P-L2): Preview showed the value Apply uses.
		String renderDistance = preview.now().stream().filter(s -> s.key().equals("renderDistance")).findFirst().orElseThrow().newValue();
		check(Integer.parseInt(renderDistance) <= 9, "the imported render distance, clamped at most: " + renderDistance);
		Map<String, String> now = context.computeOnClient(mc -> vanilla(mc.options));
		check(renderDistance.equals(now.get("vanilla.renderDistance")) && "90".equals(now.get("vanilla.maxFps"))
				&& "false".equals(now.get("vanilla.entityShadows")), "Apply switched to the imported values: " + now);
		ProfileStore.Profile imported = ProfileStore.shared(configDir).profiles().stream()
				.filter(p -> ProfileStore.SOURCE_IMPORTED.equals(p.source())).findFirst().orElse(null);
		check(imported != null && "Friend's profile".equals(imported.name()), "saved as an imported profile with the name sanitised: " + imported);
		context.takeScreenshot("profiles-imported");

		// A malformed code shows its error and opens nothing.
		context.runOnClient(mc -> ((ProfilesScreen) mc.gui.screen()).openImport());
		context.waitForScreen(ProfileImportScreen.class);
		context.runOnClient(mc -> {
			ProfileImportScreen screen = (ProfileImportScreen) mc.gui.screen();
			screen.setCode(code.substring(0, code.length() - 3) + "AAA");
			screen.importCode();
		});
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof ProfileImportScreen), "a bad code stays on the import screen");
		Component error = context.computeOnClient(mc -> ((ProfileImportScreen) mc.gui.screen()).error());
		check(error != null && key(error).startsWith("rigtune.profile.code.error."), "the error line says why: " + text(error));
		for (int[] size : SIZES) {
			screenshotAt(context, size[0], size[1], size[2], "profiles-import-error-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(ProfilesScreen.class);
	}

	private void refusedDuringABenchmark(ClientGameTestContext context, RigTuneController controller) {
		reset(context);
		int entries = journal.entries().size();
		ProfileService.overrideBenchmarkCheck(() -> true);
		Component refused = context.computeOnClient(mc -> controller.switchProfile(BATTERY));
		ProfileService.overrideBenchmarkCheck(null);
		check(key(refused).equals("rigtune.profile.status.benchmark"), "refused during a benchmark: " + text(refused));
		check(journal.entries().size() == entries, "nothing journaled");
		checkSeed(context, "a refused switch");
	}

	// --- helpers

	// The seed values, the Sodium key off, nothing staged, a fresh profiles.json whose "My settings" holds the seed.
	private void reset(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.gui.setScreen(new TitleScreen());
			RigTuneClient.controller().discardPending();
			SettingsBridge.applyVanilla(mc.options, SEED);
		});
		try {
			SodiumConfigPatcher.patchFile(sodiumFile, Map.of(CULLING_IN_FILE, "false"));
			Files.deleteIfExists(profilesFile);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		context.runOnClient(mc -> RigTuneClient.controller().profiles());
		check("false".equals(sodium()), "the Sodium key is seeded off");
	}

	private void clearJournal() {
		try {
			Files.deleteIfExists(historyFile);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void openProfiles(ClientGameTestContext context, RigTuneController controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new RigTuneScreen(new TitleScreen(), controller), controller)));
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> ((ToolsScreen) mc.gui.screen()).openProfiles());
		context.waitForScreen(ProfilesScreen.class);
		context.waitTicks(2);
	}

	// Every button inside the screen, below the title lines and clear of the others.
	private static void checkLayout(ClientGameTestContext context) {
		String problem = context.computeOnClient(mc -> {
			ProfilesScreen screen = (ProfilesScreen) mc.gui.screen();
			List<Button> buttons = screen.actions();
			for (Button b : buttons) {
				if (b.getX() < 0 || b.getY() < 30 || b.getX() + b.getWidth() > screen.width || b.getY() + b.getHeight() > screen.height) {
					return "button outside the screen: " + b.getMessage().getString() + " at " + b.getX() + "," + b.getY();
				}
				for (Button o : buttons) {
					if (o != b && o.getX() < b.getX() + b.getWidth() && b.getX() < o.getX() + o.getWidth() && o.getY() < b.getY() + b.getHeight()
							&& b.getY() < o.getY() + o.getHeight()) {
						return "buttons overlap: " + b.getMessage().getString() + " / " + o.getMessage().getString();
					}
				}
			}
			return null;
		});
		check(problem == null, String.valueOf(problem));
	}

	private void checkSeed(ClientGameTestContext context, String after) {
		Map<String, String> now = context.computeOnClient(mc -> vanilla(mc.options));
		for (Map.Entry<String, String> e : SEED.entrySet()) {
			check(SettingValues.same(e.getValue(), now.get(e.getKey())), after + ": " + e.getKey() + " back at " + e.getValue() + " (is " + now.get(e.getKey()) + ")");
		}
	}

	private static Map<String, String> vanilla(Options options) {
		Map<String, String> out = new LinkedHashMap<>();
		SettingsBridge.readVanilla(options).forEach((k, v) -> out.put("vanilla." + k, v));
		return out;
	}

	private String sodium() {
		return SettingsBridge.readSodium(sodiumFile).get(CULLING);
	}

	private List<String> stagedValues() {
		List<String> out = new ArrayList<>();
		try {
			if (Files.exists(pendingFile)) {
				for (Op op : PendingActions.load(pendingFile).ops()) {
					if (op.patches() != null && op.patches().containsKey(CULLING_IN_FILE)) {
						out.add(op.patches().get(CULLING_IN_FILE));
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}

	// What the post-exit helper does, in process (as UndoGameTest): under the apply lock, run pending.json.
	private void runHelper() {
		if (!Files.exists(pendingFile)) {
			return;
		}
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.defaultPath(configDir), Duration.ofSeconds(10))) {
			check(lock != null, "took the apply lock");
			new ApplyExecutor(2, 50).run(PendingActions.load(pendingFile), pendingFile);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// options.txt and every file under config/ (sorted by path), hashed together.
	private String hashes() {
		try (Stream<Path> files = Files.walk(configDir)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			List<Path> all = new ArrayList<>(files.filter(Files::isRegularFile).sorted().toList());
			all.addFirst(gameDir.resolve("options.txt"));
			for (Path file : all) {
				digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
				digest.update(Files.readAllBytes(file));
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Map<Path, byte[]> backup(Path... files) {
		Map<Path, byte[]> out = new LinkedHashMap<>();
		for (Path file : files) {
			try {
				out.put(file, Files.exists(file) ? Files.readAllBytes(file) : null);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
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

	private static String key(@Nullable Component component) {
		return component != null && component.getContents() instanceof TranslatableContents t ? t.getKey() : "";
	}

	private static String text(@Nullable Component component) {
		return component == null ? "null" : component.getString();
	}

	private static void press(@Nullable Button button) {
		check(button != null && button.active, "button there and active");
		button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
	}

	private static void screenshotAt(ClientGameTestContext context, int width, int height, int guiScale, @Nullable String name) {
		context.getInput().resizeWindow(width, height);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
		if (name != null) {
			context.takeScreenshot(name);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
