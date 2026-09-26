package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.ModFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class GatedModrinthClientTest {
	// Stands for the real network: any call while the switch is off is a privacy bug.
	private static final class ForbiddenClient implements ModrinthClient {
		@Override
		public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) {
			return fail("versionsByHashes called");
		}

		@Override
		public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) {
			return fail("latestVersionsByHashes called");
		}

		@Override
		public List<ModrinthProject> projects(Collection<String> idsOrSlugs) {
			return fail("projects called");
		}

		@Override
		public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) {
			return fail("latestVersion called");
		}

		@Override
		public Map<String, ModrinthVersion> versions(Collection<String> ids) {
			return fail("versions called");
		}

		@Override
		public void download(ModFile file, Path target) {
			fail("download called");
		}
	}

	private static final ModFile FILE = new ModFile("https://cdn.modrinth.com/x.jar", "x.jar", "abc", 10);

	@Test
	void everyMethodIsBlockedWhileOff(@TempDir Path dir) {
		GatedModrinthClient gated = new GatedModrinthClient(new ForbiddenClient(), () -> false);

		assertThrows(ModrinthException.class, () -> gated.versionsByHashes(List.of("aa")));
		assertThrows(ModrinthException.class, () -> gated.latestVersionsByHashes(List.of("aa"), "fabric", "26.2"));
		assertThrows(ModrinthException.class, () -> gated.projects(List.of("lithium")));
		assertThrows(ModrinthException.class, () -> gated.latestVersion("lithium", "fabric", "26.2"));
		assertThrows(ModrinthException.class, () -> gated.versions(List.of("ZouiUX7t")));
		assertThrows(ModrinthException.class, () -> gated.download(FILE, dir.resolve("x.jar")));
		assertFalse(Files.exists(dir.resolve("x.jar")));
	}

	@Test
	void onlineLookupsFallBackToOfflineWithoutARequest() {
		GatedModrinthClient gated = new GatedModrinthClient(new ForbiddenClient(), () -> false);

		OnlineDataFetcher.Result result = new OnlineDataFetcher(gated).fetchAll(Fixtures.mods("sodium", "lithium"), List.of("ferrite-core"), "26.2");

		assertFalse(result.data().online());
		assertEquals(Map.of(), result.data().updatesByModId());
		assertEquals(Map.of(), result.projectIdsByModId());
	}

	@Test
	void downloadsFailWithoutARequest() {
		GatedModrinthClient gated = new GatedModrinthClient(new ForbiddenClient(), () -> false);

		assertThrows(IOException.class, () -> new DependencyResolver(gated, "fabric", "26.2").resolve("lithium", Set.of()));
	}

	@Test
	void callsGoThroughWhileOnAndTheSwitchIsReadEveryCall() throws IOException {
		AtomicBoolean allowed = new AtomicBoolean(true);
		FakeModrinthClient fake = new FakeModrinthClient();
		GatedModrinthClient gated = new GatedModrinthClient(fake, allowed::get);

		gated.projects(List.of("lithium"));
		assertEquals(1, fake.calls.size());

		allowed.set(false);
		assertThrows(ModrinthException.class, () -> gated.projects(List.of("lithium")));
		assertEquals(1, fake.calls.size());

		allowed.set(true);
		gated.latestVersion("lithium", "fabric", "26.2");
		assertEquals(2, fake.calls.size());
	}
}
