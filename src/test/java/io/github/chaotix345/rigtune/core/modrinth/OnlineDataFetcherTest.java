package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
		client.latestByProject.put("P1", version("fc1", "P1", "7.0", NEW));

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
		assertEquals(Map.of("sodium", "sod1", "lithium", "lit1"), result.versionIdsByModId());
		assertEquals(List.of("versionsByHashes", "latestVersionsByHashes", "projects", "latestVersion:P1"), client.calls);
	}

	// Plan review A-H1/A-M2: the resolver judges updates by their own dependencies and names installed versions by
	// their project, so the result keeps the Modrinth versions (dependencies included); UpdateInfo stays as it was.
	@Test
	void keepsTheInstalledAndUpdateVersionsWithTheirDependencies() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.current.put("aaa", version("sod1", "AANobbMI", "0.9.1", OLD, FakeModrinthClient.incompatible("K")));
		client.latest.put("aaa", version("sod2", "AANobbMI", "0.9.2", NEW, new Dependency(null, "k1", "incompatible")));
		client.current.put("bbb", version("lit1", "gvQqBUqZ", "0.15", NEW));
		client.latest.put("bbb", version("lit1", "gvQqBUqZ", "0.15", NEW));

		OnlineDataFetcher.Result result = new OnlineDataFetcher(client).fetchAll(List.of(mod("sodium", "aaa"), mod("lithium", "bbb")), List.of(), "26.2");

		assertEquals(List.of(FakeModrinthClient.incompatible("K")), result.installedVersions().get("sod1").dependencies());
		assertEquals(List.of(new Dependency(null, "k1", "incompatible")), result.updateVersions().get("sod2").dependencies());
		assertEquals(java.util.Set.of("sod2"), result.updateVersions().keySet());
		assertEquals(Map.of("sod1", "AANobbMI", "lit1", "gvQqBUqZ"), result.projectIdsByVersionId());
		assertEquals("sod2", result.data().updatesByModId().get("sodium").newVersionId());
		assertTrue(OnlineDataFetcher.Result.offline().installedVersions().isEmpty());
		assertTrue(OnlineDataFetcher.Result.offline().updateVersions().isEmpty());
	}

	private static ModrinthProject project(String id, String slug, List<String> gameVersions, List<String> loaders) {
		return new ModrinthProject(id, slug, slug, "approved", gameVersions, loaders, "optional");
	}

	@Test
	void projectLevelUnionsAloneNeverSayAvailable() {
		FakeModrinthClient client = new FakeModrinthClient();
		// Fabric only up to 26.1 and NeoForge for 26.2: the project lists both, but no Fabric 26.2 version exists.
		client.projects.add(project("P1", "split", List.of("26.1", "26.2"), List.of("fabric", "neoforge")));
		client.projects.add(project("P2", "real", List.of("26.2"), List.of("fabric")));
		client.latestByProject.put("P2", version("r1", "P2", "1.0", NEW));

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(), List.of("split", "real"), "26.2");

		assertEquals(false, data.availableBySlug().get("split"));
		assertEquals(true, data.availableBySlug().get("real"));
		assertTrue(client.calls.containsAll(List.of("latestVersion:P1", "latestVersion:P2")));
	}

	@Test
	void projectLevelMissNeedsNoVersionCheck() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.projects.add(project("P1", "old", List.of("1.20.1"), List.of("fabric")));
		client.projects.add(project("P2", "forge", List.of("26.2"), List.of("neoforge")));

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(), List.of("old", "forge", "unknown"), "26.2");

		assertEquals(false, data.availableBySlug().get("old"));
		assertEquals(false, data.availableBySlug().get("forge"));
		assertFalse(data.availableBySlug().containsKey("unknown"));
		assertEquals(List.of("projects"), client.calls);
	}

	@Test
	void versionChecksRunAtMostFourAtATime() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.versionDelayMillis = 100;
		List<String> slugs = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			slugs.add("mod" + i);
			client.projects.add(project("P" + i, "mod" + i, List.of("26.2"), List.of("fabric")));
			client.latestByProject.put("P" + i, version("v" + i, "P" + i, "1", NEW));
		}

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(), slugs, "26.2");

		assertEquals(12, data.availableBySlug().size());
		assertTrue(data.availableBySlug().values().stream().allMatch(Boolean::booleanValue));
		assertTrue(client.maxInFlight.get() <= OnlineDataFetcher.VERSION_CHECK_PARALLELISM, "max in flight " + client.maxInFlight.get());
		assertTrue(client.maxInFlight.get() > 1, "checks run in parallel");
	}

	@Test
	void aFailedVersionCheckLeavesThatCandidateUnknown() {
		FakeModrinthClient client = new FakeModrinthClient();
		client.projects.add(project("P1", "a", List.of("26.2"), List.of("fabric")));
		client.projects.add(project("P2", "b", List.of("26.2"), List.of("fabric")));
		client.latestByProject.put("P1", version("a1", "P1", "1", NEW));
		client.versionFailures.put("P2", new IOException("connection reset"));

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(), List.of("a", "b"), "26.2");

		assertTrue(data.online());
		assertEquals(true, data.availableBySlug().get("a"));
		assertFalse(data.availableBySlug().containsKey("b"));
	}

	@Test
	void rateLimitStopsTheRemainingVersionChecks() {
		FakeModrinthClient client = new FakeModrinthClient();
		List<String> slugs = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			slugs.add("mod" + i);
			client.projects.add(project("P" + i, "mod" + i, List.of("26.2"), List.of("fabric")));
			client.versionFailures.put("P" + i, new ModrinthException(429, "slow down"));
		}

		OnlineData data = new OnlineDataFetcher(client).fetch(List.of(), slugs, "26.2");

		assertTrue(data.online());
		assertTrue(data.availableBySlug().isEmpty());
		long versionCalls = client.calls.stream().filter(c -> c.startsWith("latestVersion:")).count();
		assertTrue(versionCalls <= OnlineDataFetcher.VERSION_CHECK_PARALLELISM, versionCalls + " calls");
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
