package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpuClassifierTest {
	private final CpuClassifier bundled = CpuClassifier.from(RulesLoader.loadBundled());

	@Test
	void knownPartsUseRules() {
		assertEquals(5, bundled.classify(new CpuInfo("AMD Ryzen 7 7800X3D 8-Core Processor", 8, 16, 4201)));
		assertEquals(5, bundled.classify(new CpuInfo("AMD Ryzen 5 7600X 6-Core Processor", 6, 12, 4701)));
		assertEquals(1, bundled.classify(new CpuInfo("Intel(R) Celeron(R) N4020 CPU @ 1.10GHz", 2, 2, 1101)));
		assertEquals(4, bundled.classify(new CpuInfo("Apple M2", 8, 8, -1)));
	}

	// docs/research/v0.3/hardware-tiers.md §4: the generation-agnostic rows cover the 2025/2026 parts.
	@Test
	void newPartsUseTheExistingRows() {
		assertEquals(5, bundled.classify(new CpuInfo("AMD Ryzen 9 9950X3D 16-Core Processor", 16, 32, 5700)));
		assertEquals(5, bundled.classify(new CpuInfo("AMD Ryzen 9 9900X3D 12-Core Processor", 12, 24, 5500)));
		assertEquals(5, bundled.classify(new CpuInfo("AMD Ryzen 7 9800X3D 8-Core Processor", 8, 16, 5200)));
		assertEquals(5, bundled.classify(new CpuInfo("AMD Ryzen 7 9800X3D 8-Core Processor", 8, 8, -1)), "the X3D row, not the formula");
		assertEquals(5, bundled.classify(new CpuInfo("Intel(R) Core(TM) Ultra 9 285K", 24, 24, 5700)));
		assertEquals(5, bundled.classify(new CpuInfo("Intel(R) Core(TM) Ultra 5 245K", 14, 14, -1)));
	}

	@Test
	void lunarLakeIsNotAKSeriesPart() {
		assertEquals(CpuClassifier.formula(8, 4800), bundled.classify(new CpuInfo("Intel(R) Core(TM) Ultra 7 258V", 8, 8, 4800)));
		assertEquals(3, bundled.classify(new CpuInfo("Intel(R) Core(TM) Ultra 7 258V", 8, 8, 4800)));
	}

	@Test
	void unknownPartsUseFormula() {
		assertEquals(2, bundled.classify(new CpuInfo("Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz", 4, 8, 1800)));
		assertEquals(3, bundled.classify(new CpuInfo("Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz", 4, 8, -1)));
		assertEquals(4, bundled.classify(new CpuInfo("AMD Ryzen 5 5600 6-Core Processor", 6, 12, 3501)));
	}

	@Test
	void formulaFollowsSchema() {
		assertEquals(1, CpuClassifier.formula(2, -1));
		assertEquals(2, CpuClassifier.formula(3, -1));
		assertEquals(2, CpuClassifier.formula(4, -1));
		assertEquals(3, CpuClassifier.formula(8, -1));
		assertEquals(4, CpuClassifier.formula(12, -1));
		assertEquals(5, CpuClassifier.formula(16, -1));
		assertEquals(4, CpuClassifier.formula(16, 2400));
		assertEquals(5, CpuClassifier.formula(16, 2500));
		assertEquals(1, CpuClassifier.formula(2, 1000));
		assertEquals(CpuClassifier.UNKNOWN_CORES_TIER, CpuClassifier.formula(0, -1));
	}

	@Test
	void noRulesMeansFormula() {
		assertEquals(3, new CpuClassifier(List.of()).classify(new CpuInfo("AMD Ryzen 7 7800X3D 8-Core Processor", 8, 8, -1)));
	}
}
