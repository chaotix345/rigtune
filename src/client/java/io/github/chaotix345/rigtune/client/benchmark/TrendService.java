package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecords;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.ChangeWindow;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Benchmark history and regression alerts (docs/v0.4/SPEC.md 7): the trend over comparable runs of benchmarks.json, the
// change window of a regression (History's entries with History's labels), the "needs a rerun" marker against the game's
// current conditions, and the acknowledged regressions in awareness.json (plan review X-M1). RealController delegates
// benchmarkTrend() here in one line; the notice sources, the result screen, the tier tooltip and the share report read
// it too. Render thread, and only when asked (screen init, never per frame).
public final class TrendService {
	private final RealController controller;
	private final Path configDir;
	// Acknowledged this session, so "Got it" still works while awareness.json is from a newer RigTune or unreadable.
	private final Set<String> acknowledgedNow = ConcurrentHashMap.newKeySet();
	// The last view: the notice sources and the screens ask for the same one several times per screen init.
	private volatile @Nullable Memo memo;

	private record Memo(Object runs, Object journal, @Nullable String contextKey, BenchmarkTrend.@Nullable Current now, BenchmarkTrend.View view) {
	}

	public TrendService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	public BenchmarkTrend.View trend(@Nullable String contextKey) {
		List<BenchmarkRecord> runs = BenchmarkStore.history().runs();
		Minecraft minecraft = controller.minecraft();
		BenchmarkTrend.Current now = minecraft == null ? null : BenchmarkConditions.current(minecraft);
		Object journal = stamp(Journal.file(configDir));
		Memo last = memo;
		if (last != null && last.runs() == runs && last.journal().equals(journal) && Objects.equals(last.contextKey(), contextKey)
				&& Objects.equals(last.now(), now)) {
			return last.view();
		}
		BenchmarkTrend.View view = BenchmarkTrend.view(runs, contextKey, now);
		BenchmarkTrend.Assessment assessment = view.assessment();
		if (assessment != null && assessment.regression() != null) {
			BenchmarkRecord baseline = runs.stream().filter(r -> r.id().equals(assessment.baselineRunId())).findFirst().orElse(null);
			if (baseline != null) {
				view = view.withChanges(ChangeWindow.between(baseline, view.latest(), historyEntries()));
			}
		}
		memo = new Memo(runs, journal, contextKey, now, view);
		return view;
	}

	private static Object stamp(Path file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			return List.of(attributes.lastModifiedTime(), attributes.size());
		} catch (IOException e) {
			return "missing";
		}
	}

	// History's entries, newest first; none when history.json is missing, corrupt, newer or unreadable.
	private List<HistoryModel.Entry> historyEntries() {
		HistoryModel.View history = controller.history();
		return history == null || history.state() != Journal.State.OK ? List.of() : history.entries();
	}

	// The share report's latest benchmark: the newest run with a result, with its conditions and the rerun marker (the
	// newest run, as before, when none has a result).
	public @Nullable BenchmarkSummary latestSummary() {
		BenchmarkTrend.View view = trend(null);
		BenchmarkRecord last = view.last();
		if (last == null) {
			return BenchmarkStore.history().latest().map(BenchmarkRecords::summary).orElse(null);
		}
		Text rerun = TrendText.rerun(view.stale());
		return BenchmarkRecords.summary(last).withContext(TrendText.conditions(last).english(), rerun == null ? null : rerun.english());
	}

	public boolean acknowledged(String runId) {
		return acknowledgedNow.contains(runId) || AwarenessStore.shared(configDir).acknowledgedRegressions().contains(runId);
	}

	// The regression notice isn't shown again for this run.
	public void acknowledge(String runId) {
		acknowledgedNow.add(runId);
		if (!AwarenessStore.shared(configDir).acknowledgeRegression(runId)) {
			RigTune.LOGGER.warn("Could not remember the acknowledged benchmark regression in {}", AwarenessStore.file(configDir));
		}
	}
}
