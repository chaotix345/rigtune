package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chaotix345.rigtune.core.model.ModFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpModrinthClientTest {
	private static final String VERSION_JSON = """
			{"id":"xJZxADzI","project_id":"AANobbMI","version_number":"mc26.2-0.9.2-fabric","version_type":"release",
			 "game_versions":["26.2"],"loaders":["fabric"],"date_published":"2026-09-11T18:08:28.513893Z",
			 "files":[{"url":"https://cdn/x-sources.jar","filename":"x-sources.jar","primary":false,"size":1,"hashes":{"sha1":"s1","sha512":"a"}},
			          {"url":"https://cdn/x.jar","filename":"x.jar","primary":true,"size":1885572,"hashes":{"sha1":"62a7","sha512":"bb"}}],
			 "dependencies":[{"project_id":"P7dR8mSH","version_id":null,"file_name":null,"dependency_type":"required"}]}
			""";

	private HttpServer server;
	private HttpModrinthClient client;
	private final Map<String, Recorded> requests = new ConcurrentHashMap<>();
	private final Map<String, Response> responses = new ConcurrentHashMap<>();
	private final Map<String, Deque<Response>> queued = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private ExecutorService handlers;

	private static final HttpModrinthClient.Limits FAST = new HttpModrinthClient.Limits(16L << 20, 256L << 20,
			Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMillis(50));

	private record Recorded(String method, String query, String body, String userAgent) {
	}

	// stall: send the headers and this body, then hang without finishing the (longer) announced length.
	private record Response(int status, byte[] body, Map<String, String> headers, boolean stall) {
		Response(int status, byte[] body) {
			this(status, body, Map.of(), false);
		}
	}

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		handlers = Executors.newCachedThreadPool();
		server.setExecutor(handlers);
		server.start();
		client = client(FAST);
	}

	private HttpModrinthClient client(HttpModrinthClient.Limits limits) {
		return new HttpModrinthClient("1.2.3", "http://127.0.0.1:" + server.getAddress().getPort() + "/", limits);
	}

	@AfterEach
	void stop() {
		release.countDown();
		server.stop(0);
		handlers.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		requests.put(path, new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(), body,
				exchange.getRequestHeaders().getFirst("User-Agent")));
		hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
		Deque<Response> queue = queued.get(path);
		Response next = queue == null ? null : queue.poll();
		Response response = next != null ? next : responses.getOrDefault(path, new Response(404, "{}".getBytes(StandardCharsets.UTF_8)));
		response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
		if (response.stall()) {
			exchange.sendResponseHeaders(response.status(), response.body().length * 10L);
			OutputStream out = exchange.getResponseBody();
			out.write(response.body());
			out.flush();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			}
			exchange.close();
			return;
		}
		exchange.sendResponseHeaders(response.status(), response.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(response.body());
		}
	}

	private void queue(String path, Response... sequence) {
		queued.put(path, new ConcurrentLinkedDeque<>(List.of(sequence)));
	}

	private int hits(String path) {
		AtomicInteger count = hits.get(path);
		return count == null ? 0 : count.get();
	}

	private static byte[] bytes(int size) {
		byte[] out = new byte[size];
		Arrays.fill(out, (byte) 'x');
		return out;
	}

	private static String sha512(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(data));
	}

	private static List<Path> listing(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return List.of();
		}
		try (var files = Files.list(dir)) {
			return files.toList();
		}
	}

	private void respond(String path, int status, String body) {
		responses.put(path, new Response(status, body.getBytes(StandardCharsets.UTF_8)));
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private static String param(String rawQuery, String name) {
		for (String pair : rawQuery.split("&")) {
			String[] kv = pair.split("=", 2);
			if (kv[0].equals(name)) {
				return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	@Test
	void versionsByHashesPostsSha1BodyAndParsesVersions() throws IOException {
		respond("/v2/version_files", 200, "{\"62a7\":" + VERSION_JSON + "}");

		Map<String, ModrinthVersion> result = client.versionsByHashes(List.of("62a7", "dead"));

		Recorded req = requests.get("/v2/version_files");
		assertEquals("POST", req.method());
		assertEquals("chaotix345/rigtune/1.2.3 (github.com/chaotix345/rigtune)", req.userAgent());
		assertEquals(JsonParser.parseString("{\"hashes\":[\"62a7\",\"dead\"],\"algorithm\":\"sha1\"}"), JsonParser.parseString(req.body()));

		ModrinthVersion v = result.get("62a7");
		assertEquals("xJZxADzI", v.id());
		assertEquals("AANobbMI", v.projectId());
		assertEquals("mc26.2-0.9.2-fabric", v.versionNumber());
		assertEquals(Instant.parse("2026-09-11T18:08:28.513893Z"), v.datePublished());
		assertEquals(new ModFile("https://cdn/x.jar", "x.jar", "bb", 1885572), v.primaryFile());
		assertEquals(List.of(new Dependency("P7dR8mSH", null, "required")), v.dependencies());
	}

	@Test
	void latestVersionsByHashesSendsLoaderAndGameVersion() throws IOException {
		respond("/v2/version_files/update", 200, "{\"62a7\":" + VERSION_JSON + "}");

		assertEquals(1, client.latestVersionsByHashes(List.of("62a7"), "fabric", "26.2").size());

		JsonObject body = JsonParser.parseString(requests.get("/v2/version_files/update").body()).getAsJsonObject();
		assertEquals("sha1", body.get("algorithm").getAsString());
		assertEquals(JsonParser.parseString("[\"fabric\"]"), body.get("loaders"));
		assertEquals(JsonParser.parseString("[\"26.2\"]"), body.get("game_versions"));
	}

	@Test
	void projectsSendsIdsAsJsonArray() throws IOException {
		respond("/v2/projects", 200, """
				[{"id":"AANobbMI","slug":"sodium","title":"Sodium","status":"approved","client_side":"required",
				  "game_versions":["26.1","26.2"],"loaders":["fabric","neoforge"]}]""");

		List<ModrinthProject> projects = client.projects(List.of("sodium", "gvQqBUqZ"));

		assertEquals("[\"sodium\",\"gvQqBUqZ\"]", param(requests.get("/v2/projects").query(), "ids"));
		assertEquals(new ModrinthProject("AANobbMI", "sodium", "Sodium", "approved", List.of("26.1", "26.2"),
				List.of("fabric", "neoforge"), "required"), projects.getFirst());
		assertTrue(projects.getFirst().supports("fabric", "26.2"));
	}

	// docs/v0.4/SPEC.md 2d (A-M1): the versions earlier Applies staged, by id (GET /v2/versions?ids=[...]).
	@Test
	void versionsSendIdsAsJsonArrayAndAreKeyedById() throws IOException {
		respond("/v2/versions", 200, "[" + VERSION_JSON + "]");

		Map<String, ModrinthVersion> result = client.versions(List.of("xJZxADzI", "gone0000"));

		Recorded req = requests.get("/v2/versions");
		assertEquals("GET", req.method());
		assertEquals("[\"xJZxADzI\",\"gone0000\"]", param(req.query(), "ids"));
		assertEquals(List.of("xJZxADzI"), List.copyOf(result.keySet()));
		assertEquals(List.of(new Dependency("P7dR8mSH", null, "required")), result.get("xJZxADzI").dependencies());
		assertEquals(Map.of(), client.versions(List.of()));
		assertEquals(1, hits("/v2/versions"));
	}

	private static String version(String id, String type, String date) {
		return VERSION_JSON.replace("\"xJZxADzI\"", "\"" + id + "\"")
				.replace("\"release\"", "\"" + type + "\"")
				.replace("2026-09-11T18:08:28.513893Z", date);
	}

	@Test
	void latestVersionPrefersNewestRelease() throws IOException {
		respond("/v2/project/sodium/version", 200, "[" + String.join(",",
				version("beta2", "beta", "2026-09-20T00:00:00Z"),
				version("rel1", "release", "2026-08-01T00:00:00Z"),
				version("rel2", "release", "2026-09-01T00:00:00Z")) + "]");

		Optional<ModrinthVersion> latest = client.latestVersion("sodium", "fabric", "26.2");

		assertEquals("rel2", latest.orElseThrow().id());
		String query = requests.get("/v2/project/sodium/version").query();
		assertEquals("[\"fabric\"]", param(query, "loaders"));
		assertEquals("[\"26.2\"]", param(query, "game_versions"));
	}

	@Test
	void latestVersionFallsBackToNewestOfAnyType() throws IOException {
		respond("/v2/project/x/version", 200, "[" + String.join(",",
				version("alpha1", "alpha", "2026-08-01T00:00:00Z"),
				version("beta1", "beta", "2026-09-01T00:00:00Z")) + "]");
		assertEquals("beta1", client.latestVersion("x", "fabric", "26.2").orElseThrow().id());

		respond("/v2/project/x/version", 200, "[]");
		assertTrue(client.latestVersion("x", "fabric", "26.2").isEmpty());
	}

	@Test
	void unknownProjectIsEmptyButOtherErrorsThrow() throws IOException {
		assertTrue(client.latestVersion("missing", "fabric", "26.2").isEmpty());

		respond("/v2/version_files", 429, "{\"error\":\"ratelimit_error\"}");
		ModrinthException limited = assertThrows(ModrinthException.class, () -> client.versionsByHashes(List.of("a")));
		assertEquals(429, limited.statusCode());
		assertTrue(limited.rateLimited());

		respond("/v2/projects", 500, "oops");
		ModrinthException failed = assertThrows(ModrinthException.class, () -> client.projects(List.of("a")));
		assertEquals(500, failed.statusCode());
		assertFalse(failed.rateLimited());
	}

	@Test
	void downloadVerifiesSha512AndMovesIntoPlace(@TempDir Path dir) throws Exception {
		byte[] jar = "pretend jar".getBytes(StandardCharsets.UTF_8);
		String sha512 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(jar));
		responses.put("/cdn/mod.jar", new Response(200, jar));
		Path target = dir.resolve("mods").resolve("mod.jar.rigtune-pending");

		client.download(new ModFile(url("/cdn/mod.jar"), "mod.jar", sha512.toUpperCase(), jar.length), target);

		assertEquals("pretend jar", Files.readString(target));
		assertEquals("chaotix345/rigtune/1.2.3 (github.com/chaotix345/rigtune)", requests.get("/cdn/mod.jar").userAgent());
		try (var files = Files.list(target.getParent())) {
			assertEquals(List.of(target), files.toList());
		}
	}

	@Test
	void downloadStopsPastTheAnnouncedSizePlusSlack(@TempDir Path dir) throws Exception {
		byte[] body = bytes(3000);
		responses.put("/cdn/big.jar", new Response(200, body));
		Path target = dir.resolve("big.jar.rigtune-pending");

		IOException e = assertThrows(IOException.class, () -> client.download(new ModFile(url("/cdn/big.jar"), "big.jar", sha512(body), 100), target));

		assertTrue(e.getMessage().contains("larger than 1124 bytes"), e.getMessage());
		assertEquals(List.of(), listing(dir));
	}

	@Test
	void downloadHasAHardCapWhenTheSizeIsUnknown(@TempDir Path dir) throws Exception {
		byte[] body = bytes(2000);
		responses.put("/cdn/big.jar", new Response(200, body));
		HttpModrinthClient capped = client(new HttpModrinthClient.Limits(16L << 20, 500, Duration.ofSeconds(30),
				Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMillis(50)));
		Path target = dir.resolve("big.jar.rigtune-pending");

		IOException e = assertThrows(IOException.class, () -> capped.download(new ModFile(url("/cdn/big.jar"), "big.jar", sha512(body), 0), target));

		assertTrue(e.getMessage().contains("larger than 500 bytes"), e.getMessage());
		assertEquals(List.of(), listing(dir));

		byte[] small = bytes(400);
		responses.put("/cdn/small.jar", new Response(200, small));
		capped.download(new ModFile(url("/cdn/small.jar"), "small.jar", sha512(small), 0), dir.resolve("small.jar.rigtune-pending"));
		assertEquals(400, Files.size(dir.resolve("small.jar.rigtune-pending")));
	}

	@Test
	void jsonBodiesAreCapped() {
		respond("/v2/projects", 200, "[" + "{},".repeat(500) + "{}]");
		HttpModrinthClient capped = client(new HttpModrinthClient.Limits(100, 256L << 20, Duration.ofSeconds(30),
				Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMillis(50)));

		IOException e = assertThrows(IOException.class, () -> capped.projects(List.of("sodium")));

		assertTrue(e.getMessage().contains("larger than 100 bytes"), e.getMessage());
	}

	@Test
	void stalledDownloadGivesUpAndCleansUp(@TempDir Path dir) throws Exception {
		responses.put("/cdn/slow.jar", new Response(200, bytes(1000), Map.of(), true));
		HttpModrinthClient impatient = client(new HttpModrinthClient.Limits(16L << 20, 256L << 20, Duration.ofSeconds(30),
				Duration.ofSeconds(60), Duration.ofMillis(400), Duration.ofMinutes(10), Duration.ofMillis(50)));
		Path target = dir.resolve("slow.jar.rigtune-pending");

		long start = System.nanoTime();
		IOException e = assertThrows(IOException.class, () -> impatient.download(new ModFile(url("/cdn/slow.jar"), "slow.jar", "00ff", 10_000), target));

		assertTrue(e.getMessage().contains("stalled"), e.getMessage());
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
		assertEquals(List.of(), listing(dir));
	}

	@Test
	void downloadHitsTheOverallDeadline(@TempDir Path dir) {
		responses.put("/cdn/slow.jar", new Response(200, bytes(1000), Map.of(), true));
		HttpModrinthClient impatient = client(new HttpModrinthClient.Limits(16L << 20, 256L << 20, Duration.ofSeconds(30),
				Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMillis(500), Duration.ofMillis(50)));

		IOException e = assertThrows(IOException.class, () -> impatient.download(
				new ModFile(url("/cdn/slow.jar"), "slow.jar", "00ff", 10_000), dir.resolve("slow.jar.rigtune-pending")));

		assertTrue(e.getMessage().contains("took longer than"), e.getMessage());
	}

	@Test
	void stalledJsonGivesUp() {
		responses.put("/v2/projects", new Response(200, "[".getBytes(StandardCharsets.UTF_8), Map.of(), true));
		HttpModrinthClient impatient = client(new HttpModrinthClient.Limits(16L << 20, 256L << 20, Duration.ofMillis(400),
				Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMillis(50)));

		IOException e = assertThrows(IOException.class, () -> impatient.projects(List.of("sodium")));

		assertTrue(e.getMessage().contains("stalled"), e.getMessage());
	}

	@Test
	void rateLimitIsRetriedOnceAfterRetryAfter() throws IOException {
		byte[] limited = "{\"error\":\"ratelimit_error\"}".getBytes(StandardCharsets.UTF_8);
		queue("/v2/projects", new Response(429, limited, Map.of("Retry-After", "1"), false));
		respond("/v2/projects", 200, "[]");
		HttpModrinthClient patient = client(HttpModrinthClient.Limits.DEFAULT);

		long start = System.nanoTime();
		assertEquals(List.of(), patient.projects(List.of("sodium")));

		assertEquals(2, hits("/v2/projects"));
		assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(900));
	}

	@Test
	void rateLimitWaitIsCappedAndThereIsOnlyOneRetry() {
		byte[] limited = "{}".getBytes(StandardCharsets.UTF_8);
		responses.put("/v2/projects", new Response(429, limited, Map.of("Retry-After", "3600"), false));

		long start = System.nanoTime();
		ModrinthException e = assertThrows(ModrinthException.class, () -> client.projects(List.of("sodium")));

		assertTrue(e.rateLimited());
		assertEquals(2, hits("/v2/projects"));
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
	}

	@Test
	void rateLimitedDownloadIsRetried(@TempDir Path dir) throws Exception {
		byte[] jar = bytes(50);
		queue("/cdn/mod.jar", new Response(429, "slow down".getBytes(StandardCharsets.UTF_8), Map.of("Retry-After", "0"), false));
		responses.put("/cdn/mod.jar", new Response(200, jar));
		Path target = dir.resolve("mod.jar.rigtune-pending");

		client.download(new ModFile(url("/cdn/mod.jar"), "mod.jar", sha512(jar), jar.length), target);

		assertEquals(50, Files.size(target));
		assertEquals(2, hits("/cdn/mod.jar"));
	}

	@Test
	void parsesRetryAfterAsSecondsOrHttpDate() {
		Duration max = Duration.ofSeconds(10);
		assertEquals(Duration.ofSeconds(3), HttpModrinthClient.retryAfter(headers("Retry-After", "3"), max));
		assertEquals(max, HttpModrinthClient.retryAfter(headers("Retry-After", "86400"), max));
		String soon = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5));
		Duration fromDate = HttpModrinthClient.retryAfter(headers("Retry-After", soon), max);
		assertTrue(fromDate.compareTo(Duration.ofSeconds(3)) > 0 && fromDate.compareTo(Duration.ofSeconds(6)) <= 0, fromDate.toString());
		String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusHours(1));
		assertEquals(Duration.ZERO, HttpModrinthClient.retryAfter(headers("Retry-After", past), max));
		assertEquals(Duration.ofSeconds(1), HttpModrinthClient.retryAfter(headers("Retry-After", "soon"), max));
		assertEquals(Duration.ofSeconds(4), HttpModrinthClient.retryAfter(headers("X-Ratelimit-Reset", "4"), max));
		assertEquals(Duration.ofSeconds(1), HttpModrinthClient.retryAfter(HttpHeaders.of(Map.of(), (a, b) -> true), max));
	}

	private static HttpHeaders headers(String name, String value) {
		return HttpHeaders.of(Map.of(name, List.of(value)), (a, b) -> true);
	}

	@Test
	void downloadRefusesUnsafeFileNamesBeforeRequesting(@TempDir Path dir) {
		responses.put("/cdn/mod.jar", new Response(200, "x".getBytes(StandardCharsets.UTF_8)));
		Path target = dir.resolve("mod.jar.rigtune-pending");
		for (String name : List.of("../../evil.jar", "C:\\evil.jar", "evil.bat", "NUL.jar", "a\u0000.jar")) {
			IOException e = assertThrows(IOException.class, () -> client.download(new ModFile(url("/cdn/mod.jar"), name, "00ff", 1), target));
			assertTrue(e.getMessage().startsWith("Unsafe file name"), e.getMessage());
		}
		assertTrue(requests.isEmpty());
		assertFalse(Files.exists(target));
	}

	@Test
	void downloadRejectsHashMismatchAndLeavesNothingBehind(@TempDir Path dir) throws Exception {
		responses.put("/cdn/mod.jar", new Response(200, "tampered".getBytes(StandardCharsets.UTF_8)));
		Path target = dir.resolve("mod.jar.rigtune-pending");

		IOException e = assertThrows(IOException.class,
				() -> client.download(new ModFile(url("/cdn/mod.jar"), "mod.jar", "00ff", 8), target));

		assertTrue(e.getMessage().contains("SHA-512 mismatch"));
		try (var files = Files.list(dir)) {
			assertEquals(List.of(), files.toList());
		}
		assertThrows(ModrinthException.class, () -> client.download(new ModFile(url("/cdn/none.jar"), "none.jar", "00ff", 1), target));
	}

	@Test
	void allowedDownloadIsTheModrinthCdnOverHttpsOnly() {
		String base = HttpModrinthClient.DEFAULT_BASE_URL;
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("https://cdn.modrinth.com/data/AANobbMI/versions/x/x.jar"), base));
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("https://CDN.modrinth.com:443/x.jar"), base));
		for (String refused : List.of("http://cdn.modrinth.com/x.jar", "https://cdn.modrinth.com:8443/x.jar",
				"https://cdn.modrinth.com.evil.example/x.jar", "https://cdn.modrinth.com@evil.example/x.jar",
				"https://api.modrinth.com/x.jar", "ftp://cdn.modrinth.com/x.jar", "cdn.modrinth.com/x.jar")) {
			assertFalse(HttpModrinthClient.allowedDownload(URI.create(refused), base), refused);
		}
	}

	@Test
	void aTestBaseUrlAlsoAllowsItsOwnOrigin() {
		String base = "http://127.0.0.1:1234/";
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("http://127.0.0.1:1234/cdn/x.jar"), base));
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("https://cdn.modrinth.com/x.jar"), base));
		assertFalse(HttpModrinthClient.allowedDownload(URI.create("http://127.0.0.1:9999/cdn/x.jar"), base));
		assertFalse(HttpModrinthClient.allowedDownload(URI.create("https://127.0.0.1:1234/cdn/x.jar"), base));
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("https://localhost/x.jar"), "https://LOCALHOST:443"));
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("https://mirror.example/data/x.jar"), "https://mirror.example"));
	}

	@Test
	void aPlainHttpBaseUrlOffThisMachineAllowsNothingExtra() {
		assertFalse(HttpModrinthClient.allowedDownload(URI.create("http://mirror.example:8080/x.jar"), "http://mirror.example:8080"));
		assertFalse(HttpModrinthClient.allowedDownload(URI.create("http://10.0.0.5/x.jar"), "http://10.0.0.5"));
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("http://localhost:8080/x.jar"), "http://localhost:8080"));
		assertTrue(HttpModrinthClient.allowedDownload(URI.create("https://cdn.modrinth.com/x.jar"), "http://mirror.example:8080"));
	}

	@Test
	void defaultClientRefusesADownloadOutsideTheCdnBeforeRequesting(@TempDir Path dir) throws Exception {
		byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
		responses.put("/cdn/mod.jar", new Response(200, jar));
		HttpModrinthClient defaults = new HttpModrinthClient("1.2.3");

		IOException e = assertThrows(IOException.class,
				() -> defaults.download(new ModFile(url("/cdn/mod.jar"), "mod.jar", sha512(jar), jar.length), dir.resolve("mod.jar.rigtune-pending")));

		assertTrue(e.getMessage().contains("https://cdn.modrinth.com/"), e.getMessage());
		assertEquals(0, hits("/cdn/mod.jar"));
		assertEquals(List.of(), listing(dir));
	}

	@Test
	void baseUrlPropertyRedirectsRequestsAndDownloads(@TempDir Path dir) throws Exception {
		respond("/v2/version_files", 200, "{\"62a7\":" + VERSION_JSON + "}");
		byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
		responses.put("/cdn/mod.jar", new Response(200, jar));
		Path target = dir.resolve("mod.jar.rigtune-pending");
		System.setProperty(HttpModrinthClient.BASE_URL_PROPERTY, url("/"));
		try {
			HttpModrinthClient viaProperty = new HttpModrinthClient("1.2.3");
			assertEquals(1, viaProperty.versionsByHashes(List.of("62a7")).size());
			viaProperty.download(new ModFile(url("/cdn/mod.jar"), "mod.jar", sha512(jar), jar.length), target);
		} finally {
			System.clearProperty(HttpModrinthClient.BASE_URL_PROPERTY);
		}
		assertEquals(1, hits("/v2/version_files"));
		assertArrayEquals(jar, Files.readAllBytes(target));
	}

	@Test
	void downloadRedirectedToAnotherOriginIsRefusedBeforeRequestingIt(@TempDir Path dir) throws Exception {
		byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
		AtomicInteger otherHits = new AtomicInteger();
		HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		other.createContext("/", exchange -> {
			otherHits.incrementAndGet();
			exchange.sendResponseHeaders(200, jar.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(jar);
			}
		});
		other.start();
		try {
			responses.put("/cdn/moved.jar", new Response(302, new byte[0],
					Map.of("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/elsewhere.jar"), false));
			Path target = dir.resolve("moved.jar.rigtune-pending");

			IOException e = assertThrows(IOException.class,
					() -> client.download(new ModFile(url("/cdn/moved.jar"), "moved.jar", sha512(jar), jar.length), target));

			assertTrue(e.getMessage().contains("elsewhere.jar"), e.getMessage());
			assertEquals(0, otherHits.get());
			assertEquals(List.of(), listing(dir));
		} finally {
			other.stop(0);
		}
	}

	@Test
	void downloadFollowsARedirectWithinTheAllowedOrigin(@TempDir Path dir) throws Exception {
		byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
		responses.put("/cdn/moved.jar", new Response(302, new byte[0], Map.of("Location", "/cdn/mod.jar"), false));
		responses.put("/cdn/mod.jar", new Response(200, jar));
		Path target = dir.resolve("moved.jar.rigtune-pending");

		client.download(new ModFile(url("/cdn/moved.jar"), "moved.jar", sha512(jar), jar.length), target);

		assertArrayEquals(jar, Files.readAllBytes(target));
		assertEquals(1, hits("/cdn/mod.jar"));
	}

	@Test
	void aRedirectLoopGivesUp(@TempDir Path dir) throws Exception {
		responses.put("/cdn/loop.jar", new Response(307, new byte[0], Map.of("Location", url("/cdn/loop.jar")), false));

		IOException e = assertThrows(IOException.class,
				() -> client.download(new ModFile(url("/cdn/loop.jar"), "loop.jar", "00ff", 1), dir.resolve("loop.jar.rigtune-pending")));

		assertTrue(e.getMessage().contains("redirects"), e.getMessage());
		assertEquals(HttpModrinthClient.MAX_DOWNLOAD_REDIRECTS + 1, hits("/cdn/loop.jar"));
		assertEquals(List.of(), listing(dir));
	}
}
