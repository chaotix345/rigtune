package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.RigTune;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public final class RemoteRulesFetcher {
	public static final URI DEFAULT_URI = URI.create("https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v1.json");

	private final HttpClient client;
	private final URI uri;
	private final String userAgent;
	private final Path cacheFile;

	public RemoteRulesFetcher(String modVersion, Path cacheFile) {
		this(DEFAULT_URI, modVersion, cacheFile);
	}

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
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				RigTune.LOGGER.warn("Remote rules request returned HTTP {}", response.statusCode());
				return Optional.empty();
			}
			RulesDocument doc = RulesLoader.parse(response.body());
			doc.setSource(RulesLoader.SOURCE_REMOTE);
			if (cacheFile != null) {
				try {
					RulesLoader.saveCache(cacheFile, response.body());
				} catch (Exception e) {
					RigTune.LOGGER.warn("Could not write rules cache {}: {}", cacheFile, e.toString());
				}
			}
			return Optional.of(doc);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not fetch remote rules from {}: {}", uri, e.toString());
			return Optional.empty();
		}
	}
}
