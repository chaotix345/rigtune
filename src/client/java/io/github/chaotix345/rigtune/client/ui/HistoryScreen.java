package io.github.chaotix345.rigtune.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// "What RigTune changed" (docs/v0.3/SPEC.md item 6): history.json newest first. The selected entry opens up to show its
// changes and their status, and for a change the helper couldn't apply at the last exit, its reason (3e). Undo this
// plans the selected entry's undo on the Undo screen; Undo last and Undo all moved here from the RigTune screen (review
// X-M2). The model is read off the render thread, when the screen opens and again after an undo.
public class HistoryScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int GAP = 4;
	private static final int LINE = 9;
	private static final int MIN_BUTTON = 88;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_APPLIED = 0xFF7FE07F;
	private static final int COLOR_STAGED = 0xFFFFD166;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final int COLOR_REVERTED = 0xFF7EC8FF;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private HistoryModel.@Nullable View view;
	private boolean stale = true;
	private boolean loading;
	private boolean failed;
	private @Nullable String selected;
	private @Nullable String clicked;
	// docs/v0.4/SPEC.md 11: the entry chosen with Enter/Space, whose row gets the focus back after the rebuild.
	private @Nullable String refocus;
	private int focusedRow = -1;
	private double scroll;
	private @Nullable HistoryList list;

	public HistoryScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.history.title"));
		this.parent = parent;
		this.controller = controller;
	}

	// Null while loading, or when the controller has no history.
	public HistoryModel.@Nullable View view() {
		return view;
	}

	public boolean loading() {
		return loading;
	}

	public @Nullable String selected() {
		return selected;
	}

	public void select(@Nullable String entryId) {
		selected = entryId;
		rebuildWidgets();
	}

	// Where an entry's row is on screen (for the game test's click), or null.
	public @Nullable ScreenRectangle entryRow(String entryId) {
		if (list == null) {
			return null;
		}
		return list.children().stream().filter(row -> row instanceof EntryRow r && r.entry.id().equals(entryId)).findFirst()
				.map(HistoryList.Row::getRectangle).orElse(null);
	}

	// The text of the change rows the list shows now, as drawn (description, then any failure line), for the game test.
	public List<String> changeRowText() {
		if (list == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (HistoryList.Row row : list.children()) {
			if (row instanceof ChangeRow c) {
				out.add(text(c.lines));
				if (!c.failure.isEmpty()) {
					out.add(text(c.failure));
				}
			}
		}
		return out;
	}

	private static String text(List<FormattedCharSequence> lines) {
		StringBuilder out = new StringBuilder();
		for (FormattedCharSequence line : lines) {
			if (!out.isEmpty()) {
				out.append(' ');
			}
			line.accept((index, style, codePoint) -> {
				out.appendCodePoint(codePoint);
				return true;
			});
		}
		return out.toString();
	}

	// The history is read only once this screen's widgets exist: a read that is ready at once completes on the render
	// thread and rebuilds the screen, which inside layout() would add every widget a second time.
	@Override
	protected void init() {
		boolean refresh = stale;
		if (refresh) {
			stale = false;
			loading = true;
		}
		layout();
		if (refresh) {
			load();
		}
	}

	private void layout() {
		int column = Math.min(width - 32, 480);
		HistoryModel.Entry entry = selectedEntry();
		List<Button> buttons = new ArrayList<>();
		Button undoThis = Button.builder(Component.translatable("rigtune.history.undo_this"), b -> {
					HistoryModel.Entry chosen = selectedEntry();
					if (chosen != null) {
						open(new UndoScreen(this, controller, chosen.id()));
					}
				})
				.tooltip(Tooltip.create(Component.translatable("rigtune.history.undo_this.tooltip"))).build();
		undoThis.active = !loading && entry != null && entry.undoable();
		buttons.add(undoThis);
		// docs/v0.4/SPEC.md 2a: inactive while there's nothing to undo, as Undo this is for its entry.
		boolean anyUndoable = !loading && HistoryModel.anyUndoable(view);
		Button undoLast = Button.builder(Component.translatable("rigtune.screen.undo_last"), b -> open(new UndoScreen(this, controller, false)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.screen.undo_last.tooltip"))).build();
		undoLast.active = anyUndoable;
		buttons.add(undoLast);
		Button undoAll = Button.builder(Component.translatable("rigtune.screen.undo_all"), b -> open(new UndoScreen(this, controller, true)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.screen.undo_all.tooltip"))).build();
		undoAll.active = anyUndoable;
		buttons.add(undoAll);
		buttons.add(Button.builder(Component.translatable("gui.done"), b -> onClose()).build());

		// As many buttons of at least MIN_BUTTON per row as fit, then the rows balanced (as on the RigTune screen).
		int perRow = Math.clamp((column + GAP) / (MIN_BUTTON + GAP), 1, buttons.size());
		int rows = (buttons.size() + perRow - 1) / perRow;
		perRow = (buttons.size() + rows - 1) / rows;
		int buttonWidth = Math.min(120, (column - GAP * (perRow - 1)) / perRow);
		int footerTop = height - MARGIN / 2 - rows * 20 - (rows - 1) * GAP;
		int listTop = 32;

		list = new HistoryList(listTop, Math.max(20, footerTop - 4 - listTop), column);
		populate(list);
		addRenderableWidget(list);
		list.setScrollAmount(scroll);

		for (int i = 0; i < buttons.size(); i++) {
			int row = i / perRow;
			int col = i % perRow;
			int inRow = Math.min(perRow, buttons.size() - row * perRow);
			int rowWidth = inRow * buttonWidth + (inRow - 1) * GAP;
			Button button = buttons.get(i);
			button.setRectangle(buttonWidth, 20, (width - rowWidth) / 2 + col * (buttonWidth + GAP), footerTop + row * (20 + GAP));
			addRenderableWidget(button);
		}
	}

	private void load() {
		loading = true;
		CompletableFuture.supplyAsync(controller::history, Probes.EXECUTOR).whenComplete((result, error) -> minecraft.execute(() -> {
			if (error != null) {
				RigTune.LOGGER.error("Could not read RigTune's history", error);
			}
			loading = false;
			failed = error != null || result == null;
			view = error != null ? null : result;
			if (view != null && view.entries().stream().noneMatch(e -> e.id().equals(selected))) {
				selected = view.entries().isEmpty() ? null : view.entries().getFirst().id();
			}
			// Behind an Undo screen opened meanwhile, init() runs (and reloads) when this screen comes back.
			if (minecraft.gui.screen() == this) {
				rebuildWidgets();
			}
		}));
	}

	private void open(Screen screen) {
		stale = true;
		minecraft.gui.setScreen(screen);
	}

	@Override
	protected void rebuildWidgets() {
		if (list != null) {
			scroll = list.scrollAmount();
			focusedRow = list.focusedRow();
		}
		super.rebuildWidgets();
	}

	// After Enter/Space the chosen entry's row (rows move when an entry opens); after any other rebuild the same row, or
	// the first button (RowList.initialFocus).
	@Override
	protected void setInitialFocus() {
		String id = refocus;
		int row = focusedRow;
		refocus = null;
		focusedRow = -1;
		if (id != null && list != null) {
			for (HistoryList.Row r : list.children()) {
				if (r instanceof EntryRow entryRow && entryRow.entry.id().equals(id)) {
					changeFocus(ComponentPath.path(entryRow.focus, entryRow, list, this));
					return;
				}
			}
		}
		ComponentPath path = RowList.initialFocus(this, list, row, minecraft.getLastInputType().isKeyboard());
		if (path != null) {
			changeFocus(path);
		}
	}

	private HistoryModel.@Nullable Entry selectedEntry() {
		return view == null ? null : view.entries().stream().filter(e -> e.id().equals(selected)).findFirst().orElse(null);
	}

	private void populate(HistoryList target) {
		if (view == null || loading) {
			return;
		}
		for (HistoryModel.Entry entry : view.entries()) {
			boolean open = entry.id().equals(selected);
			target.addRow(new EntryRow(entry, open), 26);
			if (open) {
				for (HistoryModel.Change change : entry.changes()) {
					ChangeRow row = new ChangeRow(change, target.getRowWidth() - 16);
					target.addRow(row, row.preferredHeight());
				}
			}
		}
	}

	// A click selects in the next tick, so the list isn't rebuilt while it handles the click.
	@Override
	public void tick() {
		if (clicked != null) {
			String id = clicked;
			clicked = null;
			select(id);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(font, clip(Component.translatable("rigtune.history.subtitle"), width - 16), width / 2, 20, Palette.of(COLOR_LABEL));
		Component message = message();
		if (message != null && list != null) {
			List<FormattedCharSequence> lines = font.split(message, Math.max(40, Math.min(width - 32, 400)));
			int y = list.getY() + list.getHeight() / 2 - lines.size() * LINE / 2;
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(font, line, width / 2, y, Palette.of(COLOR_LABEL));
				y += LINE;
			}
		}
	}

	// Why no entries are shown (docs/v0.3/SPEC.md item 6: empty, corrupt, newer), or null.
	private @Nullable Component message() {
		if (loading) {
			return Component.translatable("rigtune.history.loading");
		}
		if (failed || view == null) {
			return Component.translatable("rigtune.history.error");
		}
		return switch (view.state()) {
			case CORRUPT -> Component.translatable("rigtune.history.corrupt");
			case NEWER -> Component.translatable("rigtune.history.newer");
			case UNREADABLE -> Component.translatable("rigtune.history.error");
			case MISSING, OK -> view.entries().isEmpty() ? Component.translatable("rigtune.history.empty") : null;
		};
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private FormattedCharSequence clip(Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, maxWidth);
	}

	// --- text (every word from en_us.json; names, versions and values are data)

	static Component kind(HistoryModel.Entry entry) {
		return entry.profile() != null ? Component.translatable("rigtune.profile.history_kind", SafeLiteral.of(entry.profile()))
				: Component.translatable(entry.kindKey());
	}

	static Component summary(HistoryModel.Entry entry) {
		int settings = entry.settings();
		int mods = entry.mods();
		Component s = Component.translatable(settings == 1 ? "rigtune.history.summary.setting" : "rigtune.history.summary.settings", settings);
		Component m = Component.translatable(mods == 1 ? "rigtune.history.summary.mod" : "rigtune.history.summary.mods", mods);
		if (settings > 0 && mods > 0) {
			return Component.translatable("rigtune.history.summary.both", s, m);
		}
		return settings > 0 ? s : mods > 0 ? m : Component.translatable("rigtune.history.summary.none");
	}

	static Component details(HistoryModel.Entry entry) {
		MutableComponent out = Component.empty();
		if (entry.rigtuneVersion() != null) {
			out.append(Component.translatable("rigtune.history.versions", entry.rigtuneVersion(), entry.mcVersion() == null ? "?" : entry.mcVersion()));
		} else if (entry.mcVersion() != null) {
			out.append(Component.translatable("rigtune.history.versions.mc", entry.mcVersion()));
		}
		if (entry.undoOf() != null) {
			if (!out.getSiblings().isEmpty()) {
				out.append(Component.literal(" · "));
			}
			out.append(UndoPlanner.ALL.equals(entry.undoOf()) ? Component.translatable("rigtune.history.undid_all")
					: entry.undoOfAt() != null ? Component.translatable("rigtune.history.undid", UndoScreen.when(entry.undoOfAt(), ZoneId.systemDefault()))
					: Component.translatable("rigtune.history.undid_unknown"));
		}
		return out;
	}

	static Component describe(HistoryModel.Change change) {
		return switch (change.row()) {
			case SETTING -> Component.translatable("rigtune.history.change.setting", SafeLiteral.of(change.label()), value(change.before()), value(change.after()));
			case ADDED -> Component.translatable("rigtune.history.change.added", SafeLiteral.of(change.shownName()));
			case DISABLED -> Component.translatable("rigtune.history.change.disabled", SafeLiteral.of(change.shownName()));
			case REENABLED -> Component.translatable("rigtune.history.change.reenabled", SafeLiteral.of(change.shownName()));
			case UPDATED -> Component.translatable("rigtune.history.change.updated", SafeLiteral.of(change.name() != null ? change.name() : change.modId()),
					SafeLiteral.of(change.file()), SafeLiteral.of(change.newFile()));
		};
	}

	private static Component value(@Nullable String value) {
		return value == null ? Component.translatable("rigtune.history.change.none") : SafeLiteral.of(value);
	}

	// "Last attempt failed: <reason> (try n of 3 at restart)" for a staged change, "Not applied: <reason>" for an abandoned one.
	public static @Nullable Component failureText(HistoryModel.Change change) {
		ApplyFailures.Failure f = change.failure();
		if (f == null) {
			return null;
		}
		return f.abandoned() ? Component.translatable("rigtune.history.not_applied", f.reason())
				: Component.translatable("rigtune.history.failed", f.reason(), f.attempt(), ApplyFailures.MAX_ATTEMPTS);
	}

	static int statusColor(@Nullable String status) {
		if (status == null) {
			return Palette.of(COLOR_LABEL);
		}
		return Palette.of(switch (status) {
			case JournalChange.APPLIED -> COLOR_APPLIED;
			case JournalChange.STAGED -> COLOR_STAGED;
			case JournalChange.ABANDONED -> COLOR_FAIL;
			case JournalChange.REVERTED -> COLOR_REVERTED;
			default -> COLOR_LABEL;
		});
	}

	// --- the list

	final class HistoryList extends RowList<HistoryList.Row> {
		private final int rowWidth;

		HistoryList(int top, int listHeight, int rowWidth) {
			super(HistoryScreen.this.minecraft, HistoryScreen.this.width, listHeight, top, 26);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void addRow(Row row, int height) {
			addEntry(row, height);
		}

		// docs/v0.4/SPEC.md 11: every row is a Tab/arrow stop and narrates what it shows.
		abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
			abstract RowFocus focus();

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(focus());
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(focus());
			}
		}
	}

	final class EntryRow extends HistoryList.Row {
		private final HistoryModel.Entry entry;
		private final boolean open;
		private final Component heading;
		private final Component summary;
		private final Component details;
		private final RowFocus focus;

		EntryRow(HistoryModel.Entry entry, boolean open) {
			this.entry = entry;
			this.open = open;
			MutableComponent heading = kind(entry).copy().withStyle(ChatFormatting.BOLD);
			if (entry.at() != null) {
				heading.append(Component.literal(" · " + UndoScreen.when(entry.at(), ZoneId.systemDefault())).withStyle(s -> s.withBold(false)));
			}
			this.heading = heading;
			this.summary = summary(entry);
			this.details = details(entry);
			// Enter/Space selects the entry as a click does (in the next tick), and the focus stays on its row.
			this.focus = new RowFocus(this, RowFocus.join(heading, summary, details), () -> {
				refocus = entry.id();
				clicked = entry.id();
			}, () -> open);
		}

		@Override
		RowFocus focus() {
			return focus;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			if (open || hovered) {
				graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight() - 1, Palette.of(open ? 0x30FFFFFF : 0x18FFFFFF));
			}
			int x = getContentX();
			int y = getContentY() + 1;
			int right = getContentRight();
			if (open) {
				graphics.fill(x, getY() + 2, x + 2, getY() + getHeight() - 3, Palette.of(COLOR_STAGED));
			}
			int summaryWidth = font.width(summary);
			int textX = x + 6;
			graphics.text(font, clip(heading, Math.max(20, right - textX - summaryWidth - 6)), textX, y, 0xFFFFFFFF, true);
			graphics.text(font, summary, right - summaryWidth, y, Palette.of(COLOR_LABEL), false);
			graphics.text(font, clip(details, Math.max(20, right - textX)), textX, y + 11, Palette.of(COLOR_LABEL), false);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				clicked = entry.id();
				return true;
			}
			return false;
		}
	}

	final class ChangeRow extends HistoryList.Row {
		private final HistoryModel.Change change;
		private final Component status;
		private final List<FormattedCharSequence> lines;
		private final List<FormattedCharSequence> failure;
		private final RowFocus focus;

		ChangeRow(HistoryModel.Change change, int width) {
			this.change = change;
			this.status = Component.translatable(change.statusKey());
			int textWidth = Math.max(40, width - font.width(status) - 8);
			this.lines = font.split(describe(change), textWidth);
			Component reason = failureText(change);
			this.failure = reason == null ? List.of() : font.split(reason, Math.max(40, width - 8));
			this.focus = new RowFocus(this, RowFocus.join(describe(change), status, reason));
		}

		@Override
		RowFocus focus() {
			return focus;
		}

		int preferredHeight() {
			return 2 + (lines.size() + failure.size()) * LINE + 3;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int x = getContentX() + 16;
			int y = getContentY();
			int right = getContentRight();
			boolean inactive = JournalChange.DISCARDED.equals(change.status()) || JournalChange.REVERTED.equals(change.status());
			graphics.text(font, status, right - font.width(status), y, statusColor(change.status()), false);
			for (FormattedCharSequence line : lines) {
				graphics.text(font, line, x, y, Palette.of(inactive ? 0xFFB8B8B8 : 0xFFFFFFFF), false);
				y += LINE;
			}
			for (FormattedCharSequence line : failure) {
				graphics.text(font, line, x + 8, y, Palette.of(COLOR_FAIL), false);
				y += LINE;
			}
		}
	}
}
