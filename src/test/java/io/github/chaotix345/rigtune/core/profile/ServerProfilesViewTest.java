package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView.Row;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView.State;
import io.github.chaotix345.rigtune.core.server.ServerLimitsStore;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore.Entry;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (AC7.11's and AC7.17's unit parts), sp §2.5-2.6: ServerProfilesScreen's read model. The This-server
// line for each state, the rows ("Server · Max FPS · last joined 2026-09-27", the current one marked; a deleted or
// unknown profile named as such), the status lines, and no address, host or port anywhere (rows are keyed by the HMAC).
class ServerProfilesViewTest {
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final String MAX_FPS = "template:max_fps";
	private static final String QUALITY = "template:quality";
	private static final String BATTERY = "template:battery";
	private static final String KEY_A = "a".repeat(64);
	private static final String KEY_B = "b".repeat(64);
	private static final String KEY_C = "c".repeat(64);
	private static final Map<String, Text> NAMES = Map.of(MAX_FPS, Text.of("rigtune.profile.template.max_fps", "Max FPS"), QUALITY,
			Text.of("rigtune.profile.template.quality", "Quality"), BATTERY, Text.of("rigtune.profile.template.battery", "Battery"), "p-evening",
			Text.literal("Evening"));
	private static final Function<String, Text> NAME = NAMES::get;

	private static Entry entry(String key, String profile, ServerLimits.Kind kind, String lastSeen) {
		Instant at = lastSeen == null ? null : Instant.parse(lastSeen);
		return new Entry(key, profile, kind, at, at);
	}

	private static final List<Entry> ENTRIES = List.of(entry(KEY_A, MAX_FPS, ServerLimits.Kind.REMOTE, "2026-09-27T09:00:00Z"),
			entry(KEY_B, "p-evening", ServerLimits.Kind.LAN_GUEST, "2026-09-25T18:30:00Z"), entry(KEY_C, QUALITY, ServerLimits.Kind.REALM, null));

	private static ServerProfilesView server(String currentKey, String active, boolean onBattery) {
		return ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REMOTE, currentKey, ENTRIES, NAME, active, onBattery, true, UTC);
	}

	@Test
	void theThisServerLines() {
		assertEquals("Join a server to set a profile for it.",
				ServerProfilesView.of(State.NOT_CONNECTED, null, null, ENTRIES, NAME, QUALITY, false, true, UTC).here().english());
		assertEquals("Join a server to set a profile for it.", ServerProfilesView.EMPTY.here().english());
		assertEquals("Profiles are offered on servers, not in your own worlds.",
				ServerProfilesView.of(State.OWN_WORLD, ServerLimits.Kind.SINGLEPLAYER, null, ENTRIES, NAME, QUALITY, false, true, UTC).here().english());
		assertEquals("RigTune can't recognise this server, so it can't remember a profile for it.",
				ServerProfilesView.of(State.UNRECOGNISED, ServerLimits.Kind.REMOTE, null, ENTRIES, NAME, QUALITY, false, true, UTC).here().english());
		assertEquals("This server: no profile set.", server("d".repeat(64), QUALITY, false).here().english());
		assertEquals("This server: no profile set.", server(null, QUALITY, false).here().english(), "no salt yet");
		assertEquals("This server: RigTune offers Max FPS when you join.", server(KEY_A, QUALITY, false).here().english());
		assertEquals("This server: RigTune offers Max FPS when you join.", server(KEY_A, MAX_FPS, false).here().english(), "already active");
		assertEquals("This server: Max FPS, but not while you're on battery power with the Battery profile.",
				server(KEY_A, BATTERY, true).here().english());
	}

	@Test
	void aDeletedOrUnknownCurrentProfileIsNotOffered() {
		List<Entry> entries = List.of(entry(KEY_A, "p-gone", ServerLimits.Kind.REMOTE, null), entry(KEY_B, "template:future", ServerLimits.Kind.REMOTE, null));
		assertEquals("This server: set to a deleted profile, so RigTune offers nothing here.",
				ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REMOTE, KEY_A, entries, NAME, QUALITY, false, true, UTC).here().english());
		assertEquals("This server: set to a profile this version doesn't know, so RigTune offers nothing here.",
				ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REMOTE, KEY_B, entries, NAME, BATTERY, true, true, UTC).here().english());
	}

	@Test
	void rowsInTheStoresOrderWithKindProfileAndDay() {
		ServerProfilesView view = server(null, QUALITY, false);
		assertEquals(List.of(KEY_A, KEY_B, KEY_C), view.rows().stream().map(Row::key).toList());
		assertEquals(List.of("Server · Max FPS · last joined 2026-09-27", "LAN game · Evening · last joined 2026-09-25", "Realm · Quality"),
				view.rows().stream().map(row -> row.text().english()).toList());
		Row first = view.rows().getFirst();
		assertEquals(new Row(KEY_A, ServerLimits.Kind.REMOTE, MAX_FPS, NAMES.get(MAX_FPS), "2026-09-27", false), first);
	}

	@Test
	void theDayIsTheLocalOne() {
		List<Entry> late = List.of(entry(KEY_A, MAX_FPS, ServerLimits.Kind.REMOTE, "2026-09-27T23:30:00Z"));
		assertEquals("2026-09-28", ServerProfilesView.of(State.NOT_CONNECTED, null, null, late, NAME, null, false, true, ZoneId.of("Australia/Sydney"))
				.rows().getFirst().lastJoined());
		assertEquals("2026-09-27", ServerProfilesView.of(State.NOT_CONNECTED, null, null, late, NAME, null, false, true, ZoneId.of("America/New_York"))
				.rows().getFirst().lastJoined());
	}

	// review-11 SEC-2: a date no calendar day can hold (a hand-edited lastSeen at Instant's ends) shows no day, and the
	// screen still opens (it used to throw DateTimeException in Screen.init on every open).
	@Test
	void aDateNoDayCanHoldShowsNoDay() {
		List<Entry> entries = List.of(new Entry(KEY_A, MAX_FPS, ServerLimits.Kind.REMOTE, Instant.MIN, Instant.MAX),
				new Entry(KEY_B, QUALITY, ServerLimits.Kind.REMOTE, null, Instant.MIN));
		ServerProfilesView view = ServerProfilesView.of(State.NOT_CONNECTED, null, null, entries, NAME, null, false, true, UTC);
		assertEquals(List.of("Server · Max FPS", "Server · Quality"), view.rows().stream().map(row -> row.text().english()).toList());
		assertNull(view.rows().getFirst().lastJoined());
	}

	@Test
	void deletedAndUnknownProfilesReadAsSuch() {
		List<Entry> entries = List.of(entry(KEY_A, "p-gone", ServerLimits.Kind.REMOTE, "2026-09-20T10:00:00Z"),
				entry(KEY_B, "template:future_mode", ServerLimits.Kind.REALM, null));
		ServerProfilesView view = ServerProfilesView.of(State.NOT_CONNECTED, null, null, entries, NAME, null, false, true, UTC);
		assertEquals(List.of("Server · a deleted profile · last joined 2026-09-20", "Realm · a profile this version doesn't know"),
				view.rows().stream().map(row -> row.text().english()).toList());
		assertNull(view.rows().getFirst().profileName());
	}

	@Test
	void aSavedNameIsALiteral() {
		List<Entry> entries = List.of(entry(KEY_A, "p-odd", ServerLimits.Kind.REMOTE, null));
		ServerProfilesView view = ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REMOTE, KEY_A, entries, id -> Text.literal("50% %s"), null, false,
				true, UTC);
		assertEquals("Server · 50% %s", view.rows().getFirst().text().english());
		assertEquals("This server: RigTune offers 50% %s when you join.", view.here().english());
	}

	@Test
	void theCurrentServerIsMarkedOnlyWhileConnectedToIt() {
		ServerProfilesView view = server(KEY_B, QUALITY, false);
		assertEquals(List.of(false, true, false), view.rows().stream().map(Row::current).toList());
		assertEquals(KEY_B, view.currentKey());
		assertEquals("p-evening", view.currentProfile());
		assertEquals("Evening", view.currentProfileName().english());
		assertEquals(QUALITY, view.activeProfile());
		assertEquals("Quality", view.activeProfileName().english());
		ServerProfilesView away = ServerProfilesView.of(State.NOT_CONNECTED, null, KEY_B, ENTRIES, NAME, QUALITY, false, true, UTC);
		assertEquals(List.of(false, false, false), away.rows().stream().map(Row::current).toList());
		assertNull(away.currentProfile());
		ServerProfilesView notSet = server("d".repeat(64), null, false);
		assertTrue(notSet.rows().stream().noneMatch(Row::current));
		assertNull(notSet.currentProfile());
		assertNull(notSet.currentProfileName());
		assertNull(notSet.activeProfileName());
	}

	@Test
	void heldOnBatteryOnlyWithBatteryActive() {
		assertTrue(server(KEY_A, BATTERY, true).heldOnBattery());
		assertFalse(server(KEY_A, BATTERY, false).heldOnBattery(), "Battery active on AC");
		assertFalse(server(KEY_A, QUALITY, true).heldOnBattery(), "on battery, Battery not active: offered");
		assertFalse(server("d".repeat(64), BATTERY, true).heldOnBattery(), "nothing set here");
		List<Entry> battery = List.of(entry(KEY_A, BATTERY, ServerLimits.Kind.REMOTE, null));
		assertFalse(ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REMOTE, KEY_A, battery, NAME, BATTERY, true, true, UTC).heldOnBattery(),
				"Battery is the profile set here: already active");
	}

	@Test
	void theViewKeepsWritableAndKind() {
		ServerProfilesView view = ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REALM, null, List.of(), NAME, null, false, false, UTC);
		assertFalse(view.writable());
		assertEquals(ServerLimits.Kind.REALM, view.kind());
		assertEquals(List.of(), view.rows());
	}

	@Test
	void noTextHoldsAnAddress(@TempDir Path config) {
		ServerProfileStore store = ServerProfileStore.shared(config);
		Instant at = Instant.parse("2026-09-26T20:00:00Z");
		String local = ServerLimitsStore.address("localhost", 41234);
		assertEquals(Result.OK, store.remember(local, ServerLimits.Kind.REMOTE, MAX_FPS, at));
		assertEquals(Result.OK, store.remember(ServerLimitsStore.address("127.0.0.1", 25565), ServerLimits.Kind.REMOTE, "p-gone", at));
		assertEquals(Result.OK, store.remember(ServerLimitsStore.lan("192.168.1.20"), ServerLimits.Kind.LAN_GUEST, "p-evening", at));
		assertEquals(Result.OK, store.remember(ServerLimitsStore.realm("Secret Realm"), ServerLimits.Kind.REALM, QUALITY, at));
		ServerProfilesView view = ServerProfilesView.of(State.SERVER, ServerLimits.Kind.REMOTE, store.keyOf(local), store.entries(), NAME, QUALITY, false,
				store.writable(), UTC);
		List<String> texts = new ArrayList<>(view.rows().stream().map(row -> row.text().english()).toList());
		texts.add(view.here().english());
		assertEquals(4, view.rows().size());
		for (String text : texts) {
			for (String plain : List.of("localhost", "127.0.0.1", "41234", "25565", "192.168", "Secret", "lan:", "realm:")) {
				assertFalse(text.contains(plain), plain + " in " + text);
			}
		}
		assertTrue(view.rows().stream().anyMatch(Row::current));
	}

	@Test
	void theStatusLines() {
		Text maxFps = NAMES.get(MAX_FPS);
		assertEquals("RigTune will offer Max FPS when you join this server.", ServerProfilesView.remembered(Result.OK, maxFps).english());
		assertEquals("You've set profiles for 32 servers. Forget one first.", ServerProfilesView.remembered(Result.FULL, maxFps).english());
		String readOnly = "server-profiles.json was written by a newer RigTune, so this version doesn't change it.";
		String failed = "RigTune couldn't save that (the log says why).";
		assertEquals(readOnly, ServerProfilesView.remembered(Result.READ_ONLY, maxFps).english());
		assertEquals(failed, ServerProfilesView.remembered(Result.FAILED, maxFps).english());
		assertEquals("RigTune won't offer a profile on this server any more.", ServerProfilesView.forgot(Result.OK).english());
		assertEquals(readOnly, ServerProfilesView.forgot(Result.READ_ONLY).english());
		assertEquals(failed, ServerProfilesView.forgot(Result.FAILED).english());
		assertEquals("RigTune won't offer profiles on servers until you set one again.", ServerProfilesView.forgotAll(Result.OK).english());
		assertEquals(readOnly, ServerProfilesView.forgotAll(Result.READ_ONLY).english());
		assertEquals(failed, ServerProfilesView.forgotAll(Result.FAILED).english());
	}
}
