package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.ui.TryItScreen;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.tryit.TryIt;
import io.github.chaotix345.rigtune.core.tryit.TryItText;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

// NoticePriority.TRY_IT, docs/v0.5/SPEC.md 6 (C09): the open try's next step, not dismissible (it continues the player's
// own measurement). READY: [Measure now] [Open…] (no Measure now while its scene can't start; while the player's own world
// hasn't settled, Measure now answers with the status line as a toast); RESULT: [Keep] [Open…], a toast saying what Keep
// did; any other stage between the runs: [Open…].
// Nothing while no try is open or its runs are under way. Constructed by the lazy notice list on the first notices()
// call, never during startup (X4); it reads the service's in-memory view (X8: no file content read here).
public final class TryItNoticeSource implements NoticeSource {
	public static final String KEY_PREFIX = "tryit.";
	public static final String OPEN = "open";
	public static final String MEASURE = "measure";
	public static final String KEEP = "keep";

	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(5000L);

	private final RealController controller;

	public TryItNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		TryItView view = controller.tryIt();
		TryIt t = view.tryIt();
		Text message = TryItText.notice(view, controller.settingLabels());
		if (t == null || message == null) {
			return null;
		}
		NoticeAction open = new NoticeAction(OPEN, TryItText.noticeOpen());
		List<NoticeAction> actions = switch (view.stage()) {
			case READY -> measurable(t) ? List.of(new NoticeAction(MEASURE, TryItText.noticeMeasure()), open) : List.of(open);
			case RESULT -> List.of(new NoticeAction(KEEP, TryItText.noticeKeep()), open);
			default -> List.of(open);
		};
		return new Notice(KEY_PREFIX + t.id() + "." + view.stage().name(), NoticePriority.TRY_IT, message, null, actions, false);
	}

	private boolean measurable(TryIt t) {
		Minecraft minecraft = controller.minecraft();
		return minecraft != null && BenchmarkController.unavailable(minecraft, t.scene()) == null;
	}

	@Override
	public void act(String actionId) {
		switch (actionId) {
			case MEASURE -> {
				TryIt t = controller.tryIt().tryIt();
				Text settling = t == null ? null : controller.tryItSettling(t.scene());
				Minecraft minecraft = controller.minecraft();
				if (settling != null && minecraft != null) {
					SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Texts.component(TryItText.toastTitle()), Texts.component(settling));
				} else {
					controller.tryItMeasureNow();
				}
			}
			case KEEP -> {
				Component kept = controller.tryItKeep();
				Minecraft minecraft = controller.minecraft();
				if (minecraft != null && !kept.getString().isEmpty()) {
					SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Texts.component(TryItText.toastTitle()), kept);
				}
			}
			default -> {
				Minecraft minecraft = controller.minecraft();
				if (minecraft != null) {
					minecraft.gui.setScreen(new TryItScreen(minecraft.gui.screen(), controller, null));
				}
			}
		}
	}
}
