package io.github.chaotix345.rigtune.client.profile;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// review-8 PR-2: the battery offer is handed from the "RigTune power" thread to the render thread; the render thread
// clears only the offer it read, so a fresh one stored in between is never dropped.
class ProfileServiceOfferTest {
	@Test
	void aFreshOfferStoredAfterTheReadSurvivesTheClear() {
		AtomicReference<String> slot = new AtomicReference<>("battery-offer:1");
		String seen = slot.get();
		slot.set("battery-back:2");
		assertFalse(ProfileService.retire(slot, seen), "the render thread's stale read clears nothing");
		assertEquals("battery-back:2", slot.get());
		assertTrue(ProfileService.retire(slot, slot.get()));
		assertNull(slot.get());
		assertFalse(ProfileService.retire(slot, null));
	}

	@Test
	void underContentionEveryOfferIsEitherShownOrStillPending() throws InterruptedException {
		for (int round = 0; round < 200; round++) {
			AtomicReference<String> slot = new AtomicReference<>("old");
			CountDownLatch go = new CountDownLatch(1);
			Thread power = new Thread(() -> {
				await(go);
				slot.set("fresh");
			});
			power.start();
			go.countDown();
			String seen = slot.get();
			ProfileService.retire(slot, seen);
			power.join();
			// Either the render thread read (and so handled) the fresh offer, or it is still there to show.
			assertTrue("fresh".equals(seen) || "fresh".equals(slot.get()), "round " + round + ": the fresh offer was lost");
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
