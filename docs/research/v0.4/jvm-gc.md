# JVM and GC advice: research (v0.4 P1 item 6)

Owner: r-jvm. Date: 2026-09-26. Base: `feat/v0.4.0` = v0.3.0 (a601e48). Research only: nothing in `src/` or `rules/` of the main checkout was changed.

## Evidence and sources

- **JDK used for every flag test and game run:** portable Temurin 25.0.4.1+1 (`C:/Dev/Tools/jdk/jdk-25.0.4.1+1`). The user's real instance runs Azul Zulu 25.0.3 (its crash reports say `Java Version: 25.0.3, Azul Systems, Inc.`). The 26.2 version JSON asks for `java-runtime-epsilon`, major version 25.
- **Machine:** Ryzen 7 7800X3D (8 cores, 16 threads), RX 7800 XT, 32 GB (the JVM sees `Memory: 31849M`), Windows 11 build 26200.
- **Experiment worktree:** `C:/Dev/Worktrees/rigtune-r-jvm` (branch `research/jvm`, never committed). Its uncommitted patch is for the experiment only: `build.gradle` reads extra JVM arguments from `-PrigtuneJvmArgsFile` and opens a 1920x1080 window for `runBenchmarkAutorun`; `DevAutorun` gains a `gc-measure` mode; `FrameTimes` counts GC-bean deltas inside the recorded windows; `FrameRecorder.countAbove` counts slow frames; `BenchmarkController` keeps `options.txt`'s frame cap when `-Drigtune.dev.keepFpsCap=true` (§4.3).
- **Raw data** (scratchpad `r-jvm/`): `flag-verify.txt` and `flag-verify2.txt` (every `java <flag> -version`), `flags-final-default.txt` (`-XX:+PrintFlagsFinal`), `probe/` (MXBean probes and their output), `runs/NN-<batch>-<config>/` (`jvmargs.txt`, `latest.log`, `gc.log`, `gradle.log` per game run), `cfg/` (the JVM argument files), `runner.sh` and `withlock.sh` (runs under the game-test lock), `results.json`, `cpu-samples.txt`, `cpusampler.ps1` and `cpu-per-run.json` (background CPU load), `pretouch.txt` (JVM init with `AlwaysPreTouch`), `analyze.py`, `cpujoin.py` and `mdtable.py`, and `src/` (the primary sources fetched for this doc).
- **Pinned primary sources:** OpenJDK `jdk25u` at 49411542 (2026-09-15): `runtime/arguments.cpp`, `os/windows/os_windows.cpp`, `gc/shared/gcConfig.cpp`, `runtime/os.cpp`, `prims/jvm.cpp`, `java/nio/Bits.java`; `jdk11u` and `jdk17u` `arguments.cpp` for flag history; the JDK 25 `java` man page (docs.oracle.com/en/java/javase/25/docs/specs/man/java.html, "man page" below); the JDK 25 GC tuning guide (its G1 tuning, ZGC and Available Collectors chapters); JEPs 248, 291, 363, 374, 439, 450, 474, 490, 519, 521 and 534; modrinth/code 22ccdc1 (v0.21.5); PrismLauncher ea87ffc (11.1.0); ATLauncher 1dac5d8 (v3.4.41.3); PaperMC's Aikar's flags page; the MC 26.2 jars in the Loom cache (`javap`).
- Anything I couldn't check is marked **UNVERIFIED**.

## 0. Summary

**Measured on this machine** (26.2 dev client with Sodium and Fabric API, RigTune's benchmark world at render distance 16, 80 s of frames per run, uncapped at about 1,900 FPS, `-Xmx4G`; clean runs only; medians with min-max, §4):

| Config | Avg FPS | 1% low | p99 ms | GC pauses per 80 s (count / total / longest) | Heap committed |
|---|---|---|---|---|---|
| G1 default | 1931 (1912-1944) | 610 (572-628) | 1.33 | 27 / 84 ms / 6.5 ms | 1.3 GB |
| Launcher default set (official launcher, ATLauncher) | 1937 (1931-1947) | 616 (593-638) | 1.31 | 45 / 119 ms / 11.3 ms | 1.1 GB |
| G1 + `-XX:+UseCompactObjectHeaders` | 1915 (1914-1923) | 580 (578-616) | 1.35 | 45 / 103 ms / 9.4 ms | 0.9 GB |
| Aikar's flags (`-Xms4G` + 19 flags) | 1926 (1917-1943) | 622 (587-648) | 1.34 | 6 / 10 ms / 4.4 ms | 4.1 GB |
| ZGC | 1922 (1892-1942) | 627 (593-633) | 1.33 | 23 / <1 ms / 0.1 ms | 3.9 GB |

- **Frame rates are the same for every configuration, within noise.** The medians span 1.1% in average FPS, less than one configuration's own run-to-run spread; 1% lows and p99 overlap completely; every configuration had 1-6 frames over 8 ms per 150,000. Capped at 140 FPS, G1 and ZGC were also identical (1% low 126 vs 127, no frame over 16.7 ms). A 2 GB or 7.9 GB G1 heap didn't change frame rates either; 2 GB only doubled the number of collections.
- **The GC differences are real but invisible here.** ZGC's pauses are ~0.01 ms against G1's 2-12 ms, and Aikar's set cuts G1 to 6 short pauses. Neither shows up in frames at these frame rates on this 16-thread CPU. What does differ is **memory**: ZGC keeps the heap near `-Xmx` (3.9 of 4 GB), Aikar's `-Xms` commits all 4 GB, and G1's defaults use 1.3 GB.
- **Flag classes on JDK 25** (all verified with `java <flag> -version`, §2):
  - *Won't start*, so never seen in a running game: CMS/ParNew and the CMS flags, `AggressiveOpts`, `UseBiasedLocking`, `PermSize`/`MaxPermSize`, experimental flags without `-XX:+UnlockExperimentalVMOptions`, two collectors at once, `-Xms` > `-Xmx`, `UseTransparentHugePages` on Windows.
  - *Ignored with a warning*: `±ZGenerational` (removed in 24, JEP 490; a future JDK will refuse it), `UseLargePages` without the Windows privilege, `UseCompressedClassPointers` (deprecated in 25).
  - *Silently no effect*: `UseNUMA` on Windows, the `-Daikars` markers, flags equal to their defaults (`UseG1GC`, `ParallelRefProcEnabled`, `MaxGCPauseMillis=200`, `G1HeapWastePercent=5`).
  - *Harmful or risky*: `-Xmn` (disables G1's pause-time control, per the JDK docs), `UseSerialGC`/`UseParallelGC`/`UseEpsilonGC`, `DisableExplicitGC` (disables OOM recovery and direct-buffer cleanup, with no benefit).
  - *Useful*: nothing beyond a right-sized `-Xmx`.
- **The running game can check this itself.** `RuntimeMXBean.getInputArguments()` shows every JVM option however it was passed. `HotSpotDiagnosticMXBean.getVMOption` reports "does not exist" for a flag the JVM ignored, and the real value for one it overrode (§1.3). That gives a JDK-version-independent "ignored flag" check with no table to maintain.
- **Recommended default advice:** keep Java's default G1 and set only the heap, in the launcher's memory setting (the v0.3 `ram-*` rules, unchanged). Tell players to remove flags that are ignored (`ZGenerational`), harmful (`-Xmn`, Serial/Parallel/Epsilon) or pure cost (Aikar's server set, `DisableExplicitGC`, a large `-Xms` on a small-RAM PC). Name each flag and give the launcher's Java-arguments steps. **Never recommend** ZGC, Shenandoah, compact object headers, `-Xms`/`AlwaysPreTouch`, large pages or any tuning flag. **Leave alone** the launchers' default sets and a working ZGC with at least 4 GB. Details and rule drafts are in §5.

## 1. What the running game can read

### 1.1 Real command lines, per launcher

| Launcher | How it builds the JVM arguments (order matters: for a repeated `-Xmx` the **last one wins**) | Evidence |
|---|---|---|
| **Modrinth App** | The version JSON's `jvm` arguments, then `-Xmx<memory>M`, the log4j config argument, `-javaagent:<Modrinth's agent>`, `-Dmodrinth.internal.ipc.host/port`, `-Dmodrinth.internal.quickPlay.*`, then the user's **Java arguments** (so an `-Xmx` typed there wins over the memory slider). Default Java arguments: none. | modrinth/code 22ccdc1 `packages/app-lib/src/launcher/args.rs:112-205` (`-Xmx` at :162, custom args at :205). **The user's real instance** (read-only): every crash report says `JVM Flags: 2 total; -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump -Xmx6144M`. Minecraft's "JVM Flags" line is `RuntimeMXBean.getInputArguments()` filtered to arguments starting with `-X` (26.2 `net.minecraft.SystemReport`, `javap`: `getInputArguments` → `filter(s -> s.startsWith("-X"))`). So the user runs default G1 with a 6 GB heap and no GC flags. `launcher_log.txt` doesn't log the command line. |
| **Official Minecraft Launcher** | The installation's **JVM Arguments** field, whose default is `-Xmx2G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M`, plus the version JSON's arguments and `-Dminecraft.launcher.brand=minecraft-launcher -Dminecraft.launcher.version=<v>`. | **On this machine** (read-only): `%APPDATA%/.minecraft/launcher_log0.txt` (launcher 3.22.20, 2025-11-12) logs each `JavaLaunchConfiguration.cpp(281)] Java argument:` line: `-XX:HeapDumpPath=...`, `-Xss1M`, `-Djava.library.path=...`, `-Djna.tmpdir=...`, `-Dorg.lwjgl.system.SharedLibraryExtractPath=...`, `-Dio.netty.native.workdir=...`, `-Dminecraft.launcher.brand=minecraft-launcher`, `-Dminecraft.launcher.version=3.22.20`, `-cp <classpath>`, then exactly the default set above. This also confirms v0.3's `OFFICIAL_BRAND` literal. Caveat: that launch was a 2025 game version; a 26.x launch by the official launcher wasn't observed (the log files since July 2026 contain no launch). |
| **Prism Launcher** | `-Duser.language=en`, then the user's **Java Arguments** first, then (Windows) `-XX:HeapDumpPath=...`, then `-Xms<Minimum>m -Xmx<Maximum>m` from the Memory box (so an `-Xmx` typed in Java Arguments **loses**: "custom args go first. we want to override them if we have our own here"), then `--add-opens java.base/java.net=ALL-UNNAMED` when online fixes apply. Defaults: Java Arguments empty, Minimum 512 MB, Maximum 4096 MB (or total RAM / 1.5 below 6 GB). | PrismLauncher ea87ffc `launcher/minecraft/MinecraftInstance.cpp:572-630`, `launcher/BaseInstance.cpp:485-488`, `launcher/Application.cpp:735-748`, `launcher/SysInfo.cpp:88-95` (this resolves v0.3's "formula UNVERIFIED"). |
| **ATLauncher** | `-Xmx<max>M`, `-XX:MetaspaceSize=<permGen>M`, `-Duser.language=en -Duser.country=US`, the log4j argument, then the **Java Parameters** (default = the same set as the official launcher, without `-Xmx`), then the version JSON's arguments. Default maximum memory 4096 MB. | ATLauncher 1dac5d8 `mclauncher/MCLauncher.java:280-367`, `constants/Constants.java:200` (`DEFAULT_JAVA_PARAMETERS = "-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"`), `data/Settings.java:104`. |
| **CurseForge app** | Starts the game through the official launcher (brand `minecraft-launcher`, v0.3 ws-c). `minecraftinstance.json` has a top-level `javaArgsOverride` key (null in every real file v0.3 collected). The UI field names and defaults are **UNVERIFIED** (closed source). | v0.3 fixture `src/test/resources/launcher/curseforge/minecraftinstance.json`. |
| **Loom dev/CI runs** | No `-Xmx`: the JVM picks 25% of RAM (`MaxRAMPercentage` 25, so 7964 MB here; initial 498 MB). | `-XX:+PrintFlagsFinal` (`MaxHeapSize = 8350859264 {ergonomic}`), `gc+init` log of the warm-up run. |

The 26.2 version JSON (`%APPDATA%/.minecraft/versions/26.2/26.2.json`, read-only) adds on Windows: `-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump`, `--sun-misc-unsafe-memory-access=allow`, `--enable-native-access=ALL-UNNAMED`, four `-D...` native paths, the brand/version properties and `-cp` (plus `-Xss1M` only for `"arch": "x86"`).

**Consequence for advice:** heap advice keeps pointing at each launcher's *memory* control (v0.3's steps), never at the Java arguments box. That's required for Prism, where the Memory box overrides an `-Xmx` typed in Java Arguments.

### 1.2 `RuntimeMXBean.getInputArguments()` (probe, `probe/Probe.java`, output `probe/out-*.txt`)

- It returns every JVM option, including `-D` properties and launcher options like `--enable-native-access=ALL-UNNAMED`. It leaves out `-cp`/the class path and the main class's arguments. Example with a Modrinth-shaped line: `[-XX:HeapDumpPath=..., --sun-misc-unsafe-memory-access=allow, --enable-native-access=ALL-UNNAMED, -Djava.library.path=C:/x/java, -Dminecraft.launcher.brand=theseus, -Dminecraft.launcher.version=0.21.5, -Xmx6144M]`.
- `JAVA_TOOL_OPTIONS` shows up too, **first** (`[-XX:+UseZGC, -XX:+ZGenerational, -Xmx4G]` for `JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational"` plus `-Xmx4G` on the command line; the JVM prints `Picked up JAVA_TOOL_OPTIONS`). `JDK_JAVA_OPTIONS` is spliced in by the `java` launcher, and `@argfiles` are expanded. So the list is what the JVM saw, whichever way it was passed.
- The list is **not** "what the user typed": Modrinth adds `-javaagent`, log4j and `-Dmodrinth.internal.*`, Prism adds `-Duser.language=en` and `--add-opens`, ATLauncher adds `-XX:MetaspaceSize=...`, and the version JSON adds the rest. A classifier has to ignore every argument it doesn't have a rule for, and it must never offer to rewrite the whole line.
- **Privacy:** the list contains absolute paths with the Windows user name (`-Djava.library.path`, `-javaagent`, log4j). RigTune must never display, log or share raw arguments. It should show only the flag names it classified (from its own table), and the share report should add at most the GC name and a count ("GC: G1, 2 Java argument notes").

### 1.3 `HotSpotDiagnosticMXBean.getVMOption(name)` (probe)

It gives the effective value **and its origin**: `DEFAULT`, `VM_CREATION` (command line or `JDK_JAVA_OPTIONS`), `ENVIRON_VAR` (`JAVA_TOOL_OPTIONS`), `ERGONOMIC`, `CONFIG_FILE`, `MANAGEMENT`, `ATTACH_ON_DEMAND` or `OTHER`. Verified readable on 25.0.4.1: `UseG1GC`, `UseZGC`, `UseShenandoahGC`, `UseSerialGC`, `UseParallelGC`, `MaxHeapSize`, `InitialHeapSize`, `MinHeapSize`, `SoftMaxHeapSize` (writeable), `UseCompactObjectHeaders`, `UseCompressedOops`, `UseCompressedClassPointers`, `AlwaysPreTouch`, `DisableExplicitGC`, `MaxGCPauseMillis`, `G1HeapRegionSize`, `G1ReservePercent`, `InitiatingHeapOccupancyPercent`, `ParallelGCThreads`, `ConcGCThreads`, `UseLargePages`, `UseStringDeduplication`, `UseNUMA`, `PerfDisableSharedMem`, `UnlockExperimentalVMOptions`, `MaxTenuringThreshold`, `SurvivorRatio`, `NewSize`, `MaxNewSize`, `ThreadPriorityPolicy`, `UseVectorCmov`, `MaxRAMPercentage`, `ShenandoahGCMode`, `HeapDumpPath`, `ThreadStackSize`, `ParallelRefProcEnabled`, `G1RSetUpdatingPauseTimePercent`, `G1MixedGCCountTarget`, `G1HeapWastePercent`.

Three findings make a **JDK-version-independent "ignored flag" check** possible (probe `probe/Probe2.java`):
1. An **obsolete** flag that the JVM accepted with a warning doesn't exist any more: `getVMOption("ZGenerational")` throws `IllegalArgumentException: VM option "ZGenerational" does not exist`, although `-XX:+ZGenerational` is in the input arguments.
2. A flag the JVM **overrode** reads back with the other value: `-XX:+UseNUMA` reads `false` (origin `VM_CREATION`) on Windows, and `-XX:+UseLargePages` reads `false` without the "Lock pages in memory" privilege.
3. **Experimental** flags (`G1NewSizePercent`, `G1MaxNewSizePercent`, `G1MixedGCLiveThresholdPercent`, `UseCriticalJavaThreadPriority`, `UseFastUnorderedTimeStamps`, `UseEpsilonGC`) are readable only when `-XX:+UnlockExperimentalVMOptions` is on; otherwise they "don't exist". That's harmless: without the unlock they can't be on the command line at all (the JVM refuses to start).

So: for each `-XX:[+-]Name[=v]` in the input arguments, "not found" means **ignored by this JVM**, and a boolean whose effective value differs from the typed one means **overridden by this JVM**. Everything else is honoured. That needs no per-JDK table and keeps working when players run Java 26 or 27.

`com.sun.management` may be missing on a non-HotSpot JVM (OpenJ9): `ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)` then throws. Every call must be wrapped (catch `Throwable`, like `LauncherDetector`), and the check turns itself off.

### 1.4 Which GC is running

`GarbageCollectorMXBean` names (verified on 25.0.4.1; they match r-stutter's `docs/research/v0.4/stutter.md` §1): G1 `G1 Young Generation`, `G1 Concurrent GC`, `G1 Old Generation`; ZGC `ZGC Minor Cycles`, `ZGC Minor Pauses`, `ZGC Major Cycles`, `ZGC Major Pauses`; Shenandoah `Shenandoah Pauses`, `Shenandoah Cycles`; Serial `Copy`, `MarkSweepCompact`; Parallel `PS Scavenge`, `PS MarkSweep`. The `Use*GC` VM options agree, and they also say whether the choice was typed (`VM_CREATION`/`ENVIRON_VAR`) or ergonomic. With no GC flag at all, G1 is chosen on any machine with at least 2 active CPUs and at least 1792 MB of RAM, otherwise Serial (`gcConfig.cpp:97-109`, `os.cpp:1928-1947`; JEP 248). Every PC that can run 26.2 gets G1.

## 2. Flag table for JDK 25 (verified)

Every row was run as `java <flag> -version` on Temurin 25.0.4.1+1 (`flag-verify.txt`, warnings in `flag-verify2.txt`); defaults come from `-XX:+PrintFlagsFinal` (`flags-final-default.txt`).

Classes: **E** = the JVM refuses to start, so the flag **can never be seen in a running game** (the launcher shows the error and RigTune never loads). **W** = accepted, ignored or overridden with a warning. **S** = silently has no effect (already the default, overridden without a warning, or not a JVM option). **H** = honoured, harmful or risky for a game client. **N** = honoured; no benefit shown for a client (neutral). **U** = useful.

### 2.1 Aikar's flags (PaperMC's recommended **server** flags: the page's command ends in `-jar paper.jar --nogui` and advises "at least 6-10GB, no matter how few players")

| Flag | JDK 25 result | Status and source | Effect on a client | Class |
|---|---|---|---|---|
| `-XX:+UseG1GC` | accepted | G1 is already the ergonomic default (§1.4, JEP 248) | redundant; harmless | S |
| `-XX:+ParallelRefProcEnabled` | accepted | already `true {default}` here; man page: "By default, collectors employing multiple threads perform parallel reference processing if the number of parallel threads to use is larger than one" | redundant | S |
| `-XX:MaxGCPauseMillis=200` | accepted | man page: "By default, for G1 the maximum pause time target is 200 milliseconds" | redundant | S |
| `-XX:+UnlockExperimentalVMOptions` | accepted | only an enabler; the three experimental flags below make the JVM refuse to start without it ("VM option 'G1NewSizePercent' is experimental and must be enabled via -XX:+UnlockExperimentalVMOptions") | none by itself | N |
| `-XX:+DisableExplicitGC` | accepted | makes `System.gc()` a no-op (`jvm.cpp:449-455`). Vanilla 26.2 calls `System.gc()` only on the out-of-memory path (`Minecraft.emergencySave`, `MemoryReserve.release`: bytecode search of both 26.2 jars; stutter.md §1 found the same). `java.nio.Bits.reserveMemory` calls `System.gc()` to free direct buffers before it gives up (`Bits.java:143`) | no benefit in play; it disables two recovery paths | H (low) |
| `-XX:+AlwaysPreTouch` | accepted | man page: "touch every page on the Java heap after requesting it from the operating system". With `-Xms` = `-Xmx` the whole heap is committed and touched at startup | the whole `-Xms` stays resident in RAM; startup cost is small (+75 ms per 4 GB with G1, §3.2). The JDK 25 G1 guide itself suggests `-Xms` = `-Xmx` plus `AlwaysPreTouch` to avoid commit delays; none showed in frame times here | N (RAM cost) |
| `-XX:G1NewSizePercent=30` | **E** without the unlock; accepted with it | experimental; default 5 (man page) | fixes young gen ≥ 30% of the heap; G1 tuning guide: avoid fixing young-gen size | N |
| `-XX:G1MaxNewSizePercent=40` | **E** without the unlock | experimental; default 60 | caps young gen | N |
| `-XX:G1HeapRegionSize=8M` | accepted | default at a 4 GB heap is 2 MB (`gc+init`: `Heap Region Size: 2M`) | fewer humongous objects | N |
| `-XX:G1ReservePercent=20` | accepted | default 10 | keeps 20% of the heap free | N |
| `-XX:G1HeapWastePercent=5` | accepted | default 5 | redundant | S |
| `-XX:G1MixedGCCountTarget=4` | accepted | default 8 | | N |
| `-XX:InitiatingHeapOccupancyPercent=15` | accepted | default 45; with `G1UseAdaptiveIHOP` (default on) it's only the starting value (man page) | | N |
| `-XX:G1MixedGCLiveThresholdPercent=90` | **E** without the unlock | experimental; default 85 | | N |
| `-XX:G1RSetUpdatingPauseTimePercent=5` | accepted | default 10 | | N |
| `-XX:SurvivorRatio=32` | accepted | default 8 | | N |
| `-XX:+PerfDisableSharedMem` | accepted | turns off the `hsperfdata` shared memory that `jps`/`jstat` read | none for a client | N |
| `-XX:MaxTenuringThreshold=1` | accepted | default 15; objects that survive one young GC are promoted | | N |
| `-Dusing.aikars.flags=https://mcflags.emc.gs`, `-Daikars.new.flags=true` | accepted | plain system properties; no JVM effect | none; **useful as a detector** that the set was pasted | S |

### 2.2 Other flags seen in the wild

| Flag | JDK 25 result | Status and source | Effect on a client | Class |
|---|---|---|---|---|
| `-XX:+UseZGC` | accepted | generational only since JDK 24 (JEP 474 made it the default mode in 23, JEP 490 removed non-generational mode in 24); JDK 25 ZGC guide: "without stopping the execution of application threads for more than a millisecond ... The main tuning knob is to increase the maximum heap size"; Available Collectors: "at the cost of some throughput" | see §4 (sub-ms pauses, larger footprint) | N |
| `-XX:+ZGenerational`, `-XX:-ZGenerational` (with or without `UseZGC`) | accepted, `OpenJDK 64-Bit Server VM warning: Ignoring option ZGenerational; support was removed in 24.0` | `arguments.cpp:547` (deprecated 23, obsolete 24, expiry undefined). JEP 490: "The option will expire in a future release, at which point it will not be recognized by the HotSpot JVM, which will refuse to start." `getVMOption` → not found (§1.3) | none now; **a future JDK won't start** with it | W |
| `-XX:+UseShenandoahGC` | accepted (Temurin includes Shenandoah; other vendors' builds UNVERIFIED) | JEP 379 (product in 15); generational mode is a product mode in 25 (JEP 521; `-XX:ShenandoahGCMode=generational` accepted without unlock) | not measured | N |
| `-XX:ShenandoahGCMode=iu` | **E** (`Unknown -XX:ShenandoahGCMode option`), also with the unlock | the IU mode is gone | | E |
| `-XX:+UseConcMarkSweepGC`, `-XX:+UseParNewGC`, `-XX:+CMSParallelRemarkEnabled`, `-XX:+UseCMSInitiatingOccupancyOnly`, `-XX:CMSInitiatingOccupancyFraction=70`, `-XX:+CMSIncrementalMode`, `-XX:+CMSClassUnloadingEnabled` | **E** (`Unrecognized VM option`) | CMS deprecated in 9 (JEP 291), removed in 14 (JEP 363); ParNew only worked with CMS | | E |
| `-XX:+AggressiveOpts` | **E** | `jdk11u arguments.cpp:535`: deprecated 11, obsolete 12, expired 13 | | E |
| `-XX:+UseBiasedLocking` | **E** | JEP 374; `jdk17u arguments.cpp:531`: deprecated 15, obsolete 18, expired 19 | | E |
| `-XX:PermSize=`, `-XX:MaxPermSize=` | **E** | obsolete since 8 (`jdk11u arguments.cpp:556-557`), now expired | | E |
| `-XX:+UseStringCache`, `-XX:+UseSplitVerifier`, `-XX:+UseLargePagesInMetaspace`, `-XX:+ScavengeBeforeFullGC`, `-XX:+UseMembar`, `-XX:+UseCompilerSafepoints`, `-XX:+UseSHM`, `-XX:+UseAdaptiveGCBoundary`, `-XX:+UseLWPSynchronization`, `-XX:+UseLinuxPosixThreadCPUClocks` (on Windows) | **E** | expired | | E |
| `-XX:+UseLargePages` | accepted; on Windows without the privilege: `JVM cannot use large page memory because it does not have enough privilege to lock pages in memory.`, and it reads back `false` | `os_windows.cpp:3126`; man page "Large Pages": Windows needs the "Lock pages in memory" user right (Local Security Policy) | none without the right; RigTune shouldn't ask players to change security policy | W (Windows) |
| `-XX:+UseTransparentHugePages` | **E on Windows** (`Unrecognized VM option`) | man page: "Linux only ... You may encounter performance problems ... made available for experimentation"; ZGC guide: "usually not recommended for latency sensitive applications" | Linux: not measured | E (Windows) / N (Linux) |
| `-XX:+UseCompactObjectHeaders` | accepted **without** the unlock | product in 25 (JEP 519; experimental in 24, JEP 450; default from JDK 27 per JEP 534). Man page: "reduces memory footprint in the Java heap by 4 bytes per object (on average) and often improves performance. The feature remains disabled by default while it continues to be evaluated." Temurin 25 ships `bin/server/classes_coh.jsa`, so class-data sharing still works | smaller live set (§4) | N (see §4) |
| `-XX:-UseCompressedClassPointers` | accepted with a deprecation warning; disables compact headers ("Compact object headers require compressed class pointers") and CDS | `arguments.cpp:537` (deprecated 25, obsolete 26) | | H (low) |
| `-XX:+UseCompressedClassPointers` | accepted with `Option UseCompressedClassPointers was deprecated in version 25.0` | same; it's the default | redundant; noisy | W |
| `-XX:+UseStringDeduplication` | accepted | extra concurrent GC work | not measured | N |
| `-XX:+UseNUMA` | accepted, reads back `false` on Windows | `os_windows.cpp:4499`: `UseNUMA = false; // We don't fully support this yet` | none (and desktops have one node) | S (Windows) |
| `-XX:+UseNUMAInterleaving` | accepted, `Process does not cover multiple NUMA nodes ... Ignoring UseNUMAInterleaving flag` | | none | W |
| `-Xmn<size>` | accepted | man page: "It is recommended that you do not set the size for the young generation for the G1 collector"; G1 tuning guide: "Setting the young generation size to a single value overrides and practically disables pause-time control" | disables G1's pause-time control | H |
| `-XX:+UseFastUnorderedTimeStamps` | **E** without the unlock | experimental | no documented benefit | N |
| `-XX:+UseVectorCmov` | accepted (C2 product flag, default false) | | no documented benefit | N |
| `-XX:+UseCriticalJavaThreadPriority` | **E** without the unlock | on Windows the default table already maps MaxPriority and CriticalPriority to `THREAD_PRIORITY_HIGHEST`, so alone it changes nothing; with `ThreadPriorityPolicy=1` MaxPriority threads get `THREAD_PRIORITY_TIME_CRITICAL` (`os_windows.cpp:3821-3861`) | not measured | S alone / N |
| `-XX:ThreadPriorityPolicy=1` | accepted (range 0..1; `=42` is **E**) | switches Windows to `prio_policy1` (same file) | not measured | N |
| `-XX:+UseG1GC -XX:+UseZGC` (or any two collectors) | **E** (`Multiple garbage collectors selected`) | | | E |
| `-XX:+UseSerialGC`, `-XX:+UseParallelGC` | accepted | Available Collectors: choose Parallel only if "there are no pause-time requirements or pauses of one second or longer are acceptable"; for response time "select ... -XX:+UseG1GC" | whole-heap stop-the-world collections; not measured | H |
| `-XX:+UseEpsilonGC` | **E** without the unlock | a GC that never collects: the heap fills and the game dies with OutOfMemoryError | | H |
| `-Xms` > `-Xmx` | **E** (`Initial heap size set to a larger value than the maximum heap size`) | | | E |
| `-XX:+ParallelGCThreads=8` (a +/- on a number) | **E** (`Unexpected +/- setting`) | | | E |
| `-Xmx40G`, `-Xmx64G` on this 32 GB PC | accepted | `-Xmx` is only a reservation | the heap may grow past free RAM; covered by the `ram-*` rules | H |
| `-XX:MaxRAMPercentage=` | accepted | ignored when `-Xmx` is given | | S (with `-Xmx`) |
| `-XX:+UseCompressedOops`, `-XX:+UseTLAB`, `-XX:+ResizeTLAB`, `-XX:+UseAdaptiveSizePolicy`, `-XX:+G1UseAdaptiveIHOP`, `-XX:+UseDynamicNumberOfGCThreads`, `-XX:+AlwaysActAsServerClassMachine`, `-XX:+OptimizeStringConcat`, `-XX:+DoEscapeAnalysis`, `-XX:+UseAES`, `-XX:+UseFMA` | accepted | all already on by default (or CPU-detected) here | redundant | S |
| The launchers' own `-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M` (official launcher and ATLauncher default) | accepted (the unlock is included) | §1.1 | see §4 | N |

No flag measured as **U** (useful) for a client beyond a right-sized `-Xmx` (§4). A game that is running can only contain W, S, H, N and U flags. Class E can't appear there: the JVM refused to start, so the fix belongs in a "the game won't start after pasting flags" help text, not in RigTune's in-game checks.

## 3. Heap sizing

### 3.1 What v0.3 already says (kept as it is)

The `ram-*` advice rules (`rules/source/knowledge.json`, v0.3 research `launcher-ram.md` §3) are: less than about 2.5 GB with 6 GB+ of RAM → "at least 4 GB" (`ram-low`); 6 GB for Distant Horizons or shaders (`ram-distant-horizons`, `ram-shaders`), about 8 GB for both (`ram-distant-horizons-shaders`); no more than about half of RAM on small PCs (`ram-low-system`, `ram-too-much`, `ram-distant-horizons-low-system`); 12 GB+ on an older CPU → info (`ram-huge`). They read the effective heap (`Runtime.maxMemory()`), which is right whatever mix of launcher field and Java argument set it (§1.1). Nothing measured here contradicts them, so v0.4 **extends** them (`-Xms`, `AlwaysPreTouch`, ZGC's footprint) and changes no threshold.

### 3.2 What the measurements add (this PC, light mod set, §4)

- **Live data is small in this scene.** After young collections the heap holds about **465 MB** (G1, any heap size) and **about 445 MB with compact object headers** (-5%). ZGC's post-collection number (~730 MB) isn't comparable: minor collections leave old garbage in place.
- **G1 only commits what it needs.** With `-Xmx4G` it committed about **1.3 GB**; with no `-Xmx` (7964 MB allowed) about **1.0 GB**; with `-Xmx2G` about **0.8 GB**. So a larger `-Xmx` doesn't make G1 use more RAM here. It only allows it.
- **A smaller heap means more collections, not lower frame rates:** `-Xmx2G` ran 68 pauses per 80 s against 27 at 4 GB (total 125 ms against 84 ms), with the same FPS, 1% low and p99 (§4.2). At a 2 GB heap this light setup still had 4x its live data in headroom; a pack whose live data approaches the heap would behave differently (not measured).
- **A larger heap didn't shorten pauses:** with 7964 MB allowed, G1's longest pause (Remark) was 12-15 ms against 6.5-10 ms at 4 GB. It's three runs, so this is only consistent with `ram-huge`'s "larger heaps can lengthen pauses", not proof of it.
- **ZGC uses what it's given:** it let the heap grow to 3852 MB of 4096 MB (94%, "Minor Collection (High Usage) 3852M(94%)->...") before collecting, so its RAM footprint is roughly `-Xmx`. The ZGC guide agrees: "In general, the more memory you give to ZGC the better."
- **`-Xms` = `-Xmx` commits everything up front:** Aikar's set held 4096 MB committed for the whole run, against ~1.3 GB for G1's defaults, with the same frame rates. `AlwaysPreTouch` adds very little **startup** time. JVM init measured with `RuntimeMXBean.getUptime()` at `main()` (`pretouch.txt`, 3 runs each): `-Xmx4G` 34-43 ms, `-Xms4G -Xmx4G` 39-46 ms, plus `AlwaysPreTouch` 109-116 ms, 8 GB pre-touched 166-176 ms; with ZGC 44-46 ms vs 402-414 ms pre-touched. So the real cost is resident RAM (the whole `-Xms` is touched), not startup. The JDK's own G1 guide recommends `-Xms`=`-Xmx` plus `AlwaysPreTouch` only to "avoid the delays" of committing memory, and no such delay showed up in frame times here.

### 3.3 Guidance

- **`-Xmx`:** unchanged (`ram-*` rules). Advice about the heap always points to the launcher's memory setting (v0.3 steps), never to the Java arguments box. That's required for Prism, where the Memory box overrides an `-Xmx` typed in Java Arguments (§1.1).
- **`-Xms`:** never recommend it. Flag it (`jvm-heap-reserved`, §5.2) only where it costs something: a large `-Xms` relative to the PC's RAM, or `AlwaysPreTouch` on a PC with 16 GB or less.
- **ZGC:** a heap of at least 4 GB (the ZGC guide's "enough headroom"; no allocation stall occurred in any of the 14 ZGC runs at 2 or 4 GB: "Allocation Stall" count 0 in every `gc.log`), and RAM for the whole heap, because ZGC keeps it committed. §4.3 has the 2 GB ZGC runs.

## 4. Measurements

### 4.1 Method

- **What ran:** `./gradlew :26.2:runBenchmarkAutorun -PrigtuneAutorun=gc-measure -PrigtuneJvmArgsFile=<config>` in the worktree (the Loom dev client: MC 26.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Sodium mc26.2-0.9.2, ModMenu 20.0.2, RigTune 0.3.0 + the experiment patch). It starts at the title screen, opens RigTune's benchmark world (fixed seed, camera fixed at (0, 192)), and runs a **Measure** of the current settings: render distance 16, simulation distance 12, 1920x1080 window, frame rate uncapped (the benchmark sets it, as in v0.3), 2 repeats × 2 camera sweeps × 20 s = **80 s of recorded frames per run** (about 150,000 frames). Then it quits.
- **Per run:** frame stats over all recorded frames (average FPS, 1% low = the mean of the slowest 1%, p99 frame time, frames slower than 8 ms and 16.7 ms); GC pauses from `-Xlog:gc,gc+init,safepoint:file=...` restricted to the recorded windows (the windows' JVM uptimes are logged, and the safepoint log's `Total` per GC operation is the stall application threads see); GC-bean deltas inside the windows (they agree); startup = JVM uptime when the title screen first shows; committed heap and post-GC heap from the `gc` lines.
- **Heap:** `-Xmx4G` for every configuration (Aikar's set includes its own `-Xms4G`), because 4 GB is what the `ram-low` rule recommends and what Prism and ATLauncher default to, and a heap near a real setup stresses the GC more than the dev default's 7964 MB. The dev default and 2 GB were measured separately (§4.2).
- **Order:** a discarded warm-up run first (it created the world), then the configurations interleaved in rotated order in each batch (g1 zgc coh aikar mojang / zgc coh aikar mojang g1 / coh aikar mojang g1 zgc), with the lock released between batches; then k1 (capped G1/ZGC interleaved, then ZGC at 2 GB) and z1 (ZGC 2 GB, 4 GB, 2 GB).
- **Noise control:** the machine is shared with other agents. `cpusampler.ps1` logged every other process's CPU share every 5 s (`cpu-samples.txt`), and `cpujoin.py` averages it over each run's recorded windows. **A run counts as clean when that mean is below 10%.**

**Contaminated runs (reported, not used):** runs **02-11** (all of rounds r1, r2 and the first two of r3) ran while six runaway `find /` processes left over from other agents used about 25% of all CPU (21-27% measured in runs 10-11; the processes had been running since 01:08-01:33 UTC, before the sampler started at 02:14; the coordinator killed them at about 02:17:30). Runs **24** (`c2-g1`, 10.9%) and **43** (`k1-zgc2g`, 11.7%) are also excluded: bursts of other agents' Gradle builds. Every configuration was re-run after the `find` processes were gone; the tables below use only the **33 clean runs** (12-23, 25-42, 44-46). The contaminated runs were 3-11% lower in average FPS and about 10% lower in 1% lows (plus one ZGC outlier at a 164 FPS 1% low), and they don't change any conclusion (appendix).

### 4.2 Results (clean runs; median, with min-max in brackets)

| Config | n | Avg FPS | 1% low FPS | p99 frame ms | frames > 8 ms / > 16.7 ms | GC pauses in window: count / total ms / max ms | startup to title s | heap committed max MB | live after GC MB |
|---|---|---|---|---|---|---|---|---|---|
| G1 default, `-Xmx4G` | 3 | 1931 (1912-1944) | 610 (572-628) | 1.33 (1.31-1.41) | 3 (3-3) / 0 (0-0) | 27 (26-28) / 84 (72-97) / 6.5 (6.5-10.3) | 10.8 (10.6-15.0) | 1316 (1298-1384) | 467 (467-467) |
| Launcher default set (official/ATLauncher), `-Xmx4G` | 4 | 1937 (1931-1947) | 616 (593-638) | 1.31 (1.30-1.33) | 3 (1-5) / 0 (0-1) | 45 (30-61) / 119 (102-125) / 11.3 (9.4-12.2) | 12.5 (10.5-16.3) | 1088 (992-1472) | 464 (457-466) |
| G1 + compact headers, `-Xmx4G -XX:+UseCompactObjectHeaders` | 3 | 1915 (1914-1923) | 580 (578-616) | 1.35 (1.33-1.35) | 2 (2-3) / 0 (0-0) | 45 (26-50) / 103 (69-113) / 9.4 (9.2-12.1) | 11.1 (10.6-12.6) | 886 (850-1256) | 445 (438-447) |
| Aikar's set, `-Xms4G -Xmx4G` + 19 flags | 3 | 1926 (1917-1943) | 622 (587-648) | 1.34 (1.29-1.36) | 1 (1-6) / 0 (0-0) | 6 (6-6) / 10 (10-10) / 4.4 (3.9-4.5) | 10.6 (10.6-11.0) | 4096 (4096-4096) | 490 (485-491) |
| ZGC, `-Xmx4G -XX:+UseZGC` | 5 | 1922 (1892-1942) | 627 (593-633) | 1.33 (1.31-1.38) | 2 (1-4) / 0 (0-1) | 23 (17-28) / 0 (0-1) / 0.0 (0.0-0.1) | 11.2 (10.6-16.2) | 3852 (3852-3854) | 730 (698-773) |

Heap size, same scene (G1 default):

| Config | n | Avg FPS | 1% low FPS | p99 frame ms | frames > 8 ms / > 16.7 ms | GC pauses in window: count / total ms / max ms | startup to title s | heap committed max MB | live after GC MB |
|---|---|---|---|---|---|---|---|---|---|
| G1 default, `-Xmx2G` | 3 | 1920 (1916-1940) | 605 (580-662) | 1.34 (1.30-1.35) | 2 (1-4) / 0 (0-2) | 68 (65-72) / 125 (124-129) / 12.4 (8.2-17.2) | 11.8 (10.9-11.8) | 816 (809-827) | 464 (462-465) |
| G1 default, `-Xmx4G` | 3 | 1931 (1912-1944) | 610 (572-628) | 1.33 (1.31-1.41) | 3 (3-3) / 0 (0-0) | 27 (26-28) / 84 (72-97) / 6.5 (6.5-10.3) | 10.8 (10.6-15.0) | 1316 (1298-1384) | 467 (467-467) |
| G1 default, no `-Xmx` (25% of RAM = 7964 MB) | 3 | 1919 (1905-1930) | 611 (560-617) | 1.34 (1.32-1.37) | 2 (1-3) / 0 (0-0) | 43 (40-52) / 106 (99-120) / 14.7 (12.4-15.8) | 10.9 (10.4-10.9) | 980 (912-1000) | 466 (462-466) |

**Reading the numbers:**
- **Frame rates: every difference is within noise.** Across the five 4 GB configurations the median average FPS spans 1915-1937 (1.1%), while a single configuration's own runs spread 9-50 FPS; the median 1% lows span 580-627, while single configurations spread 38-61; p99 frame time is 1.31-1.35 ms everywhere. Compact object headers has the lowest median FPS and 1% low, but its best run (1923 / 616) sits inside everyone else's range, so it's not a measured slowdown either.
- **Hitches: no difference.** Frames slower than 8 ms: 1-6 per 150,000 in every configuration, ZGC included; frames slower than 16.7 ms: 0-2 (counted from run 18 on, when the counter was added).
- **GC pauses: large, real differences** that the frame metrics don't see. In 80 s G1's defaults paused 27 times for 84 ms in total, the longest 6.5 ms (median of runs; young pauses mostly 3-5 ms, Remark up to 10 ms). The launcher default set: 45 pauses, 119 ms, longest 11 ms. Compact headers: 45, 103 ms, 9.4 ms, with **shorter young pauses** (median 1.3-2.7 ms vs 3.0-5.0 ms for plain G1, because there's less to copy). Aikar's set: **6 pauses, 10 ms, longest 4.4 ms** (a 1.6 GB young generation and no concurrent cycle in the window). ZGC: 23 pauses totalling **under 1 ms**, the longest 0.1 ms.
- **Why pauses don't show in frames here:** at ~1,900 FPS, 30 pauses touch 0.02% of frames, far below the 1% the 1% low averages; and a pause only stalls the render thread when it's in Java code (in native OpenGL/driver calls it keeps running until it returns). §4.3 repeats G1 against ZGC at a 140 FPS cap.
- **Startup: no difference** (median 10.6-12.5 s to the title screen; the spread within one configuration is larger).
- **Memory:** committed heap 1.3 GB (G1), 1.1 GB (launcher set), 0.9 GB (compact headers), **4.1 GB (Aikar, `-Xms`)**, **3.9 GB (ZGC)**.

### 4.3 Capped frame rate (G1 vs ZGC at 140 FPS) and ZGC at 2 GB

At ~1,900 FPS a GC pause can hide below the 1% low, so G1 and ZGC were repeated with the frame rate **capped** the way many players run: `maxFps:144` in `options.txt` (Minecraft rounds it to its 10-FPS slider step, so **140 FPS**), V-Sync off, and the benchmark told to keep the cap (`-Drigtune.dev.keepFpsCap=true`, experiment-only: skips the benchmark's uncap and its throttle cancel). Everything else as in §4.1; batch k1 ran g1cap, zgccap × 3 interleaved.

| Config | n | Avg FPS | 1% low FPS | p99 frame ms | frames > 8 ms / > 16.7 ms | GC pauses in window: count / total ms / max ms | startup to title s | heap committed max MB | live after GC MB |
|---|---|---|---|---|---|---|---|---|---|
| G1 default, `-Xmx4G`, capped at 140 FPS | 3 | 139 (139-139) | 126 (125-127) | 7.63 (7.61-7.67) | 23 (23-30) / 0 (0-0) | 9 (3-10) / 21 (10-32) / 4.8 (4.8-14.6) | 11.0 (10.8-14.6) | 866 (824-1542) | 410 (348-428) |
| ZGC, `-Xmx4G -XX:+UseZGC`, capped at 140 FPS | 3 | 139 (139-139) | 127 (126-128) | 7.62 (7.60-7.77) | 20 (14-30) / 0 (0-0) | 6 (3-9) / 0 (0-0) / 0.0 (0.0-0.0) | 10.7 (10.7-16.7) | 2672 (2662-2700) | 764 (731-852) |

- **Capped, the two collectors can't be told apart:** 139 FPS average, 1% low 126 vs 127, p99 7.63 vs 7.62 ms, 14-30 frames over 8 ms (of about 11,140) and **none over 16.7 ms** for either. G1's pauses (median 9 in the window, longest 4.8 ms, one run 14.6 ms) fit into the idle time of a 7.1 ms frame budget.
- Capped, G1 committed even less heap (866 MB median); ZGC still committed 2.7 GB.

ZGC with a 2 GB heap (the "not enough headroom" case), batches k1 and z1:

| Config | n | Avg FPS | 1% low FPS | p99 frame ms | frames > 8 ms / > 16.7 ms | GC pauses in window: count / total ms / max ms | startup to title s | heap committed max MB | live after GC MB |
|---|---|---|---|---|---|---|---|---|---|
| ZGC, `-Xmx4G -XX:+UseZGC` | 5 | 1922 (1892-1942) | 627 (593-633) | 1.33 (1.31-1.38) | 2 (1-4) / 0 (0-1) | 23 (17-28) / 0 (0-1) / 0.0 (0.0-0.1) | 11.2 (10.6-16.2) | 3852 (3852-3854) | 730 (698-773) |
| ZGC, `-Xmx2G -XX:+UseZGC` | 3 | 1900 (1883-1902) | 564 (562-593) | 1.39 (1.39-1.41) | 3 (1-5) / 0 (0-1) | 47 (44-48) / 1 (1-1) / 0.1 (0.0-0.2) | 11.4 (10.6-15.5) | 1906 (1906-1908) | 750 (716-762) |

- At 2 GB ZGC collected about twice as often (47 pauses in the window vs 23 at 4 GB; about 60 collections per run, most "High Usage" or "Allocation Rate") and committed 1.9 of 2 GB. **No allocation stall** occurred in any 2 GB or 4 GB ZGC run (`Allocation Stall` count 0 in every `gc.log`). Frame rates dropped a little: average 1900 vs 1922 FPS, 1% low 564 vs 627, p99 1.39 vs 1.33 ms, and every 2 GB run's 1% low was at or below the slowest 4 GB run's (593). That's only three runs each, but it's consistent, and G1 at 2 GB showed no such drop (1920 / 605). It supports `jvm-zgc-small-heap` (§5.2) as an info-level note.

### 4.4 Appendix: contaminated runs (not used above)

| Config | n | Avg FPS | 1% low FPS | p99 frame ms | frames > 8 ms / > 16.7 ms | GC pauses in window: count / total ms / max ms | startup to title s | heap committed max MB | live after GC MB |
|---|---|---|---|---|---|---|---|---|---|
| G1 default, `-Xmx4G` | 3 | 1842 (1712-1897) | 542 (434-560) | 1.45 (1.39-1.77) | 11 (11-11) / 1 (1-1) | 30 (24-40) / 94 (91-107) / 10.9 (7.7-12.9) | 12.8 (10.6-13.5) | 1186 (1006-1490) | 474 (468-488) |
| G1 + compact headers, `-Xmx4G -XX:+UseCompactObjectHeaders` | 3 | 1855 (1807-1871) | 563 (461-613) | 1.50 (1.41-1.72) | n/a / n/a | 32 (30-58) / 84 (78-125) / 7.2 (6.9-13.7) | 14.1 (13.5-15.6) | 1050 (780-1116) | 445 (438-460) |
| Aikar's set, `-Xms4G -Xmx4G` + 19 flags | 3 | 1838 (1719-1848) | 555 (436-578) | 1.46 (1.45-1.74) | n/a / n/a | 8 (6-8) / 19 (9-22) / 10.0 (3.2-11.8) | 12.5 (12.2-18.9) | 4096 (4096-4096) | 489 (484-512) |
| ZGC, `-Xmx4G -XX:+UseZGC` | 2 | 1702 (1652-1752) | 301 (164-437) | 1.79 (1.79-1.79) | n/a / n/a | 16 (15-18) / 0 (0-0) / 0.0 (0.0-0.0) | 14.1 (13.2-15.1) | 3852 (3852-3852) | 734 (728-739) |
| ZGC, `-Xmx2G -XX:+UseZGC` | 1 | 1840 (1840-1840) | 463 (463-463) | 1.58 (1.58-1.58) | 4 (4-4) / 0 (0-0) | 51 (51-51) / 1 (1-1) / 0.0 (0.0-0.0) | 17.3 (17.3-17.3) | 1910 (1910-1910) | 828 (828-828) |

Per-run background CPU (mean other-process CPU share over the recorded windows; `None` = before the sampler started, when the runaway `find` processes were known to be running): 02-09 None, 10: 25.4%, 11: 21.3%, 24: 10.9%, 43: 11.7%; every other run 2.4-7.2%.

## 5. Recommendation logic

### 5.1 Principles (from §2 and §4)

1. **Java's defaults are the baseline.** Every launcher-style configuration measured the same FPS, 1% low and p99 as plain G1 within this machine's run-to-run noise (§4). So RigTune never tells anyone to *add* GC flags, and never tells anyone to switch collectors. It only (a) sizes `-Xmx` (the existing `ram-*` rules, unchanged), and (b) points out flags that are ignored, harmful, or pure cost.
2. **Leave the launchers' own defaults alone.** The official launcher's and ATLauncher's default set (`-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M`) measured the same as plain G1. Flagging it would nag the largest group of players about nothing.
3. **Leave a working ZGC alone.** ZGC measured the same frame rates, with sub-millisecond pauses, but it keeps the heap near `-Xmx` (3852 MB of 4096 MB committed vs about 1.3 GB for G1). It's a legitimate choice. Only flag it together with a too-small heap or a small-RAM PC (the ZGC guide: "The most important tuning option for ZGC is setting the maximum heap size ... enough headroom").
4. **Name every flag and why; give the exact place to edit.** The advice lists the offending flags (from RigTune's own parse, never raw arguments with paths) and ends with the launcher's Java-arguments steps (§5.4).
5. **Nothing is applied automatically.** RigTune can't change JVM arguments (the launcher owns them), so every JVM item is advice (`kind` info or warning) with no action.

### 5.2 The checks

| id (advice) | Fires when | kind / impact | Says (draft) |
|---|---|---|---|
| `jvm-ignored-flags` | at least one `-XX` flag in the input arguments is not found by `getVMOption` (obsolete) or reads back a different boolean than typed (overridden), after removing the launcher-injected arguments | info / low | "Java ignores some of your Java arguments: `-XX:+ZGenerational` (removed in Java 24; a future Java won't start with it), `-XX:+UseNUMA` (not supported on Windows). Remove them." |
| `jvm-young-gen-fixed` | `-Xmn`, `-XX:NewSize`/`-XX:MaxNewSize` typed while G1 runs | warning / medium | "`-Xmn` fixes the size of Java's young generation, which turns off G1's pause-time control (Java's own documentation). Remove it." |
| `jvm-stop-the-world-gc` | `UseSerialGC` or `UseParallelGC` with origin `VM_CREATION`/`ENVIRON_VAR` | warning / medium | "`-XX:+UseParallelGC` is meant for work where 'pauses of one second or longer are acceptable' (Java's own documentation). Remove it so Java uses its default G1 collector." |
| `jvm-no-gc` | `UseEpsilonGC` | critical / high | "`-XX:+UseEpsilonGC` never frees memory: the game will run out of memory and crash. Remove it." |
| `jvm-server-flags` | the Aikar markers (`-Dusing.aikars.flags` or `-Daikars.new.flags`), or at least 4 of its distinctive flags (`G1NewSizePercent=30`, `G1MaxNewSizePercent=40`, `G1MixedGCLiveThresholdPercent=90`, `SurvivorRatio=32`, `MaxTenuringThreshold=1`, `G1RSetUpdatingPauseTimePercent=5`, `InitiatingHeapOccupancyPercent=15`) | info / low | "These are Paper's server flags ('Aikar's flags'). In RigTune's tests they gave the same FPS and 1% lows as Java's defaults, but with `-Xms` equal to `-Xmx` and `AlwaysPreTouch` Minecraft keeps all N GB of its memory reserved even when it doesn't need it. Keep only `-Xmx` (set in your launcher's memory setting)." Shown with extra weight when `ramMbAtMost` 16383. |
| `jvm-explicit-gc-disabled` | `DisableExplicitGC` without the Aikar markers | info / low | "`-XX:+DisableExplicitGC` stops Minecraft's out-of-memory recovery and Java's direct-memory cleanup from asking for a collection, and has no benefit in play. Remove it." |
| `jvm-heap-reserved` | `Xms` ≥ 75% of `Xmx` **and** `Xmx` > 50% of RAM (or `AlwaysPreTouch` on with `ramMbAtMost` 16383) | info / medium | "`-Xms` makes Java reserve N GB right away. On a PC with M GB that leaves little for Windows and the graphics driver; remove `-Xms` (and `-XX:+AlwaysPreTouch`)." |
| `jvm-zgc-small-heap` | ZGC and `heapMbAtMost` 3072 | info / medium | "ZGC needs spare heap to work without stalls and it will use nearly all of it. Give Minecraft at least 4 GB, or remove `-XX:+UseZGC`." (§4.3) |
| `jvm-zgc-small-pc` | ZGC and `ramMbAtMost` 8192 and `heapMbAtLeast` 4096 | info / low | "ZGC keeps close to the whole N GB heap in memory (G1 used about a third of it in RigTune's tests). On an 8 GB PC that leaves little for the system; Java's default G1 is lighter." |

What RigTune should **not** do: recommend ZGC, Shenandoah, compact object headers or any tuning flag as an FPS gain (none measured one); recommend `-Xms`, `AlwaysPreTouch`, large pages (Windows needs a security-policy change) or thread-priority flags; flag the launchers' default sets; flag redundant-but-harmless flags such as `-XX:+UseG1GC` or `-XX:MaxGCPauseMillis=200` (optional, only as part of `jvm-server-flags`); show or share the raw command line.

Compact object headers: product in 25 with a measured smaller live set (§4) and CDS still on, and they become the default in JDK 27 (JEP 534). That's worth at most a line in the knowledge base / FAQ, not advice: no frame-rate gain was measured and the man page says the feature "remains disabled by default while it continues to be evaluated".

### 5.3 Rules or code

- **Code** (`core/jvm`, pure and unit-tested), because it's parsing and JDK behaviour, not policy: splitting the input arguments, removing the launcher-injected ones, the runtime ignored/overridden check (§1.3), which GC runs and whether it was typed, the Aikar fingerprint, `Xms`/`Xmx`/`AlwaysPreTouch`. Its output is a set of **facts** added to `HardwareProfile.flags` (the set the `flags` condition already reads, so no model or condition-key change is needed): `jvm-gc-g1|zgc|shenandoah|parallel|serial|epsilon|other`, `jvm-gc-typed`, `jvm-ignored-flags`, `jvm-young-gen-fixed`, `jvm-server-flags`, `jvm-explicit-gc-disabled`, `jvm-xms-large`, `jvm-pretouch`. `ConditionEvaluator.knownFlag` learns the `jvm-` prefix, like `sodium-workaround:`.
- **Rules** (rules-v2 `advice`, new ids with the `jvm-` prefix) decide *when* and *what to say*, with the existing `heapMb*`/`ramMb*` keys plus the new flag facts, e.g. `{ "id": "jvm-zgc-small-heap", "requires": ["jvm-flags"], "when": { "flags": ["jvm-gc-zgc"], "heapMbAtMost": 3072 }, "kind": "info", ..., "v1": false }`. That keeps thresholds and wording updatable without a release.
- **Old clients stay silent, twice over:** 0.2.0 and 0.3.0 have `Recommender.SUPPORTED_FEATURES = Set.of()`, so any rule with a non-empty `requires` is skipped entirely (RULES_SCHEMA "requires (v2)"). Even without `requires`, a `flags` value they don't know that isn't present evaluates to UNKNOWN (RULES_SCHEMA "Condition"), which fails closed. 0.1.x never sees them: `"v1": false` keeps them out of rules-v1.json (the updater requires that for a non-empty `requires`). v0.4 adds `"jvm-flags"` to `SUPPORTED_FEATURES`.
- **The flag names in the text** can't live in a literal rule string, so the client adds a generated line under every `jvm-` advice: "Found in your Java arguments: `-XX:+ZGenerational`, `-XX:+UseNUMA`". It's built from `JvmFinding`s, whose names come from RigTune's own table or the `-XX:` name only (never a value with a path), and it's the same pattern as v0.3's launcher line under `ram-` advice.
- `tools/update_rules.py`/`check_rules_v1.py` must accept the `jvm-*` flag vocabulary only in v2 rules with `requires`, and RULES_SCHEMA documents the `jvm-` id prefix, the flag facts and the `jvm-flags` feature.

### 5.4 Where to paste: launcher-aware steps

Heap size stays with v0.3's memory steps (`rigtune.launcher.steps.*`, unchanged; for Prism it *must*, see §1.1). JVM flags get a second key family, used for every advice whose id starts with `jvm-` (`LauncherAdvice.JVM_ADVICE_PREFIX = "advice:jvm-"`):

| key | text (draft) | source / status |
|---|---|---|
| `rigtune.launcher.jvm_steps.modrinth_app` | this instance → Instance settings (gear) → Sync overrides → Custom Java arguments (if that's off, the app's Settings → Java and memory → Java arguments). | modrinth/code 22ccdc1: `pages/instance/components/settings-modal/index.vue:99-106` ("Sync overrides"), `.../settings-modal/java-settings.vue:176-184` ("Custom Java arguments", "Set Java arguments separately for this instance.", "Enter Java arguments..."); global `components/ui/settings/instances/instances-synced-settings/launch-options.vue:113-114,161-166` ("Java and memory", "Java arguments"). Verified from source; the name of the app-settings tab that holds "Java and memory" is from v0.3 (`AppSettingsModal.vue:147-154`), not re-read. |
| `rigtune.launcher.jvm_steps.prism` | right-click this instance → Edit... → Settings → Java → tick Java Arguments → edit the box. (Memory goes in the Memory box: Prism adds its own -Xms/-Xmx after these.) | PrismLauncher ea87ffc: `launcher/ui/widgets/JavaSettingsWidget.ui:363-378` (group "Java Argumen&ts", `jvmArgsTextBox`), `JavaSettingsWidget.cpp:81` (checkable per instance), `MinecraftInstance.cpp:576-619` (order). Verified from source; menu path as in v0.3. |
| `rigtune.launcher.jvm_steps.atlauncher` | this instance's Settings button → Java/Minecraft → Java Parameters. | ATLauncher 1dac5d8: `gui/dialogs/instancesettings/JavaInstanceSettingsTab.java:294` ("Java Parameters"); tab path as in v0.3. Verified from source. |
| `rigtune.launcher.jvm_steps.official` | Installations → this installation's three-dot menu → Edit → More Options → JVM Arguments. | Labels **UNVERIFIED** (closed source; same as v0.3's `steps.official`). The field's *content* is verified: the on-device launcher log shows exactly the default arguments (§1.1). |
| CurseForge | no JVM steps: the generic text only | **UNVERIFIED** (closed source; only the `javaArgsOverride` key in `minecraftinstance.json` is known). Add a key once someone checks the live app. |

The header could also name the collector ("Memory 6.0 GB of 32 GB, set in the Modrinth App · G1"), and the share report gets one line, `- Java: 25.0.3 (Azul Systems), G1, 2 argument notes`, with no argument text.

## 6. Implementation plan

**Add**
- `core/jvm/JvmArgs.java`: parse `getInputArguments()` into typed entries (`-XX:+/-Name`, `-XX:Name=value`, `-Xmx/-Xms/-Xmn/-Xss` with k/m/g units, `-Dkey=value`, other); duplicates resolve last-wins; launcher-injected entries recognised (`-javaagent:`, `-Dmodrinth.internal.*`, `-Dlog4j.configurationFile`, `-Djava.library.path`, `-Djna.tmpdir`, `-Dorg.lwjgl.*`, `-Dio.netty.native.workdir`, `-Dminecraft.launcher.*`, `-Duser.language/country`, `--add-opens`, `--enable-native-access`, `--sun-misc-unsafe-memory-access`, `-XX:HeapDumpPath=MojangTricks...`, ATLauncher's `-XX:MetaspaceSize`).
- `core/jvm/JvmSnapshot.java`: input arguments, a `VmOptions` lookup (name → value + origin, or "not found"), GC bean names, `Runtime.version()`, `java.vendor`.
- `core/jvm/JvmFlagTable.java`: the curated §2 rows the runtime check can't see (harmful, server-set fingerprint, redundant), each with a reason key.
- `core/jvm/JvmFlagClassifier.java` → `List<JvmFinding>` (flag name, kind IGNORED/OVERRIDDEN/HARMFUL/SERVER_SET/COST, reason key) and `Set<String>` facts.
- `client/probe/JvmProbe.java`: reads the MXBeans once per session on `Probes.EXECUTOR` (like `LauncherProbe`), catches `Throwable` → no facts.

**Modify** (hotspots marked ★: other v0.4 workstreams touch them too)
- ★ `client/probe/HardwareProbe.java`: add the JVM facts to `flags` (the fast part).
- ★ `core/rules/ConditionEvaluator.java`: the `jvm-` flag prefix in `knownFlag`.
- ★ `core/recommend/Recommender.java`: `SUPPORTED_FEATURES = Set.of("jvm-flags")`.
- `core/launcher/LauncherAdvice.java` (+ `JVM_ADVICE_PREFIX`, `jvmStepsKey`), `LauncherInfo.java` (+ `jvmStepsKey()`), ★ `client/ui/LauncherLines.java` / `RigTuneScreen.java` (the "Found in your Java arguments" line and the Java-arguments steps line).
- ★ `src/main/resources/assets/rigtune/lang/en_us.json` (reason strings, steps, the found-flags line; `LangCheckTest` must pass).
- ★ `rules/source/knowledge.json` (the `jvm-*` advice, `"v1": false`, `requires`), regenerated `rules/rules-v2.json` + bundled copy; ★ `docs/RULES_SCHEMA.md`; `tools/update_rules.py`, `tools/check_rules_v1.py` (+ Python tests).
- ★ `core/report/ShareReport.java` (the Java line), `docs/DESIGN.md`.

**Tests**
- `JvmArgsTest`: units and signs, last-wins duplicates, `-D` markers, junk tokens, an empty list.
- `JvmFlagClassifierTest` with **real command lines**: the user's Modrinth instance (version-JSON args + `-Xmx6144M` + `-javaagent`/log4j/ipc args) → no findings; the official launcher's logged line (§1.1) → no findings; ATLauncher's defaults + `-XX:MetaspaceSize` → none; Prism's `-Duser.language=en ... -Xms512m -Xmx4096m --add-opens ...` → none; Aikar's set pasted into Modrinth's Java arguments → `jvm-server-flags`; `-XX:+UseZGC -XX:+ZGenerational` with a fake lookup where `ZGenerational` is missing → IGNORED; `-XX:+UseNUMA` reading back false → OVERRIDDEN; `-Xmn1G` → `jvm-young-gen-fixed`; `-XX:+UseParallelGC` with origin `ENVIRON_VAR` (JAVA_TOOL_OPTIONS) → `jvm-stop-the-world-gc`; ergonomic G1 → nothing; OpenJ9 (no HotSpot bean) → no facts, no exception. Assert that no finding or fact contains a path or `=` value.
- Scenario tests over the bundled rules (like `LauncherScenarioTest`): each `jvm-*` advice fires for its case, gets the launcher's JVM steps line for each launcher and none for CurseForge/Unknown; the defaults fire nothing.
- Compatibility: the new rules through the 0.2.0 evaluator copy (`src/test/java/.../v020/`) and 0.3.0's `SUPPORTED_FEATURES` → skipped; `RulesV1DifferentialTest` unchanged.
- Game test `JvmAdviceGameTest`: a client game-test run with extra JVM arguments `-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x` and `-Dminecraft.launcher.brand=theseus` (a `-PgametestJvmArgs` hook on `runClientGameTest`/`runProductionClientGameTest`), opens the RigTune screen, screenshots at 854x480@2, 1280x720@2 and 1280x720@3, and asserts the advice text, the found-flags line and "In the Modrinth App: this instance → Instance settings (gear) → Sync overrides → Custom Java arguments ...". A second leg without extra arguments asserts that no `jvm-` advice shows.

## 7. Open questions

1. **Only one machine and one light mod set** (vanilla + Sodium + Fabric API + ModMenu + RigTune, benchmark world at RD 16, uncapped at about 1,900 FPS). A 150-mod pack, Distant Horizons or a CPU with fewer cores could change the ranking (a larger live set makes G1 pauses longer; ZGC's concurrent threads compete harder on 4 cores). Worth one run on a low-end machine or with the user's own 48-mod instance before shipping any GC-specific wording beyond "remove ignored/harmful flags".
2. **Where G1's pauses could matter isn't covered:** uncapped at ~1,900 FPS they're 0.02% of frames, and capped at 140 FPS the idle part of each frame absorbed them (§4.3). The untested case is a slow CPU that uses its whole frame budget, where a 10 ms Remark pause would be a visible hitch; ZGC's concurrent threads would also compete harder there.
3. The official launcher's field labels and a 26.x launch by it are still unobserved; CurseForge's Java-argument field is unknown.
4. Shenandoah and Java 26/27 weren't measured. The runtime ignored-flag check is designed to keep working on newer JDKs, but the curated table (§2) needs a re-check at each new Java the game ships with.
5. Should `jvm-server-flags` stay silent on PCs with 32 GB or more (the RAM cost is irrelevant there and frame rates didn't change)? The draft only raises its weight below 16 GB.
