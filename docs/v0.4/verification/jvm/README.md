# AC6.6: JVM findings from the running JVM + default vs Aikar's capped pairs (P5-A, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6). Machine: Ryzen 7 7800X3D, RX 7800 XT, 32 GB,
Windows 11, Temurin 25.0.4.1+1. (`local-gametestJvmArgs-26.2.txt` is WS-J's earlier run on its branch.)

## (a) Findings detected from the running JVM

| run | command | result |
|---|---|---|
| production game tests, RC, attempt 1 (21:41) | `./gradlew :26.2:runProductionClientGameTest -PgametestJvmArgs="-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x"` | RigTune logged `RigTune: Java 25.0.4.1 (Eclipse Adoptium), ZGC (typed), 2 argument notes [-XX:+ZGenerational, -Dusing.aikars.flags]` at startup; the suite then **stopped in UiGameTest** (`waitForSaved`, settings.json not written within 100 ticks) before JvmGameTest ran (`local-jvm-zgc-26.2.txt`) |
| same, attempt 2 (22:00) | same | same detection line; stopped in UiGameTest at another `waitForSaved` (`local-jvm-zgc-2-26.2.txt`) |
| same, attempt 3 | same | see "Attempt 3" below |
| production client + driver (22:42) | `e2eClient` on a scratch instance with the JVM arguments `-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x` (no `-Xmx`: 7,964 MB default) | `controller.jvmReport()` = `collector=ZGC, collectorTyped=true, findings=[IGNORED -XX:+ZGenerational, SERVER_SET -Dusing.aikars.flags], facts=[jvm-gc-typed, jvm-gc-zgc, jvm-server-flags, jvm-ignored-flags, jvm-probed]`; JvmScreen shows "Garbage collector: ZGC, set in your Java arguments", both findings with their reasons, and the fired advice `jvm-ignored-flags` with "Found in your Java arguments: -XX:+ZGenerational." (`p5a-jvmzgc-jvmscreen.png`, `-2.png`, `p5a-jvmzgc.json`, `jvmzgc-driver-log-excerpt.txt`). `jvm-server-flags` advice correctly does not fire (it's for PCs with ≤ 16 GB RAM; this one has 32 GB) | **PASS** |

**Why UiGameTest stopped (not RigTune's JVM detection):** both attempts ran while Modrinth's API was failing
(`POST /v2/version_files[/update] returned HTTP 502/503`, `HttpTimeoutException: request timed out` in the same logs).
RigTuneSettingsScreen saves settings.json asynchronously on `Probes.EXECUTOR` (2 threads), which also runs the Modrinth
lookups (20 s request timeout), so during the outage the save waited past the test's 5 s. P5-B's local 26.2 game-test
run earlier the same evening (network healthy) passed UiGameTest, and CI is green. Recorded as an environment failure;
the save latency itself is noted in `../P5A-FINDINGS.md` (P5A-F4, low).

## (b) 3 interleaved pairs, G1 default vs Aikar's set, capped at 144 FPS

(filled in below)
