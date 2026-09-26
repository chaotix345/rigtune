package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.profile.ProfileTemplates.TemplateId;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.ProfileTemplate;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.6 over the bundled rules (WS-R's rules-v2 `profileTemplates`, r14 on): every ProfileTemplatesTest
// case again, with the templates coming from the bundled section rather than the built-in copy.
class ProfileTemplatesBundledTest extends ProfileTemplatesTest {
	private static final RulesDocument BUNDLED = RulesLoader.loadBundled();

	@Override
	protected RulesDocument rules() {
		return BUNDLED;
	}

	@Test
	void theBundledSectionDefinesEveryTemplateAndIsTheOneUsed() {
		assertNotNull(BUNDLED.profileTemplates, "the bundled rules-v2.json has a profileTemplates section");
		assertEquals(Arrays.stream(TemplateId.values()).map(TemplateId::id).collect(Collectors.toSet()),
				BUNDLED.profileTemplates.templates.stream().map(t -> t.id).collect(Collectors.toSet()));
		for (TemplateId id : TemplateId.values()) {
			ProfileTemplate used = ProfileTemplates.definition(id, BUNDLED, null);
			assertSame(BUNDLED.profileTemplates.templates.stream().filter(t -> id.id().equals(t.id)).findFirst().orElseThrow(), used, id.id());
			if (used.settings != null) {
				assertTrue(used.settings.stream().allMatch(r -> r.reason != null && !r.reason.isBlank()), id.id() + " entries carry reasons");
				assertTrue(used.settings.stream().allMatch(r -> ShareKeys.managed(r.key)), id.id() + " keys are managed");
			}
		}
	}
}
