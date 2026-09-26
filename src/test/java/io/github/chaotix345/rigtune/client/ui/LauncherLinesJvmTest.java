package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.jvm.JvmCollector;
import io.github.chaotix345.rigtune.core.jvm.JvmFacts;
import io.github.chaotix345.rigtune.core.jvm.JvmFinding;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 6 with the "Launcher steps" amendment, at the text the screen shows (RigTune's en_us.json).
class LauncherLinesJvmTest {
	private Language previous;

	@BeforeEach
	void english() throws IOException {
		previous = Language.getInstance();
		Language.inject(TextsTest.rigtuneEnglish(previous));
	}

	@AfterEach
	void restore() {
		Language.inject(previous);
	}

	private static Recommendation advice(String id) {
		return new Recommendation(id, Category.ADVICE, Impact.LOW, "t", "r", new Action.None(), false);
	}

	private static final JvmReport ZGC_AIKAR = new JvmReport(true, "25.0.3", "x", JvmCollector.ZGC, true, 4096, -1,
			List.of(new JvmFinding(JvmFinding.Kind.IGNORED, "-XX:+ZGenerational"), new JvmFinding(JvmFinding.Kind.SERVER_SET, "-Dusing.aikars.flags")),
			Set.of(JvmFacts.PROBED, "jvm-gc-zgc", JvmFacts.GC_TYPED, JvmFacts.IGNORED_FLAGS, JvmFacts.SERVER_FLAGS));

	@Test
	void jvmAdviceGetsTheFoundFlagsAndTheJavaArgumentsSteps() {
		Component line = LauncherLines.adviceLine(advice("advice:jvm-ignored-flags"), LauncherInfo.of(Launcher.MODRINTH_APP), ZGC_AIKAR);
		assertEquals("Found in your Java arguments: -XX:+ZGenerational. In the Modrinth App: this instance → Instance settings (gear) → Sync overrides"
				+ " → turn on Custom Java arguments → edit the box.", line.getString());
		assertEquals("Found in your Java arguments: -Dusing.aikars.flags. In Prism Launcher: right-click this instance → Edit... → Settings → Java"
						+ " → tick Java Arguments → edit the box.",
				LauncherLines.adviceLine(advice("advice:jvm-server-flags"), LauncherInfo.of(Launcher.PRISM), ZGC_AIKAR).getString());
		assertEquals("Found in your Java arguments: -XX:+UseZGC. In the Minecraft Launcher: Installations → select this installation → More Options"
						+ " → JVM Arguments, then Save.",
				LauncherLines.adviceLine(advice("advice:jvm-zgc-small-heap"), LauncherInfo.of(Launcher.OFFICIAL), ZGC_AIKAR).getString());
		assertTrue(LauncherLines.adviceLine(advice("advice:jvm-ignored-flags"), new LauncherInfo(Launcher.CURSEFORGE, false), ZGC_AIKAR).getString()
				.endsWith("In the CurseForge app: My Modpacks → this pack's three-dot menu → Profile Options → Advanced Settings → Additional Arguments,"
						+ " and Settings (gear icon) → Minecraft → Default Additional Arguments."));
	}

	@Test
	void partsCanBeMissing() {
		assertEquals("Found in your Java arguments: -XX:+ZGenerational.",
				LauncherLines.adviceLine(advice("advice:jvm-ignored-flags"), LauncherInfo.UNKNOWN, ZGC_AIKAR).getString(), "no steps without a launcher");
		Component stepsOnly = LauncherLines.adviceLine(advice("advice:jvm-no-gc"), LauncherInfo.of(Launcher.ATLAUNCHER), ZGC_AIKAR);
		assertEquals("In ATLauncher: this instance's Settings button → Java/Minecraft → Java Parameters.", stepsOnly.getString());
		assertTrue(stepsOnly.getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.launcher.advice"));
		assertNull(LauncherLines.adviceLine(advice("advice:jvm-no-gc"), LauncherInfo.UNKNOWN, ZGC_AIKAR));
		assertNull(LauncherLines.adviceLine(advice("advice:jvm-ignored-flags"), LauncherInfo.UNKNOWN, JvmReport.UNAVAILABLE));
		assertNull(LauncherLines.adviceLine(advice("set:vanilla.renderDistance"), LauncherInfo.of(Launcher.MODRINTH_APP), ZGC_AIKAR));
	}

	@Test
	void ramAdviceKeepsTheMemoryStepsAndGainsTheTypedXmxNote() {
		Recommendation ram = advice("advice:ram-low");
		LauncherInfo modrinth = LauncherInfo.of(Launcher.MODRINTH_APP);
		Component plain = LauncherLines.adviceLine(ram, modrinth, ZGC_AIKAR);
		assertEquals(LauncherLines.adviceLine(ram, modrinth).getString(), plain.getString(), "no typed -Xmx: as in v0.3");
		JvmReport duplicate = new JvmReport(true, "25", "x", JvmCollector.G1, false, 8192, -1, List.of(new JvmFinding(JvmFinding.Kind.XMX_DUPLICATE, "-Xmx")),
				Set.of(JvmFacts.PROBED, "jvm-gc-g1", JvmFacts.XMX_DUPLICATE));
		Component noted = LauncherLines.adviceLine(ram, modrinth, duplicate);
		assertEquals("In the Modrinth App: this instance → Instance settings (gear) → Sync overrides → turn on Custom memory allocation → set the slider."
				+ " Your Java arguments also contain -Xmx, which the Modrinth App uses instead of the memory slider: change or remove it there.", noted.getString());
		assertTrue(noted.getContents() instanceof TranslatableContents t && t.getKey().equals("rigtune.launcher.advice"),
				"the memory line stays the first part (v0.3's game tests read it)");
		LauncherInfo prism = LauncherInfo.of(Launcher.PRISM);
		assertEquals(LauncherLines.adviceLine(ram, prism).getString(), LauncherLines.adviceLine(ram, prism, duplicate).getString(),
				"Prism's memory box wins over a typed -Xmx");
		assertNull(LauncherLines.adviceLine(ram, LauncherInfo.UNKNOWN, duplicate));
		assertSame(null, LauncherLines.adviceLine(advice("advice:software-rendering"), modrinth, duplicate));
	}
}
