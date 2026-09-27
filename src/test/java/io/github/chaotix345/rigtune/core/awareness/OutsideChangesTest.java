package io.github.chaotix345.rigtune.core.awareness;

import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4h (AC4h.1; lm §2): settings changed outside the game, a one-shot start-up comparison. At a clean exit
// the values of the vanilla keys RigTune applied (from the journal) are stored; at the next start options.txt is compared
// with them, once. Unchanged -> nothing; a change made in game before the exit -> nothing (it's in the snapshot); a change
// made outside -> flagged; no snapshot (a crash, a kill) -> no comparison; fullscreen and keys RigTune never applied
// ignored; values capped and sanitised.
class OutsideChangesTest {
	@TempDir
	Path config;

	private static JournalEntry apply(String id, JournalChange... changes) {
		return new JournalEntry(id, "2026-09-20T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null, List.of(changes));
	}

	private static JournalChange set(String key, String before, String after) {
		return JournalChange.setting(key, before, after, JournalChange.APPLIED, null);
	}

	// The journal: RigTune set clouds to fast and max FPS to 120 (and graphicsPreset's side effect on ao, which RigTune
	// can't apply again: not a changeable key).
	private static final List<JournalEntry> JOURNAL = List.of(apply("e1", set("vanilla.renderClouds", "fancy", "fast"), set("vanilla.maxFps", "260", "120"),
			set("vanilla.ao", "true", "false")));

	private static List<String> options(String... lines) {
		List<String> out = new ArrayList<>(List.of("version:4671", "key_key.attack:key.mouse.left", "fullscreen:false"));
		out.addAll(List.of(lines));
		return out;
	}

	private static List<OutsideOptions.Change> startUp(Map<String, String> vanillaAtExit, List<String> optionsAtStart) {
		Map<String, String> applied = OutsideOptions.applied(JOURNAL);
		Map<String, String> snapshot = OutsideOptions.snapshot(applied.keySet(), vanillaAtExit);
		return OutsideOptions.compare(snapshot, OutsideOptions.parseOptions(optionsAtStart), applied);
	}

	@Test
	void theKeysRigTuneAppliedAndItsValues() {
		assertEquals(Map.of("vanilla.renderClouds", "fast", "vanilla.maxFps", "120"), OutsideOptions.applied(JOURNAL));
	}

	@Test
	void unchangedValuesAreNothing() {
		assertEquals(List.of(), startUp(Map.of("renderClouds", "fast", "maxFps", "120"), options("renderClouds:\"fast\"", "maxFps:120")));
	}

	@Test
	void aChangeMadeInGameBeforeACleanExitIsNothing() {
		assertEquals(List.of(), startUp(Map.of("renderClouds", "fancy", "maxFps", "120"), options("renderClouds:\"fancy\"", "maxFps:120")));
	}

	@Test
	void aChangeMadeOutsideIsFlagged() {
		List<OutsideOptions.Change> changes = startUp(Map.of("renderClouds", "fast", "maxFps", "120"), options("renderClouds:\"fancy\"", "maxFps:120"));
		assertEquals(List.of(new OutsideOptions.Change("vanilla.renderClouds", "fast", "fancy", "fast")), changes);
	}

	@Test
	void theValueRigTuneWouldApplyAgainIsItsLatest() {
		List<JournalEntry> journal = List.of(apply("e1", set("vanilla.maxFps", "260", "120")), apply("e2", set("vanilla.maxFps", "120", "60")));
		assertEquals(Map.of("vanilla.maxFps", "60"), OutsideOptions.applied(journal));
		Map<String, String> snapshot = OutsideOptions.snapshot(Set.of("vanilla.maxFps"), Map.of("maxFps", "90"));
		assertEquals(List.of(new OutsideOptions.Change("vanilla.maxFps", "90", "260", "60")),
				OutsideOptions.compare(snapshot, OutsideOptions.parseOptions(options("maxFps:260")), OutsideOptions.applied(journal)));
	}

	@Test
	void anUndoneKeyIsNotWatched() {
		JournalChange applied = set("vanilla.maxFps", "260", "120").withStatus(JournalChange.REVERTED);
		JournalChange undo = set("vanilla.maxFps", "120", "260").reverting(applied.id());
		List<JournalEntry> journal = List.of(apply("e1", applied), new JournalEntry("u1", "2026-09-21T10:00:00Z", JournalEntry.UNDO, "0.5.0", "26.2", "e1",
				List.of(undo)));
		assertEquals(Map.of(), OutsideOptions.applied(journal));
	}

	@Test
	void noSnapshotNoComparison() {
		assertEquals(List.of(), OutsideOptions.compare(null, OutsideOptions.parseOptions(options("renderClouds:\"fancy\"")), OutsideOptions.applied(JOURNAL)));
		assertNull(AwarenessStore.shared(config).takeOptionsAtExit(), "a first launch, a crash or a kill left none");
	}

	@Test
	void theSnapshotIsConsumedAfterOneComparison() {
		AwarenessStore store = AwarenessStore.shared(config);
		assertTrue(store.setOptionsAtExit(OutsideOptions.snapshot(Set.of("vanilla.renderClouds"), Map.of("renderClouds", "fast"))));
		Map<String, String> taken = store.takeOptionsAtExit();
		assertEquals(Map.of("vanilla.renderClouds", "fast"), taken);
		assertEquals(1, OutsideOptions.compare(taken, OutsideOptions.parseOptions(options("renderClouds:\"fancy\"")), OutsideOptions.applied(JOURNAL)).size());
		assertNull(store.takeOptionsAtExit(), "compared once");
		assertFalse(store.read().has(AwarenessStore.OPTIONS_AT_EXIT));
	}

	@Test
	void fullscreenIsIgnored() {
		List<JournalEntry> journal = List.of(apply("e1", set("vanilla.fullscreen", "false", "true"), set("vanilla.renderClouds", "fancy", "fast")));
		Map<String, String> applied = OutsideOptions.applied(journal);
		assertFalse(applied.containsKey("vanilla.fullscreen"));
		Map<String, String> edited = Map.of("vanilla.fullscreen", "true", "vanilla.renderClouds", "fast");
		assertEquals(List.of(), OutsideOptions.compare(edited, OutsideOptions.parseOptions(List.of("fullscreen:false", "renderClouds:\"fast\"")), Map.of(
				"vanilla.fullscreen", "true", "vanilla.renderClouds", "fast")), "even if a hand-edited file lists it");
		assertEquals(Map.of("vanilla.renderClouds", "fast"), OutsideOptions.snapshot(Set.of("vanilla.fullscreen", "vanilla.renderClouds"),
				Map.of("fullscreen", "true", "renderClouds", "fast")));
	}

	@Test
	void aKeyRigTuneNeverAppliedIsIgnored() {
		Map<String, String> edited = Map.of("vanilla.renderClouds", "fast", "vanilla.gamma", "0.5");
		List<OutsideOptions.Change> changes = OutsideOptions.compare(edited, OutsideOptions.parseOptions(options("renderClouds:\"fast\"", "gamma:1.0")),
				OutsideOptions.applied(JOURNAL));
		assertEquals(List.of(), changes);
		assertEquals(Map.of("vanilla.renderClouds", "fast"), OutsideOptions.snapshot(OutsideOptions.applied(JOURNAL).keySet(),
				Map.of("renderClouds", "fast", "gamma", "0.5")), "maxFps isn't in the game's options here; gamma was never applied");
	}

	@Test
	void anOverLongOrUnsafeValueIsCappedAndSanitised() {
		String longValue = "x".repeat(40);
		assertEquals("x".repeat(OutsideOptions.MAX_VALUE), OutsideOptions.clean(longValue));
		assertEquals("fastfancy", OutsideOptions.clean("fast\u0000\nfancy"));
		assertNull(OutsideOptions.clean(null));
		Map<String, String> snapshot = OutsideOptions.snapshot(Set.of("vanilla.renderClouds"), Map.of("renderClouds", "fast\u0007"));
		assertEquals(Map.of("vanilla.renderClouds", "fast"), snapshot);
		List<OutsideOptions.Change> changes = OutsideOptions.compare(snapshot, OutsideOptions.parseOptions(options("renderClouds:\"" + longValue + "\"")),
				OutsideOptions.applied(JOURNAL));
		assertEquals(List.of(new OutsideOptions.Change("vanilla.renderClouds", "fast", "x".repeat(32), "fast")), changes);
		// A journaled value RigTune couldn't apply again as it is (unsafe, over-long) isn't watched.
		assertEquals(Map.of(), OutsideOptions.applied(List.of(apply("e1", set("vanilla.renderClouds", "fancy", "fast\u0000"),
				set("vanilla.maxFps", "260", "1".repeat(33))))));
	}

	@Test
	void theSnapshotHoldsAtMost64Keys() {
		Map<String, String> now = new LinkedHashMap<>();
		List<String> keys = new ArrayList<>();
		for (int i = 0; i < 80; i++) {
			now.put("k" + i, "v");
			keys.add("vanilla.k" + i);
		}
		assertEquals(AwarenessStore.MAX_OPTIONS_AT_EXIT, OutsideOptions.snapshot(Set.copyOf(keys), now).size());
	}

	@Test
	void optionsTxtIsReadAsTheGameReadsIt() {
		Map<String, String> read = OutsideOptions.parseOptions(List.of("renderClouds:\"fast\"", "maxFps:120", "entityDistanceScaling:1.0",
				"key_key.attack:key.mouse.left", "lang:\"en_us\"", "broken line", ":empty", "quoted:\"a\\\"b\""));
		assertEquals("fast", read.get("renderClouds"));
		assertEquals("120", read.get("maxFps"));
		assertEquals("1.0", read.get("entityDistanceScaling"));
		assertEquals("key.mouse.left", read.get("key_key.attack"));
		assertEquals("a\"b", read.get("quoted"));
		assertFalse(read.containsKey("broken line"));
		assertFalse(read.containsKey(""));
	}
}
