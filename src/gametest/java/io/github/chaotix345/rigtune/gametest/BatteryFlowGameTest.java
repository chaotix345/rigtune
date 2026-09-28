package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.PowerWatcher;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.profile.ProfileService;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.profile.BatteryPrompt;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// docs/v0.5/SPEC.md 3e (AC3e.1): the battery flow through the REAL PowerWatcher (PowerWatcher.start, its debounce, its
// thread) over a fake PowerWatcher.Battery polled every second, into the game's ProfileService.powerChanged. On
// discharge the Battery notice [switch, snooze] and the toast come within 5 s and nothing switches; taking it makes
// Battery active (60 FPS); on AC the back-offer names the previous profile and taking it restores it. Variants: a plug
// wiggle shorter than the debounce, no active profile (PF-1: My settings back), snooze, the 10-minute cooldown from a
// lastPromptAt fixture, a running benchmark, a throwing listener. RigTune's network is off (X1).
// AC3e.2 (the Linux release-tier leg only, -Drigtune.gametest.fakeBattery=<BAT0/uevent>): the game's own OSHI reads a
// tmpfs /sys/class/power_supply (-Doshi.os.linux.allowudev=false); the startup probe found a battery on battery power, the
// game's watcher runs, and rewriting STATUS gives AC -> battery -> AC through its real 30 s polls.
public class BatteryFlowGameTest implements FabricClientGameTest {
	private static final String MAX_FPS = ProfileStore.TEMPLATE_PREFIX + ProfileTemplates.TemplateId.MAX_FPS.id();
	private static final long EDGE_MILLIS = 5000;
	private static final int TICKS = 1200;
	// The game's watcher polls every PowerWatcher.PERIOD_SECONDS and needs two polls in a row: at most about 60 s an edge.
	private static final int OSHI_EDGE_TICKS = 20 * 120;

	private final Path configDir = FabricLoader.getInstance().getConfigDir();

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, TICKS);
		RealController real = V05TestContext.of(context).realController();
		String uevent = System.getProperty("rigtune.gametest.fakeBattery");
		Map<Path, byte[]> saved = backup(ProfileStore.file(configDir), Journal.file(configDir), PendingActions.defaultPath(configDir),
				ApplyResult.defaultPath(configDir), configDir.resolve("sodium-options.json"));
		Map<String, String> original = context.computeOnClient(mc -> vanilla(mc));
		boolean onBattery = real.report().hardware().onBattery();
		boolean network = GameTestNet.set(context, real, false);
		try {
			if (uevent != null) {
				oshi(context, real, Path.of(uevent));
			} else {
				offerTakeAndBack(context, real);
				wiggle(context, real);
				noActiveProfile(context, real);
				snooze(context, real);
				cooldown(context, real);
				benchmark(context, real);
				throwingListener(context);
			}
		} finally {
			ProfileService.overrideBenchmarkCheck(null);
			HardwareProbe.setOnBattery(onBattery);
			GameTestNet.set(context, real, network);
			context.runOnClient(mc -> {
				real.discardPending();
				SettingsBridge.applyVanilla(mc.options, original);
				mc.gui.toastManager().clear();
				mc.gui.setScreen(new TitleScreen());
			});
			restore(saved);
			context.runOnClient(mc -> real.rescan());
			context.waitFor(mc -> real.report() != null, TICKS);
		}
		RigTune.LOGGER.info("BatteryFlowGameTest: {}", uevent != null ? "the OSHI leg passed" : "the simulated battery flows passed");
	}

	// Max FPS active; unplugged: the offer and the toast within 5 s, nothing switched; taken: Battery (60 FPS); plugged in:
	// "Switch back to Max FPS?" [switch] (PF-2: no snooze on this one); taken: Max FPS again.
	private void offerTakeAndBack(ClientGameTestContext context, RealController real) {
		fresh(context);
		context.runOnClient(mc -> real.switchProfile(MAX_FPS));
		check(MAX_FPS.equals(store().active()), "Max FPS is active");
		String maxFps = context.computeOnClient(mc -> vanilla(mc).get("vanilla.maxFps"));
		try (Watch watch = new Watch(real.profileService()::powerChanged)) {
			Notice offer = unplug(context, real, watch);
			check(offer.key().startsWith(ProfileService.NOTICE_BATTERY) && actions(offer).equals(List.of(ProfileService.ACTION_SWITCH, ProfileService.ACTION_SNOOZE)),
					"the Battery offer [switch, snooze]: " + offer);
			check(MAX_FPS.equals(store().active()) && maxFps.equals(context.computeOnClient(mc -> vanilla(mc).get("vanilla.maxFps"))),
					"the offer switched nothing");
			screenshot(context, real, "battery-offer");
			take(context, real, offer, ProfileService.ACTION_SWITCH);
			check(BatteryPrompt.BATTERY.equals(store().active()), "taking it makes Battery active");
			check("60".equals(context.computeOnClient(mc -> vanilla(mc).get("vanilla.maxFps"))), "Battery's 60 FPS cap");

			Notice back = plugIn(context, real, watch);
			check(back.key().startsWith(ProfileService.NOTICE_BACK) && back.message().english().contains("Max FPS")
					&& actions(back).equals(List.of(ProfileService.ACTION_SWITCH)), "the back-offer names Max FPS: " + back);
			screenshot(context, real, "battery-back-offer");
			take(context, real, back, ProfileService.ACTION_SWITCH);
			check(MAX_FPS.equals(store().active()), "taking it restores Max FPS: " + store().active());
			check(watch.edges.equals(List.of(true, false)), "two edges: " + watch.edges);
		}
	}

	// One poll on battery, then AC again: shorter than the debounce, so no edge, no notice, no toast.
	private void wiggle(ClientGameTestContext context, RealController real) {
		fresh(context);
		try (Watch watch = new Watch(real.profileService()::powerChanged)) {
			watch.battery.script.add(true);
			int polls = watch.battery.polls.get();
			context.waitFor(mc -> watch.battery.script.isEmpty() && watch.battery.polls.get() >= polls + 4, TICKS);
			context.waitTicks(3);
			check(watch.edges.isEmpty(), "a wiggle gives no edge: " + watch.edges);
			check(batteryNotice(context, real) == null && toast(context) == null, "and no notice or toast");
		}
	}

	// PF-1: no profile active. Taking the offer saves My settings first and remembers it; plugged in, the back-offer
	// targets My settings, and taking it makes My settings active.
	private void noActiveProfile(ClientGameTestContext context, RealController real) {
		fresh(context);
		check(store().active() == null, "no profile active");
		try (Watch watch = new Watch(real.profileService()::powerChanged)) {
			take(context, real, unplug(context, real, watch), ProfileService.ACTION_SWITCH);
			ProfileStore.Profile mine = store().baseline();
			check(mine != null && mine.id().equals(store().battery().previousProfile()), "PF-1: My settings is the way back: " + store().battery());
			Notice back = plugIn(context, real, watch);
			check(back.key().startsWith(ProfileService.NOTICE_BACK) && back.message().english().contains("My settings"), "back to My settings: " + back);
			take(context, real, back, ProfileService.ACTION_SWITCH);
			check(mine.id().equals(store().active()), "My settings is active again");
		}
	}

	// "Don't offer again": the next unplug (past the cooldown) is an edge but offers nothing.
	private void snooze(ClientGameTestContext context, RealController real) {
		fresh(context);
		try (Watch watch = new Watch(real.profileService()::powerChanged)) {
			take(context, real, unplug(context, real, watch), ProfileService.ACTION_SNOOZE);
			check(store().battery().snoozed(), "snoozed in profiles.json");
			edge(context, watch, false);
			store().batteryOffered(Instant.now().minus(Duration.ofMinutes(11)).toString());
			noOffer(context, real, watch, "snoozed");
		}
	}

	// The 10-minute cooldown from a lastPromptAt fixture: 5 minutes ago, no offer; 11 minutes ago, the offer.
	private void cooldown(ClientGameTestContext context, RealController real) {
		fresh(context);
		try (Watch watch = new Watch(real.profileService()::powerChanged)) {
			store().batteryOffered(Instant.now().minus(Duration.ofMinutes(5)).toString());
			noOffer(context, real, watch, "offered 5 minutes ago");
			edge(context, watch, false);
			store().batteryOffered(Instant.now().minus(Duration.ofMinutes(11)).toString());
			unplug(context, real, watch);
		}
	}

	// While a benchmark runs, an unplug offers nothing.
	private void benchmark(ClientGameTestContext context, RealController real) {
		fresh(context);
		ProfileService.overrideBenchmarkCheck(() -> true);
		try (Watch watch = new Watch(real.profileService()::powerChanged)) {
			noOffer(context, real, watch, "a benchmark runs");
		} finally {
			ProfileService.overrideBenchmarkCheck(null);
		}
	}

	// A listener that throws on every edge: the watcher's thread keeps polling and delivers the next edge.
	private void throwingListener(ClientGameTestContext context) {
		AtomicInteger calls = new AtomicInteger();
		try (Watch watch = new Watch(edge -> {
			calls.incrementAndGet();
			throw new IllegalStateException("BatteryFlowGameTest: a listener that throws");
		})) {
			edge(context, watch, true);
			edge(context, watch, false);
			check(calls.get() == 2 && watch.edges.equals(List.of(true, false)), "both edges reached the throwing listener: " + calls.get());
		}
	}

	// AC3e.2: the game's own watcher over OSHI's reading of the tmpfs battery (STATUS=Discharging at launch).
	private void oshi(ClientGameTestContext context, RealController real, Path uevent) {
		HardwareProfile hardware = real.report().hardware();
		RigTune.LOGGER.info("BatteryFlowGameTest: the startup probe: hasBattery={}, onBattery={}", hardware.hasBattery(), hardware.onBattery());
		check(hardware.hasBattery() && hardware.onBattery(), "the startup probe found the tmpfs battery, discharging");
		context.waitFor(mc -> PowerWatcher.isRunning(), TICKS);
		fresh(context);
		status(context, real, uevent, "Charging", false);
		status(context, real, uevent, "Discharging", true);
		Notice offer = context.computeOnClient(mc -> batteryNotice(real));
		check(offer != null && offer.key().startsWith(ProfileService.NOTICE_BATTERY), "the offer from the real watcher: " + offer);
		check(toast(context) != null, "and its toast");
		screenshot(context, real, "battery-oshi-offer");
		status(context, real, uevent, "Charging", false);
	}

	// Rewrites the battery's STATUS and waits for the game's watcher (two 30 s polls) to report the state.
	private static void status(ClientGameTestContext context, RealController real, Path uevent, String status, boolean onBattery) {
		try {
			String text = Files.readString(uevent, StandardCharsets.UTF_8);
			Files.writeString(uevent, text.replaceAll("(?m)^POWER_SUPPLY_STATUS=.*$", "POWER_SUPPLY_STATUS=" + status), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		long start = System.nanoTime();
		context.waitFor(mc -> {
			Report report = real.report();
			return report != null && report.hardware().onBattery() == onBattery && (!onBattery || batteryNotice(real) != null);
		}, OSHI_EDGE_TICKS);
		RigTune.LOGGER.info("BatteryFlowGameTest: STATUS={} seen after {} s", status, (System.nanoTime() - start) / 1_000_000_000L);
	}

	// An AC -> battery edge that offers Battery: the notice and the toast within 5 s of the switch to battery.
	private Notice unplug(ClientGameTestContext context, RealController real, Watch watch) {
		long start = System.nanoTime();
		watch.battery.onBattery.set(true);
		context.waitFor(mc -> watch.handledNanos > start && batteryNotice(real) != null && toast(mc) != null, TICKS);
		// r12 flake (FL-6): the 5 s bound is the product's part, the unplug to the offer set and its toast queued; how soon the
		// harness's next tick shows them isn't.
		long millis = (watch.handledNanos - start) / 1_000_000;
		RigTune.LOGGER.info("BatteryFlowGameTest: the offer set and its toast queued {} ms after the unplug (shown after {} ms)", millis,
				(System.nanoTime() - start) / 1_000_000);
		check(millis <= EDGE_MILLIS, "the offer within 5 s: " + millis + " ms");
		check(watch.edges.getLast(), "the edge was AC -> battery");
		return context.computeOnClient(mc -> batteryNotice(real));
	}

	private Notice plugIn(ClientGameTestContext context, RealController real, Watch watch) {
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		watch.battery.onBattery.set(false);
		context.waitFor(mc -> !watch.edges.getLast() && batteryNotice(real) != null && batteryNotice(real).key().startsWith(ProfileService.NOTICE_BACK), TICKS);
		return context.computeOnClient(mc -> batteryNotice(real));
	}

	// An AC -> battery edge that must offer nothing.
	private void noOffer(ClientGameTestContext context, RealController real, Watch watch, String why) {
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		edge(context, watch, true);
		context.waitTicks(10);
		check(batteryNotice(context, real) == null && toast(context) == null, "no offer (" + why + "): " + batteryNotice(context, real));
	}

	private static void edge(ClientGameTestContext context, Watch watch, boolean onBattery) {
		int before = watch.edges.size();
		watch.battery.onBattery.set(onBattery);
		context.waitFor(mc -> watch.edges.size() > before, TICKS);
		check(watch.edges.getLast() == onBattery, "the edge: " + watch.edges);
		context.waitTicks(3);
	}

	private static void take(ClientGameTestContext context, RealController real, Notice notice, String action) {
		context.runOnClient(mc -> real.noticeAction(notice.key(), action));
		context.waitTicks(2);
		context.runOnClient(mc -> mc.gui.toastManager().clear());
	}

	// A fresh profiles.json (so no profile active, no cooldown, not snoozed), no toast, the title screen.
	private void fresh(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.gui.setScreen(new TitleScreen());
			mc.gui.toastManager().clear();
		});
		try {
			Files.deleteIfExists(ProfileStore.file(configDir));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private ProfileStore store() {
		return ProfileStore.shared(configDir);
	}

	private static @Nullable Notice batteryNotice(RealController real) {
		return real.notices().stream().filter(n -> n.priority() == NoticePriority.BATTERY_OFFER).findFirst().orElse(null);
	}

	private static @Nullable Notice batteryNotice(ClientGameTestContext context, RealController real) {
		return context.computeOnClient(mc -> batteryNotice(real));
	}

	private static @Nullable SystemToast toast(Minecraft mc) {
		return mc.gui.toastManager().getToast(SystemToast.class, ProfileService.BATTERY_TOAST_ID);
	}

	private static @Nullable SystemToast toast(ClientGameTestContext context) {
		return context.computeOnClient(BatteryFlowGameTest::toast);
	}

	private static List<String> actions(Notice notice) {
		return notice.actions().stream().map(NoticeAction::id).toList();
	}

	// The RigTune screen with the notice line, and the toast if it's still up; cursor in a corner.
	private static void screenshot(ClientGameTestContext context, RealController real, String name) {
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneScreen.class);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(3);
		context.takeScreenshot(name + "-854x480-scale2");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	private static Map<String, String> vanilla(Minecraft mc) {
		Map<String, String> out = new LinkedHashMap<>();
		SettingsBridge.readVanilla(mc.options).forEach((k, v) -> out.put("vanilla." + k, v));
		return out;
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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// The real PowerWatcher over one fake battery, polled every second on its own thread; closed, the thread stops.
	private static final class Watch implements AutoCloseable {
		final FakeBattery battery = new FakeBattery();
		final List<Boolean> edges = new CopyOnWriteArrayList<>();
		// r12 flake (FL-6): when the listener last returned.
		volatile long handledNanos;
		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "BatteryFlowGameTest power");
			thread.setDaemon(true);
			return thread;
		});

		Watch(Consumer<Boolean> listener) {
			PowerWatcher watcher = PowerWatcher.start(List.of(battery), false, edge -> {
				edges.add(edge);
				listener.accept(edge);
				handledNanos = System.nanoTime();
			}, executor, 1);
			check(watcher != null, "the fake battery counts as a real one");
		}

		@Override
		public void close() {
			executor.shutdownNow();
			try {
				check(executor.awaitTermination(2, TimeUnit.SECONDS), "the watcher's thread stopped");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	// A laptop battery (a name, a chemistry and capacities realBattery accepts). onBattery: unplugged and discharging;
	// script: the states of the next polls before that (a plug wiggle).
	private static final class FakeBattery implements PowerWatcher.Battery {
		final AtomicBoolean onBattery = new AtomicBoolean();
		final ConcurrentLinkedQueue<Boolean> script = new ConcurrentLinkedQueue<>();
		final AtomicInteger polls = new AtomicInteger();
		private volatile boolean now;

		@Override
		public String deviceName() {
			return "BAT0";
		}

		@Override
		public String chemistry() {
			return "Li-ion";
		}

		@Override
		public int maxCapacity() {
			return 40000;
		}

		@Override
		public int designCapacity() {
			return 50000;
		}

		@Override
		public boolean update() {
			Boolean next = script.poll();
			now = next != null ? next : onBattery.get();
			polls.incrementAndGet();
			return true;
		}

		@Override
		public boolean powerOnLine() {
			return !now;
		}

		@Override
		public boolean discharging() {
			return now;
		}
	}
}
