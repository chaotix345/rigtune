# RigTune API Reference: Minecraft Java 26.2 (Mojang-mapped, unobfuscated)

Research date: 2026-09-24. Source jars (read-only, never modified):

- **Vanilla client**: `%APPDATA%/ModrinthApp/meta/versions/26.2-0.19.5/26.2-0.19.5.jar` — confirmed unobfuscated/real Mojang names (`net/minecraft/client/Minecraft.class`, `net/minecraft/client/Options.class` etc. present verbatim; 10,952 classes total).
- **Libraries**: `%APPDATA%/ModrinthApp/meta/libraries/` (LWJGL 3.4.1, OSHI 6.9.0, etc.)
- **Sodium**: `sodium-fabric-0.9.2+mc26.2.jar`
- **Fabric API**: `fabric-api-0.161.0+26.2.jar` (jar-in-jar; 42 nested modules under `META-INF/jars/`, extracted individually)
- **User's real configs** (read-only reference): `Fabric 26.2/options.txt`, `Fabric 26.2/config/sodium-options.json`

All signatures below are `javap -p` output against these exact jars, not recalled from training data. Where behavior required bytecode reading (`javap -c`/`-v`, including tracing `invokedynamic` call sites through the `BootstrapMethods` table to resolve exact lambda/method-reference targets), that's noted explicitly.

---

## 1. `net.minecraft.client.Options`

### 1.1 `OptionInstance<T>` — the generic wrapper

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
  public AbstractWidget createButton(Options options);   // builds the settings-screen widget for this option

  // construction
  public OptionInstance(String, TooltipSupplier<T>, CaptionBasedToString<T>, ValueSet<T>, T, ValueUpdateListener<? super T>);
  public OptionInstance(String, TooltipSupplier<T>, CaptionBasedToString<T>, ValueSet<T>, Codec<T>, T, ValueUpdateListener<? super T>);
  public static OptionInstance<Boolean> createBoolean(String, boolean);   // + 3 overloads w/ tooltip/listener
}

public interface OptionInstance$ValueUpdateListener<T> { void valueChanged(T); }
public interface OptionInstance$ValueSet<T> {
  Function<OptionInstance<T>, AbstractWidget> createButton(TooltipSupplier<T>, Options, int, int, int, ValueUpdateListener<? super T>);
  Optional<T> validateValue(T);
  Codec<T> codec();
}
public final class OptionInstance$IntRange extends Record implements IntRangeBase {
  public IntRange(int minInclusive, int maxInclusive);
  public IntRange(int minInclusive, int maxInclusive, boolean applyValueImmediately);
  public Optional<Integer> validateValue(Integer);
  public int minInclusive(); public int maxInclusive(); public boolean applyValueImmediately();
}
public final class OptionInstance$Enum<T> extends Record implements CycleableValueSet<T> {
  public Enum(List<T> values, Codec<T> codec);
  public Optional<T> validateValue(T);
  public CycleButton$ValueListSupplier<T> valueListSupplier();
}
```

**`get()`** returns the cached field directly — cheap to call every frame.

**`set(T newValue)` behavior (bytecode-verified):**
1. `values.validateValue(newValue)` clamps/rejects via the `ValueSet` (e.g. `IntRange`); falls back to the current value if invalid.
2. If `Minecraft.getInstance().isRunning()` is `false` (still booting), the value is just stored — **no listener fires during bootstrap**.
3. If `Objects.equals(oldValue, newValue)`, it's a no-op — listener does **not** fire when the value hasn't actually changed.
4. Otherwise: field is updated, then `onValueUpdate.valueChanged(newValue)` fires.

All side effects of changing a setting happen inside that per-field listener supplied when `Options`'s constructor builds each `OptionInstance` — there is no generic "any option changed" hook. To intercept every change you'd have to mixin `OptionInstance.set` itself.

The first `String` ctor arg (e.g. `"options.renderDistance"`) is the settings-screen **translation key**, not the options.txt save key.

Concrete `ValueSet` implementations used throughout `Options`: `OptionInstance$IntRange`, `OptionInstance$Enum<T>` (backs `CycleButton`), plus others not inspected in depth (`UnitDouble`-style ones for sliders).

Interesting: `IntRange.applyValueImmediately` is a real, per-slider flag. Bytecode-confirmed: `renderDistance`'s `IntRange` is constructed as `new IntRange(2, hasEnoughMemory ? 32 : 16, false)` — `applyValueImmediately = false`, i.e. the vanilla render-distance slider does not pretend to apply live the way some other integer sliders do. `simulationDistance` is built the same way (`false`). If you build your own slider widget around these `OptionInstance`s, respect this flag for vanilla-consistent behavior.

### 1.2 Video/performance `OptionInstance` accessors, cross-checked against the user's `options.txt`

Every field is `private final OptionInstance<T> name;` with a matching `public OptionInstance<T> name();` getter — call `.get()`/`.set(v)` on the returned instance, there's no setter on `Options` itself.

| Accessor | Type | options.txt key | Notes |
|---|---|---|---|
| `renderDistance()` | `OptionInstance<Integer>` | `renderDistance` | range `[2, 16 or 32]` (32 if `Runtime.maxMemory() >= 1_000_000_000L`); `applyValueImmediately=false` |
| `simulationDistance()` | `OptionInstance<Integer>` | `simulationDistance` | range `[2 or 5, 16 or 32]`; `applyValueImmediately=false` |
| `entityDistanceScaling()` | `OptionInstance<Double>` | `entityDistanceScaling` | |
| `framerateLimit()` | `OptionInstance<Integer>` | `maxFps` | **key renamed**; `UNLIMITED_FRAMERATE_CUTOFF` constant = "Unlimited" |
| `preferredGraphicsBackend()` | `OptionInstance<PreferredGraphicsApi>` | `preferredGraphicsBackend` | OpenGL/Vulkan choice, see §1.3 |
| `graphicsPreset()` | `OptionInstance<GraphicsPreset>` | `graphicsPreset` | Fast/Fancy/Fabulous/Custom, see §1.3 |
| `inactivityFpsLimit()` | `OptionInstance<InactivityFpsLimit>` | `inactivityFpsLimit` | enum `MINIMIZED`/`AFK` |
| `cloudStatus()` | `OptionInstance<CloudStatus>` | `renderClouds` | **key renamed**; enum `OFF`/`FAST`/`FANCY` — codec has a legacy boolean alternative, which is why the file can show `renderClouds:"false"` instead of a quoted enum name |
| `cloudRange()` | `OptionInstance<Integer>` | `cloudRange` | range 2–128, default 128; translation key is `options.renderCloudsDistance` but save key is `cloudRange` |
| `weatherRadius()` | `OptionInstance<Integer>` | `weatherRadius` | |
| `cutoutLeaves()` | `OptionInstance<Boolean>` | `cutoutLeaves` | |
| `vignette()` | `OptionInstance<Boolean>` | `vignette` | |
| `improvedTransparency()` | `OptionInstance<Boolean>` | `improvedTransparency` | |
| `ambientOcclusion()` | `OptionInstance<Boolean>` | `ao` | **key renamed** |
| `chunkSectionFadeInTime()` | `OptionInstance<Double>` | `chunkSectionFadeInTime` | seconds; `0.0` disables fade |
| `prioritizeChunkUpdates()` | `OptionInstance<PrioritizeChunkUpdates>` | `prioritizeChunkUpdates` | enum `NONE`/`PLAYER_AFFECTED`/`NEARBY` — **not** `StringRepresentable`, saved as a raw ordinal int (matches `prioritizeChunkUpdates:1` in the real file), unlike most other enum-backed options which save quoted strings |
| `mipmapLevels()` | `OptionInstance<Integer>` | `mipmapLevels` | range 0–4 |
| `maxAnisotropyBit()` | `OptionInstance<Integer>` | `maxAnisotropyBit` | range 1–3; raw bit count, see `maxAnisotropyValue()` below |
| `textureFiltering()` | `OptionInstance<TextureFilteringMethod>` | `textureFiltering` | enum `NONE`/`RGSS`/`ANISOTROPIC` |
| `guiScale()` | `OptionInstance<Integer>` | `guiScale` | `0` = auto |
| `gamma()` | `OptionInstance<Double>` | (not in default options.txt) | brightness |
| `fov()` | `OptionInstance<Integer>` | `fov` | stored normalized `0.0–1.0`, not degrees |
| `particles()` | `OptionInstance<ParticleStatus>` (`net.minecraft.server.level.ParticleStatus`) | `particles` | |
| `entityShadows()` | `OptionInstance<Boolean>` | `entityShadows` | |
| `biomeBlendRadius()` | `OptionInstance<Integer>` | `biomeBlendRadius` | range 0–7 |
| `enableVsync()` | `OptionInstance<Boolean>` | `enableVsync` | |
| `fullscreen()` | `OptionInstance<Boolean>` | `fullscreen` | |
| `exclusiveFullscreen()` | `OptionInstance<Boolean>` | `exclusiveFullscreen` | also plain field `exclusiveFullscreenFromStartup` (captured at launch) |
| `menuBackgroundBlurriness()` | `OptionInstance<Integer>` | `menuBackgroundBlurriness` | also plain int getter `getMenuBackgroundBlurriness()` |

Other renamed keys seen in the real `options.txt`: `mouseSensitivity`↔`sensitivity`, `discrete_mouse_scroll`↔`discreteMouseScroll`, `hideLightningFlashes`↔`hideLightningFlash`, `panoramaScrollSpeed`↔`panoramaSpeed`. `syncChunkWrites` is a plain `boolean` field, not an `OptionInstance`. Most other keys match the field name 1:1. `Options.class` has ~191 total `OptionInstance<?>` fields; anything present in `options.txt` but not in this table follows the same 1:1-or-documented-rename pattern.

The literal save-key strings live in `processOptions(Options$FieldAccess)`, called by both `load()` and `save()` — the single source of truth if you need airtight certainty for a field not in this table:
```java
interface Options$OptionAccess { <T> void process(String key, OptionInstance<T> option); }
interface Options$FieldAccess extends Options$OptionAccess {
  int process(String, int); boolean process(String, boolean); String process(String, String); float process(String, float);
  <T> T process(String, T, Function<String,T> parse, Function<T,String> serialize);
}
private void processOptions(Options$FieldAccess);
```
It's one big method with ~150 call sites (`access.process("someKey", someField)` once per persisted field) — grep the disassembly's `processOptions` body for `ldc` string constants immediately preceding each `process` invocation for 100% certainty on any key not listed above.

`maxAnisotropyValue(): int` — derives the real sample count from the raw `maxAnisotropyBit` `OptionInstance<Integer>` (likely `1 << maxAnisotropyBit`).
`isRestartRequiredToApplyVideoSettings(): boolean` — bytecode decoded exactly:
```java
return preferredGraphicsBackend.get() != preferredGraphicsBackendFromStartup
    || exclusiveFullscreen.get() != exclusiveFullscreenFromStartup;
```
i.e. **switching OpenGL↔Vulkan or toggling exclusive fullscreen both require a restart**, driven by the same flag/UI warning. Use this to show a restart banner on a custom settings screen.

### 1.3 Graphics preset & graphics-API selection (26.x-new)

```java
public final class net.minecraft.client.GraphicsPreset extends Enum<GraphicsPreset> implements StringRepresentable {
  public static final GraphicsPreset FAST, FANCY, FABULOUS, CUSTOM;
  public static final Codec<GraphicsPreset> CODEC;
  public String getSerializedName();   // "fast"/"fancy"/"fabulous"/"custom"
  public String getKey();
  public void apply(Minecraft);        // pushes preset values into ~15 OptionInstances
}
```
`GraphicsPreset.apply(Minecraft)` calls `.set(...)` on, in order: `biomeBlendRadius`, `renderDistance`, `prioritizeChunkUpdates`, `simulationDistance`, `ambientOcclusion`, `cloudStatus`, `particles`, `mipmapLevels`, `entityShadows`, `entityDistanceScaling`, `menuBackgroundBlurriness`, `cloudRange`, `cutoutLeaves`, `improvedTransparency`, `weatherRadius`, and continues (almost certainly also `textureFiltering`/`vignette`/`chunkSectionFadeInTime`/`maxAnisotropyBit`, not walked to completion) — via a private static helper `set(OptionsSubScreen, OptionInstance<T>, T)` that takes a live `OptionsSubScreen`, so applying a preset through this exact method is coupled to an open settings screen, not trivially callable headless. **`Options.applyGraphicsPreset(GraphicsPreset)` is the public entry point** — it sets a guard flag `isApplyingGraphicsPreset = true` then applies. Each preset (FAST/FANCY/FABULOUS) has different constants baked in per field; CUSTOM is a sentinel meaning "user has hand-tuned at least one of these."

`Options.setGraphicsPresetToCustom()` — **private**, bytecode-confirmed to be invoked from the `valueChanged` listener of essentially every individual graphics-related `OptionInstance` (cutoutLeaves, improvedTransparency, vignette, ambientOcclusion, textureFiltering, weatherRadius, mipmapLevels, etc). **Hand-editing any one of these via `.set()` silently flips `graphicsPreset` to `CUSTOM`** — same as the vanilla screen showing "Custom" the moment you touch one slider after picking a preset. No way to avoid this short of not using the vanilla setter.

```java
public final class net.minecraft.client.PreferredGraphicsApi extends Enum<PreferredGraphicsApi> implements StringRepresentable {
  public static final PreferredGraphicsApi DEFAULT, OPENGL, VULKAN;
  public static final Codec<PreferredGraphicsApi> CODEC;
  public Component caption();
  public String getSerializedName();          // "default"/"opengl"/"vulkan"
  public GpuBackend[] getBackendsToTry();      // com.mojang.blaze3d.systems.GpuBackend[]
}
```
**26.2 has a first-class OpenGL-vs-Vulkan preference**: `Options.preferredGraphicsBackend()`, serialized as `preferredGraphicsBackend`. It requires a **restart** to take effect (restart-warning `Component`s present, `GRAPHICS_API_TOOLTIP`/`GRAPHICS_API_TOOLTIP_VULKAN`) — the plain field `preferredGraphicsBackendFromStartup` holds what's *actually active right now* vs. the pending choice in the `OptionInstance`. Both `com.mojang.blaze3d.opengl.GlBackend` and `com.mojang.blaze3d.vulkan.VulkanBackend` exist as concrete `GpuBackend` implementations in this jar — Vulkan is a real, working backend in 26.2, not a stub.

Other enums: `TextureFilteringMethod` (`NONE`/`RGSS`/`ANISOTROPIC`, each with a `Component caption()`), `InactivityFpsLimit` (`MINIMIZED`/`AFK`), `PrioritizeChunkUpdates` (`NONE`/`PLAYER_AFFECTED`/`NEARBY`), `CloudStatus` (`OFF`/`FAST`/`FANCY`).

### 1.4 `set()` side effects — chunk/geometry invalidation (bytecode-verified)

```java
private static void Options.operateOnLevelExtractor(Consumer<net.minecraft.client.renderer.extract.LevelExtractor> action);
```
Fetches `Minecraft.getInstance().levelExtractor`; if non-null (a level is loaded) runs `action.accept(levelExtractor)`; if null (main menu) it's a safe no-op.

By tracing each listener lambda's bytecode (`javap -v`, matching `InvokeDynamic` call sites to their `BootstrapMethods` table entries — this nails down the exact target method, not a guess), here is the confirmed per-option effect table:

| Option (options.txt key) | Effect on `set()` |
|---|---|
| `biomeBlendRadius` | `LevelExtractor.allChanged()` — full section rebuild |
| `cloudRange` | `LevelExtractor.allChanged()` |
| `cutoutLeaves` | `LevelExtractor.allChanged()` |
| `improvedTransparency` | conditionally calls `GpuWarnlistManager.showWarning()` (GPU-support warning) then `LevelExtractor.allChanged()` |
| `ao` (ambientOcclusion) | `LevelExtractor.allChanged()` |
| `textureFiltering` | `LevelExtractor.resetSampler()` (lighter-weight than a full rebuild — just resets the texture sampler) |
| `maxAnisotropyBit` | `LevelExtractor.resetSampler()` |
| `renderDistance` | **only** calls `setGraphicsPresetToCustom()` — no direct `allChanged()`/`resetSampler()` call |

**Non-obvious finding:** `renderDistance`'s own listener does not itself force an immediate chunk-grid rebuild. The actual chunk reload on render-distance change is handled elsewhere — by comparing `LevelExtractor.lastViewDistance()` against the current effective render distance each frame/tick (`Options` likely exposes an effective-render-distance getter used for this comparison), not via this listener. Don't assume calling `options.renderDistance().set(x)` alone will visibly change loaded chunks the same frame; if you need an instant visual effect for a benchmark, you may need to poke the poller or wait a tick. `simulationDistance` very likely follows the same "Custom-flip only" pattern but wasn't individually bytecode-walked.

`net.minecraft.client.renderer.extract.LevelExtractor` (new in 26.x — companion/successor to `LevelRenderer`'s old reload logic; see §5) relevant surface:
```java
public class LevelExtractor implements ResourceManagerReloadListener {
  public void setLevel(ClientLevel);
  public void allChanged();
  public void resetSampler();
  public void blockChanged(BlockPos, int);
  public void setBlockDirty(BlockPos, boolean);
  public void setBlocksDirty(int,int,int,int,int,int);
  public void setSectionDirty(int,int,int);
  public void setSectionDirtyWithNeighbors(int,int,int);
  public int countRenderedSections();
  public double totalSections();
  public double lastViewDistance();
  public String sectionStatistics();
  public String entityStatistics();
}
```

### 1.5 `save()` / `load()`

```java
public void save();
public void load();
public File getFile();
public String dumpOptionsForReport();   // crash-report / F3 dump
```
`save()` opens `new PrintWriter(new OutputStreamWriter(new FileOutputStream(optionsFile), UTF_8))`, writes `"version:" + dataVersion` first, then `processOptions(new Options$3(this, writer))` which `println`s `key:value` per option (quoted strings for enums, e.g. `"custom"`) — matches the real file format exactly. Afterward it separately writes `fullscreenVideoModeString`, resource pack lists, key bindings (`key_<name>:<binding>`), sound volumes (`soundCategory_<name>:<vol>`), model parts (`modelPart_<name>:<bool>`). No public "just persist this one option" method — `save()` rewrites the whole file every time.
`optionsFile` is set once in the constructor: `new File(gameDir, "options.txt")`. Only public constructor: `Options(Minecraft, File gameDir)`.

---

## 2. GPU info: vendor, renderer, driver, backend, VRAM

**The `AdapterInfo` class and the `"Found graphics adapter: AdapterInfo{...}"` log line described in the task brief do not exist anywhere in this 26.2 build** (exhaustive case-insensitive content grep across all 10,952 extracted classes for `"adapter"` and `"AdapterInfo"` — zero hits in `net.minecraft`/`com.mojang`). That log line is actually **Sodium's**, not vanilla's: Sodium 0.9.2 has `net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterInfo` (interface: `vendor()`, `name()`) and `GraphicsAdapterProbe` (`findAdapters()`/`getAdapters()`), used purely for its own driver-workaround detection (see §6). If you've seen that exact log text, Sodium was installed — don't build RigTune's vanilla GPU-info reader around a vanilla `AdapterInfo` class, it isn't there.

What 26.2 actually has and logs (bytecode-confirmed call sites in `Minecraft.class`, right after window/device creation):

```java
com.mojang.blaze3d.systems.RenderSystem
  public static GpuDevice getDevice();       // throws/asserts if not yet initialized
  public static GpuDevice tryGetDevice();     // null-safe
  public static String getBackendDescription();
  public static void initRenderer(GpuDevice);

com.mojang.blaze3d.systems.GpuDevice
  public DeviceInfo getDeviceInfo();
  public List<String> getLastDebugMessages();
  public boolean isDebuggingEnabled();

public final class com.mojang.blaze3d.systems.DeviceInfo extends Record {
  public String name();                 // GPU model name, e.g. "NVIDIA GeForce RTX 4080"
  public String vendorName();
  public String driverInfo();           // driver version string
  public String backendName();          // e.g. "OpenGL"/"Vulkan"
  public DeviceType type();             // OTHER/INTEGRATED/DISCRETE/VIRTUAL/CPU
  public boolean isZZeroToOne();
  public float timestampPeriod();
  public DeviceLimits limits();
  public DeviceFeatures features();
  public Set<String> underlyingExtensions();
  public HintsAndWorkarounds hintsAndWorkarounds();
}
public final class com.mojang.blaze3d.systems.DeviceLimits extends Record {
  public int maxAnisotropy(); public int minUniformOffsetAlignment(); public int maxTextureSize();
  public long maxMemoryAllocationSize();   // NOT total VRAM — just the max single-allocation size
  public int maxMultiDrawDirectInterleavedDrawCount(); public int maxColorAttachments();
}
public final class com.mojang.blaze3d.systems.DeviceFeatures extends Record {
  public boolean shaderDrawParameters(); public boolean multiDrawDirectInterleaved();
  public boolean multiDrawDirectSeparate(); public boolean multiDrawIndirect();
  public boolean drawIndirect(); public boolean nonZeroFirstInstance(); public boolean persistentMapping();
}
public final class com.mojang.blaze3d.systems.HintsAndWorkarounds extends Record {
  public boolean writeToBufferIsSlow(); public boolean anisotropyHasKnownIssues();
}
public final class com.mojang.blaze3d.systems.DeviceType extends Enum<DeviceType> { OTHER, INTEGRATED, DISCRETE, VIRTUAL, CPU }
```

Exact real log lines (bytecode-verified string constants + arg order in `Minecraft.class`):
- `"Using graphics backend {}, using drivers: {}"` — args: `deviceInfo.backendName()`, `deviceInfo.driverInfo()`
- `"Using graphics device: {} ({})"` — args: `deviceInfo.name()`, `deviceInfo.vendorName()`
- `"Using graphics device extensions: {}"` — arg: `String.join(",", deviceInfo.underlyingExtensions())`

Backend selection is also visible on the window itself:
```java
com.mojang.blaze3d.platform.Window
  public com.mojang.blaze3d.systems.GpuBackend backend();   // the actual active backend for this window
com.mojang.blaze3d.systems.GpuBackend  (interface)
  public String getName();
  public void setWindowHints();
  public void handleWindowCreationErrors(GLFWErrorCapture$Error) throws BackendCreationException;
  public GpuDevice createDevice(long, ShaderSource, GpuDebugOptions, Runnable) throws BackendCreationException;
```
Concrete implementations present in the jar: `com.mojang.blaze3d.opengl.GlBackend`, `com.mojang.blaze3d.vulkan.VulkanBackend`. `PreferredGraphicsApi.getBackendsToTry()` (§1.3) returns the ordered fallback list of `GpuBackend`s Minecraft will attempt for that preference.

**RigTune usage:** `RenderSystem.tryGetDevice().getDeviceInfo()` gives vendor/renderer/driver/backend name/device type in one call, null-safe before the renderer is up. Cross-reference `Options.preferredGraphicsBackend().get()` (pending choice, §1.3) against `deviceInfo.backendName()` (what's actually active) to detect a pending-restart mismatch.

**VRAM: not exposed here.** `DeviceLimits` only has `maxMemoryAllocationSize(): long` (max single allocation, not total VRAM) plus texture-size/anisotropy/alignment limits — no total-VRAM getter anywhere in `com.mojang.blaze3d`, and Sodium's own `GraphicsAdapterInfo` (vendor + name only) doesn't have it either. For actual VRAM size, use OSHI's `GraphicsCard.getVRam()` (§10) — this is also exactly what Mojang's own `SystemReport` does (§10), so it's proven to work in-game.

---

## 3. Window and monitor

```java
public final class com.mojang.blaze3d.platform.Window implements AutoCloseable {
  public long handle();                        // the GLFW window handle
  public com.mojang.blaze3d.systems.GpuBackend backend();
  public int getWidth();  public int getHeight();
  public int getScreenWidth(); public int getScreenHeight();
  public int getGuiScaledWidth(); public int getGuiScaledHeight();
  public int getX(); public int getY();
  public int getGuiScale();
  public int getRefreshRate();
  public boolean isFullscreen();
  public boolean isFocused(); public boolean isIconified(); public boolean isMinimized();
  public void toggleFullScreen();
  public void setWindowed(int, int);
  public void changeFullscreenVideoMode();
  public void updateFullscreenIfChanged();
  public Optional<VideoMode> getPreferredFullscreenVideoMode();
  public void setPreferredFullscreenVideoMode(Optional<VideoMode>);
  public Monitor findBestMonitor();
  public void setGuiScale(int);
  public static String getPlatform();
}

public final class com.mojang.blaze3d.platform.Monitor extends Record {
  public static Monitor tryCreate(long glfwMonitorHandle);
  public String monitorName();
  public long monitor();                        // GLFW monitor handle
  public List<VideoMode> videoModes();
  public VideoMode currentMode();
  public int x(); public int y();
  public VideoMode getPreferredVidMode(Optional<VideoMode>);
  public int indexOfMode(VideoMode);
  public int modeCount();
  public VideoMode mode(int index);
}

public final class com.mojang.blaze3d.platform.VideoMode {
  public VideoMode(int width, int height, int redBits, int greenBits, int blueBits, int refreshRate);
  public VideoMode(org.lwjgl.glfw.GLFWVidMode);
  public int getWidth(); public int getHeight();
  public int getRedBits(); public int getGreenBits(); public int getBlueBits();
  public int getRefreshRate();
  public String write();                         // serialized form used in options.txt's fullscreenVideoModeString
  public static Optional<VideoMode> read(String);
}

public class com.mojang.blaze3d.platform.MonitorManager implements AutoCloseable {
  public Monitor getMonitor(long glfwHandle);
  public Monitor findBestMonitor(Window);
}
```

**Use:** `Minecraft.getInstance().getWindow()` → `Window`. Refresh rate via `Window.getRefreshRate()` (queries the current monitor) or `Monitor.currentMode().getRefreshRate()`. `Window.handle()` is the raw GLFW `long` for any direct LWJGL GLFW calls RigTune needs (e.g. `GLFW.glfwGetWindowAttrib`). `Window.backend()` is the fastest way to check OpenGL-vs-Vulkan at runtime without going through `RenderSystem`. For full monitor enumeration (e.g. a fullscreen-resolution picker), `window.findBestMonitor().videoModes()`. There's also `Minecraft.windowSurface()` → `com.mojang.blaze3d.systems.GpuSurface` (the actual swapchain/surface object, backend-specific — not needed for a settings/benchmark mod, but exists).

---

## 4. FPS and frame timing

```java
public class net.minecraft.client.Minecraft {
  public int getFps();          // smoothed once-per-second value, NOT per-frame
  public long getFrameTimeNs(); // set EVERY frame in the main run loop, nanoseconds
  public DebugScreenOverlay getDebugOverlay();
}
```
Bytecode-confirmed call order inside `Minecraft`'s per-frame loop: `frameTimeNs` is set from the measured frame duration, then `this.getDebugOverlay().logFrameDuration(nanos)` is called with that same value every frame regardless of whether the F3 charts are visible (feeding the F3 chart continuously), then once per second the smoothed `fps` field is updated from accumulated frame count.

**For 1% lows, use `getFrameTimeNs()`, not `getFps()`.** `getFps()` is already a 1-second-smoothed average — useless for percentile computation. Sample `getFrameTimeNs()` every frame yourself and compute your own percentiles (sort samples over a window, take the value at the 99th percentile of frame *time* = the "1% low" frame).

Per-frame timing is also logged into a ring-buffer sampler on the F3 debug overlay:

```java
package net.minecraft.util.debugchart;
public interface SampleLogger { void logFullSample(long[]); void logSample(long); void logPartialSample(long, int); }
public interface SampleStorage { int capacity(); int size(); long get(int); long get(int, int); void reset(); }
public abstract class AbstractSampleLogger implements SampleLogger { /* ring buffer over `sample`/`defaults` */ }
public class LocalSampleLogger extends AbstractSampleLogger implements SampleStorage {
  public static final int CAPACITY;   // ring buffer size (matches F3 chart width, ~240)
  public LocalSampleLogger(int lines);
  public long get(int index); public long get(int index, int line);   // ring-buffer read, most-recent-relative
}

public class net.minecraft.client.gui.components.DebugScreenOverlay {
  private final LocalSampleLogger frameTimeLogger;    // NO public getter — see Gotchas
  private final LocalSampleLogger tickTimeLogger;     public LocalSampleLogger getTickTimeLogger();
  private final LocalSampleLogger pingLogger;         public LocalSampleLogger getPingLogger();
  private final LocalSampleLogger bandwidthLogger;    public LocalSampleLogger getBandwidthLogger();
  public void logFrameDuration(long nanos);            // public — called once per frame from Minecraft.class
  public boolean showFpsCharts();
  public void toggleFpsCharts();
}

public class net.minecraft.client.gui.components.debugchart.FpsDebugChart extends AbstractDebugChart {
  public FpsDebugChart(Font, SampleStorage);
  protected String toDisplayString(double);       // formats a sample as ms/fps text
}
```

**Gotcha / RigTune plan:** `frameTimeLogger` has **no public getter** on `DebugScreenOverlay` (unlike `tickTimeLogger`/`pingLogger`/`bandwidthLogger`, which do). Two options for computing average FPS + 1% lows:
1. **Accessor mixin** (`@Accessor("frameTimeLogger")` on `DebugScreenOverlay`) to reach the existing ring buffer and read its samples directly via `get(int)`.
2. **Simpler: mixin/inject at `HEAD` of `DebugScreenOverlay.logFrameDuration(long)`** to capture each frame's nanosecond duration into RigTune's own rolling buffer — no accessor needed, and you get the exact same values the vanilla FPS chart uses. Recommended: `Minecraft.getFrameTimeNs()` alone only gives you the *current* frame, not history.

### Sodium's own frame-time percentile tracker — simpler, since Sodium is a RigTune dependency

```java
package net.caffeinemc.mods.sodium.client.util;
public final class FrameTimeStatistics {
  public static final FrameTimeStatistics INSTANCE;
  public void logSample(long nanos);
  public Reference2LongArrayMap<FrameTimeStatistics.Percentile> get();   // percentile -> frame time (ns)
  public void invalidate();
}
public final class FrameTimeStatistics.Percentile extends Record {
  public String name(); public int window(); public float p();          // e.g. a "1% low" style percentile definition
}
```
Sodium registers this as a debug-screen entry (`SodiumFpsPercentilesEntry`) via the new vanilla debug-entry API:
```java
package net.minecraft.client.gui.components.debug;
public interface DebugScreenEntry {
  void display(DebugScreenDisplayer displayer, Level level, LevelChunk a, LevelChunk b);
  default boolean isAllowed(boolean showAll);
  default DebugEntryCategory category();
}
public interface DebugScreenDisplayer {
  void addPriorityLine(String); void addLine(String);
  void addToGroup(Identifier group, Collection<String> lines); void addToGroup(Identifier group, String line);
}
```
Since Sodium is a required RigTune dependency, **`FrameTimeStatistics.INSTANCE.get()` is the least-effort way to get 1%-low-style percentile data already computed**, instead of re-deriving it from raw per-frame samples.

### Per-frame hooks

- Fabric API's `LevelRenderEvents.END_MAIN` / `START_MAIN` (renamed from `WorldRenderEvents`, see §8) fire once per rendered world frame on the render thread — a clean non-mixin hook point for sampling `Minecraft.getFrameTimeNs()` or driving your own frame-time ring buffer. A `HudElement.extractRenderState(...)` registered via `HudElementRegistry` also runs every frame and needs no world/level loaded — good for a benchmark overlay that must run from the main menu too.
- No dedicated "end of frame" event independent of world rendering exists in Fabric API (checked all 42 nested modules — nothing named `EndFrame`/`FrameEvent`). A mixin `@Inject` at `HEAD` of `DebugScreenOverlay.logFrameDuration(long)` (or directly in `Minecraft`'s render loop near where `frameTimeNs` is written) is the most precise option if you need to sample before any other mod's HUD/render-event code runs.
- Ticks (`ClientTickEvents.END_CLIENT_TICK`, §8) are 20/s fixed-rate — **not** suitable for per-frame FPS sampling, only for driving benchmark scripting logic (camera moves, command dispatch) that doesn't need frame granularity.
- `DeltaTracker` (passed into most render-extraction methods) exposes `getGameTimeDeltaPartialTick(boolean)` / `getRealtimeDeltaTicks()` for partial-tick interpolation.

---

## 5. Chunk/section render readiness

Vanilla — 26.x splits the old monolithic `LevelRenderer` into extraction (`LevelExtractor`, §1.4) and render-submission (`LevelRenderer` itself); section-readiness queries are split across both:
```java
public class net.minecraft.client.renderer.LevelRenderer implements AutoCloseable {
  public boolean hasRenderedAllSections();
  public boolean isSectionCompiledAndVisible(BlockPos);
  public void invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors);
  public void clearVisibleSections();
  public void resetLevelRenderData();
  public SectionRenderDispatcher sectionRenderDispatcher();
  public ObjectArrayList<SectionRenderDispatcher$RenderSection> visibleSections();
  public ObjectArrayList<SectionRenderDispatcher$RenderSection> nearbyVisibleSections();
  public LongCollection expectedChunks();
  public SectionOcclusionGraph sectionOcclusionGraph();
}

public class net.minecraft.client.renderer.extract.LevelExtractor implements ResourceManagerReloadListener {
  public int countRenderedSections();     // <-- brief's "countRenderedSections" lives HERE, not on LevelRenderer
  public double totalSections();
  public double lastViewDistance();
  public void allChanged();
  public String sectionStatistics();
  public String entityStatistics();
}
```
`hasRenderedAllSections()` is the direct yes/no answer ("has the renderer finished compiling everything currently in view"). `LevelExtractor.countRenderedSections()`/`totalSections()` give the numeric progress. Reach the live instances via `Minecraft.getInstance().levelRenderer` and `Minecraft.getInstance().levelExtractor` — both are **public final fields directly on `Minecraft`**.

**Sodium 0.9.2 overrides this wholesale.** Sodium ships mixins that fully replace (`@Overwrite`, not just decorate) the relevant vanilla methods:
```java
// net.caffeinemc.mods.sodium.mixin.core.render.world.LevelRendererMixin
public boolean hasRenderedAllSections();               // @Overwrite, same signature as vanilla
public boolean isSectionCompiledAndVisible(BlockPos);  // @Overwrite
public SodiumWorldRenderer sodium$getWorldRenderer();   // accessor added to LevelRenderer

// net.caffeinemc.mods.sodium.mixin.core.render.world.LevelExtractorMixin
public int countRenderedSections();                    // @Overwrite
public String sectionStatistics();                     // @Overwrite
```
**So whichever vanilla method you call (`LevelRenderer.hasRenderedAllSections()` etc.), you transparently get Sodium's answer once Sodium is installed** — you don't need to special-case Sodium for these specific calls, vanilla's own method names keep working. You only need Sodium-specific APIs for things vanilla doesn't expose at all.

```java
public class net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer {
  public static SodiumWorldRenderer instance();          // throws if not initialized
  public static SodiumWorldRenderer instanceNullable();  // null-safe
  public boolean isTerrainRenderComplete();               // exact equivalent of hasRenderedAllSections()
  public boolean isSectionReady(int chunkX, int chunkY, int chunkZ);
  public int getVisibleChunkCount();
  public String getChunksDebugString();
  public Collection<String> getDebugStrings(boolean);
  public void scheduleRebuildForChunk(int x, int y, int z, boolean important);
  public void scheduleTerrainUpdate();
}
// Also reachable off a live LevelRenderer instance via Sodium's extension interface:
// ((LevelRendererExtension) minecraft.levelRenderer).sodium$getWorldRenderer()
```

**RigTune usage:** detect Sodium via `FabricLoader.getInstance().isModLoaded("sodium")`; since it's a required dependency, prefer `SodiumWorldRenderer.instanceNullable().isTerrainRenderComplete()` for the benchmark's "world is fully loaded, start measuring" gate — it reflects Sodium's own render-section-manager state (what's actually drawing), rather than falling back to vanilla's `hasRenderedAllSections()` (which Sodium's mixin overrides anyway, so in practice they agree).

---

## 6. Sodium 0.9.2 config

### Loading/saving

```java
public class net.caffeinemc.mods.sodium.client.SodiumClientMod {
  public static SodiumOptions options();          // the live singleton — read/write fields directly
  private static SodiumOptions loadConfig();       // called once, lazily, the first time options() is called
  public static void restoreDefaultOptions();
  public static boolean allowDebuggingOptions();
  public static String getVersion();
}

public class net.caffeinemc.mods.sodium.client.gui.SodiumOptions {
  public final QualitySettings quality;
  public final PerformanceSettings performance;
  public final AdvancedSettings advanced;
  public final DebugSettings debug;
  public final NotificationSettings notifications;
  public static SodiumOptions defaults();
  public static SodiumOptions loadFromDisk();
  public static void writeToDisk(SodiumOptions) throws IOException;   // <config>/sodium-options.json
  public boolean isReadOnly(); public void setReadOnly();
}
```
Config file path is resolved internally (`private static Path getConfigPath()`, default file name constant resolves to `config/sodium-options.json`) — always go through `SodiumClientMod.options()` + `SodiumOptions.writeToDisk(options())` to save; there's no auto-save-on-mutation, don't hardcode the path yourself.

### Nested settings classes and their real fields

```java
public class SodiumOptions$QualitySettings {
  public boolean hiddenFluidCulling;
  public boolean improvedFluidShaping;
  public boolean useClosestPointEntitySort;
  public com.mojang.blaze3d.textures.FilterMode pixelFilteringMode;
}
public class SodiumOptions$PerformanceSettings {
  public int chunkBuilderThreads;
  public net.caffeinemc.mods.sodium.client.render.chunk.DeferMode chunkBuildDeferMode;
  public boolean animateOnlyVisibleTextures;
  public boolean useEntityCulling;
  public boolean useFogOcclusion;
  public boolean useBlockFaceCulling;
  public boolean useNoErrorGLContext;
  public net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode quadSplittingMode;
}
public class SodiumOptions$AdvancedSettings { public boolean enableMemoryTracing; }
public class SodiumOptions$DebugSettings { public boolean terrainSortingEnabled; }
public class SodiumOptions$NotificationSettings {
  public boolean hasClearedDonationButton;
  public boolean hasSeenDonationPrompt;
  public boolean hasEditedFullscreenOption;
}
```
All plain mutable public fields, Gson-serialized — no getters.

### Real `config/sodium-options.json` structure (ground truth, read from the user's live file)

```json
{
  "quality": {
    "hidden_fluid_culling": true,
    "improved_fluid_shaping": false,
    "use_closest_point_entity_sort": false,
    "pixel_filtering_mode": "NEAREST"
  },
  "performance": {
    "chunk_builder_threads": 0,
    "chunk_build_defer_mode": "ALWAYS",
    "animate_only_visible_textures": true,
    "use_entity_culling": true,
    "use_fog_occlusion": true,
    "use_block_face_culling": true,
    "use_no_error_g_l_context": true,
    "quad_splitting_mode": "SAFE"
  },
  "advanced": { "enable_memory_tracing": false },
  "debug": { "terrain_sorting_enabled": true },
  "notifications": { "has_cleared_donation_button": false, "has_seen_donation_prompt": true, "has_edited_fullscreen_option": true }
}
```
Gson lower-snake-case naming policy auto-derives JSON keys from the Java field names — note the mechanical (slightly odd) result `useNoErrorGLContext` → `use_no_error_g_l_context` (each capital letter run gets its own underscore, "GL" splits into "G_L"). Don't hand-write key names; derive them the same way Gson would if you add fields.

Note how small this is compared to older Sodium versions — **render distance, vsync, FOV, GUI scale, brightness, fullscreen etc. are no longer Sodium's own options**; Sodium's own settings screen just re-displays/edits the *vanilla* `Options` fields for those (bytecode-confirmed: its page-building code directly references `options.renderDistance`, `options.simulationDistance`, `options.gamma`, `options.guiScale`) rather than duplicating them into `sodium-options.json`. Only genuinely Sodium-specific renderer-internals settings live in this file.

### `DeferMode` (`chunk_build_defer_mode`) and `QuadSplittingMode` (`quad_splitting_mode`) — full enum lists

```java
public enum net.caffeinemc.mods.sodium.client.render.chunk.DeferMode {
  ALWAYS, ONE_FRAME, ZERO_FRAMES;
  public boolean allowsUnlimitedUploadDuration();
}
public enum net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode {
  OFF, SAFE, UNLIMITED;
  public boolean allowsSplitting();
  public boolean quantizeTriggerNormals();
  public int getMaxTotalQuads(int);
}
```
(Confirmed against the real JSON: `chunk_build_defer_mode: "ALWAYS"`, `quad_splitting_mode: "SAFE"`.)

### Workaround / driver-detection

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
  AMD_GAME_OPTIMIZATION_BROKEN;
}
```
Vendor-specific detector classes exist alongside: `...workarounds.amd.AmdWorkarounds`, `...workarounds.intel.IntelWorkarounds`, `...workarounds.nvidia.NvidiaWorkarounds` + `NvidiaDriverVersion`, and `compatibility.checks.GraphicsDriverChecks` (general driver sanity checks, independent of the `Workarounds` enum). Separate adapter-identification classes (used by the workaround detectors, and the source of the log text the brief quoted — see §2):
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

### Vulkan

**Sodium 0.9.2 has no dedicated Vulkan on/off option of its own** — no `vulkan`-named field anywhere in `SodiumOptions`, nothing backend-related in the real `sodium-options.json`. Sodium instead auto-detects which backend vanilla is running and adapts:
```java
public enum net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend {
  OPENGL, VK_MULTIDRAW, VK_INDIRECT,
  BACKEND;                       // the auto-selected active one
  private static DrawBackend chooseBackend();   // private — no setter, not user-configurable
}
```
It ships real Vulkan draw-path classes (`VKIndirectDrawBatch`, `VKMultiDrawBatch`, `VKDrawContext`, `VulkanPipelineMixin`, `VulkanRenderPassAccessor`) confirming it actively hooks the Vulkan backend rather than just tolerating it — driven entirely by whatever `Options.preferredGraphicsBackend()` (§1.3) resolved to for the running session plus hardware capability, not an independent Sodium setting. If RigTune wants to report "is Sodium using the Vulkan multidraw or indirect path", read `DrawBackend.BACKEND` (reflection/mixin needed, it's not a published API).

### Public API — `net.caffeinemc.mods.sodium.api.*`

What other mods (RigTune included) can do without touching Sodium internals:
- **`api.config.*`** (`ConfigEntryPoint` with `registerConfigEarly`/`registerConfigLate(ConfigBuilder)`, `structure.ConfigBuilder`/`ModOptionsBuilder`/`OptionPageBuilder`/`OptionGroupBuilder`/`BooleanOptionBuilder`/`IntegerOptionBuilder`/`EnumOptionBuilder`/`ExternalButtonOptionBuilder`, `option.OptionBinding`/`OptionFlag`/`OptionImpact`/`Range`/`Validator`) — lets a mod register its own config page inside Sodium's video settings screen. There's also `ConfigEntryPointForge`, confirming this is written loader-agnostic. Exact registration discovery mechanism (Fabric entrypoint key vs `ServiceLoader`) wasn't pinned down — no `META-INF/services` entry and no `FabricLoader.getEntrypoints("sodium:config", ...)` call site was found; treat as unconfirmed until tested, though the interface itself is real and public.
- **`api.vertex.buffer.VertexBufferWriter`** (`of(VertexConsumer)`/`tryOf(VertexConsumer)`, `push(MemoryStack, long, int, VertexFormat)`) / **`api.vertex.format.*`** (`EntityVertex`, `GlyphVertex`, `ParticleVertex`, `VertexFormatRegistry`) / **`api.vertex.attributes.common.*`** (`PositionAttribute`, `ColorAttribute`, `LightAttribute`, `NormalAttribute`, `OverlayAttribute`, `TextureAttribute`) / **`api.vertex.serializer.*`** — direct-memory vertex writing compatible with Sodium's chunk vertex formats, for mods that want to feed geometry into Sodium's fast path.
- **`api.memory.MemoryIntrinsics`**, **`api.math.MatrixHelper`** — low-level intrinsics for the above.
- **`api.blockentity.BlockEntityRenderHandler`** (`instance().addRenderPredicate(BlockEntityType<T>, BlockEntityRenderPredicate<T>)`) — opt a custom block-entity renderer out of Sodium's culling/batching.
- **`api.util.ColorARGB`/`ColorABGR`/`ColorMixer`/`ColorU8`/`NormI8`** — packing helpers matching Sodium's internal formats.
- **`api.texture.SpriteUtil`** (`markSpriteActive`, `hasAnimation`) — sprite/texture helpers.

None of this is needed just to *read* Sodium's settings/render state (§5/§6 above cover that) — it's for mods that want to render through Sodium's pipeline, which RigTune's core settings/benchmark goal doesn't need.

---

## 7. GUI in 26.2

### `Screen`

```java
protected Screen(Component title);
protected Screen(Minecraft minecraft, Font font, Component title);

public final void init(int width, int height);       // final — do not override
protected void init();                                 // override this to add widgets

// RENAMED render pipeline — no render(GuiGraphics,...) any more:
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

public boolean keyPressed(net.minecraft.client.input.KeyEvent event);   // event object, not (int,int,int)
public void tick();
public void removed();
public void added();
public void resize(int width, int height);
public Font getFont();
```
Override `init()` (not the `final init(int,int)`) to add widgets, and `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)` (not `render`) to draw — call `super.extractRenderState(...)` for the default background. `addRenderableWidget` still registers a widget for both rendering and input in one call — same contract as 1.21.x, just against the new render-state object.

### `GuiGraphics` → **`net.minecraft.client.gui.GuiGraphicsExtractor`**

**There is no class literally named `GuiGraphics` in 26.2.** Screens/widgets *extract render state* into a `GuiRenderState` object instead of drawing immediately (actual GPU submission happens later — this is the same extract/render split as `LevelExtractor`/`LevelRenderer` in §5). Constructor:
```java
public GuiGraphicsExtractor(Minecraft minecraft, net.minecraft.client.renderer.state.gui.GuiRenderState renderState, int width, int height);
```

```java
public void text(Font, String, int x, int y, int color);
public void text(Font, String, int x, int y, int color, boolean dropShadow);
public void text(Font, Component, int x, int y, int color);
public void text(Font, FormattedCharSequence, int x, int y, int color[, boolean dropShadow]);
public void centeredText(Font, Component|String|FormattedCharSequence, int x, int y, int color);
public void textWithWordWrap(Font, FormattedText, int x, int y, int wrapWidth, int color);
public void textWithBackdrop(Font, Component, int x, int y, int wrapWidth, int color);

public void fill(int x1, int y1, int x2, int y2, int color);
public void fill(RenderPipeline, int x1, int y1, int x2, int y2, int color);
public void fillGradient(int x1, int y1, int x2, int y2, int colorFrom, int colorTo);
public void outline(int x1, int y1, int x2, int y2, int color);
public void horizontalLine(int x1, int x2, int y, int color);
public void verticalLine(int x, int y1, int y2, int color);

public void blit(RenderPipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH);
public void blitSprite(RenderPipeline, Identifier atlas, int x, int y, int w, int h);
public void item(ItemStack, int x, int y);
public void itemDecorations(Font, ItemStack, int x, int y);
public void setTooltipForNextFrame(Font, Component, int x, int y);

public org.joml.Matrix3x2fStack pose();          // 2D-only transform stack, not the old 3D PoseStack
public void enableScissor(int,int,int,int); public void disableScissor();
public int guiWidth(); public int guiHeight();
public ActiveTextCollector textRenderer();        // not a plain Font field
public ActiveTextCollector textRendererForWidget(AbstractWidget, GuiGraphicsExtractor.HoveredTextEffects);
```
No `drawString`/`drawCenteredString` — it's `text(...)`/`centeredText(...)`. `Font` is still `net.minecraft.client.gui.Font` (unchanged), passed explicitly (get it from `Screen.getFont()`), not pulled implicitly off the extractor except through `textRenderer()`.

### `Button`

```java
public static Button.Builder Button.builder(Component message, Button.OnPress onPress);
public class Button.Builder {
  Builder pos(int x, int y); Builder width(int w); Builder size(int w, int h); Builder bounds(int x, int y, int w, int h);
  Builder tooltip(Tooltip); Builder createNarration(Button.CreateNarration);
  Button build();
}
public interface Button.OnPress { void onPress(Button button); }   // unchanged
```
`Button.onPress(InputWithModifiers)` — the *instance* click-handler method (distinct from the `OnPress` functional interface above) now takes a `net.minecraft.client.input.InputWithModifiers`, part of the same input-event object refactor as `Screen.keyPressed(KeyEvent)`.

### Scrollable lists

```java
AbstractSelectionList<E extends AbstractSelectionList.Entry<E>> extends AbstractContainerWidget
ObjectSelectionList<E extends ObjectSelectionList.Entry<E>> extends AbstractSelectionList<E>
ContainerObjectSelectionList<E extends ContainerObjectSelectionList.Entry<E>> extends AbstractSelectionList<E>

public AbstractSelectionList(Minecraft, int width, int height, int y0, int itemHeight);
protected int addEntry(E entry);
public void replaceEntries(Collection<E>);
public void setSelected(E); public E getSelected();
public void setScrollAmount(double);
protected void scrollToEntry(E);
protected void extractListItems(GuiGraphicsExtractor, int, int, float);   // renamed from renderList
protected void extractItem(GuiGraphicsExtractor, int, int, float, E);     // renamed from renderItem
```
`ObjectSelectionList` = simple single-column list. `ContainerObjectSelectionList` = entries with their own focusable child widgets.

### Checkbox / CycleButton / text widgets

```java
public static Checkbox.Builder Checkbox.builder(Component message, Font font);
// .pos(x,y) .onValueChange(...) .selected(boolean) .selected(OptionInstance<Boolean>) .tooltip(...) .maxWidth(int) .build()
Checkbox.Builder.selected(OptionInstance<Boolean> option);   // binds directly to an Options OptionInstance!

public static <T> CycleButton.Builder<T> CycleButton.builder(Function<T,Component>, T defaultValue);
public static CycleButton.Builder<Boolean> CycleButton.onOffBuilder(boolean initial);
public static CycleButton.Builder<Boolean> CycleButton.booleanBuilder(Component onText, Component offText, boolean initial);
CycleButton<T> Builder.create(Component name, CycleButton.OnValueChange<T> onChange);

public StringWidget(Component, Font);              // + x/y/w/h overloads, setMaxWidth(int)
public MultiLineTextWidget(Component, Font);        // + setMaxWidth/setMaxRows/setCentered
```
Both `StringWidget`/`MultiLineTextWidget` extend `AbstractStringWidget` (package `net.minecraft.client.gui.components`, unchanged location).

### `Component`

```java
public static MutableComponent Component.literal(String text);
public static MutableComponent Component.translatable(String key);
public static MutableComponent Component.translatable(String key, Object... args);
```
Unchanged from 1.21.x.

### `SystemToast`

```java
package net.minecraft.client.gui.components.toasts;
public SystemToast(SystemToast.SystemToastId id, Component title, @Nullable Component message);
public static void SystemToast.add(ToastManager, SystemToast.SystemToastId, Component title, @Nullable Component message);
public static void addOrUpdate(ToastManager, SystemToast.SystemToastId, Component, Component);
```
Get the `ToastManager` off `Gui`, **not** directly off `Minecraft`: `Minecraft.getInstance().gui.toastManager()` (`gui` is a public final field of type `net.minecraft.client.gui.Gui`).

### `TitleScreen` / `OptionsScreen` / `VideoSettingsScreen` — unchanged package/class names

```java
net.minecraft.client.gui.screens.TitleScreen(); TitleScreen(boolean fading); TitleScreen(boolean fading, LogoRenderer);
net.minecraft.client.gui.screens.options.OptionsScreen(Screen lastScreen, Options, boolean gamemasterWarning);
net.minecraft.client.gui.screens.options.VideoSettingsScreen extends OptionsSubScreen
  (Screen lastScreen, Minecraft, Options);
```
Only the internal render plumbing changed (they implement `extractRenderState` like every other `Screen`).

---

## 8. Fabric API modules (`fabric-api-0.161.0+26.2`, 42 nested jars)

All 42 declared modules extracted and cross-checked 1:1 against `fabric.mod.json`'s `jars` array. **Two things asked for in the brief are not part of this build**: `fabric-client-gametest-api-v1` (absent entirely — not declared, no nested jar; if RigTune needs `ClientGameTestContext`/`TestSingleplayerContext`/`TestWorldBuilder`/`TestClientWorldContext`, it'll need to be added as a separate, explicit Gradle dependency — not pulled in transitively by the main `fabric-api` artifact for this MC version) and a standalone HUD-API module (HUD API lives inside `fabric-rendering-v1` instead, see below).

### `ScreenEvents` / `Screens` — `fabric-screen-api-v1` (5.2.1)

```java
public final class net.fabricmc.fabric.api.client.screen.v1.ScreenEvents {
  public static final Event<ScreenEvents.BeforeInit> BEFORE_INIT;
  public static final Event<ScreenEvents.AfterInit> AFTER_INIT;
  public static Event<ScreenEvents.Remove> remove(Screen);
  public static Event<ScreenEvents.BeforeExtract> beforeExtract(Screen);
  public static Event<ScreenEvents.AfterBackground> afterBackground(Screen);
  public static Event<ScreenEvents.AfterForeground> afterForeground(Screen);
  public static Event<ScreenEvents.AfterExtract> afterExtract(Screen);   // per-frame, extract-phase
  public static Event<ScreenEvents.BeforeTick> beforeTick(Screen);
  public static Event<ScreenEvents.AfterTick> afterTick(Screen);
}
public interface ScreenEvents.BeforeInit { void beforeInit(Minecraft, Screen, int, int); }
public interface ScreenEvents.AfterInit  { void afterInit(Minecraft, Screen, int, int); }
```
Also present: `ScreenKeyboardEvents`/`ScreenMouseEvents` for per-screen input hooks. `26.x` also has per-screen render pipeline events (`beforeExtract`/`afterExtract`/`afterBackground`/`afterForeground`) reflecting the new `extractRenderState`-based GUI render split (see §7) — extract happens once per frame, background/foreground are draw phases.

```java
public final class Screens {
  public static List<AbstractWidget> getWidgets(Screen);   // renamed from getButtons(Screen)
  public static Font getFont(Screen);
  public static Minecraft getMinecraft(Screen);
}
```
Register a button on `TitleScreen`/`OptionsScreen` via `ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> { if (screen instanceof TitleScreen) Screens.getWidgets(screen).add(...); })`.

### `ClientTickEvents` / `ClientLifecycleEvents` — `fabric-lifecycle-events-v1` (4.1.4)

```java
public final class ClientTickEvents {
  public static final Event<StartTick> START_CLIENT_TICK;
  public static final Event<EndTick> END_CLIENT_TICK;        // once per game tick — good for scripted camera moves
  public static final Event<StartLevelTick> START_LEVEL_TICK;
  public static final Event<EndLevelTick> END_LEVEL_TICK;
}
public interface StartTick      { void onStartTick(Minecraft); }
public interface EndTick        { void onEndTick(Minecraft); }
public interface StartLevelTick { void onStartTick(ClientLevel); }
public interface EndLevelTick   { void onEndTick(ClientLevel); }

public final class ClientLifecycleEvents {
  public static final Event<ClientStarted> CLIENT_STARTED;
  public static final Event<ClientStopping> CLIENT_STOPPING;
}
public interface ClientStarted  { void onClientStarted(Minecraft); }
public interface ClientStopping { void onClientStopping(Minecraft); }
```

### `KeyMappingHelper` — `fabric-key-mapping-api-v1` (2.0.5)

**Renamed from `KeyBindingHelper`**, package `net.fabricmc.fabric.api.client.keymapping.v1` (not `keybinding.v1`):
```java
public final class KeyMappingHelper {
  public static KeyMapping registerKeyMapping(KeyMapping);
  public static InputConstants.Key getBoundKeyOf(KeyMapping);
}
```
Vanilla `net.minecraft.client.KeyMapping` (confirmed on the vanilla jar):
```java
public KeyMapping(String name, int keyCode, KeyMapping.Category category);
public KeyMapping(String name, InputConstants.Type type, int keyCode, KeyMapping.Category category);
public KeyMapping(String name, InputConstants.Type type, int keyCode, KeyMapping.Category category, int sortOrder);
public KeyMapping.Category getCategory();

public final class KeyMapping.Category extends Record {
  public static final Category MOVEMENT, MISC, MULTIPLAYER, GAMEPLAY, INVENTORY, CREATIVE, SPECTATOR, DEBUG;
  public KeyMapping.Category(Identifier id);
  public static Category register(Identifier id);   // custom categories
  public Component label();
  public Identifier id();
}
```
**1.21.9+ category rewrite carries into 26.2**: the category arg is `KeyMapping.Category` (a record wrapping an `Identifier`), not a translation-key `String`. Custom category: `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rigtune", "benchmark"))`.

**Also confirmed**: `net.minecraft.resources.ResourceLocation` has been **renamed to `net.minecraft.resources.Identifier`** in 26.2 — no `ResourceLocation` class exists in the jar at all (see Gotchas). This affects every API signature that used to take `ResourceLocation`, including Fabric API's HUD/keymapping APIs.

### HUD render API — inside `fabric-rendering-v1` (25.3.3), package `...rendering.v1.hud`

**No `HudRenderCallback` any more.** Replaced by a layered registry:
```java
public interface HudElementRegistry {
  static void addFirst(Identifier id, HudElement element);
  static void addLast(Identifier id, HudElement element);
  static void attachElementBefore(Identifier anchor, Identifier id, HudElement element);
  static void attachElementAfter(Identifier anchor, Identifier id, HudElement element);
  static void removeElement(Identifier id);
  static void replaceElement(Identifier id, Function<HudElement, HudElement> replacer);
}
public interface HudElement {
  void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker);
}
```
`VanillaHudElements` gives `Identifier` anchors for built-ins (`CROSSHAIR`, `HOTBAR`, `ARMOR_BAR`, `HEALTH_BAR`, `FOOD_BAR`, `AIR_BAR`, `EXPERIENCE_LEVEL`, `BOSS_BAR`, `CHAT`, `PLAYER_LIST`, `SCOREBOARD`, etc) for `attachElementBefore`/`After`. For an FPS/1%-low overlay: `HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rigtune","overlay"), element)`.

### World render events → **`LevelRenderEvents` + `LevelExtractionEvents`**

**`WorldRenderEvents` does not exist in this Fabric API build** (grepped every nested module, zero hits). Replaced by `net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents`:
```java
public final class LevelRenderEvents {
  public static final Event<StartMain> START_MAIN;
  public static final Event<AfterOpaqueTerrain> AFTER_OPAQUE_TERRAIN;
  public static final Event<CollectSubmits> COLLECT_SUBMITS;
  public static final Event<AfterSolidFeatures> AFTER_SOLID_FEATURES;
  public static final Event<AfterTranslucentFeatures> AFTER_TRANSLUCENT_FEATURES;
  public static final Event<BeforeBlockOutline> BEFORE_BLOCK_OUTLINE;     // returns boolean (cancellable)
  public static final Event<BeforeGizmos> BEFORE_GIZMOS;
  public static final Event<BeforeTranslucentTerrain> BEFORE_TRANSLUCENT_TERRAIN;
  public static final Event<AfterTranslucentTerrain> AFTER_TRANSLUCENT_TERRAIN;
  public static final Event<EndMain> END_MAIN;             // closest equivalent to old WorldRenderEvents.END
}
public final class LevelExtractionEvents {
  public static final Event<AfterBlockOutlineExtraction> AFTER_BLOCK_OUTLINE_EXTRACTION;
  public static final Event<EndExtraction> END_EXTRACTION;
}
```
Callback params are `LevelRenderContext`/`LevelTerrainRenderContext`, not the old `WorldRenderContext`. `START_MAIN`/`END_MAIN` fire once per rendered frame on the render thread — a clean non-mixin hook for per-frame benchmark sampling (see §4). This extraction-vs-render-thread event split mirrors vanilla's own extract/render split in 26.x (see §7).

### Client gametest API

**Confirmed absent** from this build (re-verified against the 42-entry `fabric.mod.json` jar list) — no `fabric-client-gametest-api-v1` nested jar, so `ClientGameTestContext`/`TestSingleplayerContext`/`TestWorldBuilder`/`TestClientWorldContext` are not available from this Fabric API jar. Don't plan RigTune's benchmark automation around it; use the manual command-dispatch approach in §9 instead, or add the module as an explicit separate Gradle dependency if truly needed.

---

## 9. Player and camera control for a singleplayer benchmark

### Rotation / teleport — inherited from `net.minecraft.world.entity.Entity`

`LocalPlayer` declares no rotation/teleport methods itself — use `Entity`'s:
```java
public void setYRot(float); public void setXRot(float);

// instant, non-interpolated, client-side position set — no smoothing:
public void snapTo(double x, double y, double z);
public void snapTo(double x, double y, double z, float yRot, float xRot);
public void snapTo(net.minecraft.world.phys.Vec3 pos);
public void snapTo(net.minecraft.world.phys.Vec3 pos, float yRot, float xRot);
public void snapTo(net.minecraft.core.BlockPos pos, float yRot, float xRot);
public void absSnapTo(double x, double y, double z);
public void absSnapTo(double x, double y, double z, float yRot, float xRot);

// "teleport" family (mostly server-authoritative / cross-dimension):
public void teleportTo(double x, double y, double z);                       // client-safe simple overload
public boolean teleportTo(ServerLevel, double, double, double, Set<Relative>, float yRot, float xRot, boolean); // server-side
public Entity teleport(TeleportTransition);              // server-side, dimension change etc.
public void teleportRelative(double, double, double);

public final void setPos(double, double, double); public final void setPos(net.minecraft.world.phys.Vec3);
public final double getX(); public final double getY(); public final double getZ();
```
**No plain `moveTo(double,double,double)` on `Entity` in 26.2.** For a client-side benchmark camera in singleplayer, use `snapTo(x, y, z, yRot, xRot)` — instant position+rotation, no server round-trip. `setYRot`/`setXRot` alone only change facing. For anything server-authoritative (survival/singleplayer world state that must agree with the server), issue a real `/tp` command instead (below).

### Flying — `net.minecraft.world.entity.player.Abilities`
```java
public class Abilities {
  public boolean invulnerable, flying, mayfly, instabuild, mayBuild;
  private float flyingSpeed, walkingSpeed;
}
```
Set `player.getAbilities().flying = true;` then call `player.onUpdateAbilities()` (public, on `LocalPlayer`) to push the change to the server — setting `flying` client-side alone is only a prediction; for it to stick, either the server must agree (creative/spectator already grants it) or a command changes gamemode server-side.

### Running commands against the integrated server

```java
// net.minecraft.client.Minecraft
public boolean hasSingleplayerServer();
public net.minecraft.client.server.IntegratedServer getSingleplayerServer();   // extends MinecraftServer

// net.minecraft.server.MinecraftServer
public Commands getCommands();
public CommandSourceStack createCommandSourceStack();

// net.minecraft.commands.Commands
public void performPrefixedCommand(CommandSourceStack source, String command);   // handles leading "/"
public void performCommand(com.mojang.brigadier.ParseResults<CommandSourceStack> parsed, String command);
```
```java
IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
if (server != null) {
    server.execute(() -> server.getCommands()
        .performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @s"));
}
```
The integrated server runs on its own thread even in singleplayer — must marshal onto it via `server.execute(Runnable)`. This is the recommended path for a benchmark harness: full server permissions, no packet round trip.

### Client-side command dispatch (alternative)

`LocalPlayer.connection` is `net.minecraft.client.multiplayer.ClientPacketListener`:
```java
public final ClientPacketListener connection;   // field on LocalPlayer
public void sendChat(String message);
public void sendCommand(String command);          // no leading "/"; signing handled internally
public void sendUnattendedCommand(String command, net.minecraft.client.gui.screens.Screen sourceScreen);
```
`Minecraft.getInstance().player.connection.sendCommand("gamemode spectator")` works exactly as the task brief guessed — no renaming despite the modern command-signing infrastructure (signing handled internally, see `lambda$sendCommand$0` building a `MessageSignature`). **For singleplayer automation prefer the direct server-side `performPrefixedCommand` above** — it skips network/command-signing round trips entirely and is more reliable inside the same JVM.

---

## 10. OSHI (hardware info)

**Version on the classpath: `oshi-core-6.9.0`** (`%APPDATA%/ModrinthApp/meta/libraries/com/github/oshi/oshi-core/6.9.0/oshi-core-6.9.0.jar`).

```java
oshi.SystemInfo si = new oshi.SystemInfo();
oshi.hardware.HardwareAbstractionLayer hal = si.getHardware();
oshi.software.os.OperatingSystem os = si.getOperatingSystem();
```

```java
public interface HardwareAbstractionLayer {
  CentralProcessor getProcessor();
  GlobalMemory getMemory();
  List<PowerSource> getPowerSources();
  List<GraphicsCard> getGraphicsCards();
  ComputerSystem getComputerSystem();
  List<Display> getDisplays();
}

// CPU — oshi.hardware.CentralProcessor
CentralProcessor.ProcessorIdentifier getProcessorIdentifier();   // .getVendor() .getName() .getFamily() .getModel() .isCpu64bit() .getVendorFreq()
long getMaxFreq();                    // Hz
long[] getCurrentFreq();              // Hz per logical core
int getLogicalProcessorCount();
int getPhysicalProcessorCount();
int getPhysicalPackageCount();
double getSystemCpuLoad(long ticksElapsedMs);   // 0.0-1.0

// Memory — oshi.hardware.GlobalMemory
long getTotal();       // bytes
long getAvailable();   // bytes
long getPageSize();
VirtualMemory getVirtualMemory();
List<PhysicalMemory> getPhysicalMemory();

// Battery — oshi.hardware.PowerSource
String getName(); double getRemainingCapacityPercent();
boolean isPowerOnLine(); boolean isCharging(); boolean isDischarging();
double getTimeRemainingEstimated();

// GPU — oshi.hardware.GraphicsCard
String getName(); String getDeviceId(); String getVendor();
String getVersionInfo();   // driver version string
long getVRam();            // bytes — the only reliable VRAM source, see §2
```

### How vanilla itself uses OSHI — proof it works in-game

`net.minecraft.SystemReport` is the reference implementation (used for crash reports / F3 dump):
```java
private void putHardware(oshi.SystemInfo);
private void putSoftware(oshi.SystemInfo);
private void putProcessor(oshi.hardware.CentralProcessor);
private void putMemory(oshi.hardware.GlobalMemory);
private void putPhysicalMemory(List<oshi.hardware.PhysicalMemory>);
private void putVirtualMemory(oshi.hardware.VirtualMemory);
private void putGraphics(List<oshi.hardware.GraphicsCard>);   // formats "Graphics card #%d" per card, reads name/vendor/driver/VRam
public void appendToCrashReportString(StringBuilder);
public String toLineSeparatedString();
private void ignoreErrors(String label, Runnable r);   // wraps every OSHI call — exceptions are swallowed and logged, not fatal
```
Call chain (bytecode-confirmed): `new SystemInfo() → getHardware() → getProcessor()/getMemory()/getGraphicsCards()`, each wrapped in `ignoreErrors(...)`; `SystemInfo.getOperatingSystem() → putSoftware(...) → os.getCurrentProcess()` (RSS, virtual size, uptime). **`PowerSource` (battery) is not used by vanilla at all** — only CPU, memory, graphics cards, and the current OS process are queried in-game; treat battery info as best-effort/unverified, the interface is present on the classpath and safe to call but untested by Mojang.

**RigTune usage:** every OSHI call in vanilla is defensively wrapped in `ignoreErrors(String, Runnable)` — on some systems WMI/registry-backed queries can throw or stall briefly on Windows. Copy that pattern (try/catch or a cached background-computed result); don't call OSHI directly on the render thread unguarded. `putGraphics` reading `card.getVRam()` off a fresh `new oshi.SystemInfo()` each time (not reflecting into `SystemReport`'s private fields) is exactly the pattern RigTune should copy for its own GPU/VRAM panel.

---

## Gotchas (surprising vs. 1.21.x expectations)

- **`GuiGraphics` does not exist in 26.2.** It's `net.minecraft.client.gui.GuiGraphicsExtractor`. `drawString`/`drawCenteredString` → `text(...)`/`centeredText(...)`. This is the single biggest source of compile breakage porting 1.21.x mixins/screens — a full rewrite of render methods, not a search-replace.
- **`Screen.render(GuiGraphics, int, int, float)` does not exist.** It's `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)` — screens/widgets *extract render state* into a `GuiRenderState`, actual GPU submission happens later. `LevelRenderer`/`LevelExtractor` (§5) is the same split applied to world rendering. Widgets/lists have matching `extract*` methods (`extractListItems`, `extractItem`, etc.) instead of `render*`, pervasively across the GUI package.
- **`Screen.keyPressed` takes a `net.minecraft.client.input.KeyEvent`**, not `(int, int, int)`; `Button.onPress`(the instance click handler) takes `InputWithModifiers`. Part of a broader input-event-object refactor in 26.x — expect other input hooks to have moved the same way.
- **The task brief's `"Found graphics adapter: AdapterInfo{...}"` log line and `AdapterInfo` class are not vanilla** — confirmed via exhaustive content grep, zero hits in `net.minecraft`/`com.mojang`. That's **Sodium's** `GraphicsAdapterProbe`/`GraphicsAdapterInfo` (driver-workaround detection), not a vanilla API. Vanilla's real GPU-info API is `RenderSystem.tryGetDevice().getDeviceInfo()` and the real log lines are `"Using graphics backend {}, using drivers: {}"` / `"Using graphics device: {} ({})"` (§2).
- **No total-VRAM getter anywhere in `com.mojang.blaze3d`.** `DeviceLimits.maxMemoryAllocationSize()` is a max-single-allocation cap, not total VRAM. Use OSHI `GraphicsCard.getVRam()` (§10) — same as vanilla's own `SystemReport`.
- **Changing `renderDistance` via `OptionInstance.set()` does not itself trigger a chunk rebuild** — bytecode-confirmed its listener only flips `graphicsPreset` to `CUSTOM`. Other graphics toggles (`biomeBlendRadius`, `cloudRange`, `cutoutLeaves`, `improvedTransparency`, `ambientOcclusion`) *do* call `LevelExtractor.allChanged()`; `textureFiltering`/`maxAnisotropyBit` call the lighter `resetSampler()` instead. Don't assume uniform behavior across all `OptionInstance` fields — see the per-option table in §1.4.
- **Editing any single graphics `OptionInstance` silently flips `Options.graphicsPreset()` to `CUSTOM`** — there is no way to hand-tune a value through the vanilla setter without losing the preset label.
- **`isRestartRequiredToApplyVideoSettings()`** checks both `preferredGraphicsBackend` *and* `exclusiveFullscreen` against their startup snapshots — either one pending triggers the restart banner.
- **`PrioritizeChunkUpdates` is saved as a raw ordinal int** (`prioritizeChunkUpdates:1`), not a quoted string — unlike almost every other enum-backed option, because it doesn't implement `StringRepresentable`.
- **`DebugScreenOverlay.frameTimeLogger` has no public getter** (unlike `tickTimeLogger`/`pingLogger`/`bandwidthLogger`, which do). Need an accessor mixin, or simpler, hook `logFrameDuration(long)` directly (§4). Sodium's `FrameTimeStatistics.INSTANCE.get()` is a ready-made percentile alternative since Sodium is a required dependency.
- **`WorldRenderEvents` (Fabric API) is gone** → `LevelRenderEvents` + `LevelExtractionEvents`, with `LevelRenderContext`/`LevelTerrainRenderContext` replacing `WorldRenderContext`.
- **`HudRenderCallback` (Fabric API) is gone** → `HudElementRegistry`/`HudElement`, keyed by `Identifier`, extract-based like everything else in 26.x's GUI split.
- **`KeyBindingHelper` → `KeyMappingHelper`**, and package is `client.keymapping.v1` not `client.keybinding.v1`. `KeyMapping`'s category constructor arg is now `KeyMapping.Category` (a record), not a `String`.
- **`net.minecraft.resources.ResourceLocation` has been renamed to `net.minecraft.resources.Identifier`** — no `ResourceLocation` class exists in the jar at all. Affects every Fabric API and vanilla signature that used to take `ResourceLocation`.
- **`fabric-client-gametest-api-v1` is not part of Fabric API 0.161.0+26.2.** Confirmed absent (not declared in `fabric.mod.json`, no nested jar). Don't build test/benchmark automation around it in this build; use manual command dispatch (§9) instead, or add it as an explicit separate Gradle dependency.
- **No plain `Entity.moveTo(double,double,double)` in 26.2** — use `snapTo(...)` for an instant, non-interpolated client-side camera move.
- **Sodium overrides vanilla's chunk-readiness bookkeeping.** With Sodium loaded, prefer `SodiumWorldRenderer.instanceNullable().isTerrainRenderComplete()` over vanilla `LevelRenderer.hasRenderedAllSections()` (Sodium mixes into both `LevelRenderer` and the new `LevelExtractor` via `@Overwrite`, so calling vanilla's own method names still transparently gets Sodium's answer).
- **Sodium 0.9.2 has no dedicated Vulkan option** — it auto-derives its `DrawBackend` (OPENGL/VK_MULTIDRAW/VK_INDIRECT) from vanilla's `Options.preferredGraphicsBackend()`, though it does ship active Vulkan-pipeline compatibility mixins.
- **Sodium's JSON key naming is mechanical Gson snake_case**, producing slightly odd keys like `use_no_error_g_l_context` for `useNoErrorGLContext` — derive new keys the same way rather than guessing.
- **Sodium's own video-settings screen re-displays vanilla `Options` fields** (render distance, gamma, GUI scale, etc.) rather than duplicating them into `sodium-options.json` — that file only holds genuinely Sodium-internal renderer settings.
- **`ResourceLocation`→`Identifier` and the `GuiGraphics`→`GuiGraphicsExtractor`/extract-render-state split are the two changes most likely to break naive ports of existing 1.21.x mod code.**
