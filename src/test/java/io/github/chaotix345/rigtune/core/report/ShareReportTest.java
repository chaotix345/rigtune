package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareReportTest {
	private static final ShareReport.Versions VERSIONS = new ShareReport.Versions("0.2.0-dev+mc26.2", "26.2", "0.19.5");

	private static Report report(HardwareProfile hw, List<Recommendation> recs) {
		return new Report(hw, new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(4, 4, 5, 4, 5, "cpu"), Goal.BALANCED,
				recs, 7, "remote", true, Instant.parse("2026-09-25T10:00:00Z"));
	}

	private static Recommendation rec(String id, Category category, Impact impact, String title, Action action, boolean selected) {
		return new Recommendation(id, category, impact, title, "reason for " + title, action, selected);
	}

	private static List<Recommendation> sample() {
		return List.of(
				rec("warn-heap", Category.WARNING, Impact.HIGH, "Only 2 GB of RAM allocated", new Action.None(), false),
				rec("disable:indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium", new Action.DisableMod("indium", Path.of("mods", "indium.jar")), true),
				rec("add:lithium", Category.ADD_MOD, Impact.HIGH, "Install Lithium", new Action.AddMod("lithium", "gvQqBUqZ", "Lithium"), true),
				rec("add:entityculling", Category.ADD_MOD, Impact.LOW, "Install Entity Culling", new Action.AddMod("entityculling", "NNAgCjsB", "Entity Culling"), false),
				rec("set:vanilla.renderDistance", Category.SETTING, Impact.MEDIUM, "Render distance: 16 → 12", new Action.SetSetting("vanilla.renderDistance", "16", "12"), true));
	}

	@Test
	void headerHardwareAndGroups() {
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null);

		assertTrue(text.startsWith("**RigTune 0.2.0-dev+mc26.2** · Minecraft 26.2 · Fabric Loader 0.19.5\n"), text);
		assertTrue(text.contains("- CPU: AMD Ryzen 7 7800X3D 8-Core Processor (8 cores, 16 threads)\n"), text);
		assertTrue(text.contains("- GPU: AMD Radeon RX 7800 XT · driver 25.9.1 · OpenGL · 16 GB VRAM\n"), text);
		assertTrue(text.contains("- RAM 32 GB · heap 6.0 GB · display 2560×1440 @ 180 Hz\n"), text);
		assertTrue(text.contains("- Estimated tier 4/5 · lowest estimated component: CPU · goal Balanced\n"), text);
		assertTrue(text.contains("- Rules r7 (remote) · online\n"), text);
		assertTrue(text.contains("**Recommendations** (5; [x] = suggested)\n"), text);

		int warnings = text.indexOf("__Warnings__\n- Only 2 GB of RAM allocated (high)\n");
		int remove = text.indexOf("__Remove mods__\n- [x] Disable Indium (high)\n");
		int add = text.indexOf("__Add mods__\n- [x] Install Lithium (high)\n- [ ] Install Entity Culling (low)\n");
		int settings = text.indexOf("__Settings__\n- [x] Render distance: 16 → 12 (medium)");
		assertTrue(warnings > 0 && remove > warnings && add > remove && settings > add, text);
		assertFalse(text.contains("reason for"), "reasons are left out to keep it short: " + text);
	}

	@Test
	void benchmarkLine() {
		BenchmarkSummary bench = new BenchmarkSummary("2026-09-25T09:30:00Z", "tune", "CURRENT", 165, 12, 180.4, 170.2, true);
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, bench);

		assertTrue(text.contains("**Latest benchmark** 2026-09-25 · tune · current\n"
				+ "- render distance 12 · avg 180 FPS · 1% low 170 FPS · target 165 FPS met\n"), text);
		assertTrue(text.indexOf("**Latest benchmark**") < text.indexOf("**Recommendations**"), text);
	}

	@Test
	void missedTargetAndBenchmarkWorld() {
		BenchmarkSummary bench = new BenchmarkSummary("2026-09-25T09:30:00Z", "measure", "BENCHMARK_WORLD", 144, 8, 90, 60, false);
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, bench);

		assertTrue(text.contains("**Latest benchmark** 2026-09-25 · measure · benchmark world\n"
				+ "- render distance 8 · avg 90 FPS · 1% low 60 FPS · target 144 FPS missed\n"), text);
	}

	// docs/v0.4/SPEC.md 7: the last benchmark's conditions and its "needs a rerun" marker.
	@Test
	void benchmarkConditionsAndRerun() {
		BenchmarkSummary bench = new BenchmarkSummary("2026-09-25T09:30:00Z", "measure", "BENCHMARK_WORLD", 144, 12, 812, 543, true)
				.withContext("RD 12 · SD 8 · 2560×1440 · shaders off", "Needs a rerun (changed since: resolution, mod set)");
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, bench);

		assertTrue(text.contains("- render distance 12 · avg 812 FPS · 1% low 543 FPS · target 144 FPS met\n"
				+ "- conditions: RD 12 · SD 8 · 2560×1440 · shaders off\n"
				+ "- Needs a rerun (changed since: resolution, mod set)\n"), text);
		String current = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, bench.withContext("RD 12 · SD 8", null));
		assertTrue(current.contains("- conditions: RD 12 · SD 8\n**Recommendations**"), current);
	}

	@Test
	void noBenchmarkYet() {
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null);

		assertTrue(text.contains("**Latest benchmark** not run yet\n"), text);
	}

	@Test
	void unknownValuesPrintQuestionMarks() {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo("Intel", "Intel(R) UHD Graphics 620", "", GraphicsBackend.UNKNOWN, -1);
		hw.display = new DisplayInfo(0, 0, -1, false);
		hw.heapMb = -1;
		String text = ShareReport.format(report(hw.build(), sample()), VERSIONS, null);

		assertTrue(text.contains("- GPU: Intel(R) UHD Graphics 620 · ? · ? VRAM\n"), text);
		assertTrue(text.contains("- RAM 32 GB · heap ? · display ?\n"), text);
	}

	@Test
	void displayWithoutRefreshRate() {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.display = new DisplayInfo(1920, 1080, -1, false);
		String text = ShareReport.format(report(hw.build(), sample()), VERSIONS, null);

		assertTrue(text.contains("display 1920×1080\n"), text);
	}

	@Test
	void offlineReport() {
		Report offline = new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(3, 2, 3, 4, 5, "gpu"),
				Goal.PERFORMANCE, sample(), 2, "bundled", false, Instant.now());
		String text = ShareReport.format(offline, VERSIONS, null);

		assertTrue(text.contains("- Estimated tier 3/5 · lowest estimated component: GPU · goal Performance\n"), text);
		assertTrue(text.contains("- Rules r2 (bundled) · offline\n"), text);
	}

	@Test
	void truncatesWithMoreCount() {
		List<Recommendation> many = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			many.add(rec("set:" + i, Category.SETTING, Impact.LOW, "Some setting number " + i + ": off → on",
					new Action.SetSetting("vanilla.x" + i, "false", "true"), i % 2 == 0));
		}
		String text = ShareReport.format(report(Fixtures.userRig().build(), many), VERSIONS, null);

		assertTrue(text.length() <= ShareReport.DISCORD_LIMIT, "length " + text.length());
		Matcher more = Pattern.compile("\\n\\((\\d+) more\\)$").matcher(text);
		assertTrue(more.find(), text);
		long shown = text.lines().filter(line -> line.startsWith("- [")).count();
		assertEquals(200, shown + Integer.parseInt(more.group(1)));
		assertTrue(text.length() > ShareReport.DISCORD_LIMIT - 60, "uses the room it has: " + text.length());
	}

	@Test
	void nothingTruncatedWhenItFits() {
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null);

		assertFalse(text.contains("more)"), text);
	}

	@Test
	void customLimitNeverLeavesAnEmptyCategoryHeader() {
		String full = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null);
		int cut = full.indexOf("__Add mods__") + "__Add mods__".length() + 2;
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null, cut + "\n(3 more)".length());

		assertTrue(text.length() <= cut + "\n(3 more)".length(), text);
		assertFalse(text.contains("__Add mods__"), "the header only comes with an item: " + text);
		assertTrue(text.endsWith("\n(3 more)"), text);
	}

	@Test
	void hardCapWhenTheFixedPartIsTooLong() {
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null, 120);

		assertTrue(text.length() <= 120, "length " + text.length());
	}

	@Test
	void noPathsOrUserNames() {
		Path home = Path.of("C:", "Users", "alice", "AppData", "Roaming", ".minecraft", "mods");
		List<Recommendation> recs = List.of(
				new Recommendation("disable:indium", Category.REMOVE_MOD, Impact.HIGH, "Disable Indium",
						"Found at C:\\Users\\alice\\AppData\\Roaming\\.minecraft\\mods\\indium.jar",
						new Action.DisableMod("indium", home.resolve("indium.jar")), true),
				new Recommendation("update:sodium", Category.UPDATE_MOD, Impact.LOW, "Update Sodium", "Version 0.9.2 is available.",
						new Action.UpdateMod("sodium", home.resolve("sodium.jar"), new UpdateInfo("sodium", "AANobbMI", "0.9.1", "v1", "0.9.2", null)), true),
				rec("advice:x", Category.ADVICE, Impact.LOW, "Check /home/bob/.minecraft/mods/x.jar and C:\\Users\\alice\\x.jar or ~/bob/x", new Action.None(), false),
				rec("advice:y", Category.ADVICE, Impact.LOW, "See https://modrinth.com/mod/sodium for 1/2 of it", new Action.None(), false));
		Fixtures.Hw hw = Fixtures.userRig();
		hw.cpu = new io.github.chaotix345.rigtune.core.model.CpuInfo("CPU at D:\\Games\\bob\\cpu.txt", 8, 16, -1);
		String text = ShareReport.format(report(hw.build(), recs), VERSIONS, null);

		for (String leak : List.of("alice", "bob", ".minecraft", "AppData", "indium.jar", "sodium.jar", "Games")) {
			assertFalse(text.contains(leak), "leaked " + leak + ": " + text);
		}
		assertFalse(Pattern.compile("(?<![A-Za-z])[A-Za-z]:[\\\\/]").matcher(text).find(), text);
		assertTrue(text.contains("See https://modrinth.com/mod/sodium for 1/2 of it"), text);
		assertTrue(text.contains("(path)"), text);
	}

	private static String withTitle(String title) {
		return ShareReport.format(report(Fixtures.userRig().build(),
				List.of(rec("advice:t", Category.ADVICE, Impact.LOW, title, new Action.None(), false))), VERSIONS, null);
	}

	@Test
	void pathsWithSpacesLeakNoNames() {
		String text = withTitle("Check /Users/John Smith/mods/x.jar now, C:/Users/Jane Doe/AppData/x.jar and C:\\Users\\Ann Lee\\x.jar too");

		for (String leak : List.of("John", "Smith", "Jane", "Doe", "Ann", "Lee", "AppData")) {
			assertFalse(text.contains(leak), "leaked " + leak + ": " + text);
		}
		assertTrue(text.contains("Check (path) now,"), text);
		assertTrue(text.contains(" too (low)"), text);
	}

	@Test
	void slashesInOrdinaryTextSurvive() {
		String text = withTitle("Chunks a / b / c and 1/2 and 3/4");

		assertTrue(text.contains("Chunks a / b / c and 1/2 and 3/4 (low)"), text);
	}

	@Test
	void markdownAndMentionsAreDefused() {
		String text = withTitle("Install *Fast* _Mod_ `x` ~y~ |z| [a](b) <@123> @everyone");

		assertTrue(text.contains("Install \\*Fast\\* \\_Mod\\_ \\`x\\` \\~y\\~ \\|z\\| \\[a\\](b) \\<@\u200B123> @\u200Beveryone (low)"), text);
	}

	@Test
	void emptyRecommendations() {
		String text = ShareReport.format(report(Fixtures.userRig().build(), List.of()), VERSIONS, null);

		assertTrue(text.endsWith("\n**Recommendations** none"), text);
		assertFalse(text.contains("more)"), text);
	}

	@Test
	void aMoreLineThatDoesNotFitIsDroppedNotCut() {
		String full = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null);
		int fixed = full.indexOf("__Warnings__");
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null, fixed + 3);

		assertTrue(text.endsWith("**Recommendations** (5; [x] = suggested)"), text);
		assertTrue(text.length() <= fixed + 3, "length " + text.length());
	}

	@Test
	void hardCutEndsAtALineBreakAndNoRoomGivesNothing() {
		String text = ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null, 120);

		assertTrue(text.startsWith("**RigTune 0.2.0-dev+mc26.2** · Minecraft 26.2 · Fabric Loader 0.19.5\n"), text);
		assertFalse(text.endsWith("\n"), text);
		assertEquals("", ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null, 0));
		assertEquals("", ShareReport.format(report(Fixtures.userRig().build(), sample()), VERSIONS, null, -5));
	}

	@Test
	void surrogatePairsAreNeverSplit() {
		String emoji = new String(Character.toChars(0x1F600));
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo("X", "G".repeat(118) + emoji + "tail", "1", GraphicsBackend.VULKAN, 8192);
		String clipped = ShareReport.format(report(hw.build(), sample()), VERSIONS, null);
		assertWellFormed(clipped);
		assertTrue(clipped.contains("G".repeat(118) + "…"), clipped);

		Fixtures.Hw emojiCpu = Fixtures.userRig();
		emojiCpu.cpu = new io.github.chaotix345.rigtune.core.model.CpuInfo(emoji.repeat(40), 8, 16, -1);
		int length = ShareReport.format(report(emojiCpu.build(), sample()), VERSIONS, null).length();
		for (int max = 1; max <= length; max++) {
			String text = ShareReport.format(report(emojiCpu.build(), sample()), VERSIONS, null, max);
			assertTrue(text.length() <= max, max + ": " + text.length());
			assertWellFormed(text);
		}
	}

	private static void assertWellFormed(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isHighSurrogate(c)) {
				assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)), "split pair at " + i + ": " + text);
				i++;
			} else {
				assertFalse(Character.isLowSurrogate(c), "lone low surrogate at " + i + ": " + text);
			}
		}
	}

	@Test
	void longFieldsAreClipped() {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo("X", "G".repeat(500), "1", GraphicsBackend.VULKAN, 8192);
		String text = ShareReport.format(report(hw.build(), sample()), VERSIONS, null);

		assertFalse(text.contains("G".repeat(121)), text);
		assertTrue(text.contains("G".repeat(119) + "…"), text);
	}

	// docs/v0.4/SPEC.md 2j (AC2j.1): an estimate, and a tie lists every tied component, never "limited by".
	@Test
	void theTierLineIsAnEstimateAndListsTies() {
		Report balanced = new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, "x"), new TierResult(5, 5, 5, 5, 5, "gpu"),
				Goal.BALANCED, sample(), 2, "bundled", false, Instant.now());
		String text = ShareReport.format(balanced, VERSIONS, null);

		assertTrue(text.contains("- Estimated tier 5/5 · lowest estimated component: GPU, CPU, memory · goal Balanced\n"), text);
		assertFalse(text.contains("limited by"), text);
	}
}
