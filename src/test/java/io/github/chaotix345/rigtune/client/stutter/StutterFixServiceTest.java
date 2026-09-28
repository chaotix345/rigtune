package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 5 (C20): StutterFixService's pure parts; the flow runs in StutterFixGameTest.
class StutterFixServiceTest {
	// A fix is one SetSetting, ticked, whose reason names the advice it came from (History and the preview show it).
	@Test
	void anOfferIsOneSetSettingNamingItsAdvice() {
		RulesDocument rules = RulesLoader.loadBundled();
		FixOffer.Offer offer = new FixOffer.Offer("stutter-chunk-loading", "vanilla.renderDistance", "12", "10", true);
		Recommendation rec = StutterFixService.recommendation(offer, rules);
		assertEquals("stutterfix:stutter-chunk-loading", rec.id());
		assertEquals(Category.SETTING, rec.category());
		assertEquals(new Action.SetSetting("vanilla.renderDistance", "12", "10"), rec.action());
		assertTrue(rec.selectedByDefault());
		String title = rules.stutterAdvice.stream().filter(a -> a.id.equals("stutter-chunk-loading")).findFirst().orElseThrow().title;
		assertEquals("Stutter Doctor: " + title, rec.reasonText().english());
		assertTrue(rec.titleText().english().contains("12") && rec.titleText().english().contains("10"), rec.titleText().english());
		// Without rules the advice id stands in for its title.
		assertEquals("Stutter Doctor: stutter-chunk-loading", StutterFixService.recommendation(offer, null).reasonText().english());
	}

	// V05ServicesTest's rule: without a controller, holds() reads no file and holds nothing.
	@Test
	void withoutAControllerNothingIsReadOrHeld() {
		StutterFixService service = new StutterFixService(null);
		assertEquals(List.of(), service.holds());
		assertEquals(null, service.tracked());
	}
}
