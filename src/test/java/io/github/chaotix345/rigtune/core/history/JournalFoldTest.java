package io.github.chaotix345.rigtune.core.history;

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

	private static List<String> ops(UndoPlanner.Result result) {
		return result.script().fileOps().stream().map(op -> op.type() + " " + op.path() + " -> " + op.to()).sorted().toList();
	}

	private static void assertSamePlan(UndoPlanner.Result uncapped, UndoPlanner.Result capped, String where) {
		assertNull(uncapped.plan().problem(), where);
		assertNull(capped.plan().problem(), where);
		assertEquals(uncapped.script().immediate(), capped.script().immediate(), where);
		assertEquals(uncapped.script().staged(), capped.script().staged(), where);
		assertEquals(uncapped.script().discardOpIds(), capped.script().discardOpIds(), where);
		assertEquals(ops(uncapped), ops(capped), where);
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
		assertNotNull(folded.id());
		assertEquals(capped, Journal.cap(capped));
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

	// The property behind the fold: random histories (applies, player changes, undo of the newest entry, staged changes
	// and restarts), capped after every write as the Journal does, plan the same Undo all as the uncapped history.
	@Test
	void foldedHistoriesPlanUndoAllAsTheUncappedOnes() {
		List<String> keys = List.of("vanilla.a", "vanilla.b", "vanilla.c", "vanilla.d");
		Path sodiumFile = Path.of("game", "config", "sodium-options.json").toAbsolutePath();
		for (int seed = 0; seed < 300; seed++) {
			Random random = new Random(seed);
			Map<String, String> world = new HashMap<>();
			keys.forEach(k -> world.put(k, "1"));
			world.put("sodium.s", "1");
			List<JournalEntry> uncapped = new ArrayList<>();
			List<JournalEntry> capped = new ArrayList<>();
			List<Op> pending = new ArrayList<>();
			int steps = 60 + random.nextInt(90);
			for (int step = 0; step < steps; step++) {
				double p = random.nextDouble();
				JournalEntry entry = null;
				if (p < 0.1) {
					world.put(keys.get(random.nextInt(keys.size())), String.valueOf(random.nextInt(5)));
				} else if (p < 0.2 && !uncapped.isEmpty() && JournalEntry.APPLY.equals(uncapped.getLast().kind())) {
					JournalEntry newest = uncapped.getLast();
					List<JournalChange> undo = new ArrayList<>();
					Set<String> reverted = new HashSet<>();
					for (JournalChange c : newest.changes()) {
						if (JournalChange.APPLIED.equals(c.status()) && c.key().startsWith("vanilla.") && c.after().equals(world.get(c.key()))) {
							world.put(c.key(), c.before());
							reverted.add(c.id());
							undo.add(set(c.key(), c.after(), c.before()).reverting(c.id()));
						}
					}
					if (!undo.isEmpty()) {
						uncapped = HistoryUpdates.revert(uncapped, reverted);
						capped = HistoryUpdates.revert(capped, reverted);
						entry = new JournalEntry("u" + step, at(step), JournalEntry.UNDO, "0.4.0", "26.2", newest.id(), undo);
					}
				} else if (p < 0.27) {
					String before = pending.isEmpty() ? world.get("sodium.s") : pending.getLast().patches().get("s");
					String after = String.valueOf(random.nextInt(5));
					Op op = Op.patchJson(sodiumFile, Map.of("s", after));
					pending.add(op);
					entry = apply("t" + step, step, staged("sodium.s", before, after, op.id()));
				} else if (p < 0.32 && !pending.isEmpty()) {
					world.put("sodium.s", pending.getLast().patches().get("s"));
					pending.clear();
					uncapped = restarted(uncapped);
					capped = restarted(capped);
				} else {
					List<JournalChange> changes = new ArrayList<>();
					for (String key : keys) {
						if (random.nextInt(3) == 0) {
							String after = String.valueOf(random.nextInt(5));
							if (!after.equals(world.get(key))) {
								changes.add(set(key, world.get(key), after));
								world.put(key, after);
							}
						}
					}
					if (!changes.isEmpty()) {
						entry = apply("e" + step, step, changes.toArray(JournalChange[]::new));
					}
				}
				if (entry != null) {
					uncapped = appended(uncapped, entry);
					capped = Journal.cap(appended(capped, entry));
				}
				assertTrue(capped.size() <= Journal.MAX_ENTRIES, "seed " + seed);
			}
			UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
			state.settings.putAll(world);
			assertSamePlan(UndoPlanner.plan(uncapped, pending, state, true), UndoPlanner.plan(capped, pending, state, true), "seed " + seed);
		}
	}

	// The helper ran every pending op: staged changes are applied.
	private static List<JournalEntry> restarted(List<JournalEntry> entries) {
		return HistoryUpdates.map(entries, c -> JournalChange.STAGED.equals(c.status()) ? c.withStatus(JournalChange.APPLIED) : c);
	}
}
