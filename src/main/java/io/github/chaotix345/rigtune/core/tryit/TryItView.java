package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.model.Text;

// docs/v0.5/SPEC.md 6 (C09), docs/research/v0.5/feature-try-it.md §2.5: what TryItScreen and the TRY_IT notice show,
// derived (never stored) from tryit.json, the benchmark pair and the History entry. Contracts skeleton (WS-K): the stage
// only; WS-T adds the rest (TryItFlow.derive).
public record TryItView(Stage stage) {
	public static final TryItView EMPTY = new TryItView(Stage.NONE);
	// RigTuneController.tryItRefusal's default (a controller without Try it): nothing can be tried.
	public static final Text UNAVAILABLE = Text.of("rigtune.status.nothing", "Nothing to apply.");

	// NONE: no try open. The others are ti §2.5's table.
	public enum Stage {
		NONE,
		MEASURING_BEFORE,
		STOPPED_BEFORE,
		APPLYING,
		AWAITING_RESTART,
		RETRYING,
		NOT_APPLIED,
		CANCELLED,
		MEASURING_AFTER,
		READY,
		INTERRUPTED,
		RESULT,
		REVERT_PENDING,
		REVERTED,
		NO_BEFORE,
		NO_ENTRY
	}

	public TryItView {
		stage = stage == null ? Stage.NONE : stage;
	}
}
