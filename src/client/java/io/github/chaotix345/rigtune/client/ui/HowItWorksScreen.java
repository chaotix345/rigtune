package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// docs/v0.5/SPEC.md 8 (C02): "How Apply works", opened by the first-run guide's How it works (from the RigTune screen or
// NoticeScreen). A static page with no I/O: one list of paragraphs (the ticked items, settings now, config at the restart,
// the mods or the launcher's role, Preview, History and Undo per Apply), each a Tab stop that narrates it. What it says
// about mod files follows who changes them (P0.4's modFiles(), read at init); the launcher's sentence is P0.4's own
// (LauncherModText.guideLine), so this screen names no launcher. Done (and Esc) goes back to the opener.
public class HowItWorksScreen extends Screen {
	private static final int LINE = 9;
	private static final int TOP = 24;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final boolean optedIn;
	private @Nullable Paragraphs list;
	private int focusedRow = -1;

	// optedIn: RigTuneController.modFilesOptedIn(), as the guide that opens this page read it (so the two can't disagree).
	public HowItWorksScreen(@Nullable Screen parent, RigTuneController controller, boolean optedIn) {
		super(Component.translatable("rigtune.firstrun.how.title"));
		this.parent = parent;
		this.controller = controller;
		this.optedIn = optedIn;
	}

	// The paragraphs, in order. guideLine: P0.4's sentence for this policy (null for plain RIGTUNE); never shown while the
	// policy is PENDING, so nothing is claimed about mod files before the launcher is known.
	static List<Component> rows(ModFilesPolicy policy, @Nullable Text guideLine) {
		List<Component> rows = new ArrayList<>();
		boolean rigtune = policy == ModFilesPolicy.RIGTUNE;
		rows.add(Component.translatable(rigtune ? "rigtune.firstrun.how.ticked" : "rigtune.firstrun.how.ticked.settings"));
		rows.add(Component.translatable("rigtune.firstrun.how.now"));
		rows.add(Component.translatable("rigtune.firstrun.how.restart"));
		if (rigtune) {
			rows.add(Component.translatable("rigtune.firstrun.how.mods"));
		}
		if (guideLine != null && policy != ModFilesPolicy.PENDING) {
			rows.add(Texts.component(guideLine));
		}
		rows.add(Component.translatable("rigtune.firstrun.how.preview"));
		rows.add(Component.translatable("rigtune.firstrun.how.undo"));
		return rows;
	}

	@Override
	protected void init() {
		ModFilesPolicy policy = controller.modFiles();
		Text guideLine = LauncherModText.guideLine(policy, controller.launcher(), optedIn);
		int column = Math.min(width - 32, 480);
		int buttonWidth = Math.min(200, width - 16);
		int buttonY = height - 26;
		list = new Paragraphs(TOP, Math.max(20, buttonY - 4 - TOP), column);
		for (Component row : rows(policy, guideLine)) {
			list.addParagraph(row);
		}
		addRenderableWidget(list);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds((width - buttonWidth) / 2, buttonY, buttonWidth, 20).build());
	}

	@Override
	protected void rebuildWidgets() {
		focusedRow = list == null ? -1 : list.focusedRow();
		super.rebuildWidgets();
	}

	@Override
	protected void setInitialFocus() {
		ComponentPath path = RowList.initialFocus(this, list, focusedRow, minecraft.getLastInputType().isKeyboard());
		focusedRow = -1;
		if (path != null) {
			changeFocus(path);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	final class Paragraphs extends RowList<Paragraphs.Paragraph> {
		private final int rowWidth;

		Paragraphs(int top, int listHeight, int rowWidth) {
			super(HowItWorksScreen.this.minecraft, HowItWorksScreen.this.width, listHeight, top, 20);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void addParagraph(Component text) {
			Paragraph paragraph = new Paragraph(text, getRowWidth() - 8);
			addEntry(paragraph, paragraph.preferredHeight());
		}

		// docs/v0.4/SPEC.md 11: every row is a Tab/arrow stop and narrates what it shows.
		final class Paragraph extends ContainerObjectSelectionList.Entry<Paragraph> {
			private final List<FormattedCharSequence> lines;
			private final RowFocus focus;

			Paragraph(Component text, int width) {
				this.lines = font.split(text, Math.max(40, width));
				this.focus = new RowFocus(this, text);
			}

			int preferredHeight() {
				return 3 + lines.size() * LINE + 5;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = getContentX() + 4;
				int y = getContentY() + 1;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, x, y, Palette.of(0xFFFFFFFF), false);
					y += LINE;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(focus);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(focus);
			}
		}
	}
}
