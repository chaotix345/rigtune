package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ShareCode;
import io.github.chaotix345.rigtune.core.profile.ShareCodeException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

// Import a share code (docs/v0.4/SPEC.md 4): an edit box, Paste (the only place the clipboard is read) and Import, which
// decodes the code and opens Preview with Apply / Save only / Cancel. Nothing is written before one of those is clicked;
// a code that's rejected shows why here.
public class ProfileImportScreen extends Screen {
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_ERROR = 0xFFFF6B6B;
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(6000L);

	private final Screen parent;
	private final RigTuneController controller;
	private @Nullable EditBox box;
	private @Nullable Button importButton;
	private String code = "";
	private @Nullable Component error;

	public ProfileImportScreen(Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.profile.import.title"));
		this.parent = parent;
		this.controller = controller;
	}

	// For the game test.
	public @Nullable Component error() {
		return error;
	}

	// One character over the decoder's raw cap is kept, so an overlong paste is rejected as too long rather than cut to fit.
	public void setCode(String value) {
		code = value.length() > ShareCode.MAX_RAW_CHARS + 1 ? value.substring(0, ShareCode.MAX_RAW_CHARS + 1) : value;
		if (box != null) {
			box.setValue(code);
		}
		updateButtons();
	}

	@Override
	protected void init() {
		int column = Math.min(width - 16, 360);
		int left = (width - column) / 2;
		int boxY = Math.max(44, height / 2 - 24);
		box = new EditBox(font, left, boxY, column, 20, Component.translatable("rigtune.profile.import.box"));
		box.setMaxLength(ShareCode.MAX_RAW_CHARS + 1);
		box.setHint(Component.translatable("rigtune.profile.import.hint"));
		box.setValue(code);
		box.setResponder(value -> {
			code = value;
			error = null;
			updateButtons();
		});
		addRenderableWidget(box);
		setInitialFocus(box);
		int gap = 4;
		int buttonWidth = (column - 2 * gap) / 3;
		int y = height - 24;
		addRenderableWidget(Button.builder(Component.translatable("rigtune.profile.import.paste"), b -> paste()).bounds(left, y, buttonWidth, 20).build());
		importButton = addRenderableWidget(Button.builder(Component.translatable("rigtune.profile.import.go"), b -> importCode())
				.bounds(left + buttonWidth + gap, y, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(left + 2 * (buttonWidth + gap), y, column - 2 * (buttonWidth + gap), 20).build());
		updateButtons();
	}

	private void updateButtons() {
		if (importButton != null) {
			importButton.active = !code.isBlank();
		}
	}

	public void paste() {
		String clipboard = minecraft.keyboardHandler.getClipboard();
		setCode(clipboard == null ? "" : clipboard);
		error = null;
	}

	// Checks the code here (a rejected one shows its error at once); a good one opens Preview, which works out what applying
	// it would do off the render thread.
	public void importCode() {
		ShareCode.Decoded decoded;
		try {
			decoded = ShareCode.decode(code);
		} catch (ShareCodeException e) {
			error = Texts.component(e.text());
			return;
		}
		error = null;
		String submitted = code;
		AtomicReference<ProfileImport> result = new AtomicReference<>();
		Component name = decoded.name() == null ? Component.translatable("rigtune.profile.imported") : Component.literal(decoded.name());
		minecraft.gui.setScreen(new PreviewScreen(this, controller, c -> {
			ProfileImport imported = c.importProfileCode(submitted);
			result.set(imported);
			return imported.ok() ? imported.preview() : ApplyPreview.EMPTY.withNotes(List.of(imported.error()));
		}, new PreviewScreen.Confirm(Component.translatable("rigtune.profile.import.preview", name), Component.translatable("rigtune.profile.preview.apply"),
				() -> finish(result.get(), true), () -> finish(result.get(), false))));
	}

	private void finish(@Nullable ProfileImport imported, boolean apply) {
		if (imported == null) {
			return;
		}
		finish(apply ? controller.applyImportedProfile(imported) : controller.saveImportedProfile(imported), apply);
	}

	private void finish(Component result, boolean applied) {
		if (parent instanceof ProfilesScreen profiles) {
			profiles.status(result);
		}
		if (applied) {
			SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.profile.title"), result);
		}
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		int textWidth = Math.max(80, Math.min(width - 16, 360));
		int y = 22;
		for (FormattedCharSequence line : font.split(Component.translatable("rigtune.profile.import.intro"), textWidth)) {
			graphics.centeredText(font, line, width / 2, y, COLOR_LABEL);
			y += 10;
		}
		if (error != null && box != null) {
			int errorY = box.getY() + box.getHeight() + 6;
			List<FormattedCharSequence> lines = font.split(error, textWidth);
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(font, line, width / 2, errorY, COLOR_ERROR);
				errorY += 10;
			}
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
