package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.GcClock;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Starts and stops captures (render thread) together with what they share: the rings, the GC listener, the sampler and
// the dev GC thread exist exactly while a capture does. Stopping copies the capture's rings for the analysis and
// releases the buffers.
final class StutterCapture {
	static final GcListener GC = new GcListener();
	static final ThreadSampler SAMPLER = new ThreadSampler();

	// A stopped (or, for a live analysis, copied) capture: everything StutterAnalyzer needs from the render thread.
	record Copy(FrameRing.Snapshot frames, StutterRings.Snapshot rings, long startNanos, long endNanos, Instant startedAt, String source,
			boolean phaseTiming, @Nullable String collector) {
	}

	private StutterCapture() {
	}

	static synchronized StutterMonitor.Capture startSession() {
		return StutterMonitor.startSession(shared(), System.nanoTime(), Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	static synchronized StutterMonitor.Capture startBenchmark() {
		return StutterMonitor.startBenchmark(shared(), System.nanoTime(), Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	static synchronized Copy stop(StutterMonitor.Capture capture) {
		Copy copy = copy(capture);
		if (StutterMonitor.stop(capture)) {
			GC.stop();
			SAMPLER.stop();
			DevStutter.stopForcedGc();
			RigTune.LOGGER.info("Stutter Doctor: capture off; GC listener removed, sampler stopped");
		}
		return copy;
	}

	static Copy copy(StutterMonitor.Capture capture) {
		StutterRings rings = StutterMonitor.rings();
		return new Copy(capture.snapshot(), rings == null ? StutterRings.Snapshot.EMPTY : rings.snapshot(), capture.startNanos(), System.nanoTime(),
				capture.startedAt(), capture.source(), StutterMonitor.phaseTiming(), GC.collector());
	}

	private static StutterRings shared() {
		StutterRings rings = StutterMonitor.rings();
		if (rings != null) {
			return rings;
		}
		RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
		StutterRings created = new StutterRings(GcClock.anchor(runtime::getUptime, System::nanoTime));
		GC.start(created);
		SAMPLER.start(created);
		DevStutter.startForcedGc();
		RigTune.LOGGER.info("Stutter Doctor: capture on (collector {})", GC.collector());
		return created;
	}
}
