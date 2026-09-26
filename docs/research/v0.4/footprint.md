# RigTune's own footprint (P1 item 10, P2 item 13)

Research for v0.4.0. Answers: what RigTune costs at startup and per-frame/per-tick today; measured
with-vs-without startup numbers on 26.2; a CI-safe way to gate a regression; and whether Fabric Loader can
attribute per-mod init cost (P2 item 13).

**Bottom line up front:**
- Locally on 26.2 (AMD Ryzen 7 7800X3D / RX 7800 XT, Windows 11, cold `runProductionClientGameTest` launches),
  launch-to-title-screen with RigTune loaded vs excluded (same jar/classpath, RigTune toggled via Fabric
  Loader's own `-Dfabric.debug.disableModIds=rigtune`) differs by roughly **+170 to +250 ms** (with slightly
  slower), against a **run-to-run spread of up to ~2.2 s** in either arm. The delta's sign was consistent
  across two independent sessions but its size is well inside the noise band — **not safely distinguishable
  from zero** at this sample size, and it would be hopeless on a shared GitHub runner.
- This matches the code: RigTune's only synchronous startup work is a `preLaunch` file-state check and a few
  small JSON reads/object constructions in `onInitializeClient()`. The expensive parts — the OSHI hardware
  probe, the mod scan, the rules fetch, Modrinth lookups — are all dispatched to background executors and
  are **not on the path to the first frame**.
- So the CI guard should not assert on launch-to-title wall time at all. It should instrument **RigTune's own
  work** directly: render-thread wall/CPU time of the synchronous init path, a microbenchmark of the per-frame
  hook (currently one boolean check; a nanoTime+array-store ring buffer once the session monitor ships), and a
  retained-heap class histogram of `io.github.chaotix345.rigtune.*` via the `DiagnosticCommand` MBean — which
  I verified works in-process on Java 25 with no `jcmd` and no external process (~6 ms call latency).
- Fabric Loader (0.19.5) has **no per-mod entrypoint or mixin timing** anywhere in its own code (verified by
  disassembling the shipped jar — see §4). P2 item 13 is therefore a trend-only report plus general advice,
  not a per-mod breakdown.

Worktree used for all experiments: `C:/Dev/Worktrees/rigtune-r-foot` (branch `research/footprint`, off
`feat/v0.4.0` @ `0ad889c`). Not committed or pushed — left for the coordinator; see §6 for exactly what's
in it.

---

## 1. What RigTune does today

### 1.1 Entrypoints (`src/main/resources/fabric.mod.json`)

```json
"entrypoints": {
  "preLaunch": ["io.github.chaotix345.rigtune.client.RigTunePreLaunch"],
  "client":    ["io.github.chaotix345.rigtune.client.RigTuneClient"],
  "modmenu":   ["io.github.chaotix345.rigtune.client.compat.ModMenuIntegration"]
}
```

- **`RigTunePreLaunch` (preLaunch)** — `src/client/java/io/github/chaotix345/rigtune/client/RigTunePreLaunch.java:29`.
  Runs synchronously **before the game window exists**, so this is genuinely on the startup critical path.
  It: tries a non-blocking `ApplyLock` acquire (only waits, up to 5 s, if a *previous* run's apply helper is
  still alive — the common case is instant); reads `last-apply-result.json` and `pending.json` if present
  (small JSON files); runs `HistoryStartup.run` (journal reconciliation, bounded file I/O). No network, no
  OSHI. In the common case (no leftover helper, small/no journal) this is low-single-digit-ms file I/O.
- **`RigTuneClient` (client)** — `onInitializeClient()`, `src/client/java/io/github/chaotix345/rigtune/client/RigTuneClient.java:66`.
  Synchronous work, all cheap, no network/OSHI: `ChangeRecorder.install`, `new RealController()` (constructs
  `GatedModrinthClient`/`HttpModrinthClient` — object construction only, no I/O; `ClientSettings.shared` and
  `ClientState.shared` — two small JSON reads under `config/rigtune/`; `staged.recount(pendingFile)` — an
  existence check), one `KeyMapping` registration, and five Fabric API event registrations (`CLIENT_STARTED`,
  `CLIENT_STOPPING`, `END_CLIENT_TICK`, `ScreenEvents.AFTER_INIT`, one `HudElementRegistry` attach). All of
  this is object construction plus a handful of tiny file reads — no loops over user data, no network.
- **`ModMenuIntegration` (modmenu, optional)** — trivial, returns a screen factory; costs nothing unless Mod
  Menu is installed and the player opens it.

### 1.2 The expensive work is asynchronous and off the critical path

`RealController.start(minecraft)` (`RealController.java:144`) runs on `ClientLifecycleEvents.CLIENT_STARTED`
and immediately returns after calling `reloadRules()` and `rescan()` — both of which only *dispatch*
`CompletableFuture`s onto background executors and return without blocking the render thread:

- **`Probes.EXECUTOR`** (`client/probe/Probes.java:7`) — a fixed pool of **2 daemon threads named "RigTune
  worker"**. Runs the OSHI hardware probe (`HardwareProbe.probeSlow()`, memoized once per JVM via a static
  `CompletableFuture`), the mod scan (`ModScanner.scanAsync()`), the launcher probe, the report builder
  (`Recommender.recommend`), and Modrinth fetches (`OnlineDataFetcher`).
- **`RealController.RULES_EXECUTOR`** (`RealController.java:82`) — **1 daemon thread named "RigTune
  rules"**. Runs `RulesSources` loads one at a time (a newer load supersedes a stale in-flight one). This can
  hit the network (`https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/`, `RulesSources.java:18`)
  and the code's own comment says it "can wait up to a minute" — but that wait happens entirely off the
  render thread.
- **OSHI** (`client/probe/HardwareProbe.java:145`, `probeSlow()`) — `new SystemInfo().getHardware()`, CPU
  identifier, RAM, battery, GPU cards. Runs once per JVM on `Probes.EXECUTOR`, wrapped in per-field
  `try {...} catch (Throwable)` so a slow/failing OSHI call can't crash the scan. The *fast* part
  (`probeFast()` — GPU device info via `RenderSystem.tryGetDevice()`, window/monitor size) runs synchronously
  on the render thread inside the same async pipeline, but it's local queries only, no I/O.
- **Modrinth lookups** (`fetchOnline()`, `RealController.java:254`) — gated by `settings.modrinthAllowed()`
  and only fired once mods+rules+hardware are all ready (`OnlineLookupGate`); also async on `Probes.EXECUTOR`.

Net effect: the only things that can plausibly move launch-to-title are the `preLaunch` file I/O and the
handful of small JSON reads/object constructions in `onInitializeClient()`. That's consistent with what §2
measured: a delta too small to separate from run-to-run noise.

### 1.3 Every frame

One mixin total: `client/mixin/DebugScreenOverlayMixin.java` injects at `HEAD` of vanilla's
`DebugScreenOverlay.logFrameDuration`, which vanilla calls **every rendered frame** (it feeds the F3 frame-time
chart regardless of whether F3 is open). The injection calls `FrameTimes.onFrame(long nanos)`
(`client/benchmark/FrameTimes.java:31`):

```java
public static void onFrame(long nanos) {
    if (recording) {                       // <-- the only cost when idle: one field read + branch
        synchronized (FrameTimes.class) {
            if (recording) {
                RECORDER.add(nanos);
            }
        }
    }
}
```

`recording` is only `true` during the ~1-minute in-game benchmark (`BenchmarkController`). Outside of that,
per-frame cost is one boolean read and an untaken branch: no allocation, no synchronization entered. This is
the *existing* frame hook; §5 covers what changes once the new opt-in session monitor (P1 item 10b) uses the
same hook continuously during normal play.

`FrameRecorder` (`core/benchmark/FrameRecorder.java`) backing `FrameTimes` is **not** a ring buffer — it's a
plain growable `long[]` that doubles on overflow (`Arrays.copyOf`), sized for a single bounded benchmark run
(4096 initial capacity). It must not be reused as-is for a monitor that runs for a whole play session (see
§5.1).

### 1.4 Every tick

`RigTuneClient.onTick` (registered on `ClientTickEvents.END_CLIENT_TICK`, `RigTuneClient.java:140`) runs on
every client tick (nominally 20/s): `BenchmarkController.tick(minecraft)`, an `openKey.consumeClick()` loop
(Fabric API keybind check), one `instanceof RigTuneScreen` check, and (only relevant for the first few ticks
after the title screen is first seen) the one-time notices/toast logic gated by `titleSeen`/`noticesShown`
booleans. `BenchmarkController.tick` (`client/benchmark/BenchmarkController.java:383`) delegates to
`DevAutorun.tick`, `MarkerRestore.tick`, `BenchmarkWorld.tick` — I read all three: each is a cheap early-return
(a null/enum check, or a boolean cached from a single `Files.isRegularFile` on the very first tick). Steady
per-tick cost outside of an active benchmark/notice window is a handful of field reads and `instanceof`
checks — no allocation.

---

## 2. Measured: launch-to-title, with vs without RigTune (26.2, local)

### 2.1 Method

The obvious way to get "with vs without RigTune" is two different mod sets, but that reintroduces build/jar
variance as a confound. Instead I used Fabric Loader's own debug switch,
**`-Dfabric.debug.disableModIds=<id,id,...>`** (verified by disassembling `ModDiscoverer.findDisabledModIds()`
in the shipped `fabric-loader-0.19.5.jar` — it filters mod candidates out at discovery time, before mixins or
entrypoints are registered for that id). This runs the *exact same* jar/classpath/JVM/mods-folder for both
arms; only RigTune's presence changes. I confirmed the exclusion actually took effect by grepping the run
log's `Reloading ResourceManager:` line for the mod-id list (present with the flag off, absent with it on) and
by confirming no `RigTune worker` / `Hardware:` log lines appear when disabled.

"Title screen reached" is measured from **inside the game**, so it's immune to Gradle's own startup overhead:
a small new client-game-test class (`FootprintProbe`, no RigTune imports, so it keeps working with RigTune
excluded) does `context.waitForScreen(TitleScreen.class)` then logs
`ManagementFactory.getRuntimeMXBean().getUptime()` — JVM-start-relative milliseconds — via SLF4J. I replaced
the gametest module's `fabric.mod.json` entrypoint list with just this probe (dropping the `"depends":
{"rigtune": "*"}` it normally declares) so each run is a few-second boot instead of the multi-minute full
suite. This is a bigger, closer-to-real launch than a microbenchmark: full production jar, full resource/asset
load, real window creation — same class of measurement CI's `runProductionClientGameTest` does.

Command shape (from the worktree):
```
export JAVA_HOME=".../jdk-25.0.4.1+1"
./gradlew :26.2:runProductionClientGameTest                              # "with"
./gradlew :26.2:runProductionClientGameTest -PfootprintDisableMods=rigtune  # "without"
```
(`-PfootprintDisableMods` is a one-line addition to the `runProductionClientGameTest` task in the worktree's
`build.gradle`, mapping to the JVM arg above — research-only, not committed.)

Two interleaved batches (with/without alternating, run dir wiped between every run by the existing task), on
the actual game window (no Xvfb locally):

| Batch | n each | "with" median / mean / range (ms) | "without" median / mean / range (ms) |
|---|---|---|---|
| 1 | 6 | 14530 / 14782 / 14179–16296 | 14369 / 14485 / 13964–15125 |
| 2 | 4 | 14427 / 14496 / 14124–15006 | 14251 / 14306 / 14078–14645 |
| **combined** | **10** | **14517 / 14668 / 14124–16296** | **14343 / 14413 / 13964–15125** |

Raw data: `startup_results.csv`, `startup_results_batch2.csv` in this agent's scratchpad.

### 2.2 Reading it

Combined median delta ≈ **+174 ms**, mean delta ≈ **+254 ms** (RigTune slightly slower), i.e. roughly **1–2%**
of a ~14–16 s cold start. The sign was consistent across both independently-launched batches (RigTune never
came out faster), which is *some* evidence the delta is real and not pure coincidence — but its magnitude
(~150–300 ms) is small next to the **~1.1–2.2 s run-to-run range within a single arm**, on one quiet Windows
desktop with a warm disk cache. I would need on the order of several dozen runs per arm to get a confidence
interval that excludes zero, which wasn't a good use of the shared game-test client for a research pass. I'm
reporting this plainly rather than dressing it up: **RigTune's added launch-to-title cost, if it's real, is
on the order of a couple hundred milliseconds — and that is not a number you could safely gate CI on**, because
CI's shared runners have far more startup jitter (disk, CPU contention, JIT tiering differences run to run)
than this quiet desktop did. This directly motivates §3: gate RigTune's *own* instrumented cost, not
wall-clock launch time.

I did not attempt 26.3 locally per the environment note (vanilla 26.3 crashes natively on most local Windows
launches); 26.2 is architecturally identical for this question (same entrypoints, same mixin, same executors).

---

## 3. CI guard design

Absolute launch-to-title deltas are a dead end on shared runners (§2.2, and CI's own client-gametest legs
already run 4–5.5 minutes for the full suite with real variance — checked via `gh run view` on a recent run).
Instead, gate **RigTune's own work**, measured three ways, each validated for feasibility below.

### 3.1 (i) Instrumented init cost — wall + CPU time

Add timers around exactly the synchronous work identified in §1.1–1.2:
- Wrap `RigTuneClient.onInitializeClient()`'s body in `System.nanoTime()` start/end (wall) and
  `ThreadMXBean.getCurrentThreadCpuTime()` start/end (CPU, render thread) — expose the two deltas via a small
  static holder (e.g. `FootprintStats.initWallNanos`/`initCpuNanos`) that a `FootprintGameTest` can read.
- Separately, sample `ThreadMXBean.getThreadCpuTime(id)` for the "RigTune worker" and "RigTune rules" threads
  (found by name via `ThreadMXBean.getAllThreadIds()` + `getThreadInfo`) after a fixed wait window post
  `CLIENT_STARTED` (e.g. 5 real seconds, with the rules network fetch pointed at a local fixture URL via
  `-Drigtune.rules.baseUrl` so the test is deterministic and doesn't depend on GitHub being reachable from the
  runner — the property already exists, see `RulesSources.BASE_URL_PROPERTY`). This captures the OSHI probe +
  mod scan + first rules load + report build, bounded and reproducible.

**Proposed budgets** (generous margins over what §1 and code-reading suggest — a handful of tiny JSON
reads/object constructions):
- Render-thread synchronous init: **wall < 25 ms, CPU < 15 ms**.
- Background worker CPU (RigTune worker + RigTune rules combined, 5 s window, local rules fixture): **< 300 ms
  CPU total**.

These are first-pass numbers from code reading, not from a live instrumented run (that instrumentation
doesn't exist yet — it's what this feature adds). Flag to the implementer: capture the *actual* numbers from
the first few CI runs after adding the timers and tighten the budget from there, the same way the existing
`runProductionClientGameTest` job already treats a hard failure (missing backend line) as build-breaking
without needing a numeric baseline first.

### 3.2 (ii) Per-frame cost — microbenchmark, not an in-game measurement

A single boolean-check-or-nanoTime+array-store hook is far too small to detect inside a full game test: the
`nanoTime()` call itself costs ~20–30 ns, which is the same order of magnitude as the thing being measured, and
game-thread scheduling/GC noise on a shared runner would swamp it completely. Trying to measure this "live" in
`FootprintGameTest` would produce a flaky, meaningless number.

Instead, enforce it as a **tight-loop microbenchmark** (JUnit, not JMH — keeps it in the existing `test`
source set and CI job, no new tooling): call the hook (`FrameTimes.onFrame` today; the new session monitor's
equivalent once it exists) N = 10,000,000 times in a loop and assert:
- **Wall time budget**: total loop time < some generous ceiling (e.g. 200 ms for 10M calls ⇒ effectively a
  20 ns/call ceiling, ~10x the measured cost of the current idle-path boolean check plus interpretation
  overhead) — a large enough N averages out JIT warm-up and timer resolution.
- **Zero allocation**: wrap the loop in `ThreadMXBean.getCurrentThreadAllocatedBytes()` before/after (verified
  working on JDK 25, §3.3) and assert the delta is 0 once the ring buffer is pre-sized (see §5.1) — this is
  the strongest, lowest-noise check available, because it's exact (a byte counter), not a timing sample.

This gives two budgets per the feature spec: **Y ns/frame (a wall-time ceiling from the microbenchmark) and 0
bytes allocated**, enforced with a unit test instead of anything that touches the actual game client.

### 3.3 (iii) Retained heap — `DiagnosticCommand` MBean, verified in-process on Java 25

I wrote and ran a standalone Java 25 program (`diag-test/DiagTest.java`, portable JDK at
`C:/Dev/Tools/jdk/jdk-25.0.4.1+1`) to check which of the candidate approaches actually work **in-process**
(no `jcmd`, no forking a second JVM — important because CI needs this to run *inside* the same client-gametest
JVM that already has RigTune loaded):

| Approach | Works in-process on Java 25? | Notes |
|---|---|---|
| `com.sun.management:type=DiagnosticCommand` MBean, `gcClassHistogram` op | **Yes** | `server.invoke(diagName, "gcClassHistogram", new Object[]{new String[0]}, new String[]{"[Ljava.lang.String;"})` returns the full class-histogram text (same output as `jcmd <pid> GC.class_histogram`), ~6.3 ms call latency on a trivial heap, correctly found and counted test classes. This is the one to use. |
| `HotSpotDiagnosticMXBean.dumpHeap` | Available, not exercised further | Writes a `.hprof` file; needs an offline parser to get per-package byte totals. Much heavier than the histogram for no benefit here — skipped. |
| `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()` | **Yes** | Confirmed accurate on a controlled 1 MiB allocation. Used for §3.2's zero-allocation assertion. |
| `ThreadMXBean` CPU time (`isThreadCpuTimeSupported`, `getThreadCpuTime`) | **Yes**, supported | Used for §3.1. |

`FootprintGameTest` (or the probe) should, once the game reaches a steady idle state (title screen, report
built, no RigTune screen open), invoke `gcClassHistogram`, parse lines by class name for the
`io.github.chaotix345.rigtune.` prefix (module-qualified names in the output, e.g.
`io.github.chaotix345.rigtune.core.rules.RulesDocument (rigtune)`), sum the `#bytes` column, and assert under
budget. Repeat after opening-then-closing the RigTune screen once (UI leak check) and, once it exists, after
starting-then-stopping the session monitor (sizes the ring buffer specifically).

**Proposed budget: retained < 8 MB** for RigTune's own classes at idle. This is a *reasoned estimate*, not a
live measurement — I validated the mechanism (above) but did not get a live number from the actual running
mod: the machine-wide game-test lock was held by another agent (`r-jvm`) for the remainder of my window, and I
judged two rounds of contention-retries a worse use of the shared client than finishing this report on time.
The estimate comes from the bundled `rules-v2.json` being ~68 KB on disk (`rules/rules-v2.json`), which as a
parsed Gson object tree (nested maps/records, `HashMap` node overhead) is typically 3–8x its JSON size in
heap, i.e. very roughly 200–500 KB, plus small `HardwareProfile`/`Report`/`Recommendation` record graphs and
(once it ships) a ring buffer sized per §5.1 (tens to low hundreds of KB). **Open follow-up for whoever
implements this: run the histogram once for real against the built jar and tighten this number** — 8 MB has
enough headroom that it shouldn't need loosening, only tightening.

### 3.4 CI variance

I ran the local wall-clock measurement twice (§2.1, two independently-launched batches, gradle daemon and OS
disk cache both warm by batch 2) specifically to see how much the delta itself moves between sessions: median
delta was +162 ms (batch 1) vs +176 ms (batch 2) — consistent to within ~10%, even though each batch's *own*
within-arm spread was 500 ms–2.2 s. That's the core argument for §3.1–3.3: the *thing you're trying to detect*
(RigTune's own added cost) is small and comparatively stable across sessions on a quiet desktop, but it is
utterly swept up in the noise of "boot a whole JVM + Minecraft + Xvfb + Mesa on a shared runner", which is why
this design measures RigTune's own work directly instead of the total.

### 3.5 What CI uploads

A `FootprintGameTest` (new gametest class, or added to the existing `RigTuneClientGameTest` flow) writes a
small JSON artifact, e.g.:
```json
{
  "mcVersion": "26.2", "backend": "OpenGL",
  "initWallMs": 4.2, "initCpuMs": 2.1,
  "workerCpuMs5s": 38.7,
  "retainedBytes": 612480,
  "frameHookNsPerCall": 3.1, "frameHookAllocBytes": 0
}
```
uploaded the same way screenshots/logs already are (`actions/upload-artifact`, `build.yml`), one per
`(mc, backend)` matrix leg (`tools/gametest_matrix.py` already drives that fan-out). The gate itself (assert
against the budgets in §3.1–3.3) can live in the test (`AssertionError` fails the gametest task, same pattern
`check()` already uses throughout `src/gametest`) — no separate CI step needed beyond what already runs
`runProductionClientGameTest`.

---

## 4. P2 item 13: does Fabric Loader expose per-mod init/mixin timing?

**No — verified from the loader's own bytecode, not assumed.** `loader_version=0.19.5` (`gradle.properties`);
jar at
`C:/Users/Admin/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loader/0.19.5/.../fabric-loader-0.19.5.jar`
(no sources jar available, so I disassembled with `javap -p -c`):

- **`FabricLoaderImpl.invokeEntrypoints`** — the method that actually calls every mod's `ClientModInitializer`
  (and every other entrypoint type) — is a plain loop: `Log.debug("Iterating over entrypoint '%s'")`, then for
  each `EntrypointContainer`, `consumer.accept(container.getEntrypoint())` inside a try/catch that only
  *aggregates exceptions* (`ExceptionUtil.gatherExceptions`). No `System.nanoTime()`, no per-entry timing, no
  per-mod log line — confirmed by reading the disassembled bytecode line by line (no `invokestatic
  System.nanoTime` anywhere in the method).
- **`EntrypointStorage`** (stores/retrieves entrypoints by key) has no timing either — just `HashMap`/`List`
  bookkeeping.
- **Every `fabric.debug.*` system property** in this loader version (from `SystemProperties.class`):
  `deobfuscateWithClasspath`, `disableClassPathIsolation`, `disableModIds`, `disableModShuffle`,
  `discoveryTimeout`, `loadLate`, `logClassLoad`, `logClassLoadErrors`, `logLibClassification`,
  `logTransformErrors`, `replaceVersion`, `resolutionTimeout`, `throwDirectly`. None of these times or logs
  per-entrypoint/per-mixin cost; `discoveryTimeout`/`resolutionTimeout` are timeouts for mod *discovery* and
  dependency *resolution* (before any entrypoint runs), not init timing.
- Mixin application timing is SpongePowered Mixin's territory, not Fabric Loader's, and I found nothing in
  loader that surfaces it either.

(`disableModIds` — one of the properties above — is what made §2's clean A/B possible: it fully excludes a mod
by id at discovery time, confirmed by reading `ModDiscoverer.findDisabledModIds()`, which is a nice side
benefit of this investigation.)

**Verdict for P2 item 13**: honestly, **no per-mod breakdown is possible** from what Fabric Loader exposes.
The spec's fallback applies: **trend-only**. Store launch-to-title (from the same JVM-uptime-at-title-screen
technique as §2.1, which needs no Loader API) per run in a small bounded file — old RigTune versions simply
won't have written to it, so it degrades gracefully — and show it as a rolling chart/trend in the README or an
in-game screen, with general advice ("fewer mods and a faster disk/SSD help most; if launch time jumped after
adding a mod, that mod is the first thing to check") rather than a false claim of attribution RigTune cannot
actually back up.

---

## 5. Files/classes to add or modify

### 5.1 The opt-in session monitor (P1 item 10b)

- **New**: `core/benchmark/RingBuffer` (or extend `FrameRecorder` with a bounded-capacity mode) — a true
  fixed-size circular `long[]` (no `Arrays.copyOf` growth), since a whole-session monitor must not grow
  unbounded the way `FrameRecorder` does today (it's fine for `FrameTimes`'s ~1-minute benchmark use, wrong for
  a monitor meant to run for hours). Capacity should be a small fixed number (e.g. 4096–16384 entries =
  32–128 KB) with either a wraparound-overwrite policy or a decimating one; either is fine, but it must be
  *fixed-size*, allocated once at monitor-start, so §3.2's zero-allocation assertion holds.
- **New**: `client/benchmark/SessionFrameMonitor` (sibling of `FrameTimes`, same `onFrame(long nanos)` shape)
  wired to the *same* existing `DebugScreenOverlayMixin` hook — no new mixin needed, `DebugScreenOverlayMixin`
  can call both `FrameTimes.onFrame` and `SessionFrameMonitor.onFrame` (or `FrameTimes` gains a second
  opt-in ring-buffer sink). Gate it behind a new `ClientSettings` flag (opt-in, off by default per the spec).
- **Modify**: `client/mixin/DebugScreenOverlayMixin.java` — add the second call if a separate class is used.
- **Modify**: `ClientSettings`/`RigTuneSettingsScreen` — the opt-in toggle and its persistence.

### 5.2 Footprint instrumentation + CI guard

- **New**: `client/FootprintStats` (or under `client/probe/`) — static holders for init wall/CPU nanos (§3.1),
  populated by `RigTuneClient.onInitializeClient()` and `RealController`'s constructor/`start()`.
- **New**: `src/gametest/java/.../gametest/FootprintGameTest.java` — the CI-facing test: waits for title
  screen + report, reads `FootprintStats`, samples `ThreadMXBean` CPU time for the named RigTune threads,
  invokes the `DiagnosticCommand` MBean histogram (§3.3), asserts all three budgets, writes the JSON artifact
  (§3.5). Add its entrypoint to `src/gametest/resources/fabric.mod.json`'s existing `fabric-client-gametest`
  list (alongside the current suite — that file's `"depends": {"rigtune": "*"}` should stay as-is for the real
  test suite; only my throwaway `FootprintProbe` experiment dropped it, and only in my worktree, to run
  standalone without RigTune during the A/B).
- **New** (unit-level, §3.2): a JUnit test in `src/test/java/.../client/benchmark/` doing the tight-loop
  microbenchmark against the frame hook (`FrameTimes`/`SessionFrameMonitor`), asserting the wall-time ceiling
  and zero-allocation via `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()`.
- **Modify**: `.github/workflows/build.yml` — no new job needed; `FootprintGameTest` runs as part of the
  existing `client-gametest` matrix job (`runProductionClientGameTest` already runs whatever's registered in
  `fabric-client-gametest`), and its JSON artifact rides along with the existing screenshot/log
  `upload-artifact` steps (add one more `path:` entry, or a dedicated small upload step next to them).
- **Modify**: `README.md` — a short "RigTune's own footprint" section near "Build from source" or as part of
  "Privacy" (arguably footprint belongs next to privacy: "what does RigTune cost you" pairs naturally with
  "what does RigTune send"), stating the budgets from §3.1–3.3 and pointing at the trend report for P2-13
  (e.g. "RigTune adds well under Nms of its own CPU time at startup and Y bytes of heap; see
  `docs/research/v0.4/footprint.md` for methodology"). I did not edit the actual README (read-only main
  checkout per my brief) — this is the proposed content/location for whoever implements the feature.

### 5.3 Test plan

1. Unit: the microbenchmark (§3.2) — deterministic, no game client, runs in the existing `test` task.
2. `FootprintGameTest` under `runClientGameTest` (dev) first, then `runProductionClientGameTest` (CI parity) —
   confirms the budgets hold against a real production jar on Linux/Mesa/Xvfb, not just the dev classpath.
3. A one-time manual calibration run (§3.1, §3.3's open follow-up) to replace the reasoned-estimate budgets
   with numbers from an actual instrumented run, before the gate goes live as build-breaking (land it
   warn-only for one release, per how `build.yml`'s own comments describe treating new checks).
4. Re-run the §2 with/without A/B (the `-PfootprintDisableMods` trick) once `FootprintGameTest`'s own
   instrumentation exists, this time reading `FootprintStats` directly instead of inferring from wall clock —
   this becomes the regression check's own sanity test.

---

## 6. Worktree contents (uncommitted, for the coordinator)

`C:/Dev/Worktrees/rigtune-r-foot` (branch `research/footprint`, based on `feat/v0.4.0`), all uncommitted:
- `src/gametest/java/io/github/chaotix345/rigtune/gametest/FootprintProbe.java` — the throwaway timing probe
  (§2.1). Not meant to ship as-is; `FootprintGameTest` (§5.2) is the real thing to build, reusing the
  JVM-uptime-at-title-screen technique but folding in the actual budget assertions.
- `src/gametest/resources/fabric.mod.json` — entrypoint list swapped to just `FootprintProbe`, and the
  `"depends": {"rigtune": "*"}` removed (so it loads standalone when RigTune is excluded). **Revert this
  before reusing the worktree for anything else** — the real suite depends on it being restored.
- `build.gradle` — one added line on `runProductionClientGameTest`: `-PfootprintDisableMods=<ids>` →
  `-Dfabric.debug.disableModIds=<ids>`.

Scratchpad (this agent's): `startup_results.csv`, `startup_results_batch2.csv` (raw per-run data),
`diag-test/DiagTest.java` (the standalone MBean feasibility test, §3.3), `progress.log`.

---

## Open questions for the coordinator

1. **Retained-heap budget (§3.3) is an estimate, not a live measurement** — the machine-wide game-test lock
   was held by another agent for the rest of my window. Whoever implements `FootprintGameTest` should run the
   histogram once for real and tighten the 8 MB budget.
2. Init-cost budgets (§3.1) are similarly reasoned-from-code, not from an instrumented run (the instrumentation
   doesn't exist yet) — same "measure once for real, then tighten" note applies.
2. Should the trend-only P2-13 report live in-game (a screen) or purely in CI/docs? The spec allows either;
   I'd lean in-game (a small line on the RigTune screen, "last launch: Nms, Mth fastest of last 20") since
   that's where a player would look, but that's a product call, not a research one.
3. §3.1's background-worker CPU budget assumes the rules fetch is pointed at a local fixture
   (`-Drigtune.rules.baseUrl`) for determinism — confirm that's acceptable for a CI-facing test versus hitting
   the real GitHub URL (slower, flakier, but closer to a real launch).
