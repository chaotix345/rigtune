package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

// Would this mods folder start (review M9)? Shared by Undo (UndoPlanner) and the RigTune screen's "Disable X"
// (docs/v0.4/SPEC.md 2o, audit H1-B), so both refuse the same folders.
public final class FolderCheck {
	// UndoPlanner.LOADED_TWICE and MISSING (LangCheckTest reads a Text's English from this file's constants only;
	// UndoPlannerTextTest keeps one constant per undo reason there; FolderCheckTest checks they match).
	private static final String LOADED_TWICE = "mod %s would be loaded twice (%s)";
	private static final String MISSING = "%s would be missing %s";
	static final String NEEDED = "The game wouldn't start without it: %s";
	static final String CHANGE_STAGED = "Another change of it is staged; cancel that first (Undo last or Discard pending)";

	private FolderCheck() {
	}

	// missing: the mod id an active jar lacks, or null for a mod loaded twice.
	private record Problem(Text text, String missing) {
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
		Map<String, Text> out = new TreeMap<>();
		found(files, providedElsewhere).forEach((english, problem) -> out.put(english, problem.text()));
		return out;
	}

	private static Map<String, Problem> found(Map<String, ? extends Supplier<JarInfo>> files, Set<String> providedElsewhere) {
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
		Map<String, Problem> out = new TreeMap<>();
		namesById.forEach((id, names) -> {
			if (names.size() > 1) {
				Text twice = Text.of("rigtune.undo.reason.breaks.twice", LOADED_TWICE, id, String.join(", ", new TreeSet<>(names)));
				out.put(twice.english(), new Problem(twice, null));
			}
		});
		for (JarInfo info : active) {
			for (String dep : info.depends()) {
				if (!provided.contains(dep)) {
					Text missing = Text.of("rigtune.undo.reason.breaks.missing", MISSING, info.id(), dep);
					out.put(missing.english(), new Problem(missing, dep));
				}
			}
		}
		return out;
	}

	public static Text disableRefusal(UndoPlanner.Folder folder, List<Op> pending, String file) {
		return disableRefusals(folder, pending, List.of(file)).get(file);
	}

	// One Apply's "Disable" files (names in the mods folder), checked as a set: the ones that mustn't be staged, each
	// with why. The rest may be.
	// - Another change of that jar is staged that brings its mod back (an update's disable + enable): merging would drop
	//   the new disable as a repeat, and the enable would still bring the mod back at the next exit (audit M5).
	// - Once the staged ops ran (the helper runs them first) and the Apply's other disables too, a jar left active would
	//   lack a mod it depends on (audit H1-B). A disable that is the only thing providing such a mod is refused, one at a
	//   time, until the folder starts; disabling a library together with every jar that needs it goes ahead.
	public static Map<String, Text> disableRefusals(UndoPlanner.Folder folder, List<Op> pending, List<String> files) {
		Map<String, Text> refused = new LinkedHashMap<>();
		Map<String, Supplier<JarInfo>> sim = files(folder);
		List<String> accepted = new ArrayList<>();
		for (String file : new LinkedHashSet<>(files)) {
			if (changeStaged(folder, sim, pending, file)) {
				refused.put(file, Text.of("rigtune.toast.disable_refused.change_staged", CHANGE_STAGED));
			} else {
				accepted.add(file);
			}
		}
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
		Set<String> before = found(sim, folder.providedElsewhere()).keySet();
		while (!accepted.isEmpty()) {
			Map<String, Supplier<JarInfo>> after = new LinkedHashMap<>(sim);
			accepted.forEach(after::remove);
			Map<String, Problem> added = new TreeMap<>(found(after, folder.providedElsewhere()));
			added.keySet().removeAll(before);
			if (added.isEmpty()) {
				break;
			}
			String blamed = null;
			List<Text> why = new ArrayList<>();
			for (Problem problem : added.values()) {
				String provider = accepted.stream().filter(f -> provides(sim.get(f), problem.missing())).findFirst().orElse(null);
				if (provider != null && (blamed == null || blamed.equals(provider))) {
					blamed = provider;
					why.add(problem.text());
				}
			}
			if (blamed == null) {
				// Not reachable (only removing a jar can add a problem here); refuse the rest rather than loop.
				Text reason = Text.of("rigtune.toast.disable_refused.needed", NEEDED, Text.join("; ", added.values().stream().map(Problem::text).toList()));
				accepted.forEach(f -> refused.put(f, reason));
				break;
			}
			refused.put(blamed, Text.of("rigtune.toast.disable_refused.needed", NEEDED, Text.join("; ", why)));
			accepted.remove(blamed);
		}
		return refused;
	}

	private static boolean provides(Supplier<JarInfo> jar, String id) {
		JarInfo info = jar == null || id == null ? null : jar.get();
		return info != null && (id.equals(info.id()) || info.provides().contains(id));
	}

	// The jar's DISABLE is staged in a group whose ENABLE brings the same mod back (the same file name, or the same mod
	// id). A group that disables it and enables something else (an undo of several mods) already does what was asked.
	private static boolean changeStaged(UndoPlanner.Folder folder, Map<String, Supplier<JarInfo>> files, List<Op> pending, String file) {
		JarInfo jar = files.containsKey(file) ? files.get(file).get() : null;
		for (Op op : pending) {
			if (op == null || op.type() != PendingActions.Type.DISABLE_FILE || op.path() == null || op.group() == null
					|| !file.equals(HistoryUpdates.fileName(op.path()))) {
				continue;
			}
			for (Op other : pending) {
				if (other == null || other.type() != PendingActions.Type.ENABLE_FILE || !op.group().equals(other.group()) || other.to() == null) {
					continue;
				}
				String modId = other.modId();
				if (modId == null && other.from() != null) {
					JarInfo from = folder.jar(HistoryUpdates.fileName(other.from()));
					modId = from == null ? null : from.id();
				}
				if (file.equals(HistoryUpdates.fileName(other.to())) || jar == null || modId == null || Objects.equals(jar.id(), modId)) {
					return true;
				}
			}
		}
		return false;
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
