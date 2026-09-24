package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineDataFetcherTest {
	private static final Instant OLD = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant NEW = Instant.parse("2026-09-01T00:00:00Z");

	private static InstalledMod mod(String id, String sha1) {
		return new InstalledMod(id, id, "1.0", sha1 == null ? null : Path.of("mods", id + ".jar"), sha1);
	}

	@Test
	void findsUpdatesProjectIdsAndAvailability() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.current.put("aaa", version("sod1", "AANobbMI", "0.9.1", OLD));
		client.latest.put("aaa", version("sod2", "AANobbMI", "0.9.2", NEW));
		client.current.put("bbb", version("lit1", "gvQqBUqZ", "0.15", NEW));
		client.latest.put("bbb", version("lit1", "gvQqBUqZ", "0.15", NEW));
		client.projects.add(new ModrinthProject("P1", "ferrite-core", "FerriteCore", "approved", List.of("26.1", "26.2"), List.of("fabric"), "optional"));
		client.projects.add(new ModrinthProject("P2", "old-mod", "Old", "approved", List.of("1.20.1"), List.of("fabric"), "optional"));
		client.projects.add(new ModrinthProject("P3", "forge-only", "Forge", "approved", List.of("26.2"), List.of("neoforge"), "optional"));

		OnlineDataFetcher.Result result = new OnlineDataFetcher(client).fetchAll(
				List.of(mod("sodium", "aaa"), mod("lithium", "bbb"), mod("builtin", null)),
				List.of("ferrite-core", "old-mod", "forge-only", "missing"),
				"26.2");

		OnlineData data = result.data();
		assertTrue(data.online());
		assertEquals(1, data.updatesByModId().size());
		UpdateInfo update = data.updatesByModId().get("sodium");
		assertNotNull(update);
		assertEquals("AANobbMI", update.projectId());
		assertEquals("0.9.1", update.currentVersion());
		assertEquals("sod2", update.newVersionId());
		assertEquals("0.9.2", update.newVersionNumber());
		assertEquals("sha512-sod2", update.file().sha512());

		assertEquals(true, data.availableBySlug().get("ferrite-core"));
		assertEquals(false, data.availableBySlug().get("old-mod"));
		assertEquals(false, data.availableBySlug().get("forge-only"));
		assertFalse(data.availableBySlug().containsKey("missing"));

		assertEquals("AANobbMI", result.projectIdsByModId().get("sodium"));
		assertEquals("gvQqBUqZ", result.projectIdsByModId().get("lithium"));
		assertEquals(List.of("versionsByHashes", "latestVersionsByHashes", "projects"), client.calls);
	}

	@Test
	void ignoresLatestThatIsOlderThanInstalled() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.current.put("aaa", version("beta", "X", "2.0-beta", NEW));
		client.latest.put("aaa", version("rel", "X", "1.9", OLD));

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(mod("x", "aaa")), List.of(), "26.2");

		assertTrue(data.online());
		assertTrue(data.updatesByModId().isEmpty());
	}

	@Test
	void releaseInstallIsNotOfferedAnAlphaOrBetaUpdate() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.current.put("aaa", version("rel1", "X", "1.0", "release", OLD));
		client.latest.put("aaa", version("alpha1", "X", "2.0-alpha", "alpha", NEW));

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(mod("x", "aaa")), List.of(), "26.2");
		assertTrue(data.updatesByModId().isEmpty());

		client.latest.put("aaa", version("beta1", "X", "2.0-beta", "beta", NEW));
		data = new OnlineDataFetcher(client).fetch(List.of(mod("x", "aaa")), List.of(), "26.2");
		assertTrue(data.updatesByModId().isEmpty());

		client.latest.put("aaa", version("rel2", "X", "2.0", "release", NEW));
		data = new OnlineDataFetcher(client).fetch(List.of(mod("x", "aaa")), List.of(), "26.2");
		assertEquals("rel2", data.updatesByModId().get("x").newVersionId());
	}

	@Test
	void alphaInstallCanBeOfferedBetaOrReleaseUpdate() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.current.put("aaa", version("alpha1", "X", "1.0-alpha", "alpha", OLD));
		client.latest.put("aaa", version("beta1", "X", "2.0-beta", "beta", NEW));

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(mod("x", "aaa")), List.of(), "26.2");
		assertEquals("beta1", data.updatesByModId().get("x").newVersionId());

		client.latest.put("aaa", version("rel1", "X", "2.0", "release", NEW));
		data = new OnlineDataFetcher(client).fetch(List.of(mod("x", "aaa")), List.of(), "26.2");
		assertEquals("rel1", data.updatesByModId().get("x").newVersionId());
	}

	@Test
	void skipsCallsWithNothingToAsk() {
		FakeModrinthClient client = new FakeModrinthClient();
		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(mod("builtin", null)), List.of(), "26.2");
		assertTrue(data.online());
		assertTrue(client.calls.isEmpty());
	}

	@Test
	void returnsOfflineOnFailure() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.failWith = new ModrinthException(429, "slow down");
		OnlineDataFetcher.Result result = new OnlineDataFetcher(client).fetchAll(List.of(mod("sodium", "aaa")), List.of("lithium"), "26.2");
		assertFalse(result.data().online());
		assertTrue(result.projectIdsByModId().isEmpty());

		client.failWith = new IOException("no network");
		assertFalse(new OnlineDataFetcher(client).fetch(List.of(), List.of("lithium"), "26.2").online());
	}
}
