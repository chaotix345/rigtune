package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.jvm.JvmFacts;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 6 with the "Launcher steps" amendment: jvm-* advice gets the launcher's Java-arguments steps (every
// known launcher, CurseForge included), ram-* advice keeps the memory steps, and in the Modrinth App a typed -Xmx adds
// the note that it overrides the slider.
class JvmLauncherAdviceTest {
	private static Recommendation advice(String id) {
		return new Recommendation(id, Category.ADVICE, Impact.LOW, "t", "r", new Action.None(), false);
	}

	private static JvmReport withFacts(String... facts) {
		return new JvmReport(true, "25", "x", null, false, 4096, -1, List.of(), Set.of(facts));
	}

	static Map<Launcher, String> jvmSteps() {
		Map<Launcher, String> expected = new LinkedHashMap<>();
		expected.put(Launcher.PRISM, "rigtune.launcher.jvm_steps.prism");
		expected.put(Launcher.MULTIMC, "rigtune.launcher.jvm_steps.multimc");
		expected.put(Launcher.GDLAUNCHER, "rigtune.launcher.jvm_steps.gdlauncher");
		expected.put(Launcher.MODRINTH_APP, "rigtune.launcher.jvm_steps.modrinth_app");
		expected.put(Launcher.ATLAUNCHER, "rigtune.launcher.jvm_steps.atlauncher");
		expected.put(Launcher.CURSEFORGE, "rigtune.launcher.jvm_steps.curseforge");
		expected.put(Launcher.OFFICIAL, "rigtune.launcher.jvm_steps.official");
		return expected;
	}

	@Test
	void everyKnownLauncherHasJavaArgumentsSteps() {
		for (Launcher launcher : Launcher.values()) {
			if (launcher == Launcher.UNKNOWN) {
				assertNull(LauncherInfo.of(launcher).jvmStepsKey());
				continue;
			}
			assertEquals(jvmSteps().get(launcher), LauncherInfo.of(launcher).jvmStepsKey(), launcher.name());
			assertNotNull(jvmSteps().get(launcher), "every known launcher is covered: " + launcher);
		}
		for (Boolean override : new Boolean[] {null, true, false}) {
			assertEquals("rigtune.launcher.jvm_steps.curseforge", new LauncherInfo(Launcher.CURSEFORGE, override).jvmStepsKey());
		}
	}

	@Test
	void onlyJvmAdviceGetsTheJavaArgumentsSteps() {
		LauncherInfo modrinth = LauncherInfo.of(Launcher.MODRINTH_APP);
		assertEquals("rigtune.launcher.jvm_steps.modrinth_app", LauncherAdvice.jvmStepsKey(advice("advice:jvm-ignored-flags"), modrinth));
		assertTrue(LauncherAdvice.isJvmAdvice(advice("advice:jvm-anything")));
		for (String id : List.of("advice:ram-low", "advice:jvmx", "set:vanilla.jvm-x", "jvm-ignored-flags", "advice:update-queued:jvm-x")) {
			assertFalse(LauncherAdvice.isJvmAdvice(advice(id)), id);
			assertNull(LauncherAdvice.jvmStepsKey(advice(id), modrinth), id);
		}
		assertNull(LauncherAdvice.stepsKey(advice("advice:jvm-ignored-flags"), modrinth), "jvm advice never gets the memory steps");
		assertNull(LauncherAdvice.jvmStepsKey(advice("advice:jvm-ignored-flags"), LauncherInfo.UNKNOWN));
	}

	@Test
	void aTypedXmxOverridesTheSliderOnlyInTheModrinthApp() {
		Recommendation ram = advice("advice:ram-low");
		JvmReport duplicate = withFacts(JvmFacts.PROBED, "jvm-gc-g1", JvmFacts.XMX_DUPLICATE);
		JvmReport single = withFacts(JvmFacts.PROBED, "jvm-gc-g1");
		assertTrue(LauncherAdvice.typedXmxWins(ram, LauncherInfo.of(Launcher.MODRINTH_APP), duplicate));
		assertFalse(LauncherAdvice.typedXmxWins(ram, LauncherInfo.of(Launcher.MODRINTH_APP), single));
		assertFalse(LauncherAdvice.typedXmxWins(ram, LauncherInfo.of(Launcher.MODRINTH_APP), JvmReport.UNAVAILABLE));
		assertFalse(LauncherAdvice.typedXmxWins(advice("advice:jvm-xmx-duplicate"), LauncherInfo.of(Launcher.MODRINTH_APP), duplicate));
		// docs/v0.4/SPEC.md 2g + "Launcher steps": GDLauncher appends the typed arguments after its own -Xmx too.
		assertTrue(LauncherAdvice.typedXmxWins(ram, LauncherInfo.of(Launcher.GDLAUNCHER), duplicate));
		for (Launcher launcher : List.of(Launcher.PRISM, Launcher.MULTIMC, Launcher.ATLAUNCHER, Launcher.CURSEFORGE, Launcher.OFFICIAL, Launcher.UNKNOWN)) {
			assertFalse(LauncherAdvice.typedXmxWins(ram, LauncherInfo.of(launcher), duplicate), launcher.name());
		}
	}
}
