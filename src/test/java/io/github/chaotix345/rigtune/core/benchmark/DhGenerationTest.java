package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md RW-6 (AC2B.4, detection): Context.dhGenerating from the benchmark capture's "DH world generation" CPU
// over the recorded sweeps (WS-S's sampler bucket, core-equivalents).
class DhGenerationTest {
	@Test
	void rw6ThresholdTruthTable() {
		assertEquals(Boolean.TRUE, DhGeneration.generating(6.8, true), "the real first run: world generation on most cores");
		assertEquals(Boolean.TRUE, DhGeneration.generating(DhGeneration.MIN_CORES, true), "at the threshold");
		assertEquals(Boolean.FALSE, DhGeneration.generating(DhGeneration.MIN_CORES - 0.01, true));
		assertEquals(Boolean.FALSE, DhGeneration.generating(0.0, true), "DH installed and quiet");
		assertNull(DhGeneration.generating(null, true), "no capture or no sampler: not measured, left out");
		assertNull(DhGeneration.generating(6.8, false), "without Distant Horizons the field is left out");
		assertNull(DhGeneration.generating(null, false));
	}
}
