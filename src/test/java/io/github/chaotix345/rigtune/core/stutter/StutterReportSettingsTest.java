package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md C1, RW-11: the stutter session's optional settingsAtStart/settingsAtEnd (0.4.0 has no pinned copy
// here: compat040 checks its reading in CI).
class StutterReportSettingsTest {
	@TempDir
	Path dir;

	@Test
	void stutterReportSettingsAtStartAndEnd() throws Exception {
		StutterReport plain = StutterStoreTest.report("2026-09-26T10:00:00Z", 12, 1);
		assertNull(plain.settingsAtStart());
		assertNull(plain.settingsAtEnd());
		StutterReport with = plain.withSettings(Map.of("vanilla.renderDistance", "12"), Map.of("vanilla.renderDistance", "10"));
		assertEquals(Map.of("vanilla.renderDistance", "10"), with.withAdvice(List.of("x")).settingsAtEnd(), "withAdvice keeps them");

		StutterStore store = new StutterStore(dir);
		store.add(plain);
		assertFalse(Files.readString(StutterStore.file(dir)).contains("settingsAtStart"), "nulls aren't written");
		store.add(with);
		StutterReport back = new StutterStore(dir).latest();
		assertEquals(StutterReport.MAX_WORST, back.worst().size(), "trimmed on save");
		assertEquals(Map.of("vanilla.renderDistance", "12"), back.settingsAtStart(), "a trimmed session keeps them");
		assertEquals(Map.of("vanilla.renderDistance", "10"), back.settingsAtEnd());
	}
}
