package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 2H L5 (AC2H.1b, the screen's half): the disclosure line shows whenever downloads are listed that the
// preview couldn't check (Modrinth off, Range refused), and never otherwise: not once every listed download was checked,
// and not without a listed download.
class PreviewDownloadsNoteTest {
	private static final ApplyPreview.Download SODIUM = new ApplyPreview.Download("update:sodium", "Update Sodium", "sodium-0.9.3.jar",
			Path.of("mods/sodium-0.9.3.jar"), false);
	private static final ApplyPreview.Download UNRESOLVED = new ApplyPreview.Download("add:lithium", "Install Lithium", null, null, false);

	private static ApplyPreview preview(List<ApplyPreview.Download> downloads, boolean resolved, boolean checked) {
		return new ApplyPreview(List.of(), List.of(), downloads, List.of(), List.of(), resolved, List.of(), checked);
	}

	@Test
	void uncheckedDownloadsGetTheDisclosure() {
		TranslatableContents note = (TranslatableContents) PreviewScreen.downloadsNote(preview(List.of(SODIUM), true, false)).getContents();

		assertEquals("rigtune.preview.note.downloads", note.getKey());
		// Modrinth off: an update is listed, an addition unresolved.
		assertEquals("rigtune.preview.note.downloads",
				((TranslatableContents) PreviewScreen.downloadsNote(preview(List.of(SODIUM, UNRESOLVED), false, false)).getContents()).getKey());
	}

	@Test
	void checkedDownloadsOrNoneGetNoDisclosure() {
		assertNull(PreviewScreen.downloadsNote(preview(List.of(SODIUM), true, true)));
		assertNull(PreviewScreen.downloadsNote(preview(List.of(), true, false)));
		// Only unresolved additions (Modrinth off): the "Modrinth is off" note says it; no file is listed to check again.
		assertNull(PreviewScreen.downloadsNote(preview(List.of(UNRESOLVED), false, false)));
	}
}
