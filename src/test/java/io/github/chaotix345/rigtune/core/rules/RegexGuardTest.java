package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.hardware.CpuClassifier;
import io.github.chaotix345.rigtune.core.hardware.GpuClassifier;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexGuardTest {
	// Still exponential on current JDKs, which memoise simpler forms such as (a+)+b.
	private static final String EVIL = "((a+)+)+b";
	private static final String VICTIM = "a".repeat(40);

	@Test
	void ordinaryPatternsMatchWithinBudget() {
		assertEquals(true, BudgetedChars.find(Pattern.compile("(?i)rtx\\s*40[6-9]0"), "NVIDIA GeForce RTX 4070/PCIe/SSE2", BudgetedChars.DEFAULT_BUDGET));
		assertEquals(false, BudgetedChars.find(Pattern.compile("(?i)radeon"), "NVIDIA GeForce RTX 4070/PCIe/SSE2", BudgetedChars.DEFAULT_BUDGET));
		assertEquals(true, BudgetedChars.find(Pattern.compile("(?i)ryzen\\s+\\d\\s+\\d{4}x3d"), "AMD Ryzen 7 7800X3D 8-Core Processor", BudgetedChars.DEFAULT_BUDGET));
	}

	@Test
	void catastrophicBacktrackingIsCutOff() {
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			assertNull(BudgetedChars.find(Pattern.compile(EVIL), VICTIM, BudgetedChars.DEFAULT_BUDGET));
			RulesDocument.GpuTierRule rule = new RulesDocument.GpuTierRule();
			rule.pattern = EVIL;
			assertFalse(rule.find(VICTIM));
		});
	}

	@Test
	void classifiersTreatAnAbortedMatchAsNoMatch() {
		RulesDocument doc = RulesLoader.parse("""
				{"schemaVersion":1,"revision":1,
				 "gpuTiers":[{"pattern":"((a+)+)+b","tier":1},{"pattern":"(?i)a{10}","tier":4}],
				 "cpuTiers":[{"pattern":"((a+)+)+b","tier":1},{"pattern":"(?i)a{10}","tier":5}]}
				""");
		assertEquals(EVIL, doc.gpuTiers.getFirst().pattern);

		assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
			GpuClass gpu = GpuClassifier.from(doc).classify(new GpuInfo("Unknown", VICTIM, null, null, -1));
			assertEquals(4, gpu.tier());
			assertEquals(5, CpuClassifier.from(doc).classify(new CpuInfo(VICTIM, 8, 8, 4000)));
		});
	}

	@Test
	void overlongPatternsAreDropped() {
		String longest = "a".repeat(RulesDocument.PatternRule.MAX_PATTERN_LENGTH);
		RulesDocument doc = RulesLoader.parse("{\"schemaVersion\":1,\"revision\":1,\"gpuTiers\":[{\"pattern\":\"" + longest + "\",\"tier\":3},"
				+ "{\"pattern\":\"" + longest + "b\",\"tier\":3}],\"cpuTiers\":[{\"pattern\":\"" + longest + "b\",\"tier\":3}]}");

		assertEquals(1, doc.gpuTiers.size());
		assertEquals(longest, doc.gpuTiers.getFirst().pattern);
		assertTrue(doc.cpuTiers.isEmpty());
	}
}
