package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.v030.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.v030.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.v030.core.history.JournalChange;
import io.github.chaotix345.rigtune.v030.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md "Compatibility promise", docs/v0.4/design/ws-g3.md: WS-G3 changes how the helper runs a group, not
// what it reads or writes. A pending.json 0.3.0 staged runs the same under the new helper as under 0.3.0's own (the pinned
// copies in v030/, verbatim 0.3.0 code); 0.3.0's readers read the new helper's last-apply.json and pending.json after a
// failed rollback and after the run that repairs it as the new code does; and 0.3.0's helper runs the pending.json the new
// one left (a downgrade), whose record of the unfinished group the new helper later drops.
class HelperCompat030Test {
	@TempDir
	Path dir;

	private record Instance(Path root, Path mods, Path config, Path pending) {
	}

	private Instance instance(String name) throws IOException {
		Path root = dir.resolve(name);
		Path mods = Files.createDirectories(root.resolve("mods"));
		Path config = Files.createDirectories(root.resolve("config"));
		Files.writeString(mods.resolve("sodium-0.7.0.jar"), "old");
		TestJars.modJar(mods.resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), "sodium");
		Files.writeString(mods.resolve("lithium.jar"), "lithium");
		Files.writeString(mods.resolve("indium.jar"), "indium");
		TestJars.modJar(mods.resolve("iris.jar" + PendingActions.PENDING_SUFFIX), "iris");
		Files.writeString(mods.resolve("iris.jar"), "someone else's copy");
		return new Instance(root, mods, config, PendingActions.defaultPath(config));
	}

	// Staged by 0.3.0's own PendingActions: an update, a lone disable, and a group whose enable fails (its name is taken),
	// so its disable is rolled back.
	private static void stageWith030(Instance in) throws IOException {
		List<io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op> ops = new ArrayList<>(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.group(
				io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op.disableFile(in.mods().resolve("sodium-0.7.0.jar")),
				io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op.enableFile(in.mods().resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX),
						in.mods().resolve("sodium-0.7.1.jar")).withModId("sodium")));
		ops.add(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op.disableFile(in.mods().resolve("lithium.jar")));
		ops.addAll(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.group(
				io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op.disableFile(in.mods().resolve("indium.jar")),
				io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op.enableFile(in.mods().resolve("iris.jar" + PendingActions.PENDING_SUFFIX),
						in.mods().resolve("iris.jar"))));
		io.github.chaotix345.rigtune.v030.core.apply.PendingActions.create(1, in.mods(), in.config(), ops).save(in.pending());
	}

	private static String escaped(Path root) {
		return root.toString().replace("\\", "\\\\");
	}

	// A file's text with the instance folder replaced, so two instances' files compare.
	private static String portable(Path file, Instance in) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8).replace(escaped(in.root()), "${INSTANCE}");
	}

	private static Map<String, String> modsFolder(Instance in) throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		try (Stream<Path> files = Files.list(in.mods())) {
			for (Path file : files.sorted().toList()) {
				out.put(file.getFileName().toString(), new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
			}
		}
		return out;
	}

	private static JsonElement resultsOf(Path lastApply, Instance in) throws IOException {
		JsonObject json = JsonParser.parseString(portable(lastApply, in)).getAsJsonObject();
		return json.get("results");
	}

	@Test
	void aPendingFileStagedBy030RunsTheSameUnderTheNewHelper() throws IOException {
		Instance ours = instance("new");
		Instance theirs = instance("030");
		stageWith030(ours);
		Files.createDirectories(theirs.pending().getParent());
		Files.writeString(theirs.pending(), Files.readString(ours.pending()).replace(escaped(ours.root()), escaped(theirs.root())));

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(ours.pending()), ours.pending());
		io.github.chaotix345.rigtune.v030.core.apply.ApplyResult old = new io.github.chaotix345.rigtune.v030.core.apply.ApplyExecutor(2, 1)
				.run(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.load(theirs.pending()), theirs.pending());

		assertEquals(List.of(Status.OK, Status.OK, Status.OK, Status.FAILED, Status.FAILED), result.results().stream().map(ApplyResult.OpResult::status).toList());
		assertEquals(old.results().stream().map(r -> r.status().name()).toList(), result.results().stream().map(r -> r.status().name()).toList());
		assertEquals(resultsOf(ApplyResult.defaultPath(theirs.config()), theirs), resultsOf(ApplyResult.defaultPath(ours.config()), ours));
		assertEquals(portable(theirs.pending(), theirs), portable(ours.pending(), ours));
		assertEquals(modsFolder(theirs), modsFolder(ours));
		assertFalse(Files.exists(UnfinishedGroups.file(ours.config())));
	}

	private static List<Op> update(Instance in) {
		return PendingActions.group(Op.disableFile(in.mods().resolve("sodium-0.7.0.jar")),
				Op.enableFile(in.mods().resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), in.mods().resolve("sodium-0.7.1.jar")).withModId("sodium"));
	}

	// The enable and then the rollback are both denied: the group is left half-applied.
	private static ApplyResult failedRollback(Instance in, List<Op> ops) throws IOException {
		Path newPending = in.mods().resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX);
		Path oldJar = in.mods().resolve("sodium-0.7.0.jar");
		PendingActions.create(1, in.mods(), in.config(), ops).save(in.pending());
		return new ApplyExecutor(2, 1, (from, to) -> {
			if (from.equals(newPending) || to.equals(oldJar)) {
				throw new IOException("in use: " + from);
			}
			Files.move(from, to);
		}).run(PendingActions.load(in.pending()), in.pending());
	}

	// What 0.3.0's preLaunch (HistoryStartup.run) makes of the files: reconcile with its own readers.
	private static List<JournalChange> reconciledBy030(Instance in, List<JournalEntry> entries) throws IOException {
		Set<String> pendingIds = !Files.exists(in.pending()) ? Set.of()
				: io.github.chaotix345.rigtune.v030.core.apply.PendingActions.load(in.pending()).ops().stream()
						.map(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op::id).collect(Collectors.toSet());
		return HistoryUpdates.reconcile(entries, pendingIds,
				io.github.chaotix345.rigtune.v030.core.apply.ApplyResult.load(ApplyResult.defaultPath(in.config())).results()).getFirst().changes();
	}

	@Test
	void the030ReadersReadWhatTheNewHelperWritesAfterAFailedRollbackAndItsRepair() throws IOException {
		Instance in = instance("new");
		List<Op> ops = update(in);
		List<JournalEntry> entries = List.of(new JournalEntry("e1", "2026-09-20T09:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(
				JournalChange.file(JournalChange.DISABLE, null, "sodium-0.7.0.jar", JournalChange.STAGED, ops.get(0).id(), ops.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.7.1.jar", JournalChange.STAGED, ops.get(1).id(), ops.get(1).group()))));

		ApplyResult failed = failedRollback(in, ops);

		io.github.chaotix345.rigtune.v030.core.apply.ApplyResult read = io.github.chaotix345.rigtune.v030.core.apply.ApplyResult.load(ApplyResult.defaultPath(in.config()));
		assertEquals(List.of("FAILED", "FAILED"), read.results().stream().map(r -> r.status().name()).toList());
		assertEquals(failed.results().getFirst().resultPath(), read.results().getFirst().resultPath());
		List<ApplyFailures.Failure> failures = ApplyFailures.of(read, List.of(in.mods(), in.config()));
		assertEquals(2, failures.size());
		assertTrue(failures.stream().noneMatch(ApplyFailures.Failure::abandoned));
		assertTrue(failures.getFirst().reason().startsWith("Rollback failed"), failures.getFirst().reason());
		assertEquals(List.of(1, 1), failures.stream().map(ApplyFailures.Failure::attempt).toList());
		assertEquals(List.of(JournalChange.STAGED, JournalChange.STAGED), reconciledBy030(in, entries).stream().map(JournalChange::status).toList());

		ApplyResult repaired = new ApplyExecutor(2, 1).run(PendingActions.load(in.pending()), in.pending());

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), repaired.results().stream().map(ApplyResult.OpResult::status).toList());
		List<JournalChange> after = reconciledBy030(in, entries);
		assertEquals(List.of(JournalChange.APPLIED, JournalChange.APPLIED), after.stream().map(JournalChange::status).toList());
		assertEquals("sodium-0.7.0.jar.disabled", after.getFirst().resultFile());
	}

	@Test
	void the030HelperRunsWhatTheNewHelperLeftPendingAndTheStaleRecordIsDroppedLater() throws IOException {
		Instance in = instance("new");
		failedRollback(in, update(in));
		assertTrue(Files.exists(UnfinishedGroups.file(in.config())));

		io.github.chaotix345.rigtune.v030.core.apply.ApplyResult old = new io.github.chaotix345.rigtune.v030.core.apply.ApplyExecutor(2, 1)
				.run(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.load(in.pending()), in.pending());

		assertEquals(List.of("SKIPPED_ALREADY_DONE", "OK"), old.results().stream().map(r -> r.status().name()).toList());
		assertEquals("sodium", ModJars.readModId(in.mods().resolve("sodium-0.7.1.jar")));
		assertFalse(Files.exists(in.pending()));

		PendingActions.create(1, in.mods(), in.config(), List.of(Op.disableFile(in.mods().resolve("lithium.jar")))).save(in.pending());
		new ApplyExecutor(2, 1).run(PendingActions.load(in.pending()), in.pending());

		assertFalse(Files.exists(UnfinishedGroups.file(in.config())));
	}
}
