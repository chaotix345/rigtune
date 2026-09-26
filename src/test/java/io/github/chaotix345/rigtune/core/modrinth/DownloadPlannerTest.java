package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.TextChecks;
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
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.incompatible;
import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.required;
import static io.github.chaotix345.rigtune.core.modrinth.FakeModrinthClient.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
	final Set<String> notMods = new HashSet<>();
	BiPredicate<String, String> conflicts = (a, b) -> false;
	final Map<String, ModrinthVersion> installedVersions = new HashMap<>();
	final Map<String, ModrinthVersion> updateVersions = new HashMap<>();
	final List<DownloadPlanner.Result> planned = new ArrayList<>();
	StagedProjects staged = StagedProjects.NONE;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
	}

	// docs/v0.3/SPEC.md item 9 (AC9.3): each error the UI shows is "<title>: <cause>" from en_us.json, the cause a
	// planner or resolver refusal in en_us.json too; only a download's own failure (the fake's "stalled: <file>") is
	// shown as it is. The Strings stay the English.
	@AfterEach
	void everyErrorIsTranslatable() {
		for (DownloadPlanner.Result result : planned) {
			assertEquals(result.errors(), result.errorTexts().stream().map(Text::english).toList());
			for (Text error : result.errorTexts()) {
				Text.Translatable shown = assertInstanceOf(Text.Translatable.class, error);
				assertEquals("rigtune.download.error", shown.key());
				Object cause = shown.args().get(1);
				if (cause instanceof Text.Literal literal) {
					assertTrue(literal.value().startsWith("stalled: "), "an untranslated refusal: " + error.english());
				} else {
					TextChecks.assertPseudoLocalised((Text) cause, Set.of(), error.english());
				}
			}
		}
	}

	private void put(String slug, ModrinthVersion v) {
		client.latestByProject.put(slug, v);
		client.latestByProject.put(v.projectId(), v);
		client.byId.put(v.id(), v);
	}

	// Files are named <version id>.jar and hold a mod whose id is the version id minus its trailing "V", lower-cased
	// (the ones in notMods hold no mod).
	private Path fetch(ModFile file) throws IOException {
		fetched.add(file.filename());
		if (failing.contains(file.filename()) || failOnce.remove(file.filename())) {
			throw new IOException("stalled: " + file.filename());
		}
		if (notMods.contains(file.filename())) {
			return Files.writeString(SafeFileNames.resolveJar(mods, file.filename(), PendingActions.PENDING_SUFFIX), "not a jar");
		}
		String base = file.filename().substring(0, file.filename().length() - ".jar".length());
		String id = (base.endsWith("V") ? base.substring(0, base.length() - 1) : base).toLowerCase(Locale.ROOT);
		return TestJars.modJar(SafeFileNames.resolveJar(mods, file.filename(), PendingActions.PENDING_SUFFIX), id);
	}

	private Recommendation update(String current, String next) {
		updateVersions.put("v2", version("v2", "M", "2", T));
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

	// Review 4, apply-safety-1: an update's download with no readable mod id is refused like an added mod's, and the
	// installed jar is left alone.
	@Test
	void anUpdateWhoseDownloadIsNotAFabricModIsDroppedWithAnError() throws IOException {
		Files.writeString(mods.resolve("m-1.jar"), "installed");
		notMods.add("m-2.jar");

		DownloadPlanner.Result result = plan(Set.of(), update("m-1.jar", "m-2.jar"));

		assertEquals(List.of(), result.ops());
		assertEquals(List.of(), result.ids());
		assertEquals(1, result.errors().size(), result.errors().toString());
		assertTrue(result.errors().getFirst().startsWith("Update m: m-2.jar is not a Fabric mod jar"), result.errors().getFirst());
		assertFalse(Files.exists(mods.resolve("m-2.jar" + PendingActions.PENDING_SUFFIX)));
		assertEquals("installed", Files.readString(mods.resolve("m-1.jar")));
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
		DownloadPlanner planner = new DownloadPlanner(new DependencyResolver(client, "fabric", "26.2", installedVersions).withStaged(staged), mods,
				this::fetch, conflicts, updateVersions);
		DownloadPlanner.Result result = planner.plan(List.of(recs), installedProjects, Set.of(), Map.of());
		planned.add(result);
		return result;
	}

	// Review 4, rules-accuracy-2, and docs/v0.4/SPEC.md 2e (AC2e.2): a batch never stages both sides of a rules conflict,
	// and which side goes in doesn't follow the tick order: both are refused, before any download, naming each other.
	@Test
	void twoConflictingAdditionsAreBothRefusedInEitherOrder() {
		put("krypton", version("kryptonV", "KRYPTON", "1", T));
		put("b", version("bV", "B", "1", T));
		put("noise", version("noiseV", "NOISE", "1", T));
		conflicts = (a, b) -> Set.of("krypton", "noise").equals(Set.of(a, b));

		DownloadPlanner.Result result = plan(Set.of(), add("krypton", "KRYPTON"), add("b", "B"), add("noise", "NOISE"));
		DownloadPlanner.Result reversed = plan(Set.of(), add("noise", "NOISE"), add("b", "B"), add("krypton", "KRYPTON"));

		for (DownloadPlanner.Result r : List.of(result, reversed)) {
			assertEquals(List.of("add-b"), r.ids());
			assertEquals(List.of("bV.jar"), targets(r.ops()));
			assertEquals(Set.of("Add krypton: it conflicts with noise, which is ticked too; tick only one of them",
					"Add noise: it conflicts with krypton, which is ticked too; tick only one of them"), Set.copyOf(r.errors()));
		}
		assertFalse(fetched.contains("kryptonV.jar"));
		assertFalse(fetched.contains("noiseV.jar"));
	}

	// 2e: refused together even when one side would have failed anyway.
	@Test
	void aConflictingPairIsRefusedEvenWhenOneSideWouldFail() {
		put("krypton", version("kryptonV", "KRYPTON", "1", T));
		put("noise", version("noiseV", "NOISE", "1", T));
		conflicts = (a, b) -> Set.of("krypton", "noise").equals(Set.of(a, b));
		failing.add("kryptonV.jar");

		DownloadPlanner.Result result = plan(Set.of(), add("krypton", "KRYPTON"), add("noise", "NOISE"));

		assertEquals(List.of(), result.ids());
		assertEquals(2, result.errors().size(), result.errors().toString());
		assertEquals(List.of(), fetched);
	}

	// Review 4, rules-accuracy-2, and 2e (AC2e.2): a Modrinth "incompatible" dependency between two additions of one batch
	// refuses both, whichever was ticked first.
	@Test
	void twoModrinthIncompatibleAdditionsAreBothRefusedInEitherOrder() {
		put("a", version("aV", "A", "1", T, incompatible("B")));
		put("c", version("cV", "C", "1", T));
		put("b", version("bV", "B", "1", T));

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("c", "C"), add("b", "B"));
		DownloadPlanner.Result reversed = plan(Set.of(), add("b", "B"), add("c", "C"), add("a", "A"));

		for (DownloadPlanner.Result r : List.of(result, reversed)) {
			assertEquals(List.of("add-c"), r.ids());
			assertEquals(List.of("cV.jar"), targets(r.ops()));
			assertEquals(Set.of("Add a: Modrinth marks A and B as incompatible, and both would be installed",
					"Add b: Modrinth marks B and A as incompatible, and both would be installed"), Set.copyOf(r.errors()));
		}
		assertFalse(fetched.contains("aV.jar"));
		assertFalse(fetched.contains("bV.jar"));
		assertEquals(2, client.calls.stream().filter("latestVersion:A"::equals).count(), "one lookup per plan: " + client.calls);
	}

	// Mod a is installed as a-1.jar (Modrinth version a1 of project A); its update is version aV (file aV.jar).
	private Recommendation updateA(Dependency... deps) throws IOException {
		return updateOf("a", "A", deps);
	}

	// Mod <mod> is installed as <mod>-1.jar (version <mod>1 of <project>); its update is version <mod>V (file <mod>V.jar).
	private Recommendation updateOf(String mod, String project, Dependency... deps) throws IOException {
		Files.writeString(mods.resolve(mod + "-1.jar"), "installed");
		installedVersions.put(mod + "1", version(mod + "1", project, "1", T));
		ModrinthVersion next = version(mod + "V", project, "2", T, deps);
		updateVersions.put(mod + "V", next);
		UpdateInfo info = new UpdateInfo(mod, project, "1", mod + "V", "2", next.primaryFile());
		return new Recommendation("update-" + mod, Category.UPDATE_MOD, Impact.MEDIUM, "Update " + mod, "",
				new Action.UpdateMod(mod, mods.resolve(mod + "-1.jar"), info), true);
	}

	// Review of WS-A, finding 1: once an addition folds two updates' groups into one, a later addition relying on the
	// second update must join the folded group, not the second update's old (now empty) group.
	@Test
	void anAdditionRelyingOnAnUpdateWhoseGroupWasFoldedJoinsTheFoldedGroup() throws IOException {
		Recommendation updateA = updateA();
		Recommendation updateK = updateOf("k", "K");
		put("x", version("xV", "X", "1", T, new Dependency("A", "a1", "incompatible"), new Dependency("K", "k1", "incompatible")));
		put("c", version("cV", "C", "1", T, new Dependency("K", "k1", "incompatible")));

		DownloadPlanner.Result result = plan(Set.of("A", "K"), add("x", "X"), add("c", "C"), updateA, updateK);

		assertEquals(List.of(), result.errors());
		assertEquals(List.of("update-a", "update-k", "add-x", "add-c"), result.ids());
		assertEquals(1, groups(result.ops()), result.ops().toString());
		Map<String, String> groupByFile = new HashMap<>();
		result.ops().forEach(op -> groupByFile.put(files(List.of(op)).getFirst(), op.group()));
		assertEquals(groupByFile.get("k-1.jar"), groupByFile.get("cV.jar"));
	}

	// Review of WS-A, finding 8: without the update's own Modrinth version, its incompatibilities can't be checked.
	@Test
	void anUpdateWhoseModrinthVersionIsUnknownIsRefused() throws IOException {
		Files.writeString(mods.resolve("m-1.jar"), "installed");
		Recommendation update = update("m-1.jar", "m-2.jar");
		updateVersions.clear();

		DownloadPlanner.Result result = plan(Set.of(), update);

		assertEquals(List.of(), result.ids());
		assertEquals(List.of("Update m: its Modrinth data changed since the list was made; try again"), result.errors());
		assertEquals(List.of(), fetched);
	}

	private void titles() {
		client.projects.add(new ModrinthProject("A", "a", "A Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
		client.projects.add(new ModrinthProject("B", "b", "B Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
		client.projects.add(new ModrinthProject("K", "k", "K Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
	}

	// The file each op touches: an enable's target, a disable's jar.
	private static List<String> files(List<Op> ops) {
		return ops.stream().map(op -> Path.of(op.to() != null ? op.to() : op.path()).getFileName().toString()).toList();
	}

	// SPEC 3b as amended (A-H1), AC3.2 (i): B is incompatible with the A version installed now, not with the one A's
	// update installs, so it goes in only with that update: both in one all-or-nothing group.
	@Test
	void anAdditionIncompatibleOnlyWithTheOldVersionJoinsTheUpdatesGroup() throws IOException {
		titles();
		Recommendation update = updateA();
		put("b", version("bV", "B", "1", T, new Dependency("A", "a1", "incompatible")));

		DownloadPlanner.Result result = plan(Set.of("A"), update, add("b", "B"));

		assertEquals(List.of(), result.errors());
		assertEquals(List.of("update-a", "add-b"), result.ids());
		assertEquals(List.of("a-1.jar", "aV.jar", "bV.jar"), files(result.ops()));
		assertEquals(1, groups(result.ops()), result.ops().toString());
	}

	// AC3.2 (ii): B is incompatible with the version A's update installs.
	@Test
	void anAdditionIncompatibleWithTheVersionBeingInstalledIsRefused() throws IOException {
		titles();
		Recommendation update = updateA();
		put("b", version("bV", "B", "1", T, new Dependency("A", "aV", "incompatible")));

		DownloadPlanner.Result result = plan(Set.of("A"), update, add("b", "B"));

		assertEquals(List.of("update-a"), result.ids());
		assertEquals(List.of("a-1.jar", "aV.jar"), files(result.ops()));
		assertEquals(List.of("Add b: Modrinth marks B Mod and A Mod as incompatible, and both would be installed"), result.errors());
		assertFalse(fetched.contains("bV.jar"));
	}

	// AC3.2 (iii): the update's own version declares an installed project incompatible; refused before its download.
	@Test
	void anUpdateWhoseVersionDeclaresAnInstalledProjectIncompatibleIsRefused() throws IOException {
		titles();
		Recommendation update = updateA(incompatible("K"));

		DownloadPlanner.Result result = plan(Set.of("A", "K"), update);

		assertEquals(List.of(), result.ids());
		assertEquals(List.of(), result.ops());
		assertEquals(List.of("Update a: Modrinth marks A Mod as incompatible with K Mod, which is installed"), result.errors());
		assertEquals(List.of(), fetched);
		assertEquals("installed", Files.readString(mods.resolve("a-1.jar")));
	}

	// docs/v0.4/SPEC.md 2e (AC2e.1): two updates whose new versions Modrinth marks incompatible (either declaring it)
	// are both refused, naming each other, in either tick order; an unrelated update proceeds.
	@Test
	void twoMutuallyIncompatibleUpdatesAreBothRefusedInEitherOrder() throws IOException {
		titles();
		client.projects.add(new ModrinthProject("M", "m", "M Mod", "approved", List.of("26.2"), List.of("fabric"), "optional"));
		for (boolean kDeclares : List.of(true, false)) {
			Recommendation updateA = kDeclares ? updateA() : updateA(new Dependency("K", "kV", "incompatible"));
			Recommendation updateK = kDeclares ? updateOf("k", "K", new Dependency(null, "aV", "incompatible")) : updateOf("k", "K");
			Recommendation updateM = updateOf("m", "M");

			DownloadPlanner.Result result = plan(Set.of("A", "K", "M"), updateA, updateK, updateM);
			DownloadPlanner.Result reversed = plan(Set.of("A", "K", "M"), updateK, updateA, updateM);

			for (DownloadPlanner.Result r : List.of(result, reversed)) {
				assertEquals(List.of("update-m"), r.ids(), r.errors().toString());
				assertEquals(List.of("m-1.jar", "mV.jar"), files(r.ops()));
				assertEquals(Set.of("Update a: Modrinth marks A Mod and K Mod as incompatible, and both would be installed",
						"Update k: Modrinth marks K Mod and A Mod as incompatible, and both would be installed"), Set.copyOf(r.errors()));
			}
			assertFalse(fetched.contains("aV.jar"));
			assertFalse(fetched.contains("kV.jar"));
			fetched.clear();
		}
	}

	// AC3.2 (iv): updates are planned before additions, so the order they were ticked in doesn't matter.
	@Test
	void theSelectionOrderDoesNotChangeTheOutcome() throws IOException {
		titles();
		Recommendation update = updateA();
		Recommendation addB = add("b", "B");
		put("b", version("bV", "B", "1", T, new Dependency("A", "a1", "incompatible")));

		DownloadPlanner.Result updateFirst = plan(Set.of("A"), update, addB);
		DownloadPlanner.Result addFirst = plan(Set.of("A"), addB, update);

		assertEquals(List.of("update-a", "add-b"), addFirst.ids());
		assertEquals(updateFirst.ids(), addFirst.ids());
		assertEquals(files(updateFirst.ops()), files(addFirst.ops()));
		assertEquals(List.of(), addFirst.errors());
		assertEquals(1, groups(addFirst.ops()));
	}

	// AC3.2 (v): without A's update, B would go in next to the A version it is incompatible with.
	@Test
	void aFailedUpdateDownloadRefusesTheAdditionThatReliedOnIt() throws IOException {
		titles();
		Recommendation update = updateA();
		put("b", version("bV", "B", "1", T, new Dependency("A", "a1", "incompatible")));
		failing.add("aV.jar");

		DownloadPlanner.Result result = plan(Set.of("A"), add("b", "B"), update);

		assertEquals(List.of(), result.ids());
		assertEquals(List.of(), result.ops());
		assertEquals(List.of("Update a: stalled: aV.jar", "Add b: Modrinth marks B Mod as incompatible with A Mod, which is installed"), result.errors());
	}

	// Plan review A-M1: the client keeps each recommendation's op ids, so it can tell which are still staged.
	@Test
	void eachRecommendationRecordsItsOpIds() throws IOException {
		Recommendation update = updateA();
		put("b", version("bV", "B", "1", T));
		put("c", version("cV", "C", "1", T));

		DownloadPlanner.Result result = plan(Set.of("A", "C"), add("b", "B"), add("c", "C"), update);

		Map<String, String> idByFile = new HashMap<>();
		result.ops().forEach(op -> idByFile.put(files(List.of(op)).getFirst(), op.id()));
		assertEquals(List.of("update-a", "add-b", "add-c"), result.ids());
		assertEquals(List.of(idByFile.get("a-1.jar"), idByFile.get("aV.jar")), result.opIds().get("update-a"));
		assertEquals(List.of(idByFile.get("bV.jar")), result.opIds().get("add-b"));
		// C is installed already, so its recommendation brought nothing.
		assertEquals(List.of(), result.opIds().get("add-c"));
	}

	@Test
	void aRecommendationWithNoOpsOfItsOwnRecordsTheGroupItJoined() {
		libraryUsers();

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("lib", "LIB"));

		assertEquals(List.of("add-a", "add-lib"), result.ids());
		assertEquals(result.ops().stream().map(Op::id).toList(), result.opIds().get("add-lib"));
		assertEquals(result.opIds().get("add-a"), result.opIds().get("add-lib"));
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

	// Review 3, apply-safety-1: a download with no readable fabric.mod.json id can't be checked for duplicates, so it
	// is deleted and its recommendation fails.
	@Test
	void aDownloadThatIsNotAFabricModIsDroppedWithAnError() {
		put("a", version("aV", "A", "1", T));
		put("b", version("bV", "B", "1", T));
		notMods.add("aV.jar");

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"), add("b", "B"));

		assertEquals(List.of("add-b"), result.ids());
		assertEquals(List.of("bV.jar"), targets(result.ops()));
		assertEquals(1, result.errors().size(), result.errors().toString());
		assertTrue(result.errors().getFirst().startsWith("Add a: aV.jar is not a Fabric mod jar"), result.errors().getFirst());
		assertFalse(Files.exists(mods.resolve("aV.jar" + PendingActions.PENDING_SUFFIX)));
	}

	@Test
	void aDependencyThatIsNotAFabricModFailsTheRecommendation() {
		libraryUsers();
		notMods.add("libV.jar");

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"));

		assertEquals(List.of(), result.ids());
		assertEquals(List.of(), result.ops());
		assertEquals(1, result.errors().size(), result.errors().toString());
		assertFalse(Files.exists(mods.resolve("libV.jar" + PendingActions.PENDING_SUFFIX)));
	}

	@Test
	void loadedDependenciesDoNotJoinAnything() {
		libraryUsers();

		DownloadPlanner.Result result = plan(Set.of("LIB"), add("a", "A"), add("b", "B"));

		assertEquals(List.of("aV.jar", "bV.jar"), targets(result.ops()));
		assertEquals(2, groups(result.ops()));
	}

	// --- docs/v0.4/SPEC.md 2d (amendments A-H1, A-M1, A-L1): an earlier Apply's staged changes, AC2d.1 and AC2d.4

	private Path pendingFile() throws IOException {
		Path config = Files.createDirectories(dir.resolve("config"));
		Path pending = PendingActions.defaultPath(config);
		Files.createDirectories(pending.getParent());
		return pending;
	}

	// One Apply: plans these, merges the ops into pending.json as Staging does, and returns the staged view the next
	// Apply's checks read.
	private StagedProjects stage(Recommendation... recs) throws IOException {
		DownloadPlanner.Result result = plan(Set.of(), recs);
		assertEquals(List.of(), result.errors());
		Path pending = pendingFile();
		PendingActions plan = Files.exists(pending) ? PendingActions.load(pending) : PendingActions.create(1, mods, pending.getParent().getParent(), List.of());
		plan.merge(result.ops()).plan().save(pending);
		return StagedProjects.read(pending);
	}

	@Test
	void everyEnableCarriesItsModrinthProjectAndVersion() throws IOException {
		libraryUsers();
		Recommendation update = updateA();

		DownloadPlanner.Result result = plan(Set.of("A"), add("b", "B"), update);

		Map<String, Op> byFile = new HashMap<>();
		result.ops().forEach(op -> byFile.put(files(List.of(op)).getFirst(), op));
		assertEquals("B", byFile.get("bV.jar").projectId());
		assertEquals("bV", byFile.get("bV.jar").versionId());
		assertEquals("LIB", byFile.get("libV.jar").projectId());
		assertEquals("libV", byFile.get("libV.jar").versionId());
		assertEquals("A", byFile.get("aV.jar").projectId());
		assertEquals("aV", byFile.get("aV.jar").versionId());
		// A disable has no Modrinth identity (the staged view leaves it out: SPEC 2d's documented residual).
		assertEquals(null, byFile.get("a-1.jar").projectId());
	}

	@Test
	void aLaterAdditionDeclaringAStagedModIncompatibleIsRefused() throws IOException {
		titles();
		put("a", version("aV", "A", "1", T));
		staged = stage(add("a", "A"));
		put("b", version("bV", "B", "1", T, incompatible("A")));

		DownloadPlanner.Result result = plan(Set.of(), add("b", "B"));

		assertEquals(List.of(), result.ids());
		assertEquals(List.of(), result.ops());
		assertEquals(List.of("Add b: Modrinth marks B Mod as incompatible with A Mod, which is waiting for a restart"), result.errors());
		assertFalse(fetched.contains("bV.jar"));
	}

	@Test
	void aLaterAdditionAStagedModDeclaresIncompatibleIsRefused() throws IOException {
		titles();
		put("a", version("aV", "A", "1", T, incompatible("B")));
		staged = stage(add("a", "A"));
		put("b", version("bV", "B", "1", T));

		DownloadPlanner.Result result = plan(Set.of(), add("b", "B"));

		assertEquals(List.of(), result.ids());
		assertEquals(List.of("Add b: Modrinth marks A Mod, which is waiting for a restart, as incompatible with B Mod"), result.errors());
		assertEquals(1, client.calls.stream().filter(c -> c.equals("versions:aV")).count(), client.calls.toString());
	}

	@Test
	void aLaterUpdateIsCheckedAgainstStagedModsBothWays() throws IOException {
		titles();
		// Version-specific: a whole-project declaration would match the installed K too, a conflict that exists already.
		put("b", version("bV", "B", "1", T, new Dependency("K", "kV", "incompatible")));
		staged = stage(add("b", "B"));

		DownloadPlanner.Result forward = plan(Set.of("A"), updateA(incompatible("B")));
		DownloadPlanner.Result reverse = plan(Set.of("K"), updateOf("k", "K"));

		assertEquals(List.of("Update a: Modrinth marks A Mod as incompatible with B Mod, which is waiting for a restart"), forward.errors());
		assertEquals(List.of("Update k: Modrinth marks B Mod, which is waiting for a restart, as incompatible with K Mod"), reverse.errors());
	}

	@Test
	void aDeclarationNamingAStagedVersionCountsWithoutAskingModrinth() throws IOException {
		titles();
		put("a", version("aV", "A", "1", T));
		staged = stage(add("a", "A"));
		client.versionsFailWith = new IOException("Modrinth lookups are off");
		put("b", version("bV", "B", "1", T, new Dependency(null, "aV", "incompatible")));

		DownloadPlanner.Result result = plan(Set.of(), add("b", "B"));

		assertEquals(List.of("Add b: Modrinth marks B Mod as incompatible with A Mod, which is waiting for a restart"), result.errors());
	}

	// A-M1: without Modrinth lookups only the forward check runs (the staged versions' own declarations are unknown).
	@Test
	void withoutModrinthLookupsOnlyTheForwardCheckRuns() throws IOException {
		titles();
		put("a", version("aV", "A", "1", T, new Dependency("K", "kV", "incompatible")));
		staged = stage(add("a", "A"));
		client.versionsFailWith = new IOException("Modrinth lookups are off");

		DownloadPlanner.Result forward = plan(Set.of("K"), updateOf("k", "K", incompatible("A")));
		DownloadPlanner.Result reverse = plan(Set.of("K"), updateOf("k", "K"));

		assertEquals(List.of("Update k: Modrinth marks K Mod as incompatible with A Mod, which is waiting for a restart"), forward.errors());
		assertEquals(List.of("update-k"), reverse.ids());
	}

	// A pending.json written by 0.3.0 (no projectId/versionId): v0.3's behaviour, nothing staged counts.
	@Test
	void aPlanWithoutModrinthIdsKeepsTheV030Behaviour() throws IOException {
		titles();
		put("a", version("aV", "A", "1", T, incompatible("B")));
		stage(add("a", "A"));
		Path pending = pendingFile();
		PendingActions plan = PendingActions.load(pending);
		plan.withOps(plan.ops().stream().map(op -> new Op(op.type(), op.from(), op.to(), op.path(), op.patches(), op.id(), op.group(), op.modId(),
				op.attempts())).toList()).save(pending);
		staged = StagedProjects.read(pending);
		put("b", version("bV", "B", "1", T, incompatible("A")));

		DownloadPlanner.Result result = plan(Set.of(), add("b", "B"));

		assertTrue(staged.isEmpty());
		assertEquals(List.of("add-b"), result.ids());
		assertEquals(List.of(), result.errors());
	}

	// AC2d.4 (A-H1): an addition that requires a staged mod still resolves it, so it joins that mod's staged group, and
	// undoing the first Apply (which drops its whole group from pending.json) takes the dependant with it.
	@Test
	void anAdditionRequiringAStagedModJoinsItsGroup() throws IOException {
		put("lib", version("libV", "LIB", "1", T));
		staged = stage(add("lib", "LIB"));
		Path pending = pendingFile();
		Op stagedLib = PendingActions.load(pending).ops().getFirst();
		put("a", version("aV", "A", "1", T, required("LIB")));

		DownloadPlanner.Result result = plan(Set.of(), add("a", "A"));

		assertEquals(List.of("add-a"), result.ids());
		assertEquals(List.of("aV.jar", "libV.jar"), targets(result.ops()));
		PendingActions merged = PendingActions.load(pending).merge(result.ops()).plan();
		assertEquals(2, merged.ops().size(), merged.ops().toString());
		assertEquals(1, groups(merged.ops()), merged.ops().toString());
		assertEquals(List.of(), merged.remove(List.of(stagedLib.id())).plan().ops());
	}
}
