package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2L (AC2L.2's text builders, AC2L.3): Tools' launch-time advice while Windows' performance counters are
// off, and only then; the measured crash-report time only when it was measured (the whole time, never credited to the
// setting alone); Microsoft's two pages linked; never lodctr; nothing about who set it.
class PerfCounterAdviceTest {
	private static final PerfCounters OFF = new PerfCounters(true, true, List.of(), List.of("PerfOS"));
	private static final PerfCounters ON = new PerfCounters(true, false, List.of("PerfProc"), List.of());

	private static List<String> keys(List<PerfCounterAdvice.Line> lines) {
		return lines.stream().map(l -> ((Text.Translatable) l.text()).key()).toList();
	}

	private static String english(List<PerfCounterAdvice.Line> lines) {
		return String.join("\n", lines.stream().map(l -> l.text().english()).toList());
	}

	@Test
	void nothingUnlessTheCountersAreOff() {
		assertEquals(List.of(), PerfCounterAdvice.lines(PerfCounters.NOT_READ, 7_000L));
		assertEquals(List.of(), PerfCounterAdvice.lines(ON, 7_000L), "a service's own switch: OSHI skips those itself");
	}

	@Test
	void theLinesWhenOffWithTheMeasuredTimeOnlyWhenMeasured() {
		assertEquals(List.of("rigtune.startup.perf_counters.off", "rigtune.startup.perf_counters.windows", "rigtune.startup.perf_counters.link.entry",
				"rigtune.startup.perf_counters.link.kb"), keys(PerfCounterAdvice.lines(OFF, null)));
		List<PerfCounterAdvice.Line> measured = PerfCounterAdvice.lines(OFF, 6_849L);
		assertEquals(List.of("rigtune.startup.perf_counters.off", "rigtune.startup.perf_counters.measured", "rigtune.startup.perf_counters.windows",
				"rigtune.startup.perf_counters.link.entry", "rigtune.startup.perf_counters.link.kb"), keys(measured));
		assertEquals("Minecraft's crash-report setup took 6.8 s at this launch; about 1 s is usual. It asks Windows for these counters, so part of "
				+ "that time may be related to this setting.", measured.get(1).text().english());
	}

	@Test
	void whatTheAdviceSays() {
		String text = english(PerfCounterAdvice.lines(OFF, 7_000L));
		for (String says : List.of("Perflib", "every registry-based performance counter", "0 is Windows' default", "administrator", "restart",
				"on purpose", "RigTune doesn't change Windows settings", "KB 2554336", "Disable Performance Counters Entry")) {
			assertTrue(text.contains(says), says + " in:\n" + text);
		}
		assertFalse(text.toLowerCase(Locale.ROOT).contains("lodctr"));
		for (String guess : List.of("debloat", "you set", "someone")) {
			assertFalse(text.toLowerCase(Locale.ROOT).contains(guess), guess);
		}
	}

	@Test
	void bothMicrosoftPagesAreLinked() {
		List<PerfCounterAdvice.Line> lines = PerfCounterAdvice.lines(OFF, null);
		List<String> urls = lines.stream().map(PerfCounterAdvice.Line::url).filter(u -> u != null).toList();
		assertEquals(List.of(PerfCounterAdvice.ENTRY_URL, PerfCounterAdvice.KB_URL), urls);
		assertEquals("https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2003/cc737243(v=ws.10)", PerfCounterAdvice.ENTRY_URL);
		assertEquals("https://learn.microsoft.com/en-us/troubleshoot/windows-server/performance/manually-rebuild-performance-counters",
				PerfCounterAdvice.KB_URL);
		for (PerfCounterAdvice.Line line : lines) {
			if (line.url() != null) {
				assertTrue(line.text().english().endsWith(line.url()), "the page's address is shown: " + line.text().english());
			} else {
				assertNull(line.url());
			}
		}
	}
}
