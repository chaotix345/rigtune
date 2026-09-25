package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.net.BoundedHttp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public final class RemoteRulesFetcher {
	private static final Duration STALL = Duration.ofSeconds(15);
	private static final Duration DEADLINE = Duration.ofSeconds(30);
	private static final long ERROR_BODY_BYTES = 64 * 1024;

	private final HttpClient client;
	private final URI uri;
	private final String userAgent;
	private final Path cacheFile;

	// cacheFile (may be null) receives the downloaded text, but only for a document of this client's schemaVersion.
	public RemoteRulesFetcher(URI uri, String modVersion, Path cacheFile) {
		this.client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		this.uri = uri;
		this.userAgent = userAgent(modVersion);
		this.cacheFile = cacheFile;
	}

	public static String userAgent(String modVersion) {
		return "chaotix345/rigtune/" + modVersion + " (github.com/chaotix345/rigtune)";
	}

	public Optional<RulesDocument> fetch() {
		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", userAgent)
					.GET()
					.build();
			BoundedHttp.Progress progress = new BoundedHttp.Progress();
			HttpResponse<byte[]> response = BoundedHttp.send(client, request, info -> info.statusCode() == 200
					? BoundedHttp.bytes(RulesLoader.MAX_RULES_BYTES, false, progress)
					: BoundedHttp.bytes(ERROR_BODY_BYTES, true, progress), progress, STALL, DEADLINE);
			if (response.statusCode() != 200) {
				RigTune.LOGGER.warn("Remote rules request returned HTTP {}", response.statusCode());
				return Optional.empty();
			}
			String body = new String(response.body(), StandardCharsets.UTF_8);
			RulesDocument doc = RulesLoader.parse(body);
			doc.setSource(RulesLoader.SOURCE_REMOTE);
			if (cacheFile != null && doc.schemaVersion == RulesLoader.SCHEMA_VERSION) {
				try {
					RulesLoader.saveCache(cacheFile, body);
				} catch (Exception e) {
					RigTune.LOGGER.warn("Could not write rules cache {}: {}", cacheFile, e.toString());
				}
			}
			return Optional.of(doc);
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not fetch remote rules from {}: {}", uri, e.toString());
			return Optional.empty();
		}
	}
}
