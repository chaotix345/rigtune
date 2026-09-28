package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// docs/v0.5/SPEC.md 7 (C16), docs/research/v0.5/feature-server-profiles.md §2.3, §2.5: ServerProfilesScreen's read model,
// so RigTuneController can return it and stub controllers can fake it. No address, host or port: rows are keyed by the
// entry's HMAC key. ServerProfileService.view() builds it with of(); here(), Row.text() and the status lines are the
// screen's words.
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

	// lastJoined: the local day, yyyy-MM-dd. current: the server this session is connected to. profileName: null when the
	// profile doesn't resolve now (deleted, or a template this version doesn't know).
	public record Row(String key, ServerLimits.Kind kind, String profile, @Nullable Text profileName, @Nullable String lastJoined, boolean current) {
		// "Server · Max FPS · last joined 2026-09-27".
		public Text text() {
			Text kindText = switch (kind) {
				case LAN_GUEST -> Text.of("rigtune.profile.server.kind.lan", "LAN game");
				case REALM -> Text.of("rigtune.profile.server.kind.realm", "Realm");
				case REMOTE, SINGLEPLAYER -> Text.of("rigtune.profile.server.kind.remote", "Server");
			};
			return lastJoined == null ? Text.of("rigtune.profile.server.row.undated", "%s · %s", kindText, name(profile, profileName))
					: Text.of("rigtune.profile.server.row", "%s · %s · last joined %s", kindText, name(profile, profileName), lastJoined);
		}
	}

	public ServerProfilesView {
		state = state == null ? State.NOT_CONNECTED : state;
		rows = rows == null ? List.of() : List.copyOf(rows);
	}

	// kind: the connection's; currentKey: this server's key (ServerProfileStore.keyOf; used only in the SERVER state);
	// entries: the store's, in its order; names: a profile id's display name, null when it doesn't resolve now; active:
	// the active profile id; onBattery: on battery power now; zone: the player's, for "last joined".
	public static ServerProfilesView of(State state, ServerLimits.@Nullable Kind kind, @Nullable String currentKey, List<ServerProfileStore.Entry> entries,
			Function<String, @Nullable Text> names, @Nullable String active, boolean onBattery, boolean writable, ZoneId zone) {
		String here = state == State.SERVER ? currentKey : null;
		String currentProfile = null;
		List<Row> rows = new ArrayList<>();
		for (ServerProfileStore.Entry entry : entries) {
			boolean current = entry.key().equals(here);
			if (current) {
				currentProfile = entry.profile();
			}
			rows.add(new Row(entry.key(), entry.kind(), entry.profile(), names.apply(entry.profile()), day(entry.lastSeen(), zone), current));
		}
		Text currentName = currentProfile == null ? null : names.apply(currentProfile);
		boolean held = currentProfile != null
				&& ServerProfilePrompt.decide(kind, currentProfile, currentName != null, active, false, onBattery) == ServerProfilePrompt.Reason.ON_BATTERY;
		return new ServerProfilesView(state, kind, here, currentProfile, currentName, held, active, active == null ? null : names.apply(active), rows,
				writable);
	}

	// The local day of `at`, or null when there's none, or no calendar day can hold it (review-11 SEC-2: the store bounds
	// the times it reads; a view built from elsewhere must not throw on screen init either).
	private static @Nullable String day(@Nullable Instant at, ZoneId zone) {
		if (at == null) {
			return null;
		}
		try {
			return LocalDate.ofInstant(at, zone).toString();
		} catch (DateTimeException e) {
			return null;
		}
	}

	// The This-server line.
	public Text here() {
		return switch (state) {
			case NOT_CONNECTED -> Text.of("rigtune.profile.server.not_connected", "Join a server to set a profile for it.");
			case OWN_WORLD -> Text.of("rigtune.profile.server.own_world", "Profiles are offered on servers, not in your own worlds.");
			case UNRECOGNISED -> Text.of("rigtune.profile.server.unrecognised", "RigTune can't recognise this server, so it can't remember a profile for it.");
			case SERVER -> {
				if (currentProfile == null) {
					yield Text.of("rigtune.profile.server.here.none", "This server: no profile set.");
				}
				if (currentProfileName == null) {
					yield Text.of("rigtune.profile.server.here.missing", "This server: set to %s, so RigTune offers nothing here.", name(currentProfile, null));
				}
				yield heldOnBattery
						? Text.of("rigtune.profile.server.here.battery", "This server: %s, but not while you're on battery power with the Battery profile.",
								currentProfileName)
						: Text.of("rigtune.profile.server.here", "This server: RigTune offers %s when you join.", currentProfileName);
			}
		};
	}

	// After "Offer %s here".
	public static Text remembered(ServerProfileStore.Result result, Text name) {
		return switch (result) {
			case OK -> Text.of("rigtune.profile.server.status.remembered", "RigTune will offer %s when you join this server.", name);
			case FULL -> Text.of("rigtune.profile.server.status.full", "You've set profiles for %s servers. Forget one first.", ServerProfileStore.MAX_SERVERS);
			case READ_ONLY, FAILED -> notWritten(result);
		};
	}

	// After "Stop offering here", Forget or the notice's "Don't offer here".
	public static Text forgot(ServerProfileStore.Result result) {
		return result == ServerProfileStore.Result.OK ? Text.of("rigtune.profile.server.status.forgot", "RigTune won't offer a profile on this server any more.")
				: notWritten(result);
	}

	public static Text forgotAll(ServerProfileStore.Result result) {
		return result == ServerProfileStore.Result.OK
				? Text.of("rigtune.profile.server.status.forgot_all", "RigTune won't offer profiles on servers until you set one again.")
				: notWritten(result);
	}

	// READ_ONLY only for a newer RigTune's file; FAILED for anything else (unreadable now, over the cap, a write error),
	// whose reason JsonStateFile logs.
	private static Text notWritten(ServerProfileStore.Result result) {
		return result == ServerProfileStore.Result.READ_ONLY
				? Text.of("rigtune.profile.server.status.read_only", "server-profiles.json was written by a newer RigTune, so this version doesn't change it.")
				: Text.of("rigtune.profile.server.status.failed", "RigTune couldn't save that (the log says why).");
	}

	private static Text name(String profile, @Nullable Text name) {
		if (name != null) {
			return name;
		}
		return profile.startsWith(ProfileStore.TEMPLATE_PREFIX) ? Text.of("rigtune.profile.server.unknown", "a profile this version doesn't know")
				: Text.of("rigtune.profile.server.missing", "a deleted profile");
	}
}
