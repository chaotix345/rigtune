package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.model.Goal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientStateTest {
	@Test
	void sharedIsOneInstance(@TempDir Path configDir) {
		assertSame(ClientState.shared(configDir), ClientState.shared(configDir));
	}

	@Test
	void writersOfOneInstanceKeepEachOthersFields(@TempDir Path configDir) throws IOException {
		ClientState state = ClientState.load(configDir);
		state.lastShownApply = "2026-09-24T10:00:00Z";
		state.save(configDir);
		state.goal = Goal.QUALITY.name();
		state.save(configDir);

		ClientState reloaded = ClientState.load(configDir);
		assertEquals("2026-09-24T10:00:00Z", reloaded.lastShownApply);
		assertEquals(Goal.QUALITY, reloaded.goalOrDefault());
		try (Stream<Path> files = Files.list(ClientState.file(configDir).getParent())) {
			assertEquals(List.of(ClientState.file(configDir)), files.toList());
		}
	}

	@Test
	void saveReplacesAnExistingFile(@TempDir Path configDir) throws IOException {
		Files.createDirectories(ClientState.file(configDir).getParent());
		Files.writeString(ClientState.file(configDir), "{\"goal\":\"PERFORMANCE\",\"lastShownApply\":\"old\"}");
		ClientState state = ClientState.load(configDir);
		state.lastShownApply = "new";
		state.save(configDir);

		ClientState reloaded = ClientState.load(configDir);
		assertEquals("new", reloaded.lastShownApply);
		assertEquals(Goal.PERFORMANCE, reloaded.goalOrDefault());
	}
}
