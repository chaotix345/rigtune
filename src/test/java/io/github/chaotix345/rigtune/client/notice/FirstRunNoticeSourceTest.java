package io.github.chaotix345.rigtune.client.notice;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.awareness.AwarenessService;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 8 (AC8.1, AC8.4, AC8.13, AC8.14): the first-run guide. It shows only for a new player whose report
// has something to apply; its detail follows who changes mod files (P0.4) and never claims anything about them while
// that's still being checked; Got it is an ordinary notice dismissal in awareness.json, which 0.4.0's AwarenessStore keeps.
class FirstRunNoticeSourceTest {
	private static final String SET = "src/test/resources/v050-written/ws-f/";
	private static final String DETAIL = "Only the ticked items change, with any mods they need. Game settings change when you press Apply; Sodium, "
			+ "Distant Horizons and Iris settings and mod changes take effect at the next restart. Preview shows each change first.";
	private static final String DETAIL_SETTINGS = "Only the ticked settings change. Game settings change when you press Apply; Sodium, Distant "
			+ "Horizons and Iris settings at the next restart. Preview shows each change first.";
	private static final Text LAUNCHER_LINE = Text.literal("The Modrinth App manages this instance's mods: RigTune changes settings only.");
	private static final Text OPTED_IN_LINE = Text.literal("RigTune changes mod files here; the Modrinth App's own list may go out of date.");

	private static Report report(Recommendation... recommendations) {
		HardwareProfile hw = new HardwareProfile(new CpuInfo("CPU", 8, 16, 5000), new GpuInfo("AMD", "GPU", "1", GraphicsBackend.OPENGL, 8192),
				32_000, 4096, new DisplayInfo(1920, 1080, 60, true), false, false, "Windows 11", "26.2", Set.of());
		return new Report(hw, new GpuClass(GpuVendor.AMD, false, 4, null), new TierResult(4, 4, 4, 4, 4, "cpu"), Goal.BALANCED, List.of(recommendations),
				16, "bundled", false, Instant.parse("2026-09-27T10:00:00Z"), null);
	}

	private static Recommendation setting() {
		return new Recommendation("set-vanilla.renderDistance", Category.SETTING, Impact.HIGH, "Render distance: 16 → 12", "reason",
				new Action.SetSetting("vanilla.renderDistance", "16", "12"), true);
	}

	private static Recommendation advice() {
		return new Recommendation("advice-driver", Category.ADVICE, Impact.MEDIUM, "Update your GPU driver", "reason", new Action.None(), false);
	}

	@Test
	void theGuide() {
		Notice notice = FirstRunNoticeSource.notice(ModFilesPolicy.RIGTUNE, null);
		assertEquals("firstrun.guide", notice.key());
		assertEquals(NoticePriority.FIRST_RUN, notice.priority());
		assertEquals("New? History… lets you undo each Apply.", notice.message().english());
		assertEquals(DETAIL, notice.detail().english());
		assertEquals(List.of("how", "got_it"), notice.actions().stream().map(NoticeAction::id).toList());
		assertEquals(List.of("How it works", "Got it"), notice.actions().stream().map(a -> a.label().english()).toList());
		assertFalse(notice.dismissible(), "Got it is its dismissal");
	}

	// AC8.14 (fa §2.5): RIGTUNE (with P0.4's opted-in line when there is one), LAUNCHER (settings only, then the launcher's
	// own sentence), PENDING (settings only; no sentence about who manages mods, whatever it is given).
	@Test
	void theDetailFollowsWhoChangesModFiles() {
		assertEquals(DETAIL + " " + OPTED_IN_LINE.english(), FirstRunNoticeSource.notice(ModFilesPolicy.RIGTUNE, OPTED_IN_LINE).detail().english());
		assertEquals(DETAIL_SETTINGS, FirstRunNoticeSource.notice(ModFilesPolicy.LAUNCHER, null).detail().english());
		assertEquals(DETAIL_SETTINGS + " " + LAUNCHER_LINE.english(), FirstRunNoticeSource.notice(ModFilesPolicy.LAUNCHER, LAUNCHER_LINE).detail().english());
		String pending = FirstRunNoticeSource.notice(ModFilesPolicy.PENDING, LAUNCHER_LINE).detail().english();
		assertEquals(DETAIL_SETTINGS, pending);
		assertFalse(pending.contains("manage") || pending.contains("mod"), pending);
		for (ModFilesPolicy policy : ModFilesPolicy.values()) {
			assertEquals("New? History… lets you undo each Apply.", FirstRunNoticeSource.notice(policy, null).message().english());
		}
	}

	// AC8.1: only a new player (once the load has answered), and only with something Apply would change.
	@Test
	void whenItShows() {
		Report appliable = report(advice(), setting());
		assertTrue(FirstRunNoticeSource.shows(FirstRun.Status.NEW, appliable));
		assertFalse(FirstRunNoticeSource.shows(FirstRun.Status.NEW, report(advice())), "nothing to apply");
		assertFalse(FirstRunNoticeSource.shows(FirstRun.Status.NEW, null), "no report yet");
		assertFalse(FirstRunNoticeSource.shows(FirstRun.Status.UNKNOWN, appliable), "not loaded yet");
		assertFalse(FirstRunNoticeSource.shows(FirstRun.Status.RETURNING, appliable), "after the first Apply, or a returning player");
	}

	// A guide that can't be dismissed always shows on the notice line (NoticeBoard), so the source itself leaves it out once
	// Got it is stored; awareness.json is read only when the guide would otherwise show.
	@Test
	void theStoredDismissalHidesIt() {
		Report appliable = report(setting());
		assertEquals(FirstRunNoticeSource.KEY, FirstRunNoticeSource.current(FirstRun.Status.NEW, appliable, Set::of, ModFilesPolicy.RIGTUNE, null).key());
		assertNull(FirstRunNoticeSource.current(FirstRun.Status.NEW, appliable, () -> Set.of(FirstRunNoticeSource.KEY), ModFilesPolicy.RIGTUNE, null));
		assertNull(FirstRunNoticeSource.current(FirstRun.Status.RETURNING, appliable, () -> {
			throw new AssertionError("awareness.json read for a returning player");
		}, ModFilesPolicy.RIGTUNE, null));
	}

	// AC8.4: Got it goes through the notice line's dismissal, which AwarenessService stores for a key like this one (no
	// hardware, what's-new or session-only prefix); a NoticeCenter over the same awareness.json hides the guide for good.
	@Test
	void gotItHidesTheGuideForGood(@TempDir Path configDir) {
		assertFalse(FirstRunNoticeSource.KEY.startsWith(AwarenessService.HARDWARE_KEY_PREFIX));
		assertFalse(FirstRunNoticeSource.KEY.startsWith(AwarenessService.WHATS_NEW_KEY_PREFIX));
		assertTrue(AwarenessService.SESSION_ONLY_PREFIXES.stream().noneMatch(FirstRunNoticeSource.KEY::startsWith));
		AwarenessStore store = AwarenessStore.shared(configDir);
		Report appliable = report(setting());
		NoticeSource guide = new NoticeSource() {
			@Override
			public @Nullable Notice current() {
				return FirstRunNoticeSource.current(FirstRun.Status.NEW, appliable, store::dismissed, ModFilesPolicy.RIGTUNE, null);
			}

			@Override
			public void act(String actionId) {
			}
		};
		NoticeCenter center = new NoticeCenter(List.of(guide), dismissals(configDir));
		assertEquals(List.of(FirstRunNoticeSource.KEY), center.notices().stream().map(Notice::key).toList());
		center.dismiss(FirstRunNoticeSource.KEY);
		assertTrue(center.notices().isEmpty());
		assertTrue(new NoticeCenter(List.of(guide), dismissals(configDir)).notices().isEmpty(), "still hidden after a restart");
	}

	private static NoticeCenter.Dismissals dismissals(Path configDir) {
		AwarenessStore store = AwarenessStore.shared(configDir);
		return new NoticeCenter.Dismissals() {
			@Override
			public Set<String> dismissed() {
				return store.dismissed();
			}

			@Override
			public void dismiss(String key) {
				store.dismiss(key);
			}
		};
	}

	// AC8.4 (unit half): 0.4.0 dismisses other notices with this same code (AwarenessStore.dismiss is unchanged since v0.4.0;
	// the diff only adds accessors), and the guide's key stays in the file.
	@Test
	void theDismissalSurvivesTenMore(@TempDir Path configDir) throws IOException {
		AwarenessStore store = AwarenessStore.shared(configDir);
		assertTrue(store.dismiss(FirstRunNoticeSource.KEY));
		for (int i = 0; i < 10; i++) {
			assertTrue(store.dismiss("benchmark.regression.run-" + i));
		}
		JsonArray onDisk = JsonParser.parseString(Files.readString(configDir.resolve("rigtune").resolve(AwarenessStore.FILE_NAME)))
				.getAsJsonObject().getAsJsonArray(AwarenessStore.DISMISSED);
		assertTrue(onDisk.asList().stream().anyMatch(e -> e.getAsString().equals(FirstRunNoticeSource.KEY)), onDisk.toString());
		assertEquals(11, onDisk.size());
	}

	// The "written by 0.5" set ws-f (SPEC X11): awareness.json with the guide dismissed, as this version writes it.
	// RIGTUNE_REGENERATE_FIXTURES=1 rewrites it; otherwise the committed file must be exactly what the code writes now.
	@Test
	void theFixtureSetIsWhatThisVersionWrites(@TempDir Path configDir) throws IOException {
		assertTrue(AwarenessStore.shared(configDir).dismiss(FirstRunNoticeSource.KEY));
		Path written = configDir.resolve("rigtune").resolve(AwarenessStore.FILE_NAME);
		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(written, committed.resolve(AwarenessStore.FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
		}
		assertEquals(Files.readString(written, StandardCharsets.UTF_8), Files.readString(committed.resolve(AwarenessStore.FILE_NAME), StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		assertTrue(Files.isRegularFile(committed.resolve("expect.json")), "the set's expect.json");
	}
}
