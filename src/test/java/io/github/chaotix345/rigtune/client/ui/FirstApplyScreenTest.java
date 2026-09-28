package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 8 (AC8.6, AC8.7, AC8.9, AC8.11, AC8.14, AC8.15): the first-Apply confirmation. Its rows are the Apply's
// own History rows (HistoryModel changes, in journal order) grouped "In effect now" / "At the next restart" / "Undone or
// cancelled"; the restart note only with a row waiting for the restart, "No restart needed" only with none and no
// download; the downloading note while downloads run; the no-mod-files note when the launcher manages the mods; the undo
// hint last. Without the entry it says why, never an empty list. Only the Apply button opens it.
class FirstApplyScreenTest {
	private static final String P = "rigtune.firstrun.applied.";
	private static final Component STATUS = Component.literal("1 setting(s) applied.");

	private static HistoryModel.Change setting(String id, String status) {
		return new HistoryModel.Change(HistoryModel.Row.SETTING, List.of(id), status, "Render distance", "16", "12", null, null, null, null);
	}

	private static HistoryModel.Change mod(String id, String status) {
		return new HistoryModel.Change(HistoryModel.Row.ADDED, List.of(id), status, null, null, null, "lithium.jar", null, "lithium", null, "Lithium");
	}

	private static HistoryModel.View view(HistoryModel.Change... changes) {
		return new HistoryModel.View(Journal.State.OK, List.of(
				new HistoryModel.Entry("e2", JournalEntry.APPLY, "2026-09-27T10:00:00Z", "0.5.0", "26.2", null, null, true, List.of(changes)),
				new HistoryModel.Entry("e1", JournalEntry.APPLY, "2026-09-26T10:00:00Z", "0.5.0", "26.2", null, null, true,
						List.of(setting("old", JournalChange.APPLIED)))));
	}

	private static List<String> items(HistoryModel.View view, boolean downloading, ModFilesPolicy policy) {
		return names(FirstApplyScreen.items(STATUS, view, false, false, "e2", downloading, policy));
	}

	private static List<String> names(List<FirstApplyScreen.Item> items) {
		List<String> out = new ArrayList<>();
		for (FirstApplyScreen.Item item : items) {
			out.add(switch (item) {
				case FirstApplyScreen.Item.Status s -> "status";
				case FirstApplyScreen.Item.Section s -> "section " + s.key().substring(P.length()) + " " + s.count();
				case FirstApplyScreen.Item.Change c -> "change " + c.change().changeIds().getFirst();
				case FirstApplyScreen.Item.Note n -> "note " + n.key().substring(P.length());
				case FirstApplyScreen.Item.Message m -> "message " + m.key();
			});
		}
		return out;
	}

	@Test
	void allInEffectNow() {
		assertEquals(List.of("status", "section section.now 2", "change c1", "change c2", "note no_restart", "note undo_hint"),
				items(view(setting("c1", JournalChange.APPLIED), setting("c2", JournalChange.APPLIED)), false, ModFilesPolicy.RIGTUNE));
	}

	@Test
	void someAtTheNextRestart() {
		assertEquals(List.of("status", "section section.now 1", "change c1", "section section.restart 2", "change c2", "change c3", "note restart",
				"note undo_hint"), items(view(setting("c1", JournalChange.APPLIED), setting("c2", JournalChange.STAGED), mod("c3", JournalChange.STAGED)),
				false, ModFilesPolicy.RIGTUNE));
	}

	// Rows keep the journal's order inside a section (vanilla first, staged after, downloads last).
	@Test
	void journalOrderInsideASection() {
		assertEquals(List.of("status", "section section.now 2", "change a", "change c", "section section.restart 1", "change b", "note restart",
				"note undo_hint"), items(view(setting("a", JournalChange.APPLIED), setting("b", JournalChange.STAGED), setting("c", JournalChange.APPLIED)),
				false, ModFilesPolicy.RIGTUNE));
	}

	@Test
	void whileDownloadsRun() {
		assertEquals(List.of("status", "section section.now 1", "change c1", "note downloading", "note undo_hint"),
				items(view(setting("c1", JournalChange.APPLIED)), true, ModFilesPolicy.RIGTUNE), "no 'no restart needed' while downloads run");
		assertEquals(List.of("status", "section section.restart 1", "change c1", "note restart", "note downloading", "note undo_hint"),
				items(view(setting("c1", JournalChange.STAGED)), true, ModFilesPolicy.RIGTUNE));
		// Only downloads chosen: nothing is recorded until they finish, and that isn't "nothing was recorded".
		assertEquals(List.of("status", "note downloading"),
				names(FirstApplyScreen.items(STATUS, view(setting("old", JournalChange.APPLIED)), false, false, "e3", true, ModFilesPolicy.RIGTUNE)));
	}

	// After Undo this Apply from here the rows honestly read Undone or Cancelled, and nothing claims they're in effect.
	@Test
	void undoneOrCancelled() {
		assertEquals(List.of("status", "section section.undone 3", "change c1", "change c2", "change c3", "note undo_hint"),
				items(view(setting("c1", JournalChange.REVERTED), setting("c2", JournalChange.DISCARDED), mod("c3", JournalChange.ABANDONED)), false,
						ModFilesPolicy.RIGTUNE));
		assertEquals(List.of("status", "section section.now 1", "change c1", "section section.undone 1", "change c2", "note undo_hint"),
				items(view(setting("c1", JournalChange.APPLIED), setting("c2", JournalChange.REVERTED)), false, ModFilesPolicy.RIGTUNE));
	}

	// AC8.14 (the note): under LAUNCHER or PENDING every mod-file action was advice, so this Apply changed none.
	@Test
	void theLauncherManagesTheMods() {
		for (ModFilesPolicy policy : List.of(ModFilesPolicy.LAUNCHER, ModFilesPolicy.PENDING)) {
			assertEquals(List.of("status", "section section.now 1", "change c1", "note no_restart", "note no_mod_files", "note undo_hint"),
					items(view(setting("c1", JournalChange.APPLIED)), false, policy), policy.name());
		}
	}

	// Review M1/L6: a status that reports something Apply didn't do (a failed setting or stage, a failed download) keeps the
	// "in effect" note away and isn't drawn in the applied colour; the pieces may sit anywhere in the joined status.
	@Test
	void anApplyThatReportsFailures() {
		Component partly = Component.empty().append(Component.translatable("rigtune.status.settings_applied", 1)).append(" ")
				.append(Component.translatable("rigtune.status.some_failed", 1));
		assertTrue(FirstApplyScreen.reportsFailure(partly));
		assertTrue(FirstApplyScreen.reportsFailure(Component.translatable("rigtune.status.download_failed", "timeout")));
		assertFalse(FirstApplyScreen.reportsFailure(STATUS));
		assertFalse(FirstApplyScreen.reportsFailure(Component.translatable("rigtune.status.settings_applied", 2)));
		assertFalse(FirstApplyScreen.reportsFailure(null));
		List<FirstApplyScreen.Item> items = FirstApplyScreen.items(partly, view(setting("c1", JournalChange.APPLIED)), false, false, "e2", false,
				ModFilesPolicy.RIGTUNE);
		assertEquals(List.of("status", "section section.now 1", "change c1", "note undo_hint"), names(items));
		assertTrue(((FirstApplyScreen.Item.Status) items.getFirst()).failure());
		assertFalse(((FirstApplyScreen.Item.Status) FirstApplyScreen.items(STATUS, view(setting("c1", JournalChange.APPLIED)), false, false, "e2", false,
				ModFilesPolicy.RIGTUNE).getFirst()).failure());
	}

	// Review L5/L7: "didn't change any mod files" only when the rows agree; the undo hint only when Undo this Apply can act.
	@Test
	void notesTheRowsBackUp() {
		assertEquals(List.of("status", "section section.restart 1", "change c1", "note restart", "note undo_hint"),
				items(view(mod("c1", JournalChange.STAGED)), false, ModFilesPolicy.LAUNCHER), "a mod row: no no-mod-files note");
		HistoryModel.View notUndoable = new HistoryModel.View(Journal.State.OK, List.of(new HistoryModel.Entry("e2", JournalEntry.APPLY,
				"2026-09-27T10:00:00Z", "0.5.0", "26.2", null, null, false, List.of(setting("c1", JournalChange.REVERTED)))));
		assertEquals(List.of("status", "section section.undone 1", "change c1"), items(notUndoable, false, ModFilesPolicy.RIGTUNE));
	}

	// AC8.11: never an empty list without a reason.
	@Test
	void withoutTheEntry() {
		assertEquals(List.of("status", "message rigtune.history.loading"),
				names(FirstApplyScreen.items(STATUS, null, false, true, "e2", false, ModFilesPolicy.RIGTUNE)));
		assertEquals(List.of("status", "message rigtune.history.error"),
				names(FirstApplyScreen.items(STATUS, null, true, false, "e2", false, ModFilesPolicy.RIGTUNE)));
		assertEquals(List.of("status", "message rigtune.history.error"),
				names(FirstApplyScreen.items(STATUS, null, false, false, "e2", false, ModFilesPolicy.RIGTUNE)), "no history (a stub controller)");
		assertEquals(List.of("status", "message " + P + "nothing"),
				names(FirstApplyScreen.items(STATUS, view(), false, false, "e9", false, ModFilesPolicy.RIGTUNE)), "the Apply journaled nothing");
		for (Journal.State state : List.of(Journal.State.CORRUPT, Journal.State.NEWER, Journal.State.UNREADABLE)) {
			String key = switch (state) {
				case CORRUPT -> "rigtune.history.corrupt";
				case NEWER -> "rigtune.history.newer";
				default -> "rigtune.history.error";
			};
			assertEquals(List.of("status", "message " + key),
					names(FirstApplyScreen.items(STATUS, new HistoryModel.View(state, List.of()), false, false, "e2", false, ModFilesPolicy.RIGTUNE)));
		}
		assertEquals(List.of("message rigtune.history.error"), names(FirstApplyScreen.items(null, null, true, false, "e2", false, ModFilesPolicy.RIGTUNE)));
	}

	// A change the helper couldn't apply keeps History's failure line (the row's own text comes from HistoryScreen).
	@Test
	void aFailureLineComesFromHistory() {
		HistoryModel.Change failed = new HistoryModel.Change(HistoryModel.Row.ADDED, List.of("c1"), JournalChange.STAGED, null, null, null, "dh.jar", null,
				"dh", new ApplyFailures.Failure("op1", ApplyResult.Status.FAILED, PendingActions.Type.ENABLE_FILE, "dh", "dh.jar", "File in use", 2));
		assertEquals(HistoryScreen.failureText(failed).getString(), FirstApplyScreen.failure(failed).getString());
		assertEquals(HistoryScreen.describe(failed).getString(), FirstApplyScreen.describe(failed).getString());
	}

	// The open narration: the title, the summary and the restart outcome (AC8.12's narration half).
	@Test
	void theOpenNarration() {
		HistoryModel.View view = view(setting("c1", JournalChange.APPLIED), mod("c2", JournalChange.STAGED));
		String said = FirstApplyScreen.narration(Component.translatable(P + "title"), view.entries().getFirst(),
				FirstApplyScreen.items(STATUS, view, false, false, "e2", false, ModFilesPolicy.RIGTUNE)).getString();
		assertTrue(said.contains(P + "title") && said.contains("rigtune.history.summary.both") && said.contains(P + "restart"), said);
		HistoryModel.View now = view(setting("c1", JournalChange.APPLIED));
		assertTrue(FirstApplyScreen.narration(Component.translatable(P + "title"), now.entries().getFirst(),
				FirstApplyScreen.items(STATUS, now, false, false, "e2", false, ModFilesPolicy.RIGTUNE)).getString().contains(P + "no_restart"));
	}

	// AC8.15: the history is read on the executor it's given (Probes.EXECUTOR in the screen), never on the caller's thread.
	@Test
	void theHistoryIsReadOnTheExecutor() {
		List<Runnable> queued = new ArrayList<>();
		List<String> readOn = new ArrayList<>();
		CompletableFuture<HistoryModel.View> read = FirstApplyScreen.read(() -> {
			readOn.add(Thread.currentThread().getName());
			return view();
		}, queued::add);
		assertTrue(readOn.isEmpty() && !read.isDone(), "nothing read on the calling thread");
		Thread worker = new Thread(() -> queued.forEach(Runnable::run), "worker");
		worker.start();
		assertEquals(Journal.State.OK, read.join().state());
		assertEquals(List.of("worker"), readOn);
	}

	// AC8.9: only the RigTune screen's Apply button opens it (profile switches, stutter fixes, Try it and direct
	// controller.apply calls never do), and the button asks firstApplyPending() before its Apply retires it.
	@Test
	void onlyTheApplyButtonOpensIt() throws IOException {
		Path client = RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client");
		List<String> openers = new ArrayList<>();
		try (Stream<Path> files = Files.walk(client, 4)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				if (Files.readString(file).contains("new FirstApplyScreen(")) {
					openers.add(file.getFileName().toString());
				}
			}
		}
		assertEquals(List.of("RigTuneScreen.java"), openers);
		String screen = Files.readString(client.resolve("ui/RigTuneScreen.java"));
		String applySelected = screen.substring(screen.indexOf("private void applySelected()"), screen.indexOf("private void updateApplyButton()"));
		int pending = applySelected.indexOf("controller.firstApplyPending()");
		int apply = applySelected.indexOf("controller.apply(chosen, entryId)");
		int open = applySelected.indexOf("new FirstApplyScreen(");
		assertTrue(pending >= 0 && apply > pending && open > apply, applySelected);
		assertFalse(Files.readString(client.resolve("RealController.java")).contains("FirstApplyScreen"), "never from RealController.apply");
	}
}
