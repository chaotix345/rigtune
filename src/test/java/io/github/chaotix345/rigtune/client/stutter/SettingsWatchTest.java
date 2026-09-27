package io.github.chaotix345.rigtune.client.stutter;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2S RW-11 and X4.4: SettingsWatch's per-tick comparison. A change is reported once, dated when the old
// value was last seen; a resource reload (the loading overlay appearing or going away) counts; Iris and Distant Horizons
// are read after a reload and at most once every 20 ticks; and a tick allocates nothing.
class SettingsWatchTest {
	private static final long TICK = 50_000_000L;

	@Test
	void aChangeIsReportedOnceAndDatedWhenTheOldValueWasLastSeen() {
		SettingsWatch.State s = new SettingsWatch.State();
		assertEquals(0, s.check(32, 12, false, 1 * TICK), "the first look only takes the values");
		assertEquals(0, s.check(32, 12, false, 2 * TICK));
		assertEquals(SettingsWatch.RENDER_DISTANCE, s.check(12, 12, false, 3 * TICK));
		assertEquals(2 * TICK, s.since(), "the tick that still saw 32");
		assertEquals(0, s.check(12, 12, false, 4 * TICK), "reported once");
		assertEquals(SettingsWatch.SIMULATION_DISTANCE | SettingsWatch.RENDER_DISTANCE, s.check(8, 6, false, 5 * TICK));

		s.disarm();
		assertEquals(0, s.check(16, 6, false, 6 * TICK), "a new session starts from what it sees");
	}

	@Test
	void aResourceReloadCountsWhenItStartsAndWhenItEnds() {
		SettingsWatch.State s = new SettingsWatch.State();
		s.check(12, 12, false, TICK);
		assertEquals(SettingsWatch.RELOAD, s.check(12, 12, true, 2 * TICK), "the loading overlay appeared");
		assertEquals(0, s.check(12, 12, true, 3 * TICK));
		assertEquals(SettingsWatch.RELOAD, s.check(12, 12, false, 4 * TICK), "and went away");
	}

	// Review fix: a reload longer than the 10 s window keeps it open (the RELOAD bit again every 5 s while the overlay is up).
	@Test
	void aLongReloadKeepsItsWindowOpen() {
		SettingsWatch.State s = new SettingsWatch.State();
		s.check(12, 12, false, 0);
		int reloads = 0;
		for (int i = 1; i <= 300; i++) {
			if ((s.check(12, 12, true, i * TICK) & SettingsWatch.RELOAD) != 0) {
				reloads++;
			}
		}
		assertEquals(3, reloads, "15 s of overlay: when it appeared, then at 5 s and 10 s");
		assertEquals(SettingsWatch.RELOAD, s.check(12, 12, false, 301 * TICK), "and when it went away");
	}

	// Review fix: the event says how long after its time the change was seen, so the window ends 10 s after that.
	@Test
	void theLeadIsTheTimeBetweenTheLastLookAndTheChange() {
		SettingsWatch.State s = new SettingsWatch.State();
		s.check(12, 12, false, 0);
		s.optionalDue(0);
		s.checkOptional(false, false, 0);
		for (int i = 1; i < 20; i++) {
			s.check(12, 12, false, i * TICK);
			s.optionalDue(0);
		}
		int changed = s.check(12, 12, false, 20 * TICK);
		assertTrue(s.optionalDue(changed));
		assertEquals(SettingsWatch.SHADERS, s.checkOptional(true, false, 20 * TICK));
		assertEquals(0, s.since(), "dated at the last Iris read");
		assertEquals(1000, s.leadMillis(20 * TICK), "seen 1 s later");
	}

	// Review fix (M3): a check that throws is off for the rest of that session (one warning), and tries again in the next.
	@Test
	void aFailingCheckStaysOffForThatSession() {
		StutterMonitorAccess.startSession();
		try (io.github.chaotix345.rigtune.core.LogCapture log = new io.github.chaotix345.rigtune.core.LogCapture()) {
			for (int i = 0; i < 50; i++) {
				SettingsWatch.tick(null);
			}
			assertEquals(1, log.lines().stream().filter(l -> l.contains("the settings check failed")).count());
			StutterMonitorAccess.stopAll();
			StutterMonitorAccess.startSession();
			SettingsWatch.tick(null);
			assertEquals(2, log.lines().stream().filter(l -> l.contains("the settings check failed")).count(), "a new session tries again");
		} finally {
			StutterMonitorAccess.stopAll();
		}
	}

	@Test
	void irisAndDhAreReadAfterAReloadAndAtMostOnceASecond() {
		SettingsWatch.State s = new SettingsWatch.State();
		int changed = s.check(12, 12, false, TICK);
		assertTrue(s.optionalDue(changed), "the first tick of a session");
		assertEquals(0, s.checkOptional(true, false, TICK), "the first read only takes the values");
		int reads = 0;
		for (int i = 2; i <= 41; i++) {
			if (s.optionalDue(s.check(12, 12, false, i * TICK))) {
				reads++;
				assertEquals(0, s.checkOptional(true, false, i * TICK));
			}
		}
		assertEquals(2, reads, "40 ticks: two reads");
		assertTrue(s.optionalDue(s.check(12, 12, true, 42 * TICK)), "right after a reload");
		assertEquals(0, s.checkOptional(true, false, 42 * TICK));
		for (int i = 43; i < 62; i++) {
			assertFalse(s.optionalDue(s.check(12, 12, true, i * TICK)));
		}
		assertTrue(s.optionalDue(s.check(12, 12, true, 62 * TICK)));
		assertEquals(SettingsWatch.SHADERS | SettingsWatch.DH_RENDERING, s.checkOptional(false, true, 62 * TICK));
		assertEquals(42 * TICK, s.since(), "dated at the last read that still saw the old state");
	}

	@Test
	void aTickAllocatesNothing() {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported());
		SettingsWatch.State s = new SettingsWatch.State();
		run(s, 200_000);
		long before = StutterMonitorTest.allocated();
		run(s, 1_000_000);
		long allocated = StutterMonitorTest.allocated() - before;
		assertTrue(allocated < StutterMonitorTest.NOISE_BYTES, "a million ticks allocated " + allocated + " bytes");
	}

	private static void run(SettingsWatch.State s, int ticks) {
		for (int i = 0; i < ticks; i++) {
			int changed = s.check(12 + (i >> 10 & 1), 12, (i & 4095) == 0, i * TICK);
			if (s.optionalDue(changed)) {
				s.checkOptional((i & 8192) != 0, false, i * TICK);
			}
		}
	}
}
