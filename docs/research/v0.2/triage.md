# RigTune v0.2.0 upstream triage — REVIEW.md sections (a)/(c), Ixeris, Nvidium

Research date: **2026-09-25**. Target MC versions: 26.2, 26.3 (26.1.2 is EOL-tier, not re-checked here).
All Modrinth data pulled live via `GET api.modrinth.com/v2/project/<slug>` and
`GET /v2/project/<id>/version?loaders=["fabric"]&game_versions=["<mc>"]` with
User-Agent `chaotix345/rigtune-research (github.com/chaotix345/rigtune)`, plus GitHub API/raw-content
reads for source, changelogs and issue threads, and a direct fetch of
`fabricmc.net/2026/09/15/263.html` for the GLFW→SDL change. Jars for Ixeris, Nvidium and
LambDynamicLights were downloaded to confirm `fabric.mod.json` (mod id/depends/breaks); no jar was
launched, and `%APPDATA%\ModrinthApp` was not touched.

## 1. Summary table — REVIEW.md section (a), 33 mods

| slug | decision | reason |
|---|---|---|
| animaticarefabricated | IGNORE | cosmetic OptiFine/MCPatcher animated-texture format support, no perf component |
| better-mount-hud | IGNORE | cosmetic HUD addition while riding mounts |
| calcmod | IGNORE | in-chat calculator, pure QoL |
| cape-provider | IGNORE | cosmetic cape skins |
| cloth-config | IGNORE | config-UI library, auto-pulled as a dependency |
| configmanager | IGNORE | server-side config distribution tool; `client_side: unsupported` on Modrinth — doesn't even run on RigTune's client target |
| continuity | IGNORE | cosmetic connected-textures support, inert without a matching resource pack |
| controlify | IGNORE | controller/gamepad support, accessibility QoL not perf |
| crash-assistant | IGNORE | post-crash log analysis GUI, a diagnostics aid not a perf mod |
| e4mc | IGNORE | opens a LAN server to remote friends via tunnel; connectivity convenience, not local performance |
| ears | IGNORE | cosmetic skin/ears customization |
| entity-model-features | IGNORE | OptiFine-format custom entity model support, inert without a matching resource pack |
| entitytexturefeatures | IGNORE | OptiFine-format entity texture variation, inert without a matching resource pack |
| esf | IGNORE | cosmetic entity sound variation via resource packs |
| fabric-api | IGNORE | base Fabric API, auto-pulled as a dependency by nearly everything |
| fabric-language-kotlin | IGNORE | Kotlin language support, auto-pulled as a dependency |
| forge-config-api-port | IGNORE | config-system compatibility library, auto-pulled as a dependency |
| fzzy-config | IGNORE | config-UI library, auto-pulled as a dependency |
| lambdabettergrass | IGNORE | cosmetic grass/snow blending style |
| lambdynamiclights | **ADD** (tracked, not recommended) | real dynamic-lighting FPS cost on weak hardware; see §4-style writeup below |
| main-menu-credits | IGNORE | cosmetic title-screen text |
| mixintrace-reborn | IGNORE | mixin-crash debug aid for developers; no 26.2/26.3 Fabric build at all, and unlisted on Modrinth |
| modmenu | IGNORE | mod list/config-screen UI other mods hook into; not a performance mod itself |
| morechathistory | IGNORE | raises chat scrollback limit, pure QoL |
| optigui | IGNORE | cosmetic GUI texture reskinning ("blazing fast" describes its own code, not the game) |
| placeholder-api | IGNORE | text-placeholder/formatting library, auto-pulled as a dependency |
| reeses-sodium-options | IGNORE | alternative UI for Sodium's *existing* settings, changes no performance behavior itself |
| renice-shot | IGNORE | high-resolution screenshot tool |
| resourceful-config | IGNORE | cross-platform config library, auto-pulled as a dependency |
| skyboxify | IGNORE | cosmetic custom-sky/skybox support |
| sodium-shadowy-path-blocks | IGNORE | cosmetic lighting-consistency fix for dirt-path blocks under Sodium |
| yacl | IGNORE | config-UI library (YetAnotherConfigLib), auto-pulled as a dependency |
| zconfig | IGNORE | mixin-config plugin written specifically for zfastnoise (already an RigTune ModRule), auto-pulled as its dependency |

**32 IGNORE, 1 ADD** (LambDynamicLights, added as a *tracked-but-not-recommended* rule so its
avoid-on-weak-hardware advice can fire — see §2/§6).

## 2. Proposed `knowledge.json` additions

### 2a. New `mods[]` entry — LambDynamicLights

Paste-ready. `recommendWhen.always: false` means RigTune never *offers* to install it (it's a
visual preference, not a performance feature); `avoidWhen` only fires if the player already has it
installed and their hardware is weak, mirroring the existing `moonrise-opt` pattern (an always-false
rule still tracked in `mods[]`, not `reviewIgnore`, so `conflictsWith`/`avoidWhen` can act on it).

```json
{
  "slug": "lambdynamiclights",
  "projectId": "yBW8D80W",
  "title": "LambDynamicLights",
  "modIds": ["lambdynlights"],
  "category": "lighting",
  "impact": "low",
  "stability": "stable",
  "reason": "Makes held, worn and dropped light sources (torches, glowstone, lava buckets, glowing mobs) light up the world around them in real time. It's a visual preference, not a performance win, so RigTune doesn't suggest adding it.",
  "recommendWhen": { "always": false },
  "avoidWhen": { "tierAtMost": 2 },
  "avoidReason": "Dynamic lights recalculate lighting around every lit entity every frame. The mod's own docs recommend pairing it with Sodium \"for better performances\" and expose a light-update-smoothness setting to tune the cost, both signs of a real CPU drain on entry-level hardware; disabling it (or turning that smoothness down) can recover FPS.",
  "conflictsWith": ["sodiumdynamiclights", "ryoamiclights", "optifabric"],
  "defaultSelected": false
}
```

Evidence for the fields: `fabric.mod.json` (downloaded from
`lambdynamiclights-4.12.4+26.2.jar`, project `yBW8D80W`) gives `"id":"lambdynlights"` — **not**
`lambdynamiclights**s**`, the modId does not match the slug — and
`"breaks":{"optifabric":"*","sodiumdynamiclights":"*","ryoamiclights":"*"}`, which is exactly
`conflictsWith` above. `environment` is `"client"`. Its README (`LambdAurora/LambDynamicLights`,
default branch `26.3`) says "[Sodium] is recommended for better performances" and lists "Settings
to select how smoothly the dynamic lighting updates" among its features.

I considered the same treatment (EMF/ETF/Continuity) but did **not** propose it for those three —
see the note at the end of §1's table and the fuller reasoning in §6. Their cost is proportional to
whatever resource pack is loaded on top, not to the mod itself, so there's nothing RigTune (which
doesn't manage resource packs) can act on; LambDynamicLights' cost is present the moment it's
installed, with or without a resource pack, which is the actual dividing line.

### 2b. New `reviewIgnore[]` entries

Paste-ready, same tone as the existing list (plain, specific, no "not relevant").

```json
{ "slug": "animaticarefabricated", "reason": "cosmetic OptiFine/MCPatcher animated-texture format support, no performance component" },
{ "slug": "better-mount-hud", "reason": "cosmetic HUD addition while riding a mount, not a performance mod" },
{ "slug": "calcmod", "reason": "an in-chat calculator; pure QoL utility, unrelated to performance" },
{ "slug": "cape-provider", "reason": "cosmetic cape skins, no performance relevance" },
{ "slug": "cloth-config", "reason": "shared config-UI library pulled in automatically as a dependency by mods that need it" },
{ "slug": "configmanager", "reason": "a server-side config distribution tool for modpack authors; its Modrinth listing marks client_side unsupported, so it does nothing on RigTune's client-only target" },
{ "slug": "continuity", "reason": "cosmetic connected-textures support; does nothing without a resource pack that defines connected textures, so any FPS cost belongs to the pack, not to Continuity" },
{ "slug": "controlify", "reason": "adds controller/gamepad support; an accessibility and QoL feature, not a performance optimization" },
{ "slug": "crash-assistant", "reason": "shows and analyzes logs after a crash; a diagnostics aid, not a performance mod" },
{ "slug": "e4mc", "reason": "opens a LAN server to remote friends over a tunnel; a multiplayer connectivity convenience, unrelated to local performance" },
{ "slug": "ears", "reason": "cosmetic skin customization (extra skin layers), no performance relevance" },
{ "slug": "entity-model-features", "reason": "OptiFine-format custom entity model support; a cosmetic compatibility layer that does nothing without a matching resource pack, so any FPS cost belongs to the pack, not to EMF" },
{ "slug": "entitytexturefeatures", "reason": "OptiFine-format entity texture/emissive variation, the texture counterpart to EMF; same reasoning, inert without a matching resource pack" },
{ "slug": "esf", "reason": "cosmetic entity sound variation via OptiFine-format resource packs, no performance relevance" },
{ "slug": "fabric-api", "reason": "the base Fabric modding API; pulled in automatically as a dependency by nearly every other mod" },
{ "slug": "fabric-language-kotlin", "reason": "Kotlin language support for Fabric mods; pulled in automatically as a dependency when a Kotlin-based mod needs it" },
{ "slug": "forge-config-api-port", "reason": "config-system compatibility library for multiloader mods; pulled in automatically as a dependency" },
{ "slug": "fzzy-config", "reason": "config API with auto-generated GUIs; pulled in automatically as a dependency" },
{ "slug": "lambdabettergrass", "reason": "cosmetic grass/snow blending style, no performance relevance" },
{ "slug": "main-menu-credits", "reason": "cosmetic title-screen text, no performance relevance" },
{ "slug": "mixintrace-reborn", "reason": "adds mixin names to crash reports for developers; unlisted on Modrinth with no Fabric build for 26.2 or 26.3 at all (latest is 26.1.2), and not a performance mod regardless" },
{ "slug": "modmenu", "reason": "the mod list / config-screen UI other mods hook their own settings screens into; useful, but not a performance optimization itself" },
{ "slug": "morechathistory", "reason": "raises the maximum chat scrollback length; pure QoL, unrelated to performance" },
{ "slug": "optigui", "reason": "cosmetic GUI texture reskinning (OptiFine custom GUI support); \"blazing fast\" in its description is about its own implementation, not a game performance improvement" },
{ "slug": "placeholder-api", "reason": "text placeholder/formatting library for other mods; pulled in automatically as a dependency" },
{ "slug": "reeses-sodium-options", "reason": "an alternative options-menu UI for Sodium's existing settings; it doesn't add or change any performance behavior itself, and Sodium Extra (already tracked) covers RigTune's extra-options needs" },
{ "slug": "renice-shot", "reason": "a high-resolution screenshot tool, no performance relevance" },
{ "slug": "resourceful-config", "reason": "cross-platform config library; pulled in automatically as a dependency" },
{ "slug": "skyboxify", "reason": "cosmetic custom-sky/skybox support, no performance relevance" },
{ "slug": "sodium-shadowy-path-blocks", "reason": "a cosmetic lighting-consistency fix for dirt-path blocks under Sodium, no measurable performance angle" },
{ "slug": "yacl", "reason": "config-UI library (YetAnotherConfigLib); pulled in automatically as a dependency" },
{ "slug": "zconfig", "reason": "a mixin-config plugin written specifically for zfastnoise, already an RigTune ModRule; pulled in automatically as its dependency" }
```

## 3. Section (c) findings — rule mods with no Fabric build for 26.3

| slug | still missing 26.3? | upstream activity | verdict |
|---|---|---|---|
| moonrise-opt | yes (live-checked) | `Tuinity/Moonrise` pushed 2026-09-24 (yesterday), not archived, 89 open issues | active, wait for upstream — no rule change |
| krypton | yes (live-checked) | `astei/krypton` pushed 2026-08-23, not archived, 10 open issues | active, wait for upstream — no rule change |
| vulkanmod | yes (live-checked, stuck at 26.1.2, same as REVIEW.md) | `xCollateral/VulkanMod` pushed 2026-08-13, not archived, 294 open issues | slowest of the four, but not dead. Its ModRule already has `recommendWhen.always: false` (hard-conflicts with Sodium/Iris on every version), so the 26.3 gap changes nothing user-facing today — no rule change |
| particle-core | yes (live-checked) | `fzzyhmstrs/pc` pushed 2026-07-08, not archived, 9 open issues | active, wait for upstream — no rule change |
| **debugify** | **no — REVIEW.md is stale here** | version `26.3.0.0` published **2026-09-24T14:30:14Z**, one day before this research | **REVIEW.md was generated before this build shipped.** `availability` in `rules-v1.json` is regenerated live by `update_rules.py` on every run, so the next scheduled run will pick this up automatically — no `knowledge.json` edit needed. Flagging only so the maintainer doesn't spend time chasing something already fixed. |

None of these five needs a `knowledge.json` change. All four still-missing mods are on actively
pushed repos, not abandoned — "wait for upstream" holds for all of them.

## 4. Ixeris — deep dive and recommendation

**Current rule** (`knowledge.json`): `recommendWhen: {"always": true}`, no `avoidWhen`,
`stability: "stable"`, `impact: "low"`, `defaultSelected` unset (defaults `true`).

**What it does** (from its own README, `decce6/Ixeris`): two mechanisms —
1. **Threaded event polling** — moves event polling to the main thread and rendering to a
   separate render thread (in vanilla they're the same thread), so mouse/keyboard event
   processing doesn't stall frame rendering.
2. **Buffered raw input** (Windows-only) — replaces per-event `GetRawInputData` calls with
   batched `GetRawInputBuffer`, eliminating per-event JNI/FFM upcall overhead. This is the
   headline number in its README: **10.1x FPS at 8000 Hz mouse polling**, 1.78x at 2000 Hz,
   1.13x at 500 Hz (grabbed-cursor test); in menus (cursor visible, no raw input), the win is
   smaller — 1.24x on Windows, 1.03-1.11x on Linux.

**MC 26.3's windowing change** (`fabricmc.net/2026/09/15/263.html`, fetched live, quoted verbatim):

> "The GLFW library used for window and keybind management has been removed in favour of SDL. SDL
> has many benefits including a broader API than GLFW, so modders interacting with input outside
> the game may wish to check its documentation... the SDL backend will become out of sync and
> character input will entirely break [if text-input focus isn't reported]."

GLFW is not deprecated alongside SDL in 26.3 — it's **gone**. Ixeris's technical-details section
describes its mechanism entirely in GLFW terms (`glfwSet*Callback`, `glfwWaitEventsTimeout`, "GLFW
state caching", GLFW thread-safety rules), so this change hits Ixeris at its core, not at the edges.

**What Ixeris actually did about it** (full changelog history via the Modrinth versions API,
`decce6/Ixeris` issue #129, and the downloaded `fabric.mod.json` for the 26.3 build):

- **2026-07-26, v4.6.0**: "Added initial SDL3 support and ported to 26.3-snapshot-5... Buffered
  raw input does not currently work on SDL3." The maintainer's comment on issue #129 the same day:
  *"Threaded event polling is implemented, along with some necessary state caching to make it
  performant. The buffered raw input improvement is left to a future version."*
- **2026-08-01 to 2026-08-12** (v4.6.2, v4.6.3): several rounds of SDL3-specific fixes — mouse
  capture, vsync not working, a native crash on window close, a crash with "BlazeSDL" (the
  in-process mixin/compat name these changelogs use for Mojang's new SDL-based windowing layer),
  "the API in use (GLFW/SDL3) is now determined at runtime," more SDL3 state caching.
- **2026-09-17, v4.6.6**: "Updated to 26.3", "Fixed keyboard behavior with BlazeSDL."
- **2026-09-21, v4.6.8 (current, 4 days before this research)**: "Fixed game crash on 26.3
  NeoForge" (Forge-loader-specific, not Fabric), "Improved blocking behavior when setting window
  progress on SDL."
- **No changelog entry since the 2026-07-26 "left to a future version" note announces buffered raw
  input working on SDL3.** Issue #129 ("Update to `26.3-snapshot-4` (SDL3 support)") is still
  **open**.

`fabric.mod.json` from the current 26.3 build (`Ixeris-4.6.8+26.3-fabric.jar`): `"id": "ixeris"`
(matches the existing `modIds`), `"environment": "client"`, `"depends": {"minecraft": ">=26.3",
"fabricloader": ">=0.16.0"}`, no `breaks`. Nothing here blocks installation or signals a
conflict.

**Net effect on 26.3**: threaded event polling — the cross-platform mechanism, and per Ixeris's
own menu-cursor benchmark table the larger real-world win outside the 8000 Hz-mouse extreme case —
works on 26.3. Buffered raw input, the single biggest number in the README, is **not yet ported**
to the SDL3/BlazeSDL path 26.3 uses, per the maintainer's own words and the absence of any later
fix. This is a **smaller win, not a broken or unstable mod** — the 26.3-specific changelog entries
are bug fixes on top of a working port (crashes, vsync, blocking behavior), not signs of
abandonment; the repo was pushed as recently as 2026-09-21 (4 days before this research).

**Recommendation: keep Ixeris's rule exactly as-is** — `recommendWhen: {"always": true}`,
`defaultSelected: true`, `stability: "stable"`, no `avoidWhen`, no `mcVersion` gating. It's still a
genuine, no-downside performance mod on every version RigTune targets, including 26.3; nothing
about the SDL3 transition makes it worth avoiding, disabling, or hiding. I'd suggest a small wording
tweak to the existing `reason` (currently "Handles window and input events on a separate thread,
which can smooth out frame times and slightly reduce input lag") since "slightly" undersells the
26.1.2/26.2 case and oversells the 26.3 case, but that's optional copy, not a behavior change.

**Optional addition** (not required, flagging since I have the evidence): a low-impact `info`
`AdviceRule` that fires only when Ixeris is installed and `mcVersion` is `26.3`, noting that the
biggest input-latency win is still pending upstream for the new windowing backend. I did not draft
JSON for this since it's a judgment call on whether it's worth the extra advice-list noise, not a
correctness issue.

## 5. Nvidium — GPU-generation gating

**Current rule**: `recommendWhen: {"gpuVendor": ["nvidia"], "gpuIntegrated": false,
"gpuTierAtLeast": 4, "modPresent": ["sodium"]}`, `avoidWhen: {"anyOf": [{"not": {"gpuVendor":
["nvidia"]}}, {"gpuTierAtMost": 2}, {"flags": ["shaders-enabled"]}]}`.

**Requirement, confirmed from source** (`MCRcortex/nvidium` README, fetched live): *"Requires
sodium and an nvidia gtx 1600 series or newer to run (turing+ architecture)."* Mesh shaders are a
Turing-and-newer NVIDIA feature; every consumer/prosumer line from Turing onward supports them:
GTX 16-series (1650/1660 and their Ti/SUPER variants), RTX 20/30/40/50-series, and the Turing-based
Quadro RTX line plus the newer Ampere/Ada `RTX Axxxx` and `RTX xxxx Ada Generation` workstation
cards. Everything Pascal (GTX 10-series) and older does not support mesh shaders and cannot run
Nvidium, no matter how strong the card is otherwise.

**This exposes a real gap in the current `gpuTierAtLeast: 4` gate.** In `knowledge.json`'s own
`gpuTiers` table, GTX 1650/1660 and RTX 2060/2070/2080 are tier 3 (bucketed together with the
*non*-mesh-shader-capable GTX 1060-1080, `"GTX\\s*(?:10[6-8]0|16[56]0)\\b"` → tier 3) — so the
existing rule **never recommends Nvidium on genuinely-supported Turing cards** because they don't
clear the tier-4 floor, while it **would recommend it on a tier-4 AMD or Intel card if the vendor
check weren't there** (vendor check saves that case, but only by accident of the `anyOf`
structure). Tier is a raw-performance ranking; mesh-shader support is a separate, architecture-level
capability axis. `gpuModelMatches` (new in v0.2.0) lets the rule check the real thing directly.

**Current 26.2/26.3 status** (live-checked): Nvidium `0.4.4-beta7-26.3` was published
2026-09-23 (2 days before this research), `depends: {"sodium": ["0.9.2"]}` — pinned to exactly the
current Sodium 26.3 build, confirmed via the Modrinth version it points at
(`mc26.3-0.9.2-fabric`). `fabric.mod.json` for that build: `"id": "nvidium"` (matches existing
`modIds`), `"environment": "client"`, no `breaks`. Still beta channel, but active and current.

**Proposed regex** (Java, `find`-semantics, same style as the existing `gpuTiers` patterns):

```
(?i)GTX\s*16[56]0\b|RTX\s*(?:20[6-8]0|30[5-9]0|40[5-9]0|50[5-9]0)\b|Quadro\s*RTX\s*\d{4}\b|RTX\s*A\d{3,4}\b|RTX\s*\d{4}\s*Ada\b
```

JSON-escaped, ready to paste as the `gpuModelMatches` value:

```
"(?i)GTX\\s*16[56]0\\b|RTX\\s*(?:20[6-8]0|30[5-9]0|40[5-9]0|50[5-9]0)\\b|Quadro\\s*RTX\\s*\\d{4}\\b|RTX\\s*A\\d{3,4}\\b|RTX\\s*\\d{4}\\s*Ada\\b"
```

Proposed rule change — swap the tier check for the model check in both places:

```json
"recommendWhen": { "gpuVendor": ["nvidia"], "gpuIntegrated": false, "gpuModelMatches": "(?i)GTX\\s*16[56]0\\b|RTX\\s*(?:20[6-8]0|30[5-9]0|40[5-9]0|50[5-9]0)\\b|Quadro\\s*RTX\\s*\\d{4}\\b|RTX\\s*A\\d{3,4}\\b|RTX\\s*\\d{4}\\s*Ada\\b", "modPresent": ["sodium"] },
"avoidWhen": { "anyOf": [ { "not": { "gpuVendor": ["nvidia"] } }, { "not": { "gpuModelMatches": "(?i)GTX\\s*16[56]0\\b|RTX\\s*(?:20[6-8]0|30[5-9]0|40[5-9]0|50[5-9]0)\\b|Quadro\\s*RTX\\s*\\d{4}\\b|RTX\\s*A\\d{3,4}\\b|RTX\\s*\\d{4}\\s*Ada\\b" } }, { "flags": ["shaders-enabled"] } ] },
"avoidReason": "Nvidium needs an NVIDIA GTX 16-series card or newer (Turing architecture or later, for mesh shader support) and switches itself off while a shader pack is on, so it can't help on this setup."
```

Laptop variants need no special-casing: `"NVIDIA GeForce RTX 3070 Laptop GPU"` still contains the
substring `"RTX 3070"`, so the same regex matches mobile parts of a supported generation — mesh
shader support is architectural, not a desktop/laptop distinction, unlike the existing raw-tier
laptop patterns.

**Test strings — must match:**
"NVIDIA GeForce RTX 4070", "NVIDIA GeForce GTX 1660 SUPER", "NVIDIA GeForce GTX 1650",
"NVIDIA GeForce GTX 1650 Ti", "NVIDIA GeForce RTX 2060", "NVIDIA GeForce RTX 2060 SUPER",
"NVIDIA GeForce RTX 2080 Ti", "NVIDIA GeForce RTX 3080", "NVIDIA GeForce RTX 3070 Laptop GPU",
"NVIDIA GeForce RTX 4090", "NVIDIA GeForce RTX 5080", "Quadro RTX 4000", "Quadro RTX 5000",
"Quadro RTX 8000", "NVIDIA RTX A2000", "NVIDIA RTX A4000", "NVIDIA RTX A6000",
"NVIDIA RTX 4000 Ada Generation"

**Test strings — must NOT match:**
"NVIDIA GeForce GTX 1080", "NVIDIA GeForce GTX 1080 Ti", "NVIDIA GeForce GTX 1070",
"NVIDIA GeForce GTX 1060", "NVIDIA GeForce GTX 980 Ti", "NVIDIA GeForce GT 1030",
"NVIDIA GeForce MX450" (see UNVERIFIED, §7), "Quadro P4000", "Quadro M4000",
"AMD Radeon RX 6800 XT", "Intel(R) Iris(R) Xe Graphics"

## 6. Evidence per mod

Modrinth project id / client-server sides / 26.2 & 26.3 Fabric availability / license, live-checked
2026-09-25. "26.2"/"26.3" = at least one Fabric-loader version returned by the per-version query.

| slug | projectId | client_side | server_side | status | license | 26.2 | 26.3 |
|---|---|---|---|---|---|---|---|
| animaticarefabricated | xEyZuswh | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| better-mount-hud | kqJFAPU9 | required | unsupported | approved | GPL-3.0-only | yes | no |
| calcmod | XoHTb2Ap | optional | optional | approved | MIT | yes | yes |
| cape-provider | orXAsira | required | unsupported | approved | LGPL-2.1-or-later | yes | yes |
| cloth-config | 9s6osm5g | optional | optional | approved | LGPL-3.0-only | yes | yes |
| configmanager | jlNms3Jp | unsupported | required | approved | LGPL-3.0-only | yes | yes |
| continuity | 1IjD5062 | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| controlify | DOUdJVEm | required | optional | approved | LGPL-3.0-or-later | yes | yes |
| crash-assistant | ix1qq8Ux | required | unsupported | approved | LicenseRef (KostromDan MML 1.1.3) | yes | yes |
| e4mc | qANg5Jrr | required | required | approved | MIT | yes | no |
| ears | mfzaZK3Z | required | unsupported | approved | MIT | yes | yes |
| entity-model-features | 4I1XuqiY | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| entitytexturefeatures | BVzZfTc1 | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| esf | IMuO8COj | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| fabric-api | P7dR8mSH | optional | optional | approved | Apache-2.0 | yes | yes |
| fabric-language-kotlin | Ha28R6CL | optional | optional | approved | Apache-2.0 | yes | yes |
| forge-config-api-port | ohNO6lps | optional | optional | approved | MPL-2.0 | yes | yes |
| fzzy-config | hYykXjDp | required | required | approved | LicenseRef (TDL-M) | yes | yes |
| lambdabettergrass | 2Uev7LdA | required | unsupported | approved | LicenseRef (Lambda License) | yes | yes |
| lambdynamiclights | yBW8D80W | required | unsupported | approved | LicenseRef (Lambda License) | yes | yes |
| main-menu-credits | qJDfP7WN | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| mixintrace-reborn | Ok6FyXJ9 | optional | optional | **unlisted** | MIT | **no** | **no** |
| modmenu | mOgUt4GM | required | unsupported | approved | MIT | yes | yes |
| morechathistory | 8qkXwOnk | required | unsupported | approved | CC0-1.0 | yes | yes |
| optigui | JuksLGBQ | required | unsupported | approved | LGPL-3.0-or-later | yes | yes |
| placeholder-api | eXts2L7r | optional | optional | approved | LGPL-3.0-only | yes | yes |
| reeses-sodium-options | Bh37bMuy | required | unsupported | approved | MIT | yes | yes |
| renice-shot | ZDCwiIc3 | required | unsupported | approved | MIT | yes | yes |
| resourceful-config | M1953qlQ | optional | optional | approved | MIT | yes | yes |
| skyboxify | DWuwk8aA | required | unsupported | approved | GPL-3.0-only | yes | yes |
| sodium-shadowy-path-blocks | EIa1eiMm | required | unsupported | approved | LGPL-3.0-only | yes | yes |
| yacl | 1eAoo2KR | optional | optional | approved | LGPL-3.0-or-later | yes | yes |
| zconfig | 4qmvXRB9 | optional | optional | approved | MPL-2.0 | yes | yes |

Section (c) + Ixeris/Nvidium mods (all `approved`):

| slug | projectId | 26.2 | 26.3 | note |
|---|---|---|---|---|
| moonrise-opt | KOHu7RCS | yes | no | live-confirmed still missing |
| krypton | fQEb0iXm | yes | no | live-confirmed still missing |
| vulkanmod | JYQhtZtO | no | no | stuck at 26.1.2 |
| debugify | QwxR6Gcd | yes | **yes** | shipped 26.3.0.0 on 2026-09-24, REVIEW.md is stale |
| particle-core | RSeLon5O | yes | no | live-confirmed still missing |
| ixeris | p8RJPJIC | yes | yes | modId `ixeris`, see §4 |
| nvidium | SfMw2IZN | yes | yes | modId `nvidium`, see §5 |
| sodium | AANobbMI | yes | yes | context check for Nvidium's dependency pin |

`fabric.mod.json` mod ids confirmed by downloading and unzipping the latest jar (only for mods
where a `ModRule`/`avoidWhen` is proposed, per the task's jar-download guidance):

- **lambdynamiclights** (`lambdynamiclights-4.12.4+26.2.jar`): id `lambdynlights`, `environment:
  client`, `breaks: {optifabric:*, sodiumdynamiclights:*, ryoamiclights:*}`.
- **ixeris** (`Ixeris-4.6.8+26.3-fabric.jar`): id `ixeris` (unchanged from current rule),
  `environment: client`, `depends: {minecraft: >=26.3, fabricloader: >=0.16.0}`, no `breaks`.
- **nvidium** (`nvidium-0.4.4-beta7-26.3.jar`): id `nvidium` (unchanged), `environment: client`,
  `depends: {fabricloader: >=0.18.4, minecraft: [26.3], sodium: [0.9.2]}`, no `breaks`.

## 7. UNVERIFIED items

- **MX-series NVIDIA laptop chips** (MX450 etc., Turing TU117 die) may technically support mesh
  shaders, but Nvidium's README only says "GTX 1600 series or newer" and never mentions MX by
  name. I excluded MX from the `gpuModelMatches` regex to match the README's own wording rather
  than my own inference about the underlying silicon — flagging this as a possible future
  refinement if better evidence turns up, not a confirmed exclusion.
- **Ixeris buffered raw input on SDL3**: confirmed unresolved via changelog absence + issue #129
  still open as of the most recent build (4.6.8, 2026-09-21), but I did not read Ixeris's Java
  source directly to double-check — this is changelog/issue-tracker evidence, not source-level
  verification.
- **Continuity/EMF/ETF "no cost without a matching resource pack"** is architectural reasoning
  (these mods parse and apply resource-pack-supplied override files; with none present, the extra
  code paths are close to no-ops) cross-checked against `docs/research/knowledge.md`'s existing FO
  classification, not something I benchmarked or read the source of.
- **e4mc's missing 26.3 build**: not investigated further (upstream-in-progress vs. abandoned)
  since it doesn't change the IGNORE verdict either way — it's out of RigTune's scope regardless of
  availability.
- **reeses-sodium-options "no independent perf effect"**: inferred from its Modrinth description
  ("Alternative Options Menu for Sodium") and its "Pretty"/cosmetic classification in
  `docs/research/knowledge.md`, not from reading its source to confirm it exposes zero settings
  beyond what Sodium/Sodium Extra already do.
