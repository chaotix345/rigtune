package io.github.chaotix345.rigtune.core.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/plan-review.md X-M1: awareness.json and profiles.json have several writers; no update may be lost.
class StateStoreTest {
	private static final int UPDATES = 1000;

	@TempDir
	Path dir;

	private static UnaryOperator<JsonObject> increment(String field) {
		return root -> {
			root.addProperty(field, (root.has(field) ? root.get(field).getAsInt() : 0) + 1);
			return root;
		};
	}

	private static void race(Runnable a, Runnable b) throws InterruptedException {
		CountDownLatch start = new CountDownLatch(1);
		Throwable[] failure = new Throwable[1];
		List<Thread> threads = List.of(new Thread(() -> run(start, a, failure)), new Thread(() -> run(start, b, failure)));
		threads.forEach(Thread::start);
		start.countDown();
		for (Thread thread : threads) {
			thread.join();
		}
		if (failure[0] != null) {
			throw new AssertionError(failure[0]);
		}
	}

	private static void run(CountDownLatch start, Runnable body, Throwable[] failure) {
		try {
			start.await();
			body.run();
		} catch (Throwable t) {
			failure[0] = t;
		}
	}

	@Test
	void twoAwarenessWritersNeverLoseAnUpdate() throws Exception {
		AwarenessStore store = AwarenessStore.shared(dir);
		assertSame(store, AwarenessStore.shared(dir), "one instance per file");
		race(() -> {
			for (int i = 0; i < UPDATES; i++) {
				assertTrue(AwarenessStore.shared(dir).update(increment("testA")));
			}
		}, () -> {
			for (int i = 0; i < UPDATES; i++) {
				assertTrue(AwarenessStore.shared(dir).update(increment(AwarenessStore.LAST_SEEN_RULES_REVISION)));
			}
		});
		JsonObject root = JsonParser.parseString(Files.readString(AwarenessStore.file(dir))).getAsJsonObject();
		assertEquals(UPDATES, root.get("testA").getAsInt());
		assertEquals(UPDATES, root.get(AwarenessStore.LAST_SEEN_RULES_REVISION).getAsInt());
		assertEquals(1, root.get("formatVersion").getAsInt());
	}

	@Test
	void twoProfileWritersNeverLoseAnUpdate() throws Exception {
		ProfileStore store = ProfileStore.shared(dir);
		race(() -> {
			for (int i = 0; i < UPDATES; i++) {
				assertTrue(store.update(increment("testA")));
			}
		}, () -> {
			for (int i = 0; i < UPDATES; i++) {
				assertTrue(store.update(root -> {
					JsonObject battery = root.getAsJsonObject(ProfileStore.BATTERY);
					battery.addProperty(ProfileStore.BATTERY_LAST_PROMPT_AT,
							(battery.has(ProfileStore.BATTERY_LAST_PROMPT_AT) ? battery.get(ProfileStore.BATTERY_LAST_PROMPT_AT).getAsInt() : 0) + 1);
					return root;
				}));
			}
		});
		JsonObject root = store.read();
		assertEquals(UPDATES, root.get("testA").getAsInt());
		assertEquals(UPDATES, root.getAsJsonObject(ProfileStore.BATTERY).get(ProfileStore.BATTERY_LAST_PROMPT_AT).getAsInt());
	}

	@Test
	void theDefaultShapesAreAlwaysThere() {
		JsonObject awareness = AwarenessStore.shared(dir).read();
		assertTrue(awareness.getAsJsonArray(AwarenessStore.DISMISSED).isEmpty());
		assertTrue(awareness.getAsJsonArray(AwarenessStore.ACKNOWLEDGED_REGRESSIONS).isEmpty());
		assertFalse(awareness.has(AwarenessStore.FINGERPRINT), "absent means first run");
		assertFalse(awareness.has(AwarenessStore.LAST_SEEN_RECOMMENDATION_IDS), "absent means no baseline");
		assertFalse(Files.exists(AwarenessStore.file(dir)), "reading writes nothing");

		JsonObject profiles = ProfileStore.shared(dir).read();
		assertTrue(profiles.getAsJsonArray(ProfileStore.PROFILES).isEmpty());
		assertTrue(profiles.getAsJsonArray(ProfileStore.SWITCHES).isEmpty());
		assertTrue(profiles.getAsJsonObject(ProfileStore.BATTERY).get(ProfileStore.BATTERY_PROMPT).getAsBoolean());
		assertFalse(profiles.getAsJsonObject(ProfileStore.BATTERY).get(ProfileStore.BATTERY_SNOOZED).getAsBoolean());
	}

	@Test
	void unknownFieldsSurviveAndANewerFileIsNeverWritten() throws Exception {
		Path file = ProfileStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\": 1, \"future\": {\"x\": 1}, \"profiles\": [{\"id\": \"p-1\", \"futureField\": true}]}");
		ProfileStore store = ProfileStore.shared(dir);
		assertTrue(store.update(root -> {
			root.addProperty(ProfileStore.ACTIVE, "p-1");
			return root;
		}));
		JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals(1, root.getAsJsonObject("future").get("x").getAsInt());
		assertTrue(root.getAsJsonArray(ProfileStore.PROFILES).get(0).getAsJsonObject().get("futureField").getAsBoolean());
		assertEquals("p-1", root.get(ProfileStore.ACTIVE).getAsString());

		Files.writeString(file, "{\"formatVersion\": 2, \"profiles\": []}");
		byte[] newer = Files.readAllBytes(file);
		assertFalse(store.update(increment("x")));
		assertFalse(store.writable());
		assertArrayEquals(newer, Files.readAllBytes(file));
	}

	@Test
	void dismissalsPersistAndStayBounded() {
		AwarenessStore store = AwarenessStore.shared(dir);
		assertTrue(store.dismiss("a"));
		assertTrue(store.dismiss("b"));
		assertTrue(store.dismiss("a"));
		assertEquals(List.of("b", "a"), List.copyOf(store.dismissed()));
		for (int i = 0; i < AwarenessStore.MAX_DISMISSED + 10; i++) {
			store.dismiss("k" + i);
		}
		Set<String> kept = store.dismissed();
		assertEquals(AwarenessStore.MAX_DISMISSED, kept.size());
		assertTrue(kept.contains("k" + (AwarenessStore.MAX_DISMISSED + 9)));
		assertFalse(kept.contains("a"));
	}
}
