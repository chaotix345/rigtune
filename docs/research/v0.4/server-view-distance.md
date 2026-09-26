# Server-aware view/simulation distance (P1 item 8) for v0.4.0

Research date: 2026-09-26, on `feat/v0.4.0`. All packet/field/method names and bytecode claims below are
verified with `javap -p -c` (JDK 25 at `C:/Dev/Tools/jdk/jdk-25.0.4.1+1/bin/javap.exe`) against the real
merged client jars for both target versions:

- 26.2: `C:/Users/Admin/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`
- 26.3: `C:/Users/Admin/.gradle/caches/fabric-loom/26.3/minecraft-client.jar`

(These jars contain **both** client and server classes — confirmed by listing them: `net/minecraft/server/level/ChunkMap.class`, `ServerPlayer.class`, `ServerGamePacketListenerImpl.class`, `PlayerList.class` are all present alongside the client classes.) Fabric API events are verified against `fabric-networking-api-v1-6.3.4+2989c6a09e.jar` (26.2) and `fabric-networking-api-v1-6.3.8+fcdff87f5d.jar` (26.3) from `C:/Users/Admin/.gradle/caches/modules-2/`. Everything reported here was byte-identical between 26.2 and 26.3 unless a diff is called out — no Stonecutter version-gating (`//? if >=26.3 {`) is needed for this feature's detection/clamp logic.

The Distant Horizons claim in §6 could not be verified against DH's own docs directly (the gstack `/browse` skill's interactive upgrade/consent flow doesn't fit a non-interactive research subagent, and dedicated DH research agents — `r-dhiris`, `ws-d-dhiris` — are running elsewhere in this session); it is sourced from a `WebSearch` pass instead and marked accordingly. Treat §6 as lower-confidence than §1-5 and §7, and prefer the dedicated DH agents' output if it conflicts.

## 0. Headline

- **RigTune already has half of this feature, undocumented.** `BenchmarkController.maxRenderDistance(Options, boolean integratedServer)` (`src/client/java/io/github/chaotix345/rigtune/client/benchmark/BenchmarkController.java:690-704`) already reflects into the private `Options.serverRenderDistance` field and clamps the benchmark's max render distance to it when not on the integrated server. This is exactly item 8's benchmark-cap requirement for render distance — it just isn't surfaced to the user (no header text), doesn't cover simulation distance, doesn't persist across sessions, and doesn't feed the **Recommender** (the settings-recommendation engine never looks at server limits at all — confirmed by grep, zero hits).
- **The server does not send a per-player-clamped radius to the client.** `ClientboundLoginPacket`/`ClientboundSetChunkCacheRadiusPacket`/`ClientboundSetSimulationDistancePacket` all carry the server's raw `server.properties` `view-distance`/`simulation-distance` (via `PlayerList.getViewDistance()`/`getSimulationDistance()`), broadcast identically to every player. The per-player clamp (`ChunkMap.getPlayerViewDistance` = `clamp(player.requestedViewDistance(), 2, serverViewDistance)`) only governs which chunks the server actually loads/sends to that player — it is never communicated back as a separate number.
- **Vanilla already computes the client-side "effective" cap RigTune needs**: `Options.getEffectiveRenderDistance()` = `serverRenderDistance > 0 ? Math.min(renderDistance.get(), serverRenderDistance) : renderDistance.get()`. Byte-identical on 26.2 and 26.3. This is the `min()` the task asked to confirm, and it's already the mechanism RigTune's reflection hack reads (`serverRenderDistance` is the input to that method).
- Singleplayer vs LAN-host vs remote vs Realms is fully detectable with public vanilla API: `Minecraft.hasSingleplayerServer()` / `getSingleplayerServer().isPublished()` / `getCurrentServer().isRealm()` — no mixin needed for detection, only for reading the raw server radius (see §7).

## 1. How the client learns the server's view/simulation distance

### 1.1 Packet fields (verified identical 26.2 vs 26.3)

`net/minecraft/network/protocol/game/ClientboundLoginPacket` is a record; `javap -p` on both jars shows:

```
private final int chunkRadius;
private final int simulationDistance;
```

among its other fields (`playerId`, `hardcore`, `levels`, `maxPlayers`, ..., `commonPlayerSpawnInfo`, `onlineMode`, `enforcesSecureChat`). Constructor bytecode (26.2, identical on 26.3) reads these via `RegistryFriendlyByteBuf.readVarInt()` for both.

`net/minecraft/network/protocol/game/ClientboundSetChunkCacheRadiusPacket`: single `private final int radius;` field, `getRadius()` getter, dispatches via `ClientGamePacketListener.handleSetChunkCacheRadius`.

`net/minecraft/network/protocol/game/ClientboundSetSimulationDistancePacket`: record with `private final int simulationDistance;`, accessor `simulationDistance()`, dispatches via `ClientGamePacketListener.handleSetSimulationDistance`.

All three classes are byte-for-byte identical between 26.2 and 26.3 apart from constant-pool index shuffling (diffed with `javap -c` on both — no field, method, or control-flow differences).

### 1.2 Handler methods and where the values land (`ClientPacketListener`)

`net/minecraft/client/multiplayer/ClientPacketListener` (26.2 and 26.3, identical field names/types):

```
private int serverChunkRadius;
private int serverSimulationDistance;
```

- **`handleLogin(ClientboundLoginPacket)`** (called once, at login): bytecode shows
  `packet.chunkRadius() -> putfield serverChunkRadius` and `packet.simulationDistance() -> putfield serverSimulationDistance`, then both fields are read again a few instructions later and passed into `new ClientLevel(...)` (see §1.3).
- **`handleSetChunkCacheRadius(ClientboundSetChunkCacheRadiusPacket)`** (can arrive again later, e.g. if an admin changes `view-distance` live):
  ```
  this.serverChunkRadius = packet.getRadius();
  this.minecraft.options.setServerRenderDistance(this.serverChunkRadius);
  this.level.getChunkSource().updateViewRadius(packet.getRadius());
  ```
- **`handleSetSimulationDistance(ClientboundSetSimulationDistancePacket)`**:
  ```
  this.serverSimulationDistance = packet.simulationDistance();
  this.level.setServerSimulationDistance(this.serverSimulationDistance);
  ```

So the value is stored in **three** places depending on what you want to read:
1. `ClientPacketListener.serverChunkRadius` / `serverSimulationDistance` (private fields, both int) — the rawest source, but private with no getter.
2. `Options.serverRenderDistance` (private field, no public getter — `setServerRenderDistance(int)` and `getEffectiveRenderDistance()` are the only public surface). This is what `handleSetChunkCacheRadius` pushes to; **`handleLogin` does NOT push to `Options`** — only the two live-update handlers do (see §1.3 for how login-time radius reaches the client's chunk cache instead).
3. `ClientLevel.serverSimulationDistance` (private field) with a **public getter**, `getServerSimulationDistance()` — the only one of the three that needs no mixin/reflection.

### 1.3 `ClientLevel` construction (login-time path) and `ClientChunkCache`

`handleLogin`'s bytecode constructs the level directly from the two fields it just set:

```
new ClientLevel(this, levelData, dimension, dimensionTypeHolder,
                 this.serverChunkRadius, this.serverSimulationDistance,
                 levelExtractor, isDebug, biomeZoomSeed, seaLevel)
```

`ClientLevel`'s constructor (`net/minecraft/client/multiplayer/ClientLevel`, params `(..., int, int, ...)`):
- the first `int` (chunk radius) is passed straight into `new ClientChunkCache(this, chunkRadius)`, stored as the level's `chunkSource` field;
- the second `int` (simulation distance) is stored directly into `ClientLevel.serverSimulationDistance` (`putfield` at the same offset `getServerSimulationDistance()` reads from).

`ClientChunkCache` itself stores the radius **not** on itself but on an inner, hot-swappable `ClientChunkCache$Storage` instance: `private volatile ClientChunkCache$Storage storage;`, and `Storage.chunkRadius` is a private `int` with no getter — `updateViewRadius(int)` replaces the whole `Storage` object when the radius changes (rebuilds the chunk array). There is **no public getter anywhere on `ClientChunkCache`** for the current radius; `Options.serverRenderDistance` / `ClientPacketListener.serverChunkRadius` are the only readable sources (via mixin — see §7), unless you're happy with the min-already-applied `Options.getEffectiveRenderDistance()`.

Confirmed identical on 26.3 (same field names, same constructor shape, same `putfield`/`invokespecial` sequence — only constant-pool indices differ).

## 2. How the effective radius is decided (the "min()" question)

This is more subtle than "server sends min(client, server)" — the **packet the client receives always carries the server's raw configured value**, and the min-with-client's-own-setting happens independently on each side, for different purposes.

### 2.1 Server side: `PlayerList` broadcasts the raw configured value, unclamped

`net/minecraft/server/players/PlayerList.setViewDistance(int)` / `setSimulationDistance(int)` (called at startup from `server.properties`, and again if those are changed live) — bytecode (26.2, identical on 26.3 apart from constant-pool indices):

```java
// setViewDistance(int viewDistance)
this.viewDistance = viewDistance;
this.broadcastAll(new ClientboundSetChunkCacheRadiusPacket(viewDistance));   // <-- raw value, to EVERY connected player
for (ServerLevel level : server.getAllLevels()) level.getChunkSource().setViewDistance(viewDistance);
```

Same shape for `setSimulationDistance`. `PlayerList.placeNewPlayer(...)` constructs the login packet the same way: `new ClientboundLoginPacket(..., this.getViewDistance(), this.getSimulationDistance(), ...)` — i.e. the login packet's `chunkRadius`/`simulationDistance` are the **server-wide** `PlayerList.viewDistance`/`simulationDistance` fields (straight from `server.properties`), **not** the per-player clamped value. Confirmed by disassembling `placeNewPlayer` and finding `getViewDistance()`/`getSimulationDistance()` (not `getPlayerViewDistance(player)`) feeding the constructor call.

### 2.2 Server side: the per-player clamp exists, but only affects chunk *sending*, not the packet value

`net/minecraft/server/level/ChunkMap`:

```java
private int getPlayerViewDistance(ServerPlayer player) {
    return Mth.clamp(player.requestedViewDistance(), 2, this.serverViewDistance);
}
```

— i.e. effectively `min(max(player's requested view distance, 2), serverViewDistance)`. `ServerPlayer.requestedViewDistance()` is set from `ClientInformation.viewDistance()` (the value the *client* sends the server in `ServerboundClientInformationPacket`, i.e. the player's own render-distance option) via `ServerPlayer.updateOptions(ClientInformation)`. `getPlayerViewDistance` feeds `updateChunkTracking`/`applyChunkTrackingView`, which controls the `ChunkTrackingView` (how large an area of chunks the server actually loads and sends that specific connection) — **but the packet that tells the client "the radius is N" is never re-sent with this smaller, per-player number.** A player with a lower render-distance setting than the server just gets fewer chunks pushed (bandwidth/CPU saving); the client's allocated `ClientChunkCache` radius and `Options.serverRenderDistance` still reflect the server's raw configured ceiling. Byte-identical logic on 26.3 (`ChunkMap.getPlayerViewDistance`, `PlayerList.setViewDistance` diffed line-for-line).

### 2.3 Client side: `Options.getEffectiveRenderDistance()` — the real, verified min()

`net/minecraft/client/Options`:

```java
private int serverRenderDistance;               // pushed by ClientPacketListener.handleSetChunkCacheRadius
public void setServerRenderDistance(int d) { this.serverRenderDistance = d; }
public int getEffectiveRenderDistance() {
    return serverRenderDistance > 0
        ? Math.min(renderDistance.get(), serverRenderDistance)   // <-- confirmed Math.min bytecode, both versions
        : renderDistance.get();
}
```

This is byte-identical on 26.2 and 26.3 (`invokestatic Math.min:(II)I` in both disassemblies, only the constant-pool index differs: `#282` vs `#271`). **This is vanilla's own precedent for exactly the cap RigTune needs to show/apply** — RigTune should mirror this formula (client's chosen RD vs. server's raw ceiling), not invent a new one. Note again: `setServerRenderDistance`/`serverRenderDistance` is fed only by `handleSetChunkCacheRadius` (the live-update packet), not by `handleLogin` directly — but `handleSetChunkCacheRadius` is not guaranteed to fire independently of login in every server implementation (some servers send it right after login anyway); the safest read is `ClientPacketListener.serverChunkRadius`, which `handleLogin` **does** set immediately (see §7 for why the mixin should target `ClientPacketListener`, not `Options`).

### 2.4 Simulation distance

No per-player clamp exists server-side for simulation distance — `PlayerList.simulationDistance` is broadcast to everyone unclamped, and there is no `ChunkMap`-style per-player simulation-distance logic (simulation/entity-ticking radius isn't customized per player the way chunk-sending is). So simulation distance is simpler: the client's `ClientLevel.getServerSimulationDistance()` (public, no mixin needed) is *the* server value, full stop — there is no client-side "requested" simulation distance option to min() against in the way render distance has one, though the client does have its own `simulationDistance` option slider for its *own* rendering-adjacent behavior; vanilla doesn't compute an "effective simulation distance" the way it does for render distance (no `getEffectiveSimulationDistance()` method exists — checked, absent from `Options` on both versions).

## 3. Singleplayer vs LAN host vs remote multiplayer vs Realms

All confirmed present and public on both 26.2 and 26.3 (`net/minecraft/client/Minecraft`, `net/minecraft/client/multiplayer/ServerData`, `net/minecraft/client/server/IntegratedServer`):

```java
// Minecraft
private IntegratedServer singleplayerServer;
private boolean isLocalServer;
public boolean isLocalServer();                     // true for BOTH plain singleplayer and "Open to LAN"
public boolean hasSingleplayerServer();              // isLocalServer && singleplayerServer != null
public IntegratedServer getSingleplayerServer();
public net.minecraft.client.multiplayer.ServerData getCurrentServer();  // Optionull.map(getConnection(), ...) - null in singleplayer

// IntegratedServer
public boolean isPublished();                        // true only once "Open to LAN" has been used
public int getPort();

// ServerData
public boolean isLan();     // type == ServerData.Type.LAN
public boolean isRealm();   // type == ServerData.Type.REALM
// ServerData$Type enum: LAN, REALM, OTHER
```

Recommended detection (all methods verified to exist with these exact signatures):

| Case | Test |
|---|---|
| Singleplayer (not hosting) | `minecraft.hasSingleplayerServer() && !minecraft.getSingleplayerServer().isPublished()` |
| LAN host ("Open to LAN") | `minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer().isPublished()` |
| Remote multiplayer | `!minecraft.isLocalServer() && minecraft.getCurrentServer() != null && !minecraft.getCurrentServer().isRealm()` |
| Realms | `!minecraft.isLocalServer() && minecraft.getCurrentServer() != null && minecraft.getCurrentServer().isRealm()` |

**On the LAN-host case, "server view distance" is the *host's own* client-side render-distance/simulation-distance option**, because `IntegratedServer` reuses the singleplayer world's settings — `PlayerList.setViewDistance` on a LAN-hosted integrated server is driven by the same `Options` the host is using to play, not a separate `server.properties`. Practically: on LAN, the joining players' effective cap equals whatever render/simulation distance the hosting player's client is currently using, and it can change live if the host changes their own settings (since `IntegratedServer`/`PlayerList` calls `setViewDistance` again). RigTune should treat "I am hosting via Open to LAN" as **not** singleplayer-unlimited for header-text purposes if it ever inspects *joining players'* effective view — but for the host's own client, `hasSingleplayerServer()` is true, so today's singleplayer behavior (no cap shown) is correct for the host. This matches the brief's "singleplayer behaviour stays as today" — the host is functionally still singleplayer from its own client's point of view; only guests joining via LAN see a `getCurrentServer()` with `isLan() == true` and should get the server-aware treatment.

## 4. Design: capturing, persisting, and using the values

### 4.1 What already exists (don't rebuild this)

`BenchmarkController.maxRenderDistance(Options options, boolean integratedServer)` (`src/client/java/io/github/chaotix345/rigtune/client/benchmark/BenchmarkController.java:690-704`) already does:

```java
static int maxRenderDistance(Options options, boolean integratedServer) {
    int max = MAX_RD;
    // ...clamp to the renderDistance option's own IntRangeBase max...
    if (integratedServer) return max;
    try {
        Field field = Options.class.getDeclaredField("serverRenderDistance");
        field.setAccessible(true);
        int server = field.getInt(options);
        if (server > 0) max = Math.min(max, server);
    } catch (ReflectiveOperationException | RuntimeException e) {
        RigTune.LOGGER.debug("Server render distance unavailable", e);
    }
    return max;
}
```

called at `BenchmarkController.java:183`: `this.chunkLimit = maxRenderDistance(options, minecraft.hasSingleplayerServer());`, which already feeds `tuneMaxRenderDistance` (line 234) and the benchmark's settle-check clamp (line 462). **This already satisfies item 5's core ask for render distance** (benchmark won't test above the server's radius). Gaps to close, not duplicate:
- Uses reflection into a private vanilla field instead of a mixin accessor (works, but fragile-looking and doesn't declare intent; a one-line `@Accessor` mixin is more idiomatic for a mod that already has a `client/mixin` package and is cleaner than field reflection under Fabric's mixin-transformed classloader).
- Only covers render distance, not simulation distance (`simulationTunable()` at line 682 sidesteps the question entirely by disabling simulation-distance tuning outside singleplayer — reasonable for *why* it's tunable, so no change needed there, but the value should still be surfaced for the header/Recommender).
- Doesn't distinguish LAN/Realms/remote (`hasSingleplayerServer()` is the only branch) — fine for the benchmark's narrow purpose, but the header text (item 4) needs to say *which kind* of server is limiting the player.
- Nothing persists it or feeds it to `Recommender` (confirmed by grep: zero references to `serverRenderDistance`/server view distance anywhere in `core/recommend/Recommender.java` or `core/model/SettingsSnapshot.java`).

### 4.2 New model: `ServerLimits`

Add `io/github/chaotix345/rigtune/core/model/ServerLimits.java` (record, in `core`, so `Recommender` — which is in `core/recommend` and currently has zero Minecraft-class dependencies — can consume it without a client-module dependency, matching the existing `core`/`client` split):

```java
public record ServerLimits(int viewDistance, int simulationDistance, Kind kind, long lastSeenEpochMillis) {
    public enum Kind { SINGLEPLAYER, LAN_GUEST, REALM, REMOTE }
}
```

### 4.3 Capture: Fabric API event, not a packet-handling mixin

`net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents` (confirmed present, identical signatures, in `fabric-networking-api-v1` for both 26.2 and 26.3 — part of the `fabric-api` umbrella this project already depends on):

```java
public interface Join       { void onPlayReady(ClientPacketListener, PacketSender, Minecraft); }
public interface Disconnect { void onPlayDisconnect(ClientPacketListener, Minecraft); }
Event<Join> JOIN;
Event<Disconnect> DISCONNECT;
```

`JOIN` fires after login handling completes, so `serverChunkRadius`/`serverSimulationDistance` are already populated on the `ClientPacketListener` passed in — read them there via a small `@Accessor` mixin (see §7) rather than reflection, and rather than a `@Inject` into the packet handlers. Register in `RigTuneClient.onInitializeClient()` (`src/client/java/io/github/chaotix345/rigtune/client/RigTuneClient.java:67`), alongside the existing `ClientLifecycleEvents` registrations at lines 78-79.

**But `JOIN` alone misses live updates** (an admin running `/simulationdistance 6` mid-session re-sends `ClientboundSetSimulationDistancePacket`, and `handleSetChunkCacheRadius`/`handleSetSimulationDistance` fire again without a JOIN event). Fabric API has no generic "vanilla packet received" event for these two packet types (they're not chat, not S2C custom payload — checked `ClientPlayConnectionEvents`, `ClientReceiveMessageEvents`, nothing else in `fabric-networking-api-v1` matches). Two options, in order of preference:
1. **`@Inject` mixin into `ClientPacketListener.handleSetChunkCacheRadius`/`handleSetSimulationDistance`, tail, calling into a new `ServerLimitsTracker`** — small, follows the existing `DebugScreenOverlayMixin` pattern exactly (`rigtune$onSetChunkCacheRadius` etc.), and catches every update including the initial one via `handleLogin` (so JOIN + this mixin together are redundant-but-safe; simplest to just do this mixin alone and skip the Fabric event for capture, using `ClientPlayConnectionEvents.DISCONNECT` only, to know when to stop showing the "connected" state).
2. Poll `Options.getEffectiveRenderDistance()`/reflect `serverRenderDistance` once per tick from `ClientTickEvents` (already used elsewhere in `RigTuneClient.java:21`) — simpler, no mixin, but ugly and a tick late.

**Recommendation: mixin into `ClientPacketListener` (option 1)** — this project already ships a `client/mixin` package and a `rigtune.client.mixins.json` registration file, so the pattern (and the risk) is already accepted; a 3-method accessor+injector mixin on one class is low-risk and gives exact, immediate values with no polling.

### 4.4 Persistence: keyed by a hashed server address, new file, old versions ignore it

Follow the existing `core/history/Journal.java` pattern (`config/rigtune/history.json`, atomic writes via `core/apply/AtomicFiles.java`): add `config/rigtune/server-limits.json`, a small JSON map:

```json
{
  "version": 1,
  "servers": {
    "<sha256-of-normalized-address>": { "viewDistance": 12, "simulationDistance": 10, "kind": "REMOTE", "lastSeenEpochMillis": 1758844800000, "label": "mostly played" }
  }
}
```

- Key = SHA-256 of the lower-cased `host:port` (or Realms world UUID for Realms — `ServerData` doesn't expose the raw Realms ID directly in the disassembled API surface checked here, so for Realms fall back to hashing the display name + `isRealm()` flag; this is a minor gap worth a follow-up spike, not a blocker). Hashing avoids storing plaintext server IPs/hostnames in a file a user might paste into a bug report.
- A `"version": 1` top-level field, per the existing project convention (`Journal`'s format also carries a schema/version marker per `docs/v0.2/SPEC.md` references in that file) — new/old RigTune versions must ignore or migrate unknown fields; a version bump lets old RigTune builds simply skip the file (unknown filename entirely, so this is automatic — "a new file older versions ignore" is satisfied for free, since nothing in `core/history`/`core/apply` scans the config directory for unrecognized files).
- No `ApplyLock` needed (this is passive telemetry, not a settings mutation the undo system needs to track) — a plain atomic write via `AtomicFiles.writeString` is enough, following just that one piece of the existing pattern.

### 4.5 How Recommender and the header use it

- `Recommender.recommend(...)` (`core/recommend/Recommender.java`) gains an optional `ServerLimits` (or `Optional<ServerLimits>`) parameter, threaded through the same way `OnlineData`/`Goal` already are (line 74-80 shows the existing multi-overload pattern for adding a new optional input without breaking callers — mirror that: add an overload). After computing its normal `vanilla.renderDistance`/`vanilla.simulationDistance` recommendation, clamp: `Math.min(recommended, limits.viewDistance())`, and likewise for simulation distance, when `limits` is present and its `kind()` is not `SINGLEPLAYER`.
- **"Mostly plays that server" heuristic**: since a user may play both singleplayer and one or more servers, don't apply a *persisted* (not-currently-connected) `ServerLimits` clamp unconditionally — only apply it when (a) currently connected to that server (always clamp), or (b) not connected, but the persisted entry for the *most-recently-played* remote server has `lastSeenEpochMillis` within some recency window (e.g. 30 days) **and** the user hasn't played singleplayer more recently than that. Simplest correct rule given the ambiguity: **only ever clamp using the live, currently-connected `ServerLimits`; ignore persisted entries for the Recommender's clamp entirely**, and use persisted entries *only* to pre-fill the header/warning text when the RigTune screen is opened while disconnected ("Last time you played on this server, it limited view distance to N — reconnect to confirm" ), never to silently cap a recommendation for a session that might be singleplayer. This avoids the failure mode of a mixed singleplayer/multiplayer user getting an incorrectly-capped singleplayer recommendation because they last logged off from a restrictive server.
- Header text keys, following the existing `rigtune.header.*` convention in `src/main/resources/assets/rigtune/lang/en_us.json` (e.g. `rigtune.header.cpu`, `rigtune.header.tier` at lines 73-76):
  - `"rigtune.header.server_limit": "Server limits view to %s chunks (you asked for %s)"` — shown when `limits.viewDistance() < ClientInformation/current renderDistance option`.
  - `"rigtune.header.server_limit_sim": "Server limits simulation to %s chunks"`.
  - `"rigtune.header.server_kind_lan": "Hosting via Open to LAN"` / `"rigtune.header.server_kind_realm": "Playing on a Realm"` / `"rigtune.header.server_kind_remote": "Playing on %s"` — to explain *which* connection is doing the limiting, addressing the brief's "explain when the server, not the PC, limits what you see."

## 5. Benchmark: capping steps at the server's radius

`RenderDistancePlanner` (`core/benchmark/RenderDistancePlanner.java`) already takes `maxRd` as a constructor parameter (line 19) with no Minecraft dependency (it's a pure `core` class, unit-testable) — the cap only needs to happen at the call site, which `BenchmarkController` (client module) already does correctly for render distance (§4.1). Concretely, for v0.4.0:

1. Keep `BenchmarkController.maxRenderDistance` but replace the raw-field reflection with the new `@Accessor` mixin (§7) reading `ClientPacketListener.getServerChunkRadius()` off the current connection, and extend it to also read simulation distance (currently simulation-distance tuning is just disabled outside singleplayer via `simulationTunable`, which remains correct and needs no change — only the render-distance path needs the accessor swap).
2. When `BenchmarkController` is constructed with `!minecraft.hasSingleplayerServer()`, and the computed `chunkLimit` (§4.1's `maxRenderDistance`) is less than the configured `config.maxRenderDistance()`, surface a one-line notice through whatever result/summary text `BenchmarkResultScreen`/`RigTuneScreen` already renders (both already exist per the `ui` package listing) — reusing the new `rigtune.header.server_limit` key (§4.5) rather than inventing a benchmark-specific string, so the same sentence appears whether the user is looking at the header or the benchmark result.
3. No change needed to `RenderDistancePlanner` itself — it already treats `maxRd` as an opaque ceiling; the only work is computing the right ceiling and telling the user why it's lower than `config.maxRenderDistance()`.

## 6. Distant Horizons beyond the server's view distance — UNVERIFIED (see caveat above)

Sourced via `WebSearch` (not the DH GitLab wiki directly — a `WebFetch` of the "Server Owners" wiki page returned only nav-chrome, not body text, likely JS-rendered content the fetch tool couldn't extract). Cross-checked against two independent result snippets (the DH GitLab wiki's own FAQ content as summarized by the search engine, and community threads on AnswerOverflow quoting DH maintainers):

- **Client-only DH on a vanilla server**: DH caches LOD data from chunks the player has *actually received/explored*, and that cache persists between sessions (revisit an area once, its LOD is available from then on even before the full chunk re-loads). This does **not** let DH show LODs for terrain the player has never been near — it is not client-side world-generation prediction from the seed.
- **Real-time "Distant Generation" beyond the server's normal view distance** (DH proactively generating/streaming LOD terrain the player hasn't explored yet) requires DH's **server-side component** — either the DH mod itself on a Fabric/Forge/NeoForge server, or a separate Bukkit/Spigot/Paper/Folia plugin for non-modded servers — with "Distant Generation" enabled on **both** ends. On a genuinely vanilla server (no DH server-side piece at all), this mode does not run.
- **What RigTune's advice should say**: "Distant Horizons can still show terrain you've already explored beyond the server's view distance, but it can't generate *new* distant terrain unless the server also runs Distant Horizons (or its Bukkit/Spigot plugin) with Distant Generation enabled — ask the server admin if you want DH to work at full strength." Mark this string itself as sourced from this UNVERIFIED research pass in any PR/commit that adds it, and prefer whatever the dedicated `r-dhiris`/`ws-d-dhiris` research concludes if it's more specific or contradicts this.
- Sources (via WebSearch, not independently fetched/confirmed): GitLab wiki "Server Owners" FAQ (`gitlab.com/distant-horizons-team/distant-horizons/-/wikis/.../Server-Owners`), AnswerOverflow threads on DH's Discord ("Server LOD generation without players", "Clarification on preloading LODs and Chunks on Server?").

## 7. Files to add/modify, and test plan

### 7.1 New files

| File | Purpose |
|---|---|
| `src/main/java/io/github/chaotix345/rigtune/core/model/ServerLimits.java` | New record: `viewDistance`, `simulationDistance`, `Kind` enum (`SINGLEPLAYER`/`LAN_GUEST`/`REALM`/`REMOTE`), `lastSeenEpochMillis`. Pure `core`, no Minecraft classes. |
| `src/main/java/io/github/chaotix345/rigtune/core/history/ServerLimitsStore.java` (or under a new `core/server` package) | Read/write `config/rigtune/server-limits.json`, hashing the address key (`MessageDigest.getInstance("SHA-256")`), atomic write via existing `core/apply/AtomicFiles`. |
| `src/client/java/io/github/chaotix345/rigtune/client/mixin/ClientPacketListenerAccessorMixin.java` | `@Mixin(ClientPacketListener.class)` interface with `@Accessor("serverChunkRadius") int rigtune$getServerChunkRadius();` and `@Accessor("serverSimulationDistance") int rigtune$getServerSimulationDistance();` — both fields confirmed private with no existing getter (§1.2). |
| `src/client/java/io/github/chaotix345/rigtune/client/mixin/ClientPacketListenerJoinMixin.java` (or fold into the accessor mixin file as a second `@Mixin`) | `@Inject` tail of `handleLogin`, `handleSetChunkCacheRadius`, `handleSetSimulationDistance` → push into a new client-side `ServerLimitsTracker` (§4.3). |
| `src/client/java/io/github/chaotix345/rigtune/client/ServerLimitsTracker.java` | Holds the live `ServerLimits` for the current connection (null when disconnected/singleplayer); computes `Kind` from `Minecraft.hasSingleplayerServer()/getSingleplayerServer().isPublished()/getCurrentServer().isRealm()` (§3); calls `ServerLimitsStore` on join/disconnect. |

### 7.2 Modified files (hotspots)

| File | Change |
|---|---|
| `src/client/resources/rigtune.client.mixins.json` | Add the two new mixin class names to the `"client"` array (currently only `DebugScreenOverlayMixin`). |
| `src/client/java/io/github/chaotix345/rigtune/client/RigTuneClient.java` (around line 78-79) | Register `ClientPlayConnectionEvents.DISCONNECT` to clear the tracker's live state (join-time capture happens via the mixin, not the Fabric event, per §4.3 — but DISCONNECT is still useful to know "no longer connected" without a mixin). |
| `src/client/java/io/github/chaotix345/rigtune/client/benchmark/BenchmarkController.java:690-704` | Replace the `Field.setAccessible` reflection with a call to the new `@Accessor` mixin; extend to also compute a simulation-distance ceiling for use in header text (simulation *tuning* stays singleplayer-only per the existing comment at line 680-681, but the header can still report the ceiling). |
| `src/main/java/io/github/chaotix345/rigtune/core/recommend/Recommender.java` (new overload near line 69-77) | Add `Optional<ServerLimits>` (or overload) parameter; after resolving `vanilla.renderDistance`/`vanilla.simulationDistance`, clamp to `limits.viewDistance()`/`limits.simulationDistance()` when present and connected (not from a merely-persisted, disconnected entry — §4.5). |
| `src/main/resources/assets/rigtune/lang/en_us.json` (near lines 73-77, 122-124) | Add `rigtune.header.server_limit`, `rigtune.header.server_limit_sim`, `rigtune.header.server_kind_lan`, `rigtune.header.server_kind_realm`, `rigtune.header.server_kind_remote`. |
| Whatever screen currently renders the `rigtune.header.*` strings (`client/ui/RigTuneScreen.java`, confirmed to exist) | Add the server-limit line(s) when `ServerLimitsTracker` reports a live, non-singleplayer connection. |

### 7.3 Test plan

**Unit tests (pure `core`, no Minecraft, run in the existing `src/test` tree)**:
- `ServerLimits`/clamp math: given a `Recommendation` of e.g. RD 16 and a `ServerLimits.viewDistance()` of 10, assert the clamped output is 10; assert singleplayer `Kind` never clamps; assert a disconnected/persisted-only `ServerLimits` does **not** clamp (per §4.5's rule).
- `RenderDistancePlanner` already has no Minecraft dependency — add a case constructing it with `maxRd` = a server-capped value and assert `next()` never proposes above it (this mostly re-confirms existing behavior, since `maxRd` was already an opaque parameter — the new test is really about the call site, covered below).
- `BenchmarkController.maxRenderDistance`/`tuneMaxRenderDistance` (already `static`, package-visible per line 234, 690 — already testable): add a case with a mocked/stubbed server radius below `config.maxRenderDistance()` and assert the result is capped (this exercises the existing logic that item 5 already mostly satisfies — the new test just needs to swap in the accessor-mixin-backed read instead of reflection, or keep testing via reflection against a real `Options` instance since that still works in a JVM unit test without Minecraft running).
- `ServerLimitsStore` round-trip: write, read back, confirm hashed key stability for the same address and difference for different addresses/ports; confirm an unrecognized/future schema version doesn't crash a read (forward-compat check, mirroring `Journal`'s `.bad`-file handling at `core/history/Journal.java:166`).

**Manual/integration verification the human (or a follow-up CI job) should do with a real dedicated server** — per the task's constraint, this must be a **fresh vanilla or Fabric dedicated server in a scratch dir**, not the repo's own `fabric-server-launcher`:
1. Download a vanilla server jar matching 26.2 or 26.3 into a scratch directory (e.g. under the session scratchpad, never inside the mod repo), accept EULA, set `server.properties`: `view-distance=6`, `simulation-distance=5`.
2. Launch it standalone (`java -jar server.jar nogui`), confirm it's listening.
3. Connect the dev client (with RigTune installed) to `localhost:<port>`; verify RigTune's header shows "Server limits view to 6 chunks" (and the simulation-distance equivalent) once `ClientPlayConnectionEvents`/the login mixin fires.
4. Open RigTune's benchmark from that session; confirm the benchmark's render-distance steps never exceed 6, and that the result screen/summary states the server-imposed cap and why.
5. Disconnect, confirm `config/rigtune/server-limits.json` now has an entry keyed by the hashed `localhost:<port>` address with `viewDistance: 6`.
6. Reconnect after changing `server.properties` to `view-distance=10` and restarting the server; confirm the header updates to 10 (exercises `handleSetChunkCacheRadius`/a fresh login, not stale cached data).
7. Repeat steps 1-4 with **Open to LAN** from a second, separate singleplayer world instead of a dedicated server, from a second client, to confirm the LAN-guest path (`ServerData.isLan()`) is distinguished from remote in the header text, and that the *hosting* client still shows unrestricted singleplayer UI (per §3's LAN caveat).
8. Do **not** attempt to test the Realms path in an automated/scratch way — Realms requires a real Realms subscription and a live Mojang account session; note this as a manual, human-only check if it's ever exercised, and rely on the `ServerData.isRealm()` bytecode verification in §3 instead of an end-to-end Realms test.
