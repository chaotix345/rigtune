package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Cause;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Caveat;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Verdict;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The words of Try it (docs/v0.5/SPEC.md 6, docs/research/v0.5/feature-try-it.md §2.9): TryItScreen's lines, the
// Preview button's refusals, the TRY_IT notice and the title toast. Every key is written out (no dynamic family). X3:
// a verdict always shows the 1 % low change, the average change and the floor, is called a measured comparison, and
// names what else changed rather than explaining a result. Setting names and values go through History's labels.
public final class TryItText {
	// How a line is shown (the client picks the colour): a note is a caveat or reference numbers under the main lines.
	public enum Tone { NORMAL, GOOD, BAD, WARNING, NOTE }

	// One line of TryItScreen.
	public record Line(Text text, Tone tone) {
	}

	private TryItText() {
	}

	public static Text title() {
		return Text.of("rigtune.tryit.title", "Try it (measured)");
	}

	// The Preview button's tooltip when it can be pressed.
	public static Text explain() {
		return Text.of("rigtune.tryit.button.tooltip", "Measures now, applies this change, measures again, then lets you keep or revert it.");
	}

	// The refusal's words; BUSY and SCENE come from the shared busy check and the benchmark (the caller's text), and are
	// null here. open: the open try's setting, for OPEN.
	public static @Nullable Text refusal(Triable.Refusal refusal, @Nullable String open) {
		return switch (refusal) {
			case ONE -> Text.of("rigtune.tryit.refused.one", "Tick exactly one suggestion to try it.");
			case KIND -> Text.of("rigtune.tryit.refused.kind", "Only settings can be tried; mod changes can't.");
			case UNMEASURABLE -> Text.of("rigtune.tryit.refused.unmeasurable",
					"Benchmarks always run with the frame rate uncapped, so this setting can't be measured.");
			case PRESET -> Text.of("rigtune.tryit.refused.preset", "This setting changes many others at once, so it can't be tried on its own.");
			case UNSUPPORTED -> Text.of("rigtune.tryit.refused.unsupported", "RigTune doesn't change this setting, so it can't be tried.");
			case NO_FILE -> Text.of("rigtune.tryit.refused.no_file", "This setting's config file wasn't found.");
			case REMOTE_SD -> Text.of("rigtune.tryit.refused.remote_sd", "On a server, the server decides the simulation distance.");
			case PENDING -> Text.of("rigtune.tryit.refused.pending",
					"Other changes are waiting for a restart. Restart (or Discard them) first, so only this change is measured.");
			case OPEN -> Text.of("rigtune.tryit.refused.open", "Finish or cancel your other Try it first (%s).", open == null ? "?" : open);
			case HISTORY -> Text.of("rigtune.tryit.refused.history", "History can't be written right now, so this change couldn't be reverted.");
			case STORAGE -> Text.of("rigtune.tryit.refused.storage", "Results can't be saved right now; see the log.");
			case BUSY, SCENE -> null;
		};
	}

	// BenchmarkController.unavailable's language keys (the SCENE refusal), as the Benchmark menu words them.
	public static Text sceneRefusal(String key) {
		return switch (key) {
			case "rigtune.benchmark.refused.running" -> Text.of("rigtune.benchmark.refused.running", "A benchmark is already running.");
			case "rigtune.benchmark.refused.leave_world" -> Text.of("rigtune.benchmark.refused.leave_world",
					"Leave your world first: the benchmark world opens from the title screen.");
			case "rigtune.benchmark.refused.world_unsupported" -> Text.of("rigtune.benchmark.refused.world_unsupported",
					"The benchmark world isn't available.");
			default -> Text.of("rigtune.status.benchmark_unavailable", "The benchmark needs an open world.");
		};
	}

	// "Render Distance: 12 → 10".
	public static Text change(String key, @Nullable String from, @Nullable String to, HistoryModel.Labels labels) {
		return Text.of("rigtune.undo.item.setting", "%s: %s → %s", labels.label(key), shown(key, from, labels), shown(key, to, labels));
	}

	public static Text change(TryIt t, HistoryModel.Labels labels) {
		return change(t.key(), t.from(), t.to(), labels);
	}

	// TryItScreen before Start: what happens, where, and for how long.
	public static List<Line> intro(TryIt.Kind kind, BenchmarkRequest.Scene scene, String key) {
		List<Line> out = new ArrayList<>();
		out.add(normal(Text.of("rigtune.tryit.intro",
				"RigTune measures your game now, applies this change, and measures again in the same place. Then you choose: keep it or revert it.")));
		out.add(normal(scene == BenchmarkRequest.Scene.CURRENT
				? Text.of("rigtune.tryit.scene.current",
						"Measured here, where you stand. The screen and controls are taken over while it measures; Esc stops it.")
				: Text.of("rigtune.tryit.scene.world", "Measured in the benchmark world: same spot, time and weather every time.")));
		if (scene == BenchmarkRequest.Scene.CURRENT && "vanilla.renderDistance".equals(key)) {
			out.add(note(Text.of("rigtune.benchmark.menu.scene.current.saves", "Higher render distances load and save more of this world.")));
		}
		out.add(normal(kind == TryIt.Kind.NOW ? Text.of("rigtune.tryit.duration.now", "Takes about 2 minutes.")
				: Text.of("rigtune.tryit.duration.restart",
						"This setting takes effect at the next start: measure now, restart Minecraft, then measure again (about a minute each).")));
		if (scene == BenchmarkRequest.Scene.BENCHMARK_WORLD && Triable.sceneContent(key)) {
			out.add(note(caveat(Caveat.WORLD_CONTENT)));
		}
		return out;
	}

	// TryItScreen's lines for a try: the steps so far, then what this stage means (the verdict with its causes and
	// caveats for RESULT).
	public static List<Line> lines(TryItView view, HistoryModel.Labels labels) {
		TryIt t = view.tryIt();
		if (t == null) {
			return List.of();
		}
		Text change = change(t, labels);
		List<Line> out = new ArrayList<>();
		if (view.note() != null) {
			out.add(warning(view.note()));
		}
		Line before = step(view.before(), true);
		if (before != null) {
			out.add(before);
		}
		if (JournalChange.APPLIED.equals(view.changeStatus()) || JournalChange.REVERTED.equals(view.changeStatus())) {
			out.add(normal(Text.of("rigtune.tryit.step.applied", "Applied: %s", change)));
		} else if (JournalChange.STAGED.equals(view.changeStatus())) {
			out.add(normal(Text.of("rigtune.tryit.step.staged", "Waiting for a restart: %s", change)));
		}
		Line after = step(view.after(), false);
		if (after != null) {
			out.add(after);
		}
		switch (view.stage()) {
			case NONE -> {
			}
			case MEASURING_BEFORE, APPLYING, MEASURING_AFTER -> out.add(normal(Text.of("rigtune.tryit.stage.measuring", "Measuring…")));
			case STOPPED_BEFORE -> out.add(normal(Text.of("rigtune.tryit.stage.stopped_before", "Stopped before anything changed.")));
			case AWAITING_RESTART -> out.add(normal(Text.of("rigtune.tryit.stage.restart",
					"Quit and restart Minecraft. RigTune reminds you to measure again at the next start.")));
			case RETRYING -> out.add(warning(Text.of("rigtune.tryit.stage.retrying",
					"The change wasn't applied at the last restart (%s). RigTune tries again at the next restart (try %s of 3).", reason(view),
					view.failure() == null ? "?" : Integer.toString(view.failure().attempt() + 1))));
			case NOT_APPLIED -> out.add(warning(Text.of("rigtune.tryit.stage.not_applied",
					"The change wasn't applied (%s), so there's nothing to measure or revert.", reason(view))));
			case CANCELLED -> out.add(normal(Text.of("rigtune.tryit.stage.cancelled",
					"Cancelled: the change was taken back before it was applied. Nothing was changed.")));
			case READY -> {
				if (view.sameSession()) {
					out.add(normal(Text.of("rigtune.tryit.stage.stopped_after", "Stopped before the second measurement. The change is applied.")));
				} else {
					out.add(normal(Text.of("rigtune.tryit.stage.ready", "The change is in effect. Measure again to see what it did.")));
					if (t.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD) {
						out.add(note(Text.of("rigtune.tryit.stage.ready.world", "Measure again from the title screen: the benchmark world opens from there.")));
					}
				}
			}
			case INTERRUPTED -> out.add(warning(Text.of("rigtune.tryit.stage.interrupted",
					"No verdict: the game restarted between the two measurements in your own world, so they can't be matched.")));
			case RESULT -> {
				if (view.verdict() != null) {
					out.addAll(verdict(view.verdict(), labels));
				}
			}
			case REVERT_PENDING -> out.add(normal(Text.of("rigtune.tryit.stage.revert_pending", "Reverted: %s gets its old value at the next restart.",
					labels.label(t.key()))));
			case REVERTED -> out.add(normal(Text.of("rigtune.tryit.stage.reverted", "Reverted: %s.", change)));
			case NO_BEFORE -> out.add(warning(Text.of("rigtune.tryit.stage.no_before",
					"No verdict: the first measurement is no longer in the benchmark history.")));
			case NO_ENTRY -> out.add(normal(Text.of("rigtune.tryit.stage.no_entry",
					"History folded this change into an older entry, so it can't be reverted from here. It stays applied.")));
			case ENTRY_MISSING -> out.add(warning(t.unrecorded() ? unrecorded() : Text.of("rigtune.tryit.stage.entry_missing",
					"History no longer lists this change, so RigTune can't tell whether it's in effect or revert it from here.")));
			case HISTORY_UNREADABLE -> out.add(warning(Text.of("rigtune.tryit.stage.history_unreadable",
					"History or the benchmark results can't be read right now, so this Try it waits. Try again in a moment.")));
		}
		return out;
	}

	// The verdict line (always the 1 % low change, the average change and the floor, or why there's none), the numbers
	// for reference when the runs can't be compared, then the caveats.
	public static List<Line> verdict(Verdict v, HistoryModel.Labels labels) {
		List<Line> out = new ArrayList<>();
		String low = v.lowPercent() == null ? "?" : BenchmarkMath.percent(v.lowPercent());
		String avg = v.avgPercent() == null ? "?" : BenchmarkMath.percent(v.avgPercent());
		String floor = String.format(Locale.ROOT, "%.1f%%", v.floorPercent());
		switch (v.kind()) {
			case BETTER -> out.add(new Line(Text.of("rigtune.tryit.verdict.better", "Better: 1%% lows %s (average %s), more than the ±%s these runs vary by.",
					low, avg, floor), Tone.GOOD));
			case WORSE -> out.add(new Line(Text.of("rigtune.tryit.verdict.worse", "Worse: 1%% lows %s (average %s), more than the ±%s these runs vary by.",
					low, avg, floor), Tone.BAD));
			case NO_CLEAR_CHANGE -> out.add(normal(Text.of("rigtune.tryit.verdict.none",
					"No clear change: 1%% lows %s (average %s), within the ±%s these runs vary by.", low, avg, floor)));
			case NOT_COMPARABLE -> {
				out.add(warning(Text.of("rigtune.tryit.verdict.not_comparable",
						"No verdict: something else changed between the two measurements (%s). Try it again for a verdict.", causes(v.causes(), labels))));
				out.add(note(Text.of("rigtune.tryit.verdict.numbers", "For reference only: 1%% lows %s, average %s.", low, avg)));
			}
			case NO_NUMBERS -> out.add(warning(Text.of("rigtune.tryit.verdict.no_numbers", "No verdict: one of the measurements has no result.")));
		}
		for (Caveat c : v.caveats()) {
			out.add(note(caveat(c)));
		}
		return out;
	}

	public static Text causes(List<Cause> causes, HistoryModel.Labels labels) {
		return Text.join(", ", causes.stream().map(c -> cause(c, labels)).toList());
	}

	public static Text cause(Cause cause, HistoryModel.Labels labels) {
		return switch (cause) {
			case Cause.Condition c -> TrendText.difference(c.difference());
			case Cause.Excluded e -> switch (e.why()) {
				case FRESH_WORLD -> Text.of("rigtune.tryit.cause.fresh_world", "the first run in a new benchmark world");
				case DH_GENERATING -> Text.of("rigtune.tryit.cause.dh_generating", "Distant Horizons was generating terrain");
				case TERRAIN_LOADING -> Text.of("rigtune.tryit.cause.terrain_loading", "the terrain hadn't finished loading");
			};
			case Cause.Moved m -> Text.of("rigtune.tryit.cause.moved", "you moved");
			case Cause.Mods m -> Text.of("rigtune.tryit.cause.mods", "the loaded mods");
			case Cause.Entry e -> Text.of("rigtune.tryit.cause.history", "a later change in History (%s)", kind(e.kind()));
			case Cause.Setting s -> Text.of("rigtune.tryit.cause.setting", "%s changed", labels.label(s.key()));
		};
	}

	public static Text caveat(Caveat caveat) {
		return switch (caveat) {
			case NOISY -> Text.of("rigtune.tryit.caveat.noisy", "These runs varied a lot (more than 5%%), so the result is less certain.");
			case WORLD_CONTENT -> Text.of("rigtune.tryit.caveat.world_content",
					"The benchmark world has no mobs and clear weather, so this setting may show little change there.");
			case DH -> Text.of("rigtune.tryit.caveat.dh", "Distant Horizons builds distant terrain in the background, which can make runs vary.");
			case SESSIONS -> Text.of("rigtune.tryit.caveat.sessions",
					"The two measurements were in different game sessions: a driver or background program change would show up here too.");
			case SCENE -> Text.of("rigtune.tryit.caveat.scene", "A measured comparison in one scene, not proof: busier places may differ.");
		};
	}

	// The TRY_IT notice's message, or null when there's nothing to say (no try, or its runs are under way).
	public static @Nullable Text notice(TryItView view, HistoryModel.Labels labels) {
		TryIt t = view.tryIt();
		if (t == null || view.stage().chainRunning() || view.stage() == Stage.NONE) {
			return null;
		}
		String label = labels.label(t.key());
		return switch (view.stage()) {
			case AWAITING_RESTART, RETRYING -> Text.of("rigtune.tryit.notice.waiting", "Try it: %s is waiting for a restart.", label);
			case READY -> Text.of("rigtune.tryit.notice.ready", "Try it: %s is in effect. Measure again to see what it did.", label);
			case RESULT -> Text.of("rigtune.tryit.notice.result", "Try it: your result for %s is ready.", label);
			case INTERRUPTED, NO_BEFORE, ENTRY_MISSING -> Text.of("rigtune.tryit.notice.decide", "Try it: %s needs your decision.", label);
			case HISTORY_UNREADABLE -> Text.of("rigtune.tryit.notice.history", "Try it: History or the benchmark results can't be read right now, so your try of %s waits.",
					label);
			default -> Text.of("rigtune.tryit.notice.ended", "Try it: your try of %s has ended. Open it to see how.", label);
		};
	}

	public static Text noticeOpen() {
		return Text.of("rigtune.tryit.notice.open", "Open…");
	}

	public static Text noticeMeasure() {
		return Text.of("rigtune.tryit.action.measure_now", "Measure now");
	}

	public static Text noticeKeep() {
		return Text.of("rigtune.tryit.action.keep", "Keep");
	}

	public static Text toastTitle() {
		return Text.of("rigtune.tryit.toast.title", "RigTune: Try it");
	}

	// The title toast after a restart, for READY, RETRYING and NOT_APPLIED; null for the other stages.
	public static @Nullable Text toastBody(Stage stage) {
		return switch (stage) {
			case READY -> Text.of("rigtune.tryit.toast.body", "Open RigTune to measure the change again.");
			case RETRYING -> Text.of("rigtune.tryit.toast.body.retrying", "Open RigTune: the change is waiting for another restart.");
			case NOT_APPLIED -> Text.of("rigtune.tryit.toast.body.not_applied", "Open RigTune: the change wasn't applied.");
			default -> null;
		};
	}

	// Review BENCH-5: the option changed, but History's write failed.
	public static Text unrecorded() {
		return Text.of("rigtune.tryit.note.unrecorded", "History couldn't record this change (see the log), so it can't be reverted from here. It is applied.");
	}

	// Review R12FEAT-5: a RESTART try's before run settled on terrain that hadn't loaded, so it stopped before the change.
	public static Text terrainLoadingBefore() {
		return Text.of("rigtune.tryit.note.terrain_loading", "The terrain hadn't finished loading during the first measurement, so nothing was changed. Start the try again.");
	}

	// Review BENCH-7: a RESTART try's before run was left out of the trend, so it stopped before the change.
	public static Text excludedBefore(boolean freshWorld) {
		return freshWorld
				? Text.of("rigtune.tryit.note.fresh_world", "The benchmark world was just created, and its first run can't be compared, so nothing was changed. Start the try again for a verdict.")
				: Text.of("rigtune.tryit.note.dh_generating", "Distant Horizons was generating terrain during the first measurement, so nothing was changed. Start the try again once it has finished.");
	}

	// Why the chain stopped before its run (a note): the benchmark's refusal, or a run that ended without its outcome.
	public static Text lost(@Nullable Text why) {
		return why == null ? Text.of("rigtune.tryit.lost.run", "The measurement ended without a result.")
				: Text.of("rigtune.tryit.lost.start", "The measurement couldn't start: %s", why);
	}

	// The cold-start rule (docs/v0.5/SPEC.md 6, the coordinator's amendment): Start and Measure now in the player's own
	// world wait until it has settled.
	public static Text settleRefusal(int secondsLeft) {
		return Text.of("rigtune.tryit.settle.refused", "Try It measures better once the world has settled. Play for about a minute first (%s s left).",
				secondsLeft);
	}

	// A queued run waiting for the game to settle (a restart's world load, a dimension change) instead of refusing.
	public static Text settleWaiting() {
		return Text.of("rigtune.tryit.settle.waiting", "Waiting about a minute for the game to settle before measuring.");
	}

	public static Text overlay() {
		return Text.of("rigtune.tryit.overlay.between", "Try it: first measurement done. Applying the change and measuring again…");
	}

	public static Text kept(TryIt t, HistoryModel.Labels labels) {
		return Text.of("rigtune.tryit.kept", "Kept: %s.", change(t, labels));
	}

	private static @Nullable Line step(@Nullable BenchmarkRecord run, boolean before) {
		if (run == null || run.result() == null) {
			return null;
		}
		String low = fps(run.result().onePercentLowFps());
		String avg = fps(run.result().avgFps());
		return normal(before ? Text.of("rigtune.tryit.step.before", "Measured before: 1%% lows %s FPS, average %s FPS", low, avg)
				: Text.of("rigtune.tryit.step.after", "Measured after: 1%% lows %s FPS, average %s FPS", low, avg));
	}

	private static Object reason(TryItView view) {
		if (view.failure() != null) {
			return Text.literal(view.failure().reason());
		}
		return JournalChange.ABANDONED.equals(view.changeStatus()) ? Text.of("rigtune.tryit.reason.dropped", "RigTune's helper dropped it")
				: Text.of("rigtune.tryit.reason.unknown", "see the log");
	}

	private static Text kind(@Nullable String kind) {
		return switch (kind == null ? "" : kind) {
			case JournalEntry.APPLY -> Text.of("rigtune.history.kind.apply", "Apply");
			case JournalEntry.BENCHMARK -> Text.of("rigtune.history.kind.benchmark", "Benchmark result");
			case JournalEntry.UNDO -> Text.of("rigtune.history.kind.undo", "Undo");
			case JournalEntry.LEGACY_IMPORT -> Text.of("rigtune.history.kind.legacy_import", "Imported from 0.1");
			default -> Text.of("rigtune.history.kind.unknown", "Change");
		};
	}

	private static Object shown(String key, @Nullable String value, HistoryModel.Labels labels) {
		return value == null ? "?" : labels.value(key, value);
	}

	private static String fps(double value) {
		return Double.isFinite(value) ? Long.toString(Math.round(value)) : "?";
	}

	private static Line normal(Text text) {
		return new Line(text, Tone.NORMAL);
	}

	private static Line warning(Text text) {
		return new Line(text, Tone.WARNING);
	}

	private static Line note(Text text) {
		return new Line(text, Tone.NOTE);
	}
}
