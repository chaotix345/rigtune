package io.github.chaotix345.rigtune.core.profile;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingNet;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.NetChanges;
import io.github.chaotix345.rigtune.core.history.StagedChanges;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #46, end to end through the staging model as it is (EffectiveSettingsTest's switch: effective values -> recommendations
// -> Sodium stager -> merge into pending.json -> journal), then the helper at exit: what "Discard pending" sees before the
// restart (PendingNet) and what the next start's "RigTune applied N change(s)" counts (NetChanges). Every switch stays
// its own op and journal entry; only the player-facing counts are net.
class StagedNetTest {
	private static final String DEFER = "chunk_build_defer_mode";
	private static final String CULLING = "use_entity_culling";

	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path sodium;
	Path pendingFile;
	Journal journal;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		sodium = config.resolve("sodium-options.json");
		Files.writeString(sodium, "{\"performance\":{\"chunk_build_defer_mode\":\"ONE_FRAME\",\"use_entity_culling\":true}}", StandardCharsets.UTF_8);
		pendingFile = PendingActions.defaultPath(config);
		journal = new Journal(config, "0.5.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	// A -> B -> A: two staged ops would apply at exit, yet nothing changes. No Discard pending, and the toast count is 0, so
	// HelperToasts shows no "applied" toast at all.
	@Test
	void aToBToAShowsNothingPendingAndAppliesNothing() throws IOException {
		switchTo(Map.of(DEFER, "ALWAYS"), "e1");
		switchTo(Map.of(DEFER, "ONE_FRAME"), "e2");
		assertEquals(2, PendingActions.load(pendingFile).ops().size(), "the staging model is unchanged");
		assertFalse(pending());
		assertEquals(new ApplyResult.Counts(0, 0, 0), helperRuns());
		assertEquals(2, journal.entries().size(), "every switch keeps its own History entry");
	}

	@Test
	void aToBToAToBIsOneChange() throws IOException {
		switchTo(Map.of(DEFER, "ALWAYS"), "e1");
		switchTo(Map.of(DEFER, "ONE_FRAME"), "e2");
		switchTo(Map.of(DEFER, "ALWAYS"), "e3");
		assertTrue(pending());
		assertEquals(new ApplyResult.Counts(1, 0, 0), helperRuns());
		assertEquals(3, journal.entries().size());
	}

	@Test
	void twoKeysAreTwoChanges() throws IOException {
		switchTo(Map.of(DEFER, "ALWAYS", CULLING, "false"), "e1");
		assertTrue(pending());
		assertEquals(new ApplyResult.Counts(2, 0, 0), helperRuns());
	}

	// One key back where it started and another changed: Discard pending stays, and the toast says 1.
	@Test
	void onlyTheKeysThatEndUpChangedCount() throws IOException {
		switchTo(Map.of(DEFER, "ALWAYS", CULLING, "false"), "e1");
		switchTo(Map.of(DEFER, "ONE_FRAME"), "e2");
		assertTrue(pending());
		assertEquals(new ApplyResult.Counts(1, 0, 0), helperRuns());
	}

	private boolean pending() throws IOException {
		return PendingNet.changesSomething(PendingActions.load(pendingFile).ops(), (op, keyInFile) -> file().get("sodium." + keyInFile));
	}

	private ApplyResult.Counts helperRuns() throws IOException {
		new ApplyExecutor(2, 1).run(PendingActions.load(pendingFile), pendingFile);
		return NetChanges.counts(ApplyResult.load(ApplyResult.defaultPath(config)), journal.entries());
	}

	private SettingsSnapshot file() {
		try {
			return new SettingsSnapshot(SodiumConfigPatcher.flatten(JsonParser.parseString(Files.readString(sodium, StandardCharsets.UTF_8)).getAsJsonObject(),
					"sodium."));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// As EffectiveSettingsTest.switchTo, for several keys of the Performance page.
	private void switchTo(Map<String, String> targets, String entryId) throws IOException {
		PendingActions base = Files.exists(pendingFile) ? PendingActions.load(pendingFile) : PendingActions.create(1, mods, config, List.of());
		SettingsSnapshot effective = EffectiveSettings.of(file(), base.ops(), Map.of("sodium.", sodium));
		Map<String, String> wanted = new java.util.LinkedHashMap<>();
		targets.forEach((k, v) -> wanted.put("sodium.performance." + k, v));
		List<Recommendation> recs = ProfileSwitch.build(wanted, effective, Set.of("sodium"), Map.of(), "x");
		Map<String, String> patches = new java.util.LinkedHashMap<>();
		for (Recommendation rec : recs) {
			Action.SetSetting set = (Action.SetSetting) rec.action();
			patches.put(set.key().substring("sodium.".length()), set.newValue());
		}
		List<Op> ops = new ArrayList<>(SodiumConfigPatcher.stage(sodium, patches).ops());
		PendingActions.Merged merged = base.merge(ops);
		merged.plan().save(pendingFile);
		StagedChanges.ConfigKeys keys = new StagedChanges.ConfigKeys() {
			@Override
			public String key(Op op, String keyInFile) {
				return "sodium." + keyInFile;
			}

			@Override
			public String current(Op op, String keyInFile) {
				return file().get("sodium." + keyInFile);
			}
		};
		Set<String> journaled = new HashSet<>();
		journal.entries().forEach(e -> e.changes().forEach(c -> {
			if (JournalChange.STAGED.equals(c.status()) && c.opId() != null) {
				journaled.add(c.opId());
			}
		}));
		StagedChanges.Outcome outcome = StagedChanges.of(base, ops, merged, keys, path -> null, journaled);
		assertTrue(journal.update(entries -> journal.withChanges(entries, entryId, JournalEntry.APPLY, outcome.changes())));
	}
}
