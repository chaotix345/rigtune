package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md C1/2d (amendments A-M1, A-L1): the optional projectId and versionId on pending.json ops. No new op type.
class PendingOpModrinthIdsTest {
	@TempDir
	Path dir;

	@Test
	void projectIdRoundTripsThroughPendingJson() throws Exception {
		Path mods = dir.resolve("mods");
		Op enable = Op.enableFile(mods.resolve("lithium.jar.rigtune-pending"), mods.resolve("lithium.jar")).withModId("lithium")
				.withProjectId("gvQqBUqZ").withVersionId("ZouiUX7t");
		Path file = dir.resolve("pending.json");
		PendingActions.create(1, mods, dir, List.of(enable)).save(file);
		assertTrue(Files.readString(file).contains("\"projectId\": \"gvQqBUqZ\""));
		assertTrue(Files.readString(file).contains("\"versionId\": \"ZouiUX7t\""));
		Op read = PendingActions.load(file).ops().getFirst();
		assertEquals("gvQqBUqZ", read.projectId());
		assertEquals("ZouiUX7t", read.versionId());
		assertEquals(enable, read);
	}

	@Test
	void anOpWithoutProjectIdReadsAsNullAndWritesNothing() throws Exception {
		Path file = dir.resolve("pending.json");
		Files.writeString(file, """
				{"createdAt": "2026-09-20T09:00:00Z", "gamePid": 1, "modsDir": "mods", "configDir": "config",
				 "ops": [{"type": "ENABLE_FILE", "from": "mods/a.jar.rigtune-pending", "to": "mods/a.jar", "id": "op-1", "modId": "a", "attempts": 0}]}
				""");
		PendingActions plan = PendingActions.load(file);
		assertNull(plan.ops().getFirst().projectId());
		assertNull(plan.ops().getFirst().versionId());
		plan.save(file);
		assertFalse(Files.readString(file).contains("projectId"));
		assertFalse(Files.readString(file).contains("versionId"));
	}

	@Test
	void theOldConstructorsAndEveryWitherKeepProjectId() {
		Op old = new Op(PendingActions.Type.DISABLE_FILE, null, null, "mods/x.jar", null, "id", null, null, 0);
		assertNull(old.projectId());
		assertNull(old.versionId());
		assertNull(new Op(PendingActions.Type.DISABLE_FILE, null, null, "mods/x.jar", null).projectId());
		Op withId = old.withProjectId("P").withVersionId("V");
		Op copied = withId.inGroup("g").withModId("x").withAttempts(2);
		assertEquals("P", copied.projectId());
		assertEquals("V", copied.versionId());
		assertEquals("V", withId.withProjectId("Q").versionId());
		assertEquals("P", withId.withVersionId("W").projectId());
		assertTrue(withId.sameChange(old), "projectId is not part of the file change");
	}
}
