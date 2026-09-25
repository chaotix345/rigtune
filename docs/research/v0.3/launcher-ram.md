# Launcher-aware RAM advice — research (P1 item 5)

Research only. Nothing in `rules/` or `src/` was edited. Access date for every citation below unless
otherwise noted: **2026-09-26**.

## 0. Current behaviour (recap)

RigTune does **no launcher detection today**. RAM/heap handling is:

- `src/client/java/io/github/chaotix345/rigtune/client/probe/HardwareProbe.java:67` builds the
  `HardwareProfile` with `Runtime.getRuntime().maxMemory() / MIB` as `maxHeapMb` — i.e. it reads the
  JVM's *actual* configured max heap (whatever `-Xmx` the launcher passed, after JVM ergonomics),
  not a launcher config file.
- `core/hardware/TierCalculator.heapTier` buckets that value against `rules.heapTiers`
  (`knowledge.json`) into a memory tier; `core/rules/ConditionEvaluator.java:86` exposes it to rules
  as `heapMbAtLeast`/`heapMbAtMost`.
- `rules/source/knowledge.json` lines 550-623 already contain six RAM advice rules keyed off
  `heapMbAtMost/AtLeast` + `ramMbAtMost/AtLeast` + `modPresent:["distanthorizons"]` +
  `flags:["shaders-enabled"]` (`ram-low`, `ram-low-system`, `ram-too-much`, `ram-huge`,
  `ram-distant-horizons`, `ram-shaders`, `ram-distant-horizons-shaders`,
  `ram-distant-horizons-shaders-limited`, `ram-distant-horizons-low-system`). Wording is generic:
  *"Set it to 4 GB in your launcher's Java or memory settings."* — no launcher is ever named, no
  click-path is ever given, no on-disk value is ever read (only `Runtime.maxMemory()`). This P1 item
  is about making that wording launcher-specific; the thresholds themselves are already reasonable
  (see §3, they're corroborated by external sources below) and don't need to change.
- `core/model/HardwareProfile.java` is a record; `maxHeapMb`/`totalRamMb` are its only memory fields.
  There is currently no field for "detected launcher" anywhere in the model.

## 1. Detecting the launcher from inside the running JVM

### Ground truth from this machine (read-only inspection)

Only **Modrinth App** (v0.21.5, per `app_metadata.app_version`) is installed on this dev machine —
checked `%APPDATA%`, `%LOCALAPPDATA%`, `Program Files`, `Program Files (x86)` for Prism/MultiMC,
CurseForge, ATLauncher, GDLauncher, Overwolf: none found. All Modrinth files below were **copied
read-only** into the scratchpad before inspection; nothing in `%APPDATA%\ModrinthApp` was written,
renamed, or executed.

- Profile dir: `C:\Users\Admin\AppData\Roaming\ModrinthApp\profiles\Fabric 26.2\` — this *is* the
  Fabric `gameDir` (contains `mods/`, `config/`, `saves/`, `options.txt`, etc., confirmed by
  `debug-profile.json` and `logs/latest.log` sitting there).
- `%APPDATA%\ModrinthApp\app.db` is a SQLite DB (Tauri/Rust app, internal codename **"theseus"** —
  see below). Table `settings`, column `mc_memory_max` (**global** max-memory setting, MB, integer):
  schema default `'2048'`; **this user's actual current value is `6144`** (they raised it from the
  2048 MB ship default). Table `instances` has no memory column — one instance exists
  (`Fabric 26.2`); table `instance_launch_overrides.overrides` (msgpack blob) for that instance
  contains only `hooks` and `visible_tabs` keys, **no memory override key present**, i.e. this
  profile inherits the global `6144`. This confirms: global setting is the default source of truth,
  per-instance override is optional/opt-in and schema-supported but unused here.
- No `-Xmx`/`-Xms`/launcher-brand strings are printed in `logs/latest.log` or `logs/launcher_log.txt`
  (Fabric doesn't echo JVM invocation args to its own log) — the source-code confirmation below
  (`args.rs`) is the reliable path, not log-scraping.

### Signals, ranked by reliability

**1 — `System.getProperty` self-identification (HIGH reliability, near-zero false-positive rate).**
Real, documented JVM args: `-Dminecraft.launcher.brand=<name>` ("Sets the name of the launcher.
Included in crash reports and telemetry") and `-Dminecraft.launcher.version=<version>` ("Ignored by
the game") — confirmed on [Minecraft Wiki: Java Edition client command line arguments](https://minecraft.wiki/w/Java_Edition_client_command_line_arguments).
Verified per-launcher values, all from source:

| Launcher | `minecraft.launcher.brand` value | Source |
|---|---|---|
| Modrinth App | `"theseus"` (literal) | `packages/app-lib/src/launcher/args.rs:249` — `.replace("${launcher_name}", "theseus")` [github.com/modrinth/code](https://github.com/modrinth/code) |
| ATLauncher | `"ATLauncher"` (literal, `Constants.LAUNCHER_NAME`) | `src/main/java/com/atlauncher/constants/Constants.java:57`; substituted in `MCLauncher.java:481` [github.com/ATLauncher/ATLauncher](https://github.com/ATLauncher/ATLauncher) |
| Prism Launcher | build-configured `BuildConfig.LAUNCHER_NAME` (expected `"PrismLauncher"` for official builds; template is `@Launcher_Name@` at `buildconfig/BuildConfig.cpp.in:44`) | `libraries/launcher/org/prismlauncher/SystemProperties.java` sets it from a `launcherBrand` param written by `MinecraftInstance.cpp:872` (`"launcherBrand " + BuildConfig.LAUNCHER_NAME`) [github.com/PrismLauncher/PrismLauncher](https://github.com/PrismLauncher/PrismLauncher). **Exact compiled string not independently re-derived this session** — treat as UNVERIFIED-exact-literal, but the mechanism and the stronger Prism-specific properties below make the exact brand string unnecessary. |
| GDLauncher Carbon | mechanism confirmed (`-Dminecraft.launcher.brand=${launcher_name}`, `crates/carbon_app/src/domain/minecraft/minecraft.rs:402`), **resolved literal value UNVERIFIED** (didn't locate the `ArgPlaceholder::LauncherName → String` mapping in this session) | [github.com/gorilla-devs/GDLauncher-Carbon](https://github.com/gorilla-devs/GDLauncher-Carbon) |
| Official Minecraft Launcher | UNVERIFIED exact literal. Minecraft Wiki's own page describes the placeholder as launcher-name-dependent but doesn't quote Mojang's literal string for the official launcher; third-party summaries mention OS-suffixed names (e.g. "windows-launcher") but I could not confirm this from a primary source this session. | — |
| CurseForge app | **UNVERIFIED whether it sets this property at all** — closed-source (Overwolf-based); no evidence found either way. | — |

Because two of six brand values are unverified/unknown, **do not gate detection on a hardcoded
allowlist of brand strings alone** — treat `minecraft.launcher.brand` as corroborating evidence, and
fall back to the launcher-specific properties/files below for the closed/uncertain cases.

**2 — Prism/MultiMC-family self-identifying properties (HIGH reliability, Prism-specific).**
Verified in `libraries/launcher/org/prismlauncher/SystemProperties.java` (Prism's own in-JVM launch
wrapper, i.e. code that runs *inside* the game process before Minecraft's `main()`): it additionally
sets `org.prismlauncher.instance.name`, `org.prismlauncher.instance.icon.id`,
`org.prismlauncher.instance.icon.path`, `org.prismlauncher.window.title` — and, **"for compatibility"**,
also sets the legacy `multimc.instance.title` / `multimc.instance.icon` properties that the original
MultiMC5 used. This is independently corroborated: a third-party mod's source comment
(`Team-Resourceful/ResourcefulLib LauncherInfo.java`) explicitly lists
`System.getProperty("org.prismlauncher.instance.name")` and
`System.getProperty("multimc.instance.title")` as the two strings it deliberately avoids surfacing
for privacy reasons — i.e. real mod authors already treat these as reliable Prism/MultiMC-family
fingerprints. **Recommendation: check `org.prismlauncher.instance.name` first (Prism-specific), then
`multimc.instance.title` as a broader MultiMC-family catch (covers old MultiMC5 and untested forks
like PollyMC, which has its own `libraries/launcher/org/pollymc/SystemProperties.java` mirroring this
pattern per a GitHub code search hit — not independently opened this session).**

**3 — Prism/MultiMC `INST_*` environment variables (HIGH reliability, and a specific open question
from the task is now resolved).** `MinecraftInstance::getVariables()` sets `INST_NAME`, `INST_ID`,
`INST_DIR`, `INST_MC_DIR`, `INST_JAVA`, `INST_JAVA_ARGS`
(`launcher/minecraft/MinecraftInstance.cpp:663-671`). **Confirmed these reach the actual game
process, not just pre-launch/wrapper scripts**: `createEnvironment()` (line 683) wraps
`getVariables()`, `createLaunchEnvironment()` (line 708) wraps that again, and
`launcher/minecraft/launch/LauncherPartLaunch.cpp:101` calls
`m_process.setProcessEnvironment(instance->createLaunchEnvironment())` — `LauncherPartLaunch` is the
step that actually spawns the Minecraft JVM. So a Fabric mod can call
`System.getenv("INST_NAME")` / `System.getenv("INST_ID")` directly and it will be non-null under
Prism (and, by shared-codebase inheritance, MultiMC5/PollyMC/other forks that didn't strip this).

**4 — Marker files in/above the game dir (MEDIUM-HIGH reliability, requires walking up from
`FabricLoader.getInstance().getGameDir()`, verified API on
[maven.fabricmc.net javadoc](https://maven.fabricmc.net/docs/fabric-loader-0.15.11/net/fabricmc/loader/api/FabricLoader.html)).**
- **Prism**: `<instance dir>/instance.cfg` (INI) one level above the game dir (`.minecraft`/`minecraft`
  subfolder), with keys **`MaxMemAlloc`** / **`MinMemAlloc`** (legacy aliases `MaxMemoryAlloc`/
  `MinMemoryAlloc`), registered with defaults `512` (min) and `SysInfo::defaultMaxJvmMem()` (max, a
  computed OS-RAM-based default — exact formula UNVERIFIED, not re-derived) —
  `launcher/minecraft/MinecraftInstance.cpp:749-751`. `mmc-pack.json` sits alongside it (component
  list) and is a secondary corroborating signal.
- **CurseForge**: `minecraftinstance.json` sits in the modpack instance root (same dir as, or the
  immediate parent of, the Fabric `gameDir`, depending on pack layout). Verified real-world format
  against public repos, e.g. `EnigmaticaModpacks/CreateTogether/minecraftinstance.json`:
  `{"javaArgsOverride": null, "javaDirOverride": null, "isMemoryOverride": false,
  "allocatedMemory": 7008, ...}` — keys **`isMemoryOverride`** (bool) and **`allocatedMemory`** (int,
  MB) are exactly the per-instance override flag/value. When `isMemoryOverride` is `false` the
  effective value is the app's *global* Java setting, whose on-disk location is UNVERIFIED (not
  observed; the app is closed-source, no real install available on this machine).
- **Modrinth App**: no marker file needed *inside* the game dir — the distinguishing structural
  signal is the path itself (`…\ModrinthApp\profiles\<name>\`, verified against this machine's real
  install), but property #1 (`minecraft.launcher.brand=="theseus"`) is simpler and doesn't depend on
  folder-name assumptions a user could break by choosing a custom install location.
- **Official launcher**: no marker file at all beyond the historical `%APPDATA%\.minecraft` default
  path and `launcher_profiles.json` sitting there (long-standing community-documented format, not
  re-verified against Mojang's own docs this session — treat as UNVERIFIED for the current, post
  Vulkan-rewrite launcher).

**5 — Game-dir path pattern alone (LOW-MEDIUM reliability, corroborating only).** All of the above
launchers let the user relocate instances/profiles, so a bare path substring match
(e.g. `"ModrinthApp"`, `"PrismLauncher"`, `"CurseForge"` appearing somewhere in the absolute gameDir
path) is a reasonable *tie-breaker* but must never be the sole signal — a renamed folder or symlink
silently defeats it, with no error.

**6 — `ProcessHandle.current().parent()` (LOW reliability, last-resort corroborating signal only).**
Java 9+ API; per the JDK's own docs, `ProcessHandle.Info.command()` is explicitly best-effort and can
return empty depending on OS permissions, and Tauri/Electron/Qt launchers vary in whether they stay
the JVM's direct OS parent (some launchers exit immediately after spawning, some reparent to a shell
wrapper). Not independently benchmarked against each launcher this session — treat as a weak
fallback, never a primary check, and never let a failure/empty result throw.

### Proposed detection order

1. `System.getProperty("org.prismlauncher.instance.name")` or `System.getProperty("multimc.instance.title")` non-null → **Prism / MultiMC-family**. Read `INST_ID`/`INST_NAME`/`INST_MC_DIR` env vars for extra display detail if wanted (all confirmed reachable in the game process, see signal 3).
2. `System.getProperty("minecraft.launcher.brand")`: `"theseus"` → **Modrinth App**; `"ATLauncher"` → **ATLauncher**; anything else known-but-unverified (Prism/GDLauncher) → treat as corroborating only, don't hard-branch on it.
3. Walk up from `FabricLoader.getInstance().getGameDir()` (a few levels, capped) looking for `instance.cfg`+`mmc-pack.json` (→ Prism/MultiMC, corroborates #1) or `minecraftinstance.json` (→ **CurseForge app**, and read `allocatedMemory`/`isMemoryOverride` from it while there).
4. Path substring fallback (`ModrinthApp`, `CurseForge`, `PrismLauncher`, `ATLauncher`, `gdlauncher`) as a last structural tie-breaker.
5. If gameDir path resolves to (or near) `%APPDATA%\.minecraft` and nothing above matched → **official launcher** (weak, default-path-only inference).
6. Otherwise → **Unknown launcher** → generic advice, no click-path claimed, no on-disk value read — only `Runtime.getRuntime().maxMemory()` (today's behaviour, unchanged).

**False-positive/negative behaviour**: every check above must be wrapped so a missing property/file
/env-var is just "not this launcher," never an exception (all are individually nullable/optional in
their real source). False positives are structurally near-impossible for signals 1-3 (deliberately
unique strings); the only realistic false-positive vector is signal 4/5 (path/marker-file
coincidence), which is why they're ranked below the self-identifying properties and should only
*add* confidence, never independently trigger a "confirmed launcher" state on their own for the
closed-source launchers (CurseForge, official) where no self-identifying property is confirmed.
False negatives (launcher updates renaming a property, a user launching Fabric from a raw command
line, an unlisted launcher) must all resolve to **Unknown → today's generic wording**, never to a
guess at the wrong launcher.

## 2. Per-launcher click-steps, storage, and readability

| Launcher | Global setting (UI path) | Per-instance override (UI path) | On-disk storage | Readable by running game? |
|---|---|---|---|---|
| **Modrinth App** | Settings (gear) → Minecraft section → memory field/slider. *(UI label wording sourced from third-party how-tos — [Nodecraft](https://nodecraft.com/support/games/minecraft/mods/how-to-use-the-modrinth-launcher), [BisectHosting](https://help.bisecthosting.com/hc/en-us/articles/40411807520667-How-to-Allocate-More-RAM-in-the-Modrinth-Launcher) — not Modrinth's own screenshots; verify exact label text against the live app before shipping copy that quotes it.)* | Per-profile "override" toggle (schema-confirmed: `instance_launch_overrides` table exists per-instance; this machine's one instance has no memory override set, so it wasn't observed in use). | `%APPDATA%\ModrinthApp\app.db` (SQLite) → `settings.mc_memory_max` (global, MB int, **ship default `2048`**, verified schema default); per-instance override key name in `instance_launch_overrides.overrides` (JSON) not observed (inferred only). | **Yes, trivially** — Modrinth passes the resolved value straight through as `-Xmx{maximum}M` (`args.rs:162`), so `Runtime.getRuntime().maxMemory()` already *is* the configured value. No DB read needed. |
| **Prism Launcher** | Settings → **Java** page → **Memory** group → **"Minimum memory allocation"/"Maximum memory allocation"** spin-boxes. [prismlauncher.org/wiki/help-pages/launcher-settings](https://prismlauncher.org/wiki/help-pages/launcher-settings/) | Right-click instance → Edit Instance → Settings tab → override toggle → same field names, scoped to the instance. | `<instance dir>\instance.cfg`, keys `MaxMemAlloc`/`MinMemAlloc` (verified, see §1 signal 4). | Yes, same logic — Prism launches with `-Xmx<MaxMemAlloc>` (standard MultiMC-family behaviour; exact arg-construction line not re-derived this session but this is long-established behaviour), so `Runtime.maxMemory()` reflects it. `instance.cfg` can additionally be parsed directly for the raw configured integer if wanted. |
| **CurseForge app** | Gear icon (bottom-left) → **Minecraft** tab → **Java Settings** → **allocated memory slider**. [blog.curseforge.com](https://blog.curseforge.com/how-to-allocate-more-ram-to-minecraft/) *(closed-source; label wording corroborated by 5+ independent hosting-provider guides, not source strings — same caveat as Modrinth.)* | My Modpacks → hover pack → "⋮" → **Profile Options** → toggle **Custom RAM Allocation** → slider. | `minecraftinstance.json` in the instance root: `isMemoryOverride` (bool), `allocatedMemory` (int MB) — verified real-world format (§1 signal 4). Global-default storage location UNVERIFIED (no real install available). | Yes indirectly (value becomes the real `-Xmx`); reading `minecraftinstance.json` is only useful to know *whether* this pack has a custom value vs. inheriting the app default, not to get the effective number (which `Runtime.maxMemory()` already has). |
| **Official Minecraft Launcher** | Installations tab → select install → "⋮" → Edit → **More Options** → **JVM Arguments** free-text field (edit the `-Xmx` token directly — no dedicated GB slider). Corroborated by multiple 2026 how-to guides; not confirmed against a Mojang primary source this session. | N/A — each installation *is* the per-instance unit; no separate global default. | `%APPDATA%\.minecraft\launcher_profiles.json`, historically a per-profile `javaArgs` string — **UNVERIFIED for the 2026 launcher** (not re-fetched this session; Mojang's Jul 2026 requirements/engine rewrite, see §3, makes me reluctant to assume the pre-rewrite format still holds without re-checking). | Yes via `Runtime.maxMemory()` regardless (no launcher-specific parsing is reliable here anyway, since even the storage format is unverified). |
| **ATLauncher** | Settings → **Java/Minecraft** tab → **Maximum Memory/RAM** field. Verified against source: `JavaSettingsTab.java`, `JavaSettingsViewModel.java`. | Instance → Edit → **Java/Minecraft** tab (`JavaInstanceSettingsTab.java`) → same field, instance-scoped. | Per-instance config via `InstanceLauncher.java`/`Instance.java`; exact on-disk filename/JSON key not re-opened this session (found the source files, didn't read them) — largely verified via source *file names*, not byte-for-byte confirmed. | Yes via `Runtime.maxMemory()`. Detection bonus: ATLauncher is the one launcher besides Modrinth with a **fully confirmed** `minecraft.launcher.brand` literal (`"ATLauncher"`, §1). |
| **GDLauncher Carbon** *(optional per task)* | Settings → **Java** → **Java Memory** slider. [gdlauncher.com/guides/allocate-more-ram](https://gdlauncher.com/guides/allocate-more-ram/) | Right-click instance → Settings → **Instance Java Memory** row → toggle on → slider. | Rust/Tauri app (`crates/carbon_app`); exact config file/DB not located this session. | Yes via `Runtime.maxMemory()`. |

## 3. Safe recommended RAM value policy

**Inputs available to RigTune already**: `totalRamMb`, `maxHeapMb` (= `Runtime.maxMemory()`), mod
count (`FabricLoader.getAllMods().size()`, not re-verified this session but standard Fabric API),
Distant Horizons presence (`modPresent:["distanthorizons"]`, already used), shaders/Iris
(`flags:["shaders-enabled"]`, already used). **64-bit JVM is a non-issue**: Java 25/26.2+ desktop
builds are 64-bit-only, so no probe is needed — this input can be dropped from the policy rather than
detected.

**Headline: the existing `knowledge.json` thresholds (§0) are directionally correct and
independently corroborated** — this section mostly validates them rather than proposing new numbers.

- **Official launcher default ≈ 2 GB** (`-Xmx2G`) — corroborated by multiple independent 2026 guides
  (not a single primary Mojang source, see §2 caveat), consistent with the existing `ram-low` rule's
  "about 2 GB of memory or less" framing.
- **Modrinth App default = 2048 MB = 2 GB** — **verified directly from this machine's live
  `app.db`** (`settings.mc_memory_max` schema default `'2048'`).
- **Vanilla/lightly-modded baseline: 3-4 GB.** Matches Sodium/Iris community guidance ("3-4 GB is
  enough for vanilla with shaders" — search-aggregated from multiple current shader-setup guides)
  and a 2026 RAM-allocation explainer's stated baseline ("3-4GB for vanilla").
- **Curated modpack: 5-6 GB.** Matches existing `ram-shaders`/`ram-distant-horizons` 6 GB threshold.
- **Kitchen-sink/heavy modpack: 8-10 GB.** Matches existing `ram-huge` rule's "6 to 8 GB is plenty"
  framing (RigTune's own dev instance has 163 mods and runs at 6 GB per this machine's real config —
  consistent, anecdotal only).
- **Distant Horizons: baseline + 2-4 GB.** Directly matches the existing `knowledge.json` wording
  ("per its FAQ, needs roughly 2 to 4 GB more than vanilla") — cross-validated against an independent
  DH FAQ compilation found via search (4 GB minimum/6 GB recommended/10 GB "GC caution" for heavy
  terrain-gen mods), i.e. the existing copy's FAQ citation checks out.
- **G1GC over-allocation is a real, documented downside, not just a "why not just max it out"
  hand-wave**: a larger heap lets more garbage accumulate before a collection cycle triggers, so when
  it does run it has more to walk in one pause — longer, more noticeable stutters, not fewer. This
  directly supports the existing `ram-huge` rule ("12 GB or more can make memory clean-up pauses
  longer on older CPUs"). One aggregator source also claims G1GC pauses of 50-100ms vs ZGC's sub-1ms
  at 12GB+ heaps on Java 21 — directionally consistent but not independently re-derived/benchmarked
  this session, treat the specific ms figures as UNVERIFIED illustrative numbers, not something to
  quote verbatim in user-facing copy.
- **Leave for the OS**: existing `ram-low-system` rule (don't exceed ~half of RAM when system RAM <
  6 GB) and `ram-too-much` (3-4 GB is enough on an 8 GB PC, i.e. leave ~4-5 GB for OS/GPU driver) are
  consistent with general guidance found across sources (leave 4 GB+ free on 16 GB systems, roughly
  half on smaller ones). No change proposed.
- **Ceiling, regardless of available RAM**: no existing rule recommends past 8 GB even implicitly
  (the DH+shaders 16 GB+ case tops out at "about 8 GB" in `ram-distant-horizons-shaders`) — this
  matches the "allocate what you actually use, not the largest number your system allows" principle
  from the G1GC research above. **No change proposed**; a launcher-aware rewrite should keep this
  ceiling rather than scale further just because a machine has 64 GB+.

**Table of examples** (aligned to existing `knowledge.json` thresholds, not new numbers):

| System RAM | Vanilla/light | Curated modpack | + Distant Horizons | + Shaders | + DH + Shaders |
|---|---|---|---|---|---|
| 8 GB | 3-4 GB | (not realistic — too little headroom) | capped ~4 GB (existing `ram-distant-horizons-low-system`) | avoid | avoid |
| 16 GB | 4-6 GB | 6 GB | 6 GB (existing `ram-distant-horizons`) | 6 GB (existing `ram-shaders`) | 6 GB, capped (existing `ram-distant-horizons-shaders-limited`) |
| 32 GB+ | 6-8 GB | 6-8 GB | 6-8 GB | 6-8 GB | 8 GB (existing `ram-distant-horizons-shaders`) |

## 4. Wording drafts

**Too little, launcher known** (e.g. Prism):
> **Title**: "Give Minecraft more memory in Prism"
> **Text**: "Prism is letting Minecraft use about {X} GB, which causes stutter as the game keeps
> freeing memory. Open Prism → right-click this instance → Edit Instance → Settings → Java → set
> Maximum memory allocation to {Y} GB."

(Same pattern per launcher, substituting the §2 click-path: Modrinth App → "Settings → Minecraft →
memory slider"; CurseForge → "gear icon → Minecraft → Java Settings" or, if this pack has
`isMemoryOverride`, "My Modpacks → this pack → ⋮ → Profile Options"; ATLauncher → "Settings →
Java/Minecraft tab"; official launcher → "Installations → Edit → More Options → JVM Arguments".)

**Too much, launcher known**:
> **Title**: "{Launcher} is giving Minecraft more memory than it needs"
> **Text**: "{Launcher} is giving Minecraft {X} GB, which leaves too little for your system on a
> {ramGB} GB PC and can make garbage-collection pauses longer, not shorter. {Y} GB is enough here —
> lower it in {launcher's memory setting path}."

**Fine (no change needed)**:
> **Title**: "Memory allocation looks good"
> **Text**: "{Launcher} is giving Minecraft {X} GB, which fits this hardware and setup well. No
> change needed."

**Unknown launcher (fallback — keep today's copy, don't guess)**:
> **Title**: "Give Minecraft more memory" *(unchanged from current `ram-low`)*
> **Text**: "Your launcher lets Minecraft use about {X} GB, which causes stutter as the game keeps
> freeing memory. Set it to {Y} GB in your launcher's Java or memory settings." *(unchanged generic
> wording — never name a launcher or claim a specific click-path when detection didn't confirm one.)*

---

**Risks / open items for whoever implements this**: (1) CurseForge and official-launcher UI wording
above is corroborated only by third-party guides, not primary screenshots or source strings — verify
against the live apps before shipping exact quoted labels. (2) GDLauncher's and Prism's exact
`minecraft.launcher.brand` literal values, and the official launcher's, are unverified — detection
must null-check and never assume a string match will hit; rely on the Prism-specific properties
(§1 signal 2) and Modrinth's/ATLauncher's confirmed literals instead. (3) All path/marker-file
signals are fragile against user-customized install locations — never make them the sole signal for
a launcher that also has a confirmed self-identifying property. (4) `ProcessHandle` parent-process
detection is explicitly best-effort per the JDK docs and untested here across launchers — fallback
only. (5) The official launcher's on-disk memory storage format is unverified for the 2026
(post-Vulkan-rewrite) build — re-check before relying on it for anything beyond the generic
`Runtime.maxMemory()` read.
