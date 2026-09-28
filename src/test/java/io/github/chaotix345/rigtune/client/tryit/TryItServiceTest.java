package io.github.chaotix345.rigtune.client.tryit;

import io.github.chaotix345.rigtune.client.Busy;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md AC6.12 (unit part, X4.4): Try it's own END_CLIENT_TICK listener returns at once while no try is
// between or inside its runs: one volatile read, nothing allocated (a million calls allocate less than the measuring
// noise; one object per call would be megabytes), and it never needs the game or the lazy holder then. The TryItGameTest
// times it strictly in a running game. Nothing hooks Busy before a try's first run is queued.
class TryItServiceTest {
	private static final long NOISE_BYTES = 64 * 1024;

	private static long allocated() {
		return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
	}

	@Test
	void theIdleTickAllocatesNothingAndNeedsNoGame() {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported());
		for (int i = 0; i < 200_000; i++) {
			TryItService.tick(null);
		}
		long before = allocated();
		for (int i = 0; i < 1_000_000; i++) {
			TryItService.tick(null);
		}
		long bytes = allocated() - before;
		assertTrue(bytes < NOISE_BYTES, "a million idle ticks allocated " + bytes + " bytes");
	}

	@Test
	void theServiceStartsWithNoTryAndHooksNothing() {
		TryItService service = new TryItService(null);
		assertEquals(TryItView.EMPTY, service.view());
		assertFalse(Busy.tryItRunning.getAsBoolean());
		assertTrue(TryItService.SESSION.length() > 8);
	}
}
