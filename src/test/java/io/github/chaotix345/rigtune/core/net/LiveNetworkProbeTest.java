package io.github.chaotix345.rigtune.core.net;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

// PROOF (ws-ci; reverted in the next commit): a unit test that calls the live internet must fail under build.yml's
// no-network step.
class LiveNetworkProbeTest {
	@Test
	void liveModrinthIsReachable() throws Exception {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/sodium"))
				.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
	}
}
