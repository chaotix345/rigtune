package io.github.chaotix345.rigtune.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

// Stutter Doctor (docs/v0.4/SPEC.md 5). Opened from ToolsScreen. Skeleton from the contracts commit (title + Done); the Stutter Doctor workstream owns it.
public class StutterScreen extends Screen {
	private final @Nullable Screen parent;
	protected final RigTuneController controller;

	public StutterScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.stutter.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		int buttonWidth = Math.min(200, width - 16);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
