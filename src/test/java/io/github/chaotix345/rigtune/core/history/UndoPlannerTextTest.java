package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.TextChecks;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.3/SPEC.md item 9, G-M2: every reason the Undo screen can show has a rigtune.undo.reason.* key whose English is
// the constant the planner (and the game tests) use; the plans themselves are checked after every UndoPlannerTest.
class UndoPlannerTextTest {
	@Test
	void everyReasonConstantIsAnEnUsValue() throws IllegalAccessException {
		Map<String, String> reasons = new TreeMap<>();
		TextChecks.english().forEach((key, value) -> {
			if (key.startsWith("rigtune.undo.reason.")) {
				reasons.put(value, key);
			}
		});
		int constants = 0;
		for (Field field : UndoPlanner.class.getDeclaredFields()) {
			if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
				continue;
			}
			field.setAccessible(true);
			String value = (String) field.get(null);
			if (!value.contains(" ")) {
				continue;
			}
			constants++;
			assertFalse(reasons.get(value) == null, field.getName() + " has no rigtune.undo.reason.* key: " + value);
		}
		assertEquals(reasons.size(), constants, "one constant per rigtune.undo.reason.* key");
	}

	@Test
	void theOldConstructorsKeepTheirStringsAsLiterals() {
		UndoPlan.Item item = new UndoPlan.Item("Disable x.jar", UndoPlan.Action.REVERT, null, true);
		assertEquals(Text.literal("Disable x.jar"), item.descriptionText());
		assertNull(item.reasonText());
		UndoPlan.Item skipped = new UndoPlan.Item("d", UndoPlan.Action.SKIP, "why", false, List.of("c"), List.of());
		assertEquals(Text.literal("why"), skipped.reasonText());
		UndoPlan.Item made = UndoPlan.Item.of(Text.of("rigtune.undo.item.disable", "Disable %s", "x.jar"), UndoPlan.Action.REVERT, null, true,
				List.of("c"), List.of());
		assertEquals("Disable x.jar", made.description());
		assertNull(made.reason());
	}
}
