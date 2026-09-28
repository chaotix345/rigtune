package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Review-11 SEC-3: rules-v1.json carries the old-client warning unconditionally (0.1.x can only be below 0.5), and 0.5
// still loads v1 documents: (A) remote rules-v1.json when rules-v2.json fails, once main's revision is above the
// bundled one; (B) 0.1.x's rules-cache.json, a local candidate on every launch (also with remote rules off). A 0.5 client
// must never show it, whichever document it loaded.
class OldClientWarningV1PathsTest {
	private static final int BUNDLED = RulesLoader.loadBundled().revision;
	private static final String ADVICE = "advice:" + OldClientWarningTest.ID;

	private HttpServer server;
	private final Map<String, Integer> statuses = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, String> bodies = new java.util.concurrent.ConcurrentHashMap<>();
	private final List<RulesDocument> published = new ArrayList<>();

	@TempDir
	Path config;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/rules/", exchange -> {
			String file = exchange.getRequestURI().getPath().substring("/rules/".length());
			byte[] bytes = bodies.getOrDefault(file, "not found").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(statuses.getOrDefault(file, 404), bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	// The repository's rules-v1.json as main would serve it one revision after the bundled rules.
	static String rulesV1AboveTheBundledRevision() throws IOException {
		JsonObject v1 = JsonParser.parseString(Files.readString(RepoFiles.resolve("rules/rules-v1.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		v1.addProperty("revision", BUNDLED + 1);
		return v1.toString();
	}

	private void load(boolean remoteAllowed) {
		URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rules/");
		new RulesSources(config, base, "0.5.0-test").load(() -> remoteAllowed, (rules, remote) -> published.add(rules));
	}

	static List<String> mainList(RulesDocument rules, String rigtuneVersion) {
		List<InstalledMod> mods = List.of(new InstalledMod("rigtune", "RigTune", rigtuneVersion, Path.of("mods", "rigtune.jar"), "0000rigtune"),
				new InstalledMod("sodium", "Sodium", "0.9.2+mc26.2", Path.of("mods", "sodium.jar"), "0000sodium"));
		return Recommender.recommend(rules, Fixtures.userRig().build(), mods, new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED)
				.recommendations().stream().map(r -> r.id()).toList();
	}

	// Not vacuous: the served v1 document does carry the warning, unconditionally, for 0.1.x.
	@Test
	void rulesV1CarriesTheWarningFor01x() throws IOException {
		JsonObject v1 = JsonParser.parseString(rulesV1AboveTheBundledRevision()).getAsJsonObject();
		JsonObject rule = OldClientWarningTest.advice(v1.toString(), OldClientWarningTest.ID);
		assertEquals(JsonParser.parseString("{\"always\": true}"), rule.get("when"));
	}

	// (A) rules-v2.json fails, rules-v1.json at a newer revision wins: the 0.5 main list has no warning.
	@Test
	void aRemoteV1FallbackNeverShowsIt() throws IOException {
		statuses.put(RulesSources.V2_FILE, 503);
		statuses.put(RulesSources.V1_FILE, 200);
		bodies.put(RulesSources.V1_FILE, rulesV1AboveTheBundledRevision());
		load(true);
		RulesDocument used = published.getLast();
		assertEquals(1, used.schemaVersion);
		assertEquals(BUNDLED + 1, used.revision);
		for (String version : List.of("0.5.0+mc26.2", "0.5.0-dev", "0.5.0-rc.1+mc26.3")) {
			assertFalse(mainList(used, version).contains(ADVICE), version);
		}
	}

	// (B) a 0.1.x rules-cache.json at a newer revision, remote rules off: chosen at every launch, and still no warning.
	@Test
	void aLeftover01CacheNeverShowsIt() throws IOException {
		Path dir = config.resolve("rigtune");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(RulesSources.LEGACY_CACHE), rulesV1AboveTheBundledRevision());
		load(false);
		assertEquals(1, published.size());
		RulesDocument used = published.getFirst();
		assertEquals(1, used.schemaVersion);
		assertEquals(BUNDLED + 1, used.revision);
		assertFalse(mainList(used, "0.5.0+mc26.2").contains(ADVICE));
	}

	// Whatever the document: parsed by 0.5, it has no such advice (v2 and v1, bundled and repository copies).
	@Test
	void everyDocumentThisClientParsesDropsIt() throws IOException {
		for (String json : List.of(LegacyRulesParseTest.bundledJson(), Files.readString(RepoFiles.resolve("rules/rules-v1.json"), StandardCharsets.UTF_8))) {
			assertTrue(RulesLoader.parse(json).advice.stream().noneMatch(a -> OldClientWarningTest.ID.equals(a.id)));
		}
	}
}
