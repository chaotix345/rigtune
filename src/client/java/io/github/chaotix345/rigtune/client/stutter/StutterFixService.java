package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.stutter.FixHold;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import net.minecraft.network.chat.Component;

import java.util.List;

// docs/v0.5/SPEC.md 5 (C20): the Stutter Doctor's one-click fixes. Reached only through RealController.v05() (X4);
// nothing happens in the constructor. Contracts skeleton (WS-K): nothing offered or tracked, until WS-S2 fills it in.
public final class StutterFixService {
	private final RealController controller;

	public StutterFixService(RealController controller) {
		this.controller = controller;
	}

	// Off the render thread (PreviewScreen's loader).
	public ApplyPreview preview(FixOffer.Offer offer) {
		return ApplyPreview.EMPTY;
	}

	public Component apply(FixOffer.Offer offer) {
		return Component.translatable("rigtune.status.nothing");
	}

	public void dismiss(String entryId) {
	}

	// The report post-step (V05Hooks.afterRecommend), on the rebuild's worker: the active fixes FixHold keeps.
	public List<FixHold.Hold> holds() {
		return List.of();
	}
}
