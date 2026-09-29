# RigTune's own footprint

[← Back to the README](../../README.md)

RigTune's heavy work (the hardware scan, the mod scan, loading the rules and the Modrinth lookups) runs on its own background threads, not on the way to the title screen. Each frame it checks one flag; each tick it reads a few fields.

**Launch time.** On one Windows PC (Ryzen 7 7800X3D, RX 7800 XT, Minecraft 26.2), launch to title screen took 14.5 s with RigTune and 14.3 s without it: medians of 10 launches each, with RigTune switched off through Fabric Loader's `-Dfabric.debug.disableModIds=rigtune` and everything else the same. That difference is well inside the roughly 2 s spread between launches.

**Budgets.** Every build runs a footprint check (`FootprintGameTest` in each game-test run, `FrameHookBudgetTest` with the unit tests) that fails when RigTune goes over these budgets (`tools/footprint-budgets.json`). The budgets were set at twice the largest value seen in calibration runs on GitHub's runners. The numbers below are the largest of the three game-test legs (26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan; Linux, 4 vCPUs, software rendering) in the release candidate's CI run ([36236205018](https://github.com/chaotix345/rigtune/actions/runs/36236205018)); the per-frame numbers come from the unit test on the same run:

| What | Release candidate | Budget |
|---|---|---|
| RigTune's startup work on the game's main thread, CPU time | 100 ms | 150 ms |
| The same, wall time (includes waiting while the shared runner is busy) | 153 ms | 368 ms |
| RigTune's call when the game has started, wall time | 48 ms | 141 ms |
| RigTune's background threads in the first 5 s, CPU time | 208 ms | 300 ms |
| Per frame | under 1 ns, nothing allocated | 13 ns, nothing allocated |
| Per tick, at the title screen | 59 ns, nothing allocated | 111 ns, nothing allocated |
| Per tick, in a world (session monitor off) | 26 ns, nothing allocated | 87 ns, nothing allocated |
| RigTune's own objects in memory, after a full garbage collection | 74 KB | 107 KB |
| RigTune objects left behind by opening and closing its screens and Tools 20 times | none | none |

Most of the startup time is Java loading classes the first time they're used, among them Gson's, which Minecraft loads soon after anyway. On the Windows PC above, the same startup work took 65 ms (62 ms of CPU time) when the budgets were calibrated.

**The Stutter Doctor's session monitor** is off by default. Off, it costs next to nothing: no garbage-collection listener, no thread, one flag check per frame and a few field reads per tick. While it's on (in a world), it holds about 2.5 MB of memory for its frame and event buffers and gives all of it back when it stops. It adds about 35 ns per frame, or about 240 ns with its per-phase timing; that's well under a thousandth of a frame even at 240 FPS. Its sampler thread uses up to about 55 ms of CPU per minute, under 0.1 % of one core. The same checks measure it, in the same release-candidate run:

| With the session monitor on | Release candidate | Budget |
|---|---|---|
| Per frame | 35 ns, nothing allocated | 75 ns, nothing allocated |
| Per frame, with the per-phase timing | 239 ns, nothing allocated | 400 ns, nothing allocated |
| Per tick | 49 ns, nothing allocated | 101 ns, nothing allocated |
| Its buffers in memory (their exact size in 0.4.0) | 2.51 MB | 2.5 MiB (2.62 MB) |
| Left in memory after it's turned off | nothing | nothing |
| Its sampler thread, CPU time per minute | 54 ms | 102 ms |

On the dev PC, three interleaved pairs of benchmark runs with the monitor on and off gave the same average FPS and 1 % lows within run-to-run noise (average −0.4 %, 1 % low −3 %, against a spread of 11-12 % inside each set). The benchmark records its own sweeps either way, so these pairs show what the monitor adds around a benchmark rather than the cost of recording itself, which the table above covers.

**Your own launch time.** Tools… shows your last launch time and the median of your last 10, and notes when your mod set changed since the previous launch. When a launch is noticeably slower than your usual (from at least 5 earlier launches of the same Minecraft version, past a noise floor of at least 10 %), a notice says so once, by how much and what changed before it (the mod count, the mod set or RigTune's version) as "may be related"; a slow streak is one notice. On a Windows PC whose performance counters are switched off, Tools says what that setting is and how long Minecraft's crash-report setup took at this launch, with Microsoft's documentation; RigTune reads that setting and never changes it. The times are kept in `config/rigtune/startup-times.json`, on your PC only. Fabric Loader doesn't time individual mods, so RigTune can't tell you which mod is slow: fewer mods and an SSD help most, and if the launch time jumped after you added a mod, check that mod first.
