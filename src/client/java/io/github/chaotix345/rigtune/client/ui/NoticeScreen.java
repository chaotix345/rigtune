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
	private static final int MESSAGE = 11;
	private static final int ROW_GAP = 6;
	private static final int BUTTON = 14;
	private static final int GAP = 4;
	private static final int LINE_FOR_MORE = 10;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private List<Notice> shown = List.of();
	private final List<Integer> rowY = new ArrayList<>();
	private int notShown;
	private int moreY;

	public NoticeScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.notice.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		List<Notice> notices = controller.notices();
		int bottom = height - 28 - GAP - LINE_FOR_MORE;
		int left = margin();
		int y = TOP;
		List<Notice> fitting = new ArrayList<>();
		rowY.clear();
		for (Notice notice : notices) {
			int x = left;
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
			// Buttons in rows of what fits the width.
			int lines = buttons.isEmpty() ? 0 : 1;
			int lineX = left;
			for (Button button : buttons) {
				if (lineX > left && lineX + button.getWidth() > width - left) {
					lines++;
					lineX = left;
				}
				lineX += button.getWidth() + GAP;
			}
			int rowHeight = MESSAGE + lines * (BUTTON + 2) + ROW_GAP;
			if (!fitting.isEmpty() && y + rowHeight > bottom) {
				break;
			}
			fitting.add(notice);
			rowY.add(y);
			// review-8 UV-2: the notice's own text (and its detail) is a Tab stop the narrator reads, ahead of its buttons.
			addRenderableWidget(RowFocus.standalone(RowFocus.join(Texts.component(notice.message()), notice.detail() == null ? null
					: Texts.component(notice.detail())), left, y - 1, Math.max(1, width - 2 * left), MESSAGE));
			int buttonY = y + MESSAGE;
			for (Button button : buttons) {
				if (x > left && x + button.getWidth() > width - left) {
					x = left;
					buttonY += BUTTON + 2;
				}
				button.setWidth(Math.min(button.getWidth(), width - 2 * left));
				button.setPosition(x, buttonY);
				addRenderableWidget(button);
				x += button.getWidth() + GAP;
			}
			y += rowHeight;
		}
		shown = List.copyOf(fitting);
		notShown = notices.size() - shown.size();
		moreY = y;
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
			int y = rowY.get(i);
			graphics.text(font, font.width(message) <= maxWidth ? message.getVisualOrderText() : ComponentRenderUtils.clipText(message, font, maxWidth),
					left, y, Palette.of(COLOR_NOTICE), false);
			if (mouseX >= left && mouseX < left + maxWidth && mouseY >= y && mouseY < y + 9) {
				// Wrapped: a notice's detail is often longer than the screen is wide (v0.4, WS-W).
				graphics.setTooltipForNextFrame(font, font.split(notice.detail() == null ? message
						: message.copy().append(CommonComponents.NEW_LINE).append(Texts.component(notice.detail())), Math.min(250, width - 16)), mouseX, mouseY);
			}
		}
		if (notShown > 0) {
			graphics.text(font, Component.translatable("rigtune.notice.more", notShown), left, moreY, Palette.of(COLOR_LABEL), false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
