package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.TierResult;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// runProductionSmoke: read-only, so it never applies anything, except -PsmokeDh=stage (DhConfigSmoke, AC7.3). It starts
// the benchmark only with -PsmokeBenchmark (BenchmarkSmoke).
final class ProductionSmoke {
	private static final int REPORT_TIMEOUT_TICKS = 1200;
	private static final int MAX_PAGES = 40;

	private ProductionSmoke() {
	}

	static void run(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(2);
			mc.resizeGui();
		});
		check(context.computeOnClient(mc -> RigTuneClient.controller() instanceof RealController), "real controller in use");

		List<String> restored = restoreUserOptions(context);
		if (!restored.isEmpty()) {
			context.runOnClient(mc -> RigTuneClient.controller().rescan());
		}
		Report report = awaitReport(context);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("smoke-title");

		context.clickScreenButton("rigtune.button");
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("smoke-rigtune-p1");
		int pages = 1;
		while (pages < MAX_PAGES && context.computeOnClient(ProductionSmoke::nextPage)) {
			pages++;
			context.waitTicks(3);
			context.takeScreenshot("smoke-rigtune-p" + pages);
		}
		Report shown = context.computeOnClient(mc -> RigTuneClient.controller().report());
		if (shown != report) {
			RigTune.LOGGER.warn("Smoke: the report changed while paging; the text dump has the newer one");
		}
		writeReport(shown == null ? report : shown, restored, pages);
		v03Screens(context);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(TitleScreen.class);
		String dh = System.getProperty("rigtune.smoke.dh");
		if (dh != null) {
			DhConfigSmoke.run(context, dh, shown == null ? report : shown);
			return;
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			// At render distance 12 the harness's chunk download check never passes in production, even with fabric-api
			// alone; the screenshot doesn't need it.
			try {
				singleplayer.getConnection().waitForChunksDownload(600);
				singleplayer.getConnection().waitForChunksRender(false, 600);
			} catch (AssertionError e) {
				RigTune.LOGGER.warn("Smoke: chunks not all downloaded and rendered after 30 s; taking the in-world screenshot anyway", e);
			}
			context.getInput().pressKey(RigTuneClient.openKey());
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(20);
			context.takeScreenshot("smoke-world");
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(5);
			if (Boolean.getBoolean("rigtune.smoke.benchmark")) {
				BenchmarkSmoke.run(context);
			}
		}
	}

	// v0.3 Phase 5: the footer buttons, the History screen, and Report a problem's confirm screen (Cancel only).
	private static void v03Screens(ClientGameTestContext context) {
		List<String> buttons = context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(Button.class::isInstance).map(b -> ((Button) b).getMessage().getString()).toList());
		RigTune.LOGGER.info("Smoke: RigTune screen buttons {}", buttons);
		context.clickScreenButton("rigtune.history.open");
		context.waitForScreen(HistoryScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen history && !history.loading(), 400);
		context.waitTicks(3);
		context.takeScreenshot("smoke-history");
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);
		String clipboard = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
		context.clickScreenButton("rigtune.report.button");
		context.waitForScreen(ConfirmLinkScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("smoke-report-confirm");
		context.clickScreenButton("gui.cancel");
		context.waitForScreen(RigTuneScreen.class);
		context.runOnClient(mc -> mc.keyboardHandler.setClipboard(clipboard));
	}

	// The game test framework resets some options (render distance 5, clouds off) after options.txt loads.
	// Put the player's values back for the settings RigTune reads, in memory only.
	private static List<String> restoreUserOptions(ClientGameTestContext context) {
		String source = System.getProperty("rigtune.smoke.userOptions");
		if (source == null) {
			return List.of();
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(Path.of(source));
		} catch (IOException e) {
			throw new AssertionError("Could not read " + source, e);
		}
		Map<String, String> current = context.computeOnClient(mc -> SettingsBridge.readVanilla(mc.options));
		Map<String, String> wanted = new LinkedHashMap<>();
		for (String line : lines) {
			int colon = line.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String key = line.substring(0, colon);
			String value = line.substring(colon + 1);
			if (SettingKeys.changeable(SettingKeys.VANILLA_PREFIX + key) && !Objects.equals(unquote(value), current.get(key))) {
				wanted.put(SettingKeys.VANILLA_PREFIX + key, value);
			}
		}
		if (wanted.isEmpty()) {
			return List.of();
		}
		Map<String, SettingsBridge.Result> results = context.computeOnClient(mc -> SettingsBridge.applyVanilla(mc.options, wanted, false));
		List<String> notes = new ArrayList<>();
		results.forEach((key, r) -> notes.add(r.key() + ": " + r.oldValue() + " -> " + r.newValue() + (r.ok() ? "" : " (FAILED: " + r.message() + ")")));
		RigTune.LOGGER.info("Smoke: restored the player's options after the game test reset: {}", notes);
		return notes;
	}

	private static String unquote(String value) {
		return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
	}

	// Waits for the Modrinth data, then for the report to settle: remote rules can rebuild it once more.
	private static Report awaitReport(ClientGameTestContext context) {
		for (int waited = 0; waited < REPORT_TIMEOUT_TICKS && !ready(context.computeOnClient(mc -> RigTuneClient.controller().report())); waited += 10) {
			context.waitTicks(10);
		}
		Report last = null;
		for (int i = 0; i < 10; i++) {
			Report now = context.computeOnClient(mc -> RigTuneClient.controller().report());
			if (now != null && now == last) {
				break;
			}
			last = now;
			context.waitTicks(40);
		}
		check(last != null, "report built");
		if (!last.online()) {
			RigTune.LOGGER.warn("Smoke: no Modrinth data within {} ticks; the report is offline", REPORT_TIMEOUT_TICKS);
		}
		return last;
	}

	private static boolean ready(@Nullable Report report) {
		return report != null && report.online();
	}

	private static boolean nextPage(Minecraft mc) {
		AbstractSelectionList<?> list = mc.gui.screen().children().stream()
				.filter(AbstractSelectionList.class::isInstance)
				.map(c -> (AbstractSelectionList<?>) c)
				.findFirst()
				.orElseThrow(() -> new AssertionError("RigTune screen has no list"));
		if (list.scrollAmount() >= list.maxScrollAmount()) {
			return false;
		}
		list.setScrollAmount(list.scrollAmount() + list.getHeight() - 24);
		return true;
	}

	private static void writeReport(Report report, List<String> restored, int pages) {
		StringBuilder out = new StringBuilder();
		HardwareProfile hw = report.hardware();
		TierResult tier = report.tier();
		out.append("RigTune production smoke report\n");
		out.append("Created ").append(report.createdAt()).append(", goal ").append(report.goal())
				.append(", rules revision ").append(report.rulesRevision()).append(" (").append(report.rulesSource()).append(")")
				.append(", online ").append(report.online()).append('\n');
		if (!restored.isEmpty()) {
			out.append("Restored after the game test reset: ").append(String.join("; ", restored)).append('\n');
		}
		out.append("\nHardware\n");
		out.append("  CPU      ").append(hw.cpu().name()).append(", ").append(hw.cpu().physicalCores()).append(" cores / ")
				.append(hw.cpu().logicalCores()).append(" threads, max ").append(hw.cpu().maxFreqMhz()).append(" MHz\n");
		out.append("  GPU      ").append(hw.gpu().renderer()).append(" (").append(hw.gpu().vendorString()).append("), driver ")
				.append(hw.gpu().driverVersion()).append(", ").append(hw.gpu().backend()).append(", VRAM ").append(hw.gpu().vramMb()).append(" MB\n");
		out.append("  GPU class ").append(report.gpuClass()).append('\n');
		out.append("  Memory   ").append(hw.totalRamMb()).append(" MB RAM, ").append(hw.maxHeapMb()).append(" MB max heap\n");
		out.append("  Display  ").append(hw.display().width()).append('x').append(hw.display().height()).append(" @ ")
				.append(hw.display().refreshRate()).append(" Hz, fullscreen ").append(hw.display().fullscreen()).append('\n');
		out.append("  Battery  present ").append(hw.hasBattery()).append(", on battery ").append(hw.onBattery()).append('\n');
		out.append("  System   ").append(hw.osName()).append(", Minecraft ").append(hw.mcVersion()).append(", flags ").append(hw.flags()).append('\n');
		out.append("\nTier ").append(tier.rawTier()).append(" (effective ").append(tier.effectiveTier()).append("): GPU ").append(tier.gpuTier())
				.append(", CPU ").append(tier.cpuTier()).append(", memory ").append(tier.memTier()).append(", limited by ").append(tier.limitingFactor()).append('\n');

		Map<Category, List<Recommendation>> byCategory = new EnumMap<>(Category.class);
		report.recommendations().forEach(r -> byCategory.computeIfAbsent(r.category(), c -> new ArrayList<>()).add(r));
		out.append("\nRecommendations: ").append(report.recommendations().size()).append(" (");
		List<String> counts = new ArrayList<>();
		byCategory.forEach((c, list) -> counts.add(c + " " + list.size()));
		out.append(String.join(", ", counts)).append("), shown on ").append(pages).append(" screen page(s)\n");
		byCategory.forEach((category, list) -> {
			out.append("\n== ").append(category).append(" (").append(list.size()).append(")\n");
			for (Recommendation r : list) {
				out.append("- [").append(r.impact()).append("] ").append(r.title()).append(r.selectedByDefault() ? "  (ticked)" : "").append('\n');
				out.append("    reason: ").append(r.reason()).append('\n');
				out.append("    action: ").append(describe(r.action())).append('\n');
				out.append("    id: ").append(r.id()).append('\n');
			}
		});

		List<ModContainer> mods = FabricLoader.getInstance().getAllMods().stream()
				.filter(m -> m.getContainingMod().isEmpty())
				.sorted(Comparator.comparing(m -> m.getMetadata().getId()))
				.toList();
		out.append("\nTop-level mods loaded (").append(mods.size()).append(")\n");
		for (ModContainer mod : mods) {
			out.append("  ").append(mod.getMetadata().getId()).append(' ').append(mod.getMetadata().getVersion().getFriendlyString()).append('\n');
		}

		Path file = FabricLoader.getInstance().getGameDir().resolve("rigtune-smoke-report.txt");
		try {
			Files.writeString(file, out.toString());
		} catch (IOException e) {
			throw new AssertionError("Could not write " + file, e);
		}
		RigTune.LOGGER.info("Smoke: wrote {}", file);
	}

	private static String describe(Action action) {
		return switch (action) {
			case Action.None ignored -> "none (advice only)";
			case Action.AddMod add -> "add " + add.title() + " (Modrinth " + add.slug() + ", " + add.projectId() + ")";
			case Action.UpdateMod update -> "update " + update.modId() + " " + update.update().currentVersion() + " -> "
					+ update.update().newVersionNumber() + " (" + update.currentFile().getFileName()
					+ (update.update().file() == null ? "" : " -> " + update.update().file().filename()) + ")";
			case Action.DisableMod disable -> "disable " + disable.modId() + " (" + disable.file().getFileName() + ")";
			case Action.SetSetting set -> "set " + set.key() + ": " + set.currentValue() + " -> " + set.newValue();
		};
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
