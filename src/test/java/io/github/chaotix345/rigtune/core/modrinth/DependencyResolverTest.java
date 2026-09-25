package io.github.chaotix345.rigtune.core.modrinth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.incompatible;
import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.required;
import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.version;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	// Review 4, rules-accuracy-2: a Modrinth "incompatible" dependency against an installed project, or between anything
	// going in together (this mod, its dependencies and the versions earlier in the batch), refuses the resolution.
	@Test
	void refusesAModrinthIncompatibilityWithAnInstalledProject() {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, required("LIB")));
		put(client, "lib", version("libV", "LIB", "1", T, incompatible("KRYPTON")));

		IOException refused = assertThrows(IOException.class, () -> new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of("KRYPTON")));

		assertEquals("Modrinth marks LIB as incompatible with KRYPTON, which is installed", refused.getMessage());
	}

	@Test
	void refusesAModrinthIncompatibilityWithinWhatGoesInTogether() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		ModrinthVersion a = version("aV", "A", "1", T, incompatible("B"));
		ModrinthVersion b = version("bV", "B", "1", T);
		ModrinthVersion c = version("cV", "C", "1", T);
		put(client, "a", a);
		put(client, "b", b);
		put(client, "c", c);
		put(client, "d", version("dV", "D", "1", T, required("E")));
		put(client, "e", version("eV", "E", "1", T, incompatible("D")));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2");

		assertEquals("Modrinth marks A and B as incompatible, and both would be installed",
				assertThrows(IOException.class, () -> resolver.resolve("a", Set.of(), List.of(c, b))).getMessage());
		assertEquals("Modrinth marks A and B as incompatible, and both would be installed",
				assertThrows(IOException.class, () -> resolver.resolve("b", Set.of(), List.of(a))).getMessage());
		assertEquals("Modrinth marks E and D as incompatible, and both would be installed",
				assertThrows(IOException.class, () -> resolver.resolve("d", Set.of(), List.of())).getMessage());
		assertEquals(List.of("aV"), ids(resolver.resolve("a", Set.of(), List.of(c))));
	}

	// Re-check of review 4: an incompatibility naming a version (version_id) is with that version only, not with any
	// install of its project; the message names the projects by title.
	@Test
	void aVersionSpecificIncompatibilityWithAnInstalledModOnlyCountsForThatVersion() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, new Dependency("API", "apiOld", "incompatible")));
		client.projects.add(new ModrinthProject("A", "a-mod", "A Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
		client.projects.add(new ModrinthProject("API", "fabric-api", "Fabric API", "approved", List.of("26.2"), List.of("fabric"), "optional"));

		assertEquals(List.of("aV"), ids(new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of("API"))));
		assertEquals(List.of("aV"), ids(new DependencyResolver(client, "fabric", "26.2", Set.of("apiNew")).resolve("a", Set.of("API"))));
		IOException refused = assertThrows(IOException.class,
				() -> new DependencyResolver(client, "fabric", "26.2", Set.of("apiOld")).resolve("a", Set.of("API")));
		assertEquals("Modrinth marks A Mod as incompatible with Fabric API, which is installed", refused.getMessage());
	}

	@Test
	void aVersionSpecificIncompatibilityWithinWhatGoesInTogetherOnlyCountsForThatVersion() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		ModrinthVersion apiOld = version("apiOld", "API", "1", T);
		ModrinthVersion apiNew = version("apiNew", "API", "2", T);
		put(client, "a", version("aV", "A", "1", T, new Dependency("API", "apiOld", "incompatible")));
		put(client, "b", version("bV", "B", "1", T));
		ModrinthVersion wantsOtherB = version("cV", "C", "1", T, new Dependency("B", "bOld", "incompatible"));
		ModrinthVersion wantsThisB = version("dV", "D", "1", T, new Dependency(null, "bV", "incompatible"));
		client.projects.add(new ModrinthProject("B", "b-mod", "B Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
		client.projects.add(new ModrinthProject("D", "d-mod", "D Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2");

		assertEquals(List.of("aV"), ids(resolver.resolve("a", Set.of(), List.of(apiNew))));
		assertEquals("Modrinth marks A and API as incompatible, and both would be installed",
				assertThrows(IOException.class, () -> resolver.resolve("a", Set.of(), List.of(apiOld))).getMessage());
		assertEquals(List.of("bV"), ids(resolver.resolve("b", Set.of(), List.of(wantsOtherB))));
		assertEquals("Modrinth marks D Mod and B Mod as incompatible, and both would be installed",
				assertThrows(IOException.class, () -> resolver.resolve("b", Set.of(), List.of(wantsThisB))).getMessage());
	}

	private static ModrinthProject project(String id, String slug, String title) {
		return new ModrinthProject(id, slug, title, "approved", List.of("26.2"), List.of("fabric"), "optional");
	}

	private static Dependency incompatibleVersion(String projectId, String versionId) {
		return new Dependency(projectId, versionId, "incompatible");
	}

	// SPEC 3c (AC3.3, plan review A-M2): a dependency naming only a version is named by the project that version
	// belongs to, found in local data (the installed versions, or what goes in together); never by the version id.
	@Test
	void aVersionOnlyIncompatibilityNamesTheProjectNeverTheVersionId() {
		FakeModrinthClient client = new FakeModrinthClient();
		ModrinthVersion sodium = version("Kx9mP2qR", "AANobbMI", "0.9.1", T);
		put(client, "a", version("aV", "A", "1", T, incompatibleVersion(null, "Kx9mP2qR")));
		client.projects.add(project("A", "a-mod", "A Mod"));
		client.projects.add(project("AANobbMI", "sodium", "Sodium"));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2", Map.of("Kx9mP2qR", sodium));
		Pattern fixtureIds = Pattern.compile("Kx9mP2qR|AANobbMI");

		String installed = assertThrows(IOException.class, () -> resolver.resolve("a", Set.of("AANobbMI"))).getMessage();
		String together = assertThrows(IOException.class,
				() -> new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of(), List.of(sodium))).getMessage();

		assertEquals("Modrinth marks A Mod as incompatible with Sodium, which is installed", installed);
		assertFalse(fixtureIds.matcher(installed).find(), installed);
		assertEquals("Modrinth marks A Mod and Sodium as incompatible, and both would be installed", together);
		assertFalse(fixtureIds.matcher(together).find(), together);
	}

	@Test
	void aVersionOnlyIncompatibilityWhoseProjectIsUnknownSaysAnotherMod() {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, incompatibleVersion(null, "Kx9mP2qR")));
		ModrinthVersion untitled = version("Kx9mP2qR", "AANobbMI", "0.9.1", T);

		IOException unmapped = assertThrows(IOException.class,
				() -> new DependencyResolver(client, "fabric", "26.2", Set.of("Kx9mP2qR")).resolve("a", Set.of()));
		IOException untitledProject = assertThrows(IOException.class,
				() -> new DependencyResolver(client, "fabric", "26.2", Map.of("Kx9mP2qR", untitled)).resolve("a", Set.of()));

		assertEquals("Modrinth marks A as incompatible with another mod, which is installed", unmapped.getMessage());
		assertEquals("Modrinth marks A as incompatible with another mod, which is installed", untitledProject.getMessage());
	}

	// SPEC 3b as amended (A-H1): the view is "installed, with this batch's updates applied". An incompatibility with the
	// version an update replaces passes only through that update (the planner then joins its group).
	@Test
	void anIncompatibilityWithTheOldVersionOfAnUpdatedModNeedsThatUpdate() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		ModrinthVersion a1 = version("a1", "A", "1", T);
		ModrinthVersion a2 = version("a2", "A", "2", T);
		put(client, "b", version("bV", "B", "1", T, incompatibleVersion("A", "a1")));
		put(client, "c", version("cV", "C", "1", T));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2", Map.of("a1", a1));

		DependencyResolver.Resolution resolution = resolver.resolve("b", Set.of("A"), List.of(a2), Set.of("A"));

		assertEquals(List.of("bV"), ids(resolution.versions()));
		assertEquals(Set.of("A"), resolution.updatesNeeded());
		assertEquals("Modrinth marks B as incompatible with A, which is installed",
				assertThrows(IOException.class, () -> resolver.resolve("b", Set.of("A"), List.of(), Set.of())).getMessage());
		assertEquals(Set.of(), resolver.resolve("c", Set.of("A"), List.of(a2), Set.of("A")).updatesNeeded());
	}

	@Test
	void anIncompatibilityWithTheVersionBeingUpdatedToIsRefused() {
		FakeModrinthClient client = new FakeModrinthClient();
		ModrinthVersion a1 = version("a1", "A", "1", T);
		ModrinthVersion a2 = version("a2", "A", "2", T);
		put(client, "b", version("bV", "B", "1", T, incompatibleVersion("A", "a2")));
		put(client, "c", version("cV", "C", "1", T, incompatible("A")));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2", Map.of("a1", a1));

		assertEquals("Modrinth marks B and A as incompatible, and both would be installed",
				assertThrows(IOException.class, () -> resolver.resolve("b", Set.of("A"), List.of(a2), Set.of("A"))).getMessage());
		assertEquals("Modrinth marks C as incompatible with A, which is installed",
				assertThrows(IOException.class, () -> resolver.resolve("c", Set.of("A"), List.of(a2), Set.of("A"))).getMessage());
	}

	@Test
	void anUpdateDeclaringAnInstalledProjectIncompatibleIsRefused() {
		FakeModrinthClient client = new FakeModrinthClient();
		ModrinthVersion a1 = version("a1", "A", "1", T);
		ModrinthVersion k1 = version("k1", "K", "1", T);
		client.projects.add(project("A", "a-mod", "A Mod"));
		client.projects.add(project("K", "k-mod", "K Mod"));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2", Map.of("a1", a1, "k1", k1));

		assertEquals("Modrinth marks A Mod as incompatible with K Mod, which is installed", assertThrows(IOException.class,
				() -> resolver.checkUpdate(version("a2", "A", "2", T, incompatible("K")), Set.of("A", "K"), List.of())).getMessage());
		assertEquals("Modrinth marks A Mod as incompatible with K Mod, which is installed", assertThrows(IOException.class,
				() -> resolver.checkUpdate(version("a2", "A", "2", T, incompatibleVersion(null, "k1")), Set.of("A", "K"), List.of())).getMessage());
		// Its own project's installed version is the one it replaces.
		assertDoesNotThrow(() -> resolver.checkUpdate(version("a2", "A", "2", T, incompatibleVersion("A", "a1"), incompatible("A"), incompatible("GONE")),
				Set.of("A", "K"), List.of()));
	}

	@Test
	void anUpdateIsCheckedAgainstTheBatchBothWays() {
		FakeModrinthClient client = new FakeModrinthClient();
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2", Map.of());
		ModrinthVersion a2 = version("a2", "A", "2", T);

		assertEquals("Modrinth marks C and A as incompatible, and both would be installed", assertThrows(IOException.class,
				() -> resolver.checkUpdate(a2, Set.of("A"), List.of(version("c2", "C", "2", T, incompatibleVersion(null, "a2"))))).getMessage());
		assertEquals("Modrinth marks A and C as incompatible, and both would be installed", assertThrows(IOException.class,
				() -> resolver.checkUpdate(version("a2", "A", "2", T, incompatibleVersion("C", "c2")), Set.of("A"), List.of(version("c2", "C", "2", T)))).getMessage());
		assertDoesNotThrow(() -> resolver.checkUpdate(a2, Set.of("A"), List.of(version("c2", "C", "2", T, incompatibleVersion("A", "a1")))));
	}

	// Review of WS-A, finding 2: an installed mod declaring the updated mod's whole project incompatible already runs next
	// to it; refusing the update removes no risk.
	@Test
	void anUpdateIsNotRefusedForAnInstalledConflictThatAlreadyExists() {
		FakeModrinthClient client = new FakeModrinthClient();
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2",
				Map.of("k1", version("k1", "K", "1", T, incompatible("A")), "a1", version("a1", "A", "1", T)));

		assertDoesNotThrow(() -> resolver.checkUpdate(version("a2", "A", "2", T), Set.of("A", "K"), List.of()));
	}

	// The review's known gap, covered: an installed mod's own "incompatible" entries count too (both directions).
	@Test
	void anInstalledModsOwnIncompatibilityCounts() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "b", version("bV", "B", "1", T));
		DependencyResolver resolver = new DependencyResolver(client, "fabric", "26.2", Map.of("k1", version("k1", "K", "1", T, incompatible("B"))));

		assertEquals("Modrinth marks K, which is installed, as incompatible with B",
				assertThrows(IOException.class, () -> resolver.resolve("b", Set.of("K"))).getMessage());
		assertEquals(Set.of("K"), resolver.resolve("b", Set.of("K"), List.of(version("k2", "K", "2", T)), Set.of("K")).updatesNeeded());

		DependencyResolver againstUpdate = new DependencyResolver(client, "fabric", "26.2",
				Map.of("k1", version("k1", "K", "1", T, incompatibleVersion(null, "a2")), "a1", version("a1", "A", "1", T, incompatibleVersion(null, "a2"))));
		assertEquals("Modrinth marks K, which is installed, as incompatible with A",
				assertThrows(IOException.class, () -> againstUpdate.checkUpdate(version("a2", "A", "2", T), Set.of("A", "K"), List.of())).getMessage());
	}

	@Test
	void failsWhenRequiredDependencyIsUnavailable() {
		FakeModrinthClient client = new FakeModrinthClient();
		put(client, "a", version("aV", "A", "1", T, required("GONE")));
		assertThrows(IOException.class, () -> new DependencyResolver(client, "fabric", "26.2").resolve("a", Set.of()));
		assertThrows(IOException.class, () -> new DependencyResolver(client, "fabric", "26.2").resolve("nope", Set.of()));
	}
}
