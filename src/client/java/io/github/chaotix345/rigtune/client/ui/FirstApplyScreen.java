package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 8 (C02): "Your first Apply", opened once, by RigTuneScreen's Apply button only, after a new player's
// first Apply. It lists that Apply's journal entry as History does (HistoryModel's changes, drawn with HistoryScreen's own
// describe/failureText/statusColor at History's widths), grouped "In effect now" / "At the next restart" / "Undone or
// cancelled", under Apply's status line. The notes never imply a restart that isn't needed: the restart note only with a
// row waiting for the restart, "No restart needed" only with none, no download running and nothing undone. Downloads
// join the entry when they finish, so the list reloads when the controller's status changes or its downloads stop; it
// also reloads when it comes back from Undo or History. The history is read off the render thread, as History does.
// Undo this Apply, History… (that entry selected) and Done (and Esc) back to the RigTune screen.
public class FirstApplyScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int GAP = 4;
	private static final int LINE = 9;
	private static final int MIN_BUTTON = 88;
	private static final int TOP = 32;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_APPLIED = 0xFF7FE07F;
	private static final int COLOR_STAGED = 0xFFFFD166;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final int COLOR_REVERTED = 0xFF7EC8FF;
	private static final String RESTART = "rigtune.firstrun.applied.restart";
	private static final String NO_RESTART = "rigtune.firstrun.applied.no_restart";
	private static final String DOWNLOADING = "rigtune.firstrun.applied.downloading";
	// The notes that say when the Apply takes effect (the open narration's outcome).
	private static final List<String> OUTCOMES = List.of(RESTART, NO_RESTART, DOWNLOADING);

	// One row of the list, in order.
	sealed interface Item {
		record Status(Component text) implements Item {
		}

		record Section(String key, int color, int count) implements Item {
		}

		record Change(HistoryModel.Change change) implements Item {
		}

		record Note(String key, int color) implements Item {
		}

		// Why there are no change rows (loading, History's messages, nothing recorded).
		record Message(String key) implements Item {
		}
	}

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final String entryId;
	private @Nullable Component status;
	private @Nullable Component seenStatus;
	private boolean shownDownloading;
	private HistoryModel.@Nullable View view;
	private boolean stale = true;
	private boolean loading;
	private boolean failed;
	private boolean narrated;
	private int generation;
	private List<Item> items = List.of();
	private @Nullable Rows list;
	private int focusedRow = -1;
	private double scroll;

	// status: what apply() returned (the RigTune screen's status line).
	public FirstApplyScreen(@Nullable Screen parent, RigTuneController controller, String entryId, @Nullable Component status) {
		super(Component.translatable("rigtune.firstrun.applied.title"));
		this.parent = parent;
		this.controller = controller;
		this.entryId = entryId;
		this.status = status;
		this.seenStatus = controller.status();
		this.shownDownloading = controller.downloading();
	}

	// --- what the list shows (static, for the unit tests)

	static List<Item> items(@Nullable Component status, HistoryModel.@Nullable View view, boolean failed, boolean loading, String entryId,
			boolean downloading, ModFilesPolicy policy) {
		List<Item> out = new ArrayList<>();
		if (status != null) {
			out.add(new Item.Status(status));
		}
		String problem = loading ? "rigtune.history.loading" : failed || view == null ? "rigtune.history.error" : switch (view.state()) {
			case CORRUPT -> "rigtune.history.corrupt";
			case NEWER -> "rigtune.history.newer";
			case UNREADABLE -> "rigtune.history.error";
			case MISSING, OK -> null;
		};
		if (problem != null) {
			out.add(new Item.Message(problem));
			return out;
		}
		HistoryModel.Entry entry = entry(view, entryId);
		if (entry == null) {
			// Downloads alone record nothing until they finish.
			out.add(downloading ? new Item.Note(DOWNLOADING, COLOR_LABEL) : new Item.Message("rigtune.firstrun.applied.nothing"));
			return out;
		}
		List<HistoryModel.Change> now = new ArrayList<>();
		List<HistoryModel.Change> restart = new ArrayList<>();
		List<HistoryModel.Change> undone = new ArrayList<>();
		for (HistoryModel.Change change : entry.changes()) {
			(JournalChange.APPLIED.equals(change.status()) ? now : JournalChange.STAGED.equals(change.status()) ? restart : undone).add(change);
		}
		section(out, "rigtune.firstrun.applied.section.now", COLOR_APPLIED, now);
		section(out, "rigtune.firstrun.applied.section.restart", COLOR_STAGED, restart);
		section(out, "rigtune.firstrun.applied.section.undone", COLOR_REVERTED, undone);
		if (!restart.isEmpty()) {
			out.add(new Item.Note(RESTART, COLOR_STAGED));
		} else if (!downloading && undone.isEmpty() && !now.isEmpty()) {
			out.add(new Item.Note(NO_RESTART, COLOR_APPLIED));
		}
		if (downloading) {
			out.add(new Item.Note(DOWNLOADING, COLOR_LABEL));
		}
		// P0.4: under LAUNCHER or PENDING every mod-file item was advice, so this Apply changed no mod file.
		if (policy != ModFilesPolicy.RIGTUNE) {
			out.add(new Item.Note("rigtune.firstrun.applied.no_mod_files", COLOR_LABEL));
		}
		out.add(new Item.Note("rigtune.firstrun.applied.undo_hint", COLOR_LABEL));
		return out;
	}

	private static void section(List<Item> out, String key, int color, List<HistoryModel.Change> changes) {
		if (changes.isEmpty()) {
			return;
		}
		out.add(new Item.Section(key, color, changes.size()));
		changes.forEach(change -> out.add(new Item.Change(change)));
	}

	private static HistoryModel.@Nullable Entry entry(HistoryModel.@Nullable View view, String entryId) {
		return view == null ? null : view.entries().stream().filter(e -> e.id().equals(entryId)).findFirst().orElse(null);
	}

	// A change row's text and failure line: History's own functions, so the two screens can't drift apart.
	static Component describe(HistoryModel.Change change) {
		return HistoryScreen.describe(change);
	}

	static @Nullable Component failure(HistoryModel.Change change) {
		return HistoryScreen.failureText(change);
	}

	// What the narrator says when the screen opens: the title, the summary and the restart outcome.
	static Component narration(Component title, HistoryModel.@Nullable Entry entry, List<Item> items) {
		Component outcome = null;
		for (Item item : items) {
			String key = item instanceof Item.Note n && OUTCOMES.contains(n.key()) ? n.key() : item instanceof Item.Message m ? m.key() : null;
			if (key != null) {
				outcome = Component.translatable(key);
				break;
			}
		}
		return RowFocus.join(title, entry == null ? null : HistoryScreen.summary(entry), outcome);
	}

	// The history is read on the given executor (Probes.EXECUTOR), never on the render thread.
	static CompletableFuture<HistoryModel.View> read(Supplier<HistoryModel.@Nullable View> history, Executor executor) {
		return CompletableFuture.supplyAsync(history, executor);
	}

	// --- for the game tests

	public String entryId() {
		return entryId;
	}

	public boolean loading() {
		return loading;
	}

	public HistoryModel.@Nullable View view() {
		return view;
	}

	// The notes shown, by key.
	public List<String> notes() {
		return items.stream().filter(i -> i instanceof Item.Note).map(i -> ((Item.Note) i).key()).toList();
	}

	// Each change row's status, in list order.
	public List<String> changeStatuses() {
		return items.stream().filter(i -> i instanceof Item.Change).map(i -> ((Item.Change) i).change().status()).toList();
	}

	// The text of the change rows as drawn (description, then any failure line), as HistoryScreen.changeRowText() gives it.
	public List<String> changeRowText() {
		if (list == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Rows.Row row : list.children()) {
			if (row instanceof Rows.ChangeRow c) {
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

	// --- the screen

	// As HistoryScreen: the history is read only once this screen's widgets exist.
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
		// A reload (downloads finished, back from Undo or History) keeps the rows it has until the new ones arrive.
		items = items(status, view, failed, loading && view == null, entryId, shownDownloading, controller.modFiles());
		HistoryModel.Entry entry = shownEntry();
		List<Button> buttons = new ArrayList<>();
		Button undo = Button.builder(Component.translatable("rigtune.firstrun.applied.undo"), b -> open(new UndoScreen(this, controller, entryId)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.firstrun.applied.undo.tooltip"))).build();
		undo.active = entry != null && entry.undoable();
		buttons.add(undo);
		buttons.add(Button.builder(Component.translatable("rigtune.history.open"), b -> {
			HistoryScreen history = new HistoryScreen(this, controller);
			open(history);
			history.select(entryId);
		}).tooltip(Tooltip.create(Component.translatable("rigtune.history.open.tooltip"))).build());
		buttons.add(Button.builder(Component.translatable("gui.done"), b -> onClose()).build());

		// As many buttons of at least MIN_BUTTON per row as fit, then the rows balanced (as on the History screen).
		int perRow = Math.clamp((column + GAP) / (MIN_BUTTON + GAP), 1, buttons.size());
		int rows = (buttons.size() + perRow - 1) / perRow;
		perRow = (buttons.size() + rows - 1) / rows;
		int buttonWidth = Math.min(120, (column - GAP * (perRow - 1)) / perRow);
		int footerTop = height - MARGIN / 2 - rows * 20 - (rows - 1) * GAP;

		list = new Rows(TOP, Math.max(20, footerTop - 4 - TOP), column);
		for (Item item : items) {
			list.add(item);
		}
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
		int gen = ++generation;
		loading = true;
		read(controller::history, Probes.EXECUTOR).whenComplete((result, error) -> minecraft.execute(() -> {
			if (gen != generation) {
				return;
			}
			if (error != null) {
				RigTune.LOGGER.error("Could not read RigTune's history", error);
			}
			loading = false;
			failed = error != null || result == null;
			view = error != null ? null : result;
			// Behind an Undo or History screen opened meanwhile, init() runs (and reloads) when this screen comes back.
			if (minecraft.gui.screen() == this) {
				rebuildWidgets();
				if (!narrated) {
					narrated = true;
					triggerImmediateNarration(false);
				}
			}
		}));
	}

	private HistoryModel.@Nullable Entry shownEntry() {
		return failed ? null : entry(view, entryId);
	}

	private void open(Screen screen) {
		stale = true;
		minecraft.gui.setScreen(screen);
	}

	// Downloads that finish add their changes to this entry: a new controller status, or downloads stopping, reloads it.
	@Override
	public void tick() {
		Component latest = controller.status();
		boolean downloading = controller.downloading();
		boolean newStatus = latest != null && latest != seenStatus;
		if (newStatus) {
			seenStatus = latest;
			status = latest;
		}
		if (newStatus || downloading != shownDownloading) {
			shownDownloading = downloading;
			stale = true;
			rebuildWidgets();
		}
	}

	@Override
	protected void rebuildWidgets() {
		if (list != null) {
			scroll = list.scrollAmount();
			focusedRow = list.focusedRow();
		}
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
	public Component getNarrationMessage() {
		return narration(title, shownEntry(), items);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		HistoryModel.Entry entry = shownEntry();
		if (entry != null) {
			Component summary = HistoryScreen.summary(entry);
			graphics.centeredText(font, font.width(summary) <= width - 16 ? summary.getVisualOrderText() : ComponentRenderUtils.clipText(summary, font, width - 16),
					width / 2, 20, Palette.of(COLOR_LABEL));
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// --- the list

	final class Rows extends RowList<Rows.Row> {
		private final int rowWidth;

		Rows(int top, int listHeight, int rowWidth) {
			super(FirstApplyScreen.this.minecraft, FirstApplyScreen.this.width, listHeight, top, 20);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void add(Item item) {
			switch (item) {
				case Item.Status s -> addText(s.text(), COLOR_APPLIED);
				case Item.Section s -> addEntry(new SectionRow(s), 16);
				case Item.Change c -> {
					// History's own row width, so the text wraps exactly as History draws it (AC8.6).
					ChangeRow row = new ChangeRow(c.change(), getRowWidth() - 16);
					addEntry(row, row.preferredHeight());
				}
				case Item.Note n -> addText(Component.translatable(n.key()), n.color());
				case Item.Message m -> addText(Component.translatable(m.key()), COLOR_LABEL);
			}
		}

		private void addText(Component text, int color) {
			TextRow row = new TextRow(text, color, getRowWidth() - 8);
			addEntry(row, row.preferredHeight());
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

		// The status line, a note or a message: wrapped text in one colour.
		final class TextRow extends Row {
			private final List<FormattedCharSequence> lines;
			private final int color;
			private final RowFocus focus;

			TextRow(Component text, int color, int width) {
				this.lines = font.split(text, Math.max(40, width));
				this.color = color;
				this.focus = new RowFocus(this, text);
			}

			@Override
			RowFocus focus() {
				return focus;
			}

			int preferredHeight() {
				return 3 + lines.size() * LINE + 3;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = getContentX() + 4;
				int y = getContentY() + 1;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, x, y, Palette.of(color), false);
					y += LINE;
				}
			}
		}

		final class SectionRow extends Row {
			private final Component label;
			private final int color;
			private final RowFocus focus;

			SectionRow(Item.Section section) {
				Component label = Component.translatable(section.key()).append(Component.literal("  " + section.count()).withStyle(ChatFormatting.GRAY));
				this.label = label.copy().withStyle(ChatFormatting.BOLD);
				this.color = section.color();
				this.focus = new RowFocus(this, label);
			}

			@Override
			RowFocus focus() {
				return focus;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentBottom() - 11;
				graphics.text(font, label, getContentX(), y, Palette.of(color), true);
				int lineX = getContentX() + font.width(label) + 6;
				if (lineX < getContentRight()) {
					graphics.fill(lineX, y + 4, getContentRight(), y + 5, Palette.of(0x40FFFFFF));
				}
			}
		}

		// As HistoryScreen's change row: the status on the right in History's colour, the description, any failure line.
		final class ChangeRow extends Row {
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
				Component reason = failure(change);
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
				graphics.text(font, status, right - font.width(status), y, HistoryScreen.statusColor(change.status()), false);
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
}
