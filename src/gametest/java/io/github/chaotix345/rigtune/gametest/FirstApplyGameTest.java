package io.github.chaotix345.rigtune.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.FirstRunService;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.notice.FirstRunNoticeSource;
import io.github.chaotix345.rigtune.client.ui.FirstApplyScreen;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.HowItWorksScreen;
import io.github.chaotix345.rigtune.client.ui.NoticeScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.RowFocus;
import io.github.chaotix345.rigtune.client.ui.Texts;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// docs/v0.5/SPEC.md 8 (C02; AC8.1, AC8.3-AC8.9, AC8.12, AC8.15, AC8.16, AC8.18): the first-time Apply trust flow on the
// real controller with RigTune's network off (X1). The first entrypoint, so its run dir is fresh and the player is NEW from
// what's on disk; in another position (a filtered or split run) it restores a new player through FirstRunService's test
// seam, and under CI as the first entrypoint a run dir that isn't fresh fails it. The guide on the real RigTune screen at
// X12's sizes (the English message within its room, "…" and NoticeScreen below 400 scaled px, narrated with its detail),
// How it works, then the real Apply button: the confirmation for the entry Apply journaled, its rows the same as History's
// for it, notes that match the rows, History… / Undo this Apply / Done / Esc, the guide gone without Got it, and the Apply
// undone. Then a second Apply opens nothing, Got it (after the seam) is stored in awareness.json, a direct controller.apply
// retires the new player without the screen, and the recommendation list keeps 0.4.0's height (AC8.18).
public class FirstApplyGameTest implements FabricClientGameTest {
	// AC8.18: 0.4.0's RigTuneScreen list height over the StubController's report with RigTune's network off and no notice,
	// at 640x480 GUI scale 2 (list y 76, 4 header lines), measured once by a throwaway game test on a branch from the v0.4.0
	// tag (docs/v0.5/design/ws-f.md, "The 0.4.0 list-height baseline").
	private static final int V040_LIST_HEIGHT_640X480 = 76;
	private static final int NOTICE_LINE = 16;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		V05TestContext v05 = V05TestContext.of(context);
		RealController real = v05.realController();
		context.waitFor(mc -> real.report() != null, 1200);
		FirstRunService firstRun = real.v05().firstRun();
		context.waitFor(mc -> firstRun.status() != FirstRun.Status.UNKNOWN, 1200);
		// AC8.15: the start hook read the disk on Probes.EXECUTOR, never on the render thread.
		check(firstRun.loadedOn() != null && firstRun.loadedOn().startsWith("RigTune worker"), "FirstRunService.load ran on " + firstRun.loadedOn());
		boolean network = GameTestNet.set(context, real, false);
		List<String> toUndo = new ArrayList<>();
		try {
			newPlayer(context, v05, firstRun);
			Report report = real.report();
			check(report.recommendations().stream().anyMatch(Recommendation::appliable),
					"the network-off report has something to apply: " + report.recommendations().stream().map(Recommendation::id).toList());
			openRigTuneFromTheTitleScreen(context);
			guideAtEverySize(context, v05);
			howItWorks(context, v05);
			noticeScreenAt640(context, v05);
			String entryId = confirmation(context, v05, real, firstRun);
			toUndo.add(entryId);
			undoApply(context, real, entryId);
			toUndo.remove(entryId);
			toUndo.addAll(secondApplyOpensNothing(context, real, firstRun));
			for (String id : List.copyOf(toUndo)) {
				undoApply(context, real, id);
				toUndo.remove(id);
			}
			gotIt(context, v05, real, firstRun);
			directApplyRetiresNew(context, real, firstRun);
			listHeight(context, v05);
		} finally {
			for (String id : toUndo) {
				try {
					undoApply(context, real, id);
				} catch (Throwable t) {
					RigTune.LOGGER.error("FirstApplyGameTest: couldn't undo {}", id, t);
				}
			}
			GameTestNet.set(context, real, network);
			v05.resize(854, 480, 0);
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		}
		optedInLine(context, v05);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		RigTune.LOGGER.info("FirstApplyGameTest: the guide, How it works, the confirmation, Got it and the list height checked");
	}

	// AC8.1/AC8.16: a fresh run dir makes a new player from what's on disk. Not first in its JVM, the test seam restores one;
	// under CI as the first entrypoint the run dir must be fresh.
	private static void newPlayer(ClientGameTestContext context, V05TestContext v05, FirstRunService firstRun) {
		boolean fresh = context.computeOnClient(mc -> FirstRun.isNew(ClientJournal.get(), v05.configDir()));
		String firstEntrypoint = FabricLoader.getInstance().getEntrypointContainers("fabric-client-gametest", FabricClientGameTest.class).getFirst()
				.getDefinition();
		boolean first = firstEntrypoint.endsWith("." + FirstApplyGameTest.class.getSimpleName());
		if (fresh) {
			check(firstRun.status() == FirstRun.Status.NEW, "a fresh run dir is a new player: " + firstRun.status());
			return;
		}
		check(!(first && System.getenv("CI") != null), "FirstApplyGameTest is the first entrypoint but its run dir isn't fresh");
		RigTune.LOGGER.warn("FirstApplyGameTest: not a fresh run dir (first entrypoint: {}); restoring a new player through the test seam",
				firstEntrypoint);
		firstRun.forceStatusForTests(FirstRun.Status.NEW);
		check(!AwarenessStore.shared(v05.configDir()).dismissed().contains(FirstRunNoticeSource.KEY), "the guide wasn't dismissed in this run dir");
	}

	private static void openRigTuneFromTheTitleScreen(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		context.clickScreenButton("rigtune.button");
		context.waitForScreen(RigTuneScreen.class);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(3);
	}

	// AC8.1, AC8.3: the guide is the top notice with How it works and Got it; the English message fits its room at each
	// size (font.width against the notice text's Tab stop, which spans exactly that room); below 400 scaled px the line is
	// the message and "…"; the notice text narrates message and detail (AC8.12).
	private static void guideAtEverySize(ClientGameTestContext context, V05TestContext v05) {
		for (int[] size : V05TestContext.SIZES) {
			v05.resize(size[0], size[1], size[2]);
			String name = size[0] + "x" + size[1] + "-scale" + size[2];
			context.runOnClient(mc -> {
				RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
				Notice shown = screen.shownNotice();
				check(shown != null && shown.key().equals(FirstRunNoticeSource.KEY), name + ": the guide is the top notice: " + shown);
				Component message = Texts.component(shown.message());
				RowFocus text = Screens.getWidgets(screen).stream().filter(w -> w instanceof RowFocus).map(RowFocus.class::cast).findFirst()
						.orElseThrow(() -> new AssertionError(name + ": no notice text stop"));
				int width = mc.font.width(message);
				check(width <= text.getWidth(), name + ": the guide's message (" + width + " px) is clipped to " + text.getWidth() + " px");
				RigTune.LOGGER.info("FirstApplyGameTest: {}: the guide's message is {} px in a {} px room ({} other notices)", name, width,
						text.getWidth(), screen.otherNotices());
				if (screen.width < 400) {
					check(button(screen, "rigtune.notice.open") != null, name + ": the \"…\" button");
				} else {
					check(button(screen, "rigtune.firstrun.action.how") != null && button(screen, "rigtune.firstrun.action.got_it") != null,
							name + ": How it works and Got it inline");
				}
			});
			context.takeScreenshot("firstapply-guide-" + name);
		}
		v05.resize(854, 480, 2);
		String said = tabUntilNarrates(context, "the guide", "New to RigTune?");
		check(said.contains("Only the ticked"), "the guide's detail is narrated with it: " + said);
	}

	private static void howItWorks(ClientGameTestContext context, V05TestContext v05) {
		press(context, "rigtune.firstrun.action.how");
		context.waitFor(mc -> mc.gui.screen() instanceof HowItWorksScreen && rows(mc) >= 5, 100);
		layoutAtEverySize(context, v05, "firstapply-how");
		press(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> FirstRunNoticeSource.KEY.equals(((RigTuneScreen) mc.gui.screen()).shownNotice().key())),
				"back on the RigTune screen, the guide is still there");
	}

	// AC8.3: at 640x480 NoticeScreen offers both of the guide's actions.
	private static void noticeScreenAt640(ClientGameTestContext context, V05TestContext v05) {
		v05.resize(640, 480, 2);
		press(context, "rigtune.notice.open");
		context.waitForScreen(NoticeScreen.class);
		context.waitTicks(2);
		context.runOnClient(mc -> {
			NoticeScreen screen = (NoticeScreen) mc.gui.screen();
			check(screen.shown().stream().anyMatch(n -> n.key().equals(FirstRunNoticeSource.KEY)), "NoticeScreen lists the guide: " + screen.shown());
			check(button(screen, "rigtune.firstrun.action.how") != null && button(screen, "rigtune.firstrun.action.got_it") != null,
					"NoticeScreen offers How it works and Got it");
		});
		context.takeScreenshot("firstapply-guide-noticescreen-640x480-scale2");
		press(context, "gui.done");
		context.waitForScreen(RigTuneScreen.class);
		v05.resize(854, 480, 2);
	}

	// AC8.5-AC8.8: the real Apply button opens the confirmation for the entry Apply journaled; its change rows are History's
	// rows for that entry; the notes match the rows; History…, Undo this Apply, Done and Esc work; the guide is gone without
	// Got it. Returns the entry id.
	private static String confirmation(ClientGameTestContext context, V05TestContext v05, RealController real, FirstRunService firstRun) {
		RigTuneScreen rigtune = context.computeOnClient(mc -> (RigTuneScreen) mc.gui.screen());
		check(real.firstApplyPending(), "the first Apply is pending");
		press(context, "rigtune.screen.apply.count");
		context.waitFor(mc -> mc.gui.screen() instanceof FirstApplyScreen f && !f.loading() && f.view() != null, 200);
		context.waitTicks(2);
		String applyStatus = context.computeOnClient(mc -> statusLine(rigtune));
		check(applyStatus != null && !applyStatus.isEmpty(), "Apply set the RigTune screen's status line");
		check(firstRun.status() == FirstRun.Status.RETURNING && !real.firstApplyPending(), "the Apply retired the new player: " + firstRun.status());
		String entryId = context.computeOnClient(mc -> ((FirstApplyScreen) mc.gui.screen()).entryId());
		HistoryModel.View history = context.computeOnClient(mc -> real.history());
		check(history != null && !history.entries().isEmpty() && history.entries().getFirst().id().equals(entryId),
				"the confirmation is for the entry Apply journaled (the newest): " + entryId);
		List<String> grouped = new ArrayList<>();
		List<String> statuses = history.entries().getFirst().changes().stream().map(HistoryModel.Change::status).toList();
		statuses.stream().filter(JournalChange.APPLIED::equals).forEach(grouped::add);
		statuses.stream().filter(JournalChange.STAGED::equals).forEach(grouped::add);
		statuses.stream().filter(s -> !JournalChange.APPLIED.equals(s) && !JournalChange.STAGED.equals(s)).forEach(grouped::add);
		boolean staged = grouped.contains(JournalChange.STAGED);
		boolean downloading = real.downloading();
		List<String> rows = context.computeOnClient(mc -> {
			FirstApplyScreen screen = (FirstApplyScreen) mc.gui.screen();
			check(screen.changeStatuses().equals(grouped), "the rows' statuses are the entry's, grouped: " + screen.changeStatuses() + " vs " + grouped);
			List<String> notes = screen.notes();
			check(notes.contains("rigtune.firstrun.applied.restart") == staged, "the restart note iff a row waits for the restart: " + notes);
			check(notes.contains("rigtune.firstrun.applied.no_restart") == (!staged && !downloading), "no restart needed iff none waits: " + notes);
			check(notes.getLast().equals("rigtune.firstrun.applied.undo_hint"), "the undo hint last: " + notes);
			String narration = screen.getNarrationMessage().getString();
			String outcome = Component.translatable(staged ? "rigtune.firstrun.applied.restart" : "rigtune.firstrun.applied.no_restart").getString();
			check(narration.contains(Component.translatable("rigtune.firstrun.applied.title").getString()) && narration.contains(outcome),
					"the open narration has the title and the restart outcome: " + narration);
			return screen.changeRowText();
		});
		RigTune.LOGGER.info("FirstApplyGameTest: the first Apply ({}): statuses {}, downloading {}, rows:\n{}", entryId, grouped, downloading,
				String.join("\n", rows));
		layoutAtEverySize(context, v05, "firstapply-confirmation");

		// AC8.6 and AC8.8: History… opens History with that entry selected, whose rows are the confirmation's, at the same size.
		press(context, "rigtune.history.open");
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen h && !h.loading() && h.view() != null, 200);
		context.waitTicks(2);
		List<String> historyRows = context.computeOnClient(mc -> {
			HistoryScreen screen = (HistoryScreen) mc.gui.screen();
			check(entryId.equals(screen.selected()), "History… opens History with that entry selected: " + screen.selected());
			return screen.changeRowText();
		});
		check(rows.equals(historyRows), "the confirmation's rows are History's:\n" + rows + "\nvs\n" + historyRows);
		context.takeScreenshot("firstapply-history-854x480-scale2");
		press(context, "gui.done");
		context.waitFor(mc -> mc.gui.screen() instanceof FirstApplyScreen f && !f.loading(), 200);

		// AC8.8: Undo this Apply opens UndoScreen with a plan for that entry (cancelled here).
		press(context, "rigtune.firstrun.applied.undo");
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen u && u.plan() != null, 200);
		context.runOnClient(mc -> {
			UndoScreen undo = (UndoScreen) mc.gui.screen();
			check(entryId.equals(undo.entryId()) && undo.plan().problem() == null && !undo.plan().items().isEmpty(),
					"Undo this Apply plans that entry: " + undo.plan());
		});
		context.takeScreenshot("firstapply-undo-854x480-scale2");
		press(context, "gui.cancel");
		context.waitFor(mc -> mc.gui.screen() instanceof FirstApplyScreen f && !f.loading(), 200);

		// AC8.8 and AC8.5: Done returns to the RigTune screen with Apply's status line; the guide went with the Apply.
		press(context, "gui.done");
		context.waitFor(mc -> mc.gui.screen() == rigtune, 40);
		context.waitTicks(2);
		context.runOnClient(mc -> {
			check(applyStatus.equals(statusLine(rigtune)), "the status line is Apply's: " + statusLine(rigtune) + " vs " + applyStatus);
			Notice shown = rigtune.shownNotice();
			check(shown == null || !shown.key().equals(FirstRunNoticeSource.KEY), "the guide is gone after the first Apply: " + shown);
		});
		check(!AwarenessStore.shared(v05.configDir()).dismissed().contains(FirstRunNoticeSource.KEY), "gone without Got it");
		context.takeScreenshot("firstapply-after-854x480-scale2");

		// AC8.8: Esc returns to the opener as well.
		context.runOnClient(mc -> mc.gui.setScreen(new FirstApplyScreen(rigtune, real, entryId, Component.empty())));
		context.waitFor(mc -> mc.gui.screen() instanceof FirstApplyScreen f && !f.loading(), 200);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> mc.gui.screen() == rigtune, 40);
		return entryId;
	}

	// AC8.9: with the player RETURNING, the Apply button applies and opens nothing. Returns the entry ids to undo.
	private static List<String> secondApplyOpensNothing(ClientGameTestContext context, RealController real, FirstRunService firstRun) {
		check(firstRun.status() == FirstRun.Status.RETURNING, "still returning");
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitFor(mc -> real.report() != null && button(mc.gui.screen(), "rigtune.screen.apply.count") != null, 200);
		String before = newestEntry(context, real);
		press(context, "rigtune.screen.apply.count");
		context.waitTicks(10);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof RigTuneScreen), "the second Apply opened nothing");
		String after = newestEntry(context, real);
		return after != null && !after.equals(before) ? List.of(after) : List.of();
	}

	// AC8.4: Got it (on a new player again, through the seam) hides the guide and stores firstrun.guide in awareness.json.
	private static void gotIt(ClientGameTestContext context, V05TestContext v05, RealController real, FirstRunService firstRun) {
		firstRun.forceStatusForTests(FirstRun.Status.NEW);
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitFor(mc -> ((RigTuneScreen) mc.gui.screen()).shownNotice() != null
				&& FirstRunNoticeSource.KEY.equals(((RigTuneScreen) mc.gui.screen()).shownNotice().key()), 200);
		press(context, "rigtune.firstrun.action.got_it");
		context.waitTicks(2);
		context.runOnClient(mc -> {
			Notice shown = ((RigTuneScreen) mc.gui.screen()).shownNotice();
			check(shown == null || !shown.key().equals(FirstRunNoticeSource.KEY), "Got it hid the guide: " + shown);
		});
		check(AwarenessStore.shared(v05.configDir()).dismissed().contains(FirstRunNoticeSource.KEY), "Got it is stored in awareness.json");
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), real)));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> real.notices().stream().noneMatch(n -> n.key().equals(FirstRunNoticeSource.KEY))),
				"still hidden on the next RigTune screen");
		check(real.firstApplyPending(), "Got it hides the guide only; the confirmation still follows the first Apply");
	}

	// AC8.9: an Apply by any other path (here a direct controller.apply) retires the new player without the confirmation.
	private static void directApplyRetiresNew(ClientGameTestContext context, RealController real, FirstRunService firstRun) {
		Screen before = context.computeOnClient(mc -> mc.gui.screen());
		context.runOnClient(mc -> real.apply(List.of()));
		context.waitTicks(5);
		check(firstRun.status() == FirstRun.Status.RETURNING, "a direct apply retired the new player: " + firstRun.status());
		check(context.computeOnClient(mc -> mc.gui.screen() == before), "a direct apply opened no screen");
	}

	// AC8.18: over the StubController's report (the 0.4.0 baseline's fixture), with only the guide as notice the list keeps
	// at least 0.4.0's height minus one notice line, and once it's dismissed at least 0.4.0's height, under RIGTUNE and
	// LAUNCHER (P0.4 adds no header line there).
	private static void listHeight(ClientGameTestContext context, V05TestContext v05) {
		GuideController controller = new GuideController(v05.stub());
		v05.resize(640, 480, 2);
		for (ModFilesPolicy policy : List.of(ModFilesPolicy.RIGTUNE, ModFilesPolicy.LAUNCHER)) {
			controller.policy = policy;
			controller.guideLine = LauncherModText.guideLine(policy, LauncherInfo.UNKNOWN, false);
			for (boolean guide : List.of(true, false)) {
				controller.guide = guide;
				String variant = policy.name().toLowerCase(Locale.ROOT) + (guide ? "-guide" : "-dismissed");
				int height = openAndMeasure(context, controller, variant, guide);
				check(height >= V040_LIST_HEIGHT_640X480 - (guide ? NOTICE_LINE : 0), variant + ": the list is " + height + " px");
				context.takeScreenshot("firstapply-list-" + variant + "-640x480-scale2");
			}
		}
		v05.resize(854, 480, 2);
	}

	// AC8.3 and AC4e.2 (with WS-L1; the later to merge closes them): the guide at 640x480 with P0.4's opted-in warning in the
	// offline line's place (RigTune's network back as it was, the opt-in set for this screen only); the list-height check
	// still holds.
	private static void optedInLine(ClientGameTestContext context, V05TestContext v05) {
		ClientSettings settings = ClientSettings.shared(v05.configDir());
		boolean before = settings.modFilesByRigTune;
		GuideController controller = new GuideController(v05.stub());
		controller.guideLine = LauncherModText.guideLine(ModFilesPolicy.RIGTUNE, LauncherInfo.UNKNOWN, true);
		try {
			settings.modFilesByRigTune = true;
			v05.resize(640, 480, 2);
			int height = openAndMeasure(context, controller, "opted in, network " + (settings.networkEnabled ? "on" : "off"), true);
			check(height >= V040_LIST_HEIGHT_640X480 - NOTICE_LINE, "opted in: the list is " + height + " px");
			context.takeScreenshot("firstapply-guide-optedin-640x480-scale2");
		} finally {
			settings.modFilesByRigTune = before;
			v05.resize(854, 480, 0);
		}
	}

	// Opens the RigTune screen over the controller and returns its list's height; the guide shows exactly when expected.
	private static int openAndMeasure(ClientGameTestContext context, RigTuneController controller, String name, boolean guide) {
		context.runOnClient(mc -> mc.gui.setScreen(new RigTuneScreen(new TitleScreen(), controller)));
		context.waitFor(mc -> mc.gui.screen() instanceof RigTuneScreen && rows(mc) >= 10, 200);
		context.getInput().setCursorPos(1, 1);
		context.waitTicks(3);
		return context.computeOnClient(mc -> {
			RigTuneScreen screen = (RigTuneScreen) mc.gui.screen();
			Notice shown = screen.shownNotice();
			check(guide == (shown != null && shown.key().equals(FirstRunNoticeSource.KEY)), name + ": the guide shows iff canned: " + shown);
			int height = list(mc).getHeight();
			RigTune.LOGGER.info("FirstApplyGameTest: {}: list height {} at 640x480 scale 2 (0.4.0: {}); header {}", name, height, V040_LIST_HEIGHT_640X480,
					screen.headerLines().stream().map(Component::getString).toList());
			return height;
		});
	}

	// --- helpers

	// The history and the undo plan read the game's options on the render thread (RealController waits for it there), so
	// they're asked on it: the test thread holding the render thread's tick would deadlock.
	private static void undoApply(ClientGameTestContext context, RealController real, String entryId) {
		UndoPlan plan = context.computeOnClient(mc -> real.undoPlanFor(entryId));
		check(plan != null && plan.problem() == null, "an undo plan for " + entryId + ": " + plan);
		Component done = context.computeOnClient(mc -> real.undo(plan));
		RigTune.LOGGER.info("FirstApplyGameTest: undid {}: {}", entryId, done.getString());
		context.waitFor(mc -> real.report() != null, 1200);
	}

	private static @Nullable String newestEntry(ClientGameTestContext context, RealController real) {
		HistoryModel.View view = context.computeOnClient(mc -> real.history());
		return view == null || view.entries().isEmpty() ? null : view.entries().getFirst().id();
	}

	// The RigTune screen's status line (a private field: RigTuneScreen's API stays as 0.4 had it, PLAN "Hotspots").
	private static @Nullable String statusLine(RigTuneScreen screen) {
		try {
			Field field = RigTuneScreen.class.getDeclaredField("status");
			field.setAccessible(true);
			Component status = (Component) field.get(screen);
			return status == null ? null : status.getString();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("RigTuneScreen.status", e);
		}
	}

	// X12: every widget inside the screen, no two overlapping, every label fitting, at the three sizes and 854x480 at scale 3
	// (the lists scroll there), with a screenshot of each.
	private static void layoutAtEverySize(ClientGameTestContext context, V05TestContext v05, String name) {
		List<int[]> sizes = new ArrayList<>(List.of(V05TestContext.SIZES));
		sizes.add(V05TestContext.SCROLLING);
		for (int[] size : sizes) {
			v05.resize(size[0], size[1], size[2]);
			String at = name + "-" + size[0] + "x" + size[1] + "-scale" + size[2];
			context.runOnClient(mc -> {
				Screen screen = mc.gui.screen();
				List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w.visible).toList();
				for (AbstractWidget w : widgets) {
					check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
							at + ": " + describe(w) + " outside " + screen.width + "x" + screen.height);
					if (!(w instanceof AbstractSelectionList<?>) && !(w instanceof RowFocus)) {
						check(mc.font.width(w.getMessage()) <= w.getWidth() - 4, at + ": label doesn't fit " + describe(w));
					}
				}
				for (int i = 0; i < widgets.size(); i++) {
					for (int j = i + 1; j < widgets.size(); j++) {
						AbstractWidget a = widgets.get(i);
						AbstractWidget b = widgets.get(j);
						check(!(a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()),
								at + ": " + describe(a) + " overlaps " + describe(b));
					}
				}
			});
			context.takeScreenshot(at);
		}
		v05.resize(854, 480, 2);
	}

	private static String describe(AbstractWidget w) {
		return "'" + w.getMessage().getString() + "' [" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
	}

	private static @Nullable Button button(Screen screen, String key) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast).findFirst().orElse(null);
	}

	private static void press(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = button(mc.gui.screen(), key);
			check(button != null, "no button " + key + " on " + mc.gui.screen());
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		});
		context.waitTicks(2);
	}

	private static ContainerObjectSelectionList<?> list(Minecraft mc) {
		return (ContainerObjectSelectionList<?>) Screens.getWidgets(mc.gui.screen()).stream()
				.filter(w -> w instanceof ContainerObjectSelectionList<?>).findFirst()
				.orElseThrow(() -> new AssertionError("no list on " + mc.gui.screen()));
	}

	private static int rows(Minecraft mc) {
		return list(mc).children().size();
	}

	// Tab (at most 40 presses, from nothing focused) until the focused widget narrates `text`; returns what it narrates.
	private static String tabUntilNarrates(ClientGameTestContext context, String name, String text) {
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> mc.gui.screen().clearFocus());
		for (int i = 0; i < 40; i++) {
			context.getInput().pressKey(InputConstants.KEY_TAB);
			context.waitTicks(1);
			String said = context.computeOnClient(FirstApplyGameTest::focusedNarration);
			if (said.contains(text)) {
				return said;
			}
		}
		throw new AssertionError(name + ": Tab never reached a stop that narrates \"" + text + "\"");
	}

	// What the focused widget narrates (ScreenNarrationCollector over its own narration), or "".
	private static String focusedNarration(Minecraft mc) {
		ComponentPath path = mc.gui.screen().getCurrentFocusPath();
		if (path == null || !(path.leafComponent() instanceof AbstractWidget widget)) {
			return "";
		}
		ScreenNarrationCollector collector = new ScreenNarrationCollector();
		//? if >=26.3 {
		/*collector.update(widget::updateNarration, net.minecraft.client.gui.narration.NarrationTrigger.KEYBOARD);
		*///?} else
		collector.update(widget::updateNarration);
		return collector.collectNarrationText(false);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}

	// The StubController's report (0.4.0's baseline fixture) with the guide as its only notice, and a canned modFiles().
	private static final class GuideController extends ForwardingController {
		volatile ModFilesPolicy policy = ModFilesPolicy.RIGTUNE;
		volatile boolean guide = true;
		volatile @Nullable Text guideLine;

		GuideController(RigTuneController stub) {
			super(stub);
		}

		@Override
		public List<Notice> notices() {
			return guide ? List.of(FirstRunNoticeSource.notice(policy, guideLine)) : List.of();
		}

		@Override
		public ModFilesPolicy modFiles() {
			return policy;
		}
	}
}
