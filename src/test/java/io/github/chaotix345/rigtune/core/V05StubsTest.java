package io.github.chaotix345.rigtune.core;

import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/PLAN.md contracts item 8: the type stubs answer 0.4's behaviour until their owners fill them in.
class V05StubsTest {
	@Test
	void theModFilesPolicyStubIsRigTuneEverywhere() {
		assertEquals(List.of(ModFilesPolicy.RIGTUNE, ModFilesPolicy.LAUNCHER, ModFilesPolicy.PENDING), Arrays.asList(ModFilesPolicy.values()));
		List<LauncherInfo> launchers = new ArrayList<>();
		launchers.add(null);
		Arrays.stream(Launcher.values()).map(LauncherInfo::of).forEach(launchers::add);
		for (LauncherInfo launcher : launchers) {
			for (InstanceEvidence evidence : List.of(InstanceEvidence.NONE, new InstanceEvidence(true))) {
				for (boolean optIn : new boolean[]{false, true}) {
					assertEquals(ModFilesPolicy.RIGTUNE, ModFilesPolicy.of(launcher, evidence, optIn), launcher + " " + evidence + " " + optIn);
					for (ModFilesPolicy policy : ModFilesPolicy.values()) {
						assertNull(LauncherModText.guideLine(policy, launcher, optIn));
					}
				}
			}
		}
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
