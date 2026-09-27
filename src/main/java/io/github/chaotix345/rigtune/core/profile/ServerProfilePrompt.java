package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.jspecify.annotations.Nullable;

import java.util.List;

// Whether joining a server OFFERS the profile the player set for it (docs/v0.5/SPEC.md 7, C16; sp §2.3), and the offer's
// notice and toast (sp §2.5). RigTune never switches by itself: this only decides whether the toast and the
// SERVER_PROFILE notice appear. Checked in the order the reasons are declared: not a server (the own world, an
// Open-to-LAN host, the benchmark world), nothing set here, a deleted or unknown profile (fail closed), that profile
// already active, a benchmark running, on battery with Battery active (held: the player chose Battery for the power
// state; the This-server line says why), else OFFER.
public final class ServerProfilePrompt {
	// The notice's key is per join (KEY_PREFIX + the join's epoch ms): its × hides it for that connection only, and
	// AwarenessService never stores such a key (SESSION_ONLY_PREFIXES).
	public static final String KEY_PREFIX = "server-profile:";
	public static final String ACTION_SWITCH = "switch";
	public static final String ACTION_FORGET = "forget";

	public enum Reason { NO_SERVER, NO_MAPPING, MISSING_PROFILE, ALREADY_ACTIVE, BENCHMARK, ON_BATTERY, OFFER }

	private ServerProfilePrompt() {
	}

	// kind: the connection's kind, null when not connected; mapped: the profile set for this server, null when none;
	// mappedResolves: whether this version knows that profile now; active: the active profile id, null when none.
	public static Reason decide(ServerLimits.@Nullable Kind kind, @Nullable String mapped, boolean mappedResolves, @Nullable String active,
			boolean benchmarkRunning, boolean onBattery) {
		if (kind == null || kind == ServerLimits.Kind.SINGLEPLAYER) {
			return Reason.NO_SERVER;
		}
		if (mapped == null) {
			return Reason.NO_MAPPING;
		}
		if (!mappedResolves) {
			return Reason.MISSING_PROFILE;
		}
		if (mapped.equals(active)) {
			return Reason.ALREADY_ACTIVE;
		}
		if (benchmarkRunning) {
			return Reason.BENCHMARK;
		}
		if (onBattery && BatteryPrompt.BATTERY.equals(active)) {
			return Reason.ON_BATTERY;
		}
		return Reason.OFFER;
	}

	// name: the profile's display name (a saved name is a literal, never a format).
	public static Notice notice(long joinedAtMillis, Text name) {
		return new Notice(KEY_PREFIX + joinedAtMillis, NoticePriority.SERVER_PROFILE,
				Text.of("rigtune.profile.server.offer", "You set %s for this server. Switch to it?", name),
				Text.of("rigtune.profile.server.offer.detail",
						"You asked RigTune to offer it here (Profiles, Servers…). It never switches by itself, and History can undo the switch."),
				List.of(new NoticeAction(ACTION_SWITCH, Text.of("rigtune.profile.server.action.switch", "Switch")),
						new NoticeAction(ACTION_FORGET, Text.of("rigtune.profile.server.action.forget", "Don't offer here"))),
				true);
	}

	public static Text toastTitle() {
		return Text.of("rigtune.profile.server.toast.title", "Profile for this server");
	}

	// key: the name of RigTune's key (F8 unless the player changed it).
	public static Text toastBody(Text name, Text key) {
		return Text.of("rigtune.profile.server.toast.body", "%s is set for this server. Press %s to switch.", name, key);
	}
}
