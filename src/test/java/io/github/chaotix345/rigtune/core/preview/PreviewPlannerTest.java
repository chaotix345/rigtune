package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.preview.PreviewFixtures.rec;
import static io.github.chaotix345.rigtune.core.preview.PreviewFixtures.setting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 13: the preview of settings, config patches and disables, from the same partition and
// stagers Apply uses.
class PreviewPlannerTest {
	@TempDir
	Path dir;
	PreviewFixtures instance;

	@BeforeEach
	void setUp() throws IOException {
		instance = new PreviewFixtures(dir.resolve("game"));
	}

	private ApplyPreview preview(Recommendation... selected) {
		return new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), instance.configFiles(), instance.mods, null).preview(List.of(selected));
	}

	@Test
	void vanillaSettingsAreWrittenNowToOptionsTxt() {
		ApplyPreview preview = preview(setting("vanilla.renderDistance", "12", "8"));

		assertEquals(List.of(new ApplyPreview.Setting("setting:vanilla.renderDistance", instance.options, "renderDistance", "12", "8")), preview.now());
		assertEquals(List.of(), preview.atRestart());
		assertEquals(Set.of(instance.options), preview.filesNow());
		assertEquals(Set.of(), preview.filesAtRestart());
		assertFalse(preview.isEmpty());
	}

	@Test
	void aVanillaSettingRigTuneDoesntChangeIsSkipped() {
		ApplyPreview preview = preview(setting("vanilla.fov", "70", "90"), setting("vanilla.maxFps", "120", "60\u0007"));

		assertEquals(List.of(), preview.now());
		assertEquals(List.of(ApplyPreview.Reason.NOT_CHANGEABLE, ApplyPreview.Reason.REFUSED), preview.skipped().stream().map(ApplyPreview.Skipped::reason).toList());
		assertEquals("Value contains control characters", preview.skipped().get(1).detail());
		assertTrue(preview.isEmpty());
	}

	@Test
	void aVanillaSettingTheGameDoesntHaveIsSkipped() {
		ApplyPreview preview = preview(setting("vanilla.simulationDistance", "12", "8"));

		assertEquals(List.of(new ApplyPreview.Skipped("setting:vanilla.simulationDistance", "Title of setting:vanilla.simulationDistance",
				ApplyPreview.Reason.UNKNOWN_SETTING, null)), preview.skipped());
	}

	@Test
	void aValueAlreadySetIsSkipped() {
		ApplyPreview preview = preview(setting("vanilla.maxFps", "60", "120"), setting("sodium.performance.chunk_builder_threads", "4", "0"));

		assertEquals(List.of(), preview.now());
		assertEquals(List.of(), preview.atRestart());
		assertEquals(List.of(ApplyPreview.Reason.UNCHANGED, ApplyPreview.Reason.UNCHANGED), preview.skipped().stream().map(ApplyPreview.Skipped::reason).toList());
	}

	@Test
	void sodiumDhAndIrisKeysArePatchedAtRestart() {
		ApplyPreview preview = preview(
				setting("sodium.quality.weather_quality", "FANCY", "FAST"),
				setting("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
				setting("iris.maxShadowRenderDistance", "32", "16"));

		assertEquals(List.of(
				new ApplyPreview.Setting("setting:sodium.quality.weather_quality", instance.sodium, "quality.weather_quality", "FANCY", "FAST"),
				new ApplyPreview.Setting("setting:dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", instance.dh,
						"client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
				new ApplyPreview.Setting("setting:iris.maxShadowRenderDistance", instance.iris, "maxShadowRenderDistance", "32", "16")), preview.atRestart());
		assertEquals(Set.of(instance.sodium, instance.dh, instance.iris), preview.filesAtRestart());
		assertEquals(List.of(), preview.now());
	}

	// The old value is the file's, as it is now (what Staging journals as "before"), not the report's.
	@Test
	void theOldValueIsReadFromTheFileNow() throws IOException {
		Files.writeString(instance.sodium, "{\"quality\":{\"weather_quality\":\"FABULOUS\"}}");

		ApplyPreview preview = preview(setting("sodium.quality.weather_quality", "FANCY", "FAST"));

		assertEquals("FABULOUS", preview.atRestart().getFirst().oldValue());
	}

	@Test
	void aNewSodiumFieldHasNoOldValue() {
		ApplyPreview preview = preview(setting("sodium.advanced.use_persistent_mapping", "", "true"));

		assertEquals(List.of(new ApplyPreview.Setting("setting:sodium.advanced.use_persistent_mapping", instance.sodium, "advanced.use_persistent_mapping", null, "true")),
				preview.atRestart());
	}

	@Test
	void aValueTheFileRefusesIsSkippedWithThePatchersReason() {
		ApplyPreview preview = preview(setting("sodium.performance.chunk_builder_threads", "0", "fast"), setting("iris.noSuchKey", "", "1"));

		assertEquals(List.of(), preview.atRestart());
		assertEquals(2, preview.skipped().size());
		ApplyPreview.Skipped sodium = preview.skipped().get(0);
		assertEquals(ApplyPreview.Reason.REFUSED, sodium.reason());
		assertTrue(sodium.detail().contains("it expects a number"), sodium.detail());
		assertEquals("setting:sodium.performance.chunk_builder_threads", sodium.recommendationId());
		assertEquals("no such key in iris.properties", preview.skipped().get(1).detail());
	}

	@Test
	void aDisableRenamesTheJarAtRestart() throws IOException {
		Path jar = TestJars.modJar(instance.mods.resolve("old-mod.jar"), "oldmod");
		Path taken = TestJars.modJar(instance.mods.resolve("other.jar"), "other");
		Files.writeString(instance.mods.resolve("other.jar.disabled"), "an earlier disable");

		ApplyPreview preview = preview(rec("disable:oldmod", new Action.DisableMod("oldmod", jar)), rec("disable:other", new Action.DisableMod("other", taken)));

		assertEquals(List.of(
				new ApplyPreview.Disable("disable:oldmod", "Title of disable:oldmod", jar, instance.mods.resolve("old-mod.jar.disabled")),
				new ApplyPreview.Disable("disable:other", "Title of disable:other", taken, instance.mods.resolve("other.jar.disabled.1"))), preview.disables());
		assertEquals(Set.of(jar, instance.mods.resolve("old-mod.jar.disabled"), taken, instance.mods.resolve("other.jar.disabled.1")),
				preview.filesAtRestart());
	}

	@Test
	void aDisableOutsideTheModsFolderIsSkipped() throws IOException {
		Path elsewhere = TestJars.modJar(dir.resolve("shared/elsewhere.jar"), "elsewhere");

		ApplyPreview preview = preview(rec("disable:elsewhere", new Action.DisableMod("elsewhere", elsewhere)));

		assertEquals(List.of(), preview.disables());
		assertEquals(ApplyPreview.Reason.OUTSIDE_MODS, preview.skipped().getFirst().reason());
	}

	@Test
	void anAdviceOrAnUnknownNamespaceIsSkipped() {
		ApplyPreview preview = preview(rec("advice:ram", new Action.None()), setting("lithium.mixin.x", "true", "false"));

		assertEquals(List.of(ApplyPreview.Reason.NOTHING_TO_APPLY, ApplyPreview.Reason.NOTHING_TO_APPLY),
				preview.skipped().stream().map(ApplyPreview.Skipped::reason).toList());
	}

	@Test
	void thePreviewWritesNothing() throws IOException {
		Path jar = TestJars.modJar(instance.mods.resolve("old-mod.jar"), "oldmod");
		var before = instance.tree();

		preview(setting("vanilla.renderDistance", "12", "8"),
				setting("sodium.quality.weather_quality", "FANCY", "FAST"),
				setting("sodium.performance.chunk_builder_threads", "0", "fast"),
				setting("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256", "128"),
				setting("iris.maxShadowRenderDistance", "32", "16"),
				rec("disable:oldmod", new Action.DisableMod("oldmod", jar)));

		assertEquals(before, instance.tree());
	}

	@Test
	void relativeShowsPathsFromTheGameFolderWithForwardSlashes() {
		Path game = dir.resolve("game");

		assertEquals("config/sodium-options.json", ApplyPreview.relative(game, game.resolve("config").resolve("sodium-options.json")));
		assertEquals("options.txt", ApplyPreview.relative(game, game.resolve("options.txt")));
		assertEquals("elsewhere.jar", ApplyPreview.relative(game, dir.resolve("shared").resolve("elsewhere.jar")));
	}

	@Test
	void anEmptySelectionIsEmpty() {
		ApplyPreview preview = preview();

		assertTrue(preview.isEmpty());
		assertEquals(ApplyPreview.EMPTY, preview);
	}
}
