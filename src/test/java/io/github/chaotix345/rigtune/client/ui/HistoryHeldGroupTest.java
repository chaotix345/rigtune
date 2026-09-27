package io.github.chaotix345.rigtune.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// docs/v0.5/SPEC.md 2V (ws-g3 L6, AC2V.1): a group the helper left half applied is never abandoned; its attempts stop at
// the cap, so History repeated "(try 3 of 3 at restart)" after every exit. Such a change now says it can't finish on its
// own yet and that RigTune tries again at each exit; a change still under the cap keeps "try n of 3".
class HistoryHeldGroupTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();

	private static String english(Component text) throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json"))).getAsJsonObject();
		TranslatableContents t = (TranslatableContents) text.getContents();
		List<Object> args = new ArrayList<>();
		for (Object arg : t.getArgs()) {
			args.add(arg instanceof Component c ? c.getString() : arg);
		}
		return lang.get(t.getKey()).getAsString().formatted(args.toArray());
	}

	// The DH update group after a failed rollback, as last-apply.json records it: the disable done (its .disabled name as
	// resultPath), the enable failed; both ops at the capped attempt count (2 before this run).
	private static List<String> failureLines(int attemptsBefore) throws IOException {
		List<Op> group = PendingActions.group(Op.disableFile(MODS.resolve("dh-3.3.0.jar")).withAttempts(attemptsBefore),
				Op.enableFile(MODS.resolve("dh-3.3.2.jar.rigtune-pending"), MODS.resolve("dh-3.3.2.jar")).withModId("distanthorizons").withAttempts(attemptsBefore));
		ApplyResult result = new ApplyResult("2026-09-27T01:08:48Z", List.of(
				new ApplyResult.OpResult(group.get(0), ApplyResult.Status.FAILED, "Rolled back, but dh-3.3.0.jar.disabled couldn't be renamed back",
						MODS.resolve("dh-3.3.0.jar.disabled").toString()),
				new ApplyResult.OpResult(group.get(1), ApplyResult.Status.FAILED, "dh-3.3.2.jar is in use")));
		JournalEntry entry = new JournalEntry("e1", "2026-09-26T00:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null, List.of(
				JournalChange.file(JournalChange.DISABLE, "distanthorizons", "dh-3.3.0.jar", JournalChange.STAGED, group.get(0).id(), group.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, "distanthorizons", "dh-3.3.2.jar", JournalChange.STAGED, group.get(1).id(), group.get(1).group())));
		HistoryModel.View view = HistoryModel.build(Journal.State.OK, List.of(entry), ApplyFailures.byOpId(result, List.of(MODS)), HistoryModel.Labels.RAW);
		List<String> out = new ArrayList<>();
		for (HistoryModel.Change change : view.entries().getFirst().changes()) {
			Component text = HistoryScreen.failureText(change);
			if (text != null) {
				out.add(english(text));
			}
		}
		return out;
	}

	@Test
	void aHalfAppliedGroupAtTheCapSaysItCantFinishOnItsOwn() throws IOException {
		List<String> lines = failureLines(2);

		assertEquals(List.of("Can't finish on its own yet; RigTune tries again at each exit (see config/rigtune/helper.log)"), lines);
		assertFalse(lines.getFirst().contains("try 3 of 3"));
	}

	@Test
	void aChangeUnderTheCapKeepsItsTryCount() throws IOException {
		assertEquals(List.of("Last attempt failed: Rolled back, but dh-3.3.0.jar.disabled couldn't be renamed back (try 2 of 3 at restart)"),
				failureLines(1));
	}
}
