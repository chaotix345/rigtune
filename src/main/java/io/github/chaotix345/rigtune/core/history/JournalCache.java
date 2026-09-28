package io.github.chaotix345.rigtune.core.history;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

// review 11 PERF-2 (WS-H): the worker's readers of history.json (the rebuild's stutter-fix hold, the start hook's first-run,
// Try It and outside-changes checks) share one parsed Journal.Snapshot per Journal, reused while the file is unchanged: the
// same size, last-modified time and file key, and no write through that Journal since. A miss reads and parses as
// Journal.snapshot() does, so this is for worker threads, never the render thread. The snapshot is shared: callers must not
// change its entries. A missing or unreadable file is never kept (a missing one costs a Files.exists). In the game, pass
// ClientJournal.get(), never a new Journal: the cache is per Journal, and only that one's writes are seen whatever the
// file's timestamps say.
public final class JournalCache {
	// Weak keys; a cache never refers to its Journal, so a Journal no one else holds goes with its cache.
	private static final Map<Journal, JournalCache> CACHES = Collections.synchronizedMap(new WeakHashMap<>());

	private @Nullable Key key;
	private Journal.@Nullable Snapshot snapshot;

	private record Key(long writes, long size, FileTime modified, @Nullable Object fileKey) {
	}

	private JournalCache() {
	}

	public static Journal.Snapshot snapshot(Journal journal) {
		return CACHES.computeIfAbsent(journal, j -> new JournalCache()).get(journal);
	}

	private synchronized Journal.Snapshot get(Journal journal) {
		Key before = key(journal);
		if (before != null && before.equals(key) && snapshot != null) {
			return snapshot;
		}
		Journal.Snapshot read = journal.snapshot();
		// Kept only when the file didn't change while it was read.
		boolean keep = before != null && before.equals(key(journal)) && read.state() != Journal.State.UNREADABLE;
		key = keep ? before : null;
		snapshot = keep ? read : null;
		return read;
	}

	private static @Nullable Key key(Journal journal) {
		long writes = journal.writes();
		try {
			BasicFileAttributes attributes = Files.readAttributes(journal.path(), BasicFileAttributes.class);
			return new Key(writes, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
