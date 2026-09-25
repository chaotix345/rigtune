package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RigTuneScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int GAP = 4;
	private static final int LINE = 10;
	private static final int MIN_BUTTON = 88;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_REASON = 0xFFB8B8B8;
	private static final int COLOR_WARNING = 0xFFFF6E5E;
	private static final int COLOR_ADVICE = 0xFF7EC8FF;
	private static final int COLOR_NEUTRAL = 0xFF8A8A8A;
	private static final int COLOR_OK = 0xFF7FE07F;
	private static final int COLOR_LAUNCHER = 0xFFA8E0B0;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final ClientSettings settings = ClientSettings.shared(FabricLoader.getInstance().getConfigDir());
	private final Set<String> selected = new HashSet<>();
	private final Set<String> known = new HashSet<>();
	private @Nullable Report shown;
	private @Nullable Component status;
	private List<Component> headerLines = List.of();
	private @Nullable Component tierBadge;
	private int headerTop;
	private int left;
	private int right;
	private int badgeRight;
	private int headerBottom;
	private int statusY;
	private @Nullable RecommendationList list;
	private @Nullable Button applyButton;
	private @Nullable Map<String, String> captions;
	private @Nullable Component seenControllerStatus;
	private LauncherInfo shownLauncher = LauncherInfo.UNKNOWN;
	private final List<Component> launcherAdvice = new ArrayList<>();

	public RigTuneScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.screen.title"));
		this.parent = parent;
		this.controller = controller;
		this.seenControllerStatus = controller.status();
	}

	public RigTuneController controller() {
		return controller;
	}

	@Override
	protected void init() {
		if (captions == null) {
			captions = SettingsBridge.captions(minecraft.options);
		}
		shown = controller.report();
		shownLauncher = controller.launcher();
		launcherAdvice.clear();
		syncSelection(shown);

		int titleWidth = font.width(title.copy().withStyle(ChatFormatting.BOLD));
		int column = columnWidth(width);
		left = (width - column) / 2;
		right = left + column;
		Component settingsLabel = Component.translatable("rigtune.screen.settings");
		int settingsWidth = font.width(settingsLabel) + 12;
		addRenderableWidget(Button.builder(settingsLabel, b -> minecraft.gui.setScreen(new RigTuneSettingsScreen(this, controller)))
				.bounds(right - settingsWidth, 5, settingsWidth, 20).build());
		badgeRight = right - settingsWidth - MARGIN;
		int goalWidth = Math.min(130, column - titleWidth - MARGIN - settingsWidth - GAP);
		addRenderableWidget(CycleButton.builder((Goal g) -> Component.translatable("rigtune.goal." + g.name().toLowerCase(Locale.ROOT)), controller.goal())
				.withValues(Goal.values())
				.withTooltip(g -> Tooltip.create(Component.translatable("rigtune.goal." + g.name().toLowerCase(Locale.ROOT) + ".tooltip")))
				.create(left + titleWidth + MARGIN, 5, goalWidth, 20, Component.translatable("rigtune.screen.goal"), (button, goal) -> controller.setGoal(goal)));

		tierBadge = shown == null ? null : tierBadge(shown);
		int badgeRoom = badgeRight - (left + titleWidth + goalWidth + 2 * MARGIN);
		boolean badgeInTitleRow = tierBadge != null && font.width(tierBadge) <= badgeRoom;
		headerLines = shown == null ? List.of() : header(shown, badgeInTitleRow ? null : tierBadge);
		if (!badgeInTitleRow) {
			tierBadge = null;
		}
		headerTop = 30;
		headerBottom = headerTop + Math.max(1, headerLines.size()) * LINE + 2;
		int listTop = headerBottom + 4;

		List<Button> buttons = new ArrayList<>();
		applyButton = Button.builder(Component.translatable("rigtune.screen.apply"), b -> applySelected()).build();
		buttons.add(applyButton);
		buttons.add(Button.builder(Component.translatable("rigtune.screen.undo_last"), b -> minecraft.gui.setScreen(new UndoScreen(this, controller, false)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.screen.undo_last.tooltip"))).build());
		buttons.add(Button.builder(Component.translatable("rigtune.screen.undo_all"), b -> minecraft.gui.setScreen(new UndoScreen(this, controller, true)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.screen.undo_all.tooltip"))).build());
		if (controller.hasPendingChanges()) {
			Button discard = Button.builder(Component.translatable("rigtune.screen.discard"), b -> {
				status = controller.discardPending();
				rebuildWidgets();
			}).build();
			discard.setTooltip(Tooltip.create(Component.translatable("rigtune.screen.discard.tooltip")));
			buttons.add(discard);
		}
		buttons.add(Button.builder(Component.translatable("rigtune.screen.benchmark_menu"), b -> minecraft.gui.setScreen(new BenchmarkMenuScreen(this, controller))).build());
		buttons.add(Button.builder(Component.translatable("rigtune.screen.rescan"), b -> {
			status = null;
			controller.rescan();
			rebuildWidgets();
		}).build());
		Button copy = Button.builder(Component.translatable("rigtune.screen.copy_report"), b -> copyReport())
				.tooltip(Tooltip.create(Component.translatable("rigtune.screen.copy_report.tooltip"))).build();
		copy.active = shown != null;
		buttons.add(copy);
		buttons.add(Button.builder(Component.translatable("gui.done"), b -> onClose()).build());

		// As many buttons of at least MIN_BUTTON per row as fit, then the rows balanced.
		int perRow = Math.clamp((column + GAP) / (MIN_BUTTON + GAP), 1, buttons.size());
		int rows = (buttons.size() + perRow - 1) / perRow;
		perRow = (buttons.size() + rows - 1) / rows;
		int buttonWidth = Math.min(120, (column - GAP * (perRow - 1)) / perRow);
		int footerTop = height - MARGIN / 2 - rows * 20 - (rows - 1) * GAP;
		statusY = footerTop - LINE - 2;
		int listBottom = statusY - 4;

		list = new RecommendationList(listTop, Math.max(20, listBottom - listTop));
		populate(list);
		addRenderableWidget(list);

		for (int i = 0; i < buttons.size(); i++) {
			int row = i / perRow;
			int col = i % perRow;
			int inRow = Math.min(perRow, buttons.size() - row * perRow);
			int rowWidth = inRow * buttonWidth + (inRow - 1) * GAP;
			Button button = buttons.get(i);
			button.setRectangle(buttonWidth, 20, (width - rowWidth) / 2 + col * (buttonWidth + GAP), footerTop + row * (20 + GAP));
			addRenderableWidget(button);
		}
		updateApplyButton();
	}

	private void syncSelection(@Nullable Report report) {
		if (report == null) {
			return;
		}
		Set<String> ids = new HashSet<>();
		for (Recommendation r : report.recommendations()) {
			ids.add(r.id());
			if (!known.contains(r.id()) && r.appliable() && r.selectedByDefault()) {
				selected.add(r.id());
			}
		}
		known.addAll(ids);
		selected.retainAll(ids);
	}

	private Component tierBadge(Report report) {
		return Component.translatable("rigtune.header.tier",
				Component.literal(Integer.toString(report.tier().rawTier())).withStyle(ChatFormatting.BOLD),
				Component.translatable("rigtune.limit." + report.tier().limitingFactor())).withStyle(s -> s.withColor(0xFFFFD166));
	}

	private List<Component> header(Report report, @Nullable Component badge) {
		HardwareProfile hw = report.hardware();
		List<Component> lines = new ArrayList<>(cpuAndMemory(hw));
		String vram = hw.gpu().vramMb() > 0 ? gb(hw.gpu().vramMb()) : "?";
		lines.add(Component.translatable("rigtune.header.gpu",
				value(hw.gpu().renderer()),
				value(vram),
				value(backendName(hw.gpu().backend())),
				value(Integer.toString(report.tier().gpuTier()))).withStyle(s -> s.withColor(COLOR_LABEL)));
		String display = hw.display().width() > 0
				? hw.display().width() + "×" + hw.display().height() + (hw.display().refreshRate() > 0 ? " @ " + hw.display().refreshRate() + " Hz" : "")
				: "?";
		MutableComponent online = report.online()
				? Component.translatable("rigtune.header.online").withStyle(s -> s.withColor(COLOR_OK))
				: Component.translatable("rigtune.header.offline").withStyle(ChatFormatting.GOLD);
		MutableComponent last = Component.empty();
		if (badge != null) {
			last.append(badge).append(Component.literal(" · ").withStyle(s -> s.withColor(COLOR_LABEL)));
		}
		last.append(Component.translatable("rigtune.header.display_rules",
				value(display),
				value(Integer.toString(report.rulesRevision())),
				value(report.rulesSource()),
				online).withStyle(s -> s.withColor(COLOR_LABEL)));
		lines.add(last);
		if (!settings.networkEnabled) {
			lines.add(Component.translatable("rigtune.screen.header.network_off").withStyle(ChatFormatting.GOLD));
		} else if (!settings.modrinth) {
			lines.add(Component.translatable("rigtune.screen.header.modrinth_off").withStyle(ChatFormatting.GOLD));
		}
		return lines;
	}

	public List<Component> headerLines() {
		return List.copyOf(headerLines);
	}

	// v0.3 (WS-C): with a known launcher the memory gets its own line naming it ("Memory 6.0 GB of 32 GB, set in the
	// Modrinth App"); otherwise the CPU line is as before.
	private List<Component> cpuAndMemory(HardwareProfile hw) {
		Component cpu = value(cpuName(hw.cpu().name()));
		Component threads = value(Integer.toString(hw.cpu().logicalCores()));
		String launcherName = shownLauncher.nameKey();
		if (launcherName == null) {
			return List.of(Component.translatable("rigtune.header.cpu", cpu, threads, value(gb(hw.totalRamMb())), value(gb(hw.maxHeapMb())))
					.withStyle(s -> s.withColor(COLOR_LABEL)));
		}
		return List.of(
				Component.translatable("rigtune.launcher.header.cpu", cpu, threads).withStyle(s -> s.withColor(COLOR_LABEL)),
				Component.translatable("rigtune.launcher.header.memory", value(gb(hw.maxHeapMb())), value(gb(hw.totalRamMb())),
						Component.translatable(launcherName).withStyle(ChatFormatting.WHITE)).withStyle(s -> s.withColor(COLOR_LABEL)));
	}

	// v0.3 (WS-C): "In <launcher>: <steps>" under every ram-* advice, when the launcher is known.
	private @Nullable Component launcherLine(Recommendation recommendation) {
		String steps = LauncherAdvice.stepsKey(recommendation, shownLauncher);
		String launcherName = shownLauncher.nameKey();
		if (steps == null || launcherName == null) {
			return null;
		}
		Component line = Component.translatable("rigtune.launcher.advice", Component.translatable(launcherName), Component.translatable(steps));
		launcherAdvice.add(line);
		return line;
	}

	/** The launcher lines shown under the ram-* advice, in list order (for the game tests). */
	public List<Component> launcherLines() {
		return List.copyOf(launcherAdvice);
	}

	private void copyReport() {
		String text = controller.shareReport();
		if (text.isEmpty()) {
			status = Component.translatable("rigtune.share.unavailable");
			return;
		}
		minecraft.keyboardHandler.setClipboard(text);
		status = Component.translatable("rigtune.share.copied", text.length());
	}

	static String cpuName(String raw) {
		if (raw == null) {
			return "?";
		}
		String s = raw.replaceAll("(?i)\\((R|TM)\\)", "")
				.replaceAll("(?i)\\s*@\\s*[0-9.]+\\s*GHz", "")
				.replaceAll("(?i)\\s+\\d+-Core Processor", "")
				.replaceAll("(?i)\\s+(CPU|Processor)$", "")
				.replaceAll("\\s+", " ");
		return s.trim();
	}

	static String backendName(GraphicsBackend backend) {
		return switch (backend) {
			case OPENGL -> "OpenGL";
			case VULKAN -> "Vulkan";
			case UNKNOWN -> "?";
		};
	}

	private static Component value(String text) {
		return Component.literal(text == null ? "?" : text).withStyle(ChatFormatting.WHITE);
	}

	private static String gb(long mb) {
		if (mb <= 0) {
			return "?";
		}
		double gb = mb / 1024.0;
		return gb >= 10 ? Math.round(gb) + " GB" : String.format(Locale.ROOT, "%.1f GB", gb);
	}

	private void populate(RecommendationList target) {
		if (shown == null) {
			return;
		}
		Map<Category, List<Recommendation>> grouped = new EnumMap<>(Category.class);
		for (Recommendation r : shown.recommendations()) {
			grouped.computeIfAbsent(r.category(), c -> new ArrayList<>()).add(r);
		}
		for (Map.Entry<Category, List<Recommendation>> group : grouped.entrySet()) {
			target.addCategory(group.getKey(), group.getValue().size());
			for (Recommendation r : group.getValue()) {
				target.addRecommendation(r);
			}
		}
	}

	private void applySelected() {
		if (shown == null) {
			return;
		}
		List<Recommendation> chosen = shown.recommendations().stream().filter(r -> r.appliable() && selected.contains(r.id())).toList();
		if (chosen.isEmpty()) {
			return;
		}
		status = controller.apply(chosen);
		updateApplyButton();
	}

	private void updateApplyButton() {
		if (applyButton == null) {
			return;
		}
		long count = shown == null ? 0 : shown.recommendations().stream().filter(r -> r.appliable() && selected.contains(r.id())).count();
		applyButton.active = count > 0;
		applyButton.setMessage(count > 0 ? Component.translatable("rigtune.screen.apply.count", count) : Component.translatable("rigtune.screen.apply"));
	}

	public Set<String> selectedIds() {
		return Set.copyOf(selected);
	}

	@Override
	public void tick() {
		Component latest = controller.status();
		if (latest != null && latest != seenControllerStatus) {
			seenControllerStatus = latest;
			status = latest;
		}
		if (controller.report() != shown || !controller.launcher().equals(shownLauncher)) {
			rebuildWidgets();
		}
	}

	private String displayTitle(Recommendation r) {
		if (r.action() instanceof Action.SetSetting set && set.key().startsWith(SettingsBridge.VANILLA_PREFIX) && captions != null) {
			String caption = captions.get(set.key().substring(SettingsBridge.VANILLA_PREFIX.length()));
			if (caption != null && !caption.isBlank()) {
				return caption + ": " + prettyValue(set.currentValue()) + " → " + prettyValue(set.newValue());
			}
		}
		return r.title();
	}

	private static String prettyValue(String value) {
		if ("true".equals(value)) {
			return CommonComponents.OPTION_ON.getString();
		}
		if ("false".equals(value)) {
			return CommonComponents.OPTION_OFF.getString();
		}
		return value;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (minecraft.level != null) {
			graphics.fill(0, 0, width, headerBottom, 0x70000000);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, title.copy().withStyle(ChatFormatting.BOLD), left, 11, 0xFFFFFFFF, true);
		if (tierBadge != null) {
			graphics.text(font, tierBadge, badgeRight - font.width(tierBadge), 11, 0xFFFFFFFF, true);
		}
		int textWidth = right - left;
		if (shown == null) {
			graphics.text(font, Component.translatable("rigtune.screen.analysing"), left, headerTop, COLOR_LABEL, false);
		} else {
			int y = headerTop;
			for (Component line : headerLines) {
				drawClipped(graphics, line, left, y, textWidth, 0xFFFFFFFF, mouseX, mouseY);
				y += LINE;
			}
		}
		if (list != null && (shown == null || shown.recommendations().isEmpty())) {
			Component empty = Component.translatable(shown == null ? "rigtune.screen.analysing" : "rigtune.screen.nothing");
			graphics.centeredText(font, empty, width / 2, list.getY() + list.getHeight() / 2 - 4, COLOR_LABEL);
		}
		if (status != null) {
			drawClipped(graphics, status, left, statusY, textWidth, COLOR_OK, mouseX, mouseY);
		}
	}

	private void drawClipped(GuiGraphicsExtractor graphics, Component text, int x, int y, int maxWidth, int color, int mouseX, int mouseY) {
		if (font.width(text) <= maxWidth) {
			graphics.text(font, text, x, y, color, false);
			return;
		}
		graphics.text(font, ComponentRenderUtils.clipText(text, font, maxWidth), x, y, color, false);
		if (mouseX >= x && mouseX < x + maxWidth && mouseY >= y && mouseY < y + 9) {
			graphics.setTooltipForNextFrame(font, text, mouseX, mouseY);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	static int columnWidth(int screenWidth) {
		return Math.min(screenWidth - 32, 480);
	}

	static int impactColor(Impact impact) {
		return switch (impact) {
			case HIGH -> 0xFFFFB347;
			case MEDIUM -> 0xFFFFE08A;
			case LOW -> 0xFF9AA0A6;
		};
	}

	static int accentColor(Category category) {
		return switch (category) {
			case WARNING -> COLOR_WARNING;
			case ADVICE -> COLOR_ADVICE;
			default -> COLOR_NEUTRAL;
		};
	}

	final class RecommendationList extends ContainerObjectSelectionList<RecommendationList.Entry> {
		RecommendationList(int top, int listHeight) {
			super(RigTuneScreen.this.minecraft, RigTuneScreen.this.width, listHeight, top, 24);
		}

		@Override
		public int getRowWidth() {
			return columnWidth(this.width);
		}

		void addCategory(Category category, int count) {
			addEntry(new CategoryEntry(category, count), 16);
		}

		void addRecommendation(Recommendation recommendation) {
			RecommendationEntry entry = new RecommendationEntry(recommendation, getRowWidth() - 4);
			addEntry(entry, entry.preferredHeight());
		}

		abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		}

		final class CategoryEntry extends Entry {
			private final Component label;
			private final int color;

			CategoryEntry(Category category, int count) {
				this.label = Component.translatable("rigtune.category." + category.name().toLowerCase(Locale.ROOT))
						.append(Component.literal("  " + count).withStyle(ChatFormatting.GRAY))
						.withStyle(ChatFormatting.BOLD);
				this.color = category == Category.WARNING ? COLOR_WARNING : category == Category.ADVICE ? COLOR_ADVICE : 0xFFFFD166;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentBottom() - 11;
				graphics.text(font, label, getContentX(), y, color, true);
				int lineX = getContentX() + font.width(label) + 6;
				if (lineX < getContentRight()) {
					graphics.fill(lineX, y + 4, getContentRight(), y + 5, 0x40FFFFFF);
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

		final class RecommendationEntry extends Entry {
			private static final int BOX = 17;
			private final Recommendation recommendation;
			private final @Nullable Checkbox checkbox;
			private final Component impact;
			private final List<FormattedCharSequence> titleLines;
			private final List<FormattedCharSequence> reasonLines;
			private final List<FormattedCharSequence> launcherLines;
			private final int textIndent;

			RecommendationEntry(Recommendation recommendation, int width) {
				this.recommendation = recommendation;
				this.impact = Component.translatable("rigtune.impact." + recommendation.impact().name().toLowerCase(Locale.ROOT));
				this.textIndent = BOX + 5;
				int impactWidth = font.width(impact) + 8;
				int titleWidth = Math.max(40, width - textIndent - impactWidth);
				int reasonWidth = Math.max(40, width - textIndent);
				Component title = Component.literal(displayTitle(recommendation));
				List<FormattedCharSequence> split = font.split(title, titleWidth);
				this.titleLines = split.size() > 2 ? List.of(split.get(0), ComponentRenderUtils.clipText(title, font, titleWidth)) : split;
				this.reasonLines = recommendation.reason() == null || recommendation.reason().isBlank()
						? List.of() : font.split(Component.literal(recommendation.reason()), reasonWidth);
				Component launcherLine = launcherLine(recommendation);
				this.launcherLines = launcherLine == null ? List.of() : font.split(launcherLine, reasonWidth);
				if (recommendation.appliable()) {
					this.checkbox = Checkbox.builder(Component.empty(), font)
							.selected(selected.contains(recommendation.id()))
							.onValueChange((box, value) -> toggle(value))
							.build();
					this.checkbox.setWidth(BOX);
				} else {
					this.checkbox = null;
				}
			}

			private void toggle(boolean value) {
				if (value) {
					selected.add(recommendation.id());
				} else {
					selected.remove(recommendation.id());
				}
				updateApplyButton();
			}

			int preferredHeight() {
				int titleHeight = Math.max(BOX, titleLines.size() * 9 + 4);
				return 4 + titleHeight + (reasonLines.size() + launcherLines.size()) * 9 + 5;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = getContentX();
				int y = getContentY();
				int right = getContentRight();
				if (hovered) {
					graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight() - 1, 0x18FFFFFF);
				}
				boolean informational = checkbox == null;
				int accent = accentColor(recommendation.category());
				if (informational) {
					graphics.fill(x + 6, y + 1, x + 9, getY() + getHeight() - 4, accent);
				} else {
					checkbox.setPosition(x, y);
					checkbox.extractRenderState(graphics, mouseX, mouseY, partialTick);
				}
				int titleColor = informational ? (recommendation.category() == Category.WARNING || recommendation.category() == Category.ADVICE ? accent : 0xFFDDDDDD) : 0xFFFFFFFF;
				int textX = x + textIndent;
				int titleY = y + (titleLines.size() == 1 ? 4 : 0);
				for (FormattedCharSequence line : titleLines) {
					graphics.text(font, line, textX, titleY, titleColor, true);
					titleY += 9;
				}
				graphics.text(font, impact, right - font.width(impact), y + 4, impactColor(recommendation.impact()), true);
				int reasonY = y + Math.max(BOX, titleLines.size() * 9 + 4) + 1;
				for (FormattedCharSequence line : reasonLines) {
					graphics.text(font, line, textX, reasonY, informational && recommendation.category() == Category.WARNING ? 0xFFE8B0A8 : COLOR_REASON, false);
					reasonY += 9;
				}
				for (FormattedCharSequence line : launcherLines) {
					graphics.text(font, line, textX, reasonY, COLOR_LAUNCHER, false);
					reasonY += 9;
				}
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (super.mouseClicked(event, doubleClick)) {
					return true;
				}
				if (checkbox != null && event.button() == 0) {
					checkbox.onPress(event);
					return true;
				}
				return false;
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return checkbox == null ? List.of() : List.of(checkbox);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return checkbox == null ? List.of() : List.of(checkbox);
			}
		}
	}
}
