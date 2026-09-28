package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.awareness.AwarenessService;
import io.github.chaotix345.rigtune.client.awareness.OutsideChanges;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.client.probe.PreloadTimer;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.awareness.WhatsNew;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;
import io.github.chaotix345.rigtune.core.hardware.PerfCounterAdvice;
import io.github.chaotix345.rigtune.core.hardware.PerfCounters;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

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
		Path file = real.awarenessService().file();
		context.waitFor(mc -> Files.isRegularFile(file), 200);
		String original = read(file);
		try {
			GameTestNet.set(context, real, false);
			String currentDriver = seed(context, real, file);
			rescan(context, real);
			openRigTune(context);

			String hardwareKey = checkSlot(context, real);
			checkShownCommits(context, real, file, hardwareKey, currentDriver);
			checkRebenchmark(context, real, hardwareKey);
			checkDismissals(context, real, file, hardwareKey);
			RigTune.LOGGER.info("AwarenessGameTest: driver notice (shown -> committed), Re-benchmark, what's new and dismissals checked");
			// v0.5 (docs/v0.5/SPEC.md C6; PLAN contracts item 16): one block per owner, with the contracts' context record.
			V05TestContext v05 = V05TestContext.of(context);
			awarenessFixes(v05);
			startupRegression(v05);
			settingsChangedOutside(v05);
		} finally {
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			write(file, original);
			GameTestNet.set(context, real, true);
			resize(context, 854, 480, 0);
		}
	}

	// --- v0.5 blocks (docs/v0.5/SPEC.md C6): one method per owner; an owner edits only its own method's body and adds its
	// own private helpers right below it. Each block puts back what it changed.

	// ---- WS-W (AW-1, AW-2).

	// AW-2 (AC2W.2): NoticeScreen at 1280x720 GUI scale 3 (SPEC X12 as amended: the game caps 854x480 at scale 2) lists
	// as many canned notices as fit, above a fresh driver notice that then doesn't fit; dismissing the top one rebuilds
	// it, the driver notice is listed and awareness.json holds the new driver. AW-1 (AC2W.1): a rescan keeps the shown,
	// committed notice; its own Re-scan retires it. The 0.4 block dismissed the same key for this session, so the notice
	// line never shows it: a wrapper lists it on NoticeScreen under the canned ones.
	private static void awarenessFixes(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		RealController real = v05.realController();
		Path file = real.awarenessService().file();
		// The 0.4 block's driver notice was seen (committed) this session, and the same change reported again stays seen
		// (review L3): retire it through its own Re-scan first, so the one seeded next is fresh.
		Notice seen = context.computeOnClient(mc -> real.awarenessService().hardwareNotice());
		if (seen != null) {
			context.runOnClient(mc -> real.noticeAction(seen.key(), AwarenessService.RESCAN));
			context.waitFor(mc -> real.report() != null && real.awarenessService().hardwareNotice() == null, 1200);
		}
		String current = seedOlderDriver(file);
		rescan(context, real);
		Notice hardware = context.computeOnClient(mc -> real.awarenessService().hardwareNotice());
		check(hardware != null && hardware.message().english().startsWith("Your GPU driver changed since last time ("), "a fresh driver notice: " + hardware);
		check(!current.equals(storedDriver(file)), "not shown yet, so not committed");
		ListedNotices listed = new ListedNotices(real, 12);
		try {
			v05.resize(1280, 720, 3);
			// How many rows fit, with canned notices only (so the driver notice is never listed before the dismissal).
			int fits = openNotices(context, listed).size();
			check(fits >= 2 && fits < 12, "some canned notices fit, not all: " + fits);
			listed.keep(fits);
			List<Notice> before = openNotices(context, listed);
			check(context.computeOnClient(mc -> listed.notices().size()) >= 3, "at least three notices");
			check(before.size() == fits && before.stream().noneMatch(n -> n.key().equals(hardware.key())),
					"the driver notice doesn't fit below the " + fits + " canned ones: " + before);
			screenshot(context, "awareness-aw2-before-1280x720-scale3");
			check(!current.equals(storedDriver(file)), "not listed, so not committed");

			press(context, "rigtune.notice.dismiss");
			List<Notice> after = context.computeOnClient(mc -> ((NoticeScreen) mc.gui.screen()).shown());
			check(after.stream().anyMatch(n -> n.key().equals(hardware.key())), "listed after the rebuild: " + after);
			context.waitFor(mc -> current.equals(storedDriver(file)), 100);
			screenshot(context, "awareness-aw2-after-1280x720-scale3");
			RigTune.LOGGER.info("AwarenessGameTest: AW-2: the driver notice listed after a NoticeScreen rebuild committed {}", current);

			context.runOnClient(mc -> real.rescan());
			context.waitFor(mc -> real.report() != null, 1200);
			Notice kept = context.computeOnClient(mc -> real.awarenessService().hardwareNotice());
			check(kept != null && kept.key().equals(hardware.key()), "AW-1: a rescan keeps the shown notice: " + kept);

			press(context, "rigtune.awareness.rescan");
			context.waitFor(mc -> real.report() != null && real.awarenessService().hardwareNotice() == null, 1200);
			check(context.computeOnClient(mc -> ((NoticeScreen) mc.gui.screen()).shown()).stream().noneMatch(n -> n.key().equals(hardware.key())),
					"its own Re-scan retired it");
			screenshot(context, "awareness-aw1-retired-1280x720-scale3");
			RigTune.LOGGER.info("AwarenessGameTest: AW-1: the committed driver notice survived a rescan; its own Re-scan retired it");
		} finally {
			v05.resize(1280, 720, 2);
			openRigTune(context);
		}
	}

	// An older driver string in the fingerprint (as seed() does); returns the stored, current one.
	private static String seedOlderDriver(Path file) {
		JsonObject root = JsonParser.parseString(read(file)).getAsJsonObject();
		JsonObject fingerprint = root.getAsJsonObject("fingerprint");
		String current = fingerprint.get("gpuDriverRaw").getAsString();
		fingerprint.addProperty("gpuDriverRaw", current.contains("Mesa") ? "4.5 (Core Profile) Mesa 23.0.0" : "0.0 (an older driver)");
		write(file, root.toString());
		return current;
	}

	// A fresh NoticeScreen over `controller`; what it lists.
	private static List<Notice> openNotices(ClientGameTestContext context, ListedNotices controller) {
		context.runOnClient(mc -> mc.gui.setScreen(new NoticeScreen(new TitleScreen(), controller)));
		context.waitForScreen(NoticeScreen.class);
		context.waitTicks(3);
		return context.computeOnClient(mc -> ((NoticeScreen) mc.gui.screen()).shown());
	}

	// The real controller with canned, dismissible notices listed above the hardware notice (the source's own, not
	// filtered by this session's dismissals; only once keep() has cut the canned ones to what fits); their dismissals stay
	// here.
	private static final class ListedNotices extends ForwardingController {
		private final RealController real;
		private final List<Notice> canned = new ArrayList<>();
		private volatile boolean withHardware;

		ListedNotices(RealController real, int count) {
			super(real);
			this.real = real;
			for (int i = 1; i <= count; i++) {
				canned.add(new Notice("test-aw2-" + i, NoticePriority.BENCHMARK_REGRESSION, Text.literal("A notice listed above the driver notice (" + i + ")"),
						null, List.of(), true));
			}
		}

		void keep(int count) {
			canned.subList(count, canned.size()).clear();
			withHardware = true;
		}

		@Override
		public List<Notice> notices() {
			List<Notice> out = new ArrayList<>(canned);
			Notice hardware = real.awarenessService().hardwareNotice();
			if (withHardware && hardware != null) {
				out.add(hardware);
			}
			return out;
		}

		@Override
		public void dismissNotice(String key) {
			if (!canned.removeIf(n -> n.key().equals(key))) {
				super.dismissNotice(key);
			}
		}
	}

	// ---- WS-W2 (C18, AC9.2-AC9.3): a seeded startup-times.json and the STARTUP_REGRESSION notice.

	// startup-times.json seeded behind the real StartupTimes, which then recomputes (as its worker does after recording a
	// launch). From 4 comparable launches: no notice, no Tools row. From 5 of 10 s and one of 14.5 s with 12 more mods: the
	// notice with the numbers and exactly the mod-count line (screenshots at the three sizes); Tools… opens ToolsScreen with
	// the same two rows and leaves the notice; with Windows' performance counters off (seeded) the detail adds one line of
	// 2L's advice; Got it: gone on the rebuild, on reopening and after a rescan, the key in awareness.json, and a new
	// StartupTimes (the next launch's) reads it as acknowledged; a slower launch right after is the same streak (review H1:
	// no second notice), while a slowdown after an in-line launch fires with its own key. startup-times.json and the view are
	// put back (FootprintGameTest checks this launch's one run later); awareness.json by runTest.
	private static void startupRegression(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		RealController real = v05.realController();
		Path file = StartupTimesStore.file(real.configDir());
		context.waitFor(mc -> Files.isRegularFile(file), 200);
		String original = read(file);
		String mcVersion = FabricLoader.getInstance().getRawGameVersion();
		PerfCounters on = new PerfCounters(true, false, List.of(), List.of());
		try {
			HardwareProbe.seedPerfCounters(on);
			seedLaunches(real, file, mcVersion, 4, "2026-09-20T10:30:00Z", 14_500, 82);
			check(findStartup(context, real) == null, "no notice from 4 comparable launches: " + notices(context, real));
			openTools(context, real);
			check(context.computeOnClient(mc -> ((ToolsScreen) mc.gui.screen()).regressionLines()).isEmpty(), "no Tools row from 4 comparable launches");

			String key = seedLaunches(real, file, mcVersion, 5, "2026-09-20T10:30:00Z", 14_500, 82);
			Notice notice = findStartup(context, real);
			check(notice != null && notice.key().equals(key) && notice.priority() == NoticePriority.STARTUP_REGRESSION, "the notice: " + notices(context, real));
			check(notice.message().english().equals("Launch time 45% higher than usual (14.5 s vs your usual ~10.0 s)"), notice.message().english());
			check(notice.detail() != null && notice.detail().english().equals("May be related to your mod set changing (70 → 82 mods) since your last launch"),
					"exactly one cause line: " + notice.detail());
			check(!notice.dismissible() && notice.actions().stream().map(a -> a.label().english()).toList().equals(List.of("Tools…", "Got it")),
					"Tools… and Got it: " + notice);
			openRigTune(context);
			for (int[] size : SIZES) {
				resize(context, size[0], size[1], size[2]);
				cycleTo(context, key);
				screenshot(context, "awareness-startup-regression-" + name(size));
			}

			resize(context, 1280, 720, 2);
			cycleTo(context, key);
			press(context, "rigtune.startup.notice.tools");
			context.waitForScreen(ToolsScreen.class);
			context.waitTicks(2);
			List<String> rows = context.computeOnClient(mc -> ((ToolsScreen) mc.gui.screen()).regressionLines().stream().map(Component::getString).toList());
			check(rows.equals(List.of(notice.message().english(), notice.detail().english())), "Tools… shows the same two lines: " + rows);
			screenshot(context, "awareness-startup-tools-1280x720-scale2");
			context.runOnClient(mc -> mc.gui.screen().onClose());
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(2);
			check(findStartup(context, real) != null, "Tools… doesn't acknowledge it");

			PerfCounters off = new PerfCounters(true, true, List.of(), List.of("PerfOS"));
			HardwareProbe.seedPerfCounters(off);
			Notice withAdvice = findStartup(context, real);
			// Review L5: this launch's measured crash-report setup (CI's production client measures it), else what the setting is.
			String adviceLine = PerfCounterAdvice.lines(off, PreloadTimer.preloadMs()).stream().map(l -> l.text().english())
					.filter(t -> PreloadTimer.preloadMs() == null || t.startsWith("Minecraft's crash-report setup took")).findFirst().orElseThrow();
			check(withAdvice != null && withAdvice.detail() != null && withAdvice.detail().english().equals(notice.detail().english() + "\n" + adviceLine),
					"one line of 2L's advice in the detail: " + withAdvice);
			HardwareProbe.seedPerfCounters(on);

			cycleTo(context, key);
			press(context, "rigtune.startup.notice.acknowledge");
			context.waitTicks(2);
			check(findStartup(context, real) == null, "Got it: gone");
			Notice shown = context.computeOnClient(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice());
			check(shown == null || !shown.key().equals(key), "not on the rebuilt notice line: " + shown);
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			openRigTune(context);
			check(findStartup(context, real) == null, "not after reopening");
			context.runOnClient(mc -> real.rescan());
			context.waitFor(mc -> real.report() != null, 1200);
			check(findStartup(context, real) == null, "not after a rescan (the notice list rebuilt)");
			JsonObject root = JsonParser.parseString(read(real.awarenessService().file())).getAsJsonObject();
			check(root.get("acknowledgedStartupRegressions") instanceof JsonArray keys && keys.toString().contains("\"" + key + "\""),
					"awareness.json keeps it: " + root);
			StartupTimes next = new StartupTimes(real, real.configDir());
			next.refresh();
			check(next.acknowledged(key), "the next launch's StartupTimes reads it back");

			appendLaunch(real, mcVersion, "2026-09-20T10:31:00Z", 15_000, 82);
			StartupTrend.Assessment streak = real.startupTimesService().view().assessment();
			check(streak.slower() && streak.streak() == 2 && key.equals(StartupTrend.key(streak)), "the same slow streak: " + StartupTrend.describe(streak));
			check(findStartup(context, real) == null, "one Got it covers the streak: " + notices(context, real));
			appendLaunch(real, mcVersion, "2026-09-20T10:32:00Z", 10_000, 82);
			String later = appendLaunch(real, mcVersion, "2026-09-20T10:33:00Z", 15_500, 82);
			Notice again = findStartup(context, real);
			check(again != null && again.key().equals(later) && !later.equals(key), "after an in-line launch, a slowdown has its own key: " + again);
			check(again.detail() != null && again.detail().english().startsWith("No change recorded since your last launch"), "nothing changed since the one before: "
					+ again.detail());
			RigTune.LOGGER.info("AwarenessGameTest: C18: none from 4 launches; the notice, Tools…, 2L's line, Got it (kept, covers the streak), a new slowdown's own key");
		} finally {
			write(file, original);
			real.startupTimesService().refresh();
			HardwareProbe.seedPerfCounters(null);
			resize(context, 1280, 720, 2);
			openRigTune(context);
		}
	}

	// `comparable` launches of 10 s with 70 mods (this Minecraft and RigTune version), then the latest at `at`; the real
	// StartupTimes recomputes. Returns the latest launch's notice key.
	private static String seedLaunches(RealController real, Path file, String mcVersion, int comparable, String at, long ms, int mods) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new AssertionError("Could not remove " + file, e);
		}
		StartupTimesStore store = new StartupTimesStore(real.configDir());
		for (int i = 0; i < comparable; i++) {
			store.record(new StartupTimesStore.Run("2026-09-20T10:0" + i + ":00Z", 10_000, mcVersion, real.modVersion(), 70, "seeded-70"));
		}
		return appendLaunch(real, mcVersion, at, ms, mods);
	}

	private static String appendLaunch(RealController real, String mcVersion, String at, long ms, int mods) {
		new StartupTimesStore(real.configDir()).record(new StartupTimesStore.Run(at, ms, mcVersion, real.modVersion(), mods, "seeded-" + mods));
		real.startupTimesService().refresh();
		return StartupTrend.KEY_PREFIX + at;
	}

	private static @Nullable Notice findStartup(ClientGameTestContext context, RealController real) {
		return find(notices(context, real), StartupTrend.KEY_PREFIX);
	}

	private static void openTools(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), real)));
		context.waitForScreen(ToolsScreen.class);
		context.waitTicks(2);
	}

	// ---- WS-W (4h, AC4h.2): the options snapshot and SETTINGS_CHANGED_OUTSIDE.

	// A session's clean exit and the next start, in one run: RigTune applies a vanilla key (an ordinary Apply), the stop
	// snapshot is stored (the call CLIENT_STOPPING makes), options.txt is changed and loaded behind RigTune's back (as the
	// Modrinth App's sync does before a launch), then the start comparison runs on the worker. Under brand theseus the
	// notice carries the app's steps; "Apply RigTune's values again" writes one journal entry, which Undo reverts; Keep
	// retires it and writes nothing. The option, the brand and the launcher are put back (awareness.json by runTest).
	private static void settingsChangedOutside(V05TestContext v05) {
		ClientGameTestContext context = v05.context();
		RealController real = v05.realController();
		Journal journal = ClientJournal.get();
		String original = option(context, OUTSIDE_KEY);
		String rigtune = "true".equals(original) ? "false" : "true";
		String brand = System.getProperty(LauncherSignals.BRAND);
		LauncherInfo launcher = context.computeOnClient(mc -> real.launcher());
		try {
			System.setProperty(LauncherSignals.BRAND, "theseus");
			redetect(context, real);
			context.waitFor(mc -> real.launcher().launcher() == Launcher.MODRINTH_APP && real.report() != null, 1200);

			Component applied = context.computeOnClient(mc -> real.apply(List.of(outsideSet(original, rigtune))));
			check(rigtune.equals(option(context, OUTSIDE_KEY)), "RigTune applied it: " + applied.getString());
			check(hasKey(applied, "rigtune.outside.sync_line"), "the Apply status names the Modrinth App's sync (AC4h.4): " + applied.getString());
			Notice notice = restartWithOutsideChange(context, real, original);
			// Other classes' vanilla changes may be flagged too (a key they changed in memory only); this one must be.
			check(notice.message().english().contains("changed outside the game since you last played (")
					&& notice.detail() != null && notice.detail().english().contains(described(real, rigtune, original)), notice.message().english() + " / "
					+ notice.detail());
			check(notice.detail() != null && notice.detail().english().contains("App settings → Synced settings → Sync game options"),
					"the Modrinth App's steps: " + notice.detail());
			openRigTune(context);
			for (int[] size : SIZES) {
				resize(context, size[0], size[1], size[2]);
				cycleTo(context, notice.key());
				screenshot(context, "awareness-outside-" + name(size));
			}
			int entries = journal.entries().size();
			press(context, "rigtune.outside.reapply");
			context.waitFor(mc -> rigtune.equals(option(mc, OUTSIDE_KEY)), 100);
			List<JournalEntry> after = journal.entries();
			check(after.size() == entries + 1, "one journal entry: " + entries + " -> " + after.size());
			JournalEntry again = after.getLast();
			check(JournalEntry.APPLY.equals(again.kind()) && again.changes().stream().anyMatch(c -> ("vanilla." + OUTSIDE_KEY).equals(c.key())
					&& original.equals(c.before()) && rigtune.equals(c.after())), "an ordinary Apply of RigTune's value: " + again);
			check(findOutside(context, real) == null, "gone once applied again");
			UndoPlan plan = context.computeOnClient(mc -> real.undoPlanFor(again.id()));
			check(plan != null && plan.problem() == null && !plan.items().isEmpty(), "Undo this plans: " + plan);
			context.runOnClient(mc -> real.undo(plan));
			context.waitFor(mc -> original.equals(option(mc, OUTSIDE_KEY)), 100);
			RigTune.LOGGER.info("AwarenessGameTest: 4h: the outside change was flagged under theseus, applied again as one entry, undone");

			context.runOnClient(mc -> real.apply(List.of(outsideSet(original, rigtune))));
			Notice kept = restartWithOutsideChange(context, real, original);
			openRigTune(context);
			cycleTo(context, kept.key());
			int before = journal.entries().size();
			press(context, "rigtune.outside.keep");
			check(findOutside(context, real) == null, "Keep retires it");
			check(journal.entries().size() == before && original.equals(option(context, OUTSIDE_KEY)), "Keep changes nothing");
			RigTune.LOGGER.info("AwarenessGameTest: 4h: Keep retired the notice and wrote nothing");
		} finally {
			Notice left = findOutside(context, real);
			if (left != null) {
				context.runOnClient(mc -> real.noticeAction(left.key(), OutsideChanges.KEEP));
			}
			context.runOnClient(mc -> SettingsBridge.applyVanilla(mc.options, Map.of(OUTSIDE_KEY, original)));
			if (brand == null) {
				System.clearProperty(LauncherSignals.BRAND);
			} else {
				System.setProperty(LauncherSignals.BRAND, brand);
			}
			redetect(context, real);
			context.waitFor(mc -> real.launcher().equals(launcher) && real.report() != null, 1200);
			resize(context, 1280, 720, 2);
			openRigTune(context);
		}
	}

	private static final String OUTSIDE_KEY = "entityShadows";

	// The stop snapshot (as CLIENT_STOPPING takes it), the key changed in options.txt and loaded (as the Modrinth App's sync
	// writes it before a launch), then the start comparison on the worker; returns the notice.
	private static Notice restartWithOutsideChange(ClientGameTestContext context, RealController real, String outside) {
		context.runOnClient(mc -> OutsideChanges.snapshotAtStop(real, mc));
		JsonObject root = JsonParser.parseString(read(real.awarenessService().file())).getAsJsonObject();
		check(root.get("optionsAtExit") instanceof JsonObject snapshot && snapshot.has("vanilla." + OUTSIDE_KEY), "the stop snapshot: " + root);
		context.runOnClient(mc -> SettingsBridge.applyVanilla(mc.options, Map.of(OUTSIDE_KEY, outside)));
		CompletableFuture.runAsync(() -> OutsideChanges.compareAtStart(real), Probes.EXECUTOR).join();
		check(!JsonParser.parseString(read(real.awarenessService().file())).getAsJsonObject().has("optionsAtExit"), "the snapshot is consumed");
		Notice notice = findOutside(context, real);
		check(notice != null, "SETTINGS_CHANGED_OUTSIDE: " + notices(context, real));
		return notice;
	}

	private static @Nullable Notice findOutside(ClientGameTestContext context, RealController real) {
		return find(notices(context, real), OutsideChanges.KEY_PREFIX);
	}

	private static String option(ClientGameTestContext context, String key) {
		return context.computeOnClient(mc -> option(mc, key));
	}

	private static String option(net.minecraft.client.Minecraft mc, String key) {
		return SettingsBridge.readVanilla(mc.options).get(key);
	}

	private static Recommendation outsideSet(String from, String to) {
		return new Recommendation("set:vanilla." + OUTSIDE_KEY, Category.SETTING, Impact.LOW, "Entity shadows", "",
				new Action.SetSetting("vanilla." + OUTSIDE_KEY, from, to), true);
	}

	// "Entity Shadows: On → Off", with the rules' labels, as the notice names a change.
	private static String described(RealController real, String from, String to) {
		RulesDocument rules = real.rules();
		String key = "vanilla." + OUTSIDE_KEY;
		return SettingValues.describe(rules == null ? null : rules.settingLabels.get(key), key, from, to).english();
	}

	private static boolean hasKey(Component component, String key) {
		if (component.getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
			return true;
		}
		return component.getSiblings().stream().anyMatch(c -> hasKey(c, key));
	}

	private static void redetect(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> {
			LauncherProbe.reset();
			real.rescan();
		});
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
			check(others >= 1, "\"+N more\" counts the others: " + others);
			screenshot(context, "awareness-notices-" + name(size));
		}
		return hardware.key();
	}

	// W-L3: once the driver notice is the slot's notice (top, or cycled to), awareness.json holds the new driver.
	private static void checkShownCommits(ClientGameTestContext context, RealController real, Path file, String hardwareKey, String currentDriver) {
		resize(context, 1280, 720, 2);
		cycleTo(context, hardwareKey);
		context.waitFor(mc -> currentDriver.equals(storedDriver(file)), 100);
		screenshot(context, "awareness-driver-notice-1280x720-scale2");
		check(find(notices(context, real), AwarenessService.HARDWARE_KEY_PREFIX) != null, "it stays for the session once shown");
	}

	private static void checkRebenchmark(ClientGameTestContext context, RealController real, String hardwareKey) {
		cycleTo(context, hardwareKey);
		press(context, "rigtune.awareness.rebenchmark");
		context.waitForScreen(BenchmarkMenuScreen.class);
		context.waitTicks(2);
		screenshot(context, "awareness-rebenchmark");
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
			screenshot(context, "awareness-whats-new-" + name(size));
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
		screenshot(context, "awareness-after-dismiss");
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
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
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

	// The probe this rescan starts compares with the seeded file (an earlier rescan's report can come first).
	private static void rescan(ClientGameTestContext context, RealController real) {
		context.runOnClient(mc -> real.rescan());
		context.waitFor(mc -> real.report() != null && real.awarenessService().hardwareNotice() != null, 1200);
	}

	private static void openRigTune(ClientGameTestContext context) {
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
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

	// No toast over the notice line; the cursor in a corner.
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
