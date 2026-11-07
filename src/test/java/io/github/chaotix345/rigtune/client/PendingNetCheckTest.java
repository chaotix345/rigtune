package io.github.chaotix345.rigtune.client;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #46, PR #63 review: Discard pending's answer (RealController.hasPendingChanges) through A -> B -> A, then an edit of the
// config file in the game's own screen, which makes the same queue change something again; and Try it's pending stamp
// (TryItService.pendingStamp), which must change with that file so its cached answer is read again.
class PendingNetCheckTest {
	private static final String DEFER = "performance.chunk_build_defer_mode";

	@TempDir
	Path dir;
	Path sodium;
	Path pendingFile;
	List<ConfigTargets.Target> targets;
	long clock = Instant.parse("2026-11-07T00:00:00Z").toEpochMilli();

	@BeforeEach
	void setUp() throws IOException {
		Path config = Files.createDirectories(dir.resolve("config"));
		sodium = config.resolve("sodium-options.json");
		pendingFile = PendingActions.defaultPath(config);
		Files.createDirectories(pendingFile.getParent());
		writeSodium("ONE_FRAME");
		targets = List.of(new ConfigTargets.Target("sodium.", sodium, SodiumConfigPatcher::stage, file -> {
			try {
				return SodiumConfigPatcher.flatten(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(), "");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}));
	}

	// Each write gets a later modification time, so a stamp sees it whatever the file system's time granularity.
	private void writeSodium(String defer) throws IOException {
		Files.writeString(sodium, "{\"performance\":{\"chunk_build_defer_mode\":\"" + defer + "\"}}", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(sodium, FileTime.fromMillis(clock += 2000));
	}

	private void stage(String... values) throws IOException {
		List<Op> ops = java.util.Arrays.stream(values).map(v -> Op.patchJson(sodium, Map.of(DEFER, v))).toList();
		PendingActions.create(1, dir.resolve("mods"), dir.resolve("config"), ops).save(pendingFile);
		Files.setLastModifiedTime(pendingFile, FileTime.fromMillis(clock += 2000));
	}

	@Test
	void backAndForthHidesDiscardUntilTheFileChanges() throws IOException {
		PendingNetCheck check = new PendingNetCheck(pendingFile, targets);
		assertFalse(check.changesSomething(), "no pending.json");

		stage("ALWAYS");
		assertTrue(check.changesSomething(), "A -> B");
		stage("ALWAYS", "ONE_FRAME");
		assertFalse(check.changesSomething(), "A -> B -> A: nothing to discard");
		assertFalse(check.changesSomething(), "the same answer from the stamp");

		List<Object> tryItStamp = PendingNetCheck.stamp(pendingFile, targets);
		writeSodium("ALWAYS");
		assertNotEquals(tryItStamp, PendingNetCheck.stamp(pendingFile, targets), "Try it reads its answer again");
		assertTrue(check.changesSomething(), "the player set Deferred in Sodium's screen: the queue would put it back at exit");

		writeSodium("ONE_FRAME");
		assertFalse(check.changesSomething());
	}

	@Test
	void anUnreadablePlanStillOffersDiscard() throws IOException {
		Files.writeString(pendingFile, "{not json", StandardCharsets.UTF_8);
		assertTrue(new PendingNetCheck(pendingFile, targets).changesSomething());
	}
}
