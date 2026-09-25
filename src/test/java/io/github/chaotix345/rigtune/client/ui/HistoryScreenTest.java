package io.github.chaotix345.rigtune.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.ApplyFailures.Failure;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.HistoryModel.Change;
import io.github.chaotix345.rigtune.core.history.HistoryModel.Row;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 6 and 3e: the History screen's text is all in en_us.json, and a failed change says why.
class HistoryScreenTest {
	private static final Pattern KEY = Pattern.compile("\"(rigtune\\.history\\.[a-z_.]+)\"");
	private static final List<String> SOURCES = List.of("HistoryScreen.java", "UndoScreen.java", "RigTuneScreen.java");

	private static JsonObject lang() throws IOException {
		return JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json"))).getAsJsonObject();
	}

	private static Set<String> used() throws IOException {
		Set<String> keys = new TreeSet<>();
		for (String source : SOURCES) {
			Matcher m = KEY.matcher(Files.readString(RepoFiles.resolve("src/client/java/io/github/chaotix345/rigtune/client/ui/" + source)));
			while (m.find()) {
				keys.add(m.group(1));
			}
		}
		for (String status : List.of(JournalChange.APPLIED, JournalChange.STAGED, JournalChange.ABANDONED, JournalChange.DISCARDED,
				JournalChange.REVERTED, "?")) {
			keys.add(HistoryModel.statusKey(status));
		}
		for (String kind : List.of(JournalEntry.APPLY, JournalEntry.BENCHMARK, JournalEntry.UNDO, JournalEntry.LEGACY_IMPORT, "?")) {
			keys.add(HistoryModel.kindKey(kind));
		}
		return keys;
	}

	@Test
	void everyHistoryKeyIsTranslatedAndUsed() throws IOException {
		JsonObject lang = lang();
		Set<String> used = used();
		Set<String> defined = new TreeSet<>(lang.keySet().stream().filter(k -> k.startsWith("rigtune.history.")).toList());
		assertEquals(defined, used);
	}

	private static Change change(String status, Failure failure) {
		return new Change(Row.ADDED, List.of("c"), status, null, null, null, "dh.jar", null, "dh", failure);
	}

	private static String english(Component text) throws IOException {
		TranslatableContents t = (TranslatableContents) text.getContents();
		List<Object> args = new ArrayList<>();
		for (Object arg : t.getArgs()) {
			args.add(arg instanceof Component c ? c.getContents() instanceof TranslatableContents ? english(c) : c.getString() : arg);
		}
		return lang().get(t.getKey()).getAsString().formatted(args.toArray());
	}

	// AC3.5 (screen half, unit level): "Last attempt failed: ..." with attempt n of 3.
	@Test
	void aStagedChangeWhoseOpFailedSaysSo() throws IOException {
		Failure busy = new Failure("op", Status.FAILED, PendingActions.Type.ENABLE_FILE, "dh", "dh.jar", "dh.jar is in use", 2);

		assertEquals("Last attempt failed: dh.jar is in use (attempt 2 of 3)", english(HistoryScreen.failureText(change(JournalChange.STAGED, busy))));
		assertNull(HistoryScreen.failureText(change(JournalChange.STAGED, null)));
	}

	@Test
	void anAbandonedChangeSaysItWasNotApplied() throws IOException {
		Failure dropped = new Failure("op", Status.ABANDONED, PendingActions.Type.ENABLE_FILE, "dh", "dh.jar", "Gave up after 3 failed attempts: busy", 3);

		assertEquals("Not applied: Gave up after 3 failed attempts: busy", english(HistoryScreen.failureText(change(JournalChange.ABANDONED, dropped))));
	}

	@Test
	void summariesCountSettingsAndMods() throws IOException {
		assertEquals("No changes", english(HistoryScreen.summary(entry())));
		assertEquals("1 setting", english(HistoryScreen.summary(entry(Row.SETTING))));
		assertEquals("3 settings, 1 mod", english(HistoryScreen.summary(entry(Row.SETTING, Row.SETTING, Row.SETTING, Row.UPDATED))));
		assertEquals("2 mods", english(HistoryScreen.summary(entry(Row.ADDED, Row.DISABLED))));
	}

	@Test
	void changesAreDescribed() throws IOException {
		Change setting = new Change(Row.SETTING, List.of("c"), JournalChange.APPLIED, "Render Distance", null, "12", null, null, null, null);
		Change update = new Change(Row.UPDATED, List.of("a", "b"), JournalChange.APPLIED, null, null, null, "s-1.jar", "s-2.jar", "sodium", null);

		assertEquals("Render Distance: (none) → 12", english(HistoryScreen.describe(setting)));
		assertEquals("Updated sodium: s-1.jar → s-2.jar", english(HistoryScreen.describe(update)));
		assertTrue(english(HistoryScreen.describe(change(JournalChange.APPLIED, null))).startsWith("Added dh.jar"));
	}

	private static HistoryModel.Entry entry(Row... rows) {
		List<Change> changes = new ArrayList<>();
		for (Row row : rows) {
			changes.add(new Change(row, List.of("c"), JournalChange.APPLIED, "x", "1", "2", "x.jar", null, "x", null));
		}
		return new HistoryModel.Entry("e", JournalEntry.APPLY, null, null, null, null, null, false, changes);
	}
}
