# Hardware tier table refresh — research (P1 item 7)

Research only. Nothing in `rules/` was edited. Access date for every citation below: **2026-09-26**.

## 0. How the tables work (recap, so the proposals below make sense)

- Source of truth: `rules/source/knowledge.json` → `gpuTiers` (39 rows) / `cpuTiers` (8 rows) / `heapTiers`, hand-maintained, generated verbatim into `rules/rules-v2.json` **and** `rules/rules-v1.json` by `tools/update_rules.py` (`docs/RULES_SCHEMA.md` "Tiers"/"GpuTierRule"/"CpuTierRule"; `tools/README.md`).
- **GPU**: `pattern` is a Java regex, matched with `Matcher.find` (substring, not full match), case-sensitivity controlled by an inline `(?i)` (all existing rows use it). First row whose `vendor` is compatible with the detected vendor **and** whose pattern is found wins — file order matters (`GpuClassifier.classify`, `src/main/java/io/github/chaotix345/rigtune/core/hardware/GpuClassifier.java:53-61`). Vendor is detected from the renderer string first, then the vendor string, via loose substring matches (`nvidia|geforce|quadro|nouveau`, `\bamd\b|advanced micro devices|radeon|\bati\b`, `intel`, `\bapple\b`, `qualcomm|adreno`) — `GpuClassifier.java:17-22,70-107`. If nothing matches, `gpuVendorFallback` (nvidia/amd/apple=3, intel/qualcomm/other/unknown=2, software=0) applies. `integrated` on a row is authoritative; when a row omits it (none of the proposed rows below need to), a heuristic decides. Patterns are capped at 200 chars and run under a read-budget (`BudgetedChars`) so a run-away regex just becomes "no match", not a hang.
- **CPU**: same `find`, first match wins, patterns match the OSHI processor "name" string; no vendor gate. If nothing matches, the formula in `docs/RULES_SCHEMA.md` §CpuTierRule applies (cores→tier, −1 if max freq < 2500 MHz).
- **Tiers 0–5**: 0 = software rendering (critical warning), 1 = weakest, 5 = strongest, both scales independent (a tier-5 GPU paired with a tier-2 CPU still gives a tier-2 machine — `tier = min(gpu, cpu, mem)`, `docs/RULES_SCHEMA.md` §Tiers).
- **The v1 projection has no override mechanism for tier tables.** `docs/RULES_SCHEMA.md` §"The v1 projection": *"Tier rules are copied as they are."* Unlike `ModRule`/`SettingRule`/`AdviceRule`, `GpuTierRule`/`CpuTierRule` have no `v1` field and the schema note says *"Tier rules have no `requires` and no fail-closed handling... any schema change to a tier rule needs a new schemaVersion."* So whatever goes into `gpuTiers`/`cpuTiers` in `knowledge.json` is **byte-identical** in both `rules-v1.json` and `rules-v2.json` — this drives the §5 analysis below.

## 1. GPUs — coverage audit against the current 39 `gpuTiers` rows

Legend: **OK** = already correctly matched by an existing row (found via the same regex engine semantics, checked by hand against the live `knowledge.json`); **GAP** = falls through to `gpuVendorFallback` today; **BUG** = matches an existing row but gets the *wrong* classification (integrated flag or tier).

### NVIDIA GeForce RTX 50 series (desktop)

Launch dates: RTX 5090 & 5080 — Jan 30, 2025; RTX 5070 Ti — Feb 20, 2025 [[Corsair explainer]](https://www.corsair.com/us/en/explorer/gamer/gaming-pcs/rtx-5090-5080-and-5070-series-gpus-everything-you-need-to-know/); RTX 5070 — same wave, Mar 2025; RTX 5060 Ti 8/16 GB — Apr 16, 2025 [[Tom's Guide]](https://www.tomsguide.com/computing/gpus/rtx-5060-ti-release-date-just-tipped-for-april-16-hp-seemingly-confirms-nvidias-next-gen-gpus); RTX 5060 — May 19, 2025 [[NVIDIA newsroom]](https://www.nvidia.com/en-us/geforce/news/rtx-5060-desktop-family-laptop-5060-coming-soon/); RTX 5050 — Jul 2025 [[TechPowerUp]](https://www.techpowerup.com/338288/nvidia-geforce-rtx-5050-to-launch-on-july-1). **RTX 50 SUPER refresh: NOT launched as of 2026-09-26** — still unannounced/on hold per NVIDIA, rumored to slip to CES 2027 [[TechPowerUp]](https://www.techpowerup.com/342778/nvidias-partners-reportedly-lack-final-geforce-rtx-50-series-super-specifications), [[Tom's Hardware]](https://www.tomshardware.com/pc-components/gpus/nvidia-is-reportedly-still-planning-fabled-rtx-50-super-series-for-2026-leak-claims-lineup-could-now-include-a-potential-rtx-5060-super-with-12gb-of-vram) — **do not add SUPER rows yet**, nothing to classify.

GL_RENDERER on Windows: NVIDIA's OpenGL ICD has appended `/PCIe/SSE2` to every GeForce renderer string since at least the GeForce 6 era (confirmed unbroken through RTX 40/30/20 and GTX cards in this repo's own fixtures, e.g. `GpuClassifierTest.java:27` `"NVIDIA GeForce RTX 4090/PCIe/SSE2"`). I found a real Minecraft/Fabric crash report naming an RTX 5070 Ti as the GPU (`GPU: NVIDIA GeForce RTX 5070 Ti`) confirming the card shows up in the wild on modded Minecraft [[GitHub issue #316, meowdding/SkyOcean]](https://github.com/meowdding/SkyOcean/issues/316), but that report doesn't print the literal GL_RENDERER string. **So: "NVIDIA GeForce RTX 5090/PCIe/SSE2" etc. is UNVERIFIED as a literal string** (no RTX-50-specific log found with the exact renderer text) — high-confidence inference from the unbroken driver convention, not a confirmed log.

Coverage:
- 5090/5080/5070 Ti/5070 → **OK**, matched by existing `RTX\s*(?:40[89]0|50[789]0)\b` (tier 5). `50[789]0` = 5070/5080/5090; word-boundary after the digits means "RTX 5070 Ti" also matches (boundary sits between "5070" and the space before "Ti").
- 5060 Ti (8 GB and 16 GB) / 5060 → **OK**, matched by existing `RTX\s*(?:30[6-9]0|40[67]0|5060)\b` (tier 4); "5060 Ti" matches the same way "5070 Ti" does above. No VRAM-based split needed at the tier level — VRAM is handled separately by `vramMbAtLeast/AtMost` conditions elsewhere.
- 5050 (desktop) → **GAP**. No row matches "RTX 5050" (`50[789]0` needs a 7/8/9 in that slot; the generic `MX\d{3}|GT|GTS|GTX[4-9]\d0` row is GTX/MX-only). Falls to `gpuVendorFallback.nvidia = 3` today. Benchmarks put the 5050 ~32–75% faster than a GTX 1660 Super depending on suite [[nanoreview]](https://nanoreview.net/en/gpu-compare/geforce-rtx-5050-vs-geforce-gtx-1660-super), i.e. squarely where the existing GTX 1660 SUPER row already sits (tier 3). **Propose tier 3** — same as fallback, so this is a precision/documentation fix more than a behavior change, but it stops the 5050 from silently depending on the fallback default.

### NVIDIA GeForce RTX 50 series (laptop)

Same Jan 2025 CES wave as desktop, rolling into shipping laptops through 2025 [[Corsair]](https://www.corsair.com/us/en/explorer/gamer/gaming-pcs/rtx-5090-5080-and-5070-series-gpus-everything-you-need-to-know/). Renderer suffix historically `... Laptop GPU/PCIe/SSE2` (confirmed pattern for RTX 30/40 laptop parts in `GpuClassifierTest.java:28-29`); **RTX 50 laptop-specific renderer text is UNVERIFIED** (same reasoning as desktop — no RTX-50-laptop Minecraft log found).

Coverage:
- 5090/5080 Laptop → **OK**, matched by `RTX\s*(?:40[89]0|50[89]0)\b.*Laptop` (tier 4).
- 5070 Ti / 5070 / 5060 Laptop → **OK**, matched by `RTX\s*(?:30[6-8]0|40[67]0|50[67]0)\b.*Laptop` (tier 3). Note "5070 Ti Laptop GPU" matches this row (not the desktop tier-5 row above), same boundary logic as "5070 Ti" desktop — i.e. **laptop 5070 Ti lands one tier below desktop 5070 Ti**, which is directionally correct (laptop TGP-limited parts run meaningfully behind their desktop namesakes) but worth a maintainer sanity check since "Ti" suggests a stronger card than plain 5070.
- 5050 Laptop → **GAP**, same reasoning as desktop 5050; falls to the generic `(?:RTX|GTX)\s*\d{3,4}\b.*Laptop` catch-all (tier 2), which is arguably fine (weakest current-gen laptop discrete part) — **propose leaving as-is** (tier 2 via the catch-all) rather than adding a dedicated row, since it doesn't misclassify.

### AMD Radeon RX 9000 series (RDNA4)

RX 9070 XT / RX 9070 — Mar 6, 2025 [[GamersNexus]](https://gamersnexus.net/gpus/amd-rx-9070-9070-xt-gpu-prices-specs-release-date); RX 9060 XT — ~May 20/Jun 2025 [[PCGamesN]](https://www.pcgamesn.com/amd/radeon-rx-9060-xt-launch-date-rumor); RX 9060 (non-XT) — Aug 27, 2025 [[TechPowerUp]](https://www.techpowerup.com/339595/amd-officially-launches-radeon-rx-9060-non-xt-gpu); RX 9070 GRE — delayed from spring, launched ~Nov 2025 (confirmed shipping card by mid-Nov 2025) [[Tom's Hardware]](https://www.tomshardware.com/pc-components/gpus/amd-rx-9060-xt-gpus-reportedly-target-a-may-18-launch-rx-9070-gre-tipped-for-a-q4-release), review coverage exists [[PCGamesN review]](https://www.pcgamesn.com/amd/radeon-rx-9070-gre-review). All released as of 2026-09-26; nothing newer in the RX 9000 line found.

Linux/Mesa string confirmed from a real system: `"AMD Radeon RX 9070 XT (radeonsi, gfx1201, LLVM 20.1.8, DRM 3.64, 6.16.0-rc6-1-cachyos-rc)"` — RX 9070 XT is gfx1201, RX 9060 XT is gfx1200 [[OpenBenchmarking / Phoronix coverage]](https://www.phoronix.com/news/AMD-RDNA4-Kicker-Linux). Windows string follows the existing `AMD Radeon RX <model>` convention already in the table (`GpuClassifierTest.java:34,36-38`).

Coverage:
- RX 9070 XT → **OK**, matched by `RX\s*(?:6[89][05]0|7[89]00|90[7-9]0)\b` (tier 5).
- RX 9070 (non-XT) → **OK, but see hazard below** — matches the *same* tier-5 row as the XT.
- **RX 9070 GRE → BUG (ordering hazard).** "RX 9070 GRE" also matches `90[7-9]0\b` (the "9070" substring, boundary before the space) and lands in the **same tier-5 bucket as the full 9070/9070 XT**. The GRE is a materially cut-down die: 48 CU vs 9070's 56 CU (−14%), 12 GB/192-bit vs 16 GB/256-bit, and it benchmarks ~17-18% slower than the vanilla 9070 at 1440p/4K [[TechSpot review]](https://www.techspot.com/review/3133-amd-radeon-9070-gre/), putting it closer to the RX 9060 XT / previous-gen 7800 XT class (tier 4 in the current table) than to the 9070/9070 XT. **Propose inserting an explicit `RX\s*9070\s*GRE` row (tier 4) before the generic `90[7-9]0` row.**
- RX 9060 XT (16 GB and 8 GB) / RX 9060 (non-XT) → **OK**, both matched by `RX\s*(?:6[67][05]0|7[67]00|9060)\b` (tier 4). The non-XT is ~20-25% slower than the XT (28 vs 32 CU, lower clocks) per the same review coverage; sharing a tier is a coarse-granularity call the maintainers already make elsewhere (e.g. RTX 4080/4090 share tier 5) — **flagging as optional**, not a bug: propose leaving as one row unless the maintainer wants finer granularity, in which case split non-XT down to tier 3.
- Nothing newer than RX 9060 found in the RX 9000 line as of 2026-09-26.

### Intel Arc B-series (Battlemage)

Arc B580 — Dec 13, 2024; Arc B570 — Jan 16, 2025 [[Tom's Hardware roundup]](https://www.tomshardware.com/pc-components/gpus/intel-battlemage-arc-b-series-gpus-everything-we-know). Arc Pro B50 (16 GB) / Arc Pro B60 (24 GB) — announced Computex 2025 [[TweakTown]](https://www.tweaktown.com/news/105334/intel-arc-pro-b50-16gb-and-b60-24gb-gpus-announced-no-sign-of-b770-for-gamers/index.html); Arc Pro B70/B65 "Big Battlemage" — launched Mar 25, 2025 [[Notebookcheck]](https://www.notebookcheck.net/Intel-Arc-B70-Pro-and-B65-Pro-leak-reveals-Big-Battlemage-workstation-GPUs-with-32GB-ECC-GDDR6-ahead-of-March-25-launch.1255421.0.html). **Consumer Arc B770 — NOT launched as of 2026-09-26**, no official Intel announcement found, only rumors [[Tom's Hardware]](https://www.tomshardware.com/pc-components/gpus/intel-battlemage-arc-b-series-gpus-everything-we-know) — no action needed (though see hazard below, since the existing table already has a row for it).

Renderer format is the established `Intel(R) Arc(TM) <model> Graphics` convention (already used for A-series in the table/tests, e.g. `GpuClassifierTest.java:46` `"Intel(R) Arc(TM) A770 Graphics"`); I could not find a Minecraft-specific log or driver dump with the literal B580 string, so **exact B580/B570/B60/B50 GL_RENDERER text is UNVERIFIED** (naming convention is well established, but not confirmed for these specific SKUs).

Coverage:
- Arc B580 / B570 → **OK**, matched by existing `Arc(?:\s*\(TM\))?\s*(?:A7[5-9]0|B5[78]0|B7\d0)\b` (tier 4) via the `B5[78]0` alternative — **this row was clearly already written with B580/B570 in mind** (and pre-emptively includes `B7\d0` for a still-unreleased B770).
- **Arc Pro B50 / B60 → BUG.** Neither matches `B5[78]0` (needs literal "B570"/"B580", not "B50"/"B60") nor `B7\d0`, nor the mobile `A\d{3}M` row. They fall through to the catch-all `Arc(?:\s*\(TM\))?\s*(?:\d{3}V|B3\d0|Graphics)` row (tier 3, **`integrated: true`**) — because the renderer string ends in the literal word "Graphics". This **misclassifies a discrete, dedicated-VRAM workstation card as an integrated GPU**, which matters because `gpuIntegrated`-gated conditions (e.g. avoiding Nvidium-style GPU-upload mods on iGPUs) would then wrongly treat a Pro B50/B60 as integrated. Reviews put the B50 rivaling mid-tier gaming GPUs at 1080p and the B60 ~20% faster than the B50 [[LTT Labs]](https://www.lttlabs.com/articles/2025/10/03/intel-arc-pro-b50-your-workstation-deserves-nice-things-too), [[Geeky Gadgets]](https://www.geeky-gadgets.com/intel-arc-pro-b50-gaming-performance/) — roughly B570/B580 territory. **Propose a new row before the catch-all**: `Arc(?:\s*\(TM\))?\s*Pro\s*B[5-7]\d\b` (covers B50/B60/B65/B70), `vendor: intel`, `integrated: false`, tier 4 (B50 arguably tier 3, B60/B65/B70 tier 4 — see §3 for a split option).

### Integrated GPUs

- **Radeon 890M / 880M** (Ryzen AI 9 HX 370 / Ryzen AI 9 365, "Strix Point", launched with reviews from Jul 28, 2024 [[Tom's Hardware]](https://www.tomshardware.com/pc-components/cpus/amds-upcoming-ryzen-ai-9-hx-370-beats-the-companys-current-best-mobile-chip-strix-point-es-geekbench-results-show-big-improvements)) → **OK**, matched by existing `Radeon(?:\s*\(TM\))?\s*(?:680M|7[6-8]0M|8[89]0M)` (tier 3) via `8[89]0M`.
- **Radeon 8060S / "Strix Halo"** (Ryzen AI Max/Max+ 300 series, debuted Jan 2025 [[VideoCardz]](https://videocardz.com/newz/amd-launches-ryzen-ai-max-392-and-max-388-strix-halo-apus-with-radeon-8060s-graphics), new lower-CPU-core SKUs 392/388 added in early 2026) → **OK**, matched by existing `Radeon(?:\s*\(TM\))?\s*80[4-6]0S` (tier 4) via the `80[4-6]0S` alternative — already covers 8040S/8050S/8060S.
- **Radeon 780M / 760M** (Phoenix/Hawk Point, already shipping since 2023) → **OK**, matched by the same `7[6-8]0M` alternative used for 780M above (tier 3).
- **Intel Arc 140V / 130V** (Lunar Lake / Core Ultra 200V, launched Sep 2024) → **OK**, matched by existing `Arc(?:\s*\(TM\))?\s*(?:\d{3}V|B3\d0|Graphics)` (tier 3, integrated) via `\d{3}V`.
- **Intel Arc 140T** — this is a **Lunar Lake** name (confirmed — not Panther Lake) [[Tom's Hardware]](https://www.tomshardware.com/pc-components/gpus/intel-arc-b370-xe3-igpu-appears-on-furmark-2-panther-lake-graphics-fall-14-percent-behind-last-gen-xe2-arc-140v). Same `\d{3}T` shape isn't in the table at all today — **GAP**, falls to the `Graphics` catch-all in the same row anyway (tier 3, integrated), so effectively harmless, but the pattern should arguably include `\d{3}T` explicitly for clarity/robustness (a "T"-suffixed model that happens not to contain the literal word "Graphics" would currently miss). Low priority.
- **Panther Lake Xe3 iGPU (Core Ultra 300, "Arc B370"/"Arc B390")** — launched CES Jan 5 2026, retail Jan 27 2026 [[VideoCardz]](https://videocardz.com/newz/intel-core-ultra-300-panther-lake-officially-launches-at-ces-2026-on-january-5th); Intel's own Xe3 branding for the top Panther Lake iGPU tiers is literally **"Arc B370"** (10 Xe cores) / **"Arc B390"** (12 Xe cores) [[Tom's Hardware]](https://www.tomshardware.com/pc-components/gpus/intel-arc-b370-xe3-igpu-appears-on-furmark-2-panther-lake-graphics-fall-14-percent-behind-last-gen-xe2-arc-140v), [[VideoCardz]](https://videocardz.com/newz/intel-arc-b390-panther-lake-igpu-shows-promising-results-in-alleged-3dmark-tests). → **OK, already covered** — the existing catch-all row already includes `B3\d0` (matches "B370"/"B390"), and it sits *after* the discrete-Arc row (`B5[78]0|B7\d0`) in file order, so there's no clash with B580/B570/B770. This looks like it was already added with Panther Lake in mind. **No change needed**, but worth flagging that the naming is a near-miss hazard (see §3): Panther Lake's "B3xx" family and desktop Battlemage's "B5xx/B7xx" family share the "B" + 3-digit convention, so any future edit to the discrete-GPU row must not widen its digit class to accidentally swallow "B3xx".
- **Apple M-series GPU (M3/M4/M5, all variants)** → **OK, no change needed at all.** The existing rows (`Apple\s*M\d+\s*Ultra` tier 5, `Apple\s*M\d+\s*(?:Pro|Max)` tier 4, `Apple\s*M\d+` tier 3) are generation-agnostic by construction — they already match M5 Ultra/M5 Pro/M5 Max/M5 base exactly as they matched M1/M2. GL_RENDERER on macOS Java Minecraft is confirmed to be exactly the chip marketing name (`"Apple M2"`, `"Apple M3 Max"` already in `GpuClassifierTest.java:48-49`); by the same convention **"Apple M4 Pro"/"Apple M5"/"Apple M5 Ultra" are HIGH-CONFIDENCE, not directly log-verified for M4/M5 specifically** (no M4/M5 Minecraft log found — mark UNVERIFIED for the exact string, though the format itself is not in question).
- **Qualcomm Snapdragon X / Adreno X1** → Minecraft Java runs natively on Windows-on-Arm (native arm64 build) with good performance reported on Snapdragon X2 Elite [[LTT forum]](https://linustechtips.com/topic/1636780-snapdragon-x2-elite-extreme-chip-personal-performance-results-minecraft/). The renderer string `"Qualcomm(R) Adreno(TM) X1-85 GPU"` is **already in this repo's own test suite** (`GpuClassifierTest.java:50`), so it's effectively pre-verified. → **OK, already covered** by the generic `(?i)Adreno` row (tier 2, integrated). No X1-series-specific row exists, but given Adreno X1 spans a range of tiers (X1-45 up to X1-85 in laptops, weaker in phones/tablets that don't run Minecraft anyway), a flat tier 2 is a safe, conservative default — **propose leaving as-is**.

## 2. CPUs — coverage audit against the current 8 `cpuTiers` rows

- **AMD Ryzen 9000 X3D (9800X3D — Nov 2024; 9950X3D & 9900X3D — Mar 12, 2025 [[HotHardware]](https://hothardware.com/news/amd-ryzen-9-9950x3d-9900x3d-pricing-launch-date-revealed))** → **OK, no change needed.** OSHI name strings follow the existing pinned convention (`"AMD Ryzen 7 7800X3D 8-Core Processor"` is in `RulesV1DifferentialTest.java:268` today) → high-confidence `"AMD Ryzen 9 9950X3D 16-Core Processor"`, `"...9900X3D 12-Core Processor"`, `"AMD Ryzen 7 9800X3D 8-Core Processor"` (UNVERIFIED as literal strings — no OSHI dump found for these specific SKUs, but the naming convention is well-established and unchanged across generations). All three already match the existing `Ryzen\s*\d\s*\d{4}X3D` row (tier 5) — this pattern is generation-agnostic by construction (matches any single digit + 4-digit model + "X3D").
- **AMD Ryzen 9000 non-3D (9950X, 9900X, 9700X, 9600X, Aug 2024)** → **OK**, matched by existing `Ryzen\s*[579]\s*[79]\d{3}X\b` (tier 5) — also generation-agnostic.
- **AMD Ryzen AI 300 / Max (Strix Point Jul 2024, Strix Halo Jan 2025)** and **Ryzen 8000G (Jan 2024)** → **GAP, but safely conservative.** No explicit row; falls to the core-count formula. Strix Halo (16 C/32 T) and 8000G desktop APUs (8 C/16 T) have enough threads to reach tier 5/tier 4 via the formula on their own merits, so this is not a correctness bug, just unexercised by an explicit rule — **no row proposed**, formula already lands close to the right answer.
- **Intel Core Ultra 200S "Arrow Lake" K-series (285K/265K/245K)** → **OK**, matched by existing `Core\(TM\)\s*Ultra\s*[579]\s*2\d{2}K` (tier 5) — generic on the last-3-digits, so it already covers every Arrow Lake-S K SKU without needing a new row.
- **Core Ultra 200H/200HX (Arrow Lake mobile), Core Ultra 200V (Lunar Lake)** → **GAP, safely conservative.** No K-suffix, so these fall to the core-count formula (8 cores/no-SMT on Lunar Lake → tier 3; high-core-count Arrow Lake-HX → tier 4/5 by thread count). Reasonable without an explicit row given these aren't cache-boosted gaming parts the way X3D is; **no row proposed**, flagged for the maintainer as a judgment call if Lunar Lake's per-core IPC is felt to be underrated at tier 3.
- **Core Ultra 300 "Panther Lake" (launched CES Jan 5 2026, retail Jan 27 2026 — see GPU section for citation)** → same situation as Arrow Lake-H: no K-suffix on mobile SKUs, falls to core-count formula. **No row proposed** without a confirmed OSHI name string (none found).
- **Intel 14th gen refreshes (14900K/14900KS etc.)** → **OK**, already matched by the existing `Core\(TM\)\s*i[579]-1[2-4]\d{3}K` row (tier 5); this predates v0.3 and needs no change.
- **Apple M-series CPU (M3/M4/M5, all variants)** → **OK, no change needed**, same generation-agnostic argument as the GPU rows: `Apple\s*M\d+\s*(?:Pro|Max|Ultra)` (tier 5) / `Apple\s*M\d+` (tier 4) already cover every future M-series chip by construction.
- **Qualcomm Snapdragon X (Oryon)** → **GAP, safely conservative.** No explicit row. Snapdragon X Elite/Plus (12 cores, no SMT) falls to the formula: ≤12 cores → tier 4. That's a reasonable, conservative placement for a chip that benchmarks roughly mid-pack against x86 (strong multi-thread, less dominant single-thread vs top desktop parts) — **no row proposed**; exact OSHI processor-name string for Snapdragon X on Windows is **UNVERIFIED** (no confirmed dump found), so an explicit pattern isn't safely writable yet anyway.

## 3. Proposed rows (ready to paste into `rules/source/knowledge.json`)

All four keep the file's existing 2-space/inline-object style. Insert each **before** the row noted, to preserve first-match-wins semantics.

```jsonc
// gpuTiers — insert as the FIRST amd/RX row that mentions "9070" (i.e. immediately
// before the existing `"(?i)RX\s*(?:6[89][05]0|7[89]00|90[7-9]0)\b"` tier-5 row).
// Fixes: RX 9070 GRE currently inherits tier 5 from the generic "9070" match.
{ "pattern": "(?i)RX\\s*9070\\s*GRE\\b", "vendor": "amd", "integrated": false, "tier": 4 },
```

```jsonc
// gpuTiers — insert BEFORE the existing intel catch-all row
// `"(?i)Arc(?:\s*\(TM\))?\s*(?:\d{3}V|B3\d0|Graphics)"` (tier 3, integrated: true).
// Fixes: Arc Pro B50/B60/B65/B70 (discrete workstation cards) currently fall into
// that catch-all and get misclassified as an integrated GPU.
{ "pattern": "(?i)Arc(?:\\s*\\(TM\\))?\\s*Pro\\s*B[5-7]\\d\\b", "vendor": "intel", "integrated": false, "tier": 4 },
// Optional finer split instead of the single row above (B50 reviews as a notch
// below B60/B65/B70 — see §1):
{ "pattern": "(?i)Arc(?:\\s*\\(TM\\))?\\s*Pro\\s*B50\\b", "vendor": "intel", "integrated": false, "tier": 3 },
{ "pattern": "(?i)Arc(?:\\s*\\(TM\\))?\\s*Pro\\s*B[67]\\d\\b", "vendor": "intel", "integrated": false, "tier": 4 },
```

```jsonc
// gpuTiers — desktop RTX 5050, insert alongside the other NVIDIA desktop rows
// (anywhere after the RTX 5060/30xx/40xx tier-4 row, before the generic
// GTX/MX catch-all). Currently falls to gpuVendorFallback.nvidia = 3 — this row
// makes the same result explicit rather than implicit.
{ "pattern": "(?i)RTX\\s*5050\\b(?!\\s*Ti)", "vendor": "nvidia", "integrated": false, "tier": 3 },
```

No CPU rows are proposed — every CPU family in the brief either already matches an existing, generation-agnostic pattern (X3D, non-3D Ryzen X-series, Arrow Lake-S K-series, Apple M-series) or safely lands on a reasonable tier via the core-count formula with no confirmed OSHI string to key a new pattern on (Strix Halo/8000G/Arrow Lake-H/Lunar Lake/Panther Lake/Snapdragon X).

## 4. Proposed scenario test cases

For `GpuClassifierTest.CASES` (renderer → expected vendor/integrated/tier) and the CPU equivalent, plus explicit negative cases:

| Renderer / CPU string | Expected | Why (negative cases marked ✗) |
|---|---|---|
| `NVIDIA GeForce RTX 5090/PCIe/SSE2` | NVIDIA, discrete, tier 5 | existing row, add as regression coverage |
| `NVIDIA GeForce RTX 5070 Ti/PCIe/SSE2` | NVIDIA, discrete, tier 5 | confirms `50[789]0\b` + trailing "Ti" doesn't get missed |
| `NVIDIA GeForce RTX 5060 Ti/PCIe/SSE2` | NVIDIA, discrete, tier 4 | confirms shared bucket with plain 5060 |
| `NVIDIA GeForce RTX 5050/PCIe/SSE2` | NVIDIA, discrete, tier 3 | exercises the new proposed row |
| ✗ `NVIDIA GeForce RTX 5050 Ti/PCIe/SSE2` (hypothetical, not yet released) | should NOT be swallowed by the new 5050 row if a real "5050 Ti" ever ships — the proposed pattern has a negative lookahead `(?!\s*Ti)` precisely so a future Ti variant falls through to a dedicated row instead of silently inheriting tier 3 |
| `NVIDIA GeForce RTX 5090 Laptop GPU/PCIe/SSE2` | NVIDIA, discrete, tier 4 | laptop 5090/5080 bucket |
| `NVIDIA GeForce RTX 5070 Ti Laptop GPU/PCIe/SSE2` | NVIDIA, discrete, tier 3 | laptop 5070 Ti lands a tier below desktop 5070 Ti — confirm intentional |
| `AMD Radeon RX 9070 XT` | AMD, discrete, tier 5 | |
| `AMD Radeon RX 9070 GRE` | AMD, discrete, **tier 4** | regression test for the ordering fix — must NOT be tier 5 |
| `AMD Radeon RX 9070 XT (radeonsi, gfx1201, LLVM 20.1.8, DRM 3.64, 6.16.0)` | AMD, discrete, tier 5 | Mesa/Linux string variant |
| `AMD Radeon RX 9060 XT` / `AMD Radeon RX 9060` | AMD, discrete, tier 4 | both share a bucket (documented, not a bug) |
| `Intel(R) Arc(TM) B580 Graphics` | Intel, discrete, tier 4 | existing row |
| `Intel(R) Arc(TM) Pro B60 Graphics` | Intel, **discrete** (`integrated: false`), tier 4 | regression test for the misclassification fix — must NOT come back `integrated: true` |
| `Intel(R) Arc(TM) Pro B50 Graphics` | Intel, discrete, tier 3 or 4 depending on which proposal is taken | |
| ✗ `Intel(R) Graphics` (generic Panther Lake low-tier SKU, no "Arc"/model number) | falls through to Intel vendor fallback tier 2, integrated heuristic true — confirms a plain "Intel Graphics" string (no digits) isn't accidentally caught by the new Pro-B row | |
| `AMD Radeon(TM) 8060S Graphics` | AMD, integrated, tier 4 | Strix Halo — confirm existing row still fires after any edits near it |
| `AMD Radeon(TM) 890M Graphics` | AMD, integrated, tier 3 | Strix Point |
| `Apple M5` | Apple, integrated, tier 3 | confirms generation-agnostic row still works past M4 |
| `Apple M5 Pro` / `Apple M5 Max` | Apple, integrated, tier 4 | |
| `Apple M5 Ultra` | Apple, integrated, tier 5 | |
| `Qualcomm(R) Adreno(TM) X1-85 GPU` | Qualcomm, integrated, tier 2 | already in the suite; re-assert unchanged |
| CPU: `AMD Ryzen 9 9950X3D 16-Core Processor` | tier 5 | |
| CPU: `AMD Ryzen 9 9900X3D 12-Core Processor` | tier 5 | |
| CPU: `AMD Ryzen 7 9800X3D 8-Core Processor` | tier 5 | |
| CPU: `Intel(R) Core(TM) Ultra 9 285K` | tier 5 | existing row, regression only |
| ✗ CPU: `Intel(R) Core(TM) Ultra 7 258V` (Lunar Lake, no K) | falls to formula (8 cores → tier 3) — confirm it does NOT match the `...2\d{2}K` row (no trailing K) | |

## 5. The 0.1.x safety analysis

**Key structural fact: there is no `v1` override for tier tables.** Every other rule kind (`ModRule`, `SettingRule`, `AdviceRule`, `ObsoleteRule`) can carry a `"v1"` override or `"v1": false` to give 0.1.x a different (always more conservative) view. `GpuTierRule`/`CpuTierRule` cannot — `docs/RULES_SCHEMA.md` is explicit that tier rules have no fail-closed handling and are "copied as they are" into `rules-v1.json`. **So all four proposed rows above go into `rules-v1.json` automatically and identically, with no choice in the matter** — the question isn't "should this row go to v1", it's "is it safe for it to".

Why it's safe here, specifically:

1. **All four rows only match hardware that did not exist when 0.1.0 was pinned** (RX 9070 GRE: Nov 2025; Arc Pro B50/B60/B65/B70: Mar–Jun 2025; RTX 5050: Jul 2025) or hardware already handled by a *different, existing* rule for a *different* string (the GRE fix, the Arc Pro fix). None of them can retroactively change the classification of a hardware string that a real 0.1.x installation could have been running when it shipped, because those strings didn't exist yet.
2. **The real regression vector is regex breadth, not "should this be in v1"**: a new pattern is only dangerous if it's broad enough to *also* match a string that predates it and that some *other* rule (a `SettingRule`/`ModRule`/`AdviceRule` gated on `gpuTierAtLeast`/`tierAtLeast`, etc.) treats differently at the old vs. new tier — this is exactly the class of bug `RulesV1DifferentialTest` exists to catch (see its `theDifferentialCatchesALostDisable` test, referencing "review 3, compat-1": a GPU-tier change once silently stopped 0.1.x from warning to disable Nvidium on a tier-2 NVIDIA card). I checked each proposed pattern against this: `RX\s*9070\s*GRE\b` requires the literal substring "GRE", `Arc...Pro\s*B[5-7]\d\b` requires the literal substring "Pro" before the model number, and `RTX\s*5050\b(?!\s*Ti)` requires the literal "5050" — none of these substrings appear in any pre-existing row's example strings or in `GpuClassifierTest`'s/`RulesV1DifferentialTest`'s fixed hardware fixtures, so none can change classification of hardware that predates them.
3. **Important blind spot to flag for the maintainer**: `RulesV1DifferentialTest.hardware()` (`src/test/java/.../RulesV1DifferentialTest.java:265-300`) is a **fixed, hand-written hardware matrix** (RX 7800 XT, RTX 4090, RTX 2060, GTX 1080/1050 Ti, Apple M1, `AMD Radeon(TM) Graphics`, `llvmpipe`, Arc A770, etc.) — it contains **none** of the new 2025/2026 hardware from this brief. That means the differential test provides **zero regression coverage** for whether these new rows behave correctly going forward; it only protects the *existing* fixture strings from ever being reclassified. This is a correct and adequate safety net for "don't make 0.1.x less conservative for hardware it already knew about," but it is **not** a safety net for "did we tier the new hardware correctly" — that has to be reviewed by hand (as done in §1–§3 here) every time, and ideally the maintainer adds at least one representative new-generation entry (e.g. an RTX 5070 or RX 9070 XT hardware profile) to that fixed matrix so future edits near these rows do get differential coverage.
4. **Net effect on 0.1.x users**: the GRE fix and Arc Pro fix are both *corrections downward/toward-accuracy* relative to what an unmodified 0.1.x (or an un-fixed rules-v1.json) would currently compute for those specific strings (GRE was overclassified at tier 5; Arc Pro was misclassified as integrated) — i.e. they make 0.1.x *more* accurate, and the GRE fix specifically makes it *more conservative* (tier 4 instead of tier 5) for that one string. The RTX 5050 row and any CPU rows (none proposed) are net-new classifications of hardware that currently only has the vendor-fallback/formula tier, so there is no "before" behavior to regress from 0.1.x's perspective.

**Conclusion: safe to add to both `rules-v1.json` and `rules-v2.json` in the same updater run** (as they must be — no other option exists for tier tables), provided (a) each new pattern is checked by hand against `GpuClassifierTest`'s and `RulesV1DifferentialTest`'s existing fixture strings before merging (done above, all clear), and (b) `./gradlew build` is re-run afterward so `RulesV1DifferentialTest` and `SchemaConsistencyTest` both pass, per `tools/README.md`'s standard post-regen step.
