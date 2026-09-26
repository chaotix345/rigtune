# RigTune v0.4.0 verification: Phase 5 evidence index

Release candidate: `feat/v0.4.0` @ 9cf84f6 (a3f5c14 adds only a PROGRESS line). The accessibility branch (item 11) was still pending when this ran. Machine: Ryzen 7 7800X3D, Radeon RX 7800 XT, 32 GB, 2560x1440 @ 180 Hz, Windows 11, JDK 25.0.4.1. Every local game launch took the machine-wide game-test lock (`C:/Dev/Worktrees/.gametest-lock`, atomic mkdir + owner.txt) and released it in the same command. The user's real instance was only read.

## P5-B (local game tests, production smokes, real instance, footprint numbers)

Branch `test/p5-smokes`. It has no product-code change, and two test-only changes in the gametest source set:
- `ProductionSmoke.v04Tools`: the Tools hub tour in every production smoke.
- `ServerLimitsGameTest`: a free port instead of 25565 (finding F2).

| deliverable | result | evidence |
|---|---|---|
| Local client game tests, 26.2 | **PASS** (16/16 classes, 179 screenshots) on launch 2. Launch 1 failed: the port conflict, F2 | [gametests/](gametests/README.md) |
| Local client game tests, 26.3 | **PASS** (16/16, 179 screenshots) on launch 3. Launches 1-2 failed only on the local footprint budgets (F1). No native crash in 3 launches | [gametests/](gametests/README.md) |
| Screenshot review at 640x480@2 (X7) | New screens fit and are readable; 3 LOW notes (F3-F5) | [gametests/](gametests/README.md#screenshots-reviewed-x7-640x480-at-gui-scale-2-unless-named) |
| Production smoke 26.2, a copy of the user's 50 mods (incl. DH, Iris, Sodium) | **PASS**. Real hardware, Tools hub + 5 tools, History, Preview and the tools write nothing, no RigTune WARN/ERROR | [smokes/26.2-user-mods/](smokes/26.2-user-mods/README.md) |
| The same with `-Dminecraft.launcher.brand=theseus` | **PASS**. "set in the Modrinth App" and the Modrinth App memory steps (RigTune screen and JVM & memory) | [smokes/26.2-user-mods/](smokes/26.2-user-mods/README.md) |
| Production smoke 26.3, a Modrinth set (Sodium, Iris, Lithium, FerriteCore, ImmediatelyFast, Entity Culling, Mod Menu, DH 3.3.2-26.3) | **PASS on launch 4**. Launch 1 ran offline (Modrinth network failure); launches 2-3 hit the native crash (0xC0000005); launch 4 used `ALSOFT_DRIVERS=null` | [smokes/26.3-modrinth/](smokes/26.3-modrinth/README.md) |
| AC2i.1 real-instance check (read-only) | **Done**: not played since 2026-09-25 09:08; state byte-identical to the E2E seed; nothing to compare yet | [real-instance.md](real-instance.md) |
| AC10.5 RC footprint numbers | **Collected** from CI run 36236205018 (all 3 legs, `fail` mode, 0 violations), for the coordinator to fold into README | [footprint/rc-numbers.md](footprint/rc-numbers.md) |
| CI on this branch | **Green** on [36241924437](https://github.com/chaotix345/rigtune/actions/runs/36241924437) attempt 2, every job. On attempt 1 both 26.3 legs timed out in `UiGameTest.checkSettings` → `waitForSaved` (UiGameTest.java:412), before any changed code runs. fix/review-7's run 36240897813 hit the same flake | CI |
| Findings | No release blocker. F1 (MEDIUM, local test gate), F2 (LOW, test port, fixed on this branch), F3-F6 (LOW) | [P5B-FINDINGS.md](P5B-FINDINGS.md) |

## P5-A (features)

Written by the P5-A verification agent itself:
- Stutter Doctor, induced-stutter runs (AC5.8): [stutter/](stutter/)
- JVM and GC advice, real arguments and benchmark pairs (AC6.6): [jvm/](jvm/)
- Benchmark history real run (AC7.6): [benchmark/](benchmark/)
- Driver strings, GL and 26.3 Vulkan (AC9.8): [drivers/](drivers/)
- Profiles real run (AC4.13): [profiles/](profiles/)
- Local dedicated server (AC8.5): [server/](server/)

## Earlier v0.4 evidence in this folder

- [footprint/README.md](footprint/README.md): the footprint guard's calibration (AC10.3) and the session-monitor budgets.
- [snapshot-canary/](snapshot-canary/): the snapshot canary workflow (WS-0).
- [ws-a/](ws-a/): the checkbox-label crops (AC2m.1).
- [jvm/local-gametestJvmArgs-26.2.txt](jvm/local-gametestJvmArgs-26.2.txt): WS-J's local `-PgametestJvmArgs` run.
