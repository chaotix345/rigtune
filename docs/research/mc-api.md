# RigTune API Reference: Minecraft Java 26.2 (Mojang-mapped, unobfuscated)

Research date: 2026-09-24. Source jars (read-only, never modified):

- **Vanilla client**: `%APPDATA%/ModrinthApp/meta/versions/26.2-0.19.5/26.2-0.19.5.jar` — confirmed unobfuscated/real Mojang names (`net/minecraft/client/Minecraft.class`, `net/minecraft/client/Options.class` etc. present verbatim; 10,952 classes total).
- **Libraries**: `%APPDATA%/ModrinthApp/meta/libraries/` (LWJGL 3.4.1, OSHI 6.9.0, etc.)
- **Sodium**: `sodium-fabric-0.9.2+mc26.2.jar`
- **Fabric API**: `fabric-api-0.161.0+26.2.jar` (jar-in-jar; 42 nested modules under `META-INF/jars/`, extracted individually)
- **User's real configs** (read-only reference): `Fabric 26.2/options.txt`, `Fabric 26.2/config/sodium-options.json`

All signatures below are `javap -p` output against these exact jars, not recalled from training data. Where behavior required bytecode reading (`javap -c`), that's noted explicitly.

---

## 1. `net.minecraft.client.Options`

### 1.1 `OptionInstance<T>` — the generic wrapper

```java
public final class net.minecraft.client.OptionInstance<T> {
  public T get();
  public void set(T);
  public OptionInstance$ValueSet<T> values();
  public Codec<T> codec();

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
}
public final class OptionInstance$Enum<T> extends Record implements CycleableValueSet<T> {
  public Enum(List<T> values, Codec<T> codec);
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

### 1.2 Video/performance `OptionInstance` accessors, cross-checked against the user's `options.txt`

Every field is `private final OptionInstance<T> name;` with a matching `public OptionInstance<T> name();` getter — call `.get()`/`.set(v)` on the returned instance, there's no setter on `Options` itself.

| Accessor | Type | options.txt key | Notes |
|---|---|---|---|
| `renderDistance()` | `OptionInstance<Integer>` | `renderDistance` | range `[2, 16 or 32]` (32 if enough RAM); `applyValueImmediately=false` |
| `simulationDistance()` | `OptionInstance<Integer>` | `simulationDistance` | `applyValueImmediately=false` |
| `entityDistanceScaling()` | `OptionInstance<Double>` | `entityDistanceScaling` | |
| `framerateLimit()` | `OptionInstance<Integer>` | `maxFps` | **key renamed**; `UNLIMITED_FRAMERATE_CUTOFF` constant = "Unlimited" |
| `preferredGraphicsBackend()` | `OptionInstance<PreferredGraphicsApi>` | `preferredGraphicsBackend` | OpenGL/Vulkan choice, see §1.3 |
| `graphicsPreset()` | `OptionInstance<GraphicsPreset>` | `graphicsPreset` | Fast/Fancy/Fabulous/Custom, see §1.3 |
| `inactivityFpsLimit()` | `OptionInstance<InactivityFpsLimit>` | `inactivityFpsLimit` | enum `MINIMIZED`/`AFK` |
| `cloudStatus()` | `OptionInstance<CloudStatus>` | `renderClouds` | **key renamed**; enum `OFF`/`FAST`/`FANCY` |
| `cloudRange()` | `OptionInstance<Integer>` | `cloudRange` | |
| `weatherRadius()` | `OptionInstance<Integer>` | `weatherRadius` | |
| `cutoutLeaves()` | `OptionInstance<Boolean>` | `cutoutLeaves` | |
| `vignette()` | `OptionInstance<Boolean>` | `vignette` | |
| `improvedTransparency()` | `OptionInstance<Boolean>` | `improvedTransparency` | |
| `ambientOcclusion()` | `OptionInstance<Boolean>` | `ao` | **key renamed** |
| `chunkSectionFadeInTime()` | `OptionInstance<Double>` | `chunkSectionFadeInTime` | seconds; `0.0` disables fade |
| `prioritizeChunkUpdates()` | `OptionInstance<PrioritizeChunkUpdates>` | `prioritizeChunkUpdates` | enum `NONE`/`PLAYER_AFFECTED`/`NEARBY` |
| `mipmapLevels()` | `OptionInstance<Integer>` | `mipmapLevels` | |
| `maxAnisotropyBit()` | `OptionInstance<Integer>` | `maxAnisotropyBit` | raw bit count; see `maxAnisotropyValue()` below |
| `textureFiltering()` | `OptionInstance<TextureFilteringMethod>` | `textureFiltering` | enum `NONE`/`RGSS`/`ANISOTROPIC` |
| `guiScale()` | `OptionInstance<Integer>` | `guiScale` | `0` = auto |
| `gamma()` | `OptionInstance<Double>` | (not in default options.txt) | brightness |
| `fov()` | `OptionInstance<Integer>` | `fov` | stored normalized `0.0–1.0`, not degrees |
| `particles()` | `OptionInstance<ParticleStatus>` (`net.minecraft.server.level.ParticleStatus`) | `particles` | |
| `entityShadows()` | `OptionInstance<Boolean>` | `entityShadows` | |
| `biomeBlendRadius()` | `OptionInstance<Integer>` | `biomeBlendRadius` | |
| `enableVsync()` | `OptionInstance<Boolean>` | `enableVsync` | |
| `fullscreen()` | `OptionInstance<Boolean>` | `fullscreen` | |
| `exclusiveFullscreen()` | `OptionInstance<Boolean>` | `exclusiveFullscreen` | also plain field `exclusiveFullscreenFromStartup` (captured at launch) |
| `menuBackgroundBlurriness()` | `OptionInstance<Integer>` | `menuBackgroundBlurriness` | also plain int getter `getMenuBackgroundBlurriness()` |

Other renamed keys seen in the real `options.txt`: `mouseSensitivity`↔`sensitivity`, `discrete_mouse_scroll`↔`discreteMouseScroll`, `hideLightningFlashes`↔`hideLightningFlash`, `panoramaScrollSpeed`↔`panoramaSpeed`. `syncChunkWrites` is a plain `boolean` field, not an `OptionInstance`. Most other keys match the field name 1:1.

The literal save-key strings live in `processOptions(Options$FieldAccess)`, called by both `load()` and `save()` — the single source of truth if you need airtight certainty for a field not in this table:
```java
interface Options$OptionAccess { <T> void process(String key, OptionInstance<T> option); }
interface Options$FieldAccess extends Options$OptionAccess {
  int process(String, int); boolean process(String, boolean); String process(String, String); float process(String, float);
  <T> T process(String, T, Function<String,T> parse, Function<T,String> serialize);
}
private void processOptions(Options$FieldAccess);
```

`maxAnisotropyValue(): int` — derives the real sample count from the raw `maxAnisotropyBit` `OptionInstance<Integer>`.
`isRestartRequiredToApplyVideoSettings(): boolean` — for showing a restart banner on a custom settings screen.

### 1.3 Graphics preset & graphics-API selection (26.x-new)

```java
public final class net.minecraft.client.GraphicsPreset extends Enum<GraphicsPreset> implements StringRepresentable {
  public static final GraphicsPreset FAST, FANCY, FABULOUS, CUSTOM;
  public String getSerializedName();   // "fast"/"fancy"/"fabulous"/"custom"
  public void apply(Minecraft);        // pushes preset values into ~15 OptionInstances
}
```
`GraphicsPreset.apply(Minecraft)` calls `.set(...)` on, in order: `biomeBlendRadius`, `renderDistance`, `prioritizeChunkUpdates`, `simulationDistance`, `ambientOcclusion`, `cloudStatus`, `particles`, `mipmapLevels`, `entityShadows`, `entityDistanceScaling`, `menuBackgroundBlurriness`, `cloudRange`, `cutoutLeaves`, `improvedTransparency`, `weatherRadius`, and continues (almost certainly also `textureFiltering`/`vignette`/`chunkSectionFadeInTime`/`maxAnisotropyBit`, not walked to completion) — via a private static helper that takes a live `OptionsSubScreen`, so applying a preset through this exact method is coupled to an open settings screen, not trivially callable headless. **`Options.applyGraphicsPreset(GraphicsPreset)` is the public entry point** — it sets a guard flag `isApplyingGraphicsPreset = true` then applies.

`Options.setGraphicsPresetToCustom()` — **private**, bytecode-confirmed to be invoked from the `valueChanged` listener of essentially every individual graphics-related `OptionInstance` (cutoutLeaves, improvedTransparency, vignette, ambientOcclusion, textureFiltering, weatherRadius, mipmapLevels, etc). **Hand-editing any one of these via `.set()` silently flips `graphicsPreset` to `CUSTOM`** — same as the vanilla screen showing "Custom" the moment you touch one slider after picking a preset. No way to avoid this short of not using the vanilla setter.

```java
public final class net.minecraft.client.PreferredGraphicsApi extends Enum<PreferredGraphicsApi> implements StringRepresentable {
  public static final PreferredGraphicsApi DEFAULT, OPENGL, VULKAN;
  public String getSerializedName();          // "default"/"opengl"/"vulkan"
  public GpuBackend[] getBackendsToTry();      // com.mojang.blaze3d.systems.GpuBackend[]
}
```
**26.2 has a first-class OpenGL-vs-Vulkan preference**: `Options.preferredGraphicsBackend()`, serialized as `preferredGraphicsBackend`. It requires a **restart** to take effect (restart-warning `Component`s present, `GRAPHICS_API_TOOLTIP_VULKAN`) — the plain field `preferredGraphicsBackendFromStartup` holds what's *actually active right now* vs. the pending choice in the `OptionInstance`. Both `com.mojang.blaze3d.opengl.GlBackend` and `com.mojang.blaze3d.vulkan.VulkanBackend` exist as concrete `GpuBackend` implementations in this jar — Vulkan is a real, working backend in 26.2, not a stub.

Other enums: `TextureFilteringMethod` (`NONE`/`RGSS`/`ANISOTROPIC`), `InactivityFpsLimit` (`MINIMIZED`/`AFK`), `PrioritizeChunkUpdates` (`NONE`/`PLAYER_AFFECTED`/`NEARBY`), `CloudStatus` (`OFF`/`FAST`/`FANCY`).

### 1.4 `set()` side effects — chunk/geometry invalidation (bytecode-verified)

```java
private static void Options.operateOnLevelExtractor(Consumer<net.minecraft.client.renderer.extract.LevelExtractor> action);
```
Fetches `Minecraft.getInstance().levelExtractor`; if non-null (a level is loaded) runs `action.accept(levelExtractor)`; if null (main menu) it's a safe no-op. Called from the `valueChanged` listeners of multiple graphics `OptionInstance`s, wired to two distinct method references via `invokedynamic`/`LambdaMetafactory`:
- **`LevelExtractor::allChanged`** — the "recompile everything" trigger, used by cutoutLeaves and other boolean/enum graphics toggles.
- **`LevelExtractor::resetSampler`** — lighter-weight, used specifically by `textureFiltering`'s listener.

**Non-obvious finding:** `renderDistance`'s own listener does **only** `setGraphicsPresetToCustom()` — it does **not** call `LevelExtractor.allChanged()` or any chunk-reload method. Calling `options.renderDistance().set(x)` does not itself force an immediate chunk-grid rebuild; that must be picked up elsewhere (polled each tick, same as pre-26.x). Don't assume an instant visual effect for a benchmark — you may need to poke the poller or wait a tick. `simulationDistance` very likely follows the same "Custom-flip only" pattern but wasn't individually bytecode-walked.

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
  public int countRenderedSections();
  public double lastViewDistance();
}
```

### 1.5 `save()` / `load()`

```java
public void save();
public void load();
public File getFile();
public String dumpOptionsForReport();   // crash-report / F3 dump
```
`save()` opens `new PrintWriter(new OutputStreamWriter(new FileOutputStream(optionsFile), UTF_8))`, writes `"version:" + dataVersion` first, then `processOptions(new Options$3(this, writer))` which `println`s `key:value` per option (quoted strings for enums, e.g. `"custom"`) — matches the real file format exactly. Afterward it separately writes `fullscreenVideoModeString`, resource pack lists, key bindings (`key_<name>:<binding>`), sound volumes (`soundCategory_<name>:<vol>`), model parts (`modelPart_<name>:<bool>`).
`optionsFile` is set once in the constructor: `new File(gameDir, "options.txt")`. Only public constructor: `Options(Minecraft, File gameDir)`.

---

## 2. GPU info: vendor, renderer, driver, backend, VRAM

**The `AdapterInfo` class and the `"Found graphics adapter: AdapterInfo{...}"` log line described in the task brief do not exist anywhere in this 26.2 build** (exhaustive case-insensitive content grep across all 10,952 extracted classes for `"adapter"` and `"AdapterInfo"` — zero hits). That's either a memory of an older/different snapshot or a log line this build doesn't emit. Don't build detection logic around it.

What 26.2 actually has and logs (bytecode-confirmed call sites in `Minecraft.class`, right after window/device creation):

```java
com.mojang.blaze3d.systems.RenderSystem
  public static GpuDevice getDevice();       // throws/asserts if not yet initialized
  public static GpuDevice tryGetDevice();     // null-safe
  public static String getBackendDescription();

com.mojang.blaze3d.systems.GpuDevice
  public DeviceInfo getDeviceInfo();

public final class com.mojang.blaze3d.systems.DeviceInfo extends Record {
  public String name();                 // GPU model name, e.g. "NVIDIA GeForce RTX 4080"
  public String vendorName();
  public String driverInfo();           // driver version string
  public String backendName();          // e.g. "OpenGL"/"Vulkan"
  public DeviceType type();             // OTHER/INTEGRATED/DISCRETE/VIRTUAL/CPU
  public float timestampPeriod();
  public DeviceLimits limits();
  public DeviceFeatures features();
  public Set<String> underlyingExtensions();
  public HintsAndWorkarounds hintsAndWorkarounds();
}
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
  public GpuDevice createDevice(long, ShaderSource, GpuDebugOptions, Runnable) throws BackendCreationException;
```
Concrete implementations present in the jar: `com.mojang.blaze3d.opengl.GlBackend`, `com.mojang.blaze3d.vulkan.VulkanBackend`.

**RigTune usage:** `RenderSystem.tryGetDevice().getDeviceInfo()` gives vendor/renderer/driver/backend name/device type in one call, null-safe before the renderer is up. Cross-reference `Options.preferredGraphicsBackend().get()` (pending choice, §1.3) against `deviceInfo.backendName()` (what's actually active) to detect a pending-restart mismatch.

**VRAM: not exposed here.** `DeviceLimits` only has `maxMemoryAllocationSize(): long` (max single allocation, not total VRAM) plus texture-size/anisotropy/alignment limits — no total-VRAM getter anywhere in `com.mojang.blaze3d`. For actual VRAM size, use OSHI's `GraphicsCard.getVRam()` (§10) — this is also exactly what Mojang's own `SystemReport` does (§10), so it's proven to work in-game.

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
}

public final class com.mojang.blaze3d.platform.Monitor extends Record {
  public static Monitor tryCreate(long glfwMonitorHandle);
  public String monitorName();
  public long monitor();                        // GLFW monitor handle
  public List<VideoMode> videoModes();
  public VideoMode currentMode();
  public int x(); public int y();
  public VideoMode getPreferredVidMode(Optional<VideoMode>);
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

**Use:** `Minecraft.getInstance().getWindow()` → `Window`. Refresh rate via `Window.getRefreshRate()` (queries the current monitor) or `Monitor.currentMode().getRefreshRate()`. `Window.handle()` is the raw GLFW `long` for any direct LWJGL GLFW calls RigTune needs (e.g. `GLFW.glfwGetWindowAttrib`). `Window.backend()` is the fastest way to check OpenGL-vs-Vulkan at runtime without going through `RenderSystem`.

---

## 4. FPS and frame timing

```java
public class net.minecraft.client.Minecraft {
  public int getFps();
  public long getFrameTimeNs();
  public DebugScreenOverlay getDebugOverlay();
}
```

Per-frame timing is logged into a ring-buffer sampler on the F3 debug overlay:

```java
public class net.minecraft.client.gui.components.DebugScreenOverlay {
  private final LocalSampleLogger frameTimeLogger;    // NO public getter — see Gotchas
  private final LocalSampleLogger tickTimeLogger;
  public LocalSampleLogger getTickTimeLogger();
  public LocalSampleLogger getPingLogger();
  public LocalSampleLogger getBandwidthLogger();
  public void logFrameDuration(long nanos);            // public — called once per frame from Minecraft.class
  public boolean showFpsCharts();
  public void toggleFpsCharts();
}
```

`Minecraft.class` (bytecode-confirmed call site): computes a frame's CPU duration in nanos, then calls `this.getDebugOverlay().logFrameDuration(nanos)` every frame regardless of whether the F3 charts are visible — so the sampler is always being fed live data, not just when the overlay is open.

```java
public class net.minecraft.util.debugchart.LocalSampleLogger implements SampleStorage {
  public static final int CAPACITY;
  public LocalSampleLogger(int lines);
  public void logSample(long value);            // AbstractSampleLogger
  public void logFullSample(long[] values);
  public void logPartialSample(long, int line);
  public int capacity();
  public int size();
  public long get(int index);                   // ring-buffer read, most-recent-relative
  public long get(int index, int line);
  public void reset();
}

public class net.minecraft.client.gui.components.debugchart.FpsDebugChart extends AbstractDebugChart {
  public FpsDebugChart(Font, SampleStorage);
  protected String toDisplayString(double);       // formats a sample as ms/fps text
}
```

**Gotcha / RigTune plan:** `frameTimeLogger` has **no public getter** on `DebugScreenOverlay` (unlike `tickTimeLogger`/`pingLogger`/`bandwidthLogger`, which do). Two options for computing average FPS + 1% lows:
1. **Accessor mixin** (`@Accessor("frameTimeLogger")` on `DebugScreenOverlay`) to reach the existing ring buffer and read its samples directly via `get(int)`.
2. **Simpler: mixin/inject at `DebugScreenOverlay.logFrameDuration(long)` (or wherever `Minecraft.class` calls it)** to capture each frame's nanosecond duration into RigTune's own rolling buffer — no accessor needed, and you get the exact same values the vanilla FPS chart uses. This is the recommended approach: `Minecraft.getFrameTimeNs()` alone only gives you the *current* frame, not history, so you need either this hook or your own per-frame sampling loop.

Fabric API alternative to a mixin: `LevelRenderEvents.END_MAIN` / `START_MAIN` (renamed from `WorldRenderEvents`, see §8) fire once per rendered frame on the render thread and are a clean non-mixin hook point for sampling `Minecraft.getFrameTimeNs()` or driving your own frame-time ring buffer.

---

## 5. Chunk/section render readiness

Vanilla:
```java
public class net.minecraft.client.renderer.LevelRenderer {
  public boolean hasRenderedAllSections();
  public boolean isSectionCompiledAndVisible(BlockPos);
  public SectionRenderDispatcher sectionRenderDispatcher();
  public ObjectArrayList<SectionRenderDispatcher$RenderSection> visibleSections();
  public ObjectArrayList<SectionRenderDispatcher$RenderSection> nearbyVisibleSections();
}

public class net.minecraft.client.renderer.extract.LevelExtractor implements ResourceManagerReloadListener {
  public int countRenderedSections();
  public double totalSections();
  public double lastViewDistance();
  public void allChanged();
  public String sectionStatistics();
  public String entityStatistics();
}
```
`hasRenderedAllSections()` is the direct yes/no answer ("has the renderer finished compiling everything currently in view"). `LevelExtractor.countRenderedSections()`/`totalSections()` give the numeric progress (new in 26.x — `LevelExtractor` is a sibling of `LevelRenderer` introduced by the extract/render-thread split, see §7's `extractRenderState` note). Get the instance via `Minecraft.getInstance().levelExtractor` (package-visible-ish field) or `Minecraft.getInstance().levelRenderer`.

**Sodium 0.9.2 overrides this.** With Sodium installed, `LevelRenderer`'s chunk data is actually driven by Sodium's own renderer, and Sodium mixes directly into both `LevelRenderer` (`net.caffeinemc.mods.sodium.mixin.core.render.world.LevelRendererMixin`, plus a second sky-specific `LevelRendererMixin`) and the new `LevelExtractor` (`net.caffeinemc.mods.sodium.mixin.core.render.world.LevelExtractorMixin`) — so vanilla's `hasRenderedAllSections()`/`countRenderedSections()` may not reflect reality with Sodium loaded. Use Sodium's own renderer instead:

```java
public class net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer {
  public static SodiumWorldRenderer instance();          // throws if not initialized
  public static SodiumWorldRenderer instanceNullable();  // null-safe
  public boolean isTerrainRenderComplete();               // exact equivalent of hasRenderedAllSections()
  public boolean isSectionReady(int chunkX, int chunkY, int chunkZ);
  public int getVisibleChunkCount();
  public String getChunksDebugString();
  public Collection<String> getDebugStrings(boolean);
}
```
Also reachable off a live `LevelRenderer` instance via Sodium's extension interface (avoids the static singleton if you already have the renderer):
```java
public interface net.caffeinemc.mods.sodium.client.world.LevelRendererExtension {
  SodiumWorldRenderer sodium$getWorldRenderer();
}
// ((LevelRendererExtension) minecraft.levelRenderer).sodium$getWorldRenderer()
```

**RigTune usage:** detect Sodium via `FabricLoader.getInstance().isModLoaded("sodium")`; if present, poll `SodiumWorldRenderer.instanceNullable().isTerrainRenderComplete()` for the benchmark's "world is fully loaded, start measuring" gate; otherwise fall back to `Minecraft.getInstance().levelRenderer.hasRenderedAllSections()`.

---

## 6. Sodium 0.9.2 config

### Loading/saving

```java
public class net.caffeinemc.mods.sodium.client.SodiumClientMod {
  public static SodiumOptions options();          // the live singleton — read/write fields directly
  private static SodiumOptions loadConfig();       // called once at init
  public static void restoreDefaultOptions();
  public static boolean allowDebuggingOptions();
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
Config file path is resolved internally (`private static Path getConfigPath()`, not exposed) — always goes through `SodiumClientMod.options()` + `SodiumOptions.writeToDisk(options())`, don't hardcode the path yourself.

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
Vendor-specific detector classes exist alongside: `...workarounds.amd.AmdWorkarounds`, `...workarounds.intel.IntelWorkarounds`, `...workarounds.nvidia.NvidiaWorkarounds`.

### Vulkan

**Sodium 0.9.2 has no dedicated Vulkan on/off option of its own** — no `vulkan`-named field anywhere in `SodiumOptions`, and nothing backend-related in the real `sodium-options.json`. It follows whatever `Options.preferredGraphicsBackend()` picks (§1.3). It does, however, ship Vulkan-specific **compatibility mixins** confirming it actively hooks the Vulkan backend rather than just tolerating it: `net.caffeinemc.mods.sodium.mixin.core.VulkanPipelineMixin`, `...VulkanRenderPassAccessor`.

### Public API — `net.caffeinemc.mods.sodium.api.*`

What other mods (RigTune included) can do without touching Sodium internals:
- **`api.config.*`** (`ConfigEntryPoint`, `ConfigBuilder`, `ModOptionsBuilder`, `OptionPageBuilder`/`OptionGroupBuilder`, `BooleanOptionBuilder`/`IntegerOptionBuilder`/`EnumOptionBuilder`, `StorageEventHandler`) — lets a mod register its **own config page inside Sodium's video settings screen** (`registerConfigLate(ConfigBuilder)` entry point).
- **`api.vertex.buffer.VertexBufferWriter`** / **`api.vertex.format.*`** / **`api.vertex.attributes.common.*`** — direct-memory vertex writing compatible with Sodium's chunk vertex formats, for mods that want to feed geometry into Sodium's fast path (`VertexBufferWriter.of(VertexConsumer)`, `push(MemoryStack, long, int, VertexFormat)`).
- **`api.memory.MemoryIntrinsics`** — low-level intrinsics for the above.
- **`api.blockentity.BlockEntityRenderHandler` / `BlockEntityRenderPredicate`** — hook block-entity render culling.
- **`api.util.ColorARGB`/`ColorABGR`/`ColorMixer`/`ColorU8`/`NormI8`** — packing helpers matching Sodium's internal formats.
- **`api.texture.SpriteUtil`** — sprite/texture helpers.

None of this is needed just to *read* Sodium's settings/render state (§5/§6 above cover that) — it's for mods that want to render through Sodium's pipeline.

---

## 7. GUI in 26.2

### `Screen`

```java
public final void init(int width, int height);       // final — do not override
protected void init();                                 // override this to add widgets

// RENAMED render pipeline — no render(GuiGraphics,...) any more:
public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick);
public final void extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float);

public void onClose();
public boolean shouldCloseOnEsc();

protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);
protected <T extends Renderable> T addRenderableOnly(T widget);

public boolean keyPressed(net.minecraft.client.input.KeyEvent event);   // event object, not (int,int,int)
public Font getFont();
```
Override `init()` (not the `final init(int,int)`) to add widgets, and `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)` (not `render`) to draw. `addRenderableWidget` still registers a widget for both rendering and input in one call.

### `GuiGraphics` → **`net.minecraft.client.gui.GuiGraphicsExtractor`**

**There is no class literally named `GuiGraphics` in 26.2.** Screens/widgets *extract render state* into a `GuiRenderState` object instead of drawing immediately (actual GPU submission happens later — this is the same extract/render split as `LevelExtractor`/`LevelRenderer` in §5).

```java
public void text(Font, String, int x, int y, int color);
public void text(Font, String, int x, int y, int color, boolean dropShadow);
public void text(Font, Component, int x, int y, int color);
public void centeredText(Font, Component, int x, int y, int color);
public void textWithWordWrap(Font, FormattedText, int x, int y, int wrapWidth, int color);

public void fill(int x1, int y1, int x2, int y2, int color);
public void fillGradient(int x1, int y1, int x2, int y2, int colorFrom, int colorTo);
public void outline(int x1, int y1, int x2, int y2, int color);

public void blit(RenderPipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH);
public void item(ItemStack, int x, int y);
public void setTooltipForNextFrame(Font, Component, int x, int y);

public org.joml.Matrix3x2fStack pose();          // 2D-only transform stack, not the old 3D PoseStack
public void enableScissor(int,int,int,int); public void disableScissor();
public ActiveTextCollector textRenderer();        // not a plain Font field
```
No `drawString`/`drawCenteredString` — it's `text(...)`/`centeredText(...)`. `Font` is still `net.minecraft.client.gui.Font` (unchanged) and passed explicitly (get it from `Screen.getFont()`).

### `Button`

```java
public static Button.Builder Button.builder(Component message, Button.OnPress onPress);
public class Button.Builder {
  Builder pos(int x, int y); Builder size(int w, int h); Builder bounds(int x, int y, int w, int h);
  Builder tooltip(Tooltip); Builder createNarration(Button.CreateNarration);
  Button build();
}
```

### Scrollable lists

```java
AbstractSelectionList<E extends AbstractSelectionList.Entry<E>> extends AbstractContainerWidget
ObjectSelectionList<E extends ObjectSelectionList.Entry<E>> extends AbstractSelectionList<E>
ContainerObjectSelectionList<E extends ContainerObjectSelectionList.Entry<E>> extends AbstractSelectionList<E>

public AbstractSelectionList(Minecraft, int width, int height, int y0, int itemHeight);
protected int addEntry(E entry);
public void replaceEntries(Collection<E>);
public void setSelected(E); public E getSelected();
protected void extractListItems(GuiGraphicsExtractor, int, int, float);   // renamed from renderList
protected void extractItem(GuiGraphicsExtractor, int, int, float, E);     // renamed from renderItem
```
`ObjectSelectionList` = simple single-column list. `ContainerObjectSelectionList` = entries with their own focusable child widgets.

### Checkbox / CycleButton / text widgets

```java
public static Checkbox.Builder Checkbox.builder(Component message, Font font);
Checkbox.Builder.selected(OptionInstance<Boolean> option);   // binds directly to an Options OptionInstance!

public static <T> CycleButton.Builder<T> CycleButton.builder(Function<T,Component>, T defaultValue);
public static CycleButton.Builder<Boolean> CycleButton.onOffBuilder(boolean initial);
CycleButton<T> Builder.create(Component name, CycleButton.OnValueChange<T> onChange);

public StringWidget(Component, Font);              // + x/y/w/h overloads, setMaxWidth(int)
public MultiLineTextWidget(Component, Font);        // + setMaxWidth/setMaxRows/setCentered
```

### `Component`

```java
public static MutableComponent Component.literal(String text);
public static MutableComponent Component.translatable(String key, Object... args);
```
Unchanged from 1.21.x.

### `SystemToast`

```java
package net.minecraft.client.gui.components.toasts;
public static void SystemToast.add(ToastManager, SystemToast.SystemToastId, Component title, Component message);
```
Get the `ToastManager` off `Gui`, **not** directly off `Minecraft`: `Minecraft.getInstance().gui.toastManager()`.

### `TitleScreen` / `OptionsScreen` / `VideoSettingsScreen` — unchanged package/class names

```java
net.minecraft.client.gui.screens.TitleScreen(boolean fading, LogoRenderer);
net.minecraft.client.gui.screens.options.OptionsScreen(Screen lastScreen, Options, boolean gamemasterWarning);
net.minecraft.client.gui.screens.options.VideoSettingsScreen extends OptionsSubScreen
  (Screen lastScreen, Minecraft, Options);
```
Only the internal render plumbing changed (they implement `extractRenderState` like every other `Screen`).

---

## 8. Fabric API modules (`fabric-api-0.161.0+26.2`, 42 nested jars)

All 42 declared modules extracted and cross-checked 1:1 against `fabric.mod.json`'s `jars` array. **Two things asked for in the brief are not part of this build**: `fabric-client-gametest-api-v1` (absent entirely — not declared, no nested jar) and a standalone HUD-API module (HUD API lives inside `fabric-rendering-v1` instead, see below).

### `ScreenEvents` / `Screens` — `fabric-screen-api-v1` (5.2.1)

```java
public final class net.fabricmc.fabric.api.client.screen.v1.ScreenEvents {
  public static final Event<ScreenEvents.BeforeInit> BEFORE_INIT;
  public static final Event<ScreenEvents.AfterInit> AFTER_INIT;
  public static Event<ScreenEvents.AfterExtract> afterExtract(Screen);   // per-frame, extract-phase
}
public interface ScreenEvents.AfterInit { void afterInit(Minecraft, Screen, int, int); }

public final class Screens {
  public static List<AbstractWidget> getWidgets(Screen);   // renamed from getButtons(Screen)
  public static Font getFont(Screen);
}
```
Register a button on `TitleScreen`/`OptionsScreen` via `ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> { if (screen instanceof TitleScreen) Screens.getWidgets(screen).add(...); })`.

### `ClientTickEvents` / `ClientLifecycleEvents` — `fabric-lifecycle-events-v1` (4.1.4)

```java
public final class ClientTickEvents {
  public static final Event<StartTick> START_CLIENT_TICK;
  public static final Event<EndTick> END_CLIENT_TICK;    // once per game tick — good for scripted camera moves
}
public final class ClientLifecycleEvents {
  public static final Event<ClientStarted> CLIENT_STARTED;
  public static final Event<ClientStopping> CLIENT_STOPPING;
}
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
public KeyMapping.Category getCategory();

public final class KeyMapping.Category extends Record {
  public static final Category MOVEMENT, MISC, MULTIPLAYER, GAMEPLAY, INVENTORY, CREATIVE, SPECTATOR, DEBUG;
  public static Category register(Identifier id);   // custom categories
}
```
**1.21.9+ category rewrite carries into 26.2**: the category arg is `KeyMapping.Category` (a record wrapping an `Identifier`), not a translation-key `String`. Custom category: `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rigtune", "benchmark"))`.

### HUD render API — inside `fabric-rendering-v1` (25.3.3), package `...rendering.v1.hud`

**No `HudRenderCallback` any more.** Replaced by a layered registry:
```java
public interface HudElementRegistry {
  static void addFirst(Identifier id, HudElement element);
  static void addLast(Identifier id, HudElement element);
  static void attachElementBefore(Identifier anchor, Identifier id, HudElement element);
  static void removeElement(Identifier id);
}
public interface HudElement {
  void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker);
}
```
`VanillaHudElements` gives `Identifier` anchors for built-ins (`CROSSHAIR`, `HOTBAR`, `HEALTH_BAR`, `BOSS_BAR`, `CHAT`, etc) for `attachElementBefore`/`After`. For an FPS/1%-low overlay: `HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rigtune","overlay"), element)`.

### World render events → **`LevelRenderEvents` + `LevelExtractionEvents`**

**`WorldRenderEvents` does not exist in this Fabric API build** (grepped every nested module, zero hits). Replaced by `net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents`:
```java
public final class LevelRenderEvents {
  public static final Event<StartMain> START_MAIN;
  public static final Event<EndMain> END_MAIN;             // closest equivalent to old WorldRenderEvents.END
  public static final Event<AfterOpaqueTerrain> AFTER_OPAQUE_TERRAIN;
  public static final Event<LevelExtractionEvents.EndExtraction> END_EXTRACTION;
  // + AFTER_SOLID_FEATURES, AFTER_TRANSLUCENT_FEATURES, BEFORE_BLOCK_OUTLINE, BEFORE_GIZMOS, ...
}
```
Callback params are `LevelRenderContext`/`LevelTerrainRenderContext`, not the old `WorldRenderContext`. `START_MAIN`/`END_MAIN` fire once per world-render frame on the render thread — a clean non-mixin hook for per-frame benchmark sampling (see §4).

### Client gametest API

**Confirmed absent** from this build (re-verified against the 42-entry `fabric.mod.json` jar list) — no `fabric-client-gametest-api-v1` nested jar, so `ClientGameTestContext`/`TestSingleplayerContext`/`TestWorldBuilder`/`TestClientWorldContext` are not available from this Fabric API jar. Don't plan RigTune's benchmark automation around it; use the manual command-dispatch approach in §9 instead.

---

## 9. Player and camera control for a singleplayer benchmark

### Rotation / teleport — inherited from `net.minecraft.world.entity.Entity`

`LocalPlayer` declares no rotation/teleport methods itself — use `Entity`'s:
```java
public void setYRot(float); public void setXRot(float);

// instant, non-interpolated, client-side position set — no smoothing:
public void snapTo(double x, double y, double z);
public void snapTo(double x, double y, double z, float yRot, float xRot);
public void snapTo(net.minecraft.world.phys.Vec3 pos, float yRot, float xRot);

public void teleportTo(double x, double y, double z);                       // client-safe simple overload
public boolean teleportTo(ServerLevel, double, double, double, Set<Relative>, float yRot, float xRot, boolean); // server-side
```
**No plain `moveTo(double,double,double)` on `Entity` in 26.2.** For a client-side benchmark camera in singleplayer, use `snapTo(x, y, z, yRot, xRot)` — instant position+rotation, no server round-trip. `setYRot`/`setXRot` alone only change facing.

### Flying — `net.minecraft.world.entity.player.Abilities`
```java
public class Abilities {
  public boolean invulnerable, flying, mayfly, instabuild, mayBuild;
}
```
Set `player.getAbilities().flying = true;` then call `player.onUpdateAbilities()` (public, on `LocalPlayer`) to push the change to the server.

### Running commands against the integrated server

```java
// net.minecraft.client.Minecraft
public boolean hasSingleplayerServer();
public IntegratedServer getSingleplayerServer();

// net.minecraft.server.MinecraftServer
public Commands getCommands();
public CommandSourceStack createCommandSourceStack();

// net.minecraft.commands.Commands
public void performPrefixedCommand(CommandSourceStack source, String command);   // handles leading "/"
```
```java
IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
if (server != null) {
    server.execute(() -> server.getCommands()
        .performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @s"));
}
```
The integrated server runs on its own thread even in singleplayer — must marshal onto it via `server.execute(Runnable)`.

### Client-side command dispatch (alternative)

`LocalPlayer.connection` is `net.minecraft.client.multiplayer.ClientPacketListener`:
```java
public final ClientPacketListener connection;   // field on LocalPlayer
public void sendCommand(String command);          // no leading "/"; signing handled internally
```
`Minecraft.getInstance().player.connection.sendCommand("gamemode spectator")` works exactly as the task brief guessed. **For singleplayer automation prefer the direct server-side `performPrefixedCommand` above** — it skips network/command-signing round trips entirely and is more reliable inside the same JVM.

---

## 10. OSHI (hardware info)

**Version on the classpath: `oshi-core-6.9.0`** (`%APPDATA%/ModrinthApp/meta/libraries/com/github/oshi/oshi-core/6.9.0/oshi-core-6.9.0.jar`).

```java
oshi.SystemInfo si = new oshi.SystemInfo();
oshi.hardware.HardwareAbstractionLayer hal = si.getHardware();
```

```java
// CPU — oshi.hardware.CentralProcessor (hal.getProcessor())
CentralProcessor.ProcessorIdentifier getProcessorIdentifier();   // .getVendor() .getName() .getFamily() .getModel()
long getMaxFreq();                    // Hz
int getLogicalProcessorCount();
int getPhysicalProcessorCount();

// Memory — oshi.hardware.GlobalMemory (hal.getMemory())
long getTotal();       // bytes
long getAvailable();   // bytes

// Battery — oshi.hardware.PowerSource (hal.getPowerSources())
double getRemainingCapacityPercent();
boolean isCharging();

// GPU — oshi.hardware.GraphicsCard (hal.getGraphicsCards())
String getName(); String getVendor(); String getVersionInfo();   // driver version string
long getVRam();    // bytes — the only reliable VRAM source, see §2
```

### How vanilla itself uses OSHI — proof it works in-game

`net.minecraft.SystemReport` is the reference implementation (used for crash reports / F3 dump):
```java
private void putHardware(oshi.SystemInfo);
private void putProcessor(oshi.hardware.CentralProcessor);
private void putMemory(oshi.hardware.GlobalMemory);
private void putGraphics(List<oshi.hardware.GraphicsCard>);
private void ignoreErrors(String label, Runnable r);   // wraps every OSHI call
```
Call chain (bytecode-confirmed): `new SystemInfo() → getHardware() → getProcessor()/getMemory()/getGraphicsCards()`, each wrapped in `ignoreErrors(...)`. **`PowerSource` (battery) is not used by vanilla at all** — only CPU, memory, and graphics cards are queried in-game.

**RigTune usage:** every OSHI call in vanilla is defensively wrapped — on some systems WMI/registry-backed queries can throw or stall briefly on Windows. Copy that pattern (try/catch or a cached background-computed result); don't call OSHI directly on the render thread unguarded. `PowerSource` is best-effort only and will be an empty list on most benchmarking desktops.

---

## Gotchas (surprising vs. 1.21.x expectations)

- **`GuiGraphics` does not exist in 26.2.** It's `net.minecraft.client.gui.GuiGraphicsExtractor`. `drawString`/`drawCenteredString` → `text(...)`/`centeredText(...)`. This is the single biggest source of compile breakage porting 1.21.x mixins/screens.
- **`Screen.render(GuiGraphics, int, int, float)` does not exist.** It's `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)` — screens/widgets *extract render state* into a `GuiRenderState`, actual GPU submission happens later. `LevelRenderer`/`LevelExtractor` (§5) is the same split applied to world rendering. Any mixin targeting `render` needs to retarget `extractRenderState` (or `extractContents`/`extractWidgetRenderState` depending on the class).
- **`Screen.keyPressed` takes a `net.minecraft.client.input.KeyEvent`**, not `(int, int, int)`. Part of a broader input-event-object refactor in 26.x.
- **The task brief's `"Found graphics adapter: AdapterInfo{...}"` log line and `AdapterInfo` class do not exist in this build** — confirmed via exhaustive content grep. Use `RenderSystem.tryGetDevice().getDeviceInfo()` and the real log lines `"Using graphics backend {}, using drivers: {}"` / `"Using graphics device: {} ({})"` instead (§2).
- **No total-VRAM getter anywhere in `com.mojang.blaze3d`.** `DeviceLimits.maxMemoryAllocationSize()` is a max-single-allocation cap, not total VRAM. Use OSHI `GraphicsCard.getVRam()` (§10) — same as vanilla's own `SystemReport`.
- **Changing `renderDistance`/`simulationDistance` via `OptionInstance.set()` does not itself trigger a chunk rebuild** — bytecode-confirmed their listeners only flip `graphicsPreset` to `CUSTOM`. Other graphics toggles (cutoutLeaves, textureFiltering, etc.) *do* call `LevelExtractor.allChanged()`/`resetSampler()`. Don't assume uniform behavior across all `OptionInstance` fields.
- **Editing any single graphics `OptionInstance` silently flips `Options.graphicsPreset()` to `CUSTOM`** — there is no way to hand-tune a value through the vanilla setter without losing the preset label.
- **`DebugScreenOverlay.frameTimeLogger` has no public getter** (unlike `tickTimeLogger`/`pingLogger`/`bandwidthLogger`, which do). Need an accessor mixin, or simpler, hook `logFrameDuration(long)` directly (§4).
- **`WorldRenderEvents` (Fabric API) is gone** → `LevelRenderEvents` + `LevelExtractionEvents`, with `LevelRenderContext`/`LevelTerrainRenderContext` replacing `WorldRenderContext`.
- **`HudRenderCallback` (Fabric API) is gone** → `HudElementRegistry`/`HudElement`, keyed by `Identifier`, extract-based like everything else in 26.x's GUI split.
- **`KeyBindingHelper` → `KeyMappingHelper`**, and package is `client.keymapping.v1` not `client.keybinding.v1`. `KeyMapping`'s category constructor arg is now `KeyMapping.Category` (a record), not a `String`.
- **`net.minecraft.resources.ResourceLocation` has been renamed to `net.minecraft.resources.Identifier`** — no `ResourceLocation` class exists in the jar at all. Affects every Fabric API and vanilla signature that used to take `ResourceLocation`.
- **`fabric-client-gametest-api-v1` is not part of Fabric API 0.161.0+26.2.** Confirmed absent (not declared in `fabric.mod.json`, no nested jar). Don't build test/benchmark automation around it in this build; use manual command dispatch (§9) instead.
- **No plain `Entity.moveTo(double,double,double)` in 26.2** — use `snapTo(...)` for an instant, non-interpolated client-side camera move.
- **Sodium overrides vanilla's chunk-readiness bookkeeping.** With Sodium loaded, prefer `SodiumWorldRenderer.instanceNullable().isTerrainRenderComplete()` over vanilla `LevelRenderer.hasRenderedAllSections()` (Sodium mixes into both `LevelRenderer` and the new `LevelExtractor`).
- **Sodium 0.9.2 has no dedicated Vulkan option** — it follows vanilla's `Options.preferredGraphicsBackend()`, though it does ship active Vulkan-pipeline compatibility mixins.
- **Sodium's JSON key naming is mechanical Gson snake_case**, producing slightly odd keys like `use_no_error_g_l_context` for `useNoErrorGLContext` — derive new keys the same way rather than guessing.
- **`ResourceLocation`→`Identifier` and the `GuiGraphics`→`GuiGraphicsExtractor`/extract-render-state split are the two changes most likely to break naive ports of existing 1.21.x mod code.**
