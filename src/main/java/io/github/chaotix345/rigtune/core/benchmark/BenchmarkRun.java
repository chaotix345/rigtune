package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.function.LongSupplier;

// A session plus the knobs it changes. Every way a run ends (last step, cancel, failure, a knob that can't be set)
// goes through end(), which restores the original knobs once.
public final class BenchmarkRun {
	private final BenchmarkSession session;
	private final KnobGuard guard;
	private final LongSupplier nanoClock;
	private @Nullable Step current;
	private boolean finished;
	private boolean cancelled;
	private boolean restoreOk = true;
	private @Nullable Throwable error;

	public BenchmarkRun(BenchmarkSession session, KnobGuard guard, LongSupplier nanoClock) {
		this.session = session;
		this.guard = guard;
		this.nanoClock = nanoClock;
	}

	/**
	 * The next step, with its knobs applied. Empty once the run has ended (the knobs are then restored). A cost report
	 * whose knobs can't be set is skipped; any other step that can't be set fails the run.
	 */
	public Optional<Step> advance() {
		if (finished) {
			return Optional.empty();
		}
		current = null;
		while (true) {
			Optional<Step> next = session.next(nanoClock.getAsLong());
			if (next.isEmpty()) {
				end();
				return next;
			}
			Step step = next.get();
			try {
				guard.set(step.knobs());
			} catch (Exception e) {
				if (BenchmarkSession.isReport(step.kind())) {
					session.skipFailed(step, "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
					continue;
				}
				fail(e);
				return Optional.empty();
			}
			current = step;
			return next;
		}
	}

	public void record(FrameStats stats) {
		if (finished || current == null) {
			throw new IllegalStateException("No step is being measured");
		}
		session.record(current, stats);
		current = null;
	}

	public void cancel() {
		if (!finished) {
			cancelled = true;
			end();
		}
	}

	public void fail(Throwable cause) {
		if (!finished) {
			error = cause;
			cancelled = true;
			end();
		}
	}

	private void end() {
		finished = true;
		current = null;
		restoreOk = guard.restore();
	}

	public @Nullable Step current() {
		return current;
	}

	public boolean finished() {
		return finished;
	}

	public boolean cancelled() {
		return cancelled;
	}

	public boolean restoreOk() {
		return restoreOk;
	}

	public @Nullable Throwable error() {
		return error;
	}

	public Knobs original() {
		return session.original();
	}

	public SessionResult result() {
		return session.result();
	}
}
