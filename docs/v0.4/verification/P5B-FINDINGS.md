# P5-B findings (Phase 5 local game tests and production smokes)

RC code: `feat/v0.4.0` @ 9cf84f6 (a3f5c14 adds only a PROGRESS line), branch `test/p5-smokes`. **No product bug that blocks the release.**
- F1 and F2 are in the test harness (gametest source set), found on the dev machine; both were reported to the coordinator when found.
- F3-F5 are LOW UX notes on shipped screens.
- F6 is pre-existing rules wording.

## F1. MEDIUM (test gate, local Windows only): FootprintGameTest's render-thread budgets go red locally without a real regression

- **Repro:** `./gradlew :26.3:runClientGameTest` on Windows 11 (Ryzen 7 7800X3D) failed twice at FootprintGameTest, the second-to-last class; every earlier class had passed. Launch 3 passed.
  - Launch 1: `AssertionError: footprint budget renderThreadInitCpuMs: 156.25 > 150`.
  - Launch 2: `renderThreadInitWallMs: 507.79 > 368` and `clientStartedWallMs: 166.69 > 141`.
- **Evidence, CPU ([gametests/26.3-attempt1-footprint.json](gametests/26.3-attempt1-footprint.json)):**
  - preLaunch CPU 46.88 ms + init CPU 109.38 ms = 156.25 ms, against a render-thread **wall** time of 140.01 ms for the same two phases. A single thread can't use more CPU than wall time.
  - Every local CPU value is a multiple of 15.625 ms (15.63, 31.25, 46.88, 62.5, 93.75, 109.38, 156.25). The production smokes log the same pattern: "preLaunch + init 37.9 ms on the render thread (CPU 46.9 ms)".
- **Evidence, wall ([gametests/26.3-attempt2-footprint.json](gametests/26.3-attempt2-footprint.json)):**
  - init wall 377.89 ms against init CPU ≤ 31 ms: the render thread wasn't running for about 350 ms of `onInitializeClient` (preLaunch: 129.91 ms wall, 31.25 ms CPU).
  - `onInitializeClient` has no lock or blocking call. It is class loading plus registrations (`RigTuneClient.java:70-104`), and in launch 1 the same phases took 103 ms wall with the CPU matching. So this reads as the thread being descheduled or waiting on I/O on a busy shared machine (the user's server, syncthing, other agents' builds). I didn't prove the cause. SPEC F-M2 already calls wall "a loose backstop".
- **Cause (CPU):** `FootprintStats` reads `ThreadMXBean.getCurrentThreadCpuTime()`, which comes from GetThreadTimes on Windows at the scheduler-tick resolution (15.625 ms). The sum of two readings can be over by up to about 31 ms.
- **CI isn't affected:** Linux has nanosecond resolution and CI is the gate. The latest green run 36236205018 reads 98.5-100.2 ms CPU and 110-153 ms wall.
- **Impact:** a failing-mode gate that can fail a local Windows game-test run while RigTune's real cost is within budget. Product not affected.
- **Suggested:**
  - Locally (or when `os.name` is Windows), use min(cpu, wall) for the CPU budget, or skip the CPU budget when the timer resolution is coarse.
  - Accept that local wall numbers are noise.
  - Or accept both and retry local runs, as I did.

## F2. LOW (test harness): ServerLimitsGameTest bound port 25565, which a server already running on the machine holds

- **Repro:** `./gradlew :26.2:runClientGameTest` while the user's own `fabric-server-launcher.jar` (PID 36592) listened on 25565. The test's dedicated server logged `FAILED TO BIND TO PORT!`, and the game crashed with "The server crashed" ([gametests/26.2-try1-port-conflict.txt](gametests/26.2-try1-port-conflict.txt)).
- **Cause:** `TestWorldBuilder.createServer(Properties)` uses server.properties' default port. `connect()` then dials `localhost:` + `MinecraftServer.getPort()` (javap of fabric-client-gametest-api-v1 6.0.2).
- **Fix (test-only, on this branch, for the coordinator to take):** ServerLimitsGameTest sets `server-port` to a free port (`new ServerSocket(0)`). With it, 26.2 passed on the next launch and 26.3 passed too. CI behaviour is unchanged: the test connects to whatever port it chose.

## F3. LOW (wording): "1 spikes (…) in 1 hitches"

- **Where:** the Stutter Doctor header with one spike (26.3 game test, [gametests/img/a263-stutter-saved.jpg](gametests/img/a263-stutter-saved.jpg)).
- **Cause:** `rigtune.stutter.header.spikes` = "%s spikes (%s minor, %s major, %s severe, %s freezes) in %s hitches, %s s lost" has no singular form.
- **Suggested:** separate one/many keys, as `rigtune.awareness.whats_new.one`/`.many` already do.

## F4. LOW (layout, 640x480@2): the benchmark result screen clips most of its status lines

- **Where:** [gametests/img/a263-bench-result-640x480.jpg](gametests/img/a263-bench-result-640x480.jpg).
- **What:** six of the lines end in "…" at the smallest size, among them "Results were noisy (8% spread): close background apps a…", "Stutter Doctor: 122 spikes during the sweeps; no cause me…" (new in v0.4), and "Distant Horizons off: … (a report on…". Next to the trend chart the table's header runs together ("DistanceAvg FPS1% low Target").
- **Cause:** `BenchmarkResultScreen.clip` has no tooltip for a clipped line. The narrow-table column split (`drawTable`, 30/58/84 %) is unchanged since 0.3; v0.4 adds the trend and stutter lines above the table.
- **Suggested:** wrap or tooltip the clipped lines; drop a column label to its short form next to the chart.

## F5. LOW (UX): the driver-changed notice is always clipped inline

- **What:** "Your GPU driver changed since last time (%s → %s)" puts both raw driver strings in the one notice row. With the dev machine's real string ("3.3.0 Core Profile Context 26.8.1.260810") the message is clipped even at 1280x720@2 with inline buttons ([gametests/img/a262-driver-notice-1280x720.jpg](gametests/img/a262-driver-notice-1280x720.jpg) shows it with a short seeded string).
- **Mitigation already there:** the full text is in the hover tooltip and in NoticeScreen.
- **Suggested:** show the parsed version (`DriverVersion`: "Adrenalin 26.8.1 → 26.9.1") when it parses, and the raw strings only in the detail.

## F6. LOW (pre-existing wording, since v0.2): "Disable LambDynamicLights" says "entry-level hardware" on a memory-limited tier

- **Where:** S2 (26.2 smoke with a 2 GB heap: tier 2/5, "lowest estimated component: memory").
- **What:** RigTune offers "Disable LambDynamicLights … costs CPU time that entry-level hardware is short of", on a Ryzen 7 7800X3D (CPU tier 5).
- **Cause:** the rule keys on the effective tier. It has been in knowledge.json since v0.2 (662c9aa9), so it isn't a v0.4 regression.
- **Suggested:** word it by the effective tier ("on this setup") or condition it on the CPU tier.

## Environment notes (not findings)

- The **26.3 native crash** (`NTSTATUS 0xC0000005` before "OpenAL initialized") hit 2 of 4 production smoke launches; the pass used `ALSOFT_DRIVERS=null`. It hit 0 of 3 dev game-test launches. See [smokes/26.3-modrinth/README.md](smokes/26.3-modrinth/README.md).
- The **world-exit hang** with Distant Horizons or Xaero's World Map loaded happened in every smoke that had them (S1, s3a, s3d). It's the harness's phaser (jstack in [smokes/26.2-user-mods/s1-exit-hang-jstack.txt](smokes/26.2-user-mods/s1-exit-hang-jstack.txt)), not RigTune.
- **Modrinth** had transient failures during the 26.3 smokes: a connect timeout and an SSL handshake reset at 21:35, and an HTTP 502 at 21:56. RigTune fell back to offline data with one WARN each and no stack trace.
- **CI flake, not from this branch:** `UiGameTest.checkSettings` → `waitForSaved` (UiGameTest.java:412, 100 ticks) timed out on both 26.3 legs of run 36241924437 attempt 1, and on fix/review-7's run 36240897813. The re-run passed. The same class passed in all 5 local launches.
