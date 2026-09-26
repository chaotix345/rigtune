# P5-A findings (Phase 5 real-run proofs, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6). Evidence folders are next to this file.

## P5A-F1 (low): the dev machine's real AMD Vulkan driver string parses to UNKNOWN (AC9.8)
- **Repro:** 26.3, forced Vulkan (`--graphicsBackend vulkan`), AMD Radeon RX 7800 XT, Adrenalin 26.8.1, Windows 11. MC logs
  `Using graphics backend Vulkan, using drivers: 1.4.349 AMD proprietary driver 26.8.1 (LLPC)`; RigTune's
  `GpuInfo.driverVersion` is that same string. `DriverVersionParser.parse(AMD, VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)")`
  (the RC jar's own class, `p5a-tools/ParseDriver.java`) → `family=unknown comparable=[]`. The same PC's GL string
  `3.3.0 Core Profile Context 26.8.1.260810` → `adrenalin [26, 8, 1]`.
- **Evidence:** `drivers/README.md`, `drivers/vk263-log-excerpt.txt`, `drivers/p5a-vk263.json`, `drivers/parse-results.txt`.
- **Cause:** by design so far: on Vulkan only the NVIDIA and Mesa sub-parsers read the driverInfo part, and
  DriverVersionParserTest asserts the AMD proprietary form (`1.3.296 AMD proprietary driver 24.12.1 (...)`) is UNKNOWN
  ("The API version is never the driver version"). The capture shows the number after "AMD proprietary driver" IS the
  Adrenalin version (26.8.1, identical to GL's `Context 26.8.1`), so AC9.8's "the 26.3 Vulkan capture parses to the
  expected family" is not met.
- **Impact:** low. It fails closed: a future `driverVersion {vendor: amd}` rule would stay UNKNOWN on AMD + Vulkan (no AMD
  driver rule is seeded in v0.4). The hardware-change notice still works on Vulkan (raw strings on the same backend are
  compared), but it would print the raw strings instead of "26.8.1 → 26.9.1".
- **Suggested fix / test vector (not applied; no product-code changes here):** on Vulkan, read AMD's driverInfo
  `AMD proprietary driver (\d+)\.(\d+)\.(\d+)` as adrenalin, and change the test to
  `parses(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)", DriverVersion.ADRENALIN, 26, 8, 1);`
  (and flip the existing `1.3.296 AMD proprietary driver 24.12.1 (...)` assertion to adrenalin [24, 12, 1]).
  Or, if AMD Vulkan stays unsupported in v0.4, keep UNKNOWN and add the captured string as an UNKNOWN vector with a
  comment, and amend AC9.8's wording.

## P5A-F2 (medium): after a teleport into new terrain the Stutter Doctor attributes nothing to chunk loading/building (AC5.8 C)
- **Repro:** 26.2, Sodium 0.9.2 (default Chunk Updates = Deferred/`ALWAYS`), RD 12, G1 -Xmx4G, dev PC (7800X3D). The product's
  own script `-Drigtune.dev.stutterScript=teleport` (run C1 with default options; run C3 in a freshly created benchmark
  world with VSync off/uncapped), and the driver variant C4 (100 s still, `tp @a -300000 200 300000`, 30 s, save, 10 s).
- **Result:** every post-teleport hitch is "rendering (low)" and unexplained: C1 14 spikes, "not explained 100 %";
  C3 12 spikes, "garbage collection 6 %; not explained 94 %"; C4 18 spikes (worst 86 ms), "none measured; not explained
  100 %". No hitch is attributed to chunk loading or chunk building. No false GC claims (the GC shares are pauses
  overlapping the frames), and the remainder is shown, as required.
- **Why (measured in C4 with the driver's `probe` action, which reads RigTune's own `BuildBacklog` values every 5 ticks
  for 30 s after the teleport):** client chunk loads rose 637 in the first 12 s (`ClientChunkEvents.CHUNK_LOAD`), but
  Sodium's builder read `scheduled 0, busy 0, total 10` in all 120 samples: on this CPU the 10 builder threads keep up
  with the integrated server's generation rate, so the "backlog" evidence (`scheduled > 0 && busy >= total`) never
  appears; with Chunk Updates = Deferred `deferModeWaits` is false; and no chunk-load claim was made, so (by Attributor's
  rule) the spike frames' packets-phase excess stayed under `MIN_PACKETS` or they had no chunk loads. So Attributor's render-phase rule (ws-s decision 2: render claims only with evidence) leaves the time unexplained. The
  counters themselves are the right ones (javap: `ChunkBuilder.getScheduledJobCount` = queue size, `getBusyThreadCount` =
  the workers' AtomicInteger).
- **Evidence:** `stutter/README.md` (runs C1, C3, C4), `stutter/C-teleport/` (logs with the probe lines, stutter.json, screenshots).
- **Impact:** the AC's expected attribution ("chunk loading/building, chunk loads > 0, a Sodium backlog > 0") is not met on
  a fast PC; the report stays honest ("not explained") but can't name the likely cause of the most common real-world
  hitch (entering new terrain). No wrong claim is made.
- **Options (not applied):** (a) amend AC5.8 C to accept "not explained + after teleport tag" when no backlog is measured;
  (b) add a correlational tag that never claims ms, e.g. "chunks were loading (not measured)" when chunk loads in the
  frame's window > 0 after a teleport, like the save/DH tags; (c) revisit the render-phase evidence (e.g. Sodium's upload
  queue on the render thread) in a later version.

## P5A-F3 (low, observation): a benchmark run with the session monitor on saves a tiny noisy session
- With the monitor on, a Measure run's settle frames (between the 10 s world-loading exclusion and the first sweep, when
  the benchmark pauses the session) are saved as their own session: run ovh-on-1 logged
  `Stutter Doctor: session saved (OK): 33 spikes in 2 s of gameplay`. StutterScreen then shows that 2-second session
  ("33 spikes … Not enough data yet") as the latest one after a benchmark. Harmless (no advice without enough data), but
  noisy. Suggestion: don't save a session with under ~10 s of gameplay, or pause the session from the benchmark's start
  rather than its first sweep. Evidence: `stutter/F-overhead/` (log excerpt in README).

## P5A-F4 (low): Modrinth lookups share the 2-thread `Probes.EXECUTOR` with settings saves and other UI work
- **Seen:** two local runs of `./gradlew :26.2:runProductionClientGameTest -PgametestJvmArgs="-XX:+UseZGC -XX:+ZGenerational
  -Dusing.aikars.flags=x"` on the RC stopped in UiGameTest (`waitForSaved`: settings.json not written within 100 ticks,
  lines 365 and 380) while Modrinth's API answered `HTTP 502/503` and timed out (`HttpTimeoutException: request timed
  out`, same logs). `jvm/local-jvm-zgc-26.2.txt`, `jvm/local-jvm-zgc-2-26.2.txt`.
- **Cause (code reading):** `RigTuneSettingsScreen.save()` runs `settings.save` on `Probes.EXECUTOR`
  (`Executors.newFixedThreadPool(2)`), and so does `RealController`'s `OnlineDataFetcher.fetchAll` (blocking HTTP, 10 s
  connect / 20 s request timeouts per request), plus report rebuilds, History loading, Undo plans, stutter.json I/O and
  awareness commits. When a slow or failing Modrinth holds both threads, those wait for the network timeouts.
- **Impact:** low: nothing is lost, but during a Modrinth outage the settings file, History ("loading…"), Undo plans and
  report rebuilds can lag by up to the HTTP timeouts. The game test's 5 s wait is what exposed it.
- **Suggestion:** give network lookups their own executor (or a dedicated single thread), keeping `Probes.EXECUTOR` for
  short local work; and/or let UiGameTest wait longer. Not a v0.4 regression check: the same executor sharing may exist in
  0.3.0 (not checked).
