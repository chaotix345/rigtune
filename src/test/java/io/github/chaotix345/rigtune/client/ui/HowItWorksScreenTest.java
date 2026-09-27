package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 8 (AC8.14, the explainer): How it works' paragraphs follow who changes this instance's mod files. With
// RigTune: the ticked items with their mods, and the mods paragraph (plus P0.4's opted-in sentence when there is one).
// With the launcher: the ticked settings, then P0.4's own sentence about the launcher. While that's being checked: no
// sentence about mod files at all.
class HowItWorksScreenTest {
	private static final String P = "rigtune.firstrun.how.";
	private static final Text LINE = Text.literal("The Modrinth App manages this instance's mods: RigTune changes settings only.");

	private static List<String> rows(ModFilesPolicy policy, Text line) {
		return HowItWorksScreen.rows(policy, line).stream().map(HowItWorksScreenTest::name).toList();
	}

	private static String name(Component row) {
		return row.getContents() instanceof TranslatableContents t ? t.getKey() : row.getString();
	}

	@Test
	void withRigTune() {
		assertEquals(List.of(P + "ticked", P + "now", P + "restart", P + "mods", P + "preview", P + "undo"), rows(ModFilesPolicy.RIGTUNE, null));
		assertEquals(List.of(P + "ticked", P + "now", P + "restart", P + "mods", LINE.english(), P + "preview", P + "undo"),
				rows(ModFilesPolicy.RIGTUNE, LINE), "the opted-in sentence after the mods paragraph");
	}

	@Test
	void withTheLauncher() {
		assertEquals(List.of(P + "ticked.settings", P + "now", P + "restart", LINE.english(), P + "preview", P + "undo"), rows(ModFilesPolicy.LAUNCHER, LINE));
		assertEquals(List.of(P + "ticked.settings", P + "now", P + "restart", P + "preview", P + "undo"), rows(ModFilesPolicy.LAUNCHER, null));
	}

	@Test
	void whileItIsBeingChecked() {
		assertEquals(List.of(P + "ticked.settings", P + "now", P + "restart", P + "preview", P + "undo"), rows(ModFilesPolicy.PENDING, LINE));
	}
}
