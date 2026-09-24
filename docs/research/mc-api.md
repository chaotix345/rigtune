# Minecraft / Fabric / Sodium API Reference — MC 26.2 (26.2-0.19.5)

Research notes for RigTune (client Fabric mod). All signatures below were extracted with `javap`
(`-p [-c/-v]`) from the JDK at `C:/Dev/Tools/jdk/jdk-25.0.4.1+1/bin/` directly against the real game
files under `%APPDATA%/ModrinthApp` — **no signature here is guessed from memory or from older MC
versions.** Sources used:

- Vanilla client: `%APPDATA%/ModrinthApp/meta/versions/26.2-0.19.5/26.2-0.19.5.jar` — confirmed
  **unobfuscated** (real Mojang names: `net/minecraft/client/Minecraft.class`,
  `net/minecraft/client/Options.class` etc. present directly, 10952 classes total).
- Libraries: `%APPDATA%/ModrinthApp/meta/libraries/` (LWJGL 3.4.1, OSHI 6.9.0).
- Sodium: `.../profiles/Fabric 26.2/mods/sodium-fabric-0.9.2+mc26.2.jar`.
- Fabric API: `.../profiles/Fabric 26.2/mods/fabric-api-0.161.0+26.2.jar`, jar-in-jar — all 42 nested
  `META-INF/jars/*.jar` modules extracted and cross-checked against `fabric.mod.json`.
- User's real config, read-only, cross-referenced for key names:
  `.../profiles/Fabric 26.2/options.txt` and `.../profiles/Fabric 26.2/config/sodium-options.json`.

Everything under `%APPDATA%/ModrinthApp` was treated strictly read-only throughout (jars copied out
to a scratchpad and extracted there; nothing in ModrinthApp was modified).

## Contents
1. [`net.minecraft.client.Options`](#1-netminecraftclientoptions)
2. [GPU info](#2-gpu-info-vendor--renderer--driver--backend-vram)
3. [Window and monitor](#3-window-and-monitor)
4. [FPS and frame timing](#4-fps-and-frame-timing)
5. [Chunk/section readiness](#5-chunksection-readiness)
6. [Sodium 0.9.2 configuration](#6-sodium-092-configuration)
7. [GUI in 26.2](#7-gui-in-262)
8. [Fabric API modules](#8-fabric-api-modules-fabric-api-0161026-nested-jars-under-meta-infjars)
9. [Player and camera control](#9-player-and-camera-control-benchmark-automation)
10. [OSHI (hardware info)](#10-oshi-hardware-info)
11. [Gotchas](#gotchas)

---

# 1. `net.minecraft.client.Options`

All signatures below were extracted with `javap -p [-c/-v]` against the unobfuscated
`26.2-0.19.5.jar`. Package for everything option-related is flat `net.minecraft.client`
(not `net.minecraft.client.OptionsFoo` sub-packages).

## 1.1 OptionInstance<T> — the generic wrapper

```java
public final class net.minecraft.client.OptionInstance<T> {
  // fields
  private final ValueSet<T> values;
  private final Codec<T> codec;
  private final T initialValue;
  private final ValueUpdateListener<? super T> onValueUpdate;
  private final Component caption;
  private T value;

  // key API
  public T get();
  public void set(T);
  public OptionInstance$ValueSet<T> values();
  public Codec<T> codec();

  // construction
  public OptionInstance(String, TooltipSupplier<T>, CaptionBasedToString<T>, ValueSet<T>, T, ValueUpdateListener<? super T>);
  public OptionInstance(String, TooltipSupplier<T>, CaptionBasedToString<T>, ValueSet<T>, Codec<T>, T, ValueUpdateListener<? super T>);
  public static OptionInstance<Boolean> createBoolean(String, boolean);                                  // + 3 more overloads with tooltip/listener
}
```

**`get()`** just returns the cached `value` field — cheap, call every frame if needed.

**`set(T newValue)` bytecode-verified behavior:**
1. `values.validateValue(newValue)` — clamps/rejects via the `ValueSet` (e.g. `IntRange`), falls back to current value via `Optional.orElseGet` if invalid.
2. If `Minecraft.getInstance().isRunning()` is `false` (still booting), it just stores the value and returns — **no listener fires during early bootstrap**.
3. Otherwise, if `Objects.equals(oldValue, newValue)` — no-op, listener is **not** invoked when the value hasn't actually changed.
4. Otherwise: `value = newValue;` then `onValueUpdate.valueChanged(newValue)` (interface `OptionInstance$ValueUpdateListener<T>`, single abstract method `void valueChanged(T)`).

So **all** side effects of changing a setting happen inside that one listener callback, which is supplied per-field when `Options`'s constructor builds each `OptionInstance`. There is no generic "on any option changed" hook — you have to either wrap `set()` yourself (e.g. via a mixin on the specific `Options` field accessor or on `OptionInstance.set`) or poll `get()`.

The first `String` constructor arg (e.g. `"options.renderDistance"`) is a **translation key** for the settings-screen caption, not the options.txt save key (see §1.4 for the actual save key mapping).

### `OptionInstance$ValueSet<T>` (interface)
```java
public interface ValueSet<T> {
  Function<OptionInstance<T>, AbstractWidget> createButton(TooltipSupplier<T>, Options, int, int, int, ValueUpdateListener<? super T>);
  Optional<T> validateValue(T);
  Codec<T> codec();
}
```
Concrete implementations used throughout `Options`: `OptionInstance$IntRange`, `OptionInstance$Enum<T>` (backs `CycleButton`), plus others (`OptionInstance$UnitDouble`-style ones weren't inspected in depth).

### `OptionInstance$IntRange` (record, implements `IntRangeBase`)
```java
public final class IntRange extends Record implements IntRangeBase {
  public IntRange(int minInclusive, int maxInclusive);
  public IntRange(int minInclusive, int maxInclusive, boolean applyValueImmediately);
  public Optional<Integer> validateValue(Integer);
  public int minInclusive(); public int maxInclusive(); public boolean applyValueImmediately();
}
```
Interesting: `applyValueImmediately` is a real, per-slider flag. Bytecode-confirmed example: `renderDistance`'s `IntRange` is constructed as `new IntRange(2, hasEnoughMemory ? 32 : 16, false)` — **`applyValueImmediately = false`**, i.e. the vanilla render-distance slider does *not* pretend to apply live the way some other integer sliders do. `simulationDistance` is built the same way (`false`). If you build your own slider widget around these `OptionInstance`s in a mixed-in screen, respect this flag if you want vanilla-consistent behavior.

### `OptionInstance$Enum<T>` (record, implements `CycleableValueSet<T>`)
```java
public final class Enum<T> extends Record implements CycleableValueSet<T> {
  public Enum(List<T> values, Codec<T> codec);
  public Optional<T> validateValue(T);
  public CycleButton$ValueListSupplier<T> valueListSupplier();
}
```
Backs every `OptionInstance<SomeEnum>` field (graphicsPreset, preferredGraphicsBackend, textureFiltering, etc.) and drives `CycleButton` generation directly.

## 1.2 Relevant `OptionInstance<T>` fields/accessors on `Options` (video & performance)

Every field is `private final OptionInstance<T> name;` with a matching `public OptionInstance<T> name();` getter (no setter — call `.get()`/`.set(v)` on the returned instance). Table cross-checked against the user's real `options.txt` (`Fabric 26.2/options.txt`):

| Field / accessor | Type | options.txt key | Notes |
|---|---|---|---|
| `renderDistance()` | `OptionInstance<Integer>` | `renderDistance` | range `[2, 16 or 32]` (32 if `Runtime.maxMemory() >= 1_000_000_000L`), `applyValueImmediately=false` |
| `simulationDistance()` | `OptionInstance<Integer>` | `simulationDistance` | range `[2 or 5, 16 or 32]`, `applyValueImmediately=false` |
| `entityDistanceScaling()` | `OptionInstance<Double>` | `entityDistanceScaling` | |
| `framerateLimit()` | `OptionInstance<Integer>` | `maxFps` | `UNLIMITED_FRAMERATE_CUTOFF` constant marks "Unlimited" |
| `preferredGraphicsBackend()` | `OptionInstance<PreferredGraphicsApi>` | `preferredGraphicsBackend` | see §1.3 |
| `graphicsPreset()` | `OptionInstance<GraphicsPreset>` | `graphicsPreset` | see §1.3 |
| `inactivityFpsLimit()` | `OptionInstance<InactivityFpsLimit>` | `inactivityFpsLimit` | enum `MINIMIZED`/`AFK` |
| `cloudStatus()` | `OptionInstance<CloudStatus>` | `renderClouds` | note key rename vs field name |
| `cloudRange()` | `OptionInstance<Integer>` | `cloudRange` | |
| `weatherRadius()` | `OptionInstance<Integer>` | `weatherRadius` | confirmed present |
| `cutoutLeaves()` | `OptionInstance<Boolean>` | `cutoutLeaves` | confirmed present |
| `vignette()` | `OptionInstance<Boolean>` | `vignette` | |
| `improvedTransparency()` | `OptionInstance<Boolean>` | `improvedTransparency` | confirmed present |
| `ambientOcclusion()` | `OptionInstance<Boolean>` | `ao` | key rename |
| `chunkSectionFadeInTime()` | `OptionInstance<Double>` | `chunkSectionFadeInTime` | confirmed present |
| `prioritizeChunkUpdates()` | `OptionInstance<PrioritizeChunkUpdates>` | `prioritizeChunkUpdates` | enum `NONE`/`PLAYER_AFFECTED`/`NEARBY` |
| `mipmapLevels()` | `OptionInstance<Integer>` | `mipmapLevels` | |
| `maxAnisotropyBit()` | `OptionInstance<Integer>` | `maxAnisotropyBit` | confirmed present; see also `maxAnisotropyValue()` below |
| `textureFiltering()` | `OptionInstance<TextureFilteringMethod>` | `textureFiltering` | confirmed present; enum `NONE`/`RGSS`/`ANISOTROPIC` |
| `guiScale()` | `OptionInstance<Integer>` | `guiScale` | `AUTO_GUI_SCALE` = 0 |
| `gamma()` | `OptionInstance<Double>` | — (not in user's options.txt; brightness) | |
| `fov()` | `OptionInstance<Integer>` | `fov` | |
| `particles()` | `OptionInstance<ParticleStatus>` (`net.minecraft.server.level.ParticleStatus`) | `particles` | |
| `entityShadows()` | `OptionInstance<Boolean>` | `entityShadows` | |
| `biomeBlendRadius()` | `OptionInstance<Integer>` | `biomeBlendRadius` | |
| `enableVsync()` | `OptionInstance<Boolean>` | `enableVsync` | |
| `fullscreen()` | `OptionInstance<Boolean>` | `fullscreen` | |
| `exclusiveFullscreen()` | `OptionInstance<Boolean>` | `exclusiveFullscreen` | new-ish; also `exclusiveFullscreenFromStartup` plain field |
| `menuBackgroundBlurriness()` | `OptionInstance<Integer>` | `menuBackgroundBlurriness` | also plain int getter `getMenuBackgroundBlurriness()` |

Other options.txt keys that differ from the Java field name (mapping confirmed by reading options.txt + matching semantics, not from literal bytecode key strings): `maxFps`↔`framerateLimit`, `ao`↔`ambientOcclusion`, `renderClouds`↔`cloudStatus`, `mouseSensitivity`↔`sensitivity`, `discrete_mouse_scroll`↔`discreteMouseScroll`, `hideLightningFlashes`↔`hideLightningFlash`, `syncChunkWrites`↔`syncWrites` (plain `boolean` field, not an `OptionInstance`), `panoramaScrollSpeed`↔`panoramaSpeed`. Most other keys match the field name 1:1 (`renderDistance`, `simulationDistance`, `cutoutLeaves`, `weatherRadius`, `improvedTransparency`, `chunkSectionFadeInTime`, `textureFiltering`, `maxAnisotropyBit`, `graphicsPreset`, `preferredGraphicsBackend`, `mipmapLevels`, `vignette`, `entityShadows`, `entityDistanceScaling`, `biomeBlendRadius`, `guiScale`, `fov`, `particles`, `enableVsync`, `fullscreen`, `exclusiveFullscreen`, `menuBackgroundBlurriness`, `cloudRange`, `prioritizeChunkUpdates`, `inactivityFpsLimit`).

The exact key strings are compiled into an anonymous class `Options$3` (constructed inside `save()`) and its `load()` counterpart, both implementing `Options$FieldAccess`:
```java
interface Options$OptionAccess { <T> void process(String key, OptionInstance<T> option); }
interface Options$FieldAccess extends Options$OptionAccess {
  int process(String, int);
  boolean process(String, boolean);
  String process(String, String);
  float process(String, float);
  <T> T process(String, T, Function<String,T> parse, Function<T,String> serialize);
}
private void processOptions(Options$FieldAccess);   // single source of truth for ALL save/load key<->field pairs
public void load();
public void save();
```
`processOptions` is a single big method that calls `access.process("someKey", someField)` once per persisted field/OptionInstance — it is the definitive place to find the literal key string for any field if you need 100% certainty (not fully enumerated here since it's ~150 call sites; grep the disassembly's `processOptions` body for `ldc` string constants immediately preceding each `process` invocation).

`maxAnisotropyValue()` — separate `public int maxAnisotropyValue()` derives the actual anisotropy sample count (likely `1 << maxAnisotropyBit`) from the raw bit-count `OptionInstance<Integer>`.

`isRestartRequiredToApplyVideoSettings()` — `public boolean` — exists; a video-settings screen can call this to show a "restart required" banner.

## 1.3 Graphics preset & graphics-API selection (26.x new APIs)

```java
public final class net.minecraft.client.GraphicsPreset extends Enum<GraphicsPreset> implements StringRepresentable {
  public static final GraphicsPreset FAST, FANCY, FABULOUS, CUSTOM;
  public static final Codec<GraphicsPreset> CODEC;
  public String getSerializedName();  // -> "fast" / "fancy" / "fabulous" / "custom" (matches options.txt graphicsPreset:"custom")
  public String getKey();
  public void apply(Minecraft);       // pushes preset values into ~15 OptionInstances, see below
  private static <T> void set(OptionsSubScreen, OptionInstance<T>, T);  // helper used by apply()
}
```
`GraphicsPreset.apply(Minecraft mc)` (bytecode-walked) calls `Options.<field>().set(...)` (via the private static `set(OptionsSubScreen, OptionInstance<T>, T)` helper — note it actually takes an `OptionsSubScreen`, so applying a preset is coupled to a live settings screen instance, not something you can trivially call headless) for, in this order: `biomeBlendRadius`, `renderDistance`, `prioritizeChunkUpdates`, `simulationDistance`, `ambientOcclusion`, `cloudStatus` (→`CloudStatus.FAST`), `particles`, `mipmapLevels`, `entityShadows`, `entityDistanceScaling`, `menuBackgroundBlurriness`, `cloudRange`, `cutoutLeaves`, `improvedTransparency`, `weatherRadius`, (continues beyond what was captured, almost certainly also `textureFiltering`/`vignette`/`chunkSectionFadeInTime`/`maxAnisotropyBit`). Each preset (FAST/FANCY/FABULOUS) has a different constant baked in for each field; CUSTOM is a sentinel that means "user has hand-tuned at least one of these" — see below.

`Options.applyGraphicsPreset(GraphicsPreset)` — public method on `Options` itself, sets `isApplyingGraphicsPreset = true` (a guard flag), likely delegates to `GraphicsPreset.apply(minecraft)`.

`Options.setGraphicsPresetToCustom()` — **private**, but bytecode-confirmed to be invoked from the `valueChanged` listener of essentially every individual graphics-related `OptionInstance` (cutoutLeaves, improvedTransparency, vignette, ambientOcclusion, textureFiltering, weatherRadius, mipmapLevels, etc.) — i.e. hand-editing any one of these flips `graphicsPreset` to `CUSTOM` automatically, the same way vanilla's video settings screen shows "Custom" once you touch an individual slider after picking Fast/Fancy/Fabulous. **If your mod calls `.set()` on any of these fields directly, `graphicsPreset` will silently flip to CUSTOM as a side effect** — there's no way to avoid it short of not using the vanilla setter.

```java
public final class net.minecraft.client.PreferredGraphicsApi extends Enum<PreferredGraphicsApi> implements StringRepresentable {
  public static final PreferredGraphicsApi DEFAULT, OPENGL, VULKAN;
  public static final Codec<PreferredGraphicsApi> CODEC;
  public Component caption();
  public String getSerializedName();               // "default" / "opengl" / "vulkan" — matches options.txt preferredGraphicsBackend:"default"
  public GpuBackend[] getBackendsToTry();           // com.mojang.blaze3d.systems.GpuBackend[] — the actual backend-selection fallback list
}
```
`Options.preferredGraphicsBackend()` is the live `OptionInstance<PreferredGraphicsApi>`. There's also a plain field `private PreferredGraphicsApi preferredGraphicsBackendFromStartup;` captured once at launch (since the active backend can't be hot-swapped — changing this option requires a restart, consistent with `GRAPHICS_API_TOOLTIP`/`GRAPHICS_API_TOOLTIP_VULKAN` restart-warning `Component`s present in the class). **This confirms 26.2 does have a first-class OpenGL-vs-Vulkan preference**, exposed as `Options.preferredGraphicsBackend()`, serialized to `options.txt` as `preferredGraphicsBackend`, and it requires a restart to take effect (read `preferredGraphicsBackendFromStartup` if you need to know what's *actually active* right now, not just the pending choice).

Also present: `TextureFilteringMethod` (`NONE`/`RGSS`/`ANISOTROPIC`, each with a `Component caption()` and a legacy int-id `Codec`), `InactivityFpsLimit` (`MINIMIZED`/`AFK`), `PrioritizeChunkUpdates` (`NONE`/`PLAYER_AFFECTED`/`NEARBY`).

## 1.4 `set()` side effects — chunk/geometry invalidation (bytecode-verified, not guessed)

`Options` has a private static helper:
```java
private static void operateOnLevelExtractor(java.util.function.Consumer<net.minecraft.client.renderer.extract.LevelExtractor> action);
```
Bytecode: fetches `Minecraft.getInstance().levelExtractor` (a field on `Minecraft`); if it's non-null (i.e. a level/world is currently loaded), runs `action.accept(levelExtractor)`; if null (main menu, no world), it's a safe no-op.

This is called from the `valueChanged` listeners of multiple `OptionInstance`s. Two distinct method references were found wired up via `invokedynamic`/`LambdaMetafactory` (confirmed from the class's `BootstrapMethods` table, not inferred):
- `LevelExtractor::allChanged` (`public void allChanged()`) — used by, among others, the listeners for **cutoutLeaves**, **vignette-adjacent boolean options**, and other "recompile everything" style toggles (confirmed call sites: the listeners paired with `setGraphicsPresetToCustom()` for several boolean/enum graphics fields).
- `LevelExtractor::resetSampler` (`public void resetSampler()`) — used specifically by **textureFiltering**'s listener (lighter-weight than a full `allChanged()`).

`net.minecraft.client.renderer.extract.LevelExtractor` (new in 26.x — this is the successor/companion to the old `LevelRenderer` reload logic) relevant surface:
```java
public class LevelExtractor implements ResourceManagerReloadListener {
  public void setLevel(ClientLevel);
  public void allChanged();          // full re-extract/recompile trigger
  public void resetSampler();
  public void blockChanged(BlockPos, int);
  public void setBlockDirty(BlockPos, boolean);
  public void setBlocksDirty(int,int,int,int,int,int);
  public void setSectionDirty(int,int,int);
  public int countRenderedSections();
  public double lastViewDistance();
}
```

**Important, non-obvious finding:** `renderDistance`'s own `valueChanged` listener (`Options.lambda$new$106(Integer)`, bytecode-dumped in full) does **only** `this.setGraphicsPresetToCustom();` — it does **not** call `LevelExtractor.allChanged()` or any chunk-reload method directly. So changing `renderDistance` via `OptionInstance.set()` does not itself force a resend/rebuild of the chunk grid from `Options`'s side — that must be picked up elsewhere (most likely polled each tick by `ClientChunkCache`/`Minecraft` comparing the option's current value against the tracked view-distance, similar to how it always worked pre-26.x). Don't assume calling `options.renderDistance().set(x)` alone will visibly change loaded chunks the same frame; if you need an instant visual effect for a benchmark, you may need to also poke whatever polls it, or just wait a tick.

`simulationDistance`'s listener is `Options.lambda$new$104`-adjacent territory but was not individually bytecode-dumped here — treat it as very likely following the same "flip to Custom only" pattern as renderDistance unless you verify otherwise.

## 1.5 `save()` / `load()`

```java
public void save();   // writes directly to this.optionsFile
public void load();
private CompoundTag dataFix(CompoundTag);   // for migrating old formats
public File getFile();
public String dumpOptionsForReport();       // for crash reports / F3 debug dump
```
`save()` bytecode: opens `new PrintWriter(new OutputStreamWriter(new FileOutputStream(optionsFile), UTF_8))`, writes `"version:" + SharedConstants.getCurrentVersion().dataVersion().version()` as the first line, then calls `processOptions(new Options$3(this, writer))` where `Options$3` is an anonymous `FieldAccess` that `println`s `key + ":" + value` for every option (matches the real `options.txt` format: `key:value`, one per line, quoted strings for enums like `"custom"`). After that it separately writes `fullscreenVideoModeString`, resource pack lists, key bindings (`key_<name>:<binding>`), sound category volumes (`soundCategory_<name>:<vol>`), and model parts (`modelPart_<name>:<bool>`) — all visible as distinct sections in the real options.txt.

`optionsFile` is a plain `java.io.File` field set once in the constructor to `new File(gameDir, "options.txt")`.

`Options(Minecraft, File)` — the only public constructor; takes the game directory, not the options file directly.

---

# 2. GPU info (vendor / renderer / driver / backend, VRAM)

**Important correction to the brief's premise**: the exact strings `"Found graphics adapter: AdapterInfo{...}"` and a class literally named `AdapterInfo` do **not exist anywhere in vanilla's 26.2 client jar** (exhaustively grepped every `.class` under `net/minecraft` and `com/mojang` for both `"AdapterInfo"` and `"Found graphics adapter"` — zero matches). That log line is **Sodium's**, not vanilla's: Sodium 0.9.2 has `net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterInfo` (an interface) and `GraphicsAdapterProbe` (finds them), used purely for its own driver-workaround detection — see §6. If you saw that exact log text, Sodium was installed. Don't build RigTune's GPU-info reader around a vanilla `AdapterInfo` class — it isn't there.

Vanilla **does** confirm-print `"Using graphics backend {}, using drivers: {}"` (SLF4J template, found in `Minecraft.class`) — that line is real vanilla output.

## The real vanilla API: `com.mojang.blaze3d.systems.GpuDevice` / `DeviceInfo`

```java
public static com.mojang.blaze3d.systems.RenderSystem;
  public static GpuDevice getDevice();          // throws/asserts if not yet initialized
  public static GpuDevice tryGetDevice();        // null-safe
  public static String getBackendDescription();
  public static void initRenderer(GpuDevice);

public class com.mojang.blaze3d.systems.GpuDevice {
  public DeviceInfo getDeviceInfo();
  public List<String> getLastDebugMessages();
  public boolean isDebuggingEnabled();
  // + createTexture/createBuffer/createSampler/... (pipeline plumbing, not needed for a benchmark mod)
}

public final class com.mojang.blaze3d.systems.DeviceInfo extends Record {
  public String name();            // GPU/renderer name string
  public String vendorName();
  public String driverInfo();      // driver version string
  public String backendName();     // e.g. "OpenGL" / "Vulkan"
  public boolean isZZeroToOne();
  public float timestampPeriod();
  public DeviceLimits limits();
  public DeviceFeatures features();
  public Set<String> underlyingExtensions();
  public HintsAndWorkarounds hintsAndWorkarounds();
  public DeviceType type();        // OTHER / INTEGRATED / DISCRETE / VIRTUAL / CPU
}
```
**Use**: `RenderSystem.getDevice().getDeviceInfo()` gives you vendor, renderer name, driver version string, and active backend name (OpenGL/Vulkan) in one call, live, any time after renderer init. `DeviceType` tells you integrated-vs-discrete without string-parsing the name. This is the correct, stable, in-game-readable equivalent of what the brief called "AdapterInfo".

```java
public final class com.mojang.blaze3d.systems.DeviceLimits extends Record {
  public int maxAnisotropy(); public int minUniformOffsetAlignment(); public int maxTextureSize();
  public long maxMemoryAllocationSize();   // NOT total VRAM — just the max single-allocation size
  public int maxMultiDrawDirectInterleavedDrawCount(); public int maxColorAttachments();
}
public final class com.mojang.blaze3d.systems.HintsAndWorkarounds extends Record {
  public boolean writeToBufferIsSlow(); public boolean anisotropyHasKnownIssues();
}
public final class com.mojang.blaze3d.systems.DeviceFeatures extends Record {
  public boolean shaderDrawParameters(); public boolean multiDrawDirectInterleaved();
  public boolean multiDrawDirectSeparate(); public boolean multiDrawIndirect();
  public boolean drawIndirect(); public boolean nonZeroFirstInstance(); public boolean persistentMapping();
}
public final class com.mojang.blaze3d.systems.DeviceType extends Enum<DeviceType> { OTHER, INTEGRATED, DISCRETE, VIRTUAL, CPU }
```

## Can we read VRAM anywhere?

**Not from `GpuDevice`/`DeviceInfo`/`DeviceLimits`.** None of blaze3d's device-info records expose a total-VRAM number (`maxMemoryAllocationSize` is a per-allocation cap, not total memory). Sodium's own `GraphicsAdapterInfo` (vendor + name only) doesn't have it either. **The only real source of total VRAM is OSHI's `GraphicsCard.getVRam()`** (§10) — use that, same as vanilla's own `SystemReport` does.

## Backend enum

```java
public interface com.mojang.blaze3d.systems.GpuBackend {
  String getName();
  void setWindowHints();
  void handleWindowCreationErrors(GLFWErrorCapture$Error) throws BackendCreationException;
  GpuDevice createDevice(long windowHandle, ShaderSource, GpuDebugOptions, Runnable) throws BackendCreationException;
}
```
The active backend instance is reachable off `Window.backend()` (see §3) and drives which `GpuDevice` implementation gets created. Which backend is *preferred* (pending, may need restart) vs *actually active* is the `Options.preferredGraphicsBackend()` vs `preferredGraphicsBackendFromStartup` split documented in §1.3.

---

# 3. Window and monitor

```java
public final class com.mojang.blaze3d.platform.Window implements AutoCloseable {
  public long handle();                          // raw GLFW window handle
  public int getRefreshRate();                    // from GLFW, of the monitor the window is on
  public boolean isFullscreen();
  public boolean isFocused();
  public boolean isIconified();
  public boolean isMinimized();
  public int getWidth(); public int getHeight();               // framebuffer-adjacent window size
  public int getScreenWidth(); public int getScreenHeight();
  public int getGuiScaledWidth(); public int getGuiScaledHeight();
  public int getX(); public int getY();
  public int getGuiScale();
  public Monitor findBestMonitor();
  public Optional<VideoMode> getPreferredFullscreenVideoMode();
  public void setPreferredFullscreenVideoMode(Optional<VideoMode>);
  public void changeFullscreenVideoMode();
  public void toggleFullScreen();
  public void setWindowed(int, int);
  public GpuBackend backend();
  public static String getPlatform();
}
```
Reach it via `Minecraft.getInstance().getWindow()` (confirmed method, returns `Window`). There's also `Minecraft.windowSurface()` → `com.mojang.blaze3d.systems.GpuSurface` (the actual swapchain/surface object, backend-specific — not needed for a settings/benchmark mod, but exists if you need it).

```java
public final class com.mojang.blaze3d.platform.Monitor extends Record {
  public String monitorName();
  public long monitor();                        // GLFW monitor handle
  public List<VideoMode> videoModes();
  public VideoMode currentMode();
  public int x(); public int y();
  public static Monitor tryCreate(long glfwMonitorHandle);
  public VideoMode getPreferredVidMode(Optional<VideoMode> override);
  public int indexOfMode(VideoMode);
  public VideoMode mode(int index);
  public int modeCount();
}

public final class com.mojang.blaze3d.platform.VideoMode {
  public VideoMode(int width, int height, int redBits, int greenBits, int blueBits, int refreshRate);
  public VideoMode(org.lwjgl.glfw.GLFWVidMode);           // wraps a raw GLFW vidmode
  public int getWidth(); public int getHeight();
  public int getRedBits(); public int getGreenBits(); public int getBlueBits();
  public int getRefreshRate();
  public static Optional<VideoMode> read(String);          // parses options.txt's fullscreenVideoModeString format
  public String write();
}
```
**Use**: `Minecraft.getInstance().getWindow().getRefreshRate()` is the simplest live refresh-rate read. For full monitor enumeration (e.g. to list available fullscreen resolutions for a benchmark preset picker), `window.findBestMonitor().videoModes()`. `Options.fullscreenVideoModeString` (plain `String` field, §1) round-trips through `VideoMode.read(String)`/`.write()`.

---

# 4. FPS and frame timing

```java
public class net.minecraft.client.Minecraft {
  public int getFps();          // static `fps` field — updates once per second (smoothed frame count), NOT per-frame
  public long getFrameTimeNs(); // instance `frameTimeNs` field — set EVERY frame in the main run loop, nanoseconds
}
```
Bytecode-confirmed call order inside `Minecraft`'s per-frame loop: `frameTimeNs` is set from the measured frame duration, then `DebugScreenOverlay.logFrameDuration(long)` is called with that same value (feeding the F3 chart), then once per second the smoothed `fps` static field is updated from accumulated frame count.

**For 1% lows, use `getFrameTimeNs()`, not `getFps()`.** `getFps()` is already a 1-second-smoothed average — useless for percentile computation. `getFrameTimeNs()` gives you the raw per-frame duration; sample it every frame yourself (see hook options below) and compute your own percentiles (sort samples over a window, take the value at the 99th percentile of frame *time* = the "1% low" frame).

## Vanilla's own frame-time ring buffer (F3 chart) — exists but has no public accessor

```java
package net.minecraft.util.debugchart;
public interface SampleLogger { void logFullSample(long[]); void logSample(long); void logPartialSample(long, int); }
public interface SampleStorage { int capacity(); int size(); long get(int); long get(int, int); void reset(); }
public abstract class AbstractSampleLogger implements SampleLogger { /* ring buffer over `sample`/`defaults` */ }
public class LocalSampleLogger extends AbstractSampleLogger implements SampleStorage {
  public static final int CAPACITY;   // ring buffer size (matches F3 chart width, ~240)
  public long get(int); public long get(int, int); public int size(); public int capacity(); public void reset();
}
```
```java
public class net.minecraft.client.gui.components.DebugScreenOverlay {
  private final LocalSampleLogger frameTimeLogger;   // <-- PRIVATE, no getter exposed
  private final LocalSampleLogger tickTimeLogger;
  public LocalSampleLogger getTickTimeLogger();       // has a getter
  public LocalSampleLogger getPingLogger();           // has a getter
  public LocalSampleLogger getBandwidthLogger();      // has a getter
  public void logFrameDuration(long);                 // write-only, called by Minecraft's frame loop
  public boolean showFpsCharts(); public void toggleFpsCharts();
}
```
**Gotcha**: `frameTimeLogger` has **no public getter** (unlike `tickTimeLogger`/`pingLogger`/`bandwidthLogger`, which do). To read Mojang's own F3 frame-time ring buffer you'd need an Accessor mixin on `DebugScreenOverlay`. It's simpler to just maintain your own ring buffer fed from `Minecraft.getInstance().getFrameTimeNs()`.

`net.minecraft.client.gui.components.debugchart.FpsDebugChart extends AbstractDebugChart` — the F3 chart widget itself, constructed with `(Font, SampleStorage)`; not useful headless, but confirms the chart is driven by a plain `SampleStorage`, so if you do mixin-accessor your way to `frameTimeLogger`, `LocalSampleLogger.get(int)` gives you raw nanosecond samples directly (same data as the chart).

## Per-frame hooks

- **`Minecraft.getFrameTimeNs()` polled from any per-frame callback.** The cleanest per-frame hook available from Fabric API (with WorldRenderEvents gone in 26.x, see §8) is `LevelRenderEvents.END_MAIN` (`net.fabricmc.fabric.api.client.rendering.v1.level`, fires once per world-render frame) or a `HudElement.extractRenderState(...)` registered via `HudElementRegistry` (also runs every frame, even simpler to wire up since it needs no world/level to be loaded — good for a benchmark overlay). A mixin injecting into `Minecraft`'s render loop directly (near where `frameTimeNs` is written) is the most precise option if you need to sample before any other mod's HUD/render-event code runs.
- Ticks (`ClientTickEvents.END_CLIENT_TICK`, §8) are 20/s fixed-rate — **not** suitable for per-frame FPS sampling, only for driving benchmark scripting logic (camera moves, command dispatch) that doesn't need frame granularity.

---

# 5. Chunk/section readiness

## Vanilla: `LevelRenderer` + the new `LevelExtractor` (26.x split)

26.x splits the old monolithic `LevelRenderer` into extraction (`net.minecraft.client.renderer.extract.LevelExtractor`, §1.4) and render-submission (`LevelRenderer` itself). Section-readiness queries are split across both:

```java
public class net.minecraft.client.renderer.LevelRenderer implements AutoCloseable {
  public boolean hasRenderedAllSections();
  public boolean isSectionCompiledAndVisible(net.minecraft.core.BlockPos);
  public void invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors);
  public void clearVisibleSections();
  public void resetLevelRenderData();
  public ObjectArrayList<SectionRenderDispatcher$RenderSection> visibleSections();
  public ObjectArrayList<SectionRenderDispatcher$RenderSection> nearbyVisibleSections();
  public LongCollection expectedChunks();
  public SectionOcclusionGraph sectionOcclusionGraph();
  public SectionRenderDispatcher sectionRenderDispatcher();
}

public class net.minecraft.client.renderer.extract.LevelExtractor implements ResourceManagerReloadListener {
  public void allChanged();                 // force full recompile (see §1.4)
  public void resetSampler();
  public int countRenderedSections();        // <-- brief's "countRenderedSections" lives HERE, not on LevelRenderer
  public double totalSections();
  public double lastViewDistance();
  public String sectionStatistics();
  public String entityStatistics();
}
```
**Use for a benchmark "warm-up complete" gate**: poll `levelRenderer.hasRenderedAllSections()` each frame/tick after teleporting the player; combine with `levelExtractor.countRenderedSections()` vs `levelExtractor.totalSections()` if you want a progress percentage rather than a boolean. Reach the live instances via `Minecraft.getInstance().levelRenderer` and `Minecraft.getInstance().levelExtractor` (both fields exist on `Minecraft`, confirmed — `levelExtractor` specifically confirmed via `Options.operateOnLevelExtractor`'s bytecode in §1.4).

## Sodium 0.9.2 **overrides both of the above wholesale** — confirmed via mixin inspection

Sodium ships two core mixins that fully replace (not just decorate) the relevant vanilla methods:

```java
// net.caffeinemc.mods.sodium.mixin.core.render.world.LevelRendererMixin
public boolean hasRenderedAllSections();               // @Overwrite, same signature as vanilla
public boolean isSectionCompiledAndVisible(BlockPos);  // @Overwrite
public ChunkSectionsToRender prepareChunkRenders(Matrix4fc); // @Overwrite
public SodiumWorldRenderer sodium$getWorldRenderer();   // accessor added to LevelRenderer

// net.caffeinemc.mods.sodium.mixin.core.render.world.LevelExtractorMixin
public int countRenderedSections();                    // @Overwrite
public String sectionStatistics();                     // @Overwrite
public void setBlocksDirty(int,int,int,int,int,int);   // @Overwrite
public void setSectionDirtyWithNeighbors(int,int,int);  // @Overwrite
```
Both mixins delegate to a single `SodiumWorldRenderer` instance they hold a reference to. **So whichever vanilla method you call (`LevelRenderer.hasRenderedAllSections()` etc.), you transparently get Sodium's answer once Sodium is installed** — you don't need to special-case Sodium for these specific calls, vanilla's own method names keep working. You only need Sodium-specific APIs for things vanilla doesn't expose at all (visible chunk count, per-section readiness by coordinate, debug strings).

## Sodium's own API: `net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer`

```java
public class SodiumWorldRenderer {
  public static SodiumWorldRenderer instance();          // throws if not initialized
  public static SodiumWorldRenderer instanceNullable();  // null-safe, use this for a mod that may run before world load
  public boolean isTerrainRenderComplete();               // <-- exact match for the brief's ask
  public int getVisibleChunkCount();
  public boolean isSectionReady(int x, int y, int z);      // per-section-coordinate readiness
  public String getChunksDebugString();
  public Collection<String> getDebugStrings(boolean);
  public void scheduleRebuildForChunk(int x, int y, int z, boolean important);
  public void scheduleRebuildForChunks(int x0,int y0,int z0,int x1,int y1,int z1, boolean important);
  public void scheduleRebuildForBlockArea(int,int,int,int,int,int, boolean);
  public void scheduleTerrainUpdate();
}
```
**Use**: `SodiumWorldRenderer.instanceNullable()` (guard against Sodium not being present — it's a soft/optional dependency, check with `FabricLoader.getInstance().isModLoaded("sodium")` first) then `.isTerrainRenderComplete()` is the most direct "has the renderer finished building all visible chunks" signal when Sodium is active — arguably more reliable than vanilla's `hasRenderedAllSections()` for gating a benchmark start, since it's Sodium's own notion of "done" (queue-drained) rather than vanilla's occlusion-graph-based notion (which Sodium's mixin overrides anyway, so in practice they should agree, but `SodiumWorldRenderer` is the canonical source once Sodium owns the pipeline).

---

# 6. Sodium 0.9.2 configuration

## Options class(es) — `net.caffeinemc.mods.sodium.client.gui.SodiumOptions`

```java
public class SodiumOptions {
  public final QualitySettings quality;
  public final PerformanceSettings performance;
  public final AdvancedSettings advanced;
  public final DebugSettings debug;
  public final NotificationSettings notifications;

  public static SodiumOptions defaults();
  public static SodiumOptions loadFromDisk();
  public static void writeToDisk(SodiumOptions) throws IOException;
  public boolean isReadOnly(); public void setReadOnly();
}
```
Static accessor (confirmed): **`net.caffeinemc.mods.sodium.client.SodiumClientMod.options()`** → returns the live loaded `SodiumOptions` instance. Also on `SodiumClientMod`: `restoreDefaultOptions()`, `logger()`, `getVersion()`, `allowDebuggingOptions()`. Save is `SodiumOptions.writeToDisk(options)` — call it after mutating fields on the instance returned by `SodiumClientMod.options()`, there's no auto-save-on-mutation.

Field groups (all plain mutable public fields, `Gson` serialized — **field naming policy is a naive camelCase→snake_case converter**, confirmed by the real config file having `use_no_error_g_l_context` for `useNoErrorGLContext`, i.e. every capital letter gets its own underscore, including consecutive ones — don't hand-write the JSON key for an acronym field without checking this):

```java
public class SodiumOptions$QualitySettings {
  public boolean hiddenFluidCulling;
  public boolean improvedFluidShaping;
  public boolean useClosestPointEntitySort;
  public com.mojang.blaze3d.textures.FilterMode pixelFilteringMode;
}
public class SodiumOptions$PerformanceSettings {
  public int chunkBuilderThreads;
  public DeferMode chunkBuildDeferMode;
  public boolean animateOnlyVisibleTextures;
  public boolean useEntityCulling;
  public boolean useFogOcclusion;
  public boolean useBlockFaceCulling;
  public boolean useNoErrorGLContext;
  public QuadSplittingMode quadSplittingMode;
}
public class SodiumOptions$AdvancedSettings { public boolean enableMemoryTracing; }
public class SodiumOptions$DebugSettings { public boolean terrainSortingEnabled; }
public class SodiumOptions$NotificationSettings {
  public boolean hasClearedDonationButton;
  public boolean hasSeenDonationPrompt;
  public boolean hasEditedFullscreenOption;
}
```

**Real `config/sodium-options.json` from the user's profile** (ground truth, matches the fields above exactly):
```json
{
  "quality": { "hidden_fluid_culling": true, "improved_fluid_shaping": false, "use_closest_point_entity_sort": false, "pixel_filtering_mode": "NEAREST" },
  "performance": { "chunk_builder_threads": 0, "chunk_build_defer_mode": "ALWAYS", "animate_only_visible_textures": true, "use_entity_culling": true, "use_fog_occlusion": true, "use_block_face_culling": true, "use_no_error_g_l_context": true, "quad_splitting_mode": "SAFE" },
  "advanced": { "enable_memory_tracing": false },
  "debug": { "terrain_sorting_enabled": true },
  "notifications": { "has_cleared_donation_button": false, "has_seen_donation_prompt": true, "has_edited_fullscreen_option": true }
}
```
Note how small this is compared to older Sodium versions — **render distance, vsync, FOV, GUI scale, brightness, fullscreen etc. are no longer Sodium's own options**; Sodium 0.9.2's own settings screen (`SodiumConfigBuilder`) just re-displays/edits the *vanilla* `Options` fields for those (bytecode-confirmed: its page-building code directly references `options.renderDistance`, `options.simulationDistance`, `options.gamma`, `options.guiScale`, translation keys `options.renderDistance` etc.) rather than duplicating them into `sodium-options.json`. Only genuinely Sodium-specific renderer-internals settings live in this file.

## Enum values

```java
public enum net.caffeinemc.mods.sodium.client.render.chunk.DeferMode { ALWAYS, ONE_FRAME, ZERO_FRAMES }
public enum net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode { OFF, SAFE, UNLIMITED }
```
(Confirmed against the real JSON: `chunk_build_defer_mode: "ALWAYS"`, `quad_splitting_mode: "SAFE"`.)

## Workaround / driver-detection classes

```java
public class net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds {
  public static void init();
  public static boolean isWorkaroundEnabled(Workarounds$Reference);
}
public enum Workarounds$Reference {
  NVIDIA_THREADED_OPTIMIZATIONS_BROKEN,
  NO_ERROR_CONTEXT_UNSUPPORTED,
  INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED,
  INTEL_DEPTH_BUFFER_COMPARISON_UNRELIABLE,
  AMD_GAME_OPTIMIZATION_BROKEN,
}
```
Vendor-specific detector classes also present (not fully enumerated, just confirmed to exist): `compatibility.workarounds.amd.AmdWorkarounds`, `.intel.IntelWorkarounds`, `.nvidia.NvidiaWorkarounds` + `NvidiaDriverVersion`. Separate from these, the **adapter identification** classes (used by the workaround detectors, and the likely source of the log text the brief quoted) are:
```java
public interface net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterInfo {
  GraphicsAdapterVendor vendor();
  String name();
}
public class GraphicsAdapterProbe {
  public static void findAdapters();
  public static Collection<? extends GraphicsAdapterInfo> getAdapters();
}
```
All of this is under `.compatibility.` — internal, not part of Sodium's public API package (below); don't build RigTune features against it, it can change without notice.

## Vulkan backend — no user-facing Sodium option; it's auto-derived

Sodium 0.9.2 has real internal Vulkan draw-path support (`VKIndirectDrawBatch`, `VKMultiDrawBatch`, `VKDrawContext`, `VulkanPipelineMixin`, `VulkanRenderPassAccessor` all present in the jar), exposed through:
```java
public enum net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend {
  OPENGL, VK_MULTIDRAW, VK_INDIRECT,
  BACKEND;                       // the auto-selected active one
  private static DrawBackend chooseBackend();   // private — no setter, not user-configurable
}
```
**There is no field in `SodiumOptions` for backend selection** — `sodium-options.json` has nothing Vulkan-related. Sodium's draw backend is entirely derived from `chooseBackend()` at startup based on whatever vanilla's active `GpuDevice`/backend already is (driven by `Options.preferredGraphicsBackend`, §1.3) plus hardware capability — Sodium adapts to vanilla's backend choice, it doesn't offer an independent one. If RigTune wants to report "is Sodium using the Vulkan multidraw or indirect path", read `DrawBackend.BACKEND` (reflection/mixin needed, it's not a published API).

## Sodium's public API package — `net.caffeinemc.mods.sodium.api.*`

This package (distinct from `.client.`/`.mixin.`/`.compatibility.` — those are internal) is what Sodium explicitly supports other mods depending on:

- **`api.config.*`** — lets another mod contribute its own page into Sodium's video-settings UI: `ConfigEntryPoint` (`registerConfigEarly`/`registerConfigLate(ConfigBuilder)`), `structure.ConfigBuilder`/`ModOptionsBuilder`/`OptionPageBuilder`/`OptionGroupBuilder`/`BooleanOptionBuilder`/`IntegerOptionBuilder`/`EnumOptionBuilder`/`ExternalButtonOptionBuilder`, plus `option.OptionBinding`/`OptionFlag`/`OptionImpact`/`Range`/`Validator`. (Exact discovery mechanism — Fabric entrypoint key vs `ServiceLoader` — wasn't pinned down; no `META-INF/services` entry for `ConfigEntryPoint` was found in the jar and no `FabricLoader.getEntrypoints("sodium:config", ...)` call site was found either, so treat the registration path as unconfirmed until you test it, though the interface itself is real and public.) There's also `ConfigEntryPointForge`, confirming this API is written to be loader-agnostic.
- **`api.blockentity.*`** — `BlockEntityRenderHandler`, `BlockEntityRenderPredicate`: opt a custom block entity renderer out of Sodium's culling/batching.
- **`api.vertex.*`** — `VertexBufferWriter` + `attributes.common.*` (`PositionAttribute`, `ColorAttribute`, `LightAttribute`, `NormalAttribute`, `OverlayAttribute`, `TextureAttribute`) + `format.common.*` (`EntityVertex`, `GlyphVertex`, `ParticleVertex`) + `format.VertexFormatRegistry`/`VertexFormatExtensions` + `serializer.VertexSerializer`/`VertexSerializerRegistry` — the fast-path for mods writing custom geometry compatible with Sodium's renderer.
- **`api.texture.SpriteUtil`**, **`api.math.MatrixHelper`**, **`api.memory.MemoryIntrinsics`**, **`api.util.{ColorABGR,ColorARGB,ColorMixer,ColorU8,NormI8}`** — small standalone utility helpers, safe to depend on directly.

RigTune likely doesn't need any of this beyond maybe `SodiumWorldRenderer` (which is under `.client.render`, not `.api.` — technically internal, but this is the standard/expected way every perf-overlay mod reads Sodium's state; treat it as de-facto stable, not de-jure public API).

## Load/save timing

`SodiumOptions.loadFromDisk()` is called once at startup (from `SodiumClientMod`'s private `loadConfig()`); `SodiumClientMod.options()` thereafter just returns the cached static instance — mutate its fields directly, then call `SodiumOptions.writeToDisk(SodiumClientMod.options())` yourself to persist (no auto-save, no listener/callback mechanism analogous to vanilla's `OptionInstance.ValueUpdateListener`, §1). Config path is computed by a private `getConfigPath()`; the default file name constant is `DEFAULT_FILE_NAME` (confirmed to resolve to `config/sodium-options.json`, matching the real file location).

---

# 7. GUI in 26.2

## Screen (`net.minecraft.client.gui.screens.Screen`)

```java
protected Screen(Component title);
protected Screen(Minecraft minecraft, Font font, Component title);

public final void init(int width, int height);      // final; calls init()
protected void init();                                // override this, not the final one

// RENAMED render pipeline (no more render(GuiGraphics,...)):
public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick);
public final void extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float);
public void extractBackground(GuiGraphicsExtractor, int, int, float);
protected void extractBlurredBackground(GuiGraphicsExtractor);
protected void extractPanorama(GuiGraphicsExtractor, float);
protected void extractMenuBackground(GuiGraphicsExtractor);
protected void extractMenuBackground(GuiGraphicsExtractor, int, int, int, int);
public void extractTransparentBackground(GuiGraphicsExtractor);

public void onClose();
public boolean shouldCloseOnEsc();

protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);
protected <T extends Renderable> T addRenderableOnly(T widget);
protected <T extends GuiEventListener & NarratableEntry> T addWidget(T widget);
protected void removeWidget(GuiEventListener);
protected void clearWidgets();

public boolean keyPressed(net.minecraft.client.input.KeyEvent event); // takes a KeyEvent object, not (int,int,int)
public void tick();
public void removed();
public void added();
public void resize(int width, int height);
public Font getFont();
```

**Use:** Override `init()` (not `init(int,int)` — that one's `final`) to add widgets, and `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)` (not `render`) to draw. Call `super.extractRenderState(...)` if you want the default background. `addRenderableWidget` registers a widget for both rendering and event handling in one call — same contract as 1.21.x, just against the new render-state object.

## GuiGraphics -> `net.minecraft.client.gui.GuiGraphicsExtractor`

**There is no class literally named `GuiGraphics` in 26.2.** It was replaced by `net.minecraft.client.gui.GuiGraphicsExtractor`, part of the new "extract render state" pipeline (screens/widgets populate a `GuiRenderState` object instead of issuing immediate draw calls; the actual GPU submission happens later). Constructor:

```java
public GuiGraphicsExtractor(Minecraft minecraft, net.minecraft.client.renderer.state.gui.GuiRenderState renderState, int width, int height);
```

Renamed drawing methods (no `drawString`/`drawText` — it's just `text(...)`):

```java
public void text(Font font, String text, int x, int y, int color);
public void text(Font font, String text, int x, int y, int color, boolean dropShadow);
public void text(Font font, FormattedCharSequence text, int x, int y, int color);
public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean dropShadow);
public void text(Font font, Component text, int x, int y, int color);
public void text(Font font, Component text, int x, int y, int color, boolean dropShadow);
public void centeredText(Font, String, int x, int y, int color);
public void centeredText(Font, Component, int x, int y, int color);
public void centeredText(Font, FormattedCharSequence, int x, int y, int color);
public void textWithWordWrap(Font, FormattedText, int x, int y, int wrapWidth, int color);
public void textWithBackdrop(Font, Component, int x, int y, int wrapWidth, int color);

public void fill(int x1, int y1, int x2, int y2, int color);
public void fill(RenderPipeline, int x1, int y1, int x2, int y2, int color);
public void fillGradient(int x1, int y1, int x2, int y2, int colorFrom, int colorTo);
public void outline(int x1, int y1, int x2, int y2, int color);
public void horizontalLine(int x1, int x2, int y, int color);
public void verticalLine(int x, int y1, int y2, int color);

public void blit(RenderPipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH);   // + overloads
public void blitSprite(RenderPipeline, Identifier atlas, int x, int y, int w, int h);                                    // + overloads
public void item(ItemStack, int x, int y);
public void itemDecorations(Font, ItemStack, int x, int y);
public void setTooltipForNextFrame(Font, Component, int x, int y);   // + many overloads (List<Component>, FormattedCharSequence, TooltipComponent...)

public org.joml.Matrix3x2fStack pose();     // 2D transform stack (replaces old PoseStack usage in GUI code)
public void enableScissor(int x1, int y1, int x2, int y2);
public void disableScissor();
public int guiWidth();
public int guiHeight();

// Text/font access — NOT a simple `.font` field, it's via a collector:
public ActiveTextCollector textRenderer();
public ActiveTextCollector textRenderer(GuiGraphicsExtractor.HoveredTextEffects);
public ActiveTextCollector textRendererForWidget(AbstractWidget, GuiGraphicsExtractor.HoveredTextEffects);
```

**Use / gotcha:** Every screen/widget method that used to take `GuiGraphics` now takes `GuiGraphicsExtractor`, and every `draw*` call is a plain `text(...)`/`fill(...)`/`blit(...)` — they *populate* a `GuiRenderState`, they don't draw immediately. `Font` is still `net.minecraft.client.gui.Font` (unchanged) and is passed explicitly into `text(...)` calls (get it from `Screen.getFont()` / `Minecraft.font`), it is not pulled implicitly off the extractor except through `textRenderer()`.

## Button (`net.minecraft.client.gui.components.Button`)

```java
public static Button.Builder builder(Component message, Button.OnPress onPress);

public class Button.Builder {
  public Button.Builder(Component message, Button.OnPress onPress);
  public Button.Builder pos(int x, int y);
  public Button.Builder width(int w);
  public Button.Builder size(int w, int h);
  public Button.Builder bounds(int x, int y, int w, int h);
  public Button.Builder tooltip(Tooltip tooltip);
  public Button.Builder createNarration(Button.CreateNarration);
  public Button build();
}

public interface Button.OnPress {
  void onPress(Button button);   // unchanged shape
}
```

Note: `Button.onPress(InputWithModifiers)` (the instance method invoked on click, distinct from the `OnPress` functional interface) now takes a `net.minecraft.client.input.InputWithModifiers`, reflecting the same input-event refactor seen in `Screen.keyPressed(KeyEvent)`.

## Scrollable lists

```java
net.minecraft.client.gui.components.AbstractSelectionList<E extends AbstractSelectionList.Entry<E>>
  extends AbstractContainerWidget
net.minecraft.client.gui.components.ObjectSelectionList<E extends ObjectSelectionList.Entry<E>>
  extends AbstractSelectionList<E>
net.minecraft.client.gui.components.ContainerObjectSelectionList<E extends ContainerObjectSelectionList.Entry<E>>
  extends AbstractSelectionList<E>
```

```java
public AbstractSelectionList(Minecraft, int width, int height, int y0, int itemHeight);
protected int addEntry(E entry);
public void replaceEntries(Collection<E> entries);
public void setSelected(E entry);
public E getSelected();
public void setScrollAmount(double amount);
protected void scrollToEntry(E entry);
protected void extractListItems(GuiGraphicsExtractor, int, int, float);   // renamed from renderList
protected void extractItem(GuiGraphicsExtractor, int, int, float, E);     // renamed from renderItem
```

`ObjectSelectionList` adds nothing but `updateWidgetNarration`; use it for simple single-column lists. `ContainerObjectSelectionList` is for entries that themselves contain focusable child widgets (it implements container-style focus/child navigation).

## Checkbox / CycleButton

```java
public static Checkbox.Builder Checkbox.builder(Component message, Font font);
public class Checkbox.Builder {
  Builder pos(int x, int y);
  Builder onValueChange(Checkbox.OnValueChange);
  Builder selected(boolean);
  Builder selected(OptionInstance<Boolean> option);   // can bind directly to an OptionInstance<Boolean>!
  Builder tooltip(Tooltip);
  Builder maxWidth(int);
  Checkbox build();
}

public static <T> CycleButton.Builder<T> CycleButton.builder(Function<T,Component> valueStringifier, Supplier<T> defaultValueSupplier);
public static <T> CycleButton.Builder<T> CycleButton.builder(Function<T,Component>, T defaultValue);
public static CycleButton.Builder<Boolean> CycleButton.booleanBuilder(Component onText, Component offText, boolean initial);
public static CycleButton.Builder<Boolean> CycleButton.onOffBuilder(boolean initial);

public class CycleButton.Builder<T> {
  Builder<T> withValues(Collection<T>);
  Builder<T> withValues(T... values);
  Builder<T> withTooltip(OptionInstance.TooltipSupplier<T>);
  Builder<T> displayOnlyValue();
  CycleButton<T> create(Component name, CycleButton.OnValueChange<T> onChange);
  CycleButton<T> create(int x, int y, int w, int h, Component name);
  CycleButton<T> create(int x, int y, int w, int h, Component name, CycleButton.OnValueChange<T> onChange);
}
```

## StringWidget / MultiLineTextWidget

```java
public StringWidget(Component, Font);
public StringWidget(int x, int y, Component, Font);
public StringWidget(int x, int y, int w, int h, Component, Font);
public StringWidget setMaxWidth(int);

public MultiLineTextWidget(Component, Font);
public MultiLineTextWidget(int x, int y, Component, Font);
public MultiLineTextWidget setMaxWidth(int);
public MultiLineTextWidget setMaxRows(int);
public MultiLineTextWidget setCentered(boolean);
```

Both extend `AbstractStringWidget` (package `net.minecraft.client.gui.components`, unchanged location).

## Component

```java
public static MutableComponent Component.literal(String text);
public static MutableComponent Component.translatable(String key);
public static MutableComponent Component.translatable(String key, Object... args);
```

Unchanged from 1.21.x.

## SystemToast (simple notification)

```java
package net.minecraft.client.gui.components.toasts;

public SystemToast(SystemToast.SystemToastId id, Component title, @Nullable Component message);

public static void add(ToastManager toastManager, SystemToast.SystemToastId id, Component title, @Nullable Component message);
public static void addOrUpdate(ToastManager, SystemToast.SystemToastId, Component, Component);
```

Get the `ToastManager`: **not** on `Minecraft` directly — it hangs off the public `gui` field:

```java
public final net.minecraft.client.gui.Gui gui;          // field on Minecraft
public ToastManager Gui.toastManager();                  // getter on Gui
```

So: `SystemToast.add(Minecraft.getInstance().gui.toastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE /* or make your own id */, Component.literal("Title"), Component.literal("Body"));`
`SystemToastId` is a small record/enum-like type in `SystemToast` — for a custom mod toast, reuse the constructor pattern Mojang uses in `onLowDiskSpace` etc. (construct a `SystemToastId` and call `SystemToast.add`).

## TitleScreen / OptionsScreen / VideoSettingsScreen

```java
net.minecraft.client.gui.screens.TitleScreen
  TitleScreen();
  TitleScreen(boolean fading);
  TitleScreen(boolean fading, LogoRenderer logoRenderer);

net.minecraft.client.gui.screens.options.OptionsScreen
  OptionsScreen(Screen lastScreen, Options options, boolean gamemasterWarning);

net.minecraft.client.gui.screens.options.VideoSettingsScreen extends OptionsSubScreen
  VideoSettingsScreen(Screen lastScreen, Minecraft minecraft, Options options);
```

All three package/class names are unchanged versus what you'd expect from recent 1.21.x snapshots — no rename here, only the rendering plumbing inside them changed (they now implement `extractRenderState` like every other `Screen`).

---

# 8. Fabric API modules (fabric-api-0.161.0+26.2, nested jars under META-INF/jars)

All 42 declared nested modules were extracted and cross-checked against `fabric.mod.json`'s `jars` array (exact match, 42/42). Two modules the brief asked about are **not part of this build**:

- **`fabric-client-gametest-api-v1` is ABSENT.** Not declared in fabric.mod.json, not present as a nested jar. This Fabric API build does not ship the client gametest API at runtime — RigTune cannot depend on `ClientGameTestContext` / `TestSingleplayerContext` / `TestWorldBuilder` / `TestClientWorldContext` from this jar. (It may exist only as a separate Gradle/Loom test-sourceset artifact that isn't bundled into the distributed mod jar — not confirmed, low priority to chase further.)
- **There is no separate "HUD API" module** — it lives inside `fabric-rendering-v1` (see below). No standalone `fabric-hud-api-v1` jar exists.

## ScreenEvents / Screens — `fabric-screen-api-v1` (5.2.1)

```java
public final class net.fabricmc.fabric.api.client.screen.v1.ScreenEvents {
  public static final Event<ScreenEvents.BeforeInit> BEFORE_INIT;
  public static final Event<ScreenEvents.AfterInit> AFTER_INIT;
  public static Event<ScreenEvents.Remove> remove(Screen);
  public static Event<ScreenEvents.BeforeExtract> beforeExtract(Screen);
  public static Event<ScreenEvents.AfterBackground> afterBackground(Screen);
  public static Event<ScreenEvents.AfterForeground> afterForeground(Screen);
  public static Event<ScreenEvents.AfterExtract> afterExtract(Screen);
  public static Event<ScreenEvents.BeforeTick> beforeTick(Screen);
  public static Event<ScreenEvents.AfterTick> afterTick(Screen);
}

public interface ScreenEvents.BeforeInit { void beforeInit(Minecraft, Screen, int, int); }
public interface ScreenEvents.AfterInit  { void afterInit(Minecraft, Screen, int, int); }
```

Register on `AFTER_INIT` to add a button to `TitleScreen`/`OptionsScreen`:
```java
ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
    if (screen instanceof TitleScreen) {
        Screens.getWidgets(screen).add(...); // see below — no getButtons() any more
    }
});
```
Note `26.x` also has per-screen render pipeline events (`beforeExtract`/`afterExtract`/`afterBackground`/`afterForeground`) reflecting the new `extractRenderState`-based GUI render split (see §7) — extract happens once per frame, background/foreground are draw phases.

```java
public final class net.fabricmc.fabric.api.client.screen.v1.Screens {
  public static List<AbstractWidget> getWidgets(Screen);   // NOT getButtons() any more
  public static Font getFont(Screen);
  public static Minecraft getMinecraft(Screen);
}
```
**Rename vs older Fabric API**: `Screens.getButtons(Screen)` (pre-1.21ish, when button lists were `List<AbstractWidget>` typed as buttons) is gone — use `getWidgets(Screen)`, which returns `List<AbstractWidget>` covering every renderable/narratable widget, not just buttons.

## ClientTickEvents / ClientLifecycleEvents — `fabric-lifecycle-events-v1` (4.1.4)

```java
public final class ClientTickEvents {
  public static final Event<StartTick> START_CLIENT_TICK;
  public static final Event<EndTick> END_CLIENT_TICK;
  public static final Event<StartLevelTick> START_LEVEL_TICK;
  public static final Event<EndLevelTick> END_LEVEL_TICK;
}
public interface StartTick      { void onStartTick(Minecraft); }
public interface EndTick        { void onEndTick(Minecraft); }
public interface StartLevelTick { void onStartTick(ClientLevel); }
public interface EndLevelTick   { void onEndTick(ClientLevel); }
```
`END_CLIENT_TICK` is the standard hook for a benchmark harness that needs to run once per game tick (not per frame) — e.g. sampling FPS counters or driving scripted camera moves.

```java
public final class ClientLifecycleEvents {
  public static final Event<ClientStarted> CLIENT_STARTED;
  public static final Event<ClientStopping> CLIENT_STOPPING;
}
public interface ClientStarted  { void onClientStarted(Minecraft); }
public interface ClientStopping { void onClientStopping(Minecraft); }
```

## KeyMappingHelper — `fabric-key-mapping-api-v1` (2.0.5)

**Renamed from `KeyBindingHelper`** (package is `net.fabricmc.fabric.api.client.keymapping.v1`, not `keybinding.v1`):
```java
public final class net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper {
  public static KeyMapping registerKeyMapping(KeyMapping);
  public static InputConstants.Key getBoundKeyOf(KeyMapping);
}
```

Vanilla `net.minecraft.client.KeyMapping` in 26.2 (confirmed via javap on the vanilla jar):
```java
public KeyMapping(String name, int keyCode, KeyMapping.Category category);
public KeyMapping(String name, InputConstants.Type type, int keyCode, KeyMapping.Category category);
public KeyMapping(String name, InputConstants.Type type, int keyCode, KeyMapping.Category category, int order);
public KeyMapping.Category getCategory();
```
**Confirmed category rewrite (1.21.9+ change, carries into 26.2)**: the 3rd/4th constructor arg is `KeyMapping.Category`, not `String`. `Category` is now a **record** (`net.minecraft.client.KeyMapping$Category extends java.lang.Record`) wrapping a `net.minecraft.resources.Identifier id`, with built-in constants:
```java
public final class KeyMapping.Category extends Record {
  public static final Category MOVEMENT, MISC, MULTIPLAYER, GAMEPLAY, INVENTORY, CREATIVE, SPECTATOR, DEBUG;
  public KeyMapping.Category(Identifier id);
  public static Category register(Identifier id);   // custom categories
  public Component label();
  public Identifier id();
}
```
A mod adding its own keybinding category must call `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rigtune", "benchmark"))` and pass the result into the `KeyMapping` constructor — a bare string category (old-style) will not compile.

**Also confirmed**: `net.minecraft.resources.ResourceLocation` has been **renamed to `net.minecraft.resources.Identifier`** in 26.2 (no `ResourceLocation` class exists in the jar at all — see Gotchas). This affects every API signature that used to take `ResourceLocation`.

## HUD render API — inside `fabric-rendering-v1` (25.3.3), package `...rendering.v1.hud`

**No `HudRenderCallback` any more.** Replaced by a layered element-registry API:
```java
public interface net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry {
  public static void addFirst(Identifier id, HudElement element);
  public static void addLast(Identifier id, HudElement element);
  public static void attachElementBefore(Identifier anchor, Identifier id, HudElement element);
  public static void attachElementAfter(Identifier anchor, Identifier id, HudElement element);
  public static void removeElement(Identifier id);
  public static void replaceElement(Identifier id, Function<HudElement, HudElement> replacer);
}

public interface net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement {
  void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker);
}
```
Note the callback method is `extractRenderState`, matching the vanilla 26.x GUI's extract/render split (see §7) — a HUD element extracts a render *state* object off the render thread's frame data rather than drawing directly.

`VanillaHudElements` gives the `Identifier` anchors for every built-in HUD piece (useful for `attachElementBefore`/`After`), e.g. `CROSSHAIR`, `HOTBAR`, `ARMOR_BAR`, `HEALTH_BAR`, `FOOD_BAR`, `AIR_BAR`, `MOUNT_HEALTH`, `INFO_BAR`, `EXPERIENCE_LEVEL`, `HELD_ITEM_TOOLTIP`, `SPECTATOR_TOOLTIP`, `MOB_EFFECTS`, `BOSS_BAR`, `SLEEP`, `DEMO_TIMER`, `SCOREBOARD`, `OVERLAY_MESSAGE`, `TITLE_AND_SUBTITLE`, `CHAT`, `PLAYER_LIST`, `SUBTITLES`, `MISC_OVERLAYS`, `SPECTATOR_MENU`.

For an on-screen FPS/1%-low overlay, register a `HudElement` with `HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rigtune","overlay"), element)`.

## WorldRenderEvents — RENAMED/RESTRUCTURED to `LevelRenderEvents` + `LevelExtractionEvents`

**`WorldRenderEvents` does not exist anywhere in this Fabric API build** (grepped every nested module jar — zero hits). It has been replaced by two classes in `net.fabricmc.fabric.api.client.rendering.v1.level`:

```java
public final class LevelRenderEvents {
  public static final Event<StartMain> START_MAIN;
  public static final Event<AfterOpaqueTerrain> AFTER_OPAQUE_TERRAIN;
  public static final Event<CollectSubmits> COLLECT_SUBMITS;
  public static final Event<AfterSolidFeatures> AFTER_SOLID_FEATURES;
  public static final Event<AfterTranslucentFeatures> AFTER_TRANSLUCENT_FEATURES;
  public static final Event<BeforeBlockOutline> BEFORE_BLOCK_OUTLINE;
  public static final Event<BeforeGizmos> BEFORE_GIZMOS;
  public static final Event<BeforeTranslucentTerrain> BEFORE_TRANSLUCENT_TERRAIN;
  public static final Event<AfterTranslucentTerrain> AFTER_TRANSLUCENT_TERRAIN;
  public static final Event<EndMain> END_MAIN;
  public static final Event<LevelExtractionEvents.AfterBlockOutlineExtraction> AFTER_BLOCK_OUTLINE_EXTRACTION;
  public static final Event<LevelExtractionEvents.EndExtraction> END_EXTRACTION;
}
```
Callback params are `LevelRenderContext` / `LevelTerrainRenderContext` (for `StartMain`/`AfterOpaqueTerrain`) rather than the old `WorldRenderContext`. `START_MAIN`/`END_MAIN` are the closest equivalents to the old `WorldRenderEvents.START`/`END` for "run code once per world-render frame" (e.g. driving a benchmark's frame-time sampling from the render thread instead of the tick thread). This split (extraction vs. render-thread events, `LevelExtractionEvents` separate from `LevelRenderEvents`) mirrors vanilla's own extract/render split in 26.x (see §7's `extractRenderState` note).

# Gotchas relevant to §8
- `fabric-client-gametest-api-v1` is not bundled in this Fabric API release — don't plan on it.
- `Screens.getButtons()` → `Screens.getWidgets()`.
- `HudRenderCallback` → `HudElementRegistry`/`HudElement` (extract-based, layered, keyed by `Identifier`).
- `WorldRenderEvents` → `LevelRenderEvents` + `LevelExtractionEvents` (also renamed World→Level throughout, consistent with vanilla's Level naming).
- `KeyBindingHelper` → `KeyMappingHelper`, package `client.keymapping.v1` not `client.keybinding.v1`.
- `KeyMapping`'s category parameter is now `KeyMapping.Category` (a record with an `Identifier`, registered via `Category.register(Identifier)`), not a translation-key `String`.
- `ResourceLocation` → `Identifier` (`net.minecraft.resources.Identifier`), used pervasively by the above APIs.

---

# 9. Player and camera control (benchmark automation)

## Rotation / teleport — `net.minecraft.world.entity.Entity` (superclass of `LocalPlayer` via `AbstractClientPlayer`)
`LocalPlayer` itself declares no rotation/teleport methods — use the ones inherited from `Entity`:

```java
public void setYRot(float);
public void setXRot(float);

// "snap" = instant, non-interpolated position set (client-side, no smoothing):
public void snapTo(double x, double y, double z);
public void snapTo(double x, double y, double z, float yRot, float xRot);
public void snapTo(net.minecraft.world.phys.Vec3 pos);
public void snapTo(net.minecraft.world.phys.Vec3 pos, float yRot, float xRot);
public void snapTo(net.minecraft.core.BlockPos pos, float yRot, float xRot);
public void absSnapTo(double x, double y, double z);
public void absSnapTo(double x, double y, double z, float yRot, float xRot);

// "teleport" family (mostly server-authoritative / cross-dimension):
public void teleportTo(double x, double y, double z);   // client-safe simple overload
public boolean teleportTo(ServerLevel, double, double, double, Set<Relative>, float yRot, float xRot, boolean); // server-side
public Entity teleport(TeleportTransition);              // server-side, dimension change etc.
public void teleportRelative(double, double, double);

public final void setPos(double, double, double);
public final void setPos(net.minecraft.world.phys.Vec3);
public final double getX(); public final double getY(); public final double getZ();
```

**Renamed vs 1.21.x expectations**: there is no plain `moveTo(double,double,double)` on `Entity` in 26.2 — that name doesn't appear. For a client-side benchmark camera (singleplayer, own player), use `snapTo(x,y,z,yRot,xRot)` — it sets position and rotation instantly without server round-trip smoothing. `setYRot`/`setXRot` alone only change facing, not position.

## Flying — `net.minecraft.world.entity.player.Abilities`
```java
public class Abilities {
    public boolean invulnerable;
    public boolean flying;
    public boolean mayfly;
    public boolean instabuild;
    public boolean mayBuild;
    private float flyingSpeed;
    private float walkingSpeed;
}
```
Access via `Entity`/`Player`'s `getAbilities()` (inherited, not shown above but standard). Set `abilities.flying = true` then call `player.onUpdateAbilities()` (declared directly on `LocalPlayer`, public) to push the change to the server.

## Running commands against the integrated server
```java
// net.minecraft.client.Minecraft
public boolean hasSingleplayerServer();
public net.minecraft.client.server.IntegratedServer getSingleplayerServer();

// net.minecraft.server.MinecraftServer
public net.minecraft.commands.Commands getCommands();
public net.minecraft.commands.CommandSourceStack createCommandSourceStack();

// net.minecraft.commands.Commands
public void performPrefixedCommand(CommandSourceStack source, String command); // handles leading "/"
public void performCommand(com.mojang.brigadier.ParseResults<CommandSourceStack> parsed, String command);
```
Usage pattern for a benchmark mod (singleplayer, from the client thread — must still be marshalled onto the server thread, e.g. via `server.execute(Runnable)` inherited from `MinecraftServer`, since the integrated server runs on its own thread even in singleplayer):
```java
IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
if (server != null) {
    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @s"));
}
```

## Sending a command from the client connection
`net.minecraft.client.multiplayer.ClientPacketListener` (the type of `LocalPlayer.connection`, confirmed field: `public final ClientPacketListener connection;`):
```java
public void sendChat(String message);
public void sendCommand(String command);              // <-- exact match for what the brief guessed; no leading "/"
public void sendUnattendedCommand(String command, net.minecraft.client.gui.screens.Screen sourceScreen);
```
`Minecraft.getInstance().player.connection.sendCommand("gamemode spectator")` works exactly as named — no renaming here despite the modern command-signing infrastructure (signing is handled internally by `sendCommand`, see `lambda$sendCommand$0` building a `MessageSignature`). For singleplayer automation, prefer the direct server-side `performPrefixedCommand` above — it skips network/signing round trips entirely and is more reliable for a benchmark harness running in the same JVM.

---

# 10. OSHI (hardware info)

**Version confirmed**: `oshi-core-6.9.0.jar` at `%APPDATA%/ModrinthApp/meta/libraries/com/github/oshi/oshi-core/6.9.0/oshi-core-6.9.0.jar`.

## Entry point
```java
oshi.SystemInfo si = new oshi.SystemInfo();
oshi.hardware.HardwareAbstractionLayer hal = si.getHardware();
oshi.software.os.OperatingSystem os = si.getOperatingSystem();
```

## CPU — `oshi.hardware.CentralProcessor` (interface, via `hal.getProcessor()`)
```java
public abstract CentralProcessor.ProcessorIdentifier getProcessorIdentifier();
public abstract long getMaxFreq();                 // Hz
public abstract long[] getCurrentFreq();            // Hz per logical core
public abstract int getLogicalProcessorCount();
public abstract int getPhysicalProcessorCount();
public abstract int getPhysicalPackageCount();
public default  double getSystemCpuLoad(long ticksMs);
```
`CentralProcessor.ProcessorIdentifier` (via `getProcessorIdentifier()`):
```java
public String getVendor();
public String getName();          // full marketing name, e.g. "AMD Ryzen 9 ..."
public String getFamily();
public String getModel();
public boolean isCpu64bit();
public long getVendorFreq();      // Hz, from brand string
```

## Memory — `oshi.hardware.GlobalMemory` (via `hal.getMemory()`)
```java
public abstract long getTotal();      // bytes
public abstract long getAvailable();  // bytes
public abstract VirtualMemory getVirtualMemory();
public abstract List<PhysicalMemory> getPhysicalMemory();
```

## Battery — `oshi.hardware.PowerSource` (via `hal.getPowerSources()`, not shown by name above but standard OSHI API — list getter on `HardwareAbstractionLayer`)
```java
public interface PowerSource {
    String getName();
    double getRemainingCapacityPercent();
    boolean isPowerOnLine();
    boolean isCharging();
    boolean isDischarging();
    int getCurrentCapacity();
    int getMaxCapacity();
    ...
}
```
Not referenced anywhere in vanilla's `SystemReport` (see below) — untested by Mojang in-game, but the interface is present on the 6.9.0 classpath and safe to call; wrap in try/catch like vanilla does for all its OSHI calls (see `ignoreErrors`, below).

## GPU — `oshi.hardware.GraphicsCard` (via `hal.getGraphicsCards()`)
```java
public interface GraphicsCard {
    String getName();
    String getDeviceId();
    String getVendor();
    String getVersionInfo();  // driver version string
    long getVRam();           // bytes
}
```

## How vanilla itself uses OSHI — proof it works in-game
`net.minecraft.SystemReport` (built by `Minecraft`'s crash/debug reporting) is the reference implementation:
```java
private void putHardware(oshi.SystemInfo);
private void putSoftware(oshi.SystemInfo);
private void putPhysicalMemory(List<oshi.hardware.PhysicalMemory>);
private void putVirtualMemory(oshi.hardware.VirtualMemory);
private void putMemory(oshi.hardware.GlobalMemory);
private void putGraphics(List<oshi.hardware.GraphicsCard>);
private void putProcessor(oshi.hardware.CentralProcessor);
private void ignoreErrors(String label, Runnable r);   // wraps every OSHI call — exceptions are swallowed and logged, not fatal
```
Decompiled bytecode confirms the exact call chain:
```
new oshi.SystemInfo()
  -> SystemInfo.getHardware() -> HardwareAbstractionLayer
       -> hal.getProcessor()            -> putProcessor(...)
            .getProcessorIdentifier()   -> vendor/name/freq
            .getLogicalProcessorCount() / getPhysicalProcessorCount() / getPhysicalPackageCount()
       -> hal.getMemory()               -> putMemory(...) -> getVirtualMemory(), getPhysicalMemory()
       -> hal.getGraphicsCards()        -> putGraphics(...) -> card.getVRam(), etc. (checkcast oshi/hardware/GraphicsCard)
  -> SystemInfo.getOperatingSystem()    -> putSoftware(...) -> os.getCurrentProcess() -> OSProcess (RSS, virtual size, up time...)
```
**Important for RigTune**: every OSHI call in vanilla is wrapped in `ignoreErrors(String, Runnable)` — on some systems/drivers OSHI hardware queries can throw or hang briefly (WMI/registry access on Windows). Copy this defensive pattern: never call OSHI methods directly on the render thread without a try/catch or a cached background-computed result. `PowerSource` is **not** used by vanilla at all — only CPU, memory, graphics cards, and the current OS process are queried.

---

# Gotchas

Consolidated list of the most surprising/breaking findings across all sections (each section above also has inline notes; this is the "read this before you start coding" summary):

1. **`GuiGraphics` does not exist in 26.2.** It's `net.minecraft.client.gui.GuiGraphicsExtractor`. Every `drawString`/`drawCenteredString` call is now `text(...)`/`centeredText(...)`. This is the single biggest source of compile breakage porting mixins/screens from 1.21.x. (§7)
2. **`Screen.render(GuiGraphics, int, int, float)` does not exist.** It's `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)`. Screens/widgets *extract render state* into a `GuiRenderState` object; actual GPU draw calls happen later in the frame (consistent with 26.x's Vulkan-capable renderer). Any mixin targeting `render` needs to retarget `extractRenderState`/`extractContents`/`extractWidgetRenderState`. (§7)
3. **`Screen.keyPressed` takes a `net.minecraft.client.input.KeyEvent`**, not `(int,int,int)`; `Button.onPress` takes `InputWithModifiers`. Broader input-handling refactor in 26.x. (§7)
4. **`ResourceLocation` → `Identifier`** (`net.minecraft.resources.Identifier`). No `ResourceLocation` class exists in the 26.2 jar at all. Affects every API that used to take `ResourceLocation`, including Fabric API's HUD/keymapping APIs. (§8)
5. **`KeyMapping`'s category is now `KeyMapping.Category`**, a record wrapping an `Identifier`, registered via `Category.register(Identifier)` — a bare string category will not compile. `KeyBindingHelper` is also renamed to `KeyMappingHelper` (package `client.keymapping.v1`, not `keybinding.v1`). (§8)
6. **`WorldRenderEvents` doesn't exist in Fabric API 0.161.0+26.2** — replaced by `LevelRenderEvents` + `LevelExtractionEvents`. **`HudRenderCallback` doesn't exist either** — replaced by `HudElementRegistry`/`HudElement` (extract-based). **`fabric-client-gametest-api-v1` is not bundled** in this Fabric API build at all. (§8)
7. **The brief's `"Found graphics adapter: AdapterInfo{...}"` log line and `AdapterInfo` class are not vanilla** — exhaustively grepped, zero hits in `net.minecraft`/`com.mojang`. That's **Sodium's** `GraphicsAdapterProbe`/`GraphicsAdapterInfo` (driver-workaround detection), not a vanilla API. Vanilla's real GPU-info API is `RenderSystem.getDevice().getDeviceInfo()` → `DeviceInfo` (name/vendorName/driverInfo/backendName/type). (§2)
8. **No VRAM total anywhere in blaze3d's device-info classes.** `DeviceLimits.maxMemoryAllocationSize()` is a per-allocation cap, not total VRAM. The only real source is OSHI's `GraphicsCard.getVRam()`. (§2, §10)
9. **`Options.renderDistance()`'s change listener does NOT trigger a chunk-rebuild call** (`LevelExtractor.allChanged()`) — bytecode-confirmed it *only* flips `graphicsPreset` to `CUSTOM`. Many other graphics booleans/enums (cutoutLeaves, textureFiltering, etc.) *do* call `LevelExtractor.allChanged()`/`resetSampler()` from their listeners. Don't assume `.set()` on renderDistance has an immediate visible effect. (§1.4)
10. **Editing any individual graphics `OptionInstance` silently flips `graphicsPreset` to `CUSTOM`** (`Options.setGraphicsPresetToCustom()` is wired into nearly every one of their `valueChanged` listeners) — there's no way to hand-tune a setting without losing the Fast/Fancy/Fabulous preset label. (§1.3)
11. **`DebugScreenOverlay.frameTimeLogger` has no public getter** (unlike `tickTimeLogger`/`pingLogger`/`bandwidthLogger`, which do). For FPS/1%-low tracking, poll `Minecraft.getFrameTimeNs()` yourself every frame rather than trying to reach Mojang's private ring buffer. `Minecraft.getFps()` is a 1-second-smoothed average, not raw per-frame data — useless for percentile math. (§4)
12. **Sodium 0.9.2's own config (`sodium-options.json`) is much smaller than older Sodium versions** — render distance, vsync, FOV, GUI scale, brightness, fullscreen etc. are no longer duplicated into Sodium's config; its settings screen just edits vanilla's `Options` fields directly for those. Only genuinely Sodium-internal renderer settings (quality/performance/advanced/debug/notifications) live in the JSON. **No Vulkan backend toggle exists in Sodium's config** — its `DrawBackend` (OPENGL/VK_MULTIDRAW/VK_INDIRECT) is auto-derived from vanilla's active backend, not user-configurable. (§6)
13. **Sodium's JSON field naming is a naive camelCase→snake_case converter** — every capital letter gets its own underscore, so `useNoErrorGLContext` becomes `use_no_error_g_l_context` (confirmed against the real file), not `use_no_error_gl_context`. Don't hand-write JSON keys for acronym-containing fields without checking this.
14. **Sodium fully `@Overwrite`s `LevelRenderer.hasRenderedAllSections()`/`isSectionCompiledAndVisible()` and `LevelExtractor.countRenderedSections()`** — calling vanilla's own method names still works correctly with Sodium installed (they delegate to `SodiumWorldRenderer` internally), so you don't need Sodium-specific branching for these specific calls — only for things vanilla doesn't expose at all (`isTerrainRenderComplete()`, `getVisibleChunkCount()`, `isSectionReady(x,y,z)`). (§5)
15. **`Entity.moveTo(double,double,double)` doesn't exist in 26.2** — use `snapTo(...)` for an instant, non-interpolated client-side position set (good for a benchmark camera). (§9)
16. **`ClientPacketListener.sendCommand(String)` exists exactly as named** despite the command-signing overhaul — but prefer the server-side `Commands.performPrefixedCommand` via `IntegratedServer.execute(...)` for singleplayer automation, it skips network/signing round trips entirely. (§9)
17. **OSHI `PowerSource` (battery) is completely unused by vanilla's `SystemReport`** — only CPU, memory, graphics cards, and the current OS process are queried. Treat battery info as best-effort/unverified; most benchmarking rigs are desktops with an empty `PowerSource` list anyway. (§10)
18. Every OSHI call in vanilla's `SystemReport` is wrapped in a `ignoreErrors(String, Runnable)` helper that swallows exceptions — copy this defensive pattern, OSHI hardware queries can throw or hang on some Windows driver/WMI configurations. (§10)
