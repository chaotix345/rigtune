package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

// Changes the benchmark knobs and puts the original values back exactly once.
public final class KnobGuard {
	public interface Applier {
		/** Applies every field of `to` that differs from `from`, even if one fails; throws afterwards if any failed. */
		void apply(Knobs from, Knobs to) throws Exception;
	}

	private final Knobs original;
	private final Applier applier;
	private Knobs current;
	private boolean restored;
	private boolean restoreOk;
	private @Nullable Exception restoreError;

	public KnobGuard(Knobs original, Applier applier) {
		this.original = original;
		this.applier = applier;
		this.current = original;
	}

	public Knobs current() {
		return current;
	}

	public boolean restored() {
		return restored;
	}

	public @Nullable Exception restoreError() {
		return restoreError;
	}

	// current moves to the target before applying, so a partly applied change is still fully reverted by restore().
	public void set(Knobs target) throws Exception {
		if (restored) {
			throw new IllegalStateException("The knobs were already restored");
		}
		if (target.equals(current)) {
			return;
		}
		Knobs from = current;
		current = target;
		applier.apply(from, target);
	}

	public boolean restore() {
		if (restored) {
			return restoreOk;
		}
		restored = true;
		if (current.equals(original)) {
			restoreOk = true;
			return true;
		}
		try {
			applier.apply(current, original);
			current = original;
			restoreOk = true;
		} catch (Exception e) {
			restoreError = e;
			restoreOk = false;
		}
		return restoreOk;
	}
}
