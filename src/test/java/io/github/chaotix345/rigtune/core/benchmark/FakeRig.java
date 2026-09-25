package io.github.chaotix345.rigtune.core.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

// Drives a BenchmarkSession like the client does, with a fake clock and a fake frame source.
final class FakeRig {
	static final long NANOS = 1_000_000_000L;

	final List<Step> steps = new ArrayList<>();
	long now;
	long maxStepEnd;

	static FrameStats stats(double avg, double low) {
		return new FrameStats(1000, avg, low, 1000.0 / low, 2000.0 / low);
	}

	static FrameStats low(double low) {
		return stats(low * 2, low);
	}

	List<Step> drive(BenchmarkSession session, Function<Step, FrameStats> source) {
		return drive(session, source, s -> s.protocol().worstCaseSeconds());
	}

	List<Step> drive(BenchmarkSession session, Function<Step, FrameStats> source, ToDoubleFunction<Step> seconds) {
		Optional<Step> next;
		while ((next = session.next(now)).isPresent()) {
			Step step = next.get();
			steps.add(step);
			now += (long) (seconds.applyAsDouble(step) * NANOS);
			maxStepEnd = Math.max(maxStepEnd, now);
			session.record(step, source.apply(step));
		}
		return steps;
	}

	List<Step.Kind> kinds() {
		return steps.stream().map(Step::kind).toList();
	}

	double elapsedSeconds() {
		return now / (double) NANOS;
	}
}
