# WS-P plan: Performance Profiles + share codes (SPEC 4; amendments P-H1, P-L1, P-L2, X-M3)

Branch `feat/profiles` (worktree `rigtune-profiles`). TDD per task; commit after each; push often (CI runs unit + game tests).
Binding: SPEC item 4 + "Amendments from the plan review" (P-H1: no same-key patch replacement, AC4.8 removed; P-L1: `§`
dropped from names; P-L2: staged-count toast, imported/saved profiles clamped with each clamp listed in Preview; X-M3: templates
never use server limits), PLAN "WS-P" + Hotspots, docs/v0.4/design/ws-k.md (contracts as landed).

## Task 1 (done, merged early as feat/profiles-extract): `Recommender.settingTargets` extraction
- [x] `RecommenderGoldenReportTest` (240 scenarios over a pinned rules-v2 r13 copy) committed before the change.
- [x] `Recommender.context`, `settingTargets`, `supportedSettings`, `settingValues(rules, ctx, tokens)`, `applyClamps(..., List<Clamp>)`;
  `SettingTargetsTest`. recommend() output unchanged.

## Task 2: security core (before any UI)
- [x] `core/profile/ShareKeys`: the frozen, append-only v1 table (the prototype's 30 keys in its order; the two thread counts
  are local-only entries, never encoded; DH verticalQuality gains PIXEL_ART at the end); per key kind BOOL/ENUM/INT/INT10/QUARTER,
  bounds, `encode(String) -> Integer`, `decode(int) -> String`; maxFps reserved wire 26 = match the display. `MANAGED` keyset.
  Test: `ShareKeysTest` (pinned list, every key `SettingKeys.changeable`, graphicsPreset/shaderPack/threads not shareable,
  wire round trip for every value).
- [x] `core/profile/ShareCode` + `ShareCodeException` (reason enum + Text message) + `ProfileNames.sanitise`: encoder
  (`RT1-` + base64url(body + CRC32 BE)), strict decoder in SPEC order (4096 raw / 700 stripped, prefix, version, strict
  base64url, CRC, fmt, strict UTF-8 name, canonical varints <= 5 bytes < 2^31, count, duplicates, unknown keys skipped and
  counted, local-only keys validated and dropped, out of range rejected, no trailing bytes).
  Tests: `ShareCodeTest` (AC4.1: every key min/max/enum round trip, the prototype's 78/102/119 golden codes, maxFps reserved
  value per display), `ShareCodeFuzzTest` (AC4.2), `ProfileNamesTest` + injection part of AC4.3.

## Task 3: switching and templates (core)
- [x] `core/profile/ProfileSwitch`: values + snapshot + loaded mods + labels -> `List<Recommendation>` (only managed, present,
  changeable, safe, different keys; namespaced keys only with their mod loaded; never graphicsPreset). `ProfileSwitchTest` (AC4.5).
- [x] `core/profile/ProfileTemplates` + `TemplateId`: baseline -> rule values (template goal + forced facts) -> template
  overrides (`$recordingFps`) -> every clamp -> managed keys; section from active rules, else bundled, else a built-in copy;
  `clampProfile(...)` for saved/imported profiles (P-L2). `ProfileTemplatesTest` (AC4.6) over fixture rules until WS-R's
  content lands, then scenario tests over the bundled rules.
- [x] `ProfileStore` typed accessors: profiles (caps), active, switches (label per entry id, prune ids not in the journal,
  cap), battery state. `ProfileStoreTest` (AC4.7).
- [x] `core/profile/BatteryPrompt` (pure) + `client/probe/PowerWatcher` (fake source list in tests). `BatteryPromptTest` (AC4.9).

## Task 4: client integration
- [x] `ProfileService` (C4 methods + `applyImportedProfile`/`saveImportedProfile`, approved by the coordinator): baseline
  auto-save, switch = `RealController.apply(selected, entryId)` with guards (downloading, benchmark running), label into
  profiles.json, toast wording (P-L2), import decode + clamp + preview (nothing written), export.
- [x] RealController: the two new one-line delegations; `history()` labels via `profileService` (one line).
- [x] `HistoryModel.withProfiles(View, Map)` + Entry `profile` field (old constructor kept); `HistoryScreen.kind` shows
  "Profile: <name>".
- [x] `HardwareProbe.setOnBattery` (one method), `RigTuneClient` PowerWatcher start/stop (one helper), `BatteryNoticeSource`.
- [x] UI: `ProfilesScreen` (list, Switch, Preview, Save current, Rename, Delete, Copy code, Import code), `ProfileImportScreen`
  (EditBox, Paste, error line), `PreviewScreen` confirm overload (constructor + buttons + a notes section; WS-A owns row
  rendering). Lang keys in `rigtune.profile.*` / `rigtune.battery.*` (alphabetical), LangCheckTest template-id family.

## Task 5: compatibility and game tests
- [x] `V030CompatTest` (AC4.12): pinned 0.3.0 Journal/HistoryModel/UndoPlanner over a history.json with switch entries.
- [x] v040-written fixture set `src/test/resources/v040-written/ws-p/` (profiles.json + history.json) written by a test.
- [x] `ProfilesGameTest` (AC4.11): Battery switch (vanilla now, config ops staged, one entry "Profile: Battery"), Undo this,
  Battery -> Max FPS -> Undo last x2 and Undo all (P-H1), import -> Preview lists exactly the decoded keys and nothing is
  written until Apply, malformed code error, refused during a benchmark, network off, screenshots at 3 sizes.

## Task 6: finish
- [x] README "Profiles and share codes" incl. the shader-pack note (AC12.1).
- [x] Self-review (code-reviewer subagent), fixes; merge origin/feat/v0.4.0; build both versions; push; CI green; screenshots
  looked at; docs/v0.4/design/ws-p.md.
