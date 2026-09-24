package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.model.ModFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class HttpModrinthClient implements ModrinthClient {
	public static final String DEFAULT_BASE_URL = "https://api.modrinth.com";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

	private final HttpClient http;
	private final String baseUrl;
	private final String userAgent;

	public HttpModrinthClient(String modVersion) {
		this(modVersion, DEFAULT_BASE_URL);
	}

	public HttpModrinthClient(String modVersion, String baseUrl) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.userAgent = userAgent(modVersion);
		this.http = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public static String userAgent(String modVersion) {
		return "chaotix345/rigtune/" + modVersion + " (github.com/chaotix345/rigtune)";
	}

	@Override
	public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws IOException {
		if (sha1s.isEmpty()) {
			return Map.of();
		}
		return versionMap(post("/v2/version_files", hashBody(sha1s)));
	}

	@Override
	public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) throws IOException {
		if (sha1s.isEmpty()) {
			return Map.of();
		}
		JsonObject body = hashBody(sha1s);
		body.add("loaders", array(List.of(loader)));
		body.add("game_versions", array(List.of(gameVersion)));
		return versionMap(post("/v2/version_files/update", body));
	}

	@Override
	public List<ModrinthProject> projects(Collection<String> idsOrSlugs) throws IOException {
		if (idsOrSlugs.isEmpty()) {
			return List.of();
		}
		String body = get("/v2/projects?ids=" + encode(array(idsOrSlugs).toString()));
		return JsonParser.parseString(body).getAsJsonArray().asList().stream()
				.map(e -> ModrinthProject.fromJson(e.getAsJsonObject()))
				.toList();
	}

	@Override
	public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) throws IOException {
		String path = "/v2/project/" + encode(idOrSlug) + "/version"
				+ "?loaders=" + encode(array(List.of(loader)).toString())
				+ "&game_versions=" + encode(array(List.of(gameVersion)).toString())
				+ "&include_changelog=false";
		String body;
		try {
			body = get(path);
		} catch (ModrinthException e) {
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw e;
		}
		List<ModrinthVersion> versions = JsonParser.parseString(body).getAsJsonArray().asList().stream()
				.map(e -> ModrinthVersion.fromJson(e.getAsJsonObject()))
				.toList();
		return pickLatest(versions, loader, gameVersion);
	}

	static Optional<ModrinthVersion> pickLatest(List<ModrinthVersion> versions, String loader, String gameVersion) {
		Comparator<ModrinthVersion> byDate = Comparator.comparing(ModrinthVersion::datePublished);
		List<ModrinthVersion> compatible = versions.stream()
				.filter(v -> v.loaders().contains(loader) && v.gameVersions().contains(gameVersion) && !v.files().isEmpty())
				.toList();
		Optional<ModrinthVersion> release = compatible.stream().filter(ModrinthVersion::isRelease).max(byDate);
		return release.isPresent() ? release : compatible.stream().max(byDate);
	}

	@Override
	public void download(ModFile file, Path target) throws IOException {
		if (file.sha512() == null || file.sha512().isBlank()) {
			throw new IOException("Refusing to download " + file.filename() + " without a SHA-512");
		}
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		HttpRequest request = request(URI.create(file.url())).GET().build();
		HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = response.body()) {
			if (response.statusCode() / 100 != 2) {
				throw error(request, response.statusCode(), "");
			}
			Path tmp = Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
			try {
				MessageDigest digest = sha512();
				try (OutputStream out = Files.newOutputStream(tmp); DigestInputStream din = new DigestInputStream(in, digest)) {
					din.transferTo(out);
				}
				String actual = HexFormat.of().formatHex(digest.digest());
				if (!actual.equalsIgnoreCase(file.sha512())) {
					throw new IOException("SHA-512 mismatch for " + file.filename() + ": expected " + file.sha512() + ", got " + actual);
				}
				moveAtomically(tmp, target);
			} catch (IOException | RuntimeException e) {
				Files.deleteIfExists(tmp);
				throw e;
			}
		}
	}

	private static void moveAtomically(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static MessageDigest sha512() {
		try {
			return MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private String get(String pathAndQuery) throws IOException {
		return sendForString(request(URI.create(baseUrl + pathAndQuery)).GET().build());
	}

	private String post(String path, JsonObject body) throws IOException {
		return sendForString(request(URI.create(baseUrl + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build());
	}

	private HttpRequest.Builder request(URI uri) {
		return HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("User-Agent", userAgent);
	}

	private String sendForString(HttpRequest request) throws IOException {
		HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() / 100 != 2) {
			throw error(request, response.statusCode(), response.body());
		}
		return response.body();
	}

	private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return http.send(request, handler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted: " + request.method() + " " + request.uri());
		}
	}

	private static ModrinthException error(HttpRequest request, int status, String body) {
		String snippet = body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
		return new ModrinthException(status, request.method() + " " + request.uri() + " returned HTTP " + status
				+ (snippet.isBlank() ? "" : ": " + snippet));
	}

	private static JsonObject hashBody(Collection<String> sha1s) {
		JsonObject body = new JsonObject();
		body.add("hashes", array(sha1s));
		body.addProperty("algorithm", "sha1");
		return body;
	}

	private static JsonArray array(Collection<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static Map<String, ModrinthVersion> versionMap(String body) {
		Map<String, ModrinthVersion> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(body).getAsJsonObject().entrySet()) {
			out.put(entry.getKey(), ModrinthVersion.fromJson(entry.getValue().getAsJsonObject()));
		}
		return out;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
