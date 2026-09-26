package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 2o M6 (audit-verification.md M6): when the 50-entry cap evicts entries, they are folded into one
// baseline entry, so Undo all still plans exactly what it planned before the cap (per key, the chain back to where
// the player last changed it; every mod file change still in effect).
class JournalFoldTest {
	private static final String RD = "vanilla.renderDistance";
	private static final String FPS = "vanilla.maxFps";
	private static final String VSYNC = "vanilla.enableVsync";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

	@TempDir
	Path config;

	private static String at(int minute) {
		return START.plusSeconds(60L * minute).toString();
	}

	private static JournalEntry apply(String id, int minute, JournalChange... changes) {
		return new JournalEntry(id, at(minute), JournalEntry.APPLY, "0.4.0+mc26.2", "26.2", null, List.of(changes));
	}

	private static JournalChange set(String key, String before, String after) {
		return JournalChange.setting(key, before, after, JournalChange.APPLIED, null);
	}

	private static JournalChange staged(String key, String before, String after, String opId) {
		return JournalChange.setting(key, before, after, JournalChange.STAGED, opId);
	}

	private static List<JournalEntry> appended(List<JournalEntry> entries, JournalEntry entry) {
		List<JournalEntry> out = new ArrayList<>(entries);
		out.add(entry);
		return out;
	}

	private static List<String> ids(List<JournalEntry> entries) {
		return entries.stream().map(JournalEntry::id).toList();
	}

	// The file ops as the groups they're staged in (group ids are new in every plan).
	private static Set<Set<String>> ops(UndoPlanner.Result result) {
		Map<String, Set<String>> groups = new HashMap<>();
		for (Op op : result.script().fileOps()) {
			groups.computeIfAbsent(String.valueOf(op.group()), g -> new HashSet<>()).add(op.type() + " " + op.path() + " -> " + op.to());
		}
		return new HashSet<>(groups.values());
	}

	// What the plan reverts: settings keys, and each mod file change's action and file.
	private static Set<String> reverted(UndoPlanner.Result result) {
		Set<String> out = new HashSet<>();
		result.script().reverts().forEach(r -> out.add(r.undo().isSetting() ? r.undo().key() : r.undo().action() + " " + r.undo().file()));
		return out;
	}

	private static void assertSamePlan(UndoPlanner.Result uncapped, UndoPlanner.Result capped, String where) {
		assertNull(uncapped.plan().problem(), where);
		assertNull(capped.plan().problem(), where);
		assertEquals(uncapped.script().immediate(), capped.script().immediate(), where);
		assertEquals(uncapped.script().staged(), capped.script().staged(), where);
		assertEquals(uncapped.script().discardOpIds(), capped.script().discardOpIds(), where);
		assertEquals(ops(uncapped), ops(capped), where);
		assertEquals(reverted(uncapped), reverted(capped), where);
	}

	// The audit's scenario: the first Apply, then many profile switches (each an apply entry, SPEC 4), every one written
	// through Journal.update, so the cap runs after each.
	@Test
	void sixtyProfileSwitchesStillUndoAllToThePreRigTuneValues() throws Exception {
		Journal journal = new Journal(config, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		}, Duration.ofMillis(200));
		JournalChange enableX = JournalChange.file(JournalChange.ENABLE, "x", "x.jar", JournalChange.APPLIED, "op-x", "g-x").withModName("X Mod");
		List<JournalEntry> uncapped = new ArrayList<>();
		uncapped.add(apply("first", 0, set(RD, "12", "8"), set(FPS, "120", "260"),
				JournalChange.setting(DEFER, "ONE_FRAME", "ALWAYS", JournalChange.APPLIED, "op-defer"), enableX));
		for (int i = 0; i < 60; i++) {
			boolean battery = i % 2 == 0;
			uncapped.add(battery
					? apply("switch-" + i, i + 1, set(RD, "8", "6"), set(FPS, "260", "60"), set(VSYNC, "false", "true"), set(DEFER, "ALWAYS", "ZERO_FRAMES"))
					: apply("switch-" + i, i + 1, set(RD, "6", "8"), set(FPS, "60", "260"), set(VSYNC, "true", "false"), set(DEFER, "ZERO_FRAMES", "ALWAYS")));
		}
		for (JournalEntry entry : uncapped) {
			assertTrue(journal.update(entries -> appended(entries, entry)));
		}

		List<JournalEntry> kept = journal.entries();
		assertTrue(kept.size() <= Journal.MAX_ENTRIES, "entries: " + kept.size());
		assertFalse(ids(kept).contains("first"), "the first Apply was evicted: " + ids(kept));
		assertEquals("switch-59", kept.getLast().id());

		UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
		state.settings.putAll(Map.of(RD, "8", FPS, "260", VSYNC, "false", DEFER, "ALWAYS"));
		state.jar("x.jar", "x");
		UndoPlanner.Result all = UndoPlanner.plan(kept, List.of(), state, true);
		assertNull(all.plan().problem());
		assertEquals(Map.of(RD, "12", FPS, "120", VSYNC, "false"), all.script().immediate());
		assertEquals(Map.of(DEFER, "ONE_FRAME"), all.script().staged());
		assertEquals(1, all.script().fileOps().size(), all.script().fileOps().toString());
		Op disable = all.script().fileOps().getFirst();
		assertEquals(PendingActions.Type.DISABLE_FILE, disable.type());
		assertEquals(state.dir().resolve("x.jar").toString(), disable.path());
		assertSamePlan(UndoPlanner.plan(uncapped, List.of(), state, true), all, "uncapped vs capped");

		// One baseline entry (kind apply) holds everything the evicted entries left in effect.
		List<JournalEntry> baselines = kept.stream().filter(e -> !e.id().startsWith("switch-")).toList();
		assertEquals(1, baselines.size(), ids(kept).toString());
		JournalEntry baseline = baselines.getFirst();
		assertEquals(0, kept.indexOf(baseline));
		assertEquals(JournalEntry.APPLY, baseline.kind());
		assertEquals(at(0), baseline.at());
		assertTrue(baseline.changes().contains(enableX), baseline.changes().toString());
		assertTrue(UndoPlanner.undoable(kept).contains(baseline.id()));
	}

	// Pass 2 folds the oldest entries it needs; the baseline replaces them in place (the fold keeps no pass-2 drop).
	@Test
	void theBaselineIsAnApplyAtTheRunsOldestTimeInTheRunsPlace() {
		List<JournalEntry> many = new ArrayList<>();
		for (int i = 0; i < 53; i++) {
			many.add(new JournalEntry("e" + i, at(i), JournalEntry.BENCHMARK, i < 2 ? "0.3.0+mc26.2" : "0.4.0+mc26.2", i < 2 ? "26.1" : "26.2", null,
					List.of(set(FPS, String.valueOf(i), String.valueOf(i + 1)))));
		}

		List<JournalEntry> capped = Journal.cap(many);

		assertEquals(Journal.MAX_ENTRIES, capped.size());
		JournalEntry baseline = capped.getFirst();
		assertEquals(ids(many.subList(4, 53)), ids(capped.subList(1, capped.size())));
		assertFalse(ids(many).contains(baseline.id()));
		assertTrue(baseline.id().startsWith(Journal.BASELINE) && Journal.isBaseline(baseline), baseline.id());
		assertEquals(JournalEntry.APPLY, baseline.kind());
		assertEquals(at(0), baseline.at());
		assertEquals("0.3.0+mc26.2", baseline.rigtuneVersion());
		assertEquals("26.1", baseline.mcVersion());
		assertNull(baseline.undoOf());
		assertEquals(1, baseline.changes().size());
		JournalChange folded = baseline.changes().getFirst();
		assertEquals(FPS, folded.key());
		assertEquals("0", folded.before());
		assertEquals("4", folded.after());
		assertEquals(JournalChange.APPLIED, folded.status());
		assertEquals(many.get(3).changes().getFirst().id(), folded.id(), "the newest folded change's id is kept");
		assertEquals(capped, Journal.cap(capped));
	}

	// Once the journal is full every write folds again: the baseline keeps its id (an open History or Undo screen, a
	// benchmark's journalCursor), and each key's folded change the id of its newest change.
	@Test
	void theBaselineKeepsItsIdAcrossWrites() {
		List<JournalEntry> capped = new ArrayList<>();
		for (int i = 0; i < 70; i++) {
			capped = Journal.cap(appended(capped, apply("e" + i, i, set(FPS, String.valueOf(i), String.valueOf(i + 1)))));
		}
		String id = capped.getFirst().id();
		JournalEntry next = apply("e70", 70, set(FPS, "70", "71"));

		List<JournalEntry> after = Journal.cap(appended(capped, next));

		assertEquals(id, after.getFirst().id());
		assertEquals(capped.get(1).changes().getFirst().id(), after.getFirst().changes().getFirst().id());
		assertEquals("0", after.getFirst().changes().getFirst().before());
	}

	// The review's H1 case: a break inside the run (the player set the value back by hand) must still stop the chain when
	// an older entry that can't be folded (it has a staged change) set the same value.
	@Test
	void aBreakInsideTheRunStillStopsTheChainBeforeAnOlderEntry() {
		List<JournalEntry> uncapped = new ArrayList<>();
		uncapped.add(apply("a", 0, set(RD, "12", "8"), staged(DEFER, "ONE_FRAME", "ALWAYS", "op-a")));
		uncapped.add(apply("b", 1, set(RD, "8", "16")));
		uncapped.add(apply("c", 2, set(RD, "8", "10")));
		for (int i = 0; i < 49; i++) {
			uncapped.add(apply("s" + i, i + 3, set(FPS, i % 2 == 0 ? "120" : "60", i % 2 == 0 ? "60" : "120")));
		}
		List<Op> pending = List.of(new Op(PendingActions.Type.PATCH_JSON, null, null, "sodium-options.json",
				Map.of("performance.chunk_build_defer_mode", "ALWAYS"), "op-a", null, null, 0));

		List<JournalEntry> capped = Journal.cap(uncapped);

		assertEquals(List.of("a"), ids(capped.subList(0, 1)));
		assertTrue(Journal.isBaseline(capped.get(1)), ids(capped).toString());
		UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
		state.settings.putAll(Map.of(RD, "10", FPS, "60", DEFER, "ONE_FRAME"));
		UndoPlanner.Result all = UndoPlanner.plan(capped, pending, state, true);
		assertEquals("8", all.script().immediate().get(RD));
		assertSamePlan(UndoPlanner.plan(uncapped, pending, state, true), all, "break inside the run");
		// Folding again keeps the break (at most two changes per key).
		List<JournalEntry> again = Journal.cap(appended(capped, apply("more", 60, set(FPS, "60", "120"))));
		assertEquals(2, again.get(1).changes().stream().filter(c -> RD.equals(c.key())).count());
	}

	// The entry just written is never folded (Undo last, a profile switch's label and active marker need it), even when
	// only the fallback is left.
	@Test
	void theNewestEntryIsNeverFolded() {
		List<JournalEntry> many = new ArrayList<>();
		for (int i = 0; i < 49; i++) {
			many.add(apply("t" + i, i, staged("sodium.k" + i, "0", "1", "op" + i)));
		}
		many.add(apply("older", 49, set(RD, "12", "8")));
		many.add(apply("newest", 50, set(FPS, "120", "60")));

		List<JournalEntry> capped = Journal.cap(many);

		assertEquals(Journal.MAX_ENTRIES, capped.size());
		assertEquals(many.getLast(), capped.getLast());
	}

	// The fallback drops another entry before a baseline, which holds everything from before the entries it replaced.
	@Test
	void theFallbackKeepsTheBaseline() {
		List<JournalEntry> many = new ArrayList<>();
		many.add(new JournalEntry(Journal.BASELINE + "x", at(0), JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(set(RD, "12", "8"))));
		for (int i = 1; i < 51; i++) {
			many.add(i % 2 == 1 ? apply("t" + i, i, staged(DEFER, "A", "B", "op" + i)) : apply("a" + i, i, set(FPS, "1", "2")));
		}

		List<JournalEntry> capped = Journal.cap(many);

		assertEquals(Journal.BASELINE + "x", capped.getFirst().id());
		assertFalse(ids(capped).contains("a2"));
	}

	// A player change between two RigTune changes of a key ends Undo all's chain there (UndoPlanner's CHANGED_BETWEEN);
	// the fold ends it at the same place, so the player's own value is what comes back.
	@Test
	void aPlayerChangeBetweenTwoAppliesStopsTheChainAsUndoAllWould() {
		List<JournalEntry> uncapped = new ArrayList<>();
		uncapped.add(apply("a", 0, set(RD, "12", "8")));
		uncapped.add(apply("b", 1, set(RD, "16", "10")));
		for (int i = 0; i < 50; i++) {
			uncapped.add(apply("s" + i, i + 2, set(FPS, i % 2 == 0 ? "120" : "60", i % 2 == 0 ? "60" : "120")));
		}

		List<JournalEntry> capped = Journal.cap(uncapped);

		assertFalse(ids(capped).contains("b"));
		UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
		state.settings.putAll(Map.of(RD, "10", FPS, "120"));
		UndoPlanner.Result all = UndoPlanner.plan(capped, List.of(), state, true);
		assertEquals("16", all.script().immediate().get(RD));
		assertEquals("120", all.script().immediate().get(FPS));
		assertSamePlan(UndoPlanner.plan(uncapped, List.of(), state, true), all, "player change");
	}

	// Changes Undo all can't act on (reverted, discarded, abandoned) aren't carried; neither is an entry whose change a
	// staged undo is reverting (a Discard pending would make it undoable again), which stays as it is.
	@Test
	void onlyChangesStillInEffectAreFoldedAndAPendingUndoKeepsItsTarget() {
		List<JournalEntry> many = new ArrayList<>();
		many.add(apply("a", 0, set(RD, "12", "8").withStatus(JournalChange.REVERTED), set(FPS, "120", "60"),
				JournalChange.file(JournalChange.ENABLE, "y", "y.jar", JournalChange.DISCARDED, "op-y", "g-y"),
				JournalChange.setting(DEFER, "ONE_FRAME", "ALWAYS", JournalChange.ABANDONED, "op-d")));
		many.add(apply("b", 1, set(VSYNC, "false", "true")));
		JournalChange pendingTarget = set("sodium.x", "1", "2");
		many.add(apply("c", 2, pendingTarget));
		for (int i = 0; i < 48; i++) {
			many.add(apply("s" + i, i + 3, set("vanilla.particles", String.valueOf(i), String.valueOf(i + 1))));
		}
		many.add(new JournalEntry("undo-c", at(60), JournalEntry.UNDO, "0.4.0+mc26.2", "26.2", "c",
				List.of(JournalChange.setting("sodium.x", "2", "1", JournalChange.STAGED, "op-undo").reverting(pendingTarget.id()))));
		assertEquals(52, many.size());

		List<JournalEntry> capped = Journal.cap(many);

		assertEquals(Journal.MAX_ENTRIES, capped.size());
		JournalEntry baseline = capped.getFirst();
		assertEquals(List.of(FPS + " 120->60", VSYNC + " false->true"),
				baseline.changes().stream().map(c -> c.key() + " " + c.before() + "->" + c.after()).toList());
		assertEquals("c", capped.get(1).id());
	}

	// Mod file changes still in effect are carried as they are (ids, groups, result names, mod names), oldest first, so
	// Undo all disables what an evicted Apply added and re-enables what it disabled.
	@Test
	void fileChangesAreCopiedAsTheyAre() {
		JournalChange enableX = JournalChange.file(JournalChange.ENABLE, "x", "x.jar", JournalChange.APPLIED, "op-x", "g1").withModName("X Mod");
		JournalChange disableY = JournalChange.file(JournalChange.DISABLE, "y", "y-1.jar", JournalChange.APPLIED, "op-y1", "g2")
				.withResultFile("y-1.jar.disabled").withModName("Y Mod");
		JournalChange enableY = JournalChange.file(JournalChange.ENABLE, "y", "y-2.jar", JournalChange.APPLIED, "op-y2", "g2").withModName("Y Mod");
		List<JournalEntry> uncapped = new ArrayList<>();
		uncapped.add(apply("a", 0, set(RD, "12", "8"), enableX));
		uncapped.add(apply("update", 1, disableY, enableY));
		for (int i = 0; i < 49; i++) {
			uncapped.add(apply("s" + i, i + 2, set(RD, i % 2 == 0 ? "8" : "6", i % 2 == 0 ? "6" : "8")));
		}

		List<JournalEntry> capped = Journal.cap(uncapped);

		JournalEntry baseline = capped.getFirst();
		assertEquals(List.of(enableX, disableY, enableY), baseline.changes().stream().filter(JournalChange::isFile).toList());
		UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
		state.settings.put(RD, "6");
		state.jar("x.jar", "x").jar("y-1.jar.disabled", "y").jar("y-2.jar", "y");
		UndoPlanner.Result all = UndoPlanner.plan(capped, List.of(), state, true);
		assertEquals("12", all.script().immediate().get(RD));
		assertEquals(3, all.script().fileOps().size(), all.script().fileOps().toString());
		assertSamePlan(UndoPlanner.plan(uncapped, List.of(), state, true), all, "files");
	}

	// Folding across an entry with a staged change would move changes past it; a run stops there, and the next run of
	// at least two entries is folded instead.
	@Test
	void aRunNeverCrossesAnEntryWithStagedChanges() {
		List<JournalEntry> many = new ArrayList<>();
		many.add(apply("old", 0, set(RD, "12", "8")));
		many.add(apply("staged", 1, staged(DEFER, "ONE_FRAME", "ALWAYS", "op-defer")));
		for (int i = 0; i < 49; i++) {
			many.add(apply("s" + i, i + 2, set(RD, i % 2 == 0 ? "8" : "6", i % 2 == 0 ? "6" : "8")));
		}

		List<JournalEntry> capped = Journal.cap(many);

		assertEquals(Journal.MAX_ENTRIES, capped.size());
		assertEquals(List.of("old", "staged"), ids(capped.subList(0, 2)));
		assertFalse(ids(many).contains(capped.get(2).id()));
		assertEquals(at(2), capped.get(2).at());
		assertEquals("s2", capped.get(3).id());
	}

	// Without a run of two, the cap drops as before (entries with staged changes interleaved all the way).
	@Test
	void noFoldableRunFallsBackToDropping() {
		List<JournalEntry> many = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			many.add(i % 2 == 0 ? apply("a" + i, i, set(RD, "1", "2")) : apply("t" + i, i, staged(DEFER, "A", "B", "op" + i)));
		}

		List<JournalEntry> capped = Journal.cap(many);

		assertEquals(ids(many.subList(1, 51)), ids(capped));
	}

	// The property behind the fold: random histories, capped after every write as the Journal does, plan the same Undo all
	// as the uncapped ones. They mix Applies of vanilla keys, player changes, undo of the newest entry, staged Sodium
	// changes (some fail and stay staged over restarts, some are discarded), staged undos of older ones, restarts, mods
	// added and updated (a disable and an enable in one group).
	@Test
	void foldedHistoriesPlanUndoAllAsTheUncappedOnes() {
		for (int seed = 0; seed < 400; seed++) {
			RandomHistory history = new RandomHistory(new Random(seed));
			int steps = 60 + history.random.nextInt(120);
			for (int step = 0; step < steps; step++) {
				history.step(step);
				assertTrue(history.capped.size() <= Journal.MAX_ENTRIES, "seed " + seed);
			}
			UndoPlannerTest.FakeState state = history.state();
			assertSamePlan(UndoPlanner.plan(history.uncapped, history.pending, state, true), UndoPlanner.plan(history.capped, history.pending, state, true),
					"seed " + seed);
		}
	}

	private static final class RandomHistory {
		static final List<String> KEYS = List.of("vanilla.a", "vanilla.b", "vanilla.c", "vanilla.d");
		static final String SODIUM = "sodium.s";
		static final Path SODIUM_FILE = Path.of("game", "config", "sodium-options.json").toAbsolutePath();

		final Random random;
		final Map<String, String> world = new HashMap<>();
		final Map<String, String> files = new java.util.LinkedHashMap<>();
		final List<Op> pending = new ArrayList<>();
		final Set<String> failing = new HashSet<>();
		List<JournalEntry> uncapped = new ArrayList<>();
		List<JournalEntry> capped = new ArrayList<>();

		RandomHistory(Random random) {
			this.random = random;
			KEYS.forEach(k -> world.put(k, "1"));
			world.put(SODIUM, "1");
		}

		String value() {
			return String.valueOf(random.nextInt(5));
		}

		void step(int step) {
			double p = random.nextDouble();
			if (p < 0.08) {
				world.put(KEYS.get(random.nextInt(KEYS.size())), value());
			} else if (p < 0.16) {
				undoNewest(step);
			} else if (p < 0.27) {
				// A Sodium key staged, often with vanilla keys set now (a profile switch).
				Op op = Op.patchJson(SODIUM_FILE, Map.of("s", value()));
				String before = effective();
				pending.add(op);
				if (random.nextInt(3) == 0) {
					failing.add(op.id());
				}
				List<JournalChange> changes = random.nextBoolean() ? vanilla() : new ArrayList<>();
				changes.add(staged(SODIUM, before, op.patches().get("s"), op.id()));
				write(apply("t" + step, step, changes.toArray(JournalChange[]::new)));
			} else if (p < 0.31) {
				stagedUndo(step);
			} else if (p < 0.34 && !pending.isEmpty()) {
				Op op = pending.remove(random.nextInt(pending.size()));
				failing.remove(op.id());
				uncapped = HistoryUpdates.discard(uncapped, List.of(op.id()));
				capped = HistoryUpdates.discard(capped, List.of(op.id()));
			} else if (p < 0.42) {
				restart();
			} else if (p < 0.47) {
				String name = "m" + step + ".jar";
				files.put(name, "m" + step);
				write(apply("add" + step, step, JournalChange.file(JournalChange.ENABLE, "m" + step, name, JournalChange.APPLIED, "o" + step, "g" + step)));
			} else if (p < 0.51) {
				update(step);
			} else {
				List<JournalChange> changes = vanilla();
				if (!changes.isEmpty()) {
					write(apply("e" + step, step, changes.toArray(JournalChange[]::new)));
				}
			}
		}

		// Some vanilla keys set to new values now.
		List<JournalChange> vanilla() {
			List<JournalChange> changes = new ArrayList<>();
			for (String key : KEYS) {
				String after = value();
				if (random.nextInt(3) == 0 && !after.equals(world.get(key))) {
					changes.add(set(key, world.get(key), after));
					world.put(key, after);
				}
			}
			return changes;
		}

		void write(JournalEntry entry) {
			uncapped = appended(uncapped, entry);
			capped = Journal.cap(appended(capped, entry));
		}

		// As StagedChanges records it: the last pending op's value, else the file's.
		String effective() {
			String value = world.get(SODIUM);
			for (Op op : pending) {
				value = op.patches().get("s");
			}
			return value;
		}

		// Undo last of an Apply of vanilla keys (applied now; the undone changes become REVERTED).
		void undoNewest(int step) {
			JournalEntry newest = uncapped.isEmpty() ? null : uncapped.getLast();
			if (newest == null || !JournalEntry.APPLY.equals(newest.kind())
					|| newest.changes().stream().anyMatch(c -> !c.isSetting() || !c.key().startsWith("vanilla."))) {
				return;
			}
			List<JournalChange> undo = new ArrayList<>();
			Set<String> reverted = new HashSet<>();
			for (JournalChange c : newest.changes()) {
				if (JournalChange.APPLIED.equals(c.status()) && c.after().equals(world.get(c.key()))) {
					world.put(c.key(), c.before());
					reverted.add(c.id());
					undo.add(set(c.key(), c.after(), c.before()).reverting(c.id()));
				}
			}
			if (!undo.isEmpty()) {
				uncapped = HistoryUpdates.revert(uncapped, reverted);
				capped = HistoryUpdates.revert(capped, reverted);
				write(new JournalEntry("u" + step, at(step), JournalEntry.UNDO, "0.4.0", "26.2", newest.id(), undo));
			}
		}

		// Undo this of an older applied Sodium change, staged for the next restart (it may fail, or be discarded).
		void stagedUndo(int step) {
			Set<String> reverting = new HashSet<>();
			uncapped.forEach(e -> e.changes().forEach(c -> {
				if (c.reverts() != null) {
					reverting.add(c.reverts());
				}
			}));
			Map<String, JournalChange> inUncapped = new HashMap<>();
			uncapped.forEach(e -> e.changes().forEach(c -> inUncapped.put(c.id(), c)));
			List<JournalEntry> owners = new ArrayList<>();
			List<JournalChange> targets = new ArrayList<>();
			for (JournalEntry e : capped) {
				for (JournalChange c : e.changes()) {
					if (JournalEntry.APPLY.equals(e.kind()) && SODIUM.equals(c.key()) && JournalChange.APPLIED.equals(c.status())
							&& !reverting.contains(c.id()) && c.equals(inUncapped.get(c.id()))) {
						owners.add(e);
						targets.add(c);
					}
				}
			}
			if (targets.isEmpty()) {
				return;
			}
			int pick = random.nextInt(targets.size());
			JournalChange target = targets.get(pick);
			Op op = Op.patchJson(SODIUM_FILE, Map.of("s", target.before()));
			pending.add(op);
			if (random.nextInt(4) == 0) {
				failing.add(op.id());
			}
			write(new JournalEntry("su" + step, at(step), JournalEntry.UNDO, "0.4.0", "26.2", owners.get(pick).id(),
					List.of(JournalChange.setting(SODIUM, target.after(), target.before(), JournalChange.STAGED, op.id()).reverting(target.id()))));
		}

		// The helper runs every pending op in order; a failing one stays pending (and staged) and may work next time.
		void restart() {
			List<ApplyResult.OpResult> results = new ArrayList<>();
			List<Op> left = new ArrayList<>();
			for (Op op : pending) {
				boolean fails = failing.contains(op.id()) && random.nextInt(10) < 7;
				results.add(new ApplyResult.OpResult(op, fails ? ApplyResult.Status.FAILED : ApplyResult.Status.OK, "test"));
				if (fails) {
					left.add(op);
				} else {
					world.put(SODIUM, op.patches().get("s"));
				}
			}
			pending.clear();
			pending.addAll(left);
			failing.retainAll(left.stream().map(Op::id).toList());
			uncapped = HistoryUpdates.applyResults(uncapped, results);
			capped = HistoryUpdates.applyResults(capped, results);
		}

		// An update of an enabled mod: its jar disabled and a new one enabled, in one group.
		void update(int step) {
			List<String> active = files.keySet().stream().filter(n -> n.endsWith(".jar")).toList();
			if (active.isEmpty()) {
				return;
			}
			String old = active.get(random.nextInt(active.size()));
			String modId = files.remove(old);
			String disabled = old + ".disabled";
			for (int i = 1; files.containsKey(disabled); i++) {
				disabled = old + ".disabled." + i;
			}
			String now = modId + "-v" + step + ".jar";
			files.put(disabled, modId);
			files.put(now, modId);
			write(apply("up" + step, step,
					JournalChange.file(JournalChange.DISABLE, modId, old, JournalChange.APPLIED, "od" + step, "g" + step).withResultFile(disabled),
					JournalChange.file(JournalChange.ENABLE, modId, now, JournalChange.APPLIED, "oe" + step, "g" + step)));
		}

		UndoPlannerTest.FakeState state() {
			UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
			state.settings.putAll(world);
			files.forEach(state::jar);
			return state;
		}
	}
}
