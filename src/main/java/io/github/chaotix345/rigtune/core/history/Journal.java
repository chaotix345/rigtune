package io.github.chaotix345.rigtune.core.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

// config/rigtune/history.json (docs/v0.2/SPEC.md item 3): every read-modify-write holds the apply lock and replaces
// the file atomically. A corrupt file is kept as history.json.bad and the journal starts fresh; a newer formatVersion
// is never overwritten. The apply helper runs this too, with only our jar and Gson on its classpath, so it logs
// through `Log` and never touches the mod's logger.
public final class Journal implements ChangeRecorder {
	public static final int FORMAT_VERSION = 1;
	public static final int MAX_ENTRIES = 50;
	public static final Duration LOCK_WAIT = Duration.ofSeconds(2);
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public interface Log {
		void warn(String message, Throwable error);
	}

	private record HistoryFile(int formatVersion, List<JournalEntry> entries) {
	}

	private enum State { MISSING, OK, CORRUPT, NEWER }

	private record Read(State state, List<JournalEntry> entries) {
	}

	private final Path configDir;
	private final Path file;
	private final String rigtuneVersion;
	private final String mcVersion;
	private final Log log;
	private final Duration lockWait;
	private final Supplier<JournalEntry> firstEntry;

	public Journal(Path configDir, String rigtuneVersion, String mcVersion, Log log) {
		this(configDir, rigtuneVersion, mcVersion, log, LOCK_WAIT, null);
	}

	// firstEntry: made under the lock when history.json is first created (the 0.1.x legacy import); null for none.
	public Journal(Path configDir, String rigtuneVersion, String mcVersion, Log log, Supplier<JournalEntry> firstEntry) {
		this(configDir, rigtuneVersion, mcVersion, log, LOCK_WAIT, firstEntry);
	}

	Journal(Path configDir, String rigtuneVersion, String mcVersion, Log log, Duration lockWait) {
		this(configDir, rigtuneVersion, mcVersion, log, lockWait, null);
	}

	private Journal(Path configDir, String rigtuneVersion, String mcVersion, Log log, Duration lockWait, Supplier<JournalEntry> firstEntry) {
		this.configDir = configDir;
		this.file = file(configDir);
		this.rigtuneVersion = rigtuneVersion;
		this.mcVersion = mcVersion;
		this.log = log;
		this.lockWait = lockWait;
		this.firstEntry = firstEntry;
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve("history.json");
	}

	public boolean exists() {
		return Files.exists(file);
	}

	public String mcVersion() {
		return mcVersion;
	}

	// Empty when the file is missing, corrupt or from a newer RigTune.
	public List<JournalEntry> entries() {
		try {
			Read read = read();
			return read.state() == State.OK ? read.entries() : List.of();
		} catch (IOException e) {
			log.warn("Could not read " + file, e);
			return List.of();
		}
	}

	public boolean readOnly() {
		try {
			return read().state() == State.NEWER;
		} catch (IOException e) {
			return false;
		}
	}

	// Replaces the entries with change(entries), capped, under the apply lock. False when the lock is still busy after
	// the wait or the file is from a newer RigTune.
	public boolean update(UnaryOperator<List<JournalEntry>> change) throws IOException {
		return update(change, true);
	}

	// As update(), but a missing file stays missing (the helper has nothing to update then).
	public boolean updateExisting(UnaryOperator<List<JournalEntry>> change) throws IOException {
		return update(change, false);
	}

	private boolean update(UnaryOperator<List<JournalEntry>> change, boolean create) throws IOException {
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.defaultPath(configDir), lockWait)) {
			if (lock == null) {
				return false;
			}
			Read read = read();
			List<JournalEntry> base = read.entries();
			switch (read.state()) {
				case MISSING -> {
					if (!create) {
						return false;
					}
					base = first();
				}
				case NEWER -> {
					return false;
				}
				case CORRUPT -> Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
				case OK -> {
				}
			}
			List<JournalEntry> next = cap(change.apply(List.copyOf(base)));
			if (read.state() == State.OK && next.equals(read.entries())) {
				return true;
			}
			AtomicFiles.writeString(file, GSON.toJson(new HistoryFile(FORMAT_VERSION, next)));
			return true;
		}
	}

	private List<JournalEntry> first() {
		if (firstEntry == null) {
			return List.of();
		}
		try {
			JournalEntry entry = firstEntry.get();
			return entry == null ? List.of() : List.of(entry);
		} catch (RuntimeException e) {
			log.warn("Could not make the first entry of " + file, e);
			return List.of();
		}
	}

	private Read read() throws IOException {
		if (!Files.exists(file)) {
			return new Read(State.MISSING, List.of());
		}
		String json = Files.readString(file, StandardCharsets.UTF_8);
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject() || !root.getAsJsonObject().has("formatVersion")) {
				return new Read(State.CORRUPT, List.of());
			}
			if (root.getAsJsonObject().get("formatVersion").getAsInt() > FORMAT_VERSION) {
				return new Read(State.NEWER, List.of());
			}
			HistoryFile history = GSON.fromJson(root, HistoryFile.class);
			List<JournalEntry> entries = history.entries() == null ? List.of()
					: history.entries().stream().filter(Objects::nonNull).toList();
			return new Read(State.OK, entries);
		} catch (JsonParseException | IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
			return new Read(State.CORRUPT, List.of());
		}
	}

	// Adds the changes to the entry with this id, creating it (now, this version) if it doesn't exist yet.
	@Override
	public void record(String entryId, String kind, List<JournalChange> changes) {
		if (changes == null || changes.isEmpty()) {
			return;
		}
		try {
			if (!update(entries -> withChanges(entries, entryId, kind, changes))) {
				log.warn("Could not record " + changes.size() + " RigTune change(s) in " + file
						+ (readOnly() ? ": it was written by a newer RigTune" : ": the apply lock is busy"), null);
			}
		} catch (IOException | RuntimeException e) {
			log.warn("Could not record " + changes.size() + " RigTune change(s) in " + file, e);
		}
	}

	// A new entry made now by this RigTune (e.g. an undo, with the undone entry's id or "all").
	public JournalEntry newEntry(String kind, String undoOf, List<JournalChange> changes) {
		return new JournalEntry(ChangeRecorder.newEntryId(), Instant.now().toString(), kind, rigtuneVersion, mcVersion, undoOf, changes);
	}

	// entries with the changes added to the entry with this id, or a new entry (now, this version) at the end.
	public List<JournalEntry> withChanges(List<JournalEntry> entries, String entryId, String kind, List<JournalChange> changes) {
		List<JournalEntry> out = new ArrayList<>(entries);
		for (int i = 0; i < out.size(); i++) {
			JournalEntry e = out.get(i);
			if (e.id().equals(entryId)) {
				List<JournalChange> all = new ArrayList<>(e.changes());
				all.addAll(changes);
				out.set(i, new JournalEntry(e.id(), e.at(), e.kind(), e.rigtuneVersion(), e.mcVersion(), e.undoOf(), all));
				return out;
			}
		}
		out.add(new JournalEntry(entryId, Instant.now().toString(), kind, rigtuneVersion, mcVersion, null, changes));
		return out;
	}

	// Keeps the newest MAX_ENTRIES. Oldest entries go first, preferring ones with nothing left to update or undo,
	// then ones with nothing staged (the helper still has to update those), then any.
	static List<JournalEntry> cap(List<JournalEntry> entries) {
		if (entries.size() <= MAX_ENTRIES) {
			return entries;
		}
		List<JournalEntry> out = new ArrayList<>(entries);
		List<Predicate<JournalEntry>> passes = List.of(Journal::finished, e -> !has(e, JournalChange.STAGED), e -> true);
		for (Predicate<JournalEntry> droppable : passes) {
			for (int i = 0; i < out.size() && out.size() > MAX_ENTRIES; ) {
				if (droppable.test(out.get(i))) {
					out.remove(i);
				} else {
					i++;
				}
			}
		}
		return out;
	}

	private static boolean finished(JournalEntry entry) {
		return !has(entry, JournalChange.STAGED) && (JournalEntry.UNDO.equals(entry.kind()) || !has(entry, JournalChange.APPLIED));
	}

	private static boolean has(JournalEntry entry, String status) {
		return entry.changes().stream().anyMatch(c -> status.equals(c.status()));
	}
}
