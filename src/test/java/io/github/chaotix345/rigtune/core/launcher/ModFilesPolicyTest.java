package io.github.chaotix345.rigtune.core.launcher;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy.LAUNCHER;
import static io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy.PENDING;
import static io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy.RIGTUNE;
import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 4a, AC4a.1: the policy table, row by row in its order, for every Launcher value x .index/ {absent,
// present, not listed yet} x opt-in x detection {done, pending}.
class ModFilesPolicyTest {
	private static final InstanceEvidence ABSENT = new InstanceEvidence(false);
	private static final InstanceEvidence PRESENT = new InstanceEvidence(true);
	private static final InstanceEvidence UNLISTED = null;

	// The launcher's own row of the table (detection done, no .index/, no opt-in).
	private static final Map<Launcher, ModFilesPolicy> BY_LAUNCHER = new EnumMap<>(Map.of(
			Launcher.MODRINTH_APP, LAUNCHER,
			Launcher.CURSEFORGE, LAUNCHER,
			Launcher.ATLAUNCHER, LAUNCHER,
			Launcher.GDLAUNCHER, LAUNCHER,
			Launcher.OFFICIAL, RIGTUNE,
			Launcher.MULTIMC, RIGTUNE,
			Launcher.PRISM, RIGTUNE,
			Launcher.UNKNOWN, RIGTUNE));

	@Test
	void everyLauncherIsInTheTable() {
		assertEquals(Launcher.values().length, BY_LAUNCHER.size());
	}

	@Test
	void theOptInComesFirst() {
		for (Launcher launcher : Launcher.values()) {
			for (InstanceEvidence evidence : new InstanceEvidence[]{ABSENT, PRESENT, UNLISTED}) {
				assertEquals(RIGTUNE, ModFilesPolicy.of(LauncherInfo.of(launcher), evidence, true), launcher + " " + evidence);
			}
		}
		assertEquals(RIGTUNE, ModFilesPolicy.of(null, PRESENT, true));
		assertEquals(RIGTUNE, ModFilesPolicy.of(null, UNLISTED, true));
	}

	// Evidence first (SPEC-14): a packwiz index means LAUNCHER whatever launcher is detected (PolyMC is detected as MultiMC)
	// and whether or not detection has answered.
	@Test
	void aPackwizIndexIsLauncherWhateverTheLauncher() {
		for (Launcher launcher : Launcher.values()) {
			assertEquals(LAUNCHER, ModFilesPolicy.of(LauncherInfo.of(launcher), PRESENT, false), launcher.name());
		}
		assertEquals(LAUNCHER, ModFilesPolicy.of(null, PRESENT, false), "pending + .index/");
		assertEquals(LAUNCHER, ModFilesPolicy.of(LauncherInfo.of(Launcher.MULTIMC), PRESENT, false));
		assertEquals(LAUNCHER, ModFilesPolicy.of(LauncherInfo.of(Launcher.OFFICIAL), PRESENT, false));
		assertEquals(LAUNCHER, ModFilesPolicy.of(LauncherInfo.UNKNOWN, PRESENT, false));
	}

	// A detection that hasn't answered is PENDING, never RIGTUNE.
	@Test
	void anUnansweredDetectionIsPending() {
		assertEquals(PENDING, ModFilesPolicy.of(null, ABSENT, false));
		assertEquals(PENDING, ModFilesPolicy.of(null, UNLISTED, false));
	}

	@Test
	void theLaunchersThatKeepARecordAreLauncher() {
		for (Launcher launcher : Launcher.values()) {
			assertEquals(BY_LAUNCHER.get(launcher), ModFilesPolicy.of(LauncherInfo.of(launcher), ABSENT, false), launcher.name());
		}
		assertEquals(LAUNCHER, ModFilesPolicy.of(new LauncherInfo(Launcher.CURSEFORGE, false), ABSENT, false));
	}

	// Until the .index/ listing has answered, a launcher that would be RIGTUNE is PENDING: an index can't be ruled out yet.
	@Test
	void anUnlistedIndexKeepsTheFolderLaunchersPending() {
		for (Launcher launcher : Launcher.values()) {
			ModFilesPolicy expected = BY_LAUNCHER.get(launcher) == LAUNCHER ? LAUNCHER : PENDING;
			assertEquals(expected, ModFilesPolicy.of(LauncherInfo.of(launcher), UNLISTED, false), launcher.name());
		}
	}

	// The whole matrix against the table's order, spelled out cell by cell.
	@Test
	void everyCell() {
		for (Launcher launcher : Launcher.values()) {
			for (boolean detected : new boolean[]{true, false}) {
				for (InstanceEvidence evidence : new InstanceEvidence[]{ABSENT, PRESENT, UNLISTED}) {
					for (boolean optIn : new boolean[]{false, true}) {
						ModFilesPolicy expected;
						if (optIn) {
							expected = RIGTUNE;
						} else if (evidence == PRESENT) {
							expected = LAUNCHER;
						} else if (!detected) {
							expected = PENDING;
						} else if (BY_LAUNCHER.get(launcher) == LAUNCHER) {
							expected = LAUNCHER;
						} else if (evidence == UNLISTED) {
							expected = PENDING;
						} else {
							expected = RIGTUNE;
						}
						LauncherInfo info = detected ? LauncherInfo.of(launcher) : null;
						assertEquals(expected, ModFilesPolicy.of(info, evidence, optIn), launcher + " detected=" + detected + " " + evidence + " optIn=" + optIn);
					}
				}
			}
		}
	}

	@Test
	void modFilesAreTheLaunchersExactlyUnderLauncherAndPending() {
		assertEquals(false, RIGTUNE.launcherManages());
		assertEquals(true, LAUNCHER.launcherManages());
		assertEquals(true, PENDING.launcherManages());
	}
}
