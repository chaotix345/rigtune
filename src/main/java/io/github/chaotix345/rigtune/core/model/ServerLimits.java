package io.github.chaotix345.rigtune.core.model;

// The limits the connected server sent (docs/v0.4/SPEC.md 8): its raw view-distance and simulation-distance.
// SINGLEPLAYER includes an Open-to-LAN host (its limit is its own options).
public record ServerLimits(int viewDistance, int simulationDistance, Kind kind, long lastSeenEpochMillis) {
	public enum Kind { SINGLEPLAYER, LAN_GUEST, REALM, REMOTE }
}
