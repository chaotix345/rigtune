package io.github.chaotix345.rigtune.core.notice;

import io.github.chaotix345.rigtune.core.model.Text;

import java.util.Objects;

// A button on the notice line (docs/v0.4/SPEC.md C3). id goes back to the notice's source (NoticeSource.act).
public record NoticeAction(String id, Text label) {
	public NoticeAction {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(label, "label");
	}
}
