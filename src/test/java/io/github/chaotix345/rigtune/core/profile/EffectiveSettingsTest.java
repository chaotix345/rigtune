package io.github.chaotix345.rigtune.core.profile;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/research/v0.4/audit-apply-pipeline.md M1: profile switches compare a staged key with the value the next restart
// leaves (the last still-pending patch op), not with its file.
class EffectiveSettingsTest {
	private static final String KEY = "sodium.performance.chunk_build_defer_mode";
	private static final String IN_FILE = "performance.chunk_build_defer_mode";

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
		journal = new Journal(config, "0.4.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	@Test
	void theLastPendingOpForAKeyWins() {
		SettingsSnapshot files = new SettingsSnapshot(Map.of(KEY, "ONE_FRAME", "vanilla.renderDistance", "12", "sodium.performance.use_entity_culling", "true"));
		List<Op> pending = List.of(Op.patchJson(sodium, Map.of(IN_FILE, "ALWAYS")), Op.patchJson(sodium, Map.of(IN_FILE, "ZERO_FRAMES")),
				Op.patchJson(sodium, Map.of("performance.new_key", "1")), Op.patchJson(config.resolve("other.json"), Map.of(IN_FILE, "X")),
				Op.disableFile(mods.resolve("x.jar")));
		SettingsSnapshot effective = EffectiveSettings.of(files, pending, Map.of("sodium.", sodium));
		assertEquals("ZERO_FRAMES", effective.get(KEY));
		assertEquals("12", effective.get("vanilla.renderDistance"));
		assertEquals("true", effective.get("sodium.performance.use_entity_culling"));
		assertTrue(!effective.has("sodium.performance.new_key"), "a staged key the file lacks isn't added");
		assertEquals(files, EffectiveSettings.of(files, List.of(), Map.of("sodium.", sodium)));
	}

	@Test
	void aToBToABeforeARestartEndsAtAWithBothSwitchesJournaled() throws IOException {
		List<Recommendation> first = switchTo("ALWAYS", "entry-battery");
		assertEquals(List.of(new Action.SetSetting(KEY, "ONE_FRAME", "ALWAYS")), first.stream().map(Recommendation::action).toList());
		// The file still says ONE_FRAME, so judged by the file the second switch would do nothing and ALWAYS would apply.
		assertEquals(List.of(), ProfileSwitch.build(Map.of(KEY, "ONE_FRAME"), fileSnapshot(), Set.of("sodium"), Map.of(), "My settings"));
		List<Recommendation> second = switchTo("ONE_FRAME", "entry-mine");
		assertEquals(List.of(new Action.SetSetting(KEY, "ALWAYS", "ONE_FRAME")), second.stream().map(Recommendation::action).toList());

		List<JournalEntry> entries = journal.entries();
		assertEquals(List.of("entry-battery", "entry-mine"), entries.stream().map(JournalEntry::id).toList());
		JournalChange a = entries.get(0).changes().getFirst();
		JournalChange b = entries.get(1).changes().getFirst();
		assertEquals(List.of("ONE_FRAME", "ALWAYS", JournalChange.STAGED), List.of(a.before(), a.after(), a.status()));
		assertEquals(List.of("ALWAYS", "ONE_FRAME", JournalChange.STAGED), List.of(b.before(), b.after(), b.status()));

		// The helper at exit applies both ops in order: the file ends at A.
		new ApplyExecutor(2, 1).run(PendingActions.load(pendingFile), pendingFile);
		assertEquals("ONE_FRAME", fileSnapshot().get(KEY));
	}

	private SettingsSnapshot fileSnapshot() throws IOException {
		Map<String, String> flat = SodiumConfigPatcher.flatten(JsonParser.parseString(Files.readString(sodium, StandardCharsets.UTF_8)).getAsJsonObject(),
				"sodium.");
		return new SettingsSnapshot(flat);
	}

	// A switch as ProfileService and RealController.apply do it for a staged key: effective values -> recommendations ->
	// Sodium stager -> merge into pending.json -> journal (StagedChanges, as Staging records it).
	private List<Recommendation> switchTo(String target, String entryId) throws IOException {
		PendingActions base = Files.exists(pendingFile) ? PendingActions.load(pendingFile) : PendingActions.create(1, mods, config, List.of());
		SettingsSnapshot effective = EffectiveSettings.of(fileSnapshot(), base.ops(), Map.of("sodium.", sodium));
		List<Recommendation> recs = ProfileSwitch.build(Map.of(KEY, target), effective, Set.of("sodium"), Map.of(), "x");
		List<Op> ops = new ArrayList<>();
		for (Recommendation rec : recs) {
			Action.SetSetting set = (Action.SetSetting) rec.action();
			ops.addAll(SodiumConfigPatcher.stage(sodium, Map.of(set.key().substring("sodium.".length()), set.newValue())).ops());
		}
		PendingActions.Merged merged = base.merge(ops);
		merged.plan().save(pendingFile);
		StagedChanges.ConfigKeys keys = new StagedChanges.ConfigKeys() {
			@Override
			public String key(Op op, String keyInFile) {
				return "sodium." + keyInFile;
			}

			@Override
			public String current(Op op, String keyInFile) {
				try {
					return fileSnapshot().get("sodium." + keyInFile);
				} catch (IOException e) {
					throw new AssertionError(e);
				}
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
		return recs;
	}
}
