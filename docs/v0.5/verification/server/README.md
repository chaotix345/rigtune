# LAN guest and Realms: evidence (WS-E, SPEC 3d)

Game test: `src/gametest/java/io/github/chaotix345/rigtune/gametest/LanGuestGameTest.java`. Design: docs/v0.5/design/ws-e.md.

## What the test does

- **LAN guest (AC3d.1).**
  - A dedicated server from `worldBuilder().createServer(props)`: `online-mode=false`, `view-distance=6`, `simulation-distance=5`, a free port (never 25565).
  - Vanilla's own `new LanServerPinger("RigTune LAN test <port>", "<port>")` announces it: a MOTD and the port, which is what `publishServer` passes. The port in the MOTD lets the test pick its own server's entry (review L5).
  - `JoinMultiplayerScreen` lists it under "Scanning for games on your local network". The test selects the `NetworkServerEntry` and presses **Join Server** (`selectServer.select`), as a player does. `NetworkServerEntry.join()` is what makes the `ServerData` a LAN one; the fabric API's `connect()` would give `Type.OTHER`.
  - Checks:
    - `getCurrentServer().isLan()`;
    - `liveState()`: kind LAN_GUEST, 6/5, address `lan:<host>`;
    - the render-distance increase is capped to 6 with "The server sends at most 6 chunks.";
    - server-limits.json holds one entry, `kind: LAN_GUEST`, keyed HMAC-SHA256(the file's salt, `lan:<host>`) (computed in the test);
    - entries hold only `viewDistance`/`simulationDistance`/`kind`/`lastSeen`: no host, no port, no `lan:`;
    - the notice is "The server limits view distance to 6 chunks".
  - Then a restart on another port with `view-distance=4` and a rejoin from a fresh LAN list:
    - the same host;
    - still one entry, now 4;
    - the cap is 4;
    - the notice says 4 and its detail "This server's limit changed since last time (was 6).".
- **Realms (AC3d.2).**
  - `new RealmsServer()` named "RigTune Realm test", then `new RealmsConnect(screen).connect(server, ServerAddress.parseString(host + ":" + port))` against the same offline server.
  - The test calls `RealmsConnect.tick()` every client tick until the world loads, as vanilla's connect-task screen does.
  - This is vanilla's Realms connection path with a REALM-typed `ServerData` (`RealmsServer.toServerData`). It makes no Realms API call.
  - Checks:
    - `getCurrentServer().isRealm()`;
    - kind REALM with address `realm:RigTune Realm test`;
    - a second entry keyed HMAC(salt, `realm:RigTune Realm test`) with kind REALM, and the world name not in the file;
    - the notice.
- **No `publishServer`** (SPEC-17): its signature differs between 26.2 and 26.3. The Open-to-LAN host's SINGLEPLAYER classification stays with ServerLimitsTracker's unit tests.
- **javap, 26.2 vs 26.3** (Loom's mapped client jars): every class the test uses has identical signatures on both:
  - `LanServerPinger`, `LanServer`, `ServerSelectionList` and its `NetworkServerEntry`, `JoinMultiplayerScreen`, `RealmsConnect`, `RealmsServer`;
  - `Options.highContrast`'s pack callback, which A11yGameTest uses (3f).

## Runs

| date | where | result | evidence |
|---|---|---|---|
| 2026-09-27 | local, Windows 11, 26.2 production client (`-PgametestClasses=LanGuestGameTest,A11yGameTest`) | PASS, 17.2 s | `local-windows-26.2/*.png` |
| 2026-09-28 | CI [36363179808](https://github.com/chaotix345/rigtune/actions/runs/36363179808): 26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan | PASS, 13.4 / 14.9 / 14.5 s | the `gametest-screenshots-*` artifacts |

On Windows the LAN detector saw the host at its LAN interface address (`192.168.4.71:<port>`), not 127.0.0.1. This is Windows' multicast loopback. The key follows the detected host (`lan:192.168.4.71`). CI detects it differently (below).

**In CI the detected address is `127.0.0.1:<port>`** (since 5078fb90).
- The game runs in `tools/ci/offline.sh`'s namespace. While its multicast route `224.0.0.0/4 dev lo` named no source address, the pinger's packets left with source 0.0.0.0, the product keyed the host as `lan:0.0.0.0`, and the first CI run (36361989137), which asserted 127.0.0.1 (SPEC 1a's wording), failed on this alone.
- The route now names `src 127.0.0.1` (the coordinator's decision): run [36377856700](https://github.com/chaotix345/rigtune/actions/runs/36377856700) detected `127.0.0.1:<port>` on all 3 legs, both joins each, with MulticastCheck "received from 127.0.0.1". The CI check is 127.0.0.1 again.

## Stays UNVERIFIED

- **A second PC's Open-to-LAN host.** Its integrated server authenticates guests, and the one-client rule applies.
- **The real Realms service.** The user has no Realms subscription, so only the client path above is tested.
- **Windows Firewall's multicast prompt, and networks that block multicast.** The local run passed unattended. Whether this PC already had firewall rules for this Java wasn't checked.
