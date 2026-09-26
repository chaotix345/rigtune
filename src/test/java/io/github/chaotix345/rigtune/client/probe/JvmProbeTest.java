package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.jvm.JvmCollector;
import io.github.chaotix345.rigtune.core.jvm.JvmFacts;
import io.github.chaotix345.rigtune.core.jvm.JvmFinding;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.jvm.JvmSnapshot;
import io.github.chaotix345.rigtune.core.jvm.VmOptions;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 6: the probe reads the running JVM (here the unit tests' own HotSpot JVM), never fails, times out to
// UNAVAILABLE, and its facts reach HardwareProfile.flags; the game tests' seam classifies an injected snapshot.
class JvmProbeTest {
	@AfterEach
	void backToTheRealJvm() {
		JvmProbe.inject(null);
	}

	private static JvmSnapshot zgcSnapshot() {
		VmOptions vm = name -> switch (name) {
			case "UseZGC" -> VmOptions.Lookup.found("true", VmOptions.Origin.VM_CREATION);
			case "ZGenerational" -> VmOptions.Lookup.MISSING;
			default -> VmOptions.Lookup.found("false", VmOptions.Origin.DEFAULT);
		};
		return new JvmSnapshot(List.of("-XX:+UseZGC", "-XX:+ZGenerational", "-Dusing.aikars.flags=x"), vm, List.of("ZGC Minor Cycles"),
				4L << 30, -1, "25.0.4", "Test");
	}

	@Test
	void readsTheRunningJvm() {
		JvmSnapshot snapshot = assertDoesNotThrow(JvmProbe::read);
		assertNotNull(snapshot.options(), "the test JVM is HotSpot");
		assertFalse(snapshot.gcBeanNames().isEmpty());
		assertTrue(snapshot.maxHeapBytes() > 0);
		assertNotNull(snapshot.javaVersion());
		assertFalse(snapshot.toString().contains("-D"), "toString leaves the arguments out: " + snapshot);
		assertEquals(VmOptions.Status.MISSING, snapshot.options().lookup("ZGenerational").status(), "removed in Java 24");
		assertTrue(snapshot.options().lookup("MaxHeapSize").found());
	}

	@Test
	void theProbeClassifiesTheRunningJvm() throws Exception {
		JvmProbe.reset();
		JvmReport report = JvmProbe.probeAsync().get(10, TimeUnit.SECONDS);
		assertTrue(report.available(), report.toString());
		assertTrue(report.facts().contains(JvmFacts.PROBED));
		assertNotNull(report.collector());
		assertTrue(report.facts().contains(report.collector().fact()));
		assertSame(report, JvmProbe.current());
	}

	@Test
	void theSeamClassifiesAnInjectedSnapshot() throws Exception {
		JvmProbe.inject(zgcSnapshot());
		JvmReport report = JvmProbe.probeAsync().get(10, TimeUnit.SECONDS);
		assertEquals(JvmCollector.ZGC, report.collector());
		assertEquals(List.of(new JvmFinding(JvmFinding.Kind.IGNORED, "-XX:+ZGenerational"), new JvmFinding(JvmFinding.Kind.SERVER_SET, "-Dusing.aikars.flags")),
				report.findings());
		assertSame(report, JvmProbe.current());
		JvmProbe.inject(null);
		JvmReport real = JvmProbe.probeAsync().get(10, TimeUnit.SECONDS);
		assertFalse(real.findings().contains(new JvmFinding(JvmFinding.Kind.IGNORED, "-XX:+ZGenerational")));
	}

	@Test
	void anyFailureIsUnavailable() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			assertSame(JvmReport.UNAVAILABLE, JvmProbe.start(() -> {
				throw new IllegalStateException("boom");
			}, executor).get(5, TimeUnit.SECONDS));
			assertSame(JvmReport.UNAVAILABLE, JvmProbe.start(() -> {
				throw new NoClassDefFoundError("com/sun/management/HotSpotDiagnosticMXBean");
			}, executor).get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void aStalledProbeTimesOutAsUnavailable() throws Exception {
		CompletableFuture<JvmReport> never = new CompletableFuture<>();
		assertSame(JvmReport.UNAVAILABLE, JvmProbe.withTimeout(never, 50).get(5, TimeUnit.SECONDS));
		assertFalse(never.isDone(), "the shared probe isn't completed by one caller's timeout");
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			CompletableFuture<JvmReport> slow = JvmProbe.start(() -> {
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return zgcSnapshot();
			}, executor);
			assertSame(JvmReport.UNAVAILABLE, JvmProbe.withTimeout(slow, 50).get(5, TimeUnit.SECONDS));
			release.countDown();
			assertEquals(JvmCollector.ZGC, JvmProbe.withTimeout(slow, 5000).get(5, TimeUnit.SECONDS).collector(), "a later caller gets it");
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void factsReachTheProfileFlags() {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.flags = Set.of("shaders-enabled");
		HardwareProfile profile = hw.build();
		JvmReport report = new JvmReport(true, "25", "x", JvmCollector.G1, false, 6144, -1, List.of(), Set.of(JvmFacts.PROBED, "jvm-gc-g1"));
		HardwareProfile with = JvmProbe.withFacts(profile, report);
		assertEquals(Set.of("shaders-enabled", JvmFacts.PROBED, "jvm-gc-g1"), with.flags());
		assertEquals(profile.cpu(), with.cpu());
		assertEquals(profile.maxHeapMb(), with.maxHeapMb());
		assertEquals(profile.display(), with.display());
		assertSame(profile, JvmProbe.withFacts(profile, JvmReport.UNAVAILABLE));
		assertSame(profile, JvmProbe.withFacts(profile, null));
	}
}
