package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.4/SPEC.md 2o: a "Disable X" from the RigTune screen is checked before it's staged, with the same "would this
// folder start?" check Undo uses (audit H1-B), and never absorbed into a staged update of X (audit M5).
class FolderCheckTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();

	final UndoPlannerTest.FakeState folder = new UndoPlannerTest.FakeState();
	final List<Op> pending = new ArrayList<>();

	private String refusal(String file) {
		Text text = FolderCheck.disableRefusal(folder, pending, file);
		return text == null ? null : text.english();
	}

	@Test
	void aDisableAnActiveJarDependsOnIsRefused() {
		folder.jar("x.jar", "x").jar("y.jar", "y", "x");

		assertEquals("The game wouldn't start without it: y would be missing x", refusal("x.jar"));
	}

	@Test
	void aDisableNothingDependsOnGoesAhead() {
		folder.jar("x.jar", "x").jar("y.jar", "y", "minecraft");

		assertNull(refusal("x.jar"));
	}

	@Test
	void aDependencyAnotherJarOrTheGameProvidesStillGoesAhead() {
		folder.jar("x.jar", "x").jar("y.jar", "y", "x", "fabric-api");
		folder.files.put("bundle.jar", new JarInfo("bundle", Set.of("x"), Set.of()));
		folder.elsewhere.add("fabric-api");

		assertNull(refusal("x.jar"));
	}

	@Test
	void aProblemThatAlreadyExistsDoesNotRefuseIt() {
		folder.jar("x.jar", "x").jar("broken.jar", "broken", "missing");

		assertNull(refusal("x.jar"));
	}

	// The helper runs what is already staged before an op staged after it.
	@Test
	void aDependantAStagedOpDisablesDoesNotCount() {
		folder.jar("x.jar", "x").jar("y.jar", "y", "x");
		pending.add(Op.disableFile(MODS.resolve("y.jar")));

		assertNull(refusal("x.jar"));
	}

	@Test
	void aStagedDependantCounts() {
		folder.jar("x.jar", "x").jar("z.jar.rigtune-pending", "z", "x");
		pending.addAll(PendingActions.group(Op.enableFile(MODS.resolve("z.jar.rigtune-pending"), MODS.resolve("z.jar")).withModId("z")));

		assertEquals("The game wouldn't start without it: z would be missing x", refusal("x.jar"));
	}

	@Test
	void aDisableOfAJarAStagedUpdateReplacesIsRefused() {
		folder.jar("x-1.jar", "x").jar("x-2.jar.rigtune-pending", "x");
		pending.addAll(PendingActions.group(Op.disableFile(MODS.resolve("x-1.jar")),
				Op.enableFile(MODS.resolve("x-2.jar.rigtune-pending"), MODS.resolve("x-2.jar")).withModId("x")));

		assertEquals("Another change of it is staged; cancel that first (Undo last or Discard pending)", refusal("x-1.jar"));
	}

	// A staged disable of the same jar on its own is the same change (merge keeps one), not an update.
	@Test
	void aRepeatOfAStagedDisableGoesAhead() {
		folder.jar("x-1.jar", "x");
		pending.add(Op.disableFile(MODS.resolve("x-1.jar")));

		assertNull(refusal("x-1.jar"));
	}

	@Test
	void theFolderProblemsAreTheUndoChecksOnes() {
		folder.jar("a.jar", "a", "lib").jar("b-1.jar", "b").jar("b-2.jar", "b");

		assertEquals(List.of(String.format(UndoPlanner.MISSING, "a", "lib"), String.format(UndoPlanner.LOADED_TWICE, "b", "b-1.jar, b-2.jar")),
				FolderCheck.problems(FolderCheck.files(folder), folder.providedElsewhere()).values().stream().map(Text::english).toList());
	}

	// --- one Apply's disables, checked together (review of WS-G2, M1)

	private Map<String, String> refusals(String... files) {
		Map<String, String> out = new LinkedHashMap<>();
		FolderCheck.disableRefusals(folder, pending, List.of(files)).forEach((file, why) -> out.put(file, why.english()));
		return out;
	}

	@Test
	void disablingALibraryTogetherWithTheOnlyModThatNeedsItGoesAhead() {
		folder.jar("lib.jar", "lib").jar("app.jar", "app", "lib");

		assertEquals(Map.of(), refusals("lib.jar", "app.jar"));
		assertEquals(Map.of(), refusals("app.jar", "lib.jar"));
	}

	@Test
	void disablingBothProvidersOfAModAnotherNeedsKeepsOne() {
		folder.files.put("p1.jar", new JarInfo("p1", Set.of("z"), Set.of()));
		folder.files.put("p2.jar", new JarInfo("p2", Set.of("z"), Set.of()));
		folder.jar("c.jar", "c", "z");

		assertNull(refusal("p1.jar"));
		assertEquals(Map.of("p1.jar", "The game wouldn't start without it: c would be missing z"), refusals("p1.jar", "p2.jar"));
	}

	@Test
	void aChainOfDependenciesIsRefusedLinkByLink() {
		folder.jar("x.jar", "x").jar("y.jar", "y", "x").jar("z.jar", "z", "y");

		assertEquals(Map.of("y.jar", "The game wouldn't start without it: z would be missing y",
				"x.jar", "The game wouldn't start without it: y would be missing x"), refusals("x.jar", "y.jar"));
	}

	// An undo that disables this jar among others (one group per undo) already does what was asked.
	@Test
	void aStagedGroupThatDisablesTheJarAndEnablesAnotherModGoesAhead() {
		folder.jar("a.jar", "a").jar("c-1.jar.disabled", "c");
		pending.addAll(PendingActions.group(Op.disableFile(MODS.resolve("a.jar")),
				Op.enableFile(MODS.resolve("c-1.jar.disabled"), MODS.resolve("c-1.jar")).withModId("c")));

		assertNull(refusal("a.jar"));
	}

	@Test
	void aStagedSwapBackToAnOlderJarOfTheSameModIsRefused() {
		folder.jar("x-2.jar", "x").jar("x-1.jar.disabled", "x");
		pending.addAll(PendingActions.group(Op.disableFile(MODS.resolve("x-2.jar")),
				Op.enableFile(MODS.resolve("x-1.jar.disabled"), MODS.resolve("x-1.jar"))));

		assertEquals(FolderCheck.CHANGE_STAGED, refusal("x-2.jar"));
	}
}
