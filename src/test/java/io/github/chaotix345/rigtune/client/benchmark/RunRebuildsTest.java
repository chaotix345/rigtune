package io.github.chaotix345.rigtune.client.benchmark;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #42: rebuilds asked for while a benchmark has its knobs applied wait, and one runs after its restore.
class RunRebuildsTest {
	@Test
	void aRebuildDuringARunWaitsForItsRestore() {
		AtomicBoolean running = new AtomicBoolean(true);
		RunRebuilds rebuilds = new RunRebuilds(running::get);
		assertTrue(rebuilds.defer());
		assertTrue(rebuilds.defer());
		running.set(false);
		assertTrue(rebuilds.release(), "one rebuild after the restore");
		assertFalse(rebuilds.release(), "only once");
	}

	@Test
	void withoutARunNothingWaits() {
		RunRebuilds rebuilds = new RunRebuilds(() -> false);
		assertFalse(rebuilds.defer());
		assertFalse(rebuilds.release(), "a run that no rebuild waited for asks for none");
	}
}
