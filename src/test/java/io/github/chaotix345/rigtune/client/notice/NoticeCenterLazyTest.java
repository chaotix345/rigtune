package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md X4 and C3 (PLAN contracts item 4): the notice list comes from one supplier, resolved on the first
// notices() or act() call and never at construction, once; the order within a priority is the list's.
class NoticeCenterLazyTest {
	private static final class Fixed implements NoticeSource {
		private final @Nullable Notice notice;
		final List<String> actions = new ArrayList<>();

		Fixed(@Nullable Notice notice) {
			this.notice = notice;
		}

		@Override
		public @Nullable Notice current() {
			return notice;
		}

		@Override
		public void act(String actionId) {
			actions.add(actionId);
		}
	}

	private static Notice notice(String key, NoticePriority priority) {
		return new Notice(key, priority, Text.literal(key), null, List.of(), true);
	}

	@Test
	void theSupplierIsResolvedOnFirstUseOnly() {
		AtomicInteger resolved = new AtomicInteger();
		Fixed battery = new Fixed(notice("battery", NoticePriority.BATTERY_OFFER));
		Fixed server = new Fixed(notice("server-profile:1", NoticePriority.SERVER_PROFILE));
		NoticeCenter center = new NoticeCenter(() -> {
			resolved.incrementAndGet();
			return List.of(battery, server, new Fixed(null), new Fixed(notice("whats-new:1", NoticePriority.WHATS_NEW)));
		}, NoticeCenter.inMemory());
		assertEquals(0, resolved.get(), "nothing is made at construction");
		assertEquals(List.of("battery", "server-profile:1", "whats-new:1"), center.notices().stream().map(Notice::key).toList());
		center.notices();
		center.act("server-profile:1", "switch");
		assertEquals(List.of("switch"), server.actions);
		assertEquals(1, resolved.get(), "resolved once");
	}

	@Test
	void actAloneResolvesItToo() {
		AtomicInteger resolved = new AtomicInteger();
		Fixed battery = new Fixed(notice("battery", NoticePriority.BATTERY_OFFER));
		NoticeCenter center = new NoticeCenter(() -> {
			resolved.incrementAndGet();
			return List.of(battery);
		}, NoticeCenter.inMemory());
		center.act("battery", "snooze");
		assertEquals(List.of("snooze"), battery.actions);
		assertEquals(1, resolved.get());
	}
}
