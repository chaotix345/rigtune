package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 8 (the notice) and W-H1 (it explains what the player sees): "The server limits view distance to N
// chunks" (+ "you set M" when M > N), the simulation distance, "changed since last time (was N)", the DH "may" note.
class ServerLimitNoticeSourceTest {
	private static ServerLimits remote(int view, int simulation) {
		return new ServerLimits(view, simulation, ServerLimits.Kind.REMOTE, 0);
	}

	@Test
	void theLimitAndWhatThePlayerSees() {
		Notice notice = ServerLimitNoticeSource.notice(remote(10, 8), null, 16, false);
		assertEquals(NoticePriority.SERVER_LIMIT, notice.priority());
		assertEquals("The server limits view distance to 10 chunks (you set 16)", notice.message().english());
		assertEquals("You set 16; the server sends 10, so 10 is what you see. Simulation distance on this server: 8 chunks (the server decides it).",
				notice.detail().english());
		assertTrue(notice.dismissible());
		assertTrue(notice.actions().isEmpty());
		assertEquals("server-limit:10:above", notice.key());
	}

	@Test
	void atOrBelowTheLimit() {
		Notice notice = ServerLimitNoticeSource.notice(remote(6, 5), null, 5, false);
		assertEquals("The server limits view distance to 6 chunks", notice.message().english());
		assertEquals("Simulation distance on this server: 5 chunks (the server decides it).", notice.detail().english());
		assertEquals("server-limit:6", notice.key());
		assertEquals("The server limits view distance to 6 chunks", ServerLimitNoticeSource.notice(remote(6, 5), null, 6, false).message().english());
	}

	@Test
	void changedSinceLastTimeAndDistantHorizons() {
		Notice notice = ServerLimitNoticeSource.notice(remote(8, 0), 12, 8, true);
		String detail = notice.detail().english();
		assertTrue(detail.startsWith("This server's limit changed since last time (was 12)."), detail);
		assertTrue(detail.endsWith("Distant Horizons may still show terrain you've already explored beyond it; generating new distant terrain "
				+ "may need Distant Horizons on the server."), detail);
		assertFalse(detail.contains("Simulation"), "no simulation distance sent");
		assertFalse(ServerLimitNoticeSource.notice(remote(8, 6), 8, 8, false).detail().english().contains("changed"));
	}

	@Test
	void nothingForTheOwnWorldOrNoLimit() {
		assertNull(ServerLimitNoticeSource.notice(new ServerLimits(4, 4, ServerLimits.Kind.SINGLEPLAYER, 0), null, 12, true));
		assertNull(ServerLimitNoticeSource.notice(remote(0, 5), null, 12, false));
		assertEquals("The server limits view distance to 10 chunks",
				ServerLimitNoticeSource.notice(new ServerLimits(10, 10, ServerLimits.Kind.LAN_GUEST, 0), null, 8, false).message().english());
		assertEquals("The server limits view distance to 10 chunks",
				ServerLimitNoticeSource.notice(new ServerLimits(10, 10, ServerLimits.Kind.REALM, 0), null, 8, false).message().english());
	}
}
