# AC4.13: Profiles real run on 26.2 with Sodium + Iris + Distant Horizons (P5-A, 2026-09-26/27)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6), `rigtune-0.4.0-dev+mc26.2.jar` sha256 `149f20f9…dd11`.
Mods (downloaded from Modrinth's public API, sha512 checked against the API): Sodium `mc26.2-0.9.2-fabric`
(`sodium-fabric-0.9.2+mc26.2.jar`), Iris `1.11.4+26.2-fabric`, Distant Horizons `3.3.2-26.2`
(`DistantHorizons-3.3.2-26.2-fabric-neoforge.jar`), fabric-api 0.161.0+26.2. No shader pack (Iris's `enableShaders`
is still a managed setting). Dev machine: 7800X3D / RX 7800 XT / 32 GB, display 2560×1440 @ 180 Hz.

**How.** Production 26.2 clients (Loom `e2eClient`) on two scratch instances (`p5a-inst-prof1`, `p5a-inst-prof2`) plus a
copy (`prof1u`), driven by the test-only driver mod (`../p5a-tools/driver`), which calls RigTune's controller the way
the Profiles screen's buttons do: `profiles()`, `previewProfile(id)` (logged before each switch), `switchProfile(id)`,
`history()`, `undoPlanFor(entryId)` + `undo(plan)` (History's Undo this), `exportProfileCode(id)` (what Copy code puts on
the clipboard; the driver writes it to a file instead of touching the user's clipboard), and for the import the real
`ProfileImportScreen`: the code typed into its box and its **Import** button pressed, which opened PreviewScreen.
Every run quits through `Minecraft.stop()` (the Quit path), so RigTune's helper applies staged Sodium/DH/Iris changes
after the game exits. One client at a time under the lock. Each run's driver output (settings read through
`SettingsBridge.read`, the raw options.txt / sodium-options.json / DistantHorizons.toml / iris.properties, Preview rows,
History entries) is in `p5a-<run>.json`; log lines in `<run>-log-excerpt.txt`.

| run | instance | what |
|---|---|---|
| profA | prof1 | first start: the mods create their config files |
| profM (+ profM2) | prof1 | menu labels (below) |
| profB | prof1 | open Profiles (auto-saves "My settings"), Preview + switch **Max FPS**, History; quit |
| profC | prof1 | read settings/files after the restart; Preview + switch **Battery**; quit |
| (copy prof1 → prof1u) | | the state right after Battery was applied |
| profD | prof1 | read; Preview + switch **Recording**; quit |
| profE | prof1 | read; Preview + switch **My settings**; quit |
| profF | prof1 | read; History; export the share codes |
| profU1 | prof1u | **Undo this** on the "Profile: Battery" entry; quit |
| profU2 | prof1u | read after the restart |
| profI | prof2 (fresh, second instance) | import the Battery code through ProfileImportScreen → Preview |

## Results

| criterion | evidence | result |
|---|---|---|
| Max FPS → Battery → Recording → My settings, a restart after each, files match each template's values | `switch-check.md` (from `../p5a-tools/profcheck.py`): every row of each switch's Preview checked against the values read after the next restart: **Max FPS 4/4, Battery 8/8, Recording 9/9, My settings 4/4 keys match**. Raw files agree (e.g. after Battery: options.txt `renderDistance:8`, `simulationDistance:6`, `maxFps:60`, `enableVsync:true`, `particles:1`, `renderClouds:"false"`; iris.properties `enableShaders=false`; DistantHorizons.toml `rendererMode = "DISABLED"`; after My settings: `rendererMode = "DEFAULT"`, `lodChunkRenderDistanceRadius = 256`, `maxFps:120`, `enableVsync:true`, `inactivityFpsLimit:"afk"`) | **PASS** |
| Template contents as SPEC 4 defines them | Battery: maxFps 60, VSync on, clouds off, RD ≤ 8 (10 → 8), SD ≤ 6 (8 → 6), particles Decreased, shaders off (Iris), DH rendering DISABLED (inactivity already `afk`). Recording (display 180 Hz): maxFps 60 (`$recordingFps`, unchanged from 60), VSync off (display > 60 Hz), inactivity `minimized`, Sodium Chunk Updates `ALWAYS` (already). Max FPS: maxFps 260 (unlimited), VSync off. My settings: back to the baseline (maxFps 120, VSync on, afk, DH radius 256) | **PASS** |
| History shows "Profile: <name>" entries | `p5a-profF-history-final.png`: "Profile: My settings", "Profile: Recording", "Profile: Battery", "Profile: Max FPS", each an Apply entry with its changes (staged DH/Iris changes STAGED before the restart, APPLIED after) | **PASS** |
| Switch toasts/messages | "Switched to Max FPS; 1 change applies after a restart. Undo it in History." (Battery: 2 changes, Recording: 3, My settings: 1) | PASS |
| Undo this on the Battery switch restores values after the next exit | profU1: plan = 8 REVERT items (6 now, 2 needing a restart: Iris enable shaders, DH renderer mode), result "Undone: 6 now, 2 after a restart, 0 cancelled, 0 skipped."; profU2: **every setting equals its pre-Battery value** (0 differences over 388 keys vs the reading after Max FPS); History: an `undo` entry with the 8 changes APPLIED and Battery's changes REVERTED (`p5a-profU1-history-undo-battery.png`, `p5a-profU2-history-after-undo.png`) | **PASS** |
| A copied share code imported into a second instance opens Preview with the same values | Battery code `RT1-AQdCYXR0ZXJ5HAAGAQECAgMFBAEFAQYBBwIIBwkACgALAAwADQEOAQ8BEAERARIBFAAVARZAFwIYAhkEGwEcIB0A76qvOw` (98 characters, `code-Battery.txt`). In prof2: `ProfileImportScreen` → Import → **PreviewScreen "Import Battery? Nothing changes yet."** listing Render Distance 12 → 8, Simulation Distance 8 → 6, Max Framerate 120 → 60, Particles All → Decreased, Clouds Fancy → Off (written now), DH LOD Chunk Render Distance Radius 256 → 96, DH Renderer mode DEFAULT → DISABLED, Iris Enable shaders ON → OFF (at the next restart) (`p5a-profI-import-preview.png`). Decoded values = the Battery template's values on prof1 (VSync on is already on in prof2, so not listed). Nothing written before a click: all 388 settings identical before and after the Preview | **PASS** |

Share codes of the other profiles (`code-My_settings.txt` 104 characters, `code-Max_FPS.txt` 98) are there for reference.

## Menu paths named in advice text (Sodium, Distant Horizons)
- **Sodium "Chunk Updates" on the Performance page** (stutter advice `stutter-sodium-defer`: "Set Chunk Updates to Deferred
  in Sodium's video settings (Performance page)"): see profM2 below. From Sodium 0.9.2's own lang file: page names
  General / Quality / Performance / Advanced; `sodium.options.defer_chunk_updates.name` = **"Chunk Updates"**, values
  **Deferred / Soon / Immediate** (`ALWAYS` / `ONE_FRAME` / `ZERO_FRAMES`). The advice's "Deferred" matches the UI value.
- **Distant Horizons "NO. of threads"** (stutter advice `stutter-dh-threads`: 'try a lower "NO. of threads" in Distant
  Horizons' settings'): DH 3.3.2's lang: `distanthorizons.config.common.multiThreading.numberOfThreads` = **"NO. of threads"**
  under "Multi-Threading" (in "Advanced options"); the main DH page instead shows **"CPU Load"**
  (`threadPresetSetting`, "3. Balanced"), whose tooltip says it "modifies how many threads Distant Horizons' will use"
  (`p5a-profM-dh-config.png`). Runtime path: see profM2 below.

(profM2 results are appended below when that run finishes.)
