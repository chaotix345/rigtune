package io.github.chaotix345.rigtune.core.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// docs/v0.4/SPEC.md C1/2j: Report's optional tierBasis (the Report itself isn't persisted).
class TierBasisTest {
	private static final TierBasis BASIS = new TierBasis(
			new TierBasis.Gpu(4, TierBasis.Basis.TABLE_MATCH, "(?i)rx\\s*7[89]00", GpuVendor.AMD, false),
			new TierBasis.Cpu(5, TierBasis.Basis.FALLBACK_ESTIMATE, null, 16, -1),
			new TierBasis.Memory(5, TierBasis.Basis.TABLE_MATCH, 6144));

	@Test
	void theOldConstructorLeavesItNull() {
		Report report = new Report(null, null, null, Goal.BALANCED, List.of(), 13, "bundled", false, null);
		assertNull(report.tierBasis());
		Report with = new Report(null, null, null, Goal.BALANCED, List.of(), 13, "bundled", false, null, BASIS);
		assertSame(BASIS, with.tierBasis());
	}

	@Test
	void itIsPlainDataThatRoundTripsThroughGson() {
		Gson gson = new Gson();
		assertEquals(BASIS, gson.fromJson(gson.toJson(BASIS), TierBasis.class));
	}
}
