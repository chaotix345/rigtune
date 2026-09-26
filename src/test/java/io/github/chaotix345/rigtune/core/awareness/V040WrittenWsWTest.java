package io.github.chaotix345.rigtune.core.awareness;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.server.ServerLimitsStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md amendment H-M1 / PLAN "Wave A": the "written by 0.4" set src/test/resources/v040-written/ws-w/
// (awareness.json + server-limits.json, for WS-H's downgrade run and released-jar harness) is what this version's own
// code writes. The test writes both through AwarenessStore/ChangeDetector/WhatsNew and ServerLimitsStore into
// build/v040-written/ws-w/ and checks the committed copies are the same (copy them over after a deliberate change).
class V040WrittenWsWTest {
	private static final String DIR = "/v040-written/ws-w/";
	// A fixed salt so the fixture is reproducible; RigTune itself creates a random one per file.
	private static final String SALT = "3f9a0c1e5b7d2468ace013579bdf2468";

	@Test
	void theWsWSetIsWhatThisVersionWrites() throws IOException {
		Path config = Path.of("build", "v040-written", "ws-w-config").toAbsolutePath();
		Path out = Path.of("build", "v040-written", "ws-w").toAbsolutePath();
		Files.createDirectories(config.resolve("rigtune"));
		Files.createDirectories(out);
		Files.deleteIfExists(AwarenessStore.file(config));
		Files.deleteIfExists(ServerLimitsStore.file(config));

		AwarenessStore awareness = AwarenessStore.shared(config);
		Fingerprint older = new Fingerprint("AMD", "AMD Radeon RX 7800 XT", "3.3.0 Core Profile Context 26.8.1.260810", "OPENGL",
				"AMD Ryzen 7 7800X3D 8-Core Processor", 31948);
		Fingerprint now = new Fingerprint("AMD", "AMD Radeon RX 7800 XT", "3.3.0 Core Profile Context 26.9.1.260915", "OPENGL",
				"AMD Ryzen 7 7800X3D 8-Core Processor", 31948);
		assertEquals(ChangeDetector.Kind.NONE, ChangeDetector.check(awareness, older).kind(), "first run seeds");
		assertEquals(ChangeDetector.Kind.DRIVER, ChangeDetector.check(awareness, now).kind());
		assertTrue(awareness.dismiss("hardware-changed:" + now.id()));
		assertTrue(ChangeDetector.commit(awareness, now));
		assertTrue(WhatsNew.acknowledge(awareness, 13, Set.of("add:sodium", "add:lithium", "set:vanilla.renderDistance",
				"set:vanilla.simulationDistance", "advice:ram-heap-small", "disable:indium", "conflict:optifabric+sodium")));
		assertTrue(awareness.dismiss("whats-new:13"));
		assertTrue(awareness.dismiss("server-limit:10:above"));
		Files.copy(AwarenessStore.file(config), out.resolve("awareness.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		Files.writeString(ServerLimitsStore.file(config), "{\"formatVersion\": 1, \"salt\": \"" + SALT + "\", \"servers\": {}}", StandardCharsets.UTF_8);
		ServerLimitsStore servers = new ServerLimitsStore(config);
		servers.remember(ServerLimitsStore.address("play.example.com", 25565),
				new ServerLimits(12, 10, ServerLimits.Kind.REMOTE, Instant.parse("2026-09-21T19:40:00Z").toEpochMilli()));
		servers.remember(ServerLimitsStore.address("play.example.com", 25565),
				new ServerLimits(10, 10, ServerLimits.Kind.REMOTE, Instant.parse("2026-09-24T21:05:00Z").toEpochMilli()));
		servers.remember(ServerLimitsStore.address("192.168.1.20", 52814),
				new ServerLimits(8, 8, ServerLimits.Kind.LAN_GUEST, Instant.parse("2026-09-22T17:12:30Z").toEpochMilli()));
		servers.remember(ServerLimitsStore.realm("Weekend SMP"),
				new ServerLimits(10, 8, ServerLimits.Kind.REALM, Instant.parse("2026-09-23T20:00:00Z").toEpochMilli()));
		Files.copy(ServerLimitsStore.file(config), out.resolve("server-limits.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		for (String name : List.of("awareness.json", "server-limits.json")) {
			String written = Files.readString(out.resolve(name), StandardCharsets.UTF_8);
			assertEquals(written, resource(DIR + name), "copy " + out.resolve(name) + " to src/test/resources" + DIR + name);
		}
		String limits = Files.readString(out.resolve("server-limits.json"), StandardCharsets.UTF_8);
		for (String plain : List.of("example", "192.168", "52814", "Weekend", "realm:")) {
			assertFalse(limits.contains(plain), plain);
		}
		assertEquals(10, new ServerLimitsStore(config).get(ServerLimitsStore.address("PLAY.example.com", 0)).viewDistance());
		assertEquals(now, Fingerprint.read(awareness.read()));
	}

	private static String resource(String name) throws IOException {
		try (InputStream in = V040WrittenWsWTest.class.getResourceAsStream(name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
	}
}
