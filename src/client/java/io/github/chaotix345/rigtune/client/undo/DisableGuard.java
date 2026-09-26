package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.core.apply.LogSafe;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.history.FolderCheck;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The RigTune screen's "Disable X" recommendations of one Apply, checked together before RealController.apply stages
// them (docs/v0.4/SPEC.md 2o): refused when a mod left in the folder (as the staged ops and the Apply's other disables
// leave it) depends on X (audit H1-B), or when another change that brings X back, such as an update, is staged (audit
// M5). Refusals are logged and shown in one toast; the rest of the Apply goes ahead.
public final class DisableGuard {
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(8000L);

	private DisableGuard() {
	}

	// The ids of the selected DisableMod recommendations that may be staged.
	public static Set<String> allowed(Path pendingFile, Path modsDir, List<Recommendation> selected) {
		Map<String, String> fileById = new LinkedHashMap<>();
		for (Recommendation r : selected) {
			if (r.action() instanceof Action.DisableMod disable && disable.file() != null && SafeFileNames.isDirectChild(modsDir, disable.file())) {
				fileById.put(r.id(), String.valueOf(disable.file().getFileName()));
			}
		}
		if (fileById.isEmpty()) {
			return Set.of();
		}
		Map<String, Text> refused;
		try {
			refused = refusals(pendingFile, ModsFolder.current(modsDir), List.copyOf(fileById.values()));
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not check what depends on {}; disabling as before", fileById.values(), e);
			return fileById.keySet();
		}
		Set<String> out = new LinkedHashSet<>();
		fileById.forEach((id, file) -> {
			if (!refused.containsKey(file)) {
				out.add(id);
			}
		});
		if (!refused.isEmpty()) {
			refused.forEach((file, why) -> RigTune.LOGGER.warn("Not disabling {}: {}", file, why.english()));
			toast(refused);
		}
		return out;
	}

	static Map<String, Text> refusals(Path pendingFile, UndoPlanner.Folder folder, List<String> files) {
		List<Op> pending = List.of();
		if (Files.exists(pendingFile)) {
			try {
				pending = PendingActions.load(pendingFile).ops();
			} catch (IOException e) {
				RigTune.LOGGER.warn("Could not read {} ({})", LogSafe.name(pendingFile), LogSafe.error(e, pendingFile));
			}
		}
		return FolderCheck.disableRefusals(folder, pending, files);
	}

	private static void toast(Map<String, Text> refused) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}
		List<Text> reasons = new ArrayList<>(new LinkedHashSet<>(refused.values()));
		SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.toast.disable_refused.title", String.join(", ", refused.keySet())),
				Texts.component(Text.join(" ", reasons)));
	}
}
