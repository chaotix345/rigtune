package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthOffAdviceTest {
	private static final Recommendation ADD = new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Install Lithium",
			"Optimises game logic.", new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true);
	private static final Recommendation UPDATE = new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium",
			"Version 0.9.2 is available (you have 0.9.1).",
			new Action.UpdateMod("sodium", Path.of("mods", "sodium.jar"), new UpdateInfo("sodium", "AANobbMI", "0.9.1", "v", "0.9.2", null)), true);
	private static final Recommendation DISABLE = new Recommendation("disable:indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium",
			"Merged into Sodium.", new Action.DisableMod("indium", Path.of("mods", "indium.jar")), true);
	private static final Recommendation SETTING = new Recommendation("set:vanilla.renderDistance", Category.SETTING, Impact.HIGH,
			"Render distance", "Tier 4.", new Action.SetSetting("vanilla.renderDistance", "16", "12"), true);
	private static final Recommendation ADVICE = new Recommendation("advice:ram", Category.ADVICE, Impact.MEDIUM, "More RAM",
			"Raise it.", new Action.None(), false);

	private static Report report(List<Recommendation> recs) {
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"),
				Goal.QUALITY, recs, 9, "cache", false, Instant.parse("2026-09-25T10:00:00Z"));
	}

	@Test
	void addBecomesAdviceToInstallFromTheLauncher() {
		Recommendation r = ModrinthOffAdvice.apply(report(List.of(ADD))).recommendations().getFirst();

		assertEquals("add:lithium", r.id());
		assertEquals(Category.ADD_MOD, r.category());
		assertEquals(Impact.HIGH, r.impact());
		assertEquals("Install Lithium", r.title());
		assertEquals("Optimises game logic. " + ModrinthOffAdvice.ADD_NOTE, r.reason());
		assertInstanceOf(Action.None.class, r.action());
		assertFalse(r.appliable());
		assertFalse(r.selectedByDefault());
	}

	@Test
	void updateBecomesAdviceToUpdateInTheLauncher() {
		Recommendation r = ModrinthOffAdvice.apply(report(List.of(UPDATE))).recommendations().getFirst();

		assertEquals(Category.UPDATE_MOD, r.category());
		assertEquals("Version 0.9.2 is available (you have 0.9.1). " + ModrinthOffAdvice.UPDATE_NOTE, r.reason());
		assertFalse(r.appliable());
		assertFalse(r.selectedByDefault());
	}

	@Test
	void blankReasonGetsJustTheNote() {
		Recommendation noReason = new Recommendation("add:x", Category.ADD_MOD, Impact.LOW, "Install X", null,
				new Action.AddMod("x", "id", "X"), true);

		assertEquals(ModrinthOffAdvice.ADD_NOTE, ModrinthOffAdvice.apply(report(List.of(noReason))).recommendations().getFirst().reason());
	}

	@Test
	void localActionsAndTheReportItselfAreUntouched() {
		Report in = report(List.of(DISABLE, ADD, SETTING, UPDATE, ADVICE));
		Report out = ModrinthOffAdvice.apply(in);

		assertSame(DISABLE, out.recommendations().get(0));
		assertSame(SETTING, out.recommendations().get(2));
		assertSame(ADVICE, out.recommendations().get(4));
		assertEquals(List.of("disable:indium", "add:lithium", "set:vanilla.renderDistance", "update:sodium", "advice:ram"),
				out.recommendations().stream().map(Recommendation::id).toList());
		assertTrue(out.recommendations().stream().noneMatch(r -> r.action() instanceof Action.AddMod || r.action() instanceof Action.UpdateMod));
		assertEquals(in.hardware(), out.hardware());
		assertEquals(in.gpuClass(), out.gpuClass());
		assertEquals(in.tier(), out.tier());
		assertEquals(in.goal(), out.goal());
		assertEquals(in.rulesRevision(), out.rulesRevision());
		assertEquals(in.rulesSource(), out.rulesSource());
		assertEquals(in.online(), out.online());
		assertEquals(in.createdAt(), out.createdAt());
	}

	@Test
	void nothingToChangeReturnsTheSameReport() {
		Report in = report(List.of(DISABLE, SETTING, ADVICE));

		assertSame(in, ModrinthOffAdvice.apply(in));
	}

	@Test
	void withTheNetworkOffTheNotesNameTheNetworkSwitch() {
		List<Recommendation> recs = ModrinthOffAdvice.apply(report(List.of(ADD, UPDATE)), true).recommendations();

		assertEquals("Optimises game logic. " + ModrinthOffAdvice.NETWORK_ADD_NOTE, recs.get(0).reason());
		assertEquals("Version 0.9.2 is available (you have 0.9.1). " + ModrinthOffAdvice.NETWORK_UPDATE_NOTE, recs.get(1).reason());
		assertTrue(ModrinthOffAdvice.NETWORK_ADD_NOTE.startsWith("Network access is off in RigTune's settings"));
		assertFalse(recs.get(0).appliable());
	}
}
