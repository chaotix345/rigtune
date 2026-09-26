package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.BENCHMARK_STALE: the last benchmark needs a rerun (docs/v0.4/SPEC.md 7). Skeleton from the contracts commit (never shows anything); WS-B (benchmark history) fills it, reaching its service through the
// controller (e.g. controller.trendService()).
public final class BenchmarkStaleNoticeSource implements NoticeSource {
	private final RealController controller;

	public BenchmarkStaleNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return null;
	}

	@Override
	public void act(String actionId) {
	}
}
