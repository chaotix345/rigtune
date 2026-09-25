# RigTune rules update review

Target MC versions: 26.3, 26.2, 26.1.2. Newest: 26.3.

## Summary
- New upstream mods to triage: 0
- Rule mods with a status or removal concern: 0
- Rule mods missing a Fabric build for 26.3: 4
- Rules changed or omitted in rules-v1.json: 24

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
| mods[nvidium] | v1 override: avoidWhen, recommendWhen |
| mods[renderscale] | v1 override: reason, recommendWhen |
| mods[lambdynamiclights] | omitted ("v1": false) |
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
| advice[heavy-shaders] | v1 override: when |
| advice[shaders-entry-level] | omitted ("v1": false) |
