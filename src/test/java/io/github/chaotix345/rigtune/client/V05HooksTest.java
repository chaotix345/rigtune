package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.awareness.OutsideChanges;
import io.github.chaotix345.rigtune.client.undo.RefusedDisables;
import io.github.chaotix345.rigtune.client.undo.StaleGroups;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import io.github.chaotix345.rigtune.core.stutter.FixHold;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/PLAN.md contracts item 13: until their owners fill them in, the extension points' stubs change nothing (the
// report, the selection and the Apply status stay 0.4's), and the dispatch runs them in the planned order.
class V05HooksTest {
	private static final Recommendation ADD = new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Add Lithium", "r",
			new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true);

	@Test
	void theStubsAreIdentityAndNoOps() throws IOException {
		Report report = new Report(null, null, null, null, List.of(ADD), 16, "bundled", false, Instant.parse("2026-09-27T00:00:00Z"));
		assertSame(report, FixHold.apply(report, List.of(new FixHold.Hold("vanilla.renderDistance", "12", "10", "2026-09-27"))));
		for (ModFilesPolicy policy : ModFilesPolicy.values()) {
			assertSame(report, LauncherModAdvice.apply(report, policy, LauncherInfo.UNKNOWN));
			List<Recommendation> selected = List.of(ADD);
			assertSame(selected, LauncherModAdvice.guard(selected, policy));
		}
		List<Component> parts = new ArrayList<>(List.of(Component.literal("1 setting applied.")));
		V05Hooks.ApplyFacts facts = new V05Hooks.ApplyFacts("entry-1", List.of(ADD), Set.of(), 1, 0, 0, false, 1);
		RefusedDisables.afterApply(facts, parts);
		OutsideChanges.afterApply(null, facts, parts);
		assertEquals(1, parts.size());
		assertEquals(List.of(), StaleGroups.drop(null, Set.of("lithium")));
		assertNull(StaleGroups.status(List.of(), List.of()));
	}

	// The dispatch order is the plan's: FixHold before LauncherModAdvice; the refused-disable count, then the Modrinth App
	// line, then FirstRunService.applied last.
	@Test
	void theDispatchOrder() throws IOException {
		String hooks = Files.readString(RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/V05Hooks.java"));
		assertTrue(hooks.indexOf("FixHold.apply(") < hooks.indexOf("LauncherModAdvice.apply("));
		int refused = hooks.indexOf("RefusedDisables.afterApply(");
		int outside = hooks.indexOf("OutsideChanges.afterApply(");
		int firstRun = hooks.indexOf("firstRun().applied(");
		assertTrue(refused > 0 && refused < outside && outside < firstRun, hooks);
	}

	// A step that throws can't break what 0.4 did (the coordinator's review #1).
	@Test
	void aFailingPostStepLeavesTheReportItWasGiven() {
		Report report = new Report(null, null, null, null, List.of(ADD), 16, "bundled", false, Instant.parse("2026-09-27T00:00:00Z"));
		assertSame(report, V05Hooks.postStep("throws", report, r -> {
			throw new IllegalStateException("boom");
		}));
		assertSame(report, V05Hooks.postStep("an error", report, r -> {
			throw new LinkageError("boom");
		}));
		assertSame(report, V05Hooks.postStep("null", report, r -> null));
		Report other = new Report(null, null, null, null, List.of(), 16, "bundled", false, Instant.parse("2026-09-27T00:00:00Z"));
		assertSame(other, V05Hooks.postStep("works", report, r -> other));
	}

	@Test
	void aFailingGuardFailsClosed() {
		Recommendation setting = new Recommendation("setting:vanilla.renderDistance", Category.SETTING, Impact.MEDIUM, "Render distance", "r",
				new Action.SetSetting("vanilla.renderDistance", "12", "10"), true);
		List<Recommendation> selected = List.of(ADD, setting);
		assertEquals(List.of(setting), V05Hooks.guarded(selected, s -> {
			throw new IllegalStateException("boom");
		}), "only the settings changes go through");
		assertEquals(List.of(setting), V05Hooks.guarded(selected, s -> null));
		assertSame(selected, V05Hooks.guarded(selected, s -> s));
	}

	@Test
	void aFailingAfterApplyStepLeavesTheStatusAndTheNextSteps() {
		List<Component> parts = new ArrayList<>(List.of(Component.literal("1 setting applied.")));
		List<String> ran = new ArrayList<>();
		V05Hooks.applyStep("throws", () -> {
			throw new IllegalStateException("boom");
		});
		V05Hooks.applyStep("next", () -> ran.add("next"));
		assertEquals(List.of("next"), ran);
		assertEquals(1, parts.size());
	}
}
