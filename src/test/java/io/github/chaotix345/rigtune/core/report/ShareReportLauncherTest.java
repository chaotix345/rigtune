package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherDetector;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.4 (docs/v0.3/SPEC.md item 5): the share report names the launcher and nothing else about it.
class ShareReportLauncherTest {
	private static final ShareReport.Versions VERSIONS = new ShareReport.Versions("0.3.0-dev+mc26.2", "26.2", "0.19.5");
	private static final String INSTANCE = "Alice's Secret Pack";
	private static final Path GAME_DIR = Path.of("C:", "Users", "alice", "AppData", "Roaming", "PrismLauncher", "instances", INSTANCE, "minecraft");

	private static Report report() {
		Recommendation ram = new Recommendation("advice:ram-low", Category.WARNING, Impact.HIGH, "Give Minecraft more memory",
				"Your launcher lets Minecraft use about 2 GB.", new Action.None(), false);
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.BALANCED, List.of(ram), 7, "remote", true, Instant.parse("2026-09-26T10:00:00Z"));
	}

	private static String name(LauncherInfo info) {
		return info.known() ? info.launcher().displayName() : null;
	}

	@Test
	void namesTheDetectedLauncherAndNothingElse() {
		LauncherInfo prism = LauncherDetector.detect(new LauncherSignals(
				Map.of("org.prismlauncher.instance.name", INSTANCE, "multimc.instance.title", INSTANCE, "minecraft.launcher.brand", "PrismLauncher"),
				Map.of("INST_ID", INSTANCE, "INST_NAME", INSTANCE), GAME_DIR));
		assertEquals(Launcher.PRISM, prism.launcher());

		String text = ShareReport.format(report(), VERSIONS, null, name(prism));

		assertTrue(text.contains("- RAM 32 GB · heap 6.0 GB · display 2560×1440 @ 180 Hz\n- Launcher: Prism Launcher\n"), text);
		for (String leak : List.of("Secret", "alice", "INST_", "PrismLauncher", "instances", "Roaming", "org.prismlauncher")) {
			assertFalse(text.contains(leak), leak + " in " + text);
		}
	}

	// docs/v0.4/SPEC.md 2g (AC2g.3): MultiMC and GDLauncher by name, and nothing else about them.
	@Test
	void namesMultimcAndGdlauncherAndNothingElse() {
		LauncherInfo multimc = LauncherDetector.detect(new LauncherSignals(Map.of("multimc.instance.title", INSTANCE),
				Map.of("INST_ID", INSTANCE, "INST_NAME", INSTANCE), GAME_DIR));
		LauncherInfo gdlauncher = LauncherDetector.detect(new LauncherSignals(Map.of("minecraft.launcher.brand", "GDLauncher"), Map.of(), GAME_DIR));

		String multimcText = ShareReport.format(report(), VERSIONS, null, name(multimc));
		String gdText = ShareReport.format(report(), VERSIONS, null, name(gdlauncher));

		assertTrue(multimcText.contains("\n- Launcher: MultiMC\n"), multimcText);
		assertTrue(gdText.contains("\n- Launcher: GDLauncher\n"), gdText);
		for (String text : List.of(multimcText, gdText)) {
			for (String leak : List.of("Secret", "alice", "INST_", "multimc.", "instances", "Roaming", "minecraft.launcher")) {
				assertFalse(text.contains(leak), leak + " in " + text);
			}
		}
	}

	@Test
	void everyLauncherByName() {
		for (Launcher launcher : Launcher.values()) {
			LauncherInfo info = LauncherInfo.of(launcher);
			String text = ShareReport.format(report(), VERSIONS, null, name(info));
			if (info.known()) {
				assertTrue(text.contains("\n- Launcher: " + launcher.displayName() + "\n"), text);
			} else {
				assertFalse(text.contains("Launcher:"), text);
			}
		}
	}

	@Test
	void unknownLauncherLeavesTheReportAsBefore() {
		assertEquals(ShareReport.format(report(), VERSIONS, null), ShareReport.format(report(), VERSIONS, null, (String) null));
		assertEquals(ShareReport.format(report(), VERSIONS, null), ShareReport.format(report(), VERSIONS, null, " "));
		assertEquals(ShareReport.format(report(), VERSIONS, null, 300), ShareReport.format(report(), VERSIONS, null, 300, null));
	}

	@Test
	void theNameIsEscapedLikeAnyField() {
		String text = ShareReport.format(report(), VERSIONS, null, "*@everyone* C:\\Users\\alice");
		assertTrue(text.contains("- Launcher: \\*@\u200Beveryone\\* (path)\n"), text);
	}

	@Test
	void theLauncherLineCountsTowardsTheLimit() {
		String text = ShareReport.format(report(), VERSIONS, null, 2000, "Modrinth App");
		assertTrue(text.length() <= 2000, text);
		String cut = ShareReport.format(report(), VERSIONS, null, 400, "Modrinth App");
		assertTrue(cut.length() <= 400, cut);
	}
}
