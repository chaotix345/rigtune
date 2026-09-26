package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// Every current notice with its actions and dismiss button, for screens too narrow for the inline notice line
// (docs/v0.4/SPEC.md C3, plan review X-M2). Opened by the notice line's "…" button; Done returns to RigTuneScreen, which
// reads the notices again.
public class NoticeScreen extends Screen {
	private static final int COLOR_NOTICE = 0xFFFFE08A;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int TOP = 24;
	private static final int ROW = 31;
	private static final int BUTTON = 14;
	private static final int GAP = 4;
	private static final int LINE_FOR_MORE = 10;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private List<Notice> shown = List.of();
	private int notShown;

	public NoticeScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.notice.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		List<Notice> notices = controller.notices();
		int rows = Math.max(1, (height - 28 - GAP - TOP - LINE_FOR_MORE) / ROW);
		shown = notices.subList(0, Math.min(rows, notices.size()));
		notShown = notices.size() - shown.size();
		int left = margin();
		for (int i = 0; i < shown.size(); i++) {
			Notice notice = shown.get(i);
			int x = left;
			int y = TOP + i * ROW + 11;
			List<Button> buttons = new ArrayList<>();
			for (NoticeAction action : notice.actions().subList(0, Math.min(2, notice.actions().size()))) {
				buttons.add(button(Texts.component(action.label()), b -> {
					controller.noticeAction(notice.key(), action.id());
					if (minecraft.gui.screen() == this) {
						rebuildWidgets();
					}
				}));
			}
			if (notice.dismissible()) {
				Button dismiss = button(Component.translatable("rigtune.notice.dismiss"), b -> {
					controller.dismissNotice(notice.key());
					rebuildWidgets();
				});
				dismiss.setTooltip(Tooltip.create(Component.translatable("rigtune.notice.dismiss.tooltip")));
				buttons.add(dismiss);
			}
			for (Button button : buttons) {
				button.setPosition(x, y);
				addRenderableWidget(button);
				x += button.getWidth() + GAP;
			}
		}
		int buttonWidth = Math.min(200, width - 16);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
	}

	private int margin() {
		return Math.max(8, (width - 480) / 2);
	}

	private Button button(Component label, Button.OnPress onPress) {
		return Button.builder(label, onPress).size(font.width(label) + 10, BUTTON).build();
	}

	/** The notices listed (for the game tests). */
	public List<Notice> shown() {
		return List.copyOf(shown);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		int left = margin();
		int maxWidth = width - 2 * left;
		for (int i = 0; i < shown.size(); i++) {
			Notice notice = shown.get(i);
			Component message = Texts.component(notice.message());
			int y = TOP + i * ROW;
			graphics.text(font, font.width(message) <= maxWidth ? message.getVisualOrderText() : ComponentRenderUtils.clipText(message, font, maxWidth),
					left, y, COLOR_NOTICE, false);
			if (mouseX >= left && mouseX < left + maxWidth && mouseY >= y && mouseY < y + 9) {
				graphics.setTooltipForNextFrame(font, notice.detail() == null ? message
						: message.copy().append(CommonComponents.NEW_LINE).append(Texts.component(notice.detail())), mouseX, mouseY);
			}
		}
		if (notShown > 0) {
			graphics.text(font, Component.translatable("rigtune.notice.more", notShown), left, TOP + shown.size() * ROW, COLOR_LABEL, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
