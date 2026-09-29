# JVM & memory advice

[← Back to the README](../../README.md)

**Tools… → JVM & memory** shows the Java that runs your game: its version and vendor, which garbage collector it uses and whether your Java arguments chose it, and how much memory (heap) it may use. RigTune checks your Java arguments once per session, on your PC, and lists the ones Java ignores, the ones that hurt a game client and the ones that only cost memory. Under each note it tells you where your launcher keeps its Java arguments (the Modrinth App, Prism Launcher, MultiMC, ATLauncher, GDLauncher, the CurseForge app and the Minecraft Launcher). How much memory to give Minecraft stays with the memory advice, which points to your launcher's memory setting; in the Modrinth App and GDLauncher an `-Xmx` typed into the Java arguments overrides the memory slider, and the memory advice says so when RigTune sees one.

**RigTune never tells you to add GC flags or to switch collector for more FPS: its notes only ever suggest removing arguments or changing the memory setting.** On the one PC they were measured on (Ryzen 7 7800X3D, RX 7800 XT, 32 GB, Minecraft 26.2 with Sodium at render distance 16, a 4 GB heap, 33 clean runs of 80 s each), every configuration gave the same frame rates within run-to-run noise:

| Java arguments | Average FPS | 1% low | GC pauses per 80 s | Memory committed |
|---|---|---|---|---|
| Java's defaults (G1) | 1931 | 610 | 27, 84 ms in total | 1.3 GB |
| The Minecraft Launcher's and ATLauncher's G1 set | 1937 | 616 | 45, 119 ms | 1.1 GB |
| Aikar's flags (Paper's server flags) | 1926 | 622 | 6, 10 ms | 4.1 GB |
| ZGC | 1922 | 627 | 23, under 1 ms | 3.9 GB |

Capped at 140 FPS, G1 and ZGC were also the same (1% low 126 against 127). What changed was memory: ZGC and Aikar's `-Xms` keep close to the whole heap in RAM. So RigTune leaves Java's defaults, the launchers' default sets and a working ZGC alone, and only names flags worth removing. One PC and a light mod set are not every PC: a slow CPU that uses its whole frame time could behave differently (not measured). The runs and their method are in [docs/research/v0.4/jvm-gc.md](../research/v0.4/jvm-gc.md).

**Privacy:** the Java arguments can contain folder paths with your Windows user name. RigTune never shows, logs or shares them: the screen and the log name only the flags it has a note about, and **Copy report** adds one line such as `Java: 25.0.3 (Azul Systems, Inc.), G1, 2 argument notes`. The check needs a HotSpot-based Java (Temurin, Zulu, Oracle, Microsoft); on other Javas (OpenJ9) it turns itself off and gives no JVM advice.

## The game won't start after I pasted Java arguments

When Java refuses an argument, Minecraft never starts, so RigTune can't see it; your launcher shows the error. Checked with Java 25, the version Minecraft 26.2 and 26.3 use:

| The error says | Why | Fix |
|---|---|---|
| `Unrecognized VM option 'UseConcMarkSweepGC'` (or `UseParNewGC`, `CMS…`, `AggressiveOpts`, `UseBiasedLocking`, `PermSize`, `MaxPermSize`, `UseStringCache`, …) | The option was removed from Java years ago (CMS in Java 14). | Delete it. |
| `VM option 'G1NewSizePercent' is experimental and must be enabled via -XX:+UnlockExperimentalVMOptions` | An experimental option without the unlock, or with the unlock after it. | Delete it, or put `-XX:+UnlockExperimentalVMOptions` before it. |
| `Multiple garbage collectors selected` | Two `-XX:+Use…GC` options, for example `-XX:+UseG1GC -XX:+UseZGC`. | Keep at most one (with none, Java picks G1). |
| `Initial heap size set to a larger value than the maximum heap size` | `-Xms` is larger than `-Xmx`. | Delete `-Xms`. |
| `Unexpected +/- setting in VM option 'ParallelGCThreads=8'` | A number option written with `+` or `-`. | Write it as `-XX:ParallelGCThreads=8`, or delete it. |
| `Unrecognized VM option 'UseTransparentHugePages'` | A Linux-only option, on Windows. | Delete it. |
| `Unknown -XX:ShenandoahGCMode option` | The `iu` mode no longer exists. | Delete it. |

The quickest way back is to stop the instance using its own Java arguments, so it uses your launcher-wide ones again: in the Modrinth App turn off the instance's **Custom Java arguments**, in Prism Launcher untick **Java Arguments**, and in ATLauncher press **Reset** next to **Java Parameters**. In the Minecraft Launcher, create a new installation: its JVM arguments start at the defaults.
