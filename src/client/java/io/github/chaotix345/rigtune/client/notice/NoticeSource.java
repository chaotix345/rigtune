package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// One feature's notice for the notice line (docs/v0.4/SPEC.md C3). current() is asked on screen init/rebuild only (X5),
// on the render thread: keep it cheap (read state computed elsewhere). act() gets the id of one of current()'s actions.
public interface NoticeSource {
	@Nullable Notice current();

	void act(String actionId);
}
