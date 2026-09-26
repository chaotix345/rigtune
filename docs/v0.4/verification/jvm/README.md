# AC6.6: JVM findings from the running JVM + default vs Aikar's capped pairs (P5-A, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6). Machine: Ryzen 7 7800X3D, RX 7800 XT, 32 GB,
Windows 11, Temurin 25.0.4.1+1. (`local-gametestJvmArgs-26.2.txt` is WS-J's earlier run on its branch.)

## (a) Findings detected from the running JVM

| run | command | result |
|---|---|---|
| production game tests, RC, attempt 1 (21:41) | `./gradlew :26.2:runProductionClientGameTest -PgametestJvmArgs="-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x"` | RigTune logged `RigTune: Java 25.0.4.1 (Eclipse Adoptium), ZGC (typed), 2 argument notes [-XX:+ZGenerational, -Dusing.aikars.flags]` at startup; the suite then **stopped in UiGameTest** (`waitForSaved`, settings.json not written within 100 ticks) before JvmGameTest ran (`local-jvm-zgc-26.2.txt`) |
| same, attempt 2 (22:00) | same | same detection line; stopped in UiGameTest at another `waitForSaved` (`local-jvm-zgc-2-26.2.txt`) |
| same, attempt 3 (00:14, on test/p5-features @ 135e586 = feat/v0.4.0 @ 67f8b8c + evidence: includes the SettingsSaver fix) | same | UiGameTest passed; **JvmGameTest passed**: "running JVM: … ZGC typed=true, heap 7964 MB, notes [IGNORED -XX:+ZGenerational, SERVER_SET -Dusing.aikars.flags]", "**AC6.6 detection from the running JVM passed**", "the bundled rules fire [advice:jvm-ignored-flags]"; the suite then stopped in FootprintGameTest (`rigtuneClassBytesIdle: 120224 > 109296`: ZGC has no compressed oops, so shallow sizes are ~1.65× the G1-calibrated budget; P5A-F5, test harness) (`local-jvm-zgc-3-26.2.txt`, `footprint-26.2-OpenGL-zgc.json`) | **PASS** (JvmGameTest) |
| production client + driver (22:42) | `e2eClient` on a scratch instance with the JVM arguments `-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x` (no `-Xmx`: 7,964 MB default) | `controller.jvmReport()` = `collector=ZGC, collectorTyped=true, findings=[IGNORED -XX:+ZGenerational, SERVER_SET -Dusing.aikars.flags], facts=[jvm-gc-typed, jvm-gc-zgc, jvm-server-flags, jvm-ignored-flags, jvm-probed]`; JvmScreen shows "Garbage collector: ZGC, set in your Java arguments", both findings with their reasons, and the fired advice `jvm-ignored-flags` with "Found in your Java arguments: -XX:+ZGenerational." (`p5a-jvmzgc-jvmscreen.png`, `-2.png`, `p5a-jvmzgc.json`, `jvmzgc-driver-log-excerpt.txt`). `jvm-server-flags` advice correctly does not fire (it's for PCs with ≤ 16 GB RAM; this one has 32 GB) | **PASS** |

**Why UiGameTest stopped (not RigTune's JVM detection):** both attempts ran while Modrinth's API was failing
(`POST /v2/version_files[/update] returned HTTP 502/503`, `HttpTimeoutException: request timed out` in the same logs).
RigTuneSettingsScreen saved settings.json asynchronously on `Probes.EXECUTOR` (2 threads), which also runs the Modrinth
lookups (20 s request timeout), so during the outage the save waited past the test's 5 s (P5A-F4). The coordinator merged
a fix (settings.json saved on its own thread, 7adc59a); attempt 3 on that code passed UiGameTest.

## (b) 3 interleaved pairs, G1 default vs Aikar's set, capped at 144 FPS

**Method.** Production 26.2 client + Sodium 0.9.2 + the RC jar on one scratch instance: RD 16, SD 12, `maxFps:144`
(26.2 rounds it to its 10-FPS step: **the effective limit logged was 140**), VSync off, inactivity limit "minimized"
(no AFK throttle), 1280×720 window. The product's benchmark always uncaps the frame rate, so the capped measurement is
the driver's own (`capped:4:20:10`): in RigTune's benchmark world (fixed seed, camera at 0,192), wait 20 s for
terrain, turn the camera for a 10 s warm-up, then record every frame (`DebugScreenOverlay.logFrameDuration`, the same hook
RigTune records from) for 4 sweeps × 20 s = 80 s while turning 360° per sweep, the protocol of jvm-gc.md §4.1/§4.3.
Stats use RigTune's `FrameStats.of` (1 % low = mean of the slowest 1 %). GC counts/times are the GC beans' deltas over
the recorded window; `pairs/<run>-gc.log` is `-Xlog:gc,safepoint` for the whole run.
Configurations: **G1 default** `-Xmx4G`; **Aikar's set** = `-Xms4G -Xmx4G` + the 19 flags + 2 markers exactly as
research `cfg/aikar.txt` (jvm-gc.md §4.1). Order: a discarded warm-up (G1, it created the world), then
g1, aikar, aikar, g1, g1, aikar (one client at a time, the lock released between runs).

| run | frames | avg FPS | 1% low FPS | p99 ms | max ms | frames > 8 ms | > 16.7 ms | GC in window (bean deltas) | heap committed MB |
|---|---|---|---|---|---|---|---|---|---|
| jvm-g1-1 | 11128 | 139.09 | 125.84 | 7.70 | 10.13 | 21 | 0 | Young: 4, 9 ms; Concurrent: 2, 5 ms | 1044 |
| jvm-aikar-1 | 11131 | 139.13 | 127.21 | 7.62 | 8.95 | 23 | 0 | Young: 1, 4 ms | 4096 |
| jvm-aikar-2 | 11134 | 139.16 | 125.80 | 7.66 | 12.24 | 25 | 0 | Young: 1, 4 ms | 4096 |
| jvm-g1-2 | 11128 | 139.09 | 126.09 | 7.75 | 8.29 | 24 | 0 | Young: 3, 10 ms; Concurrent: 2, 5 ms | 976 |
| jvm-g1-3 | 11129 | 139.11 | 125.71 | 7.71 | 13.48 | 21 | 0 | Young: 4, 9 ms; Concurrent: 2, 7 ms | 898 |
| jvm-aikar-3 | 11131 | 139.15 | 126.62 | 7.69 | 8.24 | 19 | 0 | Young: 1, 4 ms | 4096 |
| (jvm-warm, discarded) | 11129 | 139.11 | 126.16 | 7.64 | 11.23 | 22 | 0 | Young: 5, 14 ms; Concurrent: 4, 7 ms | 1100 |

| | G1 default (n = 3) | Aikar's set (n = 3) |
|---|---|---|
| 1 % low FPS, mean (range) | **125.88** (125.71-126.09) | **126.54** (125.80-127.21) |
| frames > 8 ms, total (per run) | **66** (21, 24, 21) | **67** (23, 25, 19) |
| p99 frame ms, mean | 7.72 | 7.66 |
| average FPS, mean | 139.10 | 139.15 |
| frames > 16.7 ms | 0 | 0 |
| heap committed | 0.9-1.0 GB | 4.0 GB (all of `-Xmx`) |

**Verdict: PASS.** The default's 1 % low is 0.5 % below Aikar's set (125.88 vs 126.54), inside the runs' own spread
(Aikar 125.80-127.21 overlaps every G1 run) and far inside item 7's noise floor (2 × max(cv or 5 %, …) ≥ 10 %). Frames
over 8 ms: 66 for the default vs 67 for Aikar's set, so the default is no worse. It matches jvm-gc.md §4.3's capped G1
row (139 / 126 / p99 7.63 / 23 frames > 8 ms) and the `jvm-server-flags` wording ("the same frame rates and 1 % lows as
Java's defaults", "all 4096 MB of a 4 GB allocation"): no wording change needed.
