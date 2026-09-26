# WS-F2: footprint follow-up, the session monitor's numbers (SPEC 10 AC10.4; amendments F-L1, F-M1, F-M3)

Branch `feat/footprint-monitor`. Plan: docs/v0.4/plans/ws-f2.md. Evidence and calibration:
docs/v0.4/verification/footprint/README.md ("Session monitor (F-L1)").

## What landed

- **FrameHookBudgetTest** covers three cases, each 10 M calls measured the same way as the monitor-off case (bytes
  allocated from cold and in the hot runs, ns/call as the best of 5):
  - monitor off;
  - monitor on: what `DebugScreenOverlayMixin` runs per frame (`FrameTimes.onFrame` + `StutterMonitor.onFrame`);
  - monitor on with the phase timers: the same, plus every `MinecraftFrameMixin` call of a capped frame that has one tick
    and one chunk load. That's 8 `System.nanoTime()` reads per frame.
  The uncapped frame (no limiter pair, 6 reads) is logged as a diagnostic and its allocation is gated. A 60 ms frame
  every 1024 frames exercises the spike-candidate path. The session starts through the test-only
  `StutterMonitorAccess`, because `StutterMonitor`'s start and stop are package-private.
- **FootprintGameTest.sessionMonitor** runs after the title-screen retention part, in a singleplayer world, on the play
  path (no screen open):
  - `StutterMonitor.retainedBytes()` must be 0 with the monitor off.
  - With the monitor still off: `RigTuneClient.onTick` + `StutterHooks.tick` (`tickHookNsPerCallWorld`). This is every
    player's play path; the title-screen tick case never included the monitor's listener.
  - Monitor on: the sampler thread and the GC listener must exist. Measured: the sampler's CPU in its first 5 s and then
    over 60 s of wall time; `RigTuneClient.onTick` + `StutterHooks.tick` (the private listener, called through a
    MethodHandle, so the product code is unchanged), best of 3 × 100,000 after a 200,000-call warm-up;
    `monitorOnRetainedBytes`.
  - Monitor off: no capture, no GC listener, no sampler thread. Then `monitorOffRetainedBytes`, and once the session is in
    stutter.json, a class histogram: `monitorOffLeftoverInstances` counts live FrameRing, StutterRings, RecordRing,
    Capture and Copy objects, plus Snapshot objects beyond each class's static `EMPTY`.
  - Diagnostics, not gated: the heap after a full GC before, during and after the monitor; the capture objects at each
    point; the frames and phase timing; and the CPU of the sampler's JDK calls alone (`samplerJdkFloorMsPer240`).
  - PowerWatcher: `isRunning()` must be false and no "RigTune power" thread may exist without a battery, both at the
    title and in the world. `hasBattery` is recorded, so the check is visibly on the no-battery path.
- **New budget keys**:
  - `frameHookNsPerCallOnPhases` / `frameHookAllocBytesOnPhases` (ceilings 400 ns / 0);
  - `tickHookNsPerCallOn` / `tickHookAllocBytesOn` and `tickHookNsPerCallWorld` / `tickHookAllocBytesWorld`
    (2000 ns / 0);
  - `monitorOffLeftoverInstances` (0).
  `FootprintBudgetsTest` pins their ceilings.
- **ThreadSampler (product code; the coordinator gave this workstream ownership after WS-S finished)**. The per-thread
  part of a sample moved into `ThreadSampler.Tally`:
  - two open-addressing tables keyed by thread id, the previous sample's and this one's, swapped every sample; each
    holds the CPU time and the group, so nothing is boxed;
  - `getThreadInfo` runs only for ids not seen before;
  - a `Source` interface (the MXBeans in the game, a fake in tests).
  What it reports is unchanged: the same per-group deltas, a new thread counting from 0, a thread with CPU time -1
  dropped, the first sample as the baseline only, and the census once. `ThreadSamplerTest` runs the old loop (kept in the
  test as the reference) and the Tally over a scripted source with threads starting, ending, and listed but already
  ended when their CPU time is read. The results are equal sample for sample, and each name is read once.
  There is one difference, in a race: a known thread that ends between the CPU-time read and the old per-sample name
  read. The old loop skipped it; the new one counts its last delta, which is the CPU time it actually read.
  Group caching assumes a thread keeps its name. The game's threads are named when they're created, and the Render
  thread is renamed before any world loads. A steady-state sample allocates nothing in
  the Tally (asserted); the two JDK calls still return fresh arrays.

## Decisions and deviations

1. **Phase-timer ceiling 400 ns (F-M3, coordinator).** SPEC 10's 200 ns covers `FrameTimes.onFrame` +
   `StutterMonitor.onFrame`. The capped frame with every phase timer reads the clock 8 times (about 21 ns per read on CI).
   Under 200 ns, 2 × max would have been capped at 200 with 17 % headroom, and flaky gates are worse than loose ones.
2. **Sampler: steady state gated, ceiling 120 ms per 60 s (coordinator).**
   - The first measurement, from the thread's start, gave 48.5-68.1 ms per 60 s on CI against SPEC 10's 30 ms.
   - After the Tally, the steady state (60 s from 5 s after the start) was 20.1-50.8 ms over 21 CI legs, with 2.9-6.1 ms
     in the first 5 s.
   - The JDK calls alone cost 2-7 ms per 240 samples; why the rest costs more is unverified (see the verification
     README).
   - The 4 Hz cadence stays, because attribution quality matters more than about 0.08 % of one core while an opt-in
     monitor runs.
   - The ceiling became 120 ms (0.2 % of one core); the budget is 2 × max observed.
3. **The END_CLIENT_TICK case with the monitor on is in FootprintGameTest, not in JUnit.** `StutterHooks.tick` needs a
   Minecraft instance, the same reason as WS-F's deviation 7.
4. **64 KiB allocation-noise bound (coordinator, e8e5195).** FrameHookBudgetTest's cold-count allowance moved from 4 KiB
   to the bound StutterMonitorTest and FrameRingAllocationTest use. AC10.2's `new long[1]` regression still shows:
   129-382 KB over the cold calls.
5. **Retention = explicit accounting (F-M1).** The heap deltas in a live world move by several hundred KB to MBs, so they
   are recorded but not gated. The leftover-instance check is exact.

## Calibration

The verification README has the tables. Limits are 2 × the largest value observed, local or CI, capped at the ceiling
and rounded up. The samples: 3 CI runs per leg on `85bdcec`, 3 on `8b44741`, the earlier runs of the same measured code,
and 2 local 26.2 runs.

| budget | largest | limit |
|---|---|---|
| frameHookNsPerCallOn | 37.12 ns | 75 |
| frameHookNsPerCallOnPhases | 256.21 ns | 400 (ceiling) |
| tickHookNsPerCallOn | 50.24 ns (local; CI 48.62) | 101 |
| tickHookNsPerCallWorld | 43.28 ns | 87 |
| monitorOnRetainedBytes | 1,982,608 | 2,621,440 (ceiling) |
| samplerCpuMsPer60s | 50.83 ms | 102 |

Every allocation, `monitorOffRetainedBytes` and `monitorOffLeftoverInstances` measured 0, so their limits are 0. Fail
mode stays on.

## Self-review

A code-reviewer subagent reviewed the branch at `85bdcec` and found 0 high, 2 medium and 6 low.
Fixed:
- M1/M2: ThreadSamplerTest now grows the table with live entries in it (a mutation that skips the rehash fails 2 tests),
  and runs the equivalence check against the reference loop from an 8-slot table with a burst of 64 threads.
- L3: a renamed thread keeps its first group (documented).
- L4: the ThreadSampler cost comment gives the amended ceiling.
- L6: the leftover check retries the histogram twice.
- L7: `phaseTimersSeen` replaces the misleading `monitorPhaseTiming`.
- L8: a failed check still writes the numbers measured so far to the artifact.
Not changed:
- L5: the budgets sat at their ceilings only until calibration, which is now done.
- The pre-existing `stop()`/`start()` race when `join(1000)` times out, which isn't in this diff.
The reviewer's note that the title-screen tick case doesn't include `StutterHooks.tick` led to the new
`tickHookNsPerCallWorld`.
An earlier mutation check (halving a known thread's baseline) also fails the equivalence test.

## Verification

- `./gradlew build` passes on 26.2 and 26.3.
- CI is green on both calibration rounds. Round 1, on `85bdcec`: 36233278032, 36233279951, 36233285459. Round 2, on
  `8b44741`: 36234193894, 36234196318, 36234198557. Each run is 9 jobs, including all 3 game-test legs.
- The footprint artifacts were downloaded and read. The footprint-tools screenshots are unchanged by this workstream;
  I looked at one, 640x480, and it's fine.
- Two local 26.2 runs of FootprintGameTest alone passed under the machine-wide game-test lock, through a temporary edit
  of the game-test entrypoint list that was reverted in the same command.

## UNVERIFIED

- Why the sampler costs 20-51 ms per 60 s on CI when its JDK calls cost 2-7 ms. My hypothesis is thread wake-up with
  cold caches on a saturated 4-vCPU runner; it isn't measured. Locally, Windows' 15.6 ms CPU steps hide it.
- The battery path of PowerWatcher: CI has no battery, so only `isRunning()` false with no battery is checked in game.
  PowerWatcherTest (WS-P) covers the battery path with fakes.
- Per-frame costs depend on the runner's clock: the phase-timer case measured 152-171 ns on one runner type and
  238-256 ns on another. A slower future runner could come near the 400 ns ceiling.
- The monitor's cost with Distant Horizons or many more threads: CI has 35-59 threads, and the sampler's cost grows with
  the thread count.
