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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

	private record Recorded(String method, String query, String body, String userAgent) {
	}

	private record Response(int status, byte[] body) {
	}

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		server.start();
		client = new HttpModrinthClient("1.2.3", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		requests.put(path, new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(), body,
				exchange.getRequestHeaders().getFirst("User-Agent")));
		Response response = responses.getOrDefault(path, new Response(404, "{}".getBytes(StandardCharsets.UTF_8)));
		exchange.sendResponseHeaders(response.status(), response.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(response.body());
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
}
