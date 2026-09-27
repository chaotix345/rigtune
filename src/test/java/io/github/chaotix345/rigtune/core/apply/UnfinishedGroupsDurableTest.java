package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// docs/v0.5/SPEC.md 3f (AC3f.6): the record of a group's renames reaches the disk before the first rename, so a power cut
// between a rename and its record can't lose the record: the temp file is forced, moved over the record, and then the
// folder's entry is forced where the OS allows it.
class UnfinishedGroupsDurableTest {
	@TempDir
	Path dir;

	@Test
	void aDurableWriteForcesTheFileBeforeTheMoveAndThenItsFolder() throws IOException {
		Path target = dir.resolve("rigtune").resolve("unfinished-groups.json");
		List<String> events = new ArrayList<>();

		UnfinishedGroups.writeDurably(target, "{\"groups\":[]}", (path, folder) -> {
			events.add((folder ? "folder " : "file ") + (Files.exists(target) ? "after the move" : "before the move"));
			if (!folder) {
				assertEquals("{\"groups\":[]}", Files.readString(path));
			} else {
				assertEquals(target.getParent(), path);
			}
		});

		assertEquals(List.of("file before the move", "folder after the move"), events);
		assertEquals("{\"groups\":[]}", Files.readString(target));
		try (Stream<Path> files = Files.list(target.getParent())) {
			assertEquals(List.of("unfinished-groups.json"), files.map(p -> p.getFileName().toString()).toList());
		}
	}

	// The real syncer on this OS: a folder that can't be opened as a channel (Windows) is no error.
	@Test
	void theRealSyncerWritesTheRecord() throws IOException {
		Path target = dir.resolve("rigtune").resolve("unfinished-groups.json");
		Files.createDirectories(target.getParent());
		Files.writeString(target, "old");

		UnfinishedGroups.DURABLE.write(target, "new");

		assertEquals("new", Files.readString(target));
	}

	@Test
	void theHelperWritesItsRecordDurably() {
		assertSame(UnfinishedGroups.DURABLE, new ApplyExecutor().recordWriter());
	}
}
