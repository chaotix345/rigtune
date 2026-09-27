package io.github.chaotix345.rigtune.core.preview;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.modrinth.DependencyResolver;
import io.github.chaotix345.rigtune.core.modrinth.DownloadPlanner;
import io.github.chaotix345.rigtune.core.modrinth.DryRunPlanner;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.modrinth.RangeReader;
import io.github.chaotix345.rigtune.core.modrinth.StagedProjects;
import io.github.chaotix345.rigtune.core.modrinth.VersionPins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.chaotix345.rigtune.core.preview.PreviewFakeModrinth.version;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2H L5 (AC2H.1b, unit): with Modrinth on, Preview reads each listed download's fabric.mod.json (here a
// stand-in for RangeReader answering from fixture jars) and runs Apply's own fabric.mod.json checks (VersionPins), so a
// download Apply would refuse shows Apply's refusal line; when a read fails, or Modrinth is off, it falls back to today's
// preview and its disclosure line.
class PreviewDownloadChecksTest {
	@TempDir
	Path dir;
	PreviewFixtures instance;
	final PreviewFakeModrinth modrinth = new PreviewFakeModrinth();
	final Map<String, ModrinthVersion> updateVersions = new HashMap<>();
	// File name -> the fabric.mod.json its jar holds (absent: the read fails; null: a jar without one).
	final Map<String, String> jars = new HashMap<>();
	final List<String> reads = new ArrayList<>();
	final AtomicBoolean closed = new AtomicBoolean();
	boolean lookups = true;
	Set<String> nestedOrProvided = Set.of();
	VersionPins pins = VersionPins.NONE;

	@BeforeEach
	void setUp() throws IOException {
		instance = new PreviewFixtures(dir.resolve("game"));
	}

	// As the client's FabricPins, with a stand-in for Fabric's predicates: "*", "0.9.x" (a prefix) or an exact version.
	private static VersionPins loaded(List<VersionPins.Pin> declarations, VersionPins.Loaded... mods) {
		return new VersionPins(declarations, List.of(mods), (ranges, version) -> ranges.stream().anyMatch(range -> range.equals("*")
				|| (range.endsWith("x") ? version.startsWith(range.substring(0, range.length() - 1)) : version.equals(range))));
	}

	private static VersionPins.Loaded mod(String id, String name, String version) {
		return new VersionPins.Loaded(id, name, version, id);
	}

	private static VersionPins.Pin dependsOn(String by, String byName, String target, String targetName, String range) {
		String prefix = range.endsWith("x") ? range.substring(0, range.length() - 1) : null;
		return new VersionPins.Pin(by, byName, by, target, targetName, VersionPins.Kind.DEPENDS, () -> range,
				v -> prefix != null ? v.startsWith(prefix) : v.equals(range));
	}

	private RangeReader.Read read(ModFile file) {
		reads.add(file.filename());
		if (!jars.containsKey(file.filename())) {
			return new RangeReader.Read(null, false, "HTTP 500 to bytes=-65536", 0, 1);
		}
		String json = jars.get(file.filename());
		return json == null ? new RangeReader.Read(null, true, null, 100, 2) : new RangeReader.Read(json.getBytes(), false, null, 100, 3);
	}

	private DownloadInputs inputs() {
		return new DownloadInputs(modrinth, lookups, "fabric", "26.2", Map.of(), updateVersions, Set.of("SODIUM"), Set.of("sodium"), Map.of(),
				(a, b) -> false, StagedProjects.NONE, true).withJarChecks(new DryRunPlanner.Checks(pins, nestedOrProvided, this::read, () -> closed.set(true)));
	}

	private ApplyPreview preview(Recommendation... selected) {
		return new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), instance.configFiles(), instance.mods, inputs())
				.preview(List.of(selected));
	}

	// Apply's own planner over the real jars (what RealController.download runs): its refusal for the one recommendation.
	private Text applysRefusal(Recommendation rec) {
		DependencyResolver resolver = new DependencyResolver(modrinth, "fabric", "26.2", Map.of());
		DownloadPlanner.Result result = new DownloadPlanner(resolver, instance.mods, file -> {
			Path pending = instance.mods.resolve(file.filename() + PendingActions.PENDING_SUFFIX);
			String json = jars.get(file.filename());
			return json == null ? TestJars.plainJar(pending) : TestJars.modJar(pending, JsonParser.parseString(json).getAsJsonObject());
		}, (a, b) -> false, updateVersions, pins).plan(List.of(rec), Set.of("SODIUM"), Set.of("sodium"), Map.of());
		assertEquals(1, result.errorTexts().size(), result.errors().toString());
		Text.Translatable error = (Text.Translatable) result.errorTexts().getFirst();
		return (Text) error.args().get(1);
	}

	private static Recommendation add(String slug, String projectId, String title) {
		return new Recommendation("add:" + slug, Category.ADD_MOD, Impact.HIGH, "Install " + title, "", new Action.AddMod(slug, projectId, title), true);
	}

	private Recommendation updateSodium(String newVersion) throws IOException {
		Path installed = TestJars.modJar(instance.mods.resolve("sodium-0.9.3.jar"), "sodium");
		updateVersions.put("sodV", version("sodV", "SODIUM", "sodium-" + newVersion + ".jar"));
		UpdateInfo info = new UpdateInfo("sodium", "SODIUM", "0.9.3", "sodV", newVersion,
				new ModFile("https://cdn/sodium-" + newVersion + ".jar", "sodium-" + newVersion + ".jar", "sha512", 10));
		return new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium", "", new Action.UpdateMod("sodium", installed, info), true);
	}

	private static String json(String id, String version, String extra) {
		return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"name\":\"" + id.toUpperCase() + " Mod\"" + extra + "}";
	}

	// The audit's H2 case at Preview time: a download whose own fabric.mod.json breaks an installed mod is under "Not
	// changed" with Apply's own line, and a clean one next to it is listed.
	@Test
	void aDownloadThatBreaksAnInstalledModShowsApplysRefusalAndACleanOneIsListed() {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		modrinth.put("c", version("cV", "C", "c-1.0.jar"), "c");
		jars.put("b-1.0.jar", json("b", "1.0", ",\"breaks\":{\"sodium\":\"0.9.x\"}"));
		jars.put("c-1.0.jar", json("c", "1.0", ",\"depends\":{\"sodium\":\"0.9.x\"}"));
		pins = loaded(List.of(), mod("sodium", "Sodium", "0.9.3"));

		ApplyPreview preview = preview(add("b", "B", "B"), add("c", "C", "C"));

		assertEquals(List.of("c-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertEquals(1, preview.skipped().size());
		ApplyPreview.Skipped refused = preview.skipped().getFirst();
		assertEquals("add:b", refused.recommendationId());
		assertEquals(ApplyPreview.Reason.DOWNLOAD_FAILED, refused.reason());
		assertEquals(Text.of("rigtune.download.breaks_version", "%s doesn't work with the installed %s %s", "B Mod", "Sodium", "0.9.3"), refused.detailText());
		assertEquals(applysRefusal(add("b", "B", "B")), refused.detailText());
		assertTrue(preview.downloadsChecked());
		assertEquals(List.of("b-1.0.jar", "c-1.0.jar"), reads);
		assertTrue(closed.get(), "the reader is closed when the preview is done");
	}

	// An installed mod's pin on an update (Iris declares sodium 0.9.x): the same line as Apply's.
	@Test
	void anUpdateAnInstalledModPinsShowsApplysRefusal() throws IOException {
		Recommendation update = updateSodium("0.10.0");
		jars.put("sodium-0.10.0.jar", json("sodium", "0.10.0", ""));
		pins = new VersionPins(List.of(dependsOn("iris", "Iris", "sodium", "Sodium", "0.9.x")));

		ApplyPreview preview = preview(update);

		assertEquals(List.of(), preview.downloads());
		assertEquals(List.of(), preview.disables());
		assertEquals(applysRefusal(update), preview.skipped().getFirst().detailText());
		assertEquals("Iris, which is installed, needs SODIUM Mod 0.9.x, not 0.10.0", preview.skipped().getFirst().detail());
	}

	// A file Apply would find no mod id in is refused as Apply refuses it.
	@Test
	void aJarWithoutFabricModJsonIsRefusedAsApplyRefusesIt() {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		jars.put("b-1.0.jar", null);

		ApplyPreview preview = preview(add("b", "B", "B"));

		assertEquals(List.of(), preview.downloads());
		assertEquals(applysRefusal(add("b", "B", "B")), preview.skipped().getFirst().detailText());
		assertEquals("b-1.0.jar is not a Fabric mod jar (no readable fabric.mod.json id)", preview.skipped().getFirst().detail());
	}

	// A read that fails (the CDN refuses Range, a cap, a deadline): that download is listed as today, unjudged, and the
	// preview isn't "checked", so the disclosure line shows.
	@Test
	void aFailedReadFallsBackToTodaysPreview() {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		pins = loaded(List.of(), mod("sodium", "Sodium", "0.9.3"));

		ApplyPreview preview = preview(add("b", "B", "B"));

		assertEquals(List.of("b-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertEquals(List.of(), preview.skipped());
		assertFalse(preview.downloadsChecked());
		assertEquals(List.of("b-1.0.jar"), reads);
	}

	// Modrinth off: nothing is read (the gate is the settings'), and the preview is today's.
	@Test
	void withModrinthOffNothingIsRead() throws IOException {
		lookups = false;
		jars.put("sodium-0.10.0.jar", json("sodium", "0.10.0", ""));
		pins = new VersionPins(List.of(dependsOn("iris", "Iris", "sodium", "Sodium", "0.9.x")));

		ApplyPreview preview = preview(updateSodium("0.10.0"));

		assertEquals(List.of("sodium-0.10.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertFalse(preview.downloadsChecked());
		assertEquals(List.of(), reads);
	}

	// A read covers the outer fabric.mod.json only. Nesting jars, a download may carry the library its range names (Fabric
	// then picks that copy), so an older copy nested in an installed mod doesn't refuse it; Apply, reading the whole jar,
	// can. Without nested jars the same range is refused, as Apply refuses it.
	@Test
	void aDownloadThatNestsJarsIsNotRefusedOverALibraryItMayCarry() {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		pins = loaded(List.of(), new VersionPins.Loaded("lib", "Lib", "1.0", "distanthorizons"));
		nestedOrProvided = Set.of("lib");
		jars.put("b-1.0.jar", json("b", "1.0", ",\"depends\":{\"lib\":\"2.x\"},\"jars\":[{\"file\":\"META-INF/jars/lib-2.0.jar\"}]"));

		ApplyPreview nesting = preview(add("b", "B", "B"));

		assertEquals(List.of("b-1.0.jar"), nesting.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertTrue(nesting.downloadsChecked());

		jars.put("b-1.0.jar", json("b", "1.0", ",\"depends\":{\"lib\":\"2.x\"}"));
		ApplyPreview plain = preview(add("b", "B", "B"));

		assertEquals(List.of(), plain.downloads());
		assertEquals("B Mod needs Lib 2.x, not the installed 1.0", plain.skipped().getFirst().detail());
	}

	// A failed read's jar counts as nesting anything too: another download's range on a nested library isn't judged.
	@Test
	void aFailedReadMayCarryALibraryAnotherDownloadNeeds() {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		modrinth.put("c", version("cV", "C", "c-1.0.jar"), "c");
		pins = loaded(List.of(), new VersionPins.Loaded("lib", "Lib", "1.0", "distanthorizons"));
		nestedOrProvided = Set.of("lib");
		jars.put("b-1.0.jar", json("b", "1.0", ",\"depends\":{\"lib\":\"2.x\"}"));

		ApplyPreview preview = preview(add("b", "B", "B"), add("c", "C", "C"));

		assertEquals(List.of("b-1.0.jar", "c-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertFalse(preview.downloadsChecked());
	}

	// Without checks (no reader given): exactly today's preview.
	@Test
	void withoutChecksThePreviewIsTodays() {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		jars.put("b-1.0.jar", json("b", "1.0", ",\"breaks\":{\"sodium\":\"0.9.x\"}"));
		DownloadInputs plain = new DownloadInputs(modrinth, true, "fabric", "26.2", Map.of(), updateVersions, Set.of("SODIUM"), Set.of("sodium"), Map.of(),
				(a, b) -> false, StagedProjects.NONE, true);

		ApplyPreview preview = new PreviewPlanner(instance.options, PreviewFixtures.vanillaNow(), Map.of(), instance.configFiles(), instance.mods, plain)
				.preview(List.of(add("b", "B", "B")));

		assertEquals(List.of("b-1.0.jar"), preview.downloads().stream().map(ApplyPreview.Download::fileName).toList());
		assertFalse(preview.downloadsChecked());
		assertNull(plain.checks());
		assertEquals(List.of(), reads);
	}

	@Test
	void theChecksWriteNothing() throws IOException {
		modrinth.put("b", version("bV", "B", "b-1.0.jar"), "b");
		jars.put("b-1.0.jar", json("b", "1.0", ""));
		var before = instance.tree();

		preview(add("b", "B", "B"));

		assertEquals(before, instance.tree());
		assertFalse(modrinth.downloaded());
		assertTrue(Files.isDirectory(instance.mods));
	}
}
