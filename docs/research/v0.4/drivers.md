# Driver / GPU change awareness — research (v0.4 P1 item 9)

Status legend: **VERIFIED** (checked against the actual pinned jar/bytecode, a tagged commit, or a
primary-source page fetched during this research) · **UNVERIFIED** (could not confirm from a primary
source in the time available — do not ship a claim depending on it without re-checking).

Methodology note: MC 26.2/26.3 client jars were inspected with `javap` directly from this repo's own
Loom cache (`.gradle/loom-cache/minecraftMaven/...` and the shared Gradle cache under
`C:/Users/Admin/.gradle/caches/fabric-loom/{26.2,26.3}/minecraft-client*.jar`), and Sodium was inspected
the same way from the **exact jars this repo pins** (`sodium_version=mc26.2-0.9.2-fabric` /
`mc26.3-0.9.2-fabric` in `versions/26.2/gradle.properties` / `versions/26.3/gradle.properties`), found at
`C:/Users/Admin/.gradle/caches/modules-2/files-2.1/maven.modrinth/sodium/mc26.{2,3}-0.9.2-fabric/.../sodium-mc26.{2,3}-0.9.2-fabric.jar`.
This is stronger evidence than reading GitHub source, since it's the literal bytecode this mod runs
against. All bytecode findings below are VERIFIED against those two jars unless noted.

---

## 1. Current probe: what's captured, on which backend, and where it goes

### 1.1 OpenGL (26.2, and 26.3 when Vulkan isn't in use)

`HardwareProbe.probeFast()` (`src/client/java/io/github/chaotix345/rigtune/client/probe/HardwareProbe.java:84-129`)
calls `RenderSystem.tryGetDevice()` → `GpuDevice.getDeviceInfo()` → `com.mojang.blaze3d.systems.DeviceInfo`
(a record). On the GL backend that `DeviceInfo` is built by
`com.mojang.blaze3d.opengl.GlHeuristics.createDeviceInfo(...)`, which I disassembled directly
(`javap -c` on `com/mojang/blaze3d/opengl/GlHeuristics.class` in the 26.2 client jar):

```
name       = GlStateManager._getString(GL_RENDERER)   // 0x1F01 / 7937
vendorName = GlStateManager._getString(GL_VENDOR)      // 0x1F00 / 7936
driverInfo = GlStateManager._getString(GL_VERSION)     // 0x1F02 / 7938
backendName = "OpenGL" (constant)
```

`HardwareProbe.probeFast` then does `name→gpuName`, `vendorName→gpuVendor`, `driverInfo→driver`, and
`combine()` builds `GpuInfo(vendorString=gpuVendor, renderer=gpuName, driverVersion=driver, backend, vram)`
(`core/model/GpuInfo.java`). **This exactly reproduces the field names in the task's real log line**:
`vendorString=ATI Technologies Inc., renderer=AMD Radeon RX 7800 XT, driverVersion=3.3.0 Core Profile
Context 26.8.1.260810, backend=OPENGL` — i.e. `vendorString` is the raw `GL_VENDOR` string (legacy
"ATI Technologies Inc." — AMD's GL_VENDOR string has said this since the ATI acquisition and was never
renamed), `renderer` is `GL_RENDERER`, and `driverVersion` is the **entire** `GL_VERSION` string, not
just a version number — it includes whatever suffix the vendor appends (see §2).

`GraphicsBackend.backend(String)` (`HardwareProbe.java:131-143`) then classifies `backendName` by
substring match (`"vulkan"` → VULKAN, `"opengl"`/`"gl"` → OPENGL, else UNKNOWN).

### 1.2 Vulkan (26.3 only, opt-in renderer backend)

26.3 conditionally imports a **different** `DeviceInfo`/`GpuDevice` pair via the build's version
preprocessor (`HardwareProbe.java:7-13`, the `//? if >=26.3 { ... } else { ... }` block —
this project uses a Stonecutter/Manifold-style multi-version preprocessor, evidenced by the commented-out
26.3 import block that gets uncommented at build time): `com.mojang.renderpearl.api.device.DeviceInfo`
and `.GpuDevice`. I found the 26.3 client jar ships an entirely new package,
`com.mojang.renderpearl.*` — a rewritten render-backend abstraction (GL and Vulkan side by side) that
doesn't exist in 26.2's `com.mojang.blaze3d.*`.

`javap` on `com.mojang.renderpearl.api.device.DeviceInfo` shows **the exact same record shape** as
26.2's `blaze3d` one (`name`, `vendorName`, `driverInfo`, `isZZeroToOne`, `backendName`,
`timestampPeriod`, `limits`, `features`, `underlyingExtensions`, `hintsAndWorkarounds`, `type`) — so
`HardwareProbe`'s reflection-free code compiles unchanged against either package; this is clearly a
deliberate Mojang design so a single call site (`probeFast`) works on both backends without an `if`.

For the **Vulkan** implementation (`com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice`,
disassembled with `javap -c -p`), the interesting methods are:

```java
public String deviceName()  { return vkPhysicalDeviceProperties.properties().deviceNameString(); }

public String vendorName() {
    // switches on VkPhysicalDeviceProperties.vendorID (the PCI vendor ID) — verified case values:
    // 0x1002/4098 -> "AMD", 0x10DE/4318 & 0x12D2/4818 -> "NVIDIA", 0x8086/32902 -> "INTEL",
    // 0x1010/4112 -> "IMGTEC", 0x106B/4203 -> "APPLE", 0x1414/5140 -> "MICROSOFT",
    // 0x14E4/5348 -> "BROADCOM", 0x13B5/5045 -> "ARM", 0x5143/20803 & 0x1EB1/6089-ish/0x1AE0/6880-ish -> "QUALCOMM"-family,
    // default -> Locale.ROOT "0x%x" of the raw vendor ID.
}

private static String getStandardEncodingVersion(int packed) {
    // the STANDARD Vulkan version macro layout (VK_MAKE_API_VERSION's major/minor/patch, ignoring the
    // 3-bit variant field): major=(v>>>22)&0x7F, minor=(v>>>12)&0x3FF, patch=v&0xFFF -> "%d.%d.%d"
}

public String driverInfo() {
    String apiVer = getStandardEncodingVersion(vkPhysicalDeviceProperties.properties().apiVersion());
    return String.format(Locale.ROOT, "%s %s %s",
        apiVer,
        vkPhysicalDeviceDriverProperties.driverNameString(),   // VK_KHR_driver_properties / core 1.2
        vkPhysicalDeviceDriverProperties.driverInfoString());  // VK_KHR_driver_properties / core 1.2
}
```

**This is the single most important finding for this feature.** On 26.3's Vulkan backend, MC's
`driverInfo()` (the same field `HardwareProbe` puts into `GpuInfo.driverVersion`) is built from:

1. the Vulkan **API version** (not the driver version) formatted with the standard
   `major.minor.patch` bit layout — e.g. `1.3.296` if the ICD advertises Vulkan 1.3;
2. `VkPhysicalDeviceDriverProperties.driverName` — a short vendor-chosen label (`VK_KHR_driver_properties`,
   core since Vulkan 1.2). Per the Khronos spec (`VkPhysicalDeviceDriverProperties` man page, fetched
   during this research — VERIFIED), this and `driverInfo` are `VK_MAX_DRIVER_NAME_SIZE`/`VK_MAX_DRIVER_INFO_SIZE`
   (256-byte) null-terminated UTF-8 strings the vendor fills in freely. Typical values (from public
   `vulkan.gpuinfo.org`-style reports, common knowledge, not independently re-verified here — mark
   UNVERIFIED as exact current strings): NVIDIA driverName `"NVIDIA"`; Mesa RADV driverName
   `"Mesa RADV"`; AMD's proprietary Windows/Linux driver `"AMD proprietary driver"` /
   `"AMD open-source driver"`; Intel's Windows driver `"Intel proprietary Windows driver"`; Intel's Mesa
   ANV `"Intel open-source Mesa driver"`.
3. `VkPhysicalDeviceDriverProperties.driverInfo` — free text, vendor-specific. NVIDIA's is
   conventionally just the public driver number (e.g. `"560.94"`); Mesa's typically embeds the Mesa
   version (e.g. `"Mesa 24.2.3 (LLVM 18.1.8)"` or similar). **UNVERIFIED as exact current text** — I
   could not get a live GPU or a captured `vulkaninfo` dump to confirm the literal string content for
   2026-era drivers; only the *field semantics* (free UTF-8 text, driver-chosen) are spec-verified.

So a composed 26.3 Vulkan `driverVersion` string would look like `"1.3.296 NVIDIA 560.94"` or
`"1.3.290 Mesa RADV 24.2.3"`. Consequence for §2/§4 below: **MC's own abstraction never exposes the raw
32-bit `VkPhysicalDeviceProperties.driverVersion` integer to mod code** — only this composed,
already-humanised string. Any per-vendor *bit-packing* decode (NVIDIA's non-standard packing, Intel
Windows' 18.14 split, etc.) is therefore moot for this mod unless RigTune reaches past `GpuDevice` into
LWJGL's raw Vulkan handle itself (not currently done anywhere in this codebase — `grep -r
"org.lwjgl.vulkan"` under `src/` turns up nothing). The practical parser only ever sees *text*, on both
backends, and must be vendor-string aware, not bit-aware.

`VulkanPhysicalDevice.deviceType()` also decodes `VkPhysicalDeviceProperties.deviceType` into
`DeviceType.{INTEGRATED,DISCRETE,VIRTUAL,CPU,OTHER}` — not currently read by `HardwareProbe`, but a
free, precise "is this an iGPU" signal Vulkan gives directly, more reliable than today's GL-side
`GraphicsBackend`/name-sniffing heuristics in `GlHeuristics.guessDeviceType` (which just does
substring checks like `"intel"+"arc"` and lists like `"mesa offscreen"`/`"llvmpipe"`, `"virtgl"`).

### 1.3 Where the last `HardwareProfile` is persisted

**It is not persisted anywhere today.** `HardwareProfile` (`core/model/HardwareProfile.java`) is a
plain record; `grep -rn HardwareProfile src/main src/client` (excluding tests) shows it flowing only
through in-memory call chains: `HardwareProbe.probe()` → `RigTuneClient.setHardware()` (a
`static volatile @Nullable HardwareProfile` field, `RigTuneClient.java:60`) → `RealController` →
`Recommender.recommend()` / `RigTuneScreen` / `ShareReport` (which renders it into the human-readable
share-report text, `ShareReport.java:89-100` — the *only* place any GPU field is ever written to disk,
and only when the user explicitly generates/exports a share report). It's recomputed from scratch every
client start (`HardwareProbe.probe(minecraft)` is called fresh each session — see `RealController`).
`config/rigtune/rigtune.json` (`ClientState.java`) is the one small persisted per-install state file
today (`goal`, `lastShownApply`, `lastWarnedApply`) and is the natural place to add a fingerprint (§5).

---

## 2. Driver-version parsing: normalised model and parser spec

### 2.1 Real sample strings, with verification status

| Vendor | Backend/OS | Sample string | Status |
|---|---|---|---|
| AMD | GL, Windows, Adrenalin ≥24.x new scheme | `3.3.0 Core Profile Context 26.8.1.260810` | **VERIFIED** — this is the task's real captured log line (primary: the user's own machine); `26.8.1` matches AMD's real "Adrenalin Edition 26.8.1" release, confirmed live on `amd.com` (`RN-RAD-WIN-26-8-1.html`, fetched during this research) — AMD's driver-naming scheme is now `YY.M.rev` (year.month.revision), not a monotonic counter. `260810` is very likely a `YYMMDD` internal build stamp (2026-08-10), consistent with the 26.8.1 release cadence, but I did not find an AMD doc that spells the suffix out — **UNVERIFIED** as to the suffix's exact meaning. |
| AMD | GL, Windows, older (pre-2024) scheme | `22.20.27.09.230330` (as given in the task) | **UNVERIFIED** — could not find a primary source reproducing this exact string; AMD's pre-2024 Adrenalin builds are well known to embed a `YYMMDD`-style date tail, so the shape is plausible, but don't cite the literal digits as confirmed. |
| AMD | GL, Linux (Mesa radeonsi) | `4.6 (Core Profile) Mesa 24.2.3` | **VERIFIED shape** — confirmed generic Mesa Core-Profile format `"<ver> (Core Profile) Mesa <mesaver>"` via web search of real `glxinfo` output (`"3.3 (Core Profile) Mesa 10.7.0-devel"` etc.); this format is vendor-agnostic (also covers Intel ANV/i965, Nouveau) — the "AMDGPU-PRO" / "radeonsi" distinction is in the **renderer** string (`AMD Radeon RX ... (radeonsi, ...)`), not `GL_VERSION`. |
| NVIDIA | GL, Windows & Linux | `4.6.0 NVIDIA 560.94` | **VERIFIED shape** — confirmed pattern `"<ver> NVIDIA <driver>"` from a real captured string (`"3.3.0 NVIDIA 340.102"`, found via web search); identical on Linux. |
| Intel | GL, Windows | `4.6.0 - Build 31.0.101.5595` | **VERIFIED shape** — confirmed pattern `"<ver> - Build <driverbuild>"` from real captured strings (`"4.5.0 - Build 25.20.100.6471"`, `"4.6.0 - Build 31.0.101.4824"`). |
| Any | Vulkan (26.3) | `1.3.296 NVIDIA 560.94` / `1.3.290 Mesa RADV 24.2.3` | **Field semantics VERIFIED** (Khronos spec + this repo's own bytecode), **exact text UNVERIFIED** for current 2026 drivers (§1.2). |
| — | macOS/Apple | n/a | **Out of scope, verified from source**: `versions/26.2` and `versions/26.3` only build against Windows/Linux-relevant toolchains, and there is no `com.mojang...systems.DeviceInfo` Apple/Metal backend in either client jar's `com.mojang.blaze3d`/`com.mojang.renderpearl` packages — Minecraft Java ships GL/ANGLE-over-Metal on macOS but exposes the same `DeviceInfo` shape through GL, so no separate Apple parser is needed; `GpuVendor.APPLE` already exists in RigTune's model for the M-series-via-GL case (`GL_VENDOR` reports `"Apple"`, `GL_RENDERER` e.g. `"Apple M2"`) and Vulkan's vendor-ID switch above also has an `APPLE` (0x106B) case (MoltenVK), but this is not a distribution target per the task scope. |

### 2.2 Proposed normalised model

```java
package io.github.chaotix345.rigtune.core.model;

public record DriverVersion(
        GpuVendor vendor,          // reuse existing enum
        String family,             // "adrenalin" | "geforce" | "intel-igpu" | "mesa" | "unknown"
        int[] comparable,          // e.g. [26, 8, 1] or [560, 94] or [24, 2, 3] — left-to-right compare
        String raw) {              // the untouched driverVersion string, always kept for display/debug
}
```

`comparable` is deliberately an `int[]`, not a 3-tuple record: AMD's new scheme is 3 parts
(`year.month.rev`), NVIDIA/Intel-build/Mesa are effectively 2–4 parts, and the whole point of a
`driverVersionAtLeast`/`driverVersionAtMost` condition (§4) is lexicographic `int[]` comparison, which
needs no per-family special case once parsed.

### 2.3 Parser spec (per vendor × backend), with test vectors

The parser's only input is the `driverVersion` string already sitting in `GpuInfo` (§1) — it never
touches raw Vulkan structs (§1.2). Dispatch is by `GpuVendor` (already computed elsewhere in
`GpuClassifier`) plus regex on the string shape:

```
parse(vendor, raw) -> DriverVersion | UNKNOWN (never throws)

AMD, raw matches  \bContext\s+(\d{1,3})\.(\d{1,2})\.(\d{1,2})\.\d+\b   (new "YY.M.rev" scheme, 2024+)
  -> family="adrenalin", comparable=[YY, M, rev]
AMD, raw matches  \bContext\s+(\d{2})\.(\d{2})\.(\d{2})\.(\d{2})\.\d+\b (older 5-segment scheme)
  -> family="adrenalin-legacy", comparable=[seg1, seg2, seg3, seg4] (UNVERIFIED mapping — see 2.1; treat
     conservatively, i.e. never let an UNVERIFIED format participate in a restrictive condition — see §4
     "fail closed on unparseable" requirement, which covers this for free)
AMD, raw matches  \(Core Profile\)\s+Mesa\s+(\d+)\.(\d+)\.(\d+)      (Linux Mesa radeonsi)
  -> family="mesa", comparable=[maj, min, patch]
AMD, Vulkan raw "<apiver> Mesa RADV <mesaver>" or "<apiver> AMD proprietary driver <text>"
  -> if driverInfo segment matches \d+\.\d+(\.\d+)? reuse the Mesa/Adrenalin sub-parsers on that segment;
     else UNKNOWN (never invent a number)

NVIDIA, raw matches  NVIDIA\s+(\d{3})\.(\d{2,3})\b   (GL, Windows & Linux; also the driverInfo segment on Vulkan)
  -> family="geforce", comparable=[major, minor]   e.g. "560.94" -> [560, 94]

Intel, raw matches  -\s*Build\s+(\d+)\.(\d+)\.(\d+)\.(\d+)   (GL, Windows; e.g. "31.0.101.5595")
  -> family="intel-igpu", comparable=[seg1, seg2, seg3, seg4]
     (matches Sodium's own encoding for the same DLL version — §3 — so a driverVersion rule can reuse
     the same numbers Sodium already keys its Gen7/8 checks on)

Mesa (any vendor incl. Intel ANV/i965, AMD RADV/radeonsi), raw matches  \(Core Profile\)\s+Mesa\s+(\d+)\.(\d+)\.(\d+)
  -> family="mesa", comparable=[maj, min, patch]

Anything else (regex doesn't match, string blank, "unknown", backend UNKNOWN)
  -> UNKNOWN  (never a parse exception; the condition layer treats this as Truth.UNKNOWN — §4)
```

Test vectors (drop straight into a `DriverVersionParserTest`):

| input vendor | input raw | expected family | expected comparable |
|---|---|---|---|
| AMD | `3.3.0 Core Profile Context 26.8.1.260810` | adrenalin | `[26, 8, 1]` |
| AMD | `4.6.14761 Core Profile Context 24.12.1.241205` | adrenalin | `[24, 12, 1]` |
| AMD | `4.6 (Core Profile) Mesa 24.2.3` | mesa | `[24, 2, 3]` |
| NVIDIA | `4.6.0 NVIDIA 560.94` | geforce | `[560, 94]` |
| NVIDIA | `3.3.0 NVIDIA 340.102` | geforce | `[340, 102]` |
| Intel | `4.6.0 - Build 31.0.101.5595` | intel-igpu | `[31, 0, 101, 5595]` |
| Intel | `4.5.0 - Build 25.20.100.6471` | intel-igpu | `[25, 20, 100, 6471]` |
| anything | `""` / `"unknown"` / `null` | — | UNKNOWN |
| anything | a string a future MC/driver format doesn't match | — | UNKNOWN (fail closed, never guess) |

---

## 3. Known-bad drivers — verified only

I decompiled the **exact Sodium 0.9.2 jars this repo pins** (`sodium-mc26.2/26.3-0.9.2-fabric.jar`,
package `net.caffeinemc.mods.sodium.client.compatibility.workarounds`) rather than trusting memory or
generic GitHub browsing, and cross-checked every finding against Sodium's own GitHub issues/wiki. Full
detail of what I found in the bytecode:

- `Workarounds.Reference` has exactly 5 constants: `NVIDIA_THREADED_OPTIMIZATIONS_BROKEN`,
  `NO_ERROR_CONTEXT_UNSUPPORTED`, `INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED`,
  `INTEL_DEPTH_BUFFER_COMPARISON_UNRELIABLE`, `AMD_GAME_OPTIMIZATION_BROKEN` — these are the only ones
  `HardwareProbe.sodiumWorkarounds()` can ever see (it iterates this exact enum).
- `Workarounds.findNecessaryWorkarounds()` (disassembled in full) gates every one of the 5 **only by
  vendor + OS + (Intel only) GPU generation — never by driver version**:
  - `AMD_GAME_OPTIMIZATION_BROKEN`: AMD adapter present **and** OS is Windows. No driver-version check
    at all. Mechanism (`AmdWorkarounds.applyEnvironmentChanges$Windows`): overwrites the process's
    reported command line (`WindowsCommandLine.setCommandLine("net.caffeinemc.sodium / net.minecraft.client.main.Main /")`)
    so AMD's driver-side game-profile auto-detection (which sniffs the command line for Minecraft) can't
    recognise the process and apply a game-specific optimization profile that conflicts with MC's
    multi-context OpenGL usage. **This directly explains the task's real data point**: the user's RX 7800 XT
    is on a brand-new driver (26.8.1, Aug 2026) and the flag still fires — it's supposed to; it has
    nothing to do with driver freshness or a specific bad version.
  - `NVIDIA_THREADED_OPTIMIZATIONS_BROKEN`: any NVIDIA adapter present, any OS, any driver version.
    Mechanism: Linux sets `__GL_THREADED_OPTIMIZATIONS=0`; Windows forces `GL_DEBUG_OUTPUT_SYNCHRONOUS`
    via `GL_KHR_debug` to disable the driver's threaded command submission.
  - `INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED` + `INTEL_DEPTH_BUFFER_COMPARISON_UNRELIABLE`: both
    fire together, gated only by `IntelWorkarounds.isUsingIntelGen8OrOlder()`, which matches the
    adapter's **OpenGL ICD DLL filename** against `ig(7|75|8)icd(32|64)\.dll` (Gen7/Ivy Bridge&Haswell,
    Gen7.5, Gen8/Broadwell integrated GPUs) — a hardware-generation signal, not a driver version.
  - `NO_ERROR_CONTEXT_UNSUPPORTED`: Linux, any vendor, unconditional.

  **Conclusion for RigTune's `driverVersion` rule design: none of the 5 flags RigTune can already see
  are useful signals for "this specific driver version is bad."** They're useful as-is for
  vendor/OS/generation gating, which RigTune's existing `flags` condition already supports.

- Separately, Sodium's jar contains two **driver-version–range** checks that exist but are **not** wired
  into the public `Reference` enum (so `HardwareProbe` never sees them as a flag) — these are real,
  vendor-confirmed known-bad ranges and good seed candidates for a *new* `driverVersion` rule condition:

  | Check | Range | Sodium issue | Symptom | Still relevant? |
  |---|---|---|---|---|
  | `NvidiaWorkarounds.findNvidiaDriverMatchingBug1486` — Windows, reads the NVIDIA WDDM adapter's OpenGL ICD DLL file-version (`z==15 && 2647<=w<3623`, decoding to **526.47 ≤ driver < 536.23**) | NVIDIA Windows 526.47–536.22 | [CaffeineMC/sodium#1486](https://github.com/CaffeineMC/sodium/issues/1486) ("Game crashes when using NVIDIA drivers version 526.47 or later") — **VERIFIED**, opened 2022-10-27 | NVIDIA's driver silently force-enables "Threaded Optimizations" when it detects Minecraft; combined with Sodium this causes severe perf loss/crashes | The *specific range* is from 2022–2023 driver builds, long superseded (2026 NVIDIA drivers are in the 570s/580s+). But the underlying cause (NVIDIA always force-enables this for MC) is why Sodium's actual mitigation (`NVIDIA_THREADED_OPTIMIZATIONS_BROKEN`) now applies unconditionally to **every** NVIDIA GPU regardless of version — Sodium stopped range-gating the fix even though this detector function (seemingly vestigial/diagnostic-only now) still exists in the 0.9.2 jar. A launch flag `-Dsodium.checks.issue1486=false` exists per Sodium's own wiki ([Disabling Bug Checks](https://github.com/CaffeineMC/sodium/wiki/Disabling-Bug-Checks)) — **VERIFIED**. |
  | `IntelWorkarounds.findIntelDriverMatchingBug899` — Windows, adapter's ICD DLL matches `ig7icd(32\|64)\.dll` (Gen7/HD4000-class) **and** file-version `z==10 && w<5161` | Intel Windows HD Graphics (Haswell-class, Gen7) driver < **10.18.10.5161** | [CaffeineMC/sodium#899](https://github.com/CaffeineMC/sodium/issues/899) ("Game freezes during startup with older Intel HD Graphics drivers") — **VERIFIED**, opened 2021-09-12, status "will not be worked on" (Sodium's official fix is "update the driver", per Sodium's own [Driver Compatibility wiki](https://github.com/CaffeineMC/sodium/wiki/Driver-Compatibility): "driver version 10.18.10.5161 or newer" required — this **independently confirms** the exact threshold read out of the bytecode | Low relevance to 26.x-era discrete hardware, but genuinely still relevant to any low-end Haswell-era laptop iGPU still limping along on current MC — worth a seed entry precisely because it's cheap and fully verified, not because it's common. |

- AMD vendor-published (not Sodium) known issue, found directly on `amd.com`: **"Increased memory usage
  may be observed while playing certain versions of Minecraft Java Edition"** is listed in AMD's own
  Adrenalin Edition release notes for **24.8.1** and **24.9.1** (Aug–Sep 2024;
  `https://www.amd.com/en/resources/support-articles/release-notes/RN-RAD-WIN-24-8-1.html` and the
  24.9.1 notes) — **VERIFIED** (found live on amd.com). I could not confirm whether this line is still
  present in the current 26.8.1 notes (page fetch timed out twice during this research) —
  **UNVERIFIED as still-current**; treat as a historical AMD-acknowledged issue, not a live one, until
  re-checked.
- AMD's own official public driver bug tracker
  ([GPUOpen-Drivers/AMD-Gfx-Drivers#37](https://github.com/GPUOpen-Drivers/AMD-Gfx-Drivers/issues/37)):
  an RX 7900 XTX (same RDNA3 family as the task's RX 7800 XT) crashing ~90 minutes into modded
  Minecraft on Adrenalin 24.12.1/24.5.1, an access violation in `atio6axx.dll` (AMD's GL ICD) —
  **VERIFIED as filed and open**, but AMD's own assignee marked it **"cannot reproduce"**, so this is
  "reported against AMD's own tracker, RDNA3-relevant, unresolved" — not a confirmed root cause. Include
  only as a "watch this" item, not a rule seed, until AMD or someone else nails a reproducible range.
- NVIDIA and Intel **vendor** "Known Issues" lists mentioning Minecraft specifically: **none found**
  in the searches performed for this research. Do not seed `driverVersion` rules for NVIDIA/Intel from
  vendor release notes — there's nothing to cite yet.
- Mojang bug tracker (`bugs.mojang.com`) MC-* issues tying a crash to a specific driver version range
  for current (26.x-era) hardware: **none pinned down** within this research's time budget — the search
  surface is dominated by very old (1.8-era) generic OpenGL-context reports. Flagged as an open question
  (§ below), not fabricated.

**Net verified seed list for a first `driverVersion` rules-v2.json entry: 2 items**
(Sodium #1486 NVIDIA range, Sodium #899 Intel range) plus 1 "watch, don't seed yet" item (AMD #37). This
is intentionally short — per the task's own instruction, a short honest list beats a padded one.

---

## 4. Rules side: a `driverVersion` v2 condition, safe on 0.2.0 and 0.3.0

### 4.1 What 0.2.0 and 0.3.0 actually do with an unknown condition key (verified by running `git diff`, not assumed)

```
git diff v0.2.0 v0.3.0 -- src/main/java/.../core/rules/Condition.java            -> empty
git diff v0.2.0 HEAD    -- src/main/java/.../core/rules/Condition.java            -> empty
git diff v0.2.0 HEAD    -- src/main/java/.../core/rules/ConditionEvaluator.java   -> empty
git diff v0.2.0 v0.3.0  -- src/main/java/.../core/recommend/Recommender.java (SUPPORTED_FEATURES) -> empty
```

**0.2.0 and 0.3.0 ship byte-for-byte identical condition parsing/evaluation code.** This is not
"probably fail closed" — it's the same class file. The mechanism, read from
`core/rules/Condition.java`, `ConditionAdapterFactory.java`, `ConditionEvaluator.java`:

1. `ConditionAdapterFactory` builds `Condition.KNOWN_KEYS` by reflecting over `Condition.class`'s
   declared fields. Any JSON object key in a condition that **isn't** a declared Java field name (or
   whose value doesn't match that field's type exactly — a non-boolean for a boolean field, an
   out-of-range/non-integral number, a `null`) is recorded in `Condition.unknownFields` on that node,
   and stripped before Gson populates the rest — so the **document still parses fine**; nothing breaks
   at the file level, only at the individual-condition level.
2. `ConditionEvaluator.poisoned(c)` returns true if `c.unknownFields` is non-empty **anywhere in the
   tree**, including inside nested `not`/`anyOf`.
3. `ConditionEvaluator.evaluate(c, ctx)` returns `Truth.UNKNOWN` for a poisoned condition, otherwise
   evaluates normally. Only `Truth.TRUE` fires a rule (`matches()` = `evaluate(...) == TRUE`); `UNKNOWN`
   behaves like "doesn't fire" for every rule kind, and critically `not(UNKNOWN) == UNKNOWN` (Kleene
   logic in `Truth.java`), so a negated unknown condition can never accidentally turn on a restriction.

So: **a brand-new v2 condition field needs zero new fail-closed machinery.** Adding a field named
`driverVersion` (or `driverVersionAtLeast`/`driverVersionAtMost`, see 4.2) to `Condition.java` in the
v0.4 codebase has **no effect whatsoever** on the already-compiled 0.2.0/0.3.0 jars people are still
running — they don't have that field, so `KNOWN_KEYS` on those binaries never contains it, so any
condition using it poisons to `UNKNOWN` on those clients automatically. This is exactly the same
mechanism that already protects `gpuModelMatches`, `mcVersionRange`, `settingIs`, etc. (all added after
0.1.0, all safe on 0.1.x only via the *separate* v1-projection pipeline in §4.3 — but safe on 0.2.0 by
this same poisoning mechanism, since 0.2.0 already has all of those fields; the new case here is a field
that's newer than 0.2.0/0.3.0 too, which is new territory this repo hasn't hit yet, but the fail-closed
principle is identical).

The separate `requires: [...]` escape hatch (`RulesDocument.ModRule.requires` etc., checked by
`Recommender.supported()` against `Recommender.SUPPORTED_FEATURES`, which is `Set.of()` on **every**
released version through 0.3.0 — also verified unchanged by `git diff`) exists for *rule-level* fields
outside the `Condition` object (e.g. a hypothetical new `ModRule` field Gson would silently ignore
without complaint, which is a real fail-open risk for non-condition fields). It is **not needed** to make
a pure `driverVersion` condition safe — but see 4.4 for one case where it's still worth using.

### 4.2 Proposed schema

```json
{ "driverVersion": {
    "vendor": "nvidia",
    "atLeast": [560, 94],
    "atMost": null
} }
```

Fields, added to `Condition.java`, `ConditionEvaluatorTest`, and `docs/RULES_SCHEMA.md`'s Condition
table (v2-only):

| field | type | meaning | UNKNOWN when |
|---|---|---|---|
| `driverVersion.vendor` | string | one of the existing `gpuVendor` vocabulary | vendor doesn't match the detected `GpuVendor`, or GPU vendor unknown → the whole `driverVersion` node is UNKNOWN (not FALSE — a future client might detect a vendor this one can't) |
| `driverVersion.atLeast` / `.atMost` | int[] | compared lexicographically against the parsed `DriverVersion.comparable` (§2.2), left-to-right, missing trailing elements treated as 0 | the driver string didn't parse (§2.3's parser returned UNKNOWN), or the vendor doesn't match |

Implementation shape in `ConditionEvaluator` (mirrors the existing `range`/`knownRange` helpers already
there): add one more `t = and(t, () -> c.driverVersion == null ? TRUE : driverVersion(c.driverVersion, gpu))`
line, with `driverVersion()` parsing `gpu.driverVersion()` via §2's parser and doing an `int[]` compare,
returning `UNKNOWN` on any parse failure or vendor mismatch — never `FALSE` from a parse failure, so an
unparseable string (a future driver string format, a VM/software renderer, whatever) never gets treated
as "not affected" *or* "affected"; it just doesn't participate, per the schema's own existing "Rules for
maintainers" principle ("UNKNOWN switches restrictions off... gate restrictions on always-known facts,
or add a second rule for the unknown case").

Per the schema doc's explicit warning ("Never add a v2-only or future field to an **existing** restrictive
rule... add a new rule next to the old one instead, or gate the new one with `requires`") — `driverVersion`
is brand new, so this doesn't apply to it directly, but it **does** apply to every future edit: don't
retrofit `driverVersion` onto an existing `avoidWhen`/clamp; add a sibling rule.

### 4.3 `check_rules_v1.py` / `RulesV1DifferentialTest` / `rules-v1.json`

Completely orthogonal, verified by reading `tools/update_rules.py`: `V1_CONDITION_KEYS` (frozen,
0.1.0-only vocabulary) is a hardcoded set that does **not** include `driverVersion` and must **never**
gain it. `V2_CONDITION_KEYS = V1_CONDITION_KEYS | {...}` is the updater's own allow-list for
rules-v2.json; `driverVersion` gets added there (not to `V1_CONDITION_KEYS`), so:
- `tools/check_rules_v1.py`'s `shape_problems()` will reject rules-v1.json outright if a `driverVersion`
  key ever leaks into it (it diffs against `V1_CONDITION_KEYS`) — this is a hard CI gate, not just a
  convention.
- The v1-projection code in `update_rules.py` (the "automatic, per field" rule in
  `docs/RULES_SCHEMA.md`, "a `recommendWhen` that uses a v2 condition key... becomes
  `{"always": false}`") already handles a brand-new v2 key with zero changes: any rule whose
  `recommendWhen`/`when` uses `driverVersion` is automatically flattened to `{"always": false}` (mods)
  or becomes a maintainer-decision error requiring a `"v1"` override (settings/advice), exactly like
  every other v2-only key today. No new code needed in the updater beyond the one-line addition to
  `V2_CONDITION_KEYS`.
- `RulesV1DifferentialTest` never sees `driverVersion` at all (it only diffs `gpuTiers`/`gpuVendorFallback`/
  `cpuTiers`/`heapTiers`, none of which this feature touches) — unaffected by construction.

### 4.4 One place `requires` is still worth using

Even though the condition itself fails closed for free, consider gating the **whole rule** that uses
`driverVersion` with `"requires": ["driverVersion"]` anyway (needs one addition:
`Recommender.SUPPORTED_FEATURES` gains `"driverVersion"` in 0.4.0 only). This buys nothing for
0.2.0/0.3.0 safety (already covered), but it lets the *updater* (`update_rules.py`) assert "this rule
requires a client that understands `driverVersion`" as a **document-level** fact checked by
`SchemaConsistencyTest`, rather than relying solely on per-condition poisoning — cheap belt-and-braces,
consistent with how the schema doc already frames `requires` as "the escape hatch for future rule-level
fields that must not fail open."

---

## 5. Change-detection design (P1 item 9, part 1)

**Storage**: extend `ClientState` (`src/client/java/io/github/chaotix345/rigtune/client/ClientState.java`,
`config/rigtune/rigtune.json`) rather than inventing a new file — it's already the per-install state file,
already uses plain Gson-on-a-POJO (which silently ignores unknown JSON fields both directions: an older
RigTune reading a newer `rigtune.json` ignores new fields; a newer RigTune reading an older one defaults
them to `null`), already has the atomic-write helper wired up (`ClientState.save`, temp file + atomic
move). Add:

```java
public volatile @Nullable String lastGpuVendor;      // GpuVendor.name()
public volatile @Nullable String lastGpuRenderer;     // GpuInfo.renderer()
public volatile @Nullable String lastGpuDriverRaw;    // GpuInfo.driverVersion(), the raw string
public volatile @Nullable String lastGraphicsBackend;  // GraphicsBackend.name()
public volatile @Nullable String lastCpuName;
public volatile long lastTotalRamMb;
```

Store the **raw strings**, not the parsed `DriverVersion` — the parser (§2) can and will improve over
time, and re-deriving `comparable`/`family` from the raw string on every comparison means a parser
upgrade retroactively benefits change detection without a migration. Compare on renderer name **or**
driver raw string **or** vendor changing (a driver update alone should trigger this, not just a GPU
swap) — i.e. "changed" = any of `lastGpuRenderer`, `lastGpuDriverRaw`, `lastGpuVendor` differs from the
current probe, or any field is missing (first run / pre-v0.4 upgrade, which should *not* pop the notice
the very first time — see below).

**When to compare**: right after `HardwareProbe.probe()` resolves at startup (where
`RigTuneClient.setHardware()` is already called) — cheap, in-memory, no extra I/O beyond the
`ClientState` load that already happens for `goal`. On first-ever run (no `lastGpu*` fields in
`rigtune.json`, including an upgrade from a pre-v0.4 install where those fields never existed) — silently
seed them from the current probe and skip the notice; only compare and notify from the *second* startup
where a stored baseline exists. Update the stored fields unconditionally once the notice has been shown
(or if the user dismisses without acting) so the notice doesn't repeat every launch.

**UX**: reuse the existing recommendation/advice surface rather than inventing new UI chrome — a synthetic
`Recommendation` in a new `Category` (or the existing `ADVICE` category) with `id="advice:hardware-changed"`,
title e.g. *"Your GPU driver changed since last time"* / *"Your GPU changed since last time"* (differentiate
by which field(s) changed), and its `Action` wired to two buttons already conceptually present elsewhere in
the UI: **Re-scan** (re-run `HardwareProbe.probe()` and re-run `Recommender.recommend()` against the fresh
profile — this already happens on every startup, so "re-scan" from the notice is really "re-open/refresh
the recommendations screen") and **Re-benchmark** (route into the existing `BenchmarkController` /
`client/benchmark` flow, `src/client/java/io/github/chaotix345/rigtune/client/benchmark/`). No network
call needed for detection itself — this is pure local-state comparison.

---

## 6. "What's new for you" design (P1 item 9, part 3)

**Today**: `RulesDocument.revision` (monotonic int, bumped by the updater whenever content changes) and
`Report.rulesRevision`/`Report.rulesSource` already flow through every recommend cycle
(`core/model/Report.java`), but nothing persists "the revision I last showed the user" — confirmed by
`grep -n "revision" RulesLoader.java`, which only uses `revision` to pick the *newest* candidate among
bundled/cache/remote, never to compare against a stored "last seen." `Recommendation.id` (confirmed via
`Recommender.java`) is already a **stable, human-readable, deterministic** string per recommendation
kind — `"add:<slug>"`, `"update:<modId>"`, `"set:<key>"`, `"advice:<id>"`, `"disable:<modId>"`,
`"conflict:<a>+<b>"` — exactly the identity needed to diff "recommendations for this machine" across two
rules revisions without any new ID scheme.

**Design**: add to `ClientState` (or a new small file if this list gets large — `Set<String>` of
recommendation ids per machine is small, so `ClientState` is fine):

```java
public volatile int lastSeenRulesRevision = -1;
public volatile @Nullable Set<String> lastSeenRecommendationIds;  // ids from the last time the screen was opened
```

**Computing "new since you last looked"**: on opening the recommendations screen (not on every
background probe — this is a "you looked" signal, not a "we recomputed" signal), compute the current
`Report.recommendations()` ids as today; if `lastSeenRulesRevision != report.rulesRevision()` **and**
`lastSeenRecommendationIds != null`, the "new" set is
`currentIds - lastSeenRecommendationIds` (set difference) restricted to `appliable()` recommendations
(skip `Action.None` advice-only noise unless it's a `warning`/`critical`). This works identically whether
the newer revision came from the **bundled** copy (offline — a new mod version ships a newer
`rules-v2.json` bundled resource, `revision` jumps, `lastSeenRulesRevision` is stale from before the
update) or from **RemoteRulesFetcher** (online — a background fetch replaces the active document with a
strictly-newer revision per `RulesLoader`'s own "strictly newer" rule already documented in
`RULES_SCHEMA.md` §"How the mod picks a copy"). No special-casing needed for online vs offline: both
paths already funnel through the same `RulesDocument.revision` comparison `RulesLoader` does today; this
feature just adds one more consumer of that same number.

After showing the notice (or the user dismisses it, or simply opens the screen and sees the
recommendations list — "looked" = screen opened, not notice-dismissed, so it can't be gamed by never
dismissing), update both `lastSeenRulesRevision` and `lastSeenRecommendationIds` to the current values
and save `ClientState`.

**Where it shows**: a small header-line banner in `RigTuneScreen` (`src/client/java/.../client/ui/RigTuneScreen.java`)
rather than a Minecraft toast — this repo's UI is a full custom screen (`extends Screen`), not
transient in-world HUD, and I found no existing toast/notice pattern in `RigTuneScreen` to reuse (grep
for "Toast"/"Notice"/"Banner" turned up nothing) — a new one-line header ("3 new recommendations since
you last looked — Sodium, +2 more") with a dismiss (×) is the smallest addition consistent with the
existing screen-based UI. Dismissing just updates `lastSeenRecommendationIds`/`lastSeenRulesRevision`
immediately (equivalent to "looked").

**Localisation**: follow the existing `Text.of("rigtune.rec....", "English fallback")` pattern used
throughout `Recommender.java` (e.g. `Text.of("rigtune.rec.alpha", ALPHA_NOTE)`) and the lang JSON under
`src/main/resources/assets/rigtune/lang/` — add `rigtune.notice.new_recommendations` etc. as translation
keys with an English literal fallback, exactly like every other user-facing string added in 0.3
(`docs/v0.3/SPEC.md` item 9, referenced in `Recommendation.java`'s own header comment).

---

## 7. Files/classes to add or modify, and test plan

### Add
- `core/model/DriverVersion.java` — the normalised record (§2.2).
- `core/hardware/DriverVersionParser.java` (or a static method group in `GpuClassifier`, which already
  owns `GpuClassifier.subject(gpu)` used by `gpuModelMatches` — natural neighbour) — the parser (§2.3).
- `core/rules/Condition.java` — add `driverVersion` field(s) (a nested `DriverVersionCondition` POJO or
  three flat fields `driverVersionVendor`/`driverVersionAtLeast`/`driverVersionAtMost` — flat is simpler
  given `ConditionAdapterFactory`'s reflection is field-name-based and doesn't currently special-case
  nested objects other than `Map<String,String>`; recommend flat fields to avoid adding nested-object
  poisoning logic that doesn't exist yet).
- `core/rules/ConditionEvaluator.java` — one new `and(t, () -> ...)` line + a private `driverVersion(...)`
  helper (§4.2).
- `tools/update_rules.py` — add the 2–3 new key names to `V2_CONDITION_KEYS` (never `V1_CONDITION_KEYS`),
  plus their expected JSON types alongside `INT32_CONDITION_KEYS`/`LIST_CONDITION_KEYS`/etc.
- `docs/RULES_SCHEMA.md` — new row(s) in the Condition table, v2-only.
- `src/client/java/.../client/ClientState.java` — the new fingerprint + last-seen fields (§5, §6).
- A new advice/notice surface: either extend `Recommender.java` with a synthetic hardware-change
  recommendation, or a small new class in `core/recommend/` if that pollutes `Recommender`'s existing
  scope too much — lean toward a sibling class (`HardwareChangeAdvice.java`) called from wherever
  `Recommender.recommend()` assembles the final list, to keep `Recommender.java` (already large) from
  growing further.
- `client/ui` — the header-line banner in `RigTuneScreen.java` (or a small extracted component if the
  screen class is already large — it's 200+ lines per the earlier read).
- Rules content: one `driverVersion`-gated `AdviceRule` (or `SettingRule`) in `rules/source/knowledge.json`
  seeded from §3's 2 verified entries, `"v1": false` is implicit (it can't reach v1 — it uses a v2-only
  key) but should still get an explicit maintainer decision per the schema's "errors" list if it's
  anything other than an `info`-kind advice (a `warning`/`critical` advice using a v2 key is an error
  without a `"v1"` override, per `RULES_SCHEMA.md`'s v1-projection rules) — plan to either mark it `info`
  or write a v1-safe fallback (e.g. a generic "you're on an old driver, consider updating" without the
  specific range) if it should also reach 0.1.x users.

### Test plan
- `DriverVersionParserTest` — the table in §2.3, plus fuzz-ish garbage strings (empty, `"unknown"`,
  a truncated/mid-negotiation string, a hypothetical future AMD/NVIDIA format) all → UNKNOWN, never an
  exception.
- `ConditionEvaluatorTest` (existing file, extend) — `driverVersion` TRUE/FALSE/UNKNOWN cases: matching
  vendor+range → TRUE; matching vendor, out of range → FALSE; vendor mismatch → UNKNOWN; unparseable
  raw string → UNKNOWN; `not {driverVersion: ...}` on an UNKNOWN base → still UNKNOWN (Kleene, not TRUE) —
  this is the single most important regression test since it's the exact bug class the whole fail-closed
  design exists to prevent.
- **New**: a pinned-0.2.0/0.3.0 differential test, following the *exact* existing pattern for 0.1.0
  (`src/test/java/io/github/chaotix345/rigtune/v010/core/rules/{RulesDocument,RulesLoader}.java`,
  `core/model/HardwareProfile.java`, `core/hardware/GpuClassifier.java` — pinned copies of those
  classes as they shipped, used by `RulesV1DifferentialTest`). **There is currently no equivalent `v020`
  pinned copy of `Condition`/`ConditionEvaluator`** (`src/test/java/.../v020/` only has `core/benchmark`,
  confirmed by directory listing) — since this feature is the first time a *newer-than-0.3.0* schema
  addition needs to be proven safe against *already-released* 0.2.0/0.3.0 binaries (as opposed to 0.1.x,
  which already has this harness), add `v020/core/rules/{Condition,ConditionAdapterFactory,
  ConditionEvaluator,Truth}.java` as verbatim copies of the v0.2.0-tagged sources (identical to v0.3.0,
  per §4.1's `git diff`), and a `DriverVersionFailClosedTest` that feeds a `rules-v2.json` fragment
  containing the new `driverVersion` key to **both** the current parser and the pinned v0.2.0 copy,
  asserting: current → the intended TRUE/FALSE, pinned copy → `UNKNOWN` (poisoned) every time. This is
  strictly stronger evidence than reasoning about the mechanism, and it's cheap since the sources to pin
  already exist unchanged in git history (`git show v0.2.0:src/main/java/.../Condition.java`, etc.).
- `SchemaConsistencyTest` (existing, presumably in `core/rules` or `tools/tests` — extend) to check
  `update_rules.py`'s `V2_CONDITION_KEYS` includes the new field name(s) and that `V1_CONDITION_KEYS`
  does not.
- `check_rules_v1.py` / `tools/tests` — a fixture rules source using `driverVersion` in a
  `recommendWhen`, asserting the v1 projection flattens it to `{"always": false}` without maintainer
  intervention (mods) and that a settings/advice rule using it without a `"v1"` override is a hard
  updater error (per the schema's "errors" list) — confirms the existing pipeline needs no code change
  beyond the `V2_CONDITION_KEYS` addition.
- `ClientStateTest` (or new) — round-trip the new fingerprint fields through `save`/`load`; verify an
  old-shaped `rigtune.json` (missing the new fields) loads without error and doesn't spuriously fire the
  change notice on first post-upgrade launch (seed-not-notify behaviour, §5).
- Change-detection unit tests: same renderer+driver → no notice; renderer same, driver raw string
  different → notice; vendor same, renderer different (GPU swap) → notice; first run (no stored
  baseline) → seeds silently, no notice.
- "What's new" unit tests: revision unchanged → no diff computed; revision bumped, empty
  `lastSeenRecommendationIds` (upgrade from pre-v0.4) → treat as "nothing new" (don't dump the entire
  current list as "new" on the very first v0.4 launch) rather than diffing against an empty set; revision
  bumped with a real prior id-set → correct set difference, restricted to appliable + warning/critical
  advice.

---

## Open questions for the coordinator

1. Mojang bug tracker: no specific MC-* issue was pinned to a driver-version range for 26.x-era hardware
   within this research's budget. Worth a dedicated follow-up search pass if the rules seed list needs
   to grow beyond the 2 Sodium-derived entries in §3.
2. AMD's "increased memory usage, Minecraft Java Edition" known issue (§3) — confirmed for 24.8.1/24.9.1
   (2024), not re-confirmed for the current 26.8.1 notes (page fetch timed out twice). Needs a quick
   re-check before citing it as still-current in any UI-facing copy.
3. Vulkan `driverInfo`/`driverName` **exact current text** per vendor (§1.2/§2.1) is spec-verified in
   shape only; if the parser needs to handle Vulkan-path strings on day one (vs. gating the Vulkan
   `driverVersion` condition to UNKNOWN until real samples are captured), someone with 26.3 + Vulkan
   backend + a real GPU should capture actual `driverInfo()` output once and feed it back as test
   vectors.
4. Whether `driverVersion` needs the flat-fields-vs-nested-object design choice (§7) revisited once
   someone actually starts implementing `ConditionAdapterFactory` support — flagged as a
   recommendation, not a final decision.
