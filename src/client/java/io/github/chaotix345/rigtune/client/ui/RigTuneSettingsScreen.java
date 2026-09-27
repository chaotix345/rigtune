package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

// The v0.2 settings (docs/v0.2/SPEC.md item 8). Every change is saved at once; the network switches also make the
// controller reload and rescan, so the report reflects them.
// v0.5 (docs/v0.5/PLAN.md WS-L1 milestone 1, SPEC X6 and X12): the switches are the rows of a scrolling RowList, since more
// rows don't fit at 640x480 (GUI scale 2); each row's switch is its Tab stop, and the note is the last row.
public class RigTuneSettingsScreen extends Screen {
	private static final int ROW = 20;
	private static final int GAP = 4;
	private static final int TOP = 32;
	private static final int LINE = 9;
	// A list row's content starts this far inside the row (vanilla's Entry.CONTENT_PADDING).
	private static final int PAD = 2;
	private static final int MAX_WIDTH = 310;
	private static final int COLOR_NOTE = 0xFFA8A8A8;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final ClientSettings settings;
	private final Path configDir;
	private @Nullable CycleButton<Boolean> remoteRules;
	private @Nullable CycleButton<Boolean> modrinth;
	private @Nullable SettingsList list;

	public RigTuneSettingsScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.settings.title"));
		this.parent = parent;
		this.controller = controller;
		this.configDir = FabricLoader.getInstance().getConfigDir();
		this.settings = ClientSettings.shared(configDir);
	}

	@Override
	protected void init() {
		int column = Math.min(width - 32, MAX_WIDTH);
		int footer = height - 28;
		SettingsList rows = new SettingsList(TOP, Math.max(ROW + GAP, footer - GAP - TOP), column);
		list = rows;

		rows.add(CycleButton.onOffBuilder(settings.networkEnabled)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.network.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.network"), (b, v) -> {
					settings.networkEnabled = v;
					networkChanged();
				}));
		remoteRules = rows.add(CycleButton.onOffBuilder(settings.remoteRules)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.remote_rules.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.remote_rules"), (b, v) -> {
					settings.remoteRules = v;
					networkChanged();
				}));
		modrinth = rows.add(CycleButton.onOffBuilder(settings.modrinth)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.modrinth.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.modrinth"), (b, v) -> {
					settings.modrinth = v;
					networkChanged();
				}));
		rows.add(CycleButton.onOffBuilder(settings.startupToast)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.startup_toast.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.startup_toast"), (b, v) -> {
					settings.startupToast = v;
					save();
				}));
		rows.add(CycleButton.builder((Goal g) -> Component.translatable("rigtune.goal." + g.name().toLowerCase(Locale.ROOT)), controller.goal())
				.withValues(Goal.values())
				.withTooltip(g -> Tooltip.create(Component.translatable("rigtune.goal." + g.name().toLowerCase(Locale.ROOT) + ".tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.goal"), (b, g) -> controller.setGoal(g)));
		// The same labels and availability as the benchmark menu, which reads this setting.
		BenchmarkRequest.Scene[] scenes = BenchmarkWorld.supported() ? BenchmarkRequest.Scene.values() : new BenchmarkRequest.Scene[]{BenchmarkRequest.Scene.CURRENT};
		BenchmarkRequest.Scene scene = BenchmarkWorld.supported() ? settings.benchmarkSceneOrDefault() : BenchmarkRequest.Scene.CURRENT;
		rows.add(CycleButton.builder((BenchmarkRequest.Scene s) -> Component.translatable("rigtune.benchmark.scene." + s.name().toLowerCase(Locale.ROOT)), scene)
				.withValues(scenes)
				.withTooltip(s -> Tooltip.create(Component.translatable("rigtune.settings.scene.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.scene"), (b, s) -> {
					settings.benchmarkScene = s.name();
					save();
				}));
		// ---- WS-P (docs/v0.5/SPEC.md 2P, PF-2): the battery-offer row, from its own method, is added on the line below.
		batteryOfferRow(rows, column);
		stutterMonitorRow(rows, column);
		// ---- WS-L1 (docs/v0.5/SPEC.md 4e): the mod-files row.
		modFilesRow(rows, column);
		rows.note(Component.translatable("rigtune.settings.note"));
		addRenderableWidget(rows);
		updateActive();

		if (parent instanceof RigTuneScreen) {
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
					.bounds((width - Math.min(column, 150)) / 2, footer, Math.min(column, 150), ROW).build());
		} else {
			int half = Math.min(150, (column - GAP) / 2);
			int left = (width - 2 * half - GAP) / 2;
			addRenderableWidget(Button.builder(Component.translatable("rigtune.settings.open_rigtune"),
					b -> minecraft.gui.setScreen(new RigTuneScreen(parent, controller))).bounds(left, footer, half, ROW).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
					.bounds(left + half + GAP, footer, half, ROW).build());
		}
	}

	// v0.4 (docs/v0.4/SPEC.md 5): the opt-in Stutter Doctor session monitor (also on StutterScreen).
	private void stutterMonitorRow(SettingsList rows, int column) {
		rows.add(CycleButton.onOffBuilder(settings.stutterMonitor)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.stutter.monitor.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.stutter.monitor"), (b, v) -> {
					settings.stutterMonitor = v;
					save();
					controller.setStutterMonitor(v);
				}));
	}

	// v0.5 (docs/v0.5/SPEC.md 4e): who changes this instance's mod files. Shown where a launcher keeps its own record of
	// them (or before that's known), and wherever the opt-in is on. Choosing saves settings.json (SettingsSaver) and
	// rescans, so the report follows the policy.
	private void modFilesRow(SettingsList rows, int column) {
		if (!showModFilesRow(controller.modFiles(), settings.modFilesByRigTune)) {
			return;
		}
		Component launcher = Texts.component(LauncherModText.nameOrYours(controller.launcher()));
		rows.add(CycleButton.builder((Boolean rigtune) -> rigtune ? Component.translatable("rigtune.settings.mod_files.rigtune")
						: Component.translatable("rigtune.settings.mod_files.launcher", launcher), settings.modFilesByRigTune)
				.withValues(false, true)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.mod_files.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.mod_files"), (b, v) -> {
					settings.modFilesByRigTune = v;
					save();
					controller.rescan();
				}));
	}

	// The mod-files row's rule: the policy says the launcher keeps the mods (LAUNCHER, PENDING), or the opt-in is on.
	static boolean showModFilesRow(ModFilesPolicy policy, boolean optIn) {
		return optIn || policy.launcherManages();
	}

	// v0.5 PF-2 (WS-P): the battery offer on or off, so "Don't offer again" can be undone in game. It is profiles.json's
	// battery.snoozed (read here at init, written on a click, like the Profiles screen's own file); greyed out when a newer
	// RigTune wrote profiles.json.
	private void batteryOfferRow(SettingsList rows, int column) {
		ProfileStore store = ProfileStore.shared(configDir);
		CycleButton<Boolean> row = rows.add(CycleButton.onOffBuilder(!store.battery().snoozed())
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.battery_offer.tooltip")))
				.create(0, 0, column, ROW, Component.translatable("rigtune.settings.battery_offer"), (b, v) -> store.snoozeBattery(!v)));
		row.active = store.writable();
	}

	// Written on the settings thread (SettingsSaver), never behind the worker pool; each save writes the current values.
	private void save() {
		SettingsSaver.shared().save(settings, configDir);
	}

	private void networkChanged() {
		save();
		updateActive();
		controller.settingsChanged();
	}

	// The finer switches only matter while the master switch is on.
	private void updateActive() {
		if (remoteRules != null) {
			remoteRules.active = settings.networkEnabled;
		}
		if (modrinth != null) {
			modrinth.active = settings.networkEnabled;
		}
	}

	/** v0.5 (WS-L1 milestone 1): the rows (for the game tests' layout checks). */
	public @Nullable SettingsList list() {
		return list;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// One row per switch (the switch is the row's Tab stop and narrates itself), and the note as a text row.
	public final class SettingsList extends RowList<SettingsList.Row> {
		private final int column;

		SettingsList(int top, int listHeight, int column) {
			super(RigTuneSettingsScreen.this.minecraft, RigTuneSettingsScreen.this.width, listHeight, top, ROW + GAP);
			this.column = column;
		}

		@Override
		public int getRowWidth() {
			return column + 2 * PAD;
		}

		<W extends AbstractWidget> W add(W widget) {
			addEntry(new WidgetRow(widget), ROW + GAP);
			return widget;
		}

		void note(Component text) {
			NoteRow row = new NoteRow(text, column);
			addEntry(row, row.height());
		}

		public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
		}

		final class WidgetRow extends Row {
			private final AbstractWidget widget;

			WidgetRow(AbstractWidget widget) {
				this.widget = widget;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				widget.setPosition(getContentX(), getContentY());
				widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(widget);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(widget);
			}
		}

		final class NoteRow extends Row {
			private final List<FormattedCharSequence> lines;
			private final RowFocus focus;

			NoteRow(Component text, int width) {
				this.lines = font.split(text, width);
				this.focus = new RowFocus(this, text);
			}

			int height() {
				return lines.size() * LINE + GAP + 2 * PAD;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentY() + 2;
				for (FormattedCharSequence line : lines) {
					graphics.centeredText(font, line, getContentXMiddle(), y, Palette.of(COLOR_NOTE));
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
