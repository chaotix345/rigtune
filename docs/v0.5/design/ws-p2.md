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

## Phase B as landed

| task | commits | tests |
|---|---|---|
| B1 ServerProfileOffers | c4fb8509 | ServerProfileOffersTest 7 (red first: no API) |
| B2 service, notice source, ProfileService's two read-only methods, the tracker's visibility, RealController's `deleteProfile` line | 86617f70, 642b09da | ServerProfilesGameTest; unit: B1's |
| B3 ServerProfilesScreen, ProfilesScreen row 3, the client keys | 86617f70, 642b09da (the three text blocks wrap onto two lines) | ServerProfilesGameTest's screen steps, `walkServerProfiles`; LangCheckTest, PaletteTest, WordingTest, PseudoLocaleTest |
| B4 ServerProfilesGameTest, `A11yGameTest.walkServerProfiles` | 86617f70, 642b09da | 3 CI legs |
| extra: `ProfileService.effective` keeps `downloadsChecked`; V05ServicesTest's line | 0d4f61ce | ProfileServicePreviewTest (red first) |

The game test's red was the missing API (it didn't compile against the skeleton), not a behavioural run. **Local
runs** (26.2, Windows, under `.gametest-lock`): run 1 failed in connection 2 with no offer; the diagnostic (run 2)
showed the lookup deciding OFFER and the wait itself timing out in `waitForChunksRender`, since the Quality switch
raised the render distance above what the server sends, so those chunks never come: the dedicated-server connections
now wait for the player in the world (`inTheWorld`). Run 3: ServerProfilesGameTest passed; run 4 (ServerProfilesGameTest,
FootprintGameTest, A11yGameTest): all passed, FootprintGameTest "26 budget(s), 0 over", `v05RenderThreadResolve` null.
**CI** 36359905608 (head 0d4f61ce, after merging c59b8b93): 8/8 jobs green on the first attempt; unit tests 2482 per node
(3 skipped, 0 failed). After the review fixes, **CI 36364641254** (head 10d54895, origin/feat/v0.5.0 3f42974f merged):
8/8 green on the first attempt, unit tests 2491 per node (3 skipped, 0 failed); its 26.3 Vulkan screenshots looked at
again (the toast fully in, the selected row's frame and the three wrapped text blocks).

**Screenshots looked at** (the local runs 3-4 and CI 36359905608's `gametest-screenshots-26.2-OpenGL`, `-26.3-OpenGL`
and `-26.3-Vulkan`, 25 WS-P2 shots per leg): the own world (Offer "Offer a profile here" inactive, the line says
profiles are for servers), the screen before and after "Offer Max FPS here" at 1280x720@2, 640x480@2, 854x480@2 and
1280x720@3 (nothing clipped once the three text blocks wrap, the "This server" marker and green bar on the row), the
toast ("Profile for this server" / "Max FPS is set for this server. Press F8 to switch.", fully in after 20 ticks),
RigTuneScreen's notice line with Switch / Don't offer here / × / +1 more at the wide sizes and the "…" layout at
640x480@2, NoticeScreen listing the offer above the server limit, the Forget all confirmation, the empty list, the
not-connected line; the A11y walk's seven canned rows at the three sizes, scrolled to the last row at 1280x720@3, the
selected current row after Enter, and high contrast (the focus frame on the This-server line, the label grey
recoloured). One run-1 finding fixed from them: the subtitle and the privacy line were clipped at every size.

### Review (Phase B)
A code-reviewer pass on c4fb8509..0d4f61ce went to the coordinator: 0 high, 2 medium, 10 low; every one fixed.
- **M1**: one read of profiles.json per call through the new read-only `ProfileService.names()` (the active profile and
  every id's name from one `ProfileStore.snapshot()`), used by the lookup, `notice()`, `act()` and `view()`; the screen's
  model reads server-profiles.json once (`ServerProfileStore.snapshot()`, one `JsonStateFile` load giving the content
  and whether it can be written) and is built by `ServerProfileService.view(connection, snapshot, names, …)`. The
  "count" is proven by invalidation: `ProfileServiceNamesTest`, `ServerProfileStoreTest.aSnapshotIsOneRead` and
  `ServerProfileServiceViewTest` delete the files after the one read and get the same answers.
- **M2** (product fix): the toast waits for the world to show. The lookup hands it to a waiting slot; the service's own
  END_CLIENT_TICK listener, registered on the first offer (X4.4: one field read per tick unless a toast waits, no
  allocation), shows it on the first tick with the level and player there and no screen over the world, so a slow join
  never spends the 8 s behind the loading screen. ServerProfilesGameTest asserts it through
  `ServerProfileService.toasted(key)` and `lastToast()`; the live toast's screenshot is best effort (logged).
- **L3**: `notice()` re-checks the offer's entry (`ServerProfileStore.entry(key)`, the one server-profiles.json read
  while an offer is pending): forgotten or set to another profile meanwhile retires it. **L9**: the show/hold/retire
  choice moved into `ServerProfileOffers.kept/now/settle` (unit-tested per reason); AC7.9's rename is asserted on the
  offer's own message in a fourth connection. **L4**: `Connection.toString()` leaves the address out (tested).
- **L5**: the game test also backs up server-limits.json and last-apply.json. **L6**: with no key bound to RigTune, the
  toast body is key-free (`rigtune.profile.server.toast.body.no_key`); the same fix in the suggestions toast
  (RigTuneClient.java:209, approved, marked WS-P2; `rigtune.toast.body.no_key` next to `rigtune.toast.body`). **L7**:
  the refused Switch compares every vanilla option with a before-snapshot and checks the refusal toast's text. **L8**:
  after Enter selects a row, Tab goes from the last row to Forget, then Forget all. **L10**: the clipped lines' tooltip
  only over the text's own column. **L11**: this file. **L12**: `ProfileService.effective` is package-private; the
  preview test calls it directly.
- Local run 5 (26.2, under the lock; ServerProfilesGameTest, FootprintGameTest, A11yGameTest) passed: the toast was
  on screen for its screenshot, "26 budget(s), 0 over".

## Phase B: frozen-file exceptions and additions (coordinator-approved)
- `src/test/java/io/github/chaotix345/rigtune/client/V05ServicesTest.java` (WS-K's, frozen): the skeleton contract line
  `assertEquals(ServerProfilesView.EMPTY, services.serverProfiles().view())` now reads
  `assertNull(services.serverProfiles().notice(), "no offer pending: notice() reads nothing")`, marked WS-P2 (its
  now-unused `ServerProfilesView` import goes with it): the filled `view()` reads server-profiles.json through the
  controller, which the test passes as null; `notice()` with no offer pending reads nothing (AC7.13). Approved
  2026-09-28; the coordinator records the rule for every Wave B owner in PLAN's Amendments.
- `client/profile/ProfileService.effective` (ProfileService is WS-P2's after WS-P): the rebuilt profile preview kept
  every field but `downloadsChecked` (WS-H's L5 field), which the 7-argument constructor sets false; it now passes the
  preview's own. Red first: `ProfileServicePreviewTest.aCheckedPreviewStaysChecked` (expected true, was false). Nothing
  changes on screen today (a profile preview lists no download).
- `client/RigTuneClient.java:209` (a hotspot; review L6, approved as a 1-2 line edit, marked WS-P2): the suggestions
  toast's body is key-free when no key is bound to RigTune.
- `ProfileService` gained a third read-only method, `names()` (review M1, approved).
- Inherited and kept: WS-P's exception (`RigTuneController.profileCodeLeftOut` + its forwards, ProfilesScreen.copySelected).

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

**Phase B, final** (CI 36364641254, head 10d54895, after the review fixes) against the integration head it merged
(c59b8b93 + a docs commit; c59b8b93's run 36359299550), which isolates WS-P2's change from the Wave A merges (ms; bytes):

| leg | renderThreadInitCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference | rigtuneClassBytesIdle |
|---|---|---|---|---|---|
| 26.2 OpenGL | 88.4 (-13.3) | 39.7 (-5.5) | 174.4 (-32.0) | 1.293 (-0.405) | 80032 (+664) |
| 26.3 OpenGL | 104.1 (-9.4) | 26.9 (-12.9) | 224.1 (+15.1) | 1.559 (-0.057) | 80040 (+680) |
| 26.3 Vulkan | 107.2 (-3.8) | 43.6 (-12.5) | 216.0 (+15.1) | 1.573 (+0.006) | 80160 (+1040) |

(The first Phase B run, 36359905608 at 0d4f61ce, had 116.8/113.5/111.8 renderThreadInitCpuMs and +616..+1080 bytes.)
Against ws-k.md's baseline (run 36310249248) the final run is +6.2/+21.9/-12.8 renderThreadInitCpuMs, inside the
spread and mostly the Wave A merges. Reading: the timings move both ways inside the runner spread; nothing of C16 runs at startup (the
service is made on the first JOIN or screen, the notice source's `current()` reads a volatile while nothing is pending)
and no tick or frame hook was added. `rigtuneClassBytesIdle` grows by 0.7-1.0 KB on every leg, most likely the slightly larger
RealController and ProfileService, both loaded at startup (well inside the 109,296 budget). `v05RenderThreadResolve` null
and `v05HolderCreatedOn` "RigTune worker" on all three legs.

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

Evidence: unit tests on both nodes; game tests on CI 36364641254's (and 36359905608's) three legs (26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan),
with the screenshots listed in "Phase B as landed".

| AC | status | evidence |
|---|---|---|
| AC7.1 (the file's shape, no plaintext) | verified | ServerProfileStoreTest.theFirstRememberCreatesTheSaltAndOneEntry, theFileHoldsNoPlaintextAndItsKeysDifferFromServerLimits; ServerProfilesGameTest.checkFile (formatVersion 1, 32-hex salt, 64-hex keys, no localhost/127.0.0.1/port) |
| AC7.2 (keys, normalisation, X7 rules, bad salt, joined writes nothing) | verified (unit) | ServerProfileStoreTest (22 cases) |
| AC7.3 (32 cap, FULL, in place, ≤ 16 KiB) | verified (unit); "unchanged file" = unchanged content, the bytes may be rewritten by a concurrent update | ServerProfileStoreTest.aNewThirtyThirdServerIsFullAndTheFileUnchanged, reRememberingReplacesInPlace, twoRemembersRacingForTheLastPlaceGiveOneOkAndOneFull; ServerProfilesViewTest.theStatusLines |
| AC7.4 (the 7 reasons in order) | verified (unit) | ServerProfilePromptTest |
| AC7.5 (one notice, key, actions, dismissible, before SERVER_LIMIT, shown with "+1 more") | verified | ServerProfileNoticeTest; ServerProfilesGameTest.offerSwitchAndUndo (real.notices(): the offer first and SERVER_LIMIT after it; RigTuneScreen's shownNotice at 4 sizes with otherNotices ≥ 1) |
| AC7.6 (at most one toast per server per session; none in the own world or when active) | verified | ServerProfileOffersTest.oneToastPerServerPerSession; ServerProfilesGameTest (no toast in the own world or with nothing set, the toast on connection 2, none on connection 3); the Open-to-LAN host and the benchmark world are SINGLEPLAYER (ServerProfileOffersTest.theOwnWorld…, ServerProfilePromptTest) |
| AC7.7 (Switch → one apply entry "Profile: X", Undo this, no return in the connection) | verified | ServerProfilesGameTest.offerSwitchAndUndo |
| AC7.8 (a benchmark: no offer, Switch refused, nothing changes, the offer stays; downloading: "busy") | verified for the benchmark (game test); downloading through the shared Busy check (BusyTest), not forced in the game test | ServerProfilesGameTest; ServerProfilePromptTest; BusyTest (the switch is ProfileService.switchProfile, a listed Busy caller) |
| AC7.9 (a deleted profile: no offer; delete forgets; rename) | verified | ServerProfilePromptTest (MISSING_PROFILE); ServerProfilesGameTest (Evening set, renamed "Night" and shown, deleted → 0 entries); ServerProfilesViewTest |
| AC7.10 (Don't offer here / Stop offering here remove; × session-only) | verified | ServerProfilesGameTest (×: hidden after reopening, key not in awareness.json; Don't offer here and Stop offering here → 0 entries); ServerProfileNoticeTest.theKeyIsHiddenForTheSessionOnly |
| AC7.11 (screen states, rows, no address, fit, Tab order, "Selected") | verified | ServerProfilesViewTest; ServerProfilesGameTest.checkScreen at 1280x720@2, 640x480@2, 854x480@2, 1280x720@3 (the amended scroll size); A11yGameTest.walkServerProfiles |
| AC7.12 (NoticeBoardTest pin) | verified (WS-K) | NoticeBoardTest; ServerProfileNoticeTest |
| AC7.13 (no tick/frame hook on the timed path; notice() reads nothing while none is pending; the lookup on Probes.EXECUTOR with a connection guard; FootprintGameTest) | verified | code (ServerProfileService.onJoin/lookup/notice); ServerProfileOffersTest.aLookupThatFinishesAfterADisconnectOffersNothing; V05ServicesTest (notice() with no controller); FootprintGameTest green on 3 legs, `v05RenderThreadResolve` null. The toast's wait (review M2) is its own END_CLIENT_TICK listener, registered on the first offer, never at init, off `tickHookOnVsReference`'s timed path: one field read per tick while no toast waits |
| AC7.14 (0.4.0 leaves the file byte-identical) | fixture + expect.json landed; compat030 PASS; closes with WS-E's compat040 | V050WrittenWsP2Test; compat030 run (Phase A) |
| AC7.15 (ServerProfilesGameTest, 3 legs, network off) | verified | CI 36359905608 (3 legs green); GameTestNet.set(false) |
| AC7.16 (real JOIN on the dev PC) | rolling Phase 5 (P5 agent) | the join lookup logs "this server has a profile set (<id>): <reason>" for that run |
| AC7.17 (on battery with Battery active: held, the line says why) | verified (unit); a real laptop check is the user's optional 3g step | ServerProfilePromptTest, ServerProfilesViewTest.heldOnBatteryOnlyWithBatteryActive, theThisServerLines |
| AC7.18 (SetSetting only under every policy) | verified | ServerProfileSwitchTest (unit, pins the guard); ServerProfilesGameTest (the offer's real switch journals settings only; Preview of a template and a saved profile has no download or disable) |

Stays UNVERIFIED (SPEC 7): whether proxies or reconfiguration fire JOIN again (at most one toast per server per session
either way; ServerProfileOffers treats a same-identity JOIN as the same connection); a real Realm.
