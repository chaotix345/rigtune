package io.github.chaotix345.rigtune.core.server;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (AC7.14), 3b and X11: the "written by 0.5" set src/test/resources/v050-written/ws-p2/ is what this
// version's ServerProfileStore writes: server-profiles.json with one entry per kind (REMOTE, LAN_GUEST, REALM). Its
// expect.json has compat040 check that 0.4.0 never opens it (byte-identical after a 0.4.0 run); back on 0.5 the mappings
// offer again (the downgrade E2E). RIGTUNE_REGENERATE_FIXTURES=1 rewrites the set; otherwise the committed file must be
// exactly what the code writes now.
class V050WrittenWsP2Test {
	private static final String SET = "src/test/resources/v050-written/ws-p2/";
	// A fixed salt so the fixture is reproducible (RigTune makes a random one per file); not v040-written/ws-w's
	// server-limits.json salt, since each file has its own.
	private static final String SALT = "7c1e9a4b2d6f80351ace9b7d3f5c2e18";
	private static final String REMOTE = ServerLimitsStore.address("play.example.com", 25565);
	private static final String LAN = ServerLimitsStore.lan("192.168.1.20");
	private static final String REALM = ServerLimitsStore.realm("Weekend SMP");

	@TempDir
	Path dir;

	@Test
	void theWsP2SetIsWhatThisVersionWrites() throws IOException {
		Path file = ServerProfileStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\": 1, \"salt\": \"" + SALT + "\", \"servers\": {}}", StandardCharsets.UTF_8);
		ServerProfileStore store = ServerProfileStore.shared(dir);
		assertEquals(Result.OK, store.remember(REMOTE, ServerLimits.Kind.REMOTE, "template:max_fps", Instant.parse("2026-09-21T19:40:00Z")));
		assertEquals(Result.OK, store.remember(LAN, ServerLimits.Kind.LAN_GUEST, "template:quality", Instant.parse("2026-09-22T17:12:30Z")));
		assertEquals(Result.OK, store.remember(REALM, ServerLimits.Kind.REALM, "template:recording", Instant.parse("2026-09-23T20:00:00Z")));
		store.joined(REMOTE, Instant.parse("2026-09-24T21:05:00Z"));

		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(file, committed.resolve(ServerProfileStore.FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
		}
		String written = Files.readString(file, StandardCharsets.UTF_8);
		assertEquals(written, Files.readString(committed.resolve(ServerProfileStore.FILE_NAME), StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		assertEquals(List.of(ServerProfileStore.FILE_NAME, "expect.json"), List.of(ServerProfileStore.FILE_NAME, "expect.json").stream()
				.filter(name -> Files.isRegularFile(committed.resolve(name))).toList());
		try (var files = Files.list(committed)) {
			assertEquals(2, files.count(), "the set holds server-profiles.json and expect.json only");
		}
		String withoutHex = written.replaceAll("[0-9a-f]{32,64}", "#");
		for (String plain : List.of("example", "25565", "192.168", "lan:", "Weekend", "realm:")) {
			assertFalse(withoutHex.contains(plain), plain);
		}

		Path reread = dir.resolve("reread");
		Files.createDirectories(ServerProfileStore.file(reread).getParent());
		Files.copy(committed.resolve(ServerProfileStore.FILE_NAME), ServerProfileStore.file(reread));
		ServerProfileStore again = ServerProfileStore.shared(reread);
		assertEquals("template:max_fps", again.get(ServerLimitsStore.address("PLAY.example.com", 0)).profile());
		assertEquals(Instant.parse("2026-09-24T21:05:00Z"), again.get(REMOTE).lastSeen());
		assertEquals(ServerLimits.Kind.LAN_GUEST, again.get(LAN).kind());
		assertEquals("template:recording", again.get(REALM).profile());
		assertEquals(List.of(ServerLimits.Kind.REMOTE, ServerLimits.Kind.REALM, ServerLimits.Kind.LAN_GUEST),
				again.entries().stream().map(ServerProfileStore.Entry::kind).toList(), "one entry per kind, most recently joined first");
		String expect = Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8);
		assertTrue(expect.contains("\"set\": \"ws-p2\""), expect);
	}
}
