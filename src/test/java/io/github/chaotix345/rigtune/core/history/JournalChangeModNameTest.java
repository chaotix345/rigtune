package io.github.chaotix345.rigtune.core.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md C1/2c: the optional modName on history.json changes. history.json keeps formatVersion 1.
class JournalChangeModNameTest {
	@TempDir
	Path dir;

	private Journal journal() {
		return new Journal(dir, "0.4.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	@Test
	void modNameRoundTripsThroughHistoryJson() throws Exception {
		JournalChange change = JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.9.2.jar", JournalChange.STAGED, "op-1", "g-1")
				.withModName("Sodium");
		JournalEntry entry = new JournalEntry("e-1", "2026-09-26T10:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(change));
		assertTrue(journal().update(entries -> List.of(entry)));

		JsonObject root = JsonParser.parseString(Files.readString(Journal.file(dir))).getAsJsonObject();
		assertEquals(1, root.get("formatVersion").getAsInt());
		assertEquals("Sodium", root.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("changes").get(0).getAsJsonObject()
				.get("modName").getAsString());
		JournalChange read = journal().entries().getFirst().changes().getFirst();
		assertEquals("Sodium", read.modName());
		assertEquals(change, read);
	}

	@Test
	void aChangeWithoutModNameReadsAsNullAndWritesNothing() throws Exception {
		Files.createDirectories(Journal.file(dir).getParent());
		Files.writeString(Journal.file(dir), """
				{"formatVersion": 1, "entries": [{"id": "e-1", "at": "2026-09-20T09:00:00Z", "kind": "apply", "rigtuneVersion": "0.3.0",
				  "mcVersion": "26.2", "changes": [{"id": "c-1", "type": "file", "action": "disable", "modId": "indium",
				  "file": "indium-1.0.36.jar", "status": "APPLIED"}]}]}
				""");
		JournalChange read = journal().entries().getFirst().changes().getFirst();
		assertNull(read.modName());
		assertEquals("indium-1.0.36.jar", read.file());
		assertTrue(journal().update(entries -> entries));
		assertFalse(Files.readString(Journal.file(dir)).contains("modName"));
	}

	@Test
	void theOldConstructorAndEveryWitherKeepModName() {
		JournalChange old = new JournalChange("c", JournalChange.FILE, null, null, null, JournalChange.ENABLE, "m", "m.jar", null,
				JournalChange.STAGED, "op", "g", null);
		assertNull(old.modName());
		JournalChange named = old.withModName("Mod");
		assertEquals("Mod", named.withStatus(JournalChange.APPLIED).withGroup("g2").withOpId("op2").withResultFile("m.jar")
				.reverting("x").modName());
		assertNull(JournalChange.setting("vanilla.renderDistance", "12", "8", JournalChange.APPLIED, null).modName());
	}
}
