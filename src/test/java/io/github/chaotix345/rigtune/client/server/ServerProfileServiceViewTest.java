package io.github.chaotix345.rigtune.client.server;

import io.github.chaotix345.rigtune.client.profile.ProfileService;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers;
import io.github.chaotix345.rigtune.core.profile.ServerProfileOffers.Connection;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView.State;
import io.github.chaotix345.rigtune.core.server.ServerLimitsStore;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (C16, AC7.11), review M1: ServerProfilesScreen's model is built from one read of server-profiles.json
// (ServerProfileStore.snapshot) and one of profiles.json (ProfileService.names): with both files gone after those reads,
// the view is the same.
class ServerProfileServiceViewTest {
	private static final String PLAY = ServerLimitsStore.address("play.example.com", 25565);

	@TempDir
	Path config;

	@Test
	void theScreensModelIsBuiltFromOneReadOfEachFile() throws IOException {
		ServerProfileStore store = ServerProfileStore.shared(config);
		assertEquals(ServerProfileStore.Result.OK, store.remember(PLAY, ServerLimits.Kind.REMOTE, "template:max_fps", Instant.parse("2026-09-20T10:00:00Z")));
		ServerProfileOffers offers = new ServerProfileOffers();
		Connection connection = offers.joined(ServerLimits.Kind.REMOTE, PLAY, 1000);
		ServerProfileStore.Snapshot servers = store.snapshot();
		ProfileService.Names names = new ProfileService(null, config).names();
		Files.delete(ServerProfileStore.file(config));
		Files.deleteIfExists(config.resolve("rigtune").resolve("profiles.json"));
		ServerProfilesView view = ServerProfileService.view(connection, servers, names, false, ZoneOffset.UTC);
		assertEquals(State.SERVER, view.state());
		assertEquals("template:max_fps", view.currentProfile());
		assertEquals("This server: RigTune offers Max FPS when you join.", view.here().english());
		assertEquals("Server · Max FPS · last joined 2026-09-20", view.rows().getFirst().text().english());
		assertTrue(view.rows().getFirst().current() && view.writable());
		assertEquals(State.NOT_CONNECTED, ServerProfileService.view(null, servers, names, false, ZoneOffset.UTC).state());
		assertEquals(State.OWN_WORLD, ServerProfileService.view(new ServerProfileOffers().joined(ServerLimits.Kind.SINGLEPLAYER, null, 1),
				servers, names, false, ZoneOffset.UTC).state());
		assertEquals(State.UNRECOGNISED, ServerProfileService.view(new ServerProfileOffers().joined(ServerLimits.Kind.REMOTE, null, 1),
				servers, names, false, ZoneOffset.UTC).state());
	}
}
