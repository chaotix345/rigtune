package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.FolderCheck;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The RigTune screen's "Disable X", checked before RealController.apply stages it (docs/v0.4/SPEC.md 2o): refused when
// a mod in the folder (as the staged ops leave it) depends on X (audit H1-B), or when another change of X, such as an
// update, is staged (audit M5). A refusal is logged and shown as a toast; the rest of the Apply goes ahead.
public final class DisableGuard {
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(8000L);

	private DisableGuard() {
	}

	public static boolean allows(Path pendingFile, Path modsDir, Path file) {
		Text refusal;
		try {
			refusal = refusal(pendingFile, ModsFolder.current(modsDir), file);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not check what depends on {}; disabling it as before", file.getFileName(), e);
			return true;
		}
		if (refusal == null) {
			return true;
		}
		String name = String.valueOf(file.getFileName());
		RigTune.LOGGER.warn("Not disabling {}: {}", name, refusal.english());
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.toast.disable_refused.title", name),
					Texts.component(refusal));
		}
		return false;
	}

	static @Nullable Text refusal(Path pendingFile, UndoPlanner.Folder folder, Path file) {
		List<Op> pending = List.of();
		if (Files.exists(pendingFile)) {
			try {
				pending = PendingActions.load(pendingFile).ops();
			} catch (IOException e) {
				RigTune.LOGGER.warn("Could not read {}", pendingFile, e);
			}
		}
		return FolderCheck.disableRefusal(folder, pending, String.valueOf(file.getFileName()));
	}
}
