# RigTune v0.2.0 — MC 26.2 vs 26.3 API diff

Scope: every Minecraft/Mojang/Fabric API/Mod Menu/LWJGL symbol RigTune's `src/client`, `src/gametest`, `src/test` (and confirmed-empty `src/main`) actually reference, diffed with `javap -p -s` against the real jars for both versions. Today: 2026-09-25. Target: one codebase supporting MC 26.2 (current) and 26.3 (released 2026-09-15).

## 1. Summary

**`src/main/java` confirmed clean**: `grep -rn "^import net.minecraft\|^import com.mojang\|^import net.fabricmc\|^import org.lwjgl" src/main/java` → no matches. Core module needs no version-conditional code.

Counts across ~70 dumped classes / ~120 individual members checked:

| Status | Count | Notes |
|---|---|---|
| SAME | ~50 classes / ~100 members | Full byte-identical `javap` output, or the exact overload/field RigTune uses is untouched |
| REMOVED (no drop-in) | 2 | `Window.getRefreshRate()`, `Window.isFullscreen()` |
| RENAMED (merged) | 1 | `InputConstants.Type.KEYSYM`/`SCANCODE` → `KEYBOARD` |
| MOVED (package) | 2 | `com.mojang.blaze3d.systems.{DeviceInfo,GpuDevice}` → `com.mojang.renderpearl.api.device.*` |
| CHANGED (signature) | 1 breaking, 1 additive | `VideoMode.getRefreshRate()` `int`→`float`; `GuiEventListener` gains a default method (non-breaking) |
| BEHAVIOUR-CHANGED (same symbol, different value) | 2 | `InputConstants.KEY_F8` 297→65, `KEY_ESCAPE` 256→41 (GLFW keysym → SDL scancode) |

**Files that need version-conditional code**, with suggested fix:

| File:line | Symbol | Fix |
|---|---|---|
| `src/client/java/.../client/RigTuneClient.java:66` | `InputConstants.Type.KEYSYM` | Use `KEYBOARD` on 26.3; needs a per-version compile (see below), not a runtime branch — it's referenced as a compile-time enum constant. |
| `src/client/java/.../client/RigTuneClient.java:66` | `InputConstants.KEY_F8` | Symbol name is stable but its `int` value is inlined by javac at compile time (297 on 26.2, 65 on 26.3). A single class file built against one version's constant is silently wrong on the other — this alone forces per-version compilation of the client source set, it isn't fixable with a runtime `if`. |
| `src/gametest/java/.../gametest/RigTuneClientGameTest.java:136` | `InputConstants.KEY_ESCAPE` | Same constant-inlining issue (256→41). Same fix (recompile per version). |
| `src/client/java/.../client/probe/HardwareProbe.java:6-7,74,76` | `com.mojang.blaze3d.systems.{GpuDevice,DeviceInfo}` | Import moved to `com.mojang.renderpearl.api.device.*`. The import statement itself won't compile against both versions — needs a small `GpuInfoReader` interface with one impl per version source set (mirrors how the constant-inlining issue already forces a split). |
| `src/client/java/.../client/probe/HardwareProbe.java:90` | `Window.getRefreshRate()` | Removed. Replace with `window.getActiveVideoMode().getRefreshRate()` (26.3; returns `float`, round to `int`). 26.2 has no `getActiveVideoMode()` — version-conditional call. |
| `src/client/java/.../client/probe/HardwareProbe.java:96` | `VideoMode.getRefreshRate()` | `int` → `float` return. Round when assigning into the existing `int refresh` local; a version-split call site can just return different types and round once at the call site. |
| `src/client/java/.../client/probe/HardwareProbe.java:98` | `Window.isFullscreen()` | Removed, **no confirmed public replacement** (see §4). UNVERIFIED best option below. |
| `src/client/java/.../client/benchmark/BenchmarkController.java:88` | `minecraft.getWindow().getRefreshRate()` | Same removal as HardwareProbe:90, same fix. |

Everything else RigTune touches — the private-reflection path in `SettingsBridge.visit()` (`Options$FieldAccess`/`processOptions`), all of `Minecraft`'s fields RigTune reads, the `GuiGraphicsExtractor`/`Font`/`ContainerObjectSelectionList`/`Button`/`Checkbox`/`CycleButton`/`Tooltip`/`SystemToast`/`MouseButtonEvent` family, the `DebugScreenOverlay` mixin target, every Fabric API symbol used, and Mod Menu's `ModMenuApi`/`ConfigScreenFactory` — is byte-identical between the two versions (§3, §5).

Packaging note (not an API diff, but blocks 26.3 regardless of code fixes): `fabric.mod.json`'s `"minecraft": "~26.2"` and `gradle.properties`' `minecraft_version=26.2` need to become a version range or a second build variant.

## 2. Library / LWJGL diff (26.2 vs 26.3 version JSON `libraries`)

Source: `https://piston-meta.mojang.com/v1/packages/33c420747ce582e48dff1d8c5d8e67e5bb6257c9/26.2.json` vs `.../bc098d111a72e9f6178801544a42099bdfbb0cf2/26.3.json`.

| Library | 26.2 | 26.3 |
|---|---|---|
| `org.lwjgl:lwjgl*` (core + most modules) | 3.4.1 | 3.4.3 |
| `org.lwjgl:lwjgl-glfw` | **present** | **removed entirely** |
| `org.lwjgl:lwjgl-sdl` | absent | **added**, 3.4.3 |
| `org.lwjgl:lwjgl-tinyfd` | present | **removed**, no replacement module in the manifest (native file/message dialogs — RigTune doesn't use this, no impact) |
| `org.lwjgl:*:natives-windows-x86` (32-bit) | present | **removed** across all lwjgl modules (consistent with the `javaVersion.majorVersion: 25` floor already in `gradle.properties`) |
| `com.mojang:authlib` | 9.0.75 | 10.0.77 |
| `com.mojang:brigadier` | 1.3.10 | 1.3.11 |
| `com.mojang:jtracy` | 1.0.37 | 1.14.38 |
| `io.netty:netty-*` | 4.2.15.Final | 4.2.16.Final |
| `org.joml:joml` | 1.10.8 | 1.10.9 |
| `com.mojang:datafixerupper`, `com.google.code.gson:gson`, `org.jspecify:jspecify`, `org.slf4j:slf4j-api` | unchanged | unchanged |

26.4-snapshot-1's library list is byte-identical to 26.3's (checked cheaply, jar not downloaded) — the GLFW→SDL swap looks settled going into the next snapshot, not one-off churn. **SNAPSHOT, may change.**

## 3. Per-class table (only classes with a difference; everything else is byte-identical `javap -p -s` output)

| Symbol | 26.2 | 26.3 | Status | Our uses | Fix |
|---|---|---|---|---|---|
| `com.mojang.blaze3d.systems.GpuDevice` | `com.mojang.blaze3d.systems.GpuDevice` (interface, `getDeviceInfo()` etc.) | `com.mojang.renderpearl.api.device.GpuDevice` — same method names/shape, new package | MOVED | `HardwareProbe.java:7,74` | Version-specific probe impl (see §1) |
| `com.mojang.blaze3d.systems.DeviceInfo` | record with `name()`,`vendorName()`,`driverInfo()`,`backendName()` | `com.mojang.renderpearl.api.device.DeviceInfo` — same 4 accessors present (plus new fields: `isZZeroToOne`,`timestampPeriod`,`limits`,`features`,`underlyingExtensions`,`hintsAndWorkarounds`,`type`) | MOVED | `HardwareProbe.java:6,76-80` | Version-specific probe impl |
| `RenderSystem.tryGetDevice()` | `()Lcom/mojang/blaze3d/systems/GpuDevice;` | `()Lcom/mojang/renderpearl/api/device/GpuDevice;` | CHANGED (follows GpuDevice move) | `HardwareProbe.java:74` | same |
| `RenderSystem.getDevice()` | blaze3d `GpuDevice` | renderpearl `GpuDevice` | CHANGED | not used directly | — |
| `InputConstants.Type.KEYSYM` | present | **removed** | REMOVED/RENAMED | `RigTuneClient.java:66` | Use `KEYBOARD`, version-split |
| `InputConstants.Type.SCANCODE` | present | **removed** | REMOVED | not used directly | — |
| `InputConstants.Type.KEYBOARD` | absent | **added** — replaces KEYSYM+SCANCODE (one keyboard-input type instead of two) | ADDED | replacement for above | — |
| `InputConstants.KEY_F8` | `= 297` (GLFW keysym) | `= 65` (SDL scancode) | BEHAVIOUR-CHANGED | `RigTuneClient.java:66` | Recompile per version (constant is inlined) |
| `InputConstants.KEY_ESCAPE` | `= 256` | `= 41` | BEHAVIOUR-CHANGED | `RigTuneClientGameTest.java:136` | Recompile per version |
| `InputConstants.KEY_F25` | present | **removed** | REMOVED | not used | — |
| `InputConstants.KEY_LSUPER`/`RSUPER` | present | renamed `KEY_LGUI`/`RGUI` | RENAMED | not used | — |
| `InputConstants.isKeyDown(Window,int)` | `(Lcom/mojang/blaze3d/platform/Window;I)Z` | `isKeyDown(int)` — `Window` param dropped | CHANGED | not used | — |
| `InputConstants.setupKeyboardCallbacks`/`setupMouseCallbacks` (GLFW callback types) | present | **removed** | REMOVED | not used | Confirms GLFW callback plumbing is gone (§4) |
| `Window.getRefreshRate()` | `()I` | **removed** | REMOVED | `HardwareProbe.java:90`, `BenchmarkController.java:88` | `window.getActiveVideoMode().getRefreshRate()` (returns `float`) |
| `Window.isFullscreen()` | `()Z` | **removed**, no public equivalent found | REMOVED | `HardwareProbe.java:98` | UNVERIFIED — see §4 |
| `Window.isExclusiveFullscreen()` | absent | added (narrower: only true for exclusive fullscreen, not borderless-fullscreen-window) | ADDED | candidate replacement, semantics differ | UNVERIFIED |
| `Window.getActiveVideoMode()` | absent | added: `()Lcom/mojang/blaze3d/platform/VideoMode;` | ADDED | replacement path for refresh rate | — |
| `Window.findBestMonitor()` | `()Lcom/mojang/blaze3d/platform/Monitor;` | identical | SAME | `HardwareProbe.java:91` | — |
| `Window.getWidth()`/`getHeight()` | `()I`/`()I` | identical | SAME | `HardwareProbe.java:88-89` | — |
| `Monitor.currentMode()` | `()Lcom/mojang/blaze3d/platform/VideoMode;` | identical | SAME | `HardwareProbe.java:93` | — |
| `Monitor` other accessors (`monitorName()`→`name()`, `monitor()`→`id()`, `getPreferredVidMode`→`getPreferredVideoMode`, `long`→`int` handle) | GLFW `long` monitor handle | SDL `int` monitor id, renamed accessors | CHANGED | not used (only `currentMode()` is) | — |
| `VideoMode.getWidth()`/`getHeight()` | `()I`/`()I` | identical | SAME | `HardwareProbe.java:94-95` | — |
| `VideoMode.getRefreshRate()` | `()I` | `()F` | CHANGED | `HardwareProbe.java:96` | Round to `int` at the call site |
| `VideoMode(GLFWVidMode)` ctor | present | replaced by `VideoMode(SDL_DisplayMode)` | REMOVED/RENAMED | not used directly (RigTune never constructs one) | — |
| `Options$FieldAccess`, `Options.processOptions(FieldAccess)` | `interface … { process(String,int); process(String,boolean); process(String,String); process(String,float); <T> process(String,T,Function,Function); }` | **byte-identical** | SAME | `SettingsBridge.visit()` (reflection) | none needed — confirmed safe |
| `Options.serverRenderDistance` (private field) | `int` | identical | SAME | `BenchmarkController.maxRenderDistance()` (reflection) | none |
| `Options.{renderDistance,simulationDistance,framerateLimit,inactivityFpsLimit,enableVsync,entityShadows,guiScale}()`, `languageCode`, `save()`, `UNLIMITED_FRAMERATE_CUTOFF` | present | identical descriptors | SAME | throughout `SettingsBridge`/`BenchmarkController` | none |
| `DebugScreenOverlay.logFrameDuration(long)` | `(J)V` | identical | SAME (mixin-safe) | `DebugScreenOverlayMixin` `@Inject` target | none — mixin needs no change |
| `GuiGraphicsExtractor.fill(int,int,int,int,int)`, `text(Font,…)` (all 6 overloads used), `centeredText(Font,…)` (3 overloads), `setTooltipForNextFrame(Font,Component,int,int)` | present | identical descriptors | SAME | `RigTuneScreen`, `BenchmarkResultScreen` | none |
| `Font.width(...)` (3 overloads), `Font.split(FormattedText,int)` | present | identical | SAME | throughout UI | none |
| `ComponentRenderUtils.clipText` | present | byte-identical class | SAME | `RigTuneScreen`, `BenchmarkResultScreen` | none |
| `GuiEventListener` | interface, no `capturesInput()` | adds `public default boolean capturesInput()` | CHANGED (additive) | implemented via `ContainerObjectSelectionList.Entry`/`Checkbox` | none — default method, non-breaking |
| `AbstractSelectionList` (`scrollAmount`/`maxScrollAmount` inherited, `setScrollAmount(double)` declared) | — | only gains an unrelated static `INWORLD_MENU_LIST_BACKGROUND` field | SAME | `ProductionSmoke.nextPage()` | none |
| `Component`, `MutableComponent`, `CommonComponents`, `TranslatableContents`, `Identifier`, `LenientJsonParser`, `Vec3`, `ChatFormatting` | (in `minecraft-common`, not clientonly) | byte-identical | SAME | throughout | none |
| `Button`,`Checkbox`,`ContainerObjectSelectionList`(+`Entry`),`CycleButton`,`Tooltip`,`AbstractWidget`,`NarratableEntry`,`SystemToast`(+`SystemToastId`),`MouseButtonEvent`,`MouseButtonInfo`,`KeyMapping`(+`Category`),`InactivityFpsLimit`,`CloudStatus`,`OptionInstance`(+`IntRange`,`IntRangeBase`,`Enum`,`ValueSet`),`TitleScreen`,`VideoSettingsScreen`(class ref only),`ClientLevel`(class ref only),`LocalPlayer`(methods used only) | — | byte-identical for the members RigTune calls | SAME | throughout client/gametest/test | none |

Also checked and unchanged: `com.mojang.serialization.{DataResult,JsonOps}` (datafixerupper 10.0.21, same both versions), Gson classes (gson 2.14.0, same), `org.jspecify.annotations.Nullable` (1.0.0, same), `oshi.*` (RigTune's own pinned `oshi-core:6.9.0` dependency, not tied to MC version at all), `org.spongepowered.asm.mixin.*` (`sponge-mixin:0.17.4+mixin.0.8.7`, pinned by Fabric Loader 0.19.5, not MC-version-tied).

## 4. SDL replacing GLFW — evidence

Fabric's 26.3 announcement (`https://fabricmc.net/2026/09/15/263.html`): *"The GLFW library used for window and keybind management has been removed in favour of SDL."* SDL is described as providing *"a broader API than GLFW"*. It explicitly warns modders: *"do not attempt to hardcode your inputs to avoid using `InputConstants`"* and that *"[s]ome constants were altered during this transition, particularly mouse buttons."* It also flags a new obligation for custom text-input widgets to call `TextInputManager.onTextInputFocusChange()` (or the `GuiEventListener` default) or *"the SDL backend will become out of sync and character input will entirely break."* — RigTune has no custom text-input widgets, so this doesn't apply, but note it for future UI work.

Our own `javap` diff corroborates this precisely:
- `org.lwjgl:lwjgl-glfw` gone from the 26.3 library manifest (§2); `org.lwjgl:lwjgl-sdl` added.
- `InputConstants.setupKeyboardCallbacks(Window, GLFWKeyCallbackI, GLFWCharCallbackI, GLFWPreeditCallbackI, GLFWIMEStatusCallbackI)` and `setupMouseCallbacks(Window, GLFWCursorPosCallbackI, GLFWMouseButtonCallbackI, GLFWScrollCallbackI, GLFWDropCallbackI)` — both gone.
- `RenderSystem.pollEvents()` (`()V`) → `pollEvents(SDLEventHandler)` / new `pumpEvents(SDLEventHandler)`.
- `VideoMode(GLFWVidMode)`/`VideoMode(GLFWVidMode.Buffer)` constructors → `VideoMode(SDL_DisplayMode)`.
- `Window` gained `handleEvent(SDL_Event)`, dropped GLFW window-handle (`long`)-based callbacks in favour of `int`-based / handle-free methods (`onMove(int,int)` vs old `onMove(long,int,int)`, etc.) — see the `Window` diff in §3 for the fullscreen/refresh-rate consequences that hit `HardwareProbe`.
- `Monitor`'s GLFW `long` monitor handle became an SDL `int` id (`monitor()`→`id()`, `monitorName()`→`name()`).

**What our hardware/display probe must use instead** (`HardwareProbe.probeFast`, `src/client/java/.../client/probe/HardwareProbe.java:86-101`):
- Refresh rate: `Window.getRefreshRate()` is gone. `VideoMode.getRefreshRate()` still exists but now returns `float` (SDL reports fractional Hz). Replacement path on 26.3: `window.getActiveVideoMode().getRefreshRate()`; `Monitor.currentMode().getRefreshRate()` (already used as the fallback) also still works, just returns `float` now. Round to `int` for `DisplayInfo`.
- Fullscreen: `Window.isFullscreen()` is gone with **no verified 1:1 public replacement** (see UNVERIFIED below). `Window` now models fullscreen with three separate private booleans (`fullscreenRequested`, `fullscreen`, plus `exclusiveFullscreen`/`borderlessFullscreen`), and the only public getter is `isExclusiveFullscreen()`, which does not cover SDL's borderless-fullscreen-window mode (`useBorderlessFullscreenWindow()`/`applyBorderlessFullscreenWindow()` exist as private methods, implying that's now a distinct, possibly default, fullscreen mode).
- Key handling: `KeyMapping` itself, `KeyMappingHelper.registerKeyMapping`, and `KeyMapping.Category.register` are all **byte-identical** between versions (§3) — the break is entirely in the `InputConstants` constant RigTune passes in (`Type.KEYSYM` removed, `KEY_F8`'s inlined value changed), not in the Fabric `KeyMapping`/`KeyMappingHelper` API surface itself.

## 5. Fabric API and Mod Menu module diffs; mixin check

Fabric API overall: `0.161.0+26.2` → `0.161.0+26.3` (newest `+26.3` release per `maven.fabricmc.net/.../fabric-api/maven-metadata.xml`). Per-module versions actually used by RigTune (extracted from `META-INF/jars/` inside the fat jar):

| Module | 26.2 | 26.3 | Public API used by RigTune |
|---|---|---|---|
| `fabric-key-mapping-api-v1` | 2.0.5+e2bdee789e | 2.0.8+3434d6d95d | `KeyMappingHelper.registerKeyMapping` — **SAME** |
| `fabric-lifecycle-events-v1` | 4.1.4+29b6eb019e | 4.1.9+ffef5f675d | `ClientLifecycleEvents.{CLIENT_STARTED,CLIENT_STOPPING}`, `ClientTickEvents.END_CLIENT_TICK` — **SAME** |
| `fabric-rendering-v1` | 25.3.3+515ac5339e | 27.0.14+901a437c5d (major version jump) | `HudElementRegistry.attachElementAfter`, `VanillaHudElements.SLEEP` — **SAME** despite the major bump |
| `fabric-screen-api-v1` | 5.2.1+5087f8249e | 5.2.4+48607d035d | `ScreenEvents.AFTER_INIT`, `ScreenMouseEvents.allowMouseClick`, `Screens.getWidgets` — **SAME** |
| `fabric-client-gametest-api-v1` (from the `fabric-api` POM's `<dependencies>`, not embedded in the fat jar) | 6.0.2+0f4f1dca9e | 6.0.7+4be74c3f5d | `FabricClientGameTest`, `ClientGameTestContext`, `TestSingleplayerContext` — **SAME** |

`net.fabricmc.loader.api.{FabricLoader,ModContainer,entrypoint.PreLaunchEntrypoint,metadata.ModOrigin}` and `net.fabricmc.api.ClientModInitializer` come from `fabric-loader-0.19.5.jar`, pinned in `gradle.properties` independent of MC version; not re-diffed per-MC-version (loader isn't republished per Minecraft version) — **SAME by construction**, and Mod Menu 21.0.0's own `fabricloader: >=0.19.2` constraint confirms 0.19.5 stays compatible on 26.3.

Mod Menu: `20.0.2` (`minecraft: >=26.2 <26.3-alpha.3"`) → `21.0.0` (`minecraft: >=26.3-`, released 2026-09-23, confirmed via Modrinth's version API filtered on `game_versions=["26.3"]`). `com.terraformersmc.modmenu.api.{ModMenuApi,ConfigScreenFactory}` — **byte-identical** `javap` output between 20.0.2 and 21.0.0 for the members `ModMenuIntegration` uses (`getModConfigScreenFactory()` default method, `ConfigScreenFactory<?>` functional interface). No code change needed beyond bumping the dependency version and widening `fabric.mod.json`'s `minecraft` range.

Mixin check: `src/client/resources/rigtune.client.mixins.json` targets `DebugScreenOverlayMixin` (`compatibilityLevel: JAVA_25`), which has one `@Mixin(DebugScreenOverlay.class)` / `@Inject(method = "logFrameDuration", at = @At("HEAD"))`. `DebugScreenOverlay.logFrameDuration(long)` → descriptor `(J)V` in both 26.2 and 26.3, confirmed via `javap -p -s`. **The mixin needs no change.** (The only other change inside `DebugScreenOverlay` is an unrelated private `extractLines` method gaining a 4th `int` param — irrelevant to our injector.)

Reflection-based string lookups (`SettingsBridge.visit()` on `Options$FieldAccess`/`Options.processOptions`; `HardwareProbe`'s Iris/Sodium `Class.forName` calls; `BenchmarkController`'s Sodium `SodiumWorldRenderer` lookup) all target classes outside vanilla MC (Sodium/Iris) or the `Options$FieldAccess` path confirmed byte-identical above — none of these break on 26.3 by themselves.

fabric.mod.json entrypoints (`preLaunch` → `RigTunePreLaunch`, `client` → `RigTuneClient`, `modmenu` → `ModMenuIntegration`) target `net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint`, `net.fabricmc.api.ClientModInitializer`, and Mod Menu's `ModMenuApi` respectively — all confirmed SAME above.

## 6. Exact versions and jar sources

| Artifact | Version | Source | SHA-1 |
|---|---|---|---|
| MC 26.2 client (deobfuscated, Mojang mappings) | 26.2 | `C:/Users/Admin/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.2/minecraft-clientonly-deobf-26.2.jar` (Loom-produced from Mojang's official client.jar) | underlying download `2dc72797acbc1b63fc16a11c4ac393605f453754`, matches `26.2.json` `downloads.client.sha1` — verified |
| MC 26.2 common classes | 26.2 | `.../minecraft-common-deobf/26.2/minecraft-common-deobf-26.2.jar` (for `Component`/`ChatFormatting`/`Vec3`/etc., which live outside the client-only split) | same Loom build |
| MC 26.3 client | 26.3 | `https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar` (from `piston-meta` version manifest → `26.3.json` → `downloads.client`) | `e877b6a07acd633fb3bb475002175cec036e7b87` — verified against downloaded file. **Already Mojang-mapped/named** (not obfuscated) in this build, confirmed by listing real class names (`net/minecraft/client/Minecraft.class` etc.) directly in the jar — no deobfuscation step was needed or possible (`26.3.json` has no `client_mappings` entry). |
| Fabric API | `0.161.0+26.2` → `0.161.0+26.3` | `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{0.161.0+26.2,0.161.0+26.3}/...jar` (26.3 chosen as newest `+26.3` release per `maven-metadata.xml`) | 26.3 jar `53f9ee02c3702734d90520aa8c9719bf0e1c8d4f`, matches published `.jar.sha1` — verified. 26.2 jar taken from Gradle cache, already resolved by the real build. |
| Fabric client gametest API | `6.0.2+0f4f1dca9e` → `6.0.7+4be74c3f5d` | per-version, resolved from each `fabric-api` POM's declared dependency, downloaded from `maven.fabricmc.net` | not independently hashed (POM-declared coordinate, standard Maven resolution) |
| Mod Menu | `20.0.2` → `21.0.0` | 20.0.2 from Gradle cache (already resolved by the real build); 21.0.0 from `https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/21.0.0/modmenu-21.0.0.jar`, version chosen via Modrinth API (`api.modrinth.com/v2/project/modmenu/version?game_versions=["26.3"]&loaders=["fabric"]`) which returned `21.0.0` (full release, published 2026-09-23) as the version supporting `26.3` | not independently hashed |
| Fabric Loader | `0.19.5` (pinned in `gradle.properties`, unchanged) | Gradle cache | — |
| javap | `C:/Dev/Tools/jdk/jdk-25.0.4.1+1/bin/javap.exe -p -s` | portable JDK 25 | — |

All downloads staged under `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/097c9765-76fe-415d-a5ae-7debe28cb5de/scratchpad/r-apidiff/`. No Gradle or game launch was used at any point (raw jars + `javap` only).

## 7. UNVERIFIED

- **`Window.isFullscreen()` replacement.** No public 26.3 accessor reports "is the window fullscreen in any way" the way the old method did. `isExclusiveFullscreen()` is public but, based on the new private methods (`useBorderlessFullscreenWindow`, `applyBorderlessFullscreenWindow`, `isWindowFullscreen` (private)), appears to answer a narrower question (excludes SDL's borderless-fullscreen-window mode). Static analysis (jar inspection) can't determine actual runtime semantics or confirm there's no better path (e.g. a Fabric API rendering-v1 helper) — this needs either a runtime check on real 26.3 or asking on the Fabric Discord/tracker. Fallback if nothing better turns up: reflection on the private `fullscreen` field (name confirmed present in the 26.3 dump), flagged fragile since private field names aren't a stable contract.
- **Fabric Loader 0.19.5 on 26.3 at runtime.** Confirmed compatible on paper (Mod Menu 21.0.0 requires `>=0.19.2`; loader isn't MC-version-pinned) but not run against a real 26.3 game instance per the "never launch Minecraft / no Gradle game tasks" constraint on this task.
- **26.4-snapshot-1**: only the library manifest was diffed (cheap, no client jar download) and found identical to 26.3's — consistent with the SDL migration being settled, but the actual class-level API wasn't re-checked for that snapshot. Flag as SNAPSHOT / may change if v0.2.0 ever targets it.
- **`GuiEventListener.capturesInput()`** (new default method in 26.3, related to the SDL text-input-focus warning in the Fabric blog post): confirmed additive/non-breaking for compilation, but its actual behavioural contract (when it must return `true`) wasn't dug into since none of RigTune's `GuiEventListener` implementors currently need custom text input. Worth a second look if v0.2.0 ever adds a text field to `RigTuneScreen`.
- **`fabric-rendering-v1` 25→27 major version jump**: confirmed byte-identical for the two symbols RigTune uses (`HudElementRegistry`, `VanillaHudElements`), but the module's changelog/release notes weren't read — there may be unrelated breaking changes elsewhere in that module that don't affect us but would be worth knowing about if RigTune's rendering usage grows.
