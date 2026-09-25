# RigTune rules update review

Target MC versions: 26.3, 26.2, 26.1.2. Newest: 26.3.

## Summary
- New upstream mods to triage: 33
- Rule mods with a status or removal concern: 0
- Rule mods missing a Fabric build for 26.3: 4
- Rules changed or omitted in rules-v1.json: 0

## (a) Upstream mods not yet tracked in knowledge.json
| slug | title | pack(s) | optimization category |
|---|---|---|---|
| animaticarefabricated | Animatica Refabricated | Fabulously Optimized, Additive | no |
| better-mount-hud | Better Mount HUD | Fabulously Optimized | no |
| calcmod | CalcMod | Additive | no |
| cape-provider | Cape Provider | Fabulously Optimized, Additive | no |
| cloth-config | Cloth Config API | Fabulously Optimized, Additive | no |
| configmanager | Config Manager | Fabulously Optimized, Additive | no |
| continuity | Continuity | Fabulously Optimized, Additive | no |
| controlify | Controlify (Controller support) | Additive | no |
| crash-assistant | Crash Assistant | Fabulously Optimized, Additive | no |
| e4mc | e4mc | Fabulously Optimized, Additive | no |
| ears | Ears | Additive | no |
| entity-model-features | [EMF] Entity Model Features | Fabulously Optimized, Additive | no |
| entitytexturefeatures | [ETF] Entity Texture Features | Fabulously Optimized, Additive | no |
| esf | [ESF] Entity Sound Features | Additive | no |
| fabric-api | Fabric API | Fabulously Optimized, Additive | no |
| fabric-language-kotlin | Fabric Language Kotlin | Fabulously Optimized, Additive | no |
| forge-config-api-port | Forge Config API Port | Fabulously Optimized | no |
| fzzy-config | Fzzy Config | Additive | no |
| lambdabettergrass | LambdaBetterGrass | Additive | no |
| lambdynamiclights | LambDynamicLights - Dynamic Lights | Fabulously Optimized, Additive | no |
| main-menu-credits | Main Menu Credits | Fabulously Optimized, Additive | no |
| mixintrace-reborn | MixinTrace Reborn | Fabulously Optimized | no |
| modmenu | Mod Menu | Fabulously Optimized, Additive | no |
| morechathistory | More Chat History | Fabulously Optimized | no |
| optigui | OptiGUI | Fabulously Optimized, Additive | no |
| placeholder-api | Text Placeholder API | Fabulously Optimized | no |
| reeses-sodium-options | Reese's Sodium Options | Fabulously Optimized | no |
| renice-shot | Renice Shot | Fabulously Optimized | no |
| resourceful-config | Resourceful Config | Additive | no |
| skyboxify | Skyboxify | Additive | no |
| sodium-shadowy-path-blocks | Sodium Shadowy Path Blocks (SSPB) | Fabulously Optimized | no |
| yacl | YetAnotherConfigLib (YACL) | Additive | no |
| zconfig | ZConfig | Additive | no |

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

None.
