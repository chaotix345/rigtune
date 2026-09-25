package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

// Confirms an undo (docs/v0.2/SPEC.md item 3): lists exactly what will be undone now, after a restart, what staged
// changes are cancelled, and what is skipped and why. Confirm carries out that plan (re-checked by the controller).
// The plan is worked out off the render thread (it may read mod jars).
public class UndoScreen extends Screen {
	private static final int LINE = 9;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_REASON = 0xFFB8B8B8;
	private static final int COLOR_NOW = 0xFF7FE07F;
	private static final int COLOR_RESTART = 0xFFFFD166;
	private static final int COLOR_DISCARD = 0xFF7EC8FF;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private record Section(String key, int color, Predicate<UndoPlan.Item> matches) {
	}

	private static final List<Section> SECTIONS = List.of(
			new Section("rigtune.undo.section.now", COLOR_NOW, i -> i.action() == UndoPlan.Action.REVERT && !i.needsRestart()),
			new Section("rigtune.undo.section.restart", COLOR_RESTART, i -> i.action() == UndoPlan.Action.REVERT && i.needsRestart()),
			new Section("rigtune.undo.section.discard", COLOR_DISCARD, i -> i.action() == UndoPlan.Action.DISCARD_STAGED),
			new Section("rigtune.undo.section.skip", COLOR_LABEL, i -> i.action() == UndoPlan.Action.SKIP));

	private final @Nullable Screen parent;
	protected final RigTuneController controller;
	private final boolean all;
	private final @Nullable String entryId;
	private @Nullable UndoPlan plan;
	private boolean planned;
	private boolean loading;
	private boolean done;
	private @Nullable Component status;
	private @Nullable UndoList list;
	private int statusY;

	public UndoScreen(@Nullable Screen parent, RigTuneController controller, boolean all) {
		this(parent, controller, all, null);
	}

	// "Undo this" on one history entry (docs/v0.3/SPEC.md item 6).
	public UndoScreen(@Nullable Screen parent, RigTuneController controller, String entryId) {
		this(parent, controller, false, entryId);
	}

	private UndoScreen(@Nullable Screen parent, RigTuneController controller, boolean all, @Nullable String entryId) {
		super(Component.translatable("rigtune.undo.title"));
		this.parent = parent;
		this.controller = controller;
		this.all = all;
		this.entryId = entryId;
	}

	// The history entry this screen undoes, or null for Undo last / Undo everything.
	public @Nullable String entryId() {
		return entryId;
	}

	// Null until the plan has been worked out.
	public @Nullable UndoPlan plan() {
		return plan;
	}

	@Override
	protected void init() {
		if (!planned) {
			planned = true;
			loading = true;
			CompletableFuture.supplyAsync(() -> entryId != null ? controller.undoPlanFor(entryId) : controller.undoPlan(all), Probes.EXECUTOR).whenComplete((result, error) -> minecraft.execute(() -> {
				plan = error != null ? UndoPlan.unavailable(all, "rigtune.undo.error")
						: result != null ? result : UndoPlan.unavailable(all, "rigtune.undo.unavailable");
				loading = false;
				rebuildWidgets();
			}));
		}
		int column = Math.min(width - 32, 480);
		int buttonWidth = Math.min(150, (column - 4) / 2);
		int buttonsY = height - 26;
		statusY = buttonsY - 13;
		int listTop = 34;
		list = new UndoList(listTop, Math.max(20, statusY - 3 - listTop), column);
		populate(list);
		addRenderableWidget(list);

		long count = plan == null ? 0 : plan.items().stream().filter(i -> i.action() != UndoPlan.Action.SKIP).count();
		Button confirm = Button.builder(Component.translatable("rigtune.undo.confirm", count), b -> confirm())
				.bounds(width / 2 - buttonWidth - 2, buttonsY, buttonWidth, 20).build();
		confirm.active = !done && plan != null && !plan.isEmpty();
		addRenderableWidget(confirm);
		addRenderableWidget(Button.builder(Component.translatable(done ? "gui.done" : "gui.cancel"), b -> onClose())
				.bounds(width / 2 + 2, buttonsY, buttonWidth, 20).build());
	}

	private void populate(UndoList target) {
		if (plan == null) {
			return;
		}
		for (Section section : SECTIONS) {
			List<UndoPlan.Item> items = plan.items().stream().filter(section.matches()).toList();
			if (items.isEmpty()) {
				continue;
			}
			target.addSection(Component.translatable(section.key()).append(Component.literal("  " + items.size()).withStyle(ChatFormatting.GRAY)),
					section.color());
			for (UndoPlan.Item item : items) {
				target.addItem(item);
			}
		}
	}

	private void confirm() {
		if (plan == null || done) {
			return;
		}
		status = controller.undo(plan);
		done = true;
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(font, clip(subtitle()), width / 2, 20, COLOR_LABEL);
		Component empty = loading ? Component.translatable("rigtune.undo.loading")
				: plan == null ? null
				: plan.problem() != null ? Component.translatable(plan.problem())
				: plan.items().isEmpty() ? Component.translatable("rigtune.undo.nothing") : null;
		if (empty != null && list != null) {
			graphics.centeredText(font, clip(empty), width / 2, list.getY() + list.getHeight() / 2 - 4, COLOR_LABEL);
		}
		Component line = status != null ? status
				: plan != null && !plan.items().isEmpty() && plan.isEmpty() ? Component.translatable("rigtune.undo.nothing_possible") : null;
		if (line != null) {
			graphics.centeredText(font, clip(line), width / 2, statusY, status == null ? COLOR_LABEL : succeeded(status) ? COLOR_NOW : COLOR_FAIL);
		}
	}

	private Component subtitle() {
		if (all) {
			return Component.translatable("rigtune.undo.subtitle.all");
		}
		if (entryId != null) {
			return plan == null || plan.at() == null ? Component.translatable("rigtune.history.undo.subtitle")
					: Component.translatable("rigtune.history.undo.subtitle_at", when(plan.at(), ZoneId.systemDefault()));
		}
		return plan == null || plan.at() == null ? Component.translatable("rigtune.undo.subtitle.last")
				: Component.translatable("rigtune.undo.subtitle.last_at", when(plan.at(), ZoneId.systemDefault()));
	}

	// When an apply was, in local time; the recorded text if it can't be read.
	static String when(String at, ZoneId zone) {
		try {
			return WHEN.withZone(zone).format(Instant.parse(at));
		} catch (DateTimeException e) {
			return at;
		}
	}

	private static boolean succeeded(Component status) {
		return status.getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.undo.status.done");
	}

	private FormattedCharSequence clip(Component text) {
		return font.width(text) <= width - 16 ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, width - 16);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	final class UndoList extends ContainerObjectSelectionList<UndoList.Entry> {
		private final int rowWidth;

		UndoList(int top, int listHeight, int rowWidth) {
			super(UndoScreen.this.minecraft, UndoScreen.this.width, listHeight, top, 20);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void addSection(Component label, int color) {
			addEntry(new SectionEntry(label, color), 16);
		}

		void addItem(UndoPlan.Item item) {
			ItemEntry entry = new ItemEntry(item, getRowWidth() - 12);
			addEntry(entry, entry.preferredHeight());
		}

		abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}

		final class SectionEntry extends Entry {
			private final Component label;
			private final int color;

			SectionEntry(Component label, int color) {
				this.label = label.copy().withStyle(ChatFormatting.BOLD);
				this.color = color;
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
		}

		final class ItemEntry extends Entry {
			private final List<FormattedCharSequence> lines;
			private final List<FormattedCharSequence> reason;
			private final boolean skipped;

			ItemEntry(UndoPlan.Item item, int width) {
				this.lines = font.split(Component.literal(item.description()), Math.max(40, width));
				this.reason = item.reason() == null || item.reason().isBlank() ? List.of()
						: font.split(Component.literal(item.reason()), Math.max(40, width - 8));
				this.skipped = item.action() == UndoPlan.Action.SKIP;
			}

			int preferredHeight() {
				return 3 + (lines.size() + reason.size()) * LINE + 3;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = getContentX() + 6;
				int y = getContentY() + 1;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, x, y, skipped ? 0xFFC8C8C8 : 0xFFFFFFFF, false);
					y += LINE;
				}
				for (FormattedCharSequence line : reason) {
					graphics.text(font, line, x + 8, y, COLOR_REASON, false);
					y += LINE;
				}
			}
		}
	}
}
