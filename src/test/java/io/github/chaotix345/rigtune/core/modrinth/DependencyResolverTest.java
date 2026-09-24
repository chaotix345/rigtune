package io.github.chaotix345.rigtune.core.modrinth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.required;
import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyResolverTest {
	private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

	private static List<String> ids(List<ModrinthVersion> versions) {
		return versions.stream().map(ModrinthVersion::id).toList();
	}

	private static void put(FakeModrinthClient client, String slug, ModrinthVersion v) {
		client.latestByProject.put(slug, v);
		client.latestByProject.put(v.projectId(), v);
	}

	@Test
	void resolvesRequiredDependenciesAndSkipsOthers() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "iris", version("irisV", "IRIS", "1", T, required("SODIUM"), required("API"),
				new Dependency("OPT", null, "optional"), new Dependency("BAD", null, "incompatible")));
		put(client, "sodium", version("sodV", "SODIUM", "1", T));
		put(client, "api", version("apiV", "API", "1", T, required("LIB")));
		put(client, "lib", version("libV", "LIB", "1", T));
		put(client, "opt", version("optV", "OPT", "1", T));

		List<ModrinthVersion> plan = new DependencyResolver(client, "fabric", "26.2").resolve("iris", Set.of("SODIUM"));

		assertEquals(List.of("irisV", "apiV", "libV"), ids(plan));
	}

	@Test
	void isCycleSafe() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, required("B")));
		put(client, "b", version("bV", "B", "1", T, required("A"), required("B")));

		List<ModrinthVersion> plan = new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of());

		assertEquals(List.of("aV", "bV"), ids(plan));
	}

	@Test
	void respectsDepthLimit() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, required("B")));
		put(client, "b", version("bV", "B", "1", T, required("C")));
		put(client, "c", version("cV", "C", "1", T));

		assertEquals(List.of("aV", "bV"), ids(new DependencyResolver(client, "fabric", "26.2", 1).resolve("a", Set.of())));
	}

	@Test
	void alreadyInstalledRootNeedsNothing() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T));
		assertTrue(new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of("A")).isEmpty());
	}

	@Test
	void failsWhenRequiredDependencyIsUnavailable() {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, required("GONE")));
		assertThrows(IOException.class, () -> new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of()));
		assertThrows(IOException.class, () -> new DependencyResolver(client, "fabric", "26.2").resolve("nope", Set.of()));
	}
}
