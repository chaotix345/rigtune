package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (C16, AC7.10; PLAN contracts item 12): a server-profile offer's × hides it for this session only:
// its key is per join, so AwarenessService never stores it in awareness.json's dismissed list. Other keys are stored as
// before. (The controller isn't needed for a dismissal.)
class AwarenessDismissTest {
	@TempDir
	Path dir;

	@Test
	void aServerProfileKeyIsSessionOnly() {
		assertEquals(List.of("server-profile:"), AwarenessService.SESSION_ONLY_PREFIXES);
		AwarenessService service = new AwarenessService(null, dir);
		service.dismiss("server-profile:1727400000000");
		assertTrue(service.dismissed().contains("server-profile:1727400000000"), "hidden for the session");
		assertFalse(AwarenessStore.shared(dir).dismissed().contains("server-profile:1727400000000"), "never stored");
		service.dismiss("battery-offer:1");
		assertTrue(AwarenessStore.shared(dir).dismissed().contains("battery-offer:1"), "other notices as before");
	}
}
