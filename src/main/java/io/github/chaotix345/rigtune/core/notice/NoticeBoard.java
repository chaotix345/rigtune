package io.github.chaotix345.rigtune.core.notice;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Picks what the one notice slot shows (docs/v0.4/SPEC.md C3): notices ordered by priority (declaration order of
// NoticePriority; the sources' order within one priority), dismissed ones left out (a notice that can't be dismissed
// always shows), each key once.
public final class NoticeBoard {
	private NoticeBoard() {
	}

	// visible: what may show, best first. top: the one shown; others: how many more ("+N more"); at(i): the i-th, cycling.
	public record Selection(List<Notice> visible) {
		public Selection {
			visible = List.copyOf(visible);
		}

		public @Nullable Notice top() {
			return visible.isEmpty() ? null : visible.getFirst();
		}

		public int others() {
			return Math.max(0, visible.size() - 1);
		}

		public @Nullable Notice at(int index) {
			return visible.isEmpty() ? null : visible.get(Math.floorMod(index, visible.size()));
		}
	}

	public static Selection select(List<Notice> notices, Set<String> dismissed) {
		List<Notice> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Notice notice : notices) {
			if (notice == null || !seen.add(notice.key())) {
				continue;
			}
			if (notice.dismissible() && dismissed != null && dismissed.contains(notice.key())) {
				continue;
			}
			out.add(notice);
		}
		out.sort(Comparator.comparingInt(n -> n.priority().ordinal()));
		return new Selection(out);
	}
}
