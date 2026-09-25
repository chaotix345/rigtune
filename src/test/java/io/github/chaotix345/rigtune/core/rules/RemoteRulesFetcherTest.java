package io.github.chaotix345.rigtune.core.rules;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRulesFetcherTest {
	private HttpServer server;
	private final AtomicReference<String> userAgent = new AtomicReference<>();

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		serve("/good", 200, "{\"schemaVersion\":2,\"revision\":42}");
		serve("/v1", 200, "{\"schemaVersion\":1,\"revision\":43}");
		serve("/missing", 404, "not found");
		serve("/bad", 200, "{\"schemaVersion\":7}");
		serve("/huge", 200, "{\"schemaVersion\":1,\"revision\":50}" + " ".repeat((int) RulesLoader.MAX_RULES_BYTES));
		serve("/large", 200, "{\"schemaVersion\":1,\"revision\":51}" + " ".repeat((int) RulesLoader.MAX_RULES_BYTES / 2));
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void serve(String path, int status, String body) {
		server.createContext(path, exchange -> {
			userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
	}

	@Test
	void fetchesParsesAndCaches(@TempDir Path dir) throws IOException {
		Path cache = dir.resolve("rules-cache.json");
		RulesDocument doc = new RemoteRulesFetcher(uri("/good"), "0.1.0", cache).fetch().orElseThrow();
		assertEquals(42, doc.revision);
		assertEquals("remote", doc.source());
		assertEquals("chaotix345/rigtune/0.1.0 (github.com/chaotix345/rigtune)", userAgent.get());
		assertTrue(Files.readString(cache).contains("\"revision\":42"));
	}

	@Test
	void schemaVersion1IsUsedButNotCached(@TempDir Path dir) {
		Path cache = dir.resolve("rules-v2-cache.json");
		assertEquals(43, new RemoteRulesFetcher(uri("/v1"), "0.2.0", cache).fetch().orElseThrow().revision);
		assertFalse(Files.exists(cache));
	}

	@Test
	void httpErrorsAndInvalidRulesReturnEmptyWithoutCaching(@TempDir Path dir) {
		Path cache = dir.resolve("rules-cache.json");
		assertFalse(new RemoteRulesFetcher(uri("/missing"), "0.1.0", cache).fetch().isPresent());
		assertFalse(new RemoteRulesFetcher(uri("/bad"), "0.1.0", cache).fetch().isPresent());
		assertFalse(Files.exists(cache));
	}

	@Test
	void rulesLargerThanTheCapAreRejectedWithoutCaching(@TempDir Path dir) {
		Path cache = dir.resolve("rules-cache.json");
		assertFalse(new RemoteRulesFetcher(uri("/huge"), "0.1.0", cache).fetch().isPresent());
		assertFalse(Files.exists(cache));
		assertEquals(51, new RemoteRulesFetcher(uri("/large"), "0.1.0", cache).fetch().orElseThrow().revision);
	}

	@Test
	void unreachableServerReturnsEmpty(@TempDir Path dir) throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		URI dead = URI.create("http://127.0.0.1:" + port + "/good");
		assertFalse(new RemoteRulesFetcher(dead, "0.1.0", dir.resolve("c.json")).fetch().isPresent());
		assertFalse(new RemoteRulesFetcher(URI.create("ftp://example.invalid/rules.json"), "0.1.0", null).fetch().isPresent());
	}
}
