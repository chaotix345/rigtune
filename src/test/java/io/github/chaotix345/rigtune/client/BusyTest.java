package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md C8 (AC-X.1): the shared busy refusal. Each condition alone refuses with its own text, the first in
// C8's order wins, none refuses nothing; the callers delegate and no second copy of the checks remains.
class BusyTest {
	// The callers, under client/: C20's StutterFixService and C09's Triable add themselves when they land.
	private static final List<String> CALLERS = List.of("profile/ProfileService.java", "stutter/StutterFixService.java");

	private static String key(Text text) {
		return ((Text.Translatable) text).key();
	}

	@Test
	void eachConditionAloneRefusesWithItsText() {
		assertEquals("rigtune.profile.status.benchmark", key(Busy.refusal(true, false, false, true)));
		assertEquals("rigtune.status.busy", key(Busy.refusal(false, true, false, true)));
		assertEquals("rigtune.tryit.refused.running", key(Busy.refusal(false, false, true, true)));
		assertEquals("rigtune.profile.code.error.not_ready", key(Busy.refusal(false, false, false, false)));
		assertNull(Busy.refusal(false, false, false, true));
	}

	@Test
	void theFirstInC8sOrderWins() {
		assertEquals("rigtune.profile.status.benchmark", key(Busy.refusal(true, true, true, false)));
		assertEquals("rigtune.status.busy", key(Busy.refusal(false, true, true, false)));
		assertEquals("rigtune.tryit.refused.running", key(Busy.refusal(false, false, true, false)));
	}

	@Test
	void tryItIsOffUntilItsFeatureSetsTheHook() {
		assertFalse(Busy.tryItRunning.getAsBoolean());
	}

	// A caller delegates to Busy and keeps no copy of its checks.
	@Test
	void theCallersDelegateAndNoCopyOfTheChecksRemains() throws IOException {
		Path client = RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client");
		for (String name : CALLERS) {
			Path caller = client.resolve(name);
			String source = Files.readString(caller);
			assertTrue(source.contains("Busy.refusal(controller)"), caller + " delegates");
			assertFalse(source.contains("\"rigtune.profile.status.benchmark\""), caller + " has its own benchmark refusal");
			assertFalse(source.contains("\"rigtune.status.busy\""), caller + " has its own downloading refusal");
		}
		try (Stream<Path> files = Files.walk(RepoFiles.resolve("src"))) {
			List<String> writers = files.filter(f -> f.toString().endsWith(".java") && !f.getFileName().toString().equals("Busy.java")
							&& !f.getFileName().toString().equals("BusyTest.java"))
					.filter(f -> read(f).contains("\"rigtune.tryit.refused.running\""))
					.map(f -> f.getFileName().toString()).toList();
			assertEquals(List.of(), writers, "only Busy refuses for a running Try it");
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
