package io.github.chaotix345.rigtune.client.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

// Contract stub (docs/v0.2/PLAN.md): its workstream replaces the body; the constructor signature is fixed.
public class UndoScreen extends Screen {
	private final @Nullable Screen parent;
	protected final RigTuneController controller;

	public UndoScreen(@Nullable Screen parent, RigTuneController controller, boolean all) {
		super(Component.translatable("rigtune.undo.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
