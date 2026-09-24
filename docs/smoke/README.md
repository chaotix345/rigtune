# Production smoke test, 2026-09-24

A read-only run of RigTune in a production Fabric client with the player's own mods: no Apply, no benchmark.

```
./gradlew runProductionSmoke -PextraModsDir=<mod jars> -PuserOptions=<options.txt> -PuserConfigDir=<config dir>
```

`runProductionSmoke` is a Loom `ClientProductionRunTask`, so it uses Knot, the real client jar and Fabric Loader 0.19.5 with no dev classpath. It loads the built `rigtune` jar, the game test jar and `fabric-client-gametest-api-v1`, which the fabric-api fat jar doesn't include. The player's jars are mirrored into `run/mods`, so RigTune treats them as the instance's own mods and offers updates it can apply. The player's `options.txt` goes into `run/`, with `fullscreen` forced off, and `sodium-options.json` goes into `run/config/`. The dev-only Sodium and Mod Menu aren't loaded; the player's copies are.

The game test framework resets the render distance to 5 after `options.txt` loads. Smoke mode puts back the player's values for the settings RigTune reads, in memory only (here 5 → 12), then rescans.

## Files

All of these come from the full set of 43 mods:

- `title.png`: the title screen with the RigTune button
- `rigtune-p1.png` to `rigtune-p4.png`: the RigTune screen at 1280×720, GUI scale 2, one page each
- `rigtune-in-world.png`: the screen opened with F8 in a new singleplayer world
- `rigtune-smoke-report.txt`: the full report as text (hardware, tier, and each recommendation)

## Excluded mods (world close only)

With all 43 mods, everything above works, but the client then hangs when it leaves the test world. A thread dump shows a deadlock inside the game test framework:

- The render thread is in `Minecraft.disconnect` → `IntegratedServer.halt` → `executeBlocking`, waiting for the server thread to run a task.
- The server thread is parked in the framework's phaser (`ThreadingImpl.enterPhase` from its `waitUntilNextTick` hook), waiting for the render thread.

This happens when the client's disconnect work takes longer than a server tick. RigTune isn't in either stack, and the phaser only exists under `-Dfabric.client.gametest`. We bisected it over 12 runs:

| Mods (plus fabric-api) | World close |
|---|---|
| fabric-api only | clean |
| C2ME, Carpet + Extra + TIS, Lithium, ModernFix, ScalableLux | clean |
| Xaero's Minimap only | clean |
| Xaero's World Map only (`xaeroworldmap-fabric-26.2-1.46.1.jar`) | hangs |
| all but Distant Horizons | hangs (World Map) |
| all but both Xaero mods | hangs (Distant Horizons, `fabric-26.2.jar`) |
| all but World Map and Distant Horizons (41 jars) | clean |

The clean run excludes only Xaero's World Map and Distant Horizons. Its report matches the full set's, except that the "Update Distant Horizons 3.3.0 → 3.3.2" item is missing.

## Caveats

- **Heap**: the production run uses the JVM's default heap (7.8 GB, a quarter of RAM), not the launcher's allocation. The Distant Horizons memory warning appears only below 5.5 GB.
- **Chunk wait**: `waitForChunksDownload` never passes at render distance 12 in this production harness, even with fabric-api alone. The in-world screenshot is taken after a 30 s timeout.
- **Remote rules**: `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v1.json` returns 404, so the report uses the bundled rules (r3). The Modrinth lookups worked (header shows "Online").
