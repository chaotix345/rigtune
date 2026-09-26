package io.github.chaotix345.rigtune.core.stutter;

// What the Stutter Doctor screen shows (docs/v0.4/SPEC.md 5, C4). Skeleton from the contracts commit; the Stutter
// Doctor workstream owns and extends it.
public record StutterView(boolean monitorOn, boolean paused, boolean enoughData) {
	public static final StutterView EMPTY = new StutterView(false, false, false);
}
