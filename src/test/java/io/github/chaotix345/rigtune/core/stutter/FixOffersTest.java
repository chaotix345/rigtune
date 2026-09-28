package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.profile.EffectiveSettings;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// AC5.3 (FixOffersTest): the three layers (the advice fired, the client floor, the rules' evidence) and the setting
// precondition. Each failing condition alone blocks the offer; all passing gives exactly one Offer with the right
// from/to/now. The evidence here uses the 0.4 share keys; causeSpikesAtLeast's own cases are FixEvidenceTest's.
class FixOffersTest {
	private static final String RD = "vanilla.renderDistance";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final String SODIUM = "{\"adviceId\": \"stutter-sodium-defer\", \"requires\": [\"stutter-fix\"],"
			+ " \"evidence\": {\"stutterShareAtLeast\": {\"chunkBuild\": 40}}, \"set\": {\"key\": \"" + DEFER + "\", \"value\": \"ALWAYS\"}}";
	private static final String CHUNKS = "{\"adviceId\": \"stutter-chunk-loading\", \"requires\": [\"stutter-fix\"],"
			+ " \"evidence\": {\"stutterShareAtLeast\": {\"chunkLoad\": 40}}, \"set\": {\"key\": \"" + RD + "\", \"step\": -2, \"min\": 6}}";

	// One evaluation's inputs, each changeable on its own.
	private static final class Case {
		RulesDocument rules;
		Set<String> fired = new HashSet<>(Set.of("stutter-sodium-defer", "stutter-chunk-loading"));
		StutterReport report = FixGateTest.report(StutterReport.MONITOR, 400, 20);
		Map<String, Double> claimed = new HashMap<>(Map.of("chunkBuild", 50.0, "chunkLoad", 45.0));
		Set<String> unmeasured = new HashSet<>(Set.of(Attributor.RENDER));
		Map<String, Integer> dominated = new HashMap<>();
		Map<String, String> settings = new LinkedHashMap<>(Map.of(RD, "12", DEFER, "ZERO_FRAMES"));
		Set<String> mods = new HashSet<>(Set.of("sodium"));
		ServerLimits live;
		boolean excluded;
		boolean busy;
		boolean writable = true;

		Case() throws IOException {
			rules = FixSpecTest.rules(SODIUM, CHUNKS);
		}

		Map<String, FixOffer> run() {
			return run(new SettingsSnapshot(settings));
		}

		Map<String, FixOffer> run(SettingsSnapshot effective) {
			StutterFacts facts = new StutterFacts(claimed, Map.of(), 0, 0, 0, null, null, null, 4.0, "g1", true, unmeasured, dominated);
			EvalContext ctx = StutterAdvisor.context(rules, Fixtures.userRig().build(), Fixtures.mods(mods.toArray(String[]::new)), effective, Goal.BALANCED,
					facts);
			return FixOffers.evaluate(FixSpec.of(rules), fired, report, excluded, ctx, effective, mods, live, busy, writable);
		}
	}

	// The bundled rules (r17's seeds): the Sodium fix needs 40 % of the lost time claimed as chunk building AND 5 spikes it
	// dominated; the render-distance fix the same for chunk loading.
	@Test
	void theBundledSeedsNeedTheDominatedSpikesToo() throws IOException {
		Case c = new Case();
		c.rules = io.github.chaotix345.rigtune.core.rules.RulesLoader.loadBundled();
		c.claimed.put("chunkBuild", 40.0);
		c.dominated.put("chunkBuild", 5);
		c.dominated.put("chunkLoad", 4);
		Map<String, FixOffer> offers = c.run();
		assertEquals(new FixOffer.Offer("stutter-sodium-defer", DEFER, "ZERO_FRAMES", "ALWAYS", false), offers.get("stutter-sodium-defer"));
		assertEquals(notYet(FixOffer.Reason.EVIDENCE, "stutter-chunk-loading"), offers.get("stutter-chunk-loading"), "4 dominated spikes");
		c.dominated.put("chunkBuild", 4);
		assertEquals(notYet(FixOffer.Reason.EVIDENCE, "stutter-sodium-defer"), c.run().get("stutter-sodium-defer"));
		c.dominated.put("chunkBuild", 9);
		c.claimed.put("chunkBuild", 39.0);
		assertEquals(notYet(FixOffer.Reason.EVIDENCE, "stutter-sodium-defer"), c.run().get("stutter-sodium-defer"), "39 %");
	}

	private static FixOffer.NotYet notYet(FixOffer.Reason reason, String adviceId, String... args) {
		return new FixOffer.NotYet(adviceId, reason, List.of(args));
	}

	@Test
	void allPassingGivesOneOfferPerFiredAdvice() throws IOException {
		Case c = new Case();
		Map<String, FixOffer> offers = c.run();
		assertEquals(List.of("stutter-sodium-defer", "stutter-chunk-loading"), List.copyOf(offers.keySet()));
		assertEquals(new FixOffer.Offer("stutter-sodium-defer", DEFER, "ZERO_FRAMES", "ALWAYS", false), offers.get("stutter-sodium-defer"));
		assertEquals(new FixOffer.Offer("stutter-chunk-loading", RD, "12", "10", true), offers.get("stutter-chunk-loading"));
		c.fired = Set.of("stutter-chunk-loading");
		assertEquals(Map.of("stutter-chunk-loading", new FixOffer.Offer("stutter-chunk-loading", RD, "12", "10", true)), c.run());
	}

	@Test
	void noRulesNoOffers() throws IOException {
		Case c = new Case();
		c.rules = FixSpecTest.rules();
		assertEquals(Map.of(), c.run());
	}

	// Silent: no row at all (the advice's own text still applies).
	@Test
	void theAdviceNotFiredOrTheSettingNotApplicableOfferNothing() throws IOException {
		Case c = new Case();
		c.fired = Set.of("stutter-chunk-loading");
		assertEquals(Set.of("stutter-chunk-loading"), c.run().keySet(), "advice not fired");
		c = new Case();
		c.settings.remove(DEFER);
		assertEquals(Set.of("stutter-chunk-loading"), c.run().keySet(), "the key isn't in this instance");
		c = new Case();
		c.mods = Set.of();
		assertEquals(Set.of("stutter-chunk-loading"), c.run().keySet(), "Sodium isn't loaded");
		c = new Case();
		c.settings.put(DEFER, "ALWAYS");
		c.settings.put(RD, "6");
		assertEquals(Map.of(), c.run(), "already the target (and RD at the rule's floor)");
	}

	// Staged ops count: an earlier Apply in this start already staged Deferred, so there's nothing to offer.
	@Test
	void aStagedOpAlreadySettingTheTargetOffersNothing() throws IOException {
		Case c = new Case();
		Path sodiumFile = Path.of("config", "sodium-options.json");
		SettingsSnapshot effective = EffectiveSettings.of(new SettingsSnapshot(c.settings),
				List.of(PendingActions.Op.patchJson(sodiumFile, Map.of("performance.chunk_build_defer_mode", "ALWAYS"))), Map.of("sodium.", sodiumFile));
		assertEquals("ALWAYS", effective.get(DEFER));
		assertEquals(Set.of("stutter-chunk-loading"), c.run(effective).keySet());
	}

	@Test
	void theFloorsReasons() throws IOException {
		Case c = new Case();
		c.report = FixGateTest.report(StutterReport.MONITOR, 400, 7);
		assertEquals(notYet(FixOffer.Reason.LENGTH, "stutter-chunk-loading", "5:00", "8", "6:40", "7"), c.run().get("stutter-chunk-loading"));
		c.report = FixGateTest.report(StutterReport.MONITOR, 299, 20);
		assertEquals(notYet(FixOffer.Reason.LENGTH, "stutter-chunk-loading", "5:00", "8", "4:59", "20"), c.run().get("stutter-chunk-loading"));
		c = new Case();
		c.report = FixGateTest.report(StutterReport.BENCHMARK, 400, 20);
		assertEquals(notYet(FixOffer.Reason.BENCHMARK, "stutter-sodium-defer"), c.run().get("stutter-sodium-defer"));
		c = new Case();
		c.excluded = true;
		assertEquals(notYet(FixOffer.Reason.EXCLUDED, "stutter-chunk-loading"), c.run().get("stutter-chunk-loading"));
		c = new Case();
		c.busy = true;
		assertEquals(notYet(FixOffer.Reason.BUSY, "stutter-sodium-defer"), c.run().get("stutter-sodium-defer"));
		c = new Case();
		c.writable = false;
		assertEquals(notYet(FixOffer.Reason.STORE, "stutter-chunk-loading"), c.run().get("stutter-chunk-loading"));
	}

	@Test
	void evidenceFalseOrUnknownNeverOffers() throws IOException {
		Case c = new Case();
		c.claimed.put("chunkBuild", 39.0);
		assertEquals(notYet(FixOffer.Reason.EVIDENCE, "stutter-sodium-defer"), c.run().get("stutter-sodium-defer"));
		c = new Case();
		c.unmeasured.add(Attributor.CHUNK_BUILD);
		assertEquals(notYet(FixOffer.Reason.EVIDENCE, "stutter-sodium-defer"), c.run().get("stutter-sodium-defer"));
		assertEquals(FixOffer.Offer.class, c.run().get("stutter-chunk-loading").getClass(), "the other fix's evidence still holds");
	}

	// Only on a LAN guest, a Realm or a remote server: one that already sends no more than the target makes a shorter
	// render distance change nothing. Singleplayer (incl. an Open-to-LAN host) never blocks.
	@Test
	void aServerSendingAtMostTheTargetBlocksOnlyTheRenderDistanceFix() throws IOException {
		Case c = new Case();
		for (ServerLimits.Kind kind : List.of(ServerLimits.Kind.REMOTE, ServerLimits.Kind.REALM, ServerLimits.Kind.LAN_GUEST)) {
			c.live = new ServerLimits(10, 10, kind, 0);
			assertEquals(notYet(FixOffer.Reason.SERVER, "stutter-chunk-loading", "10"), c.run().get("stutter-chunk-loading"), kind.name());
			assertEquals(FixOffer.Offer.class, c.run().get("stutter-sodium-defer").getClass(), kind.name());
			c.live = new ServerLimits(8, 10, kind, 0);
			assertEquals(notYet(FixOffer.Reason.SERVER, "stutter-chunk-loading", "8"), c.run().get("stutter-chunk-loading"), kind.name());
			c.live = new ServerLimits(11, 10, kind, 0);
			assertEquals(FixOffer.Offer.class, c.run().get("stutter-chunk-loading").getClass(), kind.name());
		}
		c.live = new ServerLimits(8, 8, ServerLimits.Kind.SINGLEPLAYER, 0);
		assertEquals(FixOffer.Offer.class, c.run().get("stutter-chunk-loading").getClass());
		c.live = new ServerLimits(0, 0, ServerLimits.Kind.REMOTE, 0);
		assertEquals(FixOffer.Offer.class, c.run().get("stutter-chunk-loading").getClass(), "no view distance seen yet");
	}
}
