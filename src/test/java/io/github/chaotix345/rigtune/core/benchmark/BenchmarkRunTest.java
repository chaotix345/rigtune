package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkSession.TuneLimits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.chaotix345.rigtune.core.benchmark.FakeRig.low;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunTest {
	private static final Knobs ORIGINAL = new Knobs(12, 12, true, true);

	private static final class FakeApplier implements KnobGuard.Applier {
		final List<Knobs> applied = new ArrayList<>();
		Knobs live = ORIGINAL;
		boolean failOnDhOff;
		boolean failOnRestore;
		int failOnRenderDistance = -1;

		@Override
		public void apply(Knobs from, Knobs to) throws IOException {
			if (failOnDhOff && !to.dhRendering()) {
				throw new IOException("DH refused");
			}
			if (failOnRestore && to.equals(ORIGINAL)) {
				throw new IOException("restore refused");
			}
			if (to.renderDistance() == failOnRenderDistance) {
				throw new IOException("render distance refused");
			}
			applied.add(to);
			live = to;
		}
	}

	private final FakeApplier applier = new FakeApplier();
	private long now;

	private BenchmarkRun run() {
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, new TuneLimits(4, 32, 100, true, 5), Timing.DEFAULT, 0);
		return new BenchmarkRun(session, new KnobGuard(ORIGINAL, applier), () -> now);
	}

	private void runToEnd(BenchmarkRun run) {
		Optional<Step> step;
		while ((step = run.advance()).isPresent()) {
			now += 10 * FakeRig.NANOS;
			run.record(low(2000.0 / step.get().knobs().renderDistance()));
		}
	}

	@Test
	void advanceAppliesStepKnobs() {
		BenchmarkRun run = run();
		Step first = run.advance().orElseThrow();
		assertEquals(first.knobs(), applier.live);
		assertSame(first, run.current());
	}

	@Test
	void finishRestoresOnce() {
		BenchmarkRun run = run();
		runToEnd(run);
		assertTrue(run.finished());
		assertFalse(run.cancelled());
		assertEquals(ORIGINAL, applier.live);
		int calls = applier.applied.size();
		run.cancel();
		assertFalse(run.cancelled(), "cancel after the end changes nothing");
		assertEquals(calls, applier.applied.size());
		assertTrue(run.advance().isEmpty());
	}

	@Test
	void cancelMidStepRestores() {
		BenchmarkRun run = run();
		run.advance().orElseThrow();
		run.record(low(150));
		run.advance().orElseThrow();
		assertFalse(applier.live.equals(ORIGINAL));
		run.cancel();
		assertTrue(run.finished());
		assertTrue(run.cancelled());
		assertEquals(ORIGINAL, applier.live);
		assertNull(run.current());
		assertTrue(run.advance().isEmpty());
		assertThrows(IllegalStateException.class, () -> run.record(low(1)));
	}

	@Test
	void failRestoresAndKeepsError() {
		BenchmarkRun run = run();
		run.advance().orElseThrow();
		RuntimeException boom = new RuntimeException("boom");
		run.fail(boom);
		assertTrue(run.cancelled());
		assertSame(boom, run.error());
		assertEquals(ORIGINAL, applier.live);
	}

	// A cost report that can't switch its feature off is skipped; the run carries on and finishes.
	@Test
	void reportApplyFailureSkipsOnlyThatReport() {
		applier.failOnDhOff = true;
		BenchmarkRun run = run();
		runToEnd(run);
		assertTrue(run.finished());
		assertFalse(run.cancelled());
		assertNull(run.error());
		assertEquals(ORIGINAL, applier.live);
		assertTrue(run.restoreOk());
		SessionResult r = run.result();
		assertNull(r.dhCost());
		assertTrue(r.notMeasured().get(BenchmarkRecord.DISTANT_HORIZONS).contains("DH refused"), r.notMeasured().toString());
		assertNotNull(r.shaderCost());
	}

	@Test
	void tunedKnobApplyFailureFailsTheRun() {
		applier.failOnRenderDistance = 14;
		BenchmarkRun run = run();
		runToEnd(run);
		assertTrue(run.finished());
		assertTrue(run.cancelled());
		assertInstanceOf(IOException.class, run.error());
		assertEquals(ORIGINAL, applier.live);
	}

	@Test
	void restoreExceptionIsReportedNotThrown() {
		BenchmarkRun run = run();
		run.advance().orElseThrow();
		run.record(low(150));
		run.advance().orElseThrow();
		applier.failOnRestore = true;
		run.cancel();
		assertTrue(run.finished());
		assertFalse(run.restoreOk());
	}

	@Test
	void unchangedKnobsNeedNoRestore() {
		KnobGuard guard = new KnobGuard(ORIGINAL, applier);
		assertTrue(guard.restore());
		assertTrue(applier.applied.isEmpty());
	}

	@Test
	void guardSkipsNoOpChanges() throws Exception {
		KnobGuard guard = new KnobGuard(ORIGINAL, applier);
		guard.set(ORIGINAL);
		guard.set(ORIGINAL.withRenderDistance(8));
		guard.set(ORIGINAL.withRenderDistance(8));
		assertEquals(List.of(ORIGINAL.withRenderDistance(8)), applier.applied);
	}

	@Test
	void guardRefusesChangesAfterRestore() {
		KnobGuard guard = new KnobGuard(ORIGINAL, applier);
		guard.restore();
		assertThrows(IllegalStateException.class, () -> guard.set(ORIGINAL.withRenderDistance(8)));
	}

	@Test
	void guardRestoresEverythingAfterAPartialFailure() throws Exception {
		KnobGuard guard = new KnobGuard(ORIGINAL, applier);
		guard.set(ORIGINAL.withRenderDistance(8));
		applier.failOnDhOff = true;
		assertThrows(IOException.class, () -> guard.set(ORIGINAL.withRenderDistance(8).withDhRendering(false)));
		applier.failOnDhOff = false;
		assertTrue(guard.restore());
		assertEquals(ORIGINAL, applier.live);
	}

	@Test
	void resultAvailableAfterCancel() {
		BenchmarkRun run = run();
		run.advance().orElseThrow();
		run.record(low(150));
		run.cancel();
		assertEquals(1, run.result().measurements().size());
	}
}
