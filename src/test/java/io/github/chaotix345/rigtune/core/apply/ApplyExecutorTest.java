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
import java.util.List;
import java.util.Map;

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
		assertEquals(List.of(clash, missing), PendingActions.load(pending).ops());
		assertEquals(result, ApplyResult.load(ApplyResult.defaultPath(config)));
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
