package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.required;
import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 13: mod files through the real DownloadPlanner and DependencyResolver, run dry.
class PreviewDownloadsTest {
	@TempDir
	Path dir;
	PreviewFixtures instance;
	final PreviewFakeModrinth modrinth = new PreviewFakeModrinth();
	final Map<String, ModrinthVersion> updateVersions = new HashMap<>();
	final Set<String> installedProjects = new HashSet<>();
	BiPredicate<String, String> conflicts = (a, b) -> false;
	boolean lookups = true;

	@BeforeEach
	void setUp() throws IOException {
		instance = new PreviewFixtures(dir.resolve("game"));
	}

	private ApplyPreview preview(Recommendation... selected) {
		DownloadInputs inputs = new DownloadInputs(modrinth, lookups, "fabric", "26.2", Map.of(), updateVersions, installedProjects, Set.of("sodium"),
				Map.of(), conflicts);
		return new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), instance.configFiles(), instance.mods, inputs).preview(List.of(selected));
	}

	private Recommendation update() throws IOException {
		Path installed = TestJars.modJar(instance.mods.resolve("sodium-0.5.jar"), "sodium");
		updateVersions.put("sodV6", version("sodV6", "SODIUM", "sodium-0.6.jar"));
		UpdateInfo info = new UpdateInfo("sodium", "SODIUM", "0.5", "sodV6", "0.6", new ModFile("https://cdn/sodium-0.6.jar", "sodium-0.6.jar", "sha512", 10));
		return new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium", "", new Action.UpdateMod("sodium", installed, info), true);
	}

	private static Recommendation add(String slug, String projectId, String title) {
		return new Recommendation("add:" + slug, Category.ADD_MOD, Impact.HIGH, "Install " + title, "", new Action.AddMod(slug, projectId, title), true);
	}

	private void lithiumWithFabricApi() {
		modrinth.put("lithium", version("lithV", "LITHIUM", "lithium-1.0.jar", required("FAPI")), "lithium");
		modrinth.put("fabric-api", version("fapiV", "FAPI", "fabric-api-1.0.jar"), "fabric-api");
	}

	@Test
	void anUpdateDownloadsItsFileAndDisablesTheOldJar() throws IOException {
		ApplyPreview preview = preview(update());

		assertEquals(List.of(new ApplyPreview.Download("update:sodium", "Update Sodium", "sodium-0.6.jar", instance.mods.resolve("sodium-0.6.jar"), false)),
				preview.downloads());
		assertEquals(List.of(new ApplyPreview.Disable("update:sodium", "Update Sodium", instance.mods.resolve("sodium-0.5.jar"),
				instance.mods.resolve("sodium-0.5.jar.disabled"))), preview.disables());
		assertEquals(List.of(), preview.skipped());
		assertTrue(preview.resolved());
		assertFalse(modrinth.downloaded());
	}

	@Test
	void anAdditionListsItsFileAndItsRequiredDependency() {
		lithiumWithFabricApi();

		ApplyPreview preview = preview(add("lithium", "LITHIUM", "Lithium"));

		assertEquals(List.of(
				new ApplyPreview.Download("add:lithium", "Install Lithium", "lithium-1.0.jar", instance.mods.resolve("lithium-1.0.jar"), false),
				new ApplyPreview.Download("add:lithium", "Install Lithium", "fabric-api-1.0.jar", instance.mods.resolve("fabric-api-1.0.jar"), true)),
				preview.downloads());
		assertEquals(List.of("latestVersion:LITHIUM", "latestVersion:FAPI"), modrinth.calls);
	}

	@Test
	void anAdditionFoundBySlugKnowsItsOwnFile() {
		lithiumWithFabricApi();

		ApplyPreview preview = preview(add("lithium", null, "Lithium"));

		assertEquals(List.of(false, true), preview.downloads().stream().map(ApplyPreview.Download::dependency).toList());
	}

	@Test
	void aDependencyAnEarlierAdditionBringsIsListedOnce() {
		lithiumWithFabricApi();
		modrinth.put("modmenu", version("mmV", "MODMENU", "modmenu-1.0.jar", required("FAPI")), "modmenu");

		ApplyPreview preview = preview(add("lithium", "LITHIUM", "Lithium"), add("modmenu", "MODMENU", "Mod Menu"));

		assertEquals(List.of("lithium-1.0.jar", "fabric-api-1.0.jar", "modmenu-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertEquals(List.of("add:lithium", "add:lithium", "add:modmenu"), preview.downloads().stream().map(ApplyPreview.Download::recommendationId).toList());
		assertEquals(List.of(), preview.skipped());
	}

	@Test
	void anAdditionWhoseFilesAreAlreadyThereHasNoNewFiles() throws IOException {
		lithiumWithFabricApi();
		Files.writeString(instance.mods.resolve("lithium-1.0.jar"), "installed by hand");
		installedProjects.add("FAPI");

		ApplyPreview preview = preview(add("lithium", "LITHIUM", "Lithium"));

		assertEquals(List.of(), preview.downloads());
		assertEquals(List.of(new ApplyPreview.Skipped("add:lithium", "Install Lithium", ApplyPreview.Reason.NO_NEW_FILES, null)), preview.skipped());
	}

	@Test
	void aRefusedDownloadIsSkippedWithThePlannersReason() throws IOException {
		Recommendation update = update();
		Files.writeString(instance.mods.resolve("sodium-0.6.jar"), "someone else's file");

		ApplyPreview preview = preview(update);

		assertEquals(List.of(), preview.downloads());
		assertEquals(List.of(), preview.disables());
		assertEquals(List.of(new ApplyPreview.Skipped("update:sodium", "Update Sodium", ApplyPreview.Reason.DOWNLOAD_FAILED,
				"sodium-0.6.jar is already in the mods folder")), preview.skipped());
	}

	@Test
	void theLaterOfTwoConflictingAdditionsIsRefused() {
		lithiumWithFabricApi();
		modrinth.put("krypton", version("kryV", "KRYPTON", "krypton-1.0.jar"), "krypton");
		conflicts = (a, b) -> Set.of(a, b).equals(Set.of("lithium", "krypton"));

		ApplyPreview preview = preview(add("lithium", "LITHIUM", "Lithium"), add("krypton", "KRYPTON", "Krypton"));

		assertEquals(List.of("lithium-1.0.jar", "fabric-api-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		ApplyPreview.Skipped refused = preview.skipped().getFirst();
		assertEquals("add:krypton", refused.recommendationId());
		assertEquals(ApplyPreview.Reason.DOWNLOAD_FAILED, refused.reason());
		assertTrue(refused.detail().startsWith("it conflicts with Lithium"), refused.detail());
	}

	// Review WS-P #3: a file fetched again after a failed item keeps its stand-in mod id, so a later new file isn't taken for
	// a mod that's already there.
	@Test
	void aFileFetchedAgainAfterAFailedItemKeepsLaterFilesApart() throws IOException {
		modrinth.put("fabric-api", version("fapiV", "FAPI", "fabric-api-1.0.jar"), "fabric-api");
		modrinth.put("cloth", version("clothV", "CLOTH", "cloth-1.0.jar"), "cloth");
		ModrinthVersion noFile = new ModrinthVersion("brokenV", "BROKEN", "0.1", "release", List.of("26.2"), List.of("fabric"), PreviewFakeModrinth.T,
				List.of(), List.of());
		modrinth.latest.put("BROKEN", noFile);
		modrinth.put("x", version("xV", "X", "x-1.0.jar", required("FAPI"), required("BROKEN")), "x");
		modrinth.put("y", version("yV", "Y", "y-1.0.jar", required("FAPI"), required("CLOTH")), "y");
		Files.writeString(instance.mods.resolve("y-1.0.jar"), "installed by hand");

		ApplyPreview preview = preview(add("x", "X", "X"), add("y", "Y", "Y"));

		assertEquals(List.of("add:x"), preview.skipped().stream().map(ApplyPreview.Skipped::recommendationId).toList());
		assertEquals(List.of("fabric-api-1.0.jar", "cloth-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
	}

	@Test
	void anAdditionModrinthCantResolveIsSkipped() {
		ApplyPreview preview = preview(add("nothing", "NOTHING", "Nothing"));

		assertEquals(ApplyPreview.Reason.DOWNLOAD_FAILED, preview.skipped().getFirst().reason());
		assertEquals("No fabric version of NOTHING for Minecraft 26.2", preview.skipped().getFirst().detail());
	}

	@Test
	void withLookupsOffAnAdditionIsAFileFromModrinthAndNothingIsAskedOfModrinth() throws IOException {
		lithiumWithFabricApi();
		lookups = false;

		ApplyPreview preview = preview(add("lithium", "LITHIUM", "Lithium"), update());

		assertEquals(List.of(
				new ApplyPreview.Download("update:sodium", "Update Sodium", "sodium-0.6.jar", instance.mods.resolve("sodium-0.6.jar"), false),
				new ApplyPreview.Download("add:lithium", "Install Lithium", null, null, false)), preview.downloads());
		assertEquals(1, preview.disables().size());
		assertFalse(preview.resolved());
		assertEquals(List.of(), modrinth.calls);
	}

	@Test
	void downloadsWriteNothing() throws IOException {
		lithiumWithFabricApi();
		Recommendation update = update();
		var before = instance.tree();

		preview(update, add("lithium", "LITHIUM", "Lithium"));

		assertEquals(before, instance.tree());
		assertFalse(modrinth.downloaded());
	}
}
