package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

// Would this mods folder start (review M9)? Shared by Undo (UndoPlanner) and the RigTune screen's "Disable X"
// (docs/v0.4/SPEC.md 2o, audit H1-B), so both refuse the same folders.
public final class FolderCheck {
	// UndoPlanner.LOADED_TWICE and MISSING (UndoPlannerTextTest keeps one constant per undo reason there).
	private static final String LOADED_TWICE = "mod %s would be loaded twice (%s)";
	private static final String MISSING = "%s would be missing %s";
	static final String NEEDED = "The game wouldn't start without it: %s";
	static final String CHANGE_STAGED = "Another change of it is staged; cancel that first (Undo last or Discard pending)";

	private FolderCheck() {
	}

	// The folder's files, each jar's metadata read once, when asked.
	public static Map<String, Supplier<JarInfo>> files(UndoPlanner.Folder folder) {
		Map<String, Supplier<JarInfo>> out = new LinkedHashMap<>();
		for (String name : new TreeSet<>(folder.files())) {
			out.put(name, once(() -> folder.jar(name)));
		}
		return out;
	}

	// Reasons the folder wouldn't start: a mod id on two active jars, or an active jar without a mod it depends on. By
	// their English, sorted, so a plan compares and lists them the same way in every language.
	public static Map<String, Text> problems(Map<String, ? extends Supplier<JarInfo>> files, Set<String> providedElsewhere) {
		Set<String> provided = new HashSet<>(UndoPlanner.ALWAYS_PROVIDED);
		provided.addAll(providedElsewhere);
		Map<String, List<String>> namesById = new HashMap<>();
		List<JarInfo> active = new ArrayList<>();
		for (Map.Entry<String, ? extends Supplier<JarInfo>> e : files.entrySet()) {
			if (!e.getKey().endsWith(".jar")) {
				continue;
			}
			JarInfo info = e.getValue().get();
			if (info == null) {
				continue;
			}
			active.add(info);
			namesById.computeIfAbsent(info.id(), k -> new ArrayList<>()).add(e.getKey());
			provided.add(info.id());
			provided.addAll(info.provides());
		}
		Map<String, Text> out = new TreeMap<>();
		namesById.forEach((id, names) -> {
			if (names.size() > 1) {
				Text twice = Text.of("rigtune.undo.reason.breaks.twice", LOADED_TWICE, id, String.join(", ", new TreeSet<>(names)));
				out.put(twice.english(), twice);
			}
		});
		for (JarInfo info : active) {
			for (String dep : info.depends()) {
				if (!provided.contains(dep)) {
					Text missing = Text.of("rigtune.undo.reason.breaks.missing", MISSING, info.id(), dep);
					out.put(missing.english(), missing);
				}
			}
		}
		return out;
	}

	// Why "Disable <file>" (a file name in the mods folder) mustn't be staged, or null:
	// - another change of that jar is staged (an update's disable): merging would drop the new disable as a repeat, and
	//   the update would still bring the mod back at the next exit (audit M5);
	// - a jar left active once the staged ops ran (the helper runs them first) depends on it (audit H1-B).
	public static Text disableRefusal(UndoPlanner.Folder folder, List<Op> pending, String file) {
		for (Op op : pending) {
			if (op != null && op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null && op.group() != null
					&& file.equals(HistoryUpdates.fileName(op.path()))
					&& pending.stream().anyMatch(o -> o != null && o.type() == PendingActions.Type.ENABLE_FILE && op.group().equals(o.group()))) {
				return Text.of("rigtune.toast.disable_refused.change_staged", CHANGE_STAGED);
			}
		}
		Map<String, Supplier<JarInfo>> sim = files(folder);
		for (Op op : pending) {
			if (op == null || op.type() == null) {
				continue;
			}
			if (op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null) {
				sim.remove(HistoryUpdates.fileName(op.path()));
			} else if (op.type() == PendingActions.Type.ENABLE_FILE && op.from() != null && op.to() != null) {
				String from = HistoryUpdates.fileName(op.from());
				sim.put(HistoryUpdates.fileName(op.to()), once(() -> folder.jar(from)));
			}
		}
		Map<String, Text> before = problems(sim, folder.providedElsewhere());
		sim.remove(file);
		Map<String, Text> added = new TreeMap<>(problems(sim, folder.providedElsewhere()));
		added.keySet().removeAll(before.keySet());
		return added.isEmpty() ? null : Text.of("rigtune.toast.disable_refused.needed", NEEDED, Text.join("; ", List.copyOf(added.values())));
	}

	private static Supplier<JarInfo> once(Supplier<JarInfo> read) {
		return new Supplier<>() {
			private boolean done;
			private JarInfo info;

			@Override
			public JarInfo get() {
				if (!done) {
					done = true;
					info = read.get();
				}
				return info;
			}
		};
	}
}
