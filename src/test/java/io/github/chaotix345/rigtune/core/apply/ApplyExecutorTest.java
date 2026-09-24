package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyExecutorTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path pending;
	final ApplyExecutor executor = new ApplyExecutor(2, 1);

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
	}

	private PendingActions plan(Op... ops) throws IOException {
		PendingActions plan = PendingActions.create(1, mods, config, List.of(ops));
		plan.save(pending);
		return plan;
	}

	private static List<Status> statuses(ApplyResult result) {
		return result.results().stream().map(ApplyResult.OpResult::status).toList();
	}

	@Test
	void appliesPlanAndIsIdempotent() throws IOException {
		Files.writeString(mods.resolve("sodium-0.9.2.jar.rigtune-pending"), "new");
		Files.writeString(mods.resolve("sodium-0.9.1.jar"), "old");
		Path sodium = config.resolve("sodium-options.json");
		Files.writeString(sodium, "{\"performance\":{\"chunk_builder_threads\":0,\"use_fog_occlusion\":true}}");
		PendingActions plan = plan(
				Op.disableFile(mods.resolve("sodium-0.9.1.jar")),
				Op.enableFile(mods.resolve("sodium-0.9.2.jar.rigtune-pending"), mods.resolve("sodium-0.9.2.jar")),
				Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4")));

		ApplyResult first = executor.run(plan, pending);

		assertEquals(List.of(Status.OK, Status.OK, Status.OK), statuses(first));
		assertEquals("new", Files.readString(mods.resolve("sodium-0.9.2.jar")));
		assertEquals("old", Files.readString(mods.resolve("sodium-0.9.1.jar.disabled")));
		assertFalse(Files.exists(mods.resolve("sodium-0.9.2.jar.rigtune-pending")));
		assertFalse(Files.exists(mods.resolve("sodium-0.9.1.jar")));
		assertEquals("4", SodiumConfigPatcher.flatten(
				JsonParser.parseString(Files.readString(sodium)).getAsJsonObject(), "").get("performance.chunk_builder_threads"));
		assertFalse(Files.exists(pending));
		assertEquals(first, ApplyResult.load(ApplyResult.defaultPath(config)));

		ApplyResult second = executor.run(plan, pending);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.SKIPPED_ALREADY_DONE, Status.SKIPPED_ALREADY_DONE), statuses(second));
		assertTrue(second.allSucceeded());
		try (var files = Files.list(mods)) {
			assertEquals(2, files.count());
		}
	}

	@Test
	void disableAvoidsCollisionsWithNumberedSuffix() throws IOException {
		Files.writeString(mods.resolve("lithium.jar"), "current");
		Files.writeString(mods.resolve("lithium.jar.disabled"), "older");
		Files.writeString(mods.resolve("lithium.jar.disabled.1"), "oldest");

		ApplyResult result = executor.run(plan(Op.disableFile(mods.resolve("lithium.jar"))), pending);

		assertEquals(List.of(Status.OK), statuses(result));
		assertEquals("current", Files.readString(mods.resolve("lithium.jar.disabled.2")));
		assertEquals("older", Files.readString(mods.resolve("lithium.jar.disabled")));
		assertEquals("oldest", Files.readString(mods.resolve("lithium.jar.disabled.1")));
	}

	@Test
	void failedOpsStayPendingAndNothingIsOverwritten() throws IOException {
		Files.writeString(mods.resolve("a.jar.rigtune-pending"), "new a");
		Files.writeString(mods.resolve("a.jar"), "existing a");
		Files.writeString(mods.resolve("b.jar"), "b");
		Op clash = Op.enableFile(mods.resolve("a.jar.rigtune-pending"), mods.resolve("a.jar"));
		Op missing = Op.enableFile(mods.resolve("gone.jar.rigtune-pending"), mods.resolve("gone.jar"));
		Op disable = Op.disableFile(mods.resolve("b.jar"));

		ApplyResult result = executor.run(plan(clash, disable, missing), pending);

		assertEquals(List.of(Status.FAILED, Status.OK, Status.FAILED), statuses(result));
		assertFalse(result.allSucceeded());
		assertEquals("existing a", Files.readString(mods.resolve("a.jar")));
		assertEquals("new a", Files.readString(mods.resolve("a.jar.rigtune-pending")));
		assertEquals(List.of(clash.withAttempts(1), missing.withAttempts(1)), PendingActions.load(pending).ops());
		assertEquals(result, ApplyResult.load(ApplyResult.defaultPath(config)));
	}

	@Test
	void refusesOpsOutsideTheModsAndConfigFolders() throws IOException {
		Path outside = Files.createDirectories(dir.resolve("outside"));
		Path payload = Files.writeString(mods.resolve("evil.jar.rigtune-pending"), "payload");
		Path victim = Files.writeString(outside.resolve("victim.jar"), "victim");
		Files.writeString(mods.resolve("keep.jar"), "keep");
		PendingActions plan = plan(
				Op.enableFile(payload, mods.resolve("..").resolve("outside").resolve("evil.jar")),
				Op.enableFile(payload, outside.resolve("evil.jar")),
				Op.enableFile(payload, mods.resolve("run.bat")),
				Op.enableFile(payload, mods.resolve("NUL.jar")),
				Op.enableFile(payload, mods.resolve("sub").resolve("evil.jar")),
				Op.enableFile(victim, mods.resolve("victim.jar")),
				Op.disableFile(victim),
				Op.disableFile(mods.resolve("..").resolve("outside").resolve("victim.jar")),
				Op.disableFile(mods),
				Op.patchJson(outside.resolve("x.json"), Map.of("a", "1")),
				Op.patchJson(config.resolve("..").resolve("outside").resolve("x.json"), Map.of("a", "1")),
				Op.patchJson(config, Map.of("a", "1")),
				new Op(PendingActions.Type.ENABLE_FILE, null, mods.resolve("a.jar").toString(), null, null),
				new Op(PendingActions.Type.DISABLE_FILE, null, null, null, null),
				new Op(PendingActions.Type.DISABLE_FILE, null, null, "bad\u0000path", null));

		ApplyResult result = executor.run(plan, pending);

		for (ApplyResult.OpResult r : result.results()) {
			assertEquals(Status.FAILED, r.status(), r.toString());
			assertTrue(r.message().startsWith("Refused") || r.message().contains("Invalid"), r.message());
		}
		assertEquals("payload", Files.readString(payload));
		assertEquals("victim", Files.readString(victim));
		assertEquals("keep", Files.readString(mods.resolve("keep.jar")));
		try (var files = Files.list(outside)) {
			assertEquals(List.of(victim), files.toList());
		}
		try (var files = Files.list(mods)) {
			assertEquals(2, files.count());
		}
		assertEquals(plan.ops().size(), PendingActions.load(pending).ops().size());
	}

	@Test
	void foldersComeFromWhereThePlanIsNotFromWhatItRecords() throws IOException {
		Path jar = Files.writeString(mods.resolve("a.jar"), "a");
		Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
		PendingActions plan = new PendingActions("2026-09-24T00:00:00Z", 1, elsewhere.toString(), null, List.of(Op.disableFile(jar),
				Op.patchJson(config.resolve("sodium-options.json"), Map.of("a", "1"))));

		ApplyResult result = executor.run(plan, pending);

		assertEquals(List.of(Status.OK, Status.OK), statuses(result));
		assertTrue(Files.exists(mods.resolve("a.jar.disabled")));
		assertTrue(Files.exists(config.resolve("sodium-options.json")));
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else {
					Files.copy(file, target);
				}
			}
		}
	}

	// Prism's "Copy instance" (or a moved folder) copies pending.json, which still names the original's folders.
	@Test
	void aCopiedInstanceAppliesOnlyItsOwnChangesAndLeavesTheOriginalAlone() throws IOException {
		Path original = dir.resolve("original");
		Path origMods = Files.createDirectories(original.resolve("mods"));
		Path origConfig = Files.createDirectories(original.resolve("config"));
		Files.writeString(origMods.resolve("sodium-0.7.0.jar"), "old");
		Files.writeString(origMods.resolve("sodium-0.7.1.jar.rigtune-pending"), "new");
		PendingActions.create(1, origMods, origConfig, PendingActions.group(Op.disableFile(origMods.resolve("sodium-0.7.0.jar")),
				Op.enableFile(origMods.resolve("sodium-0.7.1.jar.rigtune-pending"), origMods.resolve("sodium-0.7.1.jar")).withModId("sodium")))
				.save(PendingActions.defaultPath(origConfig));
		Path copy = dir.resolve("copy");
		copyTree(original, copy);
		Path copyMods = copy.resolve("mods");
		Path copyPending = PendingActions.defaultPath(copy.resolve("config"));
		Path indium = Files.writeString(copyMods.resolve("indium.jar"), "indium");
		PendingActions copied = PendingActions.load(copyPending);
		assertEquals(origMods.toString(), copied.modsDir());

		// Staging in the copy drops the original's ops and records the copy's folders.
		PendingActions relocated = copied.relocated(InstanceDirs.modsDirOf(copyPending), InstanceDirs.configDirOf(copyPending));
		assertEquals(List.of(), relocated.ops());
		assertEquals(InstanceDirs.modsDirOf(copyPending).toString(), relocated.modsDir());

		// The helper, given the copied plan as it is, refuses the original's ops and applies the copy's own.
		Op own = Op.disableFile(indium);
		List<Op> ops = new ArrayList<>(copied.ops());
		ops.add(own);
		copied.withOps(ops).save(copyPending);

		ApplyResult result = executor.run(PendingActions.load(copyPending), copyPending);

		assertEquals(List.of(Status.FAILED, Status.FAILED, Status.OK), statuses(result));
		assertTrue(result.results().stream().limit(2).allMatch(r -> r.message().contains("refused") || r.message().startsWith("Refused")),
				result.toString());
		assertTrue(Files.exists(copyMods.resolve("indium.jar.disabled")));
		for (Path instanceMods : List.of(origMods, copyMods)) {
			assertTrue(Files.exists(instanceMods.resolve("sodium-0.7.0.jar")), instanceMods.toString());
			assertTrue(Files.exists(instanceMods.resolve("sodium-0.7.1.jar.rigtune-pending")), instanceMods.toString());
			assertFalse(Files.exists(instanceMods.resolve("sodium-0.7.1.jar")), instanceMods.toString());
		}
		assertTrue(Files.exists(ApplyResult.defaultPath(copy.resolve("config"))));
		assertFalse(Files.exists(ApplyResult.defaultPath(origConfig)));
		assertEquals(2, PendingActions.load(PendingActions.defaultPath(origConfig)).ops().size());
	}

	@Test
	void malformedJsonFailsWithoutTouchingFile() throws IOException {
		Path sodium = config.resolve("sodium-options.json");
		Files.writeString(sodium, "{ broken");

		ApplyResult result = executor.run(plan(Op.patchJson(sodium, Map.of("a.b", "1"))), pending);

		assertEquals(List.of(Status.FAILED), statuses(result));
		assertEquals("{ broken", Files.readString(sodium));
		assertTrue(Files.exists(pending));
	}
}
