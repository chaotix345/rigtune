import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.RestoreMarker;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import io.github.chaotix345.rigtune.core.server.ServerLimitsStore;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import io.github.chaotix345.rigtune.core.stutter.StutterSummary;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The released-jar compatibility harness for 0.4.0 (docs/v0.5/SPEC.md 3b, AC3b.1): feeds files 0.5 writes (the
 * v040-written and v050-written fixture sets, composed by tools/e2e/written.py) to the RELEASED 0.4.0 jar's own classes.
 * Run by tools/e2e/compat040.py as a single-file program, compiled at launch against the released
 * rigtune-0.4.0+mc26.2.jar, Gson 2.14.0 (what MC 26.2/26.3 ship), fabric-loader and slf4j; never against this
 * repository's sources.
 *
 * <pre>java -cp &lt;jars&gt; Compat040.java --config &lt;instance&gt;/config --rules &lt;rules-v2.json&gt; --scratch &lt;dir&gt;</pre>
 *
 * Prints one line per check ("PASS name | detail" or "FAIL name | detail") and exits 1 if any failed. Writes only under
 * --scratch (the AwarenessStore and RestoreMarker checks work on copies).
 */
public final class Compat040 {
	private static final String VERSION = "0.4.0+mc26.2";
	private static final String MC = "26.2";

	private final List<String> lines = new ArrayList<>();
	private boolean failed;

	public static void main(String[] args) throws Exception {
		Map<String, Path> opts = new LinkedHashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2) {
			if (!List.of("--config", "--rules", "--scratch").contains(args[i])) {
				System.err.println("unknown argument " + args[i]);
				System.exit(2);
			}
			opts.put(args[i], Path.of(args[i + 1]));
		}
		if (opts.size() != 3) {
			System.err.println("usage: Compat040.java --config <instance>/config --rules <rules-v2.json> --scratch <dir>");
			System.exit(2);
		}
		Compat040 run = new Compat040();
		run.run(opts.get("--config"), opts.get("--rules"), opts.get("--scratch"));
		run.lines.forEach(System.out::println);
		System.exit(run.failed ? 1 : 0);
	}

	private void check(String name, boolean ok, String detail) {
		failed |= !ok;
		lines.add((ok ? "PASS " : "FAIL ") + name + " | " + detail.replace('\n', ' '));
	}

	private void run(Path config, Path rulesFile, Path scratch) throws Exception {
		Path dir = config.resolve("rigtune");
		Map<String, String> before = digests(dir);

		List<JournalEntry> entries = journal(config, dir);
		undo(config, dir, entries);
		benchmarks(dir);
		pending(dir);
		settings(config, dir);
		stutter(config, dir);
		awareness(dir, scratch);
		profiles(config, dir, entries);
		serverLimits(config, dir);
		marker(dir, scratch);
		rules(rulesFile);

		Map<String, String> after = digests(dir);
		check("0.4.0 reading them changed no file", before.equals(after), before.equals(after) ? before.size() + " file(s) unchanged"
				: "before " + before + ", after " + after);
	}

	// history.json (0.5 adds foldedEntryIds to a baseline entry): state OK and exactly the file's entries, and the History
	// screen's model lists every one with a kind 0.4.0 knows.
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

		HistoryModel.View view = HistoryModel.build(state, entries, Map.of(), HistoryModel.Labels.of(plannerState(config, entries)));
		List<String> viewIds = view.entries().stream().map(HistoryModel.Entry::id).sorted().toList();
		List<String> unknown = view.entries().stream().filter(e -> e.kindKey().endsWith(".unknown")).map(HistoryModel.Entry::id).toList();
		check("HistoryModel: every entry listed, none of an unknown kind", viewIds.equals(ids.stream().sorted().toList()) && unknown.isEmpty(),
				view.entries().size() + " of " + ids.size() + " entries; unknown kinds: " + unknown);
		return entries;
	}

	// Undo this on every entry that still has a change applied or staged (a stutter fix, a Try It, a server-profile
	// switch, a profile switch, an ordinary Apply), then Undo last and Undo all.
	private void undo(Path config, Path dir, List<JournalEntry> entries) throws IOException {
		List<PendingActions.Op> ops = Files.isRegularFile(dir.resolve("pending.json")) ? PendingActions.load(dir.resolve("pending.json")).ops()
				: List.of();
		UndoPlanner.State state = plannerState(config, entries);
		List<String> planned = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (JournalEntry entry : entries) {
			boolean live = entry.changes().stream().anyMatch(c -> JournalChange.APPLIED.equals(c.status()) || JournalChange.STAGED.equals(c.status()));
			if (JournalEntry.UNDO.equals(entry.kind()) || !live) {
				continue;
			}
			UndoPlan plan = UndoPlanner.planEntry(entries, ops, state, entry.id()).plan();
			planned.add(entry.id() + " (" + entry.kind() + "): " + plan.items().size() + " item(s)");
			if (plan.problem() != null) {
				problems.add(entry.id() + ": " + plan.problem());
			}
		}
		check("UndoPlanner: Undo this on every entry with a change still applied or staged plans without a problem",
				!planned.isEmpty() && problems.isEmpty(), "planned " + planned + "; problems " + problems);
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

	// benchmarks.json with 0.5's optional context fields (worldFresh, dhGenerating, stagedAtStart) and tryit- pairs.
	private void benchmarks(Path dir) throws IOException {
		Path file = BenchmarkHistory.defaultPath(dir.getParent());
		if (!Files.isRegularFile(file)) {
			check("BenchmarkHistory: benchmarks.json loads without a .bad", false, "no benchmarks.json in the sets");
			return;
		}
		int runs = json(file).getAsJsonArray("runs").size();
		BenchmarkHistory history = BenchmarkHistory.load(file);
		List<BenchmarkRecord> loaded = history.runs();
		boolean contexts = loaded.stream().allMatch(r -> r.context() != null);
		check("BenchmarkHistory: benchmarks.json loads without a .bad", !history.unreadable() && loaded.size() == runs && contexts
				&& bad(dir).isEmpty(), "runs " + loaded.size() + " of " + runs + ", unreadable " + history.unreadable() + ", contexts kept "
				+ contexts + ", .bad files " + bad(dir));
	}

	// pending.json (0.5 adds no op type and no field): every op parses with its type (an unknown one would be null).
	private void pending(Path dir) throws IOException {
		Path file = PendingActions.defaultPath(dir.getParent());
		if (!Files.isRegularFile(file)) {
			check("PendingActions: pending.json loads with every op's type, id and mod id", false, "no pending.json in the sets");
			return;
		}
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

	// settings.json (0.5 adds modFilesByRigTune): 0.4's own fields read as the file says, not replaced by defaults.
	private void settings(Path config, Path dir) throws IOException {
		Path file = ClientSettings.file(config);
		JsonObject raw = Files.isRegularFile(file) ? json(file) : new JsonObject();
		boolean shown = raw.has("privacyNoticeShown") && raw.get("privacyNoticeShown").getAsBoolean();
		boolean monitor = raw.has("stutterMonitor") && raw.get("stutterMonitor").getAsBoolean();
		ClientSettings settings = ClientSettings.load(config);
		check("ClientSettings: settings.json read as written", Files.isRegularFile(file) && settings.privacyNoticeShown == shown
				&& settings.stutterMonitor == monitor && bad(dir).isEmpty(), "privacyNoticeShown " + settings.privacyNoticeShown + " (file "
				+ shown + "), stutterMonitor " + settings.stutterMonitor + " (file " + monitor + "), .bad files " + bad(dir));
	}

	// stutter.json (0.5 adds settingsAtStart/settingsAtEnd and the settingsChanged tag): every session loads and the
	// Copy summary renders it (an unknown tag by its key).
	private void stutter(Path config, Path dir) throws IOException {
		Path file = StutterStore.file(config);
		if (!Files.isRegularFile(file)) {
			check("StutterStore: every session loads and StutterSummary renders it", false, "no stutter.json in the sets");
			return;
		}
		int inFile = json(file).getAsJsonArray("sessions").size();
		List<StutterReport> sessions = new StutterStore(config).sessions();
		List<String> rendered = new ArrayList<>();
		String error = null;
		try {
			for (StutterReport session : sessions) {
				rendered.add(session.startedAt() + ": " + StutterSummary.text(session, List.of()).length() + " chars");
			}
		} catch (RuntimeException e) {
			error = e.toString();
		}
		check("StutterStore: every session loads and StutterSummary renders it", sessions.size() == inFile && inFile > 0 && error == null
				&& bad(dir).isEmpty(), sessions.size() + " of " + inFile + " session(s); " + rendered + (error == null ? "" : "; " + error)
				+ ", .bad files " + bad(dir));
	}

	// awareness.json (0.5 adds acknowledgedStartupRegressions, the options snapshot and new dismissed keys): 0.4.0 reads
	// its decisions, and a write of its own (on a copy) keeps every field it doesn't know.
	private void awareness(Path dir, Path scratch) throws IOException {
		Path file = dir.resolve(AwarenessStore.FILE_NAME);
		if (!Files.isRegularFile(file)) {
			check("AwarenessStore: loads, and an update on a copy keeps the unknown fields", false, "no awareness.json in the sets");
			return;
		}
		JsonObject raw = json(file);
		AwarenessStore store = AwarenessStore.shared(dir.getParent());
		List<String> dismissedInFile = new ArrayList<>();
		if (raw.get(AwarenessStore.DISMISSED) instanceof JsonArray a) {
			a.forEach(e -> dismissedInFile.add(e.getAsString()));
		}
		boolean read = store.writable() && store.dismissed().containsAll(dismissedInFile);

		Path copyConfig = scratch.resolve("awareness-copy").resolve("config");
		Files.createDirectories(copyConfig.resolve("rigtune"));
		Files.copy(file, copyConfig.resolve("rigtune").resolve(AwarenessStore.FILE_NAME));
		AwarenessStore copy = AwarenessStore.shared(copyConfig);
		boolean updated = copy.dismiss("compat040.check");
		JsonObject after = json(copyConfig.resolve("rigtune").resolve(AwarenessStore.FILE_NAME));
		List<String> lost = raw.keySet().stream().filter(k -> !after.has(k)).toList();
		check("AwarenessStore: loads, and an update on a copy keeps the unknown fields", read && updated && lost.isEmpty(),
				"writable " + store.writable() + ", dismissed " + store.dismissed().size() + " (file " + dismissedInFile.size() + "); update "
						+ updated + ", top-level fields lost " + lost + " of " + raw.keySet());
	}

	// profiles.json (0.5 adds no field; PF-1 stores the baseline in battery.previousProfile): profiles, switches and labels.
	private void profiles(Path config, Path dir, List<JournalEntry> entries) throws IOException {
		Path file = dir.resolve(ProfileStore.FILE_NAME);
		if (!Files.isRegularFile(file)) {
			check("ProfileStore: profiles.json loads with every switch's label", false, "no profiles.json in the sets");
			return;
		}
		JsonObject raw = json(file);
		ProfileStore store = ProfileStore.shared(config);
		int switches = raw.get(ProfileStore.SWITCHES) instanceof JsonArray a ? a.size() : 0;
		Map<String, String> labels = store.labels();
		check("ProfileStore: profiles.json loads with every switch's label", store.writable() && store.switches().size() == switches
				&& labels.size() == switches && !store.profiles().isEmpty(), "profiles " + store.profiles().size() + ", switches "
				+ store.switches().size() + " of " + switches + ", labels " + labels.values() + ", battery " + store.battery());
	}

	// server-limits.json (0.5 leaves it unchanged; C16 keeps its own file): loads writable, without a .bad.
	private void serverLimits(Path config, Path dir) throws IOException {
		Path file = ServerLimitsStore.file(config);
		if (!Files.isRegularFile(file)) {
			check("ServerLimitsStore: server-limits.json loads writable", false, "no server-limits.json in the sets");
			return;
		}
		ServerLimitsStore store = new ServerLimitsStore(config);
		check("ServerLimitsStore: server-limits.json loads writable", store.writable() && bad(dir).isEmpty(),
				"writable " + store.writable() + ", .bad files " + bad(dir));
	}

	// benchmark-restore.json (0.5 adds a DH world-generation target only if RW-6's pause ships): 0.4.0's restore puts
	// back what it knows and never fails on what it doesn't. On a copy: restorePending deletes or rewrites the marker.
	private void marker(Path dir, Path scratch) throws IOException {
		Path file = dir.resolve("benchmark-restore.json");
		if (!Files.isRegularFile(file)) {
			check("RestoreMarker: benchmark-restore.json restores what 0.4.0 knows", true, "no benchmark-restore.json in the sets");
			return;
		}
		Path copy = scratch.resolve("marker-copy").resolve("benchmark-restore.json");
		Files.createDirectories(copy.getParent());
		Files.copy(file, copy);
		List<String> set = new ArrayList<>();
		boolean done;
		String error = null;
		try {
			done = RestoreMarker.restorePending(copy, target("dh", set), target("iris", set));
		} catch (RuntimeException e) {
			done = false;
			error = e.toString();
		}
		check("RestoreMarker: benchmark-restore.json restores what 0.4.0 knows", done && error == null && !Files.exists(copy),
				"restored " + set + ", done " + done + (error == null ? "" : ", " + error));
	}

	private static RestoreMarker.Target target(String name, List<String> set) {
		return new RestoreMarker.Target() {
			@Override
			public boolean loaded() {
				return true;
			}

			@Override
			public boolean ready() {
				return true;
			}

			@Override
			public void set(boolean value) {
				set.add(name + "=" + value);
			}
		};
	}

	// rules-v2.json: the same counts as with every top-level section 0.4.0's RulesDocument doesn't declare removed.
	private void rules(Path file) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		JsonObject stripped = JsonParser.parseString(text).getAsJsonObject();
		Set<String> known = Set.copyOf(Arrays.stream(RulesDocument.class.getFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
				.map(Field::getName).toList());
		List<String> unknown = stripped.keySet().stream().filter(k -> !known.contains(k)).sorted().toList();
		unknown.forEach(stripped::remove);
		RulesDocument full = RulesLoader.parse(text);
		RulesDocument without = RulesLoader.parse(stripped.toString());
		Map<String, Integer> a = counts(full);
		Map<String, Integer> b = counts(without);
		check("RulesLoader: rules-v2.json parses with the same counts as without the sections 0.4.0 doesn't know", a.equals(b)
				&& full.revision == without.revision, "revision " + full.revision + ", counts " + a + (a.equals(b) ? "" : " vs " + b)
				+ "; sections 0.4.0 doesn't know: " + unknown);
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
		out.put("profileTemplates", doc.profileTemplates == null ? -1 : doc.profileTemplates.templates.size());
		out.put("stutterAdvice", doc.stutterAdvice == null ? -1 : doc.stutterAdvice.size());
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
