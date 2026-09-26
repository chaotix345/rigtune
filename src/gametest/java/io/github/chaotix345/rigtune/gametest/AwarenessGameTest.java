package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.awareness.AwarenessService;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.awareness.WhatsNew;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// docs/v0.4/SPEC.md 9 (AC9.7) and the notice slot, with the real controller and the network off (X1): a seeded
// awareness.json with an older driver string -> the driver notice (committed to awareness.json once shown, W-L3), and
// Re-benchmark opens BenchmarkMenuScreen; a seeded older rules revision whose ids lack what the report shows -> the what's-new
// notice, gone after dismiss and reopen; the slot shows the highest priority first and "+N more". Other features' notices
// may be present (one client runs every game-test class), so the checks go by key and priority order, not exact counts.
// The test's own state (awareness.json, the network switch) is put back afterwards.
public class AwarenessGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		if (!(RigTuneClient.controller() instanceof RealController real)) {
			throw new AssertionError("AwarenessGameTest needs the real controller");
		}
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path file = real.awarenessService().file();
		context.waitFor(mc -> Files.isRegularFile(file), 200);
		String original = read(file);
		try {
			setNetwork(context, configDir, real, false);
			String currentDriver = seed(context, real, file);
			rescan(context, real);
			openRigTune(context);

			String hardwareKey = checkSlot(context, real);
			checkShownCommits(context, real, file, hardwareKey, currentDriver);
			checkRebenchmark(context, real, hardwareKey);
			checkDismissals(context, real, file, hardwareKey);
			RigTune.LOGGER.info("AwarenessGameTest: driver notice (shown -> committed), Re-benchmark, what's new and dismissals checked");
		} finally {
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			write(file, original);
			setNetwork(context, configDir, real, true);
			resize(context, 854, 480, 0);
		}
	}

	// An older driver string in the fingerprint, and a baseline one rules revision back whose ids lack the report's
	// eligible ones (appliable or warning, producible by the rules). Returns the current driver string.
	private static String seed(ClientGameTestContext context, RealController real, Path file) {
		Report report = real.report();
		RulesDocument rules = real.rules();
		check(report != null && rules != null && report.rulesRevision() == rules.revision, "a report from the loaded rules");
		Set<String> potential = WhatsNew.potentialIds(rules);
		List<String> eligible = report.recommendations().stream().filter(r -> r.appliable() || r.category() == Category.WARNING)
				.map(Recommendation::id).filter(potential::contains).toList();
		check(!eligible.isEmpty(), "the report shows something the rules produce: " + report.recommendations().stream().map(Recommendation::id).toList());
		JsonObject root = JsonParser.parseString(read(file)).getAsJsonObject();
		JsonObject fingerprint = root.getAsJsonObject("fingerprint");
		check(fingerprint != null, "the startup probe seeded the fingerprint: " + root);
		String current = fingerprint.get("gpuDriverRaw").getAsString();
		String older = current.contains("Mesa") ? "4.5 (Core Profile) Mesa 23.0.0" : "0.0 (an older driver)";
		fingerprint.addProperty("gpuDriverRaw", older);
		TreeSet<String> baseline = new TreeSet<>(potential);
		eligible.forEach(baseline::remove);
		JsonArray ids = new JsonArray();
		baseline.forEach(ids::add);
		root.addProperty("lastSeenRulesRevision", rules.revision - 1);
		root.add("lastSeenRecommendationIds", ids);
		JsonArray dismissed = new JsonArray();
		if (root.get("dismissed") instanceof JsonArray old) {
			for (JsonElement key : old) {
				String k = key.getAsString();
				if (!k.startsWith(AwarenessService.HARDWARE_KEY_PREFIX) && !k.startsWith(AwarenessService.WHATS_NEW_KEY_PREFIX)) {
					dismissed.add(k);
				}
			}
		}
		root.add("dismissed", dismissed);
		write(file, root.toString());
		RigTune.LOGGER.info("AwarenessGameTest: seeded driver '{}' (now '{}'), revision {} without {}", older, current, rules.revision - 1, eligible);
		return current;
	}

	// The slot: the driver notice and the what's-new notice are both there, in priority order, and the slot shows the
	// highest priority with "+N more".
	private static String checkSlot(ClientGameTestContext context, RealController real) {
		List<Notice> visible = notices(context, real);
		Notice hardware = find(visible, AwarenessService.HARDWARE_KEY_PREFIX);
		Notice whatsNew = find(visible, AwarenessService.WHATS_NEW_KEY_PREFIX);
		check(hardware != null, "the hardware notice: " + visible);
		check(whatsNew != null, "the what's-new notice: " + visible);
		check(hardware.message().english().startsWith("Your GPU driver changed since last time ("), hardware.message().english());
		check(whatsNew.message().english().contains("since you last looked: "), whatsNew.message().english());
		check(visible.indexOf(hardware) < visible.indexOf(whatsNew), "HARDWARE_CHANGED before WHATS_NEW: " + visible);
		for (int i = 1; i < visible.size(); i++) {
			check(visible.get(i - 1).priority().ordinal() <= visible.get(i).priority().ordinal(), "priority order: " + visible);
		}
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			Notice shown = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice());
			int others = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).otherNotices());
			check(shown != null && shown.priority() == visible.getFirst().priority(), "the slot shows the highest priority at " + name(size) + ": " + shown);
			check(others == visible.size() - 1 && others >= 1, "\"+N more\" counts the others: " + others);
			context.takeScreenshot("awareness-notices-" + name(size));
		}
		return hardware.key();
	}

	// W-L3: once the driver notice is the slot's notice (top, or cycled to), awareness.json holds the new driver.
	private static void checkShownCommits(ClientGameTestContext context, RealController real, Path file, String hardwareKey, String currentDriver) {
		resize(context, 1280, 720, 2);
		cycleTo(context, hardwareKey);
		context.waitFor(mc -> currentDriver.equals(storedDriver(file)), 100);
		context.takeScreenshot("awareness-driver-notice-1280x720-scale2");
		check(find(notices(context, real), AwarenessService.HARDWARE_KEY_PREFIX) != null, "it stays for the session once shown");
	}

	private static void checkRebenchmark(ClientGameTestContext context, RealController real, String hardwareKey) {
		cycleTo(context, hardwareKey);
		press(context, "rigtune.awareness.rebenchmark");
		context.waitForScreen(BenchmarkMenuScreen.class);
		context.waitTicks(2);
		context.takeScreenshot("awareness-rebenchmark");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(2);
	}

	// Dismissing the driver notice, then what's new (which moves the baseline to this revision); reopened, neither shows.
	private static void checkDismissals(ClientGameTestContext context, RealController real, Path file, String hardwareKey) {
		cycleTo(context, hardwareKey);
		press(context, "rigtune.notice.dismiss");
		context.waitTicks(2);
		check(find(notices(context, real), AwarenessService.HARDWARE_KEY_PREFIX) == null, "the driver notice is gone once dismissed");
		String whatsNewKey = find(notices(context, real), AwarenessService.WHATS_NEW_KEY_PREFIX).key();
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			if (size[0] >= 800) {
				cycleTo(context, whatsNewKey);
			}
			context.takeScreenshot("awareness-whats-new-" + name(size));
		}
		resize(context, 1280, 720, 2);
		cycleTo(context, whatsNewKey);
		press(context, "rigtune.notice.dismiss");
		context.waitTicks(2);
		JsonObject root = JsonParser.parseString(read(file)).getAsJsonObject();
		check(root.get("lastSeenRulesRevision").getAsInt() == real.rules().revision, "dismissing what's new stores this revision: " + root);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		openRigTune(context);
		List<Notice> after = notices(context, real);
		check(find(after, AwarenessService.HARDWARE_KEY_PREFIX) == null && find(after, AwarenessService.WHATS_NEW_KEY_PREFIX) == null,
				"neither notice after dismiss and reopen: " + after);
		context.takeScreenshot("awareness-after-dismiss");
	}

	private static void cycleTo(ClientGameTestContext context, String key) {
		for (int i = 0; i < 10; i++) {
			Notice shown = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice());
			if (shown != null && shown.key().equals(key)) {
				context.waitTicks(2);
				return;
			}
			press(context, "rigtune.notice.more");
			context.waitTicks(2);
		}
		throw new AssertionError("Could not cycle the notice line to " + key);
	}

	private static void press(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = findButton(mc.gui.screen(), key);
			check(button != null, "a '" + key + "' button on " + mc.gui.screen());
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
	}

	private static @Nullable Button findButton(Screen screen, String key) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast).findFirst().orElse(null);
	}

	// On the render thread, where the notice sources run.
	private static List<Notice> notices(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> real.notices());
	}

	private static @Nullable Notice find(List<Notice> notices, String prefix) {
		return notices.stream().filter(n -> n.key().startsWith(prefix)).findFirst().orElse(null);
	}

	private static @Nullable String storedDriver(Path file) {
		try {
			return JsonParser.parseString(read(file)).getAsJsonObject().getAsJsonObject("fingerprint").get("gpuDriverRaw").getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void rescan(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> real.rescan());
		context.waitFor(mc -> real.report() != null, 1200);
	}

	private static void openRigTune(ClientGameTestContext context) {
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
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
