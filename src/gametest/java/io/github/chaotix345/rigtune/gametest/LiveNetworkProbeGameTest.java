package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

// PROOF (ws-ci; reverted in the next commit): a game test that calls the live internet must fail under build.yml's
// no-network step.
public class LiveNetworkProbeGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
		for (String url : List.of("https://api.modrinth.com/v2/project/sodium", "https://example.com/")) {
			try {
				HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
						HttpResponse.BodyHandlers.ofString());
				RigTune.LOGGER.info("LiveNetworkProbeGameTest: {} answered HTTP {}", url, response.statusCode());
			} catch (IOException | InterruptedException e) {
				throw new AssertionError("PROOF (ws-ci): a live call from a game test failed: " + url + ": " + e, e);
			}
		}
	}
}
