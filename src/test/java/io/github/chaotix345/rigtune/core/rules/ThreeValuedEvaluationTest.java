package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.TRUE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeValuedEvaluationTest {
	private final EvalFixture f = new EvalFixture();

	@Test
	void truthTables() {
		assertEquals(UNKNOWN, UNKNOWN.not());
		assertEquals(FALSE, TRUE.not());
		assertEquals(FALSE, UNKNOWN.and(FALSE));
		assertEquals(UNKNOWN, UNKNOWN.and(TRUE));
		assertEquals(TRUE, UNKNOWN.or(TRUE));
		assertEquals(UNKNOWN, UNKNOWN.or(FALSE));
	}

	@Test
	void nullConditionIsTrue() {
		assertEquals(TRUE, ConditionEvaluator.evaluate(null, f.context()));
	}

	@Test
	void unknownKeyAtTopLevelIsUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"meshShaders\":true}"));
		assertEquals(UNKNOWN, f.truth("{\"always\":true,\"meshShaders\":true}"));
		assertFalse(f.matches("{\"always\":true,\"meshShaders\":true}"));
	}

	@Test
	void unknownKeyInsideNotIsUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"meshShaders\":true}}"));
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"tierAtLeast\":5,\"meshShaders\":true}}"));
	}

	@Test
	void unknownKeyInsideAnyOfPoisonsEvenWithATrueSibling() {
		assertEquals(UNKNOWN, f.truth("{\"anyOf\":[{\"always\":true},{\"meshShaders\":true}]}"));
	}

	@Test
	void deeplyNestedUnknownKeyPoisons() {
		assertEquals(UNKNOWN, f.truth("{\"tierAtLeast\":1,\"anyOf\":[{\"not\":{\"anyOf\":[{\"not\":{\"x\":1}}]}}]}"));
	}

	@Test
	void explicitNullPoisons() {
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"gpuVendor\":null}}"));
	}

	@Test
	void notOfUnknownValueIsUnknown() {
		f.noGpuInfo();
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"gpuModelMatches\":\"(?i)rtx\"}}"));
	}

	@Test
	void unknownFlagInsideNotIsUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"flags\":[\"mesh-shaders\"]}}"));
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"mesh-shaders\"]}"));
	}

	@Test
	void knownAbsentFlagDecidesAnAndOverAnUnknownFlag() {
		assertEquals(FALSE, f.truth("{\"flags\":[\"shaders-enabled\",\"mesh-shaders\"]}"));
		f.hw.flags = Set.of("shaders-enabled");
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"shaders-enabled\",\"mesh-shaders\"]}"));
	}

	@Test
	void sodiumWorkaroundFlagsAreInTheVocabulary() {
		assertEquals(FALSE, f.truth("{\"flags\":[\"sodium-workaround:SOMETHING_NEW\"]}"));
		assertEquals(TRUE, f.truth("{\"not\":{\"flags\":[\"backend-vulkan\"]}}"));
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"sodium-workaround:\"]}"));
	}

	@Test
	void backendFlagIsUnknownWhenTheBackendIs() {
		f.noGpuInfo();
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"backend-vulkan\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"flags\":[\"backend-vulkan\"]}}"));
		f.hw.gpu = null;
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"flags\":[\"backend-vulkan\"]}}"));
		f.hw.flags = Set.of("backend-vulkan");
		assertEquals(TRUE, f.truth("{\"flags\":[\"backend-vulkan\"]}"));
	}

	@Test
	void anyOfIsTrueIfABranchIsTrueDespiteUnknownValues() {
		f.hw.ramMb = -1;
		assertEquals(TRUE, f.truth("{\"anyOf\":[{\"ramMbAtLeast\":1},{\"tierAtLeast\":3}]}"));
	}

	@Test
	void anyOfWithFalseAndUnknownIsUnknown() {
		f.hw.ramMb = -1;
		assertEquals(UNKNOWN, f.truth("{\"anyOf\":[{\"ramMbAtLeast\":1},{\"tierAtLeast\":5}]}"));
	}

	@Test
	void emptyAnyOfIsFalse() {
		assertEquals(FALSE, f.truth("{\"anyOf\":[]}"));
	}

	@Test
	void andIsFalseWhenAnyPartIsFalseEvenIfAnotherIsUnknown() {
		f.hw.ramMb = -1;
		assertEquals(FALSE, f.truth("{\"tierAtLeast\":5,\"ramMbAtLeast\":1}"));
		assertEquals(TRUE, f.truth("{\"not\":{\"tierAtLeast\":5,\"ramMbAtLeast\":1}}"));
		assertEquals(UNKNOWN, f.truth("{\"tierAtLeast\":3,\"ramMbAtLeast\":1}"));
	}

	@Test
	void unknownRamVramRefreshAreUnknown() {
		f.hw.ramMb = -1;
		f.hw.gpu = new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", "1", GraphicsBackend.OPENGL, -1);
		f.hw.display = new DisplayInfo(2560, 1440, -1, true);
		assertEquals(UNKNOWN, f.truth("{\"ramMbAtMost\":16000}"));
		assertEquals(UNKNOWN, f.truth("{\"vramMbAtLeast\":1}"));
		assertEquals(UNKNOWN, f.truth("{\"refreshRateAtLeast\":30}"));
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"refreshRateAtLeast\":30}}"));
		f.hw.display = null;
		assertEquals(UNKNOWN, f.truth("{\"refreshRateAtLeast\":30}"));
	}

	@Test
	void noGpuInfoMakesVendorIntegratedAndBackendUnknown() {
		f.noGpuInfo();
		assertEquals(UNKNOWN, f.truth("{\"gpuVendor\":[\"nvidia\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"gpuVendor\":[\"nvidia\"]}}"));
		assertEquals(TRUE, f.truth("{\"gpuVendor\":[\"unknown\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"gpuIntegrated\":false}"));
		assertEquals(UNKNOWN, f.truth("{\"backend\":[\"opengl\"]}"));
		f.hw.gpu = null;
		assertEquals(UNKNOWN, f.truth("{\"backend\":[\"opengl\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"vramMbAtLeast\":1}"));
	}

	@Test
	void otherVendorIsAKnownFact() {
		f.gpu = new GpuClass(GpuVendor.OTHER, false, 2, null);
		assertEquals(FALSE, f.truth("{\"gpuVendor\":[\"nvidia\"]}"));
		assertEquals(TRUE, f.truth("{\"gpuVendor\":[\"other\"]}"));
	}

	@Test
	void outOfVocabularyValuesAreUnknown() {
		assertEquals(UNKNOWN, f.truth("{\"gpuVendor\":[\"matrox\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"backend\":[\"metal\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"os\":[\"freebsd\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"os\":[\"\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"goal\":[\"extreme\"]}"));
	}

	@Test
	void knownMatchingEntryWinsOverOutOfVocabularyEntry() {
		assertEquals(TRUE, f.truth("{\"gpuVendor\":[\"amd\",\"matrox\"]}"));
		assertEquals(TRUE, f.truth("{\"goal\":[\"performance\",\"extreme\"]}"));
		assertEquals(UNKNOWN, f.truth("{\"goal\":[\"quality\",\"extreme\"]}"));
		assertEquals(FALSE, f.truth("{\"goal\":[\"quality\",\"balanced\"]}"));
	}

	@Test
	void blankOsIsUnknown() {
		f.hw.os = " ";
		assertEquals(UNKNOWN, f.truth("{\"os\":[\"windows\"]}"));
	}

	@Test
	void matchesIsTrueOnlyForTrue() {
		f.hw.ramMb = -1;
		assertTrue(f.matches("{\"tierAtLeast\":3}"));
		assertFalse(f.matches("{\"ramMbAtLeast\":1}"));
		assertFalse(f.matches("{\"not\":{\"ramMbAtLeast\":1}}"));
	}
}
