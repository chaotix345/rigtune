package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 4a, AC4a.3 (unit): the service's policy follows the probe's answers and settings.json, read live (render
// thread, no I/O): PENDING until detection answers, the .index/ evidence deciding first, the opt-in over everything.
class ModFilesServiceTest {
	private final AtomicReference<LauncherInfo> launcher = new AtomicReference<>();
	private final AtomicReference<InstanceEvidence> evidence = new AtomicReference<>();
	private final AtomicBoolean optIn = new AtomicBoolean();
	private final ModFilesService service = new ModFilesService(null, launcher::get, evidence::get, optIn::get);

	@Test
	void pendingUntilDetectionAnswers() {
		assertEquals(ModFilesPolicy.PENDING, service.policy());
		evidence.set(InstanceEvidence.NONE);
		assertEquals(ModFilesPolicy.PENDING, service.policy());
		launcher.set(LauncherInfo.of(Launcher.MODRINTH_APP));
		assertEquals(ModFilesPolicy.LAUNCHER, service.policy());
	}

	@Test
	void theIndexDecidesWhileDetectionPends() {
		evidence.set(new InstanceEvidence(true));
		assertEquals(ModFilesPolicy.LAUNCHER, service.policy());
		launcher.set(LauncherInfo.of(Launcher.MULTIMC));
		assertEquals(ModFilesPolicy.LAUNCHER, service.policy());
	}

	@Test
	void aFolderLauncherIsRigTuneOnceTheListingAnswers() {
		launcher.set(LauncherInfo.of(Launcher.OFFICIAL));
		assertEquals(ModFilesPolicy.PENDING, service.policy());
		evidence.set(InstanceEvidence.NONE);
		assertEquals(ModFilesPolicy.RIGTUNE, service.policy());
	}

	@Test
	void theOptInMakesItRigTuneButNotWithoutIt() {
		launcher.set(LauncherInfo.of(Launcher.MODRINTH_APP));
		evidence.set(InstanceEvidence.NONE);
		optIn.set(true);
		assertEquals(ModFilesPolicy.RIGTUNE, service.policy());
		assertEquals(ModFilesPolicy.LAUNCHER, service.withoutOptIn());
		assertEquals(true, service.optedIn());
		assertEquals("- Mod files: RigTune (opted in)", service.shareLine());
	}

	// An opt-in left on in an instance whose launcher keeps no record changes nothing and isn't reported.
	@Test
	void anOptInThatDoesntMatterIsNotOptedIn() {
		launcher.set(LauncherInfo.of(Launcher.OFFICIAL));
		evidence.set(InstanceEvidence.NONE);
		optIn.set(true);
		assertEquals(ModFilesPolicy.RIGTUNE, service.policy());
		assertEquals(false, service.optedIn());
		assertEquals(null, service.shareLine());
	}

	@Test
	void theShareLineFollowsThePolicy() {
		assertEquals("- Mod files: waiting for the launcher check", service.shareLine());
		launcher.set(LauncherInfo.of(Launcher.GDLAUNCHER));
		evidence.set(InstanceEvidence.NONE);
		assertEquals("- Mod files: changed in GDLauncher", service.shareLine());
	}
}
