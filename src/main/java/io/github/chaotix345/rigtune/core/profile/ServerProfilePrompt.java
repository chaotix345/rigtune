package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import org.jspecify.annotations.Nullable;

// Whether joining a server OFFERS the profile the player set for it (docs/v0.5/SPEC.md 7, C16; sp §2.3). RigTune never
// switches by itself: this only decides whether the toast and the SERVER_PROFILE notice appear. Checked in the order the
// reasons are declared: not a server (the own world, an Open-to-LAN host, the benchmark world), nothing set here, a
// deleted or unknown profile (fail closed), that profile already active, a benchmark running, on battery with Battery
// active (held: the player chose Battery for the power state; the This-server line says why), else OFFER.
public final class ServerProfilePrompt {
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
}
