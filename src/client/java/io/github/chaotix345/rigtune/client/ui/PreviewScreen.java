package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

// Preview (docs/v0.3/SPEC.md item 13): what Apply would do for the ticked items, file by file. The controller works it
// out off the render thread; nothing is written or downloaded.
public class PreviewScreen extends Screen {
	// One preview at a time, and never on Probes.EXECUTOR: its Modrinth lookups can't hold up a report rebuild or Apply's
	// downloads (review WS-P #7).
	private static final ExecutorService PREVIEWS = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "RigTune preview");
		thread.setDaemon(true);
		return thread;
	});
	private static final int MARGIN = 8;
	private static final int LINE = 9;
	private static final int INDENT = 12;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_HEADING = 0xFFFFD166;
	private static final int COLOR_FILE = 0xFF7EC8FF;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_NOTE = 0xFFB8B8B8;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final List<Recommendation> selected;
	// v0.4 (WS-P): what to preview, and the confirm buttons (null: the plain Preview with Done).
	private final Function<RigTuneController, ApplyPreview> loader;
	private final @Nullable Confirm confirm;
	private @Nullable Button applyButton;
	private @Nullable Button saveOnlyButton;
	private @Nullable Button cancelButton;
	private final Path gameDir = FabricLoader.getInstance().getGameDir();
	private @Nullable ApplyPreview preview;
	private boolean started;
	private boolean loading;
	private boolean failed;
	private volatile boolean closed;
	private double scroll;
	private @Nullable PreviewList list;
	// docs/v0.4/SPEC.md 11: the row that had the keyboard focus before a rebuild, which gets it back.
	private int focusedRow = -1;

	public PreviewScreen(@Nullable Screen parent, RigTuneController controller, List<Recommendation> selected) {
		super(Component.translatable("rigtune.preview.title"));
		this.parent = parent;
		this.controller = controller;
		this.selected = List.copyOf(selected);
		this.loader = c -> c.preview(this.selected);
		this.confirm = null;
	}

	// v0.4 (WS-P, docs/v0.4/SPEC.md 4): a profile switch or an imported code, previewed before anything is written, with
	// Apply / Save only / Cancel. subtitle replaces the usual one; apply and saveOnly run on the render thread and choose
	// the next screen themselves; saveOnly null leaves that button out. Cancel (and Escape) goes back to parent. ready: read
	// once the preview has loaded; false keeps Apply and Save only inactive (review-8 PR-1: an import RigTune isn't ready
	// for shows its error as a note, and nothing can be saved).
	public record Confirm(Component subtitle, Component applyLabel, Runnable apply, @Nullable Runnable saveOnly, BooleanSupplier ready) {
		public Confirm(Component subtitle, Component applyLabel, Runnable apply, @Nullable Runnable saveOnly) {
			this(subtitle, applyLabel, apply, saveOnly, () -> true);
		}
	}

	// loader: runs off the render thread, like controller.preview.
	public PreviewScreen(@Nullable Screen parent, RigTuneController controller, Function<RigTuneController, ApplyPreview> loader, Confirm confirm) {
		super(Component.translatable("rigtune.preview.title"));
		this.parent = parent;
		this.controller = controller;
		this.selected = List.of();
		this.loader = loader;
		this.confirm = confirm;
	}

	public @Nullable Button applyButton() {
		return applyButton;
	}

	public @Nullable Button saveOnlyButton() {
		return saveOnlyButton;
	}

	public @Nullable Button cancelButton() {
		return cancelButton;
	}

	// Null while loading or after a failure.
	public @Nullable ApplyPreview preview() {
		return preview;
	}

	public boolean loading() {
		return loading;
	}

	// The rows as drawn, one string per row (for the game test).
	public List<String> rowText() {
		List<String> out = new ArrayList<>();
		if (list != null) {
			for (PreviewList.Row row : list.children()) {
				StringBuilder text = new StringBuilder();
				for (FormattedCharSequence line : row.lines) {
					if (!text.isEmpty()) {
						text.append(' ');
					}
					line.accept((index, style, codePoint) -> {
						text.appendCodePoint(codePoint);
						return true;
					});
				}
				out.add(text.toString());
			}
		}
		return out;
	}

	public @Nullable PreviewList list() {
		return list;
	}

	@Override
	protected void init() {
		int column = Math.min(width - 32, 480);
		int footerTop = height - MARGIN / 2 - 20;
		int listTop = 32;
		list = new PreviewList(listTop, Math.max(20, footerTop - 4 - listTop), column);
		populate(list);
		addRenderableWidget(list);
		list.setScrollAmount(scroll);
		if (confirm != null) {
			confirmButtons(column, footerTop);
		} else {
			int buttonWidth = Math.min(150, column);
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds((width - buttonWidth) / 2, footerTop, buttonWidth, 20).build());
		}
		// Start the preview only once this screen's widgets exist: a preview that is ready at once completes on the render
		// thread and rebuilds the screen, which inside this init() would add every widget a second time.
		if (!started) {
			started = true;
			load();
		}
	}

	// Apply (only once there's something to apply) / Save only / Cancel, in one row.
	private void confirmButtons(int column, int top) {
		int count = confirm.saveOnly() == null ? 2 : 3;
		int gap = 4;
		int buttonWidth = Math.min(120, (column - gap * (count - 1)) / count);
		int x = (width - (buttonWidth * count + gap * (count - 1))) / 2;
		boolean ready = preview != null && !loading && !failed && confirm.ready().getAsBoolean();
		applyButton = addRenderableWidget(Button.builder(confirm.applyLabel(), b -> confirm.apply().run()).bounds(x, top, buttonWidth, 20).build());
		applyButton.active = ready && !preview.isEmpty();
		x += buttonWidth + gap;
		saveOnlyButton = null;
		if (confirm.saveOnly() != null) {
			saveOnlyButton = addRenderableWidget(Button.builder(Component.translatable("rigtune.profile.preview.save_only"), b -> confirm.saveOnly().run())
					.bounds(x, top, buttonWidth, 20).build());
			saveOnlyButton.active = ready;
			x += buttonWidth + gap;
		}
		cancelButton = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x, top, buttonWidth, 20).build());
	}

	private void load() {
		loading = true;
		// A preview still queued when the screen closes is skipped.
		CompletableFuture.supplyAsync(() -> closed ? null : loader.apply(controller), PREVIEWS).whenComplete((result, error) -> minecraft.execute(() -> {
			if (closed) {
				return;
			}
			if (error != null) {
				RigTune.LOGGER.error("Could not work out the RigTune preview", error);
			}
			loading = false;
			failed = error != null || result == null;
			preview = failed ? null : result;
			if (minecraft.gui.screen() == this) {
				rebuildWidgets();
			}
		}));
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

	private void populate(PreviewList target) {
		ApplyPreview shown = preview;
		if (shown == null) {
			return;
		}
		int width = target.getRowWidth() - 10;
		if (!shown.now().isEmpty()) {
			target.heading("rigtune.preview.section.now", width);
			settings(target, shown.now(), width);
		}
		if (!shown.atRestart().isEmpty()) {
			target.heading("rigtune.preview.section.restart", width);
			settings(target, shown.atRestart(), width);
		}
		if (!shown.downloads().isEmpty()) {
			target.heading("rigtune.preview.section.downloads", width);
			for (ApplyPreview.Download download : shown.downloads()) {
				target.row(download(download), COLOR_TEXT, INDENT, width);
			}
			if (shown.downloads().stream().anyMatch(d -> d.fileName() != null)) {
				target.row(Component.translatable("rigtune.preview.note.downloads"), COLOR_NOTE, INDENT, width);
			}
			if (!shown.resolved()) {
				target.row(Component.translatable("rigtune.preview.note.unresolved"), COLOR_NOTE, INDENT, width);
			}
		}
		if (!shown.disables().isEmpty()) {
			target.heading("rigtune.preview.section.disables", width);
			for (ApplyPreview.Disable disable : shown.disables()) {
				target.row(Component.translatable("rigtune.preview.disable", relative(disable.file()), String.valueOf(disable.disabledAs().getFileName()),
						Texts.component(disable.titleText())), COLOR_TEXT, INDENT, width);
			}
		}
		if (!shown.skipped().isEmpty()) {
			target.heading("rigtune.preview.section.skipped", width);
			for (ApplyPreview.Skipped skipped : shown.skipped()) {
				target.row(skipped(skipped), COLOR_NOTE, INDENT, width);
			}
		}
		notes(target, shown, width);
	}

	// v0.4 (WS-P): the preview's notes (a profile's clamps and left-out keys, plan review P-L2).
	private void notes(PreviewList target, ApplyPreview shown, int width) {
		if (shown.notes().isEmpty()) {
			return;
		}
		target.heading("rigtune.profile.preview.notes", width);
		for (Text note : shown.notes()) {
			target.row(Texts.component(note), COLOR_NOTE, INDENT, width);
		}
	}

	// Grouped by file, in order: the file, then its keys, named and valued as History shows them (docs/v0.4/SPEC.md 2b).
	private void settings(PreviewList target, List<ApplyPreview.Setting> settings, int width) {
		Map<Path, List<ApplyPreview.Setting>> byFile = new LinkedHashMap<>();
		settings.forEach(s -> byFile.computeIfAbsent(s.file(), f -> new ArrayList<>()).add(s));
		HistoryModel.Labels labels = controller.settingLabels();
		Map<Path, String> prefixes = new HashMap<>();
		prefixes.put(normal(gameDir.resolve("options.txt")), SettingKeys.VANILLA_PREFIX);
		ConfigTargets.all(FabricLoader.getInstance().getConfigDir()).forEach(t -> prefixes.put(normal(t.file()), t.prefix()));
		byFile.forEach((file, values) -> {
			target.row(SafeLiteral.of(relative(file)), COLOR_FILE, INDENT, width);
			for (ApplyPreview.Setting s : values) {
				target.row(settingRow(s, prefixes.get(normal(file)), labels), COLOR_TEXT, 2 * INDENT, width);
			}
		});
	}

	// "<name>: <before> → <after>" through History's labels; a file RigTune doesn't know (prefix null) keeps the raw key.
	static Component settingRow(ApplyPreview.Setting s, @Nullable String prefix, HistoryModel.Labels labels) {
		if (prefix == null) {
			return Component.translatable("rigtune.preview.setting", s.key(), value(s.oldValue()), value(s.newValue()));
		}
		String key = prefix + s.key();
		return Component.translatable("rigtune.preview.setting", SafeLiteral.of(labels.label(key)), labelled(labels, key, s.oldValue()), labelled(labels, key, s.newValue()));
	}

	private static Component labelled(HistoryModel.Labels labels, String key, @Nullable String value) {
		return value == null ? value(null) : SafeLiteral.of(labels.value(key, value));
	}

	private static Path normal(Path file) {
		return file.toAbsolutePath().normalize();
	}

	private String relative(Path file) {
		return ApplyPreview.relative(gameDir, file);
	}

	private Component download(ApplyPreview.Download d) {
		if (d.fileName() == null) {
			return Component.translatable("rigtune.preview.download.unresolved", Texts.component(d.titleText()));
		}
		String file = d.target() == null ? d.fileName() : relative(d.target());
		return Component.translatable(d.dependency() ? "rigtune.preview.download.dependency" : "rigtune.preview.download", file,
				Texts.component(d.titleText()));
	}

	static Component skipped(ApplyPreview.Skipped s) {
		Component title = Texts.component(s.titleText());
		return switch (s.reason()) {
			case NOTHING_TO_APPLY -> Component.translatable("rigtune.preview.skipped.nothing_to_apply", title);
			case NOT_CHANGEABLE -> Component.translatable("rigtune.preview.skipped.not_changeable", title);
			case UNKNOWN_SETTING -> Component.translatable("rigtune.preview.skipped.unknown_setting", title);
			case UNCHANGED -> Component.translatable("rigtune.preview.skipped.unchanged", title);
			case OUTSIDE_MODS -> Component.translatable("rigtune.preview.skipped.outside_mods", title);
			case NO_NEW_FILES -> Component.translatable("rigtune.preview.skipped.no_new_files", title);
			case REFUSED, DOWNLOAD_FAILED -> s.detailText() == null ? Component.translatable("rigtune.preview.skipped.failed", title)
					: Component.translatable("rigtune.preview.skipped.detail", title, Texts.component(s.detailText()));
		};
	}

	private static Component value(@Nullable String value) {
		return value == null ? Component.translatable("rigtune.preview.value.none") : SafeLiteral.of(value);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(font, clip(confirm != null ? confirm.subtitle() : Component.translatable("rigtune.preview.subtitle"), width - 16), width / 2, 20,
				Palette.of(COLOR_LABEL));
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

	private @Nullable Component message() {
		if (loading) {
			return Component.translatable("rigtune.preview.loading");
		}
		if (failed || preview == null) {
			return Component.translatable("rigtune.preview.error");
		}
		return preview.isEmpty() && preview.skipped().isEmpty() && preview.notes().isEmpty() ? Component.translatable("rigtune.preview.empty") : null;
	}

	private FormattedCharSequence clip(Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, maxWidth);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	public final class PreviewList extends RowList<PreviewList.Row> {
		private final int rowWidth;
		private boolean first = true;

		PreviewList(int top, int listHeight, int rowWidth) {
			super(PreviewScreen.this.minecraft, PreviewScreen.this.width, listHeight, top, 12);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void heading(String key, int width) {
			Row row = new Row(Component.translatable(key).withStyle(ChatFormatting.BOLD), COLOR_HEADING, 0, first ? 1 : 7, width, true);
			first = false;
			addEntry(row, row.height());
		}

		void row(Component text, int color, int indent, int width) {
			Row row = new Row(text, color, indent, 1, width, false);
			addEntry(row, row.height());
		}

		public final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final List<FormattedCharSequence> lines;
			private final int color;
			private final int indent;
			private final int top;
			private final boolean shadow;
			// docs/v0.4/SPEC.md 11: a Tab/arrow stop that narrates the row's text.
			private final RowFocus focus;

			Row(Component text, int color, int indent, int top, int width, boolean shadow) {
				this.lines = font.split(text, Math.max(40, width - indent));
				this.focus = new RowFocus(this, text);
				this.color = color;
				this.indent = indent;
				this.top = top;
				this.shadow = shadow;
			}

			int height() {
				return top + lines.size() * LINE + 1;
			}

			// Whether every line ends inside the row (for the game test's layout checks).
			public boolean textFits() {
				int widest = 0;
				for (FormattedCharSequence line : lines) {
					widest = Math.max(widest, font.width(line));
				}
				return indent + widest <= getContentWidth();
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentY() + top;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, getContentX() + indent, y, Palette.of(color), shadow);
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
