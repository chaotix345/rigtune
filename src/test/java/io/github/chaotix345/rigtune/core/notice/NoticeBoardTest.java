package io.github.chaotix345.rigtune.core.notice;

import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// docs/v0.4/SPEC.md C3: one notice slot; the highest priority shows, the rest are counted ("+N more").
class NoticeBoardTest {
	private static Notice notice(String key, NoticePriority priority, boolean dismissible) {
		return new Notice(key, priority, Text.literal(key), null, List.of(), dismissible);
	}

	private static List<String> keys(NoticeBoard.Selection selection) {
		return selection.visible().stream().map(Notice::key).toList();
	}

	@Test
	void declarationOrderIsTheSpecsPriorityOrder() {
		assertEquals(List.of(NoticePriority.BATTERY_OFFER, NoticePriority.SERVER_LIMIT, NoticePriority.BENCHMARK_REGRESSION,
				NoticePriority.HARDWARE_CHANGED, NoticePriority.WHATS_NEW, NoticePriority.BENCHMARK_STALE), Arrays.asList(NoticePriority.values()));
	}

	@Test
	void nothingToShow() {
		NoticeBoard.Selection none = NoticeBoard.select(List.of(), Set.of());
		assertNull(none.top());
		assertEquals(0, none.others());
		assertNull(none.at(3));
		List<Notice> withNull = new ArrayList<>();
		withNull.add(null);
		assertNull(NoticeBoard.select(withNull, Set.of()).top());
	}

	@Test
	void theHighestPriorityWinsAndTheRestAreCounted() {
		List<Notice> all = new ArrayList<>();
		for (NoticePriority p : NoticePriority.values()) {
			all.add(notice(p.name(), p, true));
		}
		for (int shift = 0; shift < all.size(); shift++) {
			List<Notice> rotated = new ArrayList<>(all.subList(shift, all.size()));
			rotated.addAll(all.subList(0, shift));
			NoticeBoard.Selection selection = NoticeBoard.select(rotated, Set.of());
			assertEquals("BATTERY_OFFER", selection.top().key());
			assertEquals(5, selection.others());
			assertEquals(Arrays.stream(NoticePriority.values()).map(Enum::name).toList(), keys(selection));
		}
	}

	@Test
	void samePriorityKeepsTheSourcesOrder() {
		NoticeBoard.Selection selection = NoticeBoard.select(List.of(notice("b", NoticePriority.WHATS_NEW, true),
				notice("a", NoticePriority.WHATS_NEW, true), notice("s", NoticePriority.SERVER_LIMIT, false)), Set.of());
		assertEquals(List.of("s", "b", "a"), keys(selection));
	}

	@Test
	void dismissedNoticesAreSkippedUnlessTheyCantBeDismissed() {
		NoticeBoard.Selection selection = NoticeBoard.select(List.of(notice("battery", NoticePriority.BATTERY_OFFER, true),
				notice("server", NoticePriority.SERVER_LIMIT, false), notice("new", NoticePriority.WHATS_NEW, true)), Set.of("battery", "server"));
		assertEquals(List.of("server", "new"), keys(selection));
		assertEquals(1, selection.others());
	}

	@Test
	void aRepeatedKeyShowsOnce() {
		NoticeBoard.Selection selection = NoticeBoard.select(List.of(notice("x", NoticePriority.WHATS_NEW, true),
				notice("x", NoticePriority.BATTERY_OFFER, true)), Set.of());
		assertEquals(List.of("x"), keys(selection));
		assertEquals(NoticePriority.WHATS_NEW, selection.top().priority(), "the first one given");
	}

	@Test
	void cyclingWrapsAround() {
		NoticeBoard.Selection selection = NoticeBoard.select(List.of(notice("a", NoticePriority.BATTERY_OFFER, true),
				notice("b", NoticePriority.SERVER_LIMIT, true), notice("c", NoticePriority.WHATS_NEW, true)), Set.of());
		assertEquals("a", selection.at(0).key());
		assertEquals("b", selection.at(1).key());
		assertEquals("c", selection.at(2).key());
		assertEquals("a", selection.at(3).key());
		assertEquals("c", selection.at(-1).key());
	}

	@Test
	void aNoticeNeedsAKeyPriorityAndMessage() {
		assertThrows(NullPointerException.class, () -> new Notice(null, NoticePriority.WHATS_NEW, Text.literal("m"), null, List.of(), true));
		assertThrows(NullPointerException.class, () -> new Notice("k", null, Text.literal("m"), null, List.of(), true));
		assertThrows(NullPointerException.class, () -> new Notice("k", NoticePriority.WHATS_NEW, null, null, List.of(), true));
		Notice noActions = new Notice("k", NoticePriority.WHATS_NEW, Text.literal("m"), null, null, true);
		assertEquals(List.of(), noActions.actions());
		NoticeAction rescan = new NoticeAction("rescan", Text.literal("Re-scan"));
		assertEquals(List.of(rescan), new Notice("k", NoticePriority.HARDWARE_CHANGED, Text.literal("m"), null, List.of(rescan), true).actions());
	}
}
