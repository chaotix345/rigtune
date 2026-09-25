package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// docs/v0.3/SPEC.md item 6 (AC6.3) and 3e (AC3.5's screen half): the History screen over a seeded history.json (an apply
// that was undone and its undo, an older apply, a benchmark result that changed its render distance again, and an apply
// whose staged update failed at the last exit), with screenshots at the reference sizes and at 640x480 GUI scale 2 (review
// X-M2). Then Undo this on the older apply: its entity shadows change is reverted now, its render distance change is
// skipped ("Changed again by a later apply", review B-H1), and the undo is journaled against that apply.
public class HistoryGameTest implements FabricClientGameTest {
	private static final int[][] SIZES = {{854, 480, 2}, {1280, 720, 3}, {1280, 720, 2}, {640, 480, 2}};
	private static final String SHADOWS = "vanilla.entityShadows";
	private static final String RENDER_DISTANCE = "vanilla.renderDistance";

	private final Path configDir = FabricLoader.getInstance().getConfigDir();
	private final Path modsDir = InstanceDirs.modsDir(FabricLoader.getInstance().getGameDir());
	private final Path historyFile = Journal.file(configDir);
	private final Path lastApplyFile = ApplyResult.defaultPath(configDir);
	private final Journal journal = new Journal(configDir, null, null, (message, error) -> {
		throw new AssertionError(message, error);
	});

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController controller = RigTuneClient.controller();
		context.runOnClient(mc -> controller.discardPending());
		byte[] history = read(historyFile);
		byte[] lastApply = read(lastApplyFile);
		boolean shadows = context.computeOnClient(mc -> mc.options.entityShadows().get());
		int renderDistance = context.computeOnClient(mc -> mc.options.renderDistance().get());
		try {
			run(context, controller, shadows, renderDistance);
		} finally {
			context.runOnClient(mc -> {
				mc.options.entityShadows().set(shadows);
				mc.options.renderDistance().set(renderDistance);
				mc.gui.setScreen(new TitleScreen());
			});
			restore(historyFile, history);
			restore(lastApplyFile, lastApply);
			resize(context, 854, 480, 0);
		}
	}

	private void run(ClientGameTestContext context, RigTuneController controller, boolean shadows, int renderDistance) {
		// The game is where the older apply and the benchmark left it: shadows flipped, render distance +3.
		context.runOnClient(mc -> {
			mc.options.entityShadows().set(!shadows);
			mc.options.renderDistance().set(renderDistance + 3);
		});
		String shadowsBefore = Boolean.toString(shadows);
		String shadowsAfter = Boolean.toString(!shadows);
		String rd0 = Integer.toString(renderDistance);
		String rd1 = Integer.toString(renderDistance + 1);
		String rd3 = context.computeOnClient(mc -> SettingsBridge.readVanilla(mc.options).get("renderDistance"));
		check(rd3.equals(Integer.toString(renderDistance + 3)), "render distance reads back as a number: " + rd3);

		JournalChange undone = JournalChange.setting("vanilla.simulationDistance", "12", "8", JournalChange.REVERTED, null);
		JournalChange shadowsChange = JournalChange.setting(SHADOWS, shadowsBefore, shadowsAfter, JournalChange.APPLIED, null);
		JournalChange rdChange = JournalChange.setting(RENDER_DISTANCE, rd0, rd1, JournalChange.APPLIED, null);
		List<Op> update = PendingActions.group(Op.disableFile(modsDir.resolve("fake-mod-1.0.jar")).withAttempts(1),
				Op.enableFile(modsDir.resolve("fake-mod-1.1.jar.rigtune-pending"), modsDir.resolve("fake-mod-1.1.jar")).withModId("fakemod").withAttempts(1));
		JournalEntry apply0 = new JournalEntry("hist-apply-0", "2026-09-20T09:00:00Z", JournalEntry.APPLY, "0.3.0", "26.2", null, List.of(undone));
		JournalEntry undo0 = new JournalEntry("hist-undo-0", "2026-09-20T09:05:00Z", JournalEntry.UNDO, "0.3.0", "26.2", apply0.id(),
				List.of(JournalChange.setting("vanilla.simulationDistance", "8", "12", JournalChange.APPLIED, null).reverting(undone.id())));
		JournalEntry applyA = new JournalEntry("hist-apply-a", "2026-09-21T18:30:00Z", JournalEntry.APPLY, "0.3.0", "26.2", null,
				List.of(shadowsChange, rdChange));
		JournalEntry benchmark = new JournalEntry("hist-benchmark", "2026-09-22T20:00:00Z", JournalEntry.BENCHMARK, "0.3.0", "26.2", null,
				List.of(JournalChange.setting(RENDER_DISTANCE, rd1, rd3, JournalChange.APPLIED, null)));
		JournalEntry applyB = new JournalEntry("hist-apply-b", "2026-09-24T23:08:50Z", JournalEntry.APPLY, "0.3.0", "26.2", null, List.of(
				JournalChange.file(JournalChange.DISABLE, "fakemod", "fake-mod-1.0.jar", JournalChange.STAGED, update.get(0).id(), update.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, "fakemod", "fake-mod-1.1.jar", JournalChange.STAGED, update.get(1).id(), update.get(1).group())));
		try {
			Files.deleteIfExists(historyFile);
			check(journal.update(entries -> List.of(apply0, undo0, applyA, benchmark, applyB)), "seeded history.json");
			// The last exit's helper run: the disable hit a sharing violation (as in the user's real 0.1.0 run), so the enable wasn't applied.
			Path jar = modsDir.resolve("fake-mod-1.0.jar");
			new ApplyResult("2026-09-24T23:09:01Z", List.of(
					new ApplyResult.OpResult(update.get(0), ApplyResult.Status.FAILED, "Gave up after 10 attempt(s): java.nio.file.FileSystemException: "
							+ jar + " -> " + jar + ".disabled: The process cannot access the file because it is being used by another process"),
					new ApplyResult.OpResult(update.get(1), ApplyResult.Status.FAILED, "Not applied because disabling fake-mod-1.0.jar failed")))
					.save(lastApplyFile);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		// The main screen's footer: History… in place of Undo last / Undo all.
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		resize(context, 640, 480, 2);
		check(context.computeOnClient(mc -> findButton(mc.gui.screen(), "rigtune.history.open") != null
				&& findButton(mc.gui.screen(), "rigtune.screen.undo_last") == null && findButton(mc.gui.screen(), "rigtune.screen.undo_all") == null),
				"History… replaces Undo last and Undo all");
		checkLayout(context, "main 640x480@2");
		context.takeScreenshot("history-main-640x480-scale2");
		resize(context, 854, 480, 2);

		pressByKey(context, "rigtune.history.open");
		HistoryModel.View view = waitForHistory(context);
		check(view.state() == Journal.State.OK, "history read: " + view.state());
		check(view.entries().stream().map(HistoryModel.Entry::id).toList()
						.equals(List.of(applyB.id(), benchmark.id(), applyA.id(), undo0.id(), apply0.id())),
				"entries newest first: " + view.entries());
		check(applyB.id().equals(context.computeOnClient(mc -> ((HistoryScreen) mc.gui.screen()).selected())), "the newest entry is open");
		check(entry(view, applyA.id()).undoable() && entry(view, benchmark.id()).undoable(), "applies with something left can be undone");
		check(!entry(view, undo0.id()).undoable() && !entry(view, apply0.id()).undoable(), "undos and undone applies can't");

		// AC3.5: the failed staged update shows the helper's reason and the attempt (attempts 1 before that run -> 2 of 3).
		HistoryModel.Change failed = entry(view, applyB.id()).changes().getFirst();
		check(failed.row() == HistoryModel.Row.UPDATED && JournalChange.STAGED.equals(failed.status()), "one staged update row: " + failed);
		String reason = context.computeOnClient(mc -> HistoryScreen.failureText(failed).getString());
		RigTune.LOGGER.info("HistoryGameTest: failed change shows: {}", reason);
		check(reason.startsWith("Last attempt failed: Gave up after 10 attempt(s)") && reason.endsWith("(try 2 of 3 at restart)"), "failure text: " + reason);
		check(reason.contains("fake-mod-1.0.jar -> fake-mod-1.0.jar.disabled") && !reason.contains(modsDir.toString()), "paths shown as names: " + reason);
		List<String> rows = context.computeOnClient(mc -> ((HistoryScreen) mc.gui.screen()).changeRowText()).stream()
				.map(row -> row.replaceAll("\\s+", " ")).toList();
		check(rows.size() == 2 && rows.get(0).equals("Updated fakemod: fake-mod-1.0.jar → fake-mod-1.1.jar")
				&& rows.get(1).startsWith("Last attempt failed: ") && rows.get(1).endsWith("(try 2 of 3 at restart)"), "the open entry's rows as drawn: " + rows);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			checkLayout(context, "history " + size[0] + "x" + size[1] + "@" + size[2]);
			context.takeScreenshot("history-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}

		// Select the older apply with a click on its row, then Undo this.
		resize(context, 854, 480, 2);
		context.runOnClient(mc -> {
			HistoryScreen screen = (HistoryScreen) mc.gui.screen();
			ScreenRectangle row = screen.entryRow(applyA.id());
			check(row != null, "the older apply's row is listed");
			screen.mouseClicked(new MouseButtonEvent(row.left() + 20, row.top() + 5, new MouseButtonInfo(0, 0)), false);
		});
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen screen && applyA.id().equals(screen.selected()), 40);
		context.waitTicks(2);
		context.takeScreenshot("history-older-apply");

		pressByKey(context, "rigtune.history.undo_this");
		context.waitForScreen(UndoScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen undo && undo.plan() != null, 200);
		context.waitTicks(3);
		UndoPlan plan = context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).plan());
		check(applyA.id().equals(context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).entryId())) && applyA.id().equals(plan.undoOf()),
				"Undo this plans the older apply: " + plan);
		check(plan.items().stream().anyMatch(i -> i.action() == UndoPlan.Action.REVERT && !i.needsRestart() && i.changeIds().contains(shadowsChange.id())),
				"entity shadows reverted now: " + plan);
		check(plan.items().stream().anyMatch(i -> i.action() == UndoPlan.Action.SKIP && i.changeIds().contains(rdChange.id())
				&& "Changed again by a later apply".equals(i.reason())), "render distance skipped, the benchmark changed it again: " + plan);
		checkLayout(context, "undo this 854x480@2");
		context.takeScreenshot("history-undo-this");

		pressByKey(context, "rigtune.undo.confirm");
		context.waitTicks(3);
		context.takeScreenshot("history-undo-this-done");
		check(context.computeOnClient(mc -> mc.options.entityShadows().get()) == shadows, "entity shadows back to where they were");
		check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == renderDistance + 3, "render distance left as the benchmark set it");
		JournalEntry after = journal.entries().stream().filter(e -> e.id().equals(applyA.id())).findFirst().orElseThrow();
		check(JournalChange.REVERTED.equals(change(after, shadowsChange.id()).status()), "shadows change REVERTED: " + after);
		check(JournalChange.APPLIED.equals(change(after, rdChange.id()).status()), "render distance change still APPLIED: " + after);
		JournalEntry undo = journal.entries().getLast();
		check(JournalEntry.UNDO.equals(undo.kind()) && applyA.id().equals(undo.undoOf()), "the undo is journaled against the older apply: " + undo);
		check(undo.changes().size() == 1 && shadowsChange.id().equals(undo.changes().getFirst().reverts())
				&& JournalChange.APPLIED.equals(undo.changes().getFirst().status()) && shadowsBefore.equals(undo.changes().getFirst().after()),
				"the undo reverts the shadows change: " + undo);

		// Back on History: the new undo is listed first.
		context.runOnClient(mc -> mc.gui.screen().onClose());
		view = waitForHistory(context);
		HistoryModel.Entry newest = view.entries().getFirst();
		check(undo.id().equals(newest.id()) && applyA.id().equals(newest.undoOf()) && !newest.undoable(), "the new undo is listed first: " + newest);
		context.runOnClient(mc -> ((HistoryScreen) mc.gui.screen()).select(newest.id()));
		context.waitTicks(2);
		checkLayout(context, "history after undo 854x480@2");
		context.takeScreenshot("history-after-undo");

		// Corrupt, newer and missing history.json each get a read-only message (SPEC item 6, review B-M2).
		boolean backup = Files.exists(historyFile.resolveSibling("history.json.bad"));
		showState(context, "{not json", Journal.State.CORRUPT, "history-corrupt");
		showState(context, "{\"formatVersion\":99,\"entries\":[]}", Journal.State.NEWER, "history-newer");
		showState(context, null, Journal.State.MISSING, "history-empty");
		check(Files.exists(historyFile.resolveSibling("history.json.bad")) == backup, "reading a corrupt history.json made no backup");
	}

	private void showState(ClientGameTestContext context, @Nullable String json, Journal.State expected, String screenshot) {
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(RigTuneScreen.class);
		restore(historyFile, json == null ? null : json.getBytes(StandardCharsets.UTF_8));
		pressByKey(context, "rigtune.history.open");
		HistoryModel.View view = waitForHistory(context);
		check(view.state() == expected && view.entries().isEmpty(), screenshot + ": " + view);
		check(context.computeOnClient(mc -> ((HistoryScreen) mc.gui.screen()).selected()) == null, screenshot + ": nothing selected");
		checkLayout(context, screenshot);
		context.takeScreenshot(screenshot);
	}

	private static HistoryModel.View waitForHistory(ClientGameTestContext context) {
		context.waitForScreen(HistoryScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof HistoryScreen screen && !screen.loading() && screen.view() != null, 400);
		context.waitTicks(2);
		return context.computeOnClient(mc -> ((HistoryScreen) mc.gui.screen()).view());
	}

	private static HistoryModel.Entry entry(HistoryModel.View view, String id) {
		return view.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no entry " + id));
	}

	private static JournalChange change(JournalEntry entry, String id) {
		return entry.changes().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no change " + id));
	}

	private static byte @Nullable [] read(Path file) {
		try {
			return Files.exists(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void restore(Path file, byte @Nullable [] bytes) {
		try {
			if (bytes == null) {
				Files.deleteIfExists(file);
			} else {
				Files.write(file, bytes);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// The cursor goes to a corner so no tooltip or hover highlight covers the screenshots.
	private static void resize(ClientGameTestContext context, int width, int height, int guiScale) {
		context.getInput().resizeWindow(width, height);
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
	}

	// As UiGameTest: every visible widget inside the screen, none overlapping, every button label readable.
	private static void checkLayout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w.visible).toList();
			for (AbstractWidget w : widgets) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height,
						name + ": " + describe(w) + " outside " + screen.width + "x" + screen.height);
				if (!(w instanceof AbstractSelectionList<?>)) {
					check(mc.font.width(w.getMessage()) <= w.getWidth() - 4, name + ": label doesn't fit " + describe(w));
				}
			}
			for (int i = 0; i < widgets.size(); i++) {
				for (int j = i + 1; j < widgets.size(); j++) {
					AbstractWidget a = widgets.get(i);
					AbstractWidget b = widgets.get(j);
					boolean overlap = a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
					check(!overlap, name + ": " + describe(a) + " overlaps " + describe(b));
				}
			}
		});
	}

	private static String describe(AbstractWidget w) {
		return "'" + w.getMessage().getString() + "' [" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
	}

	private static @Nullable Button findButton(Screen screen, String key) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast)
				.findFirst()
				.orElse(null);
	}

	private static void pressByKey(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = findButton(mc.gui.screen(), key);
			check(button != null, "No button " + key + " on " + mc.gui.screen());
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
		context.waitTicks(2);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
