package io.github.chaotix345.rigtune.core.modrinth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 10: RealController builds `new GatedModrinthClient(new HttpModrinthClient(...), modrinthAllowed)` in
// onInitializeClient, on the render thread, so the HttpClients are built on the first request instead, and never
// while Modrinth or the network is off.
class HttpModrinthClientLazyTest {
	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void constructingBuildsNoHttpClient() {
		assertFalse(new HttpModrinthClient("0.4.0").httpClientsBuilt());
		assertFalse(new HttpModrinthClient("0.4.0", "https://api.modrinth.com").httpClientsBuilt());
	}

	@Test
	void withTheSwitchOffNoHttpClientIsEverBuilt() {
		HttpModrinthClient http = new HttpModrinthClient("0.4.0", "http://127.0.0.1:9");
		GatedModrinthClient gated = new GatedModrinthClient(http, () -> false);
		assertThrows(ModrinthException.class, () -> gated.versionsByHashes(List.of("abc")));
		assertThrows(ModrinthException.class, () -> gated.projects(List.of("sodium")));
		assertThrows(ModrinthException.class, () -> gated.latestVersion("sodium", "fabric", "26.2"));
		assertFalse(http.httpClientsBuilt());
	}

	@Test
	void theFirstRequestBuildsIt() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		server.createContext("/", exchange -> {
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		HttpModrinthClient http = new HttpModrinthClient("0.4.0", "http://127.0.0.1:" + server.getAddress().getPort());
		GatedModrinthClient gated = new GatedModrinthClient(http, () -> true);
		assertFalse(http.httpClientsBuilt());
		assertEquals(0, gated.versionsByHashes(List.of("da39a3ee5e6b4b0d3255bfef95601890afd80709")).size());
		assertTrue(http.httpClientsBuilt());
	}
}
