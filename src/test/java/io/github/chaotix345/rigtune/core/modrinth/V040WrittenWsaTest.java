package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.StagedChanges;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md amendment H-M1 and PLAN "Wave A" (the v040-written convention): WS-A's "written by 0.4" set,
// src/test/resources/v040-written/ws-a/, produced here by the real planner, staging, helper and journal code: an Apply
// whose disable the helper applied (modName on the change, 2c) and a later Apply whose Modrinth addition is still staged
// (projectId/versionId on the ENABLE_FILE op, 2d; modName on its change). Paths start with ${INSTANCE} and use '/', ids
// are fixed UUIDs, times are in the past. The committed files must match what the code writes now: to regenerate after a
// deliberate change, delete them and run this test. Then 0.1.0's and 0.3.0's own readers (the pinned copies) read them
// (AC2c.2, AC2d.3); WS-H's released-jar harness and downgrade run use the same files.
class V040WrittenWsaTest {
	private static final Path SET = Path.of("src/test/resources/v040-written/ws-a");
	private static final String TOKEN = "${INSTANCE}";
	private static final List<String> TIMES = List.of("2026-09-20T09:00:00Z", "2026-09-20T10:00:05Z");
	private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	// The ids in order of first appearance (entry, change, op, group; then the second Apply's), the same as WS-H's
	// placeholder set used where it has them, so the harness's own tests keep their fixed ids.
	private static final List<String> FIXED_IDS = List.of("5d1c7a90-2e4b-4f6a-8b3c-1a9e0d7f2c11", "6e2d8ba1-3f5c-4a7b-9c4d-2b0f1e803d12",
			"7f3e9cb2-4a6d-4b8c-8d5e-3c1a2f914e13", "7f3e9cb2-4a6d-4b8c-8d5e-3c1a2f914e14", "8a4f0dc3-5b7e-4c9d-9e6f-4d2b3a025f14",
			"9b5a1ed4-6c8f-4dae-8f70-5e3c4b136a15", "0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a01", "0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a02");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final StagedChanges.ConfigKeys NO_CONFIG = new StagedChanges.ConfigKeys() {
		@Override
		public String key(Op op, String keyInFile) {
			return null;
		}

		@Override
		public String current(Op op, String keyInFile) {
			return null;
		}
	};

	@TempDir
	Path dir;

	private Path instance;
	private Path mods;
	private Path config;
	private Path pendingFile;
	private Journal journal;

	@Test
	void theCommittedSetIsWhatThisVersionWritesAndOlderReadersReadIt() throws Exception {
		Map<String, String> written = write();
		for (Map.Entry<String, String> file : written.entrySet()) {
			Path committed = RepoFiles.resolve(SET.resolve(file.getKey()).toString());
			if (!Files.exists(committed)) {
				Files.createDirectories(committed.getParent());
				Files.writeString(committed, file.getValue(), StandardCharsets.UTF_8);
			}
			assertEquals(file.getValue(), Files.readString(committed, StandardCharsets.UTF_8).replace("\r\n", "\n"),
					committed + " is not what 0.4 writes now; delete it and rerun this test to regenerate it");
		}
		olderReadersReadTheSet();
	}

	// The two Applies through the real code; returns the files as committed (normalised).
	private Map<String, String> write() throws Exception {
		instance = dir.resolve("instance");
		mods = Files.createDirectories(instance.resolve("mods"));
		config = Files.createDirectories(instance.resolve("config"));
		pendingFile = PendingActions.defaultPath(config);
		journal = new Journal(config, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});

		// Apply 1: disable the installed e2e-named mod; the helper applies it at the next exit.
		Path named = TestJars.modJar(mods.resolve("e2e-named-1.0.0.jar"), "e2e-named", "E2E Named Mod");
		stage(PendingActions.group(Op.disableFile(named)), ChangeRecorder.newEntryId());
		ApplyResult applied = new ApplyExecutor(2, 1).run(PendingActions.load(pendingFile), pendingFile);
		assertTrue(applied.results().stream().allMatch(r -> r.status() == ApplyResult.Status.OK), applied.toString());
		assertTrue(journal.update(entries -> HistoryUpdates.applyResults(entries, applied.results())));

		// Apply 2: add e2e-seed from (a stand-in for) Modrinth; still staged.
		FakeModrinthClient modrinth = new FakeModrinthClient();
		ModrinthVersion seed = FakeModrinthClient.version("E2ESeedV", "E2ESeed1", "1.0.0", Instant.parse("2026-09-01T00:00:00Z"));
		ModrinthVersion seedFile = new ModrinthVersion(seed.id(), seed.projectId(), seed.versionNumber(), seed.versionType(), seed.gameVersions(),
				seed.loaders(), seed.datePublished(), List.of(new ModrinthFile("https://cdn/e2e-seed-1.0.0.jar", "e2e-seed-1.0.0.jar", "sha1", "sha512", 10,
						true)), List.of());
		modrinth.latestByProject.put("E2ESeed1", seedFile);
		DownloadPlanner planner = new DownloadPlanner(new DependencyResolver(modrinth, "fabric", "26.2").withStaged(StagedProjects.read(pendingFile)), mods,
				(ModFile file) -> TestJars.modJar(SafeFileNames.resolveJar(mods, file.filename(), PendingActions.PENDING_SUFFIX), "e2e-seed", "E2E Seed Mod"));
		DownloadPlanner.Result result = planner.plan(List.of(new Recommendation("add:e2e-seed", Category.ADD_MOD, Impact.HIGH, "Install E2E Seed", "",
				new Action.AddMod("e2e-seed", "E2ESeed1", "E2E Seed"), true)), Set.of(), Set.of(), Map.of());
		assertEquals(List.of(), result.errors());
		stage(result.ops(), ChangeRecorder.newEntryId());

		Map<String, String> uuids = new LinkedHashMap<>();
		Map<String, String> out = new LinkedHashMap<>();
		out.put("history.json", normalise(Journal.file(config), uuids, json -> {
			JsonArray entries = json.getAsJsonArray("entries");
			for (int i = 0; i < entries.size(); i++) {
				entries.get(i).getAsJsonObject().addProperty("at", TIMES.get(i));
			}
		}));
		out.put("pending.json", normalise(pendingFile, uuids, json -> {
			json.addProperty("createdAt", TIMES.get(1));
			json.addProperty("gamePid", 4242);
		}));
		return out;
	}

	// As Staging does: merge into pending.json, then journal the changes with the ids they have there (review H5).
	private void stage(List<Op> ops, String entryId) throws IOException {
		PendingActions base = Files.exists(pendingFile) ? PendingActions.load(pendingFile).relocated(mods, config)
				: PendingActions.create(1, mods, config, List.of());
		PendingActions.Merged merged = base.merge(ops);
		Files.createDirectories(pendingFile.getParent());
		merged.plan().save(pendingFile);
		List<JournalChange> changes = StagedChanges.of(base, ops, merged, NO_CONFIG, ModJars::modIdOf, ModJars::nameOf, Set.of()).changes();
		assertTrue(journal.update(entries -> journal.withChanges(entries, entryId, JournalEntry.APPLY, changes)));
	}

	private interface Edit {
		void apply(JsonObject json);
	}

	// Instance paths -> ${INSTANCE} with '/'; every UUID -> a fixed one in order of first appearance (shared across the
	// set, so op ids in history.json still match pending.json); the edit sets times and the pid.
	private String normalise(Path file, Map<String, String> uuids, Edit edit) throws IOException {
		JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		edit.apply(json);
		String prefix = instance.toAbsolutePath().toString();
		JsonElement tokens = paths(json, prefix);
		String text = GSON.toJson(tokens);
		Matcher m = UUID.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String fixed = uuids.computeIfAbsent(m.group(), k -> FIXED_IDS.get(uuids.size()));
			m.appendReplacement(out, fixed);
		}
		m.appendTail(out);
		return out + "\n";
	}

	private static JsonElement paths(JsonElement element, String prefix) {
		if (element.isJsonObject()) {
			JsonObject out = new JsonObject();
			element.getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), paths(e.getValue(), prefix)));
			return out;
		}
		if (element.isJsonArray()) {
			JsonArray out = new JsonArray();
			element.getAsJsonArray().forEach(e -> out.add(paths(e, prefix)));
			return out;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && element.getAsString().startsWith(prefix)) {
			return new JsonPrimitive(TOKEN + element.getAsString().substring(prefix.length()).replace('\\', '/'));
		}
		return element;
	}

	// AC2d.3 and AC2c.2: the pinned 0.1.0 and 0.3.0 readers, on the committed files put into an instance.
	private void olderReadersReadTheSet() throws Exception {
		Path other = Files.createDirectories(dir.resolve("other"));
		Path otherConfig = Files.createDirectories(other.resolve("config").resolve("rigtune"));
		String root = other.toAbsolutePath().toString().replace('\\', '/');
		for (String name : List.of("pending.json", "history.json")) {
			String text = Files.readString(RepoFiles.resolve(SET.resolve(name).toString()), StandardCharsets.UTF_8).replace(TOKEN, root);
			Files.writeString(otherConfig.resolve(name), text, StandardCharsets.UTF_8);
		}
		Path pending = otherConfig.resolve("pending.json");
		PendingActions now = PendingActions.load(pending);
		Op enable = now.ops().getFirst();
		assertEquals(PendingActions.Type.ENABLE_FILE, enable.type());
		assertEquals("E2ESeed1", enable.projectId());
		assertEquals("E2ESeedV", enable.versionId());
		assertEquals(Set.of("E2ESeed1"), StagedProjects.read(pending).projects());

		var v030 = io.github.chaotix345.rigtune.v030.core.apply.PendingActions.load(pending);
		var v010 = io.github.chaotix345.rigtune.v010.core.apply.PendingActions.load(pending);
		assertEquals(1, v030.ops().size());
		assertEquals(1, v010.ops().size());
		assertEquals(List.of(enable.id(), enable.from(), enable.to(), enable.modId(), enable.group()), List.of(v030.ops().getFirst().id(),
				v030.ops().getFirst().from(), v030.ops().getFirst().to(), v030.ops().getFirst().modId(), v030.ops().getFirst().group()));
		assertEquals(List.of(enable.id(), enable.from(), enable.to()), List.of(v010.ops().getFirst().id(), v010.ops().getFirst().from(),
				v010.ops().getFirst().to()));
		assertEquals(io.github.chaotix345.rigtune.v010.core.apply.PendingActions.Type.ENABLE_FILE, v010.ops().getFirst().type());

		Journal current = new Journal(other.resolve("config"), "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(Journal.State.OK, current.state());
		List<JournalEntry> entries = current.entries();
		assertEquals(List.of("E2E Named Mod", "E2E Seed Mod"), entries.stream().map(e -> e.changes().getFirst().modName()).toList());
		assertEquals(List.of(JournalChange.APPLIED, JournalChange.STAGED), entries.stream().map(e -> e.changes().getFirst().status()).toList());
		assertEquals(enable.id(), entries.get(1).changes().getFirst().opId());
		HistoryModel.View view = HistoryModel.build(Journal.State.OK, entries, Map.of(), HistoryModel.Labels.RAW);
		assertEquals(List.of("E2E Seed Mod", "E2E Named Mod"), view.entries().stream().map(e -> e.changes().getFirst().shownName()).toList());

		var old = new io.github.chaotix345.rigtune.v030.core.history.Journal(other.resolve("config"), "0.3.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertEquals(entries.stream().map(JournalEntry::id).toList(), old.entries().stream()
				.map(io.github.chaotix345.rigtune.v030.core.history.JournalEntry::id).toList());
		assertEquals(entries.stream().flatMap(e -> e.changes().stream()).map(JournalChange::id).toList(), old.entries().stream()
				.flatMap(e -> e.changes().stream()).map(io.github.chaotix345.rigtune.v030.core.history.JournalChange::id).toList());
		assertEquals(List.of("e2e-named-1.0.0.jar", "e2e-seed-1.0.0.jar"), old.entries().stream().map(e -> e.changes().getFirst().file()).toList());
	}
}
