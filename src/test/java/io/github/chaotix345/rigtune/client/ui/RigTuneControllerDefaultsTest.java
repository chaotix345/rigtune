package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

// docs/v0.5/SPEC.md C4 (PLAN contracts item 7): every v0.5 default is 0.4's behaviour, so StubController and the game-test
// wrappers compile unchanged and answer as before.
class RigTuneControllerDefaultsTest {
	private static final class Minimal implements RigTuneController {
		final List<List<Recommendation>> applied = new ArrayList<>();

		@Override
		public @Nullable Report report() {
			return null;
		}

		@Override
		public Goal goal() {
			return Goal.BALANCED;
		}

		@Override
		public void setGoal(Goal goal) {
		}

		@Override
		public Component apply(List<Recommendation> selected) {
			applied.add(selected);
			return Component.literal("applied");
		}

		@Override
		public void startBenchmark() {
		}

		@Override
		public void rescan() {
		}
	}

	private static String key(Component component) {
		return component.getContents() instanceof TranslatableContents t ? t.getKey() : component.getString();
	}

	@Test
	void theDefaultsAreTheOldBehaviour() {
		Minimal controller = new Minimal();
		Recommendation rec = new Recommendation("setting:vanilla.renderDistance", Category.SETTING, Impact.MEDIUM, "Render distance", "r",
				new Action.SetSetting("vanilla.renderDistance", "12", "10"), true);
		assertEquals("applied", controller.apply(List.of(rec), "entry-1").getString(), "apply(selected, entryId) is apply(selected)");
		assertEquals(List.of(List.of(rec)), controller.applied);
		assertFalse(controller.firstApplyPending());
		assertFalse(controller.downloading());
		assertEquals(ModFilesPolicy.RIGTUNE, controller.modFiles());

		FixOffer.Offer offer = new FixOffer.Offer("stutter-chunk-loading", "vanilla.renderDistance", "12", "10", true);
		assertSame(ApplyPreview.EMPTY, controller.previewStutterFix(offer));
		assertEquals("rigtune.status.nothing", key(controller.applyStutterFix(offer)));
		controller.dismissStutterFix("entry-1");

		assertSame(TryItView.EMPTY, controller.tryIt());
		assertSame(TryItView.UNAVAILABLE, controller.tryItRefusal(rec));
		assertEquals("rigtune.tryit.refused.unavailable", ((Text.Translatable) TryItView.UNAVAILABLE).key());
		assertEquals("rigtune.status.nothing", key(controller.startTryIt(rec, BenchmarkRequest.Scene.CURRENT)));
		controller.tryItMeasureNow();
		assertEquals("rigtune.status.nothing", key(controller.tryItKeep()));
		controller.tryItCancel();

		assertSame(ServerProfilesView.EMPTY, controller.serverProfiles());
		assertEquals("rigtune.status.nothing", key(controller.rememberServerProfile(null)));
		assertEquals("rigtune.status.nothing", key(controller.forgetServerProfile("k")));
		assertEquals("rigtune.status.nothing", key(controller.forgetAllServerProfiles()));
		assertEquals(1, controller.applied.size(), "no default applies anything");
	}
}
