package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.jvm.JvmCollector;
import io.github.chaotix345.rigtune.core.jvm.JvmFacts;
import io.github.chaotix345.rigtune.core.jvm.JvmFinding;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 6 (AC6.2's last sentence): the share report's one Java line names the version, vendor, collector and
// how many argument notes there are, never an argument.
class ShareReportJvmTest {
	private static final ShareReport.Versions VERSIONS = new ShareReport.Versions("0.4.0-dev+mc26.2", "26.2", "0.19.5");

	private static Report report() {
		Recommendation ram = new Recommendation("advice:jvm-ignored-flags", Category.ADVICE, Impact.LOW, "Remove Java arguments Java ignores",
				"Java ignores some of your Java arguments.", new Action.None(), false);
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.BALANCED, List.of(ram), 7, "remote", true, Instant.parse("2026-09-26T10:00:00Z"));
	}

	private static JvmReport jvm(String vendor, JvmFinding... findings) {
		return new JvmReport(true, "25.0.3", vendor, JvmCollector.G1, false, 6144, 512, List.of(findings),
				Set.of(JvmFacts.PROBED, "jvm-gc-g1", JvmFacts.IGNORED_FLAGS));
	}

	@Test
	void oneJavaLineAfterTheLauncher() {
		JvmReport jvm = jvm("Azul Systems, Inc.", new JvmFinding(JvmFinding.Kind.IGNORED, "-XX:+ZGenerational"),
				new JvmFinding(JvmFinding.Kind.SERVER_SET, "-Dusing.aikars.flags"));
		String text = ShareReport.format(report(), VERSIONS, null, "Modrinth App", jvm);
		assertTrue(text.contains("\n- Launcher: Modrinth App\n- Java: 25.0.3 (Azul Systems, Inc.), G1, 2 argument notes\n"), text);
		for (String leak : List.of("ZGenerational", "aikars", "-XX", "-D")) {
			assertFalse(text.contains(leak), leak + " in " + text);
		}
		String javaLine = text.lines().filter(l -> l.startsWith("- Java:")).findFirst().orElseThrow();
		assertFalse(javaLine.contains("=") || javaLine.contains("/") || javaLine.contains("\\"), javaLine);
		assertTrue(ShareReport.format(report(), VERSIONS, null, null, jvm).contains("\n- Java: 25.0.3 (Azul Systems, Inc.), G1, 2 argument notes\n"));
	}

	@Test
	void countsAndMissingParts() {
		assertTrue(ShareReport.format(report(), VERSIONS, null, null, jvm("Eclipse Adoptium")).contains("- Java: 25.0.3 (Eclipse Adoptium), G1, 0 argument notes\n"));
		assertTrue(ShareReport.format(report(), VERSIONS, null, null, jvm("Eclipse Adoptium", new JvmFinding(JvmFinding.Kind.XMX_DUPLICATE, "-Xmx")))
				.contains("- Java: 25.0.3 (Eclipse Adoptium), G1, 1 argument note\n"));
		JvmReport openJ9 = new JvmReport(false, "21.0.4", "Eclipse OpenJ9", JvmCollector.OTHER, false, 4096, -1, List.of(), Set.of());
		assertTrue(ShareReport.format(report(), VERSIONS, null, null, openJ9).contains("- Java: 21.0.4 (Eclipse OpenJ9)\n"), "no collector name, no notes");
		JvmReport noVendor = new JvmReport(true, "25", " ", JvmCollector.ZGC, true, 4096, -1, List.of(), Set.of(JvmFacts.PROBED));
		assertTrue(ShareReport.format(report(), VERSIONS, null, null, noVendor).contains("- Java: 25, ZGC, 0 argument notes\n"));
	}

	@Test
	void noLineWithoutAJavaReport() {
		String before = ShareReport.format(report(), VERSIONS, null, "Modrinth App");
		assertEquals(before, ShareReport.format(report(), VERSIONS, null, "Modrinth App", null));
		assertEquals(before, ShareReport.format(report(), VERSIONS, null, "Modrinth App", JvmReport.UNAVAILABLE));
		assertFalse(before.contains("Java:"));
	}

	@Test
	void theVendorIsTreatedAsOutsideText() {
		JvmReport odd = jvm("*Vendor* @everyone C:/Users/alice/jdk");
		String text = ShareReport.format(report(), VERSIONS, null, null, odd);
		assertFalse(text.contains("alice"), text);
		assertTrue(text.contains("\\*Vendor\\*"), text);
		assertFalse(text.contains("@everyone"), text);
	}

	@Test
	void stillWithinTheLimit() {
		String text = ShareReport.format(report(), VERSIONS, null, 300, "Modrinth App", jvm("Azul Systems, Inc."));
		assertTrue(text.length() <= 300, text.length() + ": " + text);
	}
}
