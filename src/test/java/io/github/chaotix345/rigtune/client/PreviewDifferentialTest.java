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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.required;
import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.version;
import static io.github.chaotix345.rigtune.core.preview.PreviewFixtures.setting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC13.1 (docs/v0.3/SPEC.md item 13): for a selection of every kind, the preview's files equal the files the real
// staging then touches in a temp instance: the downloads it makes now, the files pending.json's ops name, and, after
// the helper's executor has run the plan, every file that was created, renamed or changed.
class PreviewDifferentialTest {
	private static final BiPredicate<String, String> NO_CONFLICTS = (a, b) -> false;

	@TempDir
	Path dir;
	PreviewFixtures instance;
	List<ConfigTargets.Target> targets;
	final PreviewFakeModrinth modrinth = new PreviewFakeModrinth();
	final Map<String, ModrinthVersion> updateVersions = new LinkedHashMap<>();
	final Set<String> installedProjects = Set.of("SODIUM");
	final Set<String> loadedIds = Set.of("sodium", "oldmod");

	@BeforeEach
	void setUp() throws IOException {
		instance = new PreviewFixtures(dir.resolve("game"));
		targets = List.of(
				new ConfigTargets.Target("sodium.", instance.sodium, SodiumConfigPatcher::stage, PreviewFixtures::readSodium),
				new ConfigTargets.Target("dh.", instance.dh, TomlConfigPatcher::stage, TomlConfigPatcher::readValues),
				new ConfigTargets.Target("iris.", instance.iris, PropertiesConfigPatcher::stage, PropertiesConfigPatcher::readValues));
	}

	private List<Recommendation> selection() throws IOException {
		Path installed = TestJars.modJar(instance.mods.resolve("sodium-0.5.jar"), "sodium");
		Path oldMod = TestJars.modJar(instance.mods.resolve("old-mod.jar"), "oldmod");
		ModrinthVersion sodium6 = version("sodV6", "SODIUM", "sodium-0.6.jar");
		updateVersions.put(sodium6.id(), sodium6);
		modrinth.modIdByFile.put("sodium-0.6.jar", "sodium");
		modrinth.put("lithium", version("lithV", "LITHIUM", "lithium-1.0.jar", required("FAPI")), "lithium");
		modrinth.put("fabric-api", version("fapiV", "FAPI", "fabric-api-1.0.jar"), "fabric-api");
		UpdateInfo info = new UpdateInfo("sodium", "SODIUM", "0.5", "sodV6", "0.6", new ModFile("https://cdn/sodium-0.6.jar", "sodium-0.6.jar", "sha512", 10));
		return List.of(
				setting("vanilla.renderDistance", "12", "8"),
				setting("sodium.quality.weather_quality", "FANCY", "FAST"),
				setting("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
				new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium", "", new Action.UpdateMod("sodium", installed, info), true),
				new Recommendation("disable:oldmod", Category.REMOVE_MOD, Impact.MEDIUM, "Disable Old Mod", "", new Action.DisableMod("oldmod", oldMod), true),
				new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Install Lithium", "", new Action.AddMod("lithium", "LITHIUM", "Lithium"), true));
	}

	private ApplyPreview preview(List<Recommendation> selected) {
		List<PreviewPlanner.ConfigFile> files = targets.stream()
				.map(t -> new PreviewPlanner.ConfigFile(t.prefix(), t.file(), t.stager()::stage, t.reader()::read)).toList();
		DownloadInputs inputs = new DownloadInputs(modrinth, true, "fabric", "26.2", Map.of(), updateVersions, installedProjects, loadedIds, Map.of(),
				NO_CONFLICTS);
		return new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), files, instance.mods, inputs).preview(selected);
	}

	// RealController.apply's staging, step by step with the same classes (its vanilla write needs the game's Options:
	// PreviewGameTest checks that half in game).
	private Staging stageLikeApply(List<Recommendation> selected) {
		Journal journal = new Journal(instance.config, "0.3.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		Staging staging = new Staging(instance.config, PendingActions.defaultPath(instance.config), targets, journal);
		Map<ConfigTargets.Target, Map<String, String>> configPatches = new LinkedHashMap<>();
		List<Op> immediateOps = new ArrayList<>();
		List<Recommendation> downloads = new ArrayList<>();
		for (Recommendation r : selected) {
			switch (r.action()) {
				case Action.SetSetting set when set.key().startsWith("vanilla.") -> {
				}
				case Action.SetSetting set when ConfigTargets.forKey(targets, set.key()) != null -> {
					ConfigTargets.Target target = ConfigTargets.forKey(targets, set.key());
					configPatches.computeIfAbsent(target, t -> new LinkedHashMap<>()).put(set.key().substring(target.prefix().length()), set.newValue());
				}
				case Action.DisableMod disable when SafeFileNames.isDirectChild(instance.mods, disable.file()) -> immediateOps.add(Op.disableFile(disable.file()));
				case Action.AddMod ignored -> downloads.add(r);
				case Action.UpdateMod ignored -> downloads.add(r);
				default -> {
				}
			}
		}
		configPatches.forEach((target, patches) -> immediateOps.addAll(0, target.stager().stage(target.file(), patches).ops()));
		assertNotNull(staging.stage(immediateOps, "apply-1"));
		DownloadPlanner planner = new DownloadPlanner(new DependencyResolver(modrinth, "fabric", "26.2", Map.of()), instance.mods, file -> {
			Path pending = SafeFileNames.resolveJar(instance.mods, file.filename(), PendingActions.PENDING_SUFFIX);
			modrinth.download(file, pending);
			return pending;
		}, NO_CONFLICTS, updateVersions);
		DownloadPlanner.Result result = planner.plan(downloads, installedProjects, loadedIds, Map.of());
		assertEquals(List.of(), result.errors());
		assertNotNull(staging.stage(result.ops(), "apply-1"));
		return staging;
	}

	@Test
	void thePreviewsFilesAreTheFilesTheRealStagingAndHelperTouch() throws IOException {
		List<Recommendation> selected = selection();
		TreeMap<String, String> before = instance.tree();

		ApplyPreview preview = preview(selected);

		assertEquals(before, instance.tree(), "the preview wrote nothing");
		assertFalse(modrinth.downloaded(), "the preview downloaded nothing");
		assertEquals(Set.of(instance.options), preview.filesNow());
		assertEquals(List.of("renderDistance"), preview.now().stream().map(ApplyPreview.Setting::key).toList());
		assertEquals(List.of(), preview.skipped());

		Staging staging = stageLikeApply(selected);

		// Downloaded now: exactly the preview's jars, as .rigtune-pending files.
		assertEquals(names(preview.downloads().stream().map(d -> d.fileName() + PendingActions.PENDING_SUFFIX)),
				changedSince(before).stream().filter(p -> p.endsWith(PendingActions.PENDING_SUFFIX)).map(p -> p.substring("mods/".length()))
						.collect(Collectors.toCollection(TreeSet::new)));
		// Named by pending.json's ops: the preview's files at restart, less the .disabled names the helper picks then.
		PendingActions plan = PendingActions.load(staging.pendingFile());
		TreeSet<String> named = new TreeSet<>();
		for (Op op : plan.ops()) {
			named.add(relative(Path.of(op.type() == PendingActions.Type.ENABLE_FILE ? op.to() : op.path())));
		}
		TreeSet<String> expectedNamed = relative(preview.filesAtRestart());
		preview.disables().forEach(d -> expectedNamed.remove(relative(d.disabledAs())));
		assertEquals(expectedNamed, named);

		// After the helper's executor: every file created, renamed away or changed is one the preview listed.
		ApplyResult result = new ApplyExecutor(2, 1).run(plan, staging.pendingFile());
		assertTrue(result.results().stream().allMatch(r -> r.status() == ApplyResult.Status.OK), result.results().toString());
		assertEquals(relative(preview.filesAtRestart()), changedSince(before));
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

	private static TreeSet<String> names(java.util.stream.Stream<String> names) {
		return names.collect(Collectors.toCollection(TreeSet::new));
	}
}
