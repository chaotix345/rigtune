package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingMergeTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
	}

	private PendingActions plan(List<Op> ops) {
		return PendingActions.create(1, mods, config, ops);
	}

	private Path pendingJar(String name) {
		return mods.resolve(name + PendingActions.PENDING_SUFFIX);
	}

	private List<Op> update(String oldName, String newName, String modId) {
		return PendingActions.group(Op.disableFile(mods.resolve(oldName)),
				Op.enableFile(pendingJar(newName), mods.resolve(newName)).withModId(modId));
	}

	private static List<Op> ofType(List<Op> ops, Type type) {
		return ops.stream().filter(op -> op.type() == type).toList();
	}

	@Test
	void newerUpdateOfTheSameModReplacesTheStagedOne() {
		List<Op> first = update("sodium-0.7.0.jar", "sodium-0.7.1.jar", "sodium");
		List<Op> second = update("sodium-0.7.0.jar", "sodium-0.7.2.jar", "sodium");

		PendingActions.Merged merged = plan(first).merge(second);

		List<Op> ops = merged.plan().ops();
		assertEquals(2, ops.size(), ops.toString());
		assertEquals(List.of(mods.resolve("sodium-0.7.0.jar").toString()), ofType(ops, Type.DISABLE_FILE).stream().map(Op::path).toList());
		assertEquals(List.of(mods.resolve("sodium-0.7.2.jar").toString()), ofType(ops, Type.ENABLE_FILE).stream().map(Op::to).toList());
		assertTrue(ops.stream().allMatch(op -> second.getFirst().group().equals(op.group())), ops.toString());
		assertEquals(List.of(pendingJar("sodium-0.7.1.jar")), merged.superseded());
	}

	@Test
	void replacingAStagedDependencyKeepsItsDependentsInTheSameGroup() {
		List<Op> addA = PendingActions.group(Op.enableFile(pendingJar("a.jar"), mods.resolve("a.jar")).withModId("a"),
				Op.enableFile(pendingJar("lib-1.jar"), mods.resolve("lib-1.jar")).withModId("lib"));
		List<Op> addB = PendingActions.group(Op.enableFile(pendingJar("b.jar"), mods.resolve("b.jar")).withModId("b"),
				Op.enableFile(pendingJar("lib-2.jar"), mods.resolve("lib-2.jar")).withModId("lib"));

		PendingActions.Merged merged = plan(addA).merge(addB);

		List<Op> ops = merged.plan().ops();
		assertEquals(List.of("a", "b", "lib"), ops.stream().map(Op::modId).sorted().toList());
		assertEquals(1, ops.stream().map(Op::group).distinct().count(), ops.toString());
		assertEquals(List.of(pendingJar("lib-1.jar")), merged.superseded());
	}

	@Test
	void ungroupedEnableTakesOverTheReplacedGroup() {
		List<Op> first = update("m-1.jar", "m-2.jar", "m");
		Op loose = Op.enableFile(pendingJar("m-3.jar"), mods.resolve("m-3.jar")).withModId("m");

		List<Op> ops = plan(first).merge(List.of(loose)).plan().ops();

		assertEquals(2, ops.size());
		assertEquals(first.getFirst().group(), ops.get(1).group());
		assertEquals(ops.get(0).group(), ops.get(1).group());
	}

	@Test
	void restagingTheSameDownloadIsDedupedAndNotRetired() {
		List<Op> first = update("x-1.jar", "x-2.jar", "x");
		List<Op> again = update("x-1.jar", "x-2.jar", "x");

		PendingActions.Merged merged = plan(first).merge(again);

		assertEquals(2, merged.plan().ops().size());
		assertTrue(merged.superseded().isEmpty());
		assertEquals(first.stream().map(Op::id).toList(), merged.plan().ops().stream().map(Op::id).toList());
	}

	@Test
	void aRepeatedOpJoinsItsNewGroup() {
		Op standalone = Op.disableFile(mods.resolve("x.jar"));
		List<Op> update = update("x.jar", "x-2.jar", "x");

		List<Op> ops = plan(List.of(standalone)).merge(update).plan().ops();

		assertEquals(2, ops.size());
		assertEquals(standalone.id(), ops.getFirst().id());
		assertEquals(update.getFirst().group(), ops.get(0).group());
		assertEquals(update.getFirst().group(), ops.get(1).group());
	}

	@Test
	void enablesWithoutModIdsAreOnlyDedupedByValue() {
		Op a = Op.enableFile(pendingJar("a.jar"), mods.resolve("a.jar"));
		Op b = Op.enableFile(pendingJar("b.jar"), mods.resolve("b.jar"));
		Op aAgain = Op.enableFile(pendingJar("a.jar"), mods.resolve("a.jar"));

		PendingActions.Merged merged = plan(List.of(a)).merge(List.of(b, aAgain));

		assertEquals(List.of(a, b), merged.plan().ops());
		assertTrue(merged.superseded().isEmpty());
	}

	@Test
	void differentModsAreNotReplaced() {
		List<Op> first = update("a-1.jar", "a-2.jar", "a");
		List<Op> second = update("b-1.jar", "b-2.jar", "b");

		PendingActions.Merged merged = plan(first).merge(second);

		assertEquals(4, merged.plan().ops().size());
		assertTrue(merged.superseded().isEmpty());
	}

	@Test
	void retireRenamesAndNeverDeletes() throws IOException {
		Path first = Files.writeString(pendingJar("x.jar"), "one");

		assertEquals(mods.resolve("x.jar" + PendingActions.SUPERSEDED_SUFFIX), PendingActions.retire(first));
		Files.writeString(pendingJar("x.jar"), "two");
		assertEquals(mods.resolve("x.jar" + PendingActions.SUPERSEDED_SUFFIX + ".1"), PendingActions.retire(pendingJar("x.jar")));
		assertNull(PendingActions.retire(pendingJar("x.jar")));

		assertEquals("one", Files.readString(mods.resolve("x.jar.rigtune-superseded")));
		assertEquals("two", Files.readString(mods.resolve("x.jar.rigtune-superseded.1")));
	}

	@Test
	void mergedPlanLeavesExactlyOneCopyOfTheMod() throws IOException {
		Files.writeString(mods.resolve("sodium-0.7.0.jar"), "0.7.0");
		Files.writeString(pendingJar("sodium-0.7.1.jar"), "0.7.1");
		Files.writeString(pendingJar("sodium-0.7.2.jar"), "0.7.2");
		Path pending = PendingActions.defaultPath(config);
		PendingActions.Merged merged = plan(update("sodium-0.7.0.jar", "sodium-0.7.1.jar", "sodium"))
				.merge(update("sodium-0.7.0.jar", "sodium-0.7.2.jar", "sodium"));
		merged.plan().save(pending);
		for (Path old : merged.superseded()) {
			assertNotNull(PendingActions.retire(old));
		}

		ApplyResult result = new ApplyExecutor(2, 1).run(merged.plan(), pending);

		assertTrue(result.results().stream().allMatch(r -> r.status() == Status.OK), result.toString());
		try (Stream<Path> files = Files.list(mods)) {
			assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-superseded", "sodium-0.7.2.jar"),
					files.map(p -> p.getFileName().toString()).sorted().toList());
		}
	}

	// Review 2, N3: the RigTune screen's "Discard pending" button.
	@Test
	void discardRetiresTheDownloadsAndDeletesThePlanUnderTheLock() throws Exception {
		Path pending = PendingActions.defaultPath(config);
		Path a = Files.writeString(pendingJar("a.jar"), "a");
		Path b = Files.writeString(pendingJar("b.jar"), "b");
		Files.writeString(mods.resolve("c.jar"), "c");
		Path outside = Files.writeString(dir.resolve("d.jar" + PendingActions.PENDING_SUFFIX), "d");
		List<Op> ops = new ArrayList<>(List.of(Op.enableFile(a, mods.resolve("a.jar"))));
		ops.addAll(update("c.jar", "b.jar", "b"));
		ops.add(Op.enableFile(outside, mods.resolve("d.jar")));
		ops.add(Op.patchJson(config.resolve("sodium-options.json"), Map.of("a", "1")));
		plan(ops).save(pending);

		try (HeldLock helper = HeldLock.hold(ApplyLock.besidePlan(pending))) {
			assertEquals(-1, PendingActions.discard(pending, Duration.ofMillis(100)));
			assertTrue(Files.exists(pending));
			assertTrue(Files.exists(a));
		}

		assertEquals(5, PendingActions.discard(pending, Duration.ZERO));

		assertFalse(Files.exists(pending));
		try (Stream<Path> files = Files.list(mods)) {
			assertEquals(List.of("a.jar.rigtune-superseded", "b.jar.rigtune-superseded", "c.jar"),
					files.map(p -> p.getFileName().toString()).sorted().toList());
		}
		assertTrue(Files.exists(outside));
		assertEquals(0, PendingActions.discard(pending, Duration.ZERO));
	}
}
