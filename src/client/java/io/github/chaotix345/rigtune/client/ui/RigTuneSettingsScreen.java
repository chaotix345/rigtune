package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.model.Goal;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

// The v0.2 settings (docs/v0.2/SPEC.md item 8). Every change is saved at once; the network switches also make the
// controller reload and rescan, so the report reflects them.
public class RigTuneSettingsScreen extends Screen {
	private static final int ROW = 20;
	private static final int GAP = 4;
	private static final int TOP = 36;
	private static final int MAX_WIDTH = 310;
	private static final int COLOR_NOTE = 0xFFA8A8A8;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final ClientSettings settings;
	private final Path configDir;
	private @Nullable CycleButton<Boolean> remoteRules;
	private @Nullable CycleButton<Boolean> modrinth;
	private int noteY;

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
		int x = (width - column) / 2;
		int y = TOP;

		addRenderableWidget(CycleButton.onOffBuilder(settings.networkEnabled)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.network.tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.settings.network"), (b, v) -> {
					settings.networkEnabled = v;
					networkChanged();
				}));
		y += ROW + GAP;
		remoteRules = addRenderableWidget(CycleButton.onOffBuilder(settings.remoteRules)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.remote_rules.tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.settings.remote_rules"), (b, v) -> {
					settings.remoteRules = v;
					networkChanged();
				}));
		y += ROW + GAP;
		modrinth = addRenderableWidget(CycleButton.onOffBuilder(settings.modrinth)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.modrinth.tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.settings.modrinth"), (b, v) -> {
					settings.modrinth = v;
					networkChanged();
				}));
		y += ROW + GAP;
		addRenderableWidget(CycleButton.onOffBuilder(settings.startupToast)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.settings.startup_toast.tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.settings.startup_toast"), (b, v) -> {
					settings.startupToast = v;
					save();
				}));
		y += ROW + GAP;
		addRenderableWidget(CycleButton.builder((Goal g) -> Component.translatable("rigtune.goal." + g.name().toLowerCase(Locale.ROOT)), controller.goal())
				.withValues(Goal.values())
				.withTooltip(g -> Tooltip.create(Component.translatable("rigtune.goal." + g.name().toLowerCase(Locale.ROOT) + ".tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.settings.goal"), (b, g) -> controller.setGoal(g)));
		y += ROW + GAP;
		// The same labels and availability as the benchmark menu, which reads this setting.
		BenchmarkRequest.Scene[] scenes = BenchmarkWorld.supported() ? BenchmarkRequest.Scene.values() : new BenchmarkRequest.Scene[]{BenchmarkRequest.Scene.CURRENT};
		BenchmarkRequest.Scene scene = BenchmarkWorld.supported() ? settings.benchmarkSceneOrDefault() : BenchmarkRequest.Scene.CURRENT;
		addRenderableWidget(CycleButton.builder((BenchmarkRequest.Scene s) -> Component.translatable("rigtune.benchmark.scene." + s.name().toLowerCase(Locale.ROOT)), scene)
				.withValues(scenes)
				.withTooltip(s -> Tooltip.create(Component.translatable("rigtune.settings.scene.tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.settings.scene"), (b, s) -> {
					settings.benchmarkScene = s.name();
					save();
				}));
		y += ROW + GAP;
		y = stutterMonitorRow(x, y, column);
		noteY = y + 2;
		updateActive();

		int footer = height - 28;
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
	private int stutterMonitorRow(int x, int y, int column) {
		addRenderableWidget(CycleButton.onOffBuilder(settings.stutterMonitor)
				.withTooltip(v -> Tooltip.create(Component.translatable("rigtune.stutter.monitor.tooltip")))
				.create(x, y, column, ROW, Component.translatable("rigtune.stutter.monitor"), (b, v) -> {
					settings.stutterMonitor = v;
					save();
					controller.setStutterMonitor(v);
				}));
		return y + ROW + GAP;
	}

	// Written on a worker thread; each save writes the current values, so the last one always wins.
	private void save() {
		CompletableFuture.runAsync(() -> settings.save(configDir), Probes.EXECUTOR);
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

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
		if (noteY + 9 < height - 28 - GAP) {
			graphics.centeredText(font, Component.translatable("rigtune.settings.note"), width / 2, noteY, Palette.of(COLOR_NOTE));
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
