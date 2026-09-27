package io.github.chaotix345.rigtune.e2e;

import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.modrinth.FabricModJson;
import io.github.chaotix345.rigtune.core.modrinth.HttpModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.RangeReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2H L5: the game tests' fake Modrinth serves its CDN files to Range requests as cdn.modrinth.com does
// (docs/v0.5/design/ws-h.md), so the preview's in-memory fabric.mod.json reads run against it in CI (FakeModrinth's one
// WS-H method, `ranged`).
class FakeModrinthRangeTest {
	@TempDir
	Path dir;
	private FakeModrinth server;
	private byte[] jar;
	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void start() throws IOException {
		Path file = TestJars.modJar(dir.resolve("examplemod-1.0.jar"), "examplemod", "Example Mod");
		jar = Files.readAllBytes(file);
		String catalog = """
				{"cdnBase": null,
				 "projects": [{"id": "EXAMPLE1", "slug": "examplemod", "title": "Example Mod", "versions": [
				   {"id": "exV10000", "version_number": "1.0", "version_type": "release", "date_published": "2026-09-24T08:00:00Z",
				    "game_versions": ["26.2"], "loaders": ["fabric"], "file": "%s", "dependencies": []}]}]}
				""".formatted(file.toAbsolutePath().toString().replace('\\', '/'));
		server = FakeModrinth.start(Files.writeString(dir.resolve("catalog.json"), catalog), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
				dir.resolve("requests.jsonl"));
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private String file() {
		return "http://127.0.0.1:" + server.port() + "/data/EXAMPLE1/versions/exV10000/examplemod-1.0.jar";
	}

	private HttpResponse<byte[]> get(String url, String range) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
		if (range != null) {
			request.header("Range", range);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	@Test
	void singleRangesGetA206WithTheirContentRange() throws Exception {
		int size = jar.length;
		HttpResponse<byte[]> suffix = get(file(), "bytes=-22");
		HttpResponse<byte[]> explicit = get(file(), "bytes=10-19");
		HttpResponse<byte[]> openEnd = get(file(), "bytes=" + (size - 5) + "-");
		HttpResponse<byte[]> longer = get(file(), "bytes=-999999");

		assertEquals(206, suffix.statusCode());
		assertEquals("bytes " + (size - 22) + "-" + (size - 1) + "/" + size, suffix.headers().firstValue("Content-Range").orElseThrow());
		assertArrayEquals(Arrays.copyOfRange(jar, size - 22, size), suffix.body());
		assertEquals("bytes 10-19/" + size, explicit.headers().firstValue("Content-Range").orElseThrow());
		assertArrayEquals(Arrays.copyOfRange(jar, 10, 20), explicit.body());
		assertArrayEquals(Arrays.copyOfRange(jar, size - 5, size), openEnd.body());
		assertEquals("bytes 0-" + (size - 1) + "/" + size, longer.headers().firstValue("Content-Range").orElseThrow());
	}

	@Test
	void pastTheEndSeveralRangesOrNoneAreNotA206() throws Exception {
		assertEquals(416, get(file(), "bytes=" + jar.length + "-" + (jar.length + 10)).statusCode());
		HttpResponse<byte[]> several = get(file(), "bytes=0-9,20-29");
		assertEquals(200, several.statusCode());
		assertArrayEquals(jar, several.body());
		HttpResponse<byte[]> none = get(file(), null);
		assertEquals(200, none.statusCode());
		assertArrayEquals(jar, none.body());
		// The API isn't ranged.
		assertEquals(200, get("http://127.0.0.1:" + server.port() + "/v2/project/examplemod", "bytes=0-1").statusCode());
	}

	// The preview's reader against the fake, as in the game tests (-Drigtune.modrinth.baseUrl names the fake's origin).
	@Test
	void theRangeReaderReadsFabricModJsonFromTheFake() {
		String before = System.getProperty(HttpModrinthClient.BASE_URL_PROPERTY);
		System.setProperty(HttpModrinthClient.BASE_URL_PROPERTY, "http://127.0.0.1:" + server.port());
		try (RangeReader reader = new RangeReader("0.5.0-test", () -> true)) {
			RangeReader.Read read = reader.read(new ModFile(file(), "examplemod-1.0.jar", "unused", jar.length));

			assertTrue(read.ok(), read.toString());
			FabricModJson json = FabricModJson.parse(read.fabricModJson());
			assertEquals("examplemod", json.id());
			assertEquals("Example Mod", json.name());
			assertEquals(1, read.requests());
		} finally {
			if (before == null) {
				System.clearProperty(HttpModrinthClient.BASE_URL_PROPERTY);
			} else {
				System.setProperty(HttpModrinthClient.BASE_URL_PROPERTY, before);
			}
		}
	}
}
