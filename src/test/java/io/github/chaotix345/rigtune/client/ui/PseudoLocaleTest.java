package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.L10nFixtures;
import io.github.chaotix345.rigtune.core.TextChecks;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// docs/v0.3/SPEC.md AC9.3 (amendment G-M2): rendered through a pseudo-locale that uppercases every en_us.json template,
// the reports' titles and reasons are upper case except their arguments (mod names, versions) and rule text, which
// stays data. The undo plans and the planner/resolver errors get the same check in UndoPlannerTest,
// DownloadPlannerTest, DependencyResolverTest and DownloadErrorTextTest (TextChecks.assertPseudoLocalised).
class PseudoLocaleTest {
	@Test
	void everyReportTextIsTranslatedExceptItsData() {
		for (Report report : L10nFixtures.reports()) {
			for (Recommendation r : report.recommendations()) {
				TextChecks.assertPseudoLocalised(r.titleText(), L10nFixtures.ruleText(), r.id() + " title");
				TextChecks.assertPseudoLocalised(r.reasonText(), L10nFixtures.ruleText(), r.id() + " reason");
			}
		}
	}

	// The check itself: a core-built literal, a missing key and an English fallback that isn't en_us.json's all fail.
	@Test
	void thePseudoLocaleCheckCatchesUntranslatedText() {
		assertThrows(AssertionError.class, () -> TextChecks.assertPseudoLocalised(Text.literal("Install Sodium"), Set.of(), "planted"));
		assertThrows(AssertionError.class, () -> TextChecks.assertPseudoLocalised(Text.of("rigtune.nope", "Nope"), Set.of(), "planted"));
		assertThrows(AssertionError.class,
				() -> TextChecks.assertPseudoLocalised(Text.of("rigtune.rec.install.title", "Instal %s", "Sodium"), Set.of(), "planted"));
		assertThrows(AssertionError.class, () -> TextChecks.assertPseudoLocalised(
				Text.sentences(Text.of("rigtune.rec.alpha", "(alpha build)"), Text.literal("Built by hand.")), Set.of(), "planted"));
		TextChecks.assertPseudoLocalised(Text.of("rigtune.rec.install.title", "Install %s", Text.literal("Sodium")), Set.of(), "fine");
		TextChecks.assertPseudoLocalised(Text.sentences(Text.literal("Rule text."), Text.of("rigtune.rec.alpha", "(alpha build)")), Set.of("Rule text."),
				"fine");
	}

	@Test
	void whatThePseudoLocaleShows() {
		Map<String, Recommendation> recs = L10nFixtures.report().recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r));
		assertEquals("INSTALL Net", TextChecks.pseudo(recs.get("add:net").titleText()));
		assertEquals("Faster net. RIGTUNE DOESN'T ALSO OFFER Noise OR Surface, WHICH CONFLICT WITH IT. (AVAILABILITY NOT CONFIRMED)",
				TextChecks.pseudo(recs.get("add:net").reasonText()));
		assertEquals("VERSION 2.1 IS AVAILABLE (YOU HAVE 2.0). IT ISN'T IN THIS INSTANCE'S MODS FOLDER, SO UPDATE IT IN YOUR LAUNCHER.",
				TextChecks.pseudo(recs.get("update:far").reasonText()));
		assertEquals("Tip", TextChecks.pseudo(recs.get("advice:tip").titleText()));
	}

	// The client shows the language's template where it has the key: the same pseudo-locale as a Minecraft Language.
	@Test
	void theClientShowsTheTranslation() {
		Language previous = Language.getInstance();
		Language.inject(new Language() {
			@Override
			public String getOrDefault(String key, String fallback) {
				String template = TextChecks.english().get(key);
				return template != null ? TextChecks.upper(template) : previous.getOrDefault(key, fallback);
			}

			@Override
			public boolean has(String key) {
				return TextChecks.english().containsKey(key) || previous.has(key);
			}

			@Override
			public boolean isDefaultRightToLeft() {
				return false;
			}

			@Override
			public FormattedCharSequence getVisualOrder(FormattedText text) {
				return previous.getVisualOrder(text);
			}
		});
		try {
			for (Report report : L10nFixtures.reports()) {
				for (Recommendation r : report.recommendations()) {
					for (Text text : new Text[]{r.titleText(), r.reasonText()}) {
						assertEquals(TextChecks.pseudo(text), Texts.component(text).getString(), r.id());
					}
				}
			}
		} finally {
			Language.inject(previous);
		}
	}
}
