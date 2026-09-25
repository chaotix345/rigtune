package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// Preview (docs/v0.3/SPEC.md item 13): what Apply would do for the ticked items, file by file. The controller works it
// out off the render thread; nothing is written or downloaded.
public class PreviewScreen extends Screen {
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
	private final Path gameDir = FabricLoader.getInstance().getGameDir();
	private @Nullable ApplyPreview preview;
	private boolean started;
	private boolean loading;
	private boolean failed;
	private double scroll;
	private @Nullable PreviewList list;

	public PreviewScreen(@Nullable Screen parent, RigTuneController controller, List<Recommendation> selected) {
		super(Component.translatable("rigtune.preview.title"));
		this.parent = parent;
		this.controller = controller;
		this.selected = List.copyOf(selected);
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
		if (!started) {
			started = true;
			load();
		}
		int column = Math.min(width - 32, 480);
		int footerTop = height - MARGIN / 2 - 20;
		int listTop = 32;
		list = new PreviewList(listTop, Math.max(20, footerTop - 4 - listTop), column);
		populate(list);
		addRenderableWidget(list);
		list.setScrollAmount(scroll);
		int buttonWidth = Math.min(150, column);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds((width - buttonWidth) / 2, footerTop, buttonWidth, 20).build());
	}

	private void load() {
		loading = true;
		CompletableFuture.supplyAsync(() -> controller.preview(selected), Probes.EXECUTOR).whenComplete((result, error) -> minecraft.execute(() -> {
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
		}
		super.rebuildWidgets();
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
						disable.title()), COLOR_TEXT, INDENT, width);
			}
		}
		if (!shown.skipped().isEmpty()) {
			target.heading("rigtune.preview.section.skipped", width);
			for (ApplyPreview.Skipped skipped : shown.skipped()) {
				target.row(skipped(skipped), COLOR_NOTE, INDENT, width);
			}
		}
	}

	// Grouped by file, in order: the file, then its keys.
	private void settings(PreviewList target, List<ApplyPreview.Setting> settings, int width) {
		Map<Path, List<ApplyPreview.Setting>> byFile = new LinkedHashMap<>();
		settings.forEach(s -> byFile.computeIfAbsent(s.file(), f -> new ArrayList<>()).add(s));
		byFile.forEach((file, values) -> {
			target.row(Component.literal(relative(file)), COLOR_FILE, INDENT, width);
			for (ApplyPreview.Setting s : values) {
				target.row(Component.translatable("rigtune.preview.setting", s.key(), value(s.oldValue()), value(s.newValue())), COLOR_TEXT, 2 * INDENT, width);
			}
		});
	}

	private String relative(Path file) {
		return ApplyPreview.relative(gameDir, file);
	}

	private Component download(ApplyPreview.Download d) {
		if (d.fileName() == null) {
			return Component.translatable("rigtune.preview.download.unresolved", d.title());
		}
		String file = d.target() == null ? d.fileName() : relative(d.target());
		return Component.translatable(d.dependency() ? "rigtune.preview.download.dependency" : "rigtune.preview.download", file, d.title());
	}

	static Component skipped(ApplyPreview.Skipped s) {
		return switch (s.reason()) {
			case NOTHING_TO_APPLY -> Component.translatable("rigtune.preview.skipped.nothing_to_apply", s.title());
			case NOT_CHANGEABLE -> Component.translatable("rigtune.preview.skipped.not_changeable", s.title());
			case UNKNOWN_SETTING -> Component.translatable("rigtune.preview.skipped.unknown_setting", s.title());
			case UNCHANGED -> Component.translatable("rigtune.preview.skipped.unchanged", s.title());
			case OUTSIDE_MODS -> Component.translatable("rigtune.preview.skipped.outside_mods", s.title());
			case NO_NEW_FILES -> Component.translatable("rigtune.preview.skipped.no_new_files", s.title());
			case REFUSED, DOWNLOAD_FAILED -> s.detail() == null ? Component.translatable("rigtune.preview.skipped.failed", s.title())
					: Component.translatable("rigtune.preview.skipped.detail", s.title(), s.detail());
		};
	}

	private static Component value(@Nullable String value) {
		return value == null ? Component.translatable("rigtune.preview.value.none") : Component.literal(value);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(font, clip(Component.translatable("rigtune.preview.subtitle"), width - 16), width / 2, 20, COLOR_LABEL);
		Component message = message();
		if (message != null && list != null) {
			List<FormattedCharSequence> lines = font.split(message, Math.max(40, Math.min(width - 32, 400)));
			int y = list.getY() + list.getHeight() / 2 - lines.size() * LINE / 2;
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(font, line, width / 2, y, COLOR_LABEL);
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
		return preview.isEmpty() && preview.skipped().isEmpty() ? Component.translatable("rigtune.preview.empty") : null;
	}

	private FormattedCharSequence clip(Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, maxWidth);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	public final class PreviewList extends ContainerObjectSelectionList<PreviewList.Row> {
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

			Row(Component text, int color, int indent, int top, int width, boolean shadow) {
				this.lines = font.split(text, Math.max(40, width - indent));
				this.color = color;
				this.indent = indent;
				this.top = top;
				this.shadow = shadow;
			}

			int height() {
				return top + lines.size() * LINE + 1;
			}

			// Where the text ends on screen (for the game test's layout checks).
			public int textRight() {
				int widest = 0;
				for (FormattedCharSequence line : lines) {
					widest = Math.max(widest, font.width(line));
				}
				return getContentX() + indent + widest;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentY() + top;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, getContentX() + indent, y, color, shadow);
					y += LINE;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}
	}
}
