package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.github.chaotix345.rigtune.core.apply.TestJars.modJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Carried-over enables are checked against what is in mods/ by then (review 2, N1).
class ApplyDuplicatesTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path pending;
	final ApplyExecutor executor = new ApplyExecutor(2, 1);

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
	}

	private ApplyResult run(List<Op> ops) throws IOException {
		PendingActions plan = PendingActions.create(1, mods, config, ops);
		plan.save(pending);
		return executor.run(plan, pending);
	}

	private static List<Status> statuses(ApplyResult result) {
		return result.results().stream().map(OpResult::status).toList();
	}

	private List<String> modsListing() throws IOException {
		try (Stream<Path> files = Files.list(mods)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private Path pendingJar(String name, String modId) throws IOException {
		return modJar(mods.resolve(name + PendingActions.PENDING_SUFFIX), modId);
	}

	private Op enable(Path pendingJar, String modId) {
		String name = pendingJar.getFileName().toString();
		return Op.enableFile(pendingJar, mods.resolve(name.substring(0, name.length() - PendingActions.PENDING_SUFFIX.length()))).withModId(modId);
	}

	@Test
	void launcherUpdatedTheModMeanwhile() throws IOException {
		Path staged = pendingJar("sodium-0.7.1.jar", "sodium");
		List<Op> update = PendingActions.group(Op.disableFile(mods.resolve("sodium-0.7.0.jar")), enable(staged, "sodium"));
		// The game was killed, so the update stayed pending; then the launcher replaced 0.7.0 with 0.7.2.
		modJar(mods.resolve("sodium-0.7.2.jar"), "sodium");

		ApplyResult result = run(update);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(result));
		assertTrue(result.results().get(1).message().contains("already installed as sodium-0.7.2.jar"), result.toString());
		assertFalse(result.allSucceeded());
		assertEquals(List.of("sodium-0.7.1.jar.rigtune-superseded", "sodium-0.7.2.jar"), modsListing());
		assertFalse(Files.exists(pending));
		assertEquals(statuses(result), statuses(ApplyResult.load(ApplyResult.defaultPath(config))));
	}

	@Test
	void modInstalledByHandWhileItsAddWasPending() throws IOException {
		Path lithium = pendingJar("lithium-0.25.jar", "lithium");
		Path lib = pendingJar("lib-1.0.jar", "lib");
		Files.writeString(mods.resolve("unrelated.jar"), "not a zip");
		modJar(mods.resolve("Lithium (manual).jar"), "lithium");
		Path other = pendingJar("other-1.0.jar", "other");

		List<Op> ops = new ArrayList<>(PendingActions.group(enable(lithium, "lithium"), enable(lib, "lib")));
		ops.add(enable(other, "other"));
		ApplyResult result = run(ops);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED, Status.OK), statuses(result));
		assertEquals(List.of("Lithium (manual).jar", "lib-1.0.jar.rigtune-superseded", "lithium-0.25.jar.rigtune-superseded",
				"other-1.0.jar", "unrelated.jar"), modsListing());
		assertFalse(Files.exists(pending));
	}

	@Test
	void theJarAnUpdateReplacesDoesNotCount() throws IOException {
		modJar(mods.resolve("sodium-0.7.0.jar"), "sodium");
		Path sodium = pendingJar("sodium-0.7.1.jar", "sodium");
		modJar(mods.resolve("lithium.jar"), "lithium");
		Path lithium = pendingJar("lithium.jar", "lithium");

		List<Op> ops = new ArrayList<>(PendingActions.group(Op.disableFile(mods.resolve("sodium-0.7.0.jar")), enable(sodium, "sodium")));
		ops.addAll(PendingActions.group(Op.disableFile(mods.resolve("lithium.jar")), enable(lithium, "lithium")));
		ApplyResult result = run(ops);

		assertEquals(List.of(Status.OK, Status.OK, Status.OK, Status.OK), statuses(result));
		assertEquals(List.of("lithium.jar", "lithium.jar.disabled", "sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar"), modsListing());
	}

	@Test
	void aJarEnabledEarlierInTheSameRunCounts() throws IOException {
		Path first = pendingJar("sodium-0.7.1.jar", "sodium");
		Path second = pendingJar("sodium-0.7.2.jar", "sodium");

		ApplyResult result = run(List.of(enable(first, "sodium"), enable(second, "sodium")));

		assertEquals(List.of(Status.OK, Status.ABANDONED), statuses(result));
		assertEquals(List.of("sodium-0.7.1.jar", "sodium-0.7.2.jar.rigtune-superseded"), modsListing());
	}

	// Review 3, apply-safety-1: an enable staged without a mod id is checked with the id its jar declares.
	@Test
	void anEnableWithoutAModIdIsCheckedWithTheJarsOwnId() throws IOException {
		modJar(mods.resolve("sodium-0.7.2.jar"), "sodium");
		Path staged = pendingJar("sodium-0.7.1.jar", "sodium");

		ApplyResult result = run(List.of(Op.enableFile(staged, mods.resolve("sodium-0.7.1.jar"))));

		assertEquals(List.of(Status.ABANDONED), statuses(result));
		assertTrue(result.results().getFirst().message().contains("already installed as sodium-0.7.2.jar"), result.toString());
	}

	@Test
	void anEnableWithoutAModIdIsAppliedWhenItsModIsNotInstalled() throws IOException {
		Path staged = pendingJar("sodium-0.7.1.jar", "sodium");

		ApplyResult result = run(List.of(Op.enableFile(staged, mods.resolve("sodium-0.7.1.jar"))));

		assertEquals(List.of(Status.OK), statuses(result));
	}

	// Review 3, apply-safety-1: a jar whose fabric.mod.json id can't be read is never enabled, nor is the rest of its group.
	@Test
	void anEnableWhoseFileIsNotAJarFailsWithItsGroup() throws IOException {
		Path notAJar = mods.resolve("broken.jar" + PendingActions.PENDING_SUFFIX);
		Files.writeString(notAJar, "not a zip");
		Path lib = pendingJar("lib-1.0.jar", "lib");

		ApplyResult result = run(PendingActions.group(Op.enableFile(notAJar, mods.resolve("broken.jar")), enable(lib, "lib")));

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().getFirst().message().contains("not a Fabric mod jar"), result.toString());
		assertEquals(List.of("broken.jar.rigtune-pending", "lib-1.0.jar.rigtune-pending"), modsListing());
	}

	// Review 4, apply-safety-1: an enable staged with a mod id is checked against its jar too, so an update whose jar
	// isn't a Fabric mod fails with its group and the jar it would replace stays enabled.
	@Test
	void anUpdateWhoseJarIsNotAFabricModFailsWithItsGroup() throws IOException {
		modJar(mods.resolve("modx-1.0.jar"), "modx");
		Path broken = Files.writeString(mods.resolve("modx-2.0.jar" + PendingActions.PENDING_SUFFIX), "not a zip");

		ApplyResult result = run(PendingActions.group(Op.disableFile(mods.resolve("modx-1.0.jar")), enable(broken, "modx")));

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().get(1).message().contains("modx-2.0.jar.rigtune-pending is not a Fabric mod jar"), result.toString());
		assertEquals(List.of("modx-1.0.jar", "modx-2.0.jar.rigtune-pending"), modsListing());
	}

	@Test
	void anEnableWhoseJarDeclaresAnotherModIdFailsWithItsGroup() throws IOException {
		modJar(mods.resolve("sodium-0.7.0.jar"), "sodium");
		Path other = pendingJar("sodium-0.7.1.jar", "lithium");

		ApplyResult result = run(PendingActions.group(Op.disableFile(mods.resolve("sodium-0.7.0.jar")), enable(other, "sodium")));

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().get(1).message().contains("declares mod id lithium, not sodium"), result.toString());
		assertEquals(List.of("sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
	}

	// Review 4, security-1: an Error while reading a jar's id (a huge or deeply nested fabric.mod.json) only makes that
	// jar unreadable. An unrelated jar in mods/ doesn't stop an enable, and an enabled one fails as not a mod.
	@Test
	void anErrorReadingAJarsIdOnlyMakesThatJarUnreadable() throws IOException {
		modJar(mods.resolve("bomb.jar"), "bomb");
		Path lithium = pendingJar("lithium.jar", "lithium");
		Path bomb = pendingJar("bomb-2.jar", "bomb");
		ApplyExecutor.ModIdReader throwing = jar -> {
			if (jar.getFileName().toString().startsWith("bomb")) {
				throw new StackOverflowError("nested too deep");
			}
			return ModJars.readModId(jar);
		};
		PendingActions plan = PendingActions.create(1, mods, config, List.of(enable(lithium, "lithium"), enable(bomb, "bomb")));
		plan.save(pending);

		ApplyResult result = new ApplyExecutor(2, 1, (from, to) -> Files.move(from, to), millis -> true, throwing).run(plan, pending);

		assertEquals(List.of(Status.OK, Status.FAILED), statuses(result));
		assertTrue(result.results().get(1).message().contains("bomb-2.jar.rigtune-pending is not a Fabric mod jar"), result.toString());
		assertEquals(List.of("bomb-2.jar.rigtune-pending", "bomb.jar", "lithium.jar"), modsListing());
	}

	@Test
	void anEnableWhoseJarHasNoFabricModJsonFails() throws IOException {
		Path plain = TestJars.plainJar(mods.resolve("pack.jar" + PendingActions.PENDING_SUFFIX));

		ApplyResult result = run(List.of(Op.enableFile(plain, mods.resolve("pack.jar"))));

		assertEquals(List.of(Status.FAILED), statuses(result));
		assertTrue(result.results().getFirst().message().contains("not a Fabric mod jar"), result.toString());
		assertEquals(List.of("pack.jar.rigtune-pending"), modsListing());
	}
}
