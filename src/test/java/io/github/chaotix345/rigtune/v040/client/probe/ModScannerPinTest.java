package io.github.chaotix345.rigtune.v040.client.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4i, AC4i.1: RigTune 0.4.0's ModScanner (the pinned copy next to this test) lists rigtune itself, so the
// released client's mod-version map has RigTune's own version for the old-client warning's modVersion condition. Its only
// filter, skip(), is the same in 0.2.0 and 0.3.0 (`git diff v0.2.0 v0.4.0 -- .../client/probe/ModScanner.java` only adds
// loadedIds()), and no released RealController drops rigtune from the scanned list before Recommender.recommend.
class ModScannerPinTest {
	@Test
	void theReleasedScannerKeepsRigTuneAndSkipsOnlyBuiltIns() {
		assertFalse(ModScanner.skip("rigtune", "fabric"));
		assertFalse(ModScanner.skip("sodium", "fabric"));
		for (String builtin : new String[] {"minecraft", "java", "fabricloader", "mixinextras"}) {
			assertTrue(ModScanner.skip(builtin, "fabric"), builtin);
		}
		assertTrue(ModScanner.skip("anything", "builtin"));
	}
}
