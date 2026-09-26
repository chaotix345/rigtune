package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.net.BoundedHttp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class HttpModrinthClient implements ModrinthClient {
	public static final String DEFAULT_BASE_URL = "https://api.modrinth.com";
	// For tests against a local server (tools/e2e); downloads are then also allowed from that server's origin.
	public static final String BASE_URL_PROPERTY = "rigtune.modrinth.baseUrl";
	private static final URI CDN = URI.create("https://cdn.modrinth.com/");
	static final int MAX_DOWNLOAD_REDIRECTS = 5;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final long DOWNLOAD_SLACK_BYTES = 1024;
	private static final long ERROR_BODY_BYTES = 64 * 1024;

	// maxRetryWait caps how long a 429's Retry-After is honoured before the single retry.
	record Limits(long maxJsonBytes, long maxDownloadBytes, Duration jsonStall, Duration jsonDeadline,
			Duration downloadStall, Duration downloadDeadline, Duration maxRetryWait) {
		static final Limits DEFAULT = new Limits(16L << 20, 256L << 20, Duration.ofSeconds(30), Duration.ofSeconds(60),
				Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofSeconds(10));
	}

	// Both built on first use (a request, always off the render thread), never in the constructor: RealController makes
	// this client in onInitializeClient, where java.net.http's classes and threads cost the render thread tens of ms
	// (docs/v0.4/SPEC.md 10). With Modrinth or the network off, GatedModrinthClient stops every call first, so neither
	// is ever built.
	private volatile HttpClient http;
	// Downloads follow redirects by hand, so every hop is checked against the allowlist before it is requested.
	private volatile HttpClient downloads;
	private final String baseUrl;
	private final String userAgent;
	private final Limits limits;

	public HttpModrinthClient(String modVersion) {
		this(modVersion, baseUrlOrDefault(System.getProperty(BASE_URL_PROPERTY)));
	}

	private static String baseUrlOrDefault(String configured) {
		return configured == null || configured.isBlank() ? DEFAULT_BASE_URL : configured.trim();
	}

	public HttpModrinthClient(String modVersion, String baseUrl) {
		this(modVersion, baseUrl, Limits.DEFAULT);
	}

	HttpModrinthClient(String modVersion, String baseUrl, Limits limits) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.userAgent = userAgent(modVersion);
		this.limits = limits;
	}

	private HttpClient http() {
		HttpClient client = http;
		if (client == null) {
			synchronized (this) {
				client = http;
				if (client == null) {
					http = client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
				}
			}
		}
		return client;
	}

	private HttpClient downloads() {
		HttpClient client = downloads;
		if (client == null) {
			synchronized (this) {
				client = downloads;
				if (client == null) {
					downloads = client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
				}
			}
		}
		return client;
	}

	// For tests: whether either HttpClient exists yet.
	boolean httpClientsBuilt() {
		return http != null || downloads != null;
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
		SafeFileNames.requireJarName(file.filename());
		if (file.sha512() == null || file.sha512().isBlank()) {
			throw new IOException("Refusing to download " + file.filename() + " without a SHA-512");
		}
		URI uri = URI.create(file.url());
		requireAllowedDownload(file, uri);
		Path dir = target.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		long cap = file.size() > 0 ? Math.min(file.size() + DOWNLOAD_SLACK_BYTES, limits.maxDownloadBytes()) : limits.maxDownloadBytes();
		Path tmp = Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
		try {
			MessageDigest digest = sha512();
			HttpRequest request;
			HttpResponse<Void> response;
			try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				// Only a 2xx body is written; a redirect's body is discarded.
				for (int hop = 0; ; hop++) {
					request = request(uri).GET().build();
					response = exchange(downloads(), request, (info, progress) -> info.statusCode() / 100 == 2
							? BoundedHttp.capped(cap, false, progress, buffer -> {
								digest.update(buffer.duplicate());
								while (buffer.hasRemaining()) {
									out.write(buffer);
								}
							})
							: BoundedHttp.capped(ERROR_BODY_BYTES, true, progress, buffer -> buffer.position(buffer.limit())),
							limits.downloadStall(), limits.downloadDeadline());
					Optional<String> location = response.headers().firstValue("Location");
					if (!isRedirect(response.statusCode()) || location.isEmpty()) {
						break;
					}
					if (hop == MAX_DOWNLOAD_REDIRECTS) {
						throw new IOException("Too many redirects downloading " + file.filename());
					}
					uri = uri.resolve(location.get());
					requireAllowedDownload(file, uri);
				}
			}
			if (response.statusCode() / 100 != 2) {
				throw error(request, response.statusCode(), "");
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

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private void requireAllowedDownload(ModFile file, URI uri) throws IOException {
		if (!allowedDownload(uri, baseUrl)) {
			URI base = extraDownloadOrigin(baseUrl);
			throw new IOException("Refusing to download " + file.filename() + " from " + uri + ": only "
					+ (base == null ? CDN + " is" : CDN + " and " + base.getScheme() + "://" + base.getRawAuthority() + " are") + " allowed");
		}
	}

	// Downloads come only from Modrinth's CDN over HTTPS, as the README promises, plus the origin of a non-default base
	// URL (a test server).
	static boolean allowedDownload(URI uri, String baseUrl) {
		URI base = extraDownloadOrigin(baseUrl);
		return sameOrigin(uri, CDN) || base != null && sameOrigin(uri, base);
	}

	// A non-default base URL's origin, if it is https, or plain http on this machine (a local test server).
	private static URI extraDownloadOrigin(String baseUrl) {
		try {
			URI base = URI.create(baseUrl);
			if (base.getScheme() == null || base.getHost() == null || sameOrigin(base, URI.create(DEFAULT_BASE_URL))) {
				return null;
			}
			String scheme = base.getScheme().toLowerCase(Locale.ROOT);
			String host = base.getHost().toLowerCase(Locale.ROOT);
			boolean loopback = host.equals("localhost") || host.equals("[::1]") || host.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
			return scheme.equals("https") || scheme.equals("http") && loopback ? base : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean sameOrigin(URI a, URI b) {
		return a.getScheme() != null && b.getScheme() != null && a.getHost() != null && b.getHost() != null
				&& a.getRawUserInfo() == null
				&& a.getScheme().equalsIgnoreCase(b.getScheme())
				&& a.getHost().equalsIgnoreCase(b.getHost())
				&& port(a) == port(b);
	}

	private static int port(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
			case "https" -> 443;
			case "http" -> 80;
			default -> -1;
		};
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
		HttpResponse<byte[]> response = exchange(http(), request, (info, progress) -> info.statusCode() / 100 == 2
				? BoundedHttp.bytes(limits.maxJsonBytes(), false, progress)
				: BoundedHttp.bytes(ERROR_BODY_BYTES, true, progress), limits.jsonStall(), limits.jsonDeadline());
		String body = new String(response.body(), StandardCharsets.UTF_8);
		if (response.statusCode() / 100 != 2) {
			throw error(request, response.statusCode(), body);
		}
		return body;
	}

	private interface Handler<T> {
		HttpResponse.BodySubscriber<T> apply(HttpResponse.ResponseInfo info, BoundedHttp.Progress progress);
	}

	// A 429 is retried once, after its Retry-After (capped at limits.maxRetryWait()).
	private <T> HttpResponse<T> exchange(HttpClient client, HttpRequest request, Handler<T> handler, Duration stall, Duration deadline)
			throws IOException {
		for (int attempt = 1; ; attempt++) {
			BoundedHttp.Progress progress = new BoundedHttp.Progress();
			HttpResponse<T> response = BoundedHttp.send(client, request, info -> handler.apply(info, progress), progress, stall, deadline);
			if (response.statusCode() != 429 || attempt > 1) {
				return response;
			}
			Duration wait = retryAfter(response.headers(), limits.maxRetryWait());
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while rate limited: " + request.uri());
			}
		}
	}

	static Duration retryAfter(HttpHeaders headers, Duration max) {
		Duration wait = headers.firstValue("Retry-After").map(HttpModrinthClient::parseRetryAfter)
				.or(() -> headers.firstValue("X-Ratelimit-Reset").map(HttpModrinthClient::parseRetryAfter))
				.orElse(Duration.ofSeconds(1));
		if (wait.isNegative()) {
			return Duration.ZERO;
		}
		return wait.compareTo(max) > 0 ? max : wait;
	}

	private static Duration parseRetryAfter(String value) {
		String text = value.trim();
		try {
			return Duration.ofSeconds(Long.parseLong(text));
		} catch (NumberFormatException ignored) {
		}
		try {
			return Duration.between(Instant.now(), ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
		} catch (DateTimeParseException ignored) {
			return null;
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
