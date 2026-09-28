package io.github.chaotix345.rigtune.core;

import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/PLAN.md contracts item 8: the type stubs answer 0.4's behaviour until their owners fill them in.
class V05StubsTest {
	// The policy table itself is WS-L1's (ModFilesPolicyTest, LauncherModTextTest); the three values stay.
	@Test
	void theModFilesPolicyValues() {
		assertEquals(List.of(ModFilesPolicy.RIGTUNE, ModFilesPolicy.LAUNCHER, ModFilesPolicy.PENDING), Arrays.asList(ModFilesPolicy.values()));
	}

	@Test
	void theViewsAndRecords() {
		assertEquals(List.of(FirstRun.Status.UNKNOWN, FirstRun.Status.NEW, FirstRun.Status.RETURNING), Arrays.asList(FirstRun.Status.values()));
		assertEquals(TryItView.Stage.NONE, TryItView.EMPTY.stage());
		assertEquals(TryItView.EMPTY, new TryItView(null));
		assertEquals(ServerProfilesView.State.NOT_CONNECTED, ServerProfilesView.EMPTY.state());
		assertEquals(List.of(), ServerProfilesView.EMPTY.rows());
		assertTrue(ServerProfilesView.EMPTY.writable());
		FixOffer offer = new FixOffer.Offer("stutter-chunk-loading", "vanilla.renderDistance", "12", "10", true);
		FixOffer notYet = new FixOffer.NotYet("stutter-sodium-defer", FixOffer.Reason.LENGTH, null);
		assertEquals("stutter-chunk-loading", offer.adviceId());
		assertEquals(List.of(), ((FixOffer.NotYet) notYet).args());
	}
}
