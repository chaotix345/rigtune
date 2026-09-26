package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierBasis;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticeBoard;
import io.github.chaotix345.rigtune.core.report.IssueLink;
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
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
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
import java.util.Objects;
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
	private static final int COLOR_NOTICE = 0xFFFFE08A;
	private static final int NOTICE_ROW = 16;
	private static final int NOTICE_BUTTON = 14;
	// Narrower than this (scaled px), the notice line is the message plus one "…" button to NoticeScreen (review X-M2).
	private static final int NOTICE_INLINE_WIDTH = 400;
	private static final int MIN_NOTICE_MESSAGE = 80;

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
	// docs/v0.4/SPEC.md 2j: where the tier badge is drawn (title row, or the start of header line badgeLine) and its one tooltip.
	private int badgeLine = -1;
	private @Nullable ScreenRectangle badgeArea;
	private @Nullable Component badgeComponent;
	private List<Component> tierTooltipLines = List.of();
	private int headerBottom;
	private int statusY;
	private @Nullable RecommendationList list;
	private @Nullable Button applyButton;
	private @Nullable Button previewButton;
	private @Nullable Map<String, String> captions;
	private @Nullable Component seenControllerStatus;
	private LauncherInfo shownLauncher = LauncherInfo.UNKNOWN;
	// v0.4 (docs/v0.4/SPEC.md C3): the one notice line, read on init/rebuild only.
	private NoticeBoard.Selection notices = NoticeBoard.select(List.of(), Set.of());
	private int noticeIndex;
	private @Nullable Notice shownNotice;
	private int noticeY;
	private int noticeTextRight;

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
		badgeLine = -1;
		headerLines = shown == null ? List.of() : header(shown, badgeInTitleRow ? null : tierBadge);
		headerTop = 30;
		badgeArea = tierBadge == null ? null : badgeInTitleRow ? new ScreenRectangle(badgeRight - font.width(tierBadge), 11, font.width(tierBadge), LINE)
				: badgeLine < 0 ? null : new ScreenRectangle(left, headerTop + badgeLine * LINE, Math.min(font.width(tierBadge), right - left), LINE);
		tierTooltipLines = shown == null ? List.of() : tierTooltip(shown);
		badgeComponent = tierBadge;
		if (!badgeInTitleRow) {
			tierBadge = null;
		}
		headerBottom = headerTop + Math.max(1, headerLines.size()) * LINE + 2;
		headerBottom += noticeLine(headerBottom);
		int listTop = headerBottom + 4;

		List<Button> buttons = new ArrayList<>();
		applyButton = Button.builder(Component.translatable("rigtune.screen.apply"), b -> applySelected()).build();
		buttons.add(applyButton);
		buttons.add(previewButton());
		// v0.3 (review X-M2): Undo last and Undo all live in the History screen.
		buttons.add(Button.builder(Component.translatable("rigtune.history.open"), b -> minecraft.gui.setScreen(new HistoryScreen(this, controller)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.history.open.tooltip"))).build());
		// v0.4 (C3, X3, plan review X-M2): the one hub button, in place of Benchmark… (now the hub's first entry); features
		// live behind it, never in this footer.
		buttons.add(toolsButton());
		if (controller.hasPendingChanges()) {
			Button discard = Button.builder(Component.translatable("rigtune.screen.discard"), b -> {
				status = controller.discardPending();
				rebuildWidgets();
			}).build();
			discard.setTooltip(Tooltip.create(Component.translatable("rigtune.screen.discard.tooltip")));
			buttons.add(discard);
		}
		buttons.add(Button.builder(Component.translatable("rigtune.screen.rescan"), b -> {
			status = null;
			controller.rescan();
			rebuildWidgets();
		}).build());
		Button copy = Button.builder(Component.translatable("rigtune.screen.copy_report"), b -> copyReport())
				.tooltip(Tooltip.create(Component.translatable("rigtune.screen.copy_report.tooltip"))).build();
		copy.active = shown != null;
		buttons.add(copy);
		buttons.add(reportButton());
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

	// docs/v0.4/SPEC.md 2j (external review §1): "Estimated tier N/5 · lowest estimated component: CPU"; a tie lists every
	// tied component. An estimate, never a bottleneck.
	private Component tierBadge(Report report) {
		return Component.translatable("rigtune.header.tier_estimate",
				Component.literal(Integer.toString(report.tier().rawTier())).withStyle(ChatFormatting.BOLD),
				components(TierBasis.lowest(report.tier()))).withStyle(s -> s.withColor(0xFFFFD166));
	}

	// "GPU", "GPU, CPU" or "GPU, CPU, memory".
	private static Component components(List<String> factors) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < factors.size(); i++) {
			if (i > 0) {
				out.append(Component.literal(", "));
			}
			out.append(Component.translatable("rigtune.limit." + factors.get(i)));
		}
		return out;
	}

	// The tier badge's one tooltip: what each component's tier rests on ("GPU tier 4 (table match)", "CPU tier 5 (fallback
	// estimate from 16 threads)", "Memory tier 5 (6.0 GB heap)"). A mutable list, so other lines (WS-B's last benchmark)
	// are appended to it and the badge still has exactly one tooltip. Empty for a report without a basis.
	static List<Component> tierTooltip(Report report) {
		List<Component> lines = new ArrayList<>();
		TierBasis basis = report.tierBasis();
		if (basis == null) {
			return lines;
		}
		lines.add(Component.translatable(basis.gpu().basis() == TierBasis.Basis.TABLE_MATCH ? "rigtune.header.tier_basis.gpu.table"
				: "rigtune.header.tier_basis.gpu.fallback", basis.gpu().tier()));
		TierBasis.Cpu cpu = basis.cpu();
		if (cpu.basis() == TierBasis.Basis.TABLE_MATCH) {
			lines.add(Component.translatable("rigtune.header.tier_basis.cpu.table", cpu.tier()));
		} else if (cpu.logicalCores() <= 0) {
			lines.add(Component.translatable("rigtune.header.tier_basis.cpu.fallback_unknown", cpu.tier()));
		} else if (cpu.maxFreqMhz() > 0 && cpu.maxFreqMhz() < 2500) {
			lines.add(Component.translatable("rigtune.header.tier_basis.cpu.fallback_clock", cpu.tier(), cpu.logicalCores(),
					String.format(Locale.ROOT, "%.1f", cpu.maxFreqMhz() / 1000.0)));
		} else {
			lines.add(Component.translatable("rigtune.header.tier_basis.cpu.fallback", cpu.tier(), cpu.logicalCores()));
		}
		lines.add(Component.translatable("rigtune.header.tier_basis.memory", basis.memory().tier(), gb(basis.memory().heapMb())));
		return lines;
	}

	/** v0.4 (docs/v0.4/SPEC.md 2j): the tier badge's tooltip lines and where the badge is (for the game tests). */
	public List<Component> tierTooltip() {
		return List.copyOf(tierTooltipLines);
	}

	public @Nullable ScreenRectangle tierBadgeArea() {
		return badgeArea;
	}

	public @Nullable String tierBadgeText() {
		return shown == null ? null : tierBadge(shown).getString();
	}

	private List<Component> header(Report report, @Nullable Component badge) {
		HardwareProfile hw = report.hardware();
		List<Component> lines = new ArrayList<>(LauncherLines.cpuAndMemory(shownLauncher, value(cpuName(hw.cpu().name())),
				value(Integer.toString(hw.cpu().logicalCores())), value(gb(hw.totalRamMb())), value(gb(hw.maxHeapMb())), COLOR_LABEL));
		lines.add(Component.translatable("rigtune.header.gpu",
				value(hw.gpu().renderer()),
				value(gb(hw.gpu().vramMb())),
				value(backendName(hw.gpu().backend())),
				value(Integer.toString(report.tier().gpuTier()))).withStyle(s -> s.withColor(COLOR_LABEL)));
		MutableComponent online = report.online()
				? Component.translatable("rigtune.header.online").withStyle(s -> s.withColor(COLOR_OK))
				: Component.translatable("rigtune.header.offline").withStyle(ChatFormatting.GOLD);
		MutableComponent last = Component.empty();
		if (badge != null) {
			badgeLine = lines.size();
			last.append(badge).append(Component.literal(" · ").withStyle(s -> s.withColor(COLOR_LABEL)));
		}
		last.append(Component.translatable("rigtune.header.display_rules",
				value(display(hw.display())),
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

	/** v0.4 (docs/v0.4/SPEC.md 2b): a recommendation's title as the list draws it, or null (for the game tests). */
	public @Nullable String titleOf(String recommendationId) {
		return shown == null ? null : shown.recommendations().stream().filter(r -> r.id().equals(recommendationId)).findFirst()
				.map(r -> displayTitle(r).getString()).orElse(null);
	}

	/**
	 * v0.4 (docs/v0.4/SPEC.md 2m): focuses a recommendation's checkbox as keyboard navigation would and returns the list,
	 * whose narration the game test collects; null when there's no such row.
	 */
	public @Nullable ContainerObjectSelectionList<?> focusRecommendation(String recommendationId) {
		if (list == null) {
			return null;
		}
		for (RecommendationList.Entry entry : list.children()) {
			if (entry instanceof RecommendationList.RecommendationEntry row && row.recommendation.id().equals(recommendationId) && row.checkbox != null) {
				setFocused(list);
				list.setFocused(row);
				row.setFocused(row.checkbox);
				return list;
			}
		}
		return null;
	}

	/** v0.3 (WS-C): the launcher lines shown under the ram-* advice (for the game tests). */
	public List<Component> launcherLines() {
		return shown == null ? List.of()
				: shown.recommendations().stream().map(r -> LauncherLines.adviceLine(r, shownLauncher)).filter(Objects::nonNull).toList();
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

	private Button reportButton() {
		Button button = Button.builder(Component.translatable("rigtune.report.button"), b -> reportProblem())
				.tooltip(Tooltip.create(Component.translatable("rigtune.report.button.tooltip"))).build();
		button.active = shown != null;
		return button;
	}

	// The full report goes to the clipboard; the link carries the title and a short report (IssueLink). Vanilla's
	// confirm screen shows the link, and nothing is opened unless the player chooses Open in Browser.
	private void reportProblem() {
		String text = controller.shareReport();
		if (text.isEmpty()) {
			status = Component.translatable("rigtune.share.unavailable");
			return;
		}
		minecraft.keyboardHandler.setClipboard(text);
		status = Component.translatable("rigtune.report.copied", text.length());
		ConfirmLinkScreen.confirmLinkNow(this, IssueLink.uri(controller.reportVersions(), text));
	}

	// v0.4 (docs/v0.4/SPEC.md C3): Tools… and the notice line. Features fill NoticeSources and ToolsScreen entries, not
	// this screen.

	private Button toolsButton() {
		return Button.builder(Component.translatable("rigtune.tools.open"), b -> minecraft.gui.setScreen(new ToolsScreen(this, controller)))
				.tooltip(Tooltip.create(Component.translatable("rigtune.tools.open.tooltip"))).build();
	}

	// One row under the header lines: the notice's message (its detail as the tooltip), then at most 2 action buttons, a
	// dismiss button when dismissible, and "+N more", which cycles. On a narrow screen: the message and one "…" button
	// that opens NoticeScreen (the actions, dismiss and the other notices), also used when the inline buttons would leave
	// the message less than MIN_NOTICE_MESSAGE px. Returns the height used (0 without a notice).
	private int noticeLine(int y) {
		notices = NoticeBoard.select(controller.notices(), Set.of());
		noticeIndex = notices.visible().isEmpty() ? 0 : noticeIndex % notices.visible().size();
		shownNotice = notices.at(noticeIndex);
		if (shownNotice == null) {
			noticeIndex = 0;
			return 0;
		}
		Notice notice = shownNotice;
		noticeY = y;
		List<Button> buttons = new ArrayList<>();
		if (width >= NOTICE_INLINE_WIDTH) {
			inlineNoticeButtons(notice, buttons);
			int total = buttons.stream().mapToInt(b -> b.getWidth() + GAP).sum();
			if (right - total >= left + MIN_NOTICE_MESSAGE) {
				return placeNoticeButtons(buttons, y);
			}
			buttons.clear();
		}
		Button open = noticeButton(Component.translatable("rigtune.notice.open"), b -> minecraft.gui.setScreen(new NoticeScreen(this, controller)));
		open.setTooltip(Tooltip.create(Component.translatable("rigtune.notice.open.tooltip")));
		buttons.add(open);
		return placeNoticeButtons(buttons, y);
	}

	private void inlineNoticeButtons(Notice notice, List<Button> buttons) {
		for (NoticeAction action : notice.actions().subList(0, Math.min(2, notice.actions().size()))) {
			buttons.add(noticeButton(Texts.component(action.label()), b -> {
				controller.noticeAction(notice.key(), action.id());
				if (minecraft.gui.screen() == this) {
					rebuildWidgets();
				}
			}));
		}
		if (notice.dismissible()) {
			Button dismiss = noticeButton(Component.translatable("rigtune.notice.dismiss"), b -> {
				controller.dismissNotice(notice.key());
				rebuildWidgets();
			});
			dismiss.setTooltip(Tooltip.create(Component.translatable("rigtune.notice.dismiss.tooltip")));
			buttons.add(dismiss);
		}
		if (notices.others() > 0) {
			Button more = noticeButton(Component.translatable("rigtune.notice.more", notices.others()), b -> {
				// Kept in range, so a dismissal shows the notice that moves into the dismissed one's place.
				noticeIndex = (noticeIndex + 1) % notices.visible().size();
				rebuildWidgets();
			});
			more.setTooltip(Tooltip.create(Component.translatable("rigtune.notice.more.tooltip")));
			buttons.add(more);
		}
	}

	private int placeNoticeButtons(List<Button> buttons, int y) {
		int x = right;
		for (Button button : buttons.reversed()) {
			x -= button.getWidth();
			button.setPosition(x, y + (NOTICE_ROW - NOTICE_BUTTON) / 2);
			addRenderableWidget(button);
			x -= GAP;
		}
		noticeTextRight = x;
		return NOTICE_ROW;
	}

	private Button noticeButton(Component label, Button.OnPress onPress) {
		return Button.builder(label, onPress).size(font.width(label) + 10, NOTICE_BUTTON).build();
	}

	private void extractNotice(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (shownNotice == null) {
			return;
		}
		Component message = Texts.component(shownNotice.message());
		int maxWidth = Math.max(0, noticeTextRight - left);
		int y = noticeY + (NOTICE_ROW - 8) / 2;
		graphics.text(font, font.width(message) <= maxWidth ? message.getVisualOrderText() : ComponentRenderUtils.clipText(message, font, maxWidth),
				left, y, COLOR_NOTICE, false);
		if (mouseX >= left && mouseX < left + maxWidth && mouseY >= y && mouseY < y + 9) {
			// Wrapped: a notice's detail is often longer than the screen is wide (v0.4, WS-W).
			graphics.setTooltipForNextFrame(font, font.split(shownNotice.detail() == null ? message
					: message.copy().append(CommonComponents.NEW_LINE).append(Texts.component(shownNotice.detail())), Math.min(250, width - 16)), mouseX, mouseY);
		}
	}

	/** v0.4 (C3): the notice the line shows, or null (for the game tests). */
	public @Nullable Notice shownNotice() {
		return shownNotice;
	}

	/** v0.4 (C3): how many other notices "+N more" cycles through (for the game tests). */
	public int otherNotices() {
		return shownNotice == null ? 0 : notices.others();
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

	private static Component value(Component text) {
		return text.copy().withStyle(ChatFormatting.WHITE);
	}

	// "2.0 GB", "16 GB"; "?" when unknown.
	static Component gb(long mb) {
		if (mb <= 0) {
			return Component.literal("?");
		}
		double gb = mb / 1024.0;
		return Component.translatable("rigtune.unit.gb", gb >= 10 ? Long.toString(Math.round(gb)) : String.format(Locale.ROOT, "%.1f", gb));
	}

	// "2560×1440 @ 180 Hz", "2560×1440"; "?" when unknown.
	static Component display(DisplayInfo display) {
		if (display.width() <= 0) {
			return Component.literal("?");
		}
		return display.refreshRate() > 0
				? Component.translatable("rigtune.header.display_size_hz", display.width(), display.height(), display.refreshRate())
				: Component.translatable("rigtune.header.display_size", display.width(), display.height());
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
		List<Recommendation> chosen = ticked();
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
		if (previewButton != null) {
			previewButton.active = count > 0;
		}
		applyButton.setMessage(count > 0 ? Component.translatable("rigtune.screen.apply.count", count) : Component.translatable("rigtune.screen.apply"));
	}

	// v0.3 (WS-P): what Apply would do for exactly the items Apply takes (docs/v0.3/SPEC.md item 13).
	private Button previewButton() {
		previewButton = Button.builder(Component.translatable("rigtune.preview.button"), b -> minecraft.gui.setScreen(new PreviewScreen(this, controller, ticked())))
				.tooltip(Tooltip.create(Component.translatable("rigtune.preview.button.tooltip"))).build();
		return previewButton;
	}

	private List<Recommendation> ticked() {
		return shown == null ? List.of() : shown.recommendations().stream().filter(r -> r.appliable() && selected.contains(r.id())).toList();
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

	private Component displayTitle(Recommendation r) {
		if (r.action() instanceof Action.SetSetting set && set.key().startsWith(SettingsBridge.VANILLA_PREFIX) && captions != null) {
			String caption = captions.get(set.key().substring(SettingsBridge.VANILLA_PREFIX.length()));
			if (caption != null && !caption.isBlank()) {
				return Component.translatable("rigtune.rec.setting.title", caption, prettyValue(set.currentValue()), prettyValue(set.newValue()));
			}
		}
		return Texts.component(r.titleText());
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
			// Over the tier badge its own tooltip shows, not the clipped line's full text (the first tooltip set wins).
			boolean overBadge = badgeArea != null && badgeArea.containsPoint(mouseX, mouseY);
			int y = headerTop;
			for (Component line : headerLines) {
				drawClipped(graphics, line, left, y, textWidth, 0xFFFFFFFF, overBadge ? -1 : mouseX, overBadge ? -1 : mouseY);
				y += LINE;
			}
			// The badge's one tooltip (2j + WS-B): the tier basis lines, then WS-B's last-benchmark line, wherever it's drawn.
			if (badgeArea != null) {
				BenchmarkTrendLines.badgeTooltip(graphics, font, controller, badgeComponent, badgeArea.left(), badgeArea.top(), mouseX, mouseY, tierTooltipLines);
			}
		}
		extractNotice(graphics, mouseX, mouseY);
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
				Component title = displayTitle(recommendation);
				List<FormattedCharSequence> split = font.split(title, titleWidth);
				this.titleLines = split.size() > 2 ? List.of(split.get(0), ComponentRenderUtils.clipText(title, font, titleWidth)) : split;
				this.reasonLines = recommendation.reason() == null || recommendation.reason().isBlank()
						? List.of() : font.split(Texts.component(recommendation.reasonText()), reasonWidth);
				Component launcherLine = LauncherLines.adviceLine(recommendation, shownLauncher);
				this.launcherLines = launcherLine == null ? List.of() : font.split(launcherLine, reasonWidth);
				if (recommendation.appliable()) {
					this.checkbox = Checkbox.builder(Component.empty(), font)
							.selected(selected.contains(recommendation.id()))
							.onValueChange((box, value) -> toggle(value))
							.build();
					this.checkbox.setWidth(BOX);
					// docs/v0.4/SPEC.md 2m: the narrator names the recommendation. Vanilla draws the label it was built with
					// (empty: the row draws its own title) and narrates getMessage(), so the row looks the same.
					this.checkbox.setMessage(Component.translatable("rigtune.screen.recommendation", title, impact));
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
