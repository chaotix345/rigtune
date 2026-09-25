package io.github.chaotix345.rigtune.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.modrinth.HttpModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.OnlineDataFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeModrinthTest {
	private static final String PROJECT = "e2eRigTn";
	private static final String OLD_ID = "e2eV0100";
	private static final String NEW_ID = "e2eV0200";
	private static final String RULES_PATH = "/chaotix345/rigtune/main/rules/rules-v1.json";

	@TempDir
	Path dir;
	private FakeModrinth server;
	private byte[] oldJar;
	private byte[] newJar;
	private final HttpClient http = HttpClient.newHttpClient();

	@AfterEach
	void stop() {
		if (server != null) {
			server.close();
		}
	}

	// Two versions of one project: 0.1.0 (the "installed" jar) and 0.2.0 (published later, file name with a '+').
	private FakeModrinth start(boolean hostRouting) throws IOException {
		oldJar = randomBytes(1, 3000);
		newJar = randomBytes(2, 5000);
		Path oldFile = Files.write(dir.resolve("rigtune-0.1.0.jar"), oldJar);
		Path newFile = Files.write(dir.resolve("rigtune-0.2.0+mc26.2.jar"), newJar);
		Path rules = Files.writeString(dir.resolve("rules-v1.json"), "{\"schemaVersion\":1}");
		// The CDN base is filled in once the port is known (single origin), or is the real CDN name (host routing).
		String cdnBase = hostRouting ? "\"https://cdn.modrinth.com\"" : "null";
		String hosts = hostRouting ? "\"hosts\": {\"api\": \"api.modrinth.com\", \"cdn\": \"cdn.modrinth.com\"}," : "";
		String catalog = """
				{%s
				 "cdnBase": %s,
				 "projects": [{"id": "%s", "slug": "rigtune", "title": "RigTune", "versions": [
				   {"id": "%s", "version_number": "0.1.0", "version_type": "release", "date_published": "2026-09-24T08:00:00Z",
				    "game_versions": ["26.2"], "loaders": ["fabric"], "file": "%s",
				    "dependencies": [{"project_id": "P7dR8mSH", "dependency_type": "required"}]},
				   {"id": "%s", "version_number": "0.2.0+mc26.2", "version_type": "release", "date_published": "2026-09-25T08:00:00Z",
				    "game_versions": ["26.2"], "loaders": ["fabric"], "file": "%s", "dependencies": []}]}],
				 "static": [{"host": "raw.githubusercontent.com", "path": "%s", "file": "%s"}]}
				""".formatted(hosts, cdnBase, PROJECT, OLD_ID, slashes(oldFile), NEW_ID, slashes(newFile), RULES_PATH, slashes(rules));
		Path catalogFile = Files.writeString(dir.resolve("catalog.json"), catalog);
		server = FakeModrinth.start(catalogFile, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, dir.resolve("requests.jsonl"));
		return server;
	}

	private static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}

	private static byte[] randomBytes(long seed, int size) {
		byte[] out = new byte[size];
		new Random(seed).nextBytes(out);
		return out;
	}

	private static String hex(String algorithm, byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
	}

	private String base() {
		return "http://127.0.0.1:" + server.port();
	}

	private HttpResponse<String> get(String pathAndQuery) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(base() + pathAndQuery)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(base() + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String query(String json) {
		return URLEncoder.encode(json, StandardCharsets.UTF_8);
	}

	private record Raw(int status, String body) {
	}

	// HttpClient won't set a Host header, so host routing is tested over a raw socket.
	private Raw rawGet(String host, String path) throws IOException {
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
			OutputStream out = socket.getOutputStream();
			out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			InputStream in = socket.getInputStream();
			String response = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
			int bodyStart = response.indexOf("\r\n\r\n");
			return new Raw(Integer.parseInt(response.split(" ", 3)[1]), bodyStart < 0 ? "" : response.substring(bodyStart + 4));
		}
	}

	@Test
	void versionFilesMapsKnownHashesAndOmitsUnknownOnes() throws Exception {
		start(false);
		String sha1 = hex("SHA-1", oldJar);

		HttpResponse<String> response = post("/v2/version_files", "{\"hashes\":[\"" + sha1 + "\",\"0000\"],\"algorithm\":\"sha1\"}");

		assertEquals(200, response.statusCode());
		JsonObject map = JsonParser.parseString(response.body()).getAsJsonObject();
		assertEquals(List.of(sha1), List.copyOf(map.keySet()));
		JsonObject version = map.getAsJsonObject(sha1);
		assertEquals(OLD_ID, version.get("id").getAsString());
		assertEquals(PROJECT, version.get("project_id").getAsString());
		assertEquals(sha1, version.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonObject("hashes").get("sha1").getAsString());
	}

	@Test
	void updateReturnsTheNewestMatchingVersionAndHonoursFilters() throws Exception {
		start(false);
		String oldSha1 = hex("SHA-1", oldJar);
		String newSha1 = hex("SHA-1", newJar);
		String body = "{\"hashes\":[\"%s\",\"%s\"],\"algorithm\":\"sha1\",\"loaders\":[%s],\"game_versions\":[%s]}";

		JsonObject both = JsonParser.parseString(post("/v2/version_files/update",
				body.formatted(oldSha1, newSha1, "\"fabric\"", "\"26.2\"")).body()).getAsJsonObject();
		assertEquals(NEW_ID, both.getAsJsonObject(oldSha1).get("id").getAsString());
		assertEquals(NEW_ID, both.getAsJsonObject(newSha1).get("id").getAsString());

		assertEquals(0, JsonParser.parseString(post("/v2/version_files/update",
				body.formatted(oldSha1, newSha1, "\"fabric\"", "\"26.3\"")).body()).getAsJsonObject().size());
		assertEquals(0, JsonParser.parseString(post("/v2/version_files/update",
				body.formatted(oldSha1, newSha1, "\"quilt\"", "\"26.2\"")).body()).getAsJsonObject().size());
	}

	@Test
	void projectsReturnsAnArrayOfKnownProjects() throws Exception {
		start(false);

		JsonArray found = JsonParser.parseString(get("/v2/projects?ids=" + query("[\"rigtune\",\"nope\"]")).body()).getAsJsonArray();
		assertEquals(1, found.size());
		JsonObject project = found.get(0).getAsJsonObject();
		assertEquals("rigtune", project.get("slug").getAsString());
		assertEquals(PROJECT, project.get("id").getAsString());
		assertTrue(project.getAsJsonArray("game_versions").toString().contains("26.2"));
		assertTrue(project.getAsJsonArray("loaders").toString().contains("fabric"));

		assertEquals("[]", get("/v2/projects?ids=" + query("[]")).body());
	}

	@Test
	void projectVersionsFiltersAndUnknownProjectIs404() throws Exception {
		start(false);
		String filters = "?loaders=" + query("[\"fabric\"]") + "&game_versions=" + query("[\"26.2\"]");

		JsonArray versions = JsonParser.parseString(get("/v2/project/rigtune/version" + filters).body()).getAsJsonArray();
		assertEquals(List.of(NEW_ID, OLD_ID), versions.asList().stream().map(v -> v.getAsJsonObject().get("id").getAsString()).toList());
		assertEquals("[]", get("/v2/project/" + PROJECT + "/version?game_versions=" + query("[\"26.3\"]")).body());
		assertEquals(404, get("/v2/project/nope/version").statusCode());
		assertEquals(404, get("/v2/nothing-here").statusCode());
	}

	@Test
	void cdnServesTheJarWithTheAdvertisedHashes() throws Exception {
		start(false);
		JsonObject newVersion = JsonParser.parseString(get("/v2/version_file/" + hex("SHA-1", newJar)).body()).getAsJsonObject();
		JsonObject file = newVersion.getAsJsonArray("files").get(0).getAsJsonObject();
		String url = file.get("url").getAsString();
		assertTrue(url.startsWith(base() + "/data/" + PROJECT + "/versions/" + NEW_ID + "/"), url);
		assertTrue(url.endsWith("rigtune-0.2.0%2Bmc26.2.jar"), url);
		assertEquals("rigtune-0.2.0+mc26.2.jar", file.get("filename").getAsString());

		HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, download.statusCode());
		assertArrayEquals(newJar, download.body());
		assertEquals(hex("SHA-512", newJar), file.getAsJsonObject("hashes").get("sha512").getAsString());
		assertEquals(newJar.length, file.get("size").getAsLong());
		assertTrue(file.get("primary").getAsBoolean());
	}

	@Test
	void hostRoutingServesApiOnlyOnTheApiHostAndFilesOnlyOnTheCdnHost() throws Exception {
		start(true);
		JsonObject version = JsonParser.parseString(rawGet("api.modrinth.com", "/v2/version_file/" + hex("SHA-1", newJar)).body())
				.getAsJsonObject();
		String url = version.getAsJsonArray("files").get(0).getAsJsonObject().get("url").getAsString();
		assertTrue(url.startsWith("https://cdn.modrinth.com/data/" + PROJECT + "/versions/" + NEW_ID + "/"), url);
		String filePath = URI.create(url).getRawPath();

		assertEquals(200, rawGet("api.modrinth.com", "/v2/projects?ids=" + query("[]")).status());
		assertEquals(404, rawGet("cdn.modrinth.com", "/v2/projects?ids=" + query("[]")).status());
		assertEquals(200, rawGet("cdn.modrinth.com:443", filePath).status());
		assertEquals(404, rawGet("api.modrinth.com", filePath).status());
		assertEquals(404, rawGet("example.com", "/v2/projects?ids=" + query("[]")).status());
	}

	@Test
	void staticFilesAreServedOnTheirHost() throws Exception {
		start(true);

		assertEquals(200, rawGet("raw.githubusercontent.com", RULES_PATH).status());
		assertEquals(404, rawGet("api.modrinth.com", RULES_PATH).status());
		assertEquals("{\"schemaVersion\":1}", rawGet("raw.githubusercontent.com", RULES_PATH).body());
	}

	@Test
	void requestsAreLogged() throws Exception {
		start(false);
		get("/v2/projects?ids=" + query("[]"));
		post("/v2/version_files", "{\"hashes\":[],\"algorithm\":\"sha1\"}");

		List<String> lines = Files.readAllLines(dir.resolve("requests.jsonl"));
		assertEquals(2, lines.size());
		JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
		assertEquals("GET", first.get("method").getAsString());
		assertEquals("/v2/projects", first.get("path").getAsString());
		assertEquals(200, first.get("status").getAsInt());
		assertNotNull(first.get("host"));
		assertEquals("POST", JsonParser.parseString(lines.get(1)).getAsJsonObject().get("method").getAsString());
	}

	// What 0.1.0 and 0.2 do (core/modrinth is unchanged since v0.1.0 apart from the download allowlist): find the update
	// by the installed jar's hash, then download it with the SHA-512 check.
	@Test
	void realClientFindsAndDownloadsTheUpdate() throws Exception {
		start(false);
		HttpModrinthClient client = new HttpModrinthClient("0.1.0", base());
		Path installed = dir.resolve("mods").resolve("rigtune-0.1.0.jar");
		InstalledMod rigtune = new InstalledMod("rigtune", "RigTune", "0.1.0", installed, hex("SHA-1", oldJar));

		OnlineDataFetcher.Result result = new OnlineDataFetcher(client).fetchAll(List.of(rigtune), List.of("rigtune", "sodium"), "26.2");

		assertTrue(result.data().online());
		assertEquals(PROJECT, result.projectIdsByModId().get("rigtune"));
		assertEquals(Boolean.TRUE, result.data().availableBySlug().get("rigtune"));
		UpdateInfo update = result.data().updatesByModId().get("rigtune");
		assertNotNull(update, "no update offered");
		assertEquals("0.1.0", update.currentVersion());
		assertEquals("0.2.0+mc26.2", update.newVersionNumber());
		ModFile file = update.file();
		Path pending = dir.resolve("mods").resolve(file.filename() + ".rigtune-pending");
		client.download(file, pending);
		assertArrayEquals(newJar, Files.readAllBytes(pending));
	}

	@Test
	void httpsWithAKeytoolCertificate() throws Exception {
		Path keystore = dir.resolve("server.p12");
		String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
		Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048",
				"-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
				"-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
				.redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output);
		start(false).close();
		SSLContext tls = FakeModrinth.tls(keystore, "changeit".toCharArray());
		server = FakeModrinth.start(dir.resolve("catalog.json"), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), tls, null);

		HttpClient trusting = HttpClient.newBuilder().sslContext(tls).build();
		HttpResponse<String> response = trusting.send(HttpRequest.newBuilder(
				URI.create("https://127.0.0.1:" + server.port() + "/v2/projects?ids=" + query("[\"rigtune\"]"))).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"slug\":\"rigtune\""), response.body());
	}
}
