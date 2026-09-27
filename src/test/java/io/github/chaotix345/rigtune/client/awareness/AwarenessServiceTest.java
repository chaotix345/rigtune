package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.awareness.Fingerprint;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2W: AW-1 (AC2W.1's unit part; av's test first) and AW-2's listener. A shown hardware notice commits
// the fingerprint; a later rescan of the same hardware (a power edge, the network toggle, the main Re-scan) keeps it for
// the session; a changed-back or new change replaces it; the notice's own Re-scan retires it (Open question 4). (The
// controller isn't needed for these.)
class AwarenessServiceTest {
	private static final String OLD = "4.6.0 Core Profile Context 25.9.1.250901";
	private static final String NEW = "4.6.0 Core Profile Context 25.10.1.251001";

	@TempDir
	Path dir;

	private static HardwareProfile rig(String driver) {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = new GpuInfo("ATI Technologies Inc.", "AMD Radeon RX 7800 XT", driver, GraphicsBackend.OPENGL, 16384);
		return hw.build();
	}

	private AwarenessService withShownDriverNotice() throws InterruptedException {
		AwarenessService service = new AwarenessService(null, dir);
		service.afterProbe(rig(OLD));
		service.afterProbe(rig(NEW));
		Notice notice = service.hardwareNotice();
		assertNotNull(notice, "a driver change is a notice");
		service.shown(notice);
		awaitCommitted(NEW);
		return service;
	}

	private void awaitCommitted(String driver) throws InterruptedException {
		AwarenessStore store = AwarenessStore.shared(dir);
		Fingerprint wanted = Fingerprint.of(rig(driver));
		for (int i = 0; i < 100 && !wanted.equals(Fingerprint.read(store.read())); i++) {
			Thread.sleep(20);
		}
		assertEquals(wanted, Fingerprint.read(store.read()), "committed once shown");
	}

	@Test
	void aw1ShownNoticeSurvivesARescan() throws InterruptedException {
		AwarenessService service = withShownDriverNotice();
		Notice shown = service.hardwareNotice();
		service.afterProbe(rig(NEW));
		assertNotNull(service.hardwareNotice(), "the notice stays for the session");
		assertEquals(shown.key(), service.hardwareNotice().key());
		service.afterProbe(rig(NEW));
		assertEquals(shown.key(), service.hardwareNotice().key(), "and after another rescan");
	}

	@Test
	void itsOwnRescanRetiresIt() throws InterruptedException {
		AwarenessService service = withShownDriverNotice();
		service.retire();
		assertNull(service.hardwareNotice());
		service.afterProbe(rig(NEW));
		assertNull(service.hardwareNotice(), "the rescan it starts finds nothing new");
	}

	@Test
	void itsOwnRescanCommitsANoticeNotYetCommitted() {
		AwarenessService service = new AwarenessService(null, dir);
		service.afterProbe(rig(OLD));
		service.afterProbe(rig(NEW));
		assertNotNull(service.hardwareNotice());
		service.retire();
		assertEquals(Fingerprint.of(rig(NEW)), Fingerprint.read(AwarenessStore.shared(dir).read()), "committed before the rescan starts");
		service.afterProbe(rig(NEW));
		assertNull(service.hardwareNotice());
	}

	@Test
	void aChangeBackReplacesTheCommittedNotice() throws InterruptedException {
		AwarenessService service = withShownDriverNotice();
		String shown = service.hardwareNotice().key();
		service.afterProbe(rig(OLD));
		Notice back = service.hardwareNotice();
		assertNotNull(back, "the driver changed back: a new notice");
		assertNotEquals(shown, back.key());
		assertEquals("Your GPU driver changed since last time (25.10.1 → 25.9.1)", back.message().english());
	}

	// Review L3: the same change reported again before the shown notice's asynchronous commit is written (or after it was
	// lost) keeps the committed notice (it doesn't start over as unseen) and writes the fingerprint again.
	@Test
	void theSameChangeReportedAgainKeepsTheCommittedNotice() throws InterruptedException {
		AwarenessService service = withShownDriverNotice();
		String key = service.hardwareNotice().key();
		AwarenessStore.shared(dir).update(Fingerprint.of(rig(OLD))::writeTo);
		service.afterProbe(rig(NEW));
		assertEquals(key, service.hardwareNotice().key());
		assertTrue(service.hardwareCommitted(), "still counted as seen");
		assertEquals(Fingerprint.of(rig(NEW)), Fingerprint.read(AwarenessStore.shared(dir).read()), "committed again");
	}

	@Test
	void anUncommittedNoticeGoesWhenTheHardwareChangesBack() {
		AwarenessService service = new AwarenessService(null, dir);
		service.afterProbe(rig(OLD));
		service.afterProbe(rig(NEW));
		assertNotNull(service.hardwareNotice());
		service.afterProbe(rig(OLD));
		assertNull(service.hardwareNotice(), "nothing was committed, and the hardware is as stored: no change (0.4's behaviour)");
	}

	// AW-2: what NoticeScreen lists after a rebuild counts as shown.
	@Test
	void aListedHardwareNoticeIsCommitted() throws InterruptedException {
		AwarenessService service = new AwarenessService(null, dir);
		service.afterProbe(rig(OLD));
		service.afterProbe(rig(NEW));
		Notice notice = service.hardwareNotice();
		service.listed(List.of(new Notice("other", notice.priority(), notice.message(), null, List.of(), true), notice));
		awaitCommitted(NEW);
		service.afterProbe(rig(NEW));
		assertNotNull(service.hardwareNotice());
	}
}
