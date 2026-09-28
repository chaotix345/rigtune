package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import io.github.chaotix345.rigtune.core.stutter.FixConditions;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.stutter.FixStore;
import io.github.chaotix345.rigtune.core.stutter.FixTracker;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.SessionOutcome;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 5 (C20): StutterFixService's pure parts; the flow runs in StutterFixGameTest.
class StutterFixServiceTest {
	// A fix is one SetSetting, ticked, whose reason names the advice it came from (History and the preview show it).
	@Test
	void anOfferIsOneSetSettingNamingItsAdvice() {
		RulesDocument rules = RulesLoader.loadBundled();
		FixOffer.Offer offer = new FixOffer.Offer("stutter-chunk-loading", "vanilla.renderDistance", "12", "10", true);
		Recommendation rec = StutterFixService.recommendation(offer, rules);
		assertEquals("stutterfix:stutter-chunk-loading", rec.id());
		assertEquals(Category.SETTING, rec.category());
		assertEquals(new Action.SetSetting("vanilla.renderDistance", "12", "10"), rec.action());
		assertTrue(rec.selectedByDefault());
		String title = rules.stutterAdvice.stream().filter(a -> a.id.equals("stutter-chunk-loading")).findFirst().orElseThrow().title;
		assertEquals("Stutter Doctor: " + title, rec.reasonText().english());
		assertTrue(rec.titleText().english().contains("12") && rec.titleText().english().contains("10"), rec.titleText().english());
		// Without rules the advice id stands in for its title.
		assertEquals("Stutter Doctor: stutter-chunk-loading", StutterFixService.recommendation(offer, null).reasonText().english());
	}

	private static final String RD = "vanilla.renderDistance";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";

	// A service over an unconstructed RealController (its constructor needs a game) holding only the config dir, as
	// StutterServiceTest does: enough for the records and History, never the render thread's parts.
	static StutterFixService service(Path config) throws ReflectiveOperationException {
		Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		RealController controller = (RealController) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, RealController.class);
		Field dir = RealController.class.getDeclaredField("configDir");
		dir.setAccessible(true);
		dir.set(controller, config);
		return new StutterFixService(controller);
	}

	static FixTracker.Record measuring(String entryId) {
		FixConditions conditions = new FixConditions("26.2", "mods", 4096, "g1", 1280, 720, false, "SINGLEPLAYER", true, true, Map.of(RD, "12"));
		return new FixTracker.Record(entryId, "stutter-chunk-loading", RD, "12", "10", Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS),
				17, true, FixTracker.State.MEASURING, new SessionOutcome(1, 400, 20, 1000, 7, 20 / 7.0, 3), conditions, null, 0, null, null, false);
	}

	static JournalEntry applied(String entryId) {
		return new JournalEntry(entryId, "2026-09-28T01:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(JournalChange.setting(RD, "12", "10", JournalChange.APPLIED, "op-" + entryId)));
	}

	private static FixTracker.State stored(Path config, String entryId) {
		return FixStore.shared(config).records().stream().filter(r -> r.entryId().equals(entryId)).findFirst().orElseThrow().state();
	}

	// C20 review M1: Apply's last check never throws. A malformed sodium-options.json reads as nothing, so the offer's
	// "from" can't be confirmed, and a read that throws (a config file mid-write) refuses the same way ("can't be applied
	// now"), before anything changes.
	@Test
	void m1ASettingThatCantBeReadRefusesTheFix(@TempDir Path config) throws IOException {
		FixOffer.Offer offer = new FixOffer.Offer("stutter-sodium-defer", DEFER, "ZERO_FRAMES", "ALWAYS", false);
		StutterFixService.Fixes shown = new StutterFixService.Fixes(Map.of(offer.adviceId(), offer), new SessionOutcome(1, 400, 20, 1000, 7, 20 / 7.0, 3),
				new FixConditions("26.2", "mods", 4096, "g1", 1280, 720, false, "SINGLEPLAYER", true, true, Map.of()), false, false, false, Instant.now(),
				StutterReport.MONITOR);
		Path sodium = config.resolve("sodium-options.json");
		Files.writeString(sodium, "{\"performance\": {\"chunk_build_defer_mode\": ");
		assertTrue(StutterFixService.gone(offer, shown, () -> new SettingsSnapshot(SettingsBridge.readSodium(sodium))));
		assertTrue(StutterFixService.gone(offer, shown, () -> {
			throw new IllegalStateException("a config file mid-write");
		}));
		Files.writeString(sodium, "{\"performance\": {\"chunk_build_defer_mode\": \"ZERO_FRAMES\"}}");
		assertFalse(StutterFixService.gone(offer, shown, () -> new SettingsSnapshot(SettingsBridge.readSodium(sodium))));
		assertTrue(StutterFixService.gone(offer, null, () -> new SettingsSnapshot(SettingsBridge.readSodium(sodium))), "no analysis shown");
	}

	// C20 review M2: the holds read history.json once per call, however many fixes there are.
	@Test
	void m2HoldsReadHistoryOnce(@TempDir Path config) throws ReflectiveOperationException {
		StutterFixService service = service(config);
		List<String> ids = List.of("5c20f1a0-7d3e-4b2a-9c61-0000000000c1", "5c20f1a0-7d3e-4b2a-9c61-0000000000c2", "5c20f1a0-7d3e-4b2a-9c61-0000000000c3");
		for (String id : ids) {
			assertTrue(FixStore.shared(config).add(measuring(id)));
		}
		AtomicInteger reads = new AtomicInteger();
		Journal.Snapshot history = new Journal.Snapshot(Journal.State.OK, ids.stream().map(StutterFixServiceTest::applied).toList());
		service.history = () -> {
			reads.incrementAndGet();
			return history;
		};
		assertEquals(3, service.holds().size());
		assertEquals(1, reads.get(), "history.json read once");
	}

	// C20 review M3: the state and the entries come from one read, and a read that fails changes nothing: a fix is never
	// expired because history.json couldn't be read for a moment. Only a good read without the entry expires it.
	@Test
	void m3AFailedHistoryReadNeverExpiresAFix(@TempDir Path config) throws ReflectiveOperationException {
		StutterFixService service = service(config);
		String id = "5c20f1a0-7d3e-4b2a-9c61-0000000000d1";
		assertTrue(FixStore.shared(config).add(measuring(id)));
		// The first read is fine, every later one fails (a file mid-write).
		AtomicInteger reads = new AtomicInteger();
		service.history = () -> reads.getAndIncrement() == 0 ? new Journal.Snapshot(Journal.State.OK, List.of(applied(id)))
				: new Journal.Snapshot(Journal.State.UNREADABLE, List.of());
		service.advanceAll(null);
		assertEquals(FixTracker.State.MEASURING, stored(config, id));
		service.history = () -> new Journal.Snapshot(Journal.State.UNREADABLE, List.of());
		service.advanceAll(null);
		assertEquals(FixTracker.State.MEASURING, stored(config, id));
		service.history = () -> new Journal.Snapshot(Journal.State.OK, List.of());
		service.advanceAll(null);
		assertEquals(FixTracker.State.EXPIRED, stored(config, id), "the entry is gone from a good read");
	}

	// A 60-minute monitor capture sampled at 4 Hz (more samples than the sample ring holds), Distant Horizons' world
	// generation at `cores` for the minutes [genFrom, genTo).
	private static StutterAnalyzer.Result dhSession(double cores, int genFrom, int genTo, boolean sampled) {
		long s = StutterAnalyzer.SECOND;
		long t0 = 50 * s;
		StutterRings rings = new StutterRings(10 * s);
		long window = s / 4;
		for (long t = t0 + window; sampled && t <= t0 + 3600 * s; t += window) {
			long[] record = new long[StutterRings.SAMPLE_STRIDE];
			record[StutterRings.S_TIME] = t;
			record[StutterRings.S_WINDOW] = window;
			long minute = (t - t0) / (60 * s);
			record[StutterRings.S_DH_WORLD_GEN] = minute >= genFrom && minute < genTo ? (long) (cores * window) : 0;
			rings.sample(record);
		}
		FrameRing ring = new FrameRing(1024, 16);
		for (int i = 1; i <= 100; i++) {
			ring.frame(t0 + i * 16 * StutterAnalyzer.MS, 16 * StutterAnalyzer.MS, false, 300_000, StutterAnalyzer.MS, 14 * StutterAnalyzer.MS, 0);
		}
		return StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), rings.snapshot(), t0, t0 + 3600 * s, Instant.parse("2026-09-26T10:00:00Z"),
				StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, true, false));
	}

	// review-11 STUTTER-4: WS-B's rule looks at the whole capture, not the sample ring's last ~17 minutes averaged: 40 minutes
	// of Distant Horizons generating terrain early in an hour excludes the session; and with Distant Horizons loaded but no
	// sample at all, it fails closed.
	@Test
	void distantHorizonsGeneratingAnywhereInTheSessionExcludesIt() {
		assertTrue(StutterFixService.excluded(dhSession(3, 0, 40, true), false, true), "generated for the first 40 of 60 minutes");
		assertTrue(StutterFixService.excluded(dhSession(3, 50, 60, true), false, true), "generated for the last 10 minutes");
		assertFalse(StutterFixService.excluded(dhSession(3, 0, 40, true), false, false), "Distant Horizons isn't loaded");
		assertFalse(StutterFixService.excluded(dhSession(0, 0, 60, true), false, true), "loaded, never generating");
		assertTrue(StutterFixService.excluded(dhSession(0, 0, 0, false), false, true), "loaded, not sampled: can't tell");
		assertTrue(StutterFixService.excluded(dhSession(0, 0, 60, true), true, false), "around a benchmark");
	}

	// review-11 STUTTER-3: the client's half of "the before side is one setup": the window (F11 to fullscreen), a managed
	// setting or the mod set moved between the capture's start and the analysis. A key read on one side only (a config file
	// mid-write) isn't a change; unknown start conditions can't tell (fail closed).
	@Test
	void theBeforeSideIsOneSetup() {
		FixConditions start = new FixConditions("26.2", "mods", 4096, "g1", 1280, 720, false, "SINGLEPLAYER", false, false, Map.of(RD, "20", DEFER, "ALWAYS"));
		assertFalse(StutterFixService.changedDuring(start, start));
		assertTrue(StutterFixService.changedDuring(start, new FixConditions("26.2", "mods", 4096, "g1", 2560, 1440, true, "SINGLEPLAYER", false, false,
				Map.of(RD, "20", DEFER, "ALWAYS"))), "F11 to 2560x1440 fullscreen");
		assertTrue(StutterFixService.changedDuring(start, new FixConditions("26.2", "mods", 4096, "g1", 1280, 720, false, "SINGLEPLAYER", false, false,
				Map.of(RD, "12", DEFER, "ALWAYS"))), "render distance 20 -> 12");
		assertFalse(StutterFixService.changedDuring(start, new FixConditions("26.2", "mods", 4096, "g1", 1280, 720, false, "SINGLEPLAYER", false, false,
				Map.of(RD, "20"))), "Sodium's file unreadable at the analysis: unknown, not a change");
		assertTrue(StutterFixService.changedDuring(null, start), "no start conditions: can't tell");
	}

	// review-11 PERF-2: a player without a tracked fix pays no history.json parse, neither on every rebuild (holds) nor every
	// 5 s while the Stutter Doctor is open (advanceAll), with or without a session that just ended.
	@Test
	void withoutAFixHistoryIsNeverRead(@TempDir Path config) throws ReflectiveOperationException {
		StutterFixService service = service(config);
		AtomicInteger reads = new AtomicInteger();
		service.history = () -> {
			reads.incrementAndGet();
			return new Journal.Snapshot(Journal.State.OK, List.of());
		};
		assertEquals(List.of(), service.holds());
		service.advanceAll(null);
		assertEquals(0, reads.get(), "no fix, no parse");
		String id = "5c20f1a0-7d3e-4b2a-9c61-0000000000e1";
		assertTrue(FixStore.shared(config).add(measuring(id)));
		service.advanceAll(null);
		assertEquals(1, reads.get(), "one fix: one read");
	}

	// review-12 R12STUTTER-3: records that can't change any more (dismissed, expired, undone) need no history.json: a player
	// who once tried a fix pays no parse for good. A dismissed compared record and an expired one: no read, and the
	// expired one (its value still in effect) still holds.
	@Test
	void finishedFixesReadNoHistory(@TempDir Path config) throws ReflectiveOperationException {
		StutterFixService service = service(config);
		AtomicInteger reads = new AtomicInteger();
		service.history = () -> {
			reads.incrementAndGet();
			return new Journal.Snapshot(Journal.State.OK, List.of());
		};
		assertTrue(FixStore.shared(config).add(measuring("5c20f1a0-7d3e-4b2a-9c61-0000000000f1").withState(FixTracker.State.COMPARED).dismiss()));
		assertTrue(FixStore.shared(config).add(measuring("5c20f1a0-7d3e-4b2a-9c61-0000000000f2").withState(FixTracker.State.EXPIRED)));
		assertEquals(2, service.holds().size());
		service.advanceAll(null);
		assertEquals(0, reads.get(), "nothing left to follow in the journal");
	}

	// V05ServicesTest's rule: without a controller, holds() reads no file and holds nothing.
	@Test
	void withoutAControllerNothingIsReadOrHeld() {
		StutterFixService service = new StutterFixService(null);
		assertEquals(List.of(), service.holds());
		assertEquals(null, service.tracked());
	}
}
