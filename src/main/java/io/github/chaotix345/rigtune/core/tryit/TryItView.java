package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.List;

// docs/v0.5/SPEC.md 6 (C09), docs/research/v0.5/feature-try-it.md §2.5: what TryItScreen and the TRY_IT notice show,
// derived (never stored) from tryit.json, the benchmark pair and the History entry (TryItFlow.derive). tryIt: the open
// try (null for NONE). before/after: the pair's runs (after: the newest). verdict: for RESULT. changeStatus: the try's
// change in history.json (null when it has none there). failure: the helper's reason when the change failed or was
// abandoned at the last exit. sameSession: this is the game session that started the try. note: what this session saw go
// wrong (a run that couldn't start, a Start that couldn't be recorded), shown above the stage's lines; null otherwise.
public record TryItView(Stage stage, @Nullable TryIt tryIt, @Nullable BenchmarkRecord before, @Nullable BenchmarkRecord after,
		TryItVerdict.@Nullable Verdict verdict, @Nullable String changeStatus, ApplyFailures.@Nullable Failure failure, boolean sameSession,
		@Nullable Text note) {
	public static final TryItView EMPTY = new TryItView(Stage.NONE);
	// RigTuneController.tryItRefusal's default (a controller without Try it): nothing can be tried.
	public static final Text UNAVAILABLE = Text.of("rigtune.tryit.refused.unavailable", "Try it (measured) isn't available here.");

	// NONE: no try open. The others are ti §2.5's table, plus two that close nothing on their own: ENTRY_MISSING, the try's
	// History entry is gone while its change may have been applied (not proven folded into a baseline, which is NO_ENTRY:
	// the journal's cap can drop an entry too), so only the player's Keep ends the try; HISTORY_UNREADABLE, history.json
	// can't be read (unreadable, corrupt, or from a newer RigTune), so the change can't be seen.
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
		NO_ENTRY,
		ENTRY_MISSING,
		HISTORY_UNREADABLE;

		// Inside or between the chained runs: the shared busy check (C8) refuses other settings changes meanwhile.
		public boolean chainRunning() {
			return this == MEASURING_BEFORE || this == APPLYING || this == MEASURING_AFTER;
		}
	}

	// TryItScreen's footer buttons (ti §2.7). REVERT and CANCEL_TRY open History's Undo this for the try's entry;
	// LATER, DECIDE_LATER and DONE only leave the screen (DONE on a closing stage after it's closed).
	public enum Action {
		MEASURE_NOW, MEASURE_AGAIN, KEEP, REVERT, CANCEL_TRY, LATER, DECIDE_LATER, DONE
	}

	public TryItView(Stage stage) {
		this(stage, null, null, null, null, null, null, false, null);
	}

	public TryItView(Stage stage, @Nullable TryIt tryIt, @Nullable BenchmarkRecord before, @Nullable BenchmarkRecord after,
			TryItVerdict.@Nullable Verdict verdict, @Nullable String changeStatus, ApplyFailures.@Nullable Failure failure, boolean sameSession) {
		this(stage, tryIt, before, after, verdict, changeStatus, failure, sameSession, null);
	}

	public TryItView withNote(@Nullable Text text) {
		return new TryItView(stage, tryIt, before, after, verdict, changeStatus, failure, sameSession, text);
	}

	public TryItView {
		stage = stage == null ? Stage.NONE : stage;
	}

	public List<Action> actions() {
		return switch (stage) {
			case NONE, MEASURING_BEFORE, APPLYING, MEASURING_AFTER -> List.of();
			case STOPPED_BEFORE, NOT_APPLIED, CANCELLED, REVERT_PENDING, REVERTED, NO_ENTRY, HISTORY_UNREADABLE -> List.of(Action.DONE);
			case AWAITING_RESTART, RETRYING -> List.of(Action.CANCEL_TRY, Action.DONE);
			case READY -> sameSession ? List.of(Action.MEASURE_AGAIN, Action.KEEP, Action.REVERT)
					: List.of(Action.MEASURE_NOW, Action.CANCEL_TRY, Action.LATER);
			case INTERRUPTED, NO_BEFORE -> List.of(Action.KEEP, Action.REVERT);
			case ENTRY_MISSING -> List.of(Action.KEEP);
			case RESULT -> List.of(Action.KEEP, Action.REVERT, Action.DECIDE_LATER);
		};
	}

	// How a stage that ends the try closes it (moved to tryit.json's `recent`); null while it stays open.
	public TryIt.@Nullable Decision closing() {
		return switch (stage) {
			case STOPPED_BEFORE, CANCELLED -> TryIt.Decision.CANCELLED;
			case NOT_APPLIED -> TryIt.Decision.FAILED;
			case REVERT_PENDING, REVERTED -> TryIt.Decision.REVERTED;
			case NO_ENTRY -> TryIt.Decision.KEPT;
			default -> null;
		};
	}
}
