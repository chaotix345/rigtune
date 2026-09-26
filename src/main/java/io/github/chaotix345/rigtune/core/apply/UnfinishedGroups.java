package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// The renames of each group a helper run started but hasn't finished (docs/v0.4/design/ws-g3.md, audit H4). A group's
// entry is written just before its first rename, removed once the group is complete or rolled back, and stays when the
// helper is killed or a rollback fails, so the next run knows exactly which renames to finish or put back. A new file in
// config/rigtune/helper/ (HelperLauncher keeps it): 0.1.0-0.3.0 never read it, and their HelperLauncher deletes it.
// Helper-safe (core and Gson only) and best effort: an unreadable file counts as empty, and a failed write loses only
// the record, never the group's own renames.
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
	private boolean unreadable;

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
			out.unreadable = true;
		}
		return out;
	}

	List<Rename> of(String group) {
		return group == null ? List.of() : groups.getOrDefault(group, List.of());
	}

	void put(String group, List<Rename> renames) {
		if (group != null && !renames.equals(groups.get(group))) {
			groups.put(group, List.copyOf(renames));
			save();
		}
	}

	void remove(String group) {
		if (group != null && groups.remove(group) != null) {
			save();
		}
	}

	// Entries of groups no longer in the plan (finished by an older helper, or discarded in game) are stale, and so is
	// an unreadable file.
	void retainOnly(Collection<String> live) {
		if (groups.keySet().retainAll(live) || unreadable) {
			save();
		}
	}

	private void save() {
		try {
			if (groups.isEmpty()) {
				Files.deleteIfExists(file);
				unreadable = false;
				return;
			}
			List<Entry> entries = new ArrayList<>();
			groups.forEach((group, renames) -> entries.add(new Entry(group, renames)));
			AtomicFiles.writeString(file, PendingActions.GSON.toJson(new Doc(entries)));
			unreadable = false;
		} catch (IOException | RuntimeException e) {
			ApplyHelper.log("Could not update " + file + ": " + e);
		}
	}
}
