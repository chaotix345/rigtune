package io.github.chaotix345.rigtune.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates.TemplateId;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Performance Profiles and share codes (docs/v0.4/SPEC.md 4), opened from ToolsScreen: the templates and the player's own and
// imported profiles, with Switch (one click, an ordinary undoable Apply), Preview, Copy code, Save current, Rename, Delete
// and Import code.
public class ProfilesScreen extends Screen {
	private static final int ROW = 22;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_ACTIVE = 0xFF7BE07B;
	private static final int COLOR_STATUS = 0xFFFFD166;
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(6000L);

	private final @Nullable Screen parent;
	protected final RigTuneController controller;
	private List<ProfileView> rows = List.of();
	private @Nullable String selected;
	private @Nullable Component status;
	private final List<Button> actions = new ArrayList<>();
	private @Nullable Button switchButton;
	private @Nullable Button previewButton;
	private @Nullable Button copyButton;
	private @Nullable Button renameButton;
	private @Nullable Button deleteButton;
	private @Nullable ProfileList list;
	// docs/v0.4/SPEC.md 11: the row that had the keyboard focus before a rebuild, which gets it back.
	private int focusedRow = -1;

	public ProfilesScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.profile.title"));
		this.parent = parent;
		this.controller = controller;
	}

	// For the game test.
	public List<ProfileView> rows() {
		return rows;
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

	public void select(@Nullable String id) {
		selected = id;
		updateButtons();
	}

	public void status(@Nullable Component message) {
		status = message;
	}

	@Override
	protected void init() {
		rows = controller.profiles();
		if (selected != null && rows.stream().noneMatch(r -> r.id().equals(selected))) {
			selected = null;
		}
		int column = Math.min(width - 16, 372);
		int gap = 4;
		int buttonWidth = (column - 2 * gap) / 3;
		int left = (width - column) / 2;
		int row3 = height - 24;
		int row2 = row3 - 24;
		int row1 = row2 - 24;
		int listTop = 32;
		list = new ProfileList(listTop, Math.max(ROW, row1 - 4 - listTop), column);
		for (ProfileView view : rows) {
			list.addRow(new ProfileRow(view));
		}
		addRenderableWidget(list);
		actions.clear();
		switchButton = action(Component.translatable("rigtune.profile.switch"), b -> switchSelected(), left, row1, buttonWidth,
				"rigtune.profile.switch.tooltip");
		previewButton = action(Component.translatable("rigtune.profile.preview"), b -> previewSelected(), left + buttonWidth + gap, row1, buttonWidth,
				"rigtune.profile.preview.tooltip");
		copyButton = action(Component.translatable("rigtune.profile.copy_code"), b -> copySelected(), left + 2 * (buttonWidth + gap), row1, buttonWidth,
				"rigtune.profile.copy_code.tooltip");
		action(Component.translatable("rigtune.profile.save_current"), b -> saveCurrent(), left, row2, buttonWidth, "rigtune.profile.save_current.tooltip");
		renameButton = action(Component.translatable("rigtune.profile.rename"), b -> renameSelected(), left + buttonWidth + gap, row2, buttonWidth, null);
		deleteButton = action(Component.translatable("rigtune.profile.delete"), b -> deleteSelected(), left + 2 * (buttonWidth + gap), row2, buttonWidth, null);
		int wide = (column - gap) / 2;
		action(Component.translatable("rigtune.profile.import"), b -> openImport(), left, row3, wide, "rigtune.profile.import.tooltip");
		action(Component.translatable("gui.done"), b -> onClose(), left + wide + gap, row3, column - wide - gap, null);
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
		ProfileView view = selectedView();
		boolean any = view != null;
		boolean own = any && !view.id().startsWith(ProfileStore.TEMPLATE_PREFIX);
		if (switchButton != null) {
			switchButton.active = any;
			previewButton.active = any;
			copyButton.active = any;
			renameButton.active = own;
			// "My settings" is the way back: it can be re-saved, never deleted.
			deleteButton.active = own && !ProfileStore.SOURCE_BASELINE.equals(view.source());
		}
	}

	private @Nullable ProfileView selectedView() {
		return selected == null ? null : rows.stream().filter(r -> r.id().equals(selected)).findFirst().orElse(null);
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

	public void switchSelected() {
		ProfileView view = selectedView();
		if (view == null) {
			return;
		}
		Component result = controller.switchProfile(view.id());
		status = result;
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.profile.title"), result);
		rebuildWidgets();
	}

	public void previewSelected() {
		ProfileView view = selectedView();
		if (view == null) {
			return;
		}
		String id = view.id();
		Component name = Texts.component(view.name());
		minecraft.gui.setScreen(new PreviewScreen(this, controller, c -> c.previewProfile(id), new PreviewScreen.Confirm(
				Component.translatable("rigtune.profile.preview.subtitle", name), Component.translatable("rigtune.profile.switch"), () -> {
					minecraft.gui.setScreen(this);
					switchSelected();
				}, null)));
	}

	public void copySelected() {
		ProfileView view = selectedView();
		if (view == null) {
			return;
		}
		String code = controller.exportProfileCode(view.id());
		if (code == null) {
			status = Component.translatable("rigtune.profile.status.nothing_to_share");
		} else {
			minecraft.keyboardHandler.setClipboard(code);
			status = Component.translatable("rigtune.profile.status.copied", Texts.component(view.name()), code.length());
		}
	}

	public void openImport() {
		minecraft.gui.setScreen(new ProfileImportScreen(this, controller));
	}

	private void saveCurrent() {
		ProfileView view = selectedView();
		String suggestion = view != null && !view.id().startsWith(ProfileStore.TEMPLATE_PREFIX) ? Texts.component(view.name()).getString() : "";
		minecraft.gui.setScreen(new NameScreen(this, Component.translatable("rigtune.profile.save_current.title"), suggestion,
				name -> status = controller.saveCurrentProfile(name)));
	}

	private void renameSelected() {
		ProfileView view = selectedView();
		if (view == null || view.id().startsWith(ProfileStore.TEMPLATE_PREFIX)) {
			return;
		}
		String id = view.id();
		minecraft.gui.setScreen(new NameScreen(this, Component.translatable("rigtune.profile.rename.title"), Texts.component(view.name()).getString(),
				name -> controller.renameProfile(id, name)));
	}

	private void deleteSelected() {
		ProfileView view = selectedView();
		if (view == null || view.id().startsWith(ProfileStore.TEMPLATE_PREFIX) || ProfileStore.SOURCE_BASELINE.equals(view.source())) {
			return;
		}
		String id = view.id();
		Component name = Texts.component(view.name());
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				controller.deleteProfile(id);
				selected = null;
				status = Component.translatable("rigtune.profile.status.deleted", name);
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("rigtune.profile.delete.title", name), Component.translatable("rigtune.profile.delete.message")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		Component line = status != null ? status : Component.translatable("rigtune.profile.subtitle");
		graphics.centeredText(font, clip(line, width - 16), width / 2, 20, Palette.of(status != null ? COLOR_STATUS : COLOR_LABEL));
		if (font.width(line) > width - 16 && mouseY >= 18 && mouseY < 30) {
			graphics.setTooltipForNextFrame(font, font.split(line, Math.max(120, width / 2)), mouseX, mouseY);
		}
	}

	private FormattedCharSequence clip(Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, maxWidth);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	static Component source(ProfileView view) {
		return switch (view.source()) {
			case ProfileView.TEMPLATE -> Component.translatable("rigtune.profile.source.template");
			case ProfileStore.SOURCE_BASELINE -> Component.translatable("rigtune.profile.source.baseline");
			case ProfileStore.SOURCE_IMPORTED -> Component.translatable("rigtune.profile.source.imported");
			default -> Component.translatable("rigtune.profile.source.saved");
		};
	}

	final class ProfileList extends RowList<ProfileRow> {
		private final int rowWidth;

		ProfileList(int top, int listHeight, int rowWidth) {
			super(ProfilesScreen.this.minecraft, ProfilesScreen.this.width, listHeight, top, ROW);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void addRow(ProfileRow row) {
			addEntry(row);
		}
	}

	final class ProfileRow extends ContainerObjectSelectionList.Entry<ProfileRow> {
		private final ProfileView view;
		private final Component name;
		private final Component right;
		private final @Nullable Component tooltip;
		// docs/v0.4/SPEC.md 11: a Tab/arrow stop narrating the name and state; Enter/Space selects the profile.
		private final RowFocus focus;

		ProfileRow(ProfileView view) {
			this.view = view;
			this.name = Texts.component(view.name()).copy().withStyle(ChatFormatting.BOLD);
			this.right = view.active() ? Component.translatable("rigtune.profile.active") : source(view);
			TemplateId template = view.id().startsWith(ProfileStore.TEMPLATE_PREFIX)
					? TemplateId.of(view.id().substring(ProfileStore.TEMPLATE_PREFIX.length())) : null;
			this.tooltip = template == null ? null : Texts.component(template.description());
			this.focus = new RowFocus(this, RowFocus.join(name, right, tooltip), () -> select(view.id()), () -> view.id().equals(selected));
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			boolean chosen = view.id().equals(selected);
			if (chosen || hovered) {
				graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight() - 1, Palette.of(chosen ? 0x30FFFFFF : 0x18FFFFFF));
			}
			int x = getContentX() + 6;
			int y = getContentY() + (getContentHeight() - 8) / 2;
			int rightWidth = font.width(right);
			if (view.active()) {
				graphics.fill(getContentX(), getY() + 3, getContentX() + 2, getY() + getHeight() - 4, Palette.of(COLOR_ACTIVE));
			}
			graphics.text(font, clip(name, Math.max(20, getContentRight() - x - rightWidth - 8)), x, y, 0xFFFFFFFF, true);
			graphics.text(font, right, getContentRight() - rightWidth, y, Palette.of(view.active() ? COLOR_ACTIVE : COLOR_LABEL), false);
			if (hovered && tooltip != null) {
				graphics.setTooltipForNextFrame(font, font.split(tooltip, Math.max(120, width / 2)), mouseX, mouseY);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				select(view.id());
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

	// A name for Save current / Rename: an edit box, Save and Cancel.
	public static final class NameScreen extends Screen {
		private final Screen parent;
		private final String initial;
		private final Consumer<String> done;
		private @Nullable EditBox box;

		NameScreen(Screen parent, Component title, String initial, Consumer<String> done) {
			super(title);
			this.parent = parent;
			this.initial = initial;
			this.done = done;
		}

		public @Nullable EditBox box() {
			return box;
		}

		@Override
		protected void init() {
			int column = Math.min(width - 16, 300);
			int left = (width - column) / 2;
			String value = box != null ? box.getValue() : initial;
			box = new EditBox(font, left, height / 2 - 20, column, 20, Component.translatable("rigtune.profile.name"));
			box.setMaxLength(64);
			box.setHint(Component.translatable("rigtune.profile.name.hint"));
			box.setValue(value);
			addRenderableWidget(box);
			setInitialFocus(box);
			int buttonWidth = (column - 4) / 2;
			addRenderableWidget(Button.builder(Component.translatable("rigtune.profile.name.save"), b -> save()).bounds(left, height / 2 + 8, buttonWidth, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
					.bounds(left + buttonWidth + 4, height / 2 + 8, column - buttonWidth - 4, 20).build());
		}

		public void save() {
			done.accept(box == null ? "" : box.getValue());
			minecraft.gui.setScreen(parent);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, height / 2 - 40, 0xFFFFFFFF);
		}

		@Override
		public void onClose() {
			minecraft.gui.setScreen(parent);
		}
	}
}
