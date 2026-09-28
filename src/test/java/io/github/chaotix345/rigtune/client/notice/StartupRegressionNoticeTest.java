package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Assessment;
import io.github.chaotix345.rigtune.core.hardware.PerfCounters;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 9 (C18, AC9.2) through the static builder (as ServerLimitNoticeSourceTest): a SLOWER launch is the
// STARTUP_REGRESSION notice with its numbers and exactly one cause line, Tools… then Got it, not dismissible, one key per
// launch; nothing else is ever a notice (no regression is claimed from fewer than 5 comparable runs). 2L (AC2L.2): while
// Windows' performance counters are off, the detail adds that advice (the measured line only when measured).
class StartupRegressionNoticeTest {
	private static final PerfCounters ON = new PerfCounters(true, false, List.of(), List.of());
	private static final PerfCounters OFF = new PerfCounters(true, true, List.of(), List.of("PerfOS"));

	private static Assessment assess(int before, Run latest) {
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < before; i++) {
			runs.add(new Run("2026-09-1" + i + "T10:00:00Z", 10_000, "26.2", "0.5.0", 80, "h"));
		}
		runs.add(latest);
		return StartupTrend.assess(runs);
	}

	private static Run latest(long ms, int mods, String hash, String rigtune) {
		return new Run("2026-09-27T10:00:00Z", ms, "26.2", rigtune, mods, hash);
	}

	@Test
	void aSlowerLaunchWithItsNumbersAndOneCauseLine() {
		Notice notice = StartupRegressionNoticeSource.notice(assess(6, latest(14_500, 92, "h2", "0.5.0")), ON, null);
		assertEquals("startup.regression.2026-09-27T10:00:00Z", notice.key());
		assertEquals(NoticePriority.STARTUP_REGRESSION, notice.priority());
		assertEquals("Launch time 45% higher than usual (14.5 s vs your usual ~10.0 s)", notice.message().english());
		assertEquals("May be related to your mod set changing (80 → 92 mods) since your last launch", notice.detail().english());
		assertEquals(List.of("tools", "acknowledge"), notice.actions().stream().map(NoticeAction::id).toList());
		assertEquals(List.of("Tools…", "Got it"), notice.actions().stream().map(a -> a.label().english()).toList());
		assertFalse(notice.dismissible());
	}

	@Test
	void eachCauseIsTheOnlyLine() {
		assertEquals("May be related to your mod set changing since your last launch",
				StartupRegressionNoticeSource.notice(assess(6, latest(14_500, 80, "h2", "0.6.0")), ON, null).detail().english());
		assertEquals("RigTune 0.5.0 → 0.6.0 since your last launch",
				StartupRegressionNoticeSource.notice(assess(6, latest(14_500, 80, "h", "0.6.0")), ON, null).detail().english());
		assertEquals("No change recorded since your last launch; possibly another program running, a cold disk cache, or a driver/OS update",
				StartupRegressionNoticeSource.notice(assess(6, latest(14_500, 80, "h", "0.5.0")), ON, null).detail().english());
	}

	@Test
	void nothingButASlowerLaunchIsANotice() {
		assertNull(StartupRegressionNoticeSource.notice(assess(4, latest(40_000, 92, "h2", "0.5.0")), ON, null), "four comparable runs: TOO_FEW");
		assertNull(StartupRegressionNoticeSource.notice(assess(6, latest(10_900, 92, "h2", "0.5.0")), ON, null), "IN_LINE");
		assertNull(StartupRegressionNoticeSource.notice(assess(6, latest(7_000, 92, "h2", "0.5.0")), ON, null), "IMPROVEMENT is never shown");
		assertNull(StartupRegressionNoticeSource.notice(StartupTrend.assess(List.of()), ON, null), "NO_RUN");
	}

	// Review L5: one of 2L's lines: this launch's measured crash-report setup, else what the setting is; the rest (and
	// Microsoft's pages) stay in Tools, which Tools… opens.
	@Test
	void withTheCountersOffTheDetailAddsOneLaunchTimeAdviceLine() {
		Assessment slower = assess(6, latest(14_500, 92, "h2", "0.5.0"));
		assertEquals("May be related to your mod set changing (80 → 92 mods) since your last launch\n"
				+ "Minecraft's crash-report setup took 3.1 s at this launch; about 1 s is usual. It asks Windows for these counters, so part of that time may be "
				+ "related to this setting.", StartupRegressionNoticeSource.notice(slower, OFF, 3135L).detail().english());
		assertEquals("May be related to your mod set changing (80 → 92 mods) since your last launch\n"
				+ "Windows performance counters are turned off on this PC. \"Disable Performance Counters\" is Windows' own Perflib setting; it turns off every "
				+ "registry-based performance counter on the PC.", StartupRegressionNoticeSource.notice(slower, OFF, null).detail().english());
		assertEquals("May be related to your mod set changing (80 → 92 mods) since your last launch",
				StartupRegressionNoticeSource.notice(slower, PerfCounters.NOT_READ, 3135L).detail().english(), "not read (not Windows): no advice");
	}

	// Review H1: a slow streak keeps its first launch's key and cause, so one Got it covers it.
	@Test
	void aSlowStreakKeepsItsFirstKey() {
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			runs.add(new Run("2026-09-1" + i + "T10:00:00Z", 10_000, "26.2", "0.5.0", 80, "h"));
		}
		runs.add(latest(14_500, 92, "h2", "0.5.0"));
		runs.add(new Run("2026-09-28T10:00:00Z", 14_800, "26.2", "0.5.0", 92, "h2"));
		Notice notice = StartupRegressionNoticeSource.notice(StartupTrend.assess(runs), ON, null);
		assertEquals("startup.regression.2026-09-27T10:00:00Z", notice.key());
		assertEquals("Launch time 48% higher than usual (14.8 s vs your usual ~10.0 s)", notice.message().english());
		assertEquals("Slower for your last 2 launches; may be related to your mod set changing (80 → 92 mods) before the first of them", notice.detail().english());
	}
}
