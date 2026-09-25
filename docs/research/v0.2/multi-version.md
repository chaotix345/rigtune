# Multi-version build: 26.2 + 26.3 from one codebase

Research for v0.2.0, 2026-09-25. Every version and API claim below was checked live on that date (sources linked). Things I could not check are marked UNVERIFIED. The working prototype is at
`C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/097c9765-76fe-415d-a5ae-7debe28cb5de/scratchpad/r-multiversion/proto`
(a clone of `feat/v0.2.0` at 2cf4317, changes staged but not committed). The full patch is next to it, in `r-multiversion/stonecutter-proto.patch`. It applies to the current `feat/v0.2.0`, because commits since 2cf4317 only touched docs.

## 1. Recommendation

**Use Stonecutter 0.9.8. Keep the Groovy build scripts and the current Loom (1.17-SNAPSHOT, which resolves to 1.17.21) and Gradle (9.5.1).** There is one shared `src/`, and each Minecraft version gets a generated Gradle subproject under `versions/<mc>/` with its own small `gradle.properties`. The few API differences are handled inline with `//? if >=26.3 {` comment conditionals. A single `./gradlew build` compiles and unit-tests both versions and writes `versions/26.2/build/libs/rigtune-0.2.0+mc26.2.jar` and `versions/26.3/build/libs/rigtune-0.2.0+mc26.3.jar`.

Evidence:

- **The prototype works.** Both versions built. 26.2 ran 24/24 client tests, and 26.3 ran 238/238, the full suite. The core tests are set to run on one version only. With that filter off, each version ran all 238. A clean build takes about 45 s on this machine, and switching the active version and back leaves `git diff` empty.
- **The API delta is small.** It covers 3 client files: 4 conditional blocks plus a 1-line helper call. `main`, `test` and `gametest` compiled on 26.3 without changes.
- **Stonecutter handles all four source sets** (`main`, `client`, `test`, `gametest`) without extra config, and it works with the non-remapping `net.fabricmc.fabric-loom`.
- **The Stonecutter Fabric template targets exactly 26.2 and 26.3.** It was updated for 26.3 on 2026-09-23 and moved to Gradle 9.8.0 on 2026-09-24 ([stonecutter-template-fabric](https://github.com/stonecutter-versioning/stonecutter-template-fabric)).
- **One jar for both versions is impossible.** The two versions need different compiled bytecode, not just different source. `InputConstants.KEY_F8` is a compile-time constant that changed from 297 to 65 (see [api-diff.md](api-diff.md)), and `GpuDevice`/`DeviceInfo` moved packages. So each version has to be compiled separately. The only question is how the per-version source is expressed. Stonecutter puts the difference at the call site, with no interface layer that would grow every Minecraft update.

For the API decisions themselves (the refresh-rate source and the `isFullscreen()` replacement), follow api-diff.md. The prototype's code at those sites only proves the mechanism works (§4.3). api-diff.md also suggests a `GpuInfoReader` interface in per-version source sets. That isn't needed with Stonecutter, because the moved import is one conditional block.

## 2. Verified versions

Newest stable Minecraft: **26.3** (release, 2026-09-15). Nothing newer is stable. The only newer build is `26.4-snapshot-1` (2026-09-22, snapshot, out of scope). Sources: [Modrinth `GET /v2/tag/game_version`](https://api.modrinth.com/v2/tag/game_version) and [Fabric meta `/v2/versions/game`](https://meta.fabricmc.net/v2/versions/game), which lists 26.3 `stable:true` and 26.4-snapshot-1 `stable:false`. Fabric API already has `0.161.1+26.4`.

| Component | 26.2 | 26.3 | Built both in the prototype? | Source |
|---|---|---|---|---|
| Java | 25 | 25 | yes | — |
| Fabric Loom | `1.17-SNAPSHOT` → 1.17.21 | same plugin, one Loom for both | **yes** (1.17.21) and **yes** (1.18.2, alternative) | [plugin metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml), [Loom 1.18 release](https://github.com/FabricMC/fabric-loom/releases/tag/1.18) (2026-09-20) |
| Gradle | 9.5.1 (current wrapper) | same | **yes** (9.5.1 with Loom 1.17.21; 9.8.0 with Loom 1.18.2) | [services.gradle.org current = 9.8.0](https://services.gradle.org/versions/current) (2026-09-24) |
| Fabric Loader | 0.19.5 | 0.19.5 | yes | [meta /v2/versions/loader](https://meta.fabricmc.net/v2/versions/loader) (0.19.5 stable) |
| Fabric API | `0.161.0+26.2` | `0.161.0+26.3` | yes | [maven-metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml), [Modrinth](https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.3%22%5D) (0.161.0+26.3 published 2026-09-18) |
| Mod Menu | `20.0.2` (kept; `20.0.3` came out 2026-09-24) | `21.0.0` (2026-09-23, release) | yes (compileOnly) | [Modrinth](https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%2226.3%22%5D), [Terraformers maven](https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml) |
| Sodium | `mc26.2-0.9.2-fabric` | `mc26.3-0.9.2-fabric` (release, 2026-09-15). `mc26.3-0.9.3-alpha.1` exists but is alpha. | resolved as localRuntime | [Modrinth](https://api.modrinth.com/v2/project/sodium/version?game_versions=%5B%2226.3%22%5D), [Modrinth maven](https://api.modrinth.com/maven/maven/modrinth/sodium/maven-metadata.xml) |
| Stonecutter | 0.9.8 (2026-08-31); `0.10-alpha.10` is snapshot-only | — | yes | [maven.kikugie.dev releases](https://maven.kikugie.dev/releases/dev/kikugie/stonecutter/maven-metadata.xml), [Gradle Plugin Portal](https://plugins.gradle.org/m2/dev/kikugie/stonecutter/dev.kikugie.stonecutter.gradle.plugin/maven-metadata.xml) (0.9.8), [0.9 changelog](https://stonecutter.kikugie.dev/blog/changes/0.9) |

Notes:
- **Fabric's own guidance.** The [26.3 blog post](https://fabricmc.net/2026/09/15/263.html) says: "Developers should use Loom 1.17 and Gradle 9.6.0 (at the time of writing)… Fabric Loader (currently 0.19.5)". Loom 1.17.21 on Gradle 9.5.1 built 26.3 fine. Loom 1.17 needs Gradle ≥ 9.5.0 (`org.gradle.plugin.api-version` in its [module file](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.17.21/fabric-loom-1.17.21.module)).
- **Loom 1.18.x needs Gradle ≥ 9.7.0 and a Java 25 Gradle JVM** ([module file](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.18.2/fabric-loom-1.18.2.module)). It changes run-config defaults: `preferGradleTask`, plus FFM in place of the native library. The upgrade works (§4.4), but it's independent of this restructure. Don't bundle the two.
- **What 26.3's move to SDL means for the build:** nothing. It only changes client APIs: input constants, `Window` fullscreen/refresh, and the GPU device classes' package. See api-diff.md.
- **Stonecutter needs Gradle ≥ 9.0** ([setup docs](https://stonecutter.kikugie.dev/wiki/start/settings)). The primary source now lives on [Codeberg](https://codeberg.org/stonecutter/stonecutter) (last updated 2026-09-25). The old GitHub repo, `kikugie/stonecutter`, redirects to `stonecutter-versioning/stonecutter`, which hasn't been pushed since 2025-12-25. License is LGPL-3.0. It's build-time only: nothing ships in the jar except 4 `Stonecutter-*` manifest lines.

## 3. Options compared

| Option | Effort in this repo | IDE | CI | Risk |
|---|---|---|---|---|
| **A. Stonecutter 0.9.8** (recommended) | Done in the prototype: 12 files, +106/−34. Of that, 3 client files/4 conditional blocks; the rest is build and CI. | One "active" version is linked to `src/`. Switch with the Gradle task `Set active project to 26.2` (about 3 s); it rewrites the conditional comments in place. Non-active versions compile from `versions/<mc>/build/generated/stonecutter/`. An optional [IntelliJ plugin](https://github.com/stonecutter-versioning/stonecutter-intellij) highlights the syntax. | One `./gradlew build` builds and tests every version. A 2-line guard step checks sources are committed in the VCS state. | Third-party plugin, single maintainer (actively released: 0.9.8 in Aug, 0.10 alphas in Sep). If you forget `Reset active project` before committing, you get git noise; the CI guard catches it. Groovy prints a warning banner (silenced by a property). |
| B. Multi-project with overlay dirs (`versions/26.x` subprojects share `src/` and add `src-mc/26.x`) | Medium to high. Needs a shared script, srcDir wiring for 4 source sets, and per-version compat classes with the same fully qualified name. Moved types like `GpuDevice` can't appear in shared code, so they need wrapper records. | IntelliJ can't attach one source root to two modules, so the shared `src/` belongs to one version and the other version's errors only show at build time. (Known IntelliJ behavior, UNVERIFIED in this session: no IDE here.) | `./gradlew build` builds both | No third-party tool. But every trivial delta (an enum constant rename, an import move) becomes a new compat method, and that layer grows every Minecraft drop (26.1 → 26.2 → 26.3 each touched rendering/window code). |
| C. Single project with `-Pmc=26.3` (per-version properties file plus overlay dir) | Smallest build change (~20 lines), same compat-class cost as B | Simple: one version at a time; switch by editing `gradle.properties` and re-syncing | Needs a matrix job per version, and the release needs a fan-in job to collect artifacts. Locally, building all versions takes 2 invocations, and they share `build/`, so everything recompiles each time unless the build dir is split per version. | No third-party tool. You can never build or test all versions in one Gradle run. |
| D. [ReplayMod preprocessor](https://github.com/ReplayMod/preprocessor) (`//#if MC>=…`) | Similar to A | Similar to A | Similar to A | Its main strength is remapping between obfuscated mapping sets, which doesn't apply to 26.x. Last push 2026-07-07. No advantage over A. Not prototyped. |
| E. Manifold preprocessor | Needs a compiler plugin plus an IDE plugin | IDE plugin required | — | Heavier than A for no gain. Not pursued. |

## 4. Prototype

### 4.1 Layout

```
settings.gradle          + Stonecutter plugin; versions '26.2','26.3'; vcsVersion '26.3'
stonecutter.gradle       NEW root "controller" script: active version, Loom declared once (apply false), run-task ordering
build.gradle             now the per-version script; runs once per versions/<mc>/
gradle.properties        shared props only (loader, loom, mod version…)
versions/26.2/gradle.properties   NEW: minecraft_dependency, fabric_api, modmenu, sodium
versions/26.3/gradle.properties   NEW
src/{main,client,test,gametest}/  unchanged layout, shared by both versions
versions/<mc>/build/libs/         rigtune-0.2.0+mc<mc>.jar, -sources.jar
versions/<mc>/build/generated/stonecutter/{main,client,test,gametest}/  generated sources for non-active versions
versions/<mc>/run/                 Loom run dir per version (was ./run)
```

`git diff --stat` against 2cf4317:

```
 .github/workflows/build.yml                        | 15 ++++++++----
 .github/workflows/release.yml                      | 12 ++++++----
 build.gradle                                       | 28 +++++++++++++---------
 gradle.properties                                  | 13 +++++-----
 settings.gradle                                    | 14 +++++++++++
 .../chaotix345/rigtune/client/RigTuneClient.java   |  6 ++++-
 .../client/benchmark/BenchmarkController.java      |  3 ++-
 .../rigtune/client/probe/HardwareProbe.java        | 27 +++++++++++++++++----
 src/main/resources/fabric.mod.json                 |  2 +-
 stonecutter.gradle                                 | 12 ++++++++++
 versions/26.2/gradle.properties                    |  4 ++++
 versions/26.3/gradle.properties                    |  4 ++++
 12 files changed, 106 insertions(+), 34 deletions(-)
```

### 4.2 Build files

`settings.gradle` (added under the existing `pluginManagement`; the plugin resolves from the Gradle Plugin Portal, which is already listed):

```groovy
plugins {
	id 'dev.kikugie.stonecutter' version '0.9.8'
}

stonecutter {
	kotlinController = false
	centralScript = 'build.gradle'

	create(getRootProject()) {
		versions '26.2', '26.3'
		vcsVersion = '26.3'
	}
}

rootProject.name = 'rigtune'
```

`stonecutter.gradle` (new):

```groovy
plugins {
	id 'dev.kikugie.stonecutter'
	id 'net.fabricmc.fabric-loom' version "${loom_version}" apply false
}

stonecutter.active '26.3'

// Game clients must not start in parallel when a run task is invoked for every version at once.
stonecutter.tasks {
	order 'runClientGameTest'
	order 'runProductionSmoke'
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# Fabric Properties (shared by every Minecraft version; per-version ones are in versions/<mc>/gradle.properties)
loader_version=0.19.5
loom_version=1.17-SNAPSHOT
# The Minecraft version whose build runs the pure-Java core tests
core_tests_mc=26.3

# Mod Properties
mod_version=0.2.0
maven_group=io.github.chaotix345
archives_base_name=rigtune

# Acknowledges Stonecutter's warning about Groovy build scripts
dev.kikugie.stonecutter.hard_mode=true
```

`versions/26.2/gradle.properties` / `versions/26.3/gradle.properties`:

```properties
minecraft_dependency=~26.2              | minecraft_dependency=~26.3
fabric_api_version=0.161.0+26.2         | fabric_api_version=0.161.0+26.3
modmenu_version=20.0.2                  | modmenu_version=21.0.0
sodium_version=mc26.2-0.9.2-fabric      | sodium_version=mc26.3-0.9.2-fabric
```

`build.gradle` changes (everything else is unchanged, including `splitEnvironmentSourceSets`, `fabricApi.configureTests` and the smoke tasks):

```groovy
plugins {
	id 'net.fabricmc.fabric-loom'                      // version now declared once, in stonecutter.gradle
}

// This script runs once per Minecraft version (versions/<mc>/), with that version's gradle.properties.
def mcVersion = stonecutter.current.version
version = "${project.mod_version}+mc${mcVersion}"   // → rigtune-0.2.0+mc26.3.jar, fabric.mod.json version 0.2.0+mc26.3
...
	minecraft "com.mojang:minecraft:${mcVersion}"
...
test {
	...
	// The core tests don't touch Minecraft, so only one version runs them.
	if (mcVersion != project.core_tests_mc) {
		filter.excludeTestsMatching "io.github.chaotix345.rigtune.core.*"
	}
}

processResources {
	def props = ["version": project.version, "minecraft_dependency": project.minecraft_dependency]
	inputs.properties props
	filesMatching("fabric.mod.json") { expand props }
}
...
jar {
	def projectName = rootProject.name                 // project.name is now "26.3"
	inputs.property "projectName", projectName
	from(rootProject.file("LICENSE")) { rename { "${it}_$projectName" } }
}
...
// -PextraModsDir/-PuserOptions/-PuserConfigDir stay relative to the repo root:
def extraModsDir = providers.gradleProperty("extraModsDir").map { rootProject.layout.projectDirectory.dir(it).asFile }
def userOptions = providers.gradleProperty("userOptions").map { rootProject.layout.projectDirectory.file(it).asFile }
def userConfigDir = providers.gradleProperty("userConfigDir").map { rootProject.layout.projectDirectory.dir(it).asFile }
```

`fabric.mod.json`: `"minecraft": "~26.2"` becomes `"minecraft": "${minecraft_dependency}"`. Stonecutter doesn't process `.json` (only `.json5`), so this relies on the existing `processResources` expansion. Checked in the built jars: `"version": "0.2.0+mc26.2"`/`"minecraft": "~26.2"` and `"0.2.0+mc26.3"`/`"~26.3"`.

The mod version string `0.2.0+mc26.x` matches SPEC.md (§4: Modrinth `version_number` and the update-offer text). `Recommender.compareVersions` splits on non-digits, so `0.2.0+mc26.3` compares as `0.2.0.26.3`. That is still correct against a `minModVersion` like `0.2.0` or `0.2.1`.

### 4.3 Version-conditional code (the mechanism, and the implementer's head start)

Everything below is the committed state (active = VCS = 26.3). For the 26.2 build, Stonecutter swaps which branch is commented out.

`client/probe/HardwareProbe.java`:

```java
import com.mojang.blaze3d.systems.RenderSystem;
//? if >=26.3 {
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
//?} else {
/*import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
*///?}
...
	public static int refreshRate(Window window) {
		//? if >=26.3 {
		VideoMode mode = window.getActiveVideoMode();
		return mode != null ? Math.round(mode.getRefreshRate()) : -1;
		//?} else
		//return window.getRefreshRate();
	}
...
			int refresh = refreshRate(window);
			...
				refresh = refresh > 0 ? refresh : Math.round(mode.getRefreshRate());   // int on 26.2, float on 26.3: compiles on both
			...
			//? if >=26.3 {
			boolean fullscreen = minecraft.options.fullscreen().get();   // PLACEHOLDER: semantics UNVERIFIED, see api-diff.md §4
			//?} else
			//boolean fullscreen = window.isFullscreen();
			display = new DisplayInfo(width, height, refresh > 0 ? refresh : -1, fullscreen);
```

`client/benchmark/BenchmarkController.java`: `minecraft.getWindow().getRefreshRate()` becomes `HardwareProbe.refreshRate(minecraft.getWindow())`. That's version-agnostic and needs no conditional.

`client/RigTuneClient.java`:

```java
		//? if >=26.3 {
		InputConstants.Type keyboard = InputConstants.Type.KEYBOARD;
		//?} else
		//InputConstants.Type keyboard = InputConstants.Type.KEYSYM;
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rigtune.open", keyboard, InputConstants.KEY_F8, category));
```

Bytecode check: the 26.2 jar's `RigTuneClient` reads `InputConstants$Type.KEYSYM`, and the 26.3 jar reads `KEYBOARD`. `KEY_F8` is inlined per version automatically.

Syntax notes from the prototype:
- A multi-line block is `//? if >=26.3 {` … `//?} else {` … `//?}`. The inactive side is wrapped as `/* … *///?}`.
- A single-line `else` (no braces) must be the last branch. Its canonical inactive form is `//code`. If you hand-write `/*code*/`, the first switch rewrites it, and the CI guard would flag that. Write `//`, or run `Refresh active project` once.
- Version predicates are semver: `>=26.3` also matches a future 26.3.1.

Nothing else broke. I checked with javap on both Minecraft jars that everything which compiles but could still fail at runtime is present on both versions: the mixin target `DebugScreenOverlay.logFrameDuration(long)`, the reflected `Options.serverRenderDistance`/`processOptions(Options$FieldAccess)`, and the Sodium reflection targets `SodiumWorldRenderer.instanceNullable`/`isTerrainRenderComplete` and `Workarounds.isWorkaroundEnabled`. That doesn't replace running the 26.3 game test (§7). One left-over for the API work: `RealController.java:314` falls back to a hard-coded `"26.2"` when the hardware probe is null. It compiles fine but is wrong on 26.3. Take the version from FabricLoader at runtime instead of making it conditional.

### 4.4 Commands and results

All run in the prototype with `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"`, with a warm Gradle cache: both Minecraft versions were already in `~/.gradle/caches/fabric-loom`.

| Step | Command | Result |
|---|---|---|
| Baseline, unchanged repo (26.2 only) | `./gradlew build` | BUILD SUCCESSFUL in 55 s; 238 tests / 31 classes, 0 failures; `rigtune-0.1.0.jar` |
| Probe: unchanged sources against 26.3 deps (single project) | `./gradlew compileJava compileClientJava compileTestJava compileGametestJava --continue` | `compileJava` OK. `compileClientJava` failed with **9 errors, all in HardwareProbe, RigTuneClient and BenchmarkController** (41 s). This also proves Loom 1.17.21 on Gradle 9.5.1 sets up 26.3. |
| Stonecutter, every test on both versions | `./gradlew build --continue` | BUILD SUCCESSFUL in 36 s; 26.2: **238/238**, 26.3: **238/238** |
| Stonecutter with the core-tests-once filter, clean | `./gradlew clean` then `./gradlew build compileGametestJava` | BUILD SUCCESSFUL in 43–45 s, 28 tasks; 26.2: **24 tests / 5 classes** (client tests only, 0.3 s); 26.3: **238 / 31 classes**; 0 failures |
| Game-test sources compile per version (not run) | `./gradlew compileGametestJava gametestJar` | OK for both (about 4 s); `rigtune-0.2.0+mc<mc>-gametest.jar` |
| One version only | `./gradlew :26.3:build` | OK |
| IDE switch round trip | `./gradlew "Set active project to 26.2"` then `./gradlew "Reset active project"` | about 3 s each. The switch rewrites 2 source files plus `stonecutter.gradle`; the reset restores them, and `git diff --exit-code` is clean. A build with 26.2 active also passes. |
| CI guard catches a commit made in the wrong state | stage the 26.2 state, `Reset active project`, `git diff --exit-code` | exit 1 (3 files differ), so the check works |
| Alternative toolchain | Loom `1.18.2` plus wrapper `gradle-9.8.0-bin.zip`, `./gradlew clean build compileGametestJava` | BUILD SUCCESSFUL in 1 m 21 s (first run, including Gradle download and Loom cache regeneration); 24 / 238 tests, both jars. Reverted afterwards. |
| Release jar collection (the script from §6, run locally) | — | `versions=2 jars=4` (2 jars plus 2 sources jars) |

Where the time goes: 32.8 s of test time is core tests, mostly `HelperLauncherTest` (21 s, spawns JVMs) and `ApplyLockTest` (7 s). The client tests take 0.2 s. That's why the core tests run on only one version.

## 5. Step-by-step for the implementer (on `feat/v0.2.0`)

1. Apply the patch, `git apply <scratchpad>/r-multiversion/stonecutter-proto.patch`, or copy the files from the prototype. Then take the three Java sites from api-diff.md's final decisions, not from the prototype placeholder (especially fullscreen).
2. If you do it by hand, in this order:
   1. `settings.gradle`: add the `plugins { id 'dev.kikugie.stonecutter' version '0.9.8' }` block and the `stonecutter { … }` block (§4.2).
   2. Create `stonecutter.gradle` (§4.2).
   3. `gradle.properties`: remove `minecraft_version`, `fabric_api_version`, `modmenu_version` and `sodium_version`; add `core_tests_mc` and `dev.kikugie.stonecutter.hard_mode`; set `mod_version=0.2.0`.
   4. Create `versions/26.2/gradle.properties` and `versions/26.3/gradle.properties`. Only the properties file is committed per version. `versions/*/build` and `versions/*/run` are already ignored by the existing `build/` and `run/` patterns in `.gitignore`.
   5. Edit `build.gradle` as in §4.2: plugin without a version, `mcVersion`/`version`, the `minecraft` dependency, the `test` filter, `processResources`, the `jar` LICENSE from the root, and root-relative smoke properties.
   6. `fabric.mod.json`: `"minecraft": "${minecraft_dependency}"`.
   7. Add the client conditionals (§4.3).
   8. Run `./gradlew build compileGametestJava`. Expect 2 jars per version and 24 + 238 tests.
   9. Run `./gradlew "Reset active project"` and `git diff`. There should be no change beyond your own edits. Commit.
3. Update docs that name paths or tasks:
   - `README.md:80-81`: jars are now in `versions/<mc>/build/libs`; game tests are run as `:26.3:runClientGameTest`.
   - `docs/smoke/README.md`: the run dir is now `versions/<mc>/run/` and the task is `:26.3:runProductionSmoke`.
   - `build.gradle:103` comment: add the project path.
   - Add a short contributor note: "switch with `Set active project to <mc>`, run `Reset active project` before committing".
4. The dev run directory moves from `./run` to `versions/<mc>/run` (Loom's default runDir is project-relative). Anything kept in the old `./run` (options, test worlds) has to be copied if you still want it.
5. Adding **26.4 when it goes stable**:
   - Check the Fabric blog for the Loom and Gradle it needs. If that's Loom 1.18+, bump Gradle to ≥ 9.7 first and build 26.2/26.3 with that toolchain alone.
   - Add `'26.4'` to `versions` and set `vcsVersion = '26.4'`.
   - Run `Reset active project`, then `Set active project to 26.4` (this rewrites the `stonecutter.active` line).
   - Add `versions/26.4/gradle.properties` and set `core_tests_mc=26.4`.
   - Build, and fix each compile error with `//? if >=26.4 {` blocks.
   - Dropping 26.2 later: remove it from `versions`, delete `versions/26.2`, and delete the `else` branches that only 26.2 used (Stonecutter doesn't prune dead branches).
   - Snapshots stay out; Stonecutter can register a node with a separate logical version, `version '<project>', '<version>'`, if that's ever wanted.

## 6. CI and release changes

A matrix is not needed. One job builds every version, in about 45 s locally. Both workflow changes are in the prototype.

`build.yml`, java job:

```yaml
      # Stonecutter rewrites src/ when the active Minecraft version is switched; commits must be in the
      # state of the VCS version (settings.gradle), with canonical comments.
      - name: Check sources are in the VCS version state
        run: |
          ./gradlew "Reset active project"
          git diff --exit-code
      # Builds and unit-tests every Minecraft version (versions/<mc>/), and compiles each version's game tests.
      - name: Build and test
        run: ./gradlew build compileGametestJava
      - name: Upload jars
        ...
          path: versions/*/build/libs/*.jar
      - name: Upload test reports
        ...
          path: |
            versions/*/build/reports/tests/**
            versions/*/build/test-results/**
```

`compileGametestJava` is new. `build` never compiled the gametest source set, even in v0.1.0 (confirmed with `./gradlew build -m`). With per-version conditionals, CI should at least compile it for each version. The alternative is `check.dependsOn gametestClasses` in `build.gradle`.

`release.yml` "Create GitHub release":

```bash
set -eu
tag="${GITHUB_REF_NAME}"
versions=$(ls -d versions/*/ | wc -l)
mapfile -t jars < <(ls versions/*/build/libs/rigtune-*+mc*.jar | grep -v -- '-gametest.jar')
if [ "${#jars[@]}" -ne $((versions * 2)) ]; then
  echo "Expected a jar and a sources jar for each of the $versions Minecraft versions, found: ${jars[*]}"
  exit 1
fi
gh release create "$tag" --title "RigTune $tag" --generate-notes "${jars[@]}"
```

Result: `rigtune-0.2.0+mc26.2.jar`, `rigtune-0.2.0+mc26.2-sources.jar`, `rigtune-0.2.0+mc26.3.jar`, `rigtune-0.2.0+mc26.3-sources.jar`.

Modrinth (SPEC §4):
- Minotaur goes in the per-version `build.gradle` with `uploadFile = tasks.jar`, `versionNumber = project.version` (so `0.2.0+mc26.x`) and `gameVersions = [stonecutter.current.version]`.
- The commented snippet in the current `release.yml` says `uploadFile = remapJar`. There is no remapJar on 26.x, so fix that.
- For SPEC's "independent per version" requirement, run separate steps `./gradlew :26.2:modrinth` and `./gradlew :26.3:modrinth`.
- If a single aggregate call is wanted, add `order 'modrinth'` to `stonecutter.tasks`.
- The Minotaur wiring was not prototyped: UNVERIFIED.

## 7. Game tests per version (described only, not run)

- **Client game tests:** `./gradlew :26.2:runClientGameTest` and `./gradlew :26.3:runClientGameTest`. Each version has its own `gametest` source set (generated for the non-active version) and its own run dir.
- **Production smoke:** `./gradlew :26.3:runProductionSmoke -PextraModsDir=<dir> [-PuserOptions=…] [-PuserConfigDir=…]`. The paths are still relative to the repo root. `extraModsDir` must hold that version's fabric-api and mods.
- **Always pass the project path.** An unqualified `./gradlew runClientGameTest` matches both projects. With `org.gradle.parallel=true` it could start two clients. The prototype adds `stonecutter.tasks { order 'runClientGameTest'; order 'runProductionSmoke' }`, which Gradle accepted at configuration (`-m` lists 26.2 then 26.3). Stonecutter's docs say ordering "can also prevent concurrency-sensitive tasks from running in parallel", but whether it really serializes execution is UNVERIFIED because nothing was launched.
- **CI later (Linux + xvfb).** Fabric's [automatic-testing docs](https://docs.fabricmc.net/develop/automatic-testing) show a `ClientProductionRunTask` with `jvmArgs.add("-Dfabric.client.gametest")` and `useXVFB = true`. That defaults to true on Linux when `CI` is set and needs the `xvfb` package. The job runs `./gradlew runProductionClientGameTest` and uploads `build/run/clientGameTest/screenshots`. The docs also suggest `-Dfabric.client.gametest.disableNetworkSynchronizer=true` if the network synchronizer fails on Actions. For RigTune:
  - Our `runProductionSmoke` is already that task type. A CI variant would drop `prepareProductionSmoke`, add `productionRuntimeMods` for fabric-api, and run as a matrix `mc: [26.2, 26.3]` with `./gradlew :${{ matrix.mc }}:runProductionClientGameTest`. Upload from `versions/${{ matrix.mc }}/build/run/...`.
  - Whether SDL-based 26.3 renders under xvfb/Mesa on GitHub runners is UNVERIFIED.

## 8. Risks and unknowns

- **Stonecutter is a single-maintainer, third-party build plugin.** Mitigations: it's build-time only, the version is pinned, and the ecosystem uses it widely (official templates track each Minecraft release). The exit path: copy each `versions/<mc>/build/generated/stonecutter/**` into per-version source dirs (option B/C).
- **Source rewriting.** Switching the active version edits tracked files. The CI guard (§6) blocks commits in the wrong state or with non-canonical comments. Developers must run `Reset active project` before committing.
- **Groovy.** It works (proven), but Stonecutter recommends Kotlin DSL for IDE completion. The warning banner is silenced by `dev.kikugie.stonecutter.hard_mode=true`. Converting to Kotlin DSL is optional and was not tested.
- **IDE behavior** is described from Stonecutter's docs: `src/` is linked to the active node, and non-active nodes use generated sources. It was not checked in IntelliJ (UNVERIFIED). Loom run-config names per subproject are also UNVERIFIED.
- **The fullscreen replacement on 26.3 is a placeholder** (`options.fullscreen().get()`). Semantics are UNVERIFIED; api-diff.md §4 owns the decision. The refresh rate uses `Window.getActiveVideoMode()`, which is nullable and asserts the render thread, like 26.2's `getRefreshRate()`.
- **Runtime-only breakage** (mixin injection points, reflection, key codes, SDL input) can only be caught by running `:26.3:runClientGameTest` / the smoke test on 26.3, which this research did not do.
- **Manifest lines.** Stonecutter adds four `Stonecutter-*` lines to `META-INF/MANIFEST.MF`, including the Gradle version. They're harmless. The 0.9.4 changelog mentions a `GENERATE_MANIFEST` flag to turn them off; how to set it is UNVERIFIED.
- **Loom `1.17-SNAPSHOT` is a moving target.** Today it resolves to 1.17.21. Pinning `loom_version=1.17.21` would make release builds reproducible (not tested separately; 1.17.21 is what built).
- **Mod Menu 20.0.3 for 26.2** came out 2026-09-24. The prototype kept 20.0.2 so dependencies didn't change during the experiment. Bumping it is optional (compileOnly/localRuntime only).
