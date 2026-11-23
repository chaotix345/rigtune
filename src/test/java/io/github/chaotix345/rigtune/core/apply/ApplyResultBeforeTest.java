package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// OpResult.before (0.6): what each config patch found in its file before it ran, in last-apply.json, for the launch toast's
// count (NetChanges). A 0.5.x RigTune must still read the file, and a 0.5.x result (no before) must still load.
class ApplyResultBeforeTest {
	private static final String RADIUS = "client.advanced.graphics.quality.lodChunkRenderDistanceRadius";

	// OpResult as 0.5.2 shipped it.
	record OpResult052(Op op, Status status, String message, String resultPath) {
	}

	record ApplyResult052(String finishedAt, List<OpResult052> results) {
	}

	@TempDir
	Path dir;
	Path config;
	Path pending;

	@BeforeEach
	void setUp() throws IOException {
		Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
	}

	@Test
	void tomlAndPropertiesPatchesRecordWhatTheyOverwrote() throws IOException {
		Path dh = Files.writeString(config.resolve("DistantHorizons.toml"), resource("/dh/DistantHorizons.toml"));
		Path iris = Files.writeString(config.resolve("iris.properties"), resource("/iris/iris.properties"));
		Op toml = Op.patchToml(dh, Map.of(RADIUS, "128"));
		Op properties = Op.patchProperties(iris, Map.of("enableShaders", "true"));
		PendingActions plan = PendingActions.create(1, dir.resolve("mods"), config, List.of(toml, properties));
		plan.save(pending);
		new ApplyExecutor(2, 1).run(plan, pending);
		ApplyResult result = ApplyResult.load(ApplyResult.defaultPath(config));
		assertEquals(List.of(Status.OK, Status.OK), result.results().stream().map(OpResult::status).toList());
		assertEquals(Map.of(RADIUS, "256"), result.results().get(0).before());
		assertEquals(Map.of("enableShaders", "false"), result.results().get(1).before());
	}

	@Test
	void a052ReaderReadsANewResultAndA052ResultStillLoads() throws IOException {
		Op op = Op.patchJson(config.resolve("sodium-options.json"), Map.of("performance.chunk_build_defer_mode", "ALWAYS"));
		Path file = ApplyResult.defaultPath(config);
		new ApplyResult("t", List.of(new OpResult(op, Status.OK, "ok").withBefore(Map.of("performance.chunk_build_defer_mode", "ZERO_FRAMES"))))
				.save(file);
		ApplyResult052 old = PendingActions.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ApplyResult052.class);
		assertEquals(op.id(), old.results().get(0).op().id());
		assertEquals(Status.OK, old.results().get(0).status());
		assertEquals(Map.of("performance.chunk_build_defer_mode", "ZERO_FRAMES"), ApplyResult.load(file).results().get(0).before());

		Files.writeString(file, PendingActions.GSON.toJson(new ApplyResult052("t", List.of(new OpResult052(op, Status.OK, "ok", null)))));
		ApplyResult loaded = ApplyResult.load(file);
		assertEquals(op.id(), loaded.results().get(0).op().id());
		assertNull(loaded.results().get(0).before());
	}

	private static String resource(String name) throws IOException {
		try (InputStream in = ApplyResultBeforeTest.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IOException("Missing test resource " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
