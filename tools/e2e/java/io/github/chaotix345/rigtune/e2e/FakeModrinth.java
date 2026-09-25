package io.github.chaotix345.rigtune.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A stand-in for api.modrinth.com and cdn.modrinth.com (plus static files, e.g. the rules on raw.githubusercontent.com)
 * for the self-update end-to-end test. See tools/e2e/README.md. It answers the endpoints RigTune calls, with versions
 * and hashes computed from real jar files listed in a catalog. JDK only, so it runs as
 * {@code java FakeModrinth.java --catalog <file> [--port 443] [--keystore <p12> --storepass <pw>] [--log <jsonl>]}.
 *
 * <p>With {@code hosts} in the catalog, the API is served only for the API host, files only for the CDN host and each
 * static file only for its host (the Host header decides). Without it, every path is served on any host, and file URLs
 * point at this server (for -Drigtune.modrinth.baseUrl runs and tests).
 */
public final class FakeModrinth implements AutoCloseable {
	private final HttpServer server;
	private final ExecutorService executor;
	private final Catalog catalog;
	private final Path requestLog;

	private FakeModrinth(HttpServer server, ExecutorService executor, Catalog catalog, Path requestLog) {
		this.server = server;
		this.executor = executor;
		this.catalog = catalog;
		this.requestLog = requestLog;
	}

	public static FakeModrinth start(Path catalogFile, InetSocketAddress bind, SSLContext tls, Path requestLog) throws IOException {
		HttpServer server;
		if (tls != null) {
			HttpsServer https = HttpsServer.create(bind, 0);
			https.setHttpsConfigurator(new HttpsConfigurator(tls));
			server = https;
		} else {
			server = HttpServer.create(bind, 0);
		}
		InetSocketAddress bound = server.getAddress();
		String selfOrigin = (tls != null ? "https" : "http") + "://" + bound.getAddress().getHostAddress() + ":" + bound.getPort();
		Catalog catalog = Catalog.load(catalogFile, selfOrigin);
		ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "FakeModrinth");
			thread.setDaemon(true);
			return thread;
		});
		FakeModrinth fake = new FakeModrinth(server, executor, catalog, requestLog);
		server.createContext("/", fake::handle);
		server.setExecutor(executor);
		server.start();
		return fake;
	}

	public int port() {
		return server.getAddress().getPort();
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

	public static SSLContext tls(Path keystore, char[] password) throws IOException {
		try (InputStream in = Files.newInputStream(keystore)) {
			KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(in, password);
			KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keys.init(store, password);
			TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trust.init(store);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
			return context;
		} catch (GeneralSecurityException e) {
			throw new IOException("Unusable keystore " + keystore + ": " + e, e);
		}
	}

	// Serves until stdin reaches end of file (the orchestrator closes it), so start it with a pipe for stdin.
	public static void main(String[] args) throws Exception {
		Map<String, String> options = new LinkedHashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2) {
			if (!args[i].startsWith("--")) {
				throw new IllegalArgumentException("Unexpected argument " + args[i]);
			}
			options.put(args[i].substring(2), args[i + 1]);
		}
		if (!options.containsKey("catalog") || args.length % 2 != 0) {
			System.err.println("Usage: FakeModrinth --catalog <file> [--bind 127.0.0.1] [--port 443] "
					+ "[--keystore <p12> --storepass <password>] [--log <requests.jsonl>]");
			System.exit(2);
		}
		SSLContext tls = options.containsKey("keystore")
				? tls(Path.of(options.get("keystore")), options.getOrDefault("storepass", "").toCharArray()) : null;
		InetSocketAddress bind = new InetSocketAddress(InetAddress.getByName(options.getOrDefault("bind", "127.0.0.1")),
				Integer.parseInt(options.getOrDefault("port", "443")));
		Path log = options.containsKey("log") ? Path.of(options.get("log")) : null;
		try (FakeModrinth fake = start(Path.of(options.get("catalog")), bind, tls, log)) {
			System.out.println("FakeModrinth listening on " + (tls != null ? "https" : "http") + "://"
					+ bind.getAddress().getHostAddress() + ":" + fake.port());
			System.out.flush();
			while (System.in.read() != -1) {
				// Keep serving.
			}
		}
	}

	private record Response(int status, String contentType, byte[] body) {
		static Response json(Object value) {
			return new Response(200, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8));
		}

		static Response notFound(String what) {
			return new Response(404, "application/json; charset=utf-8",
					Json.write(Map.of("error", "not_found", "description", what)).getBytes(StandardCharsets.UTF_8));
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String host = hostOf(exchange.getRequestHeaders().getFirst("Host"));
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getRawQuery();
		Response response;
		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			response = route(host, method, path, query, body);
		} catch (RuntimeException e) {
			response = new Response(400, "application/json; charset=utf-8",
					Json.write(Map.of("error", "invalid_input", "description", String.valueOf(e))).getBytes(StandardCharsets.UTF_8));
		}
		exchange.getResponseHeaders().set("Content-Type", response.contentType());
		exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(response.body());
		}
		log(method, host, path, query, response.status(), exchange.getRequestHeaders().getFirst("User-Agent"), response.body().length);
	}

	private static String hostOf(String header) {
		if (header == null) {
			return "";
		}
		String host = header.trim().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		return colon > 0 && !host.endsWith("]") ? host.substring(0, colon) : host;
	}

	private Response route(String host, String method, String path, String query, String body) throws IOException {
		for (Catalog.StaticFile file : catalog.statics) {
			if (file.path().equals(path) && (catalog.apiHost == null || file.host().equalsIgnoreCase(host))) {
				return "GET".equals(method) ? new Response(200, file.contentType(), Files.readAllBytes(file.file())) : methodNotAllowed();
			}
		}
		if (path.startsWith("/v2/") && (catalog.apiHost == null || catalog.apiHost.equalsIgnoreCase(host))) {
			return api(method, path, query, body);
		}
		if (path.startsWith("/data/") && (catalog.cdnHost == null || catalog.cdnHost.equalsIgnoreCase(host))) {
			return "GET".equals(method) ? cdn(path) : methodNotAllowed();
		}
		return Response.notFound(host + path);
	}

	private static Response methodNotAllowed() {
		return new Response(405, "text/plain; charset=utf-8", new byte[0]);
	}

	private Response api(String method, String path, String query, String body) {
		Map<String, String> params = params(query);
		if (path.equals("/v2/version_files") && "POST".equals(method)) {
			Map<String, Object> request = Json.object(body);
			Map<String, Object> out = new LinkedHashMap<>();
			for (String hash : Json.strings(request.get("hashes"))) {
				Catalog.Version version = catalog.byHash(algorithm(request), hash);
				if (version != null) {
					out.put(hash, catalog.versionJson(version));
				}
			}
			return Response.json(out);
		}
		if (path.equals("/v2/version_files/update") && "POST".equals(method)) {
			Map<String, Object> request = Json.object(body);
			Map<String, Object> out = new LinkedHashMap<>();
			for (String hash : Json.strings(request.get("hashes"))) {
				Catalog.Version current = catalog.byHash(algorithm(request), hash);
				if (current == null) {
					continue;
				}
				catalog.matching(current.project(), Json.strings(request.get("loaders")), Json.strings(request.get("game_versions"))).stream()
						.findFirst()
						.ifPresent(latest -> out.put(hash, catalog.versionJson(latest)));
			}
			return Response.json(out);
		}
		if (!"GET".equals(method)) {
			return methodNotAllowed();
		}
		if (path.equals("/v2/projects")) {
			List<Object> out = new ArrayList<>();
			for (String ref : Json.strings(params.containsKey("ids") ? Json.parse(params.get("ids")) : null)) {
				Catalog.Project project = catalog.project(ref);
				if (project != null && !out.contains(catalog.projectJson(project))) {
					out.add(catalog.projectJson(project));
				}
			}
			return Response.json(out);
		}
		String[] parts = path.split("/");
		// /v2/project/{ref} and /v2/project/{ref}/version
		if (parts.length >= 4 && parts[2].equals("project")) {
			Catalog.Project project = catalog.project(parts[3]);
			if (project == null) {
				return Response.notFound("project " + parts[3]);
			}
			if (parts.length == 4) {
				return Response.json(catalog.projectJson(project));
			}
			if (parts.length == 5 && parts[4].equals("version")) {
				List<String> loaders = params.containsKey("loaders") ? Json.strings(Json.parse(params.get("loaders"))) : List.of();
				List<String> games = params.containsKey("game_versions") ? Json.strings(Json.parse(params.get("game_versions"))) : List.of();
				return Response.json(catalog.matching(project, loaders, games).stream().map(catalog::versionJson).toList());
			}
		}
		// /v2/version_file/{hash}
		if (parts.length == 4 && parts[2].equals("version_file")) {
			Catalog.Version version = catalog.byHash(params.getOrDefault("algorithm", "sha1"), parts[3]);
			return version == null ? Response.notFound("hash " + parts[3]) : Response.json(catalog.versionJson(version));
		}
		return Response.notFound(path);
	}

	private static String algorithm(Map<String, Object> request) {
		Object algorithm = request.get("algorithm");
		return algorithm == null ? "sha1" : algorithm.toString();
	}

	// /data/{projectId}/versions/{versionId}/{filename}; getPath() has already decoded %2B to '+'.
	private Response cdn(String path) throws IOException {
		String[] parts = path.split("/");
		if (parts.length == 6 && parts[3].equals("versions")) {
			for (Catalog.Version version : catalog.versions) {
				if (version.project().id().equals(parts[2]) && version.id().equals(parts[4]) && version.filename().equals(parts[5])) {
					return new Response(200, "application/java-archive", Files.readAllBytes(version.file()));
				}
			}
		}
		return Response.notFound(path);
	}

	private static Map<String, String> params(String rawQuery) {
		Map<String, String> out = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return out;
		}
		for (String pair : rawQuery.split("&")) {
			String[] kv = pair.split("=", 2);
			out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
		}
		return out;
	}

	private synchronized void log(String method, String host, String path, String query, int status, String userAgent, int bytes) {
		if (requestLog == null) {
			return;
		}
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("time", Instant.now().toString());
		line.put("method", method);
		line.put("host", host);
		line.put("path", path);
		line.put("query", query);
		line.put("status", status);
		line.put("bytes", bytes);
		line.put("userAgent", userAgent);
		try {
			Files.writeString(requestLog, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// The projects, their versions (with hashes of the real files) and the static files, from the catalog JSON.
	private static final class Catalog {
		record Project(String id, String slug, String title) {
		}

		record Version(Project project, String id, String versionNumber, String versionType, String datePublished,
				List<String> gameVersions, List<String> loaders, Path file, String filename, String sha1, String sha512, long size,
				List<Object> dependencies) {
		}

		record StaticFile(String host, String path, Path file, String contentType) {
		}

		final String apiHost;
		final String cdnHost;
		final String cdnBase;
		final List<Project> projects = new ArrayList<>();
		final List<Version> versions = new ArrayList<>();
		final List<StaticFile> statics = new ArrayList<>();

		private Catalog(String apiHost, String cdnHost, String cdnBase) {
			this.apiHost = apiHost;
			this.cdnHost = cdnHost;
			this.cdnBase = cdnBase;
		}

		static Catalog load(Path file, String selfOrigin) throws IOException {
			Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
			Map<String, Object> hosts = root.get("hosts") == null ? Map.of() : Json.object(root.get("hosts"));
			String cdnBase = root.get("cdnBase") == null ? selfOrigin : root.get("cdnBase").toString();
			Catalog catalog = new Catalog((String) hosts.get("api"), (String) hosts.get("cdn"),
					cdnBase.endsWith("/") ? cdnBase.substring(0, cdnBase.length() - 1) : cdnBase);
			for (Object p : Json.list(root.get("projects"))) {
				Map<String, Object> projectJson = Json.object(p);
				Project project = new Project((String) projectJson.get("id"), (String) projectJson.get("slug"), (String) projectJson.get("title"));
				catalog.projects.add(project);
				for (Object v : Json.list(projectJson.get("versions"))) {
					Map<String, Object> versionJson = Json.object(v);
					Path jar = Path.of((String) versionJson.get("file"));
					byte[] bytes = Files.readAllBytes(jar);
					catalog.versions.add(new Version(project, (String) versionJson.get("id"), (String) versionJson.get("version_number"),
							Objects.toString(versionJson.get("version_type"), "release"), (String) versionJson.get("date_published"),
							Json.strings(versionJson.get("game_versions")), Json.strings(versionJson.get("loaders")), jar,
							Objects.toString(versionJson.get("filename"), jar.getFileName().toString()),
							digest("SHA-1", bytes), digest("SHA-512", bytes), bytes.length,
							versionJson.get("dependencies") == null ? List.of() : Json.list(versionJson.get("dependencies"))));
				}
			}
			if (root.get("static") != null) {
				for (Object s : Json.list(root.get("static"))) {
					Map<String, Object> staticJson = Json.object(s);
					catalog.statics.add(new StaticFile((String) staticJson.get("host"), (String) staticJson.get("path"),
							Path.of((String) staticJson.get("file")), Objects.toString(staticJson.get("contentType"), "text/plain; charset=utf-8")));
				}
			}
			return catalog;
		}

		Project project(String ref) {
			return projects.stream().filter(p -> p.id().equals(ref) || p.slug().equalsIgnoreCase(ref)).findFirst().orElse(null);
		}

		Version byHash(String algorithm, String hash) {
			for (Version version : versions) {
				String mine = "sha512".equalsIgnoreCase(algorithm) ? version.sha512() : version.sha1();
				if (mine.equalsIgnoreCase(hash)) {
					return version;
				}
			}
			return null;
		}

		// Newest first; an empty filter matches everything, as on Modrinth.
		List<Version> matching(Project project, List<String> loaders, List<String> gameVersions) {
			return versions.stream()
					.filter(v -> v.project().equals(project))
					.filter(v -> loaders.isEmpty() || v.loaders().stream().anyMatch(loaders::contains))
					.filter(v -> gameVersions.isEmpty() || v.gameVersions().stream().anyMatch(gameVersions::contains))
					.sorted(Comparator.comparing((Version v) -> Instant.parse(v.datePublished())).reversed())
					.toList();
		}

		Map<String, Object> versionJson(Version v) {
			Map<String, Object> hashes = new LinkedHashMap<>();
			hashes.put("sha1", v.sha1());
			hashes.put("sha512", v.sha512());
			Map<String, Object> file = new LinkedHashMap<>();
			file.put("hashes", hashes);
			file.put("url", cdnBase + "/data/" + v.project().id() + "/versions/" + v.id() + "/"
					+ URLEncoder.encode(v.filename(), StandardCharsets.UTF_8).replace("+", "%20"));
			file.put("filename", v.filename());
			file.put("primary", true);
			file.put("size", v.size());
			file.put("file_type", null);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("id", v.id());
			out.put("project_id", v.project().id());
			out.put("author_id", "e2eAuthr");
			out.put("featured", false);
			out.put("name", v.versionNumber());
			out.put("version_number", v.versionNumber());
			out.put("changelog", "");
			out.put("date_published", v.datePublished());
			out.put("downloads", 0);
			out.put("version_type", v.versionType());
			out.put("status", "listed");
			out.put("files", List.of(file));
			out.put("dependencies", v.dependencies());
			out.put("game_versions", v.gameVersions());
			out.put("loaders", v.loaders());
			return out;
		}

		Map<String, Object> projectJson(Project project) {
			List<Version> own = versions.stream().filter(v -> v.project().equals(project)).toList();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("id", project.id());
			out.put("slug", project.slug());
			out.put("project_type", "mod");
			out.put("title", project.title());
			out.put("status", "approved");
			out.put("client_side", "required");
			out.put("server_side", "unsupported");
			out.put("game_versions", union(own.stream().map(Version::gameVersions).toList()));
			out.put("loaders", union(own.stream().map(Version::loaders).toList()));
			out.put("versions", own.stream().map(Version::id).toList());
			return out;
		}

		private static List<String> union(List<List<String>> lists) {
			LinkedHashSet<String> out = new LinkedHashSet<>();
			lists.forEach(out::addAll);
			return List.copyOf(out);
		}

		private static String digest(String algorithm, byte[] bytes) {
			try {
				return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	// Just enough JSON for the catalog and the request bodies: objects, arrays, strings, numbers, booleans, null.
	static final class Json {
		private final String text;
		private int at;

		private Json(String text) {
			this.text = text;
		}

		static Object parse(String text) {
			Json json = new Json(text);
			Object value = json.value();
			json.space();
			if (json.at != text.length()) {
				throw new IllegalArgumentException("Trailing characters at " + json.at);
			}
			return value;
		}

		@SuppressWarnings("unchecked")
		static Map<String, Object> object(Object value) {
			Object parsed = value instanceof String s ? parse(s) : value;
			if (!(parsed instanceof Map)) {
				throw new IllegalArgumentException("Expected a JSON object");
			}
			return (Map<String, Object>) parsed;
		}

		@SuppressWarnings("unchecked")
		static List<Object> list(Object value) {
			if (!(value instanceof List)) {
				throw new IllegalArgumentException("Expected a JSON array");
			}
			return (List<Object>) value;
		}

		static List<String> strings(Object value) {
			return value == null ? List.of() : list(value).stream().map(String::valueOf).toList();
		}

		private Object value() {
			space();
			if (at >= text.length()) {
				throw new IllegalArgumentException("Unexpected end of JSON");
			}
			char c = text.charAt(at);
			switch (c) {
				case '{' -> {
					at++;
					Map<String, Object> out = new LinkedHashMap<>();
					space();
					if (peek('}')) {
						return out;
					}
					do {
						space();
						String key = string();
						space();
						expect(':');
						out.put(key, value());
						space();
					} while (peek(','));
					expect('}');
					return out;
				}
				case '[' -> {
					at++;
					List<Object> out = new ArrayList<>();
					space();
					if (peek(']')) {
						return out;
					}
					do {
						out.add(value());
						space();
					} while (peek(','));
					expect(']');
					return out;
				}
				case '"' -> {
					return string();
				}
				default -> {
					for (String word : List.of("true", "false", "null")) {
						if (text.startsWith(word, at)) {
							at += word.length();
							return word.equals("null") ? null : Boolean.valueOf(word);
						}
					}
					int start = at;
					while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
						at++;
					}
					String number = text.substring(start, at);
					if (number.isEmpty()) {
						throw new IllegalArgumentException("Unexpected '" + c + "' at " + start);
					}
					return number.matches("-?\\d+") ? (Object) Long.valueOf(number) : (Object) Double.valueOf(number);
				}
			}
		}

		private String string() {
			expect('"');
			StringBuilder out = new StringBuilder();
			while (true) {
				if (at >= text.length()) {
					throw new IllegalArgumentException("Unterminated string");
				}
				char c = text.charAt(at++);
				if (c == '"') {
					return out.toString();
				}
				if (c != '\\') {
					out.append(c);
					continue;
				}
				char e = text.charAt(at++);
				switch (e) {
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'u' -> {
						out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
						at += 4;
					}
					default -> out.append(e);
				}
			}
		}

		private void space() {
			while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
				at++;
			}
		}

		private boolean peek(char c) {
			if (at < text.length() && text.charAt(at) == c) {
				at++;
				return true;
			}
			return false;
		}

		private void expect(char c) {
			if (!peek(c)) {
				throw new IllegalArgumentException("Expected '" + c + "' at " + at);
			}
		}

		static String write(Object value) {
			StringBuilder out = new StringBuilder();
			write(out, value);
			return out.toString();
		}

		private static void write(StringBuilder out, Object value) {
			switch (value) {
				case null -> out.append("null");
				case String s -> quote(out, s);
				case Number n -> out.append(n);
				case Boolean b -> out.append(b);
				case Map<?, ?> map -> {
					out.append('{');
					boolean first = true;
					for (Map.Entry<?, ?> entry : map.entrySet()) {
						if (!first) {
							out.append(',');
						}
						first = false;
						quote(out, String.valueOf(entry.getKey()));
						out.append(':');
						write(out, entry.getValue());
					}
					out.append('}');
				}
				case Collection<?> list -> {
					out.append('[');
					boolean first = true;
					for (Object item : list) {
						if (!first) {
							out.append(',');
						}
						first = false;
						write(out, item);
					}
					out.append(']');
				}
				default -> quote(out, value.toString());
			}
		}

		private static void quote(StringBuilder out, String s) {
			out.append('"');
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				switch (c) {
					case '"' -> out.append("\\\"");
					case '\\' -> out.append("\\\\");
					case '\n' -> out.append("\\n");
					case '\r' -> out.append("\\r");
					case '\t' -> out.append("\\t");
					default -> {
						if (c < 0x20) {
							out.append(String.format("\\u%04x", (int) c));
						} else {
							out.append(c);
						}
					}
				}
			}
			out.append('"');
		}
	}
}
