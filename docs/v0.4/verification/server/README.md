# AC8.5: server-aware advice against a fresh vanilla dedicated server (P5-A, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6), `rigtune-0.4.0-dev+mc26.2.jar`
sha256 `149f20f9234c760ff1f446dce4cedf054d54b28ccf9f3839e48624002913dd11`, built with `./gradlew :26.2:jar`.

## Setup
- Server: vanilla **26.2** `server.jar` from Mojang's piston-meta (`https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar`,
  sha1 `823e2250d24b3ddac457a60c92a6a941943fcd6a`, verified), in a scratch directory only, started with the portable JDK
  (`C:/Dev/Tools/jdk/jdk-25.0.4.1+1/bin/java -Xmx2G -jar server-26.2.jar nogui`). `eula=true` in that scratch directory only.
  `server.properties` (final copy: `server.properties.final`): `online-mode=false`, `server-ip=127.0.0.1`,
  `server-port=25577` (not 25565; the user's `fabric-server-launcher.jar` on 25565 was never touched),
  `view-distance=6`, `simulation-distance=5`, RCON on 127.0.0.1:25578 (only to send `stop`). Stopped cleanly with
  `stop` over RCON (`../p5a-tools/rcon.py`, `restart.sh`) after the runs (console: `server-console-excerpt.txt`).
- Client: a production 26.2 client (Loom `e2eClient`, the RC jar + fabric-api 0.161.0+26.2 + Sodium 0.9.2 in a scratch
  instance's `mods/`), driven by the test-only driver mod `../p5a-tools/driver` (it calls RigTune's controller API and
  screenshots; no product code changed). One client at a time under the machine-wide lock (`../p5a-tools/run.sh`).
- Commands (from the worktree, `JAVA_HOME` = the portable JDK):
  `./gradlew -I p5a-init.gradle :26.2:e2eClient -Pe2e.instance=<scratch>/inst/p5a-inst-srv262 -Pe2e.driver=undo -Pe2e.jvmArgsFile=<args>`
  with the driver script in the args file (`-Dp5a.script=...`; exact scripts in `p5a-srv1.json` / `p5a-srv2.json` under `script`).

## Is there a live view-distance command?
No. Vanilla 26.2's dedicated server has no command that changes the view distance at runtime: `view-distance 10`,
`viewdistance 10`, `setviewdistance 10` all answer "Unknown or incomplete command", and the server jar's
`net/minecraft/server/commands/` has no such class (list checked). So the "live change" is research §7.3 step 6:
edit `server.properties` (`view-distance=10`) and restart the server, then reconnect.

## Runs and results
| step | what happened | result |
|---|---|---|
| srv1: client RD 4 (options.txt), server view-distance 6 | connected; `serverLimits()` = `ServerLimits[viewDistance=6, simulationDistance=5, kind=REMOTE]` | |
| header notice (connected) | "The server limits view distance to 6 chunks"; detail "Simulation distance on this server: 5 chunks (the server decides it)." (`p5a-srv1-rigtune-connected.png`, `p5a-srv1-noticescreen-connected.png`) | PASS |
| RD recommendation (W-H1: cap only lowers a proposed increase) | target 12 > current 4 → "Render Distance: 4 → 6", reason "... The server sends at most 6 chunks." | PASS |
| disconnect | notices `[]`, `serverLimits()` null, recommendation back to "4 → 12" without the server reason (`p5a-srv1-rigtune-disconnected.png`) | PASS |
| server restart with view-distance 10; srv2: client RD 16 | notice "The server limits view distance to 10 chunks (you set 16)"; detail "You set 16; the server sends 10, so 10 is what you see. Simulation distance on this server: 5 chunks (the server decides it). This server's limit changed since last time (was 6)." (`p5a-srv2-rigtune-connected.png`, `p5a-srv2-noticescreen-connected.png`) | PASS (notice updated, "(was 6)" from server-limits.json) |
| W-H1, current above the limit | client 16, limit 10: no server-driven decrease; the only RD recommendation is the hardware one "16 → 12" with no server reason (the same with and without the connection) | PASS |
| disconnect (srv2) | notices `[]` (`p5a-srv2-rigtune-disconnected.png`) | PASS |
| server-limits.json | one entry; key `ce059f1e…` = HMAC-SHA256(key = the file's 16-byte `salt`, message `127.0.0.1:25577`) (recomputed independently in Python); no `127.0.0.1`, `localhost` or `25577` anywhere in the file (`server-limits.json`) | PASS |

Log excerpts: `srv1-log-excerpt.txt`, `srv2-log-excerpt.txt` (driver lines: notices with message/detail, the RD
recommendation, `serverLimits()`); full driver output: `p5a-srv1.json`, `p5a-srv2.json`.

## Notes
- The notice's detail text is shown as the notice line's tooltip on RigTuneScreen (SPEC C3); NoticeScreen lists the
  message line only. The detail strings above come from `controller.notices()`.
- **LAN guest path: not run.** It needs a second client connected at the same time as the Open-to-LAN host, and the
  machine-wide rule allows one Minecraft client at a time. Covered only by the kind logic/unit tests (WS-W). UNVERIFIED end to end.
- **Realms: not tested end to end** (needs a Realms subscription and a signed-in account; recorded as SPEC AC8.5 says).
- Research §7.3 step 4 (a benchmark on the server capped at 6) was not run here; BenchmarkControllerServerLimitTest and
  ServerLimitsGameTest cover the clamp.
- The DH "may still show terrain" note wasn't exercised (no DH in this instance); research §6 stays UNVERIFIED.
