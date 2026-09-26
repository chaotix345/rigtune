package io.github.chaotix345.rigtune.core.notice;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

// One notice for RigTuneScreen's single notice line (docs/v0.4/SPEC.md C3). key: stable per notice instance (dismissals
// are stored by it, in awareness.json). The line shows message (detail as its tooltip), at most the first 2 actions, and
// a dismiss button when dismissible.
public record Notice(String key, NoticePriority priority, Text message, @Nullable Text detail, List<NoticeAction> actions,
		boolean dismissible) {
	public Notice {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(priority, "priority");
		Objects.requireNonNull(message, "message");
		actions = actions == null ? List.of() : List.copyOf(actions);
	}
}
