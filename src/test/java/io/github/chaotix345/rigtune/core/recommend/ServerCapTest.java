package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC8.1 as rewritten by plan review W-H1: RD 16, limit 10 -> no render-distance recommendation from the
// server; RD 6, target 12, limit 10 -> 6 -> 10 with the reason; after disconnect no server reason remains; singleplayer,
// an Open-to-LAN host and stored-only limits never cap; a limit at or above the target changes nothing. X-M3: the golden
// report is unchanged without a connected server.
class ServerCapTest {
	private static final String REASON = "The server sends at most %s chunks.";

	private static ServerLimits remote(int view) {
		return new ServerLimits(view, 5, ServerLimits.Kind.REMOTE, 0);
	}

	private static Recommendation rd(String current, String target) {
		return Recommendation.of("set:vanilla.renderDistance", Category.SETTING, Impact.HIGH,
				Text.of("rigtune.rec.setting.title", "%s: %s → %s", "Render distance", current, target), Text.literal("Tier 4 holds 12 chunks."),
				new Action.SetSetting("vanilla.renderDistance", current, target), true);
	}

	private static Recommendation sim() {
		return Recommendation.of("set:vanilla.simulationDistance", Category.SETTING, Impact.MEDIUM, Text.literal("Simulation distance: 12 → 8"),
				Text.literal("Fewer ticking chunks."), new Action.SetSetting("vanilla.simulationDistance", "12", "8"), true);
	}

	private static Report report(Recommendation... recs) {
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, null), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.BALANCED, List.of(recs), 13, "bundled", false, Instant.EPOCH);
	}

	private static Action.SetSetting set(Report report, String key) {
		return report.recommendations().stream().map(Recommendation::action).filter(a -> a instanceof Action.SetSetting s && s.key().equals(key))
				.map(Action.SetSetting.class::cast).findFirst().orElse(null);
	}

	@Test
	void anIncreaseIsLoweredToTheLimitWithTheReason() {
		Recommendation simulation = sim();
		Report capped = ServerCap.apply(report(rd("6", "12"), simulation), remote(10), null);
		Action.SetSetting rd = set(capped, "vanilla.renderDistance");
		assertEquals("6", rd.currentValue());
		assertEquals("10", rd.newValue());
		Recommendation rec = capped.recommendations().getFirst();
		assertEquals("Render distance: 6 → 10", rec.title());
		assertEquals("Tier 4 holds 12 chunks. The server sends at most 10 chunks.", rec.reason());
		assertTrue(rec.selectedByDefault());
		assertEquals(Impact.HIGH, rec.impact());
		assertSame(simulation, capped.recommendations().get(1), "other recommendations untouched");
		assertEquals("8", set(capped, "vanilla.simulationDistance").newValue(), "simulation distance isn't changed");
	}

	@Test
	void noDecreaseBecauseOfTheServer() {
		assertNull(set(ServerCap.apply(report(rd("16", "20")), remote(10), null), "vanilla.renderDistance"),
				"RD 16, limit 10: the increase to 20 is pointless, and nothing replaces it");
		Report rulesDecrease = report(rd("16", "12"));
		assertSame(rulesDecrease, ServerCap.apply(rulesDecrease, remote(10), null), "the rules' own decrease stays as it is");
		assertNull(set(ServerCap.apply(report(rd("10", "12")), remote(10), null), "vanilla.renderDistance"), "C = L: nothing to raise");
		Action.SetSetting partial = set(ServerCap.apply(report(rd("8", "16")), remote(10), null), "vanilla.renderDistance");
		assertEquals("10", partial.newValue());
	}

	@Test
	void unchangedWhenNotLimiting() {
		Report r = report(rd("6", "12"), sim());
		assertSame(r, ServerCap.apply(r, null, null), "not connected (or disconnected): no cap and no server reason");
		assertSame(r, ServerCap.apply(r, new ServerLimits(4, 4, ServerLimits.Kind.SINGLEPLAYER, 0), null), "singleplayer / LAN host");
		assertSame(r, ServerCap.apply(r, remote(12), null), "a limit at the target");
		assertSame(r, ServerCap.apply(r, remote(16), null), "a limit above the target");
		assertSame(r, ServerCap.apply(r, remote(0), null), "no limit sent");
		assertEquals("10", set(ServerCap.apply(r, new ServerLimits(10, 5, ServerLimits.Kind.LAN_GUEST, 0), null), "vanilla.renderDistance").newValue());
		assertEquals("10", set(ServerCap.apply(r, new ServerLimits(10, 5, ServerLimits.Kind.REALM, 0), null), "vanilla.renderDistance").newValue());
		Report weird = report(rd("far", "12"));
		assertSame(weird, ServerCap.apply(weird, remote(10), null), "a value that isn't a number is left alone");
	}

	@Test
	void afterDisconnectTheRebuiltReportHasNoServerReason() throws IOException {
		RulesDocument rules = RulesLoader.loadBundled();
		Fixtures.Hw hw = Fixtures.userRig();
		SettingsSnapshot settings = new SettingsSnapshot(Map.of("vanilla.renderDistance", "5", "vanilla.simulationDistance", "5"));
		Report built = Recommender.recommend(rules, hw.build(), Fixtures.mods("sodium"), settings, OnlineData.offline(), Goal.QUALITY, "0.4.0", Set.of());
		Action.SetSetting uncapped = set(built, "vanilla.renderDistance");
		assertNotNull(uncapped, "the bundled rules raise RD 5 on this rig");
		assertTrue(Integer.parseInt(uncapped.newValue()) > 6);
		Report connected = ServerCap.apply(built, remote(6), rules);
		assertEquals("6", set(connected, "vanilla.renderDistance").newValue());
		Recommendation rec = connected.recommendations().stream().filter(r -> r.id().equals("set:vanilla.renderDistance")).findFirst().orElseThrow();
		assertTrue(rec.reason().endsWith(REASON.formatted(6)), rec.reason());
		assertTrue(rec.title().endsWith("5 → 6"), rec.title());
		Report disconnected = ServerCap.apply(built, null, rules);
		assertFalse(disconnected.recommendations().stream().anyMatch(r -> r.reason().contains("The server sends")));
		assertEquals(uncapped.newValue(), set(disconnected, "vanilla.renderDistance").newValue());
	}

	// X-M3: without a connected server (or on the player's own world) the main list is exactly recommend()'s output, over
	// the golden report's whole matrix; a remote limit above every target changes nothing either.
	@Test
	void goldenReportUnchangedWithoutAServer() throws IOException {
		RulesDocument rules = RecommenderGoldenReportTest.goldenRules();
		int reports = 0;
		for (Map.Entry<String, Fixtures.Hw> hw : RecommenderGoldenReportTest.hardware().entrySet()) {
			for (List<String> mods : List.of(List.<String>of(), List.of("sodium"), List.of("sodium", "distanthorizons", "iris"))) {
				for (Goal goal : Goal.values()) {
					for (boolean maxed : new boolean[]{false, true}) {
						Report report = Recommender.recommend(rules, hw.getValue().build(), Fixtures.mods(mods.toArray(String[]::new)),
								RecommenderGoldenReportTest.snapshot(mods, maxed), OnlineData.offline(), goal, "0.4.0-dev+mc26.2", Set.of());
						assertSame(report, ServerCap.apply(report, null, rules));
						assertSame(report, ServerCap.apply(report, new ServerLimits(2, 2, ServerLimits.Kind.SINGLEPLAYER, 0), rules));
						assertSame(report, ServerCap.apply(report, remote(64), rules));
						reports++;
					}
				}
			}
		}
		assertTrue(reports > 100);
	}
}
