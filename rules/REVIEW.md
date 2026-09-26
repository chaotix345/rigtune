# RigTune rules update review

Target MC versions: 26.3, 26.2. Newest: 26.3.

## Summary
- New upstream mods to triage: 0
- Rule mods with a status or removal concern: 0
- Rule mods missing a Fabric build for 26.3: 4
- Rules changed or omitted in rules-v1.json: 44

## (a) Upstream mods not yet tracked in knowledge.json
None found.

## (b) Rule mods needing a status check
None found.

## (c) Rule mods with no Fabric release for 26.3
| slug | title |
|---|---|
| moonrise-opt | Moonrise |
| krypton | Krypton |
| vulkanmod | VulkanMod |
| particle-core | Particle Core |

## (d) Omitted from rules-v1.json
0.1.x clients read rules-v1.json, the v1 projection of these rules. Check that nothing below makes 0.1.x less safe, in particular that an omitted setting entry doesn't change which entry wins for a key.

| rule | change |
|---|---|
| gpuTiers[6] (?i)RTX\s*5050\b(?!\s*Ti) | omitted ("v1": false) |
| gpuTiers[18] (?i)RX\s*9070\s*GRE\b | omitted ("v1": false) |
| gpuTiers[29] (?i)Arc(?:\s*\(TM\))?\s*Pro\s*B50\b | omitted ("v1": false) |
| gpuTiers[30] (?i)Arc(?:\s*\(TM\))?\s*Pro\s*B[67]\d\b | omitted ("v1": false) |
| mods[nvidium] | v1 override: avoidWhen, recommendWhen |
| mods[renderscale] | v1 override: reason, recommendWhen |
| mods[lambdynamiclights] | omitted ("v1": false) |
| mods[distanthorizons] | omitted ("v1": false) |
| settings[8] vanilla.renderDistance | v1 override: when |
| settings[9] vanilla.renderDistance | v1 override: when |
| settings[40] dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius | omitted ("v1": false) |
| settings[41] dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius | omitted ("v1": false) |
| settings[42] dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius | omitted ("v1": false) |
| settings[43] dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius | omitted ("v1": false) |
| settings[44] dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius | omitted ("v1": false) |
| settings[45] dh.client.advanced.graphics.quality.verticalQuality | omitted ("v1": false) |
| settings[46] dh.client.advanced.graphics.quality.horizontalQuality | omitted ("v1": false) |
| settings[47] dh.client.advanced.graphics.quality.horizontalQuality | omitted ("v1": false) |
| settings[48] dh.client.advanced.graphics.quality.maxHorizontalResolution | omitted ("v1": false) |
| settings[49] dh.common.multiThreading.numberOfThreads | omitted ("v1": false) |
| settings[50] dh.common.multiThreading.numberOfThreads | omitted ("v1": false) |
| settings[51] dh.common.multiThreading.numberOfThreads | omitted ("v1": false) |
| settings[52] dh.common.multiThreading.numberOfThreads | omitted ("v1": false) |
| settings[53] iris.maxShadowRenderDistance | omitted ("v1": false) |
| settings[54] iris.maxShadowRenderDistance | omitted ("v1": false) |
| settings[55] iris.maxShadowRenderDistance | omitted ("v1": false) |
| advice[ram-distant-horizons] | v1 override: when |
| advice[ram-shaders] | v1 override: when |
| advice[ram-distant-horizons-shaders-limited] | omitted ("v1": false) |
| advice[vulkan-backend] | v1 override: when |
| advice[heavy-shaders] | v1 override: when |
| advice[shaders-entry-level] | omitted ("v1": false) |
| advice[spark-profiler] | omitted ("v1": false) |
| advice[jvm-ignored-flags] | omitted ("v1": false) |
| advice[jvm-young-gen-fixed] | omitted ("v1": false) |
| advice[jvm-stop-the-world-gc] | omitted ("v1": false) |
| advice[jvm-no-gc] | omitted ("v1": false) |
| advice[jvm-server-flags] | omitted ("v1": false) |
| advice[jvm-explicit-gc-disabled] | omitted ("v1": false) |
| advice[jvm-zgc-small-heap] | omitted ("v1": false) |
| advice[jvm-zgc-small-pc] | omitted ("v1": false) |
| advice[jvm-xmx-duplicate] | omitted ("v1": false) |
| advice[driver-nvidia-threaded-optimization] | omitted ("v1": false) |
| advice[driver-intel-gen7-old] | omitted ("v1": false) |
