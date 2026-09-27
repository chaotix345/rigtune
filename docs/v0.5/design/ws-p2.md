# WS-P2: C16 per-server profile offers (v0.5 P1, SPEC 7)

Branch `feat/v05-server-profiles` (worktree `rigtune-serverprof`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K
merged). Scope: docs/v0.5/SPEC.md 7 (AC7.1-AC7.18), PLAN "WS-P2", research docs/research/v0.5/feature-server-profiles.md
("sp"), the contracts as landed in docs/v0.5/design/ws-k.md (the `ServerProfileStore` shell, the `ServerProfilesView`
skeleton, the `ServerProfileService`/`ServerProfileNoticeSource` skeletons, `NoticePriority.SERVER_PROFILE`, the
`server-profile:` session-only dismissal, the JOIN/DISCONNECT registration, `walkServerProfiles`, `CannedViews`, the
`serverProfiles` family method, the `rigtune.profile.server*` anchor `rigtune.profile.unnamed`).

Two phases, as the coordinator scheduled them:
- **Phase A (Wave A, now): the pure core, new or WS-P2-owned core files only.** No edit to ProfileService,
  ProfilesScreen, ServerLimitsTracker, RealController or any other existing file (WS-P is fixing profiles in parallel);
  en_us.json only inside the `rigtune.profile.server.*` block, with the keys the core code uses. Push when done, CI
  green, report, stop.
- **Phase B (Wave B, after WS-P merges): the client** (service, notice source, screen, ProfilesScreen row 3, the two
  read-only ProfileService methods, the tracker's two visibility changes, RealController's `deleteProfile` line, the
  game test, the A11y walk).

## TDD task plan

Each task: a red test first, then the code, then one commit. Local runs: `./gradlew :26.2:test --tests '<classes>'`
inside a build slot (no version-specific code: X9, no `//? if` block); the full build is CI's.

### Phase A: pure core

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| A1 | `ServerProfileStore`: its own salt (16 random bytes, 32 hex, made on the first remember), entries keyed by `ServerLimitsStore.key(salt, address)` (package-private, no change to ServerLimitsStore); typed accessors `get`, `joined` (touches `lastSeen` only for a remembered server, else writes nothing), `remember` → `Result {OK, FULL, READ_ONLY (a newer file), FAILED (refused, unreadable, a failed write)}` (refuses SINGLEPLAYER and a malformed profile id; a 33rd new server is FULL and writes nothing; re-remember replaces `profile`/`setAt`/`lastSeen` in place, keeping the entry's unknown fields), `forget(address)`, `forgetKey`, `forgetProfile` (count), `forgetAll`, `entries()` (valid only, newest `lastSeen` first), `keyOf` (null without a salt, writes nothing), `writable`; `record Entry(key, profile, kind, setAt, lastSeen)`. Type checks: `profile` is `p-[A-Za-z0-9-]{1,64}` or `template:[a-z_]{1,32}` (ProfileStore's shapes, redeclared), `kind` in {REMOTE, LAN_GUEST, REALM}; anything else is ignored (not offered, not listed, not counted), removed only by Forget all. A bad or missing salt: no entries read; the next remember replaces it and drops what it keyed. | `core/server/ServerProfileStore` | `core/server/ServerProfileStoreTest`: `theFirstRememberCreatesTheSaltAndOneEntry` (AC7.1 shape), `timesAreKeptToTheSecond`, `theFileHoldsNoPlaintextAndItsKeysDifferFromServerLimits`, `oneServerHowEverItsAddressIsWritten` (`Play.Example.com` = `play.example.com:25565`; port differs; `lan:`/`realm:` keys), `joinedTouchesLastSeenOnlyForARememberedServer` (bytes and mtime unchanged otherwise), `aNewThirtyThirdServerIsFullAndTheFileUnchanged` (also: the full file with the longest ids is ≤ 16 KiB), `reRememberingReplacesInPlace`, `singleplayerAndMalformedIdsAreRefused`, `forgetForgetKeyForgetProfileForgetAll`, `forgetAllRemovesEntriesThisVersionCantRead`, `entriesAreTypeCheckedNewestFirstAndOnlyValidOnesCount`, `aBadOrMissingSaltIsReplacedAndItsEntriesDropped`, `aCorruptFileIsMovedToBadAndTheStoreStartsEmpty`, `aNewerFileStillOffersButRefusesWrites` (READ_ONLY), `aFileOverFourTimesTheCapIsLeftAlone` (FAILED), `unknownFieldsSurviveAtTheRootAndPerEntry`, `keyOfWritesNothingAndIsNullWithoutASalt`; after the review: `aServerForgottenBetweenTheLookupAndTheWriteIsNotAnswered`, `twoRemembersRacingForTheLastPlaceGiveOneOkAndOneFull`, `aValidSaltWithServersThatIsntAnObject`, `aRememberThatWouldPassTheCapFailsAndLeavesTheFile`, `forgetKeyWithoutAKeyAndOnAnEntryThisVersionCantRead`; `V05StoreShellsTest` stays green | AC7.1 (unit), AC7.2, AC7.3 |
| A2 | `ServerProfilePrompt.decide(kind, mapped, mappedResolves, active, benchmarkRunning, onBattery)` → `Reason {OFFER, NO_SERVER, NO_MAPPING, MISSING_PROFILE, ALREADY_ACTIVE, BENCHMARK, ON_BATTERY}`, checked in SPEC 7's order; ON_BATTERY = on battery with `BatteryPrompt.BATTERY` active | new `core/profile/ServerProfilePrompt` | `core/profile/ServerProfilePromptTest`: `eachReasonAlone`, `theChecksRunInTheDocumentedOrder` (all 64 combinations of the six conditions: the earliest reason wins), `aRememberedBatteryProfileOnBatteryIsAlreadyActive`, `onBatteryWithoutBatteryActiveStillOffers` | AC7.4, AC7.17 (unit), AC7.8/AC7.9 (their "no offer" unit halves) |
| A3 | The notice and toast texts in core: `ServerProfilePrompt.notice(long joinedAtMillis, Text name)` (key `server-profile:<ms>`, `SERVER_PROFILE`, "You set %s for this server. Switch to it?", the detail, actions `[switch, forget]`, dismissible), `toastTitle()`, `toastBody(Text name, Text key)`, constants `KEY_PREFIX`, `ACTION_SWITCH`, `ACTION_FORGET`; the keys in en_us.json (block after `rigtune.profile.unnamed`) | `core/profile/ServerProfilePrompt`, en_us.json | `core/profile/ServerProfileNoticeTest`: `theNoticeKeyPriorityWordingActionsAndDismissal`, `theNameIsALiteralNeverAFormat` (a name with `%s`), `theKeyIsHiddenForTheSessionOnly` (`AwarenessService.SESSION_ONLY_PREFIXES` holds `KEY_PREFIX`; a real dismissal adds nothing to awareness.json), `itSortsAfterTheBatteryOfferAndBeforeTheServerLimit` (`NoticeBoard.select` with the three), `theToastWording`; LangCheckTest, WordingTest, PseudoLocaleTest | AC7.5, AC7.6 (their unit parts), AC7.12 (already WS-K's) |
| A4 | `ServerProfilesView`: the builder `of(state, kind, currentKey, entries, names, active, onBattery, writable, zone)` (rows from the store's entries, names resolved, `lastJoined` the local day, the current row marked; `currentProfile`/`heldOnBattery` from the current row and `decide`), `here()` (the six This-server lines, plus `here.missing` for a deleted or unknown set profile: Deviations), `Row.text()` ("Server · Max FPS · last joined 2026-09-27"; "a deleted profile" for an unresolved `p-` id, "a profile this version doesn't know" for an unknown template), `remembered(Result, name)` (remembered, full, read only, failed) and `forgot(Result)`/`forgotAll(Result)`; keys in en_us.json | `core/profile/ServerProfilesView` | `core/profile/ServerProfilesViewTest`: `theThisServerLines`, `aDeletedOrUnknownCurrentProfileIsNotOffered`, `rowsInTheStoresOrderWithKindProfileAndDay`, `theDayIsTheLocalOne`, `deletedAndUnknownProfilesReadAsSuch`, `aSavedNameIsALiteral`, `theCurrentServerIsMarkedOnlyWhileConnectedToIt`, `heldOnBatteryOnlyWithBatteryActive`, `theViewKeepsWritableAndKind`, `noTextHoldsAnAddress`, `theStatusLines`; `V05StubsTest` (EMPTY) stays green | AC7.11 (unit part: lines and row text), AC7.17 (the line) |
| A5 | AC7.18 pinned in core: a switch's recommendations are `SetSetting` only, and `LauncherModAdvice.guard` keeps every one of them under RIGTUNE, LAUNCHER and PENDING (so WS-L1's guard can't drop a profile switch) | test only | `core/profile/ServerProfileSwitchTest.aSwitchIsSettingsOnlyUnderEveryLauncherPolicy` (a pin: green against WS-K's identity guard, red if WS-L1's guard ever drops a setting) | AC7.18 |
| A6 | Fixture set `ws-p2`: `server-profiles.json` with one entry per kind (REMOTE → `template:max_fps`, LAN_GUEST → `template:quality`, REALM → `template:recording`; a fixed salt seeded as V040WrittenWsWTest does, fixed past instants, one `joined`), written by the store; `expect.json` (0.4.0 never opens the file: `Unread`, `unchanged`; `ServerLimitsStore` on the composed `server-limits.json`: `noBad`); `RIGTUNE_REGENERATE_FIXTURES=1` rewrites, otherwise compare | `src/test/resources/v050-written/ws-p2/`, new `core/server/V050WrittenWsP2Test` | `V050WrittenWsP2Test.theWsP2SetIsWhatThisVersionWrites` (red: no set), re-read by the store: each address maps to its profile, no plaintext | AC7.14 (fixture half; closes when WS-E's compat040 interpreter runs the set) |

Phase A adds no client code, so it adds no render-thread, tick or startup work: footprint deltas 0 by construction
(checked on the Phase A CI run's footprint JSON anyway, below).

### Phase B: client (after WS-P merged, 48a67b22; the coordinator's GO 2026-09-28)

Starting point: `origin/feat/v0.5.0` merged in (a fast-forward to 48a67b22, since Phase A had already merged there as
de597c28). Inherited and kept: WS-P's frozen-file exception (`RigTuneController.profileCodeLeftOut` + its forwards and
ProfilesScreen.copySelected's 3 lines); PF-1 (a Battery switch with no profile in effect refreshes "My settings" first:
the offer's Switch is `ProfileService.switchProfile`, so it inherits that); every singleplayer world through
`GameTestWorlds.create/leave`; X12's scroll size is 1280×720@3 (854×480 caps at scale 2); `expect.json` keeps only
checks on files the set holds (WS-E's compat040 rule), so the `server-limits.json` check is dropped here too.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| B1 | The offer's state, pure: `core/profile/ServerProfileOffers` (the current connection and its identity; a repeated JOIN with the same kind and address and no DISCONNECT is the same connection; DISCONNECT ends it; a lookup's offer counts only while its connection is still current; one pending offer, retired by compare-and-set; at most one toast per server key per game session; forgetting a key retires its offer) | new `core/profile/ServerProfileOffers` | `core/profile/ServerProfileOffersTest`: `aRepeatedJoinIsTheSameConnection`, `aDisconnectEndsTheConnectionAndItsOffer`, `aLookupThatFinishesAfterADisconnectOffersNothing`, `oneToastPerServerPerSession`, `retireOnlyWhatWasSeen`, `forgettingAKeyRetiresItsOffer`, `theOwnWorldAndAnUnrecognisedServerAreConnectionsButNeverOffered` | AC7.6, AC7.13 (the guard), AC7.10 (retire) |
| B2 | The client service and its wiring: `ServerProfileService` (`onJoin`: kind and address from `ServerLimitsTracker`, then one `Probes.EXECUTOR` task: `joined` (lastSeen) + `decide`, then the toast on the render thread; `onDisconnect`; `notice()`: a volatile read while nothing is pending, else re-decided with fresh state (OFFER → the notice; BENCHMARK/ON_BATTERY → held; anything else → retired); `act`: Switch through `ProfileService.switchProfile` (refusals as the toast; retired once the profile is active), Don't offer here through `forgetKey`; `view()`; `remember`/`forget`/`forgetAll`/`forgetProfile`), `ServerProfileNoticeSource` delegating, ProfileService's two appended read-only methods (`activeProfileId`, `nameOf`), ServerLimitsTracker's `kind`/`address` made `public static`, RealController's one `deleteProfile` line | `client/server/ServerProfileService`, `client/notice/ServerProfileNoticeSource`, `client/profile/ProfileService` (appended), `client/server/ServerLimitsTracker`, `client/RealController` (1 line) | red: ServerProfilesGameTest's connections (the skeleton offers nothing); unit: B1's | AC7.5-AC7.10, AC7.13, AC7.18 (its gap: the offer's real switch builds settings only; Preview of a template and a saved profile has no download or disable) |
| B3 | `ServerProfilesScreen` (sp §2.5: title, status, the This-server line and the privacy line as `RowFocus.standalone` stops, [Offer %s here] [Stop offering here], a `RowList` of rows narrating their text and "This server"/"Selected", [Forget] [Forget all…] (ConfirmScreen) [Done]; Tab order as listed; `Palette.of` with ProfilesScreen's literals) and ProfilesScreen's row 3 [Import code…] [Servers…] [Done]; the client keys | `client/ui/ServerProfilesScreen`, `client/ui/ProfilesScreen`, en_us.json | red: ServerProfilesGameTest's screen steps and `walkServerProfiles`; LangCheckTest, PaletteTest, WordingTest, PseudoLocaleTest | AC7.11 |
| B4 | `ServerProfilesGameTest` (network off through GameTestNet; singleplayer through GameTestWorlds: no offer, OWN_WORLD; a dedicated server on a free port, connections 1-3 as sp §5 with "Don't offer here" through the service's action; after the disconnect NOT_CONNECTED; screenshots) and `A11yGameTest.walkServerProfiles` (a canned view through CannedViews and a ForwardingController, Tab order, "Selected", the X12 sizes + 1280×720@3 scrolling, a high-contrast shot) | `gametest/ServerProfilesGameTest`, `A11yGameTest.walkServerProfiles` | 3 CI legs; screenshots looked at | AC7.1 (game half), AC7.5-AC7.11, AC7.15 |

Local game runs: `:26.2:runProductionClientGameTest -PgametestClasses=ServerProfilesGameTest,A11yGameTest` only under
`C:/Dev/Worktrees/.gametest-lock`; CI runs every class on the three legs.

AC7.16 (a real JOIN on the dev PC against a vanilla 26.3 server on a port other than 25565) is rolling Phase 5 (the P5
agent, under the game-test lock, after this workstream merges). No code-deciding run in this workstream.

## Decisions (Phase A)
- **The notice and toast builders live in core** (`ServerProfilePrompt.notice/toastTitle/toastBody`), not as a static on
  the client service (sp §2.3): they need only core types, so `ServerProfileNoticeTest` is a plain unit test in
  `core/profile` and the client source just calls them.
- **`Result` for every write** (`remember`, `forget`, `forgetKey`, `forgetAll`), so the screen can tell read-only from a
  failed write without asking twice; forgetting what isn't there is `OK` and writes nothing. `forgetProfile` returns the
  number removed (0 on a failed write; Profiles' delete stays silent about servers).
- **Writes only when something changes**: `StateStore.update` always rewrites, so each write is decided on a read first
  and re-checked inside the update. When another thread's write lands in between (a server forgotten before the join's
  `lastSeen` write, the last free place taken before a remember), the update leaves the content unchanged; the bytes may
  still be rewritten by that concurrent update (reviewed and accepted: cancelling the write would log a WARN for a normal
  race).
- **READ_ONLY only for a newer file** (review M): when the store isn't writable, the file's load state decides: a newer
  `formatVersion` is READ_ONLY ("written by a newer RigTune"), anything else (an I/O error, over 4 × the cap) is FAILED
  ("couldn't save that (the log says why)"; JsonStateFile logs which).
- **The salt**: missing or malformed → nothing is keyed (no entries, `keyOf` null); the first `remember` makes a new one
  and drops the entries the old one keyed (ServerLimitsStore's rule). `forgetAll` keeps the salt.
- **Times are kept to the second** (`setAt`, `lastSeen`), as sp §2.4's example shows: a smaller file and no sub-second
  noise in a player-edited file.
- **A null entry value** (`"<key>": null`) is dropped by the next write (JsonStateFile's Gson leaves null members out);
  every other unreadable entry stays until Forget all.

## Phase A as landed

| task | commit | tests (26.2 and 26.3, local) |
|---|---|---|
| A1 ServerProfileStore | 33e8ab83; review fixes below | ServerProfileStoreTest 22 (17 + 5 after the review); V05StoreShellsTest, ServerLimitsStoreTest unchanged and green |
| A2 ServerProfilePrompt.decide | dc1331c3 | ServerProfilePromptTest 4 (the order test runs all 64 combinations) |
| A3 notice and toast texts | 47a0c6db | ServerProfileNoticeTest 5; LangCheckTest, WordingTest, PseudoLocaleTest, AwarenessDismissTest |
| A4 ServerProfilesView | f2cb97e9 | ServerProfilesViewTest 11; V05StubsTest |
| A5 AC7.18 pin | 6715b714 | ServerProfileSwitchTest 1 |
| A6 fixture set ws-p2 | e9e43815 | V050WrittenWsP2Test 1 |

Red first for each: A1-A4 and A6 failed before their code (A1-A4: the API didn't exist; A6: no committed set,
`NoSuchFileException`); A5 is a pin (see the table above). The targeted run (core server/profile/store/notice,
V05StubsTest, LangCheckTest, WordingTest, PseudoLocaleTest, PaletteTest, client awareness, ServerLimitNoticeSourceTest,
RigTuneControllerDefaultsTest, V05ServicesTest) passed on both nodes: 189 tests each, 0 failures.

**compat030 against the set** (PLAN "Fixtures"): the released 0.3.0 jar (sha256-checked by the tool) on the v040-written
instance with ws-p2's `server-profiles.json` added (compat030's `written.py` doesn't know the v0.5 sets yet, so the file
went into a copy of the ws-w folder): `RESULT PASS`, "0.3.0 reading them changed no file: 10 file(s) unchanged", and
`cmp` shows `server-profiles.json` byte-identical. compat040 isn't on the integration branch yet (WS-E): AC7.14 closes
when its interpreter runs `expect.json`. No `placeholder/ws-p2/` exists on the branch, so none was deleted.

### Review (Phase A)
A code-reviewer pass on a7613410..9cf12c19 went to the coordinator: 0 high, 1 medium, 4 low, decided as follows and
fixed in one commit. (M) READ_ONLY only for a newer file, FAILED for an unreadable one (Decisions; the over-4×-cap test
now expects FAILED). (L1) `joined` answers null when the server was forgotten between its lookup and its write (a
package-private, tests-only `beforeUpdate` hook stands in for the other thread). (L2) accepted: the race paths may
rewrite unchanged content (Decisions, AC7.3). (L3) the tests listed in A1's row "after the review"; AC7.18's gap is
Phase B's first B2 row. (L4) this file's task rows now name the tests as landed. Red first: with the old behaviour put
back, `aFileOverFourTimesTheCapIsLeftAlone` and `aServerForgottenBetweenTheLookupAndTheWriteIsNotAnswered` fail; the
other three new tests pin behaviour that was already right.

### Deviations (Phase A)
- `ServerProfileNoticeTest`, `ServerProfilePromptTest` and `ServerProfilesViewTest` are in `core/profile` (sp §7 put
  the notice test in `client/server`): the builders are core (Decisions).
- The This-server line has a seventh text, `rigtune.profile.server.here.missing` ("This server: set to %s, so RigTune
  offers nothing here."), for a server whose profile was deleted (outside 0.5) or is a template this version doesn't
  know: "RigTune offers a deleted profile when you join" would be untrue (MISSING_PROFILE fails closed; X3).
- Not in Phase A (client, Phase B): the keys only the screen and ProfilesScreen use (`rigtune.profile.servers*`,
  title, subtitle, remember, stop, privacy, forget buttons, the confirm screen, `row.current`, `empty`).

## Footprint deltas
Phase A adds no client code: nothing runs on the render thread, at startup or per tick (the core classes load only when
Phase B's client code calls them). The Phase A CI run 36318167902 (head 9cf12c19, all 8 jobs green; unit tests 1952 per
node, 2 skipped, 0 failed), footprint JSON per leg against ws-k.md's baseline (run 36310249248), ms:

| leg | renderThreadInitCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference | rigtuneClassBytesIdle |
|---|---|---|---|---|---|
| 26.2 OpenGL | 115.1 (+32.9) | 43.2 (+6.8) | 185.4 (+49.9) | 1.560 (+0.079) | 76296 (-336) |
| 26.3 OpenGL | 108.6 (+26.4) | 26.2 (-0.8) | 170.3 (+17.1) | 1.596 (-0.150) | 76648 (-16) |
| 26.3 Vulkan | 110.8 (-9.2) | 40.7 (+0.8) | 192.0 (-8.7) | 1.545 (+0.010) | 76408 (-592) |

Reading: mixed signs across legs, inside the runner-to-runner spread ws-k.md records (26.2 renderThreadInitCpuMs
63.5-112.9 on near-identical code), and `rigtuneClassBytesIdle` unchanged, so none of the new classes load in an idle
game; `v05RenderThreadResolve` null and `v05HolderCreatedOn` "RigTune worker" on all three legs; every key inside its
budget (150 / 141 / 300, 2.05). No screenshots are WS-P2's in Phase A (no UI yet).

## Docs (for the docs workstream)
- **README, Profiles** (a paragraph after the share codes): "**Profiles for servers.** Profiles → Servers… lets you
  have RigTune offer a profile when you join a server, a LAN game or a Realm: RigTune shows a toast (once per server
  per game session) and a notice with **Switch** and **Don't offer here**. It never switches by itself. Switch is an
  ordinary profile switch, so History can undo it; only the Minecraft settings change right away on the server, while
  Sodium, Iris and Distant Horizons values apply after a restart, as for any switch. Your own worlds, a world you open
  to LAN and the benchmark world never get offers. There's no offer on battery power while the Battery profile is on,
  and none while a benchmark runs."
- **README, "What the tools keep on your PC"** (next to `server-limits.json`), sp §2.4's wording: "`server-profiles.json`:
  the profile you asked RigTune to offer per server. Server addresses aren't stored in readable form: each entry is
  keyed by an HMAC-SHA256 of the address, with its own random key created once and kept in the same file, so someone
  who has the file could still check whether it holds a server they already know. It never goes into a report."
- **README, key areas**: `profile.server` for Profiles for servers.
- **README, known limits**: a LAN game is remembered by its host only (a new DHCP address loses it, and every world that
  host opens counts as one place); a Realm by its world name (renaming it loses the match, two Realms with the same name
  share one; untested with a real Realm); a proxy's backend switch or a reconfiguration may fire a join again
  (untested; at most one toast per server per session either way); a profile deleted by an older RigTune stays listed as
  "a deleted profile" until you forget it.
- **DESIGN.md**, a new "Per-server profile offers (0.5)" section: `ServerProfileStore` (server-profiles.json, its own
  16-byte salt, entries keyed by ServerLimitsStore's HMAC-SHA256 over the normalised address, ≤ 32 servers with a 33rd
  refused, nothing written for a server that isn't remembered); `ServerProfilePrompt.decide` (NO_SERVER, NO_MAPPING,
  MISSING_PROFILE, ALREADY_ACTIVE, BENCHMARK, ON_BATTERY, OFFER, in that order); `ServerProfileOffers` (a repeated JOIN
  with the same identity is the same connection; a lookup finishing after its connection ended offers nothing; one
  toast per server per session); `ServerProfileService` (JOIN: two field writes and one Probes.EXECUTOR task; the notice
  re-decided on every screen init and read-free while none is pending; Switch through `ProfileService.switchProfile`);
  the SERVER_PROFILE slot right after BATTERY_OFFER and before SERVER_LIMIT, which fires on every remote connection;
  the × is session-only (`AwarenessService.SESSION_ONLY_PREFIXES`); deleting a profile in Profiles forgets its servers.
- **CHANGELOG** (Added): "Profiles for servers: RigTune can offer a profile you choose when you join a server (Profiles
  → Servers…). It never switches by itself."

## AC table

| AC | status | evidence |
|---|---|---|
| AC7.1 (the file's shape, no plaintext) | unit half verified; game-test half Phase B | ServerProfileStoreTest.theFirstRememberCreatesTheSaltAndOneEntry, theFileHoldsNoPlaintextAndItsKeysDifferFromServerLimits |
| AC7.2 (keys, normalisation, X7 rules, bad salt, joined writes nothing) | verified (unit) | ServerProfileStoreTest (17 cases) |
| AC7.3 (32 cap, FULL, in place, ≤ 16 KiB) | verified (unit); the "Forget one first" status text in ServerProfilesViewTest.theStatusLines. "Unchanged file" = unchanged content; the bytes may be rewritten by a concurrent update (the race test) | ServerProfileStoreTest.aNewThirtyThirdServerIsFullAndTheFileUnchanged, reRememberingReplacesInPlace, twoRemembersRacingForTheLastPlaceGiveOneOkAndOneFull |
| AC7.4 (the 7 reasons in order) | verified (unit) | ServerProfilePromptTest |
| AC7.5 (one notice, key, actions, before SERVER_LIMIT, "+1 more") | unit part verified; game test Phase B | ServerProfileNoticeTest |
| AC7.6 (one toast per server per session) | wording only; Phase B | ServerProfileNoticeTest.theToastWording |
| AC7.7 (switch → one apply entry, Undo) | Phase B | |
| AC7.8 (benchmark/busy refusals; no offer while benchmarking) | decision half verified (BENCHMARK); refusals Phase B | ServerProfilePromptTest |
| AC7.9 (deleted profile: no offer; delete forgets; rename) | decision + `forgetProfile` verified (unit); wiring Phase B | ServerProfilePromptTest (MISSING_PROFILE), ServerProfileStoreTest.forgetForgetKeyForgetProfileForgetAll |
| AC7.10 (forget removes; × session-only) | store + key half verified; game test Phase B | ServerProfileStoreTest, ServerProfileNoticeTest.theKeyIsHiddenForTheSessionOnly |
| AC7.11 (screen lines, rows, no address, fit, Tab) | text half verified (unit); screen Phase B | ServerProfilesViewTest |
| AC7.12 (NoticeBoardTest pin) | verified (WS-K) | NoticeBoardTest; ServerProfileNoticeTest.itSortsAfterTheBatteryOfferAndBeforeTheServerLimit |
| AC7.13 (no tick/frame hook; lookup on the executor) | Phase B | |
| AC7.14 (0.4.0 leaves the file byte-identical) | fixture + expect.json landed; compat030 PASS; closes with WS-E's compat040 | V050WrittenWsP2Test; compat030 run above |
| AC7.15 (ServerProfilesGameTest, 3 legs) | Phase B | |
| AC7.16 (real JOIN on the dev PC) | rolling Phase 5 | |
| AC7.17 (on battery with Battery active: held, the line says why) | verified (unit) | ServerProfilePromptTest, ServerProfilesViewTest.heldOnBatteryOnlyWithBatteryActive, theThisServerLines |
| AC7.18 (SetSetting only under every policy) | verified (unit; pins WS-L1's guard) | ServerProfileSwitchTest |
