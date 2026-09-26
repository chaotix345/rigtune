package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
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

// NoticePriority.BENCHMARK_STALE (docs/v0.4/SPEC.md 7, external review §2): the last benchmark no longer describes the
// game (resolution, fullscreen, shaders or pack, Distant Horizons, mod set, render or simulation distance or MC version
// changed since). Dismissible per latest-run id (plan review B-L1; the key carries the run id, so the next run's marker
// shows again); Benchmark… opens the benchmark menu.
public final class BenchmarkStaleNoticeSource implements NoticeSource {
	public static final String KEY_PREFIX = "benchmark.stale.";
	public static final String RERUN = "rerun";

	private final RealController controller;

	public BenchmarkStaleNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		BenchmarkTrend.View view = controller.trendService().trend(null);
		if (view.last() == null || view.stale().isEmpty()) {
			return null;
		}
		return new Notice(KEY_PREFIX + view.last().id(), NoticePriority.BENCHMARK_STALE, TrendText.staleNotice(view.stale()),
				TrendText.last(view.last(), ZoneId.systemDefault()),
				List.of(new NoticeAction(RERUN, Text.of("rigtune.benchmark.trend.notice.rerun", "Benchmark…"))), true);
	}

	@Override
	public void act(String actionId) {
		Minecraft minecraft = controller.minecraft();
		if (RERUN.equals(actionId) && minecraft != null) {
			minecraft.gui.setScreen(new BenchmarkMenuScreen(minecraft.gui.screen(), controller));
		}
	}
}
