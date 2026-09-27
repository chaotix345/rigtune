package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.PreloadTimer;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;
import io.github.chaotix345.rigtune.core.hardware.PerfCounterAdvice;
import io.github.chaotix345.rigtune.core.hardware.PerfCounters;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// NoticePriority.STARTUP_REGRESSION, docs/v0.5/SPEC.md 9 (C18): the latest launch was slower than usual by more than the
// noise floor (StartupTrend). "Launch time 45% higher than usual (…)" with one "may be related" cause line as the detail
// and, while Windows' performance counters are off (2L), that advice; Tools… opens ToolsScreen (the same lines and 2L's
// pages), Got it keeps this launch's key in awareness.json (acknowledgedStartupRegressions), so it isn't dismissible on
// its own. A later slower launch has its own key. Advice only: nothing is applied. Constructed by the lazy notice list on
// the first notices() call, never during startup (X4); reads the view StartupTimes' worker computed, no file (X8).
public final class StartupRegressionNoticeSource implements NoticeSource {
	public static final String TOOLS = "tools";
	public static final String ACKNOWLEDGE = "acknowledge";

	private final RealController controller;
	private volatile @Nullable String shownKey;

	public StartupRegressionNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		StartupTimes times = controller.startupTimesService();
		StartupTimes.View view = times.computed();
		Notice notice = view == null || view.assessment() == null ? null : notice(view.assessment(), HardwareProbe.perfCounters(), PreloadTimer.preloadMs());
		if (notice == null || times.acknowledged(notice.key())) {
			shownKey = null;
			return null;
		}
		shownKey = notice.key();
		return notice;
	}

	// A SLOWER launch only; preloadMs: this launch's crash-report setup time (2L), or null when it wasn't measured.
	public static @Nullable Notice notice(StartupTrend.Assessment assessment, PerfCounters counters, @Nullable Long preloadMs) {
		String key = StartupTrend.key(assessment);
		if (!assessment.slower() || key == null) {
			return null;
		}
		List<Text> detail = new ArrayList<>();
		detail.add(StartupTrend.cause(assessment));
		for (PerfCounterAdvice.Line line : PerfCounterAdvice.lines(counters, preloadMs)) {
			if (line.url() == null) {
				detail.add(line.text());
			}
		}
		return new Notice(key, NoticePriority.STARTUP_REGRESSION, StartupTrend.regression(assessment), Text.join("\n", detail),
				List.of(new NoticeAction(TOOLS, Text.of("rigtune.startup.notice.tools", "Tools…")),
						new NoticeAction(ACKNOWLEDGE, Text.of("rigtune.startup.notice.acknowledge", "Got it"))), false);
	}

	@Override
	public void act(String actionId) {
		String shown = shownKey;
		if (shown == null) {
			return;
		}
		if (ACKNOWLEDGE.equals(actionId)) {
			controller.startupTimesService().acknowledge(shown);
			return;
		}
		Minecraft minecraft = controller.minecraft();
		if (TOOLS.equals(actionId) && minecraft != null) {
			minecraft.gui.setScreen(new ToolsScreen(minecraft.gui.screen(), controller));
		}
	}
}
