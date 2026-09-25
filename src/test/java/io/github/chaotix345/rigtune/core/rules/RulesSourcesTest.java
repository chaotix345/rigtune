package io.github.chaotix345.rigtune.core.rules;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesSourcesTest {
	private static final int BUNDLED = RulesLoader.loadBundled().revision;

	private HttpServer server;
	private final Map<String, Response> responses = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
	private final List<Published> published = new ArrayList<>();

	@TempDir
	Path config;

	private record Response(int status, String body) {
	}

	private record Published(int revision, int schemaVersion, String source, boolean remote) {
	}

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/rules/", exchange -> {
			String file = exchange.getRequestURI().getPath().substring("/rules/".length());
			requests.computeIfAbsent(file, f -> new AtomicInteger()).incrementAndGet();
			Response response = responses.getOrDefault(file, new Response(404, "not found"));
			byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static String doc(int schemaVersion, int revision) {
		return "{\"schemaVersion\":" + schemaVersion + ",\"revision\":" + revision + "}";
	}

	private void serve(String file, int status, String body) {
		responses.put(file, new Response(status, body));
	}

	private int requestsFor(String file) {
		AtomicInteger count = requests.get(file);
		return count == null ? 0 : count.get();
	}

	private RulesSources sources() {
		URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rules/");
		return new RulesSources(config, base, "0.2.0-test");
	}

	private void load(boolean remoteAllowed) {
		load(() -> remoteAllowed);
	}

	private void load(BooleanSupplier remoteAllowed) {
		sources().load(remoteAllowed, (rules, remote) -> published.add(new Published(rules.revision, rules.schemaVersion, rules.source(), remote)));
	}

	private Path cacheDir() {
		return config.resolve("rigtune");
	}

	@Test
	void remoteV2IsUsedAndCached() throws IOException {
		serve("rules-v2.json", 200, doc(2, BUNDLED + 10));
		load(true);
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false), new Published(BUNDLED + 10, 2, "remote", true)), published);
		assertEquals(0, requestsFor("rules-v1.json"));
		assertTrue(Files.readString(cacheDir().resolve(RulesSources.V2_CACHE)).contains("\"revision\":" + (BUNDLED + 10)));
		assertFalse(Files.exists(cacheDir().resolve(RulesSources.LEGACY_CACHE)));
	}

	@Test
	void remoteV2NotFoundFallsBackToRemoteV1WhichIsNotCached() {
		serve("rules-v1.json", 200, doc(1, BUNDLED + 5));
		load(true);
		assertEquals(new Published(BUNDLED + 5, 1, "remote", true), published.getLast());
		assertEquals(1, requestsFor("rules-v2.json"));
		assertFalse(Files.exists(cacheDir().resolve(RulesSources.V2_CACHE)));
		assertFalse(Files.exists(cacheDir().resolve(RulesSources.LEGACY_CACHE)));
	}

	@Test
	void remoteV2InvalidFallsBackToRemoteV1() {
		serve("rules-v2.json", 200, "{not json");
		serve("rules-v1.json", 200, doc(1, BUNDLED + 5));
		load(true);
		assertEquals(new Published(BUNDLED + 5, 1, "remote", true), published.getLast());
		assertFalse(Files.exists(cacheDir().resolve(RulesSources.V2_CACHE)));
	}

	@Test
	void remoteV1AtTheBundledRevisionLosesToBundledV2() {
		serve("rules-v1.json", 200, doc(1, BUNDLED));
		load(true);
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false)), published);
	}

	@Test
	void bothRemotesFailingKeepsTheLocalPick() {
		load(true);
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false)), published);
		assertEquals(1, requestsFor("rules-v2.json"));
		assertEquals(1, requestsFor("rules-v1.json"));
	}

	@Test
	void noRequestWhenRemoteNotAllowed() {
		serve("rules-v2.json", 200, doc(2, BUNDLED + 10));
		load(false);
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false)), published);
		assertTrue(requests.isEmpty(), requests.toString());
	}

	@Test
	void noFallbackRequestOnceRemoteIsSwitchedOff() {
		AtomicInteger asked = new AtomicInteger();
		serve("rules-v1.json", 200, doc(1, BUNDLED + 5));
		load(() -> asked.incrementAndGet() == 1);
		assertEquals(1, requestsFor("rules-v2.json"));
		assertEquals(0, requestsFor("rules-v1.json"));
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false)), published);
	}

	@Test
	void remoteIsAskedOnlyAfterTheLocalRulesArePublished() {
		serve("rules-v2.json", 200, doc(2, BUNDLED + 10));
		load(() -> {
			assertEquals(1, published.size());
			return false;
		});
		assertTrue(requests.isEmpty());
	}

	@Test
	void legacyV1CacheIsReadButNeverWritten() throws IOException {
		Path legacy = cacheDir().resolve(RulesSources.LEGACY_CACHE);
		Files.createDirectories(cacheDir());
		Files.writeString(legacy, doc(1, BUNDLED + 3));
		FileTime before = FileTime.fromMillis(1_000_000_000_000L);
		Files.setLastModifiedTime(legacy, before);
		serve("rules-v2.json", 200, doc(2, BUNDLED + 10));
		load(true);
		assertEquals(new Published(BUNDLED + 3, 1, "cache", false), published.getFirst());
		assertEquals(new Published(BUNDLED + 10, 2, "remote", true), published.getLast());
		assertEquals(doc(1, BUNDLED + 3), Files.readString(legacy));
		assertEquals(before, Files.getLastModifiedTime(legacy));
	}

	@Test
	void v2CacheIsACandidate() throws IOException {
		Files.createDirectories(cacheDir());
		Files.writeString(cacheDir().resolve(RulesSources.V2_CACHE), doc(2, BUNDLED + 2));
		load(false);
		assertEquals(List.of(new Published(BUNDLED + 2, 2, "cache", false)), published);
	}

	@Test
	void unreadableCachesAreSkipped() throws IOException {
		Files.createDirectories(cacheDir());
		Files.writeString(cacheDir().resolve(RulesSources.V2_CACHE), "{broken");
		Files.writeString(cacheDir().resolve(RulesSources.LEGACY_CACHE), doc(9, BUNDLED + 50));
		load(false);
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false)), published);
	}

	@Test
	void remoteV2WithSchemaVersion1IsUsedButNotCached() {
		serve("rules-v2.json", 200, doc(1, BUNDLED + 10));
		load(true);
		assertEquals(new Published(BUNDLED + 10, 1, "remote", true), published.getLast());
		assertFalse(Files.exists(cacheDir().resolve(RulesSources.V2_CACHE)));
		assertEquals(0, requestsFor("rules-v1.json"));
	}

	@Test
	void anEqualRemoteIsNotRepublished() throws IOException {
		Files.createDirectories(cacheDir());
		Files.writeString(cacheDir().resolve(RulesSources.V2_CACHE), doc(2, BUNDLED + 2));
		serve("rules-v2.json", 200, doc(2, BUNDLED + 2));
		load(true);
		assertEquals(List.of(new Published(BUNDLED + 2, 2, "cache", false)), published);
		assertEquals(1, requestsFor("rules-v2.json"));
	}

	@Test
	void anEqualRemoteV1IsNotRepublishedOverTheLegacyCache() throws IOException {
		Files.createDirectories(cacheDir());
		Files.writeString(cacheDir().resolve(RulesSources.LEGACY_CACHE), doc(1, BUNDLED + 2));
		serve("rules-v1.json", 200, doc(1, BUNDLED + 2));
		load(true);
		assertEquals(List.of(new Published(BUNDLED + 2, 1, "cache", false)), published);
	}

	@Test
	void listenerIsNotCalledAgainWhenRemoteIsNotNewer() {
		serve("rules-v2.json", 200, doc(2, BUNDLED - 1));
		load(true);
		assertEquals(List.of(new Published(BUNDLED, 2, "bundled", false)), published);
	}

	@Test
	void baseUrlOverride() {
		assertEquals(RulesSources.DEFAULT_BASE_URL, RulesSources.baseUrl(null));
		assertEquals(RulesSources.DEFAULT_BASE_URL, RulesSources.baseUrl(" "));
		assertEquals(URI.create("http://127.0.0.1:8080/x/"), RulesSources.baseUrl("http://127.0.0.1:8080/x"));
		assertEquals(URI.create("https://example.com/rules/"), RulesSources.baseUrl("https://example.com/rules/"));
		assertEquals(RulesSources.DEFAULT_BASE_URL, RulesSources.baseUrl("ftp://example.com/rules/"));
		assertEquals(RulesSources.DEFAULT_BASE_URL, RulesSources.baseUrl("not a url"));
		assertEquals(URI.create("https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v2.json"),
				RulesSources.DEFAULT_BASE_URL.resolve(RulesSources.V2_FILE));
	}
}
