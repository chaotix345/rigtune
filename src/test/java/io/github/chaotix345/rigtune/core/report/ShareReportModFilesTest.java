package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4b, AC4b.4: the share report carries the mod-files line under the launcher's, for each policy; none
// for plain RIGTUNE, so 0.4's report is unchanged.
class ShareReportModFilesTest {
	private static final ShareReport.Versions VERSIONS = new ShareReport.Versions("0.5.0+mc26.2", "26.2", "0.19.5");
	private static final LauncherInfo APP = LauncherInfo.of(Launcher.MODRINTH_APP);

	private static Report report() {
		Recommendation ram = new Recommendation("advice:ram-low", Category.WARNING, Impact.HIGH, "Give Minecraft more memory", "r", new Action.None(), false);
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.BALANCED, List.of(ram), 7, "remote", true, Instant.parse("2026-09-26T10:00:00Z"));
	}

	private static String text(ModFilesPolicy policy, boolean optedIn) {
		return ShareReport.format(report(), VERSIONS, null, "Modrinth App", null, LauncherModText.shareLine(policy, APP, optedIn));
	}

	@Test
	void theLineForEachPolicy() {
		assertTrue(text(ModFilesPolicy.LAUNCHER, false).contains("\n- Launcher: Modrinth App\n- Mod files: changed in Modrinth App\n"), text(ModFilesPolicy.LAUNCHER, false));
		assertTrue(text(ModFilesPolicy.PENDING, false).contains("\n- Launcher: Modrinth App\n- Mod files: waiting for the launcher check\n"));
		assertTrue(text(ModFilesPolicy.RIGTUNE, true).contains("\n- Launcher: Modrinth App\n- Mod files: RigTune (opted in)\n"));
		assertFalse(text(ModFilesPolicy.RIGTUNE, false).contains("Mod files"));
	}

	// Review M4: the line is escaped like every other field (a launcher name is data).
	@Test
	void theLineIsEscaped() {
		String text = ShareReport.format(report(), VERSIONS, null, "Modrinth App", null, "changed in *bold* [link](x)");
		assertTrue(text.contains("- Mod files: " + MarkdownSafe.field("changed in *bold* [link](x)") + "\n"), text);
		assertFalse(text.contains("*bold*"), text);
	}

	@Test
	void plainRigTuneIsTheReportAsBefore() {
		assertEquals(ShareReport.format(report(), VERSIONS, null, "Modrinth App", null), text(ModFilesPolicy.RIGTUNE, false));
		assertEquals(ShareReport.format(report(), VERSIONS, null, 2000, "Modrinth App", null),
				ShareReport.format(report(), VERSIONS, null, 2000, "Modrinth App", null, null));
	}
}
