import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The released-jar compatibility harness (docs/v0.4/SPEC.md item 3, AC3.3; plan review H-M1): feeds files written by
 * 0.4 to the RELEASED 0.3.0 jar's own classes. Run by tools/e2e/compat030.py as a single-file program, compiled at
 * launch against the classpath it gets: the released rigtune-0.3.0+mc26.2.jar, Gson 2.14.0 (what MC 26.2/26.3 ship),
 * fabric-loader and slf4j. Never compiled against this repository's sources.
 *
 * <pre>java -cp &lt;jars&gt; Compat030.java --config &lt;instance&gt;/config --rules &lt;rules-v2.json&gt;</pre>
 *
 * Prints one line per check ("PASS name | detail" or "FAIL name | detail") and exits 1 if any failed.
 */
public final class Compat030 {
	private static final String VERSION = "0.3.0+mc26.2";
	private static final String MC = "26.2";
	private static final List<String> NEW_RULES_SECTIONS = List.of("profileTemplates", "stutterAdvice");

	private final List<String> lines = new ArrayList<>();
	private boolean failed;

	public static void main(String[] args) throws Exception {
		Path config = null;
		Path rules = null;
		for (int i = 0; i + 1 < args.length; i += 2) {
			switch (args[i]) {
				case "--config" -> config = Path.of(args[i + 1]);
				case "--rules" -> rules = Path.of(args[i + 1]);
				default -> {
					System.err.println("unknown argument " + args[i]);
					System.exit(2);
				}
			}
		}
		if (config == null || rules == null) {
			System.err.println("usage: Compat030.java --config <instance>/config --rules <rules-v2.json>");
			System.exit(2);
		}
		Compat030 run = new Compat030();
		run.run(config, rules);
		run.lines.forEach(System.out::println);
		System.exit(run.failed ? 1 : 0);
	}

	private void check(String name, boolean ok, String detail) {
		failed |= !ok;
		lines.add((ok ? "PASS " : "FAIL ") + name + " | " + detail.replace('\n', ' '));
	}

	private void run(Path config, Path rulesFile) throws Exception {
		Path dir = config.resolve("rigtune");
		Map<String, String> before = digests(dir);

		List<JournalEntry> entries = journal(config, dir);
		String switchId = switchEntry(dir);
		undo(config, dir, entries, switchId);
		benchmarks(dir);
		pending(dir);
		settings(config, dir);
		rules(rulesFile);

		Map<String, String> after = digests(dir);
		check("0.3.0 reading them changed no file", before.equals(after), before.equals(after) ? before.size() + " file(s) unchanged"
				: "before " + before.keySet() + ", after " + after.keySet());
	}

	// history.json: state OK and exactly the file's entries, and the History screen's model lists every one.
	private List<JournalEntry> journal(Path config, Path dir) throws IOException {
		List<String> warnings = new ArrayList<>();
		Journal journal = new Journal(config, VERSION, MC, (message, error) -> warnings.add(message + (error == null ? "" : ": " + error)));
		Journal.State state = journal.state();
		List<JournalEntry> entries = journal.entries();
		List<String> fileIds = new ArrayList<>();
		for (JsonElement e : json(dir.resolve("history.json")).getAsJsonArray("entries")) {
			fileIds.add(e.getAsJsonObject().get("id").getAsString());
		}
		List<String> ids = entries.stream().map(JournalEntry::id).toList();
		check("Journal: history.json state OK with the same entries", state == Journal.State.OK && ids.equals(fileIds) && !journal.readOnly()
				&& warnings.isEmpty(), "state " + state + ", readOnly " + journal.readOnly() + ", entries " + ids.size() + " of "
				+ fileIds.size() + (ids.equals(fileIds) ? "" : " (ids differ)") + ", warnings " + warnings);

		UndoPlanner.State plannerState = plannerState(config, entries);
		HistoryModel.View view = HistoryModel.build(state, entries, Map.of(), HistoryModel.Labels.of(plannerState));
		List<String> viewIds = view.entries().stream().map(HistoryModel.Entry::id).sorted().toList();
		List<String> unknown = view.entries().stream().filter(e -> e.kindKey().endsWith(".unknown")).map(HistoryModel.Entry::id).toList();
		check("HistoryModel: every entry listed, none of an unknown kind", viewIds.equals(ids.stream().sorted().toList()) && unknown.isEmpty(),
				view.entries().size() + " of " + ids.size() + " entries; unknown kinds: " + unknown);
		return entries;
	}

	// profiles.json (0.4 only; 0.3.0 never reads it): the journal entry of the (first) profile switch.
	private String switchEntry(Path dir) throws IOException {
		Path file = dir.resolve("profiles.json");
		if (!Files.isRegularFile(file) || !json(file).has("switches") || json(file).getAsJsonArray("switches").isEmpty()) {
			check("profiles.json names a switch entry", false, "no switches in " + file);
			return null;
		}
		return json(file).getAsJsonArray("switches").get(0).getAsJsonObject().get("entryId").getAsString();
	}

	// Undo this on the switch entry: every setting change reverted, nothing refused; Undo last and Undo all plan too.
	private void undo(Path config, Path dir, List<JournalEntry> entries, String switchId) throws IOException {
		List<PendingActions.Op> ops = Files.isRegularFile(dir.resolve("pending.json")) ? PendingActions.load(dir.resolve("pending.json")).ops()
				: List.of();
		UndoPlanner.State state = plannerState(config, entries);
		JournalEntry sw = entries.stream().filter(e -> e.id().equals(switchId)).findFirst().orElse(null);
		if (sw == null) {
			check("UndoPlanner: Undo this on the profile-switch entry reverts each change", false, "entry " + switchId + " isn't in history.json");
		} else {
			UndoPlan plan = UndoPlanner.planEntry(entries, ops, state, switchId).plan();
			// What the planner may undo: applied changes (reverted) and staged ones (their op dropped); a DISCARDED one
			// (replaced by a later switch, P-H1) isn't a candidate.
			List<String> want = sw.changes().stream().filter(c -> JournalChange.APPLIED.equals(c.status()) || JournalChange.STAGED.equals(c.status()))
					.map(JournalChange::id).sorted().toList();
			List<String> reverted = plan.items().stream()
					.filter(i -> i.action() == UndoPlan.Action.REVERT || i.action() == UndoPlan.Action.DISCARD_STAGED)
					.flatMap(i -> i.changeIds().stream()).sorted().toList();
			check("UndoPlanner: Undo this on the profile-switch entry reverts each change", plan.problem() == null && !plan.isEmpty()
					&& reverted.equals(want) && JournalEntry.APPLY.equals(sw.kind()), "kind " + sw.kind() + ", problem " + plan.problem()
					+ ", items " + plan.items().stream().map(i -> i.action() + " " + i.description()
					+ (i.reason() == null ? "" : " (" + i.reason() + ")")).toList());
		}
		UndoPlan last = UndoPlanner.plan(entries, ops, state, false).plan();
		UndoPlan all = UndoPlanner.plan(entries, ops, state, true).plan();
		check("UndoPlanner: Undo last and Undo all plan without a problem", last.problem() == null && all.problem() == null
				&& !last.isEmpty(), "Undo last: " + last.undoOf() + ", " + last.items().size() + " item(s), problem " + last.problem()
				+ "; Undo all: " + all.items().size() + " item(s), problem " + all.problem());
	}

	// What the planner sees: every setting at its latest applied value (as the game has it), vanilla keys set now.
	private static UndoPlanner.State plannerState(Path config, List<JournalEntry> entries) {
		Map<String, String> current = new LinkedHashMap<>();
		for (JournalEntry entry : entries) {
			for (JournalChange change : entry.changes()) {
				if (change.isSetting() && JournalChange.APPLIED.equals(change.status())) {
					current.put(change.key(), change.after());
				}
			}
		}
		Path mods = config.getParent().resolve("mods");
		UndoPlanner.Folder folder = new UndoPlanner.Folder() {
			@Override
			public Path dir() {
				return mods;
			}

			@Override
			public Set<String> files() {
				return Set.of();
			}

			@Override
			public JarInfo jar(String fileName) {
				return null;
			}

			@Override
			public Set<String> providedElsewhere() {
				return Set.of();
			}
		};
		return new UndoPlanner.State() {
			@Override
			public String setting(String key) {
				return current.get(key);
			}

			@Override
			public boolean immediate(String key) {
				return key.startsWith("vanilla.");
			}

			@Override
			public boolean changeable(String key) {
				return true;
			}

			@Override
			public UndoPlanner.Folder folder() {
				return folder;
			}
		};
	}

	// benchmarks.json with 0.4's context fields: every run loads, none unreadable, no .bad.
	private void benchmarks(Path dir) throws IOException {
		Path file = BenchmarkHistory.defaultPath(dir.getParent());
		int runs = json(file).getAsJsonArray("runs").size();
		BenchmarkHistory history = BenchmarkHistory.load(file);
		List<BenchmarkRecord> loaded = history.runs();
		boolean contexts = loaded.stream().allMatch(r -> r.context() != null);
		check("BenchmarkHistory: benchmarks.json loads without a .bad", !history.unreadable() && loaded.size() == runs && contexts
				&& bad(dir).isEmpty(), "runs " + loaded.size() + " of " + runs + ", unreadable " + history.unreadable() + ", contexts kept "
				+ contexts + ", .bad files " + bad(dir));
	}

	// pending.json with 0.4's projectId: every op parses with its type (an unknown one would be null) and mod id.
	private void pending(Path dir) throws IOException {
		Path file = PendingActions.defaultPath(dir.getParent());
		List<JsonElement> raw = new ArrayList<>();
		json(file).getAsJsonArray("ops").forEach(raw::add);
		List<PendingActions.Op> ops = PendingActions.load(file).ops();
		boolean same = ops.size() == raw.size();
		for (int i = 0; same && i < ops.size(); i++) {
			JsonObject op = raw.get(i).getAsJsonObject();
			same = ops.get(i).type() != null && ops.get(i).type().name().equals(op.get("type").getAsString())
					&& ops.get(i).id().equals(op.get("id").getAsString())
					&& (!op.has("modId") || op.get("modId").getAsString().equals(ops.get(i).modId()));
		}
		check("PendingActions: pending.json loads with every op's type, id and mod id", same, ops.size() + " of " + raw.size() + " ops: "
				+ ops.stream().map(o -> o.type() + " " + o.modId()).toList());
	}

	// settings.json with 0.4's stutterMonitor: read as the file says, not replaced by defaults.
	private void settings(Path config, Path dir) throws IOException {
		Path file = ClientSettings.file(config);
		boolean shown = json(file).has("privacyNoticeShown") && json(file).get("privacyNoticeShown").getAsBoolean();
		ClientSettings settings = ClientSettings.load(config);
		check("ClientSettings: settings.json read as written", settings.privacyNoticeShown == shown && bad(dir).isEmpty(),
				"privacyNoticeShown " + settings.privacyNoticeShown + " (file " + shown + "), .bad files " + bad(dir));
	}

	// The regenerated rules-v2.json: the same counts as with 0.4's new top-level sections removed.
	private void rules(Path file) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		JsonObject stripped = JsonParser.parseString(text).getAsJsonObject();
		List<String> present = NEW_RULES_SECTIONS.stream().filter(stripped::has).toList();
		present.forEach(stripped::remove);
		RulesDocument full = RulesLoader.parse(text);
		RulesDocument without = RulesLoader.parse(stripped.toString());
		Map<String, Integer> a = counts(full);
		Map<String, Integer> b = counts(without);
		check("RulesLoader: rules-v2.json parses with unchanged counts", a.equals(b) && full.revision == without.revision,
				"revision " + full.revision + ", counts " + a + (a.equals(b) ? "" : " vs " + b) + "; new sections present: " + present);
	}

	private static Map<String, Integer> counts(RulesDocument doc) {
		Map<String, Integer> out = new LinkedHashMap<>();
		out.put("mods", doc.mods.size());
		out.put("settings", doc.settings.size());
		out.put("advice", doc.advice.size());
		out.put("obsolete", doc.obsolete.size());
		out.put("gpuTiers", doc.gpuTiers.size());
		out.put("cpuTiers", doc.cpuTiers.size());
		out.put("heapTiers", doc.heapTiers.size());
		return out;
	}

	private static List<String> bad(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.contains(".bad")).sorted().toList();
		}
	}

	private static JsonObject json(Path file) throws IOException {
		return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static Map<String, String> digests(Path dir) throws Exception {
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				out.put(dir.relativize(file).toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
			}
		}
		return out;
	}
}
