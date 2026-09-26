package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.ui.BenchmarkHistoryScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkTrendLines;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.List;

// NoticePriority.BENCHMARK_REGRESSION (docs/v0.4/SPEC.md 7): the newest benchmark's 1 % lows are below the usual of its
// comparable runs by at least the noise floor. Shown until acknowledged (awareness.json acknowledgedRegressions, through
// AwarenessStore.update, plan review X-M1): Details… (opens Benchmark history) and Got it both acknowledge that run, so
// it isn't dismissible on its own. The detail lists the changes History recorded in between, as possibly related.
public final class RegressionNoticeSource implements NoticeSource {
	public static final String KEY_PREFIX = "benchmark.regression.";
	public static final String DETAILS = "details";
	public static final String ACKNOWLEDGE = "acknowledge";

	private final RealController controller;
	private volatile @Nullable String runId;

	public RegressionNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		BenchmarkTrend.View view = controller.trendService().trend(null);
		BenchmarkTrend.Assessment assessment = view.assessment();
		if (assessment == null || assessment.regression() == null || assessment.latestRunId() == null
				|| controller.trendService().acknowledged(assessment.latestRunId())) {
			runId = null;
			return null;
		}
		runId = assessment.latestRunId();
		return new Notice(KEY_PREFIX + assessment.latestRunId(), NoticePriority.BENCHMARK_REGRESSION,
				TrendText.regression(assessment, TrendText.since(view, assessment, ZoneId.systemDefault())),
				TrendText.changesDetail(view.changes(), BenchmarkTrendLines::describe),
				List.of(new NoticeAction(DETAILS, Text.of("rigtune.benchmark.trend.notice.details", "Details…")),
						new NoticeAction(ACKNOWLEDGE, Text.of("rigtune.benchmark.trend.notice.acknowledge", "Got it"))), false);
	}

	@Override
	public void act(String actionId) {
		String shown = runId;
		if (shown == null) {
			return;
		}
		controller.trendService().acknowledge(shown);
		Minecraft minecraft = controller.minecraft();
		if (DETAILS.equals(actionId) && minecraft != null) {
			minecraft.gui.setScreen(new BenchmarkHistoryScreen(minecraft.gui.screen(), controller));
		}
	}
}
