package io.github.chaotix345.rigtune.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// When the player last gave input, on Util.getMillis()'s clock (javap: a private long on 26.2, 26.3 and 26.4-pre-1, set by
// onInputReceived; getThrottleReason is SHORT_AFK once it is over 60 s old). Read by SettingsWatch (review-11 STUTTER-9).
@Mixin(FramerateLimitTracker.class)
public interface FramerateLimitTrackerAccessor {
	@Accessor("latestInputTime")
	long rigtune$latestInputTime();
}
