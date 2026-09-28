package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// The title screen's toasts about the helper's last run and what is still staged (docs/v0.3/SPEC.md 3e; v0.5 SPEC 4d,
// 4f), and the WARN lines that go with the leftovers. Pure: RigTuneClient.showNotices shows them.
public final class HelperToasts {
	public enum Kind {
		RESULT, DROPPED, LEFTOVER, HELD
	}

	public record Toast(Kind kind, Component title, Component body) {
	}

	private HelperToasts() {
	}

	// 4f: "N of M changes failed" counts FAILED ops only (retried at exit); ABANDONED ones (dropped: failed 3 times, or the
	// mod was installed another way) appear only in the dropped toast; the applied toast counts the applied ops.
	public static List<Toast> result(ApplyResult result) {
		ApplyResult.Counts counts = result.counts();
		List<Toast> out = new ArrayList<>();
		if (counts.failed() > 0) {
			out.add(new Toast(Kind.RESULT, Component.translatable("rigtune.toast.failed.title", counts.failed(), counts.total()),
					Component.translatable("rigtune.toast.failed.body")));
		} else if (counts.applied() > 0) {
			out.add(new Toast(Kind.RESULT, Component.translatable("rigtune.toast.applied.title", counts.applied()), Component.translatable("rigtune.toast.applied.body")));
		}
		if (counts.dropped() > 0) {
			out.add(new Toast(Kind.DROPPED, Component.translatable("rigtune.toast.abandoned.title", counts.dropped()),
					Component.translatable("rigtune.toast.abandoned.body")));
		}
		return out;
	}

	// 4d: retried: staged ops the next exit's helper runs; held: mod changes it holds for the player's choice (an instance
	// whose launcher keeps its own list of mods), which never read "retried at the next exit".
	public static List<Toast> leftover(int retried, int held) {
		List<Toast> out = new ArrayList<>();
		if (retried > 0) {
			out.add(new Toast(Kind.LEFTOVER, Component.translatable("rigtune.toast.leftover.title", retried), Component.translatable("rigtune.toast.leftover.body")));
		}
		if (held > 0) {
			out.add(new Toast(Kind.HELD, Component.translatable("rigtune.toast.held.title", held), Component.translatable("rigtune.toast.held.body")));
		}
		return out;
	}

	public static List<String> warnLines(int retried, int held) {
		List<String> out = new ArrayList<>();
		if (retried > 0) {
			out.add(retried + " staged RigTune change(s) were not applied; they will be retried at the next exit");
		}
		if (held > 0) {
			out.add(held + " mod change(s) from an earlier Apply are waiting for your choice: this instance's launcher keeps its own list of mods,"
					+ " so RigTune holds them at exit (cancel them or let RigTune apply them from RigTune's notice)");
		}
		return out;
	}
}
