package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecords;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.ChangeWindow;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	// The last views, one per context asked for (the notice sources, the result screen and Benchmark history ask for the
	// same ones several times per screen init; review L5).
	private final Map<String, Memo> memos = new LinkedHashMap<>(8, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Memo> eldest) {
			return size() > 4;
		}
	};

	private record Memo(Object runs, Object journal, BenchmarkTrend.@Nullable Current now, BenchmarkTrend.View view) {
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
		String memoKey = String.valueOf(contextKey);
		synchronized (memos) {
			Memo last = memos.get(memoKey);
			if (last != null && last.runs() == runs && last.journal().equals(journal) && Objects.equals(last.now(), now)) {
				return last.view();
			}
		}
		BenchmarkTrend.View view = BenchmarkTrend.view(runs, contextKey, now);
		BenchmarkTrend.Assessment assessment = view.assessment();
		if (assessment != null && assessment.regression() != null && view.latest() != null) {
			view = view.withChanges(changes(view.latest(), runs));
		}
		synchronized (memos) {
			memos.put(memoKey, new Memo(runs, journal, now, view));
		}
		return view;
	}

	// What History recorded between the regression's baseline and the latest run; null (no change lines at all) when
	// history.json exists but can't be read, rather than "No change recorded" (review L4).
	private @Nullable ChangeWindow changes(BenchmarkRecord latest, List<BenchmarkRecord> runs) {
		List<BenchmarkRecord> baseline = BenchmarkTrend.baseline(latest, runs);
		HistoryModel.View history = controller.history();
		if (baseline.isEmpty() || history == null || history.state() != Journal.State.OK && history.state() != Journal.State.MISSING) {
			return null;
		}
		BenchmarkRecord previous = baseline.size() >= 2 ? baseline.get(baseline.size() - 2) : null;
		return ChangeWindow.between(previous, baseline.getLast(), latest, history.entries(), stagedChangeIds());
	}

	// Journal changes staged for the next start (they carry the pending op's id).
	private static Set<String> stagedChangeIds() {
		Set<String> out = new HashSet<>();
		for (JournalEntry entry : ClientJournal.get().entries()) {
			for (JournalChange change : entry.changes()) {
				if (change.opId() != null && change.id() != null) {
					out.add(change.id());
				}
			}
		}
		return out;
	}

	private static Object stamp(Path file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			return List.of(attributes.lastModifiedTime(), attributes.size());
		} catch (IOException e) {
			return "missing";
		}
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
