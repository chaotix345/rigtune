package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.required;
import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Review 2, N2: dependency bookkeeping in one download batch.
class DownloadPlannerTest {
	private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

	@TempDir
	Path dir;
	Path mods;
	final FakeModrinthClient client = new FakeModrinthClient();
	final List<String> fetched = new ArrayList<>();
	final Set<String> failing = new HashSet<>();
	final Set<String> failOnce = new HashSet<>();

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
	}

	private void put(String slug, ModrinthVersion v) {
		client.latestByProject.put(slug, v);
		client.latestByProject.put(v.projectId(), v);
	}

	// Files are named <version id>.jar and hold a mod whose id is the version id minus its trailing "V", lower-cased.
	private Path fetch(ModFile file) throws IOException {
		fetched.add(file.filename());
		if (failing.contains(file.filename()) || failOnce.remove(file.filename())) {
			throw new IOException("stalled: " + file.filename());
		}
		String base = file.filename().substring(0, file.filename().length() - ".jar".length());
		String id = (base.endsWith("V") ? base.substring(0, base.length() - 1) : base).toLowerCase(Locale.ROOT);
		return TestJars.modJar(SafeFileNames.resolveJar(mods, file.filename(), PendingActions.PENDING_SUFFIX), id);
	}

	private Recommendation update(String current, String next) {
		UpdateInfo info = new UpdateInfo("m", "M", "1", "v2", "2", new ModFile("https://cdn/" + next, next, "sha512", 10));
		return new Recommendation("update-m", Category.UPDATE_MOD, Impact.MEDIUM, "Update m", "", new Action.UpdateMod("m", mods.resolve(current), info), true);
	}

	// Review 2, N3: an update whose file name is already taken would fail at every exit, so it isn't staged.
	@Test
	void anUpdateWhoseTargetIsTakenIsRefusedBeforeDownloading() throws IOException {
		Files.writeString(mods.resolve("m-1.jar"), "installed");
		Files.writeString(mods.resolve("m-2.jar"), "left over");

		DownloadPlanner.Result result = plan(Set.of(), update("m-1.jar", "m-2.jar"));

		assertEquals(List.of(), result.ops());
		assertEquals(List.of(), result.ids());
		assertTrue(result.errors().getFirst().contains("m-2.jar is already in the mods folder"), result.errors().toString());
		assertEquals(List.of(), fetched);
	}

	@Test
	void anUpdateKeepingTheSameFileNameIsStaged() throws IOException {
		Files.writeString(mods.resolve("m.jar"), "installed");

		DownloadPlanner.Result result = plan(Set.of(), update("m.jar", "m.jar"));

		assertEquals(List.of("update-m"), result.ids());
		assertEquals(List.of(PendingActions.Type.DISABLE_FILE, PendingActions.Type.ENABLE_FILE), result.ops().stream().map(Op::type).toList());
		assertEquals(1, groups(result.ops()));
	}

	private DownloadPlanner.Result plan(Set<String> installedProjects, Recommendation... recs) {
		DownloadPlanner planner = new DownloadPlanner(new DependencyResolver(client, "fabric", "26.2"), mods, this::fetch);
		return planner.plan(List.of(recs), installedProjects, Set.of(), Map.of());
	}

	private static Recommendation add(String slug, String projectId) {
		return new Recommendation("add-" + slug, Category.ADD_MOD, Impact.HIGH, "Add " + slug, "", new Action.AddMod(slug, projectId, slug), true);
	}

	private static List<String> targets(List<Op> ops) {
		return ops.stream().map(op -> Path.of(op.to()).getFileName().toString()).toList();
	}

	private static long groups(List<Op> ops) {
		return ops.stream().map(Op::group).distinct().count();
	}

	private void libraryUsers() {
		put("a", version("aV", "A", "1", T, required("LIB")));
		put("b", version("bV", "B", "1", T, required("LIB")));
		put("lib", version("libV", "LIB", "1", T));
	}

	@Test
	void aDependencyThatFailedForOneRecommendationIsStillFetchedForTheNext() {
		libraryUsers();
		failOnce.add("libV.jar");

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("b", "B"));

		assertEquals(List.of("add-b"), result.ids());
		assertEquals(1, result.errors().size(), result.errors().toString());
		assertEquals(List.of("bV.jar", "libV.jar"), targets(result.ops()));
		assertEquals(1, groups(result.ops()));
		assertEquals(List.of("b", "lib"), result.ops().stream().map(Op::modId).toList());
	}

	@Test
	void aRecommendationWhoseDependencyNeverArrivesIsNotStagedAlone() {
		libraryUsers();
		failing.add("libV.jar");

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("b", "B"));

		assertEquals(List.of(), result.ids());
		assertEquals(List.of(), result.ops());
		assertEquals(2, result.errors().size());
	}

	@Test
	void modIdsSeenByAFailedRecommendationAreNotCommitted() {
		put("a", version("aV", "A", "1", T, required("LIB"), required("C")));
		put("b", version("bV", "B", "1", T, required("LIB")));
		put("lib", version("libV", "LIB", "1", T));
		put("c", version("cV", "C", "1", T));
		failing.add("cV.jar");

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("b", "B"));

		assertEquals(List.of("add-b"), result.ids());
		assertEquals(List.of("bV.jar", "libV.jar"), targets(result.ops()));
		assertEquals(1, groups(result.ops()));
	}

	@Test
	void aSharedDependencyJoinsBothRecommendationsInOneGroup() throws IOException {
		libraryUsers();

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("b", "B"));

		assertEquals(List.of("add-a", "add-b"), result.ids());
		assertEquals(List.of("aV.jar", "libV.jar", "bV.jar"), targets(result.ops()));
		assertEquals(1, groups(result.ops()), result.ops().toString());
		assertEquals(1, fetched.stream().filter("libV.jar"::equals).count());

		// At exit A's target turns out to be taken: the whole group fails, so B is never enabled without lib.
		Path config = Files.createDirectories(dir.resolve("config"));
		Path pending = PendingActions.defaultPath(config);
		PendingActions plan = PendingActions.create(1, mods, config, result.ops());
		plan.save(pending);
		Files.writeString(mods.resolve("aV.jar"), "someone else's file");

		ApplyResult applied = new ApplyExecutor(2, 1).run(plan, pending);

		assertTrue(applied.results().stream().allMatch(r -> r.status() == ApplyResult.Status.FAILED), applied.toString());
		assertFalse(Files.exists(mods.resolve("libV.jar")));
		assertFalse(Files.exists(mods.resolve("bV.jar")));
	}

	@Test
	void aRecommendationNeedingTwoEarlierGroupsMergesThem() {
		put("a", version("aV", "A", "1", T, required("LIB1")));
		put("b", version("bV", "B", "1", T, required("LIB2")));
		put("c", version("cV", "C", "1", T, required("LIB1"), required("LIB2")));
		put("lib1", version("lib1V", "LIB1", "1", T));
		put("lib2", version("lib2V", "LIB2", "1", T));
		put("d", version("dV", "D", "1", T));

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("b", "B"), add("d", "D"), add("c", "C"));

		assertEquals(List.of("aV.jar", "lib1V.jar", "bV.jar", "lib2V.jar", "dV.jar", "cV.jar"), targets(result.ops()));
		List<Op> joined = result.ops().stream().filter(op -> !op.to().endsWith("dV.jar")).toList();
		assertEquals(1, groups(joined), result.ops().toString());
		assertEquals(2, groups(result.ops()));
	}

	@Test
	void loadedDependenciesDoNotJoinAnything() {
		libraryUsers();

		DownloadPlanner.Result result = plan(Set.of("LIB"), add("a", "A"), add("b", "B"));

		assertEquals(List.of("aV.jar", "bV.jar"), targets(result.ops()));
		assertEquals(2, groups(result.ops()));
	}
}
