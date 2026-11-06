package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #45: the field case: Max FPS uncaps the frame rate (vanilla.maxFps 170 -> 260) and the main list offers capping it again,
// ticked. While the profile stands that row is unticked with a reason naming it; other keys keep their ticks.
class ProfileHoldTest {
	private static final String MAX_FPS = "vanilla.maxFps";
	private static final String RADIUS = "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius";
	private static final Text MAX_FPS_NAME = Text.of("rigtune.profile.template.max_fps", "Max FPS");

	private static Recommendation set(String key, String current, String target) {
		return Recommendation.of("set:" + key, Category.SETTING, Impact.MEDIUM, Text.literal(key + " " + current + " -> " + target),
				Text.literal("The rules' reason."), new Action.SetSetting(key, current, target), true);
	}

	private static Report report(Recommendation... recs) {
		return new Report(null, null, null, null, List.of(recs), 22, "bundled", false, Instant.parse("2026-11-07T00:00:00Z"));
	}

	private static JournalEntry maxFpsSwitch() {
		return new JournalEntry("e-1", "2026-11-07T00:00:00Z", JournalEntry.APPLY, "0.5.2", "26.2", null, List.of(
				JournalChange.setting(MAX_FPS, "170", "260", JournalChange.APPLIED, null),
				JournalChange.setting(RADIUS, "256", "160", JournalChange.STAGED, "op-1"),
				JournalChange.setting("vanilla.particles", "0", "1", JournalChange.REVERTED, null)));
	}

	private static ProfileHold.Active active() {
		return new ProfileHold.Active(MAX_FPS_NAME, ProfileHold.holds(maxFpsSwitch()));
	}

	@Test
	void theSwitchsAppliedAndStagedSettingsAreHeld() {
		assertEquals(List.of(new ProfileHold.Hold(MAX_FPS, "260", false), new ProfileHold.Hold(RADIUS, "160", true)), ProfileHold.holds(maxFpsSwitch()));
		assertEquals(List.of(), ProfileHold.holds(null));
	}

	@Test
	void cappingTheUncappedFrameRateAgainStartsUnticked() {
		Recommendation cap = set(MAX_FPS, "260", "170");
		Recommendation r = ProfileHold.apply(report(cap), active()).recommendations().getFirst();
		assertFalse(r.selectedByDefault());
		assertEquals(cap.id(), r.id());
		assertEquals(cap.action(), r.action());
		assertEquals("The rules' reason. Your Max FPS profile sets this on purpose, so it starts unticked; switch to My settings for RigTune's "
				+ "usual suggestions.", r.reasonText().english());
	}

	// A staged switch value isn't in the files yet, so it holds whatever they read; anything else holds only while the
	// key still has the profile's value (changed by hand since, the usual suggestion is back).
	@Test
	void onlyTheProfilesOwnValueIsHeld() {
		Recommendation stagedKey = set(RADIUS, "256", "128");
		assertFalse(ProfileHold.apply(report(stagedKey), active()).recommendations().getFirst().selectedByDefault());
		Report changedSince = report(set(MAX_FPS, "144", "170"));
		assertSame(changedSince, ProfileHold.apply(changedSince, active()));
		Report otherKey = report(set("vanilla.renderDistance", "16", "12"));
		assertSame(otherKey, ProfileHold.apply(otherKey, active()));
		Report reverted = report(set("vanilla.particles", "0", "2"));
		assertSame(reverted, ProfileHold.apply(reverted, active()), "a reverted change holds nothing");
	}

	@Test
	void noProfileHoldsNothing() {
		Report plain = report(set(MAX_FPS, "260", "170"));
		assertSame(plain, ProfileHold.apply(plain, null));
		assertTrue(ProfileHold.apply(plain, null).recommendations().getFirst().selectedByDefault());
	}
}
