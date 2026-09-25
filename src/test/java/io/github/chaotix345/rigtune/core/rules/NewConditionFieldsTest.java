package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.TRUE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class NewConditionFieldsTest {
	// docs/research/v0.2/triage.md §5: mesh-shader-capable NVIDIA GPUs (Turing and newer).
	private static final String NVIDIUM = "(?i)GTX\\\\s*16[56]0\\\\b|RTX\\\\s*(?:20[6-8]0|30[5-9]0|40[5-9]0|50[5-9]0)\\\\b"
			+ "|Quadro\\\\s*RTX\\\\s*\\\\d{4}\\\\b|RTX\\\\s*A\\\\d{3,4}\\\\b|RTX\\\\s*\\\\d{4}\\\\s*Ada\\\\b";
	private static final String NVIDIUM_CONDITION = "{\"gpuModelMatches\":\"" + NVIDIUM + "\"}";

	private final EvalFixture f = new EvalFixture();

	@Test
	void nvidiumRegexMatchesMeshShaderCards() {
		for (String renderer : List.of("NVIDIA GeForce RTX 4070", "NVIDIA GeForce GTX 1660 SUPER", "NVIDIA GeForce GTX 1650",
				"NVIDIA GeForce GTX 1650 Ti", "NVIDIA GeForce RTX 2060", "NVIDIA GeForce RTX 2060 SUPER", "NVIDIA GeForce RTX 2080 Ti",
				"NVIDIA GeForce RTX 3080", "NVIDIA GeForce RTX 3070 Laptop GPU", "NVIDIA GeForce RTX 4090", "NVIDIA GeForce RTX 5080",
				"Quadro RTX 4000", "Quadro RTX 5000", "Quadro RTX 8000", "NVIDIA RTX A2000", "NVIDIA RTX A4000", "NVIDIA RTX A6000",
				"NVIDIA RTX 4000 Ada Generation", "NVIDIA GeForce RTX 4070/PCIe/SSE2")) {
			f.renderer("NVIDIA Corporation", renderer, GpuVendor.NVIDIA);
			assertEquals(TRUE, f.truth(NVIDIUM_CONDITION), renderer);
		}
	}

	@Test
	void nvidiumRegexRejectsOlderAndOtherCards() {
		for (String renderer : List.of("NVIDIA GeForce GTX 1080", "NVIDIA GeForce GTX 1080 Ti", "NVIDIA GeForce GTX 1070",
				"NVIDIA GeForce GTX 1060", "NVIDIA GeForce GTX 980 Ti", "NVIDIA GeForce GT 1030", "NVIDIA GeForce MX450",
				"Quadro P4000", "Quadro M4000", "AMD Radeon RX 6800 XT", "Intel(R) Iris(R) Xe Graphics", "NVIDIA GeForce GTX 1080/PCIe/SSE2")) {
			f.renderer("NVIDIA Corporation", renderer, GpuVendor.NVIDIA);
			assertEquals(FALSE, f.truth(NVIDIUM_CONDITION), renderer);
		}
	}

	@Test
	void gpuModelMatchesUsesTheVendorStringWhenTheRendererIsBlank() {
		f.renderer("NVIDIA GeForce RTX 3060", " ", GpuVendor.NVIDIA);
		assertEquals(TRUE, f.truth(NVIDIUM_CONDITION));
	}

	@Test
	void gpuModelMatchesWithoutGpuInfoIsUnknown() {
		f.noGpuInfo();
		assertEquals(UNKNOWN, f.truth(NVIDIUM_CONDITION));
		f.hw.gpu = null;
		assertEquals(UNKNOWN, f.truth(NVIDIUM_CONDITION));
	}

	@Test
	void gpuModelMatchesInvalidOrOverlongIsUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"gpuModelMatches\":\"(?i)[unclosed\"}"));
		String longest = "a".repeat(RulesDocument.PatternRule.MAX_PATTERN_LENGTH);
		assertEquals(FALSE, f.truth("{\"gpuModelMatches\":\"" + longest + "\"}"));
		assertEquals(UNKNOWN, f.truth("{\"gpuModelMatches\":\"" + longest + "b\"}"));
	}

	@Test
	void gpuModelMatchesOutOfBudgetIsUnknown() {
		f.renderer("", "a".repeat(40), GpuVendor.OTHER);
		assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertEquals(UNKNOWN, f.truth("{\"not\":{\"gpuModelMatches\":\"((a+)+)+b\"}}")));
	}

	@Test
	void displayPixelsBoundsAreInclusive() {
		f.hw.display = new DisplayInfo(2560, 1440, 180, true);
		assertEquals(TRUE, f.truth("{\"displayPixelsAtLeast\":3686400}"));
		assertEquals(FALSE, f.truth("{\"displayPixelsAtLeast\":3686401}"));
		assertEquals(TRUE, f.truth("{\"displayPixelsAtMost\":3686400}"));
		assertEquals(FALSE, f.truth("{\"displayPixelsAtMost\":2073600}"));
		f.hw.display = new DisplayInfo(7680, 4320, 60, true);
		assertEquals(TRUE, f.truth("{\"displayPixelsAtLeast\":33177600}"));
	}

	@Test
	void displayPixelsWithUnknownSizeIsUnknown() {
		f.hw.display = new DisplayInfo(-1, 1440, 60, true);
		assertEquals(UNKNOWN, f.truth("{\"displayPixelsAtLeast\":1}"));
		f.hw.display = new DisplayInfo(2560, 0, 60, true);
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"displayPixelsAtMost\":1}}"));
		f.hw.display = null;
		assertEquals(UNKNOWN, f.truth("{\"displayPixelsAtLeast\":1}"));
	}

	@Test
	void modVersionMatchesTheInstalledVersion() {
		f.versions.put("sodium", "0.7.1");
		assertEquals(TRUE, f.truth("{\"modVersion\":{\"sodium\":\">=0.6.0 <0.8.0\"}}"));
		f.versions.put("sodium", "0.9.2");
		assertEquals(FALSE, f.truth("{\"modVersion\":{\"sodium\":\">=0.6.0 <0.8.0\"}}"));
		assertEquals(TRUE, f.truth("{\"modVersion\":{\"sodium\":\"~0.9\"}}"));
		assertEquals(TRUE, f.truth("{\"modVersion\":{\"sodium\":\"~0.9\",\"lithium\":\">=0.25\"}}"));
		assertEquals(TRUE, f.truth("{\"modVersion\":{}}"));
	}

	@Test
	void modVersionOfAModThatIsNotLoadedIsFalse() {
		assertEquals(FALSE, f.truth("{\"modVersion\":{\"iris\":\"*\"}}"));
		assertEquals(TRUE, f.truth("{\"not\":{\"modVersion\":{\"iris\":\">=1\"}}}"));
	}

	@Test
	void modVersionUnparseablePredicateOrVersionIsUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"modVersion\":{\"sodium\":\">=>=\"}}"));
		assertEquals(UNKNOWN, f.truth("{\"modVersion\":{\"sodium\":\"\"}}"));
		f.versions.put("sodium", "mc26.2-0.9.2-fabric");
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"modVersion\":{\"sodium\":\">=0.6\"}}}"));
		f.versions.remove("sodium");
		assertEquals(UNKNOWN, f.truth("{\"modVersion\":{\"sodium\":\">=0.6\"}}"));
	}

	@Test
	void modVersionWithoutVersionsInTheContextIsUnknownForLoadedMods() {
		EvalContext old = f.context();
		EvalContext ctx = new EvalContext(old.hardware(), old.gpu(), old.tier(), old.goal(), Set.of("sodium"));
		assertEquals(UNKNOWN, ConditionEvaluator.evaluate(RulesLoader.condition("{\"modVersion\":{\"sodium\":\"*\"}}"), ctx));
	}

	@Test
	void mcVersionRange() {
		assertEquals(FALSE, f.truth("{\"mcVersionRange\":\">=26.3\"}"));
		assertEquals(TRUE, f.truth("{\"mcVersionRange\":\"~26.2\"}"));
		assertEquals(TRUE, f.truth("{\"mcVersionRange\":\">=26.2 <26.4\"}"));
		f.hw.mcVersion = "26.3";
		assertEquals(TRUE, f.truth("{\"mcVersionRange\":\">=26.3\"}"));
	}

	@Test
	void mcVersionRangeUnparseableIsUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"mcVersionRange\":\">=>=\"}"));
		assertEquals(UNKNOWN, f.truth("{\"mcVersionRange\":\"\"}"));
		assertEquals(UNKNOWN, f.truth("{\"mcVersionRange\":\"not a range\"}"));
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"mcVersionRange\":\">=26.3 || <26.1\"}}"));
		f.hw.mcVersion = "unknown";
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"mcVersionRange\":\">=26.3\"}}"));
	}
}
