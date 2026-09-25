package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.UndoPlan.Action;
import io.github.chaotix345.rigtune.core.history.UndoPlanner.Result;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoPlannerTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();

	// Settings and a mods folder: file name -> jar metadata (null for a file that isn't a mod jar).
	static class FakeState implements UndoPlanner.State, UndoPlanner.Folder {
		final Map<String, String> settings = new HashMap<>();
		final Set<String> notChangeable = new HashSet<>();
		final Map<String, JarInfo> files = new LinkedHashMap<>();
		final Set<String> elsewhere = new HashSet<>();

		@Override
		public String setting(String key) {
			return settings.get(key);
		}

		@Override
		public boolean immediate(String key) {
			return key.startsWith("vanilla.");
		}

		@Override
		public boolean changeable(String key) {
			return !notChangeable.contains(key);
		}

		@Override
		public UndoPlanner.Folder folder() {
			return this;
		}

		@Override
		public Path dir() {
			return MODS;
		}

		@Override
		public Set<String> files() {
			return files.keySet();
		}

		@Override
		public JarInfo jar(String fileName) {
			return files.get(fileName);
		}

		@Override
		public Set<String> providedElsewhere() {
			return elsewhere;
		}

		FakeState jar(String name, String modId, String... depends) {
			files.put(name, new JarInfo(modId, Set.of(), Set.of(depends)));
			return this;
		}
	}

	final FakeState state = new FakeState();
	final List<JournalEntry> entries = new ArrayList<>();
	final List<Op> pending = new ArrayList<>();

	private void entry(String id, JournalChange... changes) {
		entries.add(new JournalEntry(id, "2026-09-25T10:00:00Z", JournalEntry.APPLY, "0.2.0", "26.2", null, List.of(changes)));
	}

	private void undoEntry(String id, String undoOf, JournalChange... changes) {
		entries.add(new JournalEntry(id, "2026-09-25T11:00:00Z", JournalEntry.UNDO, "0.2.0", "26.2", undoOf, List.of(changes)));
	}

	private static JournalChange applied(String key, String before, String after) {
		return JournalChange.setting(key, before, after, JournalChange.APPLIED, null);
	}

	private static JournalChange enabled(String modId, String file, String group) {
		return JournalChange.file(JournalChange.ENABLE, modId, file, JournalChange.APPLIED, "op-" + file, group);
	}

	private static JournalChange disabled(String modId, String file, String resultFile, String group) {
		return JournalChange.file(JournalChange.DISABLE, modId, file, JournalChange.APPLIED, "op-" + file, group).withResultFile(resultFile);
	}

	private Result last() {
		return UndoPlanner.plan(entries, pending, state, false);
	}

	private Result all() {
		return UndoPlanner.plan(entries, pending, state, true);
	}

	private static List<UndoPlan.Item> items(Result result, Action action) {
		return result.plan().items().stream().filter(i -> i.action() == action).toList();
	}

	private static UndoPlan.Item only(Result result, Action action) {
		List<UndoPlan.Item> found = items(result, action);
		assertEquals(1, found.size(), result.plan().toString());
		return found.getFirst();
	}

	private static String describe(Op op) {
		return switch (op.type()) {
			case DISABLE_FILE -> "disable " + Path.of(op.path()).getFileName();
			case ENABLE_FILE -> "enable " + Path.of(op.from()).getFileName() + " -> " + Path.of(op.to()).getFileName() + " (" + op.modId() + ")";
			default -> op.type().toString();
		};
	}

	// --- which entry "Undo last" picks

	@Test
	void lastPicksTheNewestEntry() {
		JournalChange rd = applied("vanilla.renderDistance", "12", "16");
		JournalChange sd = applied("vanilla.simulationDistance", "8", "6");
		entry("e1", rd);
		entry("e2", sd);
		state.settings.put("vanilla.renderDistance", "16");
		state.settings.put("vanilla.simulationDistance", "6");

		Result result = last();

		assertEquals("e2", result.plan().undoOf());
		UndoPlan.Item item = only(result, Action.REVERT);
		assertEquals(List.of(sd.id()), item.changeIds());
		assertTrue(!item.needsRestart());
		assertEquals(Map.of("vanilla.simulationDistance", "8"), result.script().immediate());
		assertEquals(1, result.script().reverts().size());
		JournalChange undo = result.script().reverts().getFirst().undo();
		assertEquals(sd.id(), undo.reverts());
		assertEquals("6", undo.before());
		assertEquals("8", undo.after());
	}

	// Review M8: an entry whose changes can't be undone any more must not block the older ones.
	@Test
	void lastPassesOverAnEntryWithNothingLeftToUndo() {
		entry("e1", applied("vanilla.renderDistance", "12", "16"));
		entry("e2", applied("vanilla.simulationDistance", "8", "6"));
		state.settings.put("vanilla.renderDistance", "16");
		state.settings.put("vanilla.simulationDistance", "10");

		assertEquals("e1", last().plan().undoOf());
	}

	// Review M8: an apply that was undone (even if some of its changes were skipped then) isn't "last" again.
	@Test
	void lastPassesOverAnEntryThatWasAlreadyUndone() {
		JournalChange sd = applied("vanilla.simulationDistance", "8", "6");
		entry("e1", applied("vanilla.renderDistance", "12", "16"));
		entry("e2", sd, JournalChange.setting("sodium.threads", "0", "4", JournalChange.APPLIED, "op1"));
		undoEntry("u1", "e2", applied("vanilla.simulationDistance", "6", "8").reverting(sd.id()));
		state.settings.put("vanilla.renderDistance", "16");
		state.settings.put("vanilla.simulationDistance", "8");
		state.settings.put("sodium.threads", "4");

		assertEquals("e1", last().plan().undoOf());
	}

	@Test
	void anUndoOfEverythingCoversEveryEarlierEntry() {
		JournalChange sd = applied("vanilla.simulationDistance", "8", "6");
		entry("e0", sd);
		entry("e1", applied("vanilla.renderDistance", "12", "16"));
		undoEntry("u1", UndoPlanner.ALL, applied("vanilla.simulationDistance", "6", "8").reverting(sd.id()));
		state.settings.put("vanilla.renderDistance", "16");
		state.settings.put("vanilla.simulationDistance", "8");

		assertTrue(last().plan().items().isEmpty());
		assertTrue(last().plan().isEmpty());
	}

	@Test
	void changesAnUndoIsRevertingAreLeftOut() {
		JournalChange threads = JournalChange.setting("sodium.threads", "0", "4", JournalChange.APPLIED, "op1");
		entry("e1", threads);
		undoEntry("u1", "e1", JournalChange.setting("sodium.threads", "4", "0", JournalChange.STAGED, "op2").reverting(threads.id()));
		state.settings.put("sodium.threads", "4");

		assertTrue(all().plan().items().isEmpty());
	}

	// --- settings

	@Test
	void aSettingTheUserChangedSinceIsSkipped() {
		entry("e1", applied("vanilla.renderDistance", "12", "16"));
		state.settings.put("vanilla.renderDistance", "10");

		UndoPlan.Item item = only(all(), Action.SKIP);
		assertTrue(item.reason().contains("changed it since"), item.reason());
		assertTrue(all().script().immediate().isEmpty());
	}

	// Phase 5 finding 8: back at the value it had before RigTune changed it, "you changed it since" misleads.
	@Test
	void aSettingAlreadyBackAtItsOriginalValueSaysSo() {
		entry("e1", applied("vanilla.entityShadows", "true", "false"));
		entry("e2", applied("vanilla.renderDistance", "12", "16"), applied("vanilla.entityShadows", "false", "true"));
		entry("e3", applied("vanilla.entityShadows", "true", "false"));
		state.settings.put("vanilla.entityShadows", "true");
		state.settings.put("vanilla.renderDistance", "16");

		Result result = all();

		List<UndoPlan.Item> skips = items(result, Action.SKIP);
		assertEquals(3, skips.size(), result.plan().toString());
		skips.forEach(item -> assertEquals("It's already back at its original value (true)", item.reason()));
		assertEquals(Map.of("vanilla.renderDistance", "12"), result.script().immediate());
	}

	// Review M8: 12 -> 16, the user sets 10, 10 -> 20. Undo everything gives 10, not 12.
	@Test
	void undoingEverythingStopsTheChainAtAUserEdit() {
		JournalChange first = applied("vanilla.renderDistance", "12", "16");
		JournalChange second = applied("vanilla.renderDistance", "10", "20");
		entry("e1", first);
		entry("e2", second);
		state.settings.put("vanilla.renderDistance", "20");

		Result result = all();

		assertEquals(List.of(second.id()), only(result, Action.REVERT).changeIds());
		assertEquals(Map.of("vanilla.renderDistance", "10"), result.script().immediate());
		assertEquals(List.of(first.id()), only(result, Action.SKIP).changeIds());
	}

	@Test
	void undoingEverythingFollowsConsecutiveApplies() {
		JournalChange first = applied("vanilla.renderDistance", "12", "16");
		JournalChange second = applied("vanilla.renderDistance", "16", "20");
		entry("e1", first);
		entry("e2", second);
		state.settings.put("vanilla.renderDistance", "20");

		Result result = all();

		assertEquals(List.of(second.id(), first.id()), only(result, Action.REVERT).changeIds());
		assertEquals(Map.of("vanilla.renderDistance", "12"), result.script().immediate());
		assertEquals(2, result.script().reverts().size());
	}

	// Review M7: the preset's side effects are recorded; ones RigTune can't write are listed as skipped.
	@Test
	void presetSideEffectsRigTuneCantWriteAreSkipped() {
		entry("e1", applied("vanilla.graphicsPreset", "custom", "fast"), applied("vanilla.ao", "true", "false"));
		state.settings.put("vanilla.graphicsPreset", "fast");
		state.settings.put("vanilla.ao", "false");
		state.notChangeable.add("vanilla.ao");

		Result result = last();

		assertEquals(Map.of("vanilla.graphicsPreset", "custom"), result.script().immediate());
		assertTrue(only(result, Action.SKIP).reason().contains("preset"), only(result, Action.SKIP).reason());
	}

	@Test
	void anOptionRigTuneCantWriteIsSkippedWithoutAPresetRevert() {
		entry("e1", applied("vanilla.ao", "true", "false"));
		state.settings.put("vanilla.ao", "false");
		state.notChangeable.add("vanilla.ao");

		assertTrue(only(all(), Action.SKIP).reason().contains("doesn't change"), only(all(), Action.SKIP).reason());
	}

	// Review M8: 4 -> 6, the user sets 2, 2 -> 8, the user sets 6, 6 -> 10. Undo everything gives 6: once the chain
	// breaks, an older change whose value happens to match again stays skipped.
	@Test
	void undoingEverythingStopsForGoodAtTheFirstUserEdit() {
		JournalChange first = applied("vanilla.renderDistance", "4", "6");
		JournalChange second = applied("vanilla.renderDistance", "2", "8");
		JournalChange third = applied("vanilla.renderDistance", "6", "10");
		entry("e1", first);
		entry("e2", second);
		entry("e3", third);
		state.settings.put("vanilla.renderDistance", "10");

		Result result = all();

		assertEquals(List.of(third.id()), only(result, Action.REVERT).changeIds());
		assertEquals(Map.of("vanilla.renderDistance", "6"), result.script().immediate());
		assertEquals(Set.of(first.id(), second.id()), Set.copyOf(items(result, Action.SKIP).stream().flatMap(i -> i.changeIds().stream()).toList()));
	}

	// Review: an undo whose reversals were all dropped (Discard pending) or abandoned didn't undo anything.
	@Test
	void anUndoThatWasCancelledDoesNotCountAsDone() {
		JournalChange older = JournalChange.setting("sodium.a", "0", "1", JournalChange.APPLIED, "op0");
		JournalChange threads = JournalChange.setting("sodium.threads", "0", "4", JournalChange.APPLIED, "op1");
		entry("b", older);
		entry("a", threads);
		undoEntry("u1", "a", JournalChange.setting("sodium.threads", "4", "0", JournalChange.DISCARDED, "op2").reverting(threads.id()));
		state.settings.put("sodium.a", "1");
		state.settings.put("sodium.threads", "4");

		assertEquals("a", last().plan().undoOf());
	}

	@Test
	void theLastPlanSaysWhenTheApplyWas() {
		entry("e1", applied("vanilla.renderDistance", "12", "16"));
		state.settings.put("vanilla.renderDistance", "16");

		assertEquals("2026-09-25T10:00:00Z", last().plan().at());
		assertNull(all().plan().at());
	}

	// Review M7: restoring the preset rewrites its options; one the screen says it leaves alone keeps its value.
	@Test
	void revertingThePresetKeepsTheOptionsItSkips() {
		entry("e1", applied("vanilla.graphicsPreset", "fast", "fancy"), applied("vanilla.particles", "decreased", "all"),
				applied("vanilla.ao", "false", "true"));
		state.settings.put("vanilla.graphicsPreset", "fancy");
		state.settings.put("vanilla.particles", "minimal");
		state.settings.put("vanilla.ao", "true");
		state.notChangeable.add("vanilla.ao");

		Result result = last();

		assertEquals(Map.of("vanilla.graphicsPreset", "fast", "vanilla.particles", "minimal"), result.script().immediate());
		assertEquals(1, items(result, Action.REVERT).size());
		UndoPlan.Item ao = items(result, Action.SKIP).stream().filter(i -> i.description().startsWith("ao")).findFirst().orElseThrow();
		assertTrue(ao.reason().contains("preset"), ao.reason());
		assertEquals(1, result.script().reverts().size());
	}

	@Test
	void aConfigSettingIsRevertedAfterARestart() {
		entry("e1", JournalChange.setting("sodium.threads", "0", "4", JournalChange.APPLIED, "op1"));
		state.settings.put("sodium.threads", "4");

		Result result = last();

		assertTrue(only(result, Action.REVERT).needsRestart());
		assertEquals(Map.of("sodium.threads", "0"), result.script().staged());
		assertTrue(result.script().immediate().isEmpty());
	}

	// Review L2: PATCH_JSON can't delete a key.
	@Test
	void aConfigSettingThatDidNotExistBeforeIsSkipped() {
		entry("e1", JournalChange.setting("sodium.threads", null, "4", JournalChange.APPLIED, "op1"));
		state.settings.put("sodium.threads", "4");

		assertTrue(only(all(), Action.SKIP).reason().contains("didn't exist"), only(all(), Action.SKIP).reason());
	}

	// --- staged changes

	@Test
	void aStagedChangeCancelsItsWholeGroupIncludingChangesFromOtherApplies() {
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("x-1.jar")),
				Op.enableFile(MODS.resolve("x-2.jar.rigtune-pending"), MODS.resolve("x-2.jar")).withModId("x"));
		pending.addAll(group);
		JournalChange older = JournalChange.file(JournalChange.DISABLE, "x", "x-1.jar", JournalChange.STAGED, group.get(0).id(), group.get(0).group());
		JournalChange newer = JournalChange.file(JournalChange.ENABLE, "x", "x-2.jar", JournalChange.STAGED, group.get(1).id(), group.get(1).group());
		entry("e1", older);
		entry("e2", newer);

		Result result = last();

		assertEquals("e2", result.plan().undoOf());
		List<UndoPlan.Item> discards = items(result, Action.DISCARD_STAGED);
		assertEquals(List.of(List.of(newer.id()), List.of(older.id())), discards.stream().map(UndoPlan.Item::changeIds).toList());
		assertTrue(discards.get(1).reason().contains("together"), discards.get(1).reason());
		assertEquals(Set.of(group.get(0).id(), group.get(1).id()), result.script().discardOpIds());
		assertTrue(discards.stream().noneMatch(UndoPlan.Item::needsRestart));
	}

	@Test
	void aStagedOpWithoutAJournalChangeIsListedWhenItsGroupIsCancelled() {
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("x-1.jar")), Op.disableFile(MODS.resolve("y.jar")));
		pending.addAll(group);
		entry("e1", JournalChange.file(JournalChange.DISABLE, "x", "x-1.jar", JournalChange.STAGED, group.get(0).id(), group.get(0).group()));

		List<UndoPlan.Item> discards = items(last(), Action.DISCARD_STAGED);

		assertEquals(2, discards.size());
		assertTrue(discards.get(1).changeIds().isEmpty());
		assertTrue(discards.get(1).description().contains("y.jar"), discards.get(1).description());
	}

	// Review L3: a staged self-update of RigTune is never dropped by an undo.
	@Test
	void aStagedRigTuneUpdateIsNeverCancelled() {
		state.jar("rigtune-0.2.0.jar", "rigtune");
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("rigtune-0.2.0.jar")),
				Op.enableFile(MODS.resolve("rigtune-0.3.0.jar.rigtune-pending"), MODS.resolve("rigtune-0.3.0.jar")).withModId("rigtune"));
		pending.addAll(group);
		entry("e1", JournalChange.file(JournalChange.DISABLE, "rigtune", "rigtune-0.2.0.jar", JournalChange.STAGED, group.get(0).id(), group.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, "rigtune", "rigtune-0.3.0.jar", JournalChange.STAGED, group.get(1).id(), group.get(1).group()));

		Result result = all();

		assertEquals(2, items(result, Action.SKIP).size());
		assertTrue(result.script().discardOpIds().isEmpty());
	}

	@Test
	void anOpWithAnUnknownTypeIsStillListed() {
		Op known = Op.disableFile(MODS.resolve("x.jar")).inGroup("g");
		Op unknown = new Op(null, null, null, MODS.resolve("y.toml").toString(), null, "op-null", "g", null, 0);
		pending.add(known);
		pending.add(unknown);
		entry("e1", JournalChange.file(JournalChange.DISABLE, "x", "x.jar", JournalChange.STAGED, known.id(), "g"));

		assertEquals(2, items(last(), Action.DISCARD_STAGED).size());
	}

	// Review: a mate staged by an earlier undo is dropped with the group; the re-check mustn't call it gone.
	@Test
	void recheckDoesNotCountAGroupMateFromAnUndoAsGone() {
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("x.jar")), Op.disableFile(MODS.resolve("y.jar")));
		pending.addAll(group);
		entry("e1", JournalChange.file(JournalChange.DISABLE, "x", "x.jar", JournalChange.STAGED, group.get(0).id(), group.get(0).group()));
		undoEntry("u0", "e0", JournalChange.file(JournalChange.DISABLE, "y", "y.jar", JournalChange.STAGED, group.get(1).id(), group.get(1).group())
				.reverting("elsewhere"));
		UndoPlan shown = last().plan();
		assertEquals(2, items(last(), Action.DISCARD_STAGED).size());

		Result result = UndoPlanner.recheck(shown, entries, pending, state);

		assertEquals(Set.copyOf(group.stream().map(Op::id).toList()), result.script().discardOpIds());
		assertTrue(items(result, Action.SKIP).isEmpty(), result.plan().toString());
	}

	@Test
	void aStagedChangeWhoseOpIsGoneIsSkipped() {
		entry("e1", JournalChange.setting("sodium.threads", "0", "4", JournalChange.STAGED, "vanished"));

		assertTrue(only(all(), Action.SKIP).reason().contains("no longer waiting"), only(all(), Action.SKIP).reason());
	}

	// --- mod files

	// AC3.2: the update chain a -> b -> c undone to a.
	@Test
	void undoingAnUpdateChainGoesBackToTheFirstJar() {
		state.jar("mod-a.jar.disabled", "m").jar("mod-b.jar.disabled", "m").jar("mod-c.jar", "m");
		JournalChange disableA = disabled("m", "mod-a.jar", "mod-a.jar.disabled", "g1");
		JournalChange enableB = enabled("m", "mod-b.jar", "g1");
		JournalChange disableB = disabled("m", "mod-b.jar", "mod-b.jar.disabled", "g2");
		JournalChange enableC = enabled("m", "mod-c.jar", "g2");
		entry("e1", disableA, enableB);
		entry("e2", disableB, enableC);

		Result result = all();

		assertEquals(4, items(result, Action.REVERT).size());
		assertTrue(items(result, Action.REVERT).stream().allMatch(UndoPlan.Item::needsRestart));
		List<Op> ops = result.script().fileOps();
		assertEquals(List.of("disable mod-c.jar", "enable mod-a.jar.disabled -> mod-a.jar (m)"), ops.stream().map(UndoPlannerTest::describe).toList());
		assertEquals(1, ops.stream().map(Op::group).distinct().count());
		Map<String, String> opRefs = new HashMap<>();
		result.script().reverts().forEach(r -> opRefs.put(r.changeId(), r.opRef()));
		assertEquals(ops.get(0).id(), opRefs.get(enableC.id()));
		assertEquals(ops.get(1).id(), opRefs.get(disableA.id()));
		assertEquals(ops.get(0).id(), opRefs.get(disableB.id()), "a round trip follows its group's first op");
	}

	// Review M6: an update chain that keeps one file name; each step's disabled name comes from resultFile.
	@Test
	void undoingASameNameUpdateChainUsesTheActualDisabledNames() {
		state.jar("mod.jar", "m").jar("mod.jar.disabled", "m").jar("mod.jar.disabled.1", "m");
		entry("e1", disabled("m", "mod.jar", "mod.jar.disabled", "g1"), enabled("m", "mod.jar", "g1"));
		entry("e2", disabled("m", "mod.jar", "mod.jar.disabled.1", "g2"), enabled("m", "mod.jar", "g2"));

		Result result = all();

		assertEquals(List.of("disable mod.jar", "enable mod.jar.disabled -> mod.jar (m)"),
				result.script().fileOps().stream().map(UndoPlannerTest::describe).toList());
	}

	@Test
	void undoingTheLastOfASameNameChainReEnablesTheJarItReplaced() {
		state.jar("mod.jar", "m").jar("mod.jar.disabled", "m").jar("mod.jar.disabled.1", "m");
		entry("e1", disabled("m", "mod.jar", "mod.jar.disabled", "g1"), enabled("m", "mod.jar", "g1"));
		entry("e2", disabled("m", "mod.jar", "mod.jar.disabled.1", "g2"), enabled("m", "mod.jar", "g2"));

		assertEquals(List.of("disable mod.jar", "enable mod.jar.disabled.1 -> mod.jar (m)"),
				last().script().fileOps().stream().map(UndoPlannerTest::describe).toList());
	}

	@Test
	void undoingJustTheLastUpdateOfAChain() {
		state.jar("mod-a.jar.disabled", "m").jar("mod-b.jar.disabled", "m").jar("mod-c.jar", "m");
		entry("e1", disabled("m", "mod-a.jar", "mod-a.jar.disabled", "g1"), enabled("m", "mod-b.jar", "g1"));
		entry("e2", disabled("m", "mod-b.jar", "mod-b.jar.disabled", "g2"), enabled("m", "mod-c.jar", "g2"));

		assertEquals(List.of("disable mod-c.jar", "enable mod-b.jar.disabled -> mod-b.jar (m)"),
				last().script().fileOps().stream().map(UndoPlannerTest::describe).toList());
	}

	@Test
	void aDisableWhoseDisabledFileIsGoneIsSkipped() {
		entry("e1", disabled("indium", "indium.jar", "indium.jar.disabled", null));

		UndoPlan.Item item = only(all(), Action.SKIP);
		assertTrue(item.reason().contains("indium.jar.disabled"), item.reason());
		assertTrue(all().script().fileOps().isEmpty());
	}

	@Test
	void reEnablingOverAnExistingFileIsSkipped() {
		state.jar("indium.jar.disabled", "indium").jar("indium.jar", "indium");
		entry("e1", disabled("indium", "indium.jar", "indium.jar.disabled", null));

		assertTrue(only(all(), Action.SKIP).reason().contains("already exists"), only(all(), Action.SKIP).reason());
	}

	@Test
	void aDisableWithoutAResultFileIsReEnabledFromTheDefaultName() {
		state.jar("indium.jar.disabled", "indium");
		entry("e1", disabled("indium", "indium.jar", null, null));

		assertEquals(List.of("enable indium.jar.disabled -> indium.jar (indium)"), all().script().fileOps().stream().map(UndoPlannerTest::describe).toList());
	}

	@Test
	void aGroupIsUndoneAllOrNothing() {
		state.jar("sodium-0.7.1.jar", "sodium");
		entry("e1", disabled("sodium", "sodium-0.7.0.jar", "sodium-0.7.0.jar.disabled", "g1"), enabled("sodium", "sodium-0.7.1.jar", "g1"));

		Result result = all();

		assertEquals(2, items(result, Action.SKIP).size());
		assertTrue(result.script().fileOps().isEmpty());
	}

	@Test
	void rigTunesOwnJarIsNeverUndoneByItsModId() {
		state.jar("rigtune-0.2.0.jar", "rigtune").jar("rigtune-0.1.0.jar.disabled", "rigtune");
		entry("e1", disabled(null, "rigtune-0.1.0.jar", "rigtune-0.1.0.jar.disabled", "g1"), enabled("rigtune", "rigtune-0.2.0.jar", "g1"));

		Result result = all();

		assertEquals(2, items(result, Action.SKIP).size());
		assertTrue(items(result, Action.SKIP).getFirst().reason().contains("RigTune"));
		assertTrue(result.script().fileOps().isEmpty());
	}

	// Review M9: exclude RigTune by reading the jar, not only by the journal's mod id.
	@Test
	void rigTunesOwnJarIsNeverUndoneEvenWithoutAModIdInTheJournal() {
		state.jar("rigtune-0.1.0.jar.disabled", "rigtune");
		entry("e1", disabled(null, "rigtune-0.1.0.jar", "rigtune-0.1.0.jar.disabled", null));

		assertTrue(only(all(), Action.SKIP).reason().contains("RigTune"));
	}

	// Review M9: after the undo no active jar may lose a mod it depends on.
	@Test
	void anUndoThatWouldRemoveADependencyIsSkipped() {
		state.jar("lib.jar", "lib").jar("app.jar", "app", "lib", "minecraft", "fabric-api");
		state.elsewhere.add("fabric-api");
		entry("e1", enabled("lib", "lib.jar", null));

		UndoPlan.Item item = only(all(), Action.SKIP);
		assertTrue(item.reason().contains("app") && item.reason().contains("lib"), item.reason());
	}

	@Test
	void aDependencyAnotherJarProvidesKeepsTheUndo() {
		state.jar("lib.jar", "lib").jar("app.jar", "app", "lib");
		state.files.put("bundle.jar", new JarInfo("bundle", Set.of("lib"), Set.of()));
		entry("e1", enabled("lib", "lib.jar", null));

		assertEquals(1, items(all(), Action.REVERT).size());
	}

	// Review M9: no two active jars may share a mod id.
	@Test
	void anUndoThatWouldLoadAModTwiceIsSkipped() {
		state.jar("x-old.jar.disabled", "x").jar("x-new.jar", "x");
		entry("e1", disabled("x", "x-old.jar", "x-old.jar.disabled", null));

		assertTrue(only(all(), Action.SKIP).reason().contains("x"), only(all(), Action.SKIP).reason());
	}

	// Review: merging a re-enable for mod x would silently replace another apply's staged update of x.
	@Test
	void aReEnableIsSkippedWhileAnotherUpdateOfTheModIsStaged() {
		state.jar("x-1.jar.disabled", "x");
		pending.add(Op.enableFile(MODS.resolve("x-3.jar.rigtune-pending"), MODS.resolve("x-3.jar")).withModId("x"));
		entry("e1", disabled("x", "x-1.jar", "x-1.jar.disabled", null));

		UndoPlan.Item item = only(all(), Action.SKIP);

		assertTrue(item.reason().contains(" x "), item.reason());
		assertTrue(all().script().fileOps().isEmpty());
	}

	@Test
	void aReEnableIsFineWhenTheStagedUpdateIsCancelledByTheSameUndo() {
		state.jar("x-1.jar.disabled", "x");
		Op update = Op.enableFile(MODS.resolve("x-3.jar.rigtune-pending"), MODS.resolve("x-3.jar")).withModId("x");
		pending.add(update);
		entry("e1", disabled("x", "x-1.jar", "x-1.jar.disabled", null));
		entry("e2", JournalChange.file(JournalChange.ENABLE, "x", "x-3.jar", JournalChange.STAGED, update.id(), null));

		Result result = all();

		assertEquals(1, items(result, Action.REVERT).size());
		assertEquals(Set.of(update.id()), result.script().discardOpIds());
	}

	@Test
	void aProblemThatAlreadyExistsDoesNotBlockOtherUndos() {
		state.jar("broken.jar", "broken", "missing").jar("indium.jar.disabled", "indium");
		entry("e1", disabled("indium", "indium.jar", "indium.jar.disabled", null));

		assertEquals(1, items(all(), Action.REVERT).size());
	}

	// --- re-checking the plan that was shown (review M8)

	@Test
	void recheckSkipsAnItemWhoseStateChanged() {
		entry("e1", applied("vanilla.renderDistance", "12", "16"));
		state.settings.put("vanilla.renderDistance", "16");
		UndoPlan shown = last().plan();
		state.settings.put("vanilla.renderDistance", "8");

		Result result = UndoPlanner.recheck(shown, entries, pending, state);

		assertEquals(Action.SKIP, only(result, Action.SKIP).action());
		assertTrue(result.script().immediate().isEmpty());
		assertEquals(shown.undoOf(), result.plan().undoOf());
	}

	@Test
	void recheckNeverAddsWhatWasNotShown() {
		entry("e1", applied("vanilla.renderDistance", "12", "16"), applied("vanilla.simulationDistance", "8", "6"));
		state.settings.put("vanilla.renderDistance", "16");
		state.settings.put("vanilla.simulationDistance", "10");
		UndoPlan shown = last().plan();
		state.settings.put("vanilla.simulationDistance", "6");

		Result result = UndoPlanner.recheck(shown, entries, pending, state);

		assertEquals(Map.of("vanilla.renderDistance", "12"), result.script().immediate());
	}

	@Test
	void recheckSkipsAStagedGroupThatChanged() {
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("x-1.jar")));
		pending.addAll(group);
		entry("e1", JournalChange.file(JournalChange.DISABLE, "x", "x-1.jar", JournalChange.STAGED, group.getFirst().id(), group.getFirst().group()));
		UndoPlan shown = last().plan();
		pending.add(Op.disableFile(MODS.resolve("y.jar")).inGroup(group.getFirst().group()));

		Result result = UndoPlanner.recheck(shown, entries, pending, state);

		assertTrue(result.script().discardOpIds().isEmpty());
		assertTrue(only(result, Action.SKIP).reason().contains("changed"), only(result, Action.SKIP).reason());
	}

	@Test
	void recheckSkipsAChangeThatWasUndoneMeanwhile() {
		JournalChange rd = applied("vanilla.renderDistance", "12", "16");
		entry("e1", rd);
		state.settings.put("vanilla.renderDistance", "16");
		UndoPlan shown = last().plan();
		List<JournalEntry> later = HistoryUpdates.revert(entries, Set.of(rd.id()));

		Result result = UndoPlanner.recheck(shown, later, pending, state);

		assertEquals(List.of(rd.id()), only(result, Action.SKIP).changeIds());
		assertTrue(result.script().immediate().isEmpty());
	}

	// Reading a jar (and its nested jars) is slow and happens on the render thread: only jars that are active or that
	// an undo moves are read.
	@Test
	void untouchedDisabledJarsAreNeverRead() {
		Set<String> read = new HashSet<>();
		FakeState counting = new FakeState() {
			@Override
			public JarInfo jar(String fileName) {
				read.add(fileName);
				return super.jar(fileName);
			}
		};
		counting.jar("indium.jar.disabled", "indium").jar("old-fabric-api.jar.disabled", "fabric-api").jar("lithium.jar", "lithium");
		entry("e1", disabled("indium", "indium.jar", "indium.jar.disabled", null));

		assertEquals(1, items(UndoPlanner.plan(entries, pending, counting, true), Action.REVERT).size());
		assertTrue(!read.contains("old-fabric-api.jar.disabled"), read.toString());
	}

	@Test
	void settingsAreDescribedWithTheStatesLabels() {
		UndoPlanner.State labelled = new UndoPlanner.State() {
			@Override
			public String setting(String key) {
				return state.setting(key);
			}

			@Override
			public boolean immediate(String key) {
				return state.immediate(key);
			}

			@Override
			public boolean changeable(String key) {
				return true;
			}

			@Override
			public UndoPlanner.Folder folder() {
				return state;
			}

			@Override
			public String label(String key) {
				return "Render Distance";
			}

			@Override
			public String value(String key, String value) {
				return value + " chunks";
			}
		};
		entry("e1", applied("vanilla.renderDistance", "8", "10"));
		entry("e2", applied("vanilla.renderDistance", "12", "16"));
		state.settings.put("vanilla.renderDistance", "16");

		Result result = UndoPlanner.plan(entries, pending, labelled, true);

		assertEquals("Render Distance: 16 chunks → 12 chunks", only(result, Action.REVERT).description());
		assertEquals("Render Distance: 8 chunks → 10 chunks", only(result, Action.SKIP).description());
	}

	@Test
	void anEmptyHistoryGivesAnEmptyPlan() {
		assertTrue(last().plan().isEmpty());
		assertNull(last().plan().undoOf());
		assertTrue(all().plan().isEmpty());
	}
}
