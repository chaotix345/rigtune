# v0.5 P1.3 (C16): per-server profile offers: design

Summary (10 lines):
1. The player opts in per server: Profiles → **Servers…** (a new `ServerProfilesScreen`) → "Offer <profile> here". On a later join, if that profile isn't active, RigTune shows one toast ("Press F8 to switch") and a notice with **Switch** / **Don't offer here** / ×. It never switches by itself.
2. The switch is `ProfileService.switchProfile(id)`, unchanged: one `apply` journal entry labelled "Profile: X", Undo this/last/all, the existing clamps and refusals (benchmark, downloading, not ready). Settings only, so it works the same whether or not the launcher manages the mod files (the new P0).
3. Servers only: remote servers, LAN guests and Realms, using ServerLimitsTracker's identity. Never your own worlds, an Open-to-LAN host or the benchmark world.
4. Storage: a new `config/rigtune/server-profiles.json` (`ServerProfileStore`, `StateStore` rules, at most 32 servers, at most 16 KiB). It has its own random salt and uses the same HMAC-SHA256 and address normalisation as server-limits.json, through ServerLimitsStore's package-private `key()` from the same package. ServerLimitsStore itself doesn't change. The docs say "not stored in readable form" and claim nothing stronger.
5. Priority: a new `NoticePriority.SERVER_PROFILE` right after BATTERY_OFFER, ahead of SERVER_LIMIT. The code shows the server-limit notice fires on every remote connection (not only on busy servers), so any slot below it would hide the offer on every server.
6. Deleted or unknown mapped profile: no offer, fail closed. Deleting a profile in Profiles also forgets its servers. Renaming keeps the mapping (ids are `p-<uuid>`). There is no switch-back offer when you leave a server; History's Undo and My settings are the way back.
7. Compatibility: one new file that 0.1.x-0.4.x never read. profiles.json, server-limits.json and awareness.json get no new fields. The NoticePriority enum is in-memory only. A downgrade to 0.4.0 simply loses the offers.
8. Per-frame cost: none. JOIN and DISCONNECT handlers do O(1) work on the render thread, and file and HMAC work runs on `Probes.EXECUTOR`. The notice source reads only an `AtomicReference` when no offer is pending. 26.2 and 26.3 are the same for every API used (checked in the class files).
9. Effort: **3.0 agent-days** (the brainstorm said 2.5; the game test with three connects and the new screen are the difference).
10. Riskiest part: when JOIN fires in real multiplayer. The toast can land during terrain loading, and a proxy backend switch or reconfiguration may fire JOIN again (UNVERIFIED), hence at most one toast per server per game session. After that come merges on shared hotspots (NoticePriority, which C18 also changes; ProfileService, which PF-1..PF-5 also touch; RealController; en_us.json).

Method: I read the docs listed in the brief and the code cited below, on `feat/v0.5.0` @ 3e97cbec. There's no JDK on this PC (no `javap`), so API claims were checked with a small Python class-file reader (constant pool, fields, methods, member refs; scratchpad `f-serverprof/cls.py`) against Loom's `minecraft-client.jar` for 26.2 and 26.3 and the Fabric API module jars those versions pin (from the fabric-api 0.161.0 POMs: 26.2 = networking 6.3.4, client-gametest 6.0.2; 26.3 = networking 6.3.8, client-gametest 6.0.7). Anything I couldn't check says UNVERIFIED.

---

## 1. The brainstorm's open questions and the critic's corrections, with evidence

**Q1: can BatteryNoticeSource's offer-never-switch pattern be reused as it is?** Only as a pattern. `BatteryNoticeSource` is a 25-line delegate (`client/notice/BatteryNoticeSource.java:16-24`). The logic lives in `ProfileService`: the `AtomicReference<Offer>` (`client/profile/ProfileService.java:85`), the notice (`:279-295`), the actions (`:297-314`) and `retire` (`:317-319`). Its decision (`core/profile/BatteryPrompt.onEdge`, `BatteryPrompt.java:28-42`) is driven by power edges and keeps its state in profiles.json's `battery` object. C16 therefore gets its own `NoticeSource` (`ServerProfileNoticeSource`), its own service (`ServerProfileService`) and its own pure decision (`ServerProfilePrompt`). It reuses battery's shape (an in-memory offer with a per-occurrence key, compare-and-set retire, a SystemToast) and avoids three audit bugs:
- PF-3 ("Switch back to ??" after a delete, `ProfileService.java:284-288,485-486`): the new source resolves the profile on every `current()` and retires the offer when the profile is gone.
- PF-2 ("Don't offer again" can't be undone, `:303-305`): "Don't offer here" only forgets this server, and setting it again undoes that.
- PF-1 (a null "previous", `:334,367-368`): there is no "previous profile" to track.

**Q2: the "remember this for here" UX.** Neither a checkbox on the switch nor a follow-up prompt. A follow-up prompt would be a second notice after every manual switch on a server, which is exactly the nagging the pitch warns against. A checkbox needs room on ProfilesScreen, whose list already gets only `row1 - 4 - 32` px (`client/ui/ProfilesScreen.java:96-100`). The choice: a **Servers…** button in ProfilesScreen's third row, which opens `ServerProfilesScreen`. There, "Offer <profile> here" takes the profile selected in Profiles, else the active one. Full UX in §2.5.

**Q3: pruning of server-profiles.json.** At most 32 remembered servers (the same number as `ServerLimitsStore.MAX_SERVERS`, `core/server/ServerLimitsStore.java:36`) and 16 KiB. A 33rd is **refused** with a status line ("Forget one first"), the way `ProfileStore.saveProfile` refuses a 51st profile (`core/profile/ProfileStore.java:174-178`, `rigtune.profile.status.full`). Nothing is evicted by age or LRU, because each entry is an explicit choice. A player returning to a server after a year still gets their offer, and the list shows "last joined" dates for clean-up. Mappings to a deleted profile are removed when it's deleted through Profiles (§2.3).

**Critic §9.3, medium (priority vs SERVER_LIMIT): confirmed, and worse than stated.** `ServerLimitNoticeSource.notice` returns a notice for every non-SINGLEPLAYER connection with `view > 0` (`client/notice/ServerLimitNoticeSource.java:43-47,66-67`). The login packet always carries a chunk radius, so every server gets it, not just busy ones. It has no actions (`:39-41`), and it's hidden only once the player dismissed that exact `server-limit:<N>[:above]` key (stored for good, `client/awareness/AwarenessService.java:204-205`). `NoticeBoard.select` sorts by ordinal (`core/notice/NoticeBoard.java:49`), and RigTuneScreen shows one notice plus "+N more" (`client/ui/RigTuneScreen.java:393-441`). A slot below SERVER_LIMIT would therefore put the offer behind "+1 more" on essentially every server. The critic's alternative, a follow-up after the server-limit notice is dismissed, would tie the offer to an unrelated, purely informational notice that most players never dismiss.
**Decision:** `SERVER_PROFILE` is declared between `BATTERY_OFFER` and `SERVER_LIMIT`. There is also a toast on join, because the notice line exists only on RigTuneScreen. In game that screen is reached by F8 (`client/RigTuneClient.java:80,175-179`) or from Video Settings (`:254-257`). Without the toast, a player who never opens RigTune on the server would never see the offer.

**Critic §9.3, low (the HMAC identity can't be shared): confirmed.** The salt is private and made once per file (`ServerLimitsStore.java:135-150`), and `key()` is package-private (`:125-133`). There are three options:
- (a) A new accessor on ServerLimitsStore. That changes a class 0.4.0's file contract depends on, and ties the mappings to that file: a corrupt server-limits.json is moved aside and its salt replaced (`:135-141`), which would silently orphan every mapping.
- (b) Store the mapping inside server-limits.json. Not downgrade-safe: 0.4.0's `remember()` rebuilds the whole entry object (`:108-114`), so an extra `"profile"` field would be lost the next time 0.4.0 joins that server. Its 32-entry LRU prune (`:196-213`) would also evict remembered servers.
- (c) **Chosen:** a new file with its own salt. `ServerProfileStore` lives in `core/server/`, so it calls the package-private static `ServerLimitsStore.key(salt, address)` with no visibility change. It reuses the public normalisers `address/lan/realm` (`:66-86`) through `ServerLimitsTracker.address(kind, data)` (`client/server/ServerLimitsTracker.java:154-167`, made public). Correct wording: "the same keyed-hash technique and address normalisation, with its own random key", not "the same identity". A side benefit is that the two files' keys can't be linked to each other.

**Critic §9.5 cross-cutting note (accessibility debt): addressed.** The new screen uses `RowList` + `RowFocus` for rows, `RowFocus.standalone` for its text lines and `Palette.of` for every colour, and joins A11yGameTest's Tab walk (§5). The notice inherits the notice line's a11y (`RigTuneScreen.java:452-456`, NoticeScreen).

**Pitch risks: "must not fire for singleplayer" and "a stale mapping must fail closed".** The kind comes from `ServerLimitsTracker.kind` (`:144-152`): `hasSingleplayerServer()` means SINGLEPLAYER, which covers the player's own world, an Open-to-LAN host and the benchmark world. Those are never stored and never offered (§2.2). For stale mappings, see §2.3.

**Other code facts the design relies on:**
- A switch builds `SetSetting` actions only (`core/profile/ProfileSwitch.java:48`), so the launcher-managed P0 (no jar changes) doesn't affect C16.
- Fabric fires `ClientPlayConnectionEvents.JOIN` from its `ClientPacketListenerMixin.handleServerPlayReady`, injected at RETURN of `handleLogin`, into `ClientPlayNetworkAddon.onServerReady` (checked in networking 6.3.4 and 6.3.8). So JOIN comes with the login packet, while the terrain-loading screen is up.
- The client game-test API's `TestDedicatedServerContext.connect()` keeps no connection state (`TestDedicatedServerContextImpl` has only a `context` field in 6.0.2 and 6.0.7). It builds `new ServerData(…, "localhost:" + port, Type.OTHER)` and `ConnectScreen.startConnecting`, so `connect()` can be called again after a connection is closed. That makes "join the same server twice" possible in one test.

---

## 2. The design

### 2.1 Data flow

```
JOIN (render thread; ServerProfileService.register(), registered after ServerLimitsTracker.register())
  kind    = ServerLimitsTracker.kind(mc, listener.getServerData())        [made public]
  address = ServerLimitsTracker.address(kind, data)                        [made public; normalised, in memory only]
  if current connection has the same kind+address (no DISCONNECT since): return   // repeated JOIN (proxy/reconfig) = same connection
  current = Connection(++connections, kind, address, joinedAtMillis); offer.set(null)
  if kind == SINGLEPLAYER || address == null: return
  runAsync on Probes.EXECUTOR:
      entry  = ServerProfileStore.shared(configDir).joined(address, now)   // HMAC + read; touches lastSeen when found
      reason = ServerProfilePrompt.decide(kind, entry?.profile, profileService.nameOf(...) != null,
                                          profileService.activeProfileId(), benchmarkRunning, hardware.onBattery())
      if reason == OFFER && connection unchanged: offer.set(Offer(connection, "server-profile:" + joinedAtMillis, profile, entry.key))
      minecraft.execute: if offer set && toasted.add(entry.key): SystemToast "Profile for this server" / "<name> is set for this server. Press F8 to switch."
DISCONNECT: connections++; current = null; offer.set(null)

RigTuneScreen/NoticeScreen init (render thread) → NoticeCenter → ServerProfileNoticeSource.current()
  → ServerProfileService.notice(): offer.get() == null → null (no I/O). Else re-decide with fresh active/name/battery/benchmark:
     ALREADY_ACTIVE or MISSING_PROFILE → retire (compareAndSet) and null; BENCHMARK or ON_BATTERY → null (kept); OFFER → Notice.
act("switch") → toast(profileService.switchProfile(profile))   // refusals come back as the message; the offer stays until
                                                              //  the profile is active (then current() retires it)
act("forget") → store.forget(address); retire; toast "RigTune won't offer a profile on this server any more."
× (dismiss)  → NoticeCenter → AwarenessService.dismiss: hidden for this connection (per-join key; session-only, §2.4)
```

### 2.2 Scope: which places get offers

| Place | Offered? | Identity | Why |
|---|---|---|---|
| Remote server (server list or Direct Connect) | yes | `host:port`, lower case, default port written out | The feature's target. |
| LAN guest (joined from the LAN list, `ServerData.isLan()`) | yes | `lan:<host>` | Same identity as server-limits.json: Open to LAN picks a new port every time (ws-w). Caveats in the docs: a DHCP address change loses the match, every world that host opens counts as one place, and a LAN game added to the server list by hand is `host:port`, which won't match next time. |
| Realm | yes, UNVERIFIED end to end | `realm:<world name>` | `RealmsServer.toServerData(String)` builds `ServerData(…, REALM)` from `RealmsServer.name` (26.2 and 26.3 class files). Renaming the Realm loses the match, and two Realms with the same name collide. There's no Realms subscription for a test (as for AC8.5). |
| Own singleplayer world | **no** | none | (1) The pitch's own risk line. (2) The player controls that world's limits, and the main list already fits the PC. (3) A world key would be a readable folder name ("Charlie's world") needing its own hashing. (4) A P2 per-world idea shouldn't be mixed in here. |
| Open-to-LAN host | **no** | none | `hasSingleplayerServer()` means SINGLEPLAYER (`ServerLimitsTracker.java:145-147`), consistent with server-limits. |
| Benchmark world | **no** | none | Singleplayer, and a running benchmark is refused anyway. |

### 2.3 Classes to add and change

**New (core, no Minecraft classes):**
- `core/server/ServerProfileStore`: `server-profiles.json` through a `StateStore`, one instance per file per process (`shared(configDir)`), because the render thread (remember and forget) and `Probes.EXECUTOR` (the join lookup) both write it.
  - `@Nullable Entry joined(String address, Instant now)`: lookup, plus a `lastSeen` write only when the server is there.
  - `@Nullable Entry get(String address)`.
  - `Result remember(String address, ServerLimits.Kind kind, String profileId, Instant now)`, where `Result` is `OK | FULL | READ_ONLY | FAILED`. It refuses SINGLEPLAYER and a malformed profile id.
  - `boolean forget(String address)`, `forgetKey(String key)`, `int forgetProfile(String profileId)`, `boolean forgetAll()`.
  - `List<Entry> entries()`: type-checked, newest `lastSeen` first.
  - `@Nullable String keyOf(String address)`: the "This server" row. It writes nothing and returns null without a salt.
  - `boolean writable()`.
  - `record Entry(String key, String profile, ServerLimits.Kind kind, @Nullable Instant setAt, @Nullable Instant lastSeen)`.
  - The key is `ServerLimitsStore.key(salt, address)`, called with package access. Profile ids are validated against `p-[A-Za-z0-9-]{1,64}` or `template:[a-z_]{1,32}` (the same shapes as `ProfileStore.java:119,121`, redeclared, so ProfileStore doesn't change).
- `core/profile/ServerProfilePrompt`: a pure decision. It returns `Reason` = `OFFER | NO_SERVER | NO_MAPPING | MISSING_PROFILE | ALREADY_ACTIVE | BENCHMARK | ON_BATTERY`, from `(kind, mapped, mappedResolves, active, benchmarkRunning, onBattery)`, checked in that order. ON_BATTERY means `onBattery && BatteryPrompt.BATTERY.equals(active)`.
- `core/profile/ServerProfilesView`: the screen's read model, so `RigTuneController` can return it and stub controllers can fake it.
  - `record ServerProfilesView(State state, @Nullable ServerLimits.Kind kind, @Nullable String currentKey, @Nullable String currentProfile, @Nullable Text currentProfileName, boolean heldOnBattery, @Nullable String activeProfile, @Nullable Text activeProfileName, List<Row> rows, boolean writable)`.
  - `enum State { NOT_CONNECTED, OWN_WORLD, UNRECOGNISED, SERVER }`.
  - `record Row(String key, ServerLimits.Kind kind, String profile, @Nullable Text profileName, @Nullable String lastJoined, boolean current)`, where `lastJoined` is the local day as `yyyy-MM-dd`, like `TrendText`.
  - `EMPTY`.

**New (client):**
- `client/server/ServerProfileService`: the flow in §2.1, with no work in its constructor (C4).
  - Constants: `KEY_PREFIX = "server-profile:"`, `ACTION_SWITCH`, `ACTION_FORGET`, and `TOAST_ID = new SystemToast.SystemToastId(8000L)`. 8 s, so a toast that appears during terrain loading is still up when the world shows (UNVERIFIED timing, checked in the real run).
  - `register()`, `@Nullable Notice notice()`, `act(String)`, `ServerProfilesView view()`.
  - `Component remember(@Nullable String profileId)`, `forget(String key)`, `forgetAll()`, `void forgetProfile(String id)` (forgets only when `nameOf(id) == null`).
  - `static overrideBenchmarkCheck(BooleanSupplier)` for the game test, like `ProfileService.java:373-375`.
  - A session `Set<String>` of keys already toasted.
  - A static `notice(key, name)` builder, so it can be unit-tested like `ServerLimitNoticeSource.notice` (`:43`).
- `client/notice/ServerProfileNoticeSource`: `current()` returns `controller.serverProfileService().notice()`, and `act` delegates (the BatteryNoticeSource shape).
- `client/ui/ServerProfilesScreen`: §2.5.

**Changed (small; hotspots flagged in §7):**
- `core/notice/NoticePriority`: `SERVER_PROFILE` between `BATTERY_OFFER` and `SERVER_LIMIT`.
- `client/RealController`:
  - The field and its construction next to `serverLimitsTracker` (`:175`).
  - `new ServerProfileNoticeSource(this)` right after `new BatteryNoticeSource(this)` in the NoticeCenter list (`:179-181`, "in NoticePriority order").
  - A `serverProfileService()` accessor.
  - Four one-line delegations.
  - `deleteProfile` (`:914-916`) gains `serverProfileService.forgetProfile(id)`, which stays out of ProfileService, where PF-3 is being fixed.
- `client/ui/RigTuneController`: four defaults. `ServerProfilesView serverProfiles()` returns `EMPTY`; `rememberServerProfile(String)`, `forgetServerProfile(String key)` and `forgetAllServerProfiles()` return `Component.empty()`.
- `client/RigTuneClient.registerAwareness` (`:108-111`): `real.serverProfileService().register();` after the tracker's line.
- `client/server/ServerLimitsTracker`: `kind(...)` and `address(...)` go from package-private to `public static` (a visibility change only).
- `client/profile/ProfileService`: two read-only public methods appended at the end of the class.
  - `@Nullable String activeProfileId()` returns the private `active()` (`:462-474`).
  - `@Nullable Text nameOf(String id)` is `displayName` (`:477-487`) but returns null instead of "?" when the id doesn't resolve.
  - Nothing else changes, so it doesn't collide with the PF-1/2/3/5 fixes in `switchTo`, `markActive`, `batteryNotice`, `batteryAction` and `managed`.
- `client/ui/ProfilesScreen`: row 3 becomes three `buttonWidth` buttons, the same grid as rows 1-2: [Import code…] [Servers…] [Done] (`:115-117`). Servers… opens `new ServerProfilesScreen(this, controller, selected)`.
- `client/awareness/AwarenessService.dismiss` (`:193-207`): the else-if also skips `ServerProfileService.KEY_PREFIX`, so the × is session-only. The key is per join and could never match again, so storing it would only churn `dismissed`'s 256-entry cap (`AwarenessStore.MAX_DISMISSED`). This is a different method from the AW-1 fix (`afterProbe`) and the AW-2 fix (`register`).

### 2.4 The state file

`config/rigtune/server-profiles.json`:
```json
{
  "formatVersion": 1,
  "salt": "<32 hex, random 16 bytes, created on the first remember>",
  "servers": {
    "<64 hex = HMAC-SHA256(salt, normalised address)>": {
      "profile": "template:max_fps",          // or "p-<uuid>"
      "kind": "REMOTE",                        // REMOTE | LAN_GUEST | REALM
      "setAt": "2026-09-27T10:15:30Z",
      "lastSeen": "2026-09-27T10:15:30Z"
    }
  }
}
```
- **JsonStateFile rules**, inherited through StateStore (`core/store/JsonStateFile.java:30-40`, `StateStore.java:9-14`):
  - Atomic writes.
  - Refused over `MAX_BYTES` = 16 KiB. 32 entries take about 6.5 KB.
  - A corrupt file becomes `server-profiles.json.bad` and the store starts empty.
  - A newer `formatVersion` is read-only: offers still work from it, while Remember and Forget show the read-only status.
  - A file over 4 × the cap is left alone.
  - Unknown fields at any depth survive.
- **Type checks** (players edit these files):
  - An entry needs `profile` in one of the two id shapes and `kind` in {REMOTE, LAN_GUEST, REALM}.
  - Anything else is ignored: never offered, not listed, not counted toward the 32, removed only by Forget all.
  - A bad or missing salt is replaced and the entries it keyed are dropped, as in `ServerLimitsStore.withDefaults` (`:135-146`).
- **Caps:** at most 32 valid entries. Re-remembering an existing server replaces its `profile` and `setAt` in place. A new 33rd gets `FULL`. There's no expiry.
- **Writes:**
  - Remember and Forget run on the render thread, on a click, and cost what ProfilesScreen's profiles.json writes cost.
  - The join `lastSeen` touch runs on `Probes.EXECUTOR`, only for a remembered server.
  - Nothing is written for servers that aren't remembered, so joining a new server writes nothing.
- **Privacy wording** (README "What the tools keep on your PC", next to `server-limits.json`, `README.md:183`):
  > `server-profiles.json`: the profile you asked RigTune to offer per server. Server addresses aren't stored in readable form: each entry is keyed by an HMAC-SHA256 of the address, with its own random key created once and kept in the same file, so someone who has the file could still check whether it holds a server they already know. It never goes into a report.
- The file isn't in the share report, Copy report or a share code. Nothing enumerates `config/rigtune/`: the only `Files.list` there is `HelperLauncher`'s `config/rigtune/helper/` (`HelperLauncher.java:66-67,99`).

### 2.5 UI

**The notice** (NoticePriority.SERVER_PROFILE; key `server-profile:<join epoch ms>`; dismissible; 2 actions):
- Message: "You set %s for this server. Switch to it?"
- Detail (tooltip, and on NoticeScreen): "You asked RigTune to offer it here (Profiles, Servers…). It never switches by itself, and History can undo the switch."
- Actions: **Switch** and **Don't offer here**, then × and "+N more" as today.
- At 640×480 GUI 2 (320 scaled px) the line uses the existing narrow layout, message plus "…" (below 400 px: `RigTuneScreen.java:67`), and NoticeScreen lists both actions.
- A11y: the message is a `RowFocus.standalone` Tab stop that narrates message and detail (`:452-456`); nothing new is needed.

**The toast** (once per server per game session, only when the decision is OFFER):
- Title: "Profile for this server".
- Body: "%s is set for this server. Press %s to switch." The key text is `RigTuneClient.openKey().getTranslatedKeyMessage()`, as the suggestions toast does (`RigTuneClient.java:204-206`).
- After a Switch or Forget from the notice, the same toast id shows the result, which is the switch's own message ("Switched to X. Undo it in History.", "…; N changes apply after a restart…", or a refusal).

**ProfilesScreen:** row 3 becomes [Import code…] [Servers…] [Done], each `buttonWidth` = (304 − 8) / 3 = 98 px at 640×480@2. Servers… has the tooltip "Have RigTune offer a profile when you join a server. It never switches by itself." `ProfilesGameTest.checkLayout` (`ProfilesGameTest.java:469-487`) already checks every button in `actions()` for overlap and bounds.

**ServerProfilesScreen** (column = min(width − 16, 372), centred like ProfilesScreen):

| y (scaled px, 640×480@2 = 320×240) | Element |
|---|---|
| 8 | Title "Profiles for servers" (bold) |
| 20 | Subtitle, which the status line replaces after an action (clipped, full text as a tooltip: the ProfilesScreen pattern, `:240-249`) |
| 32 | The "This server" line (`RowFocus.standalone`), which says one of: "This server: RigTune offers %s when you join." / "This server: no profile set." / "This server: %s, but not while you're on battery power with the Battery profile." / "Join a server to set a profile for it." / "Profiles are offered on servers, not in your own worlds." / "RigTune can't recognise this server, so it can't remember a profile for it." |
| 46-66 | [Offer %s here] [Stop offering here], each (column − 4) / 2. Offer is active in the SERVER state, with a profile chosen (selected in Profiles, else the active one), and the file writable. Disabled with no profile, tooltip "Select a profile in Profiles first." Stop is active when this server has an entry. |
| 70 | The privacy line "Server addresses aren't stored in readable form, so this list can't show them." (clipped plus tooltip, a standalone Tab stop) |
| 84 … height − 28 | `RowList` of remembered servers, ROW 22. A row reads "Server · Max FPS · last joined 2026-09-27", with "This server" right-aligned in `Palette.of(COLOR_ACTIVE)` on the current one. A deleted profile shows as "a deleted profile" and an unknown template id as "a profile this version doesn't know". Each row is a `RowFocus` whose action selects it and which says "Selected" (the ProfilesScreen pattern, `:302`). Empty list: "No servers set yet." |
| height − 24 | [Forget] (active when a row is selected) [Forget all…] (a `ConfirmScreen`) [Done], each (column − 8) / 3 |

- Fit: at 640×480@2 the list gets 128 px (5 rows). At 854×480@3 (284×160 scaled) it gets 48 px (2 rows, scrolling), about what ProfilesScreen's list gets at that size (52 px). No address, host or port is ever drawn or narrated.
- High contrast: every colour goes through `Palette.of`, reusing ProfilesScreen's `COLOR_LABEL`, `COLOR_ACTIVE` and `COLOR_STATUS` values.
- Tab order: the This-server line, Offer, Stop, the privacy line, the rows, Forget, Forget all, Done.

### 2.6 Wording: draft en_us.json keys

The block goes after `rigtune.profile.unnamed` and before `rigtune.battery.*` (`en_us.json:371-372`), in the `profile` area, so README's key-area list (`README.md:280`) needs only "`profile.server` for Profiles for servers". All English passes WordingTest: no "limited by", "bottleneck", "caused" or "because of".

```json
"rigtune.profile.servers": "Servers…",
"rigtune.profile.servers.tooltip": "Have RigTune offer a profile when you join a server. It never switches by itself.",
"rigtune.profile.server.offer": "You set %s for this server. Switch to it?",
"rigtune.profile.server.offer.detail": "You asked RigTune to offer it here (Profiles, Servers…). It never switches by itself, and History can undo the switch.",
"rigtune.profile.server.action.switch": "Switch",
"rigtune.profile.server.action.forget": "Don't offer here",
"rigtune.profile.server.toast.title": "Profile for this server",
"rigtune.profile.server.toast.body": "%s is set for this server. Press %s to switch.",
"rigtune.profile.server.title": "Profiles for servers",
"rigtune.profile.server.subtitle": "When you join a server listed here, RigTune offers its profile. It never switches by itself.",
"rigtune.profile.server.here": "This server: RigTune offers %s when you join.",
"rigtune.profile.server.here.none": "This server: no profile set.",
"rigtune.profile.server.here.battery": "This server: %s, but not while you're on battery power with the Battery profile.",
"rigtune.profile.server.not_connected": "Join a server to set a profile for it.",
"rigtune.profile.server.own_world": "Profiles are offered on servers, not in your own worlds.",
"rigtune.profile.server.unrecognised": "RigTune can't recognise this server, so it can't remember a profile for it.",
"rigtune.profile.server.remember": "Offer %s here",
"rigtune.profile.server.remember.tooltip": "Offers this profile each time you join this server. Nothing changes now.",
"rigtune.profile.server.remember.none": "Select a profile in Profiles first.",
"rigtune.profile.server.stop": "Stop offering here",
"rigtune.profile.server.privacy": "Server addresses aren't stored in readable form, so this list can't show them.",
"rigtune.profile.server.row": "%s · %s · last joined %s",
"rigtune.profile.server.row.undated": "%s · %s",
"rigtune.profile.server.row.current": "This server",
"rigtune.profile.server.kind.remote": "Server",
"rigtune.profile.server.kind.lan": "LAN game",
"rigtune.profile.server.kind.realm": "Realm",
"rigtune.profile.server.missing": "a deleted profile",
"rigtune.profile.server.unknown": "a profile this version doesn't know",
"rigtune.profile.server.empty": "No servers set yet.",
"rigtune.profile.server.forget": "Forget",
"rigtune.profile.server.forget_all": "Forget all…",
"rigtune.profile.server.forget_all.title": "Forget every server's profile?",
"rigtune.profile.server.forget_all.message": "RigTune stops offering profiles when you join these servers. Your profiles and settings stay as they are.",
"rigtune.profile.server.status.remembered": "RigTune will offer %s when you join this server.",
"rigtune.profile.server.status.forgot": "RigTune won't offer a profile on this server any more.",
"rigtune.profile.server.status.forgot_all": "RigTune won't offer profiles on servers until you set one again.",
"rigtune.profile.server.status.full": "You've set profiles for %s servers. Forget one first.",
"rigtune.profile.server.status.read_only": "server-profiles.json was written by a newer RigTune, so this version doesn't change it.",
"rigtune.profile.server.status.failed": "RigTune couldn't save that (the log says why)."
```
- Every `Text.of` in core or client writes out its key and English, as LangCheckTest (e) requires.
- Profile names are shown through `Texts.component` / `SafeLiteral`, so they are literals, never format strings (the ws-p rule).
- The kind is a translatable argument of the row template.

### 2.7 Timing and priority relative to the other offers

- **Order:** `BATTERY_OFFER` > `SERVER_PROFILE` > `SERVER_LIMIT` > `BENCHMARK_REGRESSION` > (C18's `STARTUP_REGRESSION`) > `HARDWARE_CHANGED` > `WHATS_NEW` > `BENCHMARK_STALE`. Battery stays first: a power edge is time-bound, it already leads, and NoticeBoardTest pins it there.
- **Server offer vs server limit:** both appear at join. The offer shows first and the limit notice sits behind "+1 more". Once the offer is taken, forgotten or dismissed, the limit notice moves up. If the chosen profile's view distance is above the server's limit, the existing limit notice already says "(you set M)" and "You set M; the server sends N, so N is what you see." (`ServerLimitNoticeSource.java:49-54`).
- **Battery and server offers both apply:**
  - On battery with Battery active: the server offer is held (ON_BATTERY), with no toast and no notice. The screen's This-server line explains why. Rationale: the player just chose Battery for the power state, and a server preference usually set on AC would undo it. If the remembered profile *is* Battery, the case is ALREADY_ACTIVE.
  - A pending unplug offer (Battery not active yet): both show, battery first.
  - Plugged back in: the back-offer and the server offer may both show. Whichever the player takes, the other retires on the next screen init if its target is now active. Both sources retire on "target == active": `ProfileService.java:285` and the new `current()`.
  - Nothing new is needed in `ProfileService`'s battery code.
- **Joins:** decided once per JOIN. A repeated JOIN with the same identity and no DISCONNECT counts as the same connection. That guards proxy backend switches and reconfiguration, which may fire JOIN again (UNVERIFIED; Fabric fires it from `handleLogin`). There's at most one toast per server key per game session. The notice is re-evaluated every time the RigTune screen opens.

### 2.8 Other design decisions (the brief's list)

- **Mapped profile deleted:**
  - No offer and no toast (MISSING_PROFILE), and a pending offer retires on the next `current()`.
  - Deleting through Profiles runs `forgetProfile(id)`, which removes its servers.
  - A deletion that bypasses 0.5 (0.4.0 after a downgrade, a hand edit) leaves a dangling entry, listed as "a deleted profile" with Forget.
  - A template id this version doesn't know (a later RigTune's) is never offered, but it is kept in the file (forward compatibility) and listed as "a profile this version doesn't know".
- **Mapped profile renamed:** ids don't change on rename (`ProfileStore.rename`, `:188-198`; ids are `p-<uuid>`, `:138-140`), so the offer shows the new name. Re-saving "My settings" keeps its id (`ProfileService.java:157-160`).
- **Clamps:**
  - Exactly those of a switch from Profiles. Templates are computed with every clamp; saved and imported profiles get the rules' clamps for this PC; My settings is unclamped (`ProfileService.resolve`, `:396-417`).
  - The result toast adds "N values were limited for this PC" (`:357-359`).
- **Server limits:**
  - Never applied to a profile's values. Templates never see server limits and `ServerCap` runs on the main list only (DESIGN "Server-aware advice"). RigTune never proposes a decrease because of a server.
  - The game keeps the profile's view distance and shows what the server sends. `Options.getEffectiveRenderDistance()` and the `serverRenderDistance` field exist on 26.2 and 26.3; their bodies weren't decompiled, and the claim shown to players stays the existing notice text.
  - The benchmark's `maxRenderDistance` clamp is unchanged.
- **Switching back when leaving:** no back-offer, for four reasons.
  1. Leaving usually means quitting, or joining another server that has its own offer.
  2. A toast at disconnect lands on the title screen or server list, which would be noise.
  3. The way back already exists: the switch is one History entry (Undo this), and My settings is always there.
  4. A "previous profile" record is exactly what went wrong in PF-1.

  A "back to your usual profile on your next own world" offer is a P2 option.
- **Staged keys:** Sodium, Iris and DH values in a profile are config patches staged for the restart, as for any switch. The result toast says "N changes apply after a restart". For a per-server switch, only the vanilla keys take effect in this session. The README should say so under the feature.
- **Refusal while downloading or benchmarking:**
  - No offer is made while a benchmark runs (BENCHMARK). An in-place benchmark on a server would otherwise leave a toast behind.
  - Taking the offer goes through `ProfileService.refusal()` (`:377-388`): benchmark, downloading ("busy") or not ready. The refusal becomes the toast, and the offer stays until the profile is active.
  - Remember and Forget change no settings and aren't refused.
- **Launcher-managed instances (the new P0):** the offer's switch is `SetSetting`-only (`ProfileSwitch.java:48`; "no jar is enabled or disabled", DESIGN "Performance Profiles"), so it stays one-click in both modes. C16 adds no mod-file operation and no new mutation path.

### 2.9 Threading, footprint, error handling

- **Render thread:** the JOIN and DISCONNECT handlers (field writes plus one executor submit), `notice()` (a volatile read when idle), and ServerProfilesScreen's init (reads a ≤ 16 KiB file and does one HMAC: the same cost class as ProfilesScreen reading profiles.json, ≤ 1 MiB).
  - With an offer pending, `notice()` reads profiles.json and the journal state through `activeProfileId()` and `nameOf()`, as `batteryNotice()` already does on every init (`ProfileService.java:279-285`).
  - No tick hook and no frame hook. `RigTuneClient.onTick` doesn't change, so the tickHook budgets aren't touched.
- **`Probes.EXECUTOR`:** the join lookup (HMAC, read, the `lastSeen` write) and the decision. `active()` already runs off the render thread in `powerChanged` (`:255`).
- **Idle memory:** the service holds a few references, the store is created lazily on first use (the `ServerLimitsTracker.store()` pattern, `:137-142`), and the toasted-key set is empty until a toast. `rigtuneClassBytesIdle` is 69-70 KB against a 109,296-byte budget (ws-p "Footprint"), so the headroom is large. Init adds only two event registrations: no HMAC or Gson class loading in `onInitializeClient`.
- **Errors:**
  - A throwing notice source is skipped by NoticeCenter (`NoticeCenter.java:77-84`).
  - The async lookup ends in `.exceptionally(log)`, like `ServerLimitsTracker.remember` (`:118-121`).
  - The store never throws: StateStore returns false, and a failed write becomes the status line "RigTune couldn't save that".
  - A connection-id guard stops a late lookup from offering after DISCONNECT.
  - An unparsable or blank `ServerData.ip` gives the UNRECOGNISED state.

---

## 3. Compatibility

- **0.1.x-0.3.x:** they never read `config/rigtune/server-profiles.json`, and nothing lists that directory.
- **0.4.0 reading a 0.5 config** (the downgrade must keep working):
  - `server-profiles.json` stays byte-identical, since 0.4.0 has no code that knows it.
  - C16 writes no new field to `profiles.json`, `server-limits.json` or `awareness.json`: server-limits.json is untouched, and the × is session-only.
  - If the session-only dismissal edit is cut, the per-join keys go into awareness.json's `dismissed` array. 0.4.0 keeps them (strings, capped at 256) and never matches them: harmless.
  - Profiles deleted under 0.4.0 leave dangling mappings, which 0.5 fails closed on (§2.8).
  - The NoticePriority reorder is in-memory only. Persisted notice data is keys, never ordinals (every use found is in code and tests).
- **Back on 0.5:** the mappings offer as before. The salt is the file's own, so it survives anything that happens to server-limits.json.
- **A future formatVersion 2 file:** read-only in 0.5, and Remember/Forget show the read-only status.
- **rules-v1.json:** untouched (no rules content).
- **Harness:** the v0.5 compat harness (its owner; for 0.4 it was `tools/e2e/written.py` `NEW_FILES`/`KEPT` plus the `v040-written` sets) should get a `server-profiles.json` fixture with one entry of each kind. The downgrade run must leave it byte-identical and keep profiles.json's switch labels. C16 supplies the fixture and a writer test in the `V040WrittenWsWTest` style.

## 4. 26.2 vs 26.3

There's no `//? if` block. Every API used is already compiled without one in RigTune (ServerLimitsTracker, ProfileService, ProfilesScreen) or was checked in the class files of both versions:

| API | 26.2 | 26.3 | How checked |
|---|---|---|---|
| `ServerData.name`, `.ip` (public fields), `isLan()`, `isRealm()`, `type()`, `Type.{LAN,REALM,OTHER}` | same | same | class-file reader on Loom's minecraft-client.jar |
| `Minecraft.hasSingleplayerServer()`, `getCurrentServer()`, `getConnection()` | same | same | same |
| `Options.getEffectiveRenderDistance()`, `serverRenderDistance` | same | same | same (bodies not decompiled) |
| `RealmsServer.toServerData(String)` builds `ServerData(…, REALM)` from `RealmsServer.name` | same | same | member refs |
| `ClientPlayConnectionEvents.JOIN`/`DISCONNECT`; JOIN at RETURN of `handleLogin` | networking 6.3.4 | networking 6.3.8 | mixin class strings and refs |
| `TestDedicatedServerContext.connect()` re-callable; `ServerData(…, "localhost:"+port, OTHER)` | gametest 6.0.2 | gametest 6.0.7 | impl fields and refs |
| `SystemToast.addOrUpdate`, `SystemToastId(long)`, `ToastManager.getToast`, `ConfirmScreen`, `Button.builder`, `GuiGraphicsExtractor`, `RowList`/`RowFocus` | compiled today without `//?` | same | existing code |
| Left mouse button | `InputConstants.MOUSE_BUTTON_LEFT` (0 on 26.2, 1 on 26.3), as ProfilesScreen does (`:326`) | | DESIGN "Accessibility" |

## 5. Test plan

**Unit (src/test):**
- `ServerProfileStoreTest` (mirrors `ServerLimitsStoreTest`):
  - The salt is created on the first remember, and its key is 64 hex.
  - The same address gives the same key; `Play.Example.com` and `play.example.com:25565` are one server; `lan:`/`realm:` keys.
  - The file bytes never contain the host, port or `localhost`.
  - A bad salt is replaced and its entries dropped.
  - Re-remember replaces in place; the 33rd gives FULL and nothing is written.
  - forget, forgetKey, forgetProfile, forgetAll.
  - `joined` touches `lastSeen` only when the server is present, and writes nothing otherwise (file mtime and bytes unchanged).
  - A corrupt file gives `.bad` and an empty store. A newer formatVersion: reads work, writes give READ_ONLY. Over 4× the cap: left alone.
  - Type checks: bad id shapes, SINGLEPLAYER, non-objects.
  - Unknown fields at the root and per entry survive a write.
- `ServerProfilePromptTest`: the full decision table (7 reasons, the order of checks).
- `ServerProfileNoticeTest`: the static builder's key prefix, priority, message and detail English, action ids `[switch, forget]`, and dismissible.
- `NoticeBoardTest`: update the order assertion (`:27-28`), plus one case where SERVER_PROFILE sorts after BATTERY_OFFER and before SERVER_LIMIT.
- LangCheckTest and WordingTest pick up the new keys automatically.
- The v0.5 fixture writer test (§3).

**Client game test `ServerProfilesGameTest`:**
- All three CI legs: 26.2 GL, 26.3 GL, 26.3 Vulkan under Xvfb. The network is off (the `setNetwork(false)` pattern, `ServerLimitsGameTest.java:276-284`), and only local fixtures are used.
- Registered in `src/gametest/resources/fabric.mod.json` before `FootprintGameTest`, which must run after the feature classes.
- It puts back options, profiles.json and server-profiles.json in `finally`.
- Steps:
  1. **Singleplayer** (`worldBuilder().create()`): no offer. ServerProfilesScreen state is OWN_WORLD, Offer is inactive, and no toast.
  2. **Dedicated server** on `freePort()` (never 25565; eula.txt as in `ServerLimitsGameTest.java:98-109`), **connection 1**:
     - No offer.
     - Switch to Quality via `controller.switchProfile("template:quality")`, then to Max FPS the same way.
     - Open Profiles, then Servers… (with "template:max_fps" selected), then "Offer Max FPS here".
     - server-profiles.json has 1 entry, kind REMOTE, profile `template:max_fps`, with no "localhost", "127.0.0.1" or port string.
     - Switch back to Quality, then close the connection.
     - The offer is null after DISCONNECT.
  3. **Connection 2** (`server.connect()` again):
     - Wait for `serverProfileService().notice() != null`. Its message is "You set Max FPS for this server. Switch to it?".
     - `real.notices()`: [SERVER_PROFILE, SERVER_LIMIT, …]. RigTuneScreen's `shownNotice()` is the offer, and "+1 more" is shown.
     - A SystemToast with `TOAST_ID` is present (`toastManager().getToast(SystemToast.class, id)`, the call `RigTuneClient.java:181` uses).
     - Screenshots of RigTuneScreen at 1280×720@2, 640×480@2 and 854×480@2, and NoticeScreen at 640×480@2 (the narrow "…" layout).
     - `ProfileService.overrideBenchmarkCheck(() -> true)`, then act switch. The result is "Profiles can't switch while a benchmark is running.", active is still Quality, and the offer is still there. Put the override back.
     - Act switch: exactly one new journal entry of kind `apply`, labelled "Profile: Max FPS" in History; the vanilla values are Max FPS's (for example `enableVsync=false`); the notice is gone after the rebuild.
     - `undoPlanFor(entryId)` then undo: the values are Quality's again, and the offer does **not** come back in this connection.
     - Disconnect.
  4. **Connection 3:**
     - The offer appears (Quality active), and no second toast (same server, same session).
     - × dismiss: it's hidden, and reopening RigTune shows it still hidden; awareness.json's `dismissed` doesn't contain the key.
     - ServerProfilesScreen: state SERVER; the "This server" row carries the marker; no widget message or narration contains "localhost" or the port.
     - Walk layout checks at the 3 sizes plus 854×480@3: every widget inside the screen, no overlaps.
     - Save a profile "Evening" (`saveCurrentProfile`) and set it for this server, then `deleteProfile(id)`: the entry is gone from the file.
     - Set Max FPS again, then Stop offering here: 0 entries.
     - Disconnect.
  5. **After disconnect:** state NOT_CONNECTED and no offer; screenshot.
  6. **Battery hold (`ON_BATTERY`)** is covered by the unit table, since CI has no battery. Optionally call `ProfileService.powerChanged(true)` and switch to Battery during connection 3 to check the This-server line text, as ProfilesGameTest.batteryOffer does.
- **A11yGameTest:** a `serverProfiles` step with the A11y stub controller's canned `ServerProfilesView` (3 rows). `walk(context, "server-profiles", …)` reaches every row, each row narrates its text, and Enter selects a row. There's also a screenshot under high contrast.
- **FootprintGameTest:** unchanged, and must stay green. The new source is evaluated in its notice evaluation.

**One real run on the dev PC** (Windows; the user's own server holds 25565):
- `gradlew :26.3:runProductionClientGameTest` locally. ServerProfilesGameTest binds a free port.
- Then a manual pass with `runClient` against a vanilla 26.3 dedicated server set to `server-port=25566`:
  - Remember Max FPS, rejoin, and check the toast is visible when the world appears (the 8 s id, UNVERIFIED until this run).
  - Take the offer, Undo in History, and read server-profiles.json (no plaintext).
  - Optional: a LAN guest from a second instance on the same PC (Open to LAN in instance A is SINGLEPLAYER and gets no offer; instance B joining from the LAN list gets a `lan:` entry).
- Realms stays UNVERIFIED, as AC8.5.

## 6. Draft acceptance criteria

- **AC16.1:** On the first "Offer X here", `server-profiles.json` holds `formatVersion` 1, a 32-hex salt and one entry keyed by 64 hex, with `profile`, `kind`, `setAt` and `lastSeen`. The file contains none of the server's host, port, "localhost" or "127.0.0.1" (unit test plus game test).
- **AC16.2:** JsonStateFile rules: a corrupt file gives `.bad` and an empty store; a newer formatVersion is read-only (offers work; Remember and Forget show the read-only status); over 4× the cap it's left alone; unknown fields survive; a bad salt is replaced and its entries dropped (unit).
- **AC16.3:** At most 32 servers. A new 33rd gives the "Forget one first" status with the file unchanged. Re-remembering an existing server replaces it in place. The file is ≤ 16 KiB (unit).
- **AC16.4:** `ServerProfilePrompt.decide` returns each of the 7 reasons in the documented order (unit).
- **AC16.5:** Joining a remembered server whose profile isn't active gives exactly one `SERVER_PROFILE` notice (key `server-profile:<ms>`, actions [switch, forget], dismissible). It sorts before SERVER_LIMIT, and it's RigTuneScreen's shown notice when no battery offer is pending (game test, 3 legs).
- **AC16.6:** At most one toast per server per game session, and none in singleplayer, the Open-to-LAN host, the benchmark world, or when the profile is already active (game test plus unit).
- **AC16.7:** Switch from the offer gives one `apply` journal entry labelled "Profile: X". Undo this restores the values and voids the active marker. The offer doesn't return in the same connection (game test).
- **AC16.8:** With a benchmark running, the switch is refused with the Profiles message, nothing changes and the offer stays. While downloading, it's refused with "busy". No offer is made while a benchmark runs (unit plus game test).
- **AC16.9:** A deleted mapped profile gives no offer and no toast, and deleting through Profiles removes its entries. A rename changes the offer's name (game test plus unit).
- **AC16.10:** "Don't offer here" and "Stop offering here" remove the entry. × hides the offer for this connection and writes nothing to awareness.json (game test).
- **AC16.11:** ServerProfilesScreen shows the right state line for each State. Rows show kind, profile, last-joined date and "This server". No address, host or port is drawn or narrated. It fits at 640×480@2, 854×480@2, 1280×720@2 and 854×480@3 with no overlap. Tab reaches every row and button; rows narrate their text and "Selected"; colours go through Palette (game test plus A11yGameTest).
- **AC16.12:** Every new string is in en_us.json, and LangCheckTest and WordingTest pass.
- **AC16.13:** No tick or frame hook is added. FootprintGameTest's budgets pass on all 3 legs. `notice()` does no file I/O when no offer is pending (code review plus footprint).
- **AC16.14:** 0.4.0 run on a 0.5 config dir with a server-profiles.json fixture leaves it byte-identical, and profiles.json and server-limits.json keep their 0.4.0 shape. Back on 0.5, the mapping offers again (compat harness).
- **AC16.15:** ServerProfilesGameTest passes on 26.2 GL, 26.3 GL and 26.3 Vulkan with the network off.
- **AC16.16:** A real-PC run on a port other than 25565 records the toast being visible when the world appears, the switch, and the Undo (screenshot plus log in docs/v0.5/verification).
- **AC16.17:** On battery with Battery active there's no offer, and the This-server line explains why (unit; manual on a laptop is UNVERIFIED because this PC has no battery).
- **AC16.18:** In an instance whose mods the launcher manages, the offer's switch changes settings only and produces no mod-file operation (unit on the recommendations built; coordinate with the P0 owner's detection fixture).

## 7. File ownership

New (owned by this feature):
- `src/main/java/io/github/chaotix345/rigtune/core/server/ServerProfileStore.java`
- `src/main/java/io/github/chaotix345/rigtune/core/profile/ServerProfilePrompt.java`
- `src/main/java/io/github/chaotix345/rigtune/core/profile/ServerProfilesView.java`
- `src/client/java/io/github/chaotix345/rigtune/client/server/ServerProfileService.java`
- `src/client/java/io/github/chaotix345/rigtune/client/notice/ServerProfileNoticeSource.java`
- `src/client/java/io/github/chaotix345/rigtune/client/ui/ServerProfilesScreen.java`
- `src/test/java/io/github/chaotix345/rigtune/core/server/ServerProfileStoreTest.java`
- `src/test/java/io/github/chaotix345/rigtune/core/profile/ServerProfilePromptTest.java`
- `src/test/java/io/github/chaotix345/rigtune/client/server/ServerProfileNoticeTest.java`
- `src/gametest/java/io/github/chaotix345/rigtune/gametest/ServerProfilesGameTest.java`
- The v0.5 fixture `server-profiles.json` and its writer test (paths set by the v0.5 compat harness owner)

Changed (**HOTSPOT** = shared with other v0.5 work):
- **HOTSPOT** `core/notice/NoticePriority.java`: one constant. C18 (feature-launch-alerts.md §2.4) inserts `STARTUP_REGRESSION` after BENCHMARK_REGRESSION: a trivial merge.
- **HOTSPOT** `src/test/.../core/notice/NoticeBoardTest.java`: the order list (C18 too).
- **HOTSPOT** `client/RealController.java`: a field, its construction, the NoticeCenter list, an accessor, 4 delegations, and +1 line in `deleteProfile`.
- **HOTSPOT** `client/ui/RigTuneController.java`: 4 default methods.
- **HOTSPOT** `client/ui/ProfilesScreen.java`: row 3 layout plus the button (the PF-2 toggle goes to RigTuneSettingsScreen per audit-v040-verification.md, so there's no conflict).
- **HOTSPOT** `src/main/resources/assets/rigtune/lang/en_us.json`: the block after `rigtune.profile.unnamed`. Serialise with PF-2, BH-1, L3, L7, C18 and the others.
- **HOTSPOT** `src/gametest/resources/fabric.mod.json`: one entrypoint before FootprintGameTest.
- `client/RigTuneClient.java`: +1 line in `registerAwareness`.
- `client/profile/ProfileService.java`: 2 appended read-only public methods. The file is shared with PF-1/2/3/5, whose owner should merge first or right after; there's no overlap in methods.
- `client/server/ServerLimitsTracker.java`: 2 visibility changes.
- `client/awareness/AwarenessService.java`: 1 condition in `dismiss` (the AW-1/AW-2 owner changes `afterProbe`/`register`).
- `src/gametest/.../A11yGameTest.java`: the canned view and the walk step (shared with any a11y work).
- `tools/e2e/written.py` (or its v0.5 successor): add the file to the new-files list (compat-harness owner).
- Docs: `docs/DESIGN.md` (a new "Per-server profile offers (0.5)" section), `README.md` (a Profiles paragraph, the privacy list at `:180-185`, the key areas at `:280`), `CHANGELOG.md`.
- **Not touched:** RigTuneScreen, ToolsScreen, NoticeCenter, NoticeBoard, NoticeScreen, ServerLimitsStore, ProfileStore, ProfileSwitch, the rules files, `.github/workflows/build.yml` (the game-test legs come from `tools/gametest_matrix.py`; the classes from fabric.mod.json), `build.gradle`.

## 8. Effort and risk

**3.0 agent-days:**
- Store and test: 0.5.
- Prompt, notice builder and tests: 0.25.
- Service, source and wiring (RealController, RigTuneController, RigTuneClient, tracker, ProfileService accessors, AwarenessService): 0.5.
- ServerProfilesScreen and the ProfilesScreen button: 0.5.
- lang, docs and compat fixture: 0.25.
- ServerProfilesGameTest and the A11y step, including one CI iteration on 3 legs: 0.75.
- The real-PC run: 0.25.

**Riskiest part:** real-world JOIN behaviour, which can't be proven in CI:
- whether the toast is seen when it's posted during terrain loading;
- whether proxies (Velocity, Bungee) or 1.20.2+ reconfiguration fire JOIN or DISCONNECT again, re-offering or wiping the offer.

The design limits the damage to at most one toast per server per session, with the notice re-checked on every screen open. The real run checks the first point; the second stays UNVERIFIED (no proxy here).

Second: hotspot merges (NoticePriority with C18, ProfileService with PF-*, en_us.json).

Third: CI time. The new test starts one more dedicated server per leg (not measured here). Merging its steps into ServerLimitsGameTest's server session would save that, at the cost of owning a v0.4 test file.

## 9. What to cut first if time runs short

In order:
1. The per-row list with Forget: keep the This-server line, "Stop offering here", and "Forget all…" with a count ("N servers set"). This saves the RowList and A11y walk work.
2. The ON_BATTERY hold: rely on BATTERY_OFFER coming first.
3. The session-only × edit in AwarenessService: store the key like battery offers do.
4. `lastSeen` and the "last joined" dates: no write on join at all.

Never cut:
- the SERVER_PROFILE slot above SERVER_LIMIT, and the toast (without them the feature is invisible);
- fail-closed on a deleted or unknown profile;
- the switch going through `ProfileService.switchProfile` (Undo);
- the no-plaintext checks;
- the two-connection game test;
- the downgrade fixture.
