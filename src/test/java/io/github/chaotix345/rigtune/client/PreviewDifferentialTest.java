package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PropertiesConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.apply.TomlConfigPatcher;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.DownloadPlanner;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.preview.DownloadInputs;
import io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth;
import io.github.chaotix345.rigtune.core.preview.PreviewFixtures;
import io.github.chaotix345.rigtune.core.preview.PreviewPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.required;
import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.version;
import static io.github.chaotix345.rigtune.core.preview.PreviewFixtures.setting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC13.1 (docs/v0.3/SPEC.md item 13): for selections of every kind, the preview's files equal the files the real
// staging then touches in a temp instance: the downloads it makes now, the files pending.json's ops name, and, after
// the helper's executor has run the plan, every file that was created, renamed or changed; and the items the preview
// says won't be changed are the ones the real staging refuses. The real staging below is RealController.apply's,
// step by step with the same classes; PreviewGameTest runs the real RealController.apply for the vanilla half and a
// staged config key and disable.
class PreviewDifferentialTest {
	@TempDir
	Path dir;
	PreviewFixtures instance;
	List<ConfigTargets.Target> targets;
	final PreviewFakeModrinth modrinth = new PreviewFakeModrinth();
	final Map<String, ModrinthVersion> updateVersions = new LinkedHashMap<>();
	final Set<String> installedProjects = new HashSet<>(Set.of("SODIUM"));
	final Set<String> loadedIds = Set.of("sodium", "oldmod", "other", "m");
	BiPredicate<String, String> conflicts = (a, b) -> false;

	@BeforeEach
	void setUp() throws IOException {
		instance = new PreviewFixtures(dir.resolve("game"));
		targets = List.of(
				new ConfigTargets.Target("sodium.", instance.sodium, SodiumConfigPatcher::stage, PreviewFixtures::readSodium),
				new ConfigTargets.Target("dh.", instance.dh, TomlConfigPatcher::stage, TomlConfigPatcher::readValues),
				new ConfigTargets.Target("iris.", instance.iris, PropertiesConfigPatcher::stage, PropertiesConfigPatcher::readValues));
	}

	private List<Recommendation> selection(String name) throws IOException {
		return switch (name) {
			case "everything applies" -> everythingApplies();
			case "refusals and edge cases" -> refusalsAndEdgeCases();
			case "items in one batch" -> itemsInOneBatch();
			default -> throw new IllegalArgumentException(name);
		};
	}

	private List<Recommendation> everythingApplies() throws IOException {
		modrinth.put("lithium", version("lithV", "LITHIUM", "lithium-1.0.jar", required("FAPI")), "lithium");
		modrinth.put("fabric-api", version("fapiV", "FAPI", "fabric-api-1.0.jar"), "fabric-api");
		return List.of(
				setting("vanilla.renderDistance", "12", "8"),
				setting("sodium.quality.weather_quality", "FANCY", "FAST"),
				setting("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
				update("sodium", "SODIUM", "sodium-0.5.jar", "sodium-0.6.jar"),
				disable("oldmod", TestJars.modJar(instance.mods.resolve("old-mod.jar"), "oldmod")),
				add("lithium", "LITHIUM", "Lithium"));
	}

	private List<Recommendation> refusalsAndEdgeCases() throws IOException {
		Path other = TestJars.modJar(instance.mods.resolve("other.jar"), "other");
		Files.writeString(instance.mods.resolve("other.jar.disabled"), "an earlier disable");
		Recommendation takenUpdate = update("m", "M", "m-1.jar", "m-2.jar");
		Files.writeString(instance.mods.resolve("m-2.jar"), "someone else's file");
		modrinth.put("lithium", version("lithV", "LITHIUM", "lithium-1.0.jar", required("FAPI")), "lithium");
		Files.writeString(instance.mods.resolve("lithium-1.0.jar"), "installed by hand");
		installedProjects.add("FAPI");
		return List.of(
				setting("vanilla.maxFps", "60", "120"),
				setting("sodium.performance.chunk_builder_threads", "0", "fast"),
				setting("sodium.advanced.use_persistent_mapping", "", "true"),
				setting("dh.client.advanced.graphics.quality.verticalQuality", "HIGH", "LOW"),
				setting("iris.maxShadowRenderDistance", "32", "16"),
				setting("iris.noSuchKey", "", "1"),
				disable("other", other),
				disable("elsewhere", TestJars.modJar(dir.resolve("shared/elsewhere.jar"), "elsewhere")),
				takenUpdate,
				add("lithium", "LITHIUM", "Lithium"),
				PreviewFixtures.rec("advice:ram", new Action.None()));
	}

	private List<Recommendation> itemsInOneBatch() throws IOException {
		modrinth.put("lithium", version("lithV", "LITHIUM", "lithium-1.0.jar", required("FAPI")), "lithium");
		modrinth.put("modmenu", version("mmV", "MODMENU", "modmenu-1.0.jar", required("FAPI")), "modmenu");
		modrinth.put("fabric-api", version("fapiV", "FAPI", "fabric-api-1.0.jar"), "fabric-api");
		modrinth.put("krypton", version("kryV", "KRYPTON", "krypton-1.0.jar"), "krypton");
		modrinth.put("sodium-extra", version("sexV", "SEXTRA", "sodium-extra-1.0.jar", required("SODIUM")), "sodium-extra");
		conflicts = (a, b) -> Set.of(a, b).equals(Set.of("lithium", "krypton"));
		return List.of(
				add("krypton", "KRYPTON", "Krypton"),
				add("lithium", "LITHIUM", "Lithium"),
				add("modmenu", "MODMENU", "Mod Menu"),
				add("sodium-extra", "SEXTRA", "Sodium Extra"),
				update("sodium", "SODIUM", "sodium-0.5.jar", "sodium-0.6.jar"));
	}

	private Recommendation update(String modId, String projectId, String current, String next) throws IOException {
		Path installed = TestJars.modJar(instance.mods.resolve(current), modId);
		ModrinthVersion v = version(projectId + "-next", projectId, next);
		updateVersions.put(v.id(), v);
		modrinth.modIdByFile.put(next, modId);
		UpdateInfo info = new UpdateInfo(modId, projectId, "1", v.id(), "2", new ModFile("https://cdn/" + next, next, "sha512", 10));
		return new Recommendation("update:" + modId, Category.UPDATE_MOD, Impact.LOW, "Update " + modId, "", new Action.UpdateMod(modId, installed, info), true);
	}

	private static Recommendation disable(String modId, Path file) {
		return new Recommendation("disable:" + modId, Category.REMOVE_MOD, Impact.MEDIUM, "Disable " + modId, "", new Action.DisableMod(modId, file), true);
	}

	private static Recommendation add(String slug, String projectId, String title) {
		return new Recommendation("add:" + slug, Category.ADD_MOD, Impact.HIGH, "Install " + title, "", new Action.AddMod(slug, projectId, title), true);
	}

	private ApplyPreview preview(List<Recommendation> selected) {
		List<PreviewPlanner.ConfigFile> files = targets.stream()
				.map(t -> new PreviewPlanner.ConfigFile(t.prefix(), t.file(), t.stager()::stage, t.reader()::read)).toList();
		DownloadInputs inputs = new DownloadInputs(modrinth, true, "fabric", "26.2", Map.of(), updateVersions, installedProjects, loadedIds, Map.of(),
				conflicts);
		return new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), files, instance.mods, inputs).preview(selected);
	}

	private record Staged(Staging staging, Set<String> refused) {
	}

	// RealController.apply's staging, step by step with the same classes (its vanilla write needs the game's Options).
	// refused: the items the real staging turned down (a config value its stager refused, a download the planner refused).
	private Staged stageLikeApply(List<Recommendation> selected) {
		Journal journal = new Journal(instance.config, "0.3.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		Staging staging = new Staging(instance.config, PendingActions.defaultPath(instance.config), targets, journal);
		Map<ConfigTargets.Target, Map<String, String>> configPatches = new LinkedHashMap<>();
		Map<String, String> configIds = new LinkedHashMap<>();
		List<Op> immediateOps = new ArrayList<>();
		List<Recommendation> downloads = new ArrayList<>();
		for (Recommendation r : selected) {
			switch (r.action()) {
				case Action.SetSetting set when set.key().startsWith("vanilla.") -> {
				}
				case Action.SetSetting set when ConfigTargets.forKey(targets, set.key()) != null -> {
					ConfigTargets.Target target = ConfigTargets.forKey(targets, set.key());
					configPatches.computeIfAbsent(target, t -> new LinkedHashMap<>()).put(set.key().substring(target.prefix().length()), set.newValue());
					configIds.put(set.key(), r.id());
				}
				case Action.DisableMod disable when SafeFileNames.isDirectChild(instance.mods, disable.file()) -> immediateOps.add(Op.disableFile(disable.file()));
				case Action.AddMod ignored -> downloads.add(r);
				case Action.UpdateMod ignored -> downloads.add(r);
				default -> {
				}
			}
		}
		Set<String> refused = new HashSet<>();
		configPatches.forEach((target, patches) -> {
			SodiumConfigPatcher.Staged staged = target.stager().stage(target.file(), patches);
			staged.refused().keySet().forEach(key -> refused.add(configIds.get(target.prefix() + key)));
			immediateOps.addAll(0, staged.ops());
		});
		if (!immediateOps.isEmpty()) {
			assertNotNull(staging.stage(immediateOps, "apply-1"));
		}
		DownloadPlanner planner = new DownloadPlanner(new DependencyResolver(modrinth, "fabric", "26.2", Map.of()), instance.mods, file -> {
			Path pending = SafeFileNames.resolveJar(instance.mods, file.filename(), PendingActions.PENDING_SUFFIX);
			modrinth.download(file, pending);
			return pending;
		}, conflicts, updateVersions);
		DownloadPlanner.Result result = planner.plan(downloads, installedProjects, loadedIds, Map.of());
		downloads.stream().map(Recommendation::id).filter(id -> !result.ids().contains(id)).forEach(refused::add);
		if (!result.ops().isEmpty()) {
			assertNotNull(staging.stage(result.ops(), "apply-1"));
		}
		return new Staged(staging, refused);
	}

	@Test
	void whenEverythingApplies() throws IOException {
		check("everything applies");
	}

	@Test
	void withRefusalsAndEdgeCases() throws IOException {
		ApplyPreview preview = check("refusals and edge cases");

		Map<String, ApplyPreview.Reason> reasons = new LinkedHashMap<>();
		preview.skipped().forEach(skip -> reasons.put(skip.recommendationId(), skip.reason()));
		assertEquals(Map.of("setting:vanilla.maxFps", ApplyPreview.Reason.UNCHANGED,
				"setting:sodium.performance.chunk_builder_threads", ApplyPreview.Reason.REFUSED,
				"setting:iris.noSuchKey", ApplyPreview.Reason.REFUSED,
				"disable:elsewhere", ApplyPreview.Reason.OUTSIDE_MODS,
				"update:m", ApplyPreview.Reason.DOWNLOAD_FAILED,
				"add:lithium", ApplyPreview.Reason.NO_NEW_FILES,
				"advice:ram", ApplyPreview.Reason.NOTHING_TO_APPLY), reasons);
		assertEquals(List.of("other.jar.disabled.1"), preview.disables().stream().map(d -> d.disabledAs().getFileName().toString()).toList());
	}

	@Test
	void withItemsThatMeetInOneBatch() throws IOException {
		ApplyPreview preview = check("items in one batch");

		// docs/v0.4/SPEC.md 2e: the two conflicting additions are refused together, whatever the tick order.
		assertEquals(List.of("sodium-0.6.jar", "modmenu-1.0.jar", "fabric-api-1.0.jar", "sodium-extra-1.0.jar"),
				preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertEquals(List.of("add:krypton", "add:lithium"), preview.skipped().stream().map(ApplyPreview.Skipped::recommendationId).toList());
	}

	private ApplyPreview check(String name) throws IOException {
		List<Recommendation> selected = selection(name);
		TreeMap<String, String> before = instance.tree();

		ApplyPreview preview = preview(selected);

		assertEquals(before, instance.tree(), "the preview wrote nothing");
		assertFalse(modrinth.downloaded(), "the preview downloaded nothing");

		Staged staged = stageLikeApply(selected);
		Path pendingFile = staged.staging().pendingFile();

		// Not changed: exactly what the real staging refused, plus what it passes over without a word (an unchanged
		// value is still staged but leaves the file as it is; see the helper's run below).
		assertEquals(staged.refused(), preview.skipped().stream()
				.filter(s -> s.reason() == ApplyPreview.Reason.REFUSED || s.reason() == ApplyPreview.Reason.DOWNLOAD_FAILED)
				.map(ApplyPreview.Skipped::recommendationId).collect(Collectors.toSet()), name);
		// Downloaded now: exactly the preview's jars, as .rigtune-pending files.
		assertEquals(names(preview.downloads().stream().map(d -> d.fileName() + PendingActions.PENDING_SUFFIX)),
				changedSince(before).stream().filter(p -> p.endsWith(PendingActions.PENDING_SUFFIX)).map(p -> p.substring("mods/".length()))
						.collect(Collectors.toCollection(TreeSet::new)), name);
		// Named by pending.json's ops: the preview's files at restart, less the .disabled names the helper picks then.
		TreeSet<String> named = new TreeSet<>();
		PendingActions plan = Files.exists(pendingFile) ? PendingActions.load(pendingFile) : null;
		if (plan != null) {
			for (Op op : plan.ops()) {
				named.add(relative(Path.of(op.type() == PendingActions.Type.ENABLE_FILE ? op.to() : op.path())));
			}
		}
		TreeSet<String> expectedNamed = relative(preview.filesAtRestart());
		preview.disables().forEach(d -> expectedNamed.remove(relative(d.disabledAs())));
		assertEquals(expectedNamed, named, name);

		// After the helper's executor: every file created, renamed away or changed is one the preview listed.
		if (plan != null) {
			ApplyResult result = new ApplyExecutor(2, 1).run(plan, pendingFile);
			assertTrue(result.results().stream().allMatch(r -> r.status() == ApplyResult.Status.OK), result.results().toString());
		}
		assertEquals(relative(preview.filesAtRestart()), changedSince(before), name);
		// Written now: every ticked vanilla value that differs from the game's, all in options.txt.
		Set<String> vanilla = new HashSet<>();
		for (Recommendation r : selected) {
			if (r.action() instanceof Action.SetSetting set && set.key().startsWith("vanilla.")
					&& !set.newValue().equals(PreviewFixtures.vanillaNow().get(set.key().substring("vanilla.".length())))) {
				vanilla.add(set.key().substring("vanilla.".length()));
			}
		}
		assertEquals(vanilla, preview.now().stream().map(ApplyPreview.Setting::key).collect(Collectors.toSet()), name);
		assertTrue(preview.now().stream().allMatch(s -> s.file().equals(instance.options)), name);
		return preview;
	}

	private TreeSet<String> changedSince(TreeMap<String, String> before) throws IOException {
		TreeMap<String, String> after = instance.tree();
		TreeSet<String> changed = new TreeSet<>();
		before.forEach((path, hash) -> {
			if (!hash.equals(after.get(path))) {
				changed.add(path);
			}
		});
		after.keySet().stream().filter(path -> !before.containsKey(path)).forEach(changed::add);
		changed.removeIf(path -> path.startsWith("config/rigtune/"));
		return changed;
	}

	private String relative(Path file) {
		return ApplyPreview.relative(instance.game, file);
	}

	private TreeSet<String> relative(Set<Path> files) {
		return files.stream().map(this::relative).collect(Collectors.toCollection(TreeSet::new));
	}

	private static TreeSet<String> names(Stream<String> names) {
		return names.collect(Collectors.toCollection(TreeSet::new));
	}
}
