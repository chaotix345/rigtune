package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.TextChecks;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

// docs/v0.3/SPEC.md item 9 (AC9.3): the planner's refusals the other tests don't reach, and a download's own failure.
class DownloadErrorTextTest {
	private static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

	@TempDir
	Path dir;

	private DownloadPlanner.Result plan(FakeModrinthClient client, Map<String, ModrinthVersion> updates, Recommendation rec) throws IOException {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		DownloadPlanner planner = new DownloadPlanner(new DependencyResolver(client, "fabric", "26.2"), mods, file -> {
			throw new IOException("boom");
		}, (a, b) -> false, updates);
		return planner.plan(List.of(rec), Set.of(), Set.of(), Map.of());
	}

	private static Text.Translatable cause(DownloadPlanner.Result result) {
		assertEquals(1, result.errorTexts().size(), result.errors().toString());
		Text.Translatable error = assertInstanceOf(Text.Translatable.class, result.errorTexts().getFirst());
		assertEquals("rigtune.download.error", error.key());
		assertEquals(result.errors().getFirst(), error.english());
		TextChecks.assertPseudoLocalised(error, Set.of(), error.english());
		return assertInstanceOf(Text.Translatable.class, error.args().get(1));
	}

	private static Recommendation add(String slug) {
		return Recommendation.of("add:" + slug, Category.ADD_MOD, Impact.LOW, Text.of("rigtune.rec.install.title", "Install %s", slug),
				Text.literal(""), new Action.AddMod(slug, null, slug), true);
	}

	private Recommendation update(Path current, ModFile file) {
		return Recommendation.of("update:m", Category.UPDATE_MOD, Impact.LOW, Text.of("rigtune.rec.update.title", "Update %s", "m"), Text.literal(""),
				new Action.UpdateMod("m", current, new UpdateInfo("m", "M", "1", "v2", "2", file)), true);
	}

	@Test
	void noVersionForThisMinecraft() throws IOException {
		DownloadPlanner.Result result = plan(new FakeModrinthClient(), Map.of(), add("ghost"));
		assertEquals("rigtune.download.no_version", cause(result).key());
		assertEquals("Install ghost: No fabric version of ghost for Minecraft 26.2", result.errors().getFirst());
	}

	@Test
	void aVersionWithoutAFile() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		client.latestByProject.put("bare", new ModrinthVersion("b1", "B", "1.0", "release", List.of("26.2"), List.of("fabric"), T, List.of(), List.of()));
		DownloadPlanner.Result result = plan(client, Map.of(), add("bare"));
		assertEquals("rigtune.download.no_file", cause(result).key());
		assertEquals("Install bare: No file for 1.0", result.errors().getFirst());
	}

	@Test
	void anUpdateWithoutAFileOrOutsideTheModsFolder() throws IOException {
		assertEquals("rigtune.download.no_file", cause(plan(new FakeModrinthClient(), Map.of(), update(dir.resolve("mods/m.jar"), null))).key());
		DownloadPlanner.Result outside = plan(new FakeModrinthClient(), Map.of(), update(dir.resolve("elsewhere/m.jar"), new ModFile("u", "m-2.jar", "h", 1)));
		assertEquals("rigtune.download.outside_mods_folder", cause(outside).key());
		assertEquals("Update m: it isn't in this instance's mods folder; update it in your launcher", outside.errors().getFirst());
	}

	@Test
	void aDownloadsOwnFailureIsShownAsItIs() throws IOException {
		FakeModrinthClient client = new FakeModrinthClient();
		client.latestByProject.put("a", FakeModrinthClient.version("aV", "A", "1", T));
		DownloadPlanner.Result result = plan(client, Map.of(), add("a"));
		Text.Translatable error = assertInstanceOf(Text.Translatable.class, result.errorTexts().getFirst());
		assertEquals(Text.literal("boom"), error.args().get(1));
		assertEquals("Install a: boom", result.errors().getFirst());
	}

	@Test
	void theOldConstructorsKeepTheErrorsAsLiterals() {
		DownloadPlanner.Result result = new DownloadPlanner.Result(List.of(), List.of(), List.of("x: y"));
		assertEquals(List.of(Text.literal("x: y")), result.errorTexts());
		assertEquals(List.of(), new DownloadPlanner.Result(List.of(), List.of(), null).errorTexts());
	}
}
