package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthFile;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.modrinth.StagedProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.required;
import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.version;
import static org.junit.jupiter.api.Assertions.assertEquals;

// The preview's "Disable X" items. docs/v0.5/SPEC.md 2H L9 (AC2H.4, the same batch): a ticked Disable of a mod counts as
// staged for disabling, so an addition ticked with it that needs that mod is refused in the preview as Apply refuses it
// (Apply stages its disables before its downloads). 2V (AC2V.3): one DisableGuard refuses is under "Not changed".
class PreviewDisablesTest {
	@TempDir
	Path dir;
	PreviewFixtures instance;
	final PreviewFakeModrinth modrinth = new PreviewFakeModrinth();
	Path lib;
	Map<String, ModrinthVersion> installed;

	@BeforeEach
	void setUp() throws Exception {
		instance = new PreviewFixtures(dir.resolve("game"));
		lib = TestJars.modJar(instance.mods.resolve("lib-1.jar"), "lib");
		String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(lib)));
		installed = Map.of("lib1", new ModrinthVersion("lib1", "LIB", "1", "release", List.of("26.2"), List.of("fabric"), PreviewFakeModrinth.T,
				List.of(new ModrinthFile("https://cdn/lib-1.jar", "lib-1.jar", sha1, "sha512-lib1", 10, true)), List.of()));
		modrinth.put("a", version("aV", "A", "a-1.0.jar", required("LIB")), "a");
	}

	private ApplyPreview preview(Recommendation... selected) {
		DownloadInputs inputs = new DownloadInputs(modrinth, true, "fabric", "26.2", installed, Map.of(), Set.of("LIB"), Set.of("lib"), Map.of(),
				(a, b) -> false, StagedProjects.NONE, true);
		return new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), instance.configFiles(), instance.mods, inputs).preview(List.of(selected));
	}

	private static Recommendation add(String slug, String projectId) {
		return new Recommendation("add:" + slug, Category.ADD_MOD, Impact.HIGH, "Install " + slug, "", new Action.AddMod(slug, projectId, slug), true);
	}

	private Recommendation disableLib() {
		return new Recommendation("disable:lib", Category.REMOVE_MOD, Impact.LOW, "Disable lib", "", new Action.DisableMod("lib", lib), true);
	}

	@Test
	void anAdditionTickedWithADisableOfWhatItNeedsIsRefused() {
		ApplyPreview preview = preview(disableLib(), add("a", "A"));

		assertEquals(List.of("disable:lib"), preview.disables().stream().map(ApplyPreview.Disable::recommendationId).toList());
		assertEquals(List.of(), preview.downloads());
		assertEquals("it needs LIB, which is being turned off at the next restart", preview.skipped().getFirst().detail());
	}

	@Test
	void withoutTheDisableTheAdditionIsListed() {
		ApplyPreview preview = preview(add("a", "A"));

		assertEquals(List.of("a-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
	}

	// --- docs/v0.5/SPEC.md 2V (ws-g2, AC2V.3): a "Disable X" DisableGuard refuses is under "Not changed" with its reason,
	// as Apply leaves it out (RealController.apply stages only DisableGuard.allowed's items); the others are renamed.

	@Test
	void aRefusedDisableIsNotChangedWithDisableGuardsReason() throws Exception {
		Path other = TestJars.modJar(instance.mods.resolve("other-1.jar"), "other");
		Recommendation disableOther = new Recommendation("disable:other", Category.REMOVE_MOD, Impact.LOW, "Disable other", "",
				new Action.DisableMod("other", other), true);
		io.github.chaotix345.rigtune.core.model.Text needed = io.github.chaotix345.rigtune.core.model.Text.of("rigtune.toast.disable_refused.needed",
				"The game wouldn't start without it: %s", "a needs lib");
		List<List<String>> asked = new java.util.ArrayList<>();
		DownloadInputs inputs = new DownloadInputs(modrinth, true, "fabric", "26.2", installed, Map.of(), Set.of("LIB"), Set.of("lib"), Map.of(),
				(a, b) -> false, StagedProjects.NONE, true);

		ApplyPreview preview = new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), instance.configFiles(), instance.mods, inputs)
				.withDisableRefusals(files -> {
					asked.add(files);
					return Map.of("lib-1.jar", needed);
				}).preview(List.of(disableLib(), disableOther));

		assertEquals(List.of(List.of("lib-1.jar", "other-1.jar")), asked, "all of the Apply's disables, checked together as Apply checks them");
		assertEquals(List.of("disable:other"), preview.disables().stream().map(ApplyPreview.Disable::recommendationId).toList());
		assertEquals(List.of(new ApplyPreview.Skipped("disable:lib", "Disable lib", ApplyPreview.Reason.REFUSED, needed.english(), disableLib().titleText(),
				needed)), preview.skipped());
	}

	// A refused disable isn't staged, so it doesn't count as a staged disable for the additions either (L9).
	@Test
	void aRefusedDisableDoesNotTurnItsModOffForTheAdditions() {
		DownloadInputs inputs = new DownloadInputs(modrinth, true, "fabric", "26.2", installed, Map.of(), Set.of("LIB"), Set.of("lib"), Map.of(),
				(a, b) -> false, StagedProjects.NONE, true);

		ApplyPreview preview = new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), instance.configFiles(), instance.mods, inputs)
				.withDisableRefusals(files -> Map.of("lib-1.jar", io.github.chaotix345.rigtune.core.model.Text.literal("needed")))
				.preview(List.of(disableLib(), add("a", "A")));

		assertEquals(List.of("a-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertEquals(List.of(), preview.disables());
	}
}
