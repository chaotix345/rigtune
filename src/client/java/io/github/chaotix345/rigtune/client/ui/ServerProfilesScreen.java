package io.github.chaotix345.rigtune.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// docs/v0.5/SPEC.md 7 (C16, AC7.11), sp §2.5: Profiles → Servers…. The This-server line (what joining this server
// offers, or why nothing can be set here), [Offer %s here] (the profile selected in Profiles, else the active one) and
// [Stop offering here], the privacy line, the remembered servers (kind, profile, last joined, "This server" on the
// current one), [Forget] [Forget all…] [Done]. Nothing here switches a profile. No address, host or port is drawn or
// narrated: rows are the store's keyed hashes. Tab order: the subtitle (or an action's status), the This-server line,
// Offer, Stop, the privacy line, the rows, Forget, Forget all, Done. After an action the status has the focus and is
// read out (X6, review-11 FEAT-4).
public class ServerProfilesScreen extends Screen {
	private static final int ROW = 22;
	private static final int LINE = 10;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_ACTIVE = 0xFF7BE07B;
	private static final int COLOR_STATUS = 0xFFFFD166;
	// The subtitle (or status), the This-server line and the privacy line wrap onto at most this many lines each, the
	// last one ending in "…" (the whole text is its tooltip and its narration).
	private static final int MAX_LINES = 2;
	private static final Component ELLIPSIS = Component.literal("…");

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	// The profile selected in Profiles, which Offer takes (null: the active one).
	private final @Nullable ProfileView chosen;
	private ServerProfilesView view = ServerProfilesView.EMPTY;
	private @Nullable String selected;
	private @Nullable Component status;
	private final List<Button> actions = new ArrayList<>();
	private @Nullable Button offerButton;
	private @Nullable Button stopButton;
	private @Nullable Button forgetButton;
	private @Nullable Button forgetAllButton;
	private @Nullable ServerList list;
	private int column;
	private int left;
	private Block head = Block.EMPTY;
	private Block here = Block.EMPTY;
	private Block privacy = Block.EMPTY;
	private int listTop;
	private @Nullable RowFocus statusFocus;
	// An action just set the status: the rebuilt screen focuses it, and it is read out.
	private boolean announce;

	// A wrapped text: its lines, where the first is drawn, and whether some of it didn't fit.
	private record Block(Component text, List<FormattedCharSequence> lines, int y, boolean cut) {
		static final Block EMPTY = new Block(Component.empty(), List.of(), 0, false);

		int bottom() {
			return y + lines.size() * LINE;
		}
	}
	// docs/v0.4/SPEC.md 11: the row that had the keyboard focus before a rebuild, which gets it back.
	private int focusedRow = -1;

	public ServerProfilesScreen(@Nullable Screen parent, RigTuneController controller, @Nullable ProfileView chosen) {
		super(Component.translatable("rigtune.profile.server.title"));
		this.parent = parent;
		this.controller = controller;
		this.chosen = chosen;
	}

	// For the game tests.
	public ServerProfilesView view() {
		return view;
	}

	public @Nullable String selected() {
		return selected;
	}

	public @Nullable Component status() {
		return status;
	}

	public List<Button> actions() {
		return List.copyOf(actions);
	}

	public @Nullable Button offerButton() {
		return offerButton;
	}

	public @Nullable Button stopButton() {
		return stopButton;
	}

	public @Nullable ServerList list() {
		return list;
	}

	public void select(@Nullable String key) {
		selected = key;
		updateButtons();
	}

	@Override
	protected void init() {
		view = controller.serverProfiles();
		if (selected != null && view.rows().stream().noneMatch(r -> r.key().equals(selected))) {
			selected = null;
		}
		column = Math.min(width - 16, 372);
		left = (width - column) / 2;
		actions.clear();
		head = block(status != null ? status : Component.translatable("rigtune.profile.server.subtitle"), width - 16, 20);
		statusFocus = addRenderableWidget(RowFocus.standalone(head.text(), 8, head.y() - 1, width - 16, head.lines().size() * LINE));
		here = block(Texts.component(view.here()), column, head.bottom() + 2);
		addRenderableWidget(RowFocus.standalone(here.text(), left, here.y() - 1, column, here.lines().size() * LINE));
		int buttonsY = here.bottom() + 4;
		int half = (column - 4) / 2;
		Component profile = offerName();
		offerButton = action(profile == null ? Component.translatable("rigtune.profile.server.remember.generic")
				: Component.translatable("rigtune.profile.server.remember", profile), b -> remember(), left, buttonsY, half,
				profile == null ? "rigtune.profile.server.remember.none" : "rigtune.profile.server.remember.tooltip");
		stopButton = action(Component.translatable("rigtune.profile.server.stop"), b -> stop(), left + half + 4, buttonsY, column - half - 4, null);
		privacy = block(Component.translatable("rigtune.profile.server.privacy"), column, buttonsY + 24);
		addRenderableWidget(RowFocus.standalone(privacy.text(), left, privacy.y() - 1, column, privacy.lines().size() * LINE));
		listTop = privacy.bottom() + 4;
		int bottom = height - 24;
		list = new ServerList(listTop, Math.max(ROW, bottom - 4 - listTop), column);
		for (ServerProfilesView.Row row : view.rows()) {
			list.addRow(new ServerRow(row));
		}
		addRenderableWidget(list);
		if (view.rows().isEmpty()) {
			addRenderableWidget(RowFocus.standalone(Component.translatable("rigtune.profile.server.empty"), left, listTop + 7, column, LINE));
		}
		int third = (column - 8) / 3;
		forgetButton = action(Component.translatable("rigtune.profile.server.forget"), b -> forgetSelected(), left, bottom, third, null);
		forgetAllButton = action(Component.translatable("rigtune.profile.server.forget_all"), b -> confirmForgetAll(), left + third + 4, bottom, third, null);
		action(Component.translatable("gui.done"), b -> onClose(), left + 2 * (third + 4), bottom, column - 2 * (third + 4), null);
		updateButtons();
	}

	private Button action(Component label, Button.OnPress press, int x, int y, int w, @Nullable String tooltip) {
		Button.Builder builder = Button.builder(label, press).bounds(x, y, w, 20);
		if (tooltip != null) {
			builder.tooltip(Tooltip.create(Component.translatable(tooltip)));
		}
		Button button = addRenderableWidget(builder.build());
		actions.add(button);
		return button;
	}

	private void updateButtons() {
		if (offerButton == null) {
			return;
		}
		boolean server = view.state() == ServerProfilesView.State.SERVER;
		offerButton.active = server && offerProfile() != null;
		stopButton.active = server && view.currentProfile() != null;
		forgetButton.active = selected != null;
		forgetAllButton.active = !view.rows().isEmpty();
	}

	private @Nullable String offerProfile() {
		return chosen != null ? chosen.id() : view.activeProfile();
	}

	private @Nullable Component offerName() {
		if (chosen != null) {
			return Texts.component(chosen.name());
		}
		return view.activeProfileName() == null ? null : Texts.component(view.activeProfileName());
	}

	@Override
	protected void rebuildWidgets() {
		focusedRow = list == null ? -1 : list.focusedRow();
		super.rebuildWidgets();
	}

	@Override
	protected void setInitialFocus() {
		ComponentPath status = announce && statusFocus != null ? statusFocus.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)) : null;
		announce = false;
		if (status != null) {
			focusedRow = -1;
			changeFocus(ComponentPath.path(this, status));
			return;
		}
		ComponentPath path = RowList.initialFocus(this, list, focusedRow, minecraft.getLastInputType().isKeyboard());
		focusedRow = -1;
		if (path != null) {
			changeFocus(path);
		}
	}

	public void remember() {
		report(controller.rememberServerProfile(offerProfile()));
	}

	public void stop() {
		if (view.currentKey() != null) {
			report(controller.forgetServerProfile(view.currentKey()));
		}
	}

	public void forgetSelected() {
		if (selected != null) {
			Component result = controller.forgetServerProfile(selected);
			selected = null;
			report(result);
		}
	}

	// An action's result: the status line, focused and read out.
	private void report(Component result) {
		status = result;
		announce = true;
		rebuildWidgets();
		triggerImmediateNarration(false);
	}

	public void confirmForgetAll() {
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				status = controller.forgetAllServerProfiles();
				selected = null;
				// Coming back, the screen opens with the status focused, and opening a screen reads its focus out.
				announce = true;
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("rigtune.profile.server.forget_all.title"), Component.translatable("rigtune.profile.server.forget_all.message")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		int headColor = Palette.of(status != null ? COLOR_STATUS : COLOR_LABEL);
		for (int i = 0; i < head.lines().size(); i++) {
			graphics.centeredText(font, head.lines().get(i), width / 2, head.y() + i * LINE, headColor);
		}
		for (int i = 0; i < here.lines().size(); i++) {
			graphics.text(font, here.lines().get(i), left, here.y() + i * LINE, 0xFFFFFFFF, true);
		}
		for (int i = 0; i < privacy.lines().size(); i++) {
			graphics.text(font, privacy.lines().get(i), left, privacy.y() + i * LINE, Palette.of(COLOR_LABEL), false);
		}
		for (Block block : List.of(head, here, privacy)) {
			int blockLeft = block == head ? 8 : left;
			int blockWidth = block == head ? width - 16 : column;
			if (block.cut() && mouseX >= blockLeft && mouseX < blockLeft + blockWidth && mouseY >= block.y() - 2 && mouseY < block.bottom()) {
				graphics.setTooltipForNextFrame(font, font.split(block.text(), Math.max(120, width / 2)), mouseX, mouseY);
			}
		}
		if (view.rows().isEmpty()) {
			graphics.centeredText(font, Component.translatable("rigtune.profile.server.empty"), width / 2, listTop + 8, Palette.of(COLOR_LABEL));
		}
	}

	// text wrapped at maxWidth onto at most MAX_LINES lines, the first at y.
	private Block block(Component text, int maxWidth, int y) {
		List<FormattedCharSequence> lines = font.split(text, maxWidth);
		if (lines.size() <= MAX_LINES) {
			return new Block(text, lines, y, false);
		}
		List<FormattedCharSequence> tight = font.split(text, maxWidth - font.width(ELLIPSIS));
		return new Block(text, List.of(tight.get(0), FormattedCharSequence.composite(tight.get(1), ELLIPSIS.getVisualOrderText())), y, true);
	}

	private FormattedCharSequence clip(Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, maxWidth);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	public final class ServerList extends RowList<ServerRow> {
		private final int rowWidth;

		ServerList(int top, int listHeight, int rowWidth) {
			super(ServerProfilesScreen.this.minecraft, ServerProfilesScreen.this.width, listHeight, top, ROW);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void addRow(ServerRow row) {
			addEntry(row);
		}
	}

	public final class ServerRow extends ContainerObjectSelectionList.Entry<ServerRow> {
		private final ServerProfilesView.Row row;
		private final Component text;
		private final @Nullable Component marker;
		// A Tab/arrow stop narrating the row's text, "This server" and "Selected"; Enter/Space selects it.
		private final RowFocus focus;

		ServerRow(ServerProfilesView.Row row) {
			this.row = row;
			this.text = Texts.component(row.text());
			this.marker = row.current() ? Component.translatable("rigtune.profile.server.row.current") : null;
			this.focus = new RowFocus(this, RowFocus.join(text, marker), () -> select(row.key()), () -> row.key().equals(selected));
		}

		public ServerProfilesView.Row row() {
			return row;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			boolean chosenRow = row.key().equals(selected);
			if (chosenRow || hovered) {
				graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight() - 1, Palette.of(chosenRow ? 0x30FFFFFF : 0x18FFFFFF));
			}
			int x = getContentX() + 6;
			int y = getContentY() + (getContentHeight() - 8) / 2;
			int markerWidth = marker == null ? 0 : font.width(marker) + 8;
			if (row.current()) {
				graphics.fill(getContentX(), getY() + 3, getContentX() + 2, getY() + getHeight() - 4, Palette.of(COLOR_ACTIVE));
			}
			graphics.text(font, clip(text, Math.max(20, getContentRight() - x - markerWidth)), x, y, 0xFFFFFFFF, true);
			if (marker != null) {
				graphics.text(font, marker, getContentRight() - font.width(marker), y, Palette.of(COLOR_ACTIVE), false);
			}
			if (hovered && font.width(text) > getContentRight() - x - markerWidth) {
				graphics.setTooltipForNextFrame(font, font.split(text, Math.max(120, width / 2)), mouseX, mouseY);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				select(row.key());
				return true;
			}
			return false;
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
