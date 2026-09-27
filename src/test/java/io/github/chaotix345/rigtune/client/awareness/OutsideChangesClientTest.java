package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.client.V05Hooks;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.awareness.OutsideOptions;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4h (AC4h.3's unit part, AC4h.4) and the SETTINGS_CHANGED_OUTSIDE notice: the Modrinth App fan-out line
// only in a Modrinth App instance and only when settings were written now; the stop handler stores one snapshot and
// nothing during a benchmark or a Try it; the notice's text, the app's steps only in that launcher, and its two actions;
// "Apply RigTune's values again" is an ordinary Apply of RigTune's values.
class OutsideChangesClientTest {
	private static final List<OutsideOptions.Change> CHANGES = List.of(new OutsideOptions.Change("vanilla.renderClouds", "fast", "fancy", "fast"),
			new OutsideOptions.Change("vanilla.maxFps", "120", "260", "120"), new OutsideOptions.Change("vanilla.entityShadows", "false", "true", "true"));

	@AfterEach
	void reset() {
		OutsideChanges.reset();
	}

	private static String key(Component component) {
		return component.getContents() instanceof TranslatableContents t ? t.getKey() : component.getString();
	}

	@Test
	void theFanOutLineIsOnlyForTheModrinthAppAndOnlyWhenSettingsWereWrittenNow() {
		Component line = OutsideChanges.syncLine(LauncherInfo.of(Launcher.MODRINTH_APP), true);
		assertNotNull(line);
		assertEquals("rigtune.outside.sync_line", key(line));
		assertNull(OutsideChanges.syncLine(LauncherInfo.of(Launcher.MODRINTH_APP), false), "nothing written now");
		for (Launcher other : Launcher.values()) {
			if (other != Launcher.MODRINTH_APP) {
				assertNull(OutsideChanges.syncLine(LauncherInfo.of(other), true), other.name());
			}
		}
	}

	@Test
	void anApplyAddsItsVanillaKeysToTheWatchedOnes() {
		V05Hooks.ApplyFacts facts = new V05Hooks.ApplyFacts("e1", List.of(set("vanilla.renderClouds", "fancy", "fast"), set("vanilla.fullscreen", "false", "true"),
				set("sodium.quality.weather_quality", "FANCY", "FAST"), set("vanilla.gamma", "1.0", "0.5")), Set.of(), 2, 0, 1, false, 0);
		OutsideChanges.watch(facts);
		assertEquals(Set.of("vanilla.renderClouds"), OutsideChanges.watched().keySet(), "changeable vanilla keys only, never fullscreen");
	}

	@Test
	void theStopSnapshotIsNothingDuringABenchmarkOrTryItOrWithNothingWatched() {
		Map<String, String> watched = Map.of("vanilla.renderClouds", "fast");
		Map<String, String> now = Map.of("renderClouds", "fancy");
		assertEquals(Map.of("vanilla.renderClouds", "fancy"), OutsideChanges.stopSnapshot(false, watched, () -> now));
		assertNull(OutsideChanges.stopSnapshot(true, watched, () -> {
			throw new AssertionError("the options aren't read while busy");
		}));
		assertNull(OutsideChanges.stopSnapshot(false, null, () -> now), "the start hook hasn't read the journal");
		assertNull(OutsideChanges.stopSnapshot(false, Map.of(), () -> now), "RigTune never applied a vanilla key");
		assertNull(OutsideChanges.stopSnapshot(false, watched, Map::of), "none of them in the game's options");
	}

	// AC4h.3: the stop handler does no I/O beyond one AwarenessStore.update (setOptionsAtExit is one update).
	@Test
	void theStopHandlerDoesOneUpdateAndNothingElse() throws IOException {
		String source = Files.readString(RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/awareness/OutsideChanges.java"));
		Matcher body = Pattern.compile("public static void snapshotAtStop\\(.*?\\n\\t}\\n", Pattern.DOTALL).matcher(source);
		assertTrue(body.find());
		String handler = body.group();
		assertEquals(1, handler.split("setOptionsAtExit\\(", -1).length - 1, handler);
		for (String io : List.of("Files.", "update(", "read(", "ClientJournal", "takeOptionsAtExit")) {
			assertFalse(handler.contains(io), io + " in " + handler);
		}
	}

	@Test
	void theNoticeNamesAChangeAndCountsTheRest() {
		Notice notice = OutsideChanges.notice("settings-changed-outside:1", CHANGES, Map.of(), LauncherInfo.UNKNOWN);
		assertEquals(NoticePriority.SETTINGS_CHANGED_OUTSIDE, notice.priority());
		assertEquals("3 settings were changed outside the game since you last played (e.g. Render clouds: fast → fancy).", notice.message().english());
		assertFalse(notice.dismissible(), "Keep is its answer");
		assertEquals(List.of(OutsideChanges.REAPPLY, OutsideChanges.KEEP), notice.actions().stream().map(NoticeAction::id).toList());
		assertEquals(List.of("Apply RigTune's values again", "Keep"), notice.actions().stream().map(a -> a.label().english()).toList());
		String detail = notice.detail().english();
		assertTrue(detail.startsWith("Changed: Render clouds: fast → fancy, Max fps: 120 → 260, Entity shadows: false → true."), detail);
		assertFalse(detail.contains("Modrinth"), detail);
		Notice one = OutsideChanges.notice("settings-changed-outside:1", CHANGES.subList(0, 1), Map.of(), LauncherInfo.UNKNOWN);
		assertEquals("A setting was changed outside the game since you last played (Render clouds: fast → fancy).", one.message().english());
	}

	@Test
	void theModrinthAppsStepsOnlyInThatLauncher() {
		RulesDocument.SettingLabel clouds = new RulesDocument.SettingLabel();
		clouds.name = "Clouds";
		clouds.values = Map.of("fast", "Fast", "fancy", "Fancy");
		Notice notice = OutsideChanges.notice("settings-changed-outside:1", CHANGES, Map.of("vanilla.renderClouds", clouds), LauncherInfo.of(Launcher.MODRINTH_APP));
		assertTrue(notice.message().english().contains("(e.g. Clouds: Fast → Fancy)"), notice.message().english());
		String detail = notice.detail().english();
		assertTrue(detail.contains("App settings → Synced settings → Sync game options"), detail);
		assertTrue(detail.contains("Instance settings → Sync overrides → Unsync game settings"), detail);
	}

	@Test
	void applyingAgainIsAnOrdinaryApplyOfRigTunesValues() {
		List<Recommendation> again = OutsideChanges.reapply(CHANGES, Map.of());
		assertEquals(List.of("set:vanilla.renderClouds", "set:vanilla.maxFps"), again.stream().map(Recommendation::id).toList(),
				"entity shadows is RigTune's value already");
		Action.SetSetting set = (Action.SetSetting) again.getFirst().action();
		assertEquals(new Action.SetSetting("vanilla.renderClouds", "fancy", "fast"), set);
		assertTrue(again.stream().allMatch(r -> r.category() == Category.SETTING && r.selectedByDefault()));
	}

	@Test
	void keepOrApplyRetiresTheNoticeOnce() {
		OutsideChanges.found(CHANGES);
		assertNotNull(OutsideChanges.take());
		assertNull(OutsideChanges.take(), "acted on once");
		OutsideChanges.found(List.of());
		assertNull(OutsideChanges.take(), "nothing changed: no notice");
	}

	private static Recommendation set(String key, String from, String to) {
		return new Recommendation("set:" + key, Category.SETTING, Impact.LOW, key, "", new Action.SetSetting(key, from, to), true);
	}
}
