package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

import java.util.List;

// docs/v0.5/SPEC.md 7 (C16), docs/research/v0.5/feature-server-profiles.md §2.3: ServerProfilesScreen's read model, so
// RigTuneController can return it and stub controllers can fake it. No address, host or port: rows are keyed by the
// entry's HMAC key. Contracts skeleton (WS-K); WS-P2 builds it (ServerProfileService.view()).
public record ServerProfilesView(State state, ServerLimits.@Nullable Kind kind, @Nullable String currentKey, @Nullable String currentProfile,
		@Nullable Text currentProfileName, boolean heldOnBattery, @Nullable String activeProfile, @Nullable Text activeProfileName, List<Row> rows,
		boolean writable) {
	public static final ServerProfilesView EMPTY = new ServerProfilesView(State.NOT_CONNECTED, null, null, null, null, false, null, null, List.of(),
			true);

	public enum State {
		NOT_CONNECTED,
		OWN_WORLD,
		UNRECOGNISED,
		SERVER
	}

	// lastJoined: the local day, yyyy-MM-dd. current: the server this session is connected to.
	public record Row(String key, ServerLimits.Kind kind, String profile, @Nullable Text profileName, @Nullable String lastJoined, boolean current) {
	}

	public ServerProfilesView {
		state = state == null ? State.NOT_CONNECTED : state;
		rows = rows == null ? List.of() : List.copyOf(rows);
	}
}
