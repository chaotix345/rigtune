# RigTune Research: Knowledge Base for the Rules-Generation Job

Research date: **2026-09-24**. Target: Minecraft Java **26.2** (Fabric Loader 0.19.x, Fabric API 0.152.x), with notes on **26.3** (released 2026-09-15, 9 days old at research time — upstream ports are still catching up). All claims below are sourced live; URLs are inline. Where I could not verify something live, I've flagged it explicitly rather than asserting it from memory.

---

## 1. Upstream curated mod lists as machine-readable data

### 1.1 Fabulously Optimized (`Fabulously-Optimized/fabulously-optimized`)

- **Branch:** everything lives on `main` (there is also `l10n_main` for translations and some contributor working branches like `mmh-*`; `main` is the only protected/canonical branch). There are **no per-Minecraft-version branches** — instead, versions are directories. Verified via `GET /repos/Fabulously-Optimized/fabulously-optimized/branches`.
- **Repo layout for packwiz data:**
  ```
  Packwiz/
    26.2/
      pack.toml
      index.toml
      .packwizignore
      mods/
        sodium.pw.toml
        lithium.pw.toml
        modernfix.pw.toml      <- filename is legacy; content now points at the mVUS fork, see below
        ... (46 files for 26.2)
      config/
      resourcepacks/
    26.3/                       <- alpha pack, MC 26.3, fewer mods (still catching up)
    26.1.2/
    1.21.11/
    ... (per-version dirs back to 1.16.5)
  ```
  Full path for a mod file: `Packwiz/26.2/mods/sodium.pw.toml`.
- **Important correction to the task brief:** the file extension packwiz uses is **`.pw.toml`**, not `.pm.toml`. Confirmed by directory listing (`sodium.pw.toml`, `lithium.pw.toml`, etc.) at `github.com/Fabulously-Optimized/fabulously-optimized/tree/main/Packwiz/26.2/mods`.
- **`pack.toml` for 26.2** (`raw.githubusercontent.com/Fabulously-Optimized/fabulously-optimized/main/Packwiz/26.2/pack.toml`):
  ```toml
  name = "Fabulously Optimized"
  author = "robotkoer"
  version = "14.1.0"
  pack-format = "packwiz:1.1.0"

  [index]
  file = "index.toml"
  hash-format = "sha256"
  hash = "7279fffc6159db5cc5772e2bf50b44c674eb73b35fc8ac8f15e445049d1c4bdc"

  [versions]
  fabric = "0.19.5"
  minecraft = "26.2"
  ```
  For 26.3, `pack.toml` shows `version = "15.0.0-alpha.3"` and `minecraft = "26.3"` — confirms the 26.3 pack is still a pre-release, alpha snapshot of the pack itself.
- **`index.toml`** is just a manifest of every file in the pack (mods, configs, resource packs) with sha256 hashes, e.g.:
  ```toml
  hash-format = "sha256"
  [[files]]
  file = "mods/sodium.pw.toml"
  hash = "..."
  ```
  A script enumerating the mod list does **not** need to parse `index.toml` at all — it's simpler to just list the `mods/` directory contents directly via the GitHub Contents API or a git checkout.
- **Example `.pw.toml`** — `Packwiz/26.2/mods/sodium.pw.toml`:
  ```toml
  name = "Sodium"
  filename = "sodium-fabric-0.9.2+mc26.2.jar"
  side = "both"

  [download]
  url = "https://cdn.modrinth.com/data/AANobbMI/versions/xJZxADzI/sodium-fabric-0.9.2%2Bmc26.2.jar"
  hash-format = "sha512"
  hash = "9b7a7aade8824543ce2a804fddfd04e37f57d1f91c00574e7dc87e554eb1835d888313ee6f715a7599bd729840cf01fd74e02aeae87b4e5a3c10c34a016e407c"

  [update]
  [update.modrinth]
  mod-id = "AANobbMI"
  version = "xJZxADzI"
  ```
  The fields RigTune's scraper needs are **`update.modrinth.mod-id`** (Modrinth project id, e.g. `AANobbMI` = the `sodium` project) and **`update.modrinth.version`** (the specific Modrinth version id, e.g. `xJZxADzI`). `download.url` is a direct `cdn.modrinth.com` link, which is a second, redundant way to resolve the same version.
  - **Interesting find:** `Packwiz/26.2/mods/modernfix.pw.toml` and `Packwiz/26.3/mods/modernfix.pw.toml` both have `name = "ModernFix-mVUS"` and `mod-id = "TjSm1wrD"` — i.e. FO itself ships the **mVUS fork** under the legacy `modernfix.pw.toml` filename because upstream ModernFix (`nmDcB62a`) has no 26.2/26.3 builds (verified 0 results from the Modrinth versions API, see §2). This directly answers the task's "which ModernFix fork for 26.2" question: **`modernfix-mvus` (project id `TjSm1wrD`)**.
- **Enumeration algorithm for a script:**
  1. `GET /repos/Fabulously-Optimized/fabulously-optimized/contents/Packwiz/{mcversion}/mods?ref=main` → list of `*.pw.toml` filenames.
  2. For each, `GET` the raw file, parse TOML, read `update.modrinth.mod-id` and `update.modrinth.version`.
  3. Cross-reference `mod-id` against `INCLUDED-MODS.md` (see §1.3) to get FO's own performance/cosmetic/functional classification.
  - No packwiz binary is required — it's plain TOML, parseable with any TOML library.

### 1.2 Additive (`skywardmc/additive`)

- **Branches:** `main` (protected/canonical) and `dist` (build artifact branch). Verified via `GET /repos/skywardmc/additive/branches`. Also no per-version branches.
- **Repo layout for packwiz data:** versions live under `versions/fabric/{mcversion}/`, not at the repo root:
  ```
  versions/
    fabric/
      26.2/
        pack.toml
        index.toml
        .packwizignore
        mods/
          sodium.pw.toml
          c2me-fabric.pw.toml
          vmp-fabric.pw.toml      <- "Very Many Players", superseded by Moonrise, see §2
          modernfix-mvus.pw.toml  <- mVUS fork by filename too
          ... (57 files for 26.2)
        config/
        resourcepacks/
      26.3/                        <- exists, present at research time
      26.1.2, 26.1, 1.21.x, 1.20.1 ...
  ```
  Full path: `versions/fabric/26.2/mods/sodium.pw.toml`.
- **`pack.toml` for 26.2:**
  ```toml
  name = "Additive"
  author = "SkywardMC"
  version = "26.5.0+mc26.2.fabric"
  pack-format = "packwiz:1.1.0"

  [index]
  file = "index.toml"
  hash-format = "sha256"

  [versions]
  fabric = "0.19.5"
  minecraft = "26.2"

  [options]
  no-internal-hashes = true
  ```
- **Example `.pw.toml`** — `mods/modernfix-mvus.pw.toml`:
  ```toml
  name = "ModernFix-mVUS"
  filename = "modernfix-5.27.19-build.1.jar"
  side = "both"

  [download]
  url = "https://cdn.modrinth.com/data/TjSm1wrD/versions/TUWH6NZu/modernfix-5.27.19-build.1.jar"
  hash-format = "sha512"
  hash = "..."

  [update]
  [update.modrinth]
  mod-id = "TjSm1wrD"
  version = "TUWH6NZu"
  ```
  Same field shape as FO (`packwiz:1.1.0` format), so a single parser works for both packs.
- **Enumeration algorithm:** identical to FO but rooted at `versions/fabric/{mcversion}/mods`.

### 1.3 Licenses — and the flag

| Repo | License file | Type | Key terms |
|---|---|---|---|
| Fabulously-Optimized/fabulously-optimized | `LICENSE.md` | **Modified/3-clause BSD** ("Copyright 2020-2026 Fabulously Optimized Authors") | Permits redistribution in source/binary form if: (1) copyright notice + this list of conditions + disclaimer are retained in source redistributions, (2) same in binary-form docs/materials, (3) the FO name/contributor names aren't used to endorse derived products without permission. Standard "AS IS", no warranty, no liability clause. |
| skywardmc/additive | `LICENSE` | **MIT** ("Copyright (c) 2022 SkywardMC") | Standard MIT — free to use/copy/modify/merge/publish/distribute, condition is the copyright + permission notice must be included in all copies or substantial portions of the *software* (i.e. the repo's own code/config). |

Both licenses are for the **repository content** (the packwiz TOML files, scripts, docs) — not for the mod `.jar`s themselves, which are separate projects each under their own license (e.g. Sodium is LGPL-3.0, Lithium is a mix of LGPL/custom per-file). Because RigTune's plan is to fetch mod jars directly from each mod's own official Modrinth project via the Modrinth CDN (not from FO's/Additive's packaged files), individual mod-jar licensing isn't implicated by consuming FO/Additive's *lists*.

**Flag for the plan:**
1. RigTune should treat both repos' `.pw.toml` files purely as a **data source to extract Modrinth project ids from** — not vendor the raw TOML files wholesale into RigTune's own repo/build output. If RigTune's generator script *does* check any of their files into RigTune's own repo (e.g. as cached fixtures), it must carry the copyright notice per BSD-3/MIT terms.
2. Both licenses disclaim warranty — RigTune shouldn't represent "included in FO/Additive" as a compatibility guarantee; RigTune needs its own conflict/version testing regardless.
3. Credit both projects (name + link) in RigTune's docs/about screen as good practice, independent of strict license necessity, since the actual value RigTune extracts is their curation *work*, not just code.
4. No copyleft exposure: neither license is GPL-family, so using their mod-id lists as input to a proprietary or differently-licensed RigTune codebase is not a problem.

### 1.4 Performance vs. cosmetic vs. feature mods

**Fabulously Optimized explicitly self-classifies** in `INCLUDED-MODS.md` (`github.com/Fabulously-Optimized/fabulously-optimized/blob/main/INCLUDED-MODS.md`) into four sections: **Smooth** (performance), **Pretty** (cosmetic/visual), **Functional** (QoL/utility), **Libraries** (shared dependencies). For 26.2 specifically:

- **Smooth (performance)** — Better Block Entities, Dynamic FPS, Entity Culling, FastQuit, FerriteCore, ImmediatelyFast, Ixeris, Language Reload, Lithium, **ModernFix-mVUS** (not plain ModernFix — greyed out/not shipped for 26.2), Remove Reloading Screen, Sodium. (Greyed out for 26.2, i.e. *not* shipped because obsolete/unported: Better Beds, Enhanced Block Entities, Hydrogen, LazyDFU, Phosphor, Smooth Boot, Starlight — see §2 for why.)
- **Pretty (cosmetic)** — Animatica Refabricated, BetterGrassify, Better Mount Hud, Cape Provider, Continuity, Entity Model Features, Entity Texture Features, **Iris Shaders** (FO classifies Iris as cosmetic, not performance — it's the shader-support vehicle, not an optimizer), LambDynamicLights, Model Gap Fix, MoreCulling, Paginated Advancements, Polytone, Puzzle, Skyboxify, OptiGUI, Reese's Sodium Options, Renice Shot, Sodium Extra, Sodium Shadowy Path Blocks.
- **Functional (QoL/feature)** — Controlify, Config Manager, Crash Assistant, Cubes Without Borders, Debugify, e4mc, Main Menu Credits, MixinTrace Reborn, Mod Menu, More Chat History, No Chat Reports, Zoomify.

Additive doesn't publish an equivalent table in its README (it points to a separate wiki page, `skywardmc.org/adrenaline/performance-features`, which I did not fetch), but its `mods/` directory for 26.2 is overwhelmingly performance/worldgen-tier: Sodium, Lithium, C2ME, VMP/Moonrise, ScalableLux, FerriteCore, ModernFix-mVUS, ImmediatelyFast, Ixeris, Entity Culling, Cull Fewer Leaves, Structure Layout Optimizer, Material Rule Compiler, Fast Noise, ServerCore, Quick Pack, Async Logger, Fast Server Pings, Particle Core. Its cosmetic layer is smaller: Animatica Refabricated, Ears, Cape Provider, Continuity, Entity Model Features, Entity Texture Features, Polytone, Skyboxify, Zoomify, LambdaBetterGrass, LambDynamicLights. Config-library glue (Cloth Config, YACL, Fzzy Config, ZConfig, Resourceful Config) is neither perf nor cosmetic — shared dependency.

---

## 2. Performance mod catalogue for Fabric 26.2

All Modrinth project ids, `client_side`/`server_side` flags, and 26.2 version counts below were pulled live from `api.modrinth.com/v2/project/{slug}` and `api.modrinth.com/v2/project/{slug}/version?game_versions=["26.2"]&loaders=["fabric"]` with `User-Agent: rigtune-research/0.1 (charlie.wh345 at gmail)` on 2026-09-24.

| Mod | Slug | Project ID | 26.2 fabric? | Purpose | Client-only? | Known conflicts | Hardware conditions |
|---|---|---|---|---|---|---|---|
| Sodium | `sodium` | `AANobbMI` | Yes (0.9.2, released 2026-09-11) | Rewrites the chunk/entity renderer; the base for nearly everything else here | client required, server unsupported | Mutually exclusive with **VulkanMod** and **OptiFine/OptiFabric** (both replace the same rendering pipeline) | Requires OpenGL 4.5+ ([Sodium docs](https://modrinth.com/mod/sodium)); see driver workarounds in §3 |
| Lithium | `lithium` | `gvQqBUqZ` | Yes (0.25.3) | General game-logic/simulation optimizations (AI, redstone, chunk system, etc.), not rendering | optional both sides | Overlaps with **Moonrise** on chunk-system optimizations; Moonrise auto-disables the conflicting Lithium parts but the overlap is "somewhat redundant" per community guidance ([source](https://github.com/orgs/IrisShaders)); rare mixin overwrite conflicts with old ModernFix builds (`removeIf` in `SortedArraySetMixin`) | Any |
| FerriteCore | `ferrite-core` | `uXXizFIs` | Yes (9.0.0) | Reduces RAM usage (block-state/model storage, palette memory) | optional both sides | None significant; ModernFix explicitly recommends running FerriteCore alongside it | Best win on low-RAM (4-8 GB) systems |
| ModernFix | `modernfix` | `nmDcB62a` | **No** — latest build is `5.27.22+mc26.1.2` (2026-08-31), nothing for 26.2/26.3 | Startup-time and memory-usage fixes (parallel mod/resource loading, GL leak fixes, etc.) | optional both sides | n/a for 26.2 | n/a |
| **ModernFix-mVUS (fork)** | `modernfix-mvus` | `TjSm1wrD` | **Yes (1 build, `5.27.20+build.1`)** | Community fork of ModernFix "that adds minor version update support" ([project desc](https://modrinth.com/project/TjSm1wrD)); source at [github.com/coredex-source/ModernFix---mVUS](https://github.com/coredex-source/ModernFix---mVUS) | optional both sides | Same as ModernFix | **This is the one RigTune should recommend for 26.2/26.3** — both FO and Additive ship it under the `modernfix.pw.toml`/`modernfix-mvus.pw.toml` filenames respectively, because upstream is unported |
| ImmediatelyFast | `immediatelyfast` | `5ZwdcRci` | Yes (1.16.5+26.2) | Speeds up immediate-mode GUI/text/item rendering | client required | None known | Any |
| Entity Culling | `entityculling` | `NNAgCjsB` | Yes (1.11.2, 2026-09-21) | Async ray-trace culling of entities/block entities not visible | client required | Complementary (not conflicting) with MoreCulling — different authors, different culling scope, both shipped together by FO/Additive | Any |
| MoreCulling | `moreculling` | `51shyZVL` | Yes (1.8.1) | Extends culling to more categories (leaves, particles, other block-level culling) beyond what Entity Culling covers | client required | Complementary to Entity Culling | Any |
| Better Block Entities | `better-block-entities` | `ONZm0H7Y` | Yes (1.3.8-beta.1, beta channel) | Batches/optimizes chest, sign, bed, etc. rendering ("hybrid renderer" per Sodium-addon description) | client required | Needs Sodium | Any |
| Ixeris | `ixeris` | `p8RJPJIC` | Yes (4.6.8+26.2) | Buffered raw input + threaded event polling (input-latency/CPU) | client required | None known | Any |
| BadOptimizations | `badoptimizations` | `g96Z4WVZ` | Yes (2.4.1) | Non-rendering optimizations grab-bag | client required | Historically flaky with certain other mods; as of 2.2.0 it added a system letting other mods mark themselves incompatible with specific BadOptimizations sub-options so it can auto-disable just that patch ([Modrinth changelog search](https://modrinth.com/mod/badoptimizations)) | Treat as "advanced/optional" tier — not in FO's or Additive's default list |
| ScalableLux | `scalablelux` | `Ps1zyz6x` | Yes (0.3.0-alpha.0.3+26.2) | "Based on Starlight" — modern light-engine optimization successor | optional both sides | Do not combine with **Moonrise** (Moonrise bundles its own Starlight-derived light engine) or old **Starlight**/**Phosphor** builds | Any |
| C2ME | `c2me-fabric` | `VSNURh3q` | Yes (0.4.2-alpha.0.52+26.2) | Multithreaded chunk generation/loading | client optional, **server required** | Alpha-channel software; heavier CPU-core usage during worldgen/chunk loading | Best on multi-core CPUs (6+ threads); marginal on 2-4 core laptop CPUs |
| **Moonrise** | `moonrise-opt` | `KOHu7RCS` | Yes (1.1.2, 2026-08-23) | Optimization mod "for the dedicated/integrated server" — successor to Very Many Players (Fabric); source at [github.com/Tuinity/Moonrise](https://github.com/Tuinity/Moonrise) | optional both sides | **Redundant with Lithium and FerriteCore** on overlapping systems (auto-disables conflicting Lithium parts, but still double-check); **do not combine with Starlight** (bundles equivalent); Additive still ships the older `vmp-fabric` (`wnEe9KBa`, "Very Many Players (Fabric)", server-side) rather than `moonrise-opt` for 26.2 — treat these as the same lineage, prefer `moonrise-opt` going forward | Biggest win on multiplayer/high-entity-count worlds; less relevant single-player low-entity-count |
| Krypton | `krypton` | `fQEb0iXm` | Yes (0.3.1) | Optimizes the network stack (packet encoding/encryption) | optional both sides | None known | Any; negligible visual/hardware relevance |
| Dynamic FPS | `dynamic-fps` | `LQ3K71Q1` | Yes (3.11.9) | Throttles resource usage when window is unfocused/minimized/on battery | client required | None known | **Directly relevant to RigTune's laptop/battery detection** — recommend enabling when `on_battery=true` |
| Sodium Extra | `sodium-extra` | `PtjYWJkn` | Yes (0.9.4) | Adds extra Sodium-style toggles ("features that shouldn't be in Sodium") | client required | Needs Sodium | Any |
| Reese's Sodium Options | `reeses-sodium-options` | `Bh37bMuy` | Yes (2.2.4) | Alternative/expanded options-menu UI for Sodium | client required | Needs Sodium | Any |
| Nvidium | `nvidium` | `SfMw2IZN` | Yes (0.4.4-beta6-26.2, beta) | NVIDIA-only accelerated Sodium renderer (mesh shaders) | client required | **Requires NVIDIA Turing+ (mesh-shader support), i.e. GTX 16-series/RTX 20-series or newer; AMD/Intel unsupported.** Auto-disables its accelerated path (not a crash) while Iris is actively rendering shaders — "Nvidium disabled due to shaders being loaded" ([nvidium.org FAQ](https://nvidium.org/nvidium-compatibility-with-iris-does-it-work/)) | GPU-tier-gated: only offer when detected GPU is NVIDIA Turing or newer |
| VulkanMod | `vulkanmod` | `JYQhtZtO` | **No** — latest is `0.6.8+26.1.2` (2026-06-14), not yet ported to 26.2/26.3 | Full Vulkan renderer replacement | client required | **Hard-incompatible with Sodium and Iris** — both hook OpenGL, VulkanMod replaces OpenGL entirely; official wiki lists Sodium, Indium, Iris as crash-causing ([xCollateral/VulkanMod wiki](https://github.com/xCollateral/VulkanMod/wiki/Incompatible-mods)) | Not currently offerable for 26.2 — flag as "pending upstream port" |
| Distant Horizons | `distanthorizons` | `uCdwusMi` | Yes (3.3.2-26.2, 2026-09-21) | Renders low-detail terrain (LODs) far beyond normal render distance | optional both sides | **Requires Sodium 0.6.0+** as a hard dependency; shader support via **Iris 1.7+** for a subset of packs (BSL, Reflective Vanilla, Rethinking Voxels/Complementary forks) — not universal shader compatibility | Needs VRAM/RAM headroom: community guidance is +2-4 GB RAM over vanilla, and VRAM scales with LOD render distance (~4 GB VRAM at LOD radius 1024) ([DH FAQ](https://blog.curseforge.com/distant-horizons-frequently-asked-questions/)) |
| Iris | `iris` | `YL57xq9U` | Yes (1.11.4+26.2, 2026-09-13) | Shader-pack support (OptiFine-shader-compatible), built on/alongside Sodium | client required | **Version-pinned to Sodium**: e.g. Iris `1.11.2+26.2` requires Sodium `mc26.2-0.9.1` specifically — RigTune's rules must match exact Sodium/Iris version pairs, not just loader+game-version ([irisshaders.org guide](https://irisshaders.org/guides/iris-shaders-with-sodium/)); mutually exclusive with VulkanMod | Shaders are GPU-intensive; gate behind mid/high tier |
| FastQuit | `fastquit` | `x1hIzbuY` | Yes (3.1.5+mc26.2) | Returns to title screen immediately while world save finishes in background | client required | None known | Any |
| Debugify | `debugify` | `QwxR6Gcd` | Yes (26.2.0.0) | Fixes assorted long-standing Mojang-tracked bugs | optional both sides | None known | Any |
| Particle Core (extra find) | `particle-core` | `RSeLon5O` | Yes (0.3.3+26.2) | Particle culling/spawn-reduction, "compatible with Sodium, improves performance over Sodium alone" per project description | client required | None known; complements Sodium | Good candidate for low-end/particle-heavy scenarios (potions, fireworks) |
| Cull Fewer Leaves (extra find) | n/a (referenced via Additive's `cull-fewer-leaves.pw.toml`) | `alhWWxax` | not directly queried, but shipped in Additive 26.2 | Culls hidden leaf faces | client | Overlaps conceptually with vanilla "See-Through Leaves" setting | Any |

### Obsolete / harmful mods to flag for removal on 26.x

| Mod | Modrinth slug | Last supported MC version (Modrinth) | Why remove |
|---|---|---|---|
| **Indium** | `indium` | `1.21.1` (2026 builds absent) | Was the Fabric Rendering API bridge for Sodium; **Sodium has shipped this built-in since 0.6.0**, and Indium is explicitly incompatible with Sodium 0.6+. Redundant and will conflict. |
| **Starlight** | `starlight` | `1.20.4` (dead since) | Rewrote the light engine; **Mojang merged equivalent improvements into vanilla as of MC 1.20** ("the 1.20 vanilla light engine copies basically everything from Starlight" — [technical details doc](https://github.com/PaperMC/Starlight/blob/fabric/TECHNICAL_DETAILS.md)). Client-side benefit is gone; not built for 26.x at all. (Server-side variants can still help dedicated servers on older versions, not relevant to 26.2 since it's unported.) |
| **Phosphor** | `phosphor` | `1.19.4` (dead since) | Predecessor lighting-optimization mod, itself superseded by Starlight years ago, which was then absorbed into vanilla. Double-obsolete. |
| **OptiFine / OptiFabric** | not on Modrinth (CurseForge-only; OptiFabric is the Fabric bridge) | OptiFabric's last release supports MC 1.20.4 (Jan 2024); a 2025 maintenance request for 1.21+ was answered "no longer maintained" | Closed-source, doesn't support Fabric natively, **fundamentally incompatible with Sodium** (can't run both — pick one), and unmaintained past 1.20.4 so it cannot run on 26.x at all. |
| **LazyDFU** | `lazydfu` | `1.20.6` (dead since) | Deferred DataFixerUpper init for faster boot; **Mojang optimized DFU initialization itself around 1.19.4-1.20**, closing most of the gap this mod provided. Unmaintained for 26.x. |
| **Smooth Boot (Fabric)** | `smoothboot-fabric` | `1.19.4` (dead since) | Manually retunes CPU thread priorities at boot. Abandoned; a maintained fork/successor (**ThreadTweak**) exists but even that is a niche, risky tweak on modern OS schedulers (Windows 11's own scheduler already does dynamic prioritization) — not worth carrying forward as a default recommendation. |

Note: I distinguished `smoothboot` (slug `smoothboot`, project id `tFFOFlpc`) which is actually **"SmoothBoot & Network Optimizer" for dedicated servers**, a different, unrelated, still-alive project — don't conflate the two when writing removal rules; match on project id, not display name substring.

---

## 3. Settings knowledge

### 3.1 Vanilla 26.2 video settings

Minecraft 26.2 reorganized the video-settings screen into Display / Quality & Performance / Preferences and added a new, directly relevant setting: **Graphics API** (`Default` / `Prefer OpenGL` / `Prefer Vulkan (Experimental)`) — Vulkan requires "Vulkan 1.2 with dynamic rendering and push descriptors," and critically: **"the player's dedicated graphics card is preferred over any integrated graphics [under Vulkan], which is a change from OpenGL."** On macOS, MoltenVK translates Vulkan→Metal. ([minecraft.wiki/w/Java_Edition_26.2](https://minecraft.wiki/w/Java_Edition_26.2))

This matters directly for RigTune: on a laptop with switchable graphics, which GPU gets used for rendering can differ between OpenGL and Vulkan mode — RigTune's hardware probe should record *both* which GPU it detects and which Graphics API mode is active, since they can disagree. Also: **Sodium hooks OpenGL**, so Sodium + "Prefer Vulkan" is nonsensical pairing (Sodium doesn't support the new Vulkan backend); RigTune's rules should force `Prefer OpenGL` (or leave `Default`, which resolves to OpenGL when Sodium is present) whenever Sodium is installed, and only consider Vulkan mode for vanilla-only/no-Sodium configurations. VulkanMod is the mod-side alternative renderer and is unrelated to vanilla's native Vulkan option.

| Setting | Default (26.2) | Performance direction | Notes |
|---|---|---|---|
| Render distance | 8 (Fast) / 16 (Fancy) / 32 (Fabulous) chunks | **Lower = large FPS/CPU/RAM win** | Primary dial for RigTune's in-game benchmark auto-tune |
| Simulation distance | 6 chunks (min 5) | **Lower = CPU win** (entity ticking, redstone, block/fluid ticks) — independent of render distance | Tune separately; low-end CPUs benefit even if GPU-bound render distance stays higher |
| Graphics preset (Fast/Fancy/Fabulous!) | Fancy | **Fast fastest, Fabulous! slowest** | "Fabulous!" adds a full extra transparency compositing pass; historically the single worst FPS setting. With Sodium installed, Fabulous is effectively superseded by Sodium's own "Improved Transparency"/quad-splitting handling — don't recommend Fabulous when Sodium is present |
| Improved Transparency | OFF | Off = faster, On = correct translucency blending | Sodium replaces this mechanism with `quad_splitting_mode` (§3.2) when installed |
| See-Through Leaves (cutout leaves) | OFF | Off (cutout, opaque-style) is cheaper than translucent leaf rendering | Toggle name in 26.2 is "See-Through Leaves" |
| Particles | Decreased | Minimal fastest, All slowest | 26.2 changed the overflow behavior to randomly drop particles instead of hard-cutting, per release notes |
| Biome blend | 3×3 fast (default) | 0/off fastest, 5×5 costs more | Larger blend radius = more per-pixel color averaging work |
| Entity distance | 75% | Lower % = fewer entities rendered = perf win | 100% = up to 160 blocks |
| Clouds | Fast (flat) | Off fastest, Fast cheap, Fancy (3D) most expensive | |
| Entity shadows | OFF | Off is cheaper (minor cost) | |
| Mipmap levels | 2 (max 4) | Minor VRAM cost scales with level; negligible FPS impact either way, but reduces texture shimmer at distance | Set to 0 only on very low VRAM (<1-2 GB dedicated / shared iGPU memory) |
| Texture filtering / Anisotropic filtering | None | Off cheapest; AF cost scales with level (Disabled=2×, up to higher) | Only exposed once "Anisotropic" filtering mode is selected |
| Max framerate | 120 fps | Capping to monitor Hz (or Hz×1) saves GPU work/heat/battery with no visible benefit above refresh rate | Important for laptop-on-battery detection: cap to refresh rate |
| VSync | ON | On removes tearing but adds input latency and hard-caps to refresh; Off costs more power for FPS above refresh | For laptops on battery, VSync ON (or a modest FPS cap) is the efficiency-first choice; competitive/low-latency users may want it off |
| Chunk Fade (chunk section fade-in) | 0.75s | Shorter/instant = marginally cheaper during chunk pop-in, mostly cosmetic | |
| Weather Effect Radius | 5 blocks (options: 5 or 10) | 5 blocks cheaper than 10 (less overdraw during rain/snow) | New-ish granular setting per current wiki data |
| Chunk Builder (vanilla) | Threaded (also Semi Blocking / Fully Blocking) | Irrelevant once Sodium is installed — Sodium replaces the chunk builder entirely with `chunk_builder_threads`/`chunk_build_defer_mode` (§3.2) | RigTune should hide/ignore this vanilla control when Sodium is active |

Source: [minecraft.wiki/w/Options](https://minecraft.wiki/w/Options) (Video Settings section) and [minecraft.wiki/w/Java_Edition_26.2](https://minecraft.wiki/w/Java_Edition_26.2) release notes, both fetched live 2026-09-24.

### 3.2 Sodium 0.9 options

Verified against Sodium's `dev` branch source on GitHub (`CaffeineMC/sodium`), specifically `SodiumOptions.java` (field names) and `en_us.json` (tooltip text), 2026-09-24:

```java
// common/src/main/java/net/caffeinemc/mods/sodium/client/gui/SodiumOptions.java
public static class PerformanceSettings {
    public int chunkBuilderThreads = 0;                 // -> chunk_builder_threads (0 = auto)
    public DeferMode chunkBuildDeferMode = DeferMode.ALWAYS;  // -> chunk_build_defer_mode
    public boolean useFogOcclusion = true;               // -> use_fog_occlusion
    public boolean useBlockFaceCulling = true;            // -> use_block_face_culling
    public QuadSplittingMode quadSplittingMode = QuadSplittingMode.SAFE; // -> quad_splitting_mode
}
```

| Option (JSON key) | In-game label | Direction | Detail |
|---|---|---|---|
| `chunk_builder_threads` | "Chunk Update Threads" | 0 = auto-detect from CPU cores (recommended default) | Tooltip: *"Using more threads can speed up chunk loading and update speed, but may negatively impact frame times. The default value is usually good enough for all situations."* Only hand-tune down on very low core-count CPUs (2-4 threads total) to leave headroom for the main/render thread. |
| `chunk_build_defer_mode` | "Chunk Updates" | `Deferred` (Sodium's own default) = fastest FPS, most visual lag; `Immediate` = zero visual lag, costs FPS; `Soon` = up to one frame of lag, middle ground | Tooltip: *"If set to 'Deferred', rendering will never wait for nearby chunk updates to finish... it may create significant visual lag where blocks take a while to appear or disappear. 'Immediate' eliminates visual lag... 'Soon' allows at most one frame."* Recommend `Deferred` on low-end tiers, `Immediate`/`Soon` for players doing precision building/PvP/redstone. |
| `use_fog_occlusion` | "Use Fog Occlusion" | Keep **ON** always | Tooltip: *"chunks which are determined to be fully hidden by fog effects will not be rendered... can be more dramatic when fog effects are heavier (such as while underwater)."* Free performance, rare visual-artifact tradeoff at sky/fog boundary. |
| `use_block_face_culling` | "Use Block Face Culling" | Keep **ON** always | Tooltip: *"only the faces of blocks which are facing the camera will be submitted for rendering... greatly improves rendering performance."* Only disable if a specific resource pack shows visible holes in blocks. |
| `quad_splitting_mode` | "Block Transparency Limits" | `Safe` (default) for most; `Basic`/Off only on critical-tier hardware; `Unlimited` only for correctness-sensitive builds with complex intersecting translucent geometry | Tooltip: *"Determines what approach is used to make translucent blocks... look correct even when they intersect or have unusual shapes, such as waterlogged stained glass panes. 'Basic' disables this feature and such blocks may render incorrectly."* This is Sodium's translucency-sorting mechanism, **not** greedy meshing — don't conflate the two. |

### 3.3 Hardware-tier guidance

**Low-end (iGPU, 4-8 GB RAM, laptop on battery):**
- Render distance 6-8 chunks, simulation distance 5-6.
- Graphics preset Fast; Improved Transparency off; See-Through Leaves off; particles Minimal; biome blend off/3×3; entity distance ≤50%; clouds off; entity shadows off; mipmaps 0; texture filtering None.
- `quad_splitting_mode = Basic`, `chunk_build_defer_mode = Deferred`.
- Cap max FPS to the panel's refresh rate (commonly 60 Hz on budget laptops); VSync **on** to cut GPU duty cycle and save battery.
- Enable Dynamic FPS (throttles when unfocused/backgrounded/on battery — directly matches RigTune's battery-state input).
- RAM: allocate no more than ~50% of total system RAM to the JVM heap, and leave the rest for OS + GPU driver + other apps; on an 8 GB machine that's roughly 3-4 GB heap.

**Mid-range (modern iGPU/entry discrete, 8-16 GB RAM):**
- Render distance 10-16 chunks, simulation distance 8-10.
- Fancy preset acceptable; particles Decreased/All; biome blend 3×3-5×5; mipmaps 2-4; AF 4×.
- `chunk_build_defer_mode = Soon` for a balance of responsiveness and FPS.
- Heap: 4-6 GB without shaders, 6-8 GB with a mid shader pack.

**High-end (dedicated GPU RTX/RX mid-to-high tier, 16-32+ GB RAM):**
- Render distance 24-32+, simulation distance 10-12+.
- Fancy/Fabulous acceptable if not using Sodium's own transparency handling; full AF; mipmaps max.
- Shaders (Iris) and/or Distant Horizons viable.
- Heap: 6-8 GB baseline; **with shaders, 8-12 GB**; **with Distant Horizons, add another 2-4 GB on top of whatever baseline** — community guidance for DH specifically: *"6GB is the recommended amount of RAM for Distant Horizons... for vanilla gameplay you should allocate 6-12 GB, and 16-20 GB if using shaders [+DH together]"* ([Distant Horizons FAQ](https://blog.curseforge.com/distant-horizons-frequently-asked-questions/)); VRAM also scales directly with LOD render distance (~4 GB VRAM at LOD radius 1024), so DH's hardware condition should check both system RAM headroom *and* VRAM headroom, not just RAM.

General heap rule of thumb to encode: never allocate more than ~50-60% of total physical RAM to the JVM heap (leaving room for OS, GPU driver-side allocations, and off-heap Java memory such as direct buffers), and never allocate so much heap that GC pause targets exceed frame budget on old CPUs (very large heaps, e.g. 12+ GB, can *increase* stutter on weak CPUs due to longer GC pauses — more heap isn't strictly better).

### 3.4 Sodium's hardware/driver workarounds (source: `CaffeineMC/sodium`)

Confirmed via the official wiki page [`github.com/CaffeineMC/sodium/wiki/Driver-Compatibility`](https://github.com/CaffeineMC/sodium/wiki/Driver-Compatibility) and cross-checked against source-level workaround references (DeepWiki's community-maintained code index of the same repo, treated as a secondary/unofficial cross-check, not a primary source):

| Workaround | Trigger | What Sodium does / what the user should do |
|---|---|---|
| **NVIDIA Threaded Optimizations** (`NVIDIA_THREADED_OPTIMIZATIONS_BROKEN`) | Recent NVIDIA drivers auto-enable a "Threaded Optimizations" hack specifically when they detect the Minecraft process, which — despite the name — causes crashes/stutter with Sodium | No in-process fix is possible; the wiki says the fix is to stop the driver from recognizing the process: on **Windows**, "use a third-party launcher which is known to not be detected by the drivers (such as Prism Launcher)"; on **Linux**, set `__GL_THREADED_OPTIMIZATIONS=0`. RigTune should surface this as an actionable tip when it detects NVIDIA + stutter/crash-prone driver ranges, not something it can silently fix. |
| **NVIDIA outdated drivers** | Any NVIDIA driver older than 536.23 | User must update the driver; RigTune's GPU probe should read the driver version if available and flag "update your NVIDIA driver" for anything older. |
| **AMD Terascale 2 (HD 5000/6000 series)** | Pre-Sodium-0.4.10 rendering bugs on very old AMD GPUs | Resolved by using Sodium 0.4.10+ (moot for 26.2, since only current Sodium is installable there) |
| **AMD Radeon RX Vega on Linux** | Mesa 23.1.1 through 24.0.3 | Update Mesa to 24.0.4+ |
| **AMD Game Optimization** (`AMD_GAME_OPTIMIZATION_BROKEN`) | Recent AMD drivers (reported from the 25.10.2+ line) auto-detect the Minecraft process and apply an "optimization" that breaks terrain rendering (invisible terrain) | Sodium works around this itself, in-process, by masking the process from driver detection — on Windows this is implemented via overwriting the process's PEB command-line so the AMD driver doesn't recognize "minecraft" in the process name (`AmdWorkarounds`/`WindowsCommandLine.setCommandLine()` per the repo's PR #3391, "Add AMD Workarounds"). No user action needed; this is a case RigTune can just report as "known-and-handled" rather than surface as a warning. |
| **Intel Gen7 (Ivy Bridge/Haswell, 2012-2013)** | Windows 10's auto-installed generic driver lacks needed support | User should install Intel's own driver ≥10.18.10.5161 from Intel's download center, not rely on Windows Update |
| **Intel Gen8 or older — framebuffer blit crash** (`INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED`) | `glFramebufferBlit` crashes when the window loses focus on old Intel iGPUs | Sodium works around this internally; also correlates with `INTEL_DEPTH_BUFFER_COMPARISON_UNRELIABLE` (faulty depth-comparison behavior causing Z-fighting) on the same hardware class |
| **Intel Gen11 (Ice Lake, 2019)** | Driver bug causing world flicker on block place/break | User should install the latest driver from Intel's official download center (not the Windows Update version) |
| **`NO_ERROR_CONTEXT_UNSUPPORTED`** | Linux/Wayland sessions on GLFW < 3.4 | Sodium disables "No Error" GL contexts internally to avoid startup crashes |

RigTune's rules generator should encode these as: (a) driver-version floor checks per vendor (NVIDIA ≥536.23; Intel Gen7/Gen11 needing vendor drivers instead of OS-generic ones), (b) informational-only notices for things Sodium already silently works around (AMD game-optimization masking, Wayland no-error-context), and (c) an actionable "switch launcher" tip specifically for the NVIDIA threaded-optimizations case since that one genuinely requires user action outside the game.

---

## 4. GPU tiering

**Method:** classify by GL_RENDERER (or Windows device-name) substring via ordered regex, coarse 1 (critical/software) to 5 (enthusiast) scale. Native OpenGL renderer strings follow vendor-specific formats confirmed via multiple driver-string examples (Arch/Linux Mint/FreeBSD forum threads, Modrinth/community docs):
- NVIDIA native GL: `NVIDIA GeForce RTX 4080 SUPER/PCIe/SSE2`, `GeForce GTX 1080 Ti/PCIe/SSE2`, `NVIDIA GeForce RTX 3070 Laptop GPU/PCIe/SSE2` (note the literal `Laptop GPU` suffix distinguishing mobile parts).
- AMD native GL: typically `AMD Radeon RX 6750 XT` / `Radeon (TM) RX Vega` / `AMD Radeon(TM) Graphics` (iGPU, e.g. Ryzen APUs).
- Intel native GL: `Intel(R) UHD Graphics 630`, `Intel(R) Iris(R) Xe Graphics`, `Intel(R) Arc(TM) A770 Graphics`.
- Apple (macOS native GL, deprecated-but-functional up to GL 4.1, or via Metal/ANGLE translation): renderer strings report the chip name directly, e.g. `Apple M1`, `Apple M2 Pro`.
- Software/no GPU: `llvmpipe (LLVM 13.0.0, 256 bits)` (Mesa's CPU rasterizer) — indicates no real GPU acceleration (missing/broken drivers, a VM, a remote-desktop session without GPU passthrough, or a container).

**Ground truth used for relative ordering:** Sodium's own documented floor of **OpenGL 4.5** (roughly NVIDIA Kepler+/GTX 600-series+, AMD GCN1+/HD 7000-series+, Intel Gen8+/Broadwell-series+ — anything older is "critical: unsupported/will not run Sodium" per [Sodium's Modrinth description](https://modrinth.com/mod/sodium)) as the tier-1 floor; general relative-performance ranking of GPU generations from [Tom's Hardware's GPU benchmarks hierarchy](https://www.tomshardware.com/reviews/gpu-hierarchy,4388.html) (a continuously-updated ranked list, used here only for coarse generational ordering, not exact FPS numbers — the live fetch of that page did not return its data table content, so treat the ranking below as corroborated by well-established, stable generational knowledge rather than a scraped table) and a discrete-vs-integrated relative-performance spot-check via [technical.city's Arc B580/B570 vs. Iris Xe/UHD comparisons](https://technical.city/en/video/Iris-Xe-Graphics-G7-vs-Arc-B580) (Arc B580 reported ~300-640% higher aggregate score than Intel's older integrated parts, confirming discrete Arc belongs in a materially higher tier than any Intel iGPU).

**Proposed compact regex table** (evaluate top-to-bottom, first match wins; tier 1 = critical/software, 5 = enthusiast):

```text
# Tier 0 — critical / software rendering (no real GPU accel; warn loudly, most mods will underperform or fail)
/llvmpipe|softpipe|SWR|Microsoft Basic Render|D3D12 \(Microsoft Basic Render/i  -> tier 0

# Tier 1 — pre-GL4.5 / unsupported by Sodium, or very weak iGPU
/Intel.*(HD Graphics [2-5][0-9]{2}\b|GMA)/i                                    -> tier 1   # Gen6/7 HD 2000-4000/5xxx-era
/GeForce (8|9|1[0-9]0|2[0-9]0|3[0-9]0)M?\b/i                                    -> tier 1   # pre-Kepler GeForce
/Radeon HD [1-6][0-9]{3}/i                                                      -> tier 1   # pre-GCN Radeon HD

# Tier 2 — entry / older or weak iGPU, budget mobile
/Intel.*(HD Graphics 5[0-9]{2}|UHD Graphics 6[0-9]{2})/i                       -> tier 2   # UHD 610/620/630 class
/Radeon\(TM\)? Graphics/i                                                       -> tier 2   # generic AMD APU iGPU (Vega/RDNA iGPU strings collide; refine w/ CPU model if available)
/GeForce (GTX )?9[0-9]{2}M?\b/i                                                 -> tier 2   # GTX 900-series (Maxwell)
/Radeon RX (4[0-9]{2}|5[0-9]{2})\b/i                                            -> tier 2   # RX 400/500 (Polaris)
/GeForce GTX 10[0-5][0-9]\b/i                                                   -> tier 2   # GTX 1030-1050 Ti (entry Pascal)

# Tier 3 — mainstream mid-range
/Iris\(R\)? Xe Graphics/i                                                       -> tier 3   # modern Intel iGPU
/GeForce GTX 10[6-8][0-9]\b/i                                                   -> tier 3   # GTX 1060-1080 (Pascal mid/high)
/GeForce (GTX )?16[0-6][0-9]\b/i                                                -> tier 3   # GTX 1650-1660 (Turing no-RT)
/GeForce RTX 20[6-8][0-9]\b/i                                                   -> tier 3   # RTX 2060-2080 (Turing)
/Radeon RX (Vega|5[0-7][0-9]{2}|6[0-6][0-9]{2})\b/i                             -> tier 3   # Vega, RX 5000/lower 6000 (RDNA1/2)
/Arc\(TM\)? A(3[0-9]{2}|5[0-9]{2})/i                                            -> tier 3   # Arc A380/A580 (entry Alchemist)
/Apple M[1-3]\b(?! (Pro|Max|Ultra))/i                                           -> tier 3   # base Apple M1-M3

# Tier 4 — high-end
/GeForce RTX 30[6-9][0-9]\b/i                                                   -> tier 4   # RTX 3060-3090 (Ampere)
/GeForce RTX 40[6-8][0-9]\b/i                                                   -> tier 4   # RTX 4060-4080 (Ada, non-flagship)
/Radeon RX (6[7-9][0-9]{2}|7[0-8][0-9]{2}|8[0-8][0-9]{2})\b/i                   -> tier 4   # RX 6700-7800/8800-class (RDNA2/3/4 upper-mid)
/Arc\(TM\)? [AB](7[0-9]{2}|5[0-8][0-9])/i                                       -> tier 4   # Arc A750/A770, B570/B580 (Battlemage)
/Apple M[1-4]\s?(Pro|Max)\b/i                                                   -> tier 4

# Tier 5 — enthusiast / flagship
/GeForce RTX (40(90)|5[0-9]{3})\b/i                                             -> tier 5   # RTX 4090, RTX 50-series
/Radeon RX (79[0-9]{2}( XTX)?|8900|9[0-9]{3})\b/i                               -> tier 5   # RX 7900 XTX-class, RX 8900/9000 flagships
/Apple M[1-4] Ultra\b/i                                                         -> tier 5

# Fallback
default -> tier unknown (treat as tier 2, conservative middle-low, and prompt the benchmark to self-calibrate)
```

Notes for the RigTune implementation:
- Laptop GPUs generally carry a literal `Laptop GPU` (NVIDIA) or lower clock/lower-tier naming (AMD/Intel) — detect the `Laptop GPU` substring separately and apply a mild tier-down adjustment (typically one tier lower effective performance than the desktop part of the same numeric name) rather than a separate table, since the numeric-model regexes above already match the substring regardless of the `Laptop GPU` suffix.
- AMD "Radeon Graphics" as a bare string is ambiguous between different APU generations (Vega/RDNA2/RDNA3 iGPUs share nearly the same renderer string) — if RigTune also has the CPU model string (e.g. Ryzen 5 5600G vs Ryzen 7 8700G), cross-reference CPU generation to disambiguate; regex alone is insufficient there. This is a known coarse-tiering limitation, not a bug to over-engineer around.
- Software rendering (tier 0) should be a **critical warning**, not just a low tier — it usually means broken/missing GPU drivers, a VM/remote session, or (on Windows) a "Microsoft Basic Render Driver" fallback because the real GPU driver failed to load, and no amount of mod/settings tuning fixes it.
- Keep the table intentionally coarse (5-6 buckets) per the task's own instruction — precise FPS-per-model data isn't necessary for RigTune's recommendation tiers and would require a maintained, constantly-stale lookup table instead of a small regex list.

---

## 5. Modrinth terms

Read live from [`modrinth.com/legal/terms`](https://modrinth.com/legal/terms) and [`docs.modrinth.com/api/`](https://docs.modrinth.com/api/) on 2026-09-24.

**Can a mod, at the user's explicit click, download files from the Modrinth CDN into the mods folder? Yes.** This is exactly the intended use of the public API and is precedented by numerous existing third-party tools that do precisely this (the official Modrinth App itself, plus community launchers/CLIs like Prism Launcher, ATLauncher, `ferium`, GDLauncher, and packwiz-based installers — which is literally the mechanism FO/Additive use, per §1). The Terms of Use's **API Usage** section states:

> "The Modrinth API enables users to perform actions on the Service via their own services, such as their websites and/or applications."

and grants:

> "a limited, non-exclusive, non-sublicensable and revocable license to download, display, query, create, edit, and delete the User Generated Content on the Service via their own services, such as their websites and/or applications"

conditioned on:

> "The User uses the Modrinth API in accordance with these Terms, the Community Standards and all applicable laws and regulations" and "the User does not infringe any rights of third parties."

The ToU's **Prohibited Uses** section separately bars unauthorized scraping/bot access ("Use any robot, spider or other automatic device, process or means to access the Service for any purpose, including monitoring or copying any of the material contained in the Service") and, notably, bars using Modrinth content to **train AI/ML models** — irrelevant to RigTune's use case (rule generation from structured mod-list data, not model training), but worth remembering as a hard boundary if RigTune's pipeline ever considers using Modrinth data for anything ML-adjacent. The robot/spider clause is squarely aimed at *unauthorized* scraping of the website outside the API; the API section is the carve-out that legitimizes RigTune's use as long as it goes through the documented API/CDN rather than scraping `modrinth.com` pages directly. This reading is reinforced by the fact that Modrinth actively documents and supports exactly this kind of third-party download tooling in its own docs.

**Rate limits and User-Agent obligations** (from `docs.modrinth.com/api/`, confirmed live):

- **Rate limit: 300 requests per minute**, identical whether authenticated or not. Response headers `X-Ratelimit-Limit`, `X-Ratelimit-Remaining`, `X-Ratelimit-Reset` should be read and respected/backed off on by RigTune's rules-generation job.
- **User-Agent is required and must be uniquely identifying** — a generic HTTP-client string (e.g. `okhttp/4.9.3`) "increases the likelihood that we will block your traffic." Recommended format, worst-to-best:
  - Bad: `okhttp/4.9.3`
  - OK: `project_name`
  - Better: `github_username/project_name/1.56.0`
  - Best: `github_username/project_name/1.56.0 (contact@example.com)` — including contact info specifically so Modrinth can reach out about a behavior change *before* blocking, rather than blocking first.
  - The task brief's proposed `rigtune-research/0.1 (charlie.wh345 at gmail)` follows this pattern correctly; for the production scheduled job, use the same shape with the real project repo name and a maintained contact, e.g. `RigTune/rigtune-rules-generator/<version> (contact@rigtune.example)`.
- **Authentication is optional** for the read-only endpoints RigTune needs (project/version lookups) — "You do not need a token for most requests." Tokens are only required for write operations RigTune doesn't perform (creating/editing/deleting projects).
- **No explicit attribution clause** was found in the ToU for merely *querying/displaying* project data (e.g., showing a mod's name/description pulled from the API) — Modrinth doesn't impose a CC-style attribution requirement on API consumers in the Terms text as fetched. As a courtesy and to stay unambiguously within "does not infringe any rights of third parties," RigTune should still credit "Sodium," "Lithium," etc. as their respective mod authors' names/works when displaying them in UI, which is standard practice and avoids any implication that RigTune authored the mods itself.
- Direct `cdn.modrinth.com/data/...` file downloads (the URLs embedded in the `.pw.toml` files in §1) are a **separate path from the API** and not subject to the same 300 req/min API rate limit header contract, but should still be fetched with a proper, identifying User-Agent and reasonable concurrency/backoff — being a good API citizen generally, even off the strict API surface, is consistent with the ToU's "does not infringe" and general acceptable-use framing.

**Bottom line for RigTune's design:** the "click to install, download mod files into the mods folder" flow is squarely permitted, provided RigTune (a) uses a uniquely-identifying, contactable User-Agent on every request (API and CDN), (b) respects the 300 req/min rate limit with backoff on `X-Ratelimit-*`, (c) only performs actions "on behalf of" the user in response to the user's explicit action (matches the task's own framing — "at the user's explicit click"), and (d) doesn't scrape `modrinth.com` HTML pages directly (use the API instead) or use the fetched data to train ML models.

---

## Sources consulted (primary, fetched live 2026-09-24)

- `github.com/Fabulously-Optimized/fabulously-optimized` — branches, `Packwiz/26.2/` and `Packwiz/26.3/` trees, `LICENSE.md`, `INCLUDED-MODS.md`, `pack.toml`/`index.toml`/`*.pw.toml` samples
- `github.com/skywardmc/additive` — branches, `versions/fabric/26.2/` tree, `LICENSE`, `README.md`, `pack.toml`/`*.pw.toml` samples
- `api.modrinth.com/v2/project/{slug}` and `.../version?game_versions=[...]&loaders=["fabric"]` for ~30 mod slugs
- `github.com/CaffeineMC/sodium` (`dev` branch) — `SodiumOptions.java`, `QuadSplittingMode.java`, `DeferMode.java`, `en_us.json`
- [`github.com/CaffeineMC/sodium/wiki/Driver-Compatibility`](https://github.com/CaffeineMC/sodium/wiki/Driver-Compatibility)
- [`minecraft.wiki/w/Options`](https://minecraft.wiki/w/Options), [`minecraft.wiki/w/Java_Edition_26.2`](https://minecraft.wiki/w/Java_Edition_26.2)
- [`fabricmc.net/2026/06/15/262.html`](https://fabricmc.net/2026/06/15/262.html) (Fabric for Minecraft 26.2)
- [`modrinth.com/legal/terms`](https://modrinth.com/legal/terms), [`docs.modrinth.com/api/`](https://docs.modrinth.com/api/)
- [`blog.curseforge.com/distant-horizons-frequently-asked-questions/`](https://blog.curseforge.com/distant-horizons-frequently-asked-questions/), [`irisshaders.org/guides/iris-shaders-with-sodium/`](https://irisshaders.org/guides/iris-shaders-with-sodium/), [`nvidium.org`](https://nvidium.org/nvidium-compatibility-with-iris-does-it-work/), [`github.com/xCollateral/VulkanMod/wiki/Incompatible-mods`](https://github.com/xCollateral/VulkanMod/wiki/Incompatible-mods)
- Secondary/community cross-checks (not relied on alone for any factual claim): DeepWiki's AI-generated index of the Sodium repo (used only to corroborate, and cross-checked against the primary source files listed above); Tom's Hardware GPU hierarchy (used for generational ordering only, page content could not be fully retrieved) and `technical.city` GPU comparisons (used for the Arc-vs-iGPU tiering sanity check).
