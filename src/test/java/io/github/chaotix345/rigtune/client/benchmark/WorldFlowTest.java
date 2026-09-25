package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.benchmark.WorldFlow.Action;
import io.github.chaotix345.rigtune.client.benchmark.WorldFlow.Observation;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldFlowTest {
	// No world, no server, not back on the menu yet (a loading screen is up).
	private static final Observation LOADING = new Observation(false, false, false, false, false, false, false);
	private static final Observation BACK_ON_MENU = new Observation(false, false, false, false, false, true, false);
	private static final Observation MULTIPLAYER = new Observation(true, true, false, false, false, false, false);
	private static final Observation OTHER_SAVE = new Observation(true, false, true, true, false, false, false);
	private static final Observation BENCHMARK_SAVE = new Observation(true, false, true, true, true, false, false);
	private static final Observation AT_CAMERA = new Observation(true, false, true, true, true, false, true);
	private static final Observation GONE = new Observation(false, false, false, false, false, false, false);
	private static final Observation SERVER_STOPPING = new Observation(false, false, true, false, false, false, false);

	private static WorldFlow.Step next(State state, int ticks, Observation o) {
		return WorldFlow.next(state, ticks, o);
	}

	private static void assertStep(State state, Action action, WorldFlow.Step step) {
		assertEquals(new WorldFlow.Step(state, action), step);
	}

	@Test
	void openingWaitsWhileLoading() {
		assertStep(State.OPENING, Action.NONE, next(State.OPENING, 100, LOADING));
	}

	@Test
	void openingSetsUpOnlyTheBenchmarkSave() {
		assertStep(State.SETTING_UP, Action.SET_UP, next(State.OPENING, 5, BENCHMARK_SAVE));
	}

	@Test
	void openingAnotherSaveFailsWithoutTouchingOrLeavingIt() {
		assertStep(State.FAILED, Action.NONE, next(State.OPENING, 5, OTHER_SAVE));
	}

	@Test
	void openingOnAServerFailsAtOnceWithoutDisconnecting() {
		assertStep(State.FAILED, Action.NONE, next(State.OPENING, 1, MULTIPLAYER));
	}

	@Test
	void openingBackOnTheMenuFailsAfterTheGrace() {
		assertStep(State.OPENING, Action.NONE, next(State.OPENING, WorldFlow.FAILED_OPEN_GRACE_TICKS, BACK_ON_MENU));
		assertStep(State.FAILED, Action.NONE, next(State.OPENING, WorldFlow.FAILED_OPEN_GRACE_TICKS + 1, BACK_ON_MENU));
	}

	@Test
	void openingTimesOutWithoutLeaving() {
		assertStep(State.FAILED, Action.NONE, next(State.OPENING, WorldFlow.TIMEOUT_TICKS + 1, LOADING));
	}

	@Test
	void settingUpBecomesReadyAtTheCamera() {
		assertStep(State.SETTING_UP, Action.NONE, next(State.SETTING_UP, 10, BENCHMARK_SAVE));
		assertStep(State.READY, Action.READY, next(State.SETTING_UP, 10, AT_CAMERA));
	}

	@Test
	void settingUpInAnyOtherWorldFailsWithoutLeaving() {
		assertStep(State.FAILED, Action.NONE, next(State.SETTING_UP, 10, OTHER_SAVE));
		assertStep(State.FAILED, Action.NONE, next(State.SETTING_UP, 10, MULTIPLAYER));
		assertStep(State.FAILED, Action.NONE, next(State.SETTING_UP, 10, GONE));
	}

	@Test
	void settingUpTimeoutLeavesTheBenchmarkWorld() {
		assertStep(State.SETTING_UP, Action.LEAVE, next(State.SETTING_UP, WorldFlow.TIMEOUT_TICKS + 1, BENCHMARK_SAVE));
	}

	@Test
	void readyEndsWhenTheWorldIsNoLongerTheBenchmarkSave() {
		assertStep(State.READY, Action.NONE, next(State.READY, 500, AT_CAMERA));
		assertStep(State.IDLE, Action.NONE, next(State.READY, 500, GONE));
		assertStep(State.IDLE, Action.NONE, next(State.READY, 500, OTHER_SAVE));
	}

	@Test
	void leavingFinishesOnceWorldAndServerAreGone() {
		assertStep(State.LEAVING, Action.NONE, next(State.LEAVING, 3, BENCHMARK_SAVE));
		assertStep(State.LEAVING, Action.NONE, next(State.LEAVING, 3, SERVER_STOPPING));
		assertStep(State.IDLE, Action.FINISH_EXIT, next(State.LEAVING, 3, GONE));
	}

	@Test
	void aLateLoadOfTheBenchmarkWorldAfterFailingIsLeft() {
		assertStep(State.FAILED, Action.LEAVE, next(State.FAILED, 40, BENCHMARK_SAVE));
	}

	@Test
	void theBenchmarkWorldOpenedByHandLaterIsLeftAlone() {
		assertStep(State.FAILED, Action.NONE, next(State.FAILED, WorldFlow.LATE_LOAD_TICKS + 1, BENCHMARK_SAVE));
	}

	@Test
	void failedNeverTouchesOtherWorlds() {
		assertStep(State.FAILED, Action.NONE, next(State.FAILED, 40, OTHER_SAVE));
		assertStep(State.FAILED, Action.NONE, next(State.FAILED, 40, MULTIPLAYER));
	}

	@Test
	void idleAndAwaitingExitDoNothing() {
		assertStep(State.IDLE, Action.NONE, next(State.IDLE, 40, BENCHMARK_SAVE));
		assertStep(State.AWAITING_EXIT, Action.NONE, next(State.AWAITING_EXIT, 40, BENCHMARK_SAVE));
	}
}
