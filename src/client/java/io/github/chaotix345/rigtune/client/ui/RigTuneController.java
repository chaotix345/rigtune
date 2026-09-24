package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

public interface RigTuneController {
	/** Null while the report is still being built. */
	@Nullable Report report();

	Goal goal();

	void setGoal(Goal goal);

	Component apply(List<Recommendation> selected);

	void startBenchmark();

	void rescan();

	/** Latest message from background work (downloads, benchmark), polled by the screen. */
	default @Nullable Component status() {
		return null;
	}

	/** True while staged mod or Sodium changes wait for a restart (config/rigtune/pending.json exists). */
	default boolean hasPendingChanges() {
		return false;
	}

	/** Drops every staged change; returns a status message. */
	default Component discardPending() {
		return Component.translatable("rigtune.status.nothing");
	}
}
