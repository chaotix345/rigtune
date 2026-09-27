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
| A1 | `ServerProfileStore`: its own salt (16 random bytes, 32 hex, made on the first remember), entries keyed by `ServerLimitsStore.key(salt, address)` (package-private, no change to ServerLimitsStore); typed accessors `get`, `joined` (touches `lastSeen` only for a remembered server, else writes nothing), `remember` → `Result {OK, FULL, READ_ONLY, FAILED}` (refuses SINGLEPLAYER and a malformed profile id; a 33rd new server is FULL and writes nothing; re-remember replaces `profile`/`setAt`/`lastSeen` in place, keeping the entry's unknown fields), `forget(address)`, `forgetKey`, `forgetProfile` (count), `forgetAll`, `entries()` (valid only, newest `lastSeen` first), `keyOf` (null without a salt, writes nothing), `writable`; `record Entry(key, profile, kind, setAt, lastSeen)`. Type checks: `profile` is `p-[A-Za-z0-9-]{1,64}` or `template:[a-z_]{1,32}` (ProfileStore's shapes, redeclared), `kind` in {REMOTE, LAN_GUEST, REALM}; anything else is ignored (not offered, not listed, not counted), removed only by Forget all. A bad or missing salt: no entries read; the next remember replaces it and drops what it keyed. | `core/server/ServerProfileStore` | `core/server/ServerProfileStoreTest`: `theFirstRememberCreatesTheSaltAndOneEntry` (AC7.1 shape), `theFileHoldsNoPlaintextAndItsKeysDifferFromServerLimits`, `oneServerHowEverItsAddressIsWritten` (`Play.Example.com` = `play.example.com:25565`; port differs; `lan:`/`realm:` keys), `joinedTouchesLastSeenOnlyForARememberedServer` (bytes and mtime unchanged otherwise), `aNewThirtyThirdServerIsFullAndTheFileUnchanged`, `reRememberingReplacesInPlace`, `theFileStaysUnder16KiB`, `singleplayerAndMalformedIdsAreRefused`, `forgetForgetKeyForgetProfileForgetAll`, `entriesAreTypeCheckedAndNewestFirst`, `aBadSaltIsReplacedAndItsEntriesDropped`, `aCorruptFileIsMovedToBadAndEmpty`, `aNewerFileStillOffersButRefusesWrites` (READ_ONLY), `overFourTimesTheCapIsLeftAlone`, `unknownFieldsSurviveAtTheRootAndPerEntry`, `keyOfWritesNothingAndIsNullWithoutASalt`; `V05StoreShellsTest` stays green | AC7.1 (unit), AC7.2, AC7.3 |
| A2 | `ServerProfilePrompt.decide(kind, mapped, mappedResolves, active, benchmarkRunning, onBattery)` → `Reason {OFFER, NO_SERVER, NO_MAPPING, MISSING_PROFILE, ALREADY_ACTIVE, BENCHMARK, ON_BATTERY}`, checked in SPEC 7's order; ON_BATTERY = on battery with `BatteryPrompt.BATTERY` active | new `core/profile/ServerProfilePrompt` | `core/profile/ServerProfilePromptTest`: `eachReasonAlone`, `theChecksRunInTheDocumentedOrder` (every pair of conditions: the earlier reason wins), `aRememberedBatteryProfileOnBatteryIsAlreadyActive`, `onBatteryWithoutBatteryActiveStillOffers` | AC7.4, AC7.17 (unit), AC7.8/AC7.9 (their "no offer" unit halves) |
| A3 | The notice and toast texts in core: `ServerProfilePrompt.notice(long joinedAtMillis, Text name)` (key `server-profile:<ms>`, `SERVER_PROFILE`, "You set %s for this server. Switch to it?", the detail, actions `[switch, forget]`, dismissible), `toastTitle()`, `toastBody(Text name, Text key)`, constants `KEY_PREFIX`, `ACTION_SWITCH`, `ACTION_FORGET`; the keys in en_us.json (block after `rigtune.profile.unnamed`) | `core/profile/ServerProfilePrompt`, en_us.json | `core/profile/ServerProfileNoticeTest`: `theNoticeKeyPriorityWordingActionsAndDismissal`, `theNameIsALiteralNeverAFormat` (a name with `%s`), `theKeyIsSessionOnly` (`AwarenessService.SESSION_ONLY_PREFIXES` holds `KEY_PREFIX`), `itSortsAfterBatteryAndBeforeTheServerLimit` (`NoticeBoard.select` with the three), `theToastWording`; LangCheckTest, WordingTest, PseudoLocaleTest | AC7.5, AC7.6 (their unit parts), AC7.12 (already WS-K's) |
| A4 | `ServerProfilesView`: the builder `of(state, kind, currentKey, entries, names, active, onBattery, writable, zone)` (rows from the store's entries, names resolved, `lastJoined` the local day, the current row marked; `currentProfile`/`heldOnBattery` from the current row and `decide`), `here()` (the six This-server lines), `Row.text()` ("Server · Max FPS · last joined 2026-09-27"; "a deleted profile" for an unresolved `p-` id, "a profile this version doesn't know" for an unknown template), `status(Result, name)` (remembered, full, read only, failed) and `forgot()`/`forgotAll()`; keys in en_us.json | `core/profile/ServerProfilesView` | `core/profile/ServerProfilesViewTest`: `theSixThisServerLines`, `rowsNewestFirstWithKindProfileAndDay`, `deletedAndUnknownProfilesReadAsSuch`, `theCurrentServerIsMarked`, `heldOnBatteryOnlyWithBatteryActive`, `noRowTextHoldsAnAddress`, `theStatusLines`; `V05StubsTest` (EMPTY) stays green | AC7.11 (unit part: lines and row text), AC7.17 (the line) |
| A5 | AC7.18 pinned in core: a switch's recommendations are `SetSetting` only, and `LauncherModAdvice.guard` keeps every one of them under RIGTUNE, LAUNCHER and PENDING (so WS-L1's guard can't drop a profile switch) | test only | `core/profile/ServerProfileSwitchTest.aSwitchIsSettingsOnlyUnderEveryLauncherPolicy` (a pin: green against WS-K's identity guard, red if WS-L1's guard ever drops a setting) | AC7.18 |
| A6 | Fixture set `ws-p2`: `server-profiles.json` with one entry per kind (REMOTE → `template:max_fps`, LAN_GUEST → `template:quality`, REALM → `template:recording`; a fixed salt seeded as V040WrittenWsWTest does, fixed past instants, one `joined`), written by the store; `expect.json` (0.4.0 never opens the file: `Unread`, `unchanged`; `ServerLimitsStore` on the composed `server-limits.json`: `noBad`); `RIGTUNE_REGENERATE_FIXTURES=1` rewrites, otherwise compare | `src/test/resources/v050-written/ws-p2/`, new `core/server/V050WrittenWsP2Test` | `V050WrittenWsP2Test.theWsP2SetIsWhatThisVersionWrites` (red: no set), re-read by the store: each address maps to its profile, no plaintext | AC7.14 (fixture half; closes when WS-E's compat040 interpreter runs the set) |

Phase A adds no client code, so it adds no render-thread, tick or startup work: footprint deltas 0 by construction
(checked on the Phase A CI run's footprint JSON anyway, below).

### Phase B: client (after WS-P merges; detailed when resumed)

| # | task | files | tests | closes |
|---|---|---|---|---|
| B1 | ProfileService: `activeProfileId()`, `nameOf(id)` appended (read-only); ServerLimitsTracker `kind`/`address` public static | `client/profile/ProfileService`, `client/server/ServerLimitsTracker` | compile + existing tests | enabling |
| B2 | ServerProfileService: `onJoin` (repeated JOIN with the same identity = same connection; one `Probes.EXECUTOR` task: `joined` + `decide`, a connection-id guard), `onDisconnect`, `notice()` (a volatile read while nothing is pending; re-decides with fresh state when something is), `act` (switch through `ProfileService.switchProfile`, refusals as the toast; forget), the toast (8 s id, once per server key per session), `view()`, `remember`/`forget`/`forgetAll`/`forgetProfile`; ServerProfileNoticeSource delegates; RealController's `deleteProfile` line | `client/server/ServerProfileService`, `client/notice/ServerProfileNoticeSource`, `client/RealController` (one line) | unit where the logic is pure; ServerProfilesGameTest | AC7.5-AC7.10, AC7.13 |
| B3 | ServerProfilesScreen (RowList, RowFocus, Palette, sp §2.5 layout and Tab order) + ProfilesScreen row 3 [Import code…] [Servers…] [Done] | `client/ui/ServerProfilesScreen`, `client/ui/ProfilesScreen` | ServerProfilesGameTest layout at the X12 sizes; `walkServerProfiles` | AC7.11 |
| B4 | ServerProfilesGameTest (dedicated server on a free port, three connections, network off through `GameTestNet`; sp §5) and `A11yGameTest.walkServerProfiles` (canned view through `CannedViews`) | `gametest/ServerProfilesGameTest`, `A11yGameTest` (my method only) | 3 legs, screenshots looked at | AC7.5-AC7.11, AC7.15 |

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
  and re-checked inside the update (a server that vanished in between leaves the content as it was).
- **The salt**: missing or malformed → nothing is keyed (no entries, `keyOf` null); the first `remember` makes a new one
  and drops the entries the old one keyed (ServerLimitsStore's rule). `forgetAll` keeps the salt.

## Footprint deltas
(filled in from the Phase A CI run: `workerCpuMs5s`, `renderThreadInitCpuMs`, `clientStartedWallMs`,
`tickHookOnVsReference` per leg against ws-k.md's baseline, run 36310249248)

## Docs (for the docs workstream)
(filled in at the end of Phase B; sp §2.4's privacy wording for README's "What the tools keep on your PC")

## AC table
(filled in as tasks land)
