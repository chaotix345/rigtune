# WS-P: Performance Profiles and share codes (SPEC 4; amendments P-H1, P-L1, P-L2, X-M3; item 12 README note)

Branch `feat/profiles`. The early extraction merged as `feat/profiles-extract` (22cc915). Plan: docs/v0.4/plans/ws-p.md.

## What landed

**Recommender extraction (X-M3).** New public statics: `Recommender.context(rules, hardware, mods, snapshot, goal)`,
`settingTargets(rules, ctx, snapshot)`, `supportedSettings(list)`, `settingValues(rules, ctx, extraTokens)` and
`applyClamps(targets, rules, ctx, snapshot, List<Clamp> applied)`, with the records `SettingTarget` and `Clamp`. recommend()
output is unchanged: `RecommenderGoldenReportTest` covers 240 scenarios over a pinned rules copy (r13), and the golden was
committed before the change. `SettingValues.describe` and `Recommender.supported` are now public. Templates never see
server limits: WS-W caps only the main-list path.

**Core (`core/profile/`)**
- `ShareKeys`: the frozen, append-only v1 table (30 keys in the prototype's order; kinds BOOL/ENUM/INT/INT10/QUARTER). The
  two thread counts are local-only (`shareable=false`). `MANAGED` is the profiles' keyset. `MATCH_DISPLAY` = maxFps wire value 26.
- `ShareCode`: `RT1-` + base64url_nopad(body ‖ CRC32 BE). The strict decoder checks in the SPEC's order and throws only
  `ShareCodeException(Reason, Text)`. `ProfileNames` sanitises names.
- `ProfileSwitch` builds a switch as ordinary `SetSetting` recommendations. `ProfileTemplates` (with `TemplateId`) layers
  baseline → rule values → template entries → all clamps → in-bounds values; `clamp()` handles saved and imported
  profiles. `ProfileNotes` writes Preview's clamp and left-out lines.
- `EffectiveSettings` (audit M1): each staged key at its last pending op's value, else the file's.
- `BatteryPrompt` (+ `Debouncer`) decides offers only. `ProfileStore` has typed accessors. `ProfileImport` gained `values`;
  `ProfileView` gained the `TEMPLATE` constant.
- `ApplyPreview` gained optional `notes`, with the old constructor kept and `withNotes`. `HistoryModel.Entry` gained a
  trailing `profile`, with the old constructor kept and `withProfiles(View, labels)`.
- `PendingActions.merge`: a patch op repeats only the last pending op for its file and key (`repeatOf`, coordinator
  decision on audit M1).

**Client**
- `client/profile/ProfileService`: every C4 profile method plus the two approved defaults, `applyImportedProfile` and
  `saveImportedProfile`. It also does the History labels, the battery offer, and `overrideBenchmarkCheck` (a game-test hook).
- `client/probe/PowerWatcher` has its own daemon thread "RigTune power", polls every 30 s with a 2-poll debounce, and starts
  only when the startup probe found a real battery.
- Screens: `ProfilesScreen` (with `NameScreen`), `ProfileImportScreen`, and the `PreviewScreen` confirm overload (with the
  `Confirm` record).
- `BatteryNoticeSource` is filled in.
- One-line hotspot edits:
  - RealController: two delegations, and `history()` goes through `profileService.labelled`.
  - HardwareProbe: `setOnBattery`.
  - RigTuneClient: a `powerWatcher(real)` helper.
  - HistoryScreen: `kind()` shows "Profile: <name>".
- Lang: the `rigtune.profile.*` and `rigtune.battery.*` blocks.

**Tests**
- ShareKeysTest, ShareCodeTest, ShareCodeFuzzTest, ShareCodeInjectionTest, ProfileNamesTest.
- ProfileSwitchTest, ProfileTemplatesTest and ProfileTemplatesBundledTest (bundled r14 section), ProfileStoreTest.
- BatteryPromptTest, PowerWatcherTest, EffectiveSettingsTest, PendingMergeRepeatTest, V030CompatTest.
- SettingTargetsTest, RecommenderGoldenReportTest.
- SchemaConsistencyTest.profileKeysAndTemplateIdsMatchWsP (WS-R handoff).
- ProfilesGameTest.
- The fixture set `src/test/resources/v040-written/ws-p/` is written and checked by V030CompatTest.

## Numbers
- Golden codes (research prototype, name "Charlie's Balanced"): 78 characters (vanilla, 15 keys), 102 (vanilla + Sodium +
  Iris, 24) and 119 (every key, 30). All decode to the prototype's values, minus the local-only thread counts.
- This encoder's typical profile has 23 keys (no thread count) and is 99 characters.
- The longest valid v1 code is 519 bytes: 692 base64 characters plus the prefix, capped at 700.
- Reject timing for inputs over 4096 raw or 700 stripped characters: bounded by the mean and the 99th percentile over
  1,000 runs.
- A switch that changes anything writes exactly one journal entry (kind `apply`). A switch with nothing to change writes none.

## Deviations from SPEC 4 (and why)
1. **The thread counts stay in the v1 table as local-only entries** (indices 19 and 26), so the prototype's frozen indices
   and golden codes hold. The encoder never emits them. The decoder range-checks them, drops them and counts them
   (`localOnlyKeys`).
2. **DH verticalQuality gains PIXEL_ART** at the end of its list (a valid DH value per dh-iris.md §2; append-only).
3. **When "match the display" is sent:** the encoder sends maxFps as wire value 26 when the value equals the sender's
   `$refreshRateCap` and the sender's refresh rate is known. The SPEC defines only the decoding side.
4. **The name sanitiser also drops `=`, `#`, `"` and `\`**, because AC4.3 says the result contains "none of those
   characters". The apostrophe stays ("Charlie's Balanced").
5. **Clamps for saved and imported profiles (P-L2):**
   - They get all of the rules' clamp entries, evaluated for this PC and the current goal ("the same rules clamps as
     templates"), limited to the keys the profile holds.
   - "My settings" is not clamped: it is the way back to the player's own values.
   - Preview lists each clamp through `ApplyPreview.notes`. The switch toast adds "N values were limited for this PC".
6. **Effective values (audit M1, coordinator):** every current value (switch, Preview, templates, Save current, baseline)
   goes through `EffectiveSettings`. The profile Preview turns PreviewPlanner's "unchanged" rows for such keys back into
   staged rows. PreviewPlanner and the main-list Preview are unchanged, so the same display quirk remains after two
   ordinary Applies before a restart; that is outside this workstream.
7. **`PendingActions.merge`:** a repeat is dropped only when it matches the last pending op for that path and key
   (coordinator decision). Without that, A → B → A → B ended at A.
8. **"My settings":**
   - It is auto-saved the first time Profiles opens and also before the first switch (for example from the battery offer).
   - It can be renamed, and re-saved by saving under its name.
   - It can't be deleted (coordinator decision). Saving under another own profile's name overwrites that profile;
     imported profiles are never overwritten by name.
9. **Template definitions come from** the active rules' section, else the bundled rules' section, else a built-in copy in
   code (profiles.md §4.2 plus Battery's DH entry; it matches WS-R's r14 content). A definition whose `requires` this
   client doesn't support falls through to the next source.
10. **History labels:** `HistoryModel.withProfiles` labels only `apply` entries. It runs on the View in
    `RealController.history()`, so UndoService is unchanged. Labels are pruned against the journal's ids whenever a switch
    is recorded (only while history.json reads OK).
11. **Battery offer:**
    - The notice key is `battery-offer:<epoch s>` or `battery-back:<epoch s>`, so a dismissal hides only that offer
      (dismissals persist in awareness.json).
    - `lastPromptAt` is written only for Battery offers. The previous profile is remembered whenever Battery is switched
      to, by any path.
    - The offer is shown on screen init (C3) plus a SystemToast.
    - A power edge calls `controller.rescan()` rather than `rebuild()`, so the new `onBattery` reaches the report through
      `HardwareProbe`'s cached slow part.
12. **PowerWatcher enumerates OSHI's power sources itself**, on the probe executor, when the startup probe reported
    `hasBattery`. HardwareProbe got only `setOnBattery`, and `probeSlow` is unchanged.
13. **Template names are explicit `Text.of` per id in core** (`TemplateId.displayName()` and `description()`), so
    LangCheckTest checks each key and no dynamic template-id family is needed.
14. **Benchmark refusal in the game test** uses `ProfileService.overrideBenchmarkCheck` rather than starting a real
    benchmark (that needs a world). The guard's wiring is tested; `BenchmarkController.running()` itself isn't.
15. **AC4.11's "second switch marks the first DISCARDED"** is replaced by P-H1. Two switches of a staged key before a
    restart leave two ops, both journaled STAGED, and the helper ends on the second (game test and EffectiveSettingsTest).
16. **The ws-p fixture set uses WS-H's placeholder ids** (one Battery switch applied at a restart), because tools/e2e's
    downgrade tests and compat030.py name them. The two-switch 0.3.0 checks stay in V030CompatTest's temp-dir cases.
    `tools/e2e/tests/test_written.py` now compares the composed profiles.json with whichever set compose used (a one-line
    fix; before, it compared with the placeholder).
17. **AC4.3's options.txt half** is shown by comparing the vanilla values the game is given. A unit test can't run the
    game's writer; the three config files are compared byte for byte.

## AC status
- AC4.1 verified: ShareCodeTest (golden 78/102/119, every key at min/max/every enum value, maxFps 144 Hz → 140,
  60 Hz → 60, unknown → 60).
- AC4.2 verified: ShareCodeFuzzTest.
  - Every byte flip (4 masks) and every character change and truncation of 3 goldens is rejected.
  - 100,000 random bodies with a valid CRC throw only ShareCodeException.
  - Each listed malformation is rejected with its own `Reason`, and unknown keys are skipped and counted.
- AC4.3 verified: ProfileNamesTest and ShareCodeInjectionTest.
- AC4.4 verified: ShareKeysTest.
- AC4.5 verified: ProfileSwitchTest.
- AC4.6 verified: ProfileTemplatesTest plus ProfileTemplatesBundledTest (bundled r14 section) plus the golden report.
- AC4.7 verified: ProfileStoreTest.
- AC4.8 removed (P-H1).
- AC4.9 verified: BatteryPromptTest and PowerWatcherTest; the notice wiring is covered by ProfilesGameTest.batteryOffer.
- AC4.10: WS-R's Python tests, plus the SchemaConsistencyTest keyset check.
- AC4.11 verified: ProfilesGameTest on the 26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan legs (CI run 36225492097). Screenshots
  were reviewed: Profiles, History "Profile: Battery", the import Preview, the error line and the battery offer.
- AC4.12 verified: V030CompatTest, and compat030.py against the released 0.3.0 jar (RESULT PASS locally).
- AC4.13 is Phase 5.
- AC12.1: the README's shader-pack paragraph.

## UNVERIFIED
- PowerWatcher on a real laptop: its poll cost there, and OSHI's `updateAttributes` state changes when unplugging.
  Unit-tested with fake batteries only, since CI and this desktop have no battery.
- Recording's premises inherit profiles.md §4.3's UNVERIFIED notes: OBS game capture and tearing, and Sodium's description
  of `ALWAYS`.
- DH/Iris keys in a real game: the game tests run with Sodium only, and the DH/Iris patch paths are unit-tested.
- The 50-entry journal cap: 50 switches evict the first Apply, so "My settings" is the way back. The coordinator will list
  the cap as a known limit in the README (M6).

## Self-review (code-reviewer subagent, scratchpad ws-p/review.md): 0 high, 1 medium, 11 low; all fixed except 12 (documented)
1. (medium) **An undone switch left its profile "active".** profiles.json now keeps `activeEntry`, the switch's journal
   entry id (an additive field). `core/profile/ActiveProfile.inEffect` voids the active profile once that entry has no
   applied or staged change left, or is gone from a readable journal. That view feeds the Profiles screen's marker, the
   battery prompt, the back-offer and the "previous profile". Covered by ActiveProfileTest and a ProfilesGameTest check
   after Undo this.
2. **Hand-edited profile values** are read and written in the table's own spelling (`key.decode(key.encode(v))`, in
   ProfileStore and ProfileSwitch).
3. **The decoder checks the whole code shape (character set, 8 characters or more) before the version.**
4. **The import box keeps one character over 4096**, so an overlong paste is rejected as too long instead of being cut to fit.
5. **The import screen only decodes** (for the error line). Preview works out the switch off the render thread through the
   loader; a "not ready" error shows as a Preview note.
6. **A poll that read no battery doesn't feed the debouncer.**
7. **`PowerWatcher.stop()` sets a flag**, so a start that arrives after the client began stopping doesn't happen.
8. **Copy code shares a saved or imported profile's saved values**, not this PC's clamped ones. Imported profiles are stored
   unclamped; Apply and every later switch clamp them for the PC they run on.
9. **Keys inside a profile's `settings` that this version doesn't manage survive a re-save.**
10. **Importing the same code again (same name and values) reuses its profile.**
11. **The battery back-offer looks up only the profile's name** (no template compute on the render thread).
12. (tests) The AC4.2 timing bound is the mean and 99th percentile, not every sample (deviation above, coordinator-approved).

## Footprint (SPEC 10)
Once WS-F's budgets were merged, a cached bundled RulesDocument in ProfileService, plus the built-in template section held in a
static field, pushed `rigtuneClassBytesIdle` to 114,568 bytes against a budget of 109,296 (CI run 36227826218). Neither is
kept now: the bundled rules load only for a document without the template, and the built-in copy is parsed only when neither
document has it. After ProfilesGameTest, the same measure is 69,120 to 69,648 bytes on the 3 legs (CI run 36228392253).
PowerWatcher's thread exists only on machines with a real battery.
