package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestExecutors;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.V010Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// docs/v0.5/SPEC.md 2H RW-3 (AC2H.5, preLaunch's half): at the first 0.4.0 start the player got "2 staged RigTune
// change(s) were not applied; they will be retried at the next exit", two WARNs replaying a three-day-old lock failure and
// the leftover toast, for 0.1.0's DH group that could never run (real-world-2026-09-27.md §3-4). preLaunch now counts only
// runnable ops, from Files.exists and the loaded mods' origins, and doesn't replay the stale ops' failures.
class PreLaunchStaleOpsTest {
	private static final String DH = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar";

	@TempDir
	Path game;

	private Path mods() {
		return game.resolve("mods");
	}

	private Path config() {
		return game.resolve("config");
	}

	@AfterEach
	void clear() {
		RigTunePreLaunch.takeLeftoverOps();
		RigTunePreLaunch.takeUnseenResult();
		RigTunePreLaunch.staleOps = Set.of();
	}

	// The instance as the player left it: 0.1.0's run (DH FAILED twice) and its pending.json (tools/e2e/seeds/v010-dh), the
	// download and fabric-26.2.jar deleted, DH 3.3.2 installed by the Modrinth App at the staged name.
	private void theRealInstance() throws IOException {
		Files.createDirectories(mods());
		V010Fixtures.install("real-instance/last-apply.json", ApplyResult.defaultPath(config()), mods(), config());
		String seed = Files.readString(RepoFiles.resolve("tools/e2e/seeds/v010-dh/pending.json"));
		Files.writeString(PendingActions.defaultPath(config()), V010Fixtures.template(seed, mods(), config()));
		TestJars.modJar(mods().resolve(DH), "distanthorizons");
	}

	private List<String> warnOnce() {
		List<String> lines = new ArrayList<>();
		RigTunePreLaunch.warnOnce(config(), mods(), ClientState.load(config()), lines::add, RigTunePreLaunch.staleOps);
		return lines;
	}

	@Test
	void theRealDhGroupIsNeitherCountedNorReplayed() throws IOException {
		theRealInstance();

		RigTunePreLaunch.readState(config(), false, null, () -> Map.of("distanthorizons", Set.of(DH)));

		assertEquals(0, RigTunePreLaunch.takeLeftoverOps());
		assertEquals(2, RigTunePreLaunch.staleOps.size());
		assertEquals(List.of(), warnOnce());
		// The rebuild then drops the group; at the next start that old run isn't replayed either.
		RigTunePreLaunch.staleOps = Set.of();
		assertEquals(List.of(), warnOnce());
	}

	// 0.4's count (every op) and lines without the check, as before; and a runnable group next to the stale one is still
	// counted and its failure still logged.
	@Test
	void runnableOpsAreStillCountedAndLogged() throws IOException {
		theRealInstance();
		RigTunePreLaunch.readState(config(), false, null);
		assertEquals(2, RigTunePreLaunch.takeLeftoverOps());
		assertEquals(2, warnOnce().size());

		TestJars.modJar(mods().resolve("lithium-0.20.jar"), "lithium");
		TestJars.modJar(mods().resolve("lithium-0.21.jar" + PendingActions.PENDING_SUFFIX), "lithium");
		PendingActions plan = PendingActions.load(PendingActions.defaultPath(config()));
		List<Op> ops = new ArrayList<>(plan.ops());
		ops.addAll(PendingActions.group(Op.disableFile(mods().resolve("lithium-0.20.jar")),
				Op.enableFile(mods().resolve("lithium-0.21.jar" + PendingActions.PENDING_SUFFIX), mods().resolve("lithium-0.21.jar")).withModId("lithium")));
		plan.withOps(ops).save(PendingActions.defaultPath(config()));

		RigTunePreLaunch.readState(config(), false, null, () -> Map.of("distanthorizons", Set.of(DH), "lithium", Set.of("lithium-0.20.jar")));

		assertEquals(2, RigTunePreLaunch.takeLeftoverOps());
	}

	// review 11 APPLY-1: a 0.5 helper killed after both renames of an update, before last-apply.json. Its record shows the
	// group started, so it is counted (the next exit reports it done earlier) and never taken for one installed another way.
	@Test
	void aGroupTheHelperStartedIsNeverStale() throws IOException {
		Path old = TestJars.modJar(mods().resolve("lithium-0.20.jar"), "lithium");
		Path download = TestJars.modJar(mods().resolve("lithium-0.21.jar" + PendingActions.PENDING_SUFFIX), "lithium");
		Path pending = PendingActions.defaultPath(config());
		PendingActions.create(1, mods(), config(), PendingActions.group(Op.disableFile(old),
				Op.enableFile(download, mods().resolve("lithium-0.21.jar")).withModId("lithium"))).save(pending);
		assertThrows(TestExecutors.Killed.class, () -> TestExecutors.killedAfter(download::equals).run(PendingActions.load(pending), pending));

		RigTunePreLaunch.readState(config(), false, null, () -> Map.of("lithium", Set.of("lithium-0.21.jar")));

		assertEquals(2, RigTunePreLaunch.takeLeftoverOps());
		assertEquals(Set.of(), RigTunePreLaunch.staleOps);
	}

	// No jar is opened: an enable staged without a mod id is judged by its files alone, and a check that fails counts
	// every op, as before.
	@Test
	void aFailedCheckCountsEveryOp() throws IOException {
		theRealInstance();

		RigTunePreLaunch.readState(config(), false, null, () -> {
			throw new IllegalStateException("no loader");
		});

		assertEquals(2, RigTunePreLaunch.takeLeftoverOps());
	}
}
