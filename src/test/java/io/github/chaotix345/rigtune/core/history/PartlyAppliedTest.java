package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups.Rename;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// review-8 AH-1: a helper killed between two renames of a group never counts an attempt, so only its record of the
// renames it started (UnfinishedGroups) can show the group is half applied.
class PartlyAppliedTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();

	private static final List<Op> UPDATE = PendingActions.group(Op.disableFile(MODS.resolve("x-1.jar")),
			Op.enableFile(MODS.resolve("x-2.jar.rigtune-pending"), MODS.resolve("x-2.jar")).withModId("x"));

	private static Rename disabled(String to) {
		return new Rename(UPDATE.get(0).id(), MODS.resolve("x-1.jar").toString(), MODS.resolve(to).toString());
	}

	private static Rename enabled() {
		return new Rename(UPDATE.get(1).id(), MODS.resolve("x-2.jar.rigtune-pending").toString(), MODS.resolve("x-2.jar").toString());
	}

	private static Set<String> groups(Set<String> files, Rename... recorded) {
		return PartlyApplied.groups(UPDATE, files, List.of(recorded));
	}

	@Test
	void aDisableTheRecordShowsDoneWhileItsEnableIsLeftIsHalfApplied() {
		assertEquals(Set.of(UPDATE.get(0).group()), groups(Set.of("x-1.jar.disabled", "x-2.jar.rigtune-pending"), disabled("x-1.jar.disabled"), enabled()));
	}

	@Test
	void withoutARecordAnOldDisabledCopyNextToAnUnstartedUpdateIsNot() {
		assertEquals(Set.of(), groups(Set.of("x-1.jar.disabled", "x-2.jar.rigtune-pending")));
	}

	@Test
	void aRecordedRenameThatIsntInEffectDoesntCount() {
		assertEquals(Set.of(), groups(Set.of("x-1.jar", "x-2.jar.rigtune-pending"), disabled("x-1.jar.disabled"), enabled()));
		assertEquals(Set.of(), groups(Set.of("x-1.jar.disabled", "x-2.jar.rigtune-pending"), disabled("x-1.jar.disabled.1")),
				"the recorded .disabled.1 isn't there; x-1.jar.disabled is someone else's copy");
	}

	@Test
	void aRecordOfAnotherOpDoesntCount() {
		Rename other = new Rename("someone-else", MODS.resolve("x-1.jar").toString(), MODS.resolve("x-1.jar.disabled").toString());

		assertEquals(Set.of(), groups(Set.of("x-1.jar.disabled", "x-2.jar.rigtune-pending"), other));
	}

	@Test
	void aGroupWhoseRenamesAreAllDoneIsnt() {
		assertEquals(Set.of(), groups(Set.of("x-1.jar.disabled", "x-2.jar"), disabled("x-1.jar.disabled"), enabled()));
	}

	@Test
	void anAdditionWhoseModIsEnabledAndWhoseLibraryIsntIs() {
		List<Op> addition = PendingActions.group(Op.enableFile(MODS.resolve("iris.jar.rigtune-pending"), MODS.resolve("iris.jar")),
				Op.enableFile(MODS.resolve("lib.jar.rigtune-pending"), MODS.resolve("lib.jar")));
		Rename iris = new Rename(addition.get(0).id(), MODS.resolve("iris.jar.rigtune-pending").toString(), MODS.resolve("iris.jar").toString());

		assertEquals(Set.of(addition.get(0).group()), PartlyApplied.groups(addition, Set.of("iris.jar", "lib.jar.rigtune-pending"), List.of(iris)));
	}

	@Test
	void anUpdateWhoseDownloadIsGoneStaysHalfAppliedSoTheHelperRollsItBack() {
		assertEquals(Set.of(UPDATE.get(0).group()), groups(Set.of("x-1.jar.disabled"), disabled("x-1.jar.disabled")));
	}

	@Test
	void aFailedRollbackIsStillSeenWithoutARecord() {
		List<Op> tried = UPDATE.stream().map(op -> op.withAttempts(1)).toList();

		assertEquals(Set.of(UPDATE.get(0).group()), PartlyApplied.groups(tried, Set.of("x-1.jar.disabled", "x-2.jar.rigtune-pending"), List.of()));
	}
}
