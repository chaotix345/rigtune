package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// The renames of each group a helper run started (docs/v0.4/design/ws-g3.md, audit H4). A group's entry is written just
// before its first rename and stays until last-apply.json holds the group's results; one left half-applied (a kill, a
// failed rollback) stays until a later run finishes it or rolls it back. So the next run knows exactly which renames to
// finish or put back, and what a redo reports as done. A new file in config/rigtune/helper/ (HelperLauncher keeps it):
// 0.1.0-0.3.0 never read it, and their HelperLauncher deletes it. Helper-safe (core and Gson only) and best effort: an
// unreadable file counts as empty, and a failed write loses only the record (retried at the next change), never a rename.
final class UnfinishedGroups {
	static final String FILE_NAME = "unfinished-groups.json";

	// op: the op's id; from/to: the rename's absolute paths (a disable's `to` is the .disabled name it was given).
	record Rename(String op, String from, String to) {
	}

	private record Entry(String group, List<Rename> renames) {
	}

	private record Doc(List<Entry> groups) {
	}

	private final Path file;
	private final Map<String, List<Rename>> groups = new LinkedHashMap<>();
	private final Set<String> finished = new HashSet<>();
	// The file doesn't hold `groups`: it was unreadable, or a write failed.
	private boolean dirty;

	private UnfinishedGroups(Path file) {
		this.file = file;
	}

	static Path file(Path configDir) {
		return HelperLauncher.helperDir(configDir).resolve(FILE_NAME);
	}

	static UnfinishedGroups load(Path configDir) {
		UnfinishedGroups out = new UnfinishedGroups(file(configDir));
		if (!Files.isRegularFile(out.file)) {
			return out;
		}
		try {
			Doc doc = PendingActions.GSON.fromJson(Files.readString(out.file, StandardCharsets.UTF_8), Doc.class);
			for (Entry entry : doc == null || doc.groups() == null ? List.<Entry>of() : doc.groups()) {
				if (entry != null && entry.group() != null && entry.renames() != null) {
					out.groups.put(entry.group(), entry.renames().stream().filter(Objects::nonNull).toList());
				}
			}
		} catch (IOException | RuntimeException e) {
			ApplyHelper.log("Could not read " + out.file + ": " + e);
			out.dirty = true;
		}
		return out;
	}

	// Every recorded rename, whichever group recorded it: staging may have moved an op into another group since.
	List<Rename> all() {
		return groups.values().stream().flatMap(List::stream).toList();
	}

	// A pass's renames for `group`, replacing any recorded for the same ops elsewhere.
	void put(String group, List<Rename> renames) {
		finished.remove(group);
		Set<String> ops = new HashSet<>();
		renames.forEach(r -> ops.add(r.op()));
		boolean changed = !renames.equals(groups.get(group));
		for (Map.Entry<String, List<Rename>> e : groups.entrySet()) {
			if (!e.getKey().equals(group) && e.getValue().stream().anyMatch(r -> r.op() != null && ops.contains(r.op()))) {
				e.setValue(e.getValue().stream().filter(r -> r.op() == null || !ops.contains(r.op())).toList());
				changed = true;
			}
		}
		groups.values().removeIf(List::isEmpty);
		groups.put(group, List.copyOf(renames));
		if (changed || dirty) {
			save();
		}
	}

	// The group ended with its renames all done or all put back: its entry goes once last-apply.json says so (prune).
	void finish(String group) {
		if (group != null && groups.containsKey(group)) {
			finished.add(group);
		}
	}

	// After last-apply.json is written: drops the finished groups and the renames of ops no longer in the plan (finished
	// by an older helper, discarded in game, or replaced by a newer staged op).
	void prune(Collection<String> planOpIds) {
		boolean changed = groups.keySet().removeAll(finished);
		finished.clear();
		for (Map.Entry<String, List<Rename>> e : groups.entrySet()) {
			List<Rename> kept = e.getValue().stream().filter(r -> r.op() == null || planOpIds.contains(r.op())).toList();
			if (kept.size() != e.getValue().size()) {
				e.setValue(kept);
				changed = true;
			}
		}
		groups.values().removeIf(List::isEmpty);
		if (changed || dirty) {
			save();
		}
	}

	private void save() {
		try {
			if (groups.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				List<Entry> entries = new ArrayList<>();
				groups.forEach((group, renames) -> entries.add(new Entry(group, renames)));
				AtomicFiles.writeString(file, PendingActions.GSON.toJson(new Doc(entries)));
			}
			dirty = false;
		} catch (IOException | RuntimeException e) {
			ApplyHelper.log("Could not update " + file + ": " + e);
			dirty = true;
		}
	}
}
