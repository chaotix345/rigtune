# Stutter Doctor (v0.4 P1 item 5): research

Owner: r-stutter. Date: 2026-09-26. Base: `feat/v0.4.0` = v0.3.0. Everything below was checked against the real jars (MC 26.2 and 26.3 from the Loom cache, Fabric API 0.161.0+26.2/26.3, Sodium mc26.2/mc26.3-0.9.2, DH API 7.2.0 `LTP8vW3B`) with `javap`, against OpenJDK jdk25u sources, or by running programs on the portable Temurin 25.0.4.1+1 (Windows 11, 16 logical CPUs). Anything I couldn't check is marked **UNVERIFIED**. Scratch programs and raw logs: `scratchpad/r-stutter/` (gcprobe/, bench/, proto/, javap/, rulescheck/).

## 0. Summary and decisions

| topic | decision | evidence |
|---|---|---|
| GC signal | Register a `NotificationListener` on every `GarbageCollectorMXBean` only while the monitor is on. Treat `gcAction == "end of GC cycle"` as **concurrent (not a pause)** and everything else as a **pause**. Store raw `GcInfo` start/end (ms) plus the receive `System.nanoTime()`. | §1: 5 collectors run on JDK 25; `setNotificationEnabled` is per bean and only while a listener exists (GarbageCollectorExtImpl). |
| GC clock mapping | `GcInfo` times are **not** on the `RuntimeMXBean.getUptime()` base: they're ms since VM-init-done (`Management::_stamp`), uptime is ms since `os::init`. The offset was 17–28 ms per run. Calibrate per run: `offset = min(recvUptimeMs − endMs)` over the session's notifications. Error vs `-Xlog:gc` truth: +0.08 ms idle, +0.23 ms under CPU overload. | §1.4 |
| Don't trust delivery time | Notification delivery lag was p99 1.4 ms idle, but **p99 85 ms / max 96 ms** with the CPUs oversubscribed. Always use the mapped `GcInfo` interval, never the receive time. | §1.3 |
| Frame hook | Keep the existing `@Inject(HEAD)` on `DebugScreenOverlay.logFrameDuration(J)V`. Add `System.nanoTime()` there for the frame end (21 ns per frame incl. ring write, histogram and online spike check). | §2.1–2.3: same call site and descriptor in 26.2 and 26.3; `GameRenderer.render` differs between versions. |
| Phase timers | Add 4 `@Inject INVOKE` pairs in `Minecraft.runTick`/`renderFrame` (packets, ticks, render, frame limiter). This measures chunk-packet time directly, instead of guessing it from correlation. | §2.4: identical targets in both versions. |
| Chunk loading | Fabric `ClientChunkEvents.CHUNK_LOAD` count per frame + the "packets" phase time. | §3.2 |
| Chunk building | Sodium: reflect `SodiumWorldRenderer.instanceNullable().renderSectionManager.getBuilder()` → `getScheduledJobCount()` (O(1), thread-safe), `getBusyThreadCount()`, `getTotalThreadCount()`. Without Sodium: `LevelRenderer.sectionRenderDispatcher().getCompileQueueSize()`. Plus the "render" phase time. | §3.3 |
| Autosave | Fabric `ServerLifecycleEvents.BEFORE_SAVE/AFTER_SAVE` (HEAD/TAIL of `MinecraftServer.saveAllChunks`). Autosave runs every `max(100, 300 × tickrate)` = 6000 ticks (5 min). The pause-menu save fires the same events. | §3.4 |
| Distant Horizons | The DH API 7.2.0 has **no** queue or generation-progress API. Use a 4 Hz thread-CPU sampler grouping threads by name (`DH-` prefix from `ModInfo.THREAD_NAME_PREFIX`) → "DH busy" + CPU contention. Low/medium confidence only. | §3.5 |
| Spike rule | `d > max(2.0 × b, b + 8 ms, 20 ms)`, where `b` is the median of the previous 120 non-spike frames. Merge spikes < 100 ms apart into one hitch. Attribute lost time `d − b`. | §4 |
| Advice | A new top-level `stutterAdvice` section in rules-v2.json. The released 0.2.0 jar and the 0.3.0 build both ignore it (tested). Entries also carry `requires: ["stutter-doctor"]`, and stutter facts are new condition keys (fail closed). | §5 |
| Persistence | `config/rigtune/stutter.json`, ≤ 64 KiB, the last 5 session summaries. It's a new file, so older versions never read it. | §7 |

Memory while the monitor is on is ~1.8 MiB (allocated on enable, dropped on disable). Per-frame cost is ~21 ns and allocation-free. The sampler costs ~50 µs every 250 ms off-thread. With the monitor off, the cost is zero: no listeners, no sampler thread, and the frame hook is a single `volatile` read.

---

## 1. GC notifications on Java 25 (measured)

### 1.1 Program

`scratchpad/r-stutter/gcprobe/GcProbe.java` registers a listener on every `GarbageCollectorMXBean` (`NotificationEmitter`). It records bean name, `gcName`, `gcAction`, `gcCause`, `GcInfo` id/start/end/duration, heap used before and after, the delivery thread, and `System.nanoTime()` at delivery. Then it churns young garbage, retains objects to force promotion (mixed/concurrent cycles), allocates 4 MiB humongous arrays, and calls `System.gc()` three times, recording each call's nanoTime window. Each collector ran with `-Xmx256m -Xlog:gc:file=...:uptimemillis,uptimenanos`, so `-Xlog` gives independent ground truth on the `os::elapsed` clock (the same base as `RuntimeMXBean.getUptime()`).

`GcLatency.java` repeats this for 6 s with 0 or 24 busy threads (16 CPUs) to measure delivery lag and the clock offset under contention.

Availability on Temurin 25.0.4.1+1: G1 (default), ZGC, Shenandoah, Serial and Parallel all start. `-XX:+UseZGC -XX:-ZGenerational` prints `Ignoring option ZGenerational; support was removed in 24.0`, so **generational ZGC is the only ZGC on 25**.

### 1.2 What each collector reports (real output, trimmed)

**G1** (beans: `G1 Young Generation` [pools G1 Eden Space, G1 Survivor Space, G1 Old Gen], `G1 Concurrent GC` [G1 Old Gen], `G1 Old Generation` [all three]; implementation class `com.sun.management.internal.GarbageCollectorExtImpl` for all):
```
NOTIF bean="G1 Young Generation" gcAction="end of minor GC" gcCause="G1 Evacuation Pause" id=5 startMs=139 endMs=145 durMs=6 usedBeforeMB=160 usedAfterMB=68 thread="Notification Thread" daemon=true
NOTIF bean="G1 Young Generation" gcAction="end of minor GC" gcCause="G1 Humongous Allocation" id=13 startMs=186 endMs=186 durMs=0
NOTIF bean="G1 Concurrent GC"    gcAction="end of concurrent GC pause" gcCause="No GC" id=1 startMs=177 endMs=177 durMs=0
NOTIF bean="G1 Concurrent GC"    gcAction="end of concurrent GC pause" gcCause="No GC" id=2 startMs=178 endMs=178 durMs=0
NOTIF bean="G1 Old Generation"   gcAction="end of major GC" gcCause="System.gc()" id=1 startMs=199 endMs=201 durMs=2 usedBeforeMB=136 usedAfterMB=12
counts: 11 minor/G1 Evacuation Pause, 3 minor/G1 Humongous Allocation, 4 concurrent-pause/No GC, 3 major/System.gc()
-Xlog: [194545000ns] GC(8) Pause Remark 179M->140M(256M) 0.265ms   [195710300ns] GC(8) Pause Cleanup 0.009ms
       [218129600ns] GC(16) Pause Full (System.gc()) 130M->6M(34M) 1.680ms
```
The `G1 Concurrent GC` notifications are the **Remark and Cleanup pauses**, not the concurrent mark cycle: id 1 at 177 ms + the 17 ms offset (§1.4) = 194 ms, which is `Pause Remark` in the log, and id 2 is `Pause Cleanup`. The concurrent marking itself is never reported.

**ZGC (generational)** (beans: `ZGC Minor Cycles`, `ZGC Minor Pauses`, `ZGC Major Cycles`, `ZGC Major Pauses`; pools `ZGC Young Generation`, `ZGC Old Generation` on each):
```
NOTIF bean="ZGC Major Pauses" gcAction="end of GC pause" gcCause="System.gc()" startMs=312 endMs=312 durMs=0   (x8 for one System.gc)
NOTIF bean="ZGC Major Cycles" gcAction="end of GC cycle" gcCause="System.gc()" startMs=312 endMs=316 durMs=4 usedBeforeMB=35 usedAfterMB=21
NOTIF bean="ZGC Minor Pauses" gcAction="end of GC pause" gcCause="Allocation Rate" durMs=0
NOTIF bean="ZGC Minor Cycles" gcAction="end of GC cycle" gcCause="Allocation Rate" startMs=167 endMs=182 durMs=15
NOTIF bean="ZGC Minor Pauses" gcAction="end of GC pause" gcCause="Allocation Stall" startMs=242 endMs=242 durMs=0
NOTIF bean="ZGC Minor Cycles" gcAction="end of GC cycle" gcCause="Allocation Stall" startMs=242 endMs=260 durMs=18
causes seen: Warmup, Allocation Rate, Allocation Stall, System.gc()
-Xlog: [254252199ns] Allocation Stall (main) 2.135ms ... [268866400ns] Allocation Stall (main) 5.791ms
```
- `* Pauses` beans report each STW pause (Mark Start/Mark End/Relocate Start) with `durMs=0`, because `GcInfo` has **millisecond resolution** and ZGC pauses are sub-ms.
- `* Cycles` beans report the **concurrent** cycle's wall time (4–31 ms here). That is not a pause and must never be treated as one.
- **Allocation stalls** (application threads blocked waiting for memory; the log shows 2–6 ms stalls on `main`) are *not* in any notification's duration. The only trace is `gcCause="Allocation Stall"` on the minor pauses/cycles. So the Doctor can say "ZGC had allocation stalls around this spike" but can't measure them.

**Shenandoah** (available on Temurin 25; beans `Shenandoah Pauses`, `Shenandoah Cycles`):
```
NOTIF bean="Shenandoah Pauses" gcAction="Init Mark" gcCause="System.gc()" durMs=0
NOTIF bean="Shenandoah Pauses" gcAction="Final Mark" / "Init Update Refs" / "Final Update Refs" ...
NOTIF bean="Shenandoah Cycles" gcAction="end of GC cycle" gcCause="System.gc()" startMs=436 endMs=438 durMs=2
NOTIF bean="Shenandoah Pauses" gcAction="Degenerated GC" gcCause="Allocation Failure" startMs=306 endMs=310 durMs=4
-Xlog: [330091600ns] GC(6) Pause Degenerated GC (Outside of Cycle) 242M->219M(256M) 3.182ms
```
For Shenandoah, `gcAction` on the Pauses bean is the **phase name**, not "end of ... pause". "Degenerated GC" (and a Full GC) with cause "Allocation Failure" means the heap was too small for the concurrent collector.

**Serial**: `Copy` ("end of minor GC", "Allocation Failure") and `MarkSweepCompact` ("end of major GC"). **Parallel**: `PS Scavenge` / `PS MarkSweep`. Both have only pause notifications.

**Delivery thread:** all 292 notifications across the 5 runs arrived on `"Notification Thread"` (daemon), the JVM's dedicated notification thread. The listener must be thread-safe towards the render thread.

**Classification rule** (holds for all 5): `gcAction.equals("end of GC cycle")` → CONCURRENT_CYCLE (context only). Anything else → PAUSE: `end of minor GC`, `end of major GC`, `end of concurrent GC pause`, `end of GC pause`, and Shenandoah's phase names. Sub-kinds for the report:
- FULL: G1 Old Generation, MarkSweepCompact, PS MarkSweep, Shenandoah "Degenerated GC"/"Full GC".
- STALL_HINT: cause "Allocation Stall"; Shenandoah "Allocation Failure".
- EXPLICIT: cause "System.gc()".

Vanilla 26.2/26.3 calls `System.gc()` only in `Minecraft.emergencySave()`, `Minecraft.run()`'s OOM path and `MemoryReserve.release()` (bytecode search of both jars). So an explicit GC during normal play comes from a mod, or from the JDK: `java.nio.Bits.reserveMemory` calls `System.gc()` when direct memory runs out (jdk25u Bits.java:143).

### 1.3 Delivery latency (measured)

`GcLatency` measured receive time minus (mapped end + calibrated offset), over 643 and 230 notifications:
```
busyThreads=0  cpus=16: p50=0.53 p90=0.95 p99=1.36 max=9.78 ms
busyThreads=24 cpus=16: p50=17.71 p90=42.82 p99=85.41 max=96.45 ms
```
Minecraft with Sodium's builder threads, DH and the integrated server routinely oversubscribes the CPU, so **the receive time can't stand in for the pause time**. That's fine: attribution is post-hoc (on report open), and it uses `GcInfo` start/end.

### 1.4 Mapping `GcInfo` times to `System.nanoTime()` (important)

The naive mapping `anchorNanos + (gcInfo.getStartTime() − uptimeAtAnchor) × 1e6` is wrong by 17–28 ms. Evidence:
- G1: the first `System.gc()` call ran in uptime [216.417 .. 218.155] ms (nanoTime-calibrated), and `-Xlog` puts the pause end at 218.13 ms, but `GcInfo` says start 199 / end 201.
- Same shape on every collector: ZGC ~19 ms, Shenandoah ~20.5, Serial ~19.5, Parallel ~18.5. Four more runs gave 20.7, 19.7, 28.0 and 22.5 ms. The offset **changes per JVM run**.

Why (jdk25u `services/management.cpp`, `memoryManager.cpp`, `gcNotifier.cpp`):
- `GCMemoryManager::gc_begin/gc_end` stamp `Management::timestamp()`, which is `t.ticks() − _stamp.ticks()`.
- `_stamp.update()` happens in `Management::record_vm_init_completed()`, and `gcNotifier.cpp:153` converts that with `ticks_to_ms`.
- `RuntimeMXBean.getUptime()` is `JMM_JVM_UPTIME_MS` → `Management::ticks_to_ms(os::elapsed_counter())`, counted from `os::init()`.
- So the two bases differ by the VM initialisation time. `RuntimeMXBean.getStartTime()` (epoch ms) doesn't help. The exact VM-init-done time is only in hsperf counters (`sun.rt.vmInitDoneTime`), which a mod can't read without `--add-exports`.

Calibration that works:
1. On enable, anchor: spin until `getUptime()` ticks over (`while ((u = rt.getUptime()) == u0) {}`) and take `nanosAtUptimeZero = System.nanoTime() − u × 1e6`. The two anchors measured 50 ms apart agreed to 0–1.8 µs. On Windows both clocks are QueryPerformanceCounter, so there is no drift.
2. For each notification, keep `recvUptimeMs = (recvNanos − nanosAtUptimeZero)/1e6`. At analysis time, `offsetMs = min(recvUptimeMs − endMs)`. This converges to the true offset plus the minimum delivery lag, and the `min` also removes the `endMs` floor truncation.
3. The pause interval in nanoTime is `[anchor + (startMs + offset) × 1e6, anchor + (endMs + offset + 1) × 1e6]`; the `+1 ms` covers the truncation.

Accuracy against `-Xlog:gc` ground truth (the true offset is `min(logEnd − endMs)`):
```
idle:       estimate 28.002 ms, truth 27.913..27.925 → error +0.08 ms (300 young pauses)
contended:  estimate 22.462 ms, truth 22.232..22.234 → error +0.23 ms (191 young pauses)
```
Cross-check: after calibration, every `System.gc()` pause mapped *inside* its measured call window (4/4 idle and 4/4 contended, `inside=true`). A spike is ≥ 20 ms, so sub-ms alignment is more than enough. Young GCs should arrive within seconds of enabling (MC's in-game allocation rate is **UNVERIFIED**; the sampler records it via `getTotalThreadAllocatedBytes()`, 0.22 µs per call). Until one arrives the offset is unknown, so GC attribution waits for the first notification. The report says "GC timing not calibrated yet" when none has arrived.

### 1.5 Cost and switching off

`GarbageCollectorExtImpl.addNotificationListener` calls `setNotificationEnabled(this, true)` only for the first listener, and the matching `removeNotificationListener` disables it (jdk25u lines 118–150). With the monitor off, the JVM builds no notifications at all.

Per GC, the listener allocates `GarbageCollectionNotificationInfo` + `GcInfo` maps on the Notification Thread, which is a few objects per GC and never on the render thread. It writes 6 primitives into a lock-guarded ring: nanos-received, startMs, endMs, kind/bean index, usedAfterBytes, maxBytes.

### 1.6 Heap-pressure facts the listener can derive (for advice)

- `usedAfter` summed over `getMemoryUsageAfterGc()` after an old/full/major collection (G1 Old Generation, a mixed collection's old pool, ZGC Major Cycles, Shenandoah cycles), divided by `Runtime.maxMemory()`, gives a **live-set estimate**.
- Non-explicit FULL collections (G1 Old Generation with cause ≠ System.gc(), Shenandoah Degenerated/Full), ZGC "Allocation Stall" causes, and a live set ≥ 75 % of `-Xmx` → "heap too small".
- Young pause p95, pauses per minute, and the collector family from the bean names (`G1*`, `ZGC*`, `Shenandoah*`, `PS*`, `Copy`/`MarkSweepCompact`).

The GC-choice advice itself is r-jvm's topic (docs/research/v0.4/jvm-gc.md): the Doctor only supplies the facts and links to it.

---

## 2. Frame timing

### 2.1 What the benchmark does today

`client/mixin/DebugScreenOverlayMixin` does `@Inject(method = "logFrameDuration", at = @At("HEAD"))` and calls `FrameTimes.onFrame(frameDuration)`. `FrameTimes` holds one `core/benchmark/FrameRecorder` (a growing `long[]` of **durations**, start capacity 4096, doubling). The benchmark calls `start()` at the beginning of each sweep and `stop()` at its end, and `FrameStats.of` sorts a copy to compute avg FPS, 1 % low (the mean of the slowest 1 %), p99 and max. It keeps durations only (no timestamps), locks with `synchronized` per frame while recording, and grows by `Arrays.copyOf`. That's fine for 20–60 s sweeps, but not for a session monitor (unbounded, allocates on growth, no timestamps to line up with GC or save events).

### 2.2 The per-frame call site (bytecode, both versions)

`Minecraft.runTick(Z)V` → `renderFrame(Z)V` (26.2 offset 457, 26.3 457). Inside `renderFrame`:
```
26.2: 767 invokestatic FramerateLimiter.limitDisplayFPS(I)V
      789 invokestatic Util.getNanos()J ; lstore 7
      794..801 lload 7 ; getfield lastNanoTime ; lsub ; lstore 9
      820 invokevirtual DebugScreenOverlay.logFrameDuration(J)V   (arg = local 9)
      826 putfield lastNanoTime   (= local 7)
26.3: 788 limitDisplayFPS ... 810 getNanos ... 841 logFrameDuration(J)V ... 847 putfield lastNanoTime
```
`javap -s` shows `public void logFrameDuration(long)` with descriptor `(J)V` on both. So the argument is exactly `now − previous now`: the full frame-to-frame interval, including the frame limiter / vsync wait. The frames tile time with no gaps, since `lastNanoTime` is chained.

`Util.getNanos()` reads `Util.timeSource`, a lambda over `System.nanoTime` (constant pool: `REF_invokeStatic java/lang/System.nanoTime`), so a `System.nanoTime()` call at HEAD of `logFrameDuration` is on the same clock as `GcInfo` after §1.4 and trails `now` by a few ns. It's called unconditionally every frame, whether or not F3 is open (mc-api.md; the call isn't guarded in the bytecode).

Alternatives rejected:
- `GameRenderer.render` changed signature (26.2 `(Lnet/minecraft/client/DeltaTracker;Z)V`, 26.3 `()V`), so a mixin there needs a Stonecutter branch.
- Fabric API 0.161.0's `LevelRenderEvents`/`LevelExtractionEvents` fire only while a level renders, and HUD callbacks are skipped with F1. There's no end-of-frame event.
- A `Minecraft.runTick` TAIL inject would miss `lastNanoTime` accounting.

**Decision:** reuse the existing mixin and dispatch to both `FrameTimes.onFrame(d)` and `StutterMonitor.onFrame(d)`.

### 2.3 Allocation-free capture (measured cost)

```java
// render thread only; arrays allocated on enable, never grown
static void onFrame(long d) {
    if (!enabled) return;                       // one volatile read when off
    long now = System.nanoTime();
    ends[head] = now; head = (head + 1) & MASK; // long[1<<17] ring of frame-end nanos
    hist[bucket(d)]++; histTimeNs[bucket(d)] += d;  // session-wide, log-spaced buckets
    long b = ewma;                              // online baseline (for candidates only)
    if (d >= CANDIDATE_MIN && d >= b + (b >> 1)) pushCandidate(now, d, phases, chunkLoadsThisFrame);
    ewma = b + ((Math.min(d, 2 * b) - b) >> 5); // clamped EWMA, alpha 1/32
    resetPhases();
}
```
`bench/CostProbe.java` (the same logic with 2^17 ring, histogram and EWMA) measured **20.6–21.8 ns per call** over 5 × 20 M calls. Most of that is `System.nanoTime()` (QPC). At 240 fps that's ~5 µs/s (0.0005 %). There's no `new` in the path. The unit test in §9 asserts 0 bytes allocated with `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()`, which costs 32 ns per call (measured) and exists on JDK 25.

### 2.4 Phase timers (optional but recommended): where a frame's time went

The same `INVOKE` targets are in both versions (javap, byte offsets identical in `runTick`):
```
runTick:     124 PacketProcessor.processQueuedPackets()V   137 Minecraft.runAllTasks()V
             258 Minecraft.tick()V (inside a loop, up to 10 per frame)   457 Minecraft.renderFrame(Z)V
renderFrame: 767/788 FramerateLimiter.limitDisplayFPS(I)V
```
A `MinecraftFrameMixin` with `@Inject(at = @At(value = "INVOKE", target = ..., shift = BEFORE/AFTER))` pairs accumulates four `long` fields per frame:
- **packets** (`processQueuedPackets` + `runAllTasks`): chunk data packets are handled here (`ClientPacketListener` → `ClientChunkCache.replaceWithPacketData`, where Fabric fires `CHUNK_LOAD`)
- **ticks** (all `tick()` calls)
- **render** (`renderFrame` minus the limiter)
- **limiter**

That's 8 `nanoTime` calls per frame (~160 ns). Without the phases, "chunk loading" can only be claimed from correlation. With them, the Doctor can say "this 55 ms frame spent 38 ms handling 24 chunk packets". Don't target `GpuSurface.present` (26.2 class `com/mojang/blaze3d/systems/GpuSurface`, 26.3 interface `com/mojang/renderpearl/api/device/GpuSurface`). The injection points are **UNVERIFIED at runtime** (verified in bytecode only; compile and run them in the first build).

### 2.5 Buffers and memory budget (monitor on)

| buffer | layout | size | covers |
|---|---|---|---|
| frame ring | `long[1<<17]` frame-end nanos (durations are differences) | 1 MiB | 9.1 min @240 fps, 18.2 @120, 36.4 @60 |
| session histogram | `int[24]` counts + `long[24]` time, log-spaced (bucket = `63 − numberOfLeadingZeros(d >>> 17)`, ~0.13 ms doubling); the screen merges them into the 9 display buckets of §7 | < 1 KiB | whole session |
| candidates | `long[4096 × 8]`: end, d, ewma, packets, ticks, render, limiter, chunkLoads\|flags | 256 KiB | frames ≥ max(20 ms, 1.5 × EWMA), whole session until 4096 |
| GC ring | `long[1024 × 6]` (§1.5) | 48 KiB | ~hours of young GCs at a few/min… wraps; summary counters kept |
| event ring | `long[4096 × 4]`: kind, t0, t1, value (save begin/end, level change, screen open/close, teleport, chunk-burst) | 128 KiB | session |
| sampler ring | 8192 × (long t + 9 int CPU-µs groups) | ~360 KiB | 34 min at 4 Hz |

Total ≈ **1.8 MiB**, allocated when the user enables the monitor and released when they disable it. That's r-footprint's budget to confirm.

**Threads and ownership.**
- Frame ring, histogram, candidates, phases: render thread only.
- GC ring: written by the Notification Thread under a tiny lock.
- Save events: server thread.
- Level/screen/teleport events: render thread (END_CLIENT_TICK).
- Sampler ring: the sampler thread.

Each ring has exactly one writer. **Where analysis runs:** when the report screen opens (render thread), copy the render-thread rings (`System.arraycopy` of ≤ 1.4 MiB, well under 1 ms) and take the other rings under their locks. Then run detection and attribution on a background executor (`Probes.EXECUTOR`) and show "Analysing…" until done. Nothing is analysed per frame except the O(1) candidate test.

---

## 3. Attribution signals

| signal | capture (thread) | cost | 26.2/26.3 | absent / fallback |
|---|---|---|---|---|
| GC pause/cycle | MX notifications (Notification Thread) | per GC, off-thread | JDK API | none needed |
| chunk loads | Fabric `ClientChunkEvents.CHUNK_LOAD` → `int` counter (render thread) | 1 increment per chunk | same API in both (lifecycle-events 4.1.4 / 4.1.9) | vanilla-only via packets phase |
| chunk packet time | phase timer "packets" (§2.4) | 4 nanoTime/frame | same targets | none |
| Sodium build backlog | reflection, sampled at 4 Hz: `getScheduledJobCount/getBusyThreadCount/getTotalThreadCount` | 3 calls / 250 ms | same members in 0.9.2 for both | vanilla `LevelRenderer.sectionRenderDispatcher().getCompileQueueSize()` |
| render phase time | phase timer "render" | incl. above | same | none |
| world save window | Fabric `ServerLifecycleEvents.BEFORE_SAVE/AFTER_SAVE` (server thread) | 2 events per save | same | multiplayer: n/a |
| DH activity | thread-CPU sampler, `DH-` threads (sampler thread) | ~50 µs per 250 ms | DH API 7.2.0 | "DH not installed" |
| CPU contention | same sampler: Σ thread CPU / (cores × window) | incl. | JDK API | — |
| world join / dimension | Fabric `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE` | per change | same | — |
| teleport / fast movement | END_CLIENT_TICK: player position delta | 20 Hz, trivial | stable | — |
| menus / loading | END_CLIENT_TICK: `minecraft.gui.screen()` identity change | 20 Hz | `Gui.screen()` on both | — |

### 3.1 GC: see §1.

### 3.2 Chunk loading (client)

`ClientChunkEvents.CHUNK_LOAD` (`Load.onChunkLoad(ClientLevel, LevelChunk)`) exists in 0.161.0 for both versions (javap). Fabric's `ClientChunkCacheMixin` hides the version difference in `ClientChunkCache.replaceWithPacketData`: 26.2 takes `(int, int, FriendlyByteBuf, Map, Consumer)`, 26.3 takes `(int, int, ClientboundLevelChunkPacketData)`. Its injectors (javap -v) are `onChunkLoad(int, int, FriendlyByteBuf, Map, Consumer, CallbackInfoReturnable)` in 4.1.4 and `onChunkLoad(int, int, ClientboundLevelChunkPacketData, CallbackInfoReturnable)` in 4.1.9, both on `method=["replaceWithPacketData"]`. So **use the Fabric event and don't mixin that method.** The per-frame count goes into the candidate record, and a burst (≥ 16 chunks in 1 s) also goes into the event ring. The "packets" phase is the measured cost.

### 3.3 Chunk building / uploads

Sodium 0.9.2 (both MC versions, identical members):
- `SodiumWorldRenderer.instanceNullable()` (public static)
- `private RenderSectionManager renderSectionManager`
- `RenderSectionManager.getBuilder()` (public)
- `ChunkBuilder.getScheduledJobCount()`: `semaphore.availablePermits()`, O(1), thread-safe
- `getBusyThreadCount()`: `AtomicInteger`
- `getTotalThreadCount()`

Builder threads are named `"Chunk Render Task Executor #" + i` (constant pool). Resolve the `ChunkBuilder` on the render thread at level change (reflect the private field once and cache a `MethodHandle`; RigTune already reflects `SodiumWorldRenderer.instanceNullable`/`isTerrainRenderComplete`), publish it via a `volatile`, and let the sampler read the counts. On `NoSuchFieldException` or a `ReflectiveOperationException`, mark the signal unavailable and log once.

Sodium defaults (from `SodiumOptions$PerformanceSettings.<init>`): `chunk_builder_threads = 0` (auto = `clamp(max(cores/3, cores − 6), 1, 10)` from `ChunkBuilder.getOptimalThreadCount`) and `chunk_build_defer_mode = ALWAYS`. With `ZERO_FRAMES` ("Immediate") or `ONE_FRAME` ("Soon"), the render thread **waits** for important nearby builds, which is a direct stutter mechanism. With `ALWAYS` ("Deferred") it never waits. The mode comes from the settings snapshot (`sodium.performance.chunk_build_defer_mode`).

Vanilla fallback: `LevelRenderer.sectionRenderDispatcher()` and `SectionRenderDispatcher.getCompileQueueSize()` are public on both versions.

### 3.4 Autosave (singleplayer integrated server)

The path is identical in 26.2 and 26.3:
- `MinecraftServer.tickServer` → `private void autoSave()` → `saveEverything(true, false, false)` → `saveAllChunks(ZZZ)`.
- `computeNextAutosaveInterval()` = `max(100, (int)(tickrate × 300))` ticks, which is 6000 ticks (5 min) at 20 TPS. When sprinting it uses the measured tick rate.
- `IntegratedServer.tickServer` also calls `saveEverything(false, false, false)` when the game pauses ("Saving and pausing game..."), and so does world exit.

Fabric's `MinecraftServerMixin` wraps `saveAllChunks` with `startSave` (HEAD) → `BEFORE_SAVE` and `endSave` (TAIL) → `AFTER_SAVE`, verified in both lifecycle jars. Both events pass `(MinecraftServer, boolean flush, boolean force)`, so the Doctor records save windows without its own mixin:
- A save that starts while the client is paused (menu open) is labelled "pause save".
- A save that never gets `AFTER_SAVE` (TAIL misses an early return or an exception) is closed after 10 s.
- The events run on the **server thread**: write the ring under a lock, never touch client state there.

The save stalls the server thread, not the render thread. It hurts frames indirectly: allocation → GC, and CPU contention with the IO-Worker/Worker-Main threads. So a spike inside a save window is "during a world save" with **low** confidence unless a GC pause or the sampler explains it. Whether autosave causes visible client spikes on this machine is **UNVERIFIED** (§6 measures it).

### 3.5 Distant Horizons

The DH API 7.2.0 (`ModInfo.VERSION = "3.3.2"`, API 7.2.0) has:
- events: `DhApiAfterDhInitEvent` … `DhApiChunkModifiedEvent`, `DhApiChunkProcessingEvent`, level/world load/unload, render events
- configs: `IDhApiMultiThreadingConfig.threadCount()`/`threadRuntimeRatio()`, `IDhApiWorldGenerationConfig.enableDistantWorldGeneration()`

There's **no generation queue, task count or progress API**. `DhApiChunkProcessingEvent` fires "for each block or biome change when DH is processing a chunk … may be called concurrently … very frequently", which is far too hot to count, and listening may slow DH down. Rejected.

Feasible instead:
- **Thread-CPU sampler** (4 Hz, daemon thread `RigTune stutter sampler`): `ThreadMXBean.getAllThreadIds()` + `getThreadInfo(ids)` (maxDepth 0, **no safepoint**: jdk25u management.cpp:1118 "No stack trace to dump so we do not need to stop the world") + `com.sun.management.ThreadMXBean.getThreadCpuTime(long[])`. Group by name:
  - `Render thread`, `Server thread` (verified strings in `net.minecraft.client.main.Main` / `MinecraftServer`)
  - `Worker-Main-*` (MC world-gen/worker pool; `Util` "Worker-\u0001-\u0001" with name "Main"), `IO-Worker-*`
  - `Chunk Render Task Executor #*` (Sodium), `DH-*` (DH `ModInfo.THREAD_NAME_PREFIX = "DH-"`; `DhApi.isDhThread()` uses it)
  - other
  - Plus `getProcessCpuTime()` minus the Java threads' sum ≈ GC/JIT/native threads.

  Measured on 67 threads: **48–55 µs per sample** after warm-up (91 µs first).
- DH thread settings for advice: `DhApi.Delayed.configs.multiThreading().threadCount()` (API 4.0+). The file keys `dh.common.multiThreading.numberOfThreads` (int) and `dh.common.multiThreading.threadRunTimeRatio` (a *quoted* string double) come from docs/research/v0.2/dh-iris.md; they were verified there against a real DistantHorizons.toml, not re-verified here.
- Caveats:
  - On **Windows the thread CPU clock ticks in 15.6 ms steps**: a busy thread's `getThreadCpuTime` changed 63 times in 1 s, always by 156 × 0.1 ms. So per-frame CPU time is useless, and 250 ms windows carry about ±6 % error per thread.
  - That every DH worker thread carries the `DH-` prefix at runtime is **UNVERIFIED** (only the API constant is known). Check the thread list in the real run.
- Rejected: `OperatingSystemMXBean.getCpuLoad()`/`getProcessCpuLoad()` took **110–125 ms per call** on Windows and returned −1.0 (bench/OsLoad.java). They're unusable, and dangerous if ever called on the render thread. System-wide load from other programs has no cheap API (OSHI is **UNVERIFIED** for cost), so it isn't captured and ends up in "unknown".

### 3.6 World join, dimension change, teleport, fast movement

- `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE` → `afterLevelChange(Minecraft, ClientLevel)` (both versions) marks a level change. The first 10 s after it are "world loading": excluded from gameplay stats, but counted.
- END_CLIENT_TICK tracks the player position:
  - a jump > 64 blocks in one tick → teleport event (the next 10 s are "after teleport")
  - horizontal speed > 20 blocks/s (elytra/creative flight) → "moving fast" intervals (chunks entering view)
- A `gui.screen()` identity change → menu intervals. Frames while a screen is open, or the window is unfocused (`minecraft.isWindowActive()`, which the benchmark already uses), are excluded from spike detection.

### 3.7 Unknown

Whatever is left: GPU driver stalls, shader compilation, OS scheduling, other programs, mod work not in the phases. It's reported as a share, never hidden.

---

## 4. Spike detection and attribution

### 4.1 Definitions
- Frame *i*: interval `F_i = [end_i − d_i, end_i]`.
- Baseline `b_i`: the median of the previous **120 non-spike** gameplay frames (~0.5–2 s). The trailing window is kept as a sorted `long[120]` with binary-search insert/delete (O(W) per frame, ~15 M ops for 131 k frames, well under 100 ms off-thread). Use the online EWMA while fewer than 30 frames are in the window, or for candidates older than the frame ring.
- **Spike:** `d_i > max(2.0 × b_i, b_i + 8 ms, 20 ms)`.
  - 2× is a clearly visible hitch.
  - +8 ms stops 3→7 ms jitter at 300 fps from counting.
  - 20 ms is the floor for a noticeable hitch (just over one 60 Hz frame + jitter).
  - Severity: minor < 50 ms, major 50–100 ms, severe 100–500 ms, freeze ≥ 500 ms.
- **Lost time** `e_i = d_i − b_i`.
- **Hitch:** spikes less than 100 ms apart are merged. Its lost time is the sum, and its causes are the union.
- Excluded frames (menus, unfocused, 10 s after a level change or teleport) aren't spikes. They're counted as "during loading/menus".

### 4.2 Attribution per spike (evidence → explained ms, confidence)

1. **GC (measured):**
   - `gcMs = Σ overlap([ps, pe + 1 ms], F_i)` over PAUSE events, capped at `e_i`.
   - Confidence **high** if `gcMs ≥ 0.5 e_i`; "contributing" if ≥ 0.2 e_i.
   - Context flags: FULL (a full GC), EXPLICIT (someone called System.gc()), STALL (a ZGC "Allocation Stall" or Shenandoah "Allocation Failure" event within ±100 ms of `F_i`). STALL counts as GC **medium** with no ms claimed.
2. **Chunk loading (measured with phases):**
   - `pkMs = packets_i − median(packets)`, capped at what's left.
   - Claimed when `chunkLoads_i ≥ 1` (or the frame before had ≥ 1) and `pkMs > 2 ms`. **High** if ≥ 0.5 e_i.
   - Without phases: `chunkLoads` in `F_i ± 1 frame` ≥ 8 → **low**, no ms.
3. **Chunk building (partly measured):**
   - `rdMs = render_i − median(render)` > 0.5 e_i.
   - Plus Sodium backlog (scheduled > 0 and busy = total) in the sampler window, or defer mode ≠ ALWAYS → **medium** "chunk building/uploading", with `rdMs` claimed.
   - Without the backlog evidence, the render excess stays "rendering (unknown)".
4. **Client tick (measured):** `tickMs = ticks_i − median(ticks)` ≥ 0.5 e_i → "game tick (entities/world)" **medium**.
5. **World save (correlational):**
   - `F_i` overlaps a save window ±50 ms → tag "during a world save", **low**, no ms.
   - If a GC explains it, the report says "GC during a world save".
6. **Distant Horizons (correlational):**
   - In the sampler window around `F_i`, DH threads used ≥ 1 core-equivalent, and total JVM CPU ≥ 0.85 × cores (contention) → "DH background work competing for the CPU", **low/medium**, no ms.
   - Without contention: only a context note.
7. **CPU contention (correlational):** total ≥ 0.9 × cores without DH dominance → "background threads saturating the CPU" (names the top group: Sodium builders, Worker-Main world-gen, server thread).
8. **Context:** "after teleport", "moving fast", "world loading".
9. **Unknown** = `e_i − Σ claimed ms`.

**Multiple causes** add up to at most `e_i`, applied in the order GC → chunk packets → tick → render (measured first). Correlational tags never claim ms.

### 4.3 Honest aggregate

For the session, report:
- `explained share = Σ claimed ms / Σ e_i` per cause, plus the unknown share
- the count of spikes each correlational tag touched ("7 of 19 spikes happened during a world save, not measured")

The advice evaluator only uses shares computed from claimed ms, plus the tagged-spike share for correlational causes with a higher threshold. Sessions with < 3 spikes or < 2 min of gameplay → "not enough data".

### 4.4 Worked example (prototype `proto/Proto.java`, synthetic 60 fps trace, 50 s)

Injected events:
- a 45 ms G1 young pause inside frame 600
- a frame whose packet phase took +38 ms with 24 chunk loads (1200)
- a 70 ms frame inside a save window (1800)
- a 90 ms frame with nothing (2400), followed by a 35 ms frame that holds a 14 ms GC pause (2401)

```
 frame   t(s)   dur(ms) base(ms) excess(ms)  cause (explained ms, confidence)
   600  10.07     61.7     16.6      45.1   GC 45.1 (high)
  1200  20.12     54.7     16.6      38.1   chunk loading 38.0 (high, 24 chunks)
  1800  30.21     70.0     16.7      53.3   during world save (not measured, low); unexplained 53.3
  2400  40.30     90.0     16.8      73.2   unexplained 73.2
  2401  40.34     35.0     16.8      18.2   GC 15.0 (high)          ← 14 ms pause + 1 ms truncation guard
spikes=5 lost=228.0 ms; share of lost time: gc 26% chunks 17% unexplained 57%
```
- Frames 2400 and 2401 are 35 ms apart, so they merge into one hitch: 91.4 ms lost, 16 % GC.
- The report reads: "5 spikes (1 severe). Explained 43 % of the lost time: garbage collection 26 %, chunk loading 17 %. 57 % not explained. 1 spike happened during a world save (not measured)."
- No advice fires: GC 26 % is under the 30 % threshold of the heap rule, and there's no FULL/STALL evidence.

---

## 5. Advice mapping

### 5.1 Cause → setting (verified keys)

| cause / fact | setting | where | notes |
|---|---|---|---|
| GC dominant + heap pressure (non-explicit FULL, stalls/degenerated, live set ≥ 75 % of -Xmx) | `-Xmx` (+2 GB, ≤ ½ system RAM, keep ≥ 4 GB for the OS) | launcher JVM args | id prefix `ram-` → RigTune 0.3+ shows the detected launcher's steps (RULES_SCHEMA "The ram- id prefix") |
| GC dominant, G1, short frequent young pauses, heap fine | GC choice (`-XX:+UseZGC`) | launcher JVM args | info only; defer the wording to r-jvm (jvm-gc.md). Generational ZGC is the only ZGC on 25 (§1.1) |
| EXPLICIT GCs | none; name the likely source (a mod, or direct memory) | — | don't suggest `-XX:+DisableExplicitGC`: `Bits.reserveMemory` relies on System.gc() for direct-buffer reclaim |
| chunk building + defer mode ZERO_FRAMES/ONE_FRAME | `sodium.performance.chunk_build_defer_mode` = `ALWAYS` (Sodium UI "Chunk Updates: Deferred") | config/sodium-options.json, `performance.chunk_build_defer_mode` | Gson `LOWER_CASE_WITH_UNDERSCORES` on `PerformanceSettings.chunkBuildDeferMode`; enum ALWAYS/ONE_FRAME/ZERO_FRAMES. An existing setting rule already does this for tier ≤ 3 |
| chunk building, builder threads set by hand | `sodium.performance.chunk_builder_threads` = `0` (UI "Chunk Update Threads", 0 = auto) | same file, `performance.chunk_builder_threads` | an existing setting rule (unticked) already offers it |
| CPU contention with many builder threads + DH | lower `chunk_builder_threads` (e.g. cores/4) | same | info; conservative |
| DH dominant | `dh.common.multiThreading.numberOfThreads` (lower, e.g. cores/4) or `threadRunTimeRatio` `"0.5"` | config/DistantHorizons.toml `[common.multiThreading]` | existing clamps by CPU tier exist; advice suggests going lower while exploring, or pre-generating |
| chunk loading dominant | `vanilla.renderDistance` −2..4, or `vanilla.simulationDistance` | options.txt | info; for elytra/teleport sessions also "pre-generate the world (Chunky)" in singleplayer |
| world save (singleplayer) | none (vanilla 5-min autosave) | — | info: points at the GC/heap advice if GC coincides |
| unknown dominant | none | — | info: driver update, background programs, shader packs |

### 5.2 Rules format: a new `stutterAdvice` section

The released **0.2.0 jar** (sha256 67275e23…, from the v0.2 session scratchpad) and the **0.3.0 build** (versions/26.2/build/classes) both parse the bundled rules-v2.json with an extra top-level `"stutterAdvice": [...]` without error and with unchanged counts (`base advice=23 patched advice=23 settings=56 schema=2 rev=13`, rulescheck/RulesCheck.java). Gson ignores unknown fields. So old clients ignore the section, and no `schemaVersion` bump is needed.

```json
"stutterAdvice": [
  { "id": "ram-stutter-gc-heap", "requires": ["stutter-doctor"], "kind": "warning", "impact": "high",
    "when": { "stutterShareAtLeast": { "gc": 30 },
              "anyOf": [ { "gcFullPausesAtLeast": 1 }, { "gcStallsAtLeast": 1 }, { "liveSetPercentAtLeast": 75 } ],
              "heapRaiseRoomMbAtLeast": 2048 },
    "title": "Stutter from memory pressure", "text": "Most of the lost time was garbage collection, and the heap is nearly full. Give Minecraft about 2 GB more RAM." },
  { "id": "stutter-gc-explicit", "requires": ["stutter-doctor"], "kind": "info", "impact": "medium",
    "when": { "gcExplicitPausesAtLeast": 2, "stutterShareAtLeast": { "gc": 10 } },
    "title": "Something forces full garbage collections", "text": "Vanilla never calls System.gc() in normal play; a mod (or running out of direct memory) does." },
  { "id": "stutter-sodium-defer", "requires": ["stutter-doctor"], "kind": "info", "impact": "medium",
    "when": { "stutterShareAtLeast": { "chunkBuild": 25 }, "modPresent": ["sodium"],
              "anyOf": [ { "settingIs": { "sodium.performance.chunk_build_defer_mode": "ZERO_FRAMES" } },
                         { "settingIs": { "sodium.performance.chunk_build_defer_mode": "ONE_FRAME" } } ] },
    "title": "Frames wait for chunk updates", "text": "Set Sodium's Chunk Updates to Deferred (Video Settings > Performance)." },
  { "id": "stutter-dh-threads", "requires": ["stutter-doctor"], "kind": "info", "impact": "medium",
    "when": { "modPresent": ["distanthorizons"], "stutterTaggedShareAtLeast": { "dh": 40 }, "cpuContentionShareAtLeast": 30 },
    "title": "Distant Horizons competes for the CPU", "text": "Lower Distant Horizons' CPU threads (Advanced > Multithreading) while exploring new terrain." }
]
```
- New condition keys exist only in the stutter evaluator: `stutterShareAtLeast` (claimed-ms share per cause), `stutterTaggedShareAtLeast` (share of spikes carrying a correlational tag), `gcFullPausesAtLeast`, `gcStallsAtLeast`, `gcExplicitPausesAtLeast`, `liveSetPercentAtLeast`, `heapRaiseRoomMbAtLeast` (`min(ram/2, ram − 4096) − heap`), `cpuContentionShareAtLeast`, `spikesPerMinuteAtLeast`, `gcCollector` (enum g1/zgc/shenandoah/parallel/serial).
- Existing keys (tiers, heap, RAM, `modPresent`, `settingIs`) keep their semantics.
- Unknown keys poison the condition as usual (RULES_SCHEMA "fail closed"). `requires` guards future stutter fields inside 0.4+.
- Implementation: the evaluator extends `ConditionEvaluator` with a `StutterFacts` context. `Recommender.SUPPORTED_FEATURES` stays empty for the main list; the stutter evaluator knows `"stutter-doctor"`.

Updater work:
- `tools/update_rules.py` must accept the new top-level field in knowledge.json, validate its conditions (a new key whitelist), and leave it out of rules-v1.json entirely (0.1.x never sees it).
- `SchemaConsistencyTest` gets the new vocabulary.
- RULES_SCHEMA.md gets a `## StutterAdvice (v2, 0.4+)` section.
- The advice only informs: settings are changed through the existing setting rules/apply path. A later version could add "Apply" for the named keys. **Out of scope for 0.4.**

---

## 6. Induced-stutter verification plan (real run, later; one client at a time, game-test lock)

Dev-only switches, read once at startup and never active unless set:
- `-Drigtune.dev.stutter.gcEveryMs=5000` — a daemon thread calls `System.gc()` on that period while the monitor is on.
- `-Drigtune.dev.stutter.script=teleport` — after the benchmark world is ready (WorldFlow READY):
  1. wait 20 s of standing still (control)
  2. teleport to ungenerated terrain: `server.getCommands().performPrefixedCommand(source, "tp @a 200000 200 200000")`, the same pattern BenchmarkWorld.setUp uses. The world is creative, commands are allowed (`LevelSettings(..., true, ...)`), and the seed is 8675309, so terrain that far out is never pre-generated
  3. wait 30 s
  4. `save-all`
  5. wait 10 s
  6. open the report and dump `stutter.json` + a screenshot

Runs (each ~2 min, same machine, rigtune-benchmark save):

| run | JVM | induced | expected attribution | pass criteria |
|---|---|---|---|---|
| A control | G1 default, -Xmx4G | nothing (stand still) | few spikes; unknown share reported honestly | ≤ 3 spikes/min; no cause with claimed ms unless a GC pause overlaps |
| B forced GC | G1, -Xmx4G | `gcEveryMs=5000` × 60 s | each forced Full GC (`G1 Old Generation`, cause System.gc()) whose pause ≥ 30 ms → a spike attributed to GC, **high**, flag EXPLICIT | ≥ 90 % of forced pauses ≥ 30 ms matched to a spike frame; each mapped pause within ±2 ms of `F_i`; GC ranked first; advice `stutter-gc-explicit` fires |
| C teleport | G1, -Xmx4G | teleport + save-all | spikes after the teleport tagged "after teleport"; the packets phase explains ≥ 30 % of the lost time in that 30 s; chunk loads > 0; Sodium backlog > 0; Worker-Main/Server CPU up in the sampler; a save window recorded (`AFTER_SAVE` seen) | as stated; zero spikes claimed as GC without an overlapping pause |
| D ZGC control | `-XX:+UseZGC -Xmx4G` | `gcEveryMs=5000` | pauses < 1 ms → **no** GC-attributed spikes; Cycles never used as pauses | 0 spikes with GC claimed ms > 1; the Cycles events present in the log |
| E Serial (visibility) | `-XX:+UseSerialGC -Xmx3G` | normal play of run C | long young/full pauses → high-confidence GC | GC share > 50 % |
| F overhead | G1 | benchmark sweep with the monitor on vs off, 3 interleaved pairs | — | avg FPS and 1 % low differences within the run-to-run noise (report both); the render thread's allocated bytes/frame from the monitor = 0 (unit test) |

Record these in docs/v0.4/verification/stutter/ as log excerpts, stutter.json and screenshots:
- the thread-name census (confirm the `DH-` threads when DH is installed)
- the calibrated GC offset
- the save-window durations

Full-GC durations for a multi-GB MC heap are **UNVERIFIED** (the probe heap was 256 MB). If B's forced pauses are under 30 ms, retry with `-Xmx6G` after a few minutes of play.

---

## 7. Report screen and persistence

**Entry:**
- A "Stutter Doctor" button in RigTuneScreen's button row (one line).
- An on/off toggle "Session monitor" in RigTuneSettingsScreen (default **off**; tooltip: "records frame times on this PC only; ~2 MB of memory while on"). The toggle can also sit on the Doctor screen itself.
- The benchmark can switch capture on for its own sweeps (in memory) and add one line to the result screen ("2 spikes, both GC").

**StutterScreen layout (all strings `rigtune.stutter.*` in en_us.json):**
1. **Header:** session length, gameplay time, frames, avg FPS, 1 % low; "12 spikes (9 minor, 2 major, 1 severe), 1.8 s lost".
2. **Frame-time histogram, time-weighted.** Buckets < 4.2 · 4.2–8.3 · 8.3–16.7 · 16.7–33 · 33–50 · 50–100 · 100–250 · 250–1000 · ≥ 1000 ms. Bar length = share of play *time*; the frame count is shown as text. Colour: green below the baseline's bucket, amber 33–100, red ≥ 100.
3. **Likely causes:** one bar per cause with the claimed-ms share plus "not explained X %". Correlational tags go below as text ("7 of 12 spikes during world saves (not measured)").
4. **Worst spikes (top 10):** clock time (HH:MM:SS) and mm:ss into the session, duration, baseline, causes with a confidence marker (●●● high, ●● medium, ● low).
5. **Advice:** the stutterAdvice entries that fired. `ram-` entries show the launcher steps like the main screen.
6. **Buttons:** Start/Stop monitor, Clear, Copy summary (clipboard only; no network), Done. It must fit 640×480 @ GUI scale 2 (the existing screens' constraint).

**Persistence:** `config/rigtune/stutter.json`, written atomically (`AtomicFiles`).
```json
{ "schema": 1, "sessions": [ { "startedAt": "...", "mc": "26.3", "collector": "G1", "heapMaxMb": 6144,
  "gameplaySeconds": 812, "frames": 97000, "histogramMs": [..9 counts..], "histogramTimeMs": [..],
  "spikes": {"minor": 9, "major": 2, "severe": 1, "freeze": 0}, "lostMs": 1810,
  "causes": {"gc": 0.44, "chunkLoad": 0.12, "unknown": 0.31}, "tags": {"worldSave": 7},
  "worst": [ {"t": 431.2, "ms": 212, "baseMs": 7.1, "causes": ["gc:high:FULL"]} ],
  "facts": {"liveSetPct": 81, "fullGcs": 2, "stalls": 0, "gcOffsetMs": 21.7},
  "settings": {"renderDistance": "16", "sodium.performance.chunk_builder_threads": "0"}, "advice": ["ram-stutter-gc-heap"] } ] }
```
- The last **5** sessions, each capped at 10 worst spikes; the file is capped at **64 KiB** (the oldest sessions are dropped first). The raw frame ring is never persisted.
- The file is local only. It isn't in the share report unless the user presses Copy summary.
- Older RigTune versions don't know the file and never read it. A future `schema: 2` is ignored by 0.4 (read only `schema == 1`).

---

## 8. Files and classes (grouped for parallel ownership)

**A. core (pure Java, no MC; unit-tested), package `core/stutter/`:**
- `FrameRing` (frame-end ring, histogram, candidate ring, online EWMA)
- `EventLog` (single-writer primitive ring)
- `GcEvents` (ring + `GcKind` classifier from bean/action/cause + collector family)
- `GcClock` (uptime anchor + min-offset calibration + interval mapping)
- `SpikeDetector` (median window, rule, hitch merging, exclusions)
- `Attributor` (§4.2)
- `StutterReport` (records)
- `StutterFacts` + `StutterAdvisor` (condition keys, evaluates `stutterAdvice`)
- `StutterStore` (stutter.json, bounded)

**B. client capture, package `client/stutter/`:**
- `StutterMonitor` (enable/disable, owns the buffers, `onFrame`, END_CLIENT_TICK work: screen/level/teleport/speed, chunk counter)
- `GcListener` (register/unregister on the MX beans)
- `ThreadSampler` (4 Hz daemon)
- `SodiumProbe` (reflection + vanilla fallback)
- `DhProbe` (DH threads config via `DhApi`, extends `compat/DhCompat` or sits next to it)
- `DevStutter` (the §6 dev properties)
- `mixin/MinecraftFrameMixin` (phase timers)

**C. UI:** `client/ui/StutterScreen` (+ a small histogram/bars widget), strings in en_us.json.

**D. rules:**
- knowledge.json `stutterAdvice`
- tools/update_rules.py + its tests
- rules-v2.json regenerate (then **rerun `./gradlew test`**, lesson from v0.2)
- RULES_SCHEMA.md section
- `SchemaConsistencyTest`

**E. tests:** unit tests for A, `StutterGameTest`, the §6 run script and README.

**Hotspot files (minimal edits):**
- `client/mixin/DebugScreenOverlayMixin.java`: +1 line (`StutterMonitor.onFrame(frameDuration);`)
- `rigtune.client.mixins.json`: +1 entry `MinecraftFrameMixin`
- `RigTuneClient.onInitializeClient`: +1 line `StutterMonitor.install();`, which registers `ClientChunkEvents`, `ClientLevelEvents`, `ServerLifecycleEvents` saves and a tick hook. Registration is cheap, and the listeners early-return when the monitor is off.
- `RigTuneScreen`: +1 button in the `buttons` list
- `RigTuneSettingsScreen` + `ClientSettings`: +1 `volatile boolean stutterMonitor = false` and one `CycleButton` row
- `en_us.json`: one appended `rigtune.stutter.*` block
- gametest `fabric.mod.json`: +1 entrypoint line (`StutterGameTest`)
- `FrameTimes.java`: unchanged

Owners: A and D can start at once, B needs A's interfaces, and C needs `StutterReport`. Only B touches the mixins, and only C touches the screens.

---

## 9. Test plan

**Unit (JUnit, both MC versions via Stonecutter):**
- `SpikeDetectorTest`, on synthetic traces:
  - flat 60/144/240 fps with jitter → 0 spikes
  - a single 2× frame at 240 fps (8.3 ms) → not a spike (20 ms floor)
  - 45 ms at 60 fps → spike
  - a burst of 5 spikes → one hitch
  - a baseline drift from 60 to 30 fps → no spike storm
  - menus excluded
- `AttributorTest`:
  - the §4.4 worked example, asserted exactly
  - a pause straddling two frames splits its overlap
  - `+1 ms` truncation
  - Cycles never claim ms
  - correlational tags never claim ms
  - the sum of claims ≤ lost time
- `GcClockTest`:
  - recorded notification tuples from §1 (G1 and ZGC real values) → offset within 0.3 ms of the log truth
  - mapped `System.gc()` pauses inside their call windows
- `GcKindTest`: every bean/action/cause string seen in §1.2 (G1, ZGC, Shenandoah, Serial, Parallel), including the Shenandoah phase names.
- `FrameRingAllocationTest`: warm up, then 1 M `onFrame` calls with `getCurrentThreadAllocatedBytes` delta == 0 (skip if `isThreadAllocatedMemorySupported()` is false). Also wrap-around and snapshot ordering.
- `StutterAdvisorTest`: each `stutterAdvice` rule's fire/no-fire cases; unknown keys → UNKNOWN; `requires` with an unknown feature → skipped.
- `StutterStoreTest`: the size cap, 5 sessions, a corrupt file → empty, `schema != 1` ignored.
- `RulesBackCompatTest`: a v2 document with `stutterAdvice` still parses to the same advice/settings counts (as in §5.2).
- Python: update_rules.py accepts, validates and projects out `stutterAdvice` (not in rules-v1.json).

**Game test** (`StutterGameTest`, Fabric client game-test harness):
- Enable the monitor, create a singleplayer world, wait 100 ticks.
- Call `System.gc()` on the client thread (a real Full GC), wait 40 ticks.
- Run `save-all` through the server's commands.
- Open StutterScreen.
- Assert:
  - ≥ 1 GC event with cause `System.gc()` recorded and calibrated
  - its mapped interval overlaps a recorded frame interval
  - ≥ 1 save window with begin and end
  - the screen renders without exceptions
  - Screenshot.
- Don't assert spike counts or thresholds: the harness's tick sync makes frame timing unrepresentative (PROGRESS lessons). Also check that disabling the monitor unregisters the GC listener (`getNotificationInfo` unchanged) and stops the sampler thread (no thread named `RigTune stutter sampler`).

**Overhead:**
- The allocation unit test.
- A JMH-free micro-benchmark like `CostProbe` in the test sources (report ns/frame; fail over 200 ns as a gross regression guard, not a precise benchmark).
- The in-game A/B of §6 run F.
- The sampler's own CPU from `ThreadMXBean.getThreadCpuTime(sampler)` over a session: expect < 0.05 % of one core.

---

## 10. Open questions and UNVERIFIED

1. Phase-timer mixin: the injection points are verified in bytecode only. The first build must compile and run them on both versions. With `defaultRequire: 1`, a broken target fails loudly, which is the desired behaviour.
2. The `DH-` prefix on every DH worker thread, and how DH's LOD generation shows up in the sampler, are **UNVERIFIED** at runtime (run C/D with DH installed).
3. Whether autosave produces client-side spikes on this machine: **UNVERIFIED** (run C).
4. G1 Full GC pause length on a real MC heap: **UNVERIFIED** (run B sizing).
5. System-wide CPU load (other programs): no cheap JDK API on Windows (the `OperatingSystemMXBean` load calls take 110–125 ms and return −1). OSHI cost is **UNVERIFIED**, so this stays in "unknown".
6. Footprint: 1.8 MiB while on. Does r-footprint's budget accept it? The frame ring could shrink to 2^16 (4.5 min @240 fps) if needed.
7. GC-choice advice wording and thresholds: coordinate with r-jvm (jvm-gc.md). The Doctor only emits facts and the `stutter-gc-*` ids.
8. Should the benchmark auto-enable capture (in memory) during its sweeps? Proposed yes, as a one-line result, low priority.
9. Should the session monitor survive restarts (the setting persists; the data only as summaries)? Proposed: the setting persists, and the buffers start empty on each launch.
