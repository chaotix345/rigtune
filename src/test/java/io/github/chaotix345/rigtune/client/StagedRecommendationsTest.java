package io.github.chaotix345.rigtune.client;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Plan review A-M1: pending.json knows only op ids, so the client keeps each staged recommendation's op ids (as they are
// in pending.json after the merge) and, after a drop, an undo or a discard, keeps a recommendation staged only while one
// of them is still there.
class StagedRecommendationsTest {
	@TempDir
	Path game;
	Path mods;
	Path config;
	Path pending;
	Staging staging;
	final StagedRecommendations staged = new StagedRecommendations();
	int entries;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		config = Files.createDirectories(game.resolve("config"));
		pending = PendingActions.defaultPath(config);
		Journal journal = new Journal(config, "0.3.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		staging = new Staging(config, pending, List.of(), journal);
	}

	private Path pendingJar(String name, String modId) throws IOException {
		return TestJars.modJar(mods.resolve(name + PendingActions.PENDING_SUFFIX), modId);
	}

	private List<Op> update(String oldName, String newName, String modId) throws IOException {
		TestJars.modJar(mods.resolve(oldName), modId);
		return PendingActions.group(Op.disableFile(mods.resolve(oldName)),
				Op.enableFile(pendingJar(newName, modId), mods.resolve(newName)).withModId(modId));
	}

	private static List<String> ids(List<Op> ops) {
		return ops.stream().map(Op::id).toList();
	}

	private List<Op> pendingOps() throws IOException {
		return Files.exists(pending) ? PendingActions.load(pending).ops() : List.of();
	}

	private void stage(List<Op> ops, Map<String, List<String>> byRecommendation) {
		Staging.Merge merge = staging.stage(ops, "e" + ++entries);
		assertNotNull(merge);
		staged.add(ops, byRecommendation, merge.merged().survivingIds());
	}

	// AC3.1 (as amended by A-M1): after a drop, every staged id still has one of its recorded ops in pending.json, and no
	// recommendation of a dropped group is left. The addition of y (queued jar, but y isn't loaded) stays.
	@Test
	void afterADropEveryStagedIdStillHasAnOpInPendingJson() throws IOException {
		List<Op> x = update("x-1.jar", "x-2.jar", "x");
		List<Op> y = PendingActions.group(Op.enableFile(pendingJar("y-1.jar", "y"), mods.resolve("y-1.jar")).withModId("y"));
		List<Op> a = update("a-1.jar", "a-2.jar", "a");
		Op b = Op.enableFile(pendingJar("b-1.jar", "b"), mods.resolve("b-1.jar")).withModId("b").inGroup(a.getFirst().group());
		List<Op> ops = new ArrayList<>(x);
		ops.addAll(y);
		ops.addAll(a);
		ops.add(b);
		stage(ops, Map.of("update:x", ids(x), "add:y", ids(y), "update:a", ids(a), "add:b", List.of(b.id())));
		assertEquals(Set.of("update:x", "add:y", "update:a", "add:b"), staged.ids());

		List<Op> dropped = staging.dropQueuedUpdates(Set.of("x", "a", "y"), Set.of("x", "a"));
		Set<String> gone = staged.retainPending(pendingOps());

		assertEquals(5, dropped.size());
		assertEquals(Set.of("update:x", "update:a", "add:b"), gone);
		assertEquals(Set.of("add:y"), staged.ids());
		Set<String> inPending = pendingOps().stream().map(Op::id).collect(Collectors.toSet());
		for (String id : staged.ids()) {
			assertTrue(staged.opIdsOf(id).stream().anyMatch(inPending::contains), id);
		}
		assertEquals(0, staged.unowned(pendingOps()));
	}

	@Test
	void aRepeatedChangeKeepsTheIdItHasInPendingJson() throws IOException {
		List<Op> first = update("x-1.jar", "x-2.jar", "x");
		stage(first, Map.of("update:x", ids(first)));
		List<Op> again = PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x"));

		stage(again, Map.of("update:x-again", ids(again)));

		assertEquals(Set.copyOf(ids(first)), staged.opIdsOf("update:x-again"));
		assertEquals(Set.of(), staged.retainPending(pendingOps()));
	}

	// An undo's re-enable of the user's disabled jar replaces RigTune's staged enable of the same mod id.
	@Test
	void anEnableReplacedByAnUndoIsNoLongerStaged() throws IOException {
		List<Op> add = PendingActions.group(Op.enableFile(pendingJar("x-1.jar", "x"), mods.resolve("x-1.jar")).withModId("x"));
		stage(add, Map.of("add:x", ids(add)));
		Path disabled = TestJars.modJar(mods.resolve("x-0.jar.disabled"), "x");
		try (ApplyLock lock = staging.lock()) {
			assertNotNull(lock);
			staging.mergeLocked(List.of(Op.enableFile(disabled, mods.resolve("x-0.jar")).withModId("x")));
		}

		assertEquals(Set.of("add:x"), staged.retainPending(pendingOps()));
		assertEquals(Set.of(), staged.ids());
		assertEquals(1, staged.unowned(pendingOps()));
	}

	// Review of WS-A, finding 6: an undo's re-enable replaces the update's enable and takes its disable into the undo's
	// group; the update is no longer staged even though one of its ops is still there.
	@Test
	void anUpdateWhoseEnableAnUndoReplacedIsNoLongerStaged() throws IOException {
		List<Op> update = update("x-1.jar", "x-2.jar", "x");
		stage(update, Map.of("update:x", ids(update)));
		Path disabled = TestJars.modJar(mods.resolve("x-0.jar.disabled"), "x");
		try (ApplyLock lock = staging.lock()) {
			assertNotNull(lock);
			staging.mergeLocked(List.of(Op.enableFile(disabled, mods.resolve("x-0.jar")).withModId("x")));
		}
		assertTrue(pendingOps().stream().anyMatch(op -> op.id().equals(update.getFirst().id())), "the disable is still staged");

		assertEquals(Set.of("update:x"), staged.retainPending(pendingOps()));
	}

	// Review of WS-A, finding 5: an unreadable pending.json changes nothing (a missing one means nothing is staged).
	@Test
	void recountChangesNothingWhenPendingJsonCantBeRead() throws IOException {
		List<Op> x = update("x-1.jar", "x-2.jar", "x");
		stage(x, Map.of("update:x", ids(x)));
		assertEquals(0, staged.recount(pending));

		Files.writeString(pending, "{ not json");
		assertEquals(-1, staged.recount(pending));
		assertEquals(Set.of("update:x"), staged.ids());

		Files.delete(pending);
		assertEquals(0, staged.recount(pending));
		assertEquals(Set.of(), staged.ids());
	}

	@Test
	void carriedOverOpsAreTheOnesNoStagedRecommendationOwns() throws IOException {
		PendingActions.create(1, mods, config, update("y-1.jar", "y-2.jar", "y")).save(pending);
		assertEquals(2, staged.unowned(pendingOps()));

		List<Op> x = update("x-1.jar", "x-2.jar", "x");
		stage(x, Map.of("update:x", ids(x)));
		assertEquals(Set.of(), staged.retainPending(pendingOps()));
		assertEquals(2, staged.unowned(pendingOps()));

		// A 0.1.0 op has no id: it is carried over too.
		List<Op> withLegacy = new ArrayList<>(pendingOps());
		withLegacy.add(new Op(PendingActions.Type.DISABLE_FILE, null, null, mods.resolve("z.jar").toString(), null));
		PendingActions.load(pending).withOps(withLegacy).save(pending);
		assertEquals(3, staged.unowned(pendingOps()));
	}

	// The notice names the loaded mods whose own update is waiting (not an addition that went with one), and says
	// "change": an undo's re-enable can be dropped too (plan review A-L1).
	@Test
	void theNoticeNamesTheLoadedQueuedModsAndSaysChange() throws IOException {
		List<Op> dropped = List.of(Op.disableFile(mods.resolve("a-1.jar")),
				Op.enableFile(mods.resolve("a-2.jar.rigtune-pending"), mods.resolve("a-2.jar")).withModId("a"),
				Op.enableFile(mods.resolve("b-1.jar.rigtune-pending"), mods.resolve("b-1.jar")).withModId("b"));
		List<InstalledMod> scanned = List.of(new InstalledMod("a", "A Mod", "1", mods.resolve("a-1.jar"), "aaa"),
				new InstalledMod("c", "C Mod", "1", mods.resolve("c.jar"), "ccc"));

		assertEquals(List.of("A Mod"), StagedRecommendations.droppedModNames(dropped, Set.of("a", "b"), Set.of("a"), scanned));
		// Review of WS-A, finding 7: every loaded queued mod is named (its id when the scan lacks it), never a mod that
		// isn't loaded.
		List<Op> three = new ArrayList<>(dropped);
		three.add(Op.enableFile(mods.resolve("d-2.jar.rigtune-pending"), mods.resolve("d-2.jar")).withModId("d"));
		assertEquals(List.of("A Mod", "d"), StagedRecommendations.droppedModNames(three, Set.of("a", "b", "d"), Set.of("a", "d"), scanned));
		assertEquals(List.of("a"), StagedRecommendations.droppedModNames(dropped, Set.of("a", "b"), Set.of("a"), List.of()));

		String notice;
		try (InputStream in = StagedRecommendationsTest.class.getResourceAsStream("/assets/rigtune/lang/en_us.json")) {
			assertNotNull(in);
			notice = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
					.get("rigtune.status.queued_update_dropped").getAsString();
		}
		assertTrue(notice.contains("change"), notice);
		assertFalse(notice.contains("update of %s"), notice);
	}
}
