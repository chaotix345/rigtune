package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierBasis;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.4/SPEC.md 2j (AC2j.1): the tier is an estimate; the header names the lowest estimated component(s), and the
// tooltip what each component's tier rests on (a table match or a fallback estimate).
class TierBasisReportTest {
	private static Report report(Fixtures.Hw hw) {
		return Recommender.recommend(RulesLoader.loadBundled(), hw.build(), Fixtures.mods("sodium"), new SettingsSnapshot(Map.of()), OnlineData.offline(),
				Goal.BALANCED);
	}

	@Test
	void everyTiedComponentIsListed() {
		assertEquals(List.of("gpu", "cpu", "mem"), TierBasis.lowest(new TierResult(5, 5, 5, 5, 5, "gpu")));
		assertEquals(List.of("gpu"), TierBasis.lowest(new TierResult(3, 3, 3, 5, 4, "gpu")));
		assertEquals(List.of("gpu", "cpu"), TierBasis.lowest(new TierResult(4, 4, 4, 4, 5, "gpu")));
		assertEquals(List.of("mem"), TierBasis.lowest(new TierResult(3, 3, 5, 5, 3, "mem")));
		assertEquals(List.of("gpu"), TierBasis.lowest(new TierResult(0, 0, 0, 5, 5, "gpu")));
		assertEquals(List.of(), TierBasis.lowest(null));
	}

	@Test
	void recognisedHardwareIsATableMatch() {
		Report report = report(Fixtures.userRig());

		TierBasis basis = report.tierBasis();
		assertNotNull(basis);
		assertEquals(TierBasis.Basis.TABLE_MATCH, basis.gpu().basis());
		assertEquals(report.gpuClass().matchedPattern(), basis.gpu().matchedPattern());
		assertEquals(report.tier().gpuTier(), basis.gpu().tier());
		assertEquals(TierBasis.Basis.TABLE_MATCH, basis.cpu().basis());
		assertNotNull(basis.cpu().matchedPattern());
		assertEquals(report.tier().cpuTier(), basis.cpu().tier());
		assertEquals(new TierBasis.Memory(report.tier().memTier(), TierBasis.Basis.TABLE_MATCH, 6144), basis.memory());
	}

	@Test
	void unrecognisedHardwareIsAFallbackEstimate() {
		Fixtures.Hw hw = Fixtures.userRig().gpu("Mystery Graphics Co.", "Mystery GPU 9000");
		hw.cpu = new CpuInfo("Mystery CPU 9000", 8, 16, 4000);

		TierBasis basis = report(hw).tierBasis();

		assertEquals(TierBasis.Basis.FALLBACK_ESTIMATE, basis.gpu().basis());
		assertNull(basis.gpu().matchedPattern());
		assertEquals(new TierBasis.Cpu(5, TierBasis.Basis.FALLBACK_ESTIMATE, null, 16, 4000), basis.cpu());
	}
}
