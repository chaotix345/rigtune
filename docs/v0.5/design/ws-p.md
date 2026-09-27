# WS-P: profile and battery fixes, and L8 (v0.5)

Branch `fix/v05-profiles` (worktree `rigtune-profiles5`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/SPEC.md 2P (PF-1..PF-5, Latent 1, AC2P.1-AC2P.6) and 2H's L8 (AC2H.3, moved here from WS-H by PLAN-8).
Research: docs/research/v0.5/audit-v040-verification.md (PF-*, Latent 1), v04-leftovers.md (L8). The audit's throwaway
tests (`AuditVerifyProfileTest.pf4*`, `pf5*`) are the starting red tests. WS-P2 takes ProfileService after this merges.

## TDD task plan

Each task: a red test first (a failing assertion on today's code, or a compile failure where the seam doesn't exist yet),
then the fix, then a commit. Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot; no version-specific
code is expected (X9), so `:26.3:` only as a final check; the full build and the game tests on three legs are CI's.
Game-test assertions are written before their fix; one local red run of ProfilesGameTest
(`:26.2:runClientGameTest -PgametestClasses=ProfilesGameTest`, under the game-test lock) shows them failing on today's
code, CI shows them green.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| P1 | **PF-4**: the encoder never sends "match the display" when the sender's cap is 60; a pinned copy of 0.4.0's share-code classes proves every code 0.5 emits decodes in 0.4.0 to the same values | `core/profile/ShareCode`, new `src/test/java/.../v040/core/profile/{ShareCode, ShareKeys, ShareCodeException, ProfileNames}` (`git show v0.4.0:`, package line changed only) | `ShareCodeTest.pf4SixtyFromASixtyHzSenderStaysSixty` (red: 140), `.pf4TheSixtyHzWireValueIsFive`, `ShareCodeTest.matchTheDisplayResolvesPerImportingDisplay` unchanged (180 Hz), new `V040ShareCodeCompatTest.everyCode05EmitsDecodesIn040ToTheSameValues` (every key at every wire value from 8 sender displays), `.wholeProfilesDecodeIn040ToTheSameValues` (a 1024 DH radius left out), `.sixtyFromASixtyHzSenderIsSixtyIn040`, `.theRefreshCapIsStill040s` (review L6) | AC2P.4 |
| P2 | **Latent 1**: both comments say "never extend an existing key's range or enum; add a new key index (older decoders skip unknown indices)"; ShareKeysTest pins every key's minimum and maximum wire value | `core/profile/ShareKeys` (comment), `ShareKeysTest` (comment + pin) | `ShareKeysTest.everyKeysWireRangeIsPinned` (a second pinned list: index, min wire 0, `maxWire()`, MATCH_DISPLAY for maxFps), `.theLatent1RuleIsInBothComments` (red: the comments still invite an enum value at the end; reads both sources through `RepoFiles`) | AC2P.6 |
| P3 | **PF-5**: a local-only bound per key (the DH LOD radius 32..4096, DH 3.3.2's `setMinDefaultMax(32, 256, 4096)`), used by every gate: `ProfileService.managed`, `ProfileStore.settings` (read), `ProfileSwitch.build`, and a fourth the audit missed, `ProfileTemplates.inBounds` (a saved profile's switch goes through `ProfileTemplates.clamp`). Key 22's wire range stays 32..512; the encoder leaves a value it can't carry out, and `ShareCode.leftOut(values)` counts them | `ShareKeys` (`Key.local(value)`, `LOCAL_MAX`), `ProfileStore`, `ProfileSwitch`, `ProfileTemplates`, `ProfileService`, `ShareCode` | `ShareKeysTest.pf5TheDhRadiusHasALocalRangeWiderThanTheWire` (1024 local, not on the wire; 5000 and 31 neither), `ProfileStoreTest.pf5ARadiusOf1024SurvivesARoundTripAnd5000DoesNot`, `ProfileSwitchTest.pf5ASwitchCarriesA1024RadiusAndRefuses5000`, `ProfileTemplatesTest.pf5ClampKeepsA1024Radius`, `ProfileServiceManagedTest.pf5BaselineKeepsADhRadiusAbove512` (the audit's reflective test; 5000 dropped), `ShareCodeTest.pf5ACodeLeavesOutA1024RadiusAndCountsIt`, ShareKeysTest's key-22 pin unchanged | AC2P.5 (gates, code) |
| P3b | **PF-5**: Copy code's status says how many settings the code can't carry | `ProfileService.profileCodeLeftOut`; `RigTuneController.profileCodeLeftOut` (default 0), its RealController delegation and ForwardingController forward, `ProfilesScreen.copySelected` (the coordinator's frozen-file exception), lang `rigtune.profile.status.not_carried*` | `ProfilesGameTest.pf5CopyCodeSaysWhatItLeavesOut` (a baseline with a 1024 radius: the code omits key 22, the status names 1 setting) | AC2P.5 (status) |
| P4 | **PF-1**: taking Battery with no active profile remembers "My settings" (`previous != null ? previous : baseline`) | `BatteryPrompt.previousFor`, `ProfileService.markActive` | `BatteryPromptTest.pf1WithNoActiveProfileTheBackOfferTargetsTheBaseline` (red: no seam), `ProfilesGameTest.pf1BatteryOfferFromAFreshStart` (fresh profiles.json, no switch first: `powerChanged(true)`, take it, `powerChanged(false)` → a `battery-back:` notice targeting My settings; taking it makes My settings active), `.pf1StaleMySettingsIsRefreshedFirst` (review L2) | AC2P.1 (WS-E's BatteryFlowGameTest variant is AC3e.1) |
| P5 | **PF-3**: `batteryNotice()` retires an offer whose target no longer resolves | `BatteryPrompt.stillOffered`, `ProfileService.batteryNotice` | `BatteryPromptTest.pf3AnOfferWhoseTargetIsGoneIsRetired` (red: no seam), `ProfilesGameTest.pf3DeletingTheBackOffersTargetRetiresIt` (the back-offer, delete its target → RigTuneScreen's notice line doesn't offer it and has no "Switch back to ?"; `batteryNotice()` null) | AC2P.3 |
| P6 | **PF-2, part 1**: the back-offer drops "Don't offer again" (keeps its ×); `BATTERY_TOAST_ID` public for WS-E | `ProfileService` | `ProfilesGameTest.batteryOffer` changed first: the back-offer's actions are [switch] and it is dismissible; the snooze is taken from the unplug offer (red: [switch, snooze]) | AC2P.2 (actions) |
| P7 | **L8**: the fold records `foldedEntryIds` (the folded ids and those of a baseline it absorbs, newest 50 kept); `Journal.idsWithFolded` for the label prune; `HistoryModel.Entry` carries the folded ids and resolves their labels into `includes` (newest first); HistoryScreen's baseline "Includes: Profile: Battery, Profile: Max FPS" (at most 3, then "+N") in its own method next to `kind()`; `ProfileService.journalIds()` keeps folded labels | `Journal`, `HistoryModel`, `HistoryScreen` (new `includes(entry)` next to `kind()`; the entry row wraps it and grows: `populate()`'s row line and `EntryRow`), `ProfileService`, lang `rigtune.history.includes*` (after the anchor `rigtune.history.versions.mc`) | `JournalFoldedIdsTest.theFoldRecordsTheFoldedIds`, `.aBaselineThatKeepsItsIdAddsTheEntriesItTakes`, `.aBaselineAbsorbedByALaterFoldPassesItsIdsOn`, `.atMost50IdsAreKeptTheNewest`, `.onlyABaselinesNewestFoldedIdsCount`, `.idsWithFoldedAndHoldingFindAFoldedEntry` (red: null), `HistoryModelTest.l8TheBaselineRowIncludesTheFoldedSwitchLabels` (through `fold()` and past `MAX_ENTRIES`), `L8SwitchFoldSwitchTest.theFirstLabelSurvivesTheRealPrunePath` (switch → fold past MAX_ENTRIES → another switch pruning with `Journal.idsWithFolded` → the baseline still includes the first label), `HistoryScreenTest.l8IncludesLine` (text: 1, 3 and 5 labels), `ProfilesGameTest.l8HistoryIncludesTheFoldedSwitches` (the real controller's view, screenshots at 3 sizes), JournalFoldTest's 400-seed `foldedHistoriesPlanUndoAllAsTheUncappedOnes` unchanged, `JournalFoldV030Test` + a pinned-0.3.0 read of the ws-p history.json | AC2H.3 |
| P8 | Fixture set `v050-written/ws-p`: profiles.json (a baseline holding a 1024 DH radius, Battery active, `battery.previousProfile` = the baseline as PF-1 sets it), history.json (a baseline entry with `foldedEntryIds` and the switch labels it keeps) and `expect.json` | new `src/test/resources/v050-written/ws-p/*`, new `V050WrittenWsPTest` | the test writes the set through 0.5's own code and compares (or writes it with `RIGTUNE_REGENERATE_FIXTURES=1`); the pinned 0.3.0 Journal/HistoryModel read it (state OK, same entries, the baseline an ordinary Apply); compat030 run locally against it | AC2H.3 (compat half with WS-E's interpreter), 3b's ws-p row |
| P9 | **PF-2, part 2** (after WS-L1's milestone 1 merges): the "Battery offer: On/Off" row at the marked insertion point of RigTuneSettingsScreen, writing `battery.snoozed` through `ProfileStore.snoozeBattery` | `RigTuneSettingsScreen` (marked place only), lang `rigtune.settings.battery_offer*` (after the anchor `rigtune.screen.undo_last.tooltip`), `A11yGameTest.walkBatteryOfferRow` | a block in `ProfilesGameTest.batteryOffer` (snooze from the unplug offer, the row turns it back on, the next unplug offers Battery again), the A11y walk (the row is a Tab stop narrating its state; layout at the three sizes and 854×480@3) | AC2P.2 |

No code-deciding run (none named for WS-P). No new `//? if` block expected.

Order: P1, P2, P3, P4-P6 (with one local red run of ProfilesGameTest for P4-P6), P7, P8, P3b once the coordinator
decides, P9 once WS-L1's milestone 1 has merged. First push with P1-P3 (the design doc goes up with it), never during a
streak.

---

# As landed

Paths: `client/…` = `src/client/java/io/github/chaotix345/rigtune/client/…`, `core/…` = `src/main/java/io/github/chaotix345/
rigtune/core/…`, `gametest/…` = `src/gametest/java/io/github/chaotix345/rigtune/gametest/…`.

## What changed, per item

- **PF-4** (`core/profile/ShareCode.encode`): "match the display" (wire 26) is sent only when the sender's refresh cap
  isn't 60; a 60 cap goes as wire 5 (= 60), which every 0.4.0 decoder reads as 60. Pinned verbatim 0.4.0 copies
  `src/test/java/io/github/chaotix345/rigtune/v040/core/profile/{ShareCode, ShareKeys, ShareCodeException, ProfileNames}`
  (package line changed, and ShareCode's import of `ShareCodeException.Reason` pointed at the v040 copy; they compile
  against the CURRENT `core/recommend/SettingValues` (refreshRateCap, unchanged since v0.4.0) and `core/model/Text`;
  their `package-info.java` says so). `V040ShareCodeCompatTest`: every shareable key at every wire value from 8 sender
  displays, three whole profiles (incl. a Recording 60 cap and a 1024 DH radius) from the same displays, each decoded
  by 0.5 and by the pinned 0.4.0 decoder to the same values at 5 importing displays; 60 from a 60 Hz sender is 60 in 0.4.0.
- **Latent 1**: ShareKeys' comment and ShareKeysTest's pin comment carry "never extend an existing key's range or enum;
  add a new key index (older decoders skip unknown indices)"; `ShareKeysTest.theLatent1RuleIsInBothComments` reads both
  files' comment lines; `everyKeysWireRangeIsPinned` pins index | 0 | maxWire (+26 for maxFps). The original PINNED list
  (key 22 = 32..512) is unchanged.
- **PF-5**: `ShareKeys.Key.local(value)`: the table's spelling of a wire value, else a whole number inside the key's local
  range (`ShareKeys.LOCAL_MAX`: only `dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius` → 4096), else null.
  Every profile gate uses it instead of `encode(...) != null`: `ProfileService.managed` (My settings, Save current),
  `ProfileStore.settings` (read), `ProfileSwitch.build` (the value a switch sets) and `ProfileTemplates.inBounds` (every
  template and every saved/imported profile's `clamp`). `ShareCode.encode` still uses `encode` (key 22 stays 32..512 on
  the wire; a larger radius is left out); `ShareCode.leftOut(values)` counts what a code leaves out (the local-only thread
  counts aren't counted: they're never shared). Copy code's status adds "1 setting is left out: its value is beyond what
  share codes carry." (or "%s settings …"): `RigTuneController.profileCodeLeftOut(id)` (default 0; the coordinator's
  frozen-file exception, with RealController's one-line delegation and ForwardingController's forward) →
  `ProfileService.profileCodeLeftOut` (the same resolution as `exportProfileCode`) → `ProfilesScreen.copySelected`.
- **PF-1**: `BatteryPrompt.previousFor(active, baseline)`; `ProfileService.markActive` remembers the active profile, else
  "My settings", when Battery is switched to. Coordinator decision (review L2): a switch to Battery with no profile in
  effect first refreshes "My settings" to the current (effective) values (`ProfileService.refreshBaseline`: id, name and
  creation time kept; created when missing), so plugging back in returns to the settings the player had, not an older
  snapshot.
- **PF-3**: `BatteryPrompt.stillOffered(decision, active, resolves)`; `ProfileService.batteryNotice()` retires an offer
  whose target no longer resolves (`ProfileService.resolvedName`: a known template id, or a profile still in
  profiles.json) or is already active. It reads profiles.json once per call (`ProfileStore.snapshot()`: profiles, active,
  active entry and battery from one read; review L10), as does `powerChanged`.
- **PF-2**: `BatteryPrompt.offersSnooze(offer)` (only the unplug offer); the plug-in offer's actions are [Switch back] plus
  its ×, and `batteryAction` ignores a snooze on an offer that doesn't offer it (review L3). `RigTuneSettingsScreen.batteryOfferRow` (at WS-L1's marked insertion point, one call line + its own method): a
  "Battery offer: ON/OFF" CycleButton over profiles.json's `battery.snoozed` (read at init, written on a click through
  `ProfileStore.snoozeBattery`, inactive when profiles.json is from a newer RigTune). Lang `rigtune.settings.battery_offer`
  and `.tooltip` after the anchor `rigtune.screen.undo_last.tooltip`. `ProfileService.BATTERY_TOAST_ID` is public (was
  the private `TOAST_ID`) for WS-E's BatteryFlowGameTest.
- **L8**: `Journal.baseline` fills `foldedEntryIds` (oldest first: an absorbed baseline's own list and then its id, each
  folded entry's id, never the kept baseline id; at most `Journal.MAX_FOLDED_IDS` = 50, the newest kept); the changes it
  folds are unchanged. `Journal.folded(entry)`: the ids an entry stands for, only a baseline's and at most its newest 50
  (history.json is the player's file; review L4). `Journal.idsWithFolded(entries)` (entry ids plus folded ids) and
  `@Nullable Journal.holding(entries, id)` (the entry, else the baseline that folded it; for C09/C20 records). `HistoryModel.Entry` gained trailing `folded` (from
  `build`) and `includes` (profile names of the folded switches, newest first, filled by `withProfiles`); the 9- and
  10-argument constructors are kept. `HistoryScreen.includes(entry)` (next to `kind()`): "Includes: Profile: A, Profile: B,
  Profile: C" or "… +N"; the entry row wraps it under the details line, grows by its lines and narrates it.
  `ProfileService.journalIds()` returns `Journal.idsWithFolded`, so a folded switch's label survives the next switch's
  prune. Lang `rigtune.history.includes`, `.more` after the anchor `rigtune.history.versions.mc`.
- **Fixtures**: `src/test/resources/v050-written/ws-p/{history.json, profiles.json, expect.json}`, written by
  `V050WrittenWsPTest` through 0.5's own classes (compare by default, `RIGTUNE_REGENERATE_FIXTURES=1` writes): 51 entries
  written through `Journal.update`, whose cap folds an Apply and a Max FPS switch into the baseline (50 entries kept; the
  fold's random baseline id is the one value fixed, review L7); the Max FPS label kept through `foldedEntryIds`; Battery
  taken with no profile in effect, so My settings (holding a 1024 DH radius) is refreshed and is
  `battery.previousProfile`. `expect.json`: Journal state OK with 50 entries, HistoryModel 50 with no unknown kind,
  Undo this on the Battery entry with no problem, ProfileStore with no `.bad` and its top-level fields kept.

## Deviations from the plan above (and why)
1. **A fourth PF-5 gate.** The audit (and SPEC 2P) name three gates; `ProfileTemplates.inBounds` is a fourth: resolving a
   saved profile for a switch or Preview goes through `ProfileTemplates.clamp`, which dropped a radius above 512 again.
   It uses the same local bound (`ProfileTemplatesTest.pf5ClampKeepsA1024Radius`, run over the pinned r13 and the
   bundled rules). A template computed over a baseline holding 1024 now keeps 1024 for that key unless a rule sets or
   clamps it (it never reaches a code).
2. **PF-2's row writes profiles.json directly** (`ProfileStore.snoozeBattery` from the screen) rather than through a new
   controller method: RigTuneController is frozen, and the screen already reads its own small files at init (X8), as
   ClientSettings. Consequence: the A11y walk (stub controller) writes the game's profiles.json; it puts the file back.
3. **L8's test names**: the fold tests are a new `JournalFoldedIdsTest` (so JournalFoldTest, incl. the 400-seed property,
   stays byte-for-byte unchanged); the PF-2 row check is a block inside `ProfilesGameTest.batteryOffer` (after the
   snooze) rather than its own method. `HistoryScreen`'s wiring is `populate()`'s row line (the row's height) and the
   entry row (a field, one more drawn line, the narration), besides the new `includes(entry)` next to `kind()`: a
   wrapped line needs the row to grow, which the details line (clipped to one line) couldn't give at 640×480.
4. **PF-1/PF-3/PF-2's decisions are core seams** (`BatteryPrompt.previousFor`, `stillOffered`, `offersSnooze`) so each has
   a unit test besides the game test.
5. **The fixture set's history uses its own keys and later dates** than every v040-written history (biome blend,
   particles, simulation distance, clouds, entity shadows; 2026-09-22/23). The first composition with the v040 sets
   reused v040 ws-p's keys, and compat030's "Undo this on the profile-switch entry reverts each change" then saw SKIPs
   ("changed again by a later apply"): a cross-set artefact, not a compatibility problem.
