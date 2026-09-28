package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.client.awareness.AwarenessService;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticeBoard;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (AC7.5, AC7.6 and AC7.10's unit parts), sp §2.5: the SERVER_PROFILE notice ("You set %s for this
// server. Switch to it?", Switch / Don't offer here, dismissible, a per-join key that is only ever hidden for the session)
// and the join toast's wording.
class ServerProfileNoticeTest {
	private static final long JOINED = 1727400000000L;
	private static final Text MAX_FPS = Text.of("rigtune.profile.template.max_fps", "Max FPS");

	@TempDir
	Path dir;

	@Test
	void theNoticeKeyPriorityWordingActionsAndDismissal() {
		Notice notice = ServerProfilePrompt.notice(JOINED, MAX_FPS);
		assertEquals("server-profile:1727400000000", notice.key());
		assertEquals(ServerProfilePrompt.KEY_PREFIX + JOINED, notice.key());
		assertEquals(NoticePriority.SERVER_PROFILE, notice.priority());
		assertEquals("You set Max FPS for this server. Switch to it?", notice.message().english());
		assertEquals("You asked RigTune to offer it here (Profiles, Servers…). It never switches by itself, and History can undo the switch.",
				notice.detail().english());
		assertEquals(List.of("switch", "forget"), notice.actions().stream().map(NoticeAction::id).toList());
		assertEquals(List.of(ServerProfilePrompt.ACTION_SWITCH, ServerProfilePrompt.ACTION_FORGET), notice.actions().stream().map(NoticeAction::id).toList());
		assertEquals(List.of("Switch", "Don't offer here"), notice.actions().stream().map(a -> a.label().english()).toList());
		assertTrue(notice.dismissible());
	}

	@Test
	void theNameIsALiteralNeverAFormat() {
		Notice notice = ServerProfilePrompt.notice(JOINED, Text.literal("Evening %s %1$s 100%"));
		assertEquals("You set Evening %s %1$s 100% for this server. Switch to it?", notice.message().english());
	}

	@Test
	void theKeyIsHiddenForTheSessionOnly() {
		assertTrue(AwarenessService.SESSION_ONLY_PREFIXES.contains(ServerProfilePrompt.KEY_PREFIX));
		AwarenessService awareness = new AwarenessService(null, dir);
		String key = ServerProfilePrompt.notice(JOINED, MAX_FPS).key();
		awareness.dismiss(key);
		assertTrue(awareness.dismissed().contains(key));
		assertFalse(AwarenessStore.shared(dir).dismissed().contains(key), "nothing added to awareness.json");
		assertTrue(NoticeBoard.select(List.of(ServerProfilePrompt.notice(JOINED, MAX_FPS)), awareness.dismissed()).visible().isEmpty());
		assertEquals(1, NoticeBoard.select(List.of(ServerProfilePrompt.notice(JOINED + 1, MAX_FPS)), awareness.dismissed()).visible().size(),
				"the next join's offer is a new key");
	}

	@Test
	void itSortsAfterTheBatteryOfferAndBeforeTheServerLimit() {
		Notice limit = new Notice("server-limit:10", NoticePriority.SERVER_LIMIT, Text.literal("limit"), null, List.of(), true);
		Notice battery = new Notice("battery-offer:1", NoticePriority.BATTERY_OFFER, Text.literal("battery"), null, List.of(), true);
		Notice offer = ServerProfilePrompt.notice(JOINED, MAX_FPS);
		NoticeBoard.Selection alone = NoticeBoard.select(List.of(limit, offer), Set.of());
		assertEquals(offer, alone.top(), "the offer shows first on a server");
		assertEquals(1, alone.others(), "+1 more");
		assertEquals(List.of(battery, offer, limit), NoticeBoard.select(List.of(limit, offer, battery), Set.of()).visible());
	}

	@Test
	void theToastWording() {
		assertEquals("Profile for this server", ServerProfilePrompt.toastTitle().english());
		assertEquals("Max FPS is set for this server. Press F8 to switch.", ServerProfilePrompt.toastBody(MAX_FPS, Text.literal("F8")).english());
		assertEquals("50% is set for this server. Press %s to switch.", ServerProfilePrompt.toastBody(Text.literal("50%"), Text.literal("%s")).english());
		assertEquals("Max FPS is set for this server. Open RigTune to switch.", ServerProfilePrompt.toastBody(MAX_FPS, null).english(),
				"no key bound to RigTune");
	}
}
