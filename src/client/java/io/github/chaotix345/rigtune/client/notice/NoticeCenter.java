package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeBoard;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// The notice slot's model (docs/v0.4/SPEC.md C3): asks every registered source for its current notice, leaves out the
// dismissed ones and orders the rest by priority (NoticeBoard). A source that throws is skipped (logged), never the
// screen's problem. Dismissals go to Dismissals: AwarenessService, through AwarenessStore (awareness.json).
// v0.5 (docs/v0.5/SPEC.md X4, C3): the sources can come from one supplier, resolved on the first notices() or act() call
// (never at construction, so no source class loads during startup), in NoticePriority order.
public final class NoticeCenter {
	public interface Dismissals {
		Set<String> dismissed();

		void dismiss(String key);
	}

	private final Supplier<List<NoticeSource>> supplier;
	private final Dismissals dismissals;
	private volatile @Nullable List<NoticeSource> sources;

	public NoticeCenter(List<NoticeSource> sources, Dismissals dismissals) {
		this.sources = List.copyOf(sources);
		this.supplier = () -> this.sources;
		this.dismissals = dismissals;
	}

	public NoticeCenter(Supplier<List<NoticeSource>> sources, Dismissals dismissals) {
		this.supplier = sources;
		this.dismissals = dismissals;
	}

	private List<NoticeSource> sources() {
		List<NoticeSource> resolved = sources;
		if (resolved == null) {
			synchronized (this) {
				resolved = sources;
				if (resolved == null) {
					resolved = List.copyOf(supplier.get());
					sources = resolved;
				}
			}
		}
		return resolved;
	}

	// Kept in memory only.
	public static Dismissals inMemory() {
		Set<String> keys = ConcurrentHashMap.newKeySet();
		return new Dismissals() {
			@Override
			public Set<String> dismissed() {
				return Set.copyOf(keys);
			}

			@Override
			public void dismiss(String key) {
				keys.add(key);
			}
		};
	}

	// What may show, best first (the screen shows the first and "+N more").
	public List<Notice> notices() {
		List<Notice> current = new ArrayList<>();
		for (NoticeSource source : sources()) {
			Notice notice = current(source);
			if (notice != null) {
				current.add(notice);
			}
		}
		return current.isEmpty() ? List.of() : NoticeBoard.select(current, dismissals.dismissed()).visible();
	}

	public void act(String key, String actionId) {
		for (NoticeSource source : sources()) {
			Notice notice = current(source);
			if (notice != null && notice.key().equals(key)) {
				try {
					source.act(actionId);
				} catch (RuntimeException e) {
					RigTune.LOGGER.warn("Notice action {} on {} failed", actionId, key, e);
				}
				return;
			}
		}
	}

	public void dismiss(String key) {
		dismissals.dismiss(key);
	}

	private static @Nullable Notice current(NoticeSource source) {
		try {
			return source.current();
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Notice source {} failed", source.getClass().getSimpleName(), e);
			return null;
		}
	}
}
