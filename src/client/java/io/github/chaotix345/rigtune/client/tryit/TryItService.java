package io.github.chaotix345.rigtune.client.tryit;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 6 (C09): Measured Try It. Reached only through RealController.v05() (X4); nothing happens in the
// constructor. Contracts skeleton (WS-K): no try, every action does nothing, until WS-T fills it in (its first derive is
// the start hook's, on Probes.EXECUTOR; its tick is its own END_CLIENT_TICK listener; its title toast is the title-screen
// hook's; it points Busy.tryItRunning at its state).
public final class TryItService {
	private final RealController controller;

	public TryItService(RealController controller) {
		this.controller = controller;
	}

	public TryItView view() {
		return TryItView.EMPTY;
	}

	public @Nullable Text refusal(Recommendation rec) {
		return TryItView.UNAVAILABLE;
	}

	public Component start(Recommendation rec, BenchmarkRequest.Scene scene) {
		return Component.translatable("rigtune.status.nothing");
	}

	public void measureNow() {
	}

	public Component keep() {
		return Component.translatable("rigtune.status.nothing");
	}

	public void cancel() {
	}

	// The start hook (V05Services.afterStart), on Probes.EXECUTOR.
	public void derive() {
	}

	// The title-screen hook (V05Services.titleScreen), once per launch on the render thread.
	public void titleToast(Minecraft minecraft) {
	}
}
