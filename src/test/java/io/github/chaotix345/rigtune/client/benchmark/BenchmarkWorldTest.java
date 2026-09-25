package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld.FolderAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkWorldTest {
	@Test
	void createdWhenMissing() {
		assertEquals(FolderAction.CREATE, BenchmarkWorld.folderAction(false, false, null, "26.2"));
	}

	@Test
	void reusedForTheSameVersion() {
		assertEquals(FolderAction.OPEN, BenchmarkWorld.folderAction(true, true, "26.3", "26.3"));
	}

	@Test
	void recreatedForAnotherMinecraftVersion() {
		assertEquals(FolderAction.RECREATE, BenchmarkWorld.folderAction(true, true, "26.2", "26.3"));
	}

	@Test
	void recreatedWhenOurMarkerIsUnreadableOrForAnotherSeed() {
		assertEquals(FolderAction.RECREATE, BenchmarkWorld.folderAction(true, true, null, "26.2"));
	}

	@Test
	void aFolderWithoutOurMarkerIsNeverDeleted() {
		assertEquals(FolderAction.MOVE_ASIDE_AND_CREATE, BenchmarkWorld.folderAction(true, false, null, "26.2"));
	}

	@Test
	void onlyTheBenchmarkSaveCounts() {
		assertTrue(BenchmarkWorld.isBenchmarkSave("rigtune-benchmark", "RigTune Benchmark"));
		assertFalse(BenchmarkWorld.isBenchmarkSave("rigtune-benchmark", "My survival world"));
		assertFalse(BenchmarkWorld.isBenchmarkSave("New World", "RigTune Benchmark"));
		assertFalse(BenchmarkWorld.isBenchmarkSave(null, null));
	}
	@Test
	void theCameraIsSixteenAboveTheTerrainFloorWhenThatSpotIsClear() {
		// Floor 117 on 26.2 and 26.3; 26.3's tree reaches 123 and no longer moves the camera.
		assertEquals(133, BenchmarkWorld.cameraY(117, true, 123));
		assertEquals(133, BenchmarkWorld.cameraY(117, true, 117));
	}

	@Test
	void aBlockedSpotGoesTenAboveTheSurfaceNeverLower() {
		assertEquals(150, BenchmarkWorld.cameraY(117, false, 140));
		assertEquals(133, BenchmarkWorld.cameraY(117, false, 120));
	}
}
